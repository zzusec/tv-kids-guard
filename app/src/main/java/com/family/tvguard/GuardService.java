package com.family.tvguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Foreground service that owns the whole feature:
 * counts screen-on time, paints the top-right countdown, and raises the lock
 * shield once the daily quota is exhausted.
 */
public class GuardService extends Service {

    private static final String CHANNEL_ID = "tvguard";
    private static final int NOTIF_ID = 1001;
    private static final String TAG = "TvGuard";
    /** A starved or replayed tick may never dump a long wall-clock gap into the day's counter. */
    static final long MAX_COUNTED_TICK_MILLIS = 4000L;

    private static GuardService instance;
    private static String lastFailure = "";

    private Prefs prefs;
    private Handler handler;
    private WindowManager wm;
    private PowerManager powerManager;

    private TextView countdownText;
    private boolean countdownAttached;

    private View lockShield;
    private boolean shieldAttached;

    private long usedMillis;
    private String usageDay;
    private boolean usageDayChanged;
    private long lastTickElapsed;
    /**
     * Counting baseline, deliberately on {@code uptimeMillis} rather than {@code elapsedRealtime}:
     * the latter keeps running while the television is in deep sleep, so a standby that starves the
     * ticker would otherwise feed the day's quota with hours the family was not watching.
     */
    private long lastTickUptime;
    private long lastPersistElapsed;
    private long lastLockFocusElapsed;
    private volatile boolean locked;
    private long lockUiVisibleUntil;
    private boolean consoleVisible;
    /**
     * The parent screens hold a short lease rather than a plain flag: the shield must stay off
     * while the parent walks from the gate into the panel, but it has to come back on its own if
     * that screen dies without clearing the flag.
     */
    private long parentUiLeaseElapsed;
    private long parentUiLaunchUntil;
    static final long LOCK_UI_LEASE_MILLIS = 3000L;
    private boolean maintenanceApplied;
    private final StringBuilder inputBuffer = new StringBuilder();

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            handler.postDelayed(this, 1000L);
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            lastTickElapsed = SystemClock.elapsedRealtime();
            lastTickUptime = SystemClock.uptimeMillis();
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                // Power key turns the screen off normally, even while locked. Flush the
                // counter so a hard power cut cannot roll the day's usage back.
                persist();
                // Standby is not part of the parent grace: whoever wakes the television should find
                // the lock again rather than the screen the parent left it on. This is the one
                // boundary of the grace, so the family ending a viewing session is a power key away.
                invalidateParentAuthorization();
            } else if (Intent.ACTION_SCREEN_ON.equals(action) && locked) {
                // However the child blanked the screen, the lock must be the first thing back.
                lockUiVisibleUntil = 0L;
                LockActivity.launch(GuardService.this);
            }
            tick();
        }
    };

    static GuardService get() {
        return instance;
    }

    static void start(Context ctx) {
        if (!new Prefs(ctx).isProtectionEnabled()) {
            return;
        }
        Intent intent = new Intent(ctx, GuardService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Exception e) {
            reportFailure("无法启动管控服务，请从桌面打开应用", e);
        }
    }

    static void reportFailure(String message, Exception e) {
        lastFailure = message;
        Log.e(TAG, message, e);
    }

    static String lastFailure() {
        return lastFailure;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = new Prefs(this);
        handler = new Handler(Looper.getMainLooper());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        usedMillis = prefs.getUsedSeconds() * 1000L;
        usageDay = prefs.getUsageDay();
        lastTickElapsed = SystemClock.elapsedRealtime();
        lastTickUptime = SystemClock.uptimeMillis();
        lastPersistElapsed = lastTickElapsed;
        createChannel();
        startForeground(NOTIF_ID, buildNotification());
        if (!prefs.isProtectionEnabled()) {
            stopSelf();
            return;
        }
        DeviceProtection.applyIfOwner(this);
        maintenanceApplied = AdminSession.isMaintenanceActive();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, filter);
        WatchdogReceiver.schedule(this);
        handler.post(ticker);
        // The locked flag is persisted, so a service restart while locked re-asserts the screen
        // even if the clock or the quota would otherwise say the day is still open. A parent grace
        // restored from storage holds that back until the television stands by.
        if (lockRequired()) {
            enterLock();
        } else {
            showCountdown();
        }
    }

    /**
     * Whether the quota lock has to be on screen: the day's allowance is spent, or the lock was
     * already recorded as up. The parent grace is the one thing that can answer no while the
     * allowance is spent, because a parent standing in front of the television is what the PIN
     * proves, and the grace runs until the next standby.
     */
    private boolean lockRequired() {
        return !prefs.isParentGraceDeferringLock() && (prefs.isLocked() || isQuotaExhausted());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!prefs.isProtectionEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // Restart promptly if Android kills the process; the lock must not depend on the
        // service staying alive by luck.
        synchronizeDailyLock();
        if (lockRequired() && !locked) {
            enterLock();
        }
        // START_STICKY plus a foreground notification keeps the counter and the lock watchdog
        // alive across the TV's own memory reclaim.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        persist();
        removeCountdown();
        removeShield();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- counting

    private long remainingMillis() {
        return prefs.getDailyLimitMinutes() * 60000L + prefs.getBonusSeconds() * 1000L - usedMillis;
    }

    /**
     * The day's allowance is spent. Nothing suspends it any more: since 2.5.12 the parent PIN
     * proves a parent is present instead of switching the quota off, so the only way to give the
     * child more time is the panel's own bonus or a new daily limit.
     */
    private boolean isQuotaExhausted() {
        return remainingMillis() <= 0L;
    }

    private void tick() {
        if (!prefs.isProtectionEnabled()) {
            stopSelf();
            return;
        }
        boolean maintenance = AdminSession.isMaintenanceActive();
        if (maintenance != maintenanceApplied) {
            maintenanceApplied = maintenance;
            DeviceProtection.applyIfOwner(this);
            if (!maintenance && !AdminSession.isActive()) {
                consoleVisible = false;
            }
            if (maintenance) {
                LockActivity.dismissForMaintenance();
            }
        }
        // A screen left on past midnight must not stay locked: the new day starts with a fresh
        // allowance, so release the lock and let the countdown reappear.
        synchronizeDailyLock();

        long now = SystemClock.elapsedRealtime();
        lastTickElapsed = now;
        long delta = countedDelta(SystemClock.uptimeMillis());

        boolean screenOn = isScreenOn();
        if (screenOn && !locked) {
            usedMillis += delta;
        }

        long remaining = remainingMillis();
        if (lockRequired() && !locked) {
            enterLock();
        }

        if (locked) {
            // Only opaque guard UI, an authenticated console, or explicit parent maintenance
            // may replace the lock. Merely opening the launcher activity is not authorization.
            if (isLockUiVisible() || isParentUiVisible() || maintenance) {
                removeShield();
            } else {
                showShield();
            }
            if (!maintenance) {
                keepLockInFront(now);
            }
        } else {
            // The corner is the proof that the guard is still running, so it counts down on every
            // unlocked day as well. There is no longer a day without a countdown to explain.
            updateCountdown(remaining);
        }

        if (now - lastPersistElapsed >= 5000L) {
            persist();
            lastPersistElapsed = now;
        }
    }

    private void persist() {
        // Screen-off, service destruction or another Prefs reader can beat the next tick to
        // midnight. Never write yesterday's in-memory counter back into today's fresh quota.
        synchronizeUsageDay();
        prefs.setUsedSeconds(usedMillis / 1000L);
    }

    private void synchronizeUsageDay() {
        String currentDay = prefs.getUsageDay();
        if (!currentDay.equals(usageDay)) {
            usageDay = currentDay;
            usedMillis = prefs.getUsedSeconds() * 1000L;
            lastTickUptime = SystemClock.uptimeMillis();
            usageDayChanged = true;
        }
    }

    private void synchronizeDailyLock() {
        synchronizeUsageDay();
        if (usageDayChanged) {
            usageDayChanged = false;
            // Keep a deliberate lock made today; only a cleared yesterday lock may come down.
            if (locked && !prefs.isLocked()) exitLock("new_day");
        }
    }

    /**
     * Watching time is only what happens in front of a lit panel. The power manager alone was not
     * enough: it can keep believing the screen is on while the panel is dark, dozing, or handed to
     * a screensaver, and this television's Sony thermal wake lock means the monotonic clock runs
     * through standby as well. See {@link PanelState}.
     */
    private boolean isScreenOn() {
        return PanelState.isLit(this, powerManager, wm);
    }

    /**
     * Milliseconds of watching since the previous tick. The baseline is {@code uptimeMillis}, which
     * freezes on devices that really suspend, so a standby that starves the ticker cannot be charged
     * twice; it is not frozen on every set (the BRAVIA 4K 2015 keeps it running through standby), so
     * the panel witness in {@link #isScreenOn()} is what actually keeps standby out, and the ceiling
     * keeps a starved or replayed tick from dumping a long gap into the day's counter.
     */
    private long countedDelta(long nowUptime) {
        long delta = nowUptime - lastTickUptime;
        lastTickUptime = nowUptime;
        if (delta < 0L) {
            return 0L;
        }
        return Math.min(delta, MAX_COUNTED_TICK_MILLIS);
    }

    boolean isLocked() {
        // Survives service death: the lock screen asks this even when the singleton is gone.
        return locked || prefs.isLocked();
    }

    // ----------------------------------------------------------------- lock UI

    private void enterLock() {
        synchronizeUsageDay();
        // Automatic quota exhaustion is just as much a new authentication boundary as 33.
        // A previously opened gate must not finish a pre-lock password or reuse a panel session.
        if (!locked && !prefs.isLocked()) invalidateParentAuthorization();
        locked = true;
        prefs.setLocked(true);
        lockUiVisibleUntil = 0L;
        lastLockFocusElapsed = 0L;
        inputBuffer.setLength(0);
        persist();
        Log.i(TAG, "lock_enter remaining_seconds=" + remainingMillis() / 1000L);
        removeCountdown();
        if (!isParentUiVisible() && !AdminSession.isMaintenanceActive()) {
            showShield();
            LockActivity.launch(this);
        }
    }

    /**
     * HOME and similar keys push the lock screen to the background. The shield keeps the
     * screen black, but the remote keys would then reach the launcher, so pull the lock
     * screen back to the front.
     */
    private void keepLockInFront(long now) {
        if (isLockUiVisible() || isParentUiVisible() || isParentUiLaunchPending()
                || AdminSession.isMaintenanceActive()) {
            return;
        }

        // An authenticated panel that is on screen is parent UI even before its next resume
        // re-asserts the lease; pulling the lock over it would ask for the password again.
        if (MainActivity.isAuthorizedVisible()) {
            return;
        }
        if (prefs.isParentGraceDeferringLock()) {
            // A parent entered the PIN and stepped out of the console. Until the television stands by
            // the lock waits instead of chasing whatever window the parent opened: chasing it was the
            // password prompt that never went away (BRAVIA 4K 2015, 2.5.16 and earlier).
            return;
        }
        if (now - lastLockFocusElapsed < 800L) {
            return;
        }
        lastLockFocusElapsed = now;
        LockActivity.launch(this);
    }

    /**
     * Starts the 2.5.17 parent grace for a parent who authenticated and is leaving the console. The
     * grace is not a quota: nothing here changes the daily limit, today's bonus or the watched
     * seconds, and because the lock is stood down rather than hidden, whatever the family watches
     * during it keeps counting into 今日已看.
     *
     * @return whether the guard stands down from the lock
     */
    boolean grantParentGrace() {
        // The lock being up is what makes the grace defer it. On a day that still has allowance the
        // grace only stops the settings gate asking again, so a morning errand cannot postpone an
        // evening lock that has not happened yet.
        boolean fromLock = locked || prefs.isLocked() || isQuotaExhausted()
                || prefs.isParentGraceDeferringLock();
        prefs.startParentGrace(fromLock);
        if (fromLock) {
            // Clears the lock flag and the shield, and brings the corner back showing what is left.
            exitLock("parent_pin_verified");
        } else {
            removeShield();
        }
        return true;
    }

    private void exitLock(String reason) {
        Log.i(TAG, "lock_exit reason=" + reason);
        locked = false;
        prefs.setLocked(false);
        lockUiVisibleUntil = 0L;
        inputBuffer.setLength(0);
        lastTickElapsed = SystemClock.elapsedRealtime();
        lastTickUptime = SystemClock.uptimeMillis();
        removeShield();
        LockActivity.dismiss();
        showCountdown();
    }

    void onLockActivityVisibility(boolean visible) {
        if (!locked) {
            return;
        }
        boolean wasVisible = isLockUiVisible();
        lockUiVisibleUntil = visible ? SystemClock.elapsedRealtime() + LOCK_UI_LEASE_MILLIS : 0L;
        if (wasVisible != visible) Log.i(TAG, "lock_window covered=" + visible);
        if (isLockUiVisible() || isParentUiVisible() || AdminSession.isMaintenanceActive()) {
            removeShield();
        } else {
            showShield();
        }
    }

    private boolean isLockUiVisible() {
        if (lockUiVisibleUntil > 0L && lockUiVisibleUntil <= SystemClock.elapsedRealtime()) {
            lockUiVisibleUntil = 0L;
            Log.w(TAG, "lock_window coverage_expired; restoring shield");
        }
        return lockUiVisibleUntil > SystemClock.elapsedRealtime();
    }

    /** Seconds left before the next password attempt is accepted, 0 when entry is open. */
    long lockoutRemainingSeconds() {
        return prefs.lockoutRemainingSeconds();
    }

    String lockInput() {
        return inputBuffer.toString();
    }

    void onDigit(int digit) {
        if (!locked) {
            return;
        }
        if (inputBuffer.length() < Prefs.PASSWORD_LENGTH) {
            inputBuffer.append((char) ('0' + digit));
        }
    }

    void deleteLockDigit() {
        if (locked && inputBuffer.length() > 0) {
            inputBuffer.setLength(inputBuffer.length() - 1);
        }
    }

    boolean isPasswordComplete() {
        return inputBuffer.length() >= Prefs.PASSWORD_LENGTH;
    }

    /**
     * @return true when a stage advanced: the panel was opened, or the lock was released.
     */
    boolean onOk() {
        if (!locked) {
            return false;
        }
        String value = inputBuffer.toString();
        if (!Prefs.isValidPassword(value)) {
            return false;
        }
        Prefs.AuthResult result = prefs.authenticate(value);
        inputBuffer.setLength(0);
        if (result != Prefs.AuthResult.SUCCESS) {
            return false;
        }
        if (prefs.needsPasswordSetup()) {
            // The factory PIN only authorizes replacing it; it never reaches the panel at all.
            AdminSession.grant();
            LockActivity.openParentGate(this);
            return false;
        }
        AdminSession.grant();
        releaseAfterParentPin(this);
        LockActivity.openParentConsole(this);
        return true;
    }

    /** Called only after a fresh, configured television PIN succeeds; never from web login. */
    static void releaseAfterParentPin(Context context) {
        Prefs verified = new Prefs(context);
        if (!AdminSession.isActive() || verified.needsPasswordSetup()
                || !verified.isParentPinWindowActive()) {
            throw new IllegalStateException("Fresh parent PIN required");
        }
        Log.i(TAG, "native_parent_pin_verified runtime_pid=" + android.os.Process.myPid());
        GuardService service = instance;
        if (service != null) {
            service.synchronizeDailyLock();
            if (service.locked || verified.isLocked() || service.isQuotaExhausted()) {
                service.grantParentGrace();
            }
        } else if (verified.isGuardActive()) {
            verified.startParentGrace(true);
            verified.setLocked(false);
            LockActivity.dismiss();
        }
    }

    /**
     * Re-arms the lock from the panel. The day's quota is whatever the parent set it to: locking
     * cannot suspend it, and unlocking cannot restore time that was already watched.
     */
    void lockNow() {
        if (!AdminSession.isActive()) {
            return;
        }
        applyLockNow();
    }

    private void applyLockNow() {
        invalidateParentAuthorization();
        enterLock();
    }

    /** Old authorization and partial input must never cross a new lock/password boundary. */
    void invalidateParentAuthorization() {
        prefs.endParentGrace();
        prefs.clearSettingsHold();
        prefs.clearParentPinWindow();
        AdminSession.revoke();
        clearLockInput();
        consoleVisible = false;
        parentUiLeaseElapsed = 0L;
        parentUiLaunchUntil = 0L;
        AdminAuthActivity.invalidateForLock();
        refreshParentVisibility();
    }

    void clearLockInput() {
        inputBuffer.setLength(0);
    }

    private boolean isParentUiVisible() {
        return parentUiLeaseActive() || (consoleVisible && AdminSession.isActive());
    }

    /**
     * Whether a parent screen is the UI the family is looking at right now. The settings watcher
     * needs this because the parent panel is translucent: the page it was opened over stays
     * visible underneath it and keeps reporting itself as the foreground window.
     */
    static boolean isParentUiOnScreen() {
        GuardService service = instance;
        return service != null && service.isParentUiVisible();
    }

    private boolean parentUiLeaseActive() {
        return parentUiLeaseElapsed > SystemClock.elapsedRealtime();
    }

    void beginParentUiLaunch() {
        if (locked) parentUiLaunchUntil = SystemClock.elapsedRealtime() + 5000L;
    }

    void cancelParentUiLaunch() {
        parentUiLaunchUntil = 0L;
        refreshParentVisibility();
    }

    private boolean isParentUiLaunchPending() {
        return parentUiLaunchUntil > SystemClock.elapsedRealtime();
    }

    static boolean isParentUiStarting() {
        return instance != null && instance.isParentUiLaunchPending();
    }

    /** Lease for the opaque, non-exported parent screens; refreshed once a second while shown. */
    void setParentUiVisible(boolean visible) {
        if (visible) {
            parentUiLaunchUntil = 0L;
            parentUiLeaseElapsed = SystemClock.elapsedRealtime() + 5000L;
            consoleVisible = false;
        } else {
            parentUiLeaseElapsed = 0L;
        }
        refreshParentVisibility();
    }

    void setConsoleVisible(boolean visible) {
        consoleVisible = visible && AdminSession.isActive();
        if (consoleVisible) {
            parentUiLeaseElapsed = 0L;
        }
        refreshParentVisibility();
    }

    private void refreshParentVisibility() {
        if (!locked) {
            return;
        }
        if (isParentUiVisible() || isLockUiVisible() || AdminSession.isMaintenanceActive()) {
            removeShield();
        } else {
            showShield();
        }
    }

    void refreshProtection() {
        DeviceProtection.applyIfOwner(this);
        tick();
    }

    void prepareForUpdate() {
        if (!AdminSession.isMaintenanceActive()) {
            throw new IllegalStateException("Parent maintenance authorization required");
        }
        persist();
        refreshProtection();
    }

    void setDailyLimitMinutes(int minutes) {
        if (!AdminSession.isActive()) {
            throw new IllegalStateException("Parent authorization required");
        }
        applyDailyLimitMinutes(minutes);
    }

    private void applyDailyLimitMinutes(int minutes) {
        synchronizeUsageDay();
        prefs.setDailyLimitMinutes(minutes);
        prefs.endParentGrace();
        // A new limit resumes the daily quota without resetting watched time or today's bonus.
        if (locked && remainingMillis() > 0L) {
            exitLock("daily_limit_changed");
        }
        tick();
    }

    /** Grants extra minutes on top of the daily limit and releases the current lock. */
    void grantBonusMinutes(int minutes) {
        if (!AdminSession.isActive()) {
            throw new IllegalArgumentException("Parent authorization and valid bonus required");
        }
        applyBonusMinutes(minutes);
    }

    private void applyBonusMinutes(int minutes) {
        if (minutes < 1 || minutes > Prefs.MAX_BONUS_MINUTES - prefs.getBonusSeconds() / 60L) {
            throw new IllegalArgumentException("临时加时需在今天剩余的 1–60 分钟额度内");
        }
        long seconds = Math.max(0L, prefs.getBonusSeconds() + minutes * 60L);
        prefs.setBonusSeconds(seconds);
        prefs.endParentGrace();
        if (locked && remainingMillis() > 0L) {
            exitLock("bonus_granted");
        }
        tick();
    }

    /**
     * {@code 77 清零今天已看}: today's watched seconds go back to zero. This exists because the guard
     * is verified on a real television with a real remote, and every check that runs past the
     * allowance would otherwise lock the set for the rest of the day and cost the family its time.
     *
     * <p>Only today's usage moves. The daily limit and the bonus minutes stay exactly as the parent
     * left them, so this is a reset of the counter rather than a grant of viewing time - and because
     * the allowance is whole again, a lock that is currently up has nothing left to hold and comes
     * down with the countdown returning to the corner.
     */
    void clearTodayUsage() {
        if (!AdminSession.isActive()) {
            throw new IllegalStateException("Parent authorization required");
        }
        applyClearTodayUsage();
    }

    /** Browser commands share the television mutations, with their own live authorization. */
    void applyWebCommand(ParentWebController.Authorization auth, String code, int minutes) {
        auth.require();
        synchronizeUsageDay();
        switch (code) {
            case "12": applyDailyLimitMinutes(minutes); break;
            case "22": applyBonusMinutes(minutes); break;
            case "33": applyLockNow(); break;
            case "77": applyClearTodayUsage(); break;
            default: throw new IllegalArgumentException("Unknown parent command");
        }
    }

    private void applyClearTodayUsage() {
        synchronizeUsageDay();
        prefs.endParentGrace();
        usedMillis = 0L;
        prefs.setUsedSeconds(0L);
        // Re-arm the counting baseline: without this the gap since the previous tick would be
        // charged straight back onto the counter that has just been cleared.
        lastTickUptime = SystemClock.uptimeMillis();
        lastTickElapsed = SystemClock.elapsedRealtime();
        if (locked || prefs.isLocked()) {
            // Clears the lock flag and the shield and brings the corner back showing the full day.
            exitLock("usage_cleared");
        } else {
            updateCountdown(remainingMillis());
        }
    }

    // -------------------------------------------------------------- overlays

    private int overlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private boolean canOverlay() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void showCountdown() {
        if (!canOverlay()) {
            return;
        }
        if (countdownText == null) {
            countdownText = new TextView(this);
            countdownText.setTextColor(Color.WHITE);
            countdownText.setTypeface(Typeface.DEFAULT_BOLD);
            countdownText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            countdownText.setShadowLayer(6f, 0f, 0f, Color.BLACK);
            countdownText.setPadding(dp(14), dp(6), dp(14), dp(6));
            // A soft dark pill keeps the clock readable over bright video without hiding it.
            android.graphics.drawable.GradientDrawable pill =
                    new android.graphics.drawable.GradientDrawable();
            pill.setColor(Color.parseColor("#66000000"));
            pill.setCornerRadius(dp(20));
            countdownText.setBackground(pill);
        }
        if (countdownAttached) {
            return;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        // Higher than before so it clears the video card, but still below the top edge where
        // the television and the player draw their own clock and logo.
        lp.x = dp(24);
        lp.y = dp(40);
        try {
            wm.addView(countdownText, lp);
            countdownAttached = true;
        } catch (Exception ignored) {
        }
    }

    private void updateCountdown(long remaining) {
        if (!countdownAttached) {
            showCountdown();
        }
        if (countdownText == null) {
            return;
        }
        long seconds = Math.max(0L, remaining / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        String clock = hours > 0L
                ? String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds % 60L)
                : String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds % 60L);
        countdownText.setText("剩余 " + clock);
        countdownText.setTextColor(seconds <= 300L ? Color.parseColor("#FF6B6B") : Color.WHITE);
    }

    private void removeCountdown() {
        if (countdownText != null && countdownAttached) {
            try {
                wm.removeView(countdownText);
            } catch (Exception ignored) {
            }
            countdownAttached = false;
        }
    }

    private void showShield() {
        if (prefs.isParentGraceDeferringLock()) {
            // A black overlay over whatever the parent opened would defeat the grace: the shield is
            // what hides the screen, so it has to go up only when the lock is allowed back.
            removeShield();
            return;
        }
        if (!canOverlay()) {
            return;
        }
        if (shieldAttached) {
            return;
        }
        if (lockShield == null) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundColor(Color.BLACK);
            lockShield = box;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                // NOT_FOCUSABLE keeps key events flowing to the lock activity; the shield
                // only blocks touches and paints over whatever is behind it.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(lockShield, lp);
            shieldAttached = true;
        } catch (Exception e) {
            reportFailure("锁屏遮罩无法显示，请检查悬浮窗权限", e);
        }
    }

    private void removeShield() {
        if (lockShield != null && shieldAttached) {
            try {
                wm.removeView(lockShield);
            } catch (Exception ignored) {
            }
            shieldAttached = false;
        }
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    // ----------------------------------------------------------- notification

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "时长管控", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, flags);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_LOW);
        }
        builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("时长管控运行中")
                .setContentText("计时中")
                .setOngoing(true)
                .setContentIntent(pi);
        return builder.build();
    }
}
