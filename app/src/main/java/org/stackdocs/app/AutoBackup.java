package org.stackdocs.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Scheduled library backup into a folder the user picked once, through Android's folder picker. Any document
 * provider can be the destination (a cloud app such as Filen Files, Google Drive, a USB drive); Stack holds
 * only the permission Android keeps for that folder and never an account or credentials.
 *
 * Only files named {@code Stack-auto-<timestamp>.stackbackup} are ever pruned, so nothing else in the folder is touched.
 */
final class AutoBackup {
    static final int KEEP = 5;
    static final long INTERVAL = 7L * 24 * 60 * 60 * 1000;
    /** After a failed attempt, wait before trying again so a broken destination is not hammered. */
    private static final long RETRY_AFTER = 6L * 60 * 60 * 1000;
    private static final String PREFIX = "Stack-auto-", SUFFIX = ".stackbackup";
    private static final int JOB = 0x41424b50, JOB_VERSION = 1;
    private static final AtomicBoolean running = new AtomicBoolean();

    private AutoBackup() {}

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("auto_backup", Context.MODE_PRIVATE); }

    static Uri tree(Context context) {
        String saved = prefs(context).getString("tree", "");
        return saved.isEmpty() ? null : Uri.parse(saved);
    }

    static boolean enabled(Context context) { return tree(context) != null; }

    static String newName(long time) { return PREFIX + new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT).format(new Date(time)) + SUFFIX; }

    /** Status line for the Backup screen. */
    static String label(Context context) {
        SharedPreferences p = prefs(context);
        if (tree(context) == null) return "Off · Save a copy to a folder every week";
        String folder = p.getString("tree_name", "");
        StringBuilder out = new StringBuilder("Weekly · ").append(folder.isEmpty() ? "chosen folder" : folder);
        String error = p.getString("last_error", "");
        long ok = p.getLong("last_ok", 0);
        if (!error.isEmpty()) out.append(" · Last attempt failed");
        else if (ok > 0) {
            out.append(" · Last ").append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(ok)));
            String state = p.getString("upload_state", "");
            if (state.equals("checking") || state.equals("uploading")) out.append(" · Upload not confirmed yet");
        }
        else out.append(" · First backup pending");
        return out.toString();
    }

    static String lastError(Context context) { return prefs(context).getString("last_error", ""); }

    /** Keeps the permission Android grants for the picked folder across restarts and starts the schedule. */
    static void enable(Context context, Uri tree, int grantFlags, String displayName) {
        ContentResolver resolver = context.getContentResolver();
        Uri previous = tree(context);
        resolver.takePersistableUriPermission(tree, grantFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        if (previous != null && !previous.equals(tree)) release(resolver, previous);
        prefs(context).edit().putString("tree", tree.toString()).putString("tree_name", displayName == null ? "" : displayName)
                .remove("last_error").putLong("last_attempt", 0).commit();
        schedule(context);
    }

    static void disable(Context context) {
        Uri previous = tree(context);
        if (previous != null) release(context.getContentResolver(), previous);
        prefs(context).edit().clear().commit();
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs != null) jobs.cancel(JOB);
    }

    private static void release(ContentResolver resolver, Uri tree) {
        try { resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
        catch (RuntimeException ignored) { /* the grant is already gone */ }
    }

    /** Daily check; the real interval is enforced by {@link #due}, so changing it never needs a reschedule. */
    static void schedule(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null) return;
        if (!enabled(context)) { jobs.cancel(JOB); return; }
        // Called on every process start, and scheduling again would stop a running job; keep a current one.
        JobInfo current = jobs.getPendingJob(JOB);
        if (current != null && current.getExtras().getInt("version") == JOB_VERSION) return;
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putInt("version", JOB_VERSION);
        JobInfo.Builder job = new JobInfo.Builder(JOB, new ComponentName(context, AutoBackupJob.class))
                .setExtras(extras).setPersisted(true).setPeriodic(24L * 60 * 60 * 1000);
        if (android.os.Build.VERSION.SDK_INT >= 26) job.setRequiresBatteryNotLow(true);
        jobs.schedule(job.build());
    }

    static boolean due(Context context) {
        if (!enabled(context)) return false;
        SharedPreferences p = prefs(context);
        long now = System.currentTimeMillis();
        long ok = p.getLong("last_ok", 0), attempt = p.getLong("last_attempt", 0);
        if (attempt > now) attempt = 0; // clock moved back
        if (ok > now) ok = 0;
        if (!p.getString("last_error", "").isEmpty() && now - attempt < RETRY_AFTER) return false;
        return now - ok >= INTERVAL - 60 * 60 * 1000;
    }

    /** Foreground hook: run when due and Stack is unlocked. */
    static void kickIfDue(StackApplication app) {
        if (app.resetRequested || !app.access.allowed()) return;
        if (!due(app)) { verifyPendingAsync(app); return; }
        run(app, null);
    }

    /** Runs one scheduled backup now (also the "Back up now" button). {@code finished} gets true on success. */
    static void run(StackApplication app, Consumer<Boolean> finished) {
        Uri tree = tree(app);
        if (tree == null || app.resetRequested || !app.access.allowed() || !running.compareAndSet(false, true)) {
            if (finished != null) finished.accept(false);
            return;
        }
        SharedPreferences p = prefs(app);
        p.edit().putLong("last_attempt", System.currentTimeMillis()).apply();
        boolean started;
        try {
            started = app.archives.exportToFolder(tree, KEEP, (ok, message, name, size) -> {
                SharedPreferences.Editor edit = p.edit();
                if (ok) edit.putLong("last_ok", System.currentTimeMillis()).remove("last_error").putString("upload_name", name).putLong("upload_size", size).putString("upload_state", "checking");
                else edit.putString("last_error", message == null || message.isEmpty() ? "The backup failed." : message);
                edit.commit();
                running.set(false);
                app.notifySyncChanged();
                if (finished != null) finished.accept(ok);
                if (ok) verifyAfterWrite(app);
            });
        } catch (RuntimeException error) { started = false; }
        if (!started) { running.set(false); if (finished != null) finished.accept(false); }
    }

    // ---- checking that the provider really has the file ----

    /** A cloud provider may take the file now and upload it later; list the folder to see whether it arrived. */
    private static void verifyAfterWrite(StackApplication app) {
        new Thread(() -> {
            // The provider's own listing can lag a few seconds behind an upload, so poll for a while.
            for (int i = 0; i < 12; i++) {
                String state = verify(app);
                if (!state.equals("uploading") && !state.equals("checking")) return;
                try { Thread.sleep(5000); } catch (InterruptedException stop) { return; }
            }
        }, "Stack backup check").start();
    }

    /** Re-checks a backup whose upload was not confirmed yet; cheap when nothing is pending. */
    static void verifyPendingAsync(StackApplication app) {
        if (!enabled(app) || prefs(app).getString("upload_name", "").isEmpty()) return;
        new Thread(() -> verify(app), "Stack backup check").start();
    }

    /**
     * Looks for the last backup in the folder. Outcomes, kept in {@code upload_state}: "confirmed" (a settled row with
     * the expected size), "uploading" (still listed as a pending row or not listed yet), "failed" (the provider says
     * so, or a day passed without it arriving). A failed upload clears {@code last_ok} so the next run retries.
     */
    static synchronized String verify(StackApplication app) {
        SharedPreferences p = prefs(app);
        Uri tree = tree(app);
        String name = p.getString("upload_name", "");
        if (tree == null || name.isEmpty()) return "";
        long size = p.getLong("upload_size", 0), ok = p.getLong("last_ok", 0);
        String found = "", summary = "";
        boolean listed = false, settled = false;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor c = app.getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_SUMMARY}, null, null, null)) {
            if (c == null) return p.getString("upload_state", "");
            while (c.moveToNext()) {
                if (!name.equals(c.getString(1))) continue;
                listed = true;
                String id = c.getString(0) == null ? "" : c.getString(0);
                long rowSize = c.isNull(2) ? 0 : c.getLong(2);
                String note = c.isNull(3) ? "" : c.getString(3);
                // A cloud app lists a file it still has to upload under a temporary id.
                boolean temporary = id.contains("pending");
                if (!temporary && (rowSize == 0 || rowSize == size)) { settled = true; found = id; break; }
                if (temporary) summary = note;
            }
        } catch (RuntimeException error) {
            return p.getString("upload_state", ""); // provider unavailable now; try again later
        }
        SharedPreferences.Editor edit = p.edit();
        String state;
        if (settled) { state = "confirmed"; edit.remove("upload_name").remove("upload_size"); }
        else if (summary.toLowerCase(Locale.ROOT).startsWith("upload failed")) { state = "failed"; edit.putString("last_error", summary); }
        else if (System.currentTimeMillis() - ok > 24L * 60 * 60 * 1000) { state = "failed"; edit.putString("last_error", listed ? "The upload did not finish within a day." : "The backup file never appeared in the folder."); }
        else state = "uploading";
        edit.putString("upload_state", state);
        if (state.equals("failed")) { edit.putLong("last_ok", 0).putLong("last_attempt", System.currentTimeMillis()).remove("upload_name").remove("upload_size"); }
        edit.commit();
        app.notifySyncChanged();
        return state;
    }

    // ---- document-provider helpers used by ArchiveController ----

    /** The size a provider reports for one document, or 0 when it does not say. */
    static long reportedSize(ContentResolver resolver, Uri document) {
        try (Cursor c = resolver.query(document, new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return Math.max(0, c.getLong(0));
        } catch (RuntimeException ignored) { /* provider does not answer; treat as unknown */ }
        return 0;
    }

    /** Deletes automatic backups beyond the newest {@code keep}, never the one just written. Returns how many went. */
    static int prune(ContentResolver resolver, Uri tree, String justWritten, int keep) {
        ArrayList<String[]> mine = new ArrayList<>(); // {name, document id}
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor c = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c == null) return 0;
            while (c.moveToNext()) {
                String name = c.getString(1);
                if (name != null && name.startsWith(PREFIX) && name.endsWith(SUFFIX) && !name.equals(justWritten)) mine.add(new String[]{name, c.getString(0)});
            }
        } catch (RuntimeException error) { return 0; }
        // Names carry the timestamp, so a name sort is newest-first when reversed. The new file is one of the kept.
        Collections.sort(mine, (a, b) -> b[0].compareTo(a[0]));
        int removed = 0;
        for (int i = Math.max(0, keep - 1); i < mine.size(); i++) {
            try {
                if (DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, mine.get(i)[1]))) removed++;
            } catch (Exception ignored) { /* best effort; the provider may forbid deletes */ }
        }
        return removed;
    }
}
