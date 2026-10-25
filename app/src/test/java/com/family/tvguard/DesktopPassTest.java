package com.family.tvguard;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.PowerManager;
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
import org.robolectric.shadows.ShadowPackageManager;
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
 * The 2.5.15 desktop exemption, reported broken on the BRAVIA 4K 2015: a parent entered the PIN on
 * the quota lock, opened the console, pressed BACK and got a password prompt again instead of the
 * television's own desktop — the lock and the launcher were fighting for the foreground.
 *
 * <p>The rule those tests were agreed on is deliberately narrow. Backing out of the console steps
 * onto the desktop, and only there, the lock stands down. It is one step wide: the first window
 * outside the desktop spends it, and the lock comes straight back with the viewing time still
 * owed. Every other road to the desktop keeps locking — a child who presses HOME on the lock, the
 * screen going into standby, {@code 33 立即锁定}, and cancelling the parent gate.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 33}, qualifiers = "w960dp-h540dp-land",
        shadows = {DesktopPassTest.DesktopWindowShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class DesktopPassTest {

    private static final String LAUNCHER = "com.gathin.tvhome";
    private static final String VIDEO_APP = "com.vendor.video";

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
        // The day's allowance is spent: this is the state a parent leaves the television in.
        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L);
        prefs.setLocked(true);
        DesktopPass.revoke();
        AdminSession.revoke();
        DesktopWindowShadow.foregroundPackage = null;
        ShadowSettings.setCanDrawOverlays(true);
        power = shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE));
        power.setIsInteractive(true);
        // The exemption names the desktop through the HOME intent, never a guessed vendor package.
        registerIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), LAUNCHER);
        guardController = Robolectric.buildService(GuardService.class).create();
        guard = GuardService.get();
        assertNotNull(guard);
        watcherController = Robolectric.buildService(AppLockService.class).create();
        watcher = watcherController.get();
        watcher.onServiceConnected();
        ShadowLooper.idleMainLooper();
        assertTrue(AppLockService.isConnected());
        markBaseline();
    }

    @After
    public void tearDown() {
        if (panelController != null) {
            panelController.pause().stop().destroy();
        }
        if (watcher != null) {
            watcher.onUnbind(new Intent());
        }
        if (watcherController != null) {
            watcherController.destroy();
        }
        if (guardController != null) {
            guardController.destroy();
        }
        DesktopPass.revoke();
        AdminSession.revoke();
        DesktopWindowShadow.foregroundPackage = null;
    }

    // ------------------------------------------------------------- the exemption

    @Test
    public void backingOutOfTheConsoleStepsOntoTheDesktopInsteadOfTheLock() {
        MainActivity panel = openConsole();

        panel.onBackPressed();

        assertFalse("退出后台必须结束家长会话", AdminSession.isActive());
        assertTrue(panel.isFinishing());
        assertTrue("家长从后台退出到桌面必须拿到桌面豁免", DesktopPass.isActive());
        assertTrue("必须把遥控器交回电视机桌面", startedHome());
        assertFalse("豁免期间锁屏不得抢回前台", activityStarted(LockActivity.class));
        assertTrue("桌面豁免不购买观看时长", prefs.isGuardActive());
        assertTrue(service().isLocked());
    }

    @Test
    public void closingThePanelByShortcutAlsoStepsOutToTheDesktop() {
        MainActivity panel = openConsole();

        enterDigits(panel, "00");

        assertTrue("00 关闭面板和返回键是同一条出路", DesktopPass.isActive());
        assertTrue(panel.isFinishing());
        assertTrue(startedHome());
        assertFalse(activityStarted(LockActivity.class));
        assertTrue(prefs.isProtectionEnabled());
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES, prefs.getDailyLimitMinutes());
    }

    @Test
    public void theLockAndTheShieldStayOffWhileTheDesktopIsOnScreen() {
        openConsole().onBackPressed();
        markBaseline();

        // The watcher meets the desktop both ways it can see it, and the guard keeps ticking under
        // it: a window event, the polled active window, and a second of counting.
        for (int round = 0; round < 6; round++) {
            window(LAUNCHER);
            ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);
            guard.refreshProtection();
        }

        assertFalse("坐在桌面上不是看电视，时长锁屏不得抢前台",
                activityStarted(LockActivity.class));
        assertFalse("桌面之上不得再盖黑屏", shieldAttached());
        assertTrue(prefs.isGuardActive());
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES * 60L, prefs.getUsedSeconds());
    }

    @Test
    public void theFirstWindowOutsideTheDesktopSpendsTheExemption() {
        openConsole().onBackPressed();
        window(LAUNCHER);
        assertTrue(DesktopPass.isActive());
        markBaseline();

        window(VIDEO_APP);

        assertFalse("离开桌面即失去豁免", DesktopPass.isActive());
        assertTrue("豁免用完的下一刻时长锁屏必须回来", activityStarted(LockActivity.class));

        markBaseline();
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);
        assertTrue("桌面之外必须重新盖上黑屏", shieldAttached());
        assertFalse(DesktopPass.isActive());
    }

    @Test
    public void aChildOnTheLockScreenStillCannotReachTheDesktop() {
        // No parent ever left the console, so the launcher is just another window to lock over.
        markBaseline();

        window(LAUNCHER);

        assertTrue("没有家长退出豁免时，桌面也必须被时长锁屏挡住",
                activityStarted(LockActivity.class));
        assertFalse("孩子按 HOME 换不来豁免", DesktopPass.isActive());
        assertFalse(startedHome());
    }

    // ------------------------------------------------------------ what ends it

    @Test
    public void standbyEndsTheExemptionAndWakingFindsTheLockAgain() {
        openConsole().onBackPressed();
        window(LAUNCHER);
        assertTrue(DesktopPass.isActive());
        markBaseline();

        power.setIsInteractive(false);
        deliver(Intent.ACTION_SCREEN_OFF);
        ShadowLooper.idleMainLooper();

        assertFalse("待机不是桌面豁免的一部分", DesktopPass.isActive());
        markBaseline();

        power.setIsInteractive(true);
        deliver(Intent.ACTION_SCREEN_ON);
        ShadowLooper.idleMainLooper();

        assertTrue("重新亮屏必须先把时长锁屏放回来", activityStarted(LockActivity.class));
        assertFalse(startedHome());
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES * 60L, prefs.getUsedSeconds());
    }

    @Test
    public void losingTheWatcherTakesTheDesktopBack() {
        openConsole().onBackPressed();
        window(LAUNCHER);
        assertTrue("只有拦截器看着桌面时才有豁免", DesktopPass.isActive());
        markBaseline();

        watcher.onUnbind(new Intent());
        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS);

        assertFalse("拦截器断了就没人结束豁免，必须作废", DesktopPass.isActive());
        assertTrue(activityStarted(LockActivity.class));
    }

    @Test
    public void theExemptionIsRefusedWhileTheWatcherIsDisconnected() {
        MainActivity panel = openConsole();
        watcher.onUnbind(new Intent());
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);
        assertFalse(AppLockService.isConnected());
        markBaseline();

        panel.onBackPressed();

        assertFalse("没有拦截器时不得给出豁免", DesktopPass.isActive());
        assertFalse("没有拦截器时不得把孩子放到桌面上", startedHome());
        assertTrue(activityStarted(LockActivity.class));
        assertTrue(prefs.isGuardActive());
    }

    // --------------------------------------------------- the other ways out still lock

    @Test
    public void lockingFromTheConsoleDoesNotGrantADesktopPass() {
        MainActivity panel = openConsole();

        enterDigits(panel, "33");

        assertFalse("立即锁定不得换来桌面豁免", DesktopPass.isActive());
        assertFalse(startedHome());
        assertTrue("立即锁定必须回到时长锁屏", activityStarted(LockActivity.class));
        assertTrue(prefs.isGuardActive());
    }

    @Test
    public void cancellingTheParentGateStillReturnsToTheLock() {
        ActivityController<AdminAuthActivity> gateController =
                Robolectric.buildActivity(AdminAuthActivity.class)
                        .create().start().resume().visible().windowFocusChanged(true);
        try {
            markBaseline();

            gateController.get().onBackPressed();

            assertFalse("取消验证不产生桌面豁免", DesktopPass.isActive());
            assertFalse(AdminSession.isActive());
            assertTrue(activityStarted(LockActivity.class));
        } finally {
            gateController.pause().stop().destroy();
        }
    }

    @Test
    public void theExemptionIsNotAQuotaReset() {
        openConsole().onBackPressed();

        // Nothing about stepping onto the desktop touches the day's counter, the lock flag, or the
        // limit: the 30 minutes already spent stays spent.
        assertTrue(prefs.isGuardActive());
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES * 60L, prefs.getUsedSeconds());
        assertEquals(Prefs.DEFAULT_LIMIT_MINUTES, prefs.getDailyLimitMinutes());
        assertEquals(0L, prefs.getBonusSeconds());
        assertTrue(service().isLocked());
    }

    // ------------------------------------------------------------------ helpers

    private MainActivity openConsole() {
        AdminSession.grant();
        panelController = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().windowFocusChanged(true);
        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS);
        assertTrue("后台必须处于已授权状态", MainActivity.isAuthorizedVisible());
        markBaseline();
        return panelController.get();
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
        DesktopWindowShadow.foregroundPackage = pkg;
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

    private void registerIntent(Intent intent, String pkg) {
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = pkg;
        info.activityInfo.name = pkg + ".HomeActivity";
        ShadowPackageManager manager = shadowOf(context.getPackageManager());
        manager.addResolveInfoForIntent(intent, info);
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
     * The television's own desktop, reported as the active window the way a launcher does after it
     * takes the foreground.
     */
    @Implements(AccessibilityService.class)
    public static class DesktopWindowShadow extends ShadowAccessibilityService {
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
