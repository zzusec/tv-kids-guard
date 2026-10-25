package com.family.tvguard;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33}, qualifiers = "w960dp-h540dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
public class OnScreenLockTest {
    private Prefs prefs;
    private ServiceController<GuardService> serviceController;
    private GuardService service;
    private ActivityController<LockActivity> controller;
    private LockActivity activity;

    @Before
    public void setUp() {
        Application context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword("135790");
        prefs.setUsedSeconds(1800L);
        prefs.setLocked(true);
        AdminSession.revoke();
        serviceController = Robolectric.buildService(GuardService.class).create();
        service = serviceController.get();
        controller = Robolectric.buildActivity(LockActivity.class).create().start().resume()
                .visible().windowFocusChanged(true);
        activity = controller.get();
        ShadowLooper.idleMainLooper();
        while (shadowOf(context).getNextStartedActivity() != null) {
            // The service asserts the quota lock on startup, before this remote interaction.
        }
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
        if (serviceController != null) serviceController.destroy();
        AdminSession.revoke();
    }

    @Test
    public void parentFocusAndPadHaveExplicitRoundTripNavigation() {
        assertEquals("parent_manage", activity.getWindow().getDecorView().findFocus().getTag());
        ScreenRemote.press(activity, KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("key_1", activity.getWindow().getDecorView().findFocus().getTag());
        ScreenRemote.press(activity, KeyEvent.KEYCODE_DPAD_UP);
        assertEquals("parent_manage", activity.getWindow().getDecorView().findFocus().getTag());
        ScreenRemote.press(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals("key_1", activity.getWindow().getDecorView().findFocus().getTag());
        assertLocked();
        assertFalse(AdminSession.isActive());
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void everyOkAliasOpensParentGateOnceOnReleaseWithoutUnlocking() {
        int[] aliases = {KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_SELECT};
        for (int alias : aliases) {
            ScreenRemote.send(activity, KeyEvent.ACTION_DOWN, alias, 0, 0);
            assertNull(shadowOf(activity).getNextStartedActivity());
            ScreenRemote.send(activity, KeyEvent.ACTION_DOWN, alias, 2, 0);
            ScreenRemote.send(activity, KeyEvent.ACTION_UP, alias, 0, 0);
            Intent gate = shadowOf(activity).getNextStartedActivity();
            assertNotNull("Alias " + alias, gate);
            assertEquals(AdminAuthActivity.class.getName(), gate.getComponent().getClassName());
            ScreenRemote.send(activity, KeyEvent.ACTION_UP, alias, 0, 0);
            assertNull(shadowOf(activity).getNextStartedActivity());
            assertLocked();
            assertFalse(AdminSession.isActive());
        }
    }

    @Test
    public void canceledParentOkDoesNotOpenGate() {
        ScreenRemote.send(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT, 0, 0);
        ScreenRemote.send(activity, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_SELECT, 0,
                KeyEvent.FLAG_CANCELED);
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertLocked();
    }

    @Test
    public void onScreenPinHandsOverToTheParentPanelAndKeepsTheQuotaLock() {
        int limit = prefs.getDailyLimitMinutes();
        ScreenRemote.digits(activity, "13579");
        assertEquals("●●●●●", input());
        assertLocked();
        ScreenRemote.digits(activity, "0");
        // Since 2.5.12 a correct PIN proves a parent is present; it buys no viewing time.
        assertTrue(service.isLocked());
        assertTrue(AdminSession.isActive());
        Intent panel = shadowOf(activity).getNextStartedActivity();
        assertNotNull(panel);
        assertEquals(MainActivity.class.getName(), panel.getComponent().getClassName());
        assertEquals(limit, prefs.getDailyLimitMinutes());
        assertEquals(1800L, prefs.getUsedSeconds());
    }

    @Test
    public void deleteAndIncompleteConfirmDoNotCountAsAuthenticationFailures() {
        prefs.registerFailedAttempt();
        ScreenRemote.digits(activity, "13");
        ScreenRemote.select(activity, "delete");
        assertEquals("1", service.lockInput());
        assertEquals("●", input());
        ScreenRemote.select(activity, "confirm");
        assertEquals("1", service.lockInput());
        assertEquals(1, prefs.getFailCount());
        assertLocked();
        ScreenRemote.press(activity, KeyEvent.KEYCODE_BACK);
        assertEquals("", service.lockInput());
        assertEquals("parent_manage", activity.getWindow().getDecorView().findFocus().getTag());
        assertLocked();
    }

    @Test
    public void wrongPinCooldownDisablesThePadButNotParentManagement() {
        for (int i = 0; i < 3; i++) ScreenRemote.digits(activity, "222222");
        assertEquals(3, prefs.getFailCount());
        ScreenRemote.assertKeysEnabled(activity, false);
        assertEquals("parent_manage", activity.getWindow().getDecorView().findFocus().getTag());
        ScreenRemote.press(activity, KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("parent_manage", activity.getWindow().getDecorView().findFocus().getTag());
        ScreenRemote.press(activity, KeyEvent.KEYCODE_1);
        assertEquals("", service.lockInput());
        ScreenRemote.press(activity, KeyEvent.KEYCODE_DPAD_UP);
        ScreenRemote.press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertNotNull(shadowOf(activity).getNextStartedActivity());
        assertLocked();
        assertFalse(AdminSession.isActive());
        ScreenRemote.expireCooldown(prefs);
        ScreenRemote.assertKeysEnabled(activity, true);
        ScreenRemote.digits(activity, "135790");
        assertTrue(AdminSession.isActive());
        assertEquals(MainActivity.class.getName(), shadowOf(activity).getNextStartedActivity()
                .getComponent().getClassName());
    }

    @Test
    public void missingServiceDisablesThePadAndPreservesTheParentEntry() {
        serviceController.destroy();
        serviceController = null;
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
        ScreenRemote.assertKeysEnabled(activity, false);
        assertEquals("parent_manage", activity.getWindow().getDecorView().findFocus().getTag());
        assertEquals("正在恢复服务…", ((TextView) activity.findViewById(R.id.lock_prompt))
                .getText().toString());
        ScreenRemote.press(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals("parent_manage", activity.getWindow().getDecorView().findFocus().getTag());
        ScreenRemote.press(activity, KeyEvent.KEYCODE_NUMPAD_ENTER);
        assertNotNull(shadowOf(activity).getNextStartedActivity());
        assertFalse(AdminSession.isActive());
    }

    @Test
    public void physicalDigitsStillUseLeftForDeleteAndOkForPartialSubmission() {
        ScreenRemote.press(activity, KeyEvent.KEYCODE_7);
        ScreenRemote.press(activity, KeyEvent.KEYCODE_7);
        ScreenRemote.press(activity, KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals("7", service.lockInput());
        ScreenRemote.press(activity, KeyEvent.KEYCODE_NUMPAD_ENTER);
        assertEquals("7", service.lockInput());
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertEquals(0, prefs.getFailCount());
        ScreenRemote.press(activity, KeyEvent.KEYCODE_DPAD_DOWN);
        ScreenRemote.select(activity, "3");
        assertEquals("73", service.lockInput());
        assertLocked();
    }

    private String input() {
        return ((TextView) activity.findViewById(R.id.lock_input)).getText().toString();
    }

    private void assertLocked() {
        assertTrue(service.isLocked());
        assertTrue(prefs.isGuardActive());
    }
}
