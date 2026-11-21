package com.family.tvguard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen parent gate; a dialog over playing video is not an adequate lock. */
public class AdminAuthActivity extends Activity {

    private static AdminAuthActivity instance;
    private static final int CHECK = 0;
    private static final int NEW_PIN = 1;
    private static final int CONFIRM_PIN = 2;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder input = new StringBuilder();
    private Prefs prefs;
    private TextView title;
    private TextView prompt;
    private TextView masked;
    private RemoteKeypad.NumericPad keypad;
    private RemoteInputMode inputMode;
    private int stage;
    private String firstPin;
    private String error = "";
    private boolean visible;
    private boolean openingConsole;
    private boolean authorizedSetup;
    private WindowVisibility windowVisibility;

    static void launch(Context ctx) {
        GuardService service = GuardService.get();
        if (service != null) service.beginParentUiLaunch();
        Intent intent = new Intent(ctx, AdminAuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            ctx.startActivity(intent);
        } catch (Exception e) {
            if (service != null) service.cancelParentUiLaunch();
            GuardService.reportFailure("家长认证界面启动失败", e);
        }
    }

    static boolean isVisible() {
        return instance != null && instance.visible;
    }

    /** A new explicit lock cannot accept input or a handover that began before that lock. */
    static void invalidateForLock() {
        AdminAuthActivity current = instance;
        if (current == null) return;
        current.input.setLength(0);
        current.firstPin = null;
        current.stage = CHECK;
        current.authorizedSetup = false;
        current.openingConsole = false;
        current.error = "";
        current.render();
        if (current.visible && current.inputMode != null) current.inputMode.focusEntry();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            validateSetupAuthorization();
            render();
            handler.postDelayed(this, 1000L);
        }
    };

    /** Factory-PIN proof is valid only while this device still needs its first configured PIN. */
    private boolean validateSetupAuthorization() {
        if (!authorizedSetup) return true;
        boolean active = AdminSession.isActive();
        boolean needsSetup = prefs.needsPasswordSetup();
        if (active && needsSetup) return true;
        AdminSession.revoke();
        prefs.clearParentPinWindow();
        prefs.clearSettingsHold();
        stage = CHECK;
        firstPin = null;
        input.setLength(0);
        authorizedSetup = false;
        error = needsSetup ? "授权已过期，请重新验证" : "家长密码已更新，请重新验证";
        return false;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        prefs = new Prefs(this);
        windowVisibility = new WindowVisibility(this, visible -> {
            GuardService service = GuardService.get();
            if (service != null && (visible || !openingConsole)) {
                service.setParentUiVisible(visible);
            }
        });
        ParentWebService.start(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.gate_screen);
        title = findViewById(R.id.lock_title);
        prompt = findViewById(R.id.lock_prompt);
        masked = findViewById(R.id.lock_input);
        keypad = new RemoteKeypad.NumericPad(this, new RemoteKeypad.NumericListener() {
            @Override public void onDigit(int digit) { appendDigit(digit); render(); }
            @Override public void onDelete() { deleteDigit(); render(); }
            @Override public void onConfirm() { submit(); render(); }
        });
        ((FrameLayout) findViewById(R.id.keypad_host)).addView(keypad);
        inputMode = new RemoteInputMode(this, masked, keypad, this::configureInputLayout);
        LinearLayout.LayoutParams icon = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Math.round(48 * getResources()
                .getDisplayMetrics().density));
        icon.topMargin = Math.round(12 * getResources().getDisplayMetrics().density);
        icon.gravity = Gravity.CENTER_HORIZONTAL;
        ((LinearLayout) findViewById(R.id.entry_copy)).addView(inputMode.requestButton(), icon);
        configureInputLayout();
        inputMode.focusEntry();
        render();
        GuardService.start(this);
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
        findViewById(R.id.keypad_column).setVisibility(showPad ? android.view.View.VISIBLE
                : android.view.View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        inputMode.refresh();
        visible = true;
        validateSetupAuthorization();
        if (AdminSession.isActive() && !prefs.needsPasswordSetup()) {
            openConsole();
            return;
        }
        if (AdminSession.isActive() && prefs.needsPasswordSetup()) {
            stage = NEW_PIN;
            authorizedSetup = true;
        }
        windowVisibility.start();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        visible = false;
        if (windowVisibility != null) windowVisibility.stop();
        handler.removeCallbacks(ticker);
        GuardService service = GuardService.get();
        if (service != null && !openingConsole) {
            // When handing over to the panel, keep the lease so the shield cannot flash back
            // between the two parent screens; the panel takes the lease over on its next resume.
            service.setParentUiVisible(false);
        }
        if (!openingConsole) {
            AdminSession.revoke();
            input.setLength(0);
            firstPin = null;
            stage = CHECK;
            authorizedSetup = false;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (windowVisibility != null) windowVisibility.stop();
        handler.removeCallbacksAndMessages(null);
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (windowVisibility != null) windowVisibility.focusChanged(hasFocus);
    }

    @Override protected void onStop() {
        if (windowVisibility != null) windowVisibility.stop();
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        AdminSession.revoke();
        if (prefs.isGuardActive()) {
            LockActivity.launch(this);
        } else {
            // Do not reveal the Settings/uninstall page that triggered this gate.
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (PinDialog.isVolumeKey(code) || code == KeyEvent.KEYCODE_POWER
                || code == KeyEvent.KEYCODE_TV_POWER) {
            return super.dispatchKeyEvent(event);
        }
        if (inputMode.handleKeyEvent(event) || keypad.handleKeyEvent(event)) {
            render();
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return true;
        }
        int digit = PinDialog.digitOf(code);
        if (code == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
        } else if (digit >= 0) {
            inputMode.onPhysicalDigit(event);
            keypad.usePhysicalInput();
            appendDigit(digit);
        } else if (code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_DPAD_LEFT) {
            deleteDigit();
        } else if (RemoteKeypad.isSelectKey(code)) {
            submit();
        }
        render();
        return true;
    }

    private void appendDigit(int digit) {
        if (prefs.lockoutRemainingSeconds() > 0L || openingConsole) {
            return;
        }
        if (input.length() < Prefs.PASSWORD_LENGTH) {
            input.append((char) ('0' + digit));
        }
        if (input.length() == Prefs.PASSWORD_LENGTH) {
            submit();
        }
    }

    private void deleteDigit() {
        if (prefs.lockoutRemainingSeconds() == 0L && input.length() > 0) {
            input.setLength(input.length() - 1);
        }
    }

    private void submit() {
        if (prefs.lockoutRemainingSeconds() > 0L || openingConsole) {
            return;
        }
        if (!validateSetupAuthorization()) return;
        if (input.length() != Prefs.PASSWORD_LENGTH) {
            error = input.length() == 0 ? "" : "密码未输完整";
            return;
        }
        String value = input.toString();
        input.setLength(0);
        if (stage == CHECK) {
            Prefs.AuthResult result = prefs.authenticate(value);
            if (result != Prefs.AuthResult.SUCCESS) {
                error = "密码不正确";
                return;
            }
            AdminSession.grant();
            if (prefs.needsPasswordSetup()) {
                stage = NEW_PIN;
                authorizedSetup = true;
                error = "";
            } else {
                GuardService.releaseAfterParentPin(this);
                openConsole();
            }
        } else if (!AdminSession.isActive()) {
            stage = CHECK;
            error = "授权已过期，请重新验证";
        } else if (stage == NEW_PIN) {
            if (Prefs.DEFAULT_PASSWORD.equals(value)) {
                error = "不能使用出厂密码";
                return;
            }
            firstPin = value;
            stage = CONFIRM_PIN;
            error = "";
        } else if (!value.equals(firstPin)) {
            firstPin = null;
            stage = NEW_PIN;
            error = "两次输入不一致";
        } else {
            try {
                prefs.setPassword(value);
                authorizedSetup = false;
                firstPin = null;
                openConsole();
            } catch (IllegalStateException e) {
                GuardService.reportFailure("家长密码保存失败", e);
                stage = NEW_PIN;
                error = "密码保存失败";
            }
        }
    }

    private void openConsole() {
        openingConsole = true;
        // Keep the parent lease alive across the handover: the gate finishes immediately, and
        // without the lease the guard sees "no parent UI" for a moment and pulls the quota lock
        // back over the freshly opened panel (the "asked for the password twice" bug).
        GuardService service = GuardService.get();
        if (service != null) {
            service.setParentUiVisible(true);
        }
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private void render() {
        if (title == null) {
            return;
        }
        title.setText(stage != CHECK ? "设置家长密码"
                : prefs.isGuardActive() ? "今日电视时长已用完" : "家长验证");
        long wait = prefs.lockoutRemainingSeconds();
        if (wait > 0L) {
            input.setLength(0);
            prompt.setText("错误次数过多，请等待 " + wait + " 秒");
        } else {
            String message = stage == NEW_PIN ? "请输入新的 6 位家长密码"
                    : stage == CONFIRM_PIN ? "请再次输入新密码确认"
                    : prefs.needsPasswordSetup() ? "请输入原密码后设置新密码\n首次安装请用说明书中的出厂密码"
                    : "请输入 6 位家长密码";
            prompt.setText(error.isEmpty() ? message : error + "\n" + message);
        }
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            dots.append('●');
        }
        masked.setText(dots);
        keypad.setEnabled(wait == 0L && !openingConsole);
        inputMode.syncRequestVisibility();
        android.view.View focused = getWindow().getDecorView().findFocus();
        if (wait == 0L && !openingConsole && inputMode.usesOnScreen()
                && !(focused instanceof android.widget.Button)) {
            keypad.focusFirstKey();
        }
    }
}
