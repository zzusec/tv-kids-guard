package com.family.tvguard;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A parent-confirmed installation. Persisted sessions are evidence, never authorization. */
final class UpdateInstaller {
    static final String ACTION_INSTALL_STATUS = "com.family.tvguard.UPDATE_INSTALL_STATUS";
    private static final String STORE = "tvguard_update_install";
    private static final String TAG = "TvGuardUpdate";
    private static final Object LOCK = new Object();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tvguard-update-install");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Attempt active;
    private static long generation;

    private static final class Attempt {
        final String token = UUID.randomUUID().toString();
        final long generation;
        int sessionId = -1;
        int versionCode;
        boolean committed;
        boolean confirmationLaunched;

        Attempt(long generation) {
            this.generation = generation;
        }
    }

    private UpdateInstaller() {
    }

    /** Never changes a setting. Sony/Google TV may advertise a nonfunctional stub activity. */
    @SuppressWarnings("deprecation")
    static Intent permissionIntent(Context context) {
        Intent intent;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (context.getPackageManager().canRequestPackageInstalls()) {
                    return null;
                }
                intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()));
            } else {
                if (Settings.Secure.getInt(context.getContentResolver(),
                        Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1) {
                    return null;
                }
                intent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
            }
            PackageManager manager = context.getPackageManager();
            List<ResolveInfo> matches = manager.queryIntentActivities(intent,
                    PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo match : matches) {
                ActivityInfo info = match.activityInfo;
                if (usableSettingsActivity(info)) {
                    return intent.setComponent(new ComponentName(info.packageName, info.name));
                }
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("无法检查安装来源权限：" + detail(e), e);
        }
        throw new IllegalStateException("系统没有可用的安装来源设置页面，请在电视安全设置中允许未知来源");
    }

    private static boolean usableSettingsActivity(ActivityInfo info) {
        if (info == null || info.applicationInfo == null || !info.enabled || !info.exported
                || !info.applicationInfo.enabled
                || (info.applicationInfo.flags
                & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) {
            return false;
        }
        String component = (info.packageName + "/" + info.name).toLowerCase(Locale.ROOT);
        return !component.contains("stub") && !component.contains("unsupported")
                && !component.contains("resolveractivity");
    }

    static void start(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || !authorized()) {
            throw new IllegalStateException("安装需要当前可见的家长页面和有效维护授权");
        }
        Context context = activity.getApplicationContext();
        Attempt attempt;
        synchronized (LOCK) {
            if (active != null) {
                throw new IllegalStateException("已有安装会话，请先返回或取消后重试");
            }
            attempt = new Attempt(++generation);
            active = attempt;
        }
        WORKER.execute(() -> install(context, attempt));
        watchLease(context, attempt);
    }

    private static boolean authorized() {
        return AdminSession.isMaintenanceActive() && MainActivity.isAuthorizedVisible();
    }

    private static void checkAuthorization(Attempt attempt) throws IOException {
        synchronized (LOCK) {
            if (active != attempt || !authorized()) {
                throw new IOException("家长授权已失效，请返回家长页面重试");
            }
        }
    }

    private static void install(Context context, Attempt attempt) {
        try {
            // Clean a previous process's abandoned sessions before creating another one. This
            // does not inherit its maintenance lease, even if the parent has signed in again.
            cleanOldSessions(context, attempt);
            checkAuthorization(attempt);
            UpdateManager.ReadyUpdate update = UpdateManager.readyUpdate(context);
            checkAuthorization(attempt);
            if (update == null) {
                throw new IOException("没有可安装的新版本，请先检查更新");
            }
            if (permissionIntent(context) != null) {
                throw new IOException("请先在系统设置允许安装来源，再重试安装");
            }
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());
            params.setSize(update.apk.length());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            }
            synchronized (LOCK) {
                checkAuthorization(attempt);
                attempt.versionCode = update.versionCode;
                attempt.sessionId = installer.createSession(params);
                persist(context, attempt, "正在准备系统安装");
            }
            try (PackageInstaller.Session session = installer.openSession(attempt.sessionId)) {
                checkAuthorization(attempt);
                try (FileInputStream input = new FileInputStream(update.apk);
                     OutputStream output = session.openWrite("base.apk", 0, update.apk.length())) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkAuthorization(attempt);
                        output.write(buffer, 0, count);
                    }
                    session.fsync(output);
                }
                checkAuthorization(attempt);
                PendingIntent result = callback(context, attempt.sessionId, attempt.token);
                // Serialize the last lease check with revoke()/expiry. No invalidated attempt
                // may commit, including one canceled while readyUpdate() was hashing the APK.
                synchronized (LOCK) {
                    synchronized (AdminSession.class) {
                        checkAuthorization(attempt);
                        persist(context, attempt, "等待系统安装确认");
                        // Preference fsync/binder work above can itself consume the lease.
                        checkAuthorization(attempt);
                        attempt.committed = true;
                        session.commit(result.getIntentSender());
                    }
                }
            }
        } catch (Exception e) {
            finish(context, attempt, "安装未完成：" + detail(e), true);
        }
    }

    static PendingIntent callback(Context context, int sessionId, String token) {
        Intent intent = new Intent(context, UpdateInstallReceiver.class)
                .setAction(ACTION_INSTALL_STATUS).setData(callbackUri(context, sessionId, token));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        // Not ONE_SHOT: pending-user-action is followed by a separate terminal callback.
        return PendingIntent.getBroadcast(context, sessionId, intent, flags);
    }

    private static Uri callbackUri(Context context, int sessionId, String token) {
        return new Uri.Builder().scheme("tvguard-update").authority(context.getPackageName())
                .appendPath(Integer.toString(sessionId)).appendPath(token).build();
    }

    /** Receiver work runs off the UI thread; only the activity may open system confirmation. */
    static void receive(Context context, Intent intent, Runnable complete) {
        WORKER.execute(() -> {
            try {
                handleStatus(context, intent);
            } catch (Exception e) {
                Log.e(TAG, "安装回调处理失败", e);
                Attempt attempt;
                synchronized (LOCK) {
                    attempt = active;
                }
                if (attempt != null && matches(context, intent, attempt.sessionId, attempt.token)) {
                    finish(context, attempt, "安装回调失败：" + detail(e), true);
                }
            } finally {
                complete.run();
            }
        });
    }

    private static boolean matches(Context context, Intent intent, int sessionId, String token) {
        return intent != null && ACTION_INSTALL_STATUS.equals(intent.getAction())
                && sessionId >= 0 && token != null
                && callbackUri(context, sessionId, token).equals(intent.getData())
                && intent.hasExtra(PackageInstaller.EXTRA_SESSION_ID)
                && intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) == sessionId;
    }

    private static void handleStatus(Context context, Intent intent) throws Exception {
        Attempt attempt;
        synchronized (LOCK) {
            attempt = active;
        }
        if (attempt == null) {
            SharedPreferences store = store(context);
            if (matches(context, intent, store.getInt("session", -1), store.getString("token", null))) {
                cleanOldSessions(context, null);
            }
            return;
        }
        if (!matches(context, intent, attempt.sessionId, attempt.token) || !attempt.committed
                || !intent.hasExtra(PackageInstaller.EXTRA_STATUS)) {
            return;
        }
        String packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        if (packageName != null && !context.getPackageName().equals(packageName)) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
            MAIN.post(() -> {
                synchronized (LOCK) {
                    if (active != attempt || attempt.confirmationLaunched) {
                        return;
                    }
                    boolean launched = false;
                    if (confirmation != null && authorized()) {
                        try {
                            launched = MainActivity.launchUpdateConfirmation(confirmation);
                        } catch (RuntimeException e) {
                            Log.w(TAG, "无法打开系统安装确认", e);
                        }
                    }
                    if (launched) {
                        attempt.confirmationLaunched = true;
                    } else {
                        finish(context, attempt, "安装确认未打开，请回家长页面重试", true);
                    }
                }
            });
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            if (installedVersion(context) >= attempt.versionCode) {
                UpdateManager.reconcileInstalled(context);
                finish(context, attempt, "更新已安装", false);
            } else {
                finish(context, attempt, "系统报告成功，但已安装版本未更新，请重试", true);
            }
        } else if (status >= PackageInstaller.STATUS_FAILURE
                && status <= PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            finish(context, attempt, "安装未完成（" + status + "）"
                    + (message == null ? "" : "：" + concise(message)), true);
        } else if (status == PackageInstaller.STATUS_FAILURE_TIMEOUT) {
            finish(context, attempt, "安装超时，请重试", true);
        }
    }

    static boolean hasActiveSession(Context context) {
        synchronized (LOCK) {
            return active != null;
        }
    }

    static String statusText(Context context) {
        return store(context).getString("status", "");
    }

    static void cancel(Context context) {
        Attempt attempt;
        synchronized (LOCK) {
            attempt = active;
        }
        if (attempt != null) {
            finish(context.getApplicationContext(), attempt, "安装会话已结束，可重试", true);
        } else {
            restoreProtection(context);
            recover(context);
        }
    }

    /** Boot/package-replacement entry point: no APK hashing or disk work on the caller thread. */
    static void recover(Context context) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                Attempt keep;
                synchronized (LOCK) {
                    keep = active;
                }
                cleanOldSessions(app, keep);
            } catch (Exception e) {
                recordStatus(app, "安装会话恢复失败：" + detail(e));
                Log.e(TAG, "安装会话恢复失败", e);
            }
        });
    }

    private static void cleanOldSessions(Context context, Attempt keep) throws Exception {
        SharedPreferences store = store(context);
        int oldId = store.getInt("session", -1);
        int target = store.getInt("version", 0);
        boolean old = oldId >= 0 && (keep == null || oldId != keep.sessionId);
        boolean installed = old && target > 0 && installedVersion(context) >= target;
        if (installed) {
            UpdateManager.reconcileInstalled(context);
        }
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        for (PackageInstaller.SessionInfo info : installer.getMySessions()) {
            if (info.getSessionId() != oldId
                    && !context.getPackageName().equals(info.getAppPackageName())) {
                continue;
            }
            synchronized (LOCK) {
                if (active != null && info.getSessionId() == active.sessionId) {
                    continue;
                }
            }
            String error = abandon(context, info.getSessionId());
            if (error != null) {
                throw new IOException(error);
            }
        }
        if (old) {
            synchronized (LOCK) {
                if (active == null || active == keep) {
                    if (!store.edit().remove("session").remove("token").remove("version")
                            .putString("status", installed ? "更新已安装" : "上次安装会话已清理，可重试")
                            .commit()) {
                        throw new IOException("无法保存安装会话恢复结果");
                    }
                }
            }
        }
    }

    private static void watchLease(Context context, Attempt attempt) {
        MAIN.postDelayed(() -> {
            synchronized (LOCK) {
                if (active != attempt) {
                    return;
                }
                // Visibility may legitimately end once the activity has launched confirmation;
                // the maintenance lease itself must still expire normally.
                if (!AdminSession.isMaintenanceActive()
                        || (!attempt.confirmationLaunched && !MainActivity.isAuthorizedVisible())) {
                    finish(context, attempt, "家长授权已结束，可重新验证后安装", true);
                } else {
                    watchLease(context, attempt);
                }
            }
        }, 1000L);
    }

    private static void finish(Context context, Attempt attempt, String status, boolean abandon) {
        synchronized (LOCK) {
            if (active != attempt) {
                return;
            }
            active = null;
        }
        WORKER.execute(() -> {
            String cleanupError = abandon && attempt.sessionId >= 0
                    ? abandon(context, attempt.sessionId) : null;
            synchronized (LOCK) {
                if (generation == attempt.generation) {
                    SharedPreferences.Editor edit = store(context).edit();
                    if (cleanupError == null) {
                        edit.remove("session").remove("token").remove("version");
                    }
                    edit.putString("status", cleanupError == null ? status : cleanupError);
                    if (!edit.commit()) {
                        Log.e(TAG, "无法保存安装结束状态");
                    }
                }
            }
        });
        MAIN.post(() -> {
            synchronized (LOCK) {
                if (generation == attempt.generation && active == null) {
                    restoreProtection(context);
                }
            }
        });
    }

    private static void restoreProtection(Context context) {
        AdminSession.endMaintenance();
        DeviceProtection.applyIfOwner(context);
        GuardService service = GuardService.get();
        if (service != null) {
            service.refreshProtection();
        }
    }

    private static String abandon(Context context, int id) {
        try {
            context.getPackageManager().getPackageInstaller().abandonSession(id);
            return null;
        } catch (RuntimeException e) {
            // A committed/replaced session can already be gone. Only ignore that confirmed case.
            try {
                if (context.getPackageManager().getPackageInstaller().getSessionInfo(id) == null) {
                    return null;
                }
            } catch (RuntimeException queryError) {
                e.addSuppressed(queryError);
            }
            Log.e(TAG, "无法清理安装会话 " + id, e);
            return "无法清理安装会话 " + id + "：" + detail(e);
        }
    }

    @SuppressWarnings("deprecation")
    private static long installedVersion(Context context) throws PackageManager.NameNotFoundException {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }

    private static SharedPreferences store(Context context) {
        return context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    private static void persist(Context context, Attempt attempt, String status) throws IOException {
        if (!store(context).edit().putInt("session", attempt.sessionId).putString("token", attempt.token)
                .putInt("version", attempt.versionCode).putString("status", status).commit()) {
            throw new IOException("无法保存安装会话");
        }
    }

    private static void recordStatus(Context context, String status) {
        if (!store(context).edit().putString("status", status).commit()) {
            Log.e(TAG, "无法保存安装状态：" + status);
        }
    }

    private static String detail(Exception error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + concise(message));
    }

    private static String concise(String message) {
        String line = message.replace('\n', ' ').replace('\r', ' ').trim();
        return line.length() > 180 ? line.substring(0, 180) : line;
    }
}
