package org.stackdocs.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.sqlite.SQLiteDatabase;
import android.os.BatteryManager;
import android.os.CancellationSignal;
import android.os.SystemClock;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.stackdocs.app.types.Extracted;
import org.stackdocs.app.types.FileType;
import org.stackdocs.app.types.FileTypes;
import org.stackdocs.app.types.Limits;

/**
 * Reads the folder sources (docs/FOLDER_SHARING_DESIGN.md sections 3 and 5). One scan walks a source top-down, records what each FRESH listing says,
 * applies the removal rules of docs/FOLDER_SHARING_INTERFACES.md I6, then reads the new or changed files one at a time through {@link FileTypes}.
 *
 * Removal (I6), all in {@link #scan}:
 *  - A listing counts only when {@link SourceReader.Status#FRESH}: FRESH=true and an account id. Errors, null cursors, stale answers, a missing
 *    grant and another account id change no row except the source's own state.
 *  - A file is omitted when a FRESH listing of its parent (or of an omitted ancestor's parent) did not list it and no FRESH listing in the same scan
 *    listed it anywhere else (a move keeps the id). Omitted rows get `missing_since` and are hidden from search.
 *  - An omitted row is removed only in a scan whose every folder was listed FRESH, and only when opening its id throws a trustworthy
 *    FileNotFoundException ({@link SourceReader#gone}), or an earlier scan already omitted it at least {@link #CONFIRM_MS} before.
 *  - A new file with the same parent and name as an omitted one (save-over gives a new id) gets the omitted file's stacks and tags.
 *
 * Pauses: app lock locked, low battery, another Filen account (state `account_changed`), signed out, grant lost. A scan stopped part way keeps its
 * rows; the files still `pending` are read by the next run.
 */
final class SourceIndexer {
    /** A second FRESH scan must be at least this long after the first one that omitted a file (I6). */
    static final long CONFIRM_MS = 10 * 60_000L;
    /** The foreground and app-start triggers ask at most this often. */
    static final long MIN_GAP_MS = 2 * 60_000L;
    private static final int GONE_PER_SCAN = 200;
    /** A file is tried this many times (counted before each try, so a file that kills the reader is not tried for ever); then it is unreadable. */
    static final int MAX_ATTEMPTS = 3;
    static final String GAVE_UP = "Could not be read: Stack stopped reading this file because it took too long or failed.";
    static final String TOO_BIG = "Too large to read";
    /** Larger files whose name and MIME no handler claims are not downloaded just to look at them. */
    private static final long SNIFF_MAX = 32L * 1024 * 1024;
    private static final int LOW_BATTERY_PERCENT = 15;

    /** What the app currently allows; a check can replace it. */
    interface Env {
        boolean allowed();
        boolean batteryOk();
    }

    /** Stops a run from outside (the app lock locked, the job was stopped). */
    static final class Control {
        private volatile boolean stopped;
        private volatile String why = "";
        private volatile CancellationSignal current;
        void stop(String reason) {
            why = reason == null ? "cancelled" : reason; stopped = true;
            CancellationSignal signal = current;
            if (signal != null) signal.cancel();
        }
        boolean stopped() { return stopped; }
        String why() { return why; }
    }

    /** What one scan did, for checks and logs (no names, no ids). */
    static final class Result {
        boolean complete;       // every folder was listed FRESH
        String stopped = "";    // locked, battery, account, signed_out, access, cancelled or ""
        int listed, added, updated, omitted, removed, read;
    }

    private final Context context;
    private final SourceStore store;
    private final Function<SourceStore.Source, SourceReader> readers;
    private final Env env;
    private final Extractor extractor;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "Stack sources"));
    private final AtomicBoolean active = new AtomicBoolean();
    private volatile boolean again;
    private volatile Control control = new Control();
    private long lastKick = -MIN_GAP_MS;

    SourceIndexer(Context context, SourceStore store, Function<SourceStore.Source, SourceReader> readers, Env env) {
        this(context, store, readers, env, new ExtractClient(context));
    }

    /** `extractor` reads the text of a downloaded file in the extract process ({@link ExtractService}); a check can replace it. */
    SourceIndexer(Context context, SourceStore store, Function<SourceStore.Source, SourceReader> readers, Env env, Extractor extractor) {
        this.context = context.getApplicationContext(); this.store = store; this.readers = readers; this.env = env; this.extractor = extractor;
    }

    /** The reader the app uses: Filen Files through Android's document API with the grant Stack holds. */
    static Function<SourceStore.Source, SourceReader> safReaders(Context context, SourceStore store) {
        return source -> new SourceReader.Saf(context, store, source.tree());
    }

    // ---- triggers ----------------------------------------------------------------------------------------------------------------

    /** App start, app foreground and the periodic job: rescan every source, at most once per {@link #MIN_GAP_MS} unless `force`. */
    synchronized void kickAll(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastKick < MIN_GAP_MS) return;
        lastKick = now;
        run(null, null);
    }

    /** The change notice (SHARED_CHANGED) named this source's tree. */
    void kickSource(String sourceId) { run(sourceId, null); }

    /** Runs the scans and calls `done` when no run is left (the job uses it). */
    void run(String onlySource, Runnable done) {
        if (!active.compareAndSet(false, true)) { again = true; if (done != null) executor.execute(done); return; }
        Control mine = new Control();
        control = mine;
        executor.execute(() -> {
            try {
                do {
                    again = false;
                    try { scanAll(onlySource, mine); } catch (RuntimeException unexpected) { /* the next run starts again from the database */ }
                } while (again && !mine.stopped());
            } finally { active.set(false); if (done != null) done.run(); }
        });
    }

    boolean busy() { return active.get(); }

    // ---- change notice for the screens (main thread, no polling) -------------------------------------------------------------------

    private final Set<Runnable> listeners = Collections.synchronizedSet(new java.util.LinkedHashSet<>());
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final AtomicBoolean notifyPending = new AtomicBoolean();
    private static final long NOTIFY_MS = 2000;

    /** `listener` runs on the main thread after files or sources change (added, removed, read, state), at most once every 2 seconds. The caller of {@link #changed} only sets a flag and posts: it never waits for the screen. */
    void addListener(Runnable listener) { listeners.add(listener); }
    void removeListener(Runnable listener) { listeners.remove(listener); }

    void changed() {
        if (listeners.isEmpty() || !notifyPending.compareAndSet(false, true)) return;
        main.postDelayed(() -> {
            notifyPending.set(false);
            List<Runnable> copy;
            synchronized (listeners) { copy = new ArrayList<>(listeners); }
            for (Runnable listener : copy) listener.run();
        }, NOTIFY_MS);
    }

    private void setState(String sourceId, String state, String reason) { store.setState(sourceId, state, reason); changed(); }

    /** The app lock locked or the job was stopped: the running scan stops at the next safe point; nothing is removed. */
    void cancel(String reason) { control.stop(reason); }

    /** Synchronous, for checks and the job: every source (or one), in order. */
    void scanAll(String onlySource, Control control) {
        store.recoverInterrupted();
        List<SourceStore.Source> sources = new ArrayList<>(store.list());
        sources.sort((a, b) -> Boolean.compare(!SourceStore.OWN.equals(a.kind), !SourceStore.OWN.equals(b.kind)));   // own folders first: "Shared by me" skips what they cover
        for (SourceStore.Source source : sources) {
            if (onlySource != null && !onlySource.equals(source.id)) continue;
            if (SourceStore.SCAN_SHARE.equals(source.kind)) continue;   // the folder Stack writes scans into: written by ScanShare, never read back into search
            if (control.stopped()) break;
            scan(source, control);
        }
    }

    // ---- one scan ----------------------------------------------------------------------------------------------------------------

    Result scan(SourceStore.Source src, Control control) {
        Result result = new Result();
        if (!env.allowed()) { pause(src, result, "locked"); return result; }
        if (!env.batteryOk()) { pause(src, result, "battery"); return result; }
        SourceReader reader = readers.apply(src);
        if (!reader.granted()) {
            result.stopped = "access";
            setState(src.id, SourceStore.ACCESS_LOST, "Stack no longer has access to this folder. Choose it again.");
            return result;
        }
        long seq = store.nextScan(src.id);
        setState(src.id, SourceStore.SCANNING, "");
        String account = src.accountId;
        String root = src.rootDocId();
        Map<String, SourceStore.FileRow> rows = store.rows(src.id);
        Set<String> seen = new HashSet<>();
        Set<String> freshParents = new HashSet<>();
        Map<String, String> created = new HashMap<>();   // parent doc id + NUL + name -> new row id (this scan)
        boolean complete = true;
        boolean rootFresh = false;
        String problem = "";
        Deque<String[]> folders = new ArrayDeque<>();    // {doc id, path}
        folders.add(new String[]{root, ""});
        SQLiteDatabase db = store.db();
        while (!folders.isEmpty()) {
            if (stopNow(control, result)) { complete = false; break; }
            String[] folder = folders.pop();
            SourceReader.Listing listing = reader.list(folder[0]);
            if (listing.status == SourceReader.Status.SIGNED_OUT) {
                result.stopped = "signed_out";
                setState(src.id, SourceStore.SIGNED_OUT, "Filen is signed out or locked.");
                return result;
            }
            if (listing.status == SourceReader.Status.ERROR || listing.status == SourceReader.Status.STALE) {
                complete = false;
                if (folder[0].equals(root)) problem = listing.status == SourceReader.Status.STALE ? "Filen answered from its saved copy. Waiting for a connection." : "Can't reach Filen right now.";
                continue;   // nothing from this answer is used
            }
            if (account.isEmpty()) { account = listing.account; store.setAccount(src.id, account); }
            else if (!account.equals(listing.account) && !listing.legacyAccount.isEmpty() && account.equals(listing.legacyAccount)) {
                account = listing.account; store.setAccount(src.id, account);   // the same account under Filen's new id: adopted once, without pausing
            }
            else if (!account.equals(listing.account)) {
                result.stopped = "account";
                setState(src.id, SourceStore.ACCOUNT_CHANGED, "Filen is signed in as someone else.");
                return result;
            }
            if (folder[0].equals(root)) rootFresh = true;
            freshParents.add(folder[0]);
            result.listed++;
            db.beginTransaction();
            try {
                for (SourceReader.Entry entry : listing.entries) {
                    if (!seen.add(entry.docId)) continue;   // the same id listed twice: the first answer stands
                    String path = folder[1].isEmpty() ? entry.name : folder[1] + "/" + entry.name;
                    SourceStore.FileRow old = rows.get(entry.docId);
                    if (old != null && old.dir != entry.dir) { store.removeFile(old); old = null; }
                    if (old == null) { created.put(folder[0] + "\0" + entry.name, insert(src.id, folder[0], path, entry, seq)); result.added++; }
                    else if (update(old, folder[0], path, entry, seq)) result.updated++;
                    if (entry.dir) folders.add(new String[]{entry.docId, path});
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            changed();
        }
        if (!rootFresh) complete = false;
        if (result.stopped.isEmpty()) result.complete = complete;

        omissions(src, reader, rows, seen, freshParents, created, seq, complete && result.stopped.isEmpty(), result);

        if (result.stopped.isEmpty()) readPending(src, reader, control, result);

        if (!result.stopped.isEmpty()) { pause(src, result, result.stopped); return result; }
        if (!rootFresh) setState(src.id, SourceStore.UNREACHABLE, problem.isEmpty() ? "Can't reach Filen right now." : problem);
        else {
            setState(src.id, SourceStore.OK, complete ? "" : "Some folders could not be read.");
            if (complete) store.setFreshScan(src.id, Clock.now());
        }
        return result;
    }

    private boolean stopNow(Control control, Result result) {
        if (control.stopped()) { result.stopped = control.why().isEmpty() ? "cancelled" : control.why(); return true; }
        if (!env.allowed()) { result.stopped = "locked"; return true; }
        if (!env.batteryOk()) { result.stopped = "battery"; return true; }
        return false;
    }

    private void pause(SourceStore.Source src, Result result, String why) {
        result.stopped = why;
        String words = "reader".equals(why) ? "Can't start the reader. Reading will try again." : "locked".equals(why) ? "Locked. Reading continues when you unlock Stack." : "battery".equals(why) ? "Waiting for the battery to charge." : "";
        if ("account".equals(why) || "signed_out".equals(why) || "access".equals(why)) return;   // those states were already set
        setState(src.id, SourceStore.WAITING, words);
    }

    private String insert(String sourceId, String parent, String path, SourceReader.Entry e, long seq) {
        String id = UUID.randomUUID().toString();
        ContentValues v = new ContentValues();
        v.put("id", id); v.put("source_id", sourceId); v.put("provider_doc_id", e.docId); v.put("parent_doc_id", parent); v.put("path", path); v.put("name", e.name);
        v.put("mime", e.mime); v.put("size", e.size); v.put("modified", e.modified); v.put("version", signature(e)); v.put("is_dir", e.dir ? 1 : 0);
        v.put("state", e.dir ? SourceStore.INDEXED : SourceStore.PENDING); v.put("seen_scan", seq); v.put("first_scan", seq); v.put("added_at", Clock.now());
        store.db().insertOrThrow("source_files", null, v);
        if (!e.dir) { store.indexName(id, e.name, ""); store.claimCarry(sourceId, parent, e.name, id); }   // found by name right away; takes marks kept by a lagging save-over
        return id;
    }

    /** Updates a row the scan saw again. True when anything about it changed. */
    private boolean update(SourceStore.FileRow old, String parent, String path, SourceReader.Entry e, long seq) {
        ContentValues v = new ContentValues();
        v.put("seen_scan", seq); v.put("missing_since", 0);
        boolean changed = !parent.equals(old.parentDocId) || !path.equals(old.path) || !e.name.equals(old.name) || old.missingSince != 0;
        v.put("parent_doc_id", parent); v.put("path", path); v.put("name", e.name); v.put("mime", e.mime); v.put("size", e.size); v.put("modified", e.modified);
        if (!e.dir) {
            String signature = signature(e);
            String fingerprint = FileTypes.fingerprint();
            boolean content = !signature.equals(old.version);
            boolean reread = content
                    || ((SourceStore.UNSUPPORTED.equals(old.state) || SourceStore.UNREADABLE.equals(old.state)) && !fingerprint.equals(old.fingerprint))
                    || (!old.handlerId.isEmpty() && handlerChanged(old))
                    || (SourceStore.UNSUPPORTED.equals(old.state) && !e.name.equals(old.name));
            v.put("version", signature);
            if (reread && !SourceStore.PENDING.equals(old.state)) { v.put("state", SourceStore.PENDING); v.put("reason", ""); changed = true; }
            if (content) { v.put("attempts", 0); store.clearPages(old.id); }   // pages read of the old content are of no use
            if (!e.name.equals(old.name)) store.db().execSQL("UPDATE source_search SET title=? WHERE file_id=?", new Object[]{e.name, old.id});
            changed |= content;
        }
        store.db().update("source_files", v, "id=?", new String[]{old.id});
        return changed;
    }

    private static boolean handlerChanged(SourceStore.FileRow row) {
        FileType type = FileTypes.byId(row.handlerId);
        return type == null || type.version() != row.handlerVersion;
    }

    /** The listing's version of the row, with the size and the SHA-256 of the bytes actually fetched. */
    static String contentSignature(SourceStore.FileRow row, File fetched) {
        try (java.io.InputStream in = new java.io.FileInputStream(fetched)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return row.version + "|" + fetched.length() + "|" + hex;
        } catch (java.io.IOException | java.security.GeneralSecurityException unreadable) { return row.version + "|" + fetched.length(); }
    }

    private static String signature(SourceReader.Entry e) { return e.version.isEmpty() ? e.size + "|" + e.modified : e.version; }

    // ---- omissions and removal (I6) ----------------------------------------------------------------------------------------------

    private void omissions(SourceStore.Source src, SourceReader reader, Map<String, SourceStore.FileRow> rows, Set<String> seen, Set<String> freshParents,
                           Map<String, String> created, long seq, boolean canRemove, Result result) {
        Set<String> omitted = new HashSet<>();
        boolean grew = true;
        while (grew) {   // direct omissions, then everything under an omitted folder
            grew = false;
            for (SourceStore.FileRow row : rows.values()) {
                if (seen.contains(row.docId) || omitted.contains(row.docId)) continue;
                if (freshParents.contains(row.parentDocId) || omitted.contains(row.parentDocId)) { omitted.add(row.docId); grew = true; }
            }
        }
        if (omitted.isEmpty()) return;
        long now = Clock.now();
        int goneChecks = 0;
        List<SourceStore.FileRow> toRemove = new ArrayList<>();
        SQLiteDatabase db = store.db();
        db.beginTransaction();
        try {
            for (String docId : omitted) {
                SourceStore.FileRow row = rows.get(docId);
                result.omitted++;
                String replacement = created.get(row.parentDocId + "\0" + row.name);
                if (replacement != null && !row.dir && freshParents.contains(row.parentDocId)) store.carryOver(row.id, replacement);   // save-over: a new id, same place
                if (row.missingSince == 0) db.execSQL("UPDATE source_files SET missing_since=? WHERE id=?", new Object[]{now, row.id});
                if (!canRemove) continue;
                boolean earlier = row.missingSince > 0 && now - row.missingSince >= CONFIRM_MS;   // a FRESH scan omitted it at least 10 minutes ago
                if (earlier) toRemove.add(row);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        if (canRemove) {
            for (String docId : omitted) {
                SourceStore.FileRow row = rows.get(docId);
                if (row.dir || toRemove.contains(row) || goneChecks >= GONE_PER_SCAN) continue;
                goneChecks++;
                if (reader.gone(row.docId)) toRemove.add(row);   // Filen itself says it is gone
            }
            db.beginTransaction();
            try {
                for (SourceStore.FileRow row : toRemove) { store.removeFile(row); result.removed++; }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            if (!toRemove.isEmpty()) changed();
        }
    }

    // ---- reading ---------------------------------------------------------------------------------------------------------------

    private void readPending(SourceStore.Source src, SourceReader reader, Control control, Result result) {
        List<String> ids = new ArrayList<>();
        if (SourceStore.SHARED_BY_ME.equals(src.kind)) {
            // A file one of the person's own folders already covers is not downloaded or read twice; the search shows the own folder's row.
            if (store.releaseUncovered(src.id) > 0) changed();
            for (SourceStore.FileRow row : store.coveredPending(src.id)) finish(row, SourceStore.INDEXED, "", 0, SourceStore.COVERED, "");
        }
        try (android.database.Cursor c = store.db().rawQuery("SELECT id FROM source_files WHERE source_id=? AND is_dir=0 AND state=? AND missing_since=0 ORDER BY size, id",
                new String[]{src.id, SourceStore.PENDING})) { while (c.moveToNext()) ids.add(c.getString(0)); }
        File dir = new File(context.getCacheDir(), "source-read");
        if (!dir.isDirectory()) dir.mkdirs();
        try {
            if (!ids.isEmpty() && !extractor.prepare()) { result.stopped = "reader"; return; }   // learn the readers' real versions before any file is finished
            for (String id : ids) {
                if (stopNow(control, result)) return;
                if (readOne(reader, id, dir, control)) result.read++;
                extractor.release();   // every file gets a freshly started reader process: unbound here, bound again for the next file
                if (readerDown) { readerDown = false; result.stopped = "reader"; return; }
            }
        } finally { extractor.release(); }
    }

    private static long maxBytesOfAnyType() {
        long max = 0;
        for (FileType type : FileTypes.all()) max = Math.max(max, type.limits().maxBytes);
        return max;
    }

    /**
     * Reads one file. Any failure is the file's own: it is recorded and the run goes on. True when the file got a final answer.
     * The text is read in the extract process ({@link ExtractClient}); this process only downloads the file, hands it over and stores the answer.
     * The attempt is counted and committed BEFORE the file is handed over, so a file that kills the reader (even this process) is unreadable after
     * {@link #MAX_ATTEMPTS} tries instead of crashing every run.
     */
    private boolean readOne(SourceReader reader, String id, File dir, Control control) {
        SourceStore.FileRow row = store.file(id);
        if (row == null || !SourceStore.PENDING.equals(row.state)) return false;
        if (row.attempts >= MAX_ATTEMPTS) return finish(row, SourceStore.UNREADABLE, "", 0, GAVE_UP, "");
        File tmp = new File(dir, row.id);
        CancellationSignal signal = new CancellationSignal();
        control.current = signal;
        try {
            long cap = maxBytesOfAnyType();
            if (row.size > cap) return finish(row, SourceStore.UNREADABLE, "", 0, TOO_BIG, "");
            FileType byHint = FileTypes.byMime(row.mime);
            if (byHint != null && row.size > byHint.limits().maxBytes) return finish(row, SourceStore.UNREADABLE, byHint.id(), byHint.version(), TOO_BIG, "");
            if (row.size > SNIFF_MAX && FileTypes.match(new byte[0], row.name, row.mime) == null) return finish(row, SourceStore.UNSUPPORTED, "", 0, "Not a supported type of file.", "");
            reader.fetch(row.docId, tmp, cap, signal);
            FileType type = FileTypes.match(org.stackdocs.app.types.Source.of(tmp).head(4096), row.name, row.mime);
            if (type == null) return finish(row, SourceStore.UNSUPPORTED, "", 0, "Not a supported type of file.", "");
            Limits limits = type.limits();
            if (tmp.length() > limits.maxBytes) return finish(row, SourceStore.UNREADABLE, type.id(), type.version(), TOO_BIG, "");
            if (control.stopped()) return false;
            if ("pdf".equals(type.id())) return readPdf(row, type, tmp, control, signal);
            int attempt = row.attempts + 1;
            store.db().execSQL("UPDATE source_files SET attempts=? WHERE id=?", new Object[]{attempt, row.id});   // committed before the hand-over
            ExtractClient.Outcome outcome = extractor.extract(tmp, type.id(), limits, signal);
            if (outcome.kind == ExtractClient.Outcome.Kind.CANCELLED || outcome.kind == ExtractClient.Outcome.Kind.UNAVAILABLE) {
                store.db().execSQL("UPDATE source_files SET attempts=? WHERE id=?", new Object[]{row.attempts, row.id});   // stopped from outside, or the reader would not start: not the file's doing
                if (outcome.kind == ExtractClient.Outcome.Kind.UNAVAILABLE) readerDown = true;
                return false;
            }
            if (outcome.kind != ExtractClient.Outcome.Kind.DONE) {   // timed out, or the extract process died: the process was killed, go on with the next file
                if (attempt >= MAX_ATTEMPTS) return finish(row, SourceStore.UNREADABLE, type.id(), type.version(), GAVE_UP, "");
                return false;
            }
            return answer(row, type, outcome.extracted);
        } catch (FileNotFoundException gone) {
            failed(row);   // maybe gone; only the omission rules may remove it
            return false;
        } catch (IOException | RuntimeException | OutOfMemoryError failure) {
            if (!control.stopped()) failed(row);
            return false;
        } finally {
            control.current = null;
            tmp.delete();
        }
    }

    /** The final answer of a handler for the file (not a PDF in chunks). */
    private boolean answer(SourceStore.FileRow row, FileType type, Extracted out) {
        switch (out.status) {
            case TEXT: {
                StringBuilder text = new StringBuilder();
                if (out.title != null && !out.title.isEmpty()) text.append(out.title).append('\n');
                for (String page : out.pages) { if (text.length() > 0) text.append('\n'); text.append(page); }
                return finish(row, SourceStore.INDEXED, type.id(), type.version(), out.reason == null ? "" : out.reason, text.toString());
            }
            case PROTECTED: return finish(row, SourceStore.PROTECTED, type.id(), type.version(), nz(out.reason), "");
            case UNSUPPORTED: return finish(row, SourceStore.UNSUPPORTED, type.id(), type.version(), nz(out.reason), "");
            case TOO_LARGE: return finish(row, SourceStore.UNREADABLE, type.id(), type.version(), nz(out.reason).isEmpty() ? TOO_BIG : nz(out.reason), "");
            default: return finish(row, SourceStore.UNREADABLE, type.id(), type.version(), nz(out.reason), "");
        }
    }

    // ---- PDFs in chunks ------------------------------------------------------------------------------------------------------------

    /** Hard limit of one chunk: this long for each page of it, plus a fixed part (OCR of a dense page takes about 6 s on the test phone). */
    static final long CHUNK_MS_PER_PAGE = 20_000, CHUNK_MS_BASE = 5_000;
    /** Total extract time for one content version of a file; after it the file is finished as partly read and not tried again until the content changes. */
    static final long TOTAL_BUDGET_MS = 30L * 60 * 1000;
    /** Set when the extract process could not be started: the run stops, nothing was counted against any file. */
    private volatile boolean readerDown;
    static final String SOME_PAGES = "Some pages could not be read.";
    static final String FIRST_PART = "Only the first part of this file was read.";

    /**
     * Reads a PDF in page chunks (docs/FOLDER_SHARING_DESIGN.md section 7). Step 1 asks the extract process for the text layer of every page (fast, own
     * limit, counted as a try of the file) and records one row per page. Step 2 asks it to OCR the pages without text, {@link SourceStore#CHUNK_PAGES}
     * at a time, each call with its own hard limit. Every page is stored when its chunk answers, so a stopped or killed run goes on after the last
     * finished chunk. A page is part of at most {@link SourceStore#PAGE_TRIES} counted chunks (counted before the hand-over); a page that fails them
     * is left out ("not read") and, from its first failure on, is tried alone so a bad page costs only itself. The file stays indexed with the other pages.
     * Whole-file time is not limited; each call is.
     */
    private boolean readPdf(SourceStore.FileRow row, FileType type, File tmp, Control control, CancellationSignal signal) {
        Limits limits = type.limits();
        String content = contentSignature(row, tmp);
        // A plan made for other content (the file changed between the listing and this fetch, or between two runs) is of no use: start over.
        if (store.pageTotal(row.id) != 0 && !content.equals(store.planSignature(row.id))) store.clearPages(row.id);
        if (store.pageTotal(row.id) == 0) {
            int attempt = row.attempts + 1;
            store.db().execSQL("UPDATE source_files SET attempts=? WHERE id=?", new Object[]{attempt, row.id});   // committed before the hand-over
            long started = android.os.SystemClock.elapsedRealtime();
            ExtractClient.Outcome plan = extractor.extractPages(tmp, type.id(), ExtractService.MODE_LAYERS, null, limits, signal);
            if (plan.kind != ExtractClient.Outcome.Kind.CANCELLED && plan.kind != ExtractClient.Outcome.Kind.UNAVAILABLE) store.addBudget(row.id, android.os.SystemClock.elapsedRealtime() - started);
            if (plan.kind == ExtractClient.Outcome.Kind.CANCELLED || plan.kind == ExtractClient.Outcome.Kind.UNAVAILABLE) {
                store.db().execSQL("UPDATE source_files SET attempts=? WHERE id=?", new Object[]{row.attempts, row.id});
                if (plan.kind == ExtractClient.Outcome.Kind.UNAVAILABLE) readerDown = true;
                return false;
            }
            if (plan.kind != ExtractClient.Outcome.Kind.DONE) {
                if (attempt >= MAX_ATTEMPTS) return finish(row, SourceStore.UNREADABLE, type.id(), type.version(), GAVE_UP, "");
                return false;
            }
            if (plan.extracted.status != Extracted.Status.TEXT) return answer(row, type, plan.extracted);
            store.planPages(row.id, plan.extracted.pages, content);
            store.db().execSQL("UPDATE source_files SET attempts=0 WHERE id=?", new Object[]{row.id});
            changed();
        }
        boolean capped = false;
        while (true) {
            if (control.stopped() || !env.allowed() || !env.batteryOk()) return false;   // the next run goes on with the next page
            if (store.pageChars(row.id) >= Math.max(1, limits.maxChars)) { store.capPages(row.id); capped = true; }
            if (store.budgetUsed(row.id) >= TOTAL_BUDGET_MS) { store.skipRemaining(row.id); break; }   // 30 minutes of reading spent on this content: the rest is given up
            int[] chunk = store.nextChunk(row.id);
            if (chunk.length == 0) break;
            store.addTries(row.id, chunk, 1);   // counted and committed before the hand-over: a chunk that kills the reader is not tried for ever
            Limits own = new Limits(limits.maxBytes, limits.maxPages, CHUNK_MS_PER_PAGE * chunk.length + CHUNK_MS_BASE, limits.maxChars);
            long chunkStart = android.os.SystemClock.elapsedRealtime();
            ExtractClient.Outcome out = extractor.extractPages(tmp, type.id(), ExtractService.MODE_OCR, chunk, own, signal);
            if (out.kind == ExtractClient.Outcome.Kind.CANCELLED || out.kind == ExtractClient.Outcome.Kind.UNAVAILABLE) {
                store.addTries(row.id, chunk, -1);   // not the pages' doing
                if (out.kind == ExtractClient.Outcome.Kind.UNAVAILABLE) readerDown = true;
                return false;
            }
            store.addBudget(row.id, android.os.SystemClock.elapsedRealtime() - chunkStart);   // stopped from outside: not the pages' doing
            if (out.kind == ExtractClient.Outcome.Kind.DONE && out.extracted.status == Extracted.Status.TEXT && out.extracted.pages.size() == chunk.length)
                store.savePages(row.id, chunk, out.extracted.pages);
            changed();   // a failed chunk is tried again (alone); the page's own count ends it
        }
        int skipped = store.pagesIn(row.id, "skipped");
        capped |= store.pagesIn(row.id, "capped") > 0;
        String text = store.pagesText(row.id);
        String reason = skipped > 0 ? SOME_PAGES : capped ? FIRST_PART : "";
        if (skipped > 0 && text.trim().isEmpty() && store.pageTotal(row.id) == skipped) return finish(row, SourceStore.UNREADABLE, type.id(), type.version(), GAVE_UP, "");
        return finish(row, SourceStore.INDEXED, type.id(), type.version(), reason, text);
    }

    private static String nz(String value) { return value == null ? "" : value; }

    private void failed(SourceStore.FileRow row) {
        int attempts = row.attempts + 1;
        if (attempts >= MAX_ATTEMPTS) finish(row, SourceStore.UNREADABLE, "", 0, GAVE_UP, "");
        else store.db().execSQL("UPDATE source_files SET attempts=? WHERE id=?", new Object[]{attempts, row.id});
    }

    private boolean finish(SourceStore.FileRow row, String state, String handler, int handlerVersion, String reason, String text) {
        SQLiteDatabase db = store.db();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("state", state); v.put("reason", reason); v.put("handler_id", handler); v.put("handler_version", handlerVersion);
            v.put("type_fingerprint", FileTypes.fingerprint()); v.put("attempts", 0);
            v.put("text_sha", text.isEmpty() ? "" : sha256(text));
            db.update("source_files", v, "id=? AND state=?", new String[]{row.id, SourceStore.PENDING});
            store.indexName(row.id, row.name, text);
            store.clearPages(row.id);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        changed();
        return true;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    // ---- environment ----------------------------------------------------------------------------------------------------------

    /** False below 15% unless charging. */
    static boolean batteryOk(Context context) {
        try {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return true;
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) return true;
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            return level < 0 || scale <= 0 || level * 100 / scale >= LOW_BATTERY_PERCENT;
        } catch (RuntimeException unknown) { return true; }
    }

    /** Schedules the 6-hour job when there is at least one source; cancels it when there is none. Safe to call on every start. */
    static void schedule(StackApplication app) {
        android.app.job.JobScheduler jobs = app.getSystemService(android.app.job.JobScheduler.class);
        if (jobs == null) return;
        boolean any;
        try { any = !app.library.sources.list().isEmpty(); } catch (RuntimeException unavailable) { return; }
        if (!any) { jobs.cancel(SourceIndexJob.JOB); return; }
        android.app.job.JobInfo current = jobs.getPendingJob(SourceIndexJob.JOB);
        if (current != null && current.getExtras().getInt("version") == SourceIndexJob.VERSION) return;   // scheduling again would stop a running job
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putInt("version", SourceIndexJob.VERSION);
        jobs.schedule(new android.app.job.JobInfo.Builder(SourceIndexJob.JOB, new android.content.ComponentName(app, SourceIndexJob.class))
                .setExtras(extras).setPersisted(true).setPeriodic(6 * 60 * 60 * 1000L).setRequiresBatteryNotLow(true)
                .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY).build());
    }

    /** App start: removes what an earlier run left in the download folder (a file being read, an answer of the extract process). Not while a run is going. */
    void sweepRead() {
        if (active.get()) return;
        File[] old = new File(context.getCacheDir(), "source-read").listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    List<SourceStore.Source> sources() { return Collections.unmodifiableList(store.list()); }
}
