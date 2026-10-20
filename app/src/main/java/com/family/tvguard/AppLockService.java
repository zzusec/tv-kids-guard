package com.family.tvguard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
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

import java.util.HashSet;
import java.util.Set;

/**
 * Watches the foreground window so the guard cannot be walked around.
 *
 * <p>Two things are enforced here. Once the daily quota is spent, every window other than the
 * guard's own is replaced by the lock. And the settings screens that hold the app's own
 * permissions and uninstall button stay behind the parent gate all day, because those are the
 * routes a child uses to switch the guard off.
 */
public class AppLockService extends AccessibilityService {

    private static final String TAG = "TvGuard";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final long SHORTCUT_WINDOW_MILLIS = 2000L;
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
    private final Set<String> settingsPackages = new HashSet<>();
    private boolean settingsResolved;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable foregroundCheck = new Runnable() {
        @Override
        public void run() {
            enforceForeground();
            handler.postDelayed(this, 250L);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = new Prefs(this);
        // canRequestFilterKeyEvents in the XML only declares the capability; the flag itself has
        // to be requested here or the console shortcut never receives key events.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
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

    /** Maintenance expiration must also close a settings page that never changes windows. */
    private void enforceForeground() {
        if (prefs == null) {
            prefs = new Prefs(this);
        }
        if (!prefs.isProtectionEnabled() || foregroundPackage.isEmpty()
                || AdminSession.isMaintenanceActive()) {
            return;
        }
        if (foregroundPackage.equals(getPackageName()) || foregroundPackage.equals(SYSTEM_UI)) {
            return;
        }
        if (prefs.isGuardActive()) {
            LockActivity.launch(this);
        } else if (isSettingsPackage(foregroundPackage)) {
            AdminAuthActivity.launch(this);
        }
    }

    /**
     * The settings app that owns the app-details, permission and uninstall screens. Resolved from
     * the system intents rather than a guessed vendor package name, so a TV whose settings app is
     * called something else still gets covered.
     */
    private boolean isSettingsPackage(String pkg) {
        if (!settingsResolved) {
            settingsResolved = true;
            Uri self = Uri.parse("package:" + getPackageName());
            collectSettingsPackage(new Intent(Settings.ACTION_SETTINGS));
            collectSettingsPackage(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, self));
            collectSettingsPackage(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            collectSettingsPackage(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
            collectSettingsPackage(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                collectSettingsPackage(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, self));
            }
            collectSettingsPackage(new Intent(Intent.ACTION_UNINSTALL_PACKAGE, self));
            collectSettingsPackage(new Intent(Intent.ACTION_DELETE, self));
            // Android TV builds often route these screens through their own settings package
            // without advertising the phone intents above, so cover the known TV surfaces too.
            for (String known : KNOWN_TV_SETTINGS) {
                settingsPackages.add(known);
            }
        }
        return settingsPackages.contains(pkg);
    }

    private void collectSettingsPackage(Intent intent) {
        try {
            for (ResolveInfo info : getPackageManager().queryIntentActivities(intent, 0)) {
                if (info.activityInfo != null) {
                    settingsPackages.add(info.activityInfo.packageName);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve " + intent.getAction(), e);
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
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** True when the parent has switched this service on in system settings. */
    static boolean isEnabled(android.content.Context ctx) {
        String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        String component = ctx.getPackageName() + "/" + AppLockService.class.getName();
        for (String entry : enabled.split(":")) {
            if (entry.equalsIgnoreCase(component)) {
                return true;
            }
        }
        return false;
    }
}
