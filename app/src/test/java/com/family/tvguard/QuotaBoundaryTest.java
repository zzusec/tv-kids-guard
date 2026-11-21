package com.family.tvguard;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
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
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Every new quota/standby boundary requires a fresh complete parent password. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33}, qualifiers = "w960dp-h540dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
public class QuotaBoundaryTest {
    private static final String PIN = "135790";
    private Application context;
    private Prefs prefs;
    private ServiceController<GuardService> controller;
    private GuardService guard;
    private ActivityController<AdminAuthActivity> gate;
    private ActivityController<LockActivity> lock;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword(PIN);
        prefs.setUsedSeconds(1799L);
        AdminSession.revoke();
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE)).setIsInteractive(false);
        ShadowSettings.setCanDrawOverlays(true);
        controller = Robolectric.buildService(GuardService.class).create();
        guard = controller.get();
    }

    @After
    public void tearDown() {
        if (gate != null) gate.pause().stop().destroy();
        if (lock != null) lock.pause().stop().destroy();
        if (controller != null) controller.destroy();
        AdminSession.revoke();
    }

    @Test
    public void reachingThirtyMinutesInvalidatesAPartialPasswordFromBeforeTheLock() {
        gate = Robolectric.buildActivity(AdminAuthActivity.class).setup().visible();
        typeAtGate("13579");
        exhaustQuota();
        assertTrue(guard.isLocked());
        typeAtGate("0");
        assertTrue("Five digits entered before the quota lock must not complete after it", guard.isLocked());
        assertFalse(prefs.isParentGraceDeferringLock());
        assertFalse(AdminSession.isActive());
        assertEquals("●", ((TextView) gate.get().findViewById(R.id.lock_input)).getText().toString());
        gate.get().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
        typeAtGate(PIN);
        assertFalse("A fresh complete parent password must still work", guard.isLocked());
    }

    @Test
    public void reachingThirtyMinutesRevokesEarlierParentAndSettingsAuthorization() {
        assertEquals(Prefs.AuthResult.SUCCESS, prefs.authenticate(PIN));
        AdminSession.grant();
        prefs.noteSettingsPresence();
        guard.setConsoleVisible(true);
        exhaustQuota();
        assertTrue(guard.isLocked());
        assertFalse(AdminSession.isActive());
        assertFalse(prefs.isParentPinWindowActive());
        assertFalse(prefs.isSettingsHoldActive());
        gate = Robolectric.buildActivity(AdminAuthActivity.class).setup().visible();
        assertFalse("An old session cannot auto-open the panel after quota exhaustion", gate.get().isFinishing());
    }

    @Test
    public void standbyDiscardsPartialPasswordAndAllOldSettingsPasses() {
        exhaustQuota();
        for (char digit : "13579".toCharArray()) guard.onDigit(digit - '0');
        assertEquals(Prefs.AuthResult.SUCCESS, prefs.authenticate(PIN));
        AdminSession.grant();
        prefs.noteSettingsPresence();
        Intent screenOff = new Intent(Intent.ACTION_SCREEN_OFF);
        for (BroadcastReceiver receiver : shadowOf(context).getReceiversForIntent(screenOff)) {
            receiver.onReceive(context, screenOff);
        }
        guard.onDigit(0);
        assertFalse(guard.onOk());
        assertEquals("0", guard.lockInput());
        assertTrue(guard.isLocked());
        assertFalse(prefs.isParentPinWindowActive());
        assertFalse(prefs.isSettingsHoldActive());
    }

    @Test
    public void leavingTheLockWindowDiscardsItsPartialPassword() {
        exhaustQuota();
        lock = Robolectric.buildActivity(LockActivity.class).create().start().resume().visible();
        for (char digit : "13579".toCharArray()) guard.onDigit(digit - '0');
        lock.pause();
        assertEquals("", guard.lockInput());
        lock.resume();
        guard.onDigit(0);
        assertFalse(guard.onOk());
        assertTrue(guard.isLocked());
    }

    @Test
    public void bonusAfterParentUnlockLocksAgainWhenTheGrantedMinutesAreSpent() {
        unlockSpentQuota();
        guard.grantBonusMinutes(10);
        assertFalse(prefs.isParentGraceDeferringLock());
        assertFalse(guard.isLocked());
        assertEquals(600L, prefs.remainingSeconds());
        ReflectionHelpers.setField(guard, "usedMillis", 2400000L);
        guard.refreshProtection();
        assertTrue(guard.isLocked());
    }

    @Test
    public void dailyLimitAfterParentUnlockRestoresTheChosenFiniteQuota() {
        unlockSpentQuota();
        guard.setDailyLimitMinutes(40);
        assertFalse(prefs.isParentGraceDeferringLock());
        assertFalse(guard.isLocked());
        ReflectionHelpers.setField(guard, "usedMillis", 2400000L);
        guard.refreshProtection();
        assertTrue(guard.isLocked());
    }

    @Test
    public void clearingUsageAfterParentUnlockDoesNotLeaveAnUnlimitedDay() {
        unlockSpentQuota();
        guard.clearTodayUsage();
        assertFalse(prefs.isParentGraceDeferringLock());
        assertEquals(0L, prefs.getUsedSeconds());
        exhaustQuota();
        assertTrue(guard.isLocked());
    }

    @Test
    public void reEnablingProtectionCannotRestoreTheStoppedServicesOldGrace() {
        unlockSpentQuota();
        prefs.setProtectionEnabled(false);
        controller.destroy();
        controller = null;
        assertFalse(prefs.isParentGraceDeferringLock());
        assertFalse(prefs.isParentPinWindowActive());
        prefs.setProtectionEnabled(true);
        controller = Robolectric.buildService(GuardService.class).create();
        guard = controller.get();
        assertTrue(guard.isLocked());
    }

    @Test
    public void factoryPasswordCannotLeaveSetupWithASettingsPass() {
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit()
                .remove("password_hash").commit();
        gate = Robolectric.buildActivity(AdminAuthActivity.class).setup().visible();
        typeAtGate(Prefs.DEFAULT_PASSWORD);
        assertFalse(prefs.isParentPinWindowActive());
        gate.get().onBackPressed();
        assertFalse(prefs.isSettingsGateFree());
        assertFalse(AdminSession.isActive());
    }

    private void exhaustQuota() {
        ReflectionHelpers.setField(guard, "usedMillis", 1800000L);
        guard.refreshProtection();
    }

    private void unlockSpentQuota() {
        exhaustQuota();
        for (char digit : PIN.toCharArray()) guard.onDigit(digit - '0');
        assertTrue(guard.onOk());
        assertTrue(prefs.isParentGraceDeferringLock());
        assertFalse(guard.isLocked());
    }

    private void typeAtGate(String digits) {
        for (char digit : digits.toCharArray()) {
            int key = KeyEvent.KEYCODE_0 + digit - '0';
            gate.get().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
            gate.get().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
        }
    }
}
