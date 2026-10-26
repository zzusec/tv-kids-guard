package com.family.tvguard;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;

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
import org.robolectric.shadows.ShadowAccessibilityService;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.shadows.ShadowWindowManagerImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * The 2.5.17 parent grace, reported as a loop on the BRAVIA 4K 2015: a parent entered the PIN,
 * backed out of the console into the television's own desktop, opened any other app and was asked
 * for the password again - the 2.5.15 exemption covered one step, and the first window outside the
 * launcher spent it. The rule hx10 agreed is that a parent who authenticated is present until the
 * television next stands by, so the quota lock stays off. The settings page is a separate rule:
 * since 2.5.18 it costs a PIN again once the two minutes that the last correct PIN bought are up.
 *
 * <p>The grace buys no allowance and suspends no accounting. It is granted only by leaving the
 * console after a correct PIN, it never touches the daily limit, the bonus or the lock flag except
 * to stand the lock down, and whatever is watched during it keeps counting into the day. It ends at
 * standby, at {@code 33 立即锁定}, with a new calendar day, and with a power cycle - a grace that
 * survived the television being switched off would be the 今天不限时 that 2.5.12 removed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 33}, qualifiers = "w960dp-h540dp-land",
        shadows = {ParentGraceTest.GraceWindowShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class ParentGraceTest {

    private static final String LAUNCHER = "com.gathin.tvhome";
    private static final String VIDEO_APP = "com.vendor.video";
    private static final String SETTINGS_APP = "com.android.tv.settings";

    private Application context;
    private Prefs prefs;
    private ShadowPowerManager power;
    private ServiceController<GuardService> guardController;
    private GuardService guard;
    private ServiceController<AppLockService> watcherController;
    private AppLockService watcher;
    private ActivityController<MainActivity> panelController;
    private final List<Intent> started = new ArrayList<>();

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword("135790");
        // The day's allowance is spent and the lock is up: the state the loop was reported from.
        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L);
        prefs.setLocked(true);
        prefs.endParentGrace();
        AdminSession.revoke();
        GraceWindowShadow.foregroundPackage = null;
        ShadowSettings.setCanDrawOverlays(true);
        power = shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE));
        power.setIsInteractive(true);
        guardController = Robolectric.buildService(GuardService.class).create();
        guard = GuardService.get();
        assertNotNull(guard);
        watcherController = Robolectric.buildService(AppLockService.class).create();
        watcher = watcherController.get();
        watcher.onServiceConnected();
        ShadowLooper.idleMainLooper();
        assertTrue(AppLockService.isConnected());
        assertTrue("额度用尽且已锁定，锁屏本应生效", prefs.isGuardActive());
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
        prefs.endParentGrace();
        AdminSession.revoke();
        GraceWindowShadow.foregroundPackage = null;
    }

    // ---------------------------------------------------------- how it is granted

    @Test
    public void backingOutOfTheConsoleStartsTheParentGrace() {
        MainActivity panel = openConsole();

        panel.onBackPressed();

        assertFalse("退出后台必须结束家长会话", AdminSession.isActive());
        assertTrue(panel.isFinishing());
        assertTrue("家长输过密码再退出，必须拿到宽限", prefs.isParentGraceActive());
        assertTrue("宽限来自锁屏，所以锁屏要让位", prefs.isParentGraceDeferringLock());
        assertTrue("必须把遥控器交回电视机桌面", startedHome());
        assertFalse("宽限期内锁屏不得抢回前台", activityStarted(LockActivity.class));
    }

    @Test
    public void closingThePanelByShortcutAlsoStartsTheParentGrace() {
        MainActivity panel = openConsole();

        enterDigits(panel, "00");

        assertTrue("00 关闭面板和返回键是同一条出路", prefs.isParentGraceActive());
        assertTrue(panel.isFinishing());
        assertTrue(startedHome());
        assertFalse(activityStarted(LockActivity.class));
    }

    @Test
    public void theGraceIsNotGrantedWithoutLeavingTheConsoleAuthenticated() {
        // A child on the lock screen has no session to leave, so the launcher is just a window to
        // lock over.
        markBaseline();

        window(LAUNCHER);

        assertFalse("没人输入密码就没有宽限", prefs.isParentGraceActive());
        assertTrue("孩子按 HOME 也必须被时长锁屏挡住", activityStarted(LockActivity.class));
        assertFalse(startedHome());
    }

    // ------------------------------------------------------ what the grace covers

    @Test
    public void openingAnotherAppDoesNotBringThePasswordPageBack() {
        // The reported loop: desktop first, then any other app. Under 2.5.15 the second step spent
        // the exemption and the lock came straight back for a new PIN.
        openConsole().onBackPressed();
        window(LAUNCHER);
        markBaseline();

        window(VIDEO_APP);

        assertFalse("进入其他 APP 不再要求输入密码", activityStarted(LockActivity.class));
        assertTrue("宽限不因离开桌面而结束", prefs.isParentGraceActive());
        assertFalse("宽限期内不得盖黑屏", shieldAttached());

        markBaseline();
        ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS);
        assertFalse("持续几秒的计时循环里也不得把锁屏拉回来", activityStarted(LockActivity.class));
        assertFalse(shieldAttached());
    }

    @Test
    public void settingsPagesStayFreeForTheTwoMinutesThePinBuys() {
        // 2.5.18: the settings page is not covered by the grace but by the two minutes that the PIN
        // the parent just typed buys, so on leaving the console it is still free.
        openConsole().onBackPressed();
        assertTrue(prefs.isParentGraceActive());
        markBaseline();

        window(SETTINGS_APP);

        assertFalse("刚输过密码，两分钟内进设置不再要密码", activityStarted(AdminAuthActivity.class));
        assertFalse(activityStarted(LockActivity.class));
    }

    @Test
    public void theSettingsGateReturnsWhenTheTwoMinutesRunOut() {
        // The grace keeps the quota lock off, but it does not keep the settings page free: after two
        // minutes without a PIN, opening settings costs a PIN again even inside the grace.
        openConsole().onBackPressed();
        ShadowLooper.idleMainLooper(Prefs.PIN_WINDOW_MILLIS + 1_000L, TimeUnit.MILLISECONDS);
        assertTrue("宽限还在，锁屏仍被压住", prefs.isParentGraceDeferringLock());
        markBaseline();

        window(SETTINGS_APP);

        assertTrue("两分钟一过，设置页重新要密码", activityStarted(AdminAuthActivity.class));
        assertFalse("设置页要密码不等于把时长锁屏拉回来", activityStarted(LockActivity.class));
    }

    @Test
    public void theSettingsGateComesBackOnceTheGraceHasEnded() {
        // A day that still has allowance, so the grace is only about settings; once both the grace
        // and the two minutes are gone, 2.5.9 is fully back.
        MainActivity panel = openConsole();
        service().setDailyLimitMinutes(Prefs.DEFAULT_LIMIT_MINUTES + 30);
        assertFalse("额度还剩时锁屏解除", service().isLocked());
        panel.onBackPressed();
        assertTrue(prefs.isParentGraceActive());
        window(SETTINGS_APP);
        assertFalse(activityStarted(AdminAuthActivity.class));

        closePanel();
        ShadowLooper.idleMainLooper(Prefs.PIN_WINDOW_MILLIS + 1_000L, TimeUnit.MILLISECONDS);
        power.setIsInteractive(false);
        deliver(Intent.ACTION_SCREEN_OFF);
        ShadowLooper.idleMainLooper();
        power.setIsInteractive(true);
        deliver(Intent.ACTION_SCREEN_ON);
        markBaseline();

        window(SETTINGS_APP);

        assertTrue("宽限和两分钟都结束后，设置页仍然要密码（2.5.9）",
                activityStarted(AdminAuthActivity.class));
    }

    @Test
    public void watchingDuringTheGraceStillCounts() {
        // Standing the lock down is not a pause in the accounting: the corner keeps counting and
        // 今日已看 keeps growing, which is what keeps this from being 今天不限时 again.
        long before = prefs.getUsedSeconds();
        openConsole().onBackPressed();
        window(VIDEO_APP);

        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS);

        assertTrue("宽限期内面板亮着就是在看电视", prefs.getUsedSeconds() > before);
        assertEquals("额度数字没有被宽限改动", Prefs.DEFAULT_LIMIT_MINUTES,
                prefs.getDailyLimitMinutes());
        assertEquals(0L, prefs.getBonusSeconds());
    }

    @Test
    public void standbyDuringTheGraceIsNotViewingTime() {
        openConsole().onBackPressed();
        long before = prefs.getUsedSeconds();

        power.setIsInteractive(false);
        deliver(Intent.ACTION_SCREEN_OFF);
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);
        power.setIsInteractive(true);
        deliver(Intent.ACTION_SCREEN_ON);
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);

        assertEquals("待机不算进今日已看", before, prefs.getUsedSeconds());
    }

    @Test
    public void aGraceOnADayWithAllowanceLeftDoesNotPostponeTheLock() {
        // The boundary hx10 did not ask to move: leaving the console when nothing was locked cannot
        // defer a lock the child has not reached yet.
        MainActivity panel = openConsole();
        service().setDailyLimitMinutes(Prefs.DEFAULT_LIMIT_MINUTES + 30);
        assertFalse("加额度即解除锁屏", service().isLocked());

        panel.onBackPressed();

        assertTrue(prefs.isParentGraceActive());
        assertFalse("额度还剩时宽限不推迟后来的锁", prefs.isParentGraceDeferringLock());

        closePanel();
        markBaseline();
        // Cut the allowance back to what the child already watched: the lock is due now, grace or
        // no grace, because this PIN never came off a password page.
        prefs.setDailyLimitMinutes(Prefs.DEFAULT_LIMIT_MINUTES);
        service().refreshProtection();

        assertTrue("额度用完的那一刻仍然照常锁定", activityStarted(LockActivity.class));
    }

    // ------------------------------------------------------------ what ends it

    @Test
    public void standbyEndsTheGraceAndTheLockComesStraightBack() {
        openConsole().onBackPressed();
        window(VIDEO_APP);
        assertFalse(activityStarted(LockActivity.class));
        markBaseline();

        power.setIsInteractive(false);
        deliver(Intent.ACTION_SCREEN_OFF);
        ShadowLooper.idleMainLooper();

        assertFalse("待机就是宽限的终点", prefs.isParentGraceActive());
        markBaseline();

        power.setIsInteractive(true);
        deliver(Intent.ACTION_SCREEN_ON);
        ShadowLooper.idleMainLooper();

        assertTrue("重新亮屏必须先把时长锁屏放回来", activityStarted(LockActivity.class));
        assertFalse(startedHome());
    }

    @Test
    public void lockingFromTheConsoleSpendsTheGrace() {
        openConsole().onBackPressed();
        assertTrue(prefs.isParentGraceActive());
        markBaseline();

        MainActivity panel = openConsole();
        enterDigits(panel, "33");

        assertFalse("立即锁定必须花掉这次宽限", prefs.isParentGraceActive());
        assertFalse(startedHome());
        assertTrue("立即锁定要回到时长锁屏", activityStarted(LockActivity.class));
    }

    @Test
    public void aPowerCycleEndsTheGrace() {
        openConsole().onBackPressed();
        assertTrue(prefs.isParentGraceActive());

        // The boot clock going behind the grant is what a power cycle looks like from storage: the
        // television was switched off, so the grace is spent by definition. The clock cannot be
        // turned backwards under a PAUSED looper, so the stored grant is moved forwards instead.
        long future = SystemClock.elapsedRealtime() + TimeUnit.HOURS.toMillis(6);
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit()
                .putLong("parent_grace_since", future).commit();

        assertFalse("拔电重开不能留着家长的宽限", prefs.isParentGraceActive());
        assertFalse(prefs.isParentGraceDeferringLock());
        assertTrue("宽限被电源周期花掉后，锁屏重新生效", prefs.isGuardActive());
    }

    @Test
    public void aNewCalendarDayEndsTheGrace() {
        openConsole().onBackPressed();
        assertTrue(prefs.isParentGraceActive());

        // rollOverIfNeeded only fires on a genuinely later date, so drive the day through storage.
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit()
                .putString("day", "2000-01-01").commit();

        assertTrue(prefs.rollOverIfNeeded());
        assertFalse("新的一天不继承昨天的宽限", prefs.isParentGraceActive());
    }

    // ---------------------------------------------------------- how it survives

    @Test
    public void theGraceOutlivesTheGuardProcess() {
        openConsole().onBackPressed();
        window(VIDEO_APP);
        assertTrue(prefs.isParentGraceActive());

        // Android recycles this process on the television; the watchdog then starts it again.
        guardController.destroy();
        guardController = Robolectric.buildService(GuardService.class).create();
        guard = GuardService.get();
        markBaseline();

        window(VIDEO_APP);
        ShadowLooper.idleMainLooper(2, TimeUnit.MILLISECONDS);

        assertTrue("重启管控服务不能丢掉宽限", prefs.isParentGraceActive());
        assertFalse("服务重建后也不得把孩子放回密码页", activityStarted(LockActivity.class));
    }

    @Test
    public void theGraceDoesNotDependOnTheSettingsWatcher() {
        openConsole().onBackPressed();
        markBaseline();

        watcher.onUnbind(new Intent());
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);
        window(VIDEO_APP);

        // The grace is ended by standby, which the timing service watches itself, so losing the
        // watcher cannot strand it - and must not bring the password page back either.
        assertTrue(prefs.isParentGraceActive());
        assertFalse(activityStarted(LockActivity.class));
    }

    @Test
    public void theGraceBuysNoQuota() {
        openConsole().onBackPressed();

        assertTrue(prefs.isParentGraceActive());
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES * 60L, prefs.getUsedSeconds());
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES, prefs.getDailyLimitMinutes());
        assertEquals(0L, prefs.getBonusSeconds());
        assertTrue("额度仍然欠着，宽限只是不弹密码页", prefs.remainingSeconds() <= 0L);
    }

    // ------------------------------------------------------------------ helpers

    private MainActivity openConsole() {
        closePanel();
        // The console is only ever reached through a correct parent PIN: the gate authenticates,
        // grants the session and hands the panel over. That PIN is also what buys the 2.5.18
        // two-minute settings window, so a harness that skipped it would not match the television.
        assertEquals(Prefs.AuthResult.SUCCESS, prefs.authenticate("135790"));
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

    /** The panel only ever talks to the singleton, so read the lock state through it. */
    private GuardService service() {
        GuardService service = GuardService.get();
        assertNotNull(service);
        return service;
    }

    private void enterDigits(android.view.Window.Callback target, String digits) {
        for (int i = 0; i < digits.length(); i++) {
            int keyCode = KeyEvent.KEYCODE_0 + digits.charAt(i) - '0';
            assertTrue(target.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode)));
            target.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        }
    }

    private void window(String pkg) {
        GraceWindowShadow.foregroundPackage = pkg;
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

    private boolean startedHome() {
        drainStartedActivities();
        for (Intent intent : started) {
            if (intent.hasCategory(Intent.CATEGORY_HOME)) {
                return true;
            }
        }
        return false;
    }

    private void drainStartedActivities() {
        Intent intent;
        while ((intent = shadowOf(context).getNextStartedActivity()) != null) {
            started.add(intent);
        }
    }

    /** Forget every launch before this point, so the next assertion only sees new ones. */
    private void markBaseline() {
        drainStartedActivities();
        started.clear();
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

    /** The lock shield is the guard's one opaque black box at window level; the pill is a TextView. */
    private boolean shieldAttached() {
        for (View view : attachedViews()) {
            if (!(view instanceof LinearLayout)) {
                continue;
            }
            Object background = view.getBackground();
            if (background instanceof android.graphics.drawable.ColorDrawable
                    && ((android.graphics.drawable.ColorDrawable) background).getColor() == 0xFF000000) {
                return true;
            }
        }
        return false;
    }

    private List<View> attachedViews() {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        return ((ShadowWindowManagerImpl) shadowOf(wm)).getViews();
    }

    /**
     * The window the family is looking at, reported the way an accessibility service sees it.
     */
    @Implements(AccessibilityService.class)
    public static class GraceWindowShadow extends ShadowAccessibilityService {
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
