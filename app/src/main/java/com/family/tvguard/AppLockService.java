package com.family.tvguard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Watches the foreground window so the guard cannot be walked around.
 *
 * <p>Two things are enforced here. Once the daily quota is spent, every window other than the
 * guard's own is replaced by the lock — except the television's own desktop while a parent has just
 * stepped out of the console to it, which is a desktop pass rather than watching television. And
 * the settings screens that hold the app's own
 * permissions and uninstall button stay behind the parent gate all day, because those are the
 * routes a child uses to switch the guard off. Each visit to one of those screens costs a fresh
 * PIN — a live parent session is not a pass — except while a parent screen of the guard's own is
 * what the family is looking at, and during the short maintenance window that the panel opens on
 * purpose for the system's own confirm pages.
 */
public class AppLockService extends AccessibilityService {

    private static final String TAG = "TvGuard";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final long SHORTCUT_WINDOW_MILLIS = 2000L;
    private static final long SETTINGS_RETRY_MILLIS = 1000L;
    private static final long SETTINGS_REFRESH_MILLIS = 30_000L;
    private static volatile AppLockService instance;
    /** Settings, package installer and app-info surfaces that can switch the guard off. */
    private static final String[] KNOWN_TV_SETTINGS = {
            "com.android.tv.settings",
            "com.android.settings",
            "com.google.android.tv.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.documentsui",
            // Xiaomi TV (MIUI TV / PatchWall) keeps settings and app management in its own
            // packages instead of the AOSP ones.
            "com.xiaomi.mitv.settings",
            "com.miui.packageinstaller",
            "com.lbe.security.miui",
    };

    private long firstZeroElapsed;
    private String foregroundPackage = "";
    private Prefs prefs;
    private final Set<String> settingsPackages = new HashSet<>(Arrays.asList(KNOWN_TV_SETTINGS));
    private String lastUnrecognizedPackage = "";
    private long nextSettingsResolveElapsed;
    private String windowError = "";
    private String settingsError = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable foregroundCheck = new Runnable() {
        @Override
        public void run() {
            if (prefs != null && prefs.isProtectionEnabled()) {
                refreshForegroundPackage();
            }
            enforceForeground();
            handler.postDelayed(this, 250L);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = new Prefs(this);
        foregroundPackage = "";
        lastUnrecognizedPackage = "";
        nextSettingsResolveElapsed = 0L;
        settingsPackages.clear();
        settingsPackages.addAll(Arrays.asList(KNOWN_TV_SETTINGS));
        windowError = "";
        settingsError = "";
        // canRequestFilterKeyEvents in the XML only declares the capability; the flag itself has
        // to be requested here or the console shortcut never receives key events.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        instance = this;
        GuardService.start(this);
        handler.removeCallbacks(foregroundCheck);
        handler.post(foregroundCheck);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (TextUtils.isEmpty(pkg)) {
            return;
        }
        foregroundPackage = pkg.toString();
        enforceForeground();
    }

    /** Recover the foreground after reconnecting; never inspect text or traverse child nodes. */
    @SuppressWarnings("deprecation")
    private void refreshForegroundPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    CharSequence pkg = root.getPackageName();
                    if (!TextUtils.isEmpty(pkg)) {
                        foregroundPackage = pkg.toString();
                    }
                } finally {
                    root.recycle();
                }
            }
            windowError = "";
        } catch (RuntimeException e) {
            if (windowError.isEmpty()) {
                Log.w(TAG, "Could not query the active window package", e);
            }
            windowError = "当前窗口识别失败，正在重试";
        }
    }

    /** Maintenance expiration must also close a settings page that never changes windows. */
    private void enforceForeground() {
        if (prefs == null) {
            prefs = new Prefs(this);
        }
        if (!prefs.isProtectionEnabled() || foregroundPackage.isEmpty()) {
            return;
        }
        // Every visit to a settings page costs a PIN, but the guard never fights its own screens.
        // This is not a nicety: the parent panel is translucent, so the settings page it was opened
        // over stays visible underneath and keeps reporting itself as the foreground window.
        // Watching that window without this rule pushed a fresh gate over the panel every few
        // hundred milliseconds; the panel's onPause then revoked the session, so the next gate
        // really did demand the password again, forever (BRAVIA 4K 2015, 2.5.7).
        if (parentUiOnScreen() || AdminSession.isMaintenanceActive()) {
            return;
        }
        if (foregroundPackage.equals(getPackageName()) || foregroundPackage.equals(SYSTEM_UI)) {
            return;
        }
        if (prefs.isParentGraceActive()) {
            // 2.5.17: a parent entered the PIN and left the console. Until the television next stands
            // by, neither the quota lock nor the settings gate may put a password page back on the
            // screen - the page that kept coming back was read as the guard not working at all.
            // The grace ends only at standby, which the timing service's own receiver watches.
            return;
        }
        if (prefs.isGuardActive()) {
            LockActivity.launch(this);
        } else if (isSettingsPackage(foregroundPackage)) {
            AdminAuthActivity.launch(this);
        }
    }

    /** One of the guard's own parent screens is what the family is looking at. */
    private boolean parentUiOnScreen() {
        return AdminAuthActivity.isVisible() || MainActivity.isAuthorizedVisible()
                || GuardService.isParentUiOnScreen();
    }

    /**
     * The settings app that owns the app-details, permission and uninstall screens. Resolved from
     * the system intents rather than a guessed vendor package name, so a TV whose settings app is
     * called something else still gets covered.
     */
    private boolean isSettingsPackage(String pkg) {
        long now = SystemClock.elapsedRealtime();
        if (settingsPackages.contains(pkg) && (settingsError.isEmpty()
                || now < nextSettingsResolveElapsed)) {
            return true;
        }
        if (!pkg.equals(lastUnrecognizedPackage) || now >= nextSettingsResolveElapsed) {
            lastUnrecognizedPackage = pkg;
            boolean resolved = resolveSettingsPackages();
            nextSettingsResolveElapsed = now + (resolved
                    ? SETTINGS_REFRESH_MILLIS : SETTINGS_RETRY_MILLIS);
        }
        return settingsPackages.contains(pkg);
    }

    private boolean resolveSettingsPackages() {
        Set<String> packages = new HashSet<>(Arrays.asList(KNOWN_TV_SETTINGS));
        Uri self = Uri.parse("package:" + getPackageName());
        boolean resolved = collectSettingsPackage(new Intent(Settings.ACTION_SETTINGS), packages);
        resolved &= collectSettingsPackage(new Intent(Settings.ACTION_SECURITY_SETTINGS), packages);
        resolved &= collectSettingsPackage(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, self), packages);
        resolved &= collectSettingsPackage(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), packages);
        resolved &= collectSettingsPackage(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS), packages);
        resolved &= collectSettingsPackage(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), packages);
        resolved &= collectSettingsPackage(new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN), packages);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            resolved &= collectSettingsPackage(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, self), packages);
        }
        resolved &= collectSettingsPackage(new Intent(Intent.ACTION_UNINSTALL_PACKAGE, self), packages);
        resolved &= collectSettingsPackage(new Intent(Intent.ACTION_DELETE, self), packages);
        if (resolved) {
            settingsPackages.clear();
            settingsError = "";
        }
        settingsPackages.addAll(packages);
        return resolved;
    }

    private boolean collectSettingsPackage(Intent intent, Set<String> packages) {
        try {
            for (ResolveInfo info : getPackageManager().queryIntentActivities(intent, 0)) {
                if (info.activityInfo != null) {
                    packages.add(info.activityInfo.packageName);
                }
            }
            return true;
        } catch (RuntimeException e) {
            if (settingsError.isEmpty()) {
                Log.w(TAG, "Could not resolve " + intent.getAction(), e);
            }
            settingsError = "设置页面识别失败，正在重试";
            return false;
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return false;
        }
        int code = event.getKeyCode();
        boolean zero = code == KeyEvent.KEYCODE_0 || code == KeyEvent.KEYCODE_NUMPAD_0;
        boolean guardActive = prefs != null && prefs.isProtectionEnabled() && prefs.isGuardActive();
        if (guardActive || AdminSession.isMaintenanceActive()) {
            firstZeroElapsed = 0L;
            return false;
        }
        // The console reads its own two-digit codes, so the shortcut must not fire inside it.
        if (getPackageName().equals(foregroundPackage)) {
            firstZeroElapsed = 0L;
            return false;
        }
        if (!zero) {
            firstZeroElapsed = 0L;
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (firstZeroElapsed != 0L && now - firstZeroElapsed <= SHORTCUT_WINDOW_MILLIS) {
            firstZeroElapsed = 0L;
            AdminAuthActivity.launch(this);
        } else {
            firstZeroElapsed = now;
        }
        return false;
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt: the service only reacts to window changes and the shortcut.
    }

    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacks(foregroundCheck);
        firstZeroElapsed = 0L;
        foregroundPackage = "";
        if (instance == this) {
            instance = null;
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    static boolean isConnected() {
        return instance != null;
    }

    static String lastError() {
        AppLockService service = instance;
        if (service == null) {
            return "";
        }
        return service.windowError + (service.windowError.isEmpty() || service.settingsError.isEmpty()
                ? "" : "\n") + service.settingsError;
    }

    /** Configuration alone does not prove that the service is connected. */
    static boolean isEnabled(android.content.Context ctx) {
        if (Settings.Secure.getInt(ctx.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
            return false;
        }
        String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        ComponentName component = new ComponentName(ctx, AppLockService.class);
        for (String entry : enabled.split(":")) {
            if (component.equals(ComponentName.unflattenFromString(entry))) {
                return true;
            }
        }
        return false;
    }
}
