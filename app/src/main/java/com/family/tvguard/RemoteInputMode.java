package com.family.tvguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;

/**
 * Picks between the remote's own number keys and the on-screen pad.
 *
 * <p>A remote that can type digits never sees the pad: the hardware decides, and since 2.5.13
 * nothing can override it — the 「使用屏幕数字盘」 switch is gone, and so is the stored choice
 * behind it. What the hardware cannot answer is a different case. When no device reports a
 * complete 0–9, the pad used to take over the whole screen even on televisions whose remote could
 * type; since 2.5.14 an unproven remote keeps the clean layout and offers a small number-pad icon
 * instead. Pressing it opens the pad for this screen, and the first digit that really arrives from
 * a device retires the icon for good. While the pad itself cannot take input — a cooldown, or the
 * service coming back — the icon is put away with it, because an icon for a dead pad is a trap.
 */
final class RemoteInputMode {
    private static final String STORE = "tvguard_remote";
    /** The remote really produced a digit key, whatever its declared key map says. */
    private static final String LEARNED_DIGITS = "physical_digits_seen";
    /** Written by 2.5.13 and earlier, whose manual switch is gone; dropped on first read. */
    private static final String LEGACY_SHOW_PAD = "show_keypad";
    private static final String LEGACY_PAD_PIN = "pad_pinned";
    /** Every decimal digit reported by at least one real device, one bit per digit. */
    private static final int ALL_DIGITS_MASK = 0x3FF;
    private final Context context;
    private final View primary;
    private final RemoteKeypad.NumericPad keypad;
    private final Button request;
    private final Runnable changed;
    /** The remote is known to type digits, by its own report or by a key that really arrived. */
    private boolean digitsKnown;
    /** The parent opened the pad on this screen with the icon. Never remembered. */
    private boolean padRequested;
    private int armedSelectKey;

    RemoteInputMode(Context context, View primary, RemoteKeypad.NumericPad keypad, Runnable changed) {
        this.context = context;
        this.primary = primary;
        this.keypad = keypad;
        this.changed = changed;
        SharedPreferences prefs = store();
        // A pinned pad used to be a deliberate parent choice. With no switch to make it any more,
        // a leftover pin would keep drawing a pad on a remote that can type digits, so the stored
        // switches are dropped and the hardware answers the question again.
        if (prefs.contains(LEGACY_SHOW_PAD) || prefs.contains(LEGACY_PAD_PIN)) {
            prefs.edit().remove(LEGACY_SHOW_PAD).remove(LEGACY_PAD_PIN).apply();
        }
        digitsKnown = prefs.getBoolean(LEARNED_DIGITS, false) || hasPhysicalDigits();
        request = RemoteKeypad.padRequestButton(context);
        request.setOnClickListener(view -> openPad());
        keypad.setUpperFocusTarget(primary);
        apply();
    }

    Button requestButton() { return request; }

    boolean usesOnScreen() { return !digitsKnown && padRequested; }

    /** True while the icon is the only hint that a pad exists at all. */
    boolean showsRequest() { return !digitsKnown && !padRequested && keypad.isEnabled(); }

    /**
     * Called by a screen right after it enables or disables the pad. A pad that cannot take input
     * has nothing to offer, so during a cooldown or while the service recovers the icon is put away
     * with it and the remote keeps the single entry it had before the pad existed.
     */
    void syncRequestVisibility() {
        boolean show = showsRequest();
        if (!show && request.hasFocus()) primary.requestFocus();
        request.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    void refresh() {
        boolean next = store().getBoolean(LEARNED_DIGITS, false) || hasPhysicalDigits();
        if (next == digitsKnown) return;
        digitsKnown = next;
        padRequested = false;
        apply();
        changed.run();
        focusEntry();
    }

    void focusEntry() {
        if (usesOnScreen()) keypad.focusFirstKey();
        else primary.requestFocus();
    }

    /**
     * A digit key just arrived. When it came from a real device it settles the question the vendor
     * key map could not answer, so the pad and its icon are retired from now on; a synthetic key
     * event — the on-screen pad itself, or an unknown device — teaches nothing.
     */
    void onPhysicalDigit(KeyEvent event) {
        if (!usesOnScreen()) {
            primary.requestFocus();
        }
        learnFromPhysicalDigit(event);
    }

    private void learnFromPhysicalDigit(KeyEvent event) {
        if (!isPhysicalSource(event)) {
            return;
        }
        SharedPreferences prefs = store();
        if (!prefs.getBoolean(LEARNED_DIGITS, false)) {
            prefs.edit().putBoolean(LEARNED_DIGITS, true).apply();
        }
        if (digitsKnown) {
            return;
        }
        digitsKnown = true;
        padRequested = false;
        apply();
        changed.run();
        focusEntry();
    }

    private void openPad() {
        // A remote that can type digits never gets the pad, and a pad that cannot take input is
        // not offered even to the icon that would open it.
        if (digitsKnown || !keypad.isEnabled()) return;
        padRequested = true;
        apply();
        changed.run();
        keypad.focusFirstKey();
    }

    private SharedPreferences store() {
        return context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
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

    /**
     * Routes the keys around the icon. It is armed on press and fired on release so a held OK does
     * not open the pad and then immediately move focus out of a screen the parent never chose.
     */
    boolean handleKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (!RemoteKeypad.isSelectKey(code)) cancelSelect();
        if (request.hasFocus()) {
            if (RemoteKeypad.isNavigationKey(code)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT)) {
                    primary.requestFocus();
                }
                return true;
            }
            if (RemoteKeypad.isSelectKey(code)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    armedSelectKey = code;
                    request.setPressed(true);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    boolean activate = armedSelectKey == code && !event.isCanceled();
                    cancelSelect();
                    if (activate) request.performClick();
                }
                return true;
            }
            return false;
        }
        if (showsRequest() && primary.hasFocus() && event.getAction() == KeyEvent.ACTION_DOWN
                && (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            request.requestFocus();
            return true;
        }
        return false;
    }

    private void cancelSelect() {
        if (armedSelectKey == 0) return;
        armedSelectKey = 0;
        request.setPressed(false);
    }

    private void apply() {
        if (!(primary instanceof Button)) {
            boolean padHidden = !usesOnScreen();
            primary.setFocusable(padHidden);
            primary.setFocusableInTouchMode(padHidden);
        }
        keypad.setVisibility(usesOnScreen() ? View.VISIBLE : View.GONE);
        syncRequestVisibility();
        if (!usesOnScreen()) keypad.usePhysicalInput();
    }

    static boolean hasPhysicalDigits() {
        int[] ids;
        try {
            ids = InputDevice.getDeviceIds();
        } catch (RuntimeException e) {
            // A vendor input service can fail this query. The gate must still open: an exception
            // here would crash the parent verification screen and leave the uninstall page
            // reachable without a password, so an unknown answer keeps the pad behind the icon.
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
}
