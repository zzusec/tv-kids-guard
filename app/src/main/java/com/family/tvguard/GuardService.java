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
    private boolean lockActivityVisible;
    private boolean consoleVisible;
    /**
     * The parent screens hold a short lease rather than a plain flag: the shield must stay off
     * while the parent walks from the gate into the panel, but it has to come back on its own if
     * that screen dies without clearing the flag.
     */
    private long parentUiLeaseElapsed;
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
            } else if (Intent.ACTION_SCREEN_ON.equals(action) && locked) {
                // However the child blanked the screen, the lock must be the first thing back.
                lockActivityVisible = false;
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
        // even if the clock or the quota would otherwise say the day is still open.
        if (prefs.isLocked() || isQuotaExhausted()) {
            enterLock();
        } else {
            showCountdown();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!prefs.isProtectionEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // Restart promptly if Android kills the process; the lock must not depend on the
        // service staying alive by luck.
        if (prefs != null && (prefs.isLocked() || isQuotaExhausted()) && !locked) {
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
        if (prefs.rollOverIfNeeded()) {
            usedMillis = 0L;
            if (locked) {
                exitLock();
            }
        }

        long now = SystemClock.elapsedRealtime();
        lastTickElapsed = now;
        long delta = countedDelta(SystemClock.uptimeMillis());

        boolean screenOn = isScreenOn();
        if (screenOn && !locked) {
            usedMillis += delta;
        }

        long remaining = remainingMillis();
        if (isQuotaExhausted() && !locked) {
            enterLock();
        }

        if (locked) {
            // Only opaque guard UI, an authenticated console, or explicit parent maintenance
            // may replace the lock. Merely opening the launcher activity is not authorization.
            if (lockActivityVisible || isParentUiVisible() || maintenance) {
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
        prefs.setUsedSeconds(usedMillis / 1000L);
    }

    @SuppressWarnings("deprecation")
    private boolean isScreenOn() {
        return powerManager != null && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH
                ? powerManager.isInteractive() : powerManager.isScreenOn());
    }

    /**
     * Milliseconds of watching since the previous tick. The baseline is {@code uptimeMillis}, which
     * does not advance while the television is suspended, so standby never counts; the ceiling keeps
     * a starved or replayed tick from dumping a long gap into the day's counter.
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
        locked = true;
        prefs.setLocked(true);
        lockActivityVisible = false;
        lastLockFocusElapsed = 0L;
        inputBuffer.setLength(0);
        persist();
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
        if (lockActivityVisible || isParentUiVisible() || AdminSession.isMaintenanceActive()) {
            return;
        }

        // An authenticated panel that is on screen is parent UI even before its next resume
        // re-asserts the lease; pulling the lock over it would ask for the password again.
        if (MainActivity.isAuthorizedVisible()) {
            return;
        }
        if (now - lastLockFocusElapsed < 800L) {
            return;
        }
        lastLockFocusElapsed = now;
        LockActivity.launch(this);
    }

    private void exitLock() {
        locked = false;
        prefs.setLocked(false);
        lockActivityVisible = false;
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
        lockActivityVisible = visible;
        if (visible || isParentUiVisible() || AdminSession.isMaintenanceActive()) {
            removeShield();
        } else {
            showShield();
        }
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
        // Since 2.5.12 the password is proof that a parent is present, not a day of unlimited
        // viewing: it hands over to the panel, and only the panel's bonus or daily limit can give
        // the child more time. The lock stays up and returns when the panel closes.
        AdminSession.grant();
        LockActivity.openParentConsole(this);
        return true;
    }

    /**
     * Re-arms the lock from the panel. The day's quota is whatever the parent set it to: locking
     * cannot suspend it, and unlocking cannot restore time that was already watched.
     */
    void lockNow() {
        if (!AdminSession.isActive()) {
            return;
        }
        if (!locked) {
            enterLock();
        }
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

    /** Lease for the opaque, non-exported parent screens; refreshed once a second while shown. */
    void setParentUiVisible(boolean visible) {
        if (visible) {
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
        if (isParentUiVisible() || lockActivityVisible || AdminSession.isMaintenanceActive()) {
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
        prefs.setDailyLimitMinutes(minutes);
        // A new limit resumes the daily quota without resetting watched time or today's bonus.
        if (locked && remainingMillis() > 0L) {
            exitLock();
        }
        tick();
    }

    /** Grants extra minutes on top of the daily limit and releases the current lock. */
    void grantBonusMinutes(int minutes) {
        if (!AdminSession.isActive() || minutes == 0
                || minutes > Prefs.MAX_BONUS_MINUTES - prefs.getBonusSeconds() / 60L) {
            throw new IllegalArgumentException("Parent authorization and valid bonus required");
        }
        long seconds = Math.max(0L, prefs.getBonusSeconds() + minutes * 60L);
        prefs.setBonusSeconds(seconds);
        if (locked) {
            exitLock();
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
        } catch (Exception ignored) {
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
