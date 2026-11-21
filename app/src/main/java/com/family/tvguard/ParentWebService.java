package com.family.tvguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** The LAN console stays reachable even when a parent has disabled quota enforcement. */
public class ParentWebService extends Service {
    private static final String CHANNEL = "tvguard-parent-web";
    private static volatile String error = "";
    private ParentHttpServer server;
    private ParentWebController controller;

    static void start(Context context) {
        try {
            Intent intent = new Intent(context, ParentWebService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception e) {
            error = "网页后台无法启动：" + e.getMessage();
            GuardService.reportFailure(error, e);
        }
    }

    static String lastError() { return error; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "家长网页后台",
                    NotificationManager.IMPORTANCE_LOW));
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 8888,
                new Intent(this, MainActivity.class), flags);
        Notification.Builder notification = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        startForeground(1002, notification.setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("家长网页后台")
                .setContentText("局域网端口 8888 · 家长控制后台")
                .setContentIntent(open).setOngoing(true).build());
        try {
            controller = new ParentWebController(this);
            server = new ParentHttpServer(controller::handle, ParentHttpServer.PORT, null,
                    (message, failure) -> {
                        error = "网页后台监听失败：" + message;
                        android.util.Log.e("TvGuard", error, failure);
                    });
            error = "";
            server.start();
        } catch (Exception e) {
            error = "网页后台端口 8888 启动失败：" + e.getMessage();
            GuardService.reportFailure(error, e);
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (server != null) {
            try {
                if (!server.isListening()) error = "";
                server.start();
            } catch (Exception e) {
                error = "网页后台监听恢复失败：" + e.getMessage();
                GuardService.reportFailure(error, e);
            }
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (server != null) server.close();
        if (controller != null) controller.close();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
