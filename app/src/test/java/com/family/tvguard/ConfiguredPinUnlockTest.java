package com.family.tvguard;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.view.KeyEvent;

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
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowSettings;

import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/** Correct configured television PINs release the current lock; they never edit quota numbers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33}, qualifiers = "w960dp-h540dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
public class ConfiguredPinUnlockTest {
    private static final String PIN = "135790";
    private Application context;
    private Prefs prefs;
    private GuardService guard;
    private ServiceController<GuardService> guardController;
    private ActivityController<AdminAuthActivity> gateController;
    private ActivityController<MainActivity> panelController;
    private ShadowPowerManager power;
    private TimeZone originalZone;

    @Before
    public void setUp() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("GMT-12:00"));
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword(PIN);
        prefs.setDailyLimitMinutes(45);
        prefs.setUsedSeconds(3000L);
        prefs.setBonusSeconds(120L);
        prefs.setLocked(true);
        AdminSession.revoke();
        power = shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE));
        power.setIsInteractive(false);
        ShadowSettings.setCanDrawOverlays(true);
        guardController = Robolectric.buildService(GuardService.class).create();
        guard = guardController.get();
        assertTrue(guard.isLocked());
    }

    @After
    public void tearDown() {
        if (panelController != null) panelController.pause().stop().destroy();
        closeGate();
        if (guardController != null) guardController.destroy();
        AdminSession.revoke();
        TimeZone.setDefault(originalZone);
    }

    @Test
    public void theLockInputReleasesImmediatelyOnACorrectConfiguredPin() {
        enterLock("13579");
        assertFalse(guard.onOk());
        assertTrue(guard.isLocked());
        assertFalse(prefs.isParentGraceActive());
        guard.onDigit(0);

        assertTrue(guard.onOk());

        assertReleased();
        assertQuotaUnchanged();
    }

    @Test
    public void theSeparatePinGateReleasesImmediatelyBeforeAnyPanelExit() {
        AdminAuthActivity gate = startGate();
        assertTrue(guard.isLocked());
        assertFalse(AdminSession.isActive());

        enterGate(PIN);

        assertTrue(gate.isFinishing());
        assertReleased();
        assertQuotaUnchanged();
        assertFalse("解锁不得依赖面板已经创建或退出", MainActivity.isAuthorizedVisible());
    }

    @Test
    public void exitingThePanelByBackPreservesTheAlreadyGrantedFromLockGrace() {
        unlockAtGate();
        MainActivity panel = openPanel();
        assertReleased();

        panel.onBackPressed();

        assertTrue(panel.isFinishing());
        assertFalse(AdminSession.isActive());
        assertFalse(guard.isLocked());
        assertTrue("退出时不能把fromLock=true覆盖为false", prefs.isParentGraceDeferringLock());
        assertQuotaUnchanged();
    }

    @Test
    public void exitingThePanelBy00PreservesTheAlreadyGrantedFromLockGrace() {
        unlockAtGate();
        MainActivity panel = openPanel();

        press(panel, KeyEvent.KEYCODE_0);
        press(panel, KeyEvent.KEYCODE_0);

        assertTrue(panel.isFinishing());
        assertFalse(AdminSession.isActive());
        assertFalse(guard.isLocked());
        assertTrue("00不得把立即放行降成不具备解锁能力的宽限", prefs.isParentGraceDeferringLock());
        assertQuotaUnchanged();
    }

    @Test
    public void anExplicitLockRequiresANewCorrectPin() {
        enterLock(PIN);
        assertTrue(guard.onOk());
        assertReleased();

        guard.lockNow();

        assertTrue(guard.isLocked());
        assertFalse(AdminSession.isActive());
        assertFalse(prefs.isParentGraceActive());
        assertFalse(guard.onOk());
        enterLock("999999");
        assertFalse(guard.onOk());
        assertTrue(guard.isLocked());
        assertFalse(AdminSession.isActive());
        enterLock(PIN);
        assertTrue(guard.onOk());
        assertReleased();
        assertQuotaUnchanged();
    }

    @Test
    public void standbyEndsTheReleaseAndTheOldSessionCannotSkipThePinGate() {
        enterLock(PIN);
        assertTrue(guard.onOk());

        deliver(Intent.ACTION_SCREEN_OFF);

        assertFalse(prefs.isParentGraceActive());
        assertFalse("待机后旧家长认证不能给下一次锁屏免密", AdminSession.isActive());
        power.setIsInteractive(true);
        deliver(Intent.ACTION_SCREEN_ON);
        assertTrue(guard.isLocked());
        AdminAuthActivity gate = startGate();
        assertFalse("旧认证不能让验证页自动跳进后台", gate.isFinishing());
        enterGate("13579");
        assertTrue(guard.isLocked());
        enterGate("0");
        assertReleased();
        assertQuotaUnchanged();
    }

    @Test
    public void aNewDayEndsTheReleaseAndOldAuthenticationCannotUnlockANewLock() {
        enterLock(PIN);
        assertTrue(guard.onOk());
        // Advance the calendar through a real 26-hour timezone difference; do not fake a stale
        // preference day whose value could coincidentally equal the service's in-memory day.
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+14:00"));
        guard.refreshProtection();

        assertFalse(prefs.isParentGraceActive());
        assertFalse("新一天不得沿用昨天的家长认证", AdminSession.isActive());
        assertEquals(0L, prefs.getUsedSeconds());
        assertEquals(0L, prefs.getBonusSeconds());
        assertEquals(45, prefs.getDailyLimitMinutes());
        // Model a subsequent lock on this new day. Its PIN gate must not inherit yesterday's pass.
        prefs.setLocked(true);
        guard.refreshProtection();
        AdminAuthActivity gate = startGate();
        assertTrue(guard.isLocked());
        assertFalse(gate.isFinishing());
        enterGate(PIN);
        assertReleased();
    }

    @Test
    public void completingAPinAcrossMidnightUsesTheFreshDayWithoutAnUnlimitedGrace() {
        AdminAuthActivity gate = startGate();
        enterGate("13579");
        assertTrue(guard.isLocked());
        assertFalse(AdminSession.isActive());

        // Leave the service's old locked counter untouched: the final PIN digit must itself
        // observe midnight, clear yesterday's state, and preserve the newly verified session.
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+14:00"));
        enterGate("0");

        assertTrue(gate.isFinishing());
        assertTrue("跨天时刚验证成功的家长会话不能被旧日换日逻辑撤销", AdminSession.isActive());
        assertFalse("昨日内存锁不能把新一天误判成密码解锁宽限", guard.isLocked());
        assertFalse(prefs.isGuardActive());
        assertFalse("新一天有完整额度，不能从昨日锁屏继承不限时放行", prefs.isParentGraceDeferringLock());
        assertEquals(45, prefs.getDailyLimitMinutes());
        assertEquals(0L, prefs.getUsedSeconds());
        assertEquals(0L, prefs.getBonusSeconds());
        assertEquals(45 * 60L, prefs.remainingSeconds());

        prefs.setUsedSeconds(prefs.getDailyLimitMinutes() * 60L);
        assertTrue("跨天验证不等于不限时，新一天额度耗尽仍应锁定", prefs.isGuardActive());
        assertFalse(prefs.isParentGraceDeferringLock());
    }

    @Test
    public void factoryAuthenticationAndSavingANewPinDoNotReleaseTheLock() {
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE)
                .edit().remove("password_hash").commit();
        startGate();
        enterGate(Prefs.DEFAULT_PASSWORD);
        assertTrue(prefs.needsPasswordSetup());
        assertTrue(guard.isLocked());
        assertFalse(prefs.isParentGraceActive());

        enterGate("246810");
        enterGate("246810");

        assertFalse(prefs.needsPasswordSetup());
        assertTrue("设置新密码不是输入已配置家长密码的证明", guard.isLocked());
        assertFalse(prefs.isParentGraceActive());
        assertQuotaUnchanged();
        closeGate();
        MainActivity panel = openPanel();
        panel.onBackPressed();
        assertTrue("首次设置后关闭任何面板也不能变相解除锁定", guard.isLocked());
        assertFalse(prefs.isParentGraceActive());
    }

    private void assertReleased() {
        assertFalse("正确configured PIN必须立即解除本次锁定", guard.isLocked());
        assertFalse(prefs.isGuardActive());
        assertTrue(prefs.isParentGraceDeferringLock());
        assertTrue(AdminSession.isActive());
    }

    private void assertQuotaUnchanged() {
        assertEquals(45, prefs.getDailyLimitMinutes());
        assertEquals(3000L, prefs.getUsedSeconds());
        assertEquals(120L, prefs.getBonusSeconds());
    }

    private void enterLock(String pin) {
        for (char digit : pin.toCharArray()) guard.onDigit(digit - '0');
    }

    private AdminAuthActivity startGate() {
        gateController = Robolectric.buildActivity(AdminAuthActivity.class)
                .create().start().resume().visible().windowFocusChanged(true);
        return gateController.get();
    }

    private void unlockAtGate() {
        startGate();
        enterGate(PIN);
        assertReleased();
        closeGate();
    }

    private void enterGate(String digits) {
        for (char digit : digits.toCharArray()) press(gateController.get(), KeyEvent.KEYCODE_0 + digit - '0');
    }

    private void closeGate() {
        if (gateController == null) return;
        gateController.pause().stop().destroy();
        gateController = null;
    }

    private MainActivity openPanel() {
        panelController = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().windowFocusChanged(true);
        return panelController.get();
    }

    private void press(android.app.Activity target, int key) {
        target.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        target.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
    }

    private void deliver(String action) {
        Intent intent = new Intent(action);
        List<BroadcastReceiver> receivers = shadowOf(context).getReceiversForIntent(intent);
        assertFalse(receivers.isEmpty());
        for (BroadcastReceiver receiver : receivers) receiver.onReceive(context, intent);
    }
}
