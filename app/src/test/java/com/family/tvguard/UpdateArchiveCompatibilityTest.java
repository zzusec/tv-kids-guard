package com.family.tvguard;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Keep the API-24 archive field boundary distinct from the metadata's SDK compatibility check. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 23, 24, 33})
public class UpdateArchiveCompatibilityTest {
    private PackageInfo installed;
    private PackageInfo archive;

    @Before
    public void setUp() {
        installed = info(1);
        archive = info(2);
        if (Build.VERSION.SDK_INT >= 24) {
            archive.applicationInfo = new ApplicationInfo();
            archive.applicationInfo.minSdkVersion = 19;
        }
    }

    @Test
    public void supportedArchiveUsesOnlyFieldsAvailableOnTheRunningSdk() throws Exception {
        UpdateManager.validateArchive(installed, archive, metadata(19), Build.VERSION.SDK_INT);
        if (Build.VERSION.SDK_INT < 24) assertNull(archive.applicationInfo);
    }

    @Test
    public void minimumSdkAboveDeviceRemainsRejectedOnEverySdk() throws Exception {
        UpdateManager.Metadata unsupported = metadata(Build.VERSION.SDK_INT + 1);
        IOException error = assertThrows(IOException.class,
                () -> UpdateManager.validateArchive(installed, archive, unsupported, Build.VERSION.SDK_INT));
        assertTrue(error.getMessage().contains("不支持此 Android 版本"));
    }

    @Test
    @Config(sdk = {24, 33})
    public void readableArchiveMinimumMustExactlyMatchMetadataAndSupportDevice() throws Exception {
        UpdateManager.Metadata metadata = metadata(19);
        archive.applicationInfo.minSdkVersion = 20;
        assertThrows(IOException.class, () -> UpdateManager.validateArchive(installed, archive, metadata, Build.VERSION.SDK_INT));
        archive.applicationInfo.minSdkVersion = Build.VERSION.SDK_INT + 1;
        assertThrows(IOException.class, () -> UpdateManager.validateArchive(installed, archive, metadata, Build.VERSION.SDK_INT));
        archive.applicationInfo = null;
        assertThrows(IOException.class, () -> UpdateManager.validateArchive(installed, archive, metadata, Build.VERSION.SDK_INT));
    }

    private PackageInfo info(int version) {
        PackageInfo info = new PackageInfo();
        info.packageName = "com.family.tvguard";
        info.versionCode = version;
        info.versionName = "fixture";
        info.signatures = new Signature[]{new Signature("12345678")};
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo = new SigningInfo();
            shadowOf(info.signingInfo).setSignatures(info.signatures);
        }
        return info;
    }

    private UpdateManager.Metadata metadata(int minSdk) throws Exception {
        JSONObject json = new JSONObject().put("packageName", "com.family.tvguard")
                .put("versionCode", 2).put("versionName", "fixture").put("minSdk", minSdk)
                .put("size", 1).put("sha256", new String(new char[64]).replace('\0', '0'))
                .put("notes", "test").put("apkUrl", "https://github.com/zzusec/update.apk");
        // Build the otherwise valid metadata independently, then exercise validateArchive's own check.
        return UpdateManager.parseMetadata(json.toString(), "com.family.tvguard", Math.max(33, minSdk));
    }
}
