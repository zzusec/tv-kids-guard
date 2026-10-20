package com.family.tvguard;

import android.app.Application;
import android.content.Context;
import android.content.Intent;

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

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * Regression: after the parent PIN the gate must hand over to the panel without the quota lock
 * flashing back over it (which looked like being asked for the password a second time).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 33}, qualifiers = "w960dp-h540dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
public class PanelHandoverTest {
    private Application context;
    private Prefs prefs;
    private ServiceController<GuardService> serviceController;
    private ActivityController<AdminAuthActivity> gateController;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword("135790");
        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L);
        prefs.setLocked(true);
        AdminSession.revoke();
        // LockActivity refuses to launch on a dark screen; keep the panel lit like a real TV.
        android.os.PowerManager power =
                (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
        org.robolectric.shadows.ShadowPowerManager powerShadow =
                org.robolectric.shadow.api.Shadow.extract(power);
        powerShadow.setIsInteractive(true);
        serviceController = Robolectric.buildService(GuardService.class).create();
        gateController = Robolectric.buildActivity(AdminAuthActivity.class)
                .create().start().resume().visible().windowFocusChanged(true);
        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS);
    }

    @After
    public void tearDown() {
        if (gateController != null) {
            gateController.pause().stop().destroy();
        }
        serviceController.destroy();
        AdminSession.revoke();
    }

    @Test
    public void successfulPinHandsTheLockOverToThePanelWithoutRelocking() throws Exception {
        AdminAuthActivity gate = gateController.get();
        assertNotNull(gate);
        // Baseline: the guard's own startup lock launch is expected, not the bug.
        drainStartedActivities();
        started.clear();

        // The gate opens the panel on a successful PIN, then finishes.
        for (char digit : "135790".toCharArray()) {
            gate.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_0 + digit - '0'));
        }
        assertTrue(AdminSession.isActive());
        assertTrue(gate.isFinishing());
        gateController.pause();
        drainStartedActivities();

        // The vulnerable window: the guard ticks while the gate is gone and the panel has not
        // resumed yet. Without the handover lease (or the authorized-panel guard) it relaunches
        // the quota lock, which is the "asked for the password twice" bug.
        GuardService service = GuardService.get();
        assertNotNull(service);
        java.lang.reflect.Method tick = GuardService.class.getDeclaredMethod("tick");
        tick.setAccessible(true);
        tick.invoke(service);
        drainStartedActivities();
        assertFalse("锁屏不得在验证页交接到面板的空档里重新弹出", activityStarted(LockActivity.class));
        assertTrue("授权会话必须在交接过��中保持", AdminSession.isActive());

        // And once the panel is up, a further tick must not pull the lock over it either.
        ActivityController<MainActivity> panelController = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().windowFocusChanged(true);
        ShadowLooper.idleMainLooper();
        assertTrue(MainActivity.isAuthorizedVisible());
        started.clear();
        tick.invoke(service);
        drainStartedActivities();
        assertFalse("锁屏不得在已授权面板上重新弹出", activityStarted(LockActivity.class));
        ShadowLooper.idleMainLooper(15, TimeUnit.SECONDS);
        assertTrue("面板持续可见时不得立即撤销家长授权", MainActivity.isAuthorizedVisible());
        assertFalse("面板持续可见时锁屏不得抢回前台", activityStarted(LockActivity.class));
        panelController.pause().stop().destroy();
    }

    private final java.util.List<Intent> started = new java.util.ArrayList<>();

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
}
