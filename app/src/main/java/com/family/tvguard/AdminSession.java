package com.family.tvguard;

import android.os.SystemClock;

/**
 * Parent authentication is short-lived and never survives process death or reboot. A live session
 * is not a pass for settings pages — every visit still costs a PIN — it only keeps the guard from
 * fighting its own parent screens, and 维护授权 additionally lets the system confirm pages that the
 * parent opened on purpose through without a second password.
 */
final class AdminSession {

    private static final long TIMEOUT_MILLIS = 3 * 60_000L;
    private static long expiresElapsed;
    private static boolean maintenance;
    private static boolean removing;

    private AdminSession() {
    }

    static synchronized void grant() {
        expiresElapsed = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS;
        maintenance = false;
        removing = false;
    }

    static synchronized boolean isActive() {
        if (expiresElapsed <= SystemClock.elapsedRealtime()) {
            revoke();
            return false;
        }
        return true;
    }

    /** Opening the panel alone must never release settings or pause the quota lock. */
    static synchronized boolean grantMaintenance() {
        if (!isActive()) {
            return false;
        }
        maintenance = true;
        expiresElapsed = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS;
        return true;
    }

    static synchronized boolean isMaintenanceActive() {
        return isActive() && maintenance;
    }

    static synchronized void endMaintenance() {
        maintenance = false;
        removing = false;
    }

    static synchronized long remainingSeconds() {
        return isActive() ? Math.max(0L, (expiresElapsed - SystemClock.elapsedRealtime() + 999L) / 1000L) : 0L;
    }

    static synchronized boolean beginRemoval() {
        if (!grantMaintenance()) {
            return false;
        }
        removing = true;
        return true;
    }

    static synchronized boolean isRemovalInProgress() {
        return isMaintenanceActive() && removing;
    }

    static synchronized void endRemoval() {
        removing = false;
    }

    static synchronized void revoke() {
        expiresElapsed = 0L;
        maintenance = false;
        removing = false;
    }
}
