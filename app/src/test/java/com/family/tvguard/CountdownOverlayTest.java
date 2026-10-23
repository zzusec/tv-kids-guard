package com.family.tvguard;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.view.View;
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
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowWindowManagerImpl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * Two rules about the top-right corner and the day's counter.
 *
 * <p>Standby is not watching. The counter's baseline is {@code uptimeMillis}, which stops while the
 * television suspends, and a single tick may never add more than the ticker interval: a family that
 * leaves the set on standby for hours must get its whole allowance back, and a starved or replayed
 * tick must not quietly spend the day while nobody is looking.
 *
 * <p>An opened day still shows a corner. When the admin spends the password and unlocks the day, the
 * pill stays up reading "今天不限时" instead of vanishing, so a child cannot read a blank corner as
 * "the guard is off" and the parent can see the protection is still running.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33}, qualifiers = "w960dp-h540dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
public class CountdownOverlayTest {
    private static final long BASELINE_USED_SECONDS = 60L;

    private Application context;
    private Prefs prefs;
    private ServiceController<GuardService> serviceController;
    private GuardService service;
    private Method tick;
    private ShadowPowerManager power;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setUsedSeconds(BASELINE_USED_SECONDS);
        prefs.setUnlockedToday(false);
        // Advancing the simulated clock is part of these tests; pin the day so no run can trip the
        // midnight rollover and reset the counter behind the assertions.
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit()
                .putString("day", "9999-12-31").commit();
        ShadowSettings.setCanDrawOverlays(true);
        android.os.PowerManager powerManager =
                (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
        power = Shadow.extract(powerManager);
        power.setIsInteractive(true);
        tick = GuardService.class.getDeclaredMethod("tick");
        tick.setAccessible(true);
    }

    @After
    public void tearDown() {
        if (serviceController != null) {
            serviceController.destroy();
            serviceController = null;
        }
        prefs.setUnlockedToday(false);
    }

    private void createGuard() {
        serviceController = Robolectric.buildService(GuardService.class).create();
        service = GuardService.get();
    }

    // ------------------------------------------------------------------ standby

    @Test
    public void anHourOfSuspendedStandbyAddsNothingToTheDay() throws Exception {
        createGuard();
        long before = usedMillis();
        // The set goes into deep standby for an hour: the elapsed clock and the wall clock both
        // run, uptime does not. Counting on elapsedRealtime charged the child 60 minutes of
        // watching for a blackout nobody was in front of.
        ShadowSystemClock.simulateDeepSleep(Duration.ofHours(1));
        tick.invoke(service);
        assertEquals("待机期间不得计入观看时长", before, usedMillis());
    }

    @Test
    public void aStarvedTickerMayNotDumpMoreThanOneTickIntoTheDay() throws Exception {
        createGuard();
        long before = usedMillis();
        // The process survives but the ticker does not run for twenty minutes (a stalled main
        // thread, a restart). The catch-up is capped, so the worst case is one tick of the
        // ceiling rather than twenty minutes of the allowance.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(20));
        tick.invoke(service);
        long added = usedMillis() - before;
        assertTrue("单个 tick 最多只能补一个周期的时长，实际补了 " + added + "ms",
                added <= GuardService.MAX_COUNTED_TICK_MILLIS);
        assertEquals("补时应当正好封顶在一个计时周期",
                GuardService.MAX_COUNTED_TICK_MILLIS, added);
    }

    @Test
    public void screenOffMinutesAreForgivenRatherThanChargedOnWake() throws Exception {
        createGuard();
        long before = usedMillis();
        power.setIsInteractive(false);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10));
        tick.invoke(service);
        assertEquals("熄屏的十分钟一分钟都不算", before, usedMillis());

        // Coming back to a lit screen re-anchors on the wake, so the dark minutes are never
        // charged retroactively by the next tick. Two seconds of light buys two seconds of
        // allowance, never the ten dark minutes before it.
        power.setIsInteractive(true);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
        tick.invoke(service);
        long addedAfterWake = usedMillis() - before;
        assertTrue("亮屏后只应补上唤醒之后的两秒，实际补了 " + addedAfterWake + "ms",
                addedAfterWake >= 2000L && addedAfterWake <= GuardService.MAX_COUNTED_TICK_MILLIS);
    }

    // ------------------------------------------------------------------ corner

    @Test
    public void anUnlockedDayStillShowsTheCornerNotice() throws Exception {
        createGuard();
        prefs.setUnlockedToday(true);
        tick.invoke(service);
        List<View> pills = pillViews();
        assertEquals("不限时也要保留右上角那一枚提示", 1, pills.size());
        assertEquals(GuardService.UNLIMITED_NOTICE_TEXT, textOf(pills.get(0)));
    }

    @Test
    public void theCornerCountsDownAgainOnceTheDayIsNotUnlocked() throws Exception {
        createGuard();
        prefs.setUnlockedToday(true);
        tick.invoke(service);
        prefs.setUnlockedToday(false);
        tick.invoke(service);
        List<View> pills = pillViews();
        assertEquals("切回配额日不得多出一枚角标", 1, pills.size());
        String text = textOf(pills.get(0));
        assertTrue("配额日右上角应显示剩余时间，实际为：" + text, text.startsWith("剩余 "));
    }

    @Test
    public void repeatedTicksNeverStackTheNoticeIntoSeveralPills() throws Exception {
        createGuard();
        prefs.setUnlockedToday(true);
        for (int i = 0; i < 8; i++) {
            tick.invoke(service);
        }
        List<View> pills = pillViews();
        assertEquals("角标只能有一枚", 1, pills.size());
        assertEquals(GuardService.UNLIMITED_NOTICE_TEXT, textOf(pills.get(0)));
    }

    /**
     * Below Marshmallow the overlay right comes from the manifest, so only the runtime-permission
     * generation can be asked to respect a revoked one.
     */
    @Test
    @Config(sdk = {24, 33})
    public void withoutTheOverlayPermissionNothingIsAttached() throws Exception {
        ShadowSettings.setCanDrawOverlays(false);
        createGuard();
        prefs.setUnlockedToday(true);
        tick.invoke(service);
        assertTrue("没有悬浮窗权限时不得尝试贴角标", attachedViews().isEmpty());
    }

    @Test
    public void aLockedDayShowsNoCornerNotice() throws Exception {
        // The allowance is already spent, so the guard locks rather than decorating the corner.
        prefs.setUsedSeconds(prefs.getDailyLimitMinutes() * 60L);
        createGuard();
        tick.invoke(service);
        assertTrue("锁定后右上角不应留倒计时角标，实际挂着：" + describe(attachedViews()),
                pillViews().isEmpty());
        assertTrue(service.isLocked());
        drainStartedActivities();
        assertTrue(started(LockActivity.class));
    }

    // ----------------------------------------------------------------- helpers

    private long usedMillis() throws Exception {
        Field field = GuardService.class.getDeclaredField("usedMillis");
        field.setAccessible(true);
        return (Long) field.get(service);
    }

    private List<View> attachedViews() {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        return ((ShadowWindowManagerImpl) shadowOf(wm)).getViews();
    }

    /** The corner pill is the only text view the guard attaches at window level; the shield is a box. */
    private List<View> pillViews() {
        List<View> pills = new java.util.ArrayList<>();
        for (View view : attachedViews()) {
            if (view instanceof TextView) {
                pills.add(view);
            }
        }
        return pills;
    }

    private String textOf(View view) {
        return ((TextView) view).getText().toString();
    }

    /** Which windows the guard has attached, for the assertion messages. */
    private String describe(List<View> views) {
        StringBuilder sb = new StringBuilder();
        for (View view : views) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(view.getClass().getSimpleName());
            if (view instanceof TextView) {
                sb.append('"').append(textOf(view)).append('"');
            }
        }
        return sb.toString();
    }

    private final java.util.List<Intent> started = new java.util.ArrayList<>();

    private void drainStartedActivities() {
        Intent intent;
        while ((intent = shadowOf(context).getNextStartedActivity()) != null) {
            started.add(intent);
        }
    }

    private boolean started(Class<?> target) {
        drainStartedActivities();
        for (Intent intent : started) {
            if (intent.getComponent() != null
                    && target.getName().equals(intent.getComponent().getClassName())) {
                return true;
            }
        }
        return false;
    }
}
