package com.family.tvguard;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowInputDevice;
import org.robolectric.shadows.ShadowLooper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33}, qualifiers = "w960dp-h540dp-land",
        shadows = RemoteInputModeTest.DigitInputDevice.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RemoteInputModeTest {
    private Application context;
    private SharedPreferences remotePrefs;
    private ActivityController<Activity> controller;
    private TextView primary;
    private RemoteKeypad.NumericPad keypad;
    private RemoteInputMode mode;
    private int changes;
    private int padActions;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        remotePrefs = context.getSharedPreferences("tvguard_remote", Context.MODE_PRIVATE);
        assertTrue(remotePrefs.edit().clear().commit());
        assertTrue(context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit());
        DigitInputDevice.reset();
    }

    @After
    public void tearDown() {
        closeControl();
        DigitInputDevice.reset();
    }

    @Test
    public void absentUnknownAndDisconnectedDevicesDefaultToShowingThePad() {
        assertFalse(RemoteInputMode.hasPhysicalDigits());
        createControl();
        assertMode(true);
        assertFalse(remotePrefs.contains("show_keypad"));

        DigitInputDevice.devices.put(91, null);
        assertFalse(RemoteInputMode.hasPhysicalDigits());
        DigitInputDevice.add(false, 0, digits(KeyEvent.KEYCODE_0, 0, 10));
        assertFalse(RemoteInputMode.hasPhysicalDigits());
        createControl();
        assertMode(true);
    }

    @Test
    public void virtualDevicesNeverSuppressThePadEvenWithAllNumberKeys() {
        DigitInputDevice.add(true, InputDevice.SOURCE_KEYBOARD,
                digits(KeyEvent.KEYCODE_0, 0, 10));
        DigitInputDevice.add(true, InputDevice.SOURCE_DPAD,
                digits(KeyEvent.KEYCODE_NUMPAD_0, 0, 10));
        assertFalse(RemoteInputMode.hasPhysicalDigits());
        createControl();
        assertMode(true);
    }

    @Test
    public void gamepadAndTouchSourcesAreNotPhysicalNumericRemotes() {
        for (int sources : new int[] {0, InputDevice.SOURCE_GAMEPAD,
                InputDevice.SOURCE_TOUCHSCREEN, InputDevice.SOURCE_JOYSTICK}) {
            DigitInputDevice.add(false, sources, digits(KeyEvent.KEYCODE_0, 0, 10));
        }
        assertFalse(RemoteInputMode.hasPhysicalDigits());
        createControl();
        assertMode(true);
    }

    @Test
    public void completeRegularDigitsOnKeyboardOrDpadDefaultToHidingThePad() {
        for (int sources : new int[] {InputDevice.SOURCE_KEYBOARD, InputDevice.SOURCE_DPAD}) {
            DigitInputDevice.reset();
            DigitInputDevice.add(false, sources, digits(KeyEvent.KEYCODE_0, 0, 10));
            assertTrue(RemoteInputMode.hasPhysicalDigits());
            createControl();
            assertMode(false);
            assertTrue(primary.hasFocus());
            assertFalse(remotePrefs.contains("show_keypad"));
        }
    }

    @Test
    public void numpadAndMixedKeysCanSupplyEveryDigitOnOneDevice() {
        DigitInputDevice.add(false, InputDevice.SOURCE_KEYBOARD,
                digits(KeyEvent.KEYCODE_NUMPAD_0, 0, 10));
        assertTrue(RemoteInputMode.hasPhysicalDigits());

        DigitInputDevice.reset();
        DigitInputDevice mixed = DigitInputDevice.add(false, InputDevice.SOURCE_DPAD,
                digits(KeyEvent.KEYCODE_0, 0, 5));
        mixed.addKeys(digits(KeyEvent.KEYCODE_NUMPAD_0, 5, 10));
        assertTrue(RemoteInputMode.hasPhysicalDigits());
        assertEquals(1, mixed.queries);
        int[] expected = new int[20];
        System.arraycopy(digits(KeyEvent.KEYCODE_0, 0, 10), 0, expected, 0, 10);
        System.arraycopy(digits(KeyEvent.KEYCODE_NUMPAD_0, 0, 10), 0, expected, 10, 10);
        assertArrayEquals(expected, mixed.lastQuery);
        createControl();
        assertMode(false);
    }

    @Test
    public void partialDevicesCombineTheirKeysToSuppressThePad() {
        DigitInputDevice first = DigitInputDevice.add(false, InputDevice.SOURCE_KEYBOARD,
                digits(KeyEvent.KEYCODE_0, 0, 5));
        DigitInputDevice second = DigitInputDevice.add(false, InputDevice.SOURCE_DPAD,
                digits(KeyEvent.KEYCODE_NUMPAD_0, 5, 10));
        assertTrue("The union of two partial key maps covers 0-9",
                RemoteInputMode.hasPhysicalDigits());
        assertEquals(1, first.queries);
        assertEquals(1, second.queries);
        createControl();
        assertMode(false);
        assertTrue(primary.hasFocus());
    }

    @Test
    public void devicesAreNotAskedOnceTheUnionAlreadyCoversEveryDigit() {
        DigitInputDevice first = DigitInputDevice.add(false, InputDevice.SOURCE_KEYBOARD,
                digits(KeyEvent.KEYCODE_0, 0, 10));
        DigitInputDevice later = DigitInputDevice.add(false, InputDevice.SOURCE_DPAD,
                digits(KeyEvent.KEYCODE_NUMPAD_0, 0, 10));
        assertTrue(RemoteInputMode.hasPhysicalDigits());
        assertEquals(1, first.queries);
        assertEquals("A complete union must not keep querying the remaining devices",
                0, later.queries);
    }

    @Test
    public void aUnionStillMissingOneDigitLeavesThePadUp() {
        DigitInputDevice.add(false, InputDevice.SOURCE_KEYBOARD,
                digits(KeyEvent.KEYCODE_0, 0, 5));
        DigitInputDevice.add(false, InputDevice.SOURCE_DPAD,
                digits(KeyEvent.KEYCODE_NUMPAD_0, 5, 9));
        assertFalse(RemoteInputMode.hasPhysicalDigits());
        createControl();
        assertMode(true);
    }

    @Test
    public void missingOneDigitAndMalformedCapabilityResultsRemainUnknown() {
        DigitInputDevice device = DigitInputDevice.add(false, InputDevice.SOURCE_KEYBOARD,
                digits(KeyEvent.KEYCODE_0, 0, 9));
        assertFalse(RemoteInputMode.hasPhysicalDigits());
        device.addKeys(KeyEvent.KEYCODE_9);
        for (int length : new int[] {0, 19, 21}) {
            device.responseLength = length;
            assertFalse("Malformed capability length " + length,
                    RemoteInputMode.hasPhysicalDigits());
            createControl();
            assertMode(true);
        }
    }

    @Test
    public void anOldBuildSPadPreferenceNoLongerForcesThePad() {
        DigitInputDevice.add(false, InputDevice.SOURCE_DPAD,
                digits(KeyEvent.KEYCODE_0, 0, 10));
        assertTrue(remotePrefs.edit().putBoolean("show_keypad", true)
                .putBoolean("pad_pinned", true).commit());
        createControl();
        assertMode(false);
        assertFalse("The removed switch must not leave a stored choice behind",
                remotePrefs.contains("show_keypad"));
        assertFalse(remotePrefs.contains("pad_pinned"));
    }

    @Test
    public void nothingOnTheRemoteReopensThePadOnceTheHardwareHasDigits() {
        DigitInputDevice.add(false, InputDevice.SOURCE_DPAD,
                digits(KeyEvent.KEYCODE_0, 0, 10));
        createControl();
        assertMode(false);
        for (int code : new int[] {KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT}) {
            press(code);
            press(code);
        }
        assertMode(false);
        assertEquals(0, changes);
        assertEquals(0, padActions);
        assertNull("The 2.5.12 switch button is gone", findToggle());
    }

    @Test
    public void aDigitFromARealDeviceRetiresThePadEvenWhenItsKeyMapDeniedDigits() {
        DigitInputDevice remote = DigitInputDevice.add(false, InputDevice.SOURCE_DPAD,
                digits(KeyEvent.KEYCODE_0, 0, 5));
        assertFalse("A five-digit map cannot suppress the pad", RemoteInputMode.hasPhysicalDigits());
        createControl();
        assertMode(true);

        physicalDigit(remote.id, KeyEvent.KEYCODE_7);
        assertMode(false);
        assertTrue(primary.hasFocus());
        assertEquals(1, changes);
        assertTrue(remotePrefs.getBoolean("physical_digits_seen", false));

        createControl();
        assertMode(false);
        assertTrue(primary.hasFocus());
    }

    @Test
    public void syntheticAndVirtualDigitsTeachNothing() {
        createControl();
        assertMode(true);

        physicalDigit(-1, KeyEvent.KEYCODE_7);
        assertMode(true);
        assertEquals(0, changes);
        assertFalse(remotePrefs.contains("physical_digits_seen"));

        DigitInputDevice virtual = DigitInputDevice.add(true, InputDevice.SOURCE_KEYBOARD,
                digits(KeyEvent.KEYCODE_0, 0, 10));
        physicalDigit(virtual.id, KeyEvent.KEYCODE_7);
        assertMode(true);
        assertEquals(0, changes);
        assertFalse("A virtual device proves nothing about the remote",
                remotePrefs.contains("physical_digits_seen"));
    }

    @Test
    public void learningDigitsLeavesGuardSettingsAndAuthorizationAlone() {
        Prefs prefs = new Prefs(context);
        prefs.setPassword("246813");
        prefs.setDailyLimitMinutes(30);
        Map<String, ?> before = new HashMap<>(context
                .getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).getAll());
        DigitInputDevice remote = DigitInputDevice.add(false, InputDevice.SOURCE_DPAD,
                digits(KeyEvent.KEYCODE_0, 0, 4));
        createControl();
        assertMode(true);
        physicalDigit(remote.id, KeyEvent.KEYCODE_NUMPAD_7);
        assertMode(false);
        assertEquals(before, context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE)
                .getAll());
        assertFalse(AdminSession.isActive());
        assertEquals(0, padActions);
    }

    @Test
    public void navigatingThePadLeavesInputGuardSettingsAndAuthorizationUntouched() {
        Prefs prefs = new Prefs(context);
        prefs.setPassword("135790");
        prefs.setDailyLimitMinutes(45);
        prefs.setUsedSeconds(120L);
        Map<String, ?> before = new HashMap<>(context
                .getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).getAll());
        assertFalse(AdminSession.isActive());
        createControl();
        assertMode(true);
        assertTrue("The pad keeps navigation keys while it is up",
                send(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0, 0));
        send(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, 0);
        assertEquals("key_1", focusedTag());
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("key_4", focusedTag());
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        assertMode(true);
        assertEquals(0, changes);
        assertEquals("one pad key press reaches the screen", 1, padActions);
        assertEquals("1234", primary.getText().toString());
        assertEquals(before, context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE)
                .getAll());
        assertFalse(AdminSession.isActive());
    }

    private void createControl() {
        closeControl();
        changes = 0;
        padActions = 0;
        controller = Robolectric.buildActivity(Activity.class).create().start().resume();
        Activity activity = controller.get();
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        primary = new TextView(activity);
        primary.setText("1234");
        root.addView(primary);
        keypad = new RemoteKeypad.NumericPad(activity, new RemoteKeypad.NumericListener() {
            @Override public void onDigit(int digit) { padActions++; }
            @Override public void onDelete() { padActions++; }
            @Override public void onConfirm() { padActions++; }
        });
        mode = new RemoteInputMode(activity, primary, keypad, () -> changes++);
        root.addView(keypad);
        activity.setContentView(root);
        controller.visible().windowFocusChanged(true);
        ShadowLooper.idleMainLooper();
        mode.focusEntry();
    }

    private View findToggle() {
        return controller.get().getWindow().getDecorView().findViewWithTag("keypad_toggle");
    }

    private void closeControl() {
        if (controller != null) {
            controller.pause().stop().destroy();
            controller = null;
        }
    }

    private String focusedTag() {
        View focused = controller.get().getWindow().getDecorView().findFocus();
        assertNotNull(focused);
        return String.valueOf(focused.getTag());
    }

    private void assertMode(boolean shown) {
        assertEquals(shown, mode.usesOnScreen());
        assertEquals(shown ? View.VISIBLE : View.GONE, keypad.getVisibility());
    }

    private void press(int code) {
        press(-1, code);
    }

    private void press(int deviceId, int code) {
        send(KeyEvent.ACTION_DOWN, code, 0, 0, deviceId);
        send(KeyEvent.ACTION_UP, code, 0, 0, deviceId);
    }

    private boolean send(int action, int code, int repeat, int flags) {
        return send(action, code, repeat, flags, -1);
    }

    /** The screens hand every key to the pad first, so that is the only route under test. */
    private boolean send(int action, int code, int repeat, int flags, int deviceId) {
        KeyEvent event = new KeyEvent(0L, 0L, action, code, repeat, 0, deviceId, 0, flags);
        boolean consumed = keypad.handleKeyEvent(event);
        ShadowLooper.idleMainLooper();
        return consumed;
    }

    /** The screens call this when a digit keycode arrives; the test drives it the same way. */
    private void physicalDigit(int deviceId, int code) {
        KeyEvent event = new KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, 0, 0, deviceId, 0, 0);
        mode.onPhysicalDigit(event);
        ShadowLooper.idleMainLooper();
    }

    static int[] digits(int firstCode, int from, int until) {
        int[] keys = new int[until - from];
        for (int i = from; i < until; i++) keys[i - from] = firstCode + i;
        return keys;
    }

    /** Robolectric's built-in InputDevice shadow does not model per-device key capabilities. */
    @Implements(InputDevice.class)
    public static class DigitInputDevice extends ShadowInputDevice {
        private static final Map<Integer, InputDevice> devices = new LinkedHashMap<>();
        private final Set<Integer> supportedKeys = new HashSet<>();
        private int id;
        private boolean virtual;
        private int sources;
        private int queries;
        private int[] lastQuery;
        private int responseLength = -1;

        static DigitInputDevice add(boolean virtual, int sources, int... keys) {
            InputDevice device = ShadowInputDevice.makeInputDeviceNamed("Test remote");
            DigitInputDevice shadow = Shadow.extract(device);
            shadow.virtual = virtual;
            shadow.sources = sources;
            shadow.addKeys(keys);
            shadow.id = devices.size() + 1;
            devices.put(shadow.id, device);
            return shadow;
        }

        void addKeys(int... keys) {
            for (int key : keys) supportedKeys.add(key);
        }

        @Implementation
        protected static int[] getDeviceIds() {
            int[] ids = new int[devices.size()];
            int i = 0;
            for (int id : devices.keySet()) ids[i++] = id;
            return ids;
        }

        @Implementation
        protected static InputDevice getDevice(int id) {
            return devices.get(id);
        }

        @Implementation
        protected boolean isVirtual() { return virtual; }

        @Implementation
        protected int getSources() { return sources; }

        @Implementation
        protected boolean[] hasKeys(int... keys) {
            queries++;
            lastQuery = keys.clone();
            boolean[] result = new boolean[responseLength < 0 ? keys.length : responseLength];
            for (int i = 0; i < result.length && i < keys.length; i++) {
                result[i] = supportedKeys.contains(keys[i]);
            }
            return result;
        }

        @Resetter
        public static void reset() { devices.clear(); }
    }
}
