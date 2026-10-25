package com.family.tvguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Persistent state: admin password, daily limit and today's usage counters. */
final class Prefs {

    private static final String FILE = "tvguard_prefs";
    private static final String KEY_PASSWORD = "password_hash";
    private static final String KEY_DAY = "day";
    private static final String KEY_USED = "used_seconds";
    private static final String KEY_BONUS = "bonus_seconds";
    private static final String KEY_DAILY_LIMIT = "daily_limit_minutes";
    private static final String KEY_LOCKED = "locked";
    /**
     * Written by 2.5.11 and earlier, which let the parent PIN switch the day's quota off. The
     * feature is gone, but the stored flag is not: it has to be dropped on the first read after
     * the upgrade so a day that was opened starts limiting again immediately.
     */
    private static final String LEGACY_UNLOCKED_TODAY = "unlocked_today";
    private static final String KEY_FAIL_COUNT = "fail_count";
    private static final String KEY_LOCKOUT_UNTIL = "lockout_until";
    private static final String KEY_PROTECTION_ENABLED = "protection_enabled";
    /**
     * The 2.5.17 parent grace: when a parent left the console after a correct PIN, and whether the
     * password page was on screen at that moment. Both live in storage rather than in the service
     * because Android recycles this process on the television and the grace has to outlast that.
     */
    private static final String KEY_GRACE_SINCE = "parent_grace_since";
    private static final String KEY_GRACE_FROM_LOCK = "parent_grace_from_lock";

    enum AuthResult { SUCCESS, INCORRECT, COOLDOWN, INVALID }

    /** Daily allowance until a parent saves a different limit. */
    static final int DEFAULT_LIMIT_MINUTES = 30;
    static final int MAX_LIMIT_MINUTES = 24 * 60;

    /** Upper bound for the extra minutes an admin can grant in one day. */
    static final int MAX_BONUS_MINUTES = 60;

    /** The admin password is always exactly this many digits. */
    static final int PASSWORD_LENGTH = 6;

    /**
     * Password used until the admin sets their own. The quota is enforced from the first boot,
     * so the child cannot use an unset password to escape the limit.
     */
    static final String DEFAULT_PASSWORD = "000000";

    private final SharedPreferences sp;

    Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (sp.contains(LEGACY_UNLOCKED_TODAY)) {
            sp.edit().remove(LEGACY_UNLOCKED_TODAY).apply();
        }
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(("tvguard::" + raw).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Starts a new day once the calendar date advances: the usage counters go back to zero and
     * any lock is lifted, so a screen left on past midnight is usable again and the next power-on
     * gets a fresh allowance.
     *
     * @return true when a new day actually started.
     */
    boolean rollOverIfNeeded() {
        String day = today();
        String stored = sp.getString(KEY_DAY, "");
        if (stored.isEmpty()) {
            sp.edit().putString(KEY_DAY, day).apply();
            return false;
        }
        // Only a genuinely later date starts a new quota. A TV that boots before its clock is
        // synced reports 1970, and treating that backwards jump as a new day would hand the
        // child a free reset on every power cycle, so a backwards clock keeps the day's usage.
        if (day.compareTo(stored) > 0) {
            sp.edit()
                    .putString(KEY_DAY, day)
                    .putLong(KEY_USED, 0L)
                    .putLong(KEY_BONUS, 0L)
                    .putBoolean(KEY_LOCKED, false)
                    .putInt(KEY_FAIL_COUNT, 0)
                    .putLong(KEY_LOCKOUT_UNTIL, 0L)
                    .remove(KEY_GRACE_SINCE)
                    .remove(KEY_GRACE_FROM_LOCK)
                    .apply();
            return true;
        }
        return false;
    }

    boolean hasPassword() {
        return !sp.getString(KEY_PASSWORD, "").isEmpty();
    }

    void setPassword(String raw) {
        if (!isValidPassword(raw) || DEFAULT_PASSWORD.equals(raw)) {
            throw new IllegalArgumentException("Use a new six-digit parent PIN");
        }
        if (!sp.edit().putString(KEY_PASSWORD, hash(raw)).commit()) {
            throw new IllegalStateException("Could not save parent PIN");
        }
    }

    boolean needsPasswordSetup() {
        return !hasPassword() || checkPassword(DEFAULT_PASSWORD);
    }

    AuthResult authenticate(String raw) {
        if (lockoutRemainingSeconds() > 0L) {
            return AuthResult.COOLDOWN;
        }
        if (!isValidPassword(raw)) {
            return AuthResult.INVALID;
        }
        if (!checkPassword(raw)) {
            registerFailedAttempt();
            return AuthResult.INCORRECT;
        }
        clearFailures();
        return AuthResult.SUCCESS;
    }

    boolean isProtectionEnabled() {
        return sp.getBoolean(KEY_PROTECTION_ENABLED, true);
    }

    void setProtectionEnabled(boolean enabled) {
        if (!sp.edit().putBoolean(KEY_PROTECTION_ENABLED, enabled).commit()) {
            throw new IllegalStateException("Could not save protection state");
        }
    }

    boolean checkPassword(String raw) {
        String stored = sp.getString(KEY_PASSWORD, "");
        if (stored.isEmpty()) {
            return DEFAULT_PASSWORD.equals(raw);
        }
        return stored.equals(hash(raw));
    }

    static boolean isValidPassword(String raw) {
        if (raw == null || raw.length() != PASSWORD_LENGTH) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) < '0' || raw.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    int getDailyLimitMinutes() {
        return sp.getInt(KEY_DAILY_LIMIT, DEFAULT_LIMIT_MINUTES);
    }

    void setDailyLimitMinutes(int minutes) {
        if (minutes < 1 || minutes > MAX_LIMIT_MINUTES) {
            throw new IllegalArgumentException("Daily limit must be between 1 and "
                    + MAX_LIMIT_MINUTES + " minutes");
        }
        if (!sp.edit().putInt(KEY_DAILY_LIMIT, minutes).commit()) {
            throw new IllegalStateException("Could not save daily limit");
        }
    }

    long getUsedSeconds() {
        rollOverIfNeeded();
        return sp.getLong(KEY_USED, 0L);
    }

    void addUsedSeconds(long seconds) {
        if (seconds <= 0) return;
        rollOverIfNeeded();
        sp.edit().putLong(KEY_USED, sp.getLong(KEY_USED, 0L) + seconds).apply();
    }

    void setUsedSeconds(long seconds) {
        rollOverIfNeeded();
        sp.edit().putLong(KEY_USED, Math.max(0L, seconds)).apply();
    }

    /**
     * The lock flag lives here, not only in the service singleton: the lock screen must keep
     * swallowing remote keys even after Android has recycled the service process.
     */
    /** Extra seconds the admin granted today, on top of {@link #getDailyLimitMinutes()}. */
    long getBonusSeconds() {
        rollOverIfNeeded();
        return sp.getLong(KEY_BONUS, 0L);
    }

    void setBonusSeconds(long seconds) {
        rollOverIfNeeded();
        long cap = MAX_BONUS_MINUTES * 60L;
        sp.edit().putLong(KEY_BONUS, Math.max(0L, Math.min(seconds, cap))).apply();
    }

    boolean isLocked() {
        rollOverIfNeeded();
        return sp.getBoolean(KEY_LOCKED, false);
    }

    void setLocked(boolean locked) {
        sp.edit().putBoolean(KEY_LOCKED, locked).apply();
    }

    int getFailCount() {
        return sp.getInt(KEY_FAIL_COUNT, 0);
    }

    /** Wall-clock time until which password entry stays blocked. */
    long getLockoutUntil() {
        return sp.getLong(KEY_LOCKOUT_UNTIL, 0L);
    }

    /** Seconds left before the next password attempt is accepted, 0 when entry is open. */
    long lockoutRemainingSeconds() {
        long left = getLockoutUntil() - System.currentTimeMillis();
        return left > 0L ? (left + 999L) / 1000L : 0L;
    }

    /**
     * Records one wrong password. Every three consecutive failures multiply the cool-down
     * (1 min, 5 min, 10 min, 20 min ...). Kept here rather than in the lock screen so the admin
     * panel cannot be used to retry faster than the lock screen allows.
     *
     * @return seconds the caller must now wait, 0 when no cool-down started yet.
     */
    long registerFailedAttempt() {
        int count = Math.min(getFailCount(), 1_000_000) + 1;
        sp.edit().putInt(KEY_FAIL_COUNT, count).apply();
        if (count % 3 != 0) {
            return 0L;
        }
        int steps = count / 3;
        long minutes;
        if (steps == 1) {
            minutes = 1L;
        } else if (steps == 2) {
            minutes = 5L;
        } else {
            minutes = 5L * (1L << Math.min(steps - 2, 6));
        }
        sp.edit().putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + minutes * 60000L).apply();
        return minutes * 60L;
    }

    void clearFailures() {
        sp.edit().putInt(KEY_FAIL_COUNT, 0).putLong(KEY_LOCKOUT_UNTIL, 0L).apply();
    }

    /** Seconds left in today's allowance, negative once it is overspent. */
    long remainingSeconds() {
        return getDailyLimitMinutes() * 60L + getBonusSeconds() - getUsedSeconds();
    }

    /**
     * Starts the parent grace: a parent has entered the correct PIN and is handing the remote back
     * to the television, so no password page may be put on the screen again until it next stands by.
     * The grace buys no allowance — the daily limit, today's bonus and the watched seconds are
     * untouched, and watching during it keeps counting.
     *
     * @param fromLock whether the quota lock was up when the parent authenticated. Only that case
     *                 defers the lock; a grace taken on a day that still has allowance left must not
     *                 postpone the lock the child has not reached yet, which would be the 今天不限时
     *                 that 2.5.12 removed under another name.
     */
    void startParentGrace(boolean fromLock) {
        sp.edit()
                .putLong(KEY_GRACE_SINCE, SystemClock.elapsedRealtime())
                .putBoolean(KEY_GRACE_FROM_LOCK, fromLock)
                .commit();
    }

    void endParentGrace() {
        sp.edit().remove(KEY_GRACE_SINCE).remove(KEY_GRACE_FROM_LOCK).apply();
    }

    /** Whether a parent is still inside the grace they bought with the PIN, ended by any standby. */
    boolean isParentGraceActive() {
        long since = sp.getLong(KEY_GRACE_SINCE, 0L);
        if (since <= 0L) {
            return false;
        }
        if (SystemClock.elapsedRealtime() < since) {
            // The boot clock went backwards, so the television was powered off rather than left
            // running. A power cycle counts as the standby that ends the grace, otherwise pulling
            // the plug would be the way to keep a parent's exemption alive forever.
            endParentGrace();
            return false;
        }
        return true;
    }

    /** Whether the live parent grace also holds the quota lock off, not just the settings gate. */
    boolean isParentGraceDeferringLock() {
        return isParentGraceActive() && sp.getBoolean(KEY_GRACE_FROM_LOCK, false);
    }

    /**
     * Whether the guard must be holding the screen right now, computed from persisted state
     * alone. The accessibility service uses this so a recycled service process cannot open a
     * window in which the lock is forgotten. A parent grace that came off the lock answers no:
     * the lock is deferred, not forgiven, and returns at the next standby.
     */
    boolean isGuardActive() {
        return isProtectionEnabled() && !isParentGraceDeferringLock()
                && (isLocked() || remainingSeconds() <= 0L);
    }
}
