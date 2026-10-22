package com.family.tvguard;

import android.app.ActivityManager;
import android.app.Application;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.UserManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivityManager;
import org.robolectric.shadows.ShadowDevicePolicyManager;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = DeviceProtectionTest.PolicyShadow.class)
public class DeviceProtectionTest {

    private Application context;
    private DevicePolicyManager manager;
    private ComponentName admin;
    private PolicyShadow policy;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        new Prefs(context).setProtectionEnabled(true);
        manager = DeviceProtection.dpm(context);
        admin = DeviceProtection.admin(context);
        policy = Shadow.extract(manager);
        AdminSession.revoke();
        AdminSession.endRemoval();
        ((Map<?, ?>) ReflectionHelpers.getStaticField(DeviceProtection.class, "errors")).clear();
    }

    @Test
    @Config(sdk = 19)
    public void kitKatReportsUnavailablePoliciesWithoutCallingModernApis() {
        policy.setDeviceOwner(admin);
        policy.setActiveAdmin(admin);

        DeviceProtection.applyIfOwner(context);

        assertFalse(DeviceProtection.isUninstallBlocked(context));
        assertFalse(DeviceProtection.isUserControlBlocked(context));
        assertFalse(DeviceProtection.isLockTaskAllowed(context));
        assertEquals(0, policy.uninstallQueryCount);
        // The administrator, not the owner policy, is what the KitKat package manager checks.
        assertTrue(DeviceProtection.describe(context).contains("直接卸载限制：管理员已启用"));
        assertTrue(DeviceProtection.describe(context).contains("Android 4.4 不支持系统 Lock Task"));
        assertEquals("", DeviceProtection.lastError());
    }

    @Test
    @Config(sdk = 19)
    public void kitKatWithAnActiveAdministratorReportsDirectRemovalProtection() {
        policy.setDeviceOwner(admin);
        policy.setActiveAdmin(admin);

        assertTrue(DeviceProtection.isAdminActive(context));
        assertTrue(DeviceProtection.isUninstallProtected(context));
    }

    @Test
    public void activeAdministratorReportsDirectRemovalProtectionWithoutOwnerPolicies() {
        policy.setActiveAdmin(admin);

        assertTrue(DeviceProtection.isUninstallProtected(context));
        assertFalse(DeviceProtection.isUninstallBlocked(context));
        String report = DeviceProtection.describe(context);
        assertTrue(report.contains("设备管理：普通管理员"));
        assertTrue(report.contains("直接卸载限制：管理员已启用"));
        assertTrue(report.contains("禁止强停：未开启"));
    }

    @Test
    public void activationIntentTargetsTheGuardReceiverWithoutActivatingIt() {
        android.content.Intent intent = DeviceProtection.activationIntent(context);

        assertEquals(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN,
                intent.getAction());
        assertEquals(admin, intent.getParcelableExtra(
                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN));
        assertFalse(DeviceProtection.isAdminActive(context));
    }

    @Test
    @Config(sdk = 19)
    public void kitKatOwnerRemovalFailsSafelyWithoutClearingIdentity() {
        policy.setDeviceOwner(admin);
        authorizeRemoval();

        DeviceProtection.RemovalResult result = DeviceProtection.remove(context);

        assertFalse(result.success);
        assertFalse(result.needsRetry);
        assertTrue(result.message.contains("Android 4.4 不支持安全解除设备所有者"));
        assertTrue(DeviceProtection.isDeviceOwner(context));
        assertEquals(0, policy.clearOwnerCount);
    }

    @Test
    @Config(sdk = 19)
    public void kitKatWithoutOwnerCanFinishAuthenticatedRemoval() {
        policy.setActiveAdmin(admin);
        authorizeRemoval();

        DeviceProtection.RemovalResult result = DeviceProtection.remove(context);

        assertTrue(result.success);
        assertFalse(DeviceProtection.isAdminActive(context));
        assertEquals("", DeviceProtection.lastError());
    }

    @Test
    public void nonOwnerDoesNotQueryOwnerOnlyUninstallPolicy() {
        policy.failUninstallQuery = true;
        assertFalse(DeviceProtection.isUninstallBlocked(context));
        DeviceProtection.applyIfOwner(context);
        assertEquals(0, policy.uninstallQueryCount);
        assertEquals("", DeviceProtection.lastError());
    }

    @Test
    public void disabledProtectionAndInProgressRemovalDoNotReapplyPolicies() {
        policy.setDeviceOwner(admin);
        new Prefs(context).setProtectionEnabled(false);
        DeviceProtection.applyIfOwner(context);
        assertFalse(manager.isUninstallBlocked(admin, context.getPackageName()));

        new Prefs(context).setProtectionEnabled(true);
        authorizeRemoval();
        DeviceProtection.applyIfOwner(context);
        assertFalse(manager.isUninstallBlocked(admin, context.getPackageName()));
        assertFalse(manager.isLockTaskPermitted(context.getPackageName()));
    }

    @Test
    public void enablingPreservesOtherPackagesAndChecksReadBack() {
        policy.setDeviceOwner(admin);
        manager.setLockTaskPackages(admin, new String[]{"other.player"});
        manager.setUserControlDisabledPackages(admin, Collections.singletonList("other.player"));

        DeviceProtection.applyIfOwner(context);

        assertTrue(manager.isUninstallBlocked(admin, context.getPackageName()));
        assertEquals(Arrays.asList("other.player", context.getPackageName()),
                Arrays.asList(manager.getLockTaskPackages(admin)));
        assertEquals(Arrays.asList("other.player", context.getPackageName()),
                manager.getUserControlDisabledPackages(admin));
        assertEquals("", DeviceProtection.lastError());
    }

    @Test
    public void ignoredPolicyWriteIsReportedRatherThanClaimedProtected() {
        policy.setDeviceOwner(admin);
        policy.ignoreUserControlWrites = true;

        DeviceProtection.applyIfOwner(context);

        assertFalse(DeviceProtection.isUserControlBlocked(context));
        assertTrue(DeviceProtection.lastError().contains("系统未确认本应用的强停和清数据限制"));
    }

    @Test
    @Config(sdk = 24)
    public void oldAndroidMaintenancePreservesAppControlAndUninstallBlock() {
        policy.setDeviceOwner(admin);
        DeviceProtection.applyIfOwner(context);
        assertTrue(DeviceProtection.isUserControlBlocked(context));

        AdminSession.grant();
        assertTrue(AdminSession.grantMaintenance());
        DeviceProtection.applyIfOwner(context);
        assertTrue(DeviceProtection.isUserControlBlocked(context));
        assertTrue(DeviceProtection.isUninstallBlocked(context));

        AdminSession.revoke();
        DeviceProtection.applyIfOwner(context);
        assertTrue(DeviceProtection.isUserControlBlocked(context));
        assertTrue(DeviceProtection.isUninstallBlocked(context));
    }

    @Test
    public void removalRequiresAuthenticatedConfirmation() {
        policy.setDeviceOwner(admin);
        DeviceProtection.applyIfOwner(context);

        DeviceProtection.RemovalResult result = DeviceProtection.remove(context);

        assertFalse(result.success);
        assertEquals(0, policy.clearOwnerCount);
        assertTrue(manager.isUninstallBlocked(admin, context.getPackageName()));
    }

    @Test
    public void cleanupFailureKeepsOwnerIdentityAvailableForRetry() {
        policy.setDeviceOwner(admin);
        DeviceProtection.applyIfOwner(context);
        policy.ignoreUserControlWrites = true;
        authorizeRemoval();

        DeviceProtection.RemovalResult result = DeviceProtection.remove(context);

        assertFalse(result.success);
        assertFalse(result.needsRetry);
        assertEquals(0, policy.clearOwnerCount);
        assertTrue(DeviceProtection.isDeviceOwner(context));
        assertTrue(result.message.contains("强停和清数据限制"));
    }

    @Test
    public void otherPackagePoliciesArePreservedAndOwnerIsNotCleared() {
        policy.setDeviceOwner(admin);
        manager.setLockTaskPackages(admin, new String[]{"other.player"});
        manager.setUserControlDisabledPackages(admin, Collections.singletonList("other.player"));
        DeviceProtection.applyIfOwner(context);
        authorizeRemoval();

        DeviceProtection.RemovalResult result = DeviceProtection.remove(context);

        assertFalse(result.success);
        assertEquals(0, policy.clearOwnerCount);
        assertTrue(DeviceProtection.isDeviceOwner(context));
        assertEquals(Collections.singletonList("other.player"),
                Arrays.asList(manager.getLockTaskPackages(admin)));
        assertEquals(Collections.singletonList("other.player"),
                manager.getUserControlDisabledPackages(admin));
    }

    @Test
    public void removalRejectsAnActiveLockTaskBeforeChangingPolicies() {
        policy.setDeviceOwner(admin);
        DeviceProtection.applyIfOwner(context);
        authorizeRemoval();
        ActivityManager activities = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ((ShadowActivityManager) Shadow.extract(activities))
                .setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_LOCKED);

        DeviceProtection.RemovalResult result = DeviceProtection.remove(context);

        assertFalse(result.success);
        assertEquals(0, policy.clearOwnerCount);
        assertTrue(manager.isUninstallBlocked(admin, context.getPackageName()));
    }

    @Test
    public void successfulRemovalChecksPoliciesAndIdentity() {
        policy.setDeviceOwner(admin);
        DeviceProtection.applyIfOwner(context);
        authorizeRemoval();

        DeviceProtection.RemovalResult result = DeviceProtection.remove(context);

        assertTrue(result.success);
        assertFalse(result.needsRetry);
        assertFalse(DeviceProtection.isDeviceOwner(context));
        assertFalse(manager.isAdminActive(admin));
        assertFalse(manager.isLockTaskPermitted(context.getPackageName()));
        assertTrue(manager.getUserControlDisabledPackages(admin).isEmpty());
        assertEquals(1, policy.clearOwnerCount);
    }

    @Test
    public void ignoredOwnerRemovalIsNotReportedSuccessful() {
        policy.setDeviceOwner(admin);
        DeviceProtection.applyIfOwner(context);
        policy.ignoreOwnerRemoval = true;
        authorizeRemoval();

        DeviceProtection.RemovalResult result = DeviceProtection.remove(context);

        assertFalse(result.success);
        assertFalse(result.needsRetry);
        assertTrue(DeviceProtection.isDeviceOwner(context));
        assertTrue(manager.isAdminActive(admin));
        assertTrue(result.message.contains("系统尚未解除设备所有者身份"));
    }

    @Test
    @Config(sdk = {19, 33})
    public void ordinaryAdminRemovalReportsPendingUntilSystemConfirms() {
        policy.setActiveAdmin(admin);
        policy.delayAdminRemoval = true;
        authorizeRemoval();

        DeviceProtection.RemovalResult pending = DeviceProtection.remove(context);
        assertFalse(pending.success);
        assertTrue(pending.needsRetry);
        assertTrue(manager.isAdminActive(admin));

        policy.delayAdminRemoval = false;
        DeviceProtection.RemovalResult done = DeviceProtection.remove(context);
        assertTrue(done.success);
        assertFalse(done.needsRetry);
        assertFalse(manager.isAdminActive(admin));
    }

    @Test
    public void screenPinningIsNotReportedAsSystemLockTask() {
        ActivityManager activities = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ((ShadowActivityManager) Shadow.extract(activities))
                .setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_PINNED);
        assertTrue(DeviceProtection.describe(context).contains("屏幕固定（可以退出）"));
        assertFalse(DeviceProtection.describe(context).contains("当前锁定模式：系统 Lock Task"));
        assertTrue(DeviceProtection.describe(context).contains("禁止卸载/强停：未开启"));
    }

    private void authorizeRemoval() {
        AdminSession.grant();
        AdminSession.grantMaintenance();
        AdminSession.beginRemoval();
    }

    /** Adds the policy APIs Robolectric does not implement and permits failed read-back tests. */
    @Implements(DevicePolicyManager.class)
    public static class PolicyShadow extends ShadowDevicePolicyManager {
        final ArrayList<String> controlledPackages = new ArrayList<>();
        final Bundle restrictions = new Bundle();
        boolean ignoreUserControlWrites;
        boolean failUninstallQuery;
        boolean ownerCleared;
        boolean ignoreOwnerRemoval;
        boolean delayAdminRemoval;
        int uninstallQueryCount;
        int clearOwnerCount;

        @Implementation
        protected boolean isDeviceOwnerApp(String packageName) {
            return !ownerCleared && super.isDeviceOwnerApp(packageName);
        }

        @Implementation
        protected boolean isUninstallBlocked(ComponentName admin, String packageName) {
            uninstallQueryCount++;
            if (failUninstallQuery) {
                throw new SecurityException("owner-only query");
            }
            return super.isUninstallBlocked(admin, packageName);
        }

        @Implementation(minSdk = 30)
        protected void setUserControlDisabledPackages(ComponentName admin, java.util.List<String> packages) {
            if (!ignoreUserControlWrites) {
                controlledPackages.clear();
                controlledPackages.addAll(packages);
            }
        }

        @Implementation(minSdk = 30)
        protected java.util.List<String> getUserControlDisabledPackages(ComponentName admin) {
            return new ArrayList<>(controlledPackages);
        }

        @Implementation(minSdk = 24)
        protected Bundle getUserRestrictions(ComponentName admin) {
            return new Bundle(restrictions);
        }

        @Implementation
        protected void addUserRestriction(ComponentName admin, String restriction) {
            super.addUserRestriction(admin, restriction);
            restrictions.putBoolean(restriction, true);
        }

        @Implementation
        protected void clearUserRestriction(ComponentName admin, String restriction) {
            super.clearUserRestriction(admin, restriction);
            restrictions.putBoolean(restriction, false);
        }

        @Implementation
        protected void clearDeviceOwnerApp(String packageName) {
            clearOwnerCount++;
            if (!ignoreOwnerRemoval) {
                ownerCleared = true;
            }
        }

        @Implementation
        protected void removeActiveAdmin(ComponentName admin) {
            if (!delayAdminRemoval) {
                super.removeActiveAdmin(admin);
            }
        }
    }
}
