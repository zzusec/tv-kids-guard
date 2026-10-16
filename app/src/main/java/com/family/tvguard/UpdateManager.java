package com.family.tvguard;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads only. Installation and parent authorization belong to the caller. */
public final class UpdateManager {
    static final String METADATA_URL = "https://github.com/zzusec/tv-kids-guard/releases/latest/download/update.json";
    static final String PREFS = "tvguard_updates";
    static final int PERIODIC_JOB_ID = 62001;
    static final int NOW_JOB_ID = 62002;
    static final long PERIOD_MS = 6 * 60 * 60 * 1000L;
    static final long BACKOFF_MS = 30 * 60 * 1000L;
    static final int MAX_JSON_BYTES = 64 * 1024;
    static final long MAX_APK_BYTES = 50 * 1024 * 1024L;
    static final int MAX_REDIRECTS = 3;
    private static final int SOCKET_TIMEOUT_MS = 15_000;
    private static final long RUN_TIMEOUT_MS = 240_000;
    private static final Object JOB_LOCK = new Object();
    private static final Object FILE_LOCK = new Object();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "tvguard-update");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Work active;

    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    // A narrow transport seam for offline tests; production uses platform TLS and trust store.
    static volatile ConnectionFactory connections = url -> (HttpURLConnection) url.openConnection();

    private UpdateManager() {}

    public static final class Snapshot {
        public final String status;
        public final String message;
        public final String versionName;
        public final int versionCode;

        Snapshot(String status, String message, int versionCode, String versionName) {
            this.status = status;
            this.message = message;
            this.versionCode = versionCode;
            this.versionName = versionName;
        }
    }

    public static final class ReadyUpdate {
        public final File apk;
        public final int versionCode;
        public final String versionName;

        ReadyUpdate(File apk, Metadata metadata) {
            this.apk = apk;
            this.versionCode = metadata.versionCode;
            this.versionName = metadata.versionName;
        }
    }

    /** Idempotent: never replaces an existing periodic job or resets its six-hour clock. */
    public static void schedule(Context context) {
        Context app = context.getApplicationContext();
        synchronized (JOB_LOCK) {
            try {
                JobScheduler scheduler = scheduler(app);
                if (pending(scheduler, PERIODIC_JOB_ID) == null) {
                    JobInfo job = new JobInfo.Builder(PERIODIC_JOB_ID,
                            new ComponentName(app, UpdateJobService.class))
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                            .setPersisted(true)
                            .setPeriodic(PERIOD_MS)
                            .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                            .build();
                    if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                        throw new IOException("系统未接受定时更新任务");
                    }
                    checkNow(app);
                }
            } catch (Exception e) {
                fail(app, e);
            }
        }
    }

    /** Enqueues a network-constrained job; no network or APK verification runs on the caller. */
    public static void checkNow(Context context) {
        Context app = context.getApplicationContext();
        synchronized (JOB_LOCK) {
            try {
                JobScheduler scheduler = scheduler(app);
                if (active != null && !active.cancelled) return;
                if (pending(scheduler, NOW_JOB_ID) != null) {
                    waitingForNetwork(app);
                    return;
                }
                JobInfo job = new JobInfo.Builder(NOW_JOB_ID,
                        new ComponentName(app, UpdateJobService.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                        .build();
                if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                    throw new IOException("系统未接受立即检查任务");
                }
                waitingForNetwork(app);
            } catch (Exception e) {
                fail(app, e);
            }
        }
    }

    private static void waitingForNetwork(Context context) throws IOException {
        SharedPreferences prefs = prefs(context);
        boolean ready = "ready".equals(prefs.getString("status", "idle"));
        state(context, ready ? "ready" : "checking",
                ready ? "更新已下载，等待家长安装；已安排联网检查" : "等待联网检查",
                prefs.getInt("versionCode", 0), prefs.getString("versionName", ""));
    }

    private static JobScheduler scheduler(Context context) throws IOException {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) throw new IOException("系统不支持更新任务");
        return scheduler;
    }

    private static JobInfo pending(JobScheduler scheduler, int id) {
        for (JobInfo job : scheduler.getAllPendingJobs()) {
            if (job.getId() == id) return job;
        }
        return null;
    }

    public static Snapshot snapshot(Context context) {
        SharedPreferences prefs = prefs(context);
        String status = prefs.getString("status", "idle");
        String message = prefs.getString("message", "尚未检查更新");
        int version = prefs.getInt("versionCode", 0);
        String name = prefs.getString("versionName", "");
        // A killed process cannot leave a permanently spinning progress indicator.
        synchronized (JOB_LOCK) {
            if (("checking".equals(status) || "downloading".equals(status)) && active == null) {
                JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
                if ("checking".equals(status) && scheduler != null && pending(scheduler, NOW_JOB_ID) != null) {
                    return new Snapshot("checking", "等待联网检查", version, name);
                }
                return new Snapshot("failed", "上次更新任务已中断，等待重新检查", version, name);
            }
        }
        if ("ready".equals(status)) {
            try {
                PackageInfo installed = installed(context);
                if (version <= version(installed)) {
                    return new Snapshot("current", "新版本已安装", versionInt(installed), name(installed));
                }
            } catch (IOException e) {
                return new Snapshot("failed", concise(e), version, name);
            }
        }
        return new Snapshot(status, message, version, name);
    }

    /** Worker-thread API. Rechecks bytes, package identity, SDK, version and current signer. */
    public static ReadyUpdate readyUpdate(Context context) throws IOException {
        try {
            return readyUpdate(context, new Cancellation());
        } catch (IOException e) {
            fail(context, e);
            throw e;
        }
    }

    private static ReadyUpdate readyUpdate(Context context, Cancellation cancellation) throws IOException {
        synchronized (FILE_LOCK) {
            cancellation.check();
            String json = prefs(context).getString("readyJson", null);
            if (json == null) return null;
            Metadata metadata = parseMetadata(json, context.getPackageName(), Build.VERSION.SDK_INT);
            if (metadata.versionCode <= version(installed(context))) return null;
            File apk = apkFile(context, metadata);
            validateApk(context, apk, metadata, cancellation);
            return new ReadyUpdate(apk, metadata);
        }
    }

    /** Cleanup is tied to observed installation, not a "current" metadata response. */
    public static void reconcileInstalled(Context context) {
        synchronized (FILE_LOCK) {
            SharedPreferences prefs = prefs(context);
            int readyVersion = prefs.getInt("readyVersion", 0);
            int baseVersion = prefs.getInt("readyBaseVersion", 0);
            if (readyVersion == 0) return;
            try {
                PackageInfo installed = installed(context);
                long version = version(installed);
                if (version < readyVersion || version <= baseVersion) return;
                File[] files = directory(context).listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.getName().endsWith(".apk")) {
                            String prefix = file.getName().split("-", 2)[0];
                            try {
                                if (Long.parseLong(prefix) <= version && !file.delete()) {
                                    throw new IOException("无法清理已安装更新文件");
                                }
                            } catch (NumberFormatException ignored) {
                                // Not one of this manager's completed APK files.
                            }
                        }
                    }
                }
                SharedPreferences.Editor editor = prefs.edit().remove("readyJson")
                        .remove("readyVersion").remove("readyBaseVersion");
                putState(editor, "current", "新版本已安装", versionInt(installed), name(installed));
                commit(editor);
            } catch (IOException e) {
                fail(context, e);
            }
        }
    }

    interface Completion { void finished(boolean retry); }

    static final class JobHandle {
        final Work work;
        final Completion completion;
        boolean detached;

        JobHandle(Work work, Completion completion) {
            this.work = work;
            this.completion = completion;
        }
    }

    static JobHandle startJob(Context context, Completion completion) {
        synchronized (JOB_LOCK) {
            Work work = active;
            if (work == null || work.cancelled) {
                work = new Work(context.getApplicationContext());
                active = work;
                Work queued = work;
                WORKER.execute(() -> execute(queued));
            }
            JobHandle handle = new JobHandle(work, completion);
            work.handles.add(handle);
            return handle;
        }
    }

    static void stopJob(JobHandle handle) {
        synchronized (JOB_LOCK) {
            handle.detached = true;
            boolean needed = false;
            for (JobHandle other : handle.work.handles) needed |= !other.detached;
            if (!needed) handle.work.cancel();
        }
    }

    private static final class Work extends Cancellation {
        final Context context;
        final List<JobHandle> handles = new ArrayList<>();
        volatile Thread thread;

        Work(Context context) { this.context = context; }

        @Override void cancel() {
            super.cancel();
            Thread running = thread;
            if (running != null) running.interrupt();
        }
    }

    static class Cancellation {
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        final long deadline = SystemClock.elapsedRealtime() + RUN_TIMEOUT_MS;

        void cancel() {
            cancelled = true;
            HttpURLConnection current = connection;
            if (current != null) current.disconnect();
        }

        void check() throws IOException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new IOException("更新任务已停止，将退避重试");
            }
            if (SystemClock.elapsedRealtime() > deadline) throw new IOException("更新任务超时");
        }
    }

    private static void execute(Work work) {
        boolean retry = false;
        work.thread = Thread.currentThread();
        try {
            check(work.context, work);
        } catch (Exception e) {
            retry = true;
            failOrRestoreReady(work.context, e, work);
        } finally {
            try {
                deletePartial(work.context);
            } catch (IOException e) {
                retry = true;
                fail(work.context, e);
            }
            work.thread = null;
            Thread.interrupted();
            final boolean result = retry;
            synchronized (JOB_LOCK) {
                if (active == work) active = null;
                for (JobHandle handle : work.handles) {
                    MAIN.post(() -> {
                        synchronized (JOB_LOCK) {
                            if (handle.detached) return;
                            handle.detached = true;
                        }
                        handle.completion.finished(result);
                    });
                }
            }
        }
    }

    static void check(Context context, Cancellation cancellation) throws IOException {
        cancellation.check();
        deletePartial(context);
        SharedPreferences previous = prefs(context);
        state(context, "checking", "正在检查更新", previous.getInt("versionCode", 0),
                previous.getString("versionName", ""));
        byte[] bytes;
        HttpURLConnection connection = open(new URL(METADATA_URL), cancellation, "application/json");
        try {
            long length = contentLength(connection);
            if (length > MAX_JSON_BYTES) throw new IOException("更新信息超过 64 KiB");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                copy(input, output, MAX_JSON_BYTES, length, cancellation);
                bytes = output.toByteArray();
            }
        } finally {
            connection.disconnect();
            cancellation.connection = null;
        }
        Metadata metadata = parseMetadata(new String(bytes, StandardCharsets.UTF_8),
                context.getPackageName(), Build.VERSION.SDK_INT);
        PackageInfo installed = installed(context);
        if (metadata.versionCode <= version(installed)) {
            // An older remote manifest must not erase a newer, already downloaded update.
            ReadyUpdate cached = readyUpdate(context, cancellation);
            if (cached != null) {
                state(context, "ready", "更新已下载，等待家长安装", cached.versionCode, cached.versionName);
            } else {
                state(context, "current", "已是最新版本", versionInt(installed), name(installed));
            }
            return;
        }
        File apk = apkFile(context, metadata);
        if (apk.isFile()) {
            try {
                validateApk(context, apk, metadata, cancellation);
                publishReady(context, metadata, versionInt(installed));
                return;
            } catch (IOException e) {
                cancellation.check();
                if (!apk.delete()) throw new IOException("无法移除无效更新文件", e);
            }
        }
        state(context, "downloading", "正在下载更新 " + metadata.versionName,
                metadata.versionCode, metadata.versionName);
        File partial = new File(directory(context), "download.part");
        connection = open(metadata.apkUrl, cancellation, "application/vnd.android.package-archive");
        try {
            long length = contentLength(connection);
            if (length != -1 && length != metadata.size) throw new IOException("APK 声明大小不符");
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(partial)) {
                copy(input, output, metadata.size, metadata.size, cancellation);
                output.getFD().sync();
            }
            validateApk(context, partial, metadata, cancellation);
            cancellation.check();
            synchronized (FILE_LOCK) {
                cancellation.check();
                // Installed version may have changed while the network request was running.
                installed = installed(context);
                if (metadata.versionCode <= version(installed)) {
                    state(context, "current", "新版本已安装", versionInt(installed), name(installed));
                    return;
                }
                if (!partial.renameTo(apk)) throw new IOException("无法保存完整更新文件");
                publishReady(context, metadata, versionInt(installed));
            }
        } finally {
            connection.disconnect();
            cancellation.connection = null;
            if (partial.exists() && !partial.delete()) throw new IOException("无法清理未完成下载");
        }
    }

    private static void publishReady(Context context, Metadata metadata, int installedVersion) throws IOException {
        SharedPreferences.Editor editor = prefs(context).edit().putString("readyJson", metadata.json)
                .putInt("readyVersion", metadata.versionCode).putInt("readyBaseVersion", installedVersion);
        putState(editor, "ready", "更新已下载，等待家长安装", metadata.versionCode, metadata.versionName);
        commit(editor);
    }

    static final class Metadata {
        final String packageName, versionName, sha256, notes, json;
        final int versionCode, minSdk;
        final long size;
        final URL apkUrl;

        Metadata(JSONObject object, String json) throws JSONException, IOException {
            this.json = json;
            packageName = text(object, "packageName", false);
            versionName = text(object, "versionName", false);
            notes = text(object, "notes", true);
            versionCode = (int) integer(object, "versionCode", 1, Integer.MAX_VALUE);
            minSdk = (int) integer(object, "minSdk", 1, Integer.MAX_VALUE);
            size = integer(object, "size", 1, MAX_APK_BYTES);
            sha256 = text(object, "sha256", false).toLowerCase(Locale.ROOT);
            if (!sha256.matches("[0-9a-f]{64}")) throw new IOException("无效 SHA-256");
            apkUrl = new URL(text(object, "apkUrl", false));
            validateUrl(apkUrl);
        }
    }

    static Metadata parseMetadata(String json, String packageName, int sdk) throws IOException {
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IOException("更新信息超过 64 KiB");
        }
        try {
            Metadata metadata = new Metadata(new JSONObject(json), json);
            if (!packageName.equals(metadata.packageName)) throw new IOException("更新包名不符");
            if (metadata.minSdk > sdk) throw new IOException("更新不支持此 Android 版本");
            return metadata;
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("更新信息格式无效", e);
        }
    }

    private static String text(JSONObject object, String key, boolean emptyAllowed) throws JSONException, IOException {
        Object value = object.get(key);
        if (!(value instanceof String) || !emptyAllowed && ((String) value).trim().isEmpty()) {
            throw new IOException("无效更新字段 " + key);
        }
        return (String) value;
    }

    private static long integer(JSONObject object, String key, long min, long max) throws JSONException, IOException {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IOException("无效更新字段 " + key);
        long number = ((Number) value).longValue();
        if (number < min || number > max) throw new IOException("更新字段超限 " + key);
        return number;
    }

    static void validateUrl(URL url) throws IOException {
        try {
            URI uri = url.toURI();
            String host = uri.getHost();
            // Current GitHub release downloads redirect to release-assets. No wildcard or legacy hosts.
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                    || uri.getFragment() != null || uri.getPort() != -1 && uri.getPort() != 443
                    || host == null || !("github.com".equalsIgnoreCase(host)
                    || "release-assets.githubusercontent.com".equalsIgnoreCase(host))) {
                throw new IOException("更新地址必须使用受信任的 HTTPS 域名");
            }
        } catch (URISyntaxException e) {
            throw new IOException("无效更新地址", e);
        }
    }

    static HttpURLConnection open(URL url, Cancellation cancellation, String accept) throws IOException {
        for (int redirects = 0; ; redirects++) {
            cancellation.check();
            validateUrl(url);
            HttpURLConnection connection = connections.open(url);
            cancellation.connection = connection;
            try {
                cancellation.check();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(SOCKET_TIMEOUT_MS);
                connection.setReadTimeout(SOCKET_TIMEOUT_MS);
                connection.setRequestProperty("Accept", accept);
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("User-Agent", "TVKidGuard-Updater");
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    if (redirects >= MAX_REDIRECTS) throw new IOException("更新地址重定向超过 3 次");
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) throw new IOException("更新重定向缺少地址");
                    url = new URL(url, location);
                    validateUrl(url);
                    connection.disconnect();
                    cancellation.connection = null;
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) throw new IOException("更新服务器 HTTP " + code);
                String encoding = connection.getHeaderField("Content-Encoding");
                if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
                    throw new IOException("更新响应使用了不支持的压缩");
                }
                cancellation.check();
                return connection;
            } catch (IOException | RuntimeException e) {
                connection.disconnect();
                cancellation.connection = null;
                throw e;
            }
        }
    }

    static long contentLength(HttpURLConnection connection) throws IOException {
        String value = connection.getHeaderField("Content-Length");
        if (value == null) return -1;
        try {
            long length = Long.parseLong(value);
            if (length < 0) throw new NumberFormatException();
            return length;
        } catch (NumberFormatException e) {
            throw new IOException("无效响应大小", e);
        }
    }

    static void copy(InputStream input, java.io.OutputStream output, long limit, long expected,
                     Cancellation cancellation) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        for (;;) {
            cancellation.check();
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, limit - total + 1));
            cancellation.check();
            if (read == -1) break;
            total += read;
            if (total > limit) throw new IOException("更新下载超过大小限制");
            output.write(buffer, 0, read);
        }
        if (expected != -1 && total != expected) throw new IOException("更新实际大小与声明不符");
    }

    static void validateApk(Context context, File file, Metadata metadata, Cancellation cancellation) throws IOException {
        if (!file.isFile() || file.length() != metadata.size) throw new IOException("APK 大小不符或文件缺失");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[16 * 1024];
                for (int count; (count = input.read(buffer)) != -1;) {
                    cancellation.check();
                    digest.update(buffer, 0, count);
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            if (!hex.toString().equals(metadata.sha256)) throw new IOException("APK SHA-256 不符");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("系统不支持 SHA-256", e);
        }
        cancellation.check();
        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), signatureFlags());
        validateArchive(installed(context), archive, metadata, Build.VERSION.SDK_INT);
        cancellation.check();
    }

    static void validateArchive(PackageInfo installed, PackageInfo archive, Metadata metadata, int sdk) throws IOException {
        if (archive == null || !metadata.packageName.equals(archive.packageName)
                || !installed.packageName.equals(archive.packageName)) throw new IOException("APK 包名不符或无法解析");
        if (version(archive) != metadata.versionCode || metadata.versionCode <= version(installed)) {
            throw new IOException("APK versionCode 不符或不是新版本");
        }
        if (!metadata.versionName.equals(archive.versionName)) throw new IOException("APK versionName 不符");
        if (metadata.minSdk > sdk) throw new IOException("更新不支持此 Android 版本");
        if (sdk >= 24 && (archive.applicationInfo == null
                || archive.applicationInfo.minSdkVersion != metadata.minSdk
                || archive.applicationInfo.minSdkVersion > sdk)) throw new IOException("APK minSdk 不符");
        Signature[] current = signers(installed, sdk);
        Signature[] incoming = signers(archive, sdk);
        if (!sameSignatures(current, incoming)) throw new IOException("APK 签名与已安装应用不同");
    }

    private static Signature[] signers(PackageInfo info, int sdk) {
        if (sdk >= 28) return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        return info.signatures;
    }

    static boolean sameSignatures(Signature[] first, Signature[] second) {
        if (first == null || second == null || first.length == 0 || first.length != second.length) return false;
        Set<Signature> a = new HashSet<>(Arrays.asList(first));
        Set<Signature> b = new HashSet<>(Arrays.asList(second));
        return !a.contains(null) && !b.contains(null) && a.size() == first.length && a.equals(b);
    }

    private static int signatureFlags() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    private static PackageInfo installed(Context context) throws IOException {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), signatureFlags());
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("无法读取已安装应用", e);
        }
    }

    private static long version(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static int versionInt(PackageInfo info) {
        return (int) Math.min(Integer.MAX_VALUE, version(info));
    }

    private static String name(PackageInfo info) { return info.versionName == null ? "" : info.versionName; }

    static File directory(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建私有更新目录");
        return directory;
    }

    static File apkFile(Context context, Metadata metadata) throws IOException {
        return new File(directory(context), metadata.versionCode + "-" + metadata.sha256 + ".apk");
    }

    private static void deletePartial(Context context) throws IOException {
        File partial = new File(directory(context), "download.part");
        if (partial.exists() && !partial.delete()) throw new IOException("无法清理未完成下载");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void state(Context context, String status, String message, int version, String name) throws IOException {
        SharedPreferences.Editor editor = prefs(context).edit();
        putState(editor, status, message, version, name);
        commit(editor);
    }

    private static void putState(SharedPreferences.Editor editor, String status, String message, int version, String name) {
        editor.putString("status", status).putString("message", message)
                .putInt("versionCode", version).putString("versionName", name);
    }

    private static void commit(SharedPreferences.Editor editor) throws IOException {
        if (!editor.commit()) throw new IOException("无法持久保存更新状态");
    }

    private static String concise(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) message = exception.getClass().getSimpleName();
        message = message.replaceAll("[\\r\\n]", " ");
        // Never persist signed redirect URLs or long server bodies in the parent-facing state.
        if (message.contains("://")) message = exception.getClass().getSimpleName() + "：更新网络请求失败";
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    private static void failOrRestoreReady(Context context, Exception exception, Cancellation cancellation) {
        if (!cancellation.cancelled && !Thread.currentThread().isInterrupted()) {
            try {
                // The network deadline may have expired. This is bounded local verification only;
                // stopJob still interrupts this same worker, so cancellation remains effective.
                ReadyUpdate ready = readyUpdate(context, new Cancellation());
                if (ready != null && !cancellation.cancelled) {
                    String error = concise(exception);
                    Log.w("TVGuardUpdate", error);
                    state(context, "ready", "更新已下载，等待家长安装；本次检查失败：" + error,
                            ready.versionCode, ready.versionName);
                    return;
                }
            } catch (IOException validation) {
                Log.w("TVGuardUpdate", "已有更新无法复用：" + concise(validation));
            }
        }
        fail(context, exception);
    }

    private static void fail(Context context, Exception exception) {
        String message = concise(exception);
        Log.w("TVGuardUpdate", message);
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor editor = prefs.edit();
        putState(editor, "failed", message + "（失败后由系统指数退避重试，起始 30 分钟）",
                prefs.getInt("versionCode", 0), prefs.getString("versionName", ""));
        if (!editor.commit()) Log.e("TVGuardUpdate", "无法持久保存更新错误");
    }
}
