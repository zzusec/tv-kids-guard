package com.family.tvguard;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * Repeating alarm that re-arms the guard. AlarmManager keeps the registration even when Android
 * reclaims the app process for memory, so the watchdog can bring the service (and the lock) back
 * without anyone touching the TV.
 */
public class WatchdogReceiver extends BroadcastReceiver {

    private static final String ACTION = "com.family.tvguard.action.WATCHDOG";
    private static final long INTERVAL_MILLIS = 30_000L;

    static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            PendingIntent pending = pendingIntent(ctx);
            am.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL_MILLIS,
                    INTERVAL_MILLIS,
                    pending);
        } catch (Exception ignored) {
        }
    }

    static void cancel(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(pendingIntent(ctx));
            }
        } catch (Exception ignored) {
        }
    }

    private static PendingIntent pendingIntent(Context ctx) {
        Intent intent = new Intent(ctx, WatchdogReceiver.class);
        intent.setAction(ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, 0, intent, flags);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!new Prefs(context).isProtectionEnabled()) {
            cancel(context);
            return;
        }
        GuardService.start(context);
    }
}
