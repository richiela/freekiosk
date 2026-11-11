package com.laiholdings.kioskadbkeeper;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;

/** Every 15 minutes: catches anything the other triggers missed, and re-registers the network callback. */
public class KeepJob extends JobService {
    @Override public boolean onStartJob(final JobParameters p) {
        final Context c = getApplicationContext();
        new Thread(() -> {
            try {
                Keeper.register(c);
                Keeper.reconcile(c, Keeper.Why.HEARTBEAT);
            } catch (Throwable t) {
                Keeper.w("job failed: " + t);
            } finally {
                jobFinished(p, false);
            }
        }, "keeper-job").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters p) { return false; }
}
