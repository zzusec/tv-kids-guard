package com.family.tvguard;

import android.annotation.TargetApi;
import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.IdentityHashMap;
import java.util.Map;

/** Independent of GuardService and the protection toggle; all I/O runs on UpdateManager's worker. */
@TargetApi(21)
public final class UpdateJobService extends JobService {
    private final Map<JobParameters, UpdateManager.JobHandle> running = new IdentityHashMap<>();

    @Override
    public boolean onStartJob(JobParameters params) {
        if (running.containsKey(params)) return true;
        UpdateManager.JobHandle handle = UpdateManager.startJob(getApplicationContext(), retry -> {
            if (running.remove(params) != null) jobFinished(params, retry);
        });
        running.put(params, handle);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        UpdateManager.JobHandle handle = running.remove(params);
        if (handle != null) UpdateManager.stopJob(handle);
        // JobScheduler applies the configured 30-minute exponential backoff.
        return true;
    }

    @Override
    public void onDestroy() {
        for (UpdateManager.JobHandle handle : running.values()) UpdateManager.stopJob(handle);
        running.clear();
        super.onDestroy();
    }
}
