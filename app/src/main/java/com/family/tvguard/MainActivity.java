package com.family.tvguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.ContextThemeWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Remote-controlled parent panel. Visibility alone is never an authorization. */
public class MainActivity extends Activity {

    private static volatile MainActivity instance;
    private Prefs prefs;
    private TextView statusText;
    private TextView warnText;
    private final Map<String, Button> actionButtons = new LinkedHashMap<>();
    private volatile boolean visible;
    private boolean updateExternalActivity;
    private boolean awaitingUpdatePermission;
    private boolean updatePrompted;
    private boolean updateRequested;
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
        configurePanel();
        createActionButtons();
        TextView version = findViewById(R.id.version_text);
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            version.setText("版本 " + info.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            version.setText("版本信息不可用");
        }
        GuardService.start(this);
        refreshStatus();
        actionButtons.get("12").requestFocus();
    }

    private void configurePanel() {
        float density = getResources().getDisplayMetrics().density;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        View scroll = findViewById(R.id.main_scroll);
        ViewGroup.LayoutParams panelParams = scroll.getLayoutParams();
        // Keep a safe television overscan margin without making the narrow layout rigid.
        panelParams.width = Math.min(Math.round(900 * density),
                Math.max(1, screenWidth - Math.round(72 * density)));
        scroll.setLayoutParams(panelParams);

        LinearLayout columns = findViewById(R.id.panel_columns);
        boolean twoColumns = screenWidth > screenHeight && screenWidth / density >= 600;
        columns.setOrientation(twoColumns ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        View status = findViewById(R.id.status_column);
        View actions = findViewById(R.id.menu_column);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                twoColumns ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, twoColumns ? 0.7f : 0);
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(
                twoColumns ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, twoColumns ? 1.3f : 0);
        if (twoColumns) {
            menuParams.setMarginStart(Math.round(24 * density));
        } else {
            menuParams.topMargin = Math.round(20 * density);
        }
        status.setLayoutParams(statusParams);
        actions.setLayoutParams(menuParams);
    }

    private void createActionButtons() {
        LinearLayout menu = findViewById(R.id.menu_actions);
        String[][] actions = {
                {"00", "关闭面板"},
                {"11", "改密码"},
                {"12", "修改每日时长"},
                {"13", "检查更新"},
                {"22", "临时加时长"},
                {"33", "立即锁定"},
                {"44", "关闭防护"},
                {"99", "启用防护"}
        };
        // Keep real Buttons and the established grid so DPAD and shortcuts share one action path.
        for (int index = 0; index < actions.length; index += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBaselineAligned(false);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int column = 0; column < 2 && index + column < actions.length; column++) {
                String[] action = actions[index + column];
                Button button = actionButton(action[0], action[1]);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                int gap = Math.round(10 * getResources().getDisplayMetrics().density);
                params.setMarginStart(column == 0 ? 0 : gap);
                params.topMargin = index == 0 ? 0 : gap;
                row.addView(button, params);
            }
            menu.addView(row);
        }
    }

    private Button actionButton(String code, String label) {
        Button button = new Button(this);
        button.setId(View.generateViewId());
        button.setTag("action_" + code);
        RemoteKeypad.styleButton(button, code, label);
        button.setOnClickListener(view -> executeAction(code));
        actionButtons.put(code, button);
        return button;
    }

    /** Shortcut order in the two-column grid; hidden actions are skipped, never landed on. */
    private static final String[][] GRID = {
            {"00", "11"}, {"12", "13"}, {"22", "33"}, {"44", "99"}
    };

    /**
     * Explicit DPAD navigation over the visible shortcuts in row-first order. Hidden actions are
     * skipped, so every shortcut stays reachable and focus never disappears when protection is
     * toggled; DOWN/RIGHT advance and UP/LEFT step back, both wrapping.
     */
    private boolean navigateFocus(int keyCode) {
        // Build the visible grid row by row, keeping the two-column shape. Hidden actions are
        // skipped, so a column can be shorter than its neighbours.
        List<List<Button>> rows = new ArrayList<>();
        for (String[] row : GRID) {
            List<Button> visible = new ArrayList<>();
            for (String code : row) {
                Button button = actionButtons.get(code);
                if (button != null && button.isShown()) {
                    visible.add(button);
                }
            }
            if (!visible.isEmpty()) {
                rows.add(visible);
            }
        }
        if (rows.isEmpty()) {
            return false;
        }
        View focused = getWindow() == null ? null : getWindow().getDecorView().findFocus();
        int focusedRow = -1;
        int focusedColumn = -1;
        for (int r = 0; r < rows.size() && focusedRow < 0; r++) {
            int c = rows.get(r).indexOf(focused);
            if (c >= 0) {
                focusedRow = r;
                focusedColumn = c;
            }
        }
        if (focusedRow < 0) {
            return rows.get(0).get(0).requestFocus();
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                // Stay in the same column and wrap vertically.
                int step = keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
                for (int hop = 1; hop <= rows.size(); hop++) {
                    int r = ((focusedRow + step * hop) % rows.size() + rows.size()) % rows.size();
                    List<Button> row = rows.get(r);
                    if (focusedColumn < row.size()) {
                        return row.get(focusedColumn).requestFocus();
                    }
                    // The column is shorter in this row (a hidden action): use the last cell.
                    return row.get(row.size() - 1).requestFocus();
                }
                return false;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                // Move inside the pair, wrapping to the other cell of the same row.
                List<Button> row = rows.get(focusedRow);
                int column = (focusedColumn + 1) % row.size();
                return row.get(column).requestFocus();
            }
            default:
                return false;
        }
    }

    private static boolean isPanelControl(View view) {
        return view != null && view.getTag() != null
                && view.getTag().toString().startsWith("action_");
    }

    private boolean isActionAvailable(String code) {
        switch (code) {
            case "00":
            case "11":
            case "12":
            case "13":
                return true;
            case "22":
                return prefs.isProtectionEnabled()
                        && prefs.getBonusSeconds() < Prefs.MAX_BONUS_MINUTES * 60L;
            case "33":
            case "44":
                return prefs.isProtectionEnabled();
            case "99":
                return !prefs.isProtectionEnabled();
            default:
                return false;
        }
    }

    private boolean canExecuteAction(String code) {
        Button button = actionButtons.get(code);
        return button != null && button.isShown() && button.isEnabled() && isActionAvailable(code);
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
        if (PinDialog.isVolumeKey(event.getKeyCode()) || event.getKeyCode() == KeyEvent.KEYCODE_POWER) {
            return super.dispatchKeyEvent(event);
        }
        // Panel navigation is consumed for both DOWN and UP, before any action filtering: if the
        // UP reached the platform it would run a second geometry-based focus search and move the
        // ring somewhere else.
        View navFocus = getWindow() == null ? null : getWindow().getDecorView().findFocus();
        if (RemoteKeypad.isNavigationKey(event.getKeyCode()) && isPanelControl(navFocus)) {
            shortcut.setLength(0);
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                    && requireParent()) {
                navigateFocus(event.getKeyCode());
            }
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return super.dispatchKeyEvent(event);
        }
        if (!requireParent()) {
            return true;
        }
        if (RemoteKeypad.isNavigationKey(event.getKeyCode())) {
            shortcut.setLength(0);
            return super.dispatchKeyEvent(event);
        }
        if (RemoteKeypad.isSelectKey(event.getKeyCode())) {
            shortcut.setLength(0);
            return super.dispatchKeyEvent(event);
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
        executeAction(code);
        return true;
    }

    /** Both numeric shortcuts and focused buttons cross the same authorization boundary. */
    private void executeAction(String code) {
        shortcut.setLength(0);
        if (!requireParent() || !canExecuteAction(code)) {
            return;
        }
        switch (code) {
            case "00": closePanel(); break;
            case "11": verifyAgain(this::promptNewPassword); break;
            case "12": promptDailyLimit(); break;
            case "13": checkUpdate(); break;
            case "22": addBonus(); break;
            case "33": lockNow(); break;
            case "44": confirmRemoval(); break;
            case "99": resumeProtection(); break;
            default: break;
        }
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

    /** Fetches a newly published release, then installs it; download progress stays on the panel. */
    private void checkUpdate() {
        updateRequested = true;
        UpdateManager.Snapshot update = UpdateManager.snapshot(this);
        if (UpdateInstaller.hasActiveSession(this)) {
            toast("正在准备安装");
        } else if ("ready".equals(update.status)) {
            beginUpdate();
        } else if ("checking".equals(update.status) || "downloading".equals(update.status)) {
            toast(update.message);
        } else {
            UpdateManager.checkNow(this);
            toast("正在检查更新，下载完成后会询问是否安装");
            refreshStatus();
        }
    }

    /** Called from the one-second refresh: a finished download asks for install automatically. */
    private void offerReadyUpdate() {
        UpdateManager.Snapshot update = UpdateManager.snapshot(this);
        if ("ready".equals(update.status) && !UpdateInstaller.hasActiveSession(this)
                && !updatePrompted && AdminSession.isActive()) {
            updatePrompted = true;
            beginUpdate();
        } else if (!"ready".equals(update.status)) {
            updatePrompted = false;
        }
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
        showPin("临时加时长", "仅对今天有效。输入 1-" + left + " 分钟，按 OK 确认", 2, false, value -> {
            if (!canExecuteAction("22")) {
                return;
            }
            int minutes = Integer.parseInt(value);
            GuardService service = GuardService.get();
            if (minutes < 1 || minutes > left) {
                toast("请输入 1-" + left + " 之间的分钟数");
            } else if (service == null) {
                GuardService.start(this);
                toast("服务正在恢复，请稍后重试");
            } else {
                service.grantBonusMinutes(minutes);
                toast("今天已临时增加 " + minutes + " 分钟");
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
        new AlertDialog.Builder(new ContextThemeWrapper(this,
                R.style.Theme_TvGuard_PanelDialog)).setTitle("关闭防护")
                .setMessage("将停止管控，不会清除数据。\n确认关闭请按「确认」，返回请按「取消」。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认关闭", (dialog, which) -> {
                    if (!canExecuteAction("44") || !AdminSession.beginRemoval()) {
                        return;
                    }
                    LockActivity.dismissForMaintenance();
                    removeProtection(0);
                })
                .show();
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
        boolean enabled = prefs.isProtectionEnabled();
        statusText.setText(enabled ? "防护已开启" : "防护已关闭");
        statusText.setTextColor(getResources().getColor(enabled ? R.color.panel_green : R.color.panel_muted));
        statusText.getBackground().mutate().setTint(getResources().getColor(enabled
                ? R.color.panel_green_soft : R.color.panel_line));
        ((TextView) findViewById(R.id.daily_limit_value)).setText(prefs.getDailyLimitMinutes() + " 分钟");
        ((TextView) findViewById(R.id.used_time_value)).setText(prefs.getUsedSeconds() / 60L + " 分钟");
        findViewById(R.id.today_override).setVisibility(enabled && prefs.isUnlockedToday()
                ? View.VISIBLE : View.GONE);
        TextView bonus = findViewById(R.id.bonus_summary);
        bonus.setText("今天临时加时 " + prefs.getBonusSeconds() / 60L + " 分钟");
        bonus.setVisibility(prefs.getBonusSeconds() > 0L ? View.VISIBLE : View.GONE);
        UpdateManager.Snapshot update = UpdateManager.snapshot(this);
        String updateText;
        if ("ready".equals(update.status)) {
            updateText = "有新版本，按 13 安装";
        } else if ("failed".equals(update.status)) {
            updateText = "更新未完成，按 13 重试";
        } else if (updateRequested || "checking".equals(update.status)
                || "downloading".equals(update.status)) {
            updateText = update.message;
        } else {
            updateText = "";
        }
        String installation = UpdateInstaller.statusText(this);
        if (!installation.isEmpty()) {
            updateText += (updateText.isEmpty() ? "" : "\n") + installation;
        }
        TextView updateView = findViewById(R.id.update_text);
        updateView.setText(updateText);
        updateView.setVisibility(updateText.isEmpty() ? View.GONE : View.VISIBLE);
        View focused = findViewById(R.id.menu_actions).findFocus();
        boolean focusedActionHidden = false;
        for (Map.Entry<String, Button> action : actionButtons.entrySet()) {
            Button button = action.getValue();
            int visibility = isActionAvailable(action.getKey()) ? View.VISIBLE : View.GONE;
            if (button.getVisibility() != visibility) {
                focusedActionHidden |= button == focused && visibility == View.GONE;
                button.setVisibility(visibility);
            }
        }
        if (focusedActionHidden) {
            actionButtons.get("12").requestFocus();
        }
        StringBuilder warning = new StringBuilder();
        if (prefs.isProtectionEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                warning.append("倒计时未开启：需允许悬浮窗权限\n");
            }
            if (!AppLockService.isEnabled(this)) {
                warning.append("设置保护未开启：需启用无障碍服务\n");
            }
        }
        if (!GuardService.lastFailure().isEmpty()) {
            warning.append(GuardService.lastFailure()).append('\n');
        }
        if (!DeviceProtection.lastError().isEmpty()) {
            warning.append(DeviceProtection.lastError()).append('\n');
        }
        warnText.setVisibility(warning.length() == 0 ? View.GONE : View.VISIBLE);
        warnText.setText(warning.toString().trim());
        offerReadyUpdate();
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
