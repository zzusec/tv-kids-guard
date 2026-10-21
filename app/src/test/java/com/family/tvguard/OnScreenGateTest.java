package com.family.tvguard;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.View;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33}, qualifiers = "w960dp-h540dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
public class OnScreenGateTest {
    private Prefs prefs;
    private ActivityController<AdminAuthActivity> controller;
    private AdminAuthActivity activity;

    @Before
    public void setUp() {
        Application context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setUsedSeconds(1800L);
        prefs.setLocked(true);
        AdminSession.revoke();
    }

    @After
    public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
        AdminSession.revoke();
    }

    @Test
    public void directionsEditMaskedInputAndPartialConfirmDoesNotAuthenticate() {
        prefs.setPassword("135790");
        startGate();
        assertEquals("key_1", activity.getWindow().getDecorView().findFocus().getTag());
        ScreenRemote.digits(activity, "13");
        assertEquals("●●", input());
        ScreenRemote.select(activity, "delete");
        assertEquals("●", input());
        ScreenRemote.select(activity, "confirm");
        assertEquals("●", input());
        assertEquals(0, prefs.getFailCount());
        assertFalse(AdminSession.isActive());
        assertFalse(prefs.isUnlockedToday());
        assertTrue(prefs.isGuardActive());
    }

    @Test
    public void virtualOkActivatesOnReleaseOnlyAndRejectsCanceledPresses() {
        prefs.setPassword("135790");
        startGate();
        ScreenRemote.send(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0, 0);
        assertEquals("", input());
        ScreenRemote.send(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 2, 0);
        ScreenRemote.send(activity, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0, 0);
        assertEquals("●", input());
        ScreenRemote.send(activity, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0, 0);
        assertEquals("●", input());
        ScreenRemote.send(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0, 0);
        ScreenRemote.send(activity, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0,
                KeyEvent.FLAG_CANCELED);
        assertEquals("●", input());
    }

    @Test
    public void factoryPinAndReplacementConfirmationWorkWithoutPhysicalDigits() {
        startGate();
        ScreenRemote.digits(activity, Prefs.DEFAULT_PASSWORD);
        assertTrue(AdminSession.isActive());
        assertTrue(prefs.needsPasswordSetup());
        assertFalse(prefs.isUnlockedToday());
        ScreenRemote.digits(activity, "135790");
        assertTrue(((TextView) activity.findViewById(R.id.lock_prompt)).getText()
                .toString().contains("再次输入"));
        ScreenRemote.digits(activity, "135791");
        assertTrue(prefs.needsPasswordSetup());
        assertFalse(activity.isFinishing());
        ScreenRemote.digits(activity, "135790");
        ScreenRemote.digits(activity, "135790");
        assertFalse(prefs.needsPasswordSetup());
        assertTrue(prefs.checkPassword("135790"));
        assertTrue(activity.isFinishing());
        assertEquals(MainActivity.class.getName(), shadowOf(activity).getNextStartedActivity()
                .getComponent().getClassName());
        assertFalse(prefs.isUnlockedToday());
        assertTrue(prefs.isGuardActive());
    }

    @Test
    public void cooldownDisablesEveryKeyAndRestoresDpadEntryAfterExpiry() {
        prefs.setPassword("135790");
        startGate();
        for (int i = 0; i < 3; i++) ScreenRemote.digits(activity, "222222");
        assertEquals(3, prefs.getFailCount());
        ScreenRemote.assertKeysEnabled(activity, false);
        ScreenRemote.press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        ScreenRemote.press(activity, KeyEvent.KEYCODE_1);
        assertEquals("", input());
        assertFalse(AdminSession.isActive());
        ScreenRemote.expireCooldown(prefs);
        ScreenRemote.assertKeysEnabled(activity, true);
        assertNotNull(activity.getWindow().getDecorView().findFocus());
        ScreenRemote.press(activity, KeyEvent.KEYCODE_DPAD_UP);
        ScreenRemote.digits(activity, "135790");
        assertTrue(AdminSession.isActive());
        assertFalse(prefs.isUnlockedToday());
    }

    @Test
    public void physicalLeftStillDeletesAndDirectionReturnsToVirtualEntry() {
        prefs.setPassword("135790");
        startGate();
        ScreenRemote.press(activity, KeyEvent.KEYCODE_7);
        ScreenRemote.press(activity, KeyEvent.KEYCODE_7);
        ScreenRemote.press(activity, KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals("●", input());
        ScreenRemote.press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals("●", input());
        assertEquals(0, prefs.getFailCount());
        ScreenRemote.press(activity, KeyEvent.KEYCODE_DPAD_UP);
        ScreenRemote.select(activity, "3");
        assertEquals("●●", input());
    }

    @Test
    public void expiredSetupAuthorizationCannotSaveAVirtualPin() {
        startGate();
        ScreenRemote.digits(activity, Prefs.DEFAULT_PASSWORD);
        assertTrue(AdminSession.isActive());
        AdminSession.revoke();
        ScreenRemote.digits(activity, "135790");
        assertTrue(prefs.needsPasswordSetup());
        assertFalse(activity.isFinishing());
        assertFalse(prefs.isUnlockedToday());
        assertFalse(AdminSession.isActive());
    }

    private void startGate() {
        controller = Robolectric.buildActivity(AdminAuthActivity.class).create().start().resume()
                .visible().windowFocusChanged(true);
        activity = controller.get();
        ShadowLooper.idleMainLooper();
    }

    private String input() {
        return ((TextView) activity.findViewById(R.id.lock_input)).getText().toString();
    }
}

/** Send complete remote events through ViewRoot, including KitKat's unconnected-IME precondition. */
final class ScreenRemote {
    private ScreenRemote() {}

    static void press(Activity activity, int code) {
        send(activity, KeyEvent.ACTION_DOWN, code, 0, 0);
        send(activity, KeyEvent.ACTION_UP, code, 0, 0);
    }

    static void send(Activity activity, int action, int code, int repeat, int flags) {
        Object root = ReflectionHelpers.callInstanceMethod(activity.getWindow().getDecorView(),
                "getViewRootImpl");
        assertNotNull(root);
        if (RuntimeEnvironment.getApiLevel() == 19) {
            ReflectionHelpers.setField(root, "mLastWasImTarget", false);
        }
        long now = SystemClock.uptimeMillis();
        ReflectionHelpers.callInstanceMethod(root, "dispatchInputEvent",
                ReflectionHelpers.ClassParameter.from(InputEvent.class,
                        new KeyEvent(now, now, action, code, repeat, 0, -1, 0, flags)));
        ShadowLooper.idleMainLooper();
    }

    static void digits(Activity activity, String value) {
        for (char digit : value.toCharArray()) select(activity, String.valueOf(digit));
    }

    static void select(Activity activity, String suffix) {
        focus(activity, suffix);
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
    }

    static void focus(Activity activity, String suffix) {
        View focused = activity.getWindow().getDecorView().findFocus();
        assertNotNull("Remote entry requires an initial focus", focused);
        if ("parent_manage".equals(focused.getTag())) {
            press(activity, KeyEvent.KEYCODE_DPAD_DOWN);
            focused = activity.getWindow().getDecorView().findFocus();
        }
        int from = index(focused.getTag().toString());
        int target = index("key_" + suffix);
        int row = from / 3;
        int col = from % 3;
        while (row != target / 3) {
            press(activity, row < target / 3 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
            row += row < target / 3 ? 1 : -1;
        }
        while (col != target % 3) {
            press(activity, col < target % 3 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
            col += col < target % 3 ? 1 : -1;
        }
        assertEquals("key_" + suffix, activity.getWindow().getDecorView().findFocus().getTag());
    }

    static void assertKeysEnabled(Activity activity, boolean enabled) {
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "delete", "0", "confirm"};
        for (String key : keys) {
            View button = activity.getWindow().getDecorView().findViewWithTag("key_" + key);
            assertNotNull(button);
            assertEquals(key, enabled, button.isEnabled());
        }
    }

    static void expireCooldown(Prefs prefs) {
        assertTrue(prefs.lockoutRemainingSeconds() > 0L);
        int failures = prefs.getFailCount();
        // PAUSED Looper advances uptime, not java.lang.System's wall clock used by Prefs.
        assertTrue(RuntimeEnvironment.getApplication()
                .getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit()
                .putLong("lockout_until", System.currentTimeMillis() - 1L).commit());
        assertEquals(0L, prefs.lockoutRemainingSeconds());
        assertEquals(failures, prefs.getFailCount());
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
    }

    private static int index(String tag) {
        if ("key_delete".equals(tag)) return 9;
        if ("key_0".equals(tag)) return 10;
        if ("key_confirm".equals(tag)) return 11;
        assertTrue("Unexpected focus: " + tag, tag.matches("key_[1-9]"));
        return Integer.parseInt(tag.substring(4)) - 1;
    }
}
