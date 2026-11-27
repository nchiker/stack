package org.stackdocs.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Scan sharing (docs/FOLDER_SHARING_DESIGN.md section 1, "Scans"). A stack with the Share switch on has every document in it written as a PDF into
 * the Filen folder Filen created for it ({@link FilenPartner#createFolderRequest}, kept as the `scan_share` source with a READ+WRITE grant).
 * The files are named by a short stable part of the document id and its title, so a rename keeps the same file. A change to a document's title or
 * pages writes the file again; a document that leaves every shared stack (or is deleted, or the switch goes off) has its file removed. The
 * originals stay in Stack. Work is done by {@link #run}: it waits while the app is locked, and an unreachable Filen leaves the work for the next
 * try (the foreground poll, the six-hourly job, the next change). Never runs on the UI thread.
 */
final class ScanShare {
    static final int REQUEST = 7331;
    private static final long DEBOUNCE = 3000L, RETRY_AFTER = 10L * 60 * 1000;

    /** The shared Filen folder as the writer needs it: names in it, an atomic put, a delete. */
    interface Folder {
        /** Puts `content` into the folder under `name`, replacing an old file of that name; a reader never sees a half-written file. */
        void put(String name, File content) throws IOException;
        /** Removes a file by name; a file that is not there is fine. */
        void delete(String name) throws IOException;
        /** The names in the folder and Filen's account id for the answer; null when this folder cannot say (nothing is then reconciled or checked). */
        default Names list() throws IOException { return null; }
    }

    /** The folder's files by name. `fresh` is true only for an answer Filen listed from the server just now with an account id. */
    static final class Names {
        final boolean fresh; final String account, legacyAccount; final java.util.List<String> names;
        Names(boolean fresh, String account, java.util.List<String> names) { this(fresh, account, "", names); }
        Names(boolean fresh, String account, String legacyAccount, java.util.List<String> names) { this.fresh = fresh; this.account = account == null ? "" : account; this.legacyAccount = legacyAccount == null ? "" : legacyAccount; this.names = names; }
    }

    /** Writes the PDF of one document. */
    interface Exporter { void export(String documentId, OutputStream out) throws IOException; }

    /** What one run did, for checks (no names). */
    static final class Result { int written, removed, orphans; boolean complete, removalPending; String stopped = ""; }

    private final Context context;
    private final SourceStore store;
    private final Function<SourceStore.Source, Folder> folders;
    private final Exporter exporter;
    private final java.util.function.BooleanSupplier allowed;
    private final File cache;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "Stack scan sharing"));
    private final java.util.concurrent.atomic.AtomicBoolean queued = new java.util.concurrent.atomic.AtomicBoolean();
    private Runnable scheduled;
    private volatile Runnable afterRun = () -> {};
    /** Bumped by every library change (a run checks the document it is about to write again) and, in {@link #stopper}, by anything that must end a run now. */
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong stopper = new java.util.concurrent.atomic.AtomicLong();
    static final String REMOVAL_PENDING = "Shared copies will be removed when Filen is reachable";
    /** Whether Stack still holds the READ+WRITE grant for a source; a check replaces it. */
    volatile java.util.function.Predicate<SourceStore.Source> grantOk;

    /** The partner Stack shares with now, "" when unknown. A run asks it before it writes; the default knows only what Filen's partner_status said earlier, the app asks Filen. Off the main thread. */
    volatile java.util.function.Supplier<String> partnerLookup = () -> FilenPartner.knownName();
    /** Filen's stable id of the partner now (F9 `partner_id`), "" when Filen cannot say or sends none. The app asks Filen each time. */
    volatile java.util.function.Supplier<String> partnerIdLookup = () -> "";
    /** Filen's partner_status answer now (state, id, name from ONE call), null when Filen did not answer (offline, no reply, too old). A null lookup: not asked (checks). */
    volatile java.util.function.Supplier<FilenPartner.Status> partnerStateLookup;
    static final String PARTNER_UNKNOWN = "Waiting to check your partner in Filen";

    ScanShare(Context context, SourceStore store, Function<SourceStore.Source, Folder> folders, Exporter exporter, java.util.function.BooleanSupplier allowed) {
        this.context = context.getApplicationContext(); this.store = store; this.folders = folders; this.exporter = exporter; this.allowed = allowed;
        this.cache = new File(this.context.getCacheDir(), "scan-share");
        this.grantOk = source -> store.granted(source.tree()) && store.writable(source.tree());
    }

    /** The real folder, exporter and app lock. */
    static ScanShare forApp(StackApplication app) {
        ScanShare made = new ScanShare(app, app.library.sources, source -> new SafFolder(app.getContentResolver(), source.tree()), (id, out) -> {
            try { app.io.submit(() -> { app.library.exportPdf(app.library.document(id), out); return true; }).get(); }
            catch (java.util.concurrent.ExecutionException error) { throw error.getCause() instanceof IOException ? (IOException) error.getCause() : new IOException("Could not make the PDF"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Stopped"); }
        }, () -> !app.resetRequested && app.access.allowed());
        made.partnerLookup = () -> FilenPartner.freshName(app);   // asked of Filen each run: a kept name would hide a change
        made.partnerIdLookup = () -> FilenPartner.freshId(app);
        made.partnerStateLookup = () -> FilenPartner.askedStatus(app);
        return made;
    }

    /** At app start no run is going: the PDFs a run left in the cache (a crash, a kill) are deleted. */
    static void sweepCache(Context context) {
        File[] left = new File(context.getCacheDir(), "scan-share").listFiles();
        if (left != null) for (File file : left) file.delete();
    }

    void onRunFinished(Runnable action) { afterRun = action; }

    SharedPreferences prefs() { return context.getSharedPreferences("scan_share", Context.MODE_PRIVATE); }

    // ---- the per-stack switch -----------------------------------------------------------------------------------------------------

    boolean isOn(String stackId) {
        try (Cursor c = store.db().rawQuery("SELECT 1 FROM scan_share_stacks WHERE stack_id=?", new String[]{stackId})) { return c.moveToFirst(); }
    }

    void setOn(String stackId, boolean on) {
        generation.incrementAndGet(); stopper.incrementAndGet();   // a run in progress ends at its next step
        if (on) store.db().execSQL("INSERT OR IGNORE INTO scan_share_stacks(stack_id,added_at) VALUES(?,?)", new Object[]{stackId, Clock.now()});
        else store.db().execSQL("DELETE FROM scan_share_stacks WHERE stack_id=?", new Object[]{stackId});
        prefs().edit().putBoolean("pending", true).apply();
    }

    /** The app lock locked, the library is being erased, or the switch changed: a run in progress ends before its next write. */
    void cancel() { generation.incrementAndGet(); stopper.incrementAndGet(); }

    /** True while there is anything to do or to undo: a switch, a written file, an upload in the journal, or the reconcile or pending flag. */
    boolean anyOn() {
        SharedPreferences p = prefs();
        if (p.getBoolean("reconcile", false) || p.getBoolean("pending", false)) return true;
        try (Cursor c = store.db().rawQuery("SELECT 1 FROM scan_share_stacks UNION SELECT 1 FROM scan_share_files UNION SELECT 1 FROM scan_share_journal LIMIT 1", null)) { return c.moveToFirst(); }
    }

    // ---- the journal: a file name is written down BEFORE the upload and crossed out only once the row exists or the delete is confirmed -----

    void journal(String documentId, String fileName) {
        store.db().execSQL("INSERT OR REPLACE INTO scan_share_journal(file_name,document_id,state,started_at) VALUES(?,?,'uploading',?)", new Object[]{fileName, documentId, Clock.now()});
    }

    void unjournal(String fileName) { store.db().execSQL("DELETE FROM scan_share_journal WHERE file_name=?", new Object[]{fileName}); }

    /** Names (and their document ids) that may be in the folder without a row: {file name, document id}. */
    List<String[]> journalRows() {
        List<String[]> out = new ArrayList<>();
        try (Cursor c = store.db().rawQuery("SELECT file_name,document_id FROM scan_share_journal ORDER BY started_at,file_name", null)) { while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1)}); }
        return out;
    }

    /** Every name Stack itself recorded for the folder, including the temporary names of saves in flight: the only names it ever deletes. */
    java.util.Set<String> recordedNames() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (Written w : written().values()) names.add(w.fileName);
        for (String[] j : journalRows()) { names.add(j[0]); names.add(j[0] + ".part"); }
        return names;
    }

    /** All switches off (a new partner: nobody is shared with until the person turns each stack on again). The files stay on the list to be removed. */
    void switchesOff() {
        generation.incrementAndGet(); stopper.incrementAndGet();
        store.db().execSQL("DELETE FROM scan_share_stacks");
        prefs().edit().putBoolean("pending", true).apply();
    }

    private static String quiet(java.util.function.Supplier<String> lookup) {
        try { String got = lookup.get(); return got == null ? "" : got.trim(); } catch (RuntimeException unavailable) { return ""; }
    }

    /** The partner name now: Filen's fresh answer first, the name kept from earlier only when Filen cannot answer. */
    private String currentPartner() {
        String asked = quiet(partnerLookup);
        if (!asked.isEmpty()) return asked;
        String known = store.partnerName();
        return known != null && !known.trim().isEmpty() ? known.trim() : "";
    }

    /**
     * True when the folder was made for another partner than the one Filen reports now. Filen's fresh `partner_id` is compared first with the id kept with
     * the folder; with no id on either side (an older Filen, or a folder made before ids) Filen's fresh name is compared with the name kept with the folder
     * (an id seen for the first time is then kept). Only when Filen cannot answer at all, the partner source's kept id, else its kept name, stands in.
     * A folder with no recorded partner counts as changed once a partner is known. When nobody can be named nothing is assumed: the switches stay.
     */
    boolean partnerChanged(SourceStore.Source source) {
        String freshId = quiet(partnerIdLookup), freshName = quiet(partnerLookup);
        if (!freshId.isEmpty() && !source.partnerId.isEmpty()) return !freshId.equals(source.partnerId);
        String current = freshName;
        if (current.isEmpty() && freshId.isEmpty()) {
            String keptId = store.partnerId();
            if (!keptId.isEmpty() && !source.partnerId.isEmpty()) return !keptId.equals(source.partnerId);
            String known = store.partnerName();
            current = known == null ? "" : known.trim();
        }
        if (current.isEmpty()) return false;
        boolean changed = source.partnerName.isEmpty() || !source.partnerName.equals(current);
        if (!changed) store.setPartnerId(source.id, freshId);   // the same partner by name: its id is kept from now on
        return changed;
    }

    /**
     * Like {@link #partnerChanged(SourceStore.Source)} but decided only by the one partner_status answer [status] (state connected): its own id against the id kept with the folder,
     * else its own name against the folder's name. Nothing is asked again and nothing falls back to a kept partner.
     */
    boolean partnerChanged(SourceStore.Source source, FilenPartner.Status status) {
        String id = status.id.trim(), name = status.name.trim();
        if (!id.isEmpty() && !source.partnerId.isEmpty()) return !id.equals(source.partnerId);
        if (name.isEmpty()) return false;
        boolean changed = source.partnerName.isEmpty() || !source.partnerName.equals(name);
        if (!changed) store.setPartnerId(source.id, id);
        return changed;
    }

    // ---- the plan -----------------------------------------------------------------------------------------------------------------

    /** One document that should be in the folder. */
    static final class Item { final String id, title, signature; Item(String id, String title, String signature) { this.id = id; this.title = title; this.signature = signature; } }

    /** One file the writer put into the folder earlier. */
    static final class Written { final String documentId, fileName, signature; Written(String d, String n, String s) { documentId = d; fileName = n; signature = s; } }

    /** What to do: documents to write (new or changed) and earlier files to remove (document gone from every shared stack, or renamed). */
    static final class Plan { final List<Item> write = new ArrayList<>(); final List<Written> remove = new ArrayList<>(); }

    static Plan plan(Map<String, Item> desired, Map<String, Written> written) {
        Plan plan = new Plan();
        for (Item item : desired.values()) {
            Written old = written.get(item.id);
            if (old == null || !old.signature.equals(item.signature) || !old.fileName.equals(fileName(item.title, item.id))) plan.write.add(item);
        }
        for (Written old : written.values()) if (!desired.containsKey(old.documentId)) plan.remove.add(old);
        return plan;
    }

    /** `Title [8 characters of the id].pdf`: readable, and the same file for the same document whatever its title. */
    static String fileName(String title, String documentId) {
        String clean = title == null ? "" : title.replaceAll("[\\p{Cc}\\\\/:*?\"<>|\\u202a-\\u202e\\u2066-\\u2069]", " ").replaceAll("\\s+", " ").trim();
        if (clean.length() > 80) clean = clean.substring(0, 80).trim();
        if (clean.isEmpty()) clean = "Scan";
        String id = documentId.replace("-", "");
        return clean + " [" + id.substring(0, Math.min(8, id.length())) + "].pdf";
    }

    /** Documents in a stack with the switch on, not in Recently deleted, with a signature of their title and pages. */
    Map<String, Item> desired() throws IOException {
        SQLiteDatabase db = store.db();
        Map<String, String[]> docs = new LinkedHashMap<>();
        try (Cursor c = db.rawQuery("SELECT DISTINCT d.id,d.title,d.mime,d.page_count FROM documents d JOIN document_stacks ds ON ds.document_id=d.id "
                + "JOIN scan_share_stacks s ON s.stack_id=ds.stack_id WHERE d.id NOT IN (SELECT document_id FROM trash) ORDER BY d.id", null)) {
            while (c.moveToNext()) docs.put(c.getString(0), new String[]{c.getString(1), c.getString(2), Integer.toString(c.getInt(3))});
        }
        Map<String, MessageDigest> digests = new HashMap<>();
        try {
            for (String id : docs.keySet()) {
                MessageDigest d = MessageDigest.getInstance("SHA-256");
                String[] v = docs.get(id);
                d.update((v[0] + "\n" + v[1] + "\n" + v[2] + "\n").getBytes("UTF-8"));
                digests.put(id, d);
            }
            try (Cursor c = db.rawQuery("SELECT document_id,id,position,source_page,rotation,crop,enhancement,flatten,source_file FROM pages ORDER BY document_id,position", null)) {
                while (c.moveToNext()) {
                    MessageDigest d = digests.get(c.getString(0));
                    if (d == null) continue;
                    StringBuilder line = new StringBuilder();
                    for (int i = 1; i < 9; i++) line.append(c.getString(i)).append('|');
                    d.update(line.append('\n').toString().getBytes("UTF-8"));
                }
            }
            Map<String, Item> out = new LinkedHashMap<>();
            for (String id : docs.keySet()) out.put(id, new Item(id, docs.get(id)[0], hex(digests.get(id).digest())));
            return out;
        } catch (java.security.GeneralSecurityException | java.io.UnsupportedEncodingException impossible) { throw new IOException("No digest"); }
    }

    private static String hex(byte[] bytes) { StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 0xff)); return s.toString(); }

    Map<String, Written> written() {
        Map<String, Written> out = new LinkedHashMap<>();
        try (Cursor c = store.db().rawQuery("SELECT document_id,file_name,signature FROM scan_share_files ORDER BY document_id", null)) {
            while (c.moveToNext()) out.put(c.getString(0), new Written(c.getString(0), c.getString(1), c.getString(2)));
        }
        return out;
    }

    // ---- running ------------------------------------------------------------------------------------------------------------------

    private static final long RECONCILE_EVERY = 6L * 60 * 60 * 1000;
    static final String PARTNER_CHANGED = "Your partner changed. Scan sharing is off; turn it on again for each stack.";
    static final String ACCOUNT_CHANGED = "Filen is signed in as someone else.";

    /** True when the document is still in a stack whose switch is on and not in Recently deleted. Asked again right before every write. */
    boolean sharedNow(String documentId) {
        try (Cursor c = store.db().rawQuery("SELECT 1 FROM documents d JOIN document_stacks ds ON ds.document_id=d.id JOIN scan_share_stacks s ON s.stack_id=ds.stack_id "
                + "WHERE d.id=? AND d.id NOT IN (SELECT document_id FROM trash) LIMIT 1", new String[]{documentId})) { return c.moveToFirst(); }
    }

    /** Files that are scheduled to leave the Filen folder but are still there: switched off, removed scans, an old folder. */
    boolean removalPending() {
        try { if (!plan(desired(), written()).remove.isEmpty() || !journalRows().isEmpty()) return true; } catch (IOException | RuntimeException unknown) { return false; }
        return !oldTrees().isEmpty();
    }

    /**
     * Brings the folder in line with the shared stacks. Returns what it did. Locked app: waits (state `waiting`). No folder or lost grant: states
     * `ok`-less `access_lost`. An IOException (Filen unreachable, offline) stops the run, keeps everything, and is tried again later.
     * Before every write the document is checked again (still in a shared stack, not deleted, switch on, app unlocked, grant held): a switch turned
     * off or a lock during a run stops it before the next file, so nothing is written after the person said stop.
     */
    synchronized Result run() {
        Result result = new Result();
        try { sweepOldTrees(); } catch (RuntimeException ignored) { /* tried again at the next run */ }
        SourceStore.Source source = store.first(SourceStore.SCAN_SHARE);
        if (source == null) { result.stopped = "no_folder"; return result; }
        if (!allowed.getAsBoolean()) { store.setState(source.id, SourceStore.WAITING, "Locked. Saving continues when you unlock Stack."); result.stopped = "locked"; prefs().edit().putBoolean("pending", true).apply(); return result; }
        if (!grantOk.test(source)) {
            store.setState(source.id, SourceStore.ACCESS_LOST, "Stack no longer has access to this folder. Choose it again."); result.stopped = "access"; return result;
        }
        // A definite Filen answer other than "connected" is a partner change; no answer pauses the writes (removals still go ahead); only "connected" with the same partner goes on.
        FilenPartner.Status asked = null;
        if (partnerStateLookup != null) try { asked = partnerStateLookup.get(); } catch (RuntimeException failed) { asked = null; }
        // "connected" with neither an id nor a name says nobody we can compare: that is unknown, not unchanged.
        boolean partnerUnknown = partnerStateLookup != null && (asked == null || asked.state.isEmpty()
                || (FilenPartner.CONNECTED.equals(asked.state) && asked.id.trim().isEmpty() && asked.name.trim().isEmpty()));
        boolean connected = partnerStateLookup == null || (asked != null && FilenPartner.CONNECTED.equals(asked.state));
        boolean partnerChanged = !partnerUnknown && (!connected || (asked == null ? partnerChanged(source) : partnerChanged(source, asked)));
        if (partnerChanged) switchesOff();   // a new partner gets nothing until each stack is switched on again; the old copies are still removed below
        final long hard = stopper.get();
        prefs().edit().remove("pending").apply();
        Plan plan;
        try { plan = plan(desired(), written()); } catch (IOException | RuntimeException unreadable) { result.stopped = "database"; prefs().edit().putBoolean("pending", true).apply(); return result; }
        if (partnerUnknown) plan.write.clear();
        long now = Clock.now();
        boolean reconcile = prefs().getBoolean("reconcile", false) || now - prefs().getLong("last_reconcile", 0) >= RECONCILE_EVERY || prefs().getLong("last_reconcile", 0) > now;
        if (partnerUnknown && plan.remove.isEmpty() && journalRows().isEmpty()) { waitForPartner(source, result); return result; }
        if (plan.write.isEmpty() && plan.remove.isEmpty() && !reconcile && journalRows().isEmpty()) {
            if (partnerChanged) { store.setState(source.id, SourceStore.ACCESS_LOST, PARTNER_CHANGED); result.stopped = "identity"; return result; }
            store.setState(source.id, SourceStore.OK, ""); result.complete = true; return result;
        }
        store.setState(source.id, SourceStore.SCANNING, "");
        Folder folder = folders.apply(source);
        cache.mkdirs();
        try {
            Names listing = folder.list();
            if (listing != null && !listing.fresh) throw new IOException("Filen answered from its saved copy");
            String problem = identityProblem(source, listing);
            if (problem != null) {
                store.setState(source.id, SourceStore.ACCOUNT_CHANGED, problem);
                result.stopped = "identity"; return result;   // nothing is written or removed under another Filen account
            }
            // Uploads that were cut short (a failed take-down, a crash between the upload and its row): remove exactly the names written down before them.
            List<String[]> cutShort = journalRows();
            for (String[] j : cutShort) {
                folder.delete(j[0]); folder.delete(j[0] + ".part");
                store.db().execSQL("DELETE FROM scan_share_files WHERE file_name=?", new Object[]{j[0]});   // a replaced old file went with it: the scan is written again
                unjournal(j[0]);
                result.orphans++;
            }
            if (!cutShort.isEmpty()) try { plan = plan(desired(), written()); } catch (IOException unreadable) { throw new IOException("database"); }
            if (partnerUnknown) plan.write.clear();   // a re-plan must not bring writes back
            for (Written old : plan.remove) {
                folder.delete(old.fileName);
                store.db().execSQL("DELETE FROM scan_share_files WHERE document_id=?", new Object[]{old.documentId});
                result.removed++;
            }
            if (listing != null) {   // nothing is ever deleted by its look: only names Stack recorded (rows and journal) are touched
                java.util.Set<String> listed = new java.util.HashSet<>(listing.names);
                java.util.Map<String, Item> wanted = desired();
                for (Written w : written().values()) {   // a row but no file: a failed rename after the old file was deleted, or the file was removed by hand
                    Item item = wanted.get(w.documentId);
                    if (item == null || listed.contains(w.fileName) || partnerUnknown) continue;
                    boolean queued = false;
                    for (Item q : plan.write) if (q.id.equals(item.id)) queued = true;
                    if (!queued) plan.write.add(item);
                }
                prefs().edit().remove("reconcile").putLong("last_reconcile", Clock.now()).apply();
            }
            Map<String, Written> before = written();
            if (!partnerUnknown) for (Item item : plan.write) {
                String stop = guard(hard, source, partnerUnknown);
                if (stop != null) { result.stopped = stop; prefs().edit().putBoolean("pending", true).apply(); return result; }
                if (!sharedNow(item.id)) continue;
                File temp = new File(cache, UUID.randomUUID() + ".pdf");
                try {
                    try (OutputStream out = new FileOutputStream(temp)) { exporter.export(item.id, out); }
                    stop = guard(hard, source, partnerUnknown);   // the export takes time: ask again right before the write
                    if (stop != null) { result.stopped = stop; prefs().edit().putBoolean("pending", true).apply(); return result; }
                    if (!sharedNow(item.id)) continue;
                    String name = fileName(item.title, item.id);
                    journal(item.id, name);   // written down BEFORE the upload: a crash or a failed take-down leaves this line, so the file is found and removed
                    folder.put(name, temp);
                    if (!sharedNow(item.id) || stopper.get() != hard) {   // it changed while the file was going up: take it down again
                        try { folder.delete(name); folder.delete(name + ".part"); unjournal(name); } catch (IOException later) { /* the journal line stays: the next run removes it */ }
                        if (stopper.get() != hard) { result.stopped = "changed"; return result; }
                        continue;
                    }
                    Written old = before.get(item.id);
                    if (old != null && !old.fileName.equals(name)) folder.delete(old.fileName);
                    store.db().execSQL("INSERT OR REPLACE INTO scan_share_files(document_id,file_name,signature,written_at) VALUES(?,?,?,?)", new Object[]{item.id, name, item.signature, Clock.now()});
                    unjournal(name);
                    result.written++;
                } catch (IllegalArgumentException gone) {
                    // The document left the library while the run was going: the next run removes whatever it had.
                } finally { temp.delete(); }
            }
            if (partnerChanged) { result.stopped = "identity"; store.setState(source.id, SourceStore.ACCESS_LOST, PARTNER_CHANGED); }
            else if (partnerUnknown) waitForPartner(source, result);
            else { result.complete = true; store.setState(source.id, SourceStore.OK, ""); }
        } catch (IOException unreachable) {
            result.stopped = "unreachable";
            store.setState(source.id, SourceStore.UNREACHABLE, "Can't reach Filen right now. Saving will try again.");
            prefs().edit().putBoolean("pending", true).putLong("last_attempt", Clock.now()).apply();
        } catch (RuntimeException unexpected) {
            result.stopped = "error";
            prefs().edit().putBoolean("pending", true).putLong("last_attempt", Clock.now()).apply();
        }
        result.removalPending = removalPending();
        return result;
    }

    /** Filen could not say who the partner is: nothing is written, the run is tried again later. */
    private void waitForPartner(SourceStore.Source source, Result result) {
        store.setState(source.id, SourceStore.WAITING, PARTNER_UNKNOWN);
        result.stopped = "partner_unknown";
        prefs().edit().putBoolean("pending", true).putLong("last_attempt", Clock.now()).apply();
    }

    /** Null to go on; otherwise why the run must end now. */
    private String guard(long hard, SourceStore.Source source, boolean partnerUnknown) {
        if (stopper.get() != hard) return "changed";
        if (partnerUnknown) return "partner_unknown";
        if (!allowed.getAsBoolean()) { store.setState(source.id, SourceStore.WAITING, "Locked. Saving continues when you unlock Stack."); return "locked"; }
        if (!grantOk.test(source)) { store.setState(source.id, SourceStore.ACCESS_LOST, "Stack no longer has access to this folder. Choose it again."); return "access"; }
        return null;
    }

    /** Another Filen account than the folder was made under: the run pauses with a message and writes and removes nothing. (Another partner is handled in {@link #run}: removals go on.) */
    private String identityProblem(SourceStore.Source source, Names listing) {
        if (listing != null && listing.fresh && !listing.account.isEmpty()) {
            if (source.accountId.isEmpty()) store.setAccount(source.id, listing.account);
            else if (!source.accountId.equals(listing.account)) {
                if (!listing.legacyAccount.isEmpty() && source.accountId.equals(listing.legacyAccount)) store.setAccount(source.id, listing.account);   // Filen's new id for the same account: adopted once
                else return ACCOUNT_CHANGED;
            }
        }
        return null;
    }

    // ---- an old folder: replaced by another one, or left by an erased library ---------------------------------------------------------

    private SharedPreferences oldPrefs() { return context.getSharedPreferences("scan_share_old", Context.MODE_PRIVATE); }

    /** Trees whose shared copies could not be removed yet, with the file names. Kept in preferences: they survive replacing the folder and erasing the library. */
    Map<String, java.util.Set<String>> oldTrees() {
        Map<String, java.util.Set<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : oldPrefs().getAll().entrySet()) {
            if (!(e.getValue() instanceof java.util.Set)) continue;
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            for (Object o : (java.util.Set<?>) e.getValue()) if (o instanceof String) names.add((String) o);
            if (!names.isEmpty()) out.put(e.getKey(), names);
        }
        return out;
    }

    /** Removes the files of every old tree that is reachable now; a tree with nothing left gives its grant back. */
    void sweepOldTrees() {
        for (Map.Entry<String, java.util.Set<String>> old : oldTrees().entrySet()) {
            Uri tree = Uri.parse(old.getKey());
            Folder folder = folders.apply(new SourceStore.Source("old", SourceStore.SCAN_SHARE, old.getKey(), "", "", "", SourceStore.OK, "", 0, 0));
            java.util.Set<String> left = new java.util.LinkedHashSet<>(old.getValue());
            try { for (String name : old.getValue()) { folder.delete(name); left.remove(name); } }
            catch (IOException unreachable) { /* later */ }
            if (left.isEmpty()) { oldPrefs().edit().remove(old.getKey()).commit(); store.releaseUnlessUsed(tree); }
            else oldPrefs().edit().putStringSet(old.getKey(), left).apply();
        }
    }

    /** Remembers files that could not be removed from `tree` yet (its grant is kept until they are). */
    void rememberOld(String tree, java.util.Collection<String> names) { rememberOld(context, tree, names); }

    static void rememberOld(Context context, String tree, java.util.Collection<String> names) {
        if (names.isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences("scan_share_old", Context.MODE_PRIVATE);
        java.util.Set<String> all = new java.util.LinkedHashSet<>(names);
        java.util.Set<String> already = prefs.getStringSet(tree, null);
        if (already != null) all.addAll(already);
        prefs.edit().putStringSet(tree, all).commit();
    }

    // ---- when it runs -------------------------------------------------------------------------------------------------------------

    /** A library change or a switch: a burst becomes one run a few seconds later. Safe on the UI thread; cheap when nothing is shared. */
    void changed() {
        if (scheduled != null) ui.removeCallbacks(scheduled);
        scheduled = this::start;
        ui.postDelayed(scheduled, DEBOUNCE);
    }

    /** Runs soon, off the main thread (the switch just changed). */
    void start() {
        if (!queued.compareAndSet(false, true)) return;
        worker.execute(() -> {
            queued.set(false);
            try { if (anyOn() || !oldTrees().isEmpty()) run(); } catch (RuntimeException ignored) { /* the next trigger tries again */ } finally { afterRun.run(); }
        });
    }

    /** As {@link #start}, then `done` gets whether shared copies are still waiting to be removed (main thread): the screens say so in plain words. */
    void startAndReport(java.util.function.Consumer<Boolean> done) {
        worker.execute(() -> {
            boolean pending;
            try { if (anyOn() || !oldTrees().isEmpty()) run(); } catch (RuntimeException ignored) { /* the next trigger tries again */ }
            try { pending = removalPending(); } catch (RuntimeException unknown) { pending = false; }
            final boolean answer = pending;
            ui.post(() -> done.accept(answer));
            afterRun.run();
        });
    }

    /** Foreground poll and job: try again after a failure or a locked app, not more often than every ten minutes. */
    void retryIfNeeded() {
        SharedPreferences p = prefs();
        if (!p.getBoolean("pending", false) && oldTrees().isEmpty()) return;
        long now = Clock.now(), attempt = p.getLong("last_attempt", 0);
        if (attempt > now || now - attempt >= RETRY_AFTER) start();
    }

    // ---- Filen's answer to "create the shared scan folder" ------------------------------------------------------------------------

    /** Keeps the READ+WRITE grants of a CREATE_SHARED_FOLDER result. Call on the main thread inside onActivityResult. */
    static void keepGrant(Context context, android.content.Intent data) {
        Uri tree = data == null ? null : data.getData();
        if (tree == null) return;
        try { context.getContentResolver().takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
        catch (RuntimeException refused) { /* SourceStore.add reports a tree that is not held */ }
    }

    /**
     * Makes the folder Filen returned the `scan_share` source (named by Filen's FOLDER_NAME extra). When there already is one with another tree (Fix,
     * or a new partner) it is replaced in place: the shared copies in the old folder are removed when it is reachable (otherwise remembered and removed
     * later), and every scan is written again into the new folder. The Filen account is recorded with the source. Off the main thread.
     */
    synchronized SourceStore.Source setFolder(Uri tree, String folderName, String partnerName) throws IOException {
        String title = folderName == null || folderName.trim().isEmpty() ? FilenPartner.scanFolderName(PartnerSetup.lowerName(partnerName)) : folderName.trim();
        SourceStore.Source old = store.first(SourceStore.SCAN_SHARE);
        if (partnerName == null || partnerName.trim().isEmpty()) partnerName = currentPartner();   // the partner this folder is for is always written down
        String partnerId = quiet(partnerIdLookup);
        boolean newPartner = old != null && (!partnerId.isEmpty() && !old.partnerId.isEmpty() ? !partnerId.equals(old.partnerId) : !partnerName.trim().equals(old.partnerName));
        if (newPartner) switchesOff();   // a new partner: nothing is shared with them until each stack is switched on again
        SourceStore.Source now;
        if (old == null || old.treeUri.equals(tree.toString())) now = store.add(SourceStore.SCAN_SHARE, tree, title, partnerName);
        else {
            java.util.Set<String> names = recordedNames();
            boolean removed = true;
            try { Folder gone = folders.apply(old); for (String name : names) gone.delete(name); }
            catch (IOException | RuntimeException unreachable) { removed = false; }
            if (!removed) rememberOld(old.treeUri, names);
            now = store.replaceScanShare(old.id, tree, title, partnerName, !removed);
            store.db().execSQL("DELETE FROM scan_share_files");
            store.db().execSQL("DELETE FROM scan_share_journal");   // its names went with the old folder: removed there, or remembered in rememberOld above
        }
        store.setPartnerId(now.id, partnerId);
        try {
            Names listing = folders.apply(now).list();
            if (listing != null && listing.fresh && !listing.account.isEmpty()) store.setAccount(now.id, listing.account);
        } catch (IOException | RuntimeException unreachable) { /* recorded by the first run that can list it */ }
        prefs().edit().putBoolean("reconcile", true).putBoolean("pending", true).apply();
        return store.get(now.id);
    }

    /**
     * Turns the switch on for a stack. With a scan folder already there it is just the switch; otherwise Filen's CREATE_SHARED_FOLDER screen runs
     * first (partner setup before that when there is no partner). `done` gets "" or plain words, on the main thread.
     */
    static void turnOn(LibraryActivity activity, StackCategory stack, java.util.function.Consumer<String> done) {
        activity.work(() -> new Object[]{activity.app().scanShare.shareableCount(stack.id), FilenPartner.name(activity, activity.app().library.sources.partnerName())}, ask -> {
            String text = confirmText((Integer) ask[0], (String) ask[1]);
            new android.app.AlertDialog.Builder(activity).setTitle("Share scans?").setMessage(text)
                    .setNegativeButton("Cancel", (d, w) -> done.accept(""))
                    .setOnCancelListener(d -> done.accept(""))
                    .setPositiveButton("Share", (d, w) -> proceed(activity, stack, done)).show();
        });
    }

    /** The words of the question asked before the first scan of a stack is shared. */
    static String confirmText(int count, String partner) {
        return "Share " + count + (count == 1 ? " scan" : " scans") + " with " + PartnerSetup.lowerName(partner) + "? They'll be copied to " + FilenPartner.SCAN_FOLDER + " in your Filen.";
    }

    /** How many documents the switch of this stack would share now (not in Recently deleted). */
    int shareableCount(String stackId) {
        try (Cursor c = store.db().rawQuery("SELECT COUNT(DISTINCT d.id) FROM documents d JOIN document_stacks ds ON ds.document_id=d.id WHERE ds.stack_id=? "
                + "AND d.id NOT IN (SELECT document_id FROM trash)", new String[]{stackId})) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }

    private static void proceed(LibraryActivity activity, StackCategory stack, java.util.function.Consumer<String> done) {
        activity.work(() -> {
            SourceStore.Source have = activity.app().library.sources.first(SourceStore.SCAN_SHARE);
            // A folder made for another partner is not used again: Filen makes a new one for the partner there is now.
            return new String[]{have == null || activity.app().scanShare.partnerChanged(have) ? "" : "have", activity.app().library.sources.partnerName()};
        }, state -> {
            if (!state[0].isEmpty()) { activity.work(() -> { activity.app().scanShare.setOn(stack.id, true); activity.app().scanShare.start(); return true; }, ok -> done.accept("")); return; }
            pendingStack = stack.id;
            if (state[1] == null) { pendingAfterSetup = true; String[] problem = {""}; if (!PartnerSetup.start(activity, problem)) { pendingAfterSetup = false; pendingStack = null; done.accept(problem[0]); } return; }
            startCreate(activity, state[1], done);
        });
    }

    static String pendingStack;
    static boolean pendingAfterSetup;

    static void startCreate(LibraryActivity activity, String partner, java.util.function.Consumer<String> done) {
        android.content.Intent request = FilenPartner.createFolderRequest(FilenPartner.scanFolderName(PartnerSetup.lowerName(partner)));
        if (!FilenPartner.available(activity, request)) { pendingStack = null; done.accept("Update Filen Files to share scans."); return; }
        try { activity.startActivityForResult(request, REQUEST); } catch (RuntimeException unavailable) { pendingStack = null; done.accept("Filen Files could not be opened."); }
    }

    /** The result of the partner setup that came first: go on to the folder. */
    static void afterSetup(LibraryActivity activity, int result, android.content.Intent data, java.util.function.Consumer<String> done) {
        final boolean follow = pendingAfterSetup; pendingAfterSetup = false;
        PartnerSetup.finish(activity, result, data, problem -> {
            if (!follow || result != android.app.Activity.RESULT_OK || !problem.isEmpty()) { pendingStack = null; done.accept(problem); return; }
            activity.work(() -> activity.app().library.sources.partnerName(), name -> startCreate(activity, name, done));
        });
    }

    /** The result of CREATE_SHARED_FOLDER: keep the grant, add the source, switch the stack on, and start writing. */
    static void afterCreate(LibraryActivity activity, int result, android.content.Intent data, java.util.function.Consumer<String> done) {
        final String stack = pendingStack; pendingStack = null;
        if (result != android.app.Activity.RESULT_OK || data == null || data.getData() == null) { done.accept(""); return; }
        keepGrant(activity, data);
        final Uri tree = data.getData();
        final String name = data.getStringExtra(FilenFolder.EXTRA_FOLDER_NAME);
        activity.work(() -> {
            ScanShare share = activity.app().scanShare;
            if (!FilenPartner.trusted(activity, tree)) {
                SourceStore.releaseUnlessUsed(activity, tree, used -> activity.app().library.sources.byTree(used.toString()) != null);
                throw new IOException("That folder is not from Filen Files.");
            }
            share.setFolder(tree, name, activity.app().library.sources.partnerName());
            if (stack != null) share.setOn(stack, true);
            share.start();
            return true;
        }, ok -> done.accept(""));
    }

    // ---- the real folder ----------------------------------------------------------------------------------------------------------

    /**
     * A folder reached through a document provider. A file is written under a temporary name and renamed over the old one, so a reader never sees a
     * half-written PDF. Most providers cannot replace a file in one step, so an old file is deleted just before the rename (as the backup writer does).
     * Filen Files hands back a provisional "pending" document id while an upload runs: the old file is only deleted once the new one is listed.
     */
    static final class SafFolder implements Folder {
        private final ContentResolver resolver; private final Uri tree, folder;
        SafFolder(ContentResolver resolver, Uri tree) {
            this.resolver = resolver; this.tree = tree;
            this.folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        }
        private Uri find(String name) throws IOException {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor c = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (c == null) throw new IOException("The folder did not answer.");
                while (c.moveToNext()) if (name.equals(c.getString(1))) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
            } catch (SecurityException | IllegalArgumentException error) { throw new IOException("No access to the folder."); }
            return null;
        }
        @Override public void put(String name, File content) throws IOException {
            String tempName = name + ".part";
            Uri stale = find(tempName);
            if (stale != null) try { DocumentsContract.deleteDocument(resolver, stale); } catch (Exception ignored) { /* best effort */ }
            Uri temp;
            try { temp = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", tempName); } catch (Exception error) { throw new IOException("The folder does not accept new files."); }
            if (temp == null) throw new IOException("The folder does not accept new files.");
            try {
                try (OutputStream out = resolver.openOutputStream(temp, "w"); FileInputStream in = new FileInputStream(content)) {
                    if (out == null) throw new IOException("The folder could not be opened.");
                    byte[] buffer = new byte[1 << 16];
                    for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                }
                temp = landed(tempName, temp);
                Uri old = find(name);
                if (old != null) {
                    boolean gone;
                    try { gone = DocumentsContract.deleteDocument(resolver, old); } catch (Exception error) { gone = false; }
                    if (!gone) throw new IOException("The folder does not let Stack replace the file.");
                }
                Uri renamed;
                try { renamed = DocumentsContract.renameDocument(resolver, temp, name); } catch (Exception error) { renamed = null; }
                if (renamed == null) throw new IOException("The folder does not let Stack rename the file.");
            } catch (IOException failed) {
                try { Uri leftover = find(tempName); if (leftover != null) DocumentsContract.deleteDocument(resolver, leftover); } catch (Exception ignored) { /* nothing more to do */ }
                throw failed;
            }
        }
        private Uri landed(String tempName, Uri created) throws IOException {
            long until = android.os.SystemClock.elapsedRealtime() + 60_000;
            while (true) {
                Uri listed = find(tempName);
                if (listed != null && !DocumentsContract.getDocumentId(listed).contains("pending")) return listed;
                if (android.os.SystemClock.elapsedRealtime() >= until) { if (listed != null) return listed; throw new IOException("The folder has not finished saving the file."); }
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Stopped"); }
            }
        }
        @Override public Names list() throws IOException {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor c = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (c == null) throw new IOException("The folder did not answer.");
                android.os.Bundle extras = c.getExtras();
                boolean fresh = extras != null && extras.getBoolean(SourceReader.Saf.EXTRA_FRESH, false);
                String account = extras == null ? null : extras.getString(SourceReader.Saf.EXTRA_ACCOUNT);
                String legacy = extras == null ? null : extras.getString(SourceReader.Saf.EXTRA_ACCOUNT_LEGACY);
                List<String> names = new ArrayList<>();
                while (c.moveToNext()) if (!c.isNull(0)) names.add(c.getString(0));
                return new Names(fresh && account != null && !account.isEmpty(), account, legacy, names);
            } catch (SecurityException | IllegalArgumentException error) { throw new IOException("No access to the folder."); }
        }
        @Override public void delete(String name) throws IOException {
            Uri file = find(name);
            if (file == null) return;
            boolean gone;
            try { gone = DocumentsContract.deleteDocument(resolver, file); } catch (Exception error) { throw new IOException("The folder did not let Stack remove the file."); }
            if (!gone) throw new IOException("The folder did not let Stack remove the file.");
        }
    }
}
