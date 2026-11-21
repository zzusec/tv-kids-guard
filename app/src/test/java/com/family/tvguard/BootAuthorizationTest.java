package com.family.tvguard;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33})
@LooperMode(LooperMode.Mode.PAUSED)
public class BootAuthorizationTest {
    private Context context;
    private Prefs prefs;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        Settings.Global.putInt(context.getContentResolver(), "boot_count", 10);
        AdminSession.revoke();
        prefs = new Prefs(context);
        prefs.setPassword("246813");
        prefs.setDailyLimitMinutes(30);
        prefs.setUsedSeconds(1800);
        prefs.setLocked(true);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
        assertEquals(Prefs.AuthResult.SUCCESS, prefs.authenticate("246813"));
        AdminSession.grant();
        prefs.startParentGrace(true);
        prefs.noteSettingsPresence();
        assertTrue(prefs.isParentGraceDeferringLock());
        assertTrue(prefs.isParentPinWindowActive());
        assertTrue(prefs.isSettingsHoldActive());
    }

    @After public void tearDown() {
        AdminSession.revoke();
        Settings.Global.putString(context.getContentResolver(), "boot_count", null);
    }

    @Test public void newBootWithLongerUptimeCannotInheritAnyParentAuthorization() {
        // A slower new boot can already have a larger uptime than the previous PIN timestamp.
        // Comparing only now < oldTimestamp therefore cannot establish a common boot.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20));
        Settings.Global.putInt(context.getContentResolver(), "boot_count", 11);
        AdminSession.revoke();

        Prefs afterBoot = new Prefs(context);

        assertFalse("new boot cannot inherit grace when its uptime is larger", afterBoot.isParentGraceDeferringLock());
        assertFalse(afterBoot.isParentPinWindowActive());
        assertFalse(afterBoot.isSettingsHoldActive());
        assertTrue(afterBoot.isGuardActive());
        assertTrue(afterBoot.checkPassword("246813"));
        assertEquals(30, afterBoot.getDailyLimitMinutes());
        assertEquals(1800, afterBoot.getUsedSeconds());
    }

    @Test public void newBootIsRejectedBeforeTheDelayedBootBroadcastIsDelivered() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(15));
        Settings.Global.putInt(context.getContentResolver(), "boot_count", 11);

        Prefs earlySettingsWatcher = new Prefs(context);

        assertFalse(earlySettingsWatcher.isSettingsGateFree());
        assertFalse(earlySettingsWatcher.isParentGraceActive());
        assertFalse(AdminSession.isActive());
    }

    @Test public void sameBootProcessRestartPreservesThePinGrantedRelease() {
        AdminSession.revoke();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20));

        Prefs sameBoot = new Prefs(context);

        assertTrue(sameBoot.isParentGraceDeferringLock());
        assertTrue(sameBoot.isParentPinWindowActive());
        assertTrue(sameBoot.isSettingsHoldActive());
        assertFalse(sameBoot.isGuardActive());
    }

    @Test public void lateBootBroadcastPreservesFreshAuthorizationBoundToThisBoot() {
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        assertTrue(prefs.isParentGraceDeferringLock());
        assertTrue(prefs.isParentPinWindowActive());
        assertTrue(prefs.isSettingsHoldActive());
    }

    @Test public void packageReplacementWithinTheSameBootPreservesTheRelease() {
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));

        assertTrue(prefs.isParentGraceDeferringLock());
        assertTrue(prefs.isParentPinWindowActive());
        assertTrue(prefs.isSettingsHoldActive());
    }

    @Test public void unknownBootBroadcastClearsAuthorizationFromThePreviousRuntime() {
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit()
                .putString("parent_authorization_runtime", "previous-process").commit();
        Prefs withoutBootIdentity = new Prefs(context, "");

        withoutBootIdentity.onBootCompleted();

        assertFalse(withoutBootIdentity.isParentGraceActive());
        assertFalse(withoutBootIdentity.isParentPinWindowActive());
        assertFalse(withoutBootIdentity.isSettingsHoldActive());
        assertFalse(AdminSession.isActive());
        assertTrue(withoutBootIdentity.isGuardActive());
    }

    @Test public void unknownLateBootBroadcastPreservesPinEnteredInTheNewRuntime() {
        Prefs withoutBootIdentity = new Prefs(context, "");
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit()
                .putString("parent_authorization_runtime", "previous-process").commit();
        assertEquals(Prefs.AuthResult.SUCCESS, withoutBootIdentity.authenticate("246813"));
        AdminSession.grant();
        withoutBootIdentity.startParentGrace(true);
        withoutBootIdentity.noteSettingsPresence();

        withoutBootIdentity.onBootCompleted();

        assertTrue(withoutBootIdentity.isParentGraceDeferringLock());
        assertTrue(withoutBootIdentity.isParentPinWindowActive());
        assertTrue(withoutBootIdentity.isSettingsHoldActive());
        assertTrue(AdminSession.isActive());
    }

    @Test public void unknownBootCannotRestoreReleaseFromAnotherRuntimeBeforeAnyBroadcast() {
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit()
                .putString("parent_authorization_runtime", "previous-process").commit();
        AdminSession.revoke();

        Prefs sameBootWithoutIdentity = new Prefs(context, "");

        assertFalse(sameBootWithoutIdentity.isParentGraceDeferringLock());
        assertFalse(sameBootWithoutIdentity.isParentPinWindowActive());
        assertFalse(sameBootWithoutIdentity.isSettingsHoldActive());
        assertTrue(sameBootWithoutIdentity.isGuardActive());
    }

    @Test public void unknownIdentityStillPreservesAuthorizationInsideTheSameRuntime() {
        Prefs sameRuntime = new Prefs(context, "");

        assertTrue(sameRuntime.isParentGraceDeferringLock());
        assertTrue(sameRuntime.isParentPinWindowActive());
        assertTrue(sameRuntime.isSettingsHoldActive());
        assertTrue(AdminSession.isActive());
    }

    @Test public void clearingUnknownPreviousBootDoesNotChangePasswordQuotaOrProtection() {
        prefs.setDailyLimitMinutes(40);
        prefs.setUsedSeconds(61);
        prefs.setBonusSeconds(240);
        prefs.setProtectionEnabled(false);
        prefs.setLocked(true);
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit()
                .putString("parent_authorization_runtime", "previous-process").commit();
        Prefs afterBoot = new Prefs(context, "");

        afterBoot.onBootCompleted();

        assertTrue(afterBoot.checkPassword("246813"));
        assertEquals(40, afterBoot.getDailyLimitMinutes());
        assertEquals(61, afterBoot.getUsedSeconds());
        assertEquals(240, afterBoot.getBonusSeconds());
        assertFalse(afterBoot.isProtectionEnabled());
        assertTrue(afterBoot.isLocked());
        assertFalse(afterBoot.isParentGraceDeferringLock());
    }

    @Test public void legacyAuthorizationWithoutBootBindingNeedsAFreshPin() {
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit()
                .remove("parent_authorization_boot").commit();

        Prefs upgraded = new Prefs(context);

        assertFalse(upgraded.isParentGraceDeferringLock());
        assertFalse(upgraded.isParentPinWindowActive());
        assertFalse(upgraded.isSettingsHoldActive());
        assertTrue(upgraded.isGuardActive());
        assertTrue(upgraded.checkPassword("246813"));
    }
}
