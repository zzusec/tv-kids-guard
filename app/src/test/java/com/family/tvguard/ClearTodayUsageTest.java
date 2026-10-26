package com.family.tvguard;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityService;
import org.robolectric.shadows.ShadowDisplay;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowWindowManagerImpl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

/**
 * {@code 77 清零今天已看}, the 2.5.18 panel row. The guard is verified on a real television with a
 * real remote, and every check that runs past the allowance used to lock the set for the rest of the
 * day and spend the family's actual viewing time on a test. hx10's rule is a fifth panel row that
 * zeroes today's counter the moment OK is pressed - no second confirmation, no new limit.
 *
 * <p>What the row is not: it is not extra time. The daily limit and today's bonus minutes come out
 * of the call exactly as they went in, and the only thing that moves is the watched counter, so the
 * corner immediately shows a whole day again. A lock that is up has nothing left to hold once the
 * counter is zero, so it comes down by itself. The row is behind the parent session, so a child on
 * the panel cannot press it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 33}, qualifiers = "w960dp-h540dp-land",
        shadows = {ClearTodayUsageTest.ClearWindowShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class ClearTodayUsageTest {

    private static final String PIN = "135790";
    private static final String VIDEO_APP = "com.vendor.video";
    /** Twenty of the default thirty minutes watched: a day with a quarter of the allowance left. */
    private static final int WATCHED_MINUTES = 20;

    private Application context;
    private Prefs prefs;
    private ShadowPowerManager power;
    private ServiceController<GuardService> guardController;
    private GuardService guard;
    private ServiceController<AppLockService> watcherController;
    private AppLockService watcher;
    private ActivityController<MainActivity> panelController;
    private Method tick;
    private final List<Intent> started = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword(PIN);
        prefs.setUsedSeconds(WATCHED_MINUTES * 60L);
        // Advancing the simulated clock is part of these tests; pin the day so no run can trip the
        // midnight rollover and reset the counter behind the assertions.
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit()
                .putString("day", "9999-12-31").commit();
        prefs.setBonusSeconds(0L);
        prefs.setLocked(false);
        prefs.endParentGrace();
        AdminSession.revoke();
        ClearWindowShadow.foregroundPackage = null;
        ShadowSettings.setCanDrawOverlays(true);
        power = shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE));
        power.setIsInteractive(true);
        // The counter asks two subsystems before it charges a minute, so answer for both.
        panel(Display.STATE_ON);
        tick = GuardService.class.getDeclaredMethod("tick");
        tick.setAccessible(true);
        guardController = Robolectric.buildService(GuardService.class).create();
        guard = GuardService.get();
        assertNotNull(guard);
        watcherController = Robolectric.buildService(AppLockService.class).create();
        watcher = watcherController.get();
        watcher.onServiceConnected();
        ShadowLooper.idleMainLooper();
        assertTrue(AppLockService.isConnected());
        assertFalse("额度还剩时不该有锁屏", prefs.isGuardActive());
        markBaseline();
    }

    @After
    public void tearDown() {
        closePanel();
        if (watcher != null) {
            watcher.onUnbind(new Intent());
        }
        if (watcherController != null) {
            watcherController.destroy();
        }
        if (guardController != null) {
            guardController.destroy();
        }
        AdminSession.revoke();
        prefs.endParentGrace();
        ClearWindowShadow.foregroundPackage = null;
    }

    // ------------------------------------------------------------ what it clears

    @Test
    public void clearingTakesTodayBackToZero() throws Exception {
        AdminSession.grant();

        guard.clearTodayUsage();

        assertEquals(0L, prefs.getUsedSeconds());
        assertEquals(0L, usedMillis());
        assertTrue("清零后剩余就是一整天的额度",
                prefs.remainingSeconds() >= Prefs.DEFAULT_LIMIT_MINUTES * 60L - 1L);
    }

    @Test
    public void clearingKeepsTheDailyLimitAndTheBonusMinutes() {
        prefs.setBonusSeconds(10 * 60L);
        AdminSession.grant();

        guard.clearTodayUsage();

        assertEquals("清零不是改额度", Prefs.DEFAULT_LIMIT_MINUTES, prefs.getDailyLimitMinutes());
        assertEquals("临时加时一分钟都不许多出来", 10 * 60L, prefs.getBonusSeconds());
        assertEquals((Prefs.DEFAULT_LIMIT_MINUTES + 10) * 60L, prefs.remainingSeconds());
    }

    @Test
    public void aClearedDayCountsFromZeroAndNotFromTheOldTotal() throws Exception {
        AdminSession.grant();
        guard.clearTodayUsage();
        assertEquals(0L, usedMillis());

        // A minute in front of a lit panel is a minute of the day - and only that minute, not the
        // twenty that were just wiped. Without re-arming the counting baseline the first tick after
        // a clear charged the gap since the previous tick straight onto the fresh counter.
        ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS);

        long added = usedMillis();
        assertTrue("清零后一分钟只该计一分钟，实际计了 " + added + "ms",
                added >= 55_000L && added <= 70_000L);
    }

    @Test
    public void standbyStillIsNotViewingTimeAfterAClear() throws Exception {
        AdminSession.grant();
        guard.clearTodayUsage();
        markBaseline();

        power.setIsInteractive(false);
        deliver(Intent.ACTION_SCREEN_OFF);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(15));
        tick.invoke(guard);
        power.setIsInteractive(true);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
        tick.invoke(guard);

        long added = usedMillis();
        assertTrue("待机的十五分钟一分钟都不能算，实际 " + added + "ms",
                added >= 2_000L && added <= GuardService.MAX_COUNTED_TICK_MILLIS);
    }

    @Test
    public void theClearedCounterSurvivesTheGuardProcess() throws Exception {
        AdminSession.grant();
        guard.clearTodayUsage();
        AdminSession.revoke();

        // Android recycles the process on the television; the watchdog starts it again off storage.
        guardController.destroy();
        guardController = Robolectric.buildService(GuardService.class).create();
        guard = GuardService.get();
        assertNotNull(guard);

        assertEquals("清零必须落进存储", 0L, prefs.getUsedSeconds());
        assertFalse(guard.isLocked());
    }

    // ---------------------------------------------------------- what it lifts

    @Test
    public void clearingTakesDownAQuotaLockThatIsCurrentlyUp() throws Exception {
        // Spend the allowance through the service so its own counter is what says the day is over.
        AdminSession.grant();
        service().setDailyLimitMinutes(WATCHED_MINUTES);
        assertTrue("额度用完必须已经锁屏", guard.isLocked());
        assertTrue(prefs.isGuardActive());
        markBaseline();

        guard.clearTodayUsage();

        assertFalse("额度重新完整，锁屏没有理由还挂着", guard.isLocked());
        assertFalse(prefs.isLocked());
        assertFalse(prefs.isGuardActive());
        assertEquals("锁定标记必须一起清掉", 0L, prefs.getUsedSeconds());
        markBaseline();

        // The child goes straight back to the video app: the next window is watched, not locked.
        window(VIDEO_APP);
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);

        assertFalse("清零后不得再把锁屏抢回前台", activityStarted(LockActivity.class));
        assertEquals("一枚倒计时角标回到右上角", 1, pillViews().size());
        assertTrue("角标显示剩下一整天，实际：" + textOf(pillViews().get(0)),
                textOf(pillViews().get(0)).startsWith("剩余 "));
    }

    // ------------------------------------------------------------ who may press it

    @Test
    public void clearingWithoutAParentSessionIsRefused() throws Exception {
        AdminSession.revoke();

        try {
            guard.clearTodayUsage();
            fail("没有家长会话不得清零");
        } catch (IllegalStateException expected) {
            // The counter is untouched, so a child cannot erase the day they just spent.
            assertEquals(WATCHED_MINUTES * 60L, prefs.getUsedSeconds());
            assertTrue("服务内存里的计数也没动",
                    Math.abs(usedMillis() - WATCHED_MINUTES * 60_000L) < 10_000L);
        }
    }

    @Test
    public void thePanelOffersClearOnItsFifthRow() {
        MainActivity panel = openConsole();

        Button clear = panel.findViewById(android.R.id.content).findViewWithTag("action_77");

        assertNotNull("面板第五行必须有清零按钮", clear);
        assertTrue(clear.getText().toString().contains("清零今天已看"));
    }

    @Test
    public void remote77OnThePanelClearsTodayAndLiftsTheLock() {
        AdminSession.grant();
        service().setDailyLimitMinutes(WATCHED_MINUTES);
        assertTrue(guard.isLocked());

        MainActivity panel = openConsole();
        enterDigits(panel, "77");

        assertEquals("遥控器连按 77 立即清零", 0L, prefs.getUsedSeconds());
        assertFalse(guard.isLocked());
        assertTrue("面板按完仍在使用中", MainActivity.isAuthorizedVisible());
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);
        assertFalse("清零后锁屏不得抢回前台", activityStarted(LockActivity.class));
    }

    @Test
    public void theFifthRowButtonClearsWhenTheRemotePressesOk() {
        MainActivity panel = openConsole();
        Button clear = panel.findViewById(android.R.id.content).findViewWithTag("action_77");
        assertNotNull(clear);
        clear.requestFocus();

        clear.performClick();

        assertEquals("按 OK 就直接清零，不再二次确认", 0L, prefs.getUsedSeconds());
        assertFalse(guard.isLocked());
    }

    @Test
    public void clearingKeepsTheLimitAndBonusFromThePanel() {
        MainActivity panel = openConsole();
        service().grantBonusMinutes(10);

        enterDigits(panel, "77");

        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES, prefs.getDailyLimitMinutes());
        assertEquals("清零不能顺手把加时抹掉", 10 * 60L, prefs.getBonusSeconds());
        assertEquals(0L, prefs.getUsedSeconds());
    }

    // ------------------------------------------------------------------ helpers

    private MainActivity openConsole() {
        closePanel();
        // The console is only ever reached with a correct parent PIN, which is what grants the
        // session that clearTodayUsage insists on.
        assertEquals(Prefs.AuthResult.SUCCESS, prefs.authenticate(PIN));
        AdminSession.grant();
        panelController = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().windowFocusChanged(true);
        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS);
        assertTrue("后台必须处于已授权状态", MainActivity.isAuthorizedVisible());
        markBaseline();
        return panelController.get();
    }

    private void closePanel() {
        if (panelController != null) {
            panelController.pause().stop().destroy();
            panelController = null;
        }
    }

    /** The panel only ever talks to the singleton, so read the counters through it. */
    private GuardService service() {
        GuardService service = GuardService.get();
        assertNotNull(service);
        return service;
    }

    private long usedMillis() throws Exception {
        Field field = GuardService.class.getDeclaredField("usedMillis");
        field.setAccessible(true);
        return (Long) field.get(guard);
    }

    private void enterDigits(android.view.Window.Callback target, String digits) {
        for (int i = 0; i < digits.length(); i++) {
            int keyCode = android.view.KeyEvent.KEYCODE_0 + digits.charAt(i) - '0';
            assertTrue(target.dispatchKeyEvent(new android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN, keyCode)));
            target.dispatchKeyEvent(new android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_UP, keyCode));
        }
    }

    /** Answers for the television's display subsystem, the counter's second witness. */
    private void panel(int state) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        ShadowDisplay shadow = Shadow.extract(wm.getDefaultDisplay());
        shadow.setState(state);
    }

    private List<View> attachedViews() {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        return ((ShadowWindowManagerImpl) shadowOf(wm)).getViews();
    }

    private List<View> pillViews() {
        List<View> pills = new ArrayList<>();
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

    private void window(String pkg) {
        ClearWindowShadow.foregroundPackage = pkg;
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(pkg);
        watcher.onAccessibilityEvent(event);
        event.recycle();
    }

    /** Delivers a system broadcast to the receiver the timing service registered for it. */
    private void deliver(String action) {
        Intent intent = new Intent(action);
        List<BroadcastReceiver> receivers = shadowOf(context).getReceiversForIntent(intent);
        assertFalse("计时服务必须监听 " + action, receivers.isEmpty());
        for (BroadcastReceiver receiver : receivers) {
            receiver.onReceive(guard, intent);
        }
    }

    private void markBaseline() {
        drainStartedActivities();
        started.clear();
    }

    private void drainStartedActivities() {
        Intent intent;
        while ((intent = shadowOf(context).getNextStartedActivity()) != null) {
            started.add(intent);
        }
    }

    private boolean activityStarted(Class<?> target) {
        drainStartedActivities();
        for (Intent intent : started) {
            if (intent.getComponent() != null
                    && target.getName().equals(intent.getComponent().getClassName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The window the family is looking at, reported the way an accessibility service sees it.
     */
    @Implements(AccessibilityService.class)
    public static class ClearWindowShadow extends ShadowAccessibilityService {
        static String foregroundPackage;

        @Implementation
        protected AccessibilityNodeInfo getRootInActiveWindow() {
            if (foregroundPackage == null) {
                return null;
            }
            AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain();
            root.setPackageName(foregroundPackage);
            return root;
        }
    }
}
