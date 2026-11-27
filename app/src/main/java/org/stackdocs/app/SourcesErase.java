package org.stackdocs.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * What "Erase library" does for folder sources, in two steps so nothing irreversible happens before the library has really been moved aside
 * (LibraryReset): {@link #snapshot} reads, before the move, which folders and which file names Stack itself recorded (scan_share_files and the upload
 * journal, with the temporary names of saves in flight; never anything chosen by a name pattern) and keeps them in preferences; {@link #apply},
 * after the move succeeded, removes those names from the shared Filen folder (or, when Filen cannot be reached, remembers them with the folder's
 * grant, so they are removed later although the library is gone), gives back every other persisted grant for a source, and deletes the caches of
 * folder files. {@link #discard} drops the snapshot when the reset is rolled back. Never throws: a reset must not fail because Filen is away.
 */
final class SourcesErase {
    private static final long WAIT_MS = 20_000;
    private static final String PREFS = "sources_erase", KEY_TREES = "trees", KEY_SCAN = "scan_trees", KEY_NAMES = "names";

    private SourcesErase() {}

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Both steps at once, for a check: `library` is the database to read the sources from; `folders` opens a scan-share tree. */
    static void run(Context context, File db, java.util.function.Function<Uri, ScanShare.Folder> folders) {
        snapshot(context, db);
        apply(context, folders);
    }

    static void apply(Context context) { apply(context, tree -> new ScanShare.SafFolder(context.getContentResolver(), tree)); }

    /** Reads the sources and the recorded file names from the library file (read only) and keeps them. Nothing is deleted or released. */
    static void snapshot(Context context, File db) {
        Set<String> trees = new LinkedHashSet<>(), scan = new LinkedHashSet<>(), names = new LinkedHashSet<>();
        if (db.isFile()) {
            try (SQLiteDatabase old = SQLiteDatabase.openDatabase(db.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                try (Cursor c = old.rawQuery("SELECT tree_uri,kind FROM sources", null)) {
                    while (c.moveToNext()) { trees.add(c.getString(0)); if (SourceStore.SCAN_SHARE.equals(c.getString(1))) scan.add(c.getString(0)); }
                }
                try (Cursor c = old.rawQuery("SELECT file_name FROM scan_share_files", null)) { while (c.moveToNext()) names.add(c.getString(0)); }
                try (Cursor c = old.rawQuery("SELECT file_name FROM scan_share_journal", null)) { while (c.moveToNext()) { names.add(c.getString(0)); names.add(c.getString(0) + ".part"); } }
            } catch (RuntimeException unreadable) { /* an older library without some of these tables: what was read stays */ }
        }
        prefs(context).edit().putStringSet(KEY_TREES, trees).putStringSet(KEY_SCAN, scan).putStringSet(KEY_NAMES, names).commit();
    }

    /** The reset was rolled back: the library is still there, nothing is to be removed. */
    static void discard(Context context) { prefs(context).edit().clear().commit(); }

    static boolean hasSnapshot(Context context) { return prefs(context).contains(KEY_TREES); }

    /** Removes the snapshot's copies and caches. Call only after the library was moved away. */
    static void apply(Context context, java.util.function.Function<Uri, ScanShare.Folder> folders) {
        SharedPreferences saved = prefs(context);
        Set<String> trees = new LinkedHashSet<>(saved.getStringSet(KEY_TREES, new LinkedHashSet<String>()));
        Set<String> scan = new LinkedHashSet<>(saved.getStringSet(KEY_SCAN, new LinkedHashSet<String>()));
        Set<String> names = new LinkedHashSet<>(saved.getStringSet(KEY_NAMES, new LinkedHashSet<String>()));
        for (String tree : scan) removeCopies(context, Uri.parse(tree), names, folders);   // a folder that could not be cleaned is remembered, with its grant
        List<Uri> release = new ArrayList<>();
        for (String tree : trees) release.add(Uri.parse(tree));
        // The one central rule: the backup folder and a folder with copies still to remove keep their grants; the sources are gone with the library.
        for (Uri tree : release) SourceStore.releaseUnlessUsed(context, tree, used -> false);
        for (String name : new String[]{"scan-share", "source-read", "source-view"}) deleteTree(new File(context.getCacheDir(), name));
        discard(context);
    }

    /** True when the scan-share folder holds none of the recorded names any more; otherwise they are remembered (with the grant). */
    private static boolean removeCopies(Context context, Uri tree, Set<String> rowNames, java.util.function.Function<Uri, ScanShare.Folder> folders) {
        if (rowNames.isEmpty()) return true;
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "Stack erase shared copies"));
        Set<String> left = new LinkedHashSet<>(rowNames);
        try {
            Future<Set<String>> result = pool.submit((Callable<Set<String>>) () -> {
                Set<String> stillThere = new LinkedHashSet<>(rowNames);
                try {
                    ScanShare.Folder folder = folders.apply(tree);
                    for (String name : rowNames) { folder.delete(name); stillThere.remove(name); }
                } catch (IOException | RuntimeException unreachable) { /* the rest is remembered */ }
                return stillThere;
            });
            try { left = result.get(WAIT_MS, TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.TimeoutException late) { result.cancel(true); }
            catch (java.util.concurrent.ExecutionException failed) { /* all remembered */ }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        } finally { pool.shutdownNow(); }
        if (left.isEmpty()) { context.getSharedPreferences("scan_share_old", Context.MODE_PRIVATE).edit().remove(tree.toString()).commit(); return true; }   // done now: an earlier "remembered" duty for it is done too
        ScanShare.rememberOld(context, tree.toString(), left);
        return false;
    }

    private static void deleteTree(File file) {
        File[] kids = file.listFiles();
        if (kids != null) for (File kid : kids) deleteTree(kid);
        file.delete();
    }
}
