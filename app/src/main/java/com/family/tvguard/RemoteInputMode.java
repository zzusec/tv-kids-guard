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
 * <p>The hardware decides, and since 2.5.13 it decides alone: every non-virtual keyboard or D-pad
 * device is asked which digits it reports, and the pad appears only when those devices cannot
 * cover 0-9 between them. The 「使用屏幕数字盘」 switch that used to sit under the entry was
 * removed on purpose, so nothing is stored about a parent's preference any more — a digit that
 * actually arrives from a real device still retires a pad that an under-reporting key map left up.
 */
final class RemoteInputMode {
    private static final String STORE = "tvguard_remote";
    /** The remote really produced a digit key, whatever its declared key map says. */
    private static final String LEARNED_DIGITS = "physical_digits_seen";
    /** Written by 2.5.12 and earlier, whose manual switch is gone; dropped on first read. */
    private static final String LEGACY_SHOW_PAD = "show_keypad";
    private static final String LEGACY_PAD_PIN = "pad_pinned";
    /** Every decimal digit reported by at least one real device, one bit per digit. */
    private static final int ALL_DIGITS_MASK = 0x3FF;
    private final Context context;
    private final View primary;
    private final RemoteKeypad.NumericPad keypad;
    private final Runnable changed;
    private boolean onScreen;

    RemoteInputMode(Context context, View primary, RemoteKeypad.NumericPad keypad, Runnable changed) {
        this.context = context;
        this.primary = primary;
        this.keypad = keypad;
        this.changed = changed;
        SharedPreferences prefs = store();
        // A pinned pad used to be a deliberate parent choice. With no way to make that choice any
        // more, a leftover pin would keep drawing a pad on a remote that can type digits, so the
        // stored switches are dropped and the hardware answers the question again.
        if (prefs.contains(LEGACY_SHOW_PAD) || prefs.contains(LEGACY_PAD_PIN)) {
            prefs.edit().remove(LEGACY_SHOW_PAD).remove(LEGACY_PAD_PIN).apply();
        }
        onScreen = readChoice();
        keypad.setUpperFocusTarget(primary);
        apply();
    }

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
        if (store().getBoolean(LEARNED_DIGITS, false)) {
            return false;
        }
        return !hasPhysicalDigits();
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
        }
        learnFromPhysicalDigit(event);
    }

    private void learnFromPhysicalDigit(KeyEvent event) {
        if (!isPhysicalSource(event)) {
            return;
        }
        SharedPreferences prefs = store();
        if (prefs.getBoolean(LEARNED_DIGITS, false)) {
            return;
        }
        prefs.edit().putBoolean(LEARNED_DIGITS, true).apply();
        if (onScreen) {
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

    private void apply() {
        if (!(primary instanceof Button)) {
            primary.setFocusable(!onScreen);
            primary.setFocusableInTouchMode(!onScreen);
        }
        keypad.setVisibility(onScreen ? View.VISIBLE : View.GONE);
        if (!onScreen) keypad.usePhysicalInput();
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
}
