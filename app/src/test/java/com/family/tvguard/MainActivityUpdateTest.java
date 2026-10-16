package com.family.tvguard;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.widget.TextView;

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
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 33})
@LooperMode(LooperMode.Mode.PAUSED)
public class MainActivityUpdateTest {
    private Application context;
    private ActivityController<MainActivity> controller;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("tvguard_updates", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs prefs = new Prefs(context);
        prefs.setPassword("135790");
        prefs.setProtectionEnabled(false);
        AdminSession.revoke();
    }

    @After
    public void tearDown() {
        if (ShadowDialog.getLatestDialog() != null) {
            ShadowDialog.getLatestDialog().dismiss();
        }
        if (controller != null) {
            controller.pause().stop().destroy();
        }
        UpdateInstaller.cancel(context);
        AdminSession.revoke();
    }

    @Test
    public void installConfirmationNeedsVisibleParentAndExplicitMaintenance() {
        Intent confirmation = new Intent("android.content.pm.action.CONFIRM_PERMISSIONS");
        assertFalse(MainActivity.launchUpdateConfirmation(confirmation));
        MainActivity activity = startPanel();
        assertFalse(MainActivity.launchUpdateConfirmation(confirmation));
        assertTrue(AdminSession.grantMaintenance());
        assertTrue(MainActivity.launchUpdateConfirmation(confirmation));
        assertNotNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void expectedInstallerPauseKeepsLeaseButReturningEndsMaintenance() {
        startPanel();
        assertTrue(AdminSession.grantMaintenance());
        assertTrue(MainActivity.launchUpdateConfirmation(
                new Intent("android.content.pm.action.CONFIRM_PERMISSIONS")));

        controller.pause();
        assertTrue(AdminSession.isMaintenanceActive());
        controller.resume();

        assertTrue(AdminSession.isActive());
        assertFalse(AdminSession.isMaintenanceActive());
        assertFalse(new Prefs(context).isProtectionEnabled());
    }

    @Test
    public void unrelatedPauseDoesNotKeepMaintenanceAuthorization() {
        startPanel();
        assertTrue(AdminSession.grantMaintenance());

        controller.pause();

        assertFalse(AdminSession.isActive());
        assertFalse(AdminSession.isMaintenanceActive());
        assertFalse(MainActivity.launchUpdateConfirmation(new Intent("unused")));
        controller.resume();
    }

    @Test
    public void updateSchedulingSurvivesProtectionBeingDisabled() {
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        boolean periodic = false;
        boolean immediate = false;
        for (JobInfo job : scheduler.getAllPendingJobs()) {
            periodic |= job.getId() == UpdateManager.PERIODIC_JOB_ID;
            immediate |= job.getId() == UpdateManager.NOW_JOB_ID;
        }
        assertTrue(periodic);
        assertTrue(immediate);
        assertFalse(new Prefs(context).isProtectionEnabled());
        assertFalse(GuardService.get() != null);
    }

    @Test
    public void parentPanelOffersOneUpdateEntryWithoutRestoringUnsupportedSettings() {
        MainActivity activity = startPanel();
        String menu = ((TextView) activity.findViewById(R.id.menu_text)).getText().toString();
        assertTrue(menu.contains("13 检查更新"));
        for (String code : new String[]{"55", "66", "77", "88"}) {
            assertFalse(menu.contains(code));
        }
        assertNotNull(activity.findViewById(R.id.update_text));
    }

    private MainActivity startPanel() {
        AdminSession.grant();
        controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
        ShadowLooper.idleMainLooper();
        return controller.get();
    }
}
