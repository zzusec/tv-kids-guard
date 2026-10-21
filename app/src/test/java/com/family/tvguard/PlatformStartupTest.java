package com.family.tvguard;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33})
public class PlatformStartupTest {
    private Application context;
    private Prefs prefs;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = new Prefs(context);
        AdminSession.revoke();
    }

    @After
    public void tearDown() throws Exception {
        ExecutorService worker = ReflectionHelpers.getStaticField(UpdateInstaller.class, "WORKER");
        worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        AdminSession.revoke();
    }

    @Test
    public void onlyTheSupportedUpdateServiceIsEnabled() throws Exception {
        PackageManager packages = context.getPackageManager();
        boolean jobs = packages.getServiceInfo(new ComponentName(context.getPackageName(),
                context.getPackageName() + ".UpdateJobService"),
                PackageManager.GET_DISABLED_COMPONENTS).enabled;
        boolean legacy = packages.getServiceInfo(new ComponentName(context, UpdateLegacyService.class),
                PackageManager.GET_DISABLED_COMPONENTS).enabled;
        assertEquals(Build.VERSION.SDK_INT >= 21, jobs);
        assertEquals(Build.VERSION.SDK_INT < 21, legacy);
    }

    @Test
    public void bootRestoresTheGuardWithoutResettingUsageOrPassword() {
        prefs.setPassword("135790");
        prefs.setUsedSeconds(420L);
        prefs.setLocked(true);

        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        Intent service = shadowOf(context).getNextStartedService();
        assertNotNull(service);
        assertEquals(GuardService.class.getName(), service.getComponent().getClassName());
        assertEquals(420L, prefs.getUsedSeconds());
        assertTrue(prefs.isLocked());
        assertTrue(prefs.checkPassword("135790"));
        assertFalse(AdminSession.isActive());
        assertFalse(AdminSession.isMaintenanceActive());
    }

    @Test
    public void replacementAndWatchdogDoNotReenableDisabledProtection() {
        prefs.setProtectionEnabled(false);

        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        new WatchdogReceiver().onReceive(context, new Intent());

        assertNull(shadowOf(context).getNextStartedService());
        assertFalse(prefs.isProtectionEnabled());
    }

    @Test
    public void unrecognizedBootActionDoesNotStartTheGuard() {
        new BootReceiver().onReceive(context, new Intent("example.SPOOF_BOOT"));
        new BootReceiver().onReceive(context, null);

        assertNull(shadowOf(context).getNextStartedService());
    }
}
