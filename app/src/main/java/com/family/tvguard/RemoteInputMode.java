package com.family.tvguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

/**
 * Picks between the remote's own number keys and the on-screen pad.
 *
 * <p>The default follows the hardware: every non-virtual keyboard or D-pad device is asked which
 * digits it reports, and the pad appears only when those devices cannot cover 0–9 between them.
 * Vendor key maps lie in both directions, so two manual routes stay open. A parent can always
 * switch to the pad, and a second explicit switch after the remote is known to type digits pins it
 * there. Conversely, a digit that actually arrives from a real device proves the remote has number
 * keys and retires a stale "show the pad" choice — the keymap never had to be believed for that.
 */
final class RemoteInputMode {
    private static final String STORE = "tvguard_remote";
    private static final String SHOW_PAD = "show_keypad";
    /** The remote really produced a digit key, whatever its declared key map says. */
    private static final String LEARNED_DIGITS = "physical_digits_seen";
    /** The parent asked for the pad a second time after the remote was known to type digits. */
    private static final String PAD_PIN = "pad_pinned";
    /** Every decimal digit reported by at least one real device, one bit per digit. */
    private static final int ALL_DIGITS_MASK = 0x3FF;
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
        SharedPreferences prefs = store();
        if (prefs.getBoolean(PAD_PIN, false)) {
            return true;
        }
        if (prefs.getBoolean(LEARNED_DIGITS, false)) {
            return false;
        }
        return prefs.contains(SHOW_PAD) ? prefs.getBoolean(SHOW_PAD, true) : !hasPhysicalDigits();
    }

    private SharedPreferences store() {
        return context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    void focusEntry() {
        if (onScreen) keypad.focusFirstKey();
        else primary.requestFocus();
    }

    /**
     * A digit key just arrived. When it came from a real device it settles the question the vendor
     * key map could not answer, so the pad is retired from now on; a synthetic key event — the
     * on-screen pad itself, or an unknown device — teaches nothing.
     */
    void onPhysicalDigit(KeyEvent event) {
        if (!onScreen) {
            primary.requestFocus();
        } else if (toggle.hasFocus()) {
            keypad.focusFirstKey();
        }
        learnFromPhysicalDigit(event);
    }

    private void learnFromPhysicalDigit(KeyEvent event) {
        if (!isPhysicalSource(event)) {
            return;
        }
        SharedPreferences prefs = store();
        boolean learned = prefs.getBoolean(LEARNED_DIGITS, false);
        boolean pinned = prefs.getBoolean(PAD_PIN, false);
        if (!learned) {
            prefs.edit().putBoolean(LEARNED_DIGITS, true).apply();
        }
        // A pad the parent pinned on purpose stays, even though the remote can type digits.
        if (!learned && !pinned && onScreen) {
            onScreen = false;
            apply();
            changed.run();
            focusEntry();
        }
    }

    /** True only for a key event carried by a real, non-virtual input device. */
    static boolean isPhysicalSource(KeyEvent event) {
        if (event == null) {
            return false;
        }
        try {
            InputDevice device = InputDevice.getDevice(event.getDeviceId());
            return device != null && !device.isVirtual();
        } catch (RuntimeException e) {
            return false;
        }
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
        SharedPreferences prefs = store();
        // Opening the pad while the remote is known to have number keys is a deliberate override,
        // so it pins; opening it because nothing else is available is just the default and must
        // not outlive the first digit the remote actually types.
        boolean digitsKnown = prefs.getBoolean(LEARNED_DIGITS, false) || hasPhysicalDigits();
        SharedPreferences.Editor editor = prefs.edit().putBoolean(SHOW_PAD, next);
        if (next && digitsKnown) {
            editor.putBoolean(PAD_PIN, true);
        } else {
            editor.remove(PAD_PIN);
        }
        if (!editor.commit()) {
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
        int[] ids;
        try {
            ids = InputDevice.getDeviceIds();
        } catch (RuntimeException e) {
            // A vendor input service can fail this query. The gate must still open: an exception
            // here would crash the parent verification screen and leave the uninstall page
            // reachable without a password, so an unknown answer falls back to the on-screen pad.
            return false;
        }
        if (ids == null) return false;
        // A TV can split its keys over several input devices — a remote profile for the DPAD, a
        // bluetooth HID profile for the keys — so no single map has to cover 0-9 on its own. The
        // digits are unioned and the devices stop being asked once that union is complete.
        int seen = 0;
        for (int id : ids) {
            InputDevice device;
            try {
                device = InputDevice.getDevice(id);
            } catch (RuntimeException e) {
                continue;
            }
            if (device == null || device.isVirtual()) continue;
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_KEYBOARD) != InputDevice.SOURCE_KEYBOARD
                    && (sources & InputDevice.SOURCE_DPAD) != InputDevice.SOURCE_DPAD) continue;
            seen |= digitMask(device);
            if (seen == ALL_DIGITS_MASK) return true;
        }
        return false;
    }

    /**
     * Which digits this device reports, counted under either {@code KEYCODE_0} or
     * {@code KEYCODE_NUMPAD_0}. A malformed or failed answer contributes nothing, because guessing
     * from a half-written capability list would hide the pad from a remote that cannot type.
     */
    private static int digitMask(InputDevice device) {
        int[] codes = new int[20];
        for (int i = 0; i < 10; i++) {
            codes[i] = KeyEvent.KEYCODE_0 + i;
            codes[i + 10] = KeyEvent.KEYCODE_NUMPAD_0 + i;
        }
        boolean[] presentKeys;
        try {
            presentKeys = device.hasKeys(codes);
        } catch (RuntimeException e) {
            return 0;
        }
        if (presentKeys == null || presentKeys.length != codes.length) return 0;
        int mask = 0;
        for (int i = 0; i < 10; i++) {
            if (presentKeys[i] || presentKeys[i + 10]) mask |= 1 << i;
        }
        return mask;
    }

    private int dp(int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }
}
