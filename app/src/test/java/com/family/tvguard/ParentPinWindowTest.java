package com.family.tvguard;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * The settings rule: opening the television's settings costs the parent PIN, one correct PIN keeps
 * the settings pages free for the next two minutes (2.5.18), and a parent who is still working inside
 * the settings menu stays free for as long as they stay there (2.5.19). The reported bug was the
 * second half of that: a parent who paid the PIN and kept operating in the menu was pushed back onto
 * the password page the moment the two minutes ran out.
 *
 * <p>The hold is the watcher's own sighting of a settings surface on a lit panel, so it dies the first
 * time the family looks at anything else, at standby, at {@code 33 立即锁定}, with a new day, and with
 * a power cycle - and an unrefreshed sighting ages out on its own, so a recycled watcher cannot leave
 * the menu open forever.
 *
 * <p>Two boundaries are deliberate. The window and the hold cover the settings gate only: they never
 * lift the quota lock and they never apply to the parent console, whose remote shortcut always
 * verifies. And they are opened by a verified PIN, not by writing one - setting or changing the
 * password through {@link Prefs#setPassword} is not proof that the parent typed the current one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 33}, qualifiers = "w960dp-h540dp-land",
        shadows = {ParentPinWindowTest.PinWindowShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class ParentPinWindowTest {

    private static final String SETTINGS_APP = "com.android.tv.settings";
    private static final String XIAOMI_SETTINGS = "com.xiaomi.mitv.settings";
    private static final String INSTALLER = "com.miui.packageinstaller";
    private static final String VIDEO_APP = "com.vendor.video";
    private static final String PIN = "135790";

    private Application context;
    private Prefs prefs;
    private ShadowPowerManager power;
    private ServiceController<GuardService> guardController;
    private GuardService guard;
    private ServiceController<AppLockService> watcherController;
    private AppLockService watcher;
    private ActivityController<AdminAuthActivity> gateController;
    private final List<Intent> started = new ArrayList<>();

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword(PIN);
        // A day with allowance left, so the settings gate - not the quota lock - is what is being
        // tested. Tests that need the lock set it up themselves.
        prefs.setUsedSeconds(0L);
        prefs.setLocked(false);
        prefs.endParentGrace();
        AdminSession.revoke();
        PinWindowShadow.foregroundPackage = null;
        ShadowSettings.setCanDrawOverlays(true);
        power = shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE));
        power.setIsInteractive(true);
        // The hold is refreshed only while the panel is lit, so these tests have to answer for the
        // display subsystem as well as the power manager.
        panel(Display.STATE_ON);
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
        if (gateController != null) {
            gateController.pause().stop().destroy();
            gateController = null;
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
        AdminSession.revoke();
        prefs.endParentGrace();
        PinWindowShadow.foregroundPackage = null;
    }

    // ------------------------------------------------------- what one PIN buys

    @Test
    public void openingSettingsWithoutAPinCostsTheParentPassword() {
        window(SETTINGS_APP);

        assertTrue("没输过密码，设置页必须弹家长验证", activityStarted(AdminAuthActivity.class));
        assertFalse(prefs.isParentPinWindowActive());
    }

    @Test
    public void aCorrectPinKeepsTheSettingsPageFreeForTwoMinutes() {
        enterPin();

        window(SETTINGS_APP);

        assertTrue("刚输过密码，两分钟内进设置不再要密码", prefs.isParentPinWindowActive());
        assertFalse(activityStarted(AdminAuthActivity.class));
        assertFalse(activityStarted(LockActivity.class));
    }

    @Test
    public void theGateTheFamilyActuallyTypesIsWhatOpensTheWindow() {
        // The window has to be bought through the real verification page, not only through Prefs.
        window(SETTINGS_APP);
        assertTrue("第一次进设置要密码", activityStarted(AdminAuthActivity.class));

        gateController = Robolectric.buildActivity(AdminAuthActivity.class)
                .create().start().resume().visible().windowFocusChanged(true);
        AdminAuthActivity gate = gateController.get();
        assertNotNull(gate);
        typePin(gate);
        assertTrue("正确的密码必须建立家长会话", AdminSession.isActive());
        assertTrue("验证通过后验证页必须自行退场", gate.isFinishing());
        gateController.pause().stop().destroy();
        gateController = null;
        markBaseline();

        // The parent walks on into the settings app without touching the remote again.
        for (int round = 0; round < 4; round++) {
            ShadowLooper.idleMainLooper(20_000L, TimeUnit.MILLISECONDS);
            window(SETTINGS_APP);
        }

        assertTrue(prefs.isParentPinWindowActive());
        assertFalse("验证页里输对的密码必须换来两分钟免密",
                activityStarted(AdminAuthActivity.class));
    }

    @Test
    public void theSettingsGateReturnsOnceTheMenuIsLeftAndTheTwoMinutesAreUp() {
        enterPin();
        window(SETTINGS_APP);
        assertFalse(activityStarted(AdminAuthActivity.class));

        ShadowLooper.idleMainLooper(Prefs.PIN_WINDOW_MILLIS + 1_000L, TimeUnit.MILLISECONDS);
        markBaseline();
        window(VIDEO_APP);
        assertFalse("走出设置菜单，宽限就花掉了", prefs.isSettingsHoldActive());

        markBaseline();
        window(SETTINGS_APP);

        assertFalse("两分钟一到，窗口就关闭", prefs.isParentPinWindowActive());
        assertTrue("离开菜单之后重新进设置要密码", activityStarted(AdminAuthActivity.class));
    }

    @Test
    public void everyCorrectPinReopensTheWindow() {
        // Rolling, not a fixed allotment: a parent who keeps proving they are there keeps working.
        enterPin();
        ShadowLooper.idleMainLooper(90_000L, TimeUnit.MILLISECONDS);
        markBaseline();
        enterPin();

        ShadowLooper.idleMainLooper(90_000L, TimeUnit.MILLISECONDS);
        markBaseline();
        window(SETTINGS_APP);

        assertTrue("九十分钟前的第一次输入早该过期，但第二次刚把窗口拨回来",
                prefs.isParentPinWindowActive());
        assertFalse("窗口内进设置仍不弹密码页", activityStarted(AdminAuthActivity.class));
    }

    @Test
    public void aWrongPinDoesNotOpenTheWindow() {
        assertEquals(Prefs.AuthResult.INCORRECT, prefs.authenticate("000000"));

        assertFalse(prefs.isParentPinWindowActive());
        window(SETTINGS_APP);
        assertTrue("输错密码不能换来免密", activityStarted(AdminAuthActivity.class));
    }

    @Test
    public void writingAPinIsNotEnteringAPin() {
        // Setting or changing the password stores a PIN; it does not prove the current one was typed.
        Prefs fresh = new Prefs(context);
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        fresh.setPassword("246810");

        assertFalse("新建密码不发放两分钟窗口", fresh.isParentPinWindowActive());
    }

    @Test
    public void theWindowCoversEveryProtectedSettingsSurface() {
        enterPin();

        for (String pkg : new String[] {SETTINGS_APP, XIAOMI_SETTINGS, INSTALLER}) {
            markBaseline();
            window(pkg);
            assertFalse(pkg + " 在两分钟内不该弹密码页", activityStarted(AdminAuthActivity.class));
        }

        // ... and once the window is gone each of them costs a PIN again. The family has to leave the
        // menu first: staying inside it is the 2.5.19 hold, not the two minutes (2.5.18).
        window(VIDEO_APP);
        ShadowLooper.idleMainLooper(Prefs.PIN_WINDOW_MILLIS + 1_000L, TimeUnit.MILLISECONDS);
        for (String pkg : new String[] {SETTINGS_APP, XIAOMI_SETTINGS, INSTALLER}) {
            markBaseline();
            window(pkg);
            assertTrue(pkg + " 窗口结束后要重新要密码", activityStarted(AdminAuthActivity.class));
        }
    }

    @Test
    public void anOrdinaryAppIsNeitherGatedByTheWindowNorForgivenByIt() {
        enterPin();
        markBaseline();

        window(VIDEO_APP);

        assertFalse("普通应用本来就不要密码", activityStarted(AdminAuthActivity.class));
        assertFalse(activityStarted(LockActivity.class));
    }

    // --------------------------------------------------- the 2.5.19 settings hold

    @Test
    public void workingThroughTheSettingsMenuNeverAsksForThePinAgain() {
        // The reported bug: a parent paid the PIN, kept operating inside the settings menu, and the
        // password page came back for them the moment the two minutes ran out.
        enterPin();
        window(SETTINGS_APP);
        assertTrue("走进设置菜单的一刻就挂上宽限", prefs.isSettingsHoldActive());
        markBaseline();

        // Eight minutes of walking through the menu, reported the way the watcher sees each page.
        for (int minute = 0; minute < 8; minute++) {
            ShadowLooper.idleMainLooper(60_000L, TimeUnit.MILLISECONDS);
            window(new String[] {SETTINGS_APP, XIAOMI_SETTINGS, INSTALLER}[minute % 3]);
            assertFalse("停在设置菜单里第 " + (minute + 1) + " 分钟不该弹密码页",
                    activityStarted(AdminAuthActivity.class));
        }

        assertFalse("两分钟窗口本身早就走完了", prefs.isParentPinWindowActive());
        assertTrue("人还在菜单里，宽限就还在", prefs.isSettingsHoldActive());
        assertFalse("整段设置操作一次密码都没要", activityStarted(AdminAuthActivity.class));
    }

    @Test
    public void walkingOutOfTheSettingsMenuSpendsTheHoldAtOnce() {
        enterPin();
        window(SETTINGS_APP);
        assertTrue(prefs.isSettingsHoldActive());
        markBaseline();

        window(VIDEO_APP);

        assertFalse("走出设置菜单，宽限当场结束", prefs.isSettingsHoldActive());
        assertTrue("两分钟窗口仍然单独成立", prefs.isParentPinWindowActive());
    }

    @Test
    public void theHoldOnlyStartsBehindACorrectPin() {
        // The menu cannot feed itself: without a paid PIN an unbounded stay buys nothing.
        window(SETTINGS_APP);
        assertTrue("第一次进设置要密码", activityStarted(AdminAuthActivity.class));
        assertFalse("没付过密码就没有宽限", prefs.isSettingsHoldActive());
        markBaseline();

        ShadowLooper.idleMainLooper(3 * 60_000L, TimeUnit.MILLISECONDS);
        window(SETTINGS_APP);

        assertFalse(prefs.isSettingsHoldActive());
        assertTrue("没付过密码，停在设置菜单里多久还是要密码",
                activityStarted(AdminAuthActivity.class));
    }

    @Test
    public void aSightingTheWatcherNeverRefreshesAgesOut() {
        // The hold lives on sightings, and a sighting that stops being reported - a recycled watcher -
        // has to expire, or the settings menu would stand open for the rest of the boot.
        enterPin();
        window(SETTINGS_APP);
        ShadowLooper.idleMainLooper(3 * 60_000L, TimeUnit.MILLISECONDS);
        assertFalse(prefs.isParentPinWindowActive());
        assertTrue("一直停在菜单里，宽限续过了两分钟", prefs.isSettingsHoldActive());

        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit()
                .putLong("parent_settings_seen_at",
                        SystemClock.elapsedRealtime() - Prefs.SETTINGS_HOLD_MILLIS - 1_000L)
                .commit();

        assertFalse("五分钟没人看见设置菜单，宽限就自己结束", prefs.isSettingsHoldActive());
        markBaseline();
        window(SETTINGS_APP);
        assertTrue("陈旧的一眼不构成长期免密", activityStarted(AdminAuthActivity.class));
    }

    @Test
    public void aDarkPanelIsNotAParentStandingInTheSettingsMenu() {
        enterPin();
        window(SETTINGS_APP);
        assertTrue(prefs.isSettingsHoldActive());

        power.setIsInteractive(false);
        panel(Display.STATE_OFF);
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit()
                .putLong("parent_settings_seen_at",
                        SystemClock.elapsedRealtime() - Prefs.SETTINGS_HOLD_MILLIS - 1_000L)
                .commit();

        assertFalse("熄屏期间看不到有人在菜单里", prefs.isSettingsHoldActive());
        markBaseline();
        power.setIsInteractive(true);
        panel(Display.STATE_ON);
        window(SETTINGS_APP);
        assertFalse(activityStarted(AdminAuthActivity.class));
    }

    // ------------------------------------------------------- what it never covers

    @Test
    public void theHoldNeverLiftsTheQuotaLock() {
        enterPin();
        window(SETTINGS_APP);
        assertTrue(prefs.isSettingsHoldActive());

        // The allowance runs out while the parent is still inside the menu.
        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L);
        markBaseline();

        window(SETTINGS_APP);

        assertTrue("额度用尽时，停在设置菜单也照样被时长锁屏拦住",
                activityStarted(LockActivity.class));
        assertFalse("宽限只管设置密码，不管锁屏", activityStarted(AdminAuthActivity.class));
    }

    @Test
    public void theHoldDoesNotHandOverTheParentConsole() {
        enterPin();
        window(SETTINGS_APP);
        assertTrue(prefs.isSettingsHoldActive());
        markBaseline();

        watcher.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0));
        watcher.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0));

        assertTrue("停在设置菜单里也不能连按 0 免密进面板",
                activityStarted(AdminAuthActivity.class));
        assertFalse(AdminSession.isActive());
    }

    @Test
    public void theWindowDoesNotLiftTheQuotaLock() {
        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L);
        prefs.setLocked(true);
        enterPin();
        markBaseline();

        window(VIDEO_APP);

        assertTrue("两分钟免密只管设置页，额度用尽照样锁屏", activityStarted(LockActivity.class));
        assertFalse(activityStarted(AdminAuthActivity.class));
    }

    @Test
    public void theWindowDoesNotOpenTheParentConsole() {
        // The console holds the rules, so its remote shortcut always verifies.
        enterPin();
        markBaseline();

        watcher.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0));
        watcher.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0));

        assertTrue("免密窗口不能让遥控器连按 0 绕过密码直接进面板",
                activityStarted(AdminAuthActivity.class));
        assertFalse("连按 0 只是叫出验证页，不会建立家长会话", AdminSession.isActive());
    }

    // ------------------------------------------------------------- how it ends

    @Test
    public void standbySpendsTheHoldWithTheGrace() {
        enterPin();
        window(SETTINGS_APP);
        assertTrue(prefs.isSettingsHoldActive());

        power.setIsInteractive(false);
        deliver(Intent.ACTION_SCREEN_OFF);
        ShadowLooper.idleMainLooper();

        assertFalse("待机把设置宽限一起花掉", prefs.isSettingsHoldActive());
    }

    @Test
    public void lockingNowSpendsTheHold() {
        enterPin();
        window(SETTINGS_APP);
        assertTrue(prefs.isSettingsHoldActive());

        AdminSession.grant();
        guard.lockNow();

        assertFalse("33 立即锁定把设置宽限也花掉", prefs.isSettingsHoldActive());
    }

    @Test
    public void aPowerCycleEndsTheHold() {
        enterPin();
        window(SETTINGS_APP);
        assertTrue(prefs.isSettingsHoldActive());

        long future = SystemClock.elapsedRealtime() + TimeUnit.HOURS.toMillis(6);
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit()
                .putLong("parent_settings_seen_at", future)
                .putLong("parent_pin_verified_at", future).commit();

        assertFalse("断电重开不能继承上一次开机的设置宽限", prefs.isSettingsHoldActive());
        assertFalse(prefs.isParentPinWindowActive());
        markBaseline();
        window(SETTINGS_APP);
        assertTrue(activityStarted(AdminAuthActivity.class));
    }

    @Test
    public void aNewCalendarDaySpendsTheHold() {
        enterPin();
        window(SETTINGS_APP);
        ShadowLooper.idleMainLooper(Prefs.PIN_WINDOW_MILLIS + 60_000L, TimeUnit.MILLISECONDS);
        assertTrue("一直停在菜单里，宽限续过了两分钟", prefs.isSettingsHoldActive());
        assertFalse(prefs.isParentPinWindowActive());

        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit()
                .putString("day", "2000-01-01").commit();

        assertTrue(prefs.rollOverIfNeeded());
        assertFalse("换天连同它清零的计数器一起结束宽限", prefs.isSettingsHoldActive());
        markBaseline();
        window(SETTINGS_APP);
        assertTrue("新的一天进设置菜单重新要密码", activityStarted(AdminAuthActivity.class));
    }

    @Test
    public void aPowerCycleEndsTheWindow() {
        enterPin();
        assertTrue(prefs.isParentPinWindowActive());

        // A boot clock behind the stored PIN entry is what the television being switched off looks
        // like from storage; it cannot be produced by moving the clock backwards under PAUSED mode.
        long future = SystemClock.elapsedRealtime() + TimeUnit.HOURS.toMillis(6);
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit()
                .putLong("parent_pin_verified_at", future).commit();

        assertFalse("断电重开不能继承上一次开机的免密窗口", prefs.isParentPinWindowActive());
        markBaseline();
        window(SETTINGS_APP);
        assertTrue(activityStarted(AdminAuthActivity.class));
    }

    @Test
    public void standbyEndsTheGraceButNotTheSettingsWindow() {
        // The two rules own different screens: the 2.5.17 grace dies at standby, the two minutes are
        // a clock that the parent's own PIN started, and the television staying on is not a boot.
        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L);
        prefs.setLocked(true);
        prefs.startParentGrace(true);
        enterPin();
        assertTrue(prefs.isParentGraceDeferringLock());

        power.setIsInteractive(false);
        deliver(Intent.ACTION_SCREEN_OFF);
        ShadowLooper.idleMainLooper();

        assertFalse("待机花掉宽限", prefs.isParentGraceActive());
        assertTrue("两分钟窗口按自己的时钟走", prefs.isParentPinWindowActive());
    }

    @Test
    public void theWindowOutlivesTheGuardProcess() {
        enterPin();

        guardController.destroy();
        guardController = null;
        guard = null;
        markBaseline();

        window(SETTINGS_APP);

        assertTrue("免密窗口存在存储里，管控服务被回收也还在", prefs.isParentPinWindowActive());
        assertFalse("服务不在时设置页也不该弹密码页", activityStarted(AdminAuthActivity.class));
    }

    @Test
    public void aNewCalendarDayEndsTheWindowWithItsOwnClock() {
        enterPin();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit()
                .putString("day", "2000-01-01").commit();

        assertTrue(prefs.rollOverIfNeeded());
        assertTrue("换天不是断电，也不是新的开机，窗口仍按两分钟走",
                prefs.isParentPinWindowActive());

        ShadowLooper.idleMainLooper(Prefs.PIN_WINDOW_MILLIS + 1_000L, TimeUnit.MILLISECONDS);
        assertFalse(prefs.isParentPinWindowActive());
        markBaseline();
        window(SETTINGS_APP);
        assertTrue("窗口到自己的两分钟就关，与换天无关", activityStarted(AdminAuthActivity.class));
    }

    // ------------------------------------------------------------------ helpers

    private void enterPin() {
        assertEquals(Prefs.AuthResult.SUCCESS, prefs.authenticate(PIN));
    }

    private void typePin(AdminAuthActivity gate) {
        for (char digit : PIN.toCharArray()) {
            gate.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_0 + (digit - '0')));
        }
    }

    private void window(String pkg) {
        PinWindowShadow.foregroundPackage = pkg;
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

    /** Answers for the television's display subsystem, the hold's second witness. */
    private void panel(int state) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        ShadowDisplay shadow = Shadow.extract(wm.getDefaultDisplay());
        shadow.setState(state);
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
    public static class PinWindowShadow extends ShadowAccessibilityService {
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
