package com.family.tvguard;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.View;

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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowActivityManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.TimeUnit;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 33}, shadows = {
        GuardServiceAuthTest.TaskActivityShadow.class,
        GuardServiceAuthTest.TaskManagerShadow.class
})
@LooperMode(LooperMode.Mode.PAUSED)
public class GuardServiceAuthTest {

    private Application context;
    private Prefs prefs;
    private ServiceController<GuardService> serviceController;
    private ActivityController<LockActivity> lockController;
    private ActivityController<AdminAuthActivity> authController;
    private ActivityManager activityManager;
    private TaskManagerShadow taskManager;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L);
        prefs.setLocked(true);
        AdminSession.revoke();
        ReflectionHelpers.setStaticField(GuardService.class, "lastFailure", "");
        activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        taskManager = Shadow.extract(activityManager);
    }

    @After
    public void tearDown() {
        taskManager.failQuery = false;
        if (authController != null) {
            authController.pause().stop().destroy();
        }
        if (lockController != null) {
            lockController.pause().stop().destroy();
        }
        if (serviceController != null) {
            serviceController.destroy();
        }
        AdminSession.revoke();
    }

    @Test
    @Config(sdk = {19, 24, 33})
    public void factoryPinOnlyOpensSetupAndDoesNotGrantUnlimitedViewing() {
        GuardService service = startService();
        while (org.robolectric.Shadows.shadowOf(service).getNextStartedActivity() != null) {
            // Discard the initial quota-lock launch so the next intent is the setup gate.
        }
        enterPin(service, Prefs.DEFAULT_PASSWORD);

        assertFalse(service.onOk());
        assertTrue(service.isLocked());
        assertTrue(prefs.isGuardActive());
        assertFalse(prefs.isUnlockedToday());
        assertTrue(prefs.needsPasswordSetup());
        assertTrue(AdminSession.isActive());
        Intent launched = org.robolectric.Shadows.shadowOf(service).getNextStartedActivity();
        assertNotNull(launched);
        assertEquals(AdminAuthActivity.class.getName(), launched.getComponent().getClassName());
    }

    @Test
    public void cancellingFactoryPinSetupAndRestartingServicePreservesTheQuotaLock() {
        GuardService service = startService();
        enterPin(service, Prefs.DEFAULT_PASSWORD);
        service.onOk();
        authController = Robolectric.buildActivity(AdminAuthActivity.class).create().start().resume();

        authController.get().onBackPressed();

        assertFalse(AdminSession.isActive());
        assertFalse(prefs.isUnlockedToday());
        assertTrue(prefs.isGuardActive());
        serviceController.destroy();
        serviceController = null;
        assertTrue(startService().isLocked());
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES * 60L, prefs.getUsedSeconds());
    }

    @Test
    @Config(sdk = {19, 24, 33})
    public void configuredParentPinStillReleasesTodaysQuota() {
        prefs.setPassword("135790");
        GuardService service = startService();
        enterPin(service, "135790");

        assertTrue(service.onOk());
        assertFalse(service.isLocked());
        assertTrue(prefs.isUnlockedToday());
    }

    @Test
    public void increasingDailyLimitReleasesQuotaLockWithoutResettingUsageOrBonus() {
        GuardService service = startService();
        prefs.setBonusSeconds(300L);
        AdminSession.grant();

        service.setDailyLimitMinutes(45);

        assertFalse(service.isLocked());
        assertFalse(prefs.isGuardActive());
        assertEquals(1800L, prefs.getUsedSeconds());
        assertEquals(300L, prefs.getBonusSeconds());
        assertEquals(1200L, prefs.remainingSeconds());
        serviceController.destroy();
        serviceController = null;
        assertFalse(startService().isLocked());
    }

    @Test
    public void decreasingDailyLimitAppliesImmediatelyAndRearmsTodaysQuota() {
        prefs.setUsedSeconds(600L);
        prefs.setLocked(false);
        prefs.setUnlockedToday(true);
        GuardService service = startService();
        AdminSession.grant();

        service.setDailyLimitMinutes(5);

        assertEquals(5, prefs.getDailyLimitMinutes());
        assertFalse(prefs.isUnlockedToday());
        assertTrue(service.isLocked());
        assertTrue(prefs.isGuardActive());
        assertEquals(600L, prefs.getUsedSeconds());
    }

    @Test
    public void dailyLimitCannotBeChangedWithoutParentAuthorization() {
        GuardService service = startService();

        assertThrows(IllegalStateException.class, () -> service.setDailyLimitMinutes(45));

        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES, prefs.getDailyLimitMinutes());
        assertTrue(service.isLocked());
    }

    @Test
    public void wrongPinDoesNotAuthorizeTheGateOrReleaseTheQuota() {
        GuardService service = startService();
        enterPin(service, "000001");

        assertFalse(service.onOk());
        assertTrue(service.isLocked());
        assertFalse(prefs.isUnlockedToday());
        assertFalse(AdminSession.isActive());
    }

    @Test
    public void emptyOkOpensTheParentGateWithoutStoppingLockTask() {
        assertGateKeepsLockTask(KeyEvent.KEYCODE_DPAD_CENTER);
    }

    @Test
    public void menuKeyIsBlockedOnTheLockScreen() {
        LockActivity lock = startLock();

        assertTrue(lock.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU)));

        assertNull(activityShadow().getNextStartedActivity());
        assertTrue(prefs.isGuardActive());
        assertFalse(AdminSession.isActive());
    }

    /** The room keeps its volume buttons while the quota lock holds the remote. */
    @Test
    public void volumeButtonsStayWithTheSystemWhileTheQuotaLockIsUp() {
        LockActivity lock = startLock();

        for (int keyCode : new int[] {KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE}) {
            assertFalse(lock.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode)));
            assertFalse(lock.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode)));
        }

        assertNull(activityShadow().getNextStartedActivity());
        assertTrue(prefs.isGuardActive());
        // Everything else still belongs to the lock.
        assertTrue(lock.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0)));
    }

    /** The power key blanks the screen and it stays blank: standby is a pause, not an escape. */
    @Test
    public void powerKeyLeavesTheScreenDarkWhileTheLockHolds() {
        GuardService service = startService();
        startLock();
        // Blanking the panel pauses the lock window, which is what re-arms the re-assert loop.
        lockController.pause();
        while (activityShadow().getNextStartedActivity() != null) { /* drain setup */ }

        // The power key reaches the system, which blanks the panel and broadcasts SCREEN_OFF.
        powerShadow().turnScreenOn(false);
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS);

        // Neither the SCREEN_OFF handling nor the 800ms re-assert loop may re-launch the lock
        // window: it carries FLAG_TURN_SCREEN_ON, so that would light the room back up.
        assertNull(activityShadow().getNextStartedActivity());
        assertTrue(service.isLocked());
        assertTrue(prefs.isGuardActive());
        // A dark screen is not viewing time.
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES * 60L, prefs.getUsedSeconds());
    }

    @Test
    @Config(sdk = 19)
    public void kitKatCountsOnlyScreenOnTimeAndPersistsAcrossRestart() {
        prefs.setLocked(false);
        prefs.setUsedSeconds(0L);
        GuardService service = startService();
        pauseAutomaticTicks(service);
        powerShadow().turnScreenOn(true);
        ShadowLooper.idleMainLooper();

        advanceCounting(service, 5);
        assertEquals(5L, prefs.getUsedSeconds());
        powerShadow().turnScreenOn(false);
        ShadowLooper.idleMainLooper();
        advanceCounting(service, 5);
        assertEquals(5L, prefs.getUsedSeconds());
        assertFalse(service.isLocked());

        serviceController.destroy();
        serviceController = null;
        service = startService();
        pauseAutomaticTicks(service);
        assertFalse(service.isLocked());
        assertEquals(5L, prefs.getUsedSeconds());
        powerShadow().turnScreenOn(true);
        ShadowLooper.idleMainLooper();
        advanceCounting(service, 5);
        assertEquals(10L, prefs.getUsedSeconds());
    }

    @Test
    @Config(sdk = 19)
    public void kitKatLocksAtTheQuotaWithoutUnlockingOnServiceRestart() {
        prefs.setLocked(false);
        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L - 1L);
        GuardService service = startService();
        powerShadow().turnScreenOn(true);

        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);

        assertTrue(service.isLocked());
        assertTrue(prefs.isLocked());
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES * 60L, prefs.getUsedSeconds());
        serviceController.destroy();
        serviceController = null;
        assertTrue(startService().isLocked());
        assertFalse(prefs.isUnlockedToday());
    }

    /** However the screen was blanked, the lock is the first thing back when it lights up. */
    @Test
    public void screenOnPullsTheLockBackToTheFront() {
        startService();
        startLock();
        lockController.pause();
        powerShadow().turnScreenOn(false);
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS);
        while (activityShadow().getNextStartedActivity() != null) { /* drain */ }

        powerShadow().turnScreenOn(true);
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);

        Intent next = activityShadow().getNextStartedActivity();
        assertNotNull(next);
        assertEquals(LockActivity.class.getName(), next.getComponent().getClassName());
        assertTrue(prefs.isGuardActive());
    }

    /** DEL key is consumed by the lock and does nothing. */
    @Test
    public void deleteKeyIsBlockedOnTheLockScreen() {
        LockActivity lock = startLock();

        assertTrue(lock.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)));

        assertNull(activityShadow().getNextStartedActivity());
        assertTrue(prefs.isGuardActive());
    }

    /** BACK key is consumed by the lock and does nothing. */
    @Test
    public void backKeyIsBlockedOnTheLockScreen() {
        LockActivity lock = startLock();

        assertTrue(lock.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)));

        assertNull(activityShadow().getNextStartedActivity());
        assertTrue(prefs.isGuardActive());
    }

    @Test
    public void authorizedMaintenanceStopsLockTaskAndFinishesOriginalLockScreen() {
        LockActivity lock = startLock();
        taskManager.setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_LOCKED);
        AdminSession.grant();
        AdminSession.grantMaintenance();

        LockActivity.dismissForMaintenance();

        assertEquals(ActivityManager.LOCK_TASK_MODE_NONE, activityManager.getLockTaskModeState());
        assertEquals(1, activityShadow().stopCalls);
        assertTrue(lock.isFinishing());
    }

    @Test
    public void rejectedLockTaskStopDoesNotFinishOrClaimAnExit() {
        LockActivity lock = startLock();
        taskManager.setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_LOCKED);
        activityShadow().failStop = true;
        AdminSession.grant();
        AdminSession.grantMaintenance();

        LockActivity.dismissForMaintenance();

        assertFalse(lock.isFinishing());
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, activityManager.getLockTaskModeState());
        assertTrue(GuardService.lastFailure().contains("尚未核实退出"));
    }

    @Test
    public void ignoredLockTaskStopIsDetectedByReadBack() {
        LockActivity lock = startLock();
        taskManager.setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_LOCKED);
        activityShadow().ignoreStop = true;
        AdminSession.grant();
        AdminSession.grantMaintenance();

        LockActivity.dismissForMaintenance();

        assertFalse(lock.isFinishing());
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, activityManager.getLockTaskModeState());
        assertTrue(GuardService.lastFailure().contains("尚未核实退出"));
    }

    @Test
    public void failedLockTaskQueryDoesNotFinishOrTreatItAsAlreadyStopped() {
        LockActivity lock = startLock();
        taskManager.setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_LOCKED);
        taskManager.failQuery = true;
        AdminSession.grant();
        AdminSession.grantMaintenance();

        LockActivity.dismissForMaintenance();

        assertFalse(lock.isFinishing());
        assertEquals(0, activityShadow().stopCalls);
        assertTrue(GuardService.lastFailure().contains("尚未核实退出"));
    }

    @Test
    public void activityDestructionDoesNotEndTheSystemLockTask() {
        startLock();
        taskManager.setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_LOCKED);
        TaskActivityShadow shadow = activityShadow();

        lockController.pause().stop().destroy();
        lockController = null;

        assertEquals(0, shadow.stopCalls);
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, activityManager.getLockTaskModeState());
    }

    private void assertGateKeepsLockTask(int keyCode) {
        LockActivity lock = startLock();
        taskManager.setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_LOCKED);

        // An empty buffer plus OK opens the parent gate without ending Lock Task.
        assertTrue(lock.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode)));
        assertTrue(lock.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode)));

        assertEquals(0, activityShadow().stopCalls);
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, activityManager.getLockTaskModeState());
        assertFalse(prefs.isUnlockedToday());
        assertFalse(AdminSession.isActive());
        Intent started = activityShadow().getNextStartedActivity();
        assertNotNull(started);
        assertEquals(AdminAuthActivity.class.getName(), started.getComponent().getClassName());
    }

    private GuardService startService() {
        serviceController = Robolectric.buildService(GuardService.class).create();
        return serviceController.get();
    }

    private void pauseAutomaticTicks(GuardService service) {
        android.os.Handler handler = ReflectionHelpers.getField(service, "handler");
        handler.removeCallbacks(ReflectionHelpers.getField(service, "ticker"));
        ShadowLooper.idleMainLooper();
    }

    private void advanceCounting(GuardService service, int seconds) {
        // KitKat layout traversal also advances Robolectric's clock; isolate the counting ticks.
        for (int i = 0; i < seconds; i++) {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
            ReflectionHelpers.callInstanceMethod(service, "tick");
        }
    }

    private LockActivity startLock() {
        lockController = Robolectric.buildActivity(LockActivity.class).create().start().resume().visible();
        return lockController.get();
    }

    private TaskActivityShadow activityShadow() {
        return Shadow.extract(lockController.get());
    }

    private ShadowPowerManager powerShadow() {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return Shadow.extract(power);
    }

    private void enterPin(GuardService service, String pin) {
        for (int i = 0; i < pin.length(); i++) {
            service.onDigit(pin.charAt(i) - '0');
        }
    }

    @Implements(Activity.class)
    public static class TaskActivityShadow extends ShadowActivity {
        int stopCalls;
        boolean failStop;
        boolean ignoreStop;

        @Implementation
        protected void stopLockTask() {
            stopCalls++;
            if (failStop) {
                throw new SecurityException("Lock task stop rejected");
            }
            if (!ignoreStop) {
                super.stopLockTask();
            }
        }
    }

    @Implements(ActivityManager.class)
    public static class TaskManagerShadow extends ShadowActivityManager {
        boolean failQuery;

        @Implementation(minSdk = 23)
        protected int getLockTaskModeState() {
            if (failQuery) {
                throw new IllegalStateException("Lock task state unavailable");
            }
            return super.getLockTaskModeState();
        }
    }
}
