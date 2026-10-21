package com.family.tvguard;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

/** Input capability is a hint; a remote-reachable override handles inaccurate vendor key maps. */
final class RemoteInputMode {
    private static final String STORE = "tvguard_remote";
    private static final String SHOW_PAD = "show_keypad";
    private final Context context;
    private final View primary;
    private final RemoteKeypad.NumericPad keypad;
    private final Button toggle;
    private final Runnable changed;
    private boolean onScreen;
    private int armedKey;

    RemoteInputMode(Context context, View primary, RemoteKeypad.NumericPad keypad, Runnable changed) {
        this.context = context;
        this.primary = primary;
        this.keypad = keypad;
        this.changed = changed;
        onScreen = readChoice();
        toggle = new Button(context);
        toggle.setTag("keypad_toggle");
        toggle.setTextSize(16);
        toggle.setAllCaps(false);
        toggle.setGravity(Gravity.CENTER);
        toggle.setBackgroundResource(R.drawable.parent_button_bg);
        toggle.setTextColor(new android.content.res.ColorStateList(new int[][] {
                {android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}
        }, new int[] {Color.WHITE, Color.WHITE,
                context.getResources().getColor(R.color.panel_ink)}));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            toggle.setBackgroundTintList(null);
            toggle.setStateListAnimator(null);
        }
        toggle.setFocusable(true);
        toggle.setFocusableInTouchMode(true);
        toggle.setMinHeight(dp(48));
        toggle.setMinimumHeight(dp(48));
        toggle.setPadding(dp(12), 0, dp(12), 0);
        toggle.setOnClickListener(view -> switchMode());
        keypad.setUpperFocusTarget(toggle);
        apply();
    }

    Button toggle() { return toggle; }
    boolean usesOnScreen() { return onScreen; }

    void refresh() {
        boolean next = readChoice();
        if (next == onScreen) return;
        onScreen = next;
        apply();
        changed.run();
        focusEntry();
    }

    private boolean readChoice() {
        android.content.SharedPreferences prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        return prefs.contains(SHOW_PAD) ? prefs.getBoolean(SHOW_PAD, true) : !hasPhysicalDigits();
    }

    void focusEntry() {
        if (onScreen) keypad.focusFirstKey();
        else primary.requestFocus();
    }

    void onPhysicalDigit() {
        if (!onScreen) primary.requestFocus();
        else if (toggle.hasFocus()) keypad.focusFirstKey();
    }

    boolean handleKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (!RemoteKeypad.isSelectKey(code)) cancelSelect();
        if (toggle.hasFocus()) {
            if (RemoteKeypad.isNavigationKey(code)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (code == KeyEvent.KEYCODE_DPAD_DOWN && onScreen) keypad.focusFirstKey();
                    else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                        if (!primary.requestFocus()) keypad.focusFirstKey();
                    }
                }
                return true;
            }
            if (RemoteKeypad.isSelectKey(code)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    armedKey = code;
                    toggle.setPressed(true);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    boolean activate = armedKey == code && !event.isCanceled();
                    cancelSelect();
                    if (activate) toggle.performClick();
                }
                return true;
            }
        }
        if (!onScreen && primary.hasFocus()
                && (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) toggle.requestFocus();
            return true;
        }
        return false;
    }

    private void switchMode() {
        boolean next = !onScreen;
        if (!context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                .putBoolean(SHOW_PAD, next).commit()) {
            GuardService.reportFailure("遥控器输入方式保存失败", null);
            Toast.makeText(context, "输入方式保存失败，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        onScreen = next;
        apply();
        changed.run();
        focusEntry();
    }

    private void apply() {
        toggle.setText(onScreen ? "使用遥控器数字键" : "使用屏幕数字盘");
        if (!(primary instanceof Button)) {
            primary.setFocusable(!onScreen);
            primary.setFocusableInTouchMode(!onScreen);
        }
        keypad.setVisibility(onScreen ? View.VISIBLE : View.GONE);
        if (!onScreen) keypad.usePhysicalInput();
    }

    private void cancelSelect() {
        armedKey = 0;
        toggle.setPressed(false);
    }

    static boolean hasPhysicalDigits() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) continue;
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_KEYBOARD) != InputDevice.SOURCE_KEYBOARD
                    && (sources & InputDevice.SOURCE_DPAD) != InputDevice.SOURCE_DPAD) continue;
            if (allDigits(device)) return true;
        }
        return false;
    }

    private static boolean allDigits(InputDevice device) {
        int[] codes = new int[20];
        for (int i = 0; i < 10; i++) {
            codes[i] = KeyEvent.KEYCODE_0 + i;
            codes[i + 10] = KeyEvent.KEYCODE_NUMPAD_0 + i;
        }
        boolean[] presentKeys = device.hasKeys(codes);
        if (presentKeys.length != codes.length) return false;
        for (int i = 0; i < 10; i++) if (!presentKeys[i] && !presentKeys[i + 10]) return false;
        return true;
    }

    private int dp(int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }
}
