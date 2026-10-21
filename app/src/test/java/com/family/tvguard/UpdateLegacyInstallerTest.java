package com.family.tvguard;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
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
import org.robolectric.shadows.ShadowApplicationPackageManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.util.ReflectionHelpers.getStaticField;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19, shadows = UpdateLegacyInstallerTest.ArchiveShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class UpdateLegacyInstallerTest {
    private Application context;
    private ShadowPackageManager packages;
    private ActivityController<MainActivity> panel;
    private File original;
    private int nextVersion;
    private final byte[] bytes = "API19 verified APK fixture".getBytes(StandardCharsets.UTF_8);

    @Implements(className = "android.app.ApplicationPackageManager")
    public static class ArchiveShadow extends ShadowApplicationPackageManager {
        static PackageInfo archive;
        static ResolveInfo installer;
        static boolean revokeOnStagedValidation;

        @Implementation
        @Override
        protected PackageInfo getPackageArchiveInfo(String path, int flags) {
            if (new File(path).getName().startsWith("tvguard-install-")) {
                if (revokeOnStagedValidation) AdminSession.revoke();
                return archive;
            }
            return super.getPackageArchiveInfo(path, flags);
        }

        @Implementation
        @Override
        protected List<ResolveInfo> queryIntentActivities(Intent intent, int flags) {
            if (Intent.ACTION_INSTALL_PACKAGE.equals(intent.getAction())) {
                return installer == null ? Collections.emptyList() : Collections.singletonList(installer);
            }
            return super.queryIntentActivities(intent, flags);
        }
    }

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        packages = shadowOf(context.getPackageManager());
        store().edit().clear().commit();
        context.getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs prefs = new Prefs(context);
        prefs.setPassword("135790");
        prefs.setProtectionEnabled(false);
        AdminSession.revoke();
        ArchiveShadow.revokeOnStagedValidation = false;
        ArchiveShadow.installer = null;
        Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.INSTALL_NON_MARKET_APPS, 1);
        PackageInfo current = packages.getInternalMutablePackageInfo(context.getPackageName());
        current.signatures = new Signature[]{new Signature("12345678")};
        nextVersion = current.versionCode + 1;
        StringBuilder hash = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        }
        JSONObject json = new JSONObject().put("packageName", context.getPackageName())
                .put("versionCode", nextVersion).put("versionName", "api19-next")
                .put("minSdk", 19).put("size", bytes.length).put("sha256", hash.toString())
                .put("apkUrl", "https://github.com/zzusec/tv-kids-guard/releases/download/test/update.apk")
                .put("notes", "KitKat regression");
        original = UpdateManager.apkFile(context, UpdateManager.parseMetadata(json.toString(), context.getPackageName(), 19));
        Files.write(original.toPath(), bytes);
        PackageInfo archive = new PackageInfo();
        archive.packageName = current.packageName;
        archive.versionCode = nextVersion;
        archive.versionName = "api19-next";
        archive.signatures = current.signatures;
        ArchiveShadow.archive = archive;
        packages.setPackageArchiveInfo(original.getAbsolutePath(), archive);
        context.getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE).edit()
                .putString("readyJson", json.toString()).putInt("readyVersion", nextVersion)
                .putInt("readyBaseVersion", current.versionCode).commit();
    }

    @After
    public void tearDown() throws Exception {
        UpdateInstaller.cancel(context);
        drain();
        if (panel != null) panel.pause().stop().destroy();
        AdminSession.revoke();
        drain();
    }

    @Test
    public void installUsesExplicitSystemFileConfirmationWithoutPackageInstallerClasses() throws Exception {
        registerInstaller(true);
        beginInstall();
        Intent intent = shadowOf(panel.get()).getNextStartedActivity();
        assertNotNull(UpdateInstaller.statusText(context), intent);
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, intent.getAction());
        assertEquals("com.android.packageinstaller", intent.getComponent().getPackageName());
        assertEquals("file", intent.getData().getScheme());
        assertEquals("application/vnd.android.package-archive", intent.getType());
        File staged = new File(intent.getData().getPath());
        assertNotEquals(original, staged);
        assertEquals(context.getFilesDir(), staged.getParentFile());
        assertArrayEquals(bytes, Files.readAllBytes(staged.toPath()));
        assertTrue(UpdateInstaller.hasActiveSession(context));
        assertTrue(AdminSession.isMaintenanceActive());
        assertTrue(original.exists());
        assertNull(shadowOf(context).getNextStartedActivity());
    }

    @Test
    public void cancelledInstallRemovesReadableStagingButKeepsPrivateVerifiedDownload() throws Exception {
        registerInstaller(true);
        beginInstall();
        File staged = new File(shadowOf(panel.get()).getNextStartedActivity().getData().getPath());
        UpdateInstaller.cancel(context);
        drain();
        assertFalse(staged.exists());
        assertFalse(UpdateInstaller.hasActiveSession(context));
        assertFalse(AdminSession.isMaintenanceActive());
        assertTrue(original.exists());
        assertNotNull(UpdateManager.readyUpdate(context));
    }

    @Test
    public void revokedAuthorizationDuringStagedValidationCannotOpenInstaller() throws Exception {
        registerInstaller(true);
        ArchiveShadow.revokeOnStagedValidation = true;
        beginInstall();
        assertNull(shadowOf(panel.get()).getNextStartedActivity());
        assertFalse(UpdateInstaller.hasActiveSession(context));
        assertFalse(store().contains("legacyFile"));
        assertTrue(UpdateInstaller.statusText(context).contains("授权"));
    }

    @Test
    public void thirdPartyCannotReceiveTheStagedApk() throws Exception {
        registerInstaller(false);
        beginInstall();
        assertNull(shadowOf(panel.get()).getNextStartedActivity());
        assertFalse(UpdateInstaller.hasActiveSession(context));
        assertTrue(UpdateInstaller.statusText(context).contains("安装确认页面"));
        assertFalse(store().contains("legacyFile"));
    }

    @Test
    public void leaseStillExpiresAfterLaunchingLegacyConfirmation() throws Exception {
        registerInstaller(true);
        beginInstall();
        File staged = new File(shadowOf(panel.get()).getNextStartedActivity().getData().getPath());
        panel.pause();
        ShadowSystemClock.advanceBy(Duration.ofMinutes(4));
        drain();
        assertFalse(staged.exists());
        assertFalse(UpdateInstaller.hasActiveSession(context));
        assertFalse(AdminSession.isMaintenanceActive());
    }

    @Test
    public void hashAndSignerChecksStillRejectInvalidLegacyUpdates() throws Exception {
        registerInstaller(true);
        Files.write(original.toPath(), new byte[bytes.length]);
        beginInstall();
        assertNull(shadowOf(panel.get()).getNextStartedActivity());
        assertTrue(UpdateInstaller.statusText(context).contains("SHA-256"));
        Files.write(original.toPath(), bytes);
        ArchiveShadow.archive.signatures = new Signature[]{new Signature("87654321")};
        AdminSession.grant();
        assertTrue(AdminSession.grantMaintenance());
        UpdateInstaller.start(panel.get());
        drain();
        assertNull(shadowOf(panel.get()).getNextStartedActivity());
        assertTrue(UpdateInstaller.statusText(context).contains("签名"));
    }

    @Test
    public void recoverAfterProcessDeathCleansOnlyStagedFilesWithoutGrantingAuthorization() throws Exception {
        File staged = new File(context.getFilesDir(), "tvguard-install-00000000-0000-0000-0000-000000000000.apk");
        Files.write(staged.toPath(), bytes);
        store().edit().putInt("session", -1).putString("token", "old")
                .putInt("version", nextVersion).putString("legacyFile", staged.getName()).commit();
        UpdateInstaller.recover(context);
        drain();
        assertFalse(staged.exists());
        assertFalse(store().contains("legacyFile"));
        assertTrue(original.exists());
        assertFalse(AdminSession.isActive());
        assertTrue(UpdateInstaller.statusText(context).contains("清理"));
    }

    @Test
    public void legacyRecoveryClaimsSuccessOnlyAfterObservingInstalledVersion() throws Exception {
        File staged = new File(context.getFilesDir(), "tvguard-install-00000000-0000-0000-0000-000000000000.apk");
        Files.write(staged.toPath(), bytes);
        store().edit().putInt("session", -1).putString("token", "old")
                .putInt("version", nextVersion).putString("legacyFile", staged.getName()).commit();
        packages.getInternalMutablePackageInfo(context.getPackageName()).versionCode = nextVersion;
        UpdateInstaller.recover(context);
        drain();
        assertEquals("更新已安装", UpdateInstaller.statusText(context));
        assertFalse(staged.exists());
        assertFalse(original.exists());
        assertFalse(AdminSession.isActive());
    }

    @Test
    public void legacyPermissionChecksDoNotWriteUnknownSourcesSetting() {
        assertNull(UpdateInstaller.permissionIntent(context));
        assertEquals(1, Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.INSTALL_NON_MARKET_APPS, 0));
        assertThrows(IllegalStateException.class, () -> UpdateInstaller.callback(context, 1, "unused"));
    }

    private void beginInstall() throws Exception {
        AdminSession.grant();
        panel = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
        assertTrue(MainActivity.isAuthorizedVisible());
        assertTrue(AdminSession.grantMaintenance());
        UpdateInstaller.start(panel.get());
        drain();
    }

    private void registerInstaller(boolean system) {
        ActivityInfo info = new ActivityInfo();
        info.packageName = system ? "com.android.packageinstaller" : "third.party";
        info.name = info.packageName + ".PackageInstallerActivity";
        info.enabled = true;
        info.exported = true;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.enabled = true;
        if (system) info.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        ResolveInfo result = new ResolveInfo();
        result.activityInfo = info;
        ArchiveShadow.installer = result;
    }

    private void drain() throws Exception {
        ExecutorService worker = getStaticField(UpdateInstaller.class, "WORKER");
        for (int i = 0; i < 3; i++) {
            worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
            ShadowLooper.idleMainLooper();
        }
    }

    private SharedPreferences store() {
        return context.getSharedPreferences("tvguard_update_install", Context.MODE_PRIVATE);
    }
}
