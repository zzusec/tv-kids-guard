package com.family.tvguard;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.IOException;

/** KitKat background downloads. Alarms survive process death; BootReceiver restores them on boot. */
public final class UpdateLegacyService extends Service {
    private static final String PERIODIC = "com.family.tvguard.UPDATE_PERIODIC";
    private static final String NOW = "com.family.tvguard.UPDATE_NOW";
    private static final Object LOCK = new Object();
    private UpdateManager.JobHandle running;
    private PowerManager.WakeLock wakeLock;

    static void schedule(Context context) {
        synchronized (LOCK) {
            try {
                if (alarmIntent(context, true, PendingIntent.FLAG_NO_CREATE) == null) {
                    alarms(context).setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            SystemClock.elapsedRealtime() + UpdateManager.PERIOD_MS,
                            UpdateManager.PERIOD_MS, alarmIntent(context, true, 0));
                    checkNow(context);
                }
            } catch (Exception e) {
                UpdateManager.fail(context, e);
            }
        }
    }

    static void checkNow(Context context) {
        synchronized (LOCK) {
            try {
                if (UpdateManager.isRunning()) return;
                saveRetry(context, 0);
                enqueue(context, 0);
                UpdateManager.waitingForNetwork(context);
            } catch (Exception e) {
                UpdateManager.fail(context, e);
            }
        }
    }

    static boolean waiting(Context context) {
        return alarmIntent(context, false, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_ONE_SHOT) != null;
    }

    private static AlarmManager alarms(Context context) throws IOException {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) throw new IOException("系统不支持旧版更新定时任务");
        return manager;
    }

    private static PendingIntent alarmIntent(Context context, boolean periodic, int flags) {
        return PendingIntent.getService(context, periodic ? UpdateManager.PERIODIC_JOB_ID : UpdateManager.NOW_JOB_ID,
                new Intent(context, UpdateLegacyService.class).setAction(periodic ? PERIODIC : NOW), flags);
    }

    private static void enqueue(Context context, long delay) throws IOException {
        PendingIntent previous = alarmIntent(context, false, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_ONE_SHOT);
        if (previous != null) {
            alarms(context).cancel(previous);
            previous.cancel();
        }
        alarms(context).set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay,
                alarmIntent(context, false, PendingIntent.FLAG_ONE_SHOT));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE);
    }

    private static void saveRetry(Context context, int retry) throws IOException {
        if (!prefs(context).edit().putInt("legacyRetry", retry).commit()) {
            throw new IOException("无法保存旧版更新重试状态");
        }
    }

    static void completed(Context context, boolean retry) throws IOException {
        synchronized (LOCK) {
            if (!retry) {
                saveRetry(context, 0);
                return;
            }
            int failures = Math.min(5, prefs(context).getInt("legacyRetry", 0) + 1);
            saveRetry(context, failures);
            long delay = Math.min(UpdateManager.PERIOD_MS, UpdateManager.BACKOFF_MS << (failures - 1));
            enqueue(context, delay);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !(PERIODIC.equals(intent.getAction()) || NOW.equals(intent.getAction()))) {
            if (running == null) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (running != null) return START_NOT_STICKY;
        try {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power == null) throw new IOException("无法保持更新下载唤醒");
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvguard:update");
            wakeLock.acquire(270_000L);
            running = UpdateManager.startJob(this, retry -> {
                running = null;
                try {
                    completed(this, retry);
                } catch (IOException e) {
                    UpdateManager.fail(this, e);
                } finally {
                    releaseWakeLock();
                    stopSelf();
                }
            });
        } catch (Exception e) {
            UpdateManager.fail(this, e);
            try {
                completed(this, true);
            } catch (IOException scheduling) {
                UpdateManager.fail(this, scheduling);
            }
            releaseWakeLock();
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (running != null) {
            UpdateManager.stopJob(running);
            running = null;
            try {
                completed(this, true);
            } catch (IOException e) {
                UpdateManager.fail(this, e);
            }
        }
        releaseWakeLock();
        super.onDestroy();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
