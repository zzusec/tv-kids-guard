package com.family.tvguard;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.view.Display;
import android.view.KeyEvent;
import android.view.WindowManager;
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
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDisplay;
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

    /**
     * A lock screen that holds the panel awake is a television that can never stand by, and a
     * standby the framework is never told about is a standby the counter charges. Measured on the
     * BRAVIA 4K 2015, this window was the set's only bright wake lock and its only hold-screen
     * window, and the television stayed Awake for 29 hours with nobody in front of it.
     */
    @Test
    public void theLockScreenLeavesTheTelevisionFreeToStandBy() {
        int flags = activity.getWindow().getAttributes().flags;
        assertEquals("锁屏不得替电视保持亮屏",
                0, flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        assertTrue("唤醒电视时锁屏要自己亮回来",
                (flags & WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON) != 0);
    }

    /** The panel witness needs API 20; a KitKat set only ever had the power manager. */
    @Test
    @Config(sdk = {24, 33})
    public void theLockNeverLightsADarkPanelBackUp() {
        Application context = RuntimeEnvironment.getApplication();
        controller.pause();
        panel(Display.STATE_OFF);
        LockActivity.launch(context);
        assertNull("面板熄灭时不得重新拉起锁屏，它带 TURN_SCREEN_ON，会把刚关掉的电视点亮",
                shadowOf(context).getNextStartedActivity());

        panel(Display.STATE_ON);
        LockActivity.launch(context);
        Intent lock = shadowOf(context).getNextStartedActivity();
        assertNotNull("亮屏之后锁屏必须自己回来", lock);
        assertEquals(LockActivity.class.getName(), lock.getComponent().getClassName());
    }

    /** Answers for the display subsystem, which now has a vote over the lock and the counter. */
    private void panel(int state) {
        WindowManager wm = (WindowManager) RuntimeEnvironment.getApplication()
                .getSystemService(Context.WINDOW_SERVICE);
        ShadowDisplay shadow = Shadow.extract(wm.getDefaultDisplay());
        shadow.setState(state);
    }

    @Test
    public void parentEntryIconAndPadHaveExplicitRoundTripNavigation() {
        assertEquals("parent_manage", activity.getWindow().getDecorView().findFocus().getTag());
        assertTrue("A remote with unproven digits is offered the icon instead of the pad",
                ScreenRemote.offersIcon(activity));
        ScreenRemote.press(activity, KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("keypad_request", activity.getWindow().getDecorView().findFocus().getTag());
        ScreenRemote.press(activity, KeyEvent.KEYCODE_DPAD_UP);
        assertEquals("parent_manage", activity.getWindow().getDecorView().findFocus().getTag());
        ScreenRemote.press(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals("keypad_request", activity.getWindow().getDecorView().findFocus().getTag());
        ScreenRemote.openPad(activity);
        assertEquals("key_1", activity.getWindow().getDecorView().findFocus().getTag());
        ScreenRemote.press(activity, KeyEvent.KEYCODE_DPAD_UP);
        assertEquals("parent_manage", activity.getWindow().getDecorView().findFocus().getTag());
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
