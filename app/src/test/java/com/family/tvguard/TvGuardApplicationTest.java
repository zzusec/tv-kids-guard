package com.family.tvguard;

import android.app.ActivityManager;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowProcess;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/** Startup ownership must work on TVs where every peer /proc entry is inaccessible. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33}, application = TvGuardApplicationTest.ProbeApplication.class)
public class TvGuardApplicationTest {
    private static final int UID = 10010;
    private ProbeApplication context;
    private ActivityManager manager;

    @Before
    public void setUp() {
        context = (ProbeApplication) RuntimeEnvironment.getApplication();
        manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ShadowProcess.setUid(UID);
        ShadowProcess.setPid(100);
        ShadowProcess.clearKilledProcesses();
        shadowOf(manager).setProcesses(new ArrayList<>());
        context.processesAfterLock = null;
        context.failLock = false;
        context.lockAcquisitions = 0;
    }

    @After
    public void tearDown() {
        ShadowProcess.clearKilledProcesses();
        shadowOf(manager).setProcesses(new ArrayList<>());
    }

    @Test
    public void onlyOtherDefaultProcessesOfTheSameUidAreStopped() {
        String name = context.getPackageName();
        shadowOf(manager).setProcesses(Arrays.asList(
                process(100, UID, name), process(200, UID + 1, name),
                process(300, UID, name + ":worker"), process(400, UID, "another.application"),
                process(0, UID, name), process(-1, UID, name), process(500, UID, name)));

        context.onCreate();

        assertTrue(ShadowProcess.wasKilled(500));
        for (int pid : new int[]{0, -1, 100, 200, 300, 400}) {
            assertFalse("excluded pid=" + pid, ShadowProcess.wasKilled(pid));
        }
        assertEquals(1, context.lockAcquisitions);
    }

    @Test
    public void startupTakesOverRegardlessOfPidWrapWithoutReadingAnyPeerProc() {
        String name = context.getPackageName();
        // These fake peer PIDs have no corresponding /proc files in the test runtime.
        shadowOf(manager).setProcesses(Arrays.asList(
                process(100, UID, name), process(99, UID, name), process(32760, UID, name)));

        context.onCreate();

        assertTrue(ShadowProcess.wasKilled(99));
        assertTrue(ShadowProcess.wasKilled(32760));
        assertFalse(ShadowProcess.wasKilled(100));
    }

    @Test
    public void peerSnapshotIsRefreshedAfterWaitingForStartupOwnership() {
        String name = context.getPackageName();
        shadowOf(manager).setProcesses(Arrays.asList(process(200, UID, name)));
        context.processesAfterLock = Arrays.asList(
                process(100, UID, name), process(300, UID, name));

        context.onCreate();

        assertFalse("stale snapshot must not be used", ShadowProcess.wasKilled(200));
        assertTrue(ShadowProcess.wasKilled(300));
        assertFalse(ShadowProcess.wasKilled(100));
    }

    @Test
    public void anEmptySnapshotStillSerializesStartupAndReleasesTheLock() throws Exception {
        context.onCreate();

        assertEquals(1, context.lockAcquisitions);
        assertTrue(context.startupLockFile().isFile());
        try (FileOutputStream output = new FileOutputStream(context.startupLockFile(), true);
                FileChannel channel = output.getChannel(); FileLock lock = channel.tryLock()) {
            assertNotNull("startup lock must not be held for the runtime lifetime", lock);
        }
    }

    @Test
    public void cannotStartASecondRuntimeSilentlyWhenStartupLockFails() {
        String name = context.getPackageName();
        shadowOf(manager).setProcesses(Arrays.asList(process(200, UID, name)));
        context.failLock = true;

        IllegalStateException error = assertThrows(IllegalStateException.class, context::onCreate);

        assertTrue(error.getCause() instanceof IOException);
        assertFalse("no cleanup without ownership", ShadowProcess.wasKilled(200));
    }

    private static ActivityManager.RunningAppProcessInfo process(int pid, int uid, String name) {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        info.pid = pid;
        info.uid = uid;
        info.processName = name;
        return info;
    }

    public static class ProbeApplication extends TvGuardApplication {
        List<ActivityManager.RunningAppProcessInfo> processesAfterLock;
        boolean failLock;
        int lockAcquisitions;

        @Override FileLock acquireStartupLock(FileChannel channel) throws IOException {
            lockAcquisitions++;
            if (failLock) throw new IOException("fixture lock failure");
            FileLock lock = super.acquireStartupLock(channel);
            if (processesAfterLock != null) {
                ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                shadowOf(manager).setProcesses(processesAfterLock);
            }
            return lock;
        }
    }
}
