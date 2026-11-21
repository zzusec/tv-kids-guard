package com.family.tvguard;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.view.Display;
import android.view.WindowManager;

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
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.shadows.ShadowSystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/** A persisted lock is insufficient: video must remain covered until replacement UI is visible. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33}, qualifiers = "w960dp-h540dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
public class LockCoverageTest {
    private Application context;
    private Prefs prefs;
    private ServiceController<GuardService> guardController;
    private GuardService guard;
    private ActivityController<LockActivity> lockController;
    private ActivityController<AdminAuthActivity> gateController;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword("135790");
        prefs.setUsedSeconds(1800L);
        prefs.setLocked(true);
        AdminSession.revoke();
        ShadowSettings.setCanDrawOverlays(true);
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE)).setIsInteractive(true);
        if (Build.VERSION.SDK_INT >= 20) {
            Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay();
            shadowOf(display).setState(Display.STATE_ON);
        }
        guardController = Robolectric.buildService(GuardService.class).create();
        guard = guardController.get();
        assertTrue("复现的起点必须有真实黑屏遮罩", shieldAttached());
        drainStartedActivities();
    }

    @After
    public void tearDown() {
        if (gateController != null) gateController.pause().stop().destroy();
        if (lockController != null) lockController.pause().stop().destroy();
        if (guardController != null) guardController.destroy();
        AdminSession.revoke();
    }

    @Test
    public void resumingBeforeTheLockWindowIsVisibleDoesNotUncoverVideo() throws Exception {
        // onResume precedes the window's first frame and focus. The old implementation removed
        // the shield here, exposing the underlying video before its replacement was drawn.
        lockController = Robolectric.buildActivity(LockActivity.class).create().start().resume();

        assertTrue("仅收到onResume不得撤销黑屏遮罩", shieldAttached());
        assertTrue(guard.isLocked());
        assertTrue(prefs.isGuardActive());
        assertFalse(AdminSession.isActive());
        assertFalse(GuardService.isParentUiOnScreen());
    }

    @Test
    public void aMissingVisibilityHeartbeatRestoresTheShieldAndReassertsTheLock() throws Exception {
        // This is the real callback used by a visibly covering lock window. No window remains to
        // renew it in this test, as when the TV stops reporting the old activity's lifecycle.
        guard.onLockActivityVisibility(true);
        assertFalse(shieldAttached());
        drainStartedActivities();

        ShadowSystemClock.advanceBy(Duration.ofMillis(3100));
        tick();

        assertTrue("三秒未续约不能继续假定旧锁屏挡住了视频", shieldAttached());
        assertTrue("遮罩恢复后还必须重新拉起可交互锁屏", activityStarted(LockActivity.class));
        assertTrue(guard.isLocked());
        assertFalse(prefs.isParentGraceActive());
    }

    @Test
    public void requestingThePinGateCannotUncoverVideoBeforeTheGateAppears() throws Exception {
        // startActivity is only a request. Do not instantiate/resume the gate: its launch may be
        // delayed or denied on a television, so the existing opaque cover is still responsible.
        LockActivity.openParentGate(context);

        assertTrue("必须确实发出了验证页启动请求", activityStarted(AdminAuthActivity.class));
        assertTrue("验证页尚未显示时不得先撤黑屏遮罩", shieldAttached());
        assertFalse("启动请求本身不能作为家长界面已显示的证据", GuardService.isParentUiOnScreen());
        assertTrue(guard.isLocked());
        assertFalse(AdminSession.isActive());
        tick();
        assertTrue(shieldAttached());
    }

    @Test
    public void aSlowPinGateLaunchKeepsCoverageWithoutBeingInterruptedByTheLeaveHint() throws Exception {
        lockController = Robolectric.buildActivity(LockActivity.class).create().start().resume();
        drainStartedActivities();
        LockActivity.openParentGate(context);
        assertTrue(activityStarted(AdminAuthActivity.class));

        lockController.get().onUserLeaveHint();
        org.robolectric.shadows.ShadowLooper.idleMainLooper(350, java.util.concurrent.TimeUnit.MILLISECONDS);

        assertFalse("验证页启动期间不要被300ms回退抢回锁屏", activityStarted(LockActivity.class));
        assertTrue("等待密码页首帧时必须一直保留遮罩", shieldAttached());
        assertFalse(AdminSession.isActive());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6));
        tick();
        assertTrue("启动请求超时必须重新拉起锁屏", activityStarted(LockActivity.class));
        assertTrue(shieldAttached());
    }

    @Test
    public void losingWindowFocusRestoresCoverageWithoutWaitingForPause() throws Exception {
        lockController = Robolectric.buildActivity(LockActivity.class).create().start().resume()
                .visible().windowFocusChanged(true);
        // Supply the renderer's current coverage acknowledgment, then test its invalidation
        // independently of Robolectric's first-frame rendering support.
        guard.onLockActivityVisibility(true);
        assertFalse(shieldAttached());
        drainStartedActivities();

        lockController.windowFocusChanged(false);

        assertTrue("厂商窗口夺焦但未触发onPause时也必须恢复黑屏", shieldAttached());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        tick();
        assertTrue(activityStarted(LockActivity.class));
        assertTrue(guard.isLocked());
        assertFalse(prefs.isParentGraceActive());
    }

    @Test
    public void aDrawnFocusedLockWindowKeepsRenewingItsCoverage() throws Exception {
        lockController = Robolectric.buildActivity(LockActivity.class).create().start().resume()
                .visible().windowFocusChanged(true);
        assertTrue(lockController.get().hasWindowFocus());
        assertTrue(lockController.get().getWindow().getDecorView().isShown());

        // Deliver the real observer callback, then let its after-traversal post run. No direct
        // coverage acknowledgment is used: the window must earn its lease through rendering.
        lockController.get().getWindow().getDecorView().getViewTreeObserver().dispatchOnDraw();
        ShadowLooper.idleMainLooper();

        assertFalse("已绘制并持有焦点的锁屏应接管遮罩", shieldAttached());
        assertTrue(guard.isLocked());
        drainStartedActivities();
        ShadowLooper.idleMainLooper(4, TimeUnit.SECONDS);
        assertFalse("持续可见窗口的心跳必须让三秒租约继续生效", shieldAttached());
        assertFalse("实际窗口持续覆盖时不应反复重启锁屏", activityStarted(LockActivity.class));

        lockController.windowFocusChanged(false);
        assertTrue("同一窗口失焦时必须立即交还黑屏遮罩", shieldAttached());
        assertTrue(guard.isLocked());
    }

    @Test
    public void aPinGateOnlyTakesCoverageAfterItsFirstFocusedFrame() throws Exception {
        gateController = Robolectric.buildActivity(AdminAuthActivity.class).create().start().resume();
        assertTrue("验证页仅resume时不能先露出其下的视频", shieldAttached());
        assertFalse(GuardService.isParentUiOnScreen());
        assertFalse(AdminSession.isActive());

        gateController.visible().windowFocusChanged(true);
        assertTrue(gateController.get().hasWindowFocus());
        assertTrue(gateController.get().getWindow().getDecorView().isShown());
        gateController.get().getWindow().getDecorView().getViewTreeObserver().dispatchOnDraw();
        ShadowLooper.idleMainLooper();

        assertFalse("已绘制且持有焦点的验证页应能正常显示", shieldAttached());
        assertTrue(GuardService.isParentUiOnScreen());
        assertFalse("验证页显示本身不授予家长会话", AdminSession.isActive());
        assertTrue(guard.isLocked());
        ShadowLooper.idleMainLooper(6, TimeUnit.SECONDS);
        assertFalse("验证页持续覆盖时应自行续租，不能被黑罩盖住", shieldAttached());
        assertTrue(GuardService.isParentUiOnScreen());

        gateController.windowFocusChanged(false);
        assertTrue("验证页失焦且未验证密码时必须立刻恢复遮罩", shieldAttached());
        assertFalse(GuardService.isParentUiOnScreen());
        assertTrue(guard.isLocked());
        assertFalse(prefs.isParentGraceActive());
    }

    private void tick() throws Exception {
        Method method = GuardService.class.getDeclaredMethod("tick");
        method.setAccessible(true);
        method.invoke(guard);
    }

    private boolean shieldAttached() throws Exception {
        Field field = GuardService.class.getDeclaredField("shieldAttached");
        field.setAccessible(true);
        return field.getBoolean(guard);
    }

    private void drainStartedActivities() {
        while (shadowOf(context).getNextStartedActivity() != null) { /* discard setup */ }
    }

    private boolean activityStarted(Class<?> target) {
        boolean found = false;
        Intent intent;
        while ((intent = shadowOf(context).getNextStartedActivity()) != null) {
            if (intent.getComponent() != null
                    && target.getName().equals(intent.getComponent().getClassName())) found = true;
        }
        return found;
    }
}
