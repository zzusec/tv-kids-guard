package com.family.tvguard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.WindowManager;
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
    private int stage;
    private String firstPin;
    private String error = "";
    private boolean visible;
    private boolean openingConsole;
    private boolean authorizedSetup;

    static void launch(Context ctx) {
        Intent intent = new Intent(ctx, AdminAuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            ctx.startActivity(intent);
        } catch (Exception e) {
            GuardService.reportFailure("家长认证界面启动失败", e);
        }
    }

    static boolean isVisible() {
        return instance != null && instance.visible;
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (authorizedSetup && !AdminSession.isActive()) {
                stage = CHECK;
                firstPin = null;
                input.setLength(0);
                authorizedSetup = false;
                error = "授权已过期，请重新验证";
            }
            GuardService service = GuardService.get();
            if (service != null) {
                service.setParentUiVisible(true);
            }
            render();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        prefs = new Prefs(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.gate_screen);
        title = findViewById(R.id.lock_title);
        prompt = findViewById(R.id.lock_prompt);
        masked = findViewById(R.id.lock_input);
        render();
        GuardService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        if (AdminSession.isActive() && !prefs.needsPasswordSetup()) {
            openConsole();
            return;
        }
        if (AdminSession.isActive() && prefs.needsPasswordSetup()) {
            stage = NEW_PIN;
            authorizedSetup = true;
        }
        GuardService service = GuardService.get();
        if (service != null) {
            service.setParentUiVisible(true);
        }
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        visible = false;
        handler.removeCallbacks(ticker);
        GuardService service = GuardService.get();
        // The lease follows the visible parent UI. The panel (or the OTA install confirmation
        // opened from it) re-asserts it, so holding it here would leave the shield suspended
        // over the home screen after a system dialog.
        if (service != null) {
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
        handler.removeCallbacksAndMessages(null);
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
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
        if (PinDialog.isVolumeKey(code) || code == KeyEvent.KEYCODE_POWER) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return true;
        }
        int digit = PinDialog.digitOf(code);
        if (code == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
        } else if (digit >= 0) {
            appendDigit(digit);
        } else if (code == KeyEvent.KEYCODE_DEL) {
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
        if (input.length() != Prefs.PASSWORD_LENGTH) {
            error = "请输入 6 位数字密码";
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
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private void render() {
        if (title == null) {
            return;
        }
        title.setText(stage == CHECK ? "家长验证" : "设置家长密码");
        long wait = prefs.lockoutRemainingSeconds();
        if (wait > 0L) {
            input.setLength(0);
            prompt.setText("错误次数过多，请等待 " + wait + " 秒");
        } else {
            String message = stage == NEW_PIN ? "请输入新的 6 位数字密码"
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
    }
}
