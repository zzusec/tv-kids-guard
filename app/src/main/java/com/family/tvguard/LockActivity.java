package com.family.tvguard;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Button;
import android.widget.LinearLayout;

/** Opaque quota lock. Only a verified parent action or a new allowance releases it. */
public class LockActivity extends Activity {

    private static LockActivity instance;
    private TextView promptView;
    private TextView inputView;
    private TextView titleView;
    private Prefs prefs;
    private boolean rejected;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!isLockActive() || AdminSession.isMaintenanceActive()) {
                finishAuthorized();
                return;
            }
            if (GuardService.get() == null) {
                GuardService.start(LockActivity.this);
            }
            refreshUi();
            handler.postDelayed(this, 1000L);
        }
    };

    static void launch(Context ctx) {
        if (!new Prefs(ctx).isGuardActive() || AdminSession.isMaintenanceActive()) {
            return;
        }
        // The power key must be able to blank the screen and leave it blank. This window
        // carries FLAG_TURN_SCREEN_ON, so re-launching it while the panel is dark would light
        // the room back up about a second after the power key was pressed. SCREEN_ON
        // re-asserts the lock, so standby is a pause, never an escape.
        if (!isScreenOn(ctx)) {
            return;
        }
        Intent intent = new Intent(ctx, LockActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            ctx.startActivity(intent);
        } catch (Exception e) {
            GuardService.reportFailure("锁屏启动失败，请检查系统权限", e);
        }
    }

    /** True while the panel is lit. The guard has nothing to say to a dark screen. */
    private static boolean isScreenOn(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm == null || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH
                ? pm.isInteractive() : pm.isScreenOn());
    }

    static void dismiss() {
        LockActivity current = instance;
        if (current != null && !current.isLockActive()) {
            current.finishAuthorized();
        }
    }

    static void dismissForMaintenance() {
        if (AdminSession.isMaintenanceActive() && instance != null) {
            instance.finishAuthorized();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        prefs = new Prefs(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        setContentView(R.layout.lock_screen);
        titleView = findViewById(R.id.lock_title);
        promptView = findViewById(R.id.lock_prompt);
        inputView = findViewById(R.id.lock_input);
        // No on-screen keypad anywhere: a highlighted virtual key would show the PIN to anyone
        // in the room. The remote's number keys are the only entry path.
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        instance = this;
        if (!isLockActive() || AdminSession.isMaintenanceActive()) {
            finishAuthorized();
            return;
        }
        GuardService service = GuardService.get();
        if (service != null) {
            service.onLockActivityVisibility(true);
        }
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        enterLockTask();
        refreshUi();
    }

    private void enterLockTask() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return; // KitKat has no managed Lock Task; the ordinary quota lock still applies.
        }
        DeviceProtection.applyIfOwner(this);
        if (!DeviceProtection.isLockTaskAllowed(this)) {
            return; // Never substitute user-exitable screen pinning for a managed lock.
        }
        try {
            if (!lockTaskRunning()) {
                startLockTask();
            }
        } catch (Exception e) {
            GuardService.reportFailure("系统锁定任务启动失败，当前只能使用普通防护", e);
        }
    }

    /** The parent gate belongs to the allowlisted app, so opening it must not end Lock Task. */
    static void openParentGate(Context ctx) {
        GuardService service = GuardService.get();
        if (service != null) {
            service.setParentUiVisible(true);
        }
        AdminAuthActivity.launch(instance != null ? instance : ctx);
    }

    private boolean lockTaskRunning() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) {
            throw new IllegalStateException("Task manager unavailable");
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE
                : Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && am.isInLockTaskMode();
    }

    private boolean releaseLockTask() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && lockTaskRunning()) {
                stopLockTask();
                if (lockTaskRunning()) {
                    throw new IllegalStateException("System lock task is still active");
                }
            }
            return true;
        } catch (Exception e) {
            GuardService.reportFailure("退出系统锁定任务失败，尚未核实退出，请重试家长维护", e);
            return false;
        }
    }

    private void finishAuthorized() {
        if (isLockActive() && !AdminSession.isMaintenanceActive()) {
            return;
        }
        if (releaseLockTask()) {
            finish();
        }
    }

    @Override
    protected void onPause() {
        GuardService service = GuardService.get();
        if (service != null) {
            service.onLockActivityVisibility(false);
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(ticker);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (instance == this) {
            instance = null;
        }
        // Activity destruction is not parent authorization to stop Lock Task.
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Returning to the previous video task would bypass the daily quota.
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        handler.postDelayed(() -> {
            if (isLockActive() && !AdminSession.isMaintenanceActive()
                    && !AdminAuthActivity.isVisible() && !MainActivity.isAuthorizedVisible()) {
                launch(this);
            }
        }, 300L);
    }

    private boolean isLockActive() {
        return prefs != null && prefs.isGuardActive();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!isLockActive()) {
            return super.dispatchKeyEvent(event);
        }
        // The quota lock owns the remote, not the volume: the room keeps its audio controls.
        if (PinDialog.isVolumeKey(event.getKeyCode())) {
            return super.dispatchKeyEvent(event);
        }
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_TV_POWER) {
            return super.dispatchKeyEvent(event);
        }
        if (RemoteKeypad.isSelectKey(code)) {
            // An empty buffer opens the parent gate; a complete PIN submits.
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                GuardService current = GuardService.get();
                if (current == null || current.lockInput().isEmpty()) {
                    openParentGate(this);
                } else if (current.isPasswordComplete()) {
                    current.onOk();
                }
                refreshUi();
            }
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return true;
        }
        int digit = PinDialog.digitOf(code);
        if (digit >= 0) {
            enterDigit(digit);
        } else if (code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_DPAD_LEFT) {
            GuardService service = GuardService.get();
            if (service != null && prefs.lockoutRemainingSeconds() == 0L) {
                service.deleteLockDigit();
                rejected = false;
            }
        }
        refreshUi();
        return true;
    }

    private void enterDigit(int digit) {
        GuardService service = GuardService.get();
        if (service != null && prefs.lockoutRemainingSeconds() == 0L) {
            service.onDigit(digit);
            rejected = false;
            if (service.isPasswordComplete()) {
                rejected = !service.onOk();
            }
        }
        refreshUi();
    }

    private void refreshUi() {
        GuardService service = GuardService.get();
        titleView.setText("今日电视时长已用完");
        if (service == null) {
            promptView.setText("正在恢复服务…");
            inputView.setText("");
            return;
        }
        long wait = prefs.lockoutRemainingSeconds();
        if (wait > 0L) {
            promptView.setText(String.format(java.util.Locale.US,
                    "错误次数过多，请等待 %d 分 %d 秒", wait / 60L, wait % 60L));
            inputView.setText("");
        } else if (rejected) {
            promptView.setText("密码不正确，请重新输入");
            inputView.setText("");
        } else {
            promptView.setText(prefs.needsPasswordSetup()
                    ? "请家长按 OK 设置密码"
                    : "输入 6 位家长密码解除限时");
            StringBuilder mask = new StringBuilder();
            for (int i = 0; i < service.lockInput().length(); i++) {
                mask.append('●');
            }
            inputView.setText(mask);
        }
    }
}
