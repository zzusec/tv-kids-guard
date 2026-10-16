package com.family.tvguard;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Looper;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowJobScheduler;
import org.robolectric.shadows.ShadowJobService;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSigningInfo;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 33})
public class UpdateManagerTest {
    private Application context;
    private SharedPreferences prefs;
    private ShadowPackageManager packages;
    private PackageInfo installed;
    private JobScheduler scheduler;
    private Signature signer;
    private int nextVersion;
    private final byte[] apkBytes = "offline signed APK fixture bytes".getBytes(StandardCharsets.UTF_8);
    private final Deque<FakeConnection> responses = new ArrayDeque<>();
    private final List<URL> requests = new ArrayList<>();
    private UpdateManager.ConnectionFactory originalConnections;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        prefs = context.getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        packages = Shadow.extract(context.getPackageManager());
        installed = packages.getInternalMutablePackageInfo(context.getPackageName());
        // Never assume an obsolete release code: derive every candidate from the built app.
        nextVersion = (int) installedVersion() + 1;
        signer = new Signature(new byte[]{1, 2, 3});
        setSignatures(installed, signer);
        scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        scheduler.cancelAll();
        File[] files = UpdateManager.directory(context).listFiles();
        if (files != null) for (File file : files) assertTrue(file.delete());
        originalConnections = UpdateManager.connections;
        UpdateManager.connections = url -> {
            synchronized (responses) {
                requests.add(url);
                FakeConnection connection = responses.poll();
                if (connection == null) throw new IOException("unexpected offline request");
                connection.requestThread = Thread.currentThread();
                return connection;
            }
        };
    }

    @After
    public void tearDown() {
        UpdateManager.connections = originalConnections;
        scheduler.cancelAll();
    }

    @Test
    public void firstScheduleAddsImmediateNetworkJobAndDoesNotResetPeriodicClock() {
        UpdateManager.schedule(context);
        JobInfo periodic = pending(UpdateManager.PERIODIC_JOB_ID);
        JobInfo immediate = pending(UpdateManager.NOW_JOB_ID);
        assertNotNull(periodic);
        assertNotNull(immediate);
        assertTrue(periodic.isPersisted());
        assertTrue(periodic.isPeriodic());
        assertEquals(UpdateManager.PERIOD_MS, periodic.getIntervalMillis());
        assertEquals(JobInfo.NETWORK_TYPE_ANY, periodic.getNetworkType());
        assertEquals(JobInfo.NETWORK_TYPE_ANY, immediate.getNetworkType());
        assertFalse(immediate.isPeriodic());
        assertEquals(UpdateManager.BACKOFF_MS, immediate.getInitialBackoffMillis());
        assertEquals(JobInfo.BACKOFF_POLICY_EXPONENTIAL, immediate.getBackoffPolicy());
        UpdateManager.schedule(context);
        UpdateManager.checkNow(context);
        assertSame(periodic, pending(UpdateManager.PERIODIC_JOB_ID));
        assertSame(immediate, pending(UpdateManager.NOW_JOB_ID));
        assertEquals(2, scheduler.getAllPendingJobs().size());
        assertTrue(requests.isEmpty());
        scheduler.cancel(UpdateManager.NOW_JOB_ID);
        UpdateManager.schedule(context);
        assertNull(pending(UpdateManager.NOW_JOB_ID));
        UpdateManager.checkNow(context);
        assertNotNull(pending(UpdateManager.NOW_JOB_ID));
    }

    @Test
    public void manualOfflineCheckShowsWaitingAndPreservesReadyVersion() throws Exception {
        UpdateManager.checkNow(context);
        assertEquals("checking", UpdateManager.snapshot(context).status);
        assertTrue(UpdateManager.snapshot(context).message.contains("等待联网"));
        scheduler.cancelAll();
        UpdateManager.Metadata metadata = metadata(manifest());
        writeReady(metadata);
        registerArchive(metadata, archive());
        UpdateManager.checkNow(context);
        assertEquals("ready", UpdateManager.snapshot(context).status);
        assertEquals(nextVersion, UpdateManager.snapshot(context).versionCode);
        assertNotNull(UpdateManager.readyUpdate(context));
        assertTrue(requests.isEmpty());
    }

    @Test
    public void failedRefreshRestoresRevalidatedReadyPackageForOfflineInstall() throws Exception {
        UpdateManager.Metadata metadata = metadata(manifest());
        File ready = writeReady(metadata);
        registerArchive(metadata, archive());
        FakeConnection failure = response(new byte[0]);
        failure.code = 503;
        responses.add(failure);
        AtomicBoolean retried = new AtomicBoolean();
        UpdateManager.startJob(context, retried::set);
        waitForWorkerToFinish();
        UpdateManager.Snapshot snapshot = UpdateManager.snapshot(context);
        assertTrue(retried.get());
        assertEquals("ready", snapshot.status);
        assertEquals(nextVersion, snapshot.versionCode);
        assertTrue(snapshot.message.contains("本次检查失败"));
        assertTrue(snapshot.message.contains("503"));
        assertTrue(ready.exists());
        assertNotNull(UpdateManager.readyUpdate(context));
    }

    @Test
    public void timedOutRefreshKeepsVersionDuringCheckAndCanLocallyRecoverReady() throws Exception {
        UpdateManager.Metadata metadata = metadata(manifest());
        writeReady(metadata);
        registerArchive(metadata, archive());
        FakeConnection failure = response(new byte[0]);
        failure.code = 503;
        AtomicBoolean versionPreserved = new AtomicBoolean();
        UpdateManager.connections = url -> {
            versionPreserved.set(prefs.getInt("versionCode", 0) == nextVersion);
            ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
            return failure;
        };
        UpdateManager.startJob(context, retry -> {});
        waitForWorkerToFinish();
        assertTrue(versionPreserved.get());
        assertEquals("ready", UpdateManager.snapshot(context).status);
        assertEquals(nextVersion, UpdateManager.snapshot(context).versionCode);
        assertNotNull(UpdateManager.readyUpdate(context));
    }

    @Test
    public void failedRefreshDoesNotRecoverCorruptedReadyPackage() throws Exception {
        UpdateManager.Metadata metadata = metadata(manifest());
        File ready = writeReady(metadata);
        registerArchive(metadata, archive());
        try (FileOutputStream output = new FileOutputStream(ready)) { output.write(new byte[apkBytes.length]); }
        FakeConnection failure = response(new byte[0]);
        failure.code = 503;
        responses.add(failure);
        UpdateManager.startJob(context, retry -> {});
        waitForWorkerToFinish();
        assertEquals("failed", UpdateManager.snapshot(context).status);
        expectIOException(() -> UpdateManager.readyUpdate(context));
    }

    @Test
    public void schedulingFailureIsVisibleAndNotReportedAsChecking() {
        ((ShadowJobScheduler) Shadow.extract(scheduler)).failOnJob(UpdateManager.PERIODIC_JOB_ID);
        UpdateManager.schedule(context);
        assertEquals("failed", UpdateManager.snapshot(context).status);
        assertTrue(UpdateManager.snapshot(context).message.contains("系统未接受"));
        assertNull(pending(UpdateManager.NOW_JOB_ID));
    }

    @Test
    public void metadataRequiresCorrectPackageSupportedSdkAndStrictBoundedFields() throws Exception {
        assertEquals(nextVersion, metadata(manifest()).versionCode);
        JSONObject wrong = manifest().put("packageName", "other.package");
        expectIOException(() -> metadata(wrong));
        expectIOException(() -> metadata(manifest().put("minSdk", Build.VERSION.SDK_INT + 1)));
        expectIOException(() -> metadata(manifest().put("versionCode", "14")));
        expectIOException(() -> metadata(manifest().put("versionCode", nextVersion + 0.5)));
        expectIOException(() -> metadata(manifest().put("versionCode", 0)));
        expectIOException(() -> metadata(manifest().put("versionCode", 2147483648L)));
        expectIOException(() -> metadata(manifest().put("size", 0)));
        expectIOException(() -> metadata(manifest().put("size", UpdateManager.MAX_APK_BYTES + 1)));
        expectIOException(() -> metadata(manifest().put("sha256", "bad")));
        JSONObject missing = manifest();
        missing.remove("notes");
        expectIOException(() -> metadata(missing));
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i <= UpdateManager.MAX_JSON_BYTES; i++) huge.append('x');
        expectIOException(() -> UpdateManager.parseMetadata(huge.toString(), context.getPackageName(), Build.VERSION.SDK_INT));
    }

    @Test
    public void httpsWhitelistRejectsDowngradesCredentialsLookalikesPortsAndUnneededHosts() throws Exception {
        UpdateManager.validateUrl(new URL("https://github.com/zzusec/file"));
        UpdateManager.validateUrl(new URL("https://release-assets.githubusercontent.com/file?sig=secret"));
        for (String url : new String[]{"http://github.com/file", "https://github.com.evil.test/file",
                "https://github.com@evil.test/file", "https://user@github.com/file",
                "https://github.com:444/file", "https://github.com/file#fragment",
                "https://objects.githubusercontent.com/file", "https://raw.githubusercontent.com/file",
                "https://127.0.0.1/file", "https://release-assets.githubusercontent.com.evil.test/file"}) {
            expectIOException(() -> UpdateManager.validateUrl(new URL(url)));
        }
    }

    @Test
    public void redirectsAreManuallyValidatedBeforeOpeningNextHopAndHaveHardLimit() throws Exception {
        FakeConnection first = redirect("https://evil.test/payload");
        responses.add(first);
        expectIOException(() -> UpdateManager.open(new URL(UpdateManager.METADATA_URL),
                new UpdateManager.Cancellation(), "application/json"));
        assertEquals(1, requests.size());
        assertTrue(first.disconnected);
        assertFalse(first.getInstanceFollowRedirects());
        requests.clear();
        for (int i = 0; i < 3; i++) responses.add(redirect("https://github.com/hop" + i));
        FakeConnection ok = response(new byte[0]);
        responses.add(ok);
        assertSame(ok, UpdateManager.open(new URL(UpdateManager.METADATA_URL), new UpdateManager.Cancellation(), "application/json"));
        assertEquals(4, requests.size());
        assertTrue(ok.getConnectTimeout() > 0);
        assertTrue(ok.getReadTimeout() > 0);
        requests.clear();
        for (int i = 0; i < 4; i++) responses.add(redirect("https://github.com/hop" + i));
        expectIOException(() -> UpdateManager.open(new URL(UpdateManager.METADATA_URL), new UpdateManager.Cancellation(), "application/json"));
        assertEquals(4, requests.size());
        assertTrue(responses.isEmpty());
    }

    @Test
    public void errorStatusesCompressionAndMalformedLengthsCannotBecomeSuccess() throws Exception {
        FakeConnection missing = response(new byte[0]);
        missing.code = 404;
        responses.add(missing);
        expectIOException(() -> UpdateManager.check(context, new UpdateManager.Cancellation()));
        FakeConnection compressed = response(new byte[0]);
        compressed.headers.put("Content-Encoding", "gzip");
        responses.add(compressed);
        expectIOException(() -> UpdateManager.check(context, new UpdateManager.Cancellation()));
        FakeConnection badLength = response(new byte[0]);
        badLength.headers.put("Content-Length", "broken");
        expectIOException(() -> UpdateManager.contentLength(badLength));
        badLength.headers.put("Content-Length", "-1");
        expectIOException(() -> UpdateManager.contentLength(badLength));
    }

    @Test
    public void streamingEnforcesDeclaredAndActualLimitsEvenWithoutContentLength() throws Exception {
        expectIOException(() -> UpdateManager.copy(new ByteArrayInputStream(new byte[5]),
                new ByteArrayOutputStream(), 4, -1, new UpdateManager.Cancellation()));
        expectIOException(() -> UpdateManager.copy(new ByteArrayInputStream(new byte[3]),
                new ByteArrayOutputStream(), 4, 4, new UpdateManager.Cancellation()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        UpdateManager.copy(new ByteArrayInputStream(new byte[4]), out, 4, 4, new UpdateManager.Cancellation());
        assertEquals(4, out.size());
        responses.add(response(new byte[UpdateManager.MAX_JSON_BYTES + 1]));
        expectIOException(() -> UpdateManager.check(context, new UpdateManager.Cancellation()));
    }

    @Test
    public void taskDeadlineAndCancellationStopBeforeReadingMoreBytes() throws Exception {
        UpdateManager.Cancellation expired = new UpdateManager.Cancellation();
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
        expectIOException(expired::check);
        UpdateManager.Cancellation cancelled = new UpdateManager.Cancellation();
        cancelled.cancel();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        expectIOException(() -> UpdateManager.copy(new ByteArrayInputStream(new byte[3]), out, 5, -1, cancelled));
        assertEquals(0, out.size());
    }

    @Test
    public void downloadsValidatedPrivateApkPersistsReadyAndReusesCompletePackage() throws Exception {
        JSONObject manifest = manifest();
        UpdateManager.Metadata metadata = metadata(manifest);
        registerArchive(metadata, archive());
        responses.add(response(manifest.toString().getBytes(StandardCharsets.UTF_8)));
        responses.add(response(apkBytes));
        UpdateManager.check(context, new UpdateManager.Cancellation());
        UpdateManager.Snapshot snapshot = UpdateManager.snapshot(context);
        assertEquals("ready", snapshot.status);
        assertEquals(nextVersion, snapshot.versionCode);
        assertEquals("next-release", snapshot.versionName);
        assertTrue(prefs.contains("readyJson"));
        UpdateManager.ReadyUpdate ready = UpdateManager.readyUpdate(context);
        assertNotNull(ready);
        assertEquals(UpdateManager.directory(context), ready.apk.getParentFile());
        assertEquals(context.getFilesDir(), ready.apk.getParentFile().getParentFile());
        assertEquals(nextVersion, ready.versionCode);
        assertFalse(new File(UpdateManager.directory(context), "download.part").exists());
        responses.add(response(manifest.toString().getBytes(StandardCharsets.UTF_8)));
        UpdateManager.check(context, new UpdateManager.Cancellation());
        assertEquals(3, requests.size()); // second check only fetches JSON, not the valid APK again
        assertEquals("ready", UpdateManager.snapshot(context).status);
    }

    @Test
    public void readyUpdateRehashesAndRechecksArchiveWithoutGrantingAuthorization() throws Exception {
        JSONObject manifest = manifest();
        UpdateManager.Metadata metadata = metadata(manifest);
        File file = writeReady(metadata);
        registerArchive(metadata, archive());
        SharedPreferences other = context.getSharedPreferences("tvguard_prefs", Context.MODE_PRIVATE);
        other.edit().putString("authorization_sentinel", "unchanged").commit();
        assertNotNull(UpdateManager.readyUpdate(context));
        assertEquals("unchanged", other.getString("authorization_sentinel", ""));
        byte[] tampered = apkBytes.clone();
        tampered[0] ^= 1;
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(tampered); }
        expectIOException(() -> UpdateManager.readyUpdate(context));
        assertEquals("failed", UpdateManager.snapshot(context).status);
        assertTrue(UpdateManager.snapshot(context).message.contains("SHA-256"));
    }

    @Test
    public void archiveRequiresExactPackageIncreasingVersionNameSdkAndCurrentSigner() throws Exception {
        UpdateManager.Metadata metadata = metadata(manifest());
        UpdateManager.validateArchive(installed, archive(), metadata, Build.VERSION.SDK_INT);
        PackageInfo wrongPackage = archive();
        wrongPackage.packageName = "other.package";
        expectIOException(() -> UpdateManager.validateArchive(installed, wrongPackage, metadata, Build.VERSION.SDK_INT));
        PackageInfo wrongVersion = archive();
        wrongVersion.versionCode++;
        expectIOException(() -> UpdateManager.validateArchive(installed, wrongVersion, metadata, Build.VERSION.SDK_INT));
        PackageInfo wrongName = archive();
        wrongName.versionName = "forged";
        expectIOException(() -> UpdateManager.validateArchive(installed, wrongName, metadata, Build.VERSION.SDK_INT));
        PackageInfo wrongSdk = archive();
        wrongSdk.applicationInfo.minSdkVersion++;
        expectIOException(() -> UpdateManager.validateArchive(installed, wrongSdk, metadata, Build.VERSION.SDK_INT));
        PackageInfo wrongSigner = archive();
        setSignatures(wrongSigner, new Signature(new byte[]{9, 9}));
        expectIOException(() -> UpdateManager.validateArchive(installed, wrongSigner, metadata, Build.VERSION.SDK_INT));
        PackageInfo unsigned = archive();
        unsigned.signatures = null;
        if (Build.VERSION.SDK_INT >= 28) unsigned.signingInfo = null;
        expectIOException(() -> UpdateManager.validateArchive(installed, unsigned, metadata, Build.VERSION.SDK_INT));
        expectIOException(() -> UpdateManager.validateArchive(installed, null, metadata, Build.VERSION.SDK_INT));
        JSONObject old = manifest().put("versionCode", installedVersion());
        UpdateManager.Metadata oldMetadata = metadata(old);
        PackageInfo sameVersion = archive();
        sameVersion.versionCode = (int) installedVersion();
        expectIOException(() -> UpdateManager.validateArchive(installed, sameVersion, oldMetadata, Build.VERSION.SDK_INT));
    }

    @Test
    public void signerComparisonIsExactSetNotSingleMatchingOrHistoricalCertificate() {
        Signature second = new Signature(new byte[]{4, 5});
        assertTrue(UpdateManager.sameSignatures(new Signature[]{signer, second}, new Signature[]{second, signer}));
        assertFalse(UpdateManager.sameSignatures(new Signature[]{signer, second}, new Signature[]{signer}));
        assertFalse(UpdateManager.sameSignatures(new Signature[]{signer}, new Signature[]{second}));
        assertFalse(UpdateManager.sameSignatures(new Signature[]{signer, signer}, new Signature[]{signer, signer}));
        assertFalse(UpdateManager.sameSignatures(null, new Signature[]{signer}));
        assertFalse(UpdateManager.sameSignatures(new Signature[0], new Signature[0]));
    }

    @Test
    public void oldRemoteManifestCannotEraseNewerReadyUpdateAndReconcileWaitsForActualInstall() throws Exception {
        UpdateManager.Metadata metadata = metadata(manifest());
        File ready = writeReady(metadata);
        registerArchive(metadata, archive());
        JSONObject remote = manifest().put("versionCode", installedVersion());
        responses.add(response(remote.toString().getBytes(StandardCharsets.UTF_8)));
        UpdateManager.check(context, new UpdateManager.Cancellation());
        assertEquals("ready", UpdateManager.snapshot(context).status);
        assertTrue(ready.exists());
        UpdateManager.reconcileInstalled(context);
        assertTrue(ready.exists());
        assertTrue(prefs.contains("readyJson"));
        installed.versionCode = nextVersion;
        assertNull(UpdateManager.readyUpdate(context));
        assertEquals("current", UpdateManager.snapshot(context).status);
        assertTrue(ready.exists()); // snapshot/current is not cleanup authority
        UpdateManager.reconcileInstalled(context);
        assertFalse(ready.exists());
        assertFalse(prefs.contains("readyJson"));
        assertEquals("current", prefs.getString("status", ""));
    }

    @Test
    public void nonNewRemoteVersionNeverDownloadsOrExposesInstallableUpdate() throws Exception {
        JSONObject remote = manifest().put("versionCode", installedVersion());
        responses.add(response(remote.toString().getBytes(StandardCharsets.UTF_8)));
        UpdateManager.check(context, new UpdateManager.Cancellation());
        assertEquals("current", UpdateManager.snapshot(context).status);
        assertNull(UpdateManager.readyUpdate(context));
        assertEquals(1, requests.size());
    }

    @Test
    public void truncatedOrOversizedDownloadsCleanPartialAndNeverBecomeReady() throws Exception {
        for (byte[] payload : new byte[][]{new byte[apkBytes.length - 1], new byte[apkBytes.length + 1]}) {
            responses.add(response(manifest().toString().getBytes(StandardCharsets.UTF_8)));
            responses.add(response(payload)); // no HTTP Content-Length: actual streaming bound still applies
            expectIOException(() -> UpdateManager.check(context, new UpdateManager.Cancellation()));
            assertNull(UpdateManager.readyUpdate(context));
            assertFalse(new File(UpdateManager.directory(context), "download.part").exists());
        }
        responses.add(response(manifest().toString().getBytes(StandardCharsets.UTF_8)));
        FakeConnection mismatch = response(apkBytes);
        mismatch.headers.put("Content-Length", String.valueOf(apkBytes.length + 1));
        responses.add(mismatch);
        expectIOException(() -> UpdateManager.check(context, new UpdateManager.Cancellation()));
        assertFalse(new File(UpdateManager.directory(context), "download.part").exists());
    }

    @Test
    public void orphanedProgressIsVisibleAsInterruptedRatherThanPermanentlySpinning() {
        prefs.edit().putString("status", "downloading").commit();
        assertEquals("failed", UpdateManager.snapshot(context).status);
        assertTrue(UpdateManager.snapshot(context).message.contains("中断"));
    }

    @Test
    public void concurrentJobHandlesShareOneWorkerAndStoppingLastHandleDisconnectsNetwork() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        FakeConnection blocked = response(new byte[0]);
        blocked.onDisconnect = disconnected::countDown;
        blocked.body = new InputStream() {
            @Override public int read() throws IOException {
                reading.countDown();
                try {
                    if (!disconnected.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout");
                } catch (InterruptedException e) {
                    throw new IOException("cancelled", e);
                }
                throw new IOException("disconnected");
            }
        };
        responses.add(blocked);
        AtomicBoolean called = new AtomicBoolean();
        UpdateManager.JobHandle first = UpdateManager.startJob(context, retry -> called.set(true));
        assertTrue(reading.await(5, TimeUnit.SECONDS));
        UpdateManager.JobHandle second = UpdateManager.startJob(context, retry -> called.set(true));
        assertSame(first.work, second.work);
        assertNotSame(Looper.getMainLooper().getThread(), blocked.requestThread);
        UpdateManager.stopJob(first);
        assertFalse(blocked.disconnected);
        UpdateManager.stopJob(second);
        assertTrue(disconnected.await(5, TimeUnit.SECONDS));
        waitForWorkerToFinish();
        assertEquals(1, requests.size());
        assertFalse(called.get());
        assertEquals("failed", UpdateManager.snapshot(context).status);
        assertTrue(UpdateManager.snapshot(context).message.contains("退避"));
    }

    @Test
    public void workerFailurePersistsVisibleErrorAndRequestsRetry() throws Exception {
        FakeConnection failure = response(new byte[0]);
        failure.code = 503;
        responses.add(failure);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean retry = new AtomicBoolean();
        UpdateManager.startJob(context, result -> {
            retry.set(result);
            finished.set(true);
        });
        waitForWorkerToFinish();
        assertTrue(finished.get());
        assertTrue(retry.get());
        assertEquals("failed", prefs.getString("status", ""));
        assertTrue(UpdateManager.snapshot(context).message.contains("HTTP 503"));
    }

    @Test
    public void jobServiceFinishesSuccessfulWorkAndRequestsRetryForFailure() throws Exception {
        UpdateJobService service = Robolectric.buildService(UpdateJobService.class).create().get();
        JSONObject current = manifest().put("versionCode", installedVersion());
        responses.add(response(current.toString().getBytes(StandardCharsets.UTF_8)));
        assertTrue(service.onStartJob(Shadow.newInstanceOf(JobParameters.class)));
        waitForWorkerToFinish();
        ShadowJobService shadow = Shadow.extract(service);
        assertTrue(shadow.getIsJobFinished());
        assertFalse(shadow.getIsRescheduleNeeded());
        FakeConnection failure = response(new byte[0]);
        failure.code = 500;
        responses.add(failure);
        assertTrue(service.onStartJob(Shadow.newInstanceOf(JobParameters.class)));
        waitForWorkerToFinish();
        assertTrue(shadow.getIsRescheduleNeeded());
        service.onDestroy();
    }

    @Test
    public void jobServiceStopCancelsWithoutCallingJobFinishedAfterStop() throws Exception {
        UpdateJobService service = Robolectric.buildService(UpdateJobService.class).create().get();
        JobParameters params = Shadow.newInstanceOf(JobParameters.class);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        FakeConnection blocked = response(new byte[0]);
        blocked.onDisconnect = disconnected::countDown;
        blocked.body = new InputStream() {
            @Override public int read() throws IOException {
                reading.countDown();
                try {
                    if (!disconnected.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout");
                } catch (InterruptedException e) { throw new IOException("cancelled", e); }
                throw new IOException("disconnected");
            }
        };
        responses.add(blocked);
        assertTrue(service.onStartJob(params));
        assertTrue(reading.await(5, TimeUnit.SECONDS));
        assertTrue(service.onStartJob(params)); // same params cannot register a duplicate running job
        assertTrue(service.onStopJob(params));
        assertTrue(disconnected.await(5, TimeUnit.SECONDS));
        waitForWorkerToFinish();
        assertFalse(((ShadowJobService) Shadow.extract(service)).getIsJobFinished());
        assertEquals(1, requests.size());
        service.onDestroy();
    }

    private void waitForWorkerToFinish() throws Exception {
        // Post a terminal sentinel via the single worker to avoid race-prone timing sleeps.
        java.util.concurrent.ExecutorService worker = org.robolectric.util.ReflectionHelpers.getStaticField(UpdateManager.class, "WORKER");
        worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        ShadowLooper.shadowMainLooper().idle();
    }

    private long installedVersion() {
        return Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
    }

    private JSONObject manifest() throws Exception {
        return new JSONObject().put("packageName", context.getPackageName())
                .put("versionCode", nextVersion).put("versionName", "next-release")
                .put("minSdk", 21).put("size", apkBytes.length)
                .put("sha256", sha256(apkBytes)).put("notes", "offline test")
                .put("apkUrl", "https://github.com/zzusec/tv-kids-guard/releases/download/test/update.apk");
    }

    private UpdateManager.Metadata metadata(JSONObject object) throws Exception {
        return UpdateManager.parseMetadata(object.toString(), context.getPackageName(), Build.VERSION.SDK_INT);
    }

    private PackageInfo archive() {
        PackageInfo info = new PackageInfo();
        info.packageName = context.getPackageName();
        info.versionCode = nextVersion;
        info.versionName = "next-release";
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = context.getPackageName();
        info.applicationInfo.minSdkVersion = 21;
        setSignatures(info, signer);
        return info;
    }

    private void setSignatures(PackageInfo info, Signature signature) {
        info.signatures = new Signature[]{signature};
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo = new SigningInfo();
            ((ShadowSigningInfo) Shadow.extract(info.signingInfo)).setSignatures(new Signature[]{signature});
        }
    }

    private void registerArchive(UpdateManager.Metadata metadata, PackageInfo archive) throws Exception {
        packages.setPackageArchiveInfo(new File(UpdateManager.directory(context), "download.part").getAbsolutePath(), archive);
        packages.setPackageArchiveInfo(UpdateManager.apkFile(context, metadata).getAbsolutePath(), archive);
    }

    private File writeReady(UpdateManager.Metadata metadata) throws Exception {
        File file = UpdateManager.apkFile(context, metadata);
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(apkBytes); }
        prefs.edit().putString("readyJson", metadata.json).putInt("readyVersion", metadata.versionCode)
                .putInt("readyBaseVersion", (int) installedVersion()).putString("status", "ready")
                .putInt("versionCode", metadata.versionCode).putString("versionName", metadata.versionName).commit();
        return file;
    }

    private JobInfo pending(int id) {
        for (JobInfo job : scheduler.getAllPendingJobs()) if (job.getId() == id) return job;
        return null;
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format("%02x", value & 255));
        return hex.toString();
    }

    private static FakeConnection redirect(String target) throws Exception {
        FakeConnection connection = response(new byte[0]);
        connection.code = 302;
        connection.headers.put("Location", target);
        return connection;
    }

    private static FakeConnection response(byte[] bytes) throws Exception {
        return new FakeConnection(bytes);
    }

    interface ThrowingRunnable { void run() throws Exception; }

    private static void expectIOException(ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            fail("Expected IOException");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    static final class FakeConnection extends HttpURLConnection {
        final Map<String, String> headers = new HashMap<>();
        InputStream body;
        int code = 200;
        volatile boolean disconnected;
        Thread requestThread;
        Runnable onDisconnect;

        FakeConnection(byte[] bytes) throws Exception {
            super(new URL("https://github.com/offline-test"));
            body = new ByteArrayInputStream(bytes);
        }

        @Override public int getResponseCode() { return code; }
        @Override public String getHeaderField(String name) { return headers.get(name); }
        @Override public InputStream getInputStream() { return body; }
        @Override public void disconnect() {
            disconnected = true;
            if (onDisconnect != null) onDisconnect.run();
        }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }
}
