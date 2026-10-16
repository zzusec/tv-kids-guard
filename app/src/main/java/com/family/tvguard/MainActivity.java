package com.family.tvguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Remote-controlled parent panel. Visibility alone is never an authorization. */
public class MainActivity extends Activity {

    private static volatile MainActivity instance;
    private Prefs prefs;
    private TextView statusText;
    private TextView warnText;
    private volatile boolean visible;
    private boolean updateExternalActivity;
    private boolean awaitingUpdatePermission;
    /** Set while this activity is only bouncing to the parent gate; that is not a sign-out. */
    private boolean redirectingToGate;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder shortcut = new StringBuilder();
    private PinDialog pinDialog;

    static boolean isAuthorizedVisible() {
        return instance != null && instance.visible && AdminSession.isActive()
                && instance.prefs != null && !instance.prefs.needsPasswordSetup();
    }

    private final Runnable statusRefresher = new Runnable() {
        @Override
        public void run() {
            if (!requireParent()) {
                return;
            }
            GuardService service = GuardService.get();
            if (service != null) {
                service.setConsoleVisible(true);
            }
            refreshStatus();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        prefs = new Prefs(this);
        UpdateInstaller.recover(this);
        UpdateManager.schedule(this);
        if (!requireParent()) {
            return;
        }
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.status_text);
        warnText = findViewById(R.id.warn_text);
        TextView version = findViewById(R.id.version_text);
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            version.setText(String.format(Locale.US, "版本 %s (%d)", info.versionName, info.versionCode));
        } catch (PackageManager.NameNotFoundException e) {
            version.setText("版本信息不可用");
        }
        GuardService.start(this);
    }

    private boolean requireParent() {
        if (AdminSession.isActive() && !prefs.needsPasswordSetup()) {
            return true;
        }
        if (pinDialog != null) {
            pinDialog.dismiss();
            pinDialog = null;
        }
        if (UpdateInstaller.hasActiveSession(this)) {
            UpdateInstaller.cancel(this);
        }
        // The gate grants the session on success, so this activity must not revoke it on the way
        // out; it is only handing the parent over.
        redirectingToGate = true;
        AdminAuthActivity.launch(this);
        finish();
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        if (redirectingToGate) {
            // onCreate already handed this launch over to the gate; do not launch it again.
            return;
        }
        if (updateExternalActivity) {
            updateExternalActivity = false;
            GuardService service = GuardService.get();
            if (service != null) {
                service.setConsoleVisible(AdminSession.isActive());
            }
            UpdateInstaller.cancel(this);
            if (awaitingUpdatePermission && AdminSession.isActive()) {
                toast("授权返回，请按 13 继续安装");
            }
            awaitingUpdatePermission = false;
        }
        if (!requireParent() || statusText == null) {
            return;
        }
        shortcut.setLength(0);
        handler.removeCallbacks(statusRefresher);
        handler.post(statusRefresher);
    }

    @Override
    protected void onPause() {
        visible = false;
        handler.removeCallbacks(statusRefresher);
        if (pinDialog != null) {
            pinDialog.dismiss();
            pinDialog = null;
        }
        GuardService service = GuardService.get();
        if (service != null) {
            service.setConsoleVisible(false);
        }
        if (!redirectingToGate && !(updateExternalActivity && AdminSession.isMaintenanceActive())) {
            if (UpdateInstaller.hasActiveSession(this)) {
                UpdateInstaller.cancel(this);
            }
            AdminSession.revoke();
            if (service != null) {
                service.refreshProtection();
            }
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (PinDialog.isVolumeKey(event.getKeyCode())) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return super.dispatchKeyEvent(event);
        }
        if (!requireParent()) {
            return true;
        }
        int digit = PinDialog.digitOf(event.getKeyCode());
        if (digit < 0) {
            return super.dispatchKeyEvent(event);
        }
        shortcut.append((char) ('0' + digit));
        if (shortcut.length() < 2) {
            return true;
        }
        String code = shortcut.toString();
        shortcut.setLength(0);
        switch (code) {
            case "00": closePanel(); break;
            case "11": verifyAgain(this::promptNewPassword); break;
            case "12": promptDailyLimit(); break;
            case "13": checkUpdate(); break;
            case "22":
                if (prefs.isProtectionEnabled() && prefs.getBonusSeconds() < Prefs.MAX_BONUS_MINUTES * 60L) {
                    addBonus();
                }
                break;
            case "33":
                if (prefs.isProtectionEnabled()) {
                    lockNow();
                }
                break;
            case "44":
                if (prefs.isProtectionEnabled()) {
                    verifyAgain(this::confirmRemoval);
                }
                break;
            case "99":
                if (!prefs.isProtectionEnabled()) {
                    resumeProtection();
                }
                break;
            default: break;
        }
        return true;
    }

    private void verifyAgain(Runnable success) {
        showPin("再次验证家长密码", "请输入 6 位家长密码", Prefs.PASSWORD_LENGTH, value -> {
            Prefs.AuthResult result = prefs.authenticate(value);
            if (result == Prefs.AuthResult.SUCCESS) {
                success.run();
            } else {
                toast(prefs.lockoutRemainingSeconds() > 0L
                        ? "请等待 " + prefs.lockoutRemainingSeconds() + " 秒后重试" : "密码不正确");
            }
        });
    }

    private void showPin(String title, String message, int length, PinDialog.Listener listener) {
        showPin(title, message, length, true, listener);
    }

    private void showPin(String title, String message, int length, boolean password,
                         PinDialog.Listener listener) {
        if (!requireParent()) {
            return;
        }
        pinDialog = new PinDialog(this, title, message, length, password, value -> {
            pinDialog = null;
            if (requireParent()) {
                listener.onConfirm(value);
            }
        });
        pinDialog.show();
    }

    private void promptNewPassword() {
        showPin("修改家长密码", "输入新的 6 位密码（不能用出厂密码）", Prefs.PASSWORD_LENGTH, first -> {
            if (!Prefs.isValidPassword(first) || Prefs.DEFAULT_PASSWORD.equals(first)) {
                toast("不能使用出厂密码");
                return;
            }
            showPin("修改家长密码", "请再次输入新密码确认", Prefs.PASSWORD_LENGTH, second -> {
                if (!first.equals(second)) {
                    toast("两次输入不一致");
                    return;
                }
                try {
                    prefs.setPassword(first);
                    toast("密码已更新");
                } catch (IllegalStateException e) {
                    GuardService.reportFailure("家长密码保存失败", e);
                    toast("密码保存失败");
                }
            });
        });
    }

    private void checkUpdate() {
        UpdateManager.Snapshot update = UpdateManager.snapshot(this);
        if (UpdateInstaller.hasActiveSession(this)) {
            toast("正在准备安装");
        } else if ("ready".equals(update.status)) {
            verifyAgain(() -> showPin("安装更新 " + update.versionName,
                    "保留密码与计时数据\n输入 99，按 OK 安装", 2, false, value -> {
                        if ("99".equals(value)) {
                            beginUpdate();
                        }
                    }));
        } else if ("checking".equals(update.status) || "downloading".equals(update.status)) {
            toast(update.message);
        } else {
            UpdateManager.checkNow(this);
            toast("正在检查更新");
            refreshStatus();
        }
    }

    private void beginUpdate() {
        if (!requireParent()) {
            return;
        }
        try {
            Intent permission = UpdateInstaller.permissionIntent(this);
            if (!AdminSession.grantMaintenance()) {
                requireParent();
                return;
            }
            LockActivity.dismissForMaintenance();
            GuardService service = GuardService.get();
            if (service != null) {
                service.prepareForUpdate();
            }
            if (permission != null) {
                awaitingUpdatePermission = true;
                updateExternalActivity = true;
                startActivity(permission);
            } else {
                UpdateInstaller.start(this);
            }
        } catch (Exception e) {
            updateExternalActivity = false;
            awaitingUpdatePermission = false;
            UpdateInstaller.cancel(this);
            GuardService.reportFailure("无法打开更新安装：" + e.getMessage(), e);
            toast("无法安装更新：" + e.getMessage());
            refreshStatus();
        }
    }

    static boolean launchUpdateConfirmation(Intent intent) {
        MainActivity activity = instance;
        if (intent == null || activity == null || !isAuthorizedVisible()
                || !AdminSession.isMaintenanceActive()) {
            return false;
        }
        try {
            activity.updateExternalActivity = true;
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            activity.updateExternalActivity = false;
            GuardService.reportFailure("更新安装确认页无法打开", e);
            return false;
        }
    }

    private void promptDailyLimit() {
        showPin("每日时长", "当前 " + prefs.getDailyLimitMinutes() + " 分钟\n输入 1-"
                + Prefs.MAX_LIMIT_MINUTES + " 分钟，按 OK 保存", 4, false, value -> {
            int minutes = Integer.parseInt(value);
            if (minutes < 1 || minutes > Prefs.MAX_LIMIT_MINUTES) {
                toast("请输入 1-" + Prefs.MAX_LIMIT_MINUTES + " 之间的分钟数");
                return;
            }
            GuardService service = GuardService.get();
            if (service == null && prefs.isProtectionEnabled()) {
                GuardService.start(this);
                toast("服务正在恢复，请稍后重试");
                return;
            }
            try {
                if (service != null) {
                    service.setDailyLimitMinutes(minutes);
                } else {
                    prefs.setDailyLimitMinutes(minutes);
                }
                toast("每日时长已设为 " + minutes + " 分钟");
                refreshStatus();
            } catch (IllegalStateException e) {
                GuardService.reportFailure("每日时长保存失败", e);
                toast("每日时长保存失败");
            }
        });
    }

    private void addBonus() {
        long left = Prefs.MAX_BONUS_MINUTES - prefs.getBonusSeconds() / 60L;
        if (left <= 0L) {
            toast("今天最多增加 " + Prefs.MAX_BONUS_MINUTES + " 分钟");
            return;
        }
        showPin("增加时长", "输入 1-" + left + " 分钟，按 OK 确认", 2, false, value -> {
            int minutes = Integer.parseInt(value);
            GuardService service = GuardService.get();
            if (minutes < 1 || minutes > left) {
                toast("请输入 1-" + left + " 之间的分钟数");
            } else if (service == null) {
                GuardService.start(this);
                toast("服务正在恢复，请稍后重试");
            } else {
                service.grantBonusMinutes(minutes);
                toast("已增加 " + minutes + " 分钟");
                refreshStatus();
            }
        });
    }

    private void lockNow() {
        GuardService service = GuardService.get();
        if (service == null) {
            GuardService.start(this);
            toast("请先启用防护，或等待服务恢复");
            return;
        }
        service.lockNow();
        closePanel();
    }

    private void confirmRemoval() {
        showPin("关闭防护", "将停止管控，不会清除数据。\n输入 99 确认。", 2, value -> {
            if (!"99".equals(value) || !AdminSession.beginRemoval()) {
                return;
            }
            LockActivity.dismissForMaintenance();
            removeProtection(0);
        });
    }

    private void removeProtection(int attempt) {
        if (!AdminSession.isRemovalInProgress() || !requireParent()) {
            return;
        }
        DeviceProtection.RemovalResult result = DeviceProtection.remove(this);
        if (result.needsRetry && attempt < 8) {
            handler.postDelayed(() -> removeProtection(attempt + 1), 250L);
            return;
        }
        if (result.success) {
            try {
                prefs.setProtectionEnabled(false);
                WatchdogReceiver.cancel(this);
                stopService(new Intent(this, GuardService.class));
            } catch (IllegalStateException e) {
                GuardService.reportFailure("保存停用状态失败", e);
                result = new DeviceProtection.RemovalResult(false, false,
                        "管理身份已解除，但停用状态保存失败，请保持当前页面重试。");
            }
        }
        AdminSession.endRemoval();
        if (!result.success) {
            AdminSession.endMaintenance();
            DeviceProtection.applyIfOwner(this);
        }
        new AlertDialog.Builder(this).setTitle(result.success ? "防护已关闭" : "解除未完成")
                .setMessage(result.message).setPositiveButton("知道了", null).show();
        refreshStatus();
    }

    private void resumeProtection() {
        prefs.setProtectionEnabled(true);
        AdminSession.endMaintenance();
        DeviceProtection.applyIfOwner(this);
        GuardService.start(this);
        requestNotifications();
        toast("已启用普通防护");
        refreshStatus();
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 11);
        }
    }

    private void refreshStatus() {
        if (statusText == null) {
            return;
        }
        long used = prefs.getUsedSeconds();
        String text = String.format(Locale.US,
                "每日限额：%d 分钟\n今日已用：%d 分 %d 秒（已加 %d 分钟）\n防护：%s\n%s",
                prefs.getDailyLimitMinutes(), used / 60L, used % 60L, prefs.getBonusSeconds() / 60L,
                prefs.isProtectionEnabled() ? "运行中" : "已关闭，可卸载", DeviceProtection.describe(this));
        statusText.setText(text);
        UpdateManager.Snapshot update = UpdateManager.snapshot(this);
        String updateText = "更新：" + update.message;
        String installation = UpdateInstaller.statusText(this);
        if (!installation.isEmpty()) {
            updateText += "\n" + installation;
        }
        ((TextView) findViewById(R.id.update_text)).setText(updateText);
        String menu = "00 关闭　11 改密码　12 每日时长\n13 检查更新";
        if (prefs.isProtectionEnabled()) {
            if (prefs.getBonusSeconds() < Prefs.MAX_BONUS_MINUTES * 60L) {
                menu += "\n22 加时长";
            }
            menu += "\n33 立即锁定　44 关闭防护";
        } else {
            menu += "\n99 启用防护";
        }
        ((TextView) findViewById(R.id.menu_text)).setText(menu);
        StringBuilder warning = new StringBuilder();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            warning.append("缺少悬浮窗权限\n");
        }
        if (!AppLockService.isEnabled(this)) {
            warning.append("无障碍未启用，设置入口不受保护。\n");
        }
        if (!DeviceProtection.isDeviceOwner(this)) {
            warning.append("普通防护不能阻止卸载和强停。\n");
        }
        if (!GuardService.lastFailure().isEmpty()) {
            warning.append(GuardService.lastFailure()).append('\n');
        }
        warnText.setVisibility(warning.length() == 0 ? View.GONE : View.VISIBLE);
        warnText.setText(warning.toString().trim());
    }

    private void closePanel() {
        AdminSession.revoke();
        GuardService service = GuardService.get();
        if (service != null) {
            service.setConsoleVisible(false);
            service.refreshProtection();
        }
        if (prefs.isGuardActive()) {
            LockActivity.launch(this);
        } else {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onBackPressed() {
        closePanel();
    }
}
