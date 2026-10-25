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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** Opaque quota lock. Only a verified parent action or a new allowance releases it. */
public class LockActivity extends Activity {

    private static LockActivity instance;
    private TextView promptView;
    private TextView inputView;
    private TextView titleView;
    private Button parentButton;
    private RemoteKeypad.NumericPad keypad;
    private RemoteInputMode inputMode;
    private Prefs prefs;
    private boolean rejected;
    private boolean physicalInput;
    private int parentSelectKey;
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
        parentButton = findViewById(R.id.parent_manage);
        parentButton.setOnClickListener(view -> openParentGate(this));
        keypad = new RemoteKeypad.NumericPad(this, new RemoteKeypad.NumericListener() {
            @Override public void onDigit(int digit) { enterDigit(digit); }
            @Override public void onDelete() { deleteDigit(); refreshUi(); }
            @Override public void onConfirm() { submitPin(); refreshUi(); }
        });
        ((FrameLayout) findViewById(R.id.keypad_host)).addView(keypad);
        inputMode = new RemoteInputMode(this, parentButton, keypad, this::configureInputLayout);
        keypad.setUpperFocusTarget(parentButton);
        LinearLayout.LayoutParams icon = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Math.round(48 * getResources()
                .getDisplayMetrics().density));
        icon.topMargin = Math.round(12 * getResources().getDisplayMetrics().density);
        icon.gravity = Gravity.CENTER_HORIZONTAL;
        ((LinearLayout) findViewById(R.id.entry_copy)).addView(inputMode.requestButton(), icon);
        configureInputLayout();
        parentButton.requestFocus();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void configureInputLayout() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        float density = getResources().getDisplayMetrics().density;
        boolean showPad = inputMode.usesOnScreen();
        boolean wide = showPad && widthDp >= 600;
        LinearLayout columns = findViewById(R.id.entry_columns);
        columns.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        FrameLayout.LayoutParams content = (FrameLayout.LayoutParams) columns.getLayoutParams();
        content.width = Math.round(Math.min(showPad ? 900 : 560, widthDp - 48) * density);
        content.gravity = Gravity.CENTER;
        columns.setLayoutParams(content);
        findViewById(R.id.entry_copy).setLayoutParams(new LinearLayout.LayoutParams(
                wide ? 0 : LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, wide ? 1f : 0f));
        LinearLayout.LayoutParams pad = new LinearLayout.LayoutParams(Math.round(252 * density),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pad.gravity = Gravity.CENTER_HORIZONTAL;
        pad.leftMargin = wide ? Math.round(24 * density) : 0;
        pad.topMargin = wide ? 0 : Math.round(18 * density);
        findViewById(R.id.keypad_column).setLayoutParams(pad);
        findViewById(R.id.keypad_column).setVisibility(showPad ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        inputMode.refresh();
        parentButton.requestFocus();
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        inputMode.refresh();
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

    /**
     * Opens the parent panel for a PIN that was just verified on the lock screen, so the parent
     * does not have to type it a second time. Only the panel is allowed to hand out viewing time,
     * which means the lock itself stays in place underneath and returns as soon as the panel
     * closes. Same package, so Lock Task does not have to be released.
     */
    static void openParentConsole(Context ctx) {
        GuardService service = GuardService.get();
        if (service != null) {
            service.setParentUiVisible(true);
        }
        // The verified PIN can arrive from the lock screen or from the guard service, and a
        // service may only open an activity of its own task, so the flag follows the caller.
        Context caller = instance != null ? instance : ctx;
        Intent intent = new Intent(caller, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!(caller instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            caller.startActivity(intent);
        } catch (Exception e) {
            GuardService.reportFailure("家长面板启动失败，请用遥控器上的家长管理重新进入", e);
        }
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
        int digit = PinDialog.digitOf(code);
        if (digit >= 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                cancelParentSelect();
                physicalInput = true;
                inputMode.onPhysicalDigit(event);
                keypad.usePhysicalInput();
                enterDigit(digit);
            }
            return true;
        }
        if (!RemoteKeypad.isSelectKey(code)) cancelParentSelect();
        if (inputMode.handleKeyEvent(event)) return true;
        if (parentButton.hasFocus()) {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keypad.isEnabled()) {
                    physicalInput = false;
                    keypad.focusFirstKey();
                }
                return true;
            }
            if (!physicalInput) {
                if (RemoteKeypad.isSelectKey(code)) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                        parentSelectKey = code;
                        parentButton.setPressed(true);
                    } else if (event.getAction() == KeyEvent.ACTION_UP) {
                        boolean activate = parentSelectKey == code && !event.isCanceled();
                        cancelParentSelect();
                        if (activate) parentButton.performClick();
                    }
                    return true;
                }
                if (RemoteKeypad.isNavigationKey(code)) return true;
            }
        }
        if (RemoteKeypad.isNavigationKey(code) && code != KeyEvent.KEYCODE_DPAD_LEFT) {
            physicalInput = false;
        }
        if (keypad.handleKeyEvent(event)) {
            refreshUi();
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return true;
        }
        if (RemoteKeypad.isSelectKey(code)) {
            GuardService service = GuardService.get();
            if (service == null || service.lockInput().isEmpty()) {
                openParentGate(this);
            } else {
                submitPin();
            }
        } else if (code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_DPAD_LEFT) {
            deleteDigit();
        } else if (code == KeyEvent.KEYCODE_BACK) {
            GuardService service = GuardService.get();
            if (service != null) {
                int length = service.lockInput().length();
                for (int i = 0; i < length; i++) service.deleteLockDigit();
            }
            rejected = false;
            physicalInput = false;
            parentButton.requestFocus();
        }
        refreshUi();
        return true;
    }

    private void cancelParentSelect() {
        parentSelectKey = 0;
        parentButton.setPressed(false);
    }

    private void deleteDigit() {
        GuardService service = GuardService.get();
        if (service != null && prefs.lockoutRemainingSeconds() == 0L) {
            service.deleteLockDigit();
            rejected = false;
        }
    }

    private void submitPin() {
        GuardService service = GuardService.get();
        if (service != null && prefs.lockoutRemainingSeconds() == 0L
                && service.isPasswordComplete()) {
            rejected = !service.onOk();
        }
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
        boolean canInput = service != null && prefs.lockoutRemainingSeconds() == 0L;
        keypad.setEnabled(canInput);
        if (!canInput && !parentButton.hasFocus()) parentButton.requestFocus();
        inputMode.syncRequestVisibility();
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
                    : "输入 6 位家长密码进入家长面板");
            StringBuilder mask = new StringBuilder();
            for (int i = 0; i < service.lockInput().length(); i++) {
                mask.append('●');
            }
            inputView.setText(mask);
        }
    }
}
