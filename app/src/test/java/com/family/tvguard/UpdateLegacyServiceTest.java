package com.family.tvguard;

import android.app.AlarmManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowNetworkInfo;
import org.robolectric.shadows.ShadowPowerManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.util.ReflectionHelpers.getStaticField;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
@LooperMode(LooperMode.Mode.PAUSED)
public class UpdateLegacyServiceTest {
    private Application context;
    private ShadowAlarmManager alarms;
    private UpdateManager.ConnectionFactory originalConnections;
    private final AtomicInteger requests = new AtomicInteger();

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        alarms = shadowOf((AlarmManager) context.getSystemService(Context.ALARM_SERVICE));
        originalConnections = UpdateManager.connections;
        UpdateManager.connections = url -> {
            requests.incrementAndGet();
            throw new IOException("offline fixture");
        };
    }

    @After
    public void tearDown() throws Exception {
        drain();
        UpdateManager.connections = originalConnections;
    }

    @Test
    public void kitkatSchedulesPeriodicAndImmediateServiceAlarmsWithoutJobScheduler() {
        UpdateManager.schedule(context);
        assertEquals(2, alarms.getScheduledAlarms().size());
        ShadowAlarmManager.ScheduledAlarm periodic = periodic();
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, periodic.type);
        assertEquals(UpdateManager.PERIOD_MS, periodic.interval);
        assertEquals(UpdateLegacyService.class.getName(), shadowOf(periodic.operation).getSavedIntent().getComponent().getClassName());
        assertTrue(shadowOf(periodic.operation).isService());
        assertEquals("checking", UpdateManager.snapshot(context).status);
        assertTrue(UpdateLegacyService.waiting(context));
        UpdateManager.schedule(context);
        assertEquals(2, alarms.getScheduledAlarms().size());
        assertSame(periodic, periodic());
        assertEquals(0, requests.get());
    }

    @Test
    public void retriesBackOffExponentiallyAndManualCheckCancelsTheDelay() throws Exception {
        long[] minutes = {30, 60, 120, 240, 360, 360};
        for (long value : minutes) {
            UpdateLegacyService.completed(context, true);
            assertEquals(1, alarms.getScheduledAlarms().size());
            assertEquals(value * 60_000L, immediate().triggerAtTime - SystemClock.elapsedRealtime());
        }
        UpdateManager.checkNow(context);
        assertEquals(1, alarms.getScheduledAlarms().size());
        assertEquals(SystemClock.elapsedRealtime(), immediate().triggerAtTime);
        assertEquals(0, context.getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE).getInt("legacyRetry", -1));
        assertEquals("checking", UpdateManager.snapshot(context).status);
    }

    @Test
    public void disconnectedNetworkDoesNotOpenHttpsAndServiceReleasesWakeLockAndRetries() throws Exception {
        shadowOf((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE)).setActiveNetworkInfo(null);
        ServiceController<UpdateLegacyService> controller = Robolectric.buildService(UpdateLegacyService.class).create();
        UpdateLegacyService service = controller.get();
        service.onStartCommand(new Intent("com.family.tvguard.UPDATE_NOW"), 0, 1);
        drain();
        assertEquals(0, requests.get());
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld());
        assertTrue(shadowOf(service).isStoppedBySelf());
        assertEquals("failed", UpdateManager.snapshot(context).status);
        assertTrue(UpdateManager.snapshot(context).message.contains("无可用网络"));
        assertEquals(UpdateManager.BACKOFF_MS, immediate().triggerAtTime - SystemClock.elapsedRealtime());
        controller.destroy();
    }

    @Test
    public void connectedLegacyServiceChecksOffMainThreadAndStopsWithoutRetryOnSuccess() throws Exception {
        shadowOf((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE)).setActiveNetworkInfo(
                ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true));
        int version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        byte[] json = new JSONObject().put("packageName", context.getPackageName()).put("versionCode", version)
                .put("versionName", "installed").put("minSdk", 19).put("size", 1)
                .put("sha256", new String(new char[64]).replace('\0', '0')).put("notes", "test")
                .put("apkUrl", "https://github.com/zzusec/tv-kids-guard/releases/download/test/update.apk")
                .toString().getBytes(StandardCharsets.UTF_8);
        UpdateManager.connections = url -> {
            assertNotSame(Looper.getMainLooper().getThread(), Thread.currentThread());
            requests.incrementAndGet();
            return new HttpURLConnection(url) {
                @Override public int getResponseCode() { return 200; }
                @Override public InputStream getInputStream() { return new ByteArrayInputStream(json); }
                @Override public void disconnect() {}
                @Override public boolean usingProxy() { return false; }
                @Override public void connect() {}
            };
        };
        ServiceController<UpdateLegacyService> controller = Robolectric.buildService(UpdateLegacyService.class).create();
        UpdateLegacyService service = controller.get();
        service.onStartCommand(new Intent("com.family.tvguard.UPDATE_NOW"), 0, 1);
        drain();
        assertEquals(1, requests.get());
        assertEquals("current", UpdateManager.snapshot(context).status);
        PowerManager.WakeLock lock = ShadowPowerManager.getLatestWakeLock();
        assertNotNull(lock);
        assertFalse(lock.isHeld());
        assertTrue(shadowOf(service).isStoppedBySelf());
        assertTrue(alarms.getScheduledAlarms().isEmpty());
        controller.destroy();
    }

    @Test
    public void unsupportedSdkAndUntrustedReleaseHostsRemainRejectedOnKitkat() throws Exception {
        assertThrows(IOException.class, () -> UpdateManager.validateUrl(new URL("https://github.com.evil.test/update.apk")));
        assertThrows(IOException.class, () -> UpdateManager.validateUrl(new URL("http://github.com/update.apk")));
        assertThrows(IOException.class, () -> UpdateManager.parseMetadata("{\"packageName\":\"" + context.getPackageName()
                + "\",\"versionName\":\"next\",\"versionCode\":999,\"minSdk\":21,\"size\":1,\"notes\":\"test\","
                + "\"sha256\":\"0000000000000000000000000000000000000000000000000000000000000000\","
                + "\"apkUrl\":\"https://github.com/update.apk\"}", context.getPackageName(), 19));
    }

    private ShadowAlarmManager.ScheduledAlarm periodic() {
        for (ShadowAlarmManager.ScheduledAlarm alarm : alarms.getScheduledAlarms()) {
            if (alarm.interval > 0) return alarm;
        }
        throw new AssertionError("missing periodic alarm");
    }

    private ShadowAlarmManager.ScheduledAlarm immediate() {
        for (ShadowAlarmManager.ScheduledAlarm alarm : alarms.getScheduledAlarms()) {
            if (alarm.interval == 0) return alarm;
        }
        throw new AssertionError("missing immediate alarm");
    }

    private void drain() throws Exception {
        ExecutorService worker = getStaticField(UpdateManager.class, "WORKER");
        worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper();
    }
}
