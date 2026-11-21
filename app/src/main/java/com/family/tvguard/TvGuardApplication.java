package com.family.tvguard;

import android.app.ActivityManager;
import android.app.Application;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.List;

/** All services and parent screens share one runtime and one in-memory authorization session. */
public class TvGuardApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) return;
        // Some old TVs hide every peer's /proc entry even from the same UID. Serialize
        // startup instead of comparing peer start times. The lock holder takes over;
        // another startup cannot simultaneously choose to kill it.
        try (FileOutputStream output = new FileOutputStream(startupLockFile(), true);
                FileChannel channel = output.getChannel();
                FileLock ignored = acquireStartupLock(channel)) {
            // Refresh only after obtaining the lock: a process observed before waiting
            // could already have exited, and its PID could have been reused.
            List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes == null) return;
            for (ActivityManager.RunningAppProcessInfo peer : processes) {
                if (peer.pid <= 0 || peer.pid == Process.myPid() || peer.uid != Process.myUid()
                        || !getPackageName().equals(peer.processName)) continue;
                Log.w("TvGuard", "duplicate_runtime stopping_pid=" + peer.pid);
                Process.killProcess(peer.pid);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot acquire TV guard runtime startup lock", e);
        }
    }

    File startupLockFile() {
        return new File(getFilesDir(), "runtime-start.lock");
    }

    FileLock acquireStartupLock(FileChannel channel) throws IOException {
        return channel.lock();
    }
}
