package com.family.tvguard;

import android.app.Application;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/** Parent-controlled activation and recovery after an unexpected administrator deactivation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33})
@LooperMode(LooperMode.Mode.PAUSED)
public class UninstallProtectionTest {

    private Application context;
    private Prefs prefs;
    private ActivityController<MainActivity> controller;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword("135790");
        AdminSession.revoke();
        AdminSession.grant();
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.pause().stop().destroy();
        }
        AdminSession.revoke();
    }

    @Test
    public void parentCanOpenTheSystemActivationPageButItIsNeverSilentlyActivated() {
        registerAdminPage();
        MainActivity activity = startPanel();

        enterDigits(activity, "55");

        Intent next = shadowOf(activity).getNextStartedActivity();
        assertEquals(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN, next.getAction());
        assertEquals(DeviceProtection.admin(context), next.getParcelableExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN));
        assertFalse("The app must not activate the administrator itself",
                DeviceProtection.isAdminActive(context));
        assertTrue(prefs.isProtectionEnabled());
        assertTrue(AdminSession.isActive());
        assertTrue("The system confirmation page needs explicit parent maintenance",
                AdminSession.isMaintenanceActive());
    }

    @Test
    public void returningFromSuccessfulActivationRestoresSettingsProtection() {
        assertActivationReturn(true);
    }

    @Test
    public void cancellingActivationAlsoRestoresSettingsProtection() {
        assertActivationReturn(false);
    }

    @Test
    public void returningAfterActivationAuthorizationExpiresRequiresThePasswordAgain() {
        registerAdminPage();
        MainActivity activity = startPanel();
        enterDigits(activity, "55");
        shadowOf(activity).getNextStartedActivity();
        controller.pause();

        ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.MINUTES);
        controller.resume();

        assertFalse(AdminSession.isActive());
        assertFalse(AdminSession.isMaintenanceActive());
        assertTrue(activity.isFinishing());
        Intent gate = shadowOf(activity).getNextStartedActivity();
        assertEquals(AdminAuthActivity.class.getName(), gate.getComponent().getClassName());
    }

    @Test
    public void activationPageIsNotOfferedWhenTheFirmwareHasNoDeviceAdmin() {
        MainActivity activity = startPanel();

        enterDigits(activity, "55");

        assertNull(shadowOf(activity).getNextStartedActivity());
        assertFalse(DeviceProtection.isAdminActive(context));
    }

    @Test
    public void deactivationOutsideTheParentFlowAsksForThePasswordAgain() {
        registerAdminPage();
        startPanel();

        new GuardDeviceAdmin().onDisabled(context, new Intent(
                "android.app.action.DEVICE_ADMIN_DISABLED"));

        Intent next = shadowOf(context).getNextStartedActivity();
        assertEquals(AdminAuthActivity.class.getName(), next.getComponent().getClassName());
        assertFalse("A stale parent session must not skip the password gate",
                AdminSession.isActive());
        ActivityController<AdminAuthActivity> gate = Robolectric.buildActivity(AdminAuthActivity.class)
                .create().start().resume().visible();
        try {
            assertFalse(gate.get().isFinishing());
            assertFalse(prefs.isUnlockedToday());
            assertNull(shadowOf(gate.get()).getNextStartedActivity());
        } finally {
            gate.pause().stop().destroy();
        }
    }

    @Test
    public void parentsOwnRemovalAndDisabledProtectionDoNotTriggerTheGate() {
        prefs.setProtectionEnabled(false);
        new GuardDeviceAdmin().onDisabled(context, new Intent());
        assertNull(shadowOf(context).getNextStartedActivity());

        prefs.setProtectionEnabled(true);
        AdminSession.grant();
        assertTrue(AdminSession.beginRemoval());
        new GuardDeviceAdmin().onDisabled(context, new Intent());
        assertNull(shadowOf(context).getNextStartedActivity());
    }

    private void assertActivationReturn(boolean activated) {
        registerAdminPage();
        MainActivity activity = startPanel();
        enterDigits(activity, "55");
        shadowOf(activity).getNextStartedActivity();
        controller.pause();
        assertTrue(AdminSession.isActive());
        assertTrue(AdminSession.isMaintenanceActive());
        if (activated) {
            shadowOf(DeviceProtection.dpm(context)).setActiveAdmin(DeviceProtection.admin(context));
        }

        controller.resume();

        assertFalse(AdminSession.isMaintenanceActive());
        assertTrue(AdminSession.isActive());
        assertEquals(activated, DeviceProtection.isAdminActive(context));
        assertTrue(prefs.isProtectionEnabled());
    }

    private MainActivity startPanel() {
        prefs.setProtectionEnabled(true);
        controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
        ShadowLooper.idleMainLooper();
        while (shadowOf(context).getNextStartedService() != null) {
            // UI tests do not create the requested guard service.
        }
        return controller.get();
    }

    private void registerAdminPage() {
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = "com.android.settings";
        info.activityInfo.name = "com.android.settings.DeviceAdminAdd";
        info.activityInfo.exported = true;
        ShadowPackageManager packages = shadowOf(context.getPackageManager());
        packages.addResolveInfoForIntent(
                new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN), info);
    }

    private void enterDigits(MainActivity activity, String digits) {
        for (int i = 0; i < digits.length(); i++) {
            int keyCode = android.view.KeyEvent.KEYCODE_0 + digits.charAt(i) - '0';
            activity.dispatchKeyEvent(new android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN, keyCode));
            activity.dispatchKeyEvent(new android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_UP, keyCode));
        }
        ShadowLooper.idleMainLooper();
    }
}
