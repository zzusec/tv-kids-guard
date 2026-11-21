package com.family.tvguard;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Authenticated browser requests execute on the same main thread as the television controls. */
final class ParentWebController {
    private static final long ADMIN_REMOVAL_WINDOW_MILLIS = 2500L;
    private static volatile ParentWebController adminRemovalOwner;
    private final Context context;
    private final Prefs prefs;
    private final ParentWebCredentials credentials;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ParentWebSession sessions = new ParentWebSession(SystemClock::elapsedRealtime);
    private final Map<String, String> assets = new LinkedHashMap<>();
    private boolean closed;
    private boolean removing;
    private Authorization adminRemovalAuthorization;
    private long adminRemovalDeadline;
    private String actionError = "";

    /** A request-scoped capability; remote login never grants a television AdminSession. */
    final class Authorization {
        private final String cookie;
        private Authorization(String cookie) { this.cookie = cookie; }
        void require() {
            if (closed || sessions.find(cookie, credentialVersion()) == null) {
                throw new IllegalStateException("网页登录已过期，请重新登录");
            }
        }
    }

    ParentWebController(Context context) throws IOException {
        this.context = context.getApplicationContext();
        prefs = new Prefs(context);
        credentials = new ParentWebCredentials(context);
        for (String name : new String[]{"index.html", "app.js", "style.css"}) {
            try (InputStream input = context.getAssets().open("parent/" + name)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                assets.put(name, new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        }
    }

    ParentHttpServer.Response handle(ParentHttpServer.Request request) throws Exception {
        if ("GET".equals(request.method)) {
            String name = "/".equals(request.path) ? "index.html" : request.path.substring(1);
            if (assets.containsKey(name)) {
                String type = name.endsWith(".js") ? "application/javascript"
                        : name.endsWith(".css") ? "text/css" : "text/html";
                return ParentHttpServer.Response.text(200, type + "; charset=utf-8", assets.get(name));
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return route(request);
        FutureTask<ParentHttpServer.Response> task = new FutureTask<>(() -> route(request));
        main.post(task);
        try {
            return task.get(8, TimeUnit.SECONDS);
        } finally {
            // A request that timed out in the queue must not perform a delayed command later.
            task.cancel(false);
        }
    }

    private String credentialVersion() {
        return credentials.version();
    }

    private ParentHttpServer.Response route(ParentHttpServer.Request request) throws JSONException {
        try {
            if (closed) return error(503, "网页后台正在关闭");
            String cookie = request.header("cookie");
            ParentWebSession.Session session = sessions.find(cookie, credentialVersion());
            if ("GET".equals(request.method) && "/api/session".equals(request.path)) {
                return json(200, sessionInfo(session));
            }
            if ("POST".equals(request.method) && "/api/login".equals(request.path)) {
                JSONObject input = new JSONObject(request.body);
                if (!credentials.authenticate(input.optString("password"))) {
                    long seconds = credentials.lockoutRemainingSeconds();
                    return error(seconds > 0 ? 429 : 401, seconds > 0
                            ? "请等待 " + seconds + " 秒后重试" : "家长密码不正确，请输入 6 位家长密码");
                }
                sessions.revoke(cookie);
                session = sessions.issue(credentialVersion());
                return json(200, sessionInfo(session)).header("Set-Cookie", ParentWebSession.cookie(session));
            }
            if (!request.path.startsWith("/api/")) return error(404, "页面不存在");
            if (session == null) return error(401, "请先登录家长后台");
            if ("GET".equals(request.method) && "/api/state".equals(request.path)) {
                return json(200, state());
            }
            if (!"POST".equals(request.method)) return error(405, "不支持此请求方式");
            if (!ParentWebSession.csrfMatches(session, request.header("x-csrf-token"))) {
                return error(403, "页面验证已失效，请刷新后重试");
            }
            if ("/api/logout".equals(request.path)) {
                sessions.revoke(cookie);
                return message("已退出网页登录").header("Set-Cookie", ParentWebSession.expiredCookie());
            }
            if (!"/api/action".equals(request.path)) return error(404, "接口不存在");
            ParentHttpServer.Response response = action(new JSONObject(request.body), new Authorization(cookie));
            if (response.status >= 200 && response.status < 300) sessions.recordAction(session);
            return response;
        } catch (JSONException | IllegalArgumentException e) {
            return error(400, "输入无效：" + e.getMessage());
        } catch (IllegalStateException e) {
            return error(409, e.getMessage());
        } catch (Exception e) {
            GuardService.reportFailure("网页后台操作失败", e);
            return error(500, "操作失败：" + e.getMessage());
        }
    }

    private JSONObject sessionInfo(ParentWebSession.Session session) throws JSONException {
        return new JSONObject().put("authenticated", session != null)
                .put("csrf", session == null ? "" : session.csrfToken);
    }

    private JSONArray availableActions() {
        JSONArray actions = new JSONArray();
        for (String code : new String[]{"00", "11", "12", "13", "22", "33", "44", "55", "99", "77"}) {
            if (available(code)) actions.put(code);
        }
        return actions;
    }

    private boolean available(String code) {
        // The factory password authorizes first-time setup only, never viewing or removal.
        if (prefs.needsPasswordSetup()) return "00".equals(code) || "11".equals(code);
        switch (code) {
            case "00": case "11": case "12": case "13": return true;
            case "22": return prefs.isProtectionEnabled()
                    && prefs.getBonusSeconds() < Prefs.MAX_BONUS_MINUTES * 60L;
            case "33": case "44": case "77": return prefs.isProtectionEnabled();
            case "55": return prefs.isProtectionEnabled() && !DeviceProtection.isAdminActive(context)
                    && DeviceProtection.canActivateAdmin(context);
            case "99": return !prefs.isProtectionEnabled();
            default: return false;
        }
    }

    private JSONObject state() throws JSONException {
        GuardService service = GuardService.get();
        if (service != null) service.refreshProtection();
        boolean enabled = prefs.isProtectionEnabled();
        boolean adminActive = DeviceProtection.isAdminActive(context);
        boolean adminSupported = adminActive || DeviceProtection.canActivateAdmin(context);
        String settings = !AppLockService.isEnabled(context) ? "未开启"
                : AppLockService.isConnected() ? "已连接" : "未连接";
        String warning = actionError;
        if (enabled && service == null) warning += "\n管控服务未运行，请在电视打开应用";
        if (enabled && Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
            warning += "\n倒计时未开启：请在电视允许悬浮窗权限";
        }
        if (enabled && !"已连接".equals(settings)) warning += "\n设置保护" + settings;
        for (String detail : new String[]{AppLockService.lastError(), DeviceProtection.lastError(),
                GuardService.lastFailure(), ParentWebService.lastError()}) {
            if (!detail.isEmpty()) warning += "\n" + detail;
        }
        String version = "未知";
        try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception ignored) { }
        return new JSONObject().put("version", version).put("enabled", enabled)
                .put("guardRunning", service != null).put("locked", prefs.isGuardActive())
                .put("grace", prefs.isParentGraceDeferringLock())
                .put("dailyMinutes", prefs.getDailyLimitMinutes()).put("usedSeconds", prefs.getUsedSeconds())
                .put("bonusSeconds", prefs.getBonusSeconds()).put("remainingSeconds", Math.max(0L, prefs.remainingSeconds()))
                .put("adminActive", adminActive).put("adminSupported", adminSupported)
                .put("settingsProtection", settings)
                .put("updateMessage", UpdateManager.snapshot(context).message)
                .put("warning", warning.trim()).put("availableActions", availableActions());
    }

    private ParentHttpServer.Response action(JSONObject input, Authorization auth) throws Exception {
        auth.require();
        String code = input.optString("code");
        if (!available(code)) return error(409, "此操作在当前防护状态下不可用");
        if (removing) return error(409, "正在关闭防护，请稍候");
        actionError = "";
        GuardService service = GuardService.get();
        switch (code) {
            case "00":
                sessions.revoke(auth.cookie);
                return message("已关闭网页面板").header("Set-Cookie", ParentWebSession.expiredCookie());
            case "11": {
                String pin = input.optString("newPin");
                if (!Prefs.isValidPassword(pin) || Prefs.DEFAULT_PASSWORD.equals(pin)
                        || !pin.equals(input.optString("confirmPin"))) {
                    return error(400, "新密码需为 6 位数字，两次输入一致，不能使用出厂密码");
                }
                if (prefs.authenticateRemote(input.optString("oldPin")) != Prefs.AuthResult.SUCCESS) {
                    return error(prefs.lockoutRemainingSeconds() > 0 ? 429 : 400,
                            prefs.lockoutRemainingSeconds() > 0 ? "密码尝试过多，请稍后重试" : "当前家长密码不正确");
                }
                prefs.setPassword(pin);
                // A password replaced from the browser invalidates both old browser sessions
                // and television authorization, including any half-entered old password.
                sessions.clear();
                AdminSession.revoke();
                prefs.clearParentPinWindow();
                prefs.clearSettingsHold();
                prefs.endParentGrace();
                AdminAuthActivity.invalidateForLock();
                if (service != null) service.invalidateParentAuthorization();
                return message("家长密码已更新，请重新登录")
                        .header("Set-Cookie", ParentWebSession.expiredCookie());
            }
            case "12": case "22": case "33": case "77": {
                if (service == null && prefs.isProtectionEnabled()) {
                    GuardService.start(context);
                    return error(503, "管控服务正在恢复，请稍后重试");
                }
                int minutes = 0;
                if ("12".equals(code) || "22".equals(code)) {
                    Object value = input.opt("minutes");
                    if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue()) {
                        return error(400, "请输入整数分钟数");
                    }
                    minutes = ((Number) value).intValue();
                }
                if (service == null) prefs.setDailyLimitMinutes(minutes);
                else service.applyWebCommand(auth, code, minutes);
                return message("12".equals(code) ? "每日时长已设为 " + minutes + " 分钟"
                        : "22".equals(code) ? "今天已临时增加 " + minutes + " 分钟"
                        : "33".equals(code) ? "电视已立即锁定" : "今天已看已清零");
            }
            case "13":
                UpdateManager.checkNow(context);
                return message("已开始检查更新，下载完成后请在电视家长面板按 13 确认安装");
            case "44":
                disableProtection(auth, 0);
                if (!actionError.isEmpty()) return error(409, actionError);
                return message(removing ? "正在解除设备管理，稍后自动更新防护状态" : "防护已关闭");
            case "55":
                AdminAuthActivity.launch(context);
                return message("已在电视打开家长验证，请在电视输入密码后按 55，在系统页面确认开启防卸载");
            case "99":
                prefs.setProtectionEnabled(true);
                DeviceProtection.applyIfOwner(context);
                GuardService.start(context);
                return message("已启用防护");
            default: return error(400, "未知操作");
        }
    }

    private void disableProtection(Authorization auth, int attempt) {
        // A canceled or expired operation must not resume from its old delayed callback.
        if (attempt > 0 && (adminRemovalAuthorization != auth || !removing)) return;
        boolean waiting = false;
        try {
            auth.require();
            if (attempt == 0) {
                if (adminRemovalOwner != null && adminRemovalOwner != this
                        && hasAuthorizedAdminRemoval()) {
                    throw new IllegalStateException("正在解除设备管理，请稍候");
                }
                adminRemovalAuthorization = auth;
                adminRemovalDeadline = SystemClock.elapsedRealtime() + ADMIN_REMOVAL_WINDOW_MILLIS;
                removing = true;
                adminRemovalOwner = this;
            } else if (SystemClock.elapsedRealtime() >= adminRemovalDeadline) {
                throw new IllegalStateException("解除设备管理超时，请重试");
            }
            DeviceProtection.RemovalResult result = DeviceProtection.removeFromWeb(context, auth);
            if (result.needsRetry && attempt < 8) {
                waiting = true;
                main.postDelayed(() -> disableProtection(auth, attempt + 1), 250L);
                return;
            }
            if (!result.success) {
                actionError = result.message;
                DeviceProtection.applyIfOwner(context);
                return;
            }
            prefs.setProtectionEnabled(false);
            WatchdogReceiver.cancel(context);
            context.stopService(new Intent(context, GuardService.class));
            LockActivity.dismiss();
        } catch (Exception e) {
            actionError = "关闭防护未完成：" + e.getMessage();
            GuardService.reportFailure(actionError, e);
        } finally {
            if (!waiting) clearAdminRemoval(auth);
        }
    }

    /** Only an outstanding, still-authenticated web command may expect the system callback. */
    static boolean hasAuthorizedAdminRemoval() {
        ParentWebController owner = adminRemovalOwner;
        if (owner == null) return false;
        Authorization auth = owner.adminRemovalAuthorization;
        if (owner.closed || !owner.removing || auth == null) {
            owner.clearAdminRemoval(auth);
            return false;
        }
        try {
            auth.require();
            if (SystemClock.elapsedRealtime() >= owner.adminRemovalDeadline) {
                throw new IllegalStateException("解除设备管理超时，请重试");
            }
            return true;
        } catch (IllegalStateException e) {
            owner.actionError = "关闭防护未完成：" + e.getMessage();
            owner.clearAdminRemoval(auth);
            return false;
        }
    }

    private void clearAdminRemoval(Authorization auth) {
        if (adminRemovalAuthorization != auth) return;
        if (adminRemovalOwner == this) adminRemovalOwner = null;
        adminRemovalAuthorization = null;
        adminRemovalDeadline = 0L;
        removing = false;
    }

    private static ParentHttpServer.Response json(int status, JSONObject value) {
        return ParentHttpServer.Response.text(status, "application/json; charset=utf-8", value.toString());
    }

    private static ParentHttpServer.Response error(int status, String message) throws JSONException {
        return json(status, new JSONObject().put("message", message));
    }

    private static ParentHttpServer.Response message(String message) throws JSONException {
        return json(200, new JSONObject().put("message", message));
    }

    void close() {
        closed = true;
        clearAdminRemoval(adminRemovalAuthorization);
        main.removeCallbacksAndMessages(null);
        sessions.clear();
    }
}
