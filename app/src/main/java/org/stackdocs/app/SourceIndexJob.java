package org.stackdocs.app;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Every six hours (and after a restart): rescan the folder sources. Nothing runs while the app lock is locked or the battery is low. */
public final class SourceIndexJob extends JobService {
    static final int JOB = 7301;
    static final int VERSION = 1;

    @Override public boolean onStartJob(JobParameters parameters) {
        StackApplication app = (StackApplication) getApplication();
        if (app.resetRequested || !app.access.allowed() || app.sourceIndexer == null) return false;   // the foreground trigger runs it after unlock
        if (app.scanShare != null) app.scanShare.start();   // queued scans that could not be saved earlier
        app.sourceIndexer.run(null, () -> jobFinished(parameters, false));
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        StackApplication app = (StackApplication) getApplication();
        if (app.sourceIndexer != null) app.sourceIndexer.cancel("cancelled");
        return true;   // run again later; the pending rows are in the database
    }
}
