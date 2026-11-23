package org.stackdocs.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Partner sharing, import side (docs/STACK_LINK_SLICE7_DESIGN.md, S7-3). Reads {@code Stack shared.stackshare} from a read-only folder grant the
 * person chose once, decrypts it with the key the partner's QR gave this phone ({@link PartnerKeys}, receive slot) and applies it with
 * {@link LinkStore#applyPartnerArchive}. It runs at app start / return to the foreground and about once a day while the app is open, never on the
 * UI thread, and skips a file whose size and modified time are unchanged since the last import. A missing file (a provider replacing it) or an
 * unreadable one is "nothing new", not an error; a file that does not decrypt is one quiet line in settings, never a dialog.
 * The key is held only for the duration of one read and wiped afterwards; nothing here logs.
 */
final class PartnerImport {
    static final long DAY = 24L * 60 * 60 * 1000, FOREGROUND_GAP = 30L * 1000;
    static final String DEFAULT_NAME = "Partner";
    static final String OLDER_PROBLEM = "The shared file is older than one already imported.";
    static final String TOO_LARGE_PROBLEM = "The shared file is too large for this phone to read.";
    static final String KEY_PROBLEM = "The shared file cannot be opened with your key. Ask your partner to pair again.";
    private static final AtomicBoolean running = new AtomicBoolean();

    private PartnerImport() {}

    // ---- the folder grant ---------------------------------------------------------------------------------------

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("partner_import", Context.MODE_PRIVATE); }

    static Uri tree(Context context) {
        String saved = prefs(context).getString("tree", "");
        return saved.isEmpty() ? null : Uri.parse(saved);
    }

    static boolean enabled(Context context) { return tree(context) != null; }

    static String name(Context context) {
        String saved = prefs(context).getString("name", "");
        return saved.isEmpty() ? DEFAULT_NAME : saved;
    }

    /** The name for the "Shared by" badge; cleaned like a title and limited in length. */
    static String cleanName(String raw) {
        String name = raw == null ? "" : raw.replaceAll("[\\p{Cc}\\u202a-\\u202e\\u2066-\\u2069\\u200e\\u200f\\u061c]", "").trim();
        if (name.length() > 40) name = name.substring(0, 40).trim();
        return name.isEmpty() ? DEFAULT_NAME : name;
    }

    static void setName(Context context, String raw) { prefs(context).edit().putString("name", cleanName(raw)).commit(); }

    /** Keeps the READ permission for the chosen folder and imports from it. Call off the UI thread. */
    static void enable(StackApplication app, Uri tree, int grantFlags, String displayName) {
        Uri previous = tree(app);
        app.getContentResolver().takePersistableUriPermission(tree, grantFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (previous != null && !previous.equals(tree)) release(app, previous);
        prefs(app).edit().putString("tree", tree.toString()).putString("tree_name", displayName == null ? "" : displayName)
                .remove("last_sig").remove("last_error").remove("key_problem").remove("older").remove("too_large").putLong("last_ok", 0).putLong("last_attempt", 0).commit();
        watch(app);
        start(app, true);
    }

    static void disable(StackApplication app) {
        Uri previous = tree(app);
        if (previous != null) release(app, previous);
        String name = prefs(app).getString("name", "");
        prefs(app).edit().clear().putString("name", name).commit();
        watch(app);
        app.refreshListeners();
    }

    private static void release(Context context, Uri tree) {
        Uri share = PartnerShare.tree(context), backup = AutoBackup.tree(context);
        if (tree.equals(share) || tree.equals(backup)) return;   // a grant another feature uses stays
        try { context.getContentResolver().releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (RuntimeException ignored) { /* already gone */ }
    }

    /** A new receive key means the old file's verdict no longer holds: the next run reads it again. */
    static void keyChanged(Context context) { prefs(context).edit().remove("last_sig").remove("key_problem").remove("older").remove("too_large").commit(); }

    // ---- status for settings --------------------------------------------------------------------------------------

    /** "" or the one quiet line about a file that does not open with this phone's key. */
    static String problem(Context context) {
        SharedPreferences p = prefs(context);
        return p.getBoolean("key_problem", false) ? KEY_PROBLEM : p.getBoolean("older", false) ? OLDER_PROBLEM : p.getBoolean("too_large", false) ? TOO_LARGE_PROBLEM : "";
    }

    static String label(Context context) {
        SharedPreferences p = prefs(context);
        if (tree(context) == null) return "Off · Choose the folder your partner shares with you";
        String folder = p.getString("tree_name", "");
        StringBuilder out = new StringBuilder("Reading from ").append(folder.isEmpty() ? "the chosen folder" : folder);
        long ok = p.getLong("last_ok", 0);
        if (ok > 0) out.append(" · Checked ").append(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(new java.util.Date(ok)));
        return out.toString();
    }

    // ---- when it runs ---------------------------------------------------------------------------------------------

    /** Return to the foreground (and app start): at most once every 30 seconds. Safe on the UI thread. */
    static void onForeground(StackApplication app) { startIfDue(app, FOREGROUND_GAP); }

    /** The daily timer: called from the foreground poll. */
    static void daily(StackApplication app) { startIfDue(app, DAY); }

    /** The partner's folder changed (a Filen Files broadcast or a content observer): same debounced run as the other triggers, no time gap. */
    static void changed(StackApplication app) { start(app, false, null); }

    /** Same, and `done` runs when the import thread ends (or at once when nothing starts), so a broadcast receiver can hold the process open with goAsync. */
    static void changed(StackApplication app, Runnable done) { start(app, false, done); }

    private static android.database.ContentObserver observer;

    /**
     * While the app process runs, watch the received folder's children for provider notifications (Filen Files calls notifyChange while it runs).
     * Call on any thread; it re-registers for the current folder, or just unregisters when none is chosen. Only {@link #changed} runs on a hit, so a
     * file whose size and modified time are unchanged is skipped as before.
     */
    static synchronized void watch(StackApplication app) {
        android.content.ContentResolver resolver = app.getContentResolver();
        if (observer != null) { resolver.unregisterContentObserver(observer); observer = null; }
        Uri tree = tree(app);
        if (tree == null) return;
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            observer = new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange) { changed(app); }
            };
            resolver.registerContentObserver(children, true, observer);
        } catch (RuntimeException unusable) { observer = null; /* a tree that cannot be watched is still read by the other triggers */ }
    }

    /** Runs an import now (a new folder, a new key). Safe on the UI thread. */
    static void importNow(StackApplication app) { start(app, true); }

    private static void startIfDue(StackApplication app, long gap) {
        if (!enabled(app)) return;
        long now = Clock.now(), last = prefs(app).getLong("last_attempt", 0);
        if (last > now || now - last >= gap) start(app, false);
    }

    private static void start(StackApplication app, boolean force) { start(app, force, null); }

    private static void start(StackApplication app, boolean force, Runnable done) {
        if (!enabled(app) || app.resetRequested || !app.access.allowed()) { if (done != null) done.run(); return; }
        if (!running.compareAndSet(false, true)) { if (done != null) done.run(); return; }
        new Thread(() -> {
            try { run(app, force); } finally { running.set(false); if (done != null) done.run(); }
        }, "Stack partner import").start();
    }

    private static void run(StackApplication app, boolean force) {
        SharedPreferences p = prefs(app);
        Uri tree = tree(app);
        if (tree == null) return;
        byte[] key = null;
        try {
            key = PartnerKeys.receiveKey(app, PartnerKeys.DEFAULT_PARTNER);
            if (key == null) return;   // not paired in this direction yet: nothing to do, nothing to say
            p.edit().putLong("last_attempt", Clock.now()).apply();
            final byte[] used = key;
            PartnerKeys.Stamp seen = PartnerKeys.seen(app, PartnerKeys.DEFAULT_PARTNER);
            Result result = importOnce(app.library.links, new SafSource(app.getContentResolver(), tree), used, name(app), Clock.now(), force ? "" : p.getString("last_sig", ""), app.io, seen);
            SharedPreferences.Editor edit = p.edit();
            switch (result.kind) {
                case IMPORTED:
                    edit.putString("last_sig", result.signature).putBoolean("key_problem", false).remove("older").remove("too_large").putLong("last_ok", Clock.now());
                    if (result.stamp != null) PartnerKeys.setSeen(app, PartnerKeys.DEFAULT_PARTNER, used, result.stamp);
                    break;
                case OLDER: edit.putString("last_sig", result.signature).putBoolean("older", true); break;
                case TOO_LARGE: edit.putString("last_sig", result.signature).putBoolean("too_large", true); break;
                case UNCHANGED: edit.putLong("last_ok", Clock.now()); break;
                case BAD_KEY: edit.putString("last_sig", result.signature).putBoolean("key_problem", true); break;
                default: break;   // MISSING / UNREADABLE: nothing new; try again at the next trigger
            }
            edit.commit();
            if (result.kind == Kind.IMPORTED && result.imported != null && result.imported.changed() > 0) app.refreshListeners();
            else if (result.kind == Kind.BAD_KEY || result.kind == Kind.OLDER || result.kind == Kind.TOO_LARGE) app.refreshListeners();
        } catch (OutOfMemoryError tooBig) {
            // Remember this file (size and modified time) as too large so the next triggers stop retrying until it changes.
            try { String stat = new SafSource(app.getContentResolver(), tree).stat(); if (stat != null && key != null) p.edit().putString("last_sig", stat + "|" + PartnerKeys.fingerprint(key)).putBoolean("too_large", true).commit(); }
            catch (Exception | OutOfMemoryError ignored) { /* the next trigger tries again */ }
        } catch (Exception ignored) {
            // A failed run is "nothing new"; the next trigger tries again. No message, no key, no path is logged.
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    // ---- one import, testable without a provider ----------------------------------------------------------------

    enum Kind { MISSING, UNCHANGED, UNREADABLE, BAD_KEY, IMPORTED, OLDER, TOO_LARGE }

    static final class Result {
        final Kind kind; final String signature; final LinkStore.Imported imported;
        /** The (epoch, counter) of an imported archive, to be remembered with the receive key; null otherwise. */
        final PartnerKeys.Stamp stamp;
        Result(Kind kind, String signature, LinkStore.Imported imported) { this(kind, signature, imported, null); }
        Result(Kind kind, String signature, LinkStore.Imported imported, PartnerKeys.Stamp stamp) { this.kind = kind; this.signature = signature; this.imported = imported; this.stamp = stamp; }
    }

    /** Where the archive file comes from. {@code stat} is null when there is no such file right now. */
    interface Source {
        /** "size|modified", or null when the file is missing or cannot be looked at. */
        String stat();
        InputStream open() throws IOException;
    }

    static final class FileSource implements Source {
        private final File file;
        FileSource(File file) { this.file = file; }
        @Override public String stat() { return file.isFile() ? file.length() + "|" + file.lastModified() : null; }
        @Override public InputStream open() throws IOException { return new FileInputStream(file); }
    }

    /**
     * One import on the calling thread, writing through `executor` when given (the app's IO executor) and directly otherwise.
     * `lastSignature` is what the caller saved after the previous run ("" = none). The returned signature includes a fingerprint of the key, so a
     * new key is read again even when the file did not change.
     */
    static Result importOnce(LinkStore links, Source source, byte[] key, String sharedBy, long now, String lastSignature, java.util.concurrent.ExecutorService executor) throws Exception {
        return importOnce(links, source, key, sharedBy, now, lastSignature, executor, null);
    }

    /** `seen` is the newest (epoch, counter) already imported with this key, or null. An archive of the same epoch with a lower counter is refused (OLDER); a new epoch is accepted. */
    static Result importOnce(LinkStore links, Source source, byte[] key, String sharedBy, long now, String lastSignature, java.util.concurrent.ExecutorService executor, PartnerKeys.Stamp seen) throws Exception {
        String stat = source.stat();
        if (stat == null) return new Result(Kind.MISSING, "", null);
        String signature = stat + "|" + PartnerKeys.fingerprint(key);
        if (signature.equals(lastSignature)) return new Result(Kind.UNCHANGED, signature, null);
        ShareArchive.Contents contents;
        try (InputStream in = source.open()) {
            contents = ShareArchive.read(in, key);
        } catch (BackupEnvelope.NoKey wrong) {
            return new Result(Kind.BAD_KEY, signature, null);
        } catch (ShareArchive.LimitExceeded tooLarge) {
            // The same file (size and modified time) is not read again until it changes.
            return new Result(Kind.TOO_LARGE, signature, null);
        } catch (IOException | RuntimeException unreadable) {
            // Half-written, replaced under us, damaged, or not a share file: nothing new this time.
            return new Result(Kind.UNREADABLE, "", null);
        }
        if (seen != null && seen.epoch.equals(contents.epoch) && contents.counter < seen.counter) return new Result(Kind.OLDER, signature, null);
        LinkStore.Imported applied;
        try {
            applied = executor == null ? links.applyPartnerArchive(contents, sharedBy, now)
                : executor.submit(() -> links.applyPartnerArchive(contents, sharedBy, now)).get();
        } catch (OutOfMemoryError tooBig) {
            return new Result(Kind.TOO_LARGE, signature, null);
        } catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof OutOfMemoryError) return new Result(Kind.TOO_LARGE, signature, null);
            throw failed;
        }
        return new Result(Kind.IMPORTED, signature, applied, new PartnerKeys.Stamp(contents.epoch, contents.counter));
    }

    // ---- reading from the granted folder ------------------------------------------------------------------------------

    static final class SafSource implements Source {
        private final ContentResolver resolver; private final Uri tree;
        private Uri found;
        SafSource(ContentResolver resolver, Uri tree) { this.resolver = resolver; this.tree = tree; }
        @Override public String stat() {
            found = null;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor c = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                if (c != null) while (c.moveToNext()) {
                    if (!ShareArchive.FILE_NAME.equals(c.getString(1))) continue;
                    found = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                    long size = c.isNull(2) ? -1 : c.getLong(2), modified = c.isNull(3) ? -1 : c.getLong(3);
                    // A provider that reports neither cannot tell us the file changed, so it is read every time (applying the same generation is a no-op).
                    return size < 0 && modified < 0 ? "unknown|" + System.nanoTime() : size + "|" + modified;
                }
            } catch (RuntimeException unreadable) { /* a provider that cannot be listed has nothing new */ }
            return null;
        }
        @Override public InputStream open() throws IOException {
            if (found == null) throw new IOException("The shared file is not there.");
            InputStream in;
            try { in = resolver.openInputStream(found); } catch (RuntimeException | java.io.FileNotFoundException error) { throw new IOException("The shared file could not be opened."); }
            if (in == null) throw new IOException("The shared file could not be opened.");
            return in;
        }
    }
}
