package com.family.tvguard;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 24)
public class AppLockServiceTest {

    private Application context;
    private Prefs prefs;
    private ServiceController<AppLockService> controller;
    private AppLockService service;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(context);
        prefs.setPassword("135790");
        AdminSession.revoke();
        controller = Robolectric.buildService(AppLockService.class).create();
        service = controller.get();
    }

    @After
    public void tearDown() {
        controller.destroy();
        AdminSession.revoke();
    }

    @Test
    public void standaloneUninstallerIsProtectedBeforeQuotaExhaustion() {
        register(new Intent(Intent.ACTION_UNINSTALL_PACKAGE, selfUri()), "vendor.installer");
        window("vendor.installer");
        assertGateStarted();
    }

    @Test
    public void deleteAndPermissionIntentsUseTheApplicationPackageUri() {
        register(new Intent(Intent.ACTION_DELETE, selfUri()), "vendor.delete");
        register(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, selfUri()), "vendor.details");
        register(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, selfUri()), "vendor.overlay");
        for (String name : new String[]{"vendor.delete", "vendor.details", "vendor.overlay"}) {
            window(name);
            assertGateStarted();
        }
    }

    @Test
    public void maintenanceExpirationRechecksSettingsWithoutANewWindowEvent() {
        register(new Intent(Settings.ACTION_SETTINGS), "vendor.settings");
        service.onServiceConnected();
        AdminSession.grant();
        AdminSession.grantMaintenance();
        window("vendor.settings");
        assertNull(shadowOf(context).getNextStartedActivity());

        ShadowLooper.idleMainLooper(3, TimeUnit.MINUTES);

        assertGateStarted();
        assertEquals(0L, prefs.getUsedSeconds());
        assertEquals(false, prefs.isUnlockedToday());
    }

    @Test
    public void watchingUnlockDoesNotAuthorizeSettingsMaintenance() {
        register(new Intent(Settings.ACTION_SETTINGS), "vendor.settings");
        prefs.setUnlockedToday(true);
        window("vendor.settings");
        assertGateStarted();
    }

    @Test
    public void persistedQuotaLockDoesNotNeedAServiceSingleton() {
        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L);
        window("video.player");
        Intent next = shadowOf(context).getNextStartedActivity();
        assertEquals(LockActivity.class.getName(), next.getComponent().getClassName());
    }

    @Test
    public void disabledProtectionDoesNotBlockSettings() {
        register(new Intent(Settings.ACTION_SETTINGS), "vendor.settings");
        prefs.setProtectionEnabled(false);
        window("vendor.settings");
        assertNull(shadowOf(context).getNextStartedActivity());
    }

    private Uri selfUri() {
        return Uri.parse("package:" + context.getPackageName());
    }

    private void register(Intent intent, String pkg) {
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = pkg;
        info.activityInfo.name = pkg + ".SettingsActivity";
        ShadowPackageManager manager = shadowOf(context.getPackageManager());
        manager.addResolveInfoForIntent(intent, info);
    }

    private void window(String pkg) {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(pkg);
        service.onAccessibilityEvent(event);
        event.recycle();
    }

    private void assertGateStarted() {
        Intent next = shadowOf(context).getNextStartedActivity();
        assertEquals(AdminAuthActivity.class.getName(), next.getComponent().getClassName());
    }
}
