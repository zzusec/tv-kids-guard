package com.family.tvguard;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowAccessibilityService;
import org.robolectric.shadows.ShadowApplicationPackageManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.xmlpull.v1.XmlPullParser;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33}, shadows = {
        AppLockServiceTest.ForegroundAccessibilityShadow.class,
        AppLockServiceTest.RetryingPackageManagerShadow.class
})
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
        ForegroundAccessibilityShadow.foregroundPackage = null;
        ForegroundAccessibilityShadow.queryFailure = null;
        RetryingPackageManagerShadow.failSettingsQueries = 0;
        RetryingPackageManagerShadow.settingsQueryCount = 0;
        Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        Settings.Secure.putString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
        controller = Robolectric.buildService(AppLockService.class).create();
        service = controller.get();
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.destroy();
        }
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
        for (String name : new String[]{"vendor.delete", "vendor.details"}) {
            window(name);
            assertGateStarted();
        }
    }

    @Test
    @Config(sdk = {24, 33})
    public void overlayPermissionPageIsProtectedOnSystemsWithRuntimeOverlayPermission() {
        register(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, selfUri()), "vendor.overlay");
        window("vendor.overlay");
        assertGateStarted();
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
    public void knownTvSettingsAndInstallerPackagesAreGatedWithoutAnIntent() {
        // Android TV builds often expose these screens without advertising the phone intents.
        for (String pkg : new String[]{"com.android.tv.settings", "com.android.settings",
                "com.google.android.tv.settings", "com.android.packageinstaller",
                "com.google.android.packageinstaller", "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
                "com.xiaomi.mitv.settings", "com.miui.packageinstaller"}) {
            window(pkg);
            assertGateStarted();
        }
    }

    @Test
    public void disabledProtectionDoesNotBlockSettings() {
        register(new Intent(Settings.ACTION_SETTINGS), "vendor.settings");
        prefs.setProtectionEnabled(false);
        window("vendor.settings");
        assertNull(shadowOf(context).getNextStartedActivity());
    }

    @Test
    public void connectionProtectsTheCurrentSettingsRootWithoutAWindowEvent() {
        register(new Intent(Settings.ACTION_SETTINGS), "vendor.settings");
        foregroundRoot("vendor.settings");

        service.onServiceConnected();
        ShadowLooper.idleMainLooper();

        assertGateStarted();
    }

    @Test
    public void periodicCheckFindsSettingsOpenedWithoutAWindowEvent() {
        register(new Intent(Settings.ACTION_SETTINGS), "vendor.settings");
        foregroundRoot("video.player");
        service.onServiceConnected();
        ShadowLooper.idleMainLooper();
        assertNull(shadowOf(context).getNextStartedActivity());

        foregroundRoot("vendor.settings");
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);

        assertGateStarted();
    }

    @Test
    public void reconnectDoesNotKeepThePreviousOrdinaryAppAsForeground() {
        register(new Intent(Settings.ACTION_SETTINGS), "vendor.settings");
        service.onServiceConnected();
        window("video.player");
        ShadowLooper.idleMainLooper();
        assertNull(shadowOf(context).getNextStartedActivity());
        service.onUnbind(new Intent());

        foregroundRoot("vendor.settings");
        service.onServiceConnected();
        ShadowLooper.idleMainLooper();

        assertGateStarted();
    }

    @Test
    public void reconnectResolvesAHandlerAddedAfterThePreviousConnection() {
        foregroundRoot("vendor.settings");
        service.onServiceConnected();
        window("vendor.settings");
        ShadowLooper.idleMainLooper();
        assertNull(shadowOf(context).getNextStartedActivity());
        int firstConnectionQueries = RetryingPackageManagerShadow.settingsQueryCount;
        service.onUnbind(new Intent());
        register(new Intent(Settings.ACTION_SETTINGS), "vendor.settings");

        service.onServiceConnected();
        ShadowLooper.idleMainLooper();

        assertGateStarted();
        assertTrue(RetryingPackageManagerShadow.settingsQueryCount > firstConnectionQueries);
    }

    @Test
    public void failedSettingsResolutionRetriesWithoutANewWindowEvent() {
        register(new Intent(Settings.ACTION_SETTINGS), "vendor.settings");
        RetryingPackageManagerShadow.failSettingsQueries = 1;
        service.onServiceConnected();
        window("vendor.settings");
        ShadowLooper.idleMainLooper();
        assertNull(shadowOf(context).getNextStartedActivity());
        int failedQueries = RetryingPackageManagerShadow.settingsQueryCount;
        assertTrue(failedQueries > 0);
        assertFalse(TextUtils.isEmpty(AppLockService.lastError()));

        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
        assertEquals("Failed queries must not retry on every foreground tick", failedQueries,
                RetryingPackageManagerShadow.settingsQueryCount);
        assertFalse("An unrelated successful root query must not hide a resolver failure",
                TextUtils.isEmpty(AppLockService.lastError()));
        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS);

        assertGateStarted();
        assertTrue(RetryingPackageManagerShadow.settingsQueryCount > failedQueries);
        assertTrue(TextUtils.isEmpty(AppLockService.lastError()));
    }

    @Test
    public void switchingToAnUnknownPackageRefreshesAnEarlierSuccessfulResolution() {
        window("video.player");
        assertNull(shadowOf(context).getNextStartedActivity());
        register(new Intent(Settings.ACTION_SETTINGS), "vendor.new.settings");

        window("vendor.new.settings");

        assertGateStarted();
    }

    @Test
    public void unchangedUnknownPackageEventuallyDiscoversANewSettingsHandler() {
        service.onServiceConnected();
        window("vendor.new.settings");
        ShadowLooper.idleMainLooper();
        assertNull(shadowOf(context).getNextStartedActivity());
        int initialQueries = RetryingPackageManagerShadow.settingsQueryCount;
        assertTrue(initialQueries > 0);
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);
        assertEquals("Successful unknown-package lookups must retain their normal cache",
                initialQueries, RetryingPackageManagerShadow.settingsQueryCount);
        register(new Intent(Settings.ACTION_SETTINGS), "vendor.new.settings");

        ShadowLooper.idleMainLooper(31, TimeUnit.SECONDS);

        assertGateStarted();
        assertTrue(RetryingPackageManagerShadow.settingsQueryCount > initialQueries);
    }

    @Test
    public void knownSettingsPackageDoesNotResolveAgainOnEveryForegroundTick() {
        register(new Intent(Settings.ACTION_SETTINGS), "vendor.settings");
        service.onServiceConnected();
        window("vendor.settings");
        ShadowLooper.idleMainLooper();
        assertGateStarted();
        int initialQueries = RetryingPackageManagerShadow.settingsQueryCount;

        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);

        assertEquals(initialQueries, RetryingPackageManagerShadow.settingsQueryCount);
        assertGateStarted();
    }

    @Test
    public void verifiedParentSessionIsNotGatedAgainUntilItExpires() {
        register(new Intent(Settings.ACTION_SETTINGS), "vendor.settings");
        AdminSession.grant();

        window("vendor.settings");

        assertTrue(AdminSession.isActive());
        assertFalse(AdminSession.isMaintenanceActive());
        assertNull("密码已经输入过，就不许再要求家长输入第二次",
                shadowOf(context).getNextStartedActivity());

        // The session is a lease, not a pass: after it expires the settings page is protected again.
        ShadowLooper.idleMainLooper(3, TimeUnit.MINUTES);
        window("vendor.settings");
        assertGateStarted();
    }

    @Test
    public void endingMaintenanceKeepsTheVerifiedParentSessionQuiet() {
        register(new Intent(Settings.ACTION_SETTINGS), "vendor.settings");
        foregroundRoot("vendor.settings");
        AdminSession.grant();
        assertTrue(AdminSession.grantMaintenance());
        service.onServiceConnected();
        window("vendor.settings");
        ShadowLooper.idleMainLooper();
        assertNull(shadowOf(context).getNextStartedActivity());

        AdminSession.endMaintenance();
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);

        assertTrue(AdminSession.isActive());
        assertNull("退出维护不得让已验证的家长再输一次密码",
                shadowOf(context).getNextStartedActivity());

        // Suspension is tied to the session, never to the settings page itself.
        ShadowLooper.idleMainLooper(3, TimeUnit.MINUTES);
        assertGateStarted();
    }

    @Test
    public void disabledProtectionDoesNotBlockTheCurrentSettingsRoot() {
        register(new Intent(Settings.ACTION_SETTINGS), "vendor.settings");
        foregroundRoot("vendor.settings");
        prefs.setProtectionEnabled(false);

        service.onServiceConnected();
        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS);

        assertNull(shadowOf(context).getNextStartedActivity());
    }

    @Test
    public void persistedQuotaLockProtectsTheCurrentRootWithoutAWindowEvent() {
        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L);
        foregroundRoot("video.player");

        service.onServiceConnected();
        ShadowLooper.idleMainLooper();

        Intent next = shadowOf(context).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(LockActivity.class.getName(), next.getComponent().getClassName());
    }

    @Test
    public void enabledComponentRequiresTheAccessibilityMasterSwitch() {
        ComponentName component = new ComponentName(context, AppLockService.class);
        Settings.Secure.putString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, component.flattenToString());

        assertFalse(AppLockService.isEnabled(context));
        Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        assertTrue(AppLockService.isEnabled(context));
        Settings.Secure.putString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "vendor.app/vendor.app.OtherService");
        assertFalse(AppLockService.isEnabled(context));
    }

    @Test
    public void enabledComponentAcceptsItsShortNameAmongOtherServices() {
        ComponentName component = new ComponentName(context, AppLockService.class);
        Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.putString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "vendor.app/vendor.app.OtherService:" + component.flattenToShortString());

        assertTrue(AppLockService.isEnabled(context));
    }

    @Test
    public void connectedStateTracksConnectionUnbindingAndDestructionNotTheEnabledList() {
        ComponentName component = new ComponentName(context, AppLockService.class);
        Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.putString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, component.flattenToString());
        assertTrue(AppLockService.isEnabled(context));
        assertFalse(AppLockService.isConnected());

        service.onServiceConnected();
        assertTrue(AppLockService.isConnected());
        service.onUnbind(new Intent());
        assertFalse(AppLockService.isConnected());
        assertTrue(AppLockService.isEnabled(context));

        service.onServiceConnected();
        assertTrue(AppLockService.isConnected());
        controller.destroy();
        controller = null;
        assertFalse(AppLockService.isConnected());
    }

    @Test
    public void rootQueryFailureIsVisibleAndClearsAfterForegroundRecovery() {
        register(new Intent(Settings.ACTION_SETTINGS), "vendor.settings");
        foregroundRoot("vendor.settings");
        ForegroundAccessibilityShadow.queryFailure =
                new IllegalStateException("Temporary active-window query failure");
        service.onServiceConnected();
        ShadowLooper.idleMainLooper();
        assertFalse(TextUtils.isEmpty(AppLockService.lastError()));
        assertNull(shadowOf(context).getNextStartedActivity());

        ForegroundAccessibilityShadow.queryFailure = null;
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);

        assertGateStarted();
        assertTrue(TextUtils.isEmpty(AppLockService.lastError()));
    }

    @Test
    public void accessibilityConfigurationAllowsRetrievingTheActiveWindowRoot() throws Exception {
        XmlResourceParser parser = context.getResources().getXml(R.xml.accessibility_service);
        try {
            int event;
            do {
                event = parser.next();
            } while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT);

            assertEquals("accessibility-service", parser.getName());
            assertTrue(parser.getAttributeBooleanValue("http://schemas.android.com/apk/res/android",
                    "canRetrieveWindowContent", false));
        } finally {
            parser.close();
        }
    }

    @Test
    public void securitySettingsHandlerAddedAfterInitialResolutionIsProtected() {
        window("video.player");
        register(new Intent(Settings.ACTION_SECURITY_SETTINGS), "vendor.security");

        window("vendor.security");

        assertGateStarted();
    }

    @Test
    public void deviceAdminHandlerAddedAfterInitialResolutionIsProtected() {
        window("video.player");
        register(new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN), "vendor.deviceadmin");

        window("vendor.deviceadmin");

        assertGateStarted();
    }

    @Test
    public void unavailableRootDoesNotReleaseAnAlreadyProtectedSettingsPage() {
        register(new Intent(Settings.ACTION_SETTINGS), "vendor.settings");
        service.onServiceConnected();
        window("vendor.settings");
        ShadowLooper.idleMainLooper();
        assertGateStarted();
        drainStartedActivities();

        foregroundRoot(null);
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
        assertGateStarted();
        drainStartedActivities();

        ForegroundAccessibilityShadow.queryFailure =
                new IllegalStateException("Temporary active-window query failure");
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);

        assertGateStarted();
        assertFalse(TextUtils.isEmpty(AppLockService.lastError()));
        assertFalse(AdminSession.isActive());
        assertFalse(AdminSession.isMaintenanceActive());
    }

    @Test
    public void unbindingDoesNotUnlockQuotaAndReconnectionRestoresTheLock() {
        prefs.setUsedSeconds(Prefs.DEFAULT_LIMIT_MINUTES * 60L);
        service.onServiceConnected();
        window("video.player");
        ShadowLooper.idleMainLooper();
        Intent first = shadowOf(context).getNextStartedActivity();
        assertNotNull(first);
        assertEquals(LockActivity.class.getName(), first.getComponent().getClassName());
        drainStartedActivities();

        service.onUnbind(new Intent());
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);
        assertFalse(AppLockService.isConnected());
        assertNull(shadowOf(context).getNextStartedActivity());
        assertTrue(prefs.isGuardActive());
        assertFalse(prefs.isUnlockedToday());
        assertFalse(AdminSession.isActive());
        assertFalse(AdminSession.isMaintenanceActive());

        service.onServiceConnected();
        ShadowLooper.idleMainLooper();

        Intent next = shadowOf(context).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(LockActivity.class.getName(), next.getComponent().getClassName());
    }

    private void drainStartedActivities() {
        while (shadowOf(context).getNextStartedActivity() != null) {
            // Each foreground tick may enqueue the same gate; discard only previously checked ones.
        }
    }

    private void foregroundRoot(String pkg) {
        ForegroundAccessibilityShadow.foregroundPackage = pkg;
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
        foregroundRoot(pkg);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(pkg);
        service.onAccessibilityEvent(event);
        event.recycle();
    }

    private void assertGateStarted() {
        Intent next = shadowOf(context).getNextStartedActivity();
        assertNotNull("The settings surface must launch the parent gate", next);
        assertEquals(AdminAuthActivity.class.getName(), next.getComponent().getClassName());
    }

    @Implements(AccessibilityService.class)
    public static class ForegroundAccessibilityShadow extends ShadowAccessibilityService {
        static String foregroundPackage;
        static RuntimeException queryFailure;

        @Implementation
        protected AccessibilityNodeInfo getRootInActiveWindow() {
            if (queryFailure != null) {
                throw queryFailure;
            }
            if (foregroundPackage == null) {
                return null;
            }
            AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain();
            root.setPackageName(foregroundPackage);
            return root;
        }
    }

    @Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
    public static class RetryingPackageManagerShadow extends ShadowApplicationPackageManager {
        static int failSettingsQueries;
        static int settingsQueryCount;

        @Implementation
        protected List<ResolveInfo> queryIntentActivities(Intent intent, int flags) {
            if (Settings.ACTION_SETTINGS.equals(intent.getAction())) {
                settingsQueryCount++;
                if (failSettingsQueries > 0) {
                    failSettingsQueries--;
                    throw new IllegalStateException("Temporary settings resolver failure");
                }
            }
            return super.queryIntentActivities(intent, flags);
        }
    }
}
