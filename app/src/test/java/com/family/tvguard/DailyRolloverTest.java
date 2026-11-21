package com.family.tvguard;

import android.app.Application;
import android.content.Context;
import android.os.PowerManager;
import android.view.Display;
import android.view.WindowManager;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.shadows.ShadowSystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/** The next day must have its own counter, whichever reader first observes midnight. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33}, qualifiers = "w960dp-h540dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
public class DailyRolloverTest {
    private TimeZone originalZone;
    private Application context;
    private Prefs prefs;
    private ShadowPowerManager power;
    private ServiceController<GuardService> controller;
    private GuardService guard;

    @Before
    public void setUp() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("GMT-12:00"));
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L);
        ShadowSettings.setCanDrawOverlays(true);
        power = shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE));
        power.setIsInteractive(false);
        AdminSession.revoke();
    }

    @After
    public void tearDown() {
        if (controller != null) controller.destroy();
        AdminSession.revoke();
        TimeZone.setDefault(originalZone);
    }

    @Test
    public void aPrefsReaderCannotLeaveYesterdayInTheServiceCounter() throws Exception {
        prefs.startParentGrace(true);
        createGuard();
        tick();
        assertTrue(countdown().getText().toString().contains("00:00"));

        nextDay();
        // The settings watcher, panel and HTTP status reader can all arrive before the ticker.
        assertEquals(0L, new Prefs(context).getUsedSeconds());
        tick();
        persist();

        assertEquals(0L, prefs.getUsedSeconds());
        assertFalse(guard.isLocked());
        assertFalse(prefs.isParentGraceActive());
        assertTrue(countdown().getText().toString().contains("30:00"));
    }

    @Test
    public void aWatchdogStartBeforeTheFirstNewDayTickDoesNotLockFreshQuota() throws Exception {
        prefs.startParentGrace(true);
        createGuard();
        assertFalse(guard.isLocked());

        nextDay();
        guard.onStartCommand(null, 0, 1);
        tick();
        persist();

        assertEquals(0L, prefs.getUsedSeconds());
        assertFalse("午夜后的服务启动不得把昨日用量转成今日主动锁", guard.isLocked());
        assertFalse(prefs.isLocked());
        assertFalse(prefs.isParentGraceActive());
        assertTrue(countdown().getText().toString().contains("30:00"));
    }

    @Test
    public void persistingBeforeTheNextTickDoesNotWriteBackYesterday() throws Exception {
        prefs.setLocked(true);
        createGuard();
        assertTrue(guard.isLocked());

        nextDay();
        persist();

        assertEquals(0L, prefs.getUsedSeconds());
        tick();
        assertFalse("昨日锁屏必须让出新一天的额度", guard.isLocked());
        assertTrue(countdown().getText().toString().contains("30:00"));
    }

    @Test
    public void serviceDestructionAtMidnightKeepsTheFreshDay() {
        prefs.startParentGrace(true);
        createGuard();
        nextDay();

        controller.destroy();
        controller = null;
        createGuard();

        assertEquals(0L, prefs.getUsedSeconds());
        assertFalse(guard.isLocked());
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES, prefs.getDailyLimitMinutes());
    }

    @Test
    public void checkingOnlyTheGuardCannotKeepYesterdaysGraceAlive() {
        prefs.startParentGrace(true);
        assertFalse(prefs.isGuardActive());
        nextDay();

        assertFalse(prefs.isGuardActive());

        assertFalse("宽限查询自身必须处理跨天，不能短路跳过换日", prefs.isParentGraceActive());
        assertEquals(0L, prefs.getUsedSeconds());
    }

    @Test
    public void theNewDayLocksAgainAfterItsFullThirtyMinutes() throws Exception {
        prefs.startParentGrace(true);
        createGuard();
        nextDay();
        prefs.getBonusSeconds();
        tick();
        power.setIsInteractive(true);
        if (android.os.Build.VERSION.SDK_INT >= 20) {
            Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay();
            shadowOf(display).setState(Display.STATE_ON);
        }

        for (int i = 0; i < 449; i++) {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4));
            tick();
        }
        assertFalse("未看满新一天的 30 分钟不锁定", guard.isLocked());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4));
        tick();

        assertTrue("新一天实际亮屏看满 30 分钟应重新锁定", guard.isLocked());
        assertEquals(1800L, prefs.getUsedSeconds());
        assertFalse(prefs.isParentGraceActive());
    }

    @Test
    public void aParentLockOnTheNewDayIsNotMistakenForYesterday() throws Exception {
        prefs.startParentGrace(true);
        createGuard();
        nextDay();
        AdminSession.grant();

        guard.lockNow();
        tick();

        assertTrue("家长在新一天主动锁定必须保留", guard.isLocked());
        assertTrue(prefs.isLocked());
        assertEquals(0L, prefs.getUsedSeconds());
    }

    private void createGuard() {
        controller = Robolectric.buildService(GuardService.class).create();
        guard = controller.get();
    }

    private void nextDay() {
        // A 26-hour timezone advance always crosses a real calendar boundary, without changing
        // production clocks or reaching into the service's private accounting state.
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+14:00"));
    }

    private void tick() throws Exception {
        invoke("tick");
    }

    private void persist() throws Exception {
        invoke("persist");
    }

    private void invoke(String name) throws Exception {
        Method method = GuardService.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(guard);
    }

    private TextView countdown() throws Exception {
        Field field = GuardService.class.getDeclaredField("countdownText");
        field.setAccessible(true);
        return (TextView) field.get(guard);
    }
}
