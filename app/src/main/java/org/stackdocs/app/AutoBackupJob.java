package org.stackdocs.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;

/** Daily wake-up for {@link AutoBackup}; the backup itself only runs when a week has passed since the last one. */
public final class AutoBackupJob extends JobService {
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override public boolean onStartJob(JobParameters parameters) {
        StackApplication app = (StackApplication) getApplication();
        // Locked (app lock) or not due: nothing to do now; the foreground hook runs it after unlock.
        if (app.resetRequested || !app.access.allowed()) return false;
        if (!AutoBackup.due(app)) { AutoBackup.verifyPendingAsync(app); return false; }
        ui.post(() -> AutoBackup.run(app, ok -> jobFinished(parameters, !ok)));
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) { return true; }
}
