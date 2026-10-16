package com.family.tvguard;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Owner-only policies, with read-back checks before reporting protection or removal. */
final class DeviceProtection {

    private static final String TAG = "TvGuard";
    private static final Map<String, String> errors = new LinkedHashMap<>();

    private DeviceProtection() {
    }

    static ComponentName admin(Context ctx) {
        return new ComponentName(ctx.getPackageName(), GuardDeviceAdmin.class.getName());
    }

    static DevicePolicyManager dpm(Context ctx) {
        return (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    static boolean isDeviceOwner(Context ctx) {
        String operation = "查询设备所有者";
        try {
            DevicePolicyManager manager = dpm(ctx);
            boolean owner = manager != null && manager.isDeviceOwnerApp(ctx.getPackageName());
            clearError(operation);
            return owner;
        } catch (RuntimeException e) {
            recordError(operation, e);
            return false;
        }
    }

    static boolean isAdminActive(Context ctx) {
        String operation = "查询设备管理员";
        try {
            DevicePolicyManager manager = dpm(ctx);
            boolean active = manager != null && manager.isAdminActive(admin(ctx));
            clearError(operation);
            return active;
        } catch (RuntimeException e) {
            recordError(operation, e);
            return false;
        }
    }

    static boolean isUninstallBlocked(Context ctx) {
        // A plain administrator cannot query an owner policy using its component name.
        if (!isDeviceOwner(ctx)) {
            return false;
        }
        String operation = "查询禁止卸载";
        try {
            boolean blocked = dpm(ctx).isUninstallBlocked(admin(ctx), ctx.getPackageName());
            clearError(operation);
            return blocked;
        } catch (RuntimeException e) {
            recordError(operation, e);
            return false;
        }
    }

    static boolean isLockTaskAllowed(Context ctx) {
        String operation = "查询锁定任务白名单";
        try {
            DevicePolicyManager manager = dpm(ctx);
            boolean allowed = manager != null && manager.isLockTaskPermitted(ctx.getPackageName());
            clearError(operation);
            return allowed;
        } catch (RuntimeException e) {
            recordError(operation, e);
            return false;
        }
    }

    static boolean isUserControlBlocked(Context ctx) {
        if (!isDeviceOwner(ctx)) {
            return false;
        }
        String operation = "查询强停和清数据限制";
        try {
            boolean blocked;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                blocked = dpm(ctx).getUserControlDisabledPackages(admin(ctx))
                        .contains(ctx.getPackageName());
            } else {
                blocked = userManager(ctx).hasUserRestriction(UserManager.DISALLOW_APPS_CONTROL);
            }
            clearError(operation);
            return blocked;
        } catch (RuntimeException e) {
            recordError(operation, e);
            return false;
        }
    }

    /** Does not change other packages' entries or race a parent's in-progress removal. */
    static synchronized void applyIfOwner(Context ctx) {
        if (!new Prefs(ctx).isProtectionEnabled() || AdminSession.isRemovalInProgress()
                || !isDeviceOwner(ctx)) {
            return;
        }
        DevicePolicyManager manager = dpm(ctx);
        ComponentName admin = admin(ctx);
        String self = ctx.getPackageName();
        String operation = "设置锁定任务白名单";
        try {
            List<String> packages = lockTaskPackages(manager, admin);
            if (!packages.contains(self)) {
                packages.add(self);
                manager.setLockTaskPackages(admin, packages.toArray(new String[0]));
            }
            require(samePackages(packages, lockTaskPackages(manager, admin))
                    && manager.isLockTaskPermitted(self), "系统未确认锁定任务白名单");
            clearError(operation);
        } catch (Exception e) {
            recordError(operation, e);
        }

        operation = "设置禁止卸载";
        try {
            if (!manager.isUninstallBlocked(admin, self)) {
                manager.setUninstallBlocked(admin, self, true);
            }
            require(manager.isUninstallBlocked(admin, self), "系统未确认禁止卸载");
            clearError(operation);
        } catch (RuntimeException e) {
            recordError(operation, e);
        }

        operation = "设置强停和清数据限制";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                List<String> packages = new ArrayList<>(manager.getUserControlDisabledPackages(admin));
                if (!packages.contains(self)) {
                    packages.add(self);
                    manager.setUserControlDisabledPackages(admin, packages);
                }
                require(samePackages(packages, manager.getUserControlDisabledPackages(admin)),
                        "系统未确认本应用的强停和清数据限制");
                // An OS upgrade must not leave the old, device-wide restriction behind.
                setAppsControlRestriction(ctx, manager, admin, false);
            } else {
                // Older Android has no per-app switch. Keep protection even during maintenance:
                // force-stopping here would prevent the maintenance timeout from restoring it.
                setAppsControlRestriction(ctx, manager, admin, true);
            }
            clearError(operation);
        } catch (RuntimeException e) {
            recordError(operation, e);
        }
    }

    static final class RemovalResult {
        final boolean success;
        final boolean needsRetry;
        final String message;

        RemovalResult(boolean success, boolean needsRetry, String message) {
            this.success = success;
            this.needsRetry = needsRetry;
            this.message = message;
        }
    }

    /**
     * The parent must first end lock task in the activity that started it. Keep the owner identity
     * on every policy-cleanup failure, so the parent can retry instead of inheriting orphan rules.
     * removeActiveAdmin is asynchronous: an outstanding request is not a successful removal.
     */
    @SuppressWarnings("deprecation")
    static synchronized RemovalResult remove(Context ctx) {
        if (!AdminSession.isRemovalInProgress() || !AdminSession.isMaintenanceActive()) {
            return new RemovalResult(false, false, "请先输入家长密码并确认解除。");
        }
        String operation = "检查解除管理条件";
        try {
            DevicePolicyManager manager = dpm(ctx);
            require(manager != null, "设备管理服务不可用，无法核实解除结果");
            ActivityManager activityManager = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            require(activityManager != null, "任务管理服务不可用，无法核实锁定模式");
            boolean inLockTask = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? activityManager.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE
                    : activityManager.isInLockTaskMode();
            require(!inLockTask, "请先从原锁屏界面退出锁定任务，再解除设备管理");
            ComponentName admin = admin(ctx);
            String self = ctx.getPackageName();
            require(!manager.isProfileOwnerApp(self), "本应用是工作资料所有者，不能按普通设备管理员解除");
            clearError(operation);

            if (manager.isDeviceOwnerApp(self)) {
                operation = "解除强停和清数据限制";
                List<String> controlledPackages = new ArrayList<>();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    controlledPackages.addAll(manager.getUserControlDisabledPackages(admin));
                    if (controlledPackages.removeAll(Arrays.asList(self))) {
                        manager.setUserControlDisabledPackages(admin, controlledPackages);
                    }
                    require(samePackages(controlledPackages, manager.getUserControlDisabledPackages(admin)),
                            "系统仍保留或改变了强停和清数据限制");
                }
                setAppsControlRestriction(ctx, manager, admin, false);
                clearError(operation);

                operation = "解除锁定任务白名单";
                List<String> packages = lockTaskPackages(manager, admin);
                if (packages.removeAll(Arrays.asList(self))) {
                    manager.setLockTaskPackages(admin, packages.toArray(new String[0]));
                }
                require(samePackages(packages, lockTaskPackages(manager, admin))
                        && !manager.isLockTaskPermitted(self), "系统仍保留或改变了锁定任务白名单");
                clearError(operation);

                operation = "解除禁止卸载";
                if (manager.isUninstallBlocked(admin, self)) {
                    manager.setUninstallBlocked(admin, self, false);
                }
                require(!manager.isUninstallBlocked(admin, self), "系统仍然禁止卸载本应用");
                clearError(operation);

                operation = "解除设备所有者";
                // Clearing the owner may clear its remaining policies. Do not silently erase
                // other packages' entries merely because this application wants to leave.
                require(packages.isEmpty() && controlledPackages.isEmpty(),
                        "设备所有者仍管理其他应用，已保留其策略和管理身份，请先由家长核查");
                Bundle restrictions = ownRestrictions(ctx, manager, admin);
                for (String key : restrictions.keySet()) {
                    require(!restrictions.getBoolean(key),
                            "仍存在其他设备限制 " + key + "，已保留管理身份，请先由家长核查");
                }
                // Deprecated for enterprise offboarding because data remains. This explicitly
                // confirmed local household flow preserves data and never substitutes a wipe.
                manager.clearDeviceOwnerApp(self);
                require(!manager.isDeviceOwnerApp(self), "系统尚未解除设备所有者身份");
                clearError(operation);
            }

            operation = "解除设备管理员";
            if (manager.isAdminActive(admin)) {
                manager.removeActiveAdmin(admin);
            }
            if (manager.isAdminActive(admin)) {
                clearError(operation);
                return new RemovalResult(false, true, "已请求解除设备管理员，等待系统确认；请勿直接卸载。");
            }
            require(!manager.isDeviceOwnerApp(self), "设备所有者身份仍然有效");
            errors.clear();
            return new RemovalResult(true, false, "设备管理身份已解除，不会清除电视数据。");
        } catch (Exception e) {
            recordError(operation, e);
            return new RemovalResult(false, false, operation + "未完成：" + errorDetail(e)
                    + "。请保留当前配置重试。");
        }
    }

    /** Android 5–7 expose this as a public @hide method; it became a public SDK API in 26. */
    private static List<String> lockTaskPackages(DevicePolicyManager manager, ComponentName admin)
            throws Exception {
        String[] packages;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packages = manager.getLockTaskPackages(admin);
        } else {
            packages = (String[]) DevicePolicyManager.class
                    .getMethod("getLockTaskPackages", ComponentName.class).invoke(manager, admin);
        }
        require(packages != null, "无法读取现有锁定任务白名单，未覆盖其内容");
        return new ArrayList<>(Arrays.asList(packages));
    }

    private static boolean samePackages(List<String> expected, List<String> actual) {
        return actual != null && new LinkedHashSet<>(expected).equals(new LinkedHashSet<>(actual));
    }

    private static Bundle ownRestrictions(Context ctx, DevicePolicyManager manager, ComponentName admin) {
        // Before API 24 only effective restrictions are readable. Refuse to discard an unknown
        // restriction on removal, rather than assume another source's restriction belongs to us.
        Bundle restrictions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? manager.getUserRestrictions(admin) : userManager(ctx).getUserRestrictions();
        require(restrictions != null, "无法读取现有用户限制");
        return restrictions;
    }

    private static void setAppsControlRestriction(Context ctx, DevicePolicyManager manager,
                                                   ComponentName admin, boolean restricted) {
        boolean current = ownRestrictions(ctx, manager, admin)
                .getBoolean(UserManager.DISALLOW_APPS_CONTROL);
        if (current != restricted) {
            if (restricted) {
                manager.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL);
            } else {
                manager.clearUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL);
            }
        }
        require(ownRestrictions(ctx, manager, admin).getBoolean(UserManager.DISALLOW_APPS_CONTROL) == restricted,
                "系统未确认应用管理限制变更");
    }

    private static UserManager userManager(Context ctx) {
        UserManager manager = (UserManager) ctx.getSystemService(Context.USER_SERVICE);
        require(manager != null, "用户管理服务不可用");
        return manager;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String errorDetail(Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static synchronized void recordError(String operation, Exception error) {
        String detail = errorDetail(error);
        String previous = errors.put(operation, detail);
        if (!detail.equals(previous)) {
            Log.w(TAG, operation + ": " + detail, error);
        }
    }

    private static synchronized void clearError(String operation) {
        errors.remove(operation);
    }

    static synchronized String lastError() {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : errors.entrySet()) {
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return result.toString();
    }

    /** Reports permission and actual runtime mode separately; screen pinning is not lock task. */
    @SuppressWarnings("deprecation")
    static String describe(Context ctx) {
        StringBuilder result = new StringBuilder();
        boolean owner = isDeviceOwner(ctx);
        boolean active = isAdminActive(ctx);
        result.append("设备管理：").append(owner ? "设备所有者" : active ? "普通管理员" : "未启用");
        if (owner) {
            boolean sealed = isUninstallBlocked(ctx) && isUserControlBlocked(ctx);
            result.append("\n禁止卸载/强停：").append(sealed ? "已生效" : "未完全生效");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                result.append("（旧版 Android 下影响所有应用）");
            }
        }
        String operation = "查询当前锁定模式";
        try {
            ActivityManager manager = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            require(manager != null, "任务管理服务不可用");
            result.append("\n当前锁定模式：");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int mode = manager.getLockTaskModeState();
                result.append(mode == ActivityManager.LOCK_TASK_MODE_LOCKED ? "系统 Lock Task"
                        : mode == ActivityManager.LOCK_TASK_MODE_PINNED ? "屏幕固定（可以退出）" : "未进入");
            } else {
                result.append(manager.isInLockTaskMode()
                        ? "已进入任务限制（Android 5 无法区分屏幕固定）" : "未进入");
            }
            clearError(operation);
        } catch (RuntimeException e) {
            recordError(operation, e);
            result.append("未知");
        }
        String error = lastError();
        if (!error.isEmpty()) {
            result.append("\n策略核验异常：\n").append(error);
        }
        return result.toString();
    }
}
