package com.family.tvguard;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowAccessibilityService;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * Regression for the BRAVIA 4K 2015 loop: the parent panel is translucent, so the settings page it
 * was opened over stays visible underneath and keeps announcing itself as the foreground window.
 * The watcher used to answer that by raising a fresh gate over the panel, which revoked the session
 * in the panel's onPause, which made the gate really ask for the PIN again — forever.
 *
 * <p>Product rule proven here: once the PIN has been entered, nothing locks the parent out again
 * until the session ends or the parent chooses 立即锁定.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 33}, shadows = {SettingsGateLoopTest.BehindWindowShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class SettingsGateLoopTest {

    private static final String SONY_SETTINGS = "com.android.tv.settings";

    private Application context;
    private Prefs prefs;
    private ServiceController<AppLockService> serviceController;
    private AppLockService service;
    private ActivityController<AdminAuthActivity> gateController;
    private ActivityController<MainActivity> panelController;
    private final List<Intent> started = new ArrayList<>();

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword("135790");
        AdminSession.revoke();
        BehindWindowShadow.foregroundPackage = null;
        // LockActivity refuses to launch on a dark screen; keep it lit like a real television.
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        shadowOf(power).setIsInteractive(true);
        serviceController = Robolectric.buildService(AppLockService.class).create();
        service = serviceController.get();
        service.onServiceConnected();
        ShadowLooper.idleMainLooper();
        markBaseline();
    }

    @After
    public void tearDown() {
        if (panelController != null) {
            panelController.pause().stop().destroy();
        }
        if (gateController != null) {
            gateController.pause().stop().destroy();
        }
        service.onUnbind(new Intent());
        serviceController.destroy();
        AdminSession.revoke();
        BehindWindowShadow.foregroundPackage = null;
    }

    @Test
    public void settingsPageUnderTheOpenPanelNeverDemandsThePinAgain() {
        // The child opens the TV settings page, so the watcher raises the gate.
        window(SONY_SETTINGS);
        assertTrue("设置页必须被家长验证拦住", activityStarted(AdminAuthActivity.class));

        // The parent enters the PIN; the gate hands over to the panel and finishes.
        gateController = Robolectric.buildActivity(AdminAuthActivity.class)
                .create().start().resume().visible().windowFocusChanged(true);
        AdminAuthActivity gate = gateController.get();
        assertNotNull(gate);
        typePin(gate);
        assertTrue("正确的密码必须建立家长会话", AdminSession.isActive());
        assertTrue("验证通过后验证页必须自行退场", gate.isFinishing());
        gateController.pause().stop().destroy();
        gateController = null;

        // The panel opens on top of the settings page it was called from.
        panelController = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().windowFocusChanged(true);
        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS);
        assertTrue(MainActivity.isAuthorizedVisible());
        markBaseline();

        // Observed on the television: the settings window beneath the translucent panel keeps
        // sending window-state events and keeps being the root the accessibility service reads.
        for (int round = 0; round < 8; round++) {
            window(SONY_SETTINGS);
            ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);
        }

        assertFalse("验证通过后不得再次弹出家长验证", activityStarted(AdminAuthActivity.class));
        assertFalse("验证通过后不得弹出时长锁屏", activityStarted(LockActivity.class));
        assertTrue("面板在设置页之上时必须保留家长授权", AdminSession.isActive());
        assertTrue(MainActivity.isAuthorizedVisible());

        // Leaving the panel ends the session, and the same settings page is protected again.
        panelController.pause().stop().destroy();
        panelController = null;
        assertFalse(AdminSession.isActive());
        markBaseline();

        window(SONY_SETTINGS);

        assertTrue("家长离开后设置页必须重新受保护", activityStarted(AdminAuthActivity.class));
    }

    @Test
    public void verifiedSessionKeepsTheQuotaLockAwayUntilTheParentLocksAgain() {
        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L);
        assertTrue(prefs.isGuardActive());
        AdminSession.grant();
        markBaseline();

        window("video.player");
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);

        assertFalse("刚验证过的家长不能被时长锁屏挡住", activityStarted(LockActivity.class));

        // The parent presses 33 (立即锁定): the panel signs the session out and the lock returns.
        AdminSession.revoke();
        window("video.player");

        assertTrue("家长主动锁定后必须立即恢复锁屏", activityStarted(LockActivity.class));
    }

    private void typePin(AdminAuthActivity gate) {
        for (char digit : "135790".toCharArray()) {
            gate.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_0 + (digit - '0')));
        }
    }

    private void window(String pkg) {
        BehindWindowShadow.foregroundPackage = pkg;
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(pkg);
        service.onAccessibilityEvent(event);
        event.recycle();
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

    /**
     * The active window stays the settings page while the translucent panel is on top of it, which
     * is exactly what the real BRAVIA reports. A null root would be the platform's documented
     * behaviour, and the guard must not depend on it.
     */
    @Implements(AccessibilityService.class)
    public static class BehindWindowShadow extends ShadowAccessibilityService {
        static String foregroundPackage;

        @Implementation
        protected android.view.accessibility.AccessibilityNodeInfo getRootInActiveWindow() {
            if (foregroundPackage == null) {
                return null;
            }
            android.view.accessibility.AccessibilityNodeInfo root =
                    android.view.accessibility.AccessibilityNodeInfo.obtain();
            root.setPackageName(foregroundPackage);
            return root;
        }
    }
}
