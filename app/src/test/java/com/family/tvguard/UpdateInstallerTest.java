package com.family.tvguard;

import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageInstaller;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 33}, shadows = {UpdateInstallerTest.SessionShadow.class,
        UpdateInstallerTest.InstallerShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class UpdateInstallerTest {
    private Application context;
    private ShadowPackageManager packages;
    private PackageInstaller installer;
    private ActivityController<MainActivity> panel;
    private int nextVersion;

    @Implements(PackageInstaller.Session.class)
    public static class SessionShadow extends ShadowPackageInstaller.ShadowSession {
        static boolean revokeOnFsync;
        static boolean expireOnFsync;
        static boolean committed;
        static boolean synced;
        static IntentSender sender;

        @Implementation
        @Override
        protected void fsync(OutputStream output) {
            synced = true;
            if (revokeOnFsync) AdminSession.revoke();
            if (expireOnFsync) ShadowSystemClock.advanceBy(Duration.ofMinutes(4));
        }

        @Implementation
        @Override
        protected void commit(IntentSender statusReceiver) {
            // Real PackageInstaller must not be replaced with the stock Robolectric shadow:
            // that shadow reports immediate success without an installed package or user action.
            committed = true;
            sender = statusReceiver;
        }
    }

    @Implements(PackageInstaller.class)
    public static class InstallerShadow extends ShadowPackageInstaller {
        static PackageInstaller.SessionParams params;
        static boolean failAbandon;

        @Implementation
        @Override
        protected int createSession(PackageInstaller.SessionParams value) throws java.io.IOException {
            params = value;
            return super.createSession(value);
        }

        @Implementation
        @Override
        protected void abandonSession(int id) {
            if (failAbandon) throw new SecurityException("cannot abandon sealed session");
            super.abandonSession(id);
        }
    }

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        packages = shadowOf(context.getPackageManager());
        installer = context.getPackageManager().getPackageInstaller();
        store().edit().clear().commit();
        context.getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs prefs = new Prefs(context);
        prefs.setPassword("135790");
        prefs.setProtectionEnabled(false);
        AdminSession.revoke();
        SessionShadow.revokeOnFsync = false;
        SessionShadow.expireOnFsync = false;
        SessionShadow.committed = false;
        SessionShadow.synced = false;
        SessionShadow.sender = null;
        InstallerShadow.params = null;
        InstallerShadow.failAbandon = false;
        Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.INSTALL_NON_MARKET_APPS, 1);
        packages.setCanRequestPackageInstalls(true);
        nextVersion = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode + 1;
    }

    @After
    public void tearDown() throws Exception {
        InstallerShadow.failAbandon = false;
        UpdateInstaller.cancel(context);
        drain();
        if (panel != null) panel.pause().stop().destroy();
        AdminSession.revoke();
        drain();
    }

    @Test
    public void permissionDoesNotNavigateOrWriteSettingsWhenAlreadyAllowed() {
        assertNull(UpdateInstaller.permissionIntent(context));
        assertEquals(1, Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.INSTALL_NON_MARKET_APPS, 0));
        assertNull(shadowOf(context).getNextStartedActivity());
    }

    @Test
    public void deniedPermissionUsesExplicitRealSystemSettingsNotGoogleStub() {
        denyInstallPermission();
        Intent source = settingsIntent();
        packages.addResolveInfoForIntent(source, settings("com.google.android.tv.frameworkpackagestubs",
                "com.google.android.tv.frameworkpackagestubs.Stubs", true));
        packages.addResolveInfoForIntent(source, settings("com.android.tv.settings",
                "com.android.tv.settings.SecurityActivity", true));

        Intent result = UpdateInstaller.permissionIntent(context);

        assertEquals(source.getAction(), result.getAction());
        assertEquals(source.getData(), result.getData());
        assertEquals("com.android.tv.settings", result.getComponent().getPackageName());
        assertEquals(0, Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.INSTALL_NON_MARKET_APPS, 0));
        assertNull(shadowOf(context).getNextStartedActivity());
    }

    @Test
    public void stubOrThirdPartyPermissionPagesFailClearly() {
        denyInstallPermission();
        packages.addResolveInfoForIntent(settingsIntent(), settings("com.google.android.tv.settings",
                "com.google.android.tv.settings.UnsupportedIntentActivity", true));
        packages.addResolveInfoForIntent(settingsIntent(), settings("third.party",
                "third.party.SecurityActivity", false));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> UpdateInstaller.permissionIntent(context));
        assertTrue(error.getMessage().contains("安装来源"));
    }

    @Test
    public void callbackIsExplicitMutableReusableAndBoundToSessionAndNonce() {
        PendingIntent callback = UpdateInstaller.callback(context, 17, "nonce");
        Intent intent = shadowOf(callback).getSavedIntent();
        assertEquals(UpdateInstaller.ACTION_INSTALL_STATUS, intent.getAction());
        assertEquals(new ComponentName(context, UpdateInstallReceiver.class), intent.getComponent());
        assertEquals(Uri.parse("tvguard-update://" + context.getPackageName() + "/17/nonce"), intent.getData());
        assertTrue(shadowOf(callback).isBroadcast());
        assertEquals(0, shadowOf(callback).getFlags() & PendingIntent.FLAG_ONE_SHOT);
        assertFalse(shadowOf(callback).isImmutable());
        if (Build.VERSION.SDK_INT >= 31) {
            assertNotEquals(0, shadowOf(callback).getFlags() & PendingIntent.FLAG_MUTABLE);
        }
    }

    @Test
    public void startRequiresVisibleParentAndMaintenanceNotJustAnActivity() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertThrows(IllegalStateException.class, () -> UpdateInstaller.start(activity));
        startPanel();
        assertThrows(IllegalStateException.class, () -> UpdateInstaller.start(panel.get()));
        assertTrue(installer.getMySessions().isEmpty());
    }

    @Test
    public void verifiedApkIsWrittenSyncedAndExplicitlyRequiresConfirmationOn31Plus() throws Exception {
        beginInstall();
        assertTrue(SessionShadow.synced);
        assertTrue(SessionShadow.committed);
        assertTrue(UpdateInstaller.hasActiveSession(context));
        assertEquals(context.getPackageName(),
                org.robolectric.util.ReflectionHelpers.getField(InstallerShadow.params, "appPackageName"));
        if (Build.VERSION.SDK_INT >= 31) {
            int requireUserAction = org.robolectric.util.ReflectionHelpers.getField(
                    InstallerShadow.params, "requireUserAction");
            assertEquals(PackageInstaller.SessionParams.USER_ACTION_REQUIRED, requireUserAction);
        }
        assertFalse(new Prefs(context).isProtectionEnabled());
        assertNull(shadowOf(context).getNextStartedService());
    }

    @Test
    public void authorizationRevokedAfterWritingPreventsCommitAndAbandonsSession() throws Exception {
        SessionShadow.revokeOnFsync = true;
        beginInstall();
        assertTrue(SessionShadow.synced);
        assertFalse(SessionShadow.committed);
        assertFalse(UpdateInstaller.hasActiveSession(context));
        assertTrue(installer.getMySessions().isEmpty());
        assertFalse(AdminSession.isMaintenanceActive());
    }

    @Test
    public void leaseExpiredDuringWritingPreventsCommitWithoutAnExplicitRevoke() throws Exception {
        SessionShadow.expireOnFsync = true;
        beginInstall();
        assertTrue(SessionShadow.synced);
        assertFalse(SessionShadow.committed);
        assertFalse(UpdateInstaller.hasActiveSession(context));
        assertTrue(installer.getMySessions().isEmpty());
        assertFalse(AdminSession.isMaintenanceActive());
    }

    @Test
    public void invalidSessionOrNonceOrActionCannotOpenConfirmationOrFinishAttempt() throws Exception {
        beginInstall();
        Intent pending = status(PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT, new Intent("system.confirm"));
        receive(new Intent(pending).putExtra(PackageInstaller.EXTRA_SESSION_ID, 123456));
        receive(new Intent(pending).setData(Uri.parse("tvguard-update://forged/1/token")));
        receive(new Intent(pending).setAction("forged.action"));
        Intent missingStatus = new Intent(pending);
        missingStatus.removeExtra(PackageInstaller.EXTRA_STATUS);
        receive(missingStatus);
        assertTrue(UpdateInstaller.hasActiveSession(context));
        assertNull(shadowOf(panel.get()).getNextStartedActivity());
        assertTrue(AdminSession.isMaintenanceActive());
    }

    @Test
    public void pendingWithoutParentOrWithoutConfirmationIsAbandonedNotLaunchedInBackground() throws Exception {
        beginInstall();
        AdminSession.revoke();
        receive(status(PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT, new Intent("system.confirm")));
        assertFalse(UpdateInstaller.hasActiveSession(context));
        assertTrue(installer.getMySessions().isEmpty());
        assertNull(shadowOf(context).getNextStartedActivity());
        assertNull(shadowOf(panel.get()).getNextStartedActivity());
    }

    @Test
    public void missingConfirmationFailsBackToRetryWithoutStartingService() throws Exception {
        beginInstall();
        receive(status(PackageInstaller.STATUS_PENDING_USER_ACTION));
        assertFalse(UpdateInstaller.hasActiveSession(context));
        assertTrue(installer.getMySessions().isEmpty());
        assertFalse(AdminSession.isMaintenanceActive());
        assertFalse(new Prefs(context).isProtectionEnabled());
        assertNull(shadowOf(context).getNextStartedService());
    }

    @Test
    public void receiverDispatchesTerminalFailureAndEndsMaintenance() throws Exception {
        beginInstall();
        new UpdateInstallReceiver().onReceive(context, status(PackageInstaller.STATUS_FAILURE_INVALID)
                .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "INSTALL_PARSE_FAILED_BAD_MANIFEST"));
        drain();
        assertFalse(UpdateInstaller.hasActiveSession(context));
        assertTrue(installer.getMySessions().isEmpty());
        assertFalse(AdminSession.isMaintenanceActive());
        assertTrue(UpdateInstaller.statusText(context).contains("INSTALL_PARSE_FAILED_BAD_MANIFEST"));
    }

    @Test
    public void successfulCallbackMustObserveInstalledVersionNotJustSystemResult() throws Exception {
        beginInstall();
        receive(status(PackageInstaller.STATUS_SUCCESS));
        assertFalse(UpdateInstaller.hasActiveSession(context));
        assertTrue(UpdateInstaller.statusText(context).contains("版本未更新"));
        assertFalse(AdminSession.isMaintenanceActive());
        assertTrue(context.getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE)
                .contains("readyJson"));
    }

    @Test
    public void successWithObservedNewVersionReconcilesAndRestoresWithoutEnablingProtection() throws Exception {
        beginInstall();
        packages.getInternalMutablePackageInfo(context.getPackageName()).versionCode = nextVersion;
        receive(status(PackageInstaller.STATUS_SUCCESS));
        assertEquals("更新已安装", UpdateInstaller.statusText(context));
        assertFalse(UpdateInstaller.hasActiveSession(context));
        assertFalse(AdminSession.isMaintenanceActive());
        assertFalse(context.getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE)
                .contains("readyJson"));
        assertFalse(new Prefs(context).isProtectionEnabled());
        assertNull(shadowOf(context).getNextStartedService());
    }

    @Test
    public void backgroundConfirmationLeaseStillExpiresWhenNoGuardServiceIsRunning() throws Exception {
        beginInstall();
        receive(status(PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT, new Intent("system.confirm")));
        assertNotNull(shadowOf(panel.get()).getNextStartedActivity());
        panel.pause();
        assertTrue(AdminSession.isMaintenanceActive());
        ShadowSystemClock.advanceBy(Duration.ofMinutes(4));
        drain();
        assertFalse(UpdateInstaller.hasActiveSession(context));
        assertTrue(installer.getMySessions().isEmpty());
        assertFalse(AdminSession.isMaintenanceActive());
        assertNull(GuardService.get());
        assertNull(shadowOf(context).getNextStartedService());
    }

    @Test
    public void recoverCleansPreviousProcessWithoutRestoringParentAuthorization() throws Exception {
        readyApk();
        int id = oldSession(nextVersion);
        UpdateInstaller.recover(context);
        drain();
        assertNull(installer.getSessionInfo(id));
        assertFalse(UpdateInstaller.hasActiveSession(context));
        assertFalse(AdminSession.isActive());
        assertFalse(AdminSession.isMaintenanceActive());
        assertTrue(UpdateInstaller.statusText(context).contains("清理"));
        assertTrue(context.getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE)
                .contains("readyJson"));
    }

    @Test
    public void recoverAfterReplacementRecognizesInstalledVersionAndReconciles() throws Exception {
        readyApk();
        int id = oldSession(nextVersion);
        packages.getInternalMutablePackageInfo(context.getPackageName()).versionCode = nextVersion;
        UpdateInstaller.recover(context);
        drain();
        assertNull(installer.getSessionInfo(id));
        assertEquals("更新已安装", UpdateInstaller.statusText(context));
        assertFalse(context.getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE)
                .contains("readyJson"));
        assertFalse(AdminSession.isActive());
    }

    @Test
    public void recoverDoesNotAbandonUnrelatedPackagesSessions() throws Exception {
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName("other.package");
        int other = installer.createSession(params);
        oldSession(nextVersion);
        UpdateInstaller.recover(context);
        drain();
        assertNotNull(installer.getSessionInfo(other));
    }

    @Test
    public void failedAbandonIsVisibleAndRetainsSessionIdentityForRetry() throws Exception {
        beginInstall();
        int id = store().getInt("session", -1);
        InstallerShadow.failAbandon = true;
        UpdateInstaller.cancel(context);
        drain();
        assertFalse(AdminSession.isMaintenanceActive());
        assertTrue(UpdateInstaller.statusText(context).contains("cannot abandon sealed session"));
        assertEquals(id, store().getInt("session", -1));
        assertNotNull(installer.getSessionInfo(id));
        InstallerShadow.failAbandon = false;
        UpdateInstaller.recover(context);
        drain();
        assertNull(installer.getSessionInfo(id));
    }

    private void startPanel() {
        AdminSession.grant();
        panel = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
        ShadowLooper.idleMainLooper();
        assertTrue(MainActivity.isAuthorizedVisible());
    }

    private void beginInstall() throws Exception {
        readyApk();
        startPanel();
        assertTrue(AdminSession.grantMaintenance());
        UpdateInstaller.start(panel.get());
        drain();
    }

    private void readyApk() throws Exception {
        byte[] bytes = "verified apk test bytes".getBytes(StandardCharsets.UTF_8);
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        }
        JSONObject json = new JSONObject().put("packageName", context.getPackageName())
                .put("versionCode", nextVersion).put("versionName", "test-next")
                .put("minSdk", 21).put("size", bytes.length).put("sha256", hash.toString())
                .put("apkUrl", "https://github.com/zzusec/tv-kid-guard-updates/releases/download/test/tvguard.apk").put("notes", "test");
        UpdateManager.Metadata metadata = UpdateManager.parseMetadata(json.toString(),
                context.getPackageName(), Build.VERSION.SDK_INT);
        File file = UpdateManager.apkFile(context, metadata);
        Files.write(file.toPath(), bytes);
        PackageInfo current = packages.getInternalMutablePackageInfo(context.getPackageName());
        Signature[] signatures = {new Signature("12345678")};
        current.signatures = signatures;
        PackageInfo archive = new PackageInfo();
        archive.packageName = context.getPackageName();
        archive.versionCode = nextVersion;
        archive.versionName = "test-next";
        archive.signatures = signatures;
        archive.applicationInfo = new ApplicationInfo();
        archive.applicationInfo.minSdkVersion = 21;
        if (Build.VERSION.SDK_INT >= 28) {
            current.signingInfo = new SigningInfo();
            shadowOf(current.signingInfo).setSignatures(signatures);
            archive.signingInfo = new SigningInfo();
            shadowOf(archive.signingInfo).setSignatures(signatures);
        }
        packages.setPackageArchiveInfo(file.getAbsolutePath(), archive);
        context.getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE).edit()
                .putString("readyJson", json.toString()).putInt("readyVersion", nextVersion)
                .putInt("readyBaseVersion", current.versionCode).commit();
    }

    private int oldSession(int version) throws Exception {
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int id = installer.createSession(params);
        store().edit().putInt("session", id).putString("token", "old-process")
                .putInt("version", version).commit();
        return id;
    }

    private Intent status(int status) {
        int session = store().getInt("session", -1);
        assertTrue(session >= 0);
        String token = store().getString("token", null);
        return new Intent(shadowOf(UpdateInstaller.callback(context, session, token)).getSavedIntent())
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, session)
                .putExtra(PackageInstaller.EXTRA_STATUS, status);
    }

    private void receive(Intent intent) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        UpdateInstaller.receive(context, intent, finished::countDown);
        assertTrue("receiver completion", finished.await(10, TimeUnit.SECONDS));
        drain();
    }

    private void drain() throws Exception {
        Field field = UpdateInstaller.class.getDeclaredField("WORKER");
        field.setAccessible(true);
        ExecutorService worker = (ExecutorService) field.get(null);
        for (int i = 0; i < 3; i++) {
            worker.submit(() -> { }).get(10, TimeUnit.SECONDS);
            ShadowLooper.idleMainLooper();
        }
    }

    private SharedPreferences store() {
        return context.getSharedPreferences("tvguard_update_install", Context.MODE_PRIVATE);
    }

    private void denyInstallPermission() {
        Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.INSTALL_NON_MARKET_APPS, 0);
        packages.setCanRequestPackageInstalls(false);
    }

    private Intent settingsIntent() {
        return Build.VERSION.SDK_INT >= 26
                ? new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()))
                : new Intent(Settings.ACTION_SECURITY_SETTINGS);
    }

    private ResolveInfo settings(String pkg, String name, boolean system) {
        ResolveInfo result = new ResolveInfo();
        ActivityInfo info = new ActivityInfo();
        info.packageName = pkg;
        info.name = name;
        info.enabled = true;
        info.exported = true;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.enabled = true;
        info.applicationInfo.packageName = pkg;
        info.applicationInfo.flags = system ? ApplicationInfo.FLAG_SYSTEM : 0;
        result.activityInfo = info;
        result.isDefault = true;
        return result;
    }
}
