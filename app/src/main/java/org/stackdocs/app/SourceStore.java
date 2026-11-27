package org.stackdocs.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Folder sources (docs/FOLDER_SHARING_DESIGN.md sections 1, 5; interfaces I4, I6): the Filen folder trees Stack reads through persisted READ
 * grants, the files found in them, their text for search, and removal of a source. The scan itself is {@link SourceIndexer}. Like the other
 * stores, every method is meant to run off the main thread. Source rows are local only: not backed up, not synced.
 *
 * Screens use: {@link #add}, {@link #list}, {@link #progress}, {@link #remove}, {@link #search}, {@link #files}.
 */
final class SourceStore {
    /** Filen Files' documents provider: the only authority a source may name. */
    static final String AUTHORITY = "app.filenfiles.documents";

    static final String OWN = "own", PARTNER = "partner", SHARED_BY_ME = "shared_by_me", SCAN_SHARE = "scan_share";
    /** Source states. `waiting` has a reason (locked, battery); `unreachable`, `signed_out`, `account_changed` and `access_lost` never remove anything. */
    static final String NEW = "new", OK = "ok", SCANNING = "scanning", WAITING = "waiting", UNREACHABLE = "unreachable",
            SIGNED_OUT = "signed_out", ACCOUNT_CHANGED = "account_changed", ACCESS_LOST = "access_lost";
    /** File states (design section 5). */
    static final String PENDING = "pending", INDEXED = "indexed", UNSUPPORTED = "unsupported", UNREADABLE = "unreadable", PROTECTED = "protected";

    /** One source as the screens need it. */
    static final class Source {
        final String id, kind, treeUri, title, partnerName, accountId, state, reason;
        /** Filen's stable id of the partner contact this source was made for; "" when unknown. */
        final String partnerId;
        final long lastFreshScan, scanSeq;
        Source(String id, String kind, String treeUri, String title, String partnerName, String accountId, String state, String reason, long lastFreshScan, long scanSeq) {
            this(id, kind, treeUri, title, partnerName, accountId, state, reason, lastFreshScan, scanSeq, "");
        }
        Source(String id, String kind, String treeUri, String title, String partnerName, String accountId, String state, String reason, long lastFreshScan, long scanSeq, String partnerId) {
            this.partnerId = partnerId == null ? "" : partnerId;
            this.id = id; this.kind = kind; this.treeUri = treeUri; this.title = title; this.partnerName = partnerName; this.accountId = accountId;
            this.state = state; this.reason = reason; this.lastFreshScan = lastFreshScan; this.scanSeq = scanSeq;
        }
        Uri tree() { return Uri.parse(treeUri); }
        String rootDocId() { return DocumentsContract.getTreeDocumentId(tree()); }
    }

    /** One file or folder row of a source. */
    static final class FileRow {
        final String id, sourceId, docId, parentDocId, path, name, mime, version, handlerId, fingerprint, state, reason;
        final long size, modified, missingSince, seenScan, firstScan;
        final int handlerVersion, attempts;
        final boolean dir;
        FileRow(String id, String sourceId, String docId, String parentDocId, String path, String name, String mime, String version, String handlerId,
                String fingerprint, String state, String reason, long size, long modified, long missingSince, long seenScan, long firstScan,
                int handlerVersion, int attempts, boolean dir) {
            this.id = id; this.sourceId = sourceId; this.docId = docId; this.parentDocId = parentDocId; this.path = path; this.name = name; this.mime = mime;
            this.version = version; this.handlerId = handlerId; this.fingerprint = fingerprint; this.state = state; this.reason = reason; this.size = size;
            this.modified = modified; this.missingSince = missingSince; this.seenScan = seenScan; this.firstScan = firstScan; this.handlerVersion = handlerVersion;
            this.attempts = attempts; this.dir = dir;
        }
    }

    /** How far a source is: for the Folders screen ("reading 12 of 57"). `files` counts files only, not folders. */
    static final class Progress {
        final String state, reason;
        final int files, indexed, pending, unsupported, unreadable, protectedFiles, missing;
        final long lastFreshScan;
        /** The PDF being read in chunks: pages finished and page count (0 = none). The Folders screen says "Reading page 12 of 100". */
        final int pageDone, pageTotal;
        Progress(String state, String reason, int files, int indexed, int pending, int unsupported, int unreadable, int protectedFiles, int missing, long lastFreshScan) {
            this(state, reason, files, indexed, pending, unsupported, unreadable, protectedFiles, missing, lastFreshScan, 0, 0);
        }
        Progress(String state, String reason, int files, int indexed, int pending, int unsupported, int unreadable, int protectedFiles, int missing, long lastFreshScan, int pageDone, int pageTotal) {
            this.pageDone = pageDone; this.pageTotal = pageTotal;
            this.state = state; this.reason = reason; this.files = files; this.indexed = indexed; this.pending = pending; this.unsupported = unsupported;
            this.unreadable = unreadable; this.protectedFiles = protectedFiles; this.missing = missing; this.lastFreshScan = lastFreshScan;
        }
        /** Files done (read or given a final answer). */
        int done() { return files - pending; }
    }

    /** One source file found by a search, marked with its source kind and partner name. */
    static final class Hit {
        final String id, sourceId, docId, name, path, mime, state, reason, kind, partnerName, sourceTitle, treeUri, excerpt;
        /** An own file that Filen lists as shared by this account (also seen through "Shared by me"). */
        final boolean sharedByMe;
        Hit(String id, String sourceId, String docId, String name, String path, String mime, String state, String reason, String kind, String partnerName,
            String sourceTitle, String treeUri, String excerpt) { this(id, sourceId, docId, name, path, mime, state, reason, kind, partnerName, sourceTitle, treeUri, excerpt, false); }
        Hit(String id, String sourceId, String docId, String name, String path, String mime, String state, String reason, String kind, String partnerName,
            String sourceTitle, String treeUri, String excerpt, boolean sharedByMe) {
            this.sharedByMe = sharedByMe;
            this.id = id; this.sourceId = sourceId; this.docId = docId; this.name = name; this.path = path; this.mime = mime; this.state = state; this.reason = reason;
            this.kind = kind; this.partnerName = partnerName; this.sourceTitle = sourceTitle; this.treeUri = treeUri; this.excerpt = excerpt;
        }
        /** The Filen UUID of the file (the part of its document id after the colon), or null. */
        String filenUuid() { return uuidOf(docId); }
    }

    /** Item kinds of item_stacks / item_tags. */
    static final String ITEM_SOURCE = "source", ITEM_DOCUMENT = "document", ITEM_LINK = "link";

    private final Context context;
    private final Supplier<SQLiteDatabase> database;
    private final File directory;

    SourceStore(Context context, Supplier<SQLiteDatabase> database, File directory) {
        this.context = context.getApplicationContext(); this.database = database; this.directory = directory;
    }

    SQLiteDatabase db() { return database.get(); }
    Context context() { return context; }
    File previewDir(String sourceId) { return new File(directory, sourceId); }

    // ---- grants -------------------------------------------------------------------------------------------------------------------

    /** True for a tree URI of Filen Files' documents provider and nothing else. */
    static boolean validTree(Uri uri) {
        try { return uri != null && "content".equals(uri.getScheme()) && AUTHORITY.equals(uri.getAuthority()) && DocumentsContract.isTreeUri(uri) && !DocumentsContract.getTreeDocumentId(uri).isEmpty(); }
        catch (RuntimeException invalid) { return false; }
    }

    /** A check replaces the answer to "does Stack hold the grant" (a real grant needs Filen's picker). Never set by the app. */
    volatile java.util.function.Predicate<Uri> heldOverride;

    /** True when Android still lists a persisted READ grant for exactly this tree. */
    boolean granted(Uri tree) {
        java.util.function.Predicate<Uri> check = heldOverride;
        if (check != null) return check.test(tree);
        try {
            for (android.content.UriPermission permission : context.getContentResolver().getPersistedUriPermissions())
                if (permission.isReadPermission() && permission.getUri().equals(tree)) return true;
        } catch (RuntimeException unknown) { return false; }
        return false;
    }

    /** True when Android still lists a persisted WRITE grant for exactly this tree (the scan-share folder). */
    boolean writable(Uri tree) {
        java.util.function.Predicate<Uri> check = heldOverride;
        if (check != null) return check.test(tree);
        try {
            for (android.content.UriPermission permission : context.getContentResolver().getPersistedUriPermissions())
                if (permission.isWritePermission() && permission.getUri().equals(tree)) return true;
        } catch (RuntimeException unknown) { return false; }
        return false;
    }

    // ---- add, list, remove --------------------------------------------------------------------------------------------------------

    /**
     * Adds a source for a tree Filen Files granted (from a folder picker or from Filen's partner setup) and keeps the READ grant. The same tree twice
     * gives the same source. Throws IOException with plain words when the tree is not Filen's or no grant is held.
     */
    Source add(String kind, Uri tree, String title, String partnerName) throws IOException {
        if (!OWN.equals(kind) && !PARTNER.equals(kind) && !SHARED_BY_ME.equals(kind) && !SCAN_SHARE.equals(kind)) throw new IOException("Unknown kind of folder.");
        if (!validTree(tree)) throw new IOException("Only folders from Filen Files can be added.");
        Source already = byTree(tree.toString());
        if (already != null && !already.kind.equals(kind)) throw new IOException(ALREADY_OTHER);   // the kind of a tree never changes
        // The folder for shared scans is the one tree Stack also writes to (interfaces I4 F11); every other source is read only.
        int flags = SCAN_SHARE.equals(kind) ? Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION : Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try { context.getContentResolver().takePersistableUriPermission(tree, flags); }
        catch (SecurityException | IllegalArgumentException refused) { if (!granted(tree)) throw new IOException("Stack was not given access to this folder."); }
        if (!granted(tree)) throw new IOException("Stack was not given access to this folder.");
        if (SCAN_SHARE.equals(kind) && !writable(tree)) throw new IOException("Stack was not given permission to save into this folder.");
        SQLiteDatabase db = db();
        String name = title == null || title.trim().isEmpty() ? "Folder" : title.trim();
        String partner = partnerName == null ? "" : partnerName.trim();
        db.beginTransaction();
        try {
            Source existing = byTree(db, tree.toString());
            if (existing != null) {
                // The kind of a tree never changes: the same kind is "already added", another kind is refused (a shared or partner folder must not
                // become an ordinary folder, or the other way round, because someone picked it again).
                if (!existing.kind.equals(kind)) throw new IOException(ALREADY_OTHER);
                ContentValues values = new ContentValues();
                values.put("title", name); values.put("partner_name", partner);
                db.update("sources", values, "id=?", new String[]{existing.id});
                db.setTransactionSuccessful();
                return get(existing.id);
            }
            ContentValues values = new ContentValues();
            String id = UUID.randomUUID().toString();
            values.put("id", id); values.put("kind", kind); values.put("tree_uri", tree.toString()); values.put("title", name);
            values.put("partner_name", partner); values.put("state", NEW); values.put("added_at", Clock.now());
            db.insertOrThrow("sources", null, values);
            db.setTransactionSuccessful();
            return get(id);
        } finally { db.endTransaction(); }
    }

    static final String ALREADY_ADDED = "This folder is already added.";
    static final String ALREADY_OTHER = "This folder is already used for sharing and can't be added here.";
    static final String NOT_OWN = "Choose a folder from your own Filen Drive.";

    /**
     * Why a folder the person picked in the Folders screen cannot be added, in plain words, or null. Nothing here changes a source: the scan-share
     * folder and the partner's folders are never added (or turned into another kind) from this picker.
     */
    String pickProblem(Uri tree) {
        if (!validTree(tree)) return "Only folders from Filen Files can be added.";
        Source existing = byTree(tree.toString());
        if (existing != null) return OWN.equals(existing.kind) ? ALREADY_ADDED : ALREADY_OTHER;
        String doc = DocumentsContract.getTreeDocumentId(tree);
        if (doc.startsWith("filen-in:") || doc.startsWith("filen-out:")) return NOT_OWN;
        return null;
    }

    List<Source> list() {
        List<Source> out = new ArrayList<>();
        try (Cursor c = db().rawQuery(SOURCE_SQL + " ORDER BY added_at, id", null)) { while (c.moveToNext()) out.add(source(c)); }
        return out;
    }

    Source get(String id) {
        try (Cursor c = db().rawQuery(SOURCE_SQL + " WHERE id=?", new String[]{id})) { return c.moveToFirst() ? source(c) : null; }
    }

    private static final String SOURCE_SQL = "SELECT id,kind,tree_uri,title,partner_name,account_id,state,reason,last_fresh_scan,scan_seq,partner_id FROM sources";

    private static Source byTree(SQLiteDatabase db, String tree) {
        try (Cursor c = db.rawQuery(SOURCE_SQL + " WHERE tree_uri=?", new String[]{tree})) { return c.moveToFirst() ? source(c) : null; }
    }

    Source byTree(String tree) { return byTree(db(), tree); }

    private static Source source(Cursor c) {
        return new Source(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getLong(8), c.getLong(9), c.getString(10));
    }

    /** Removes a source with all its rows, text, previews, stacks and tags, and gives the READ grant back when no other source uses the tree. */
    void remove(String id) {
        Source source = get(id);
        if (source == null) return;
        SQLiteDatabase db = db();
        List<String> rowIds = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id FROM source_files WHERE source_id=?", new String[]{id})) { while (c.moveToNext()) rowIds.add(c.getString(0)); }
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM source_search WHERE file_id IN (SELECT id FROM source_files WHERE source_id=?)", new Object[]{id});
            db.execSQL("DELETE FROM item_stacks WHERE item_kind='source' AND item_id IN (SELECT id FROM source_files WHERE source_id=?)", new Object[]{id});
            db.execSQL("DELETE FROM item_tags WHERE item_kind='source' AND item_id IN (SELECT id FROM source_files WHERE source_id=?)", new Object[]{id});
            db.delete("source_files", "source_id=?", new String[]{id});
            db.delete("source_carry", "source_id=?", new String[]{id});
            db.execSQL("DELETE FROM source_budget WHERE file_id NOT IN (SELECT id FROM source_files)");
            db.delete("sources", "id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        deleteTree(previewDir(id));
        clearCaches(id, rowIds);
        releaseUnlessUsed(source.tree());
    }

    /** Removes this source's copies from the two cache folders: the files being read (named by row id) and the viewer's copies (named by source id). */
    private void clearCaches(String sourceId, List<String> rowIds) {
        File reading = new File(context.getCacheDir(), "source-read");
        for (String rowId : rowIds) { new File(reading, rowId).delete(); new File(reading, rowId + ".answer").delete(); }
        File[] viewing = new File(context.getCacheDir(), "source-view").listFiles();
        if (viewing != null) for (File f : viewing) if (f.getName().startsWith(sourceId + "_")) f.delete();
    }

    /** Gives back the persisted READ and WRITE grant of a tree when no source, no automatic backup and no pending removal of shared copies uses it any more. */
    void releaseUnlessUsed(Uri tree) { releaseUnlessUsed(context, tree, used -> byTree(used.toString()) != null); }

    /**
     * THE one place a persisted folder grant is given back (a source removed or re-picked, the old scan folder, the backup turned off, the QR retirement,
     * the library erase). The grant stays when the automatic backup folder is this tree, when removing shared copies from it is still to do, or when
     * `usedBySource` says a folder source holds it (an answer that throws counts as "used": when in doubt the grant is kept).
     */
    static void releaseUnlessUsed(Context context, Uri tree, java.util.function.Predicate<Uri> usedBySource) {
        if (tree == null) return;
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        try { Uri backup = AutoBackup.tree(app); if (backup != null && backup.equals(tree)) return; } catch (RuntimeException unknown) { return; }
        try { if (app.getSharedPreferences("scan_share_old", Context.MODE_PRIVATE).contains(tree.toString())) return; } catch (RuntimeException unknown) { return; }
        try { if (usedBySource != null && usedBySource.test(tree)) return; } catch (RuntimeException unknown) { return; }
        java.util.function.Consumer<Uri> probe = releaseProbe;
        if (probe != null) { probe.accept(tree); return; }
        try { app.getContentResolver().releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (RuntimeException alreadyGone) { /* not held */ }
    }

    /** Checks only: sees every release instead of the system doing it. */
    static volatile java.util.function.Consumer<Uri> releaseProbe;

    /** As above for callers without a store: the application's own sources decide (no sources available: the grant is kept). */
    static void releaseUnlessUsed(Context context, Uri tree) {
        Context app = context.getApplicationContext();
        final SourceStore sources;
        SourceStore found = null;
        try { if (app instanceof StackApplication) found = ((StackApplication) app).library.sources; } catch (RuntimeException unavailable) { /* kept */ }
        sources = found;
        releaseUnlessUsed(context, tree, used -> sources == null || sources.byTree(used.toString()) != null);
    }

    /**
     * The scan-share folder was chosen again (Fix, or another partner): the one `scan_share` source takes the new tree in place. The old tree's grant is
     * given back unless `keepOldGrant` (its shared copies could not be removed yet). Returns the source.
     */
    Source replaceScanShare(String id, Uri tree, String title, String partnerName, boolean keepOldGrant) throws IOException {
        Source old = get(id);
        if (old == null || !SCAN_SHARE.equals(old.kind)) throw new IOException("There is no shared scan folder to replace.");
        if (!validTree(tree)) throw new IOException("Only folders from Filen Files can be added.");
        Source other = byTree(tree.toString());
        if (other != null && !other.id.equals(id)) throw new IOException(ALREADY_OTHER);
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try { context.getContentResolver().takePersistableUriPermission(tree, flags); }
        catch (SecurityException | IllegalArgumentException refused) { if (!granted(tree)) throw new IOException("Stack was not given access to this folder."); }
        if (!granted(tree) || !writable(tree)) throw new IOException("Stack was not given permission to save into this folder.");
        ContentValues values = new ContentValues();
        values.put("tree_uri", tree.toString()); values.put("title", title == null || title.trim().isEmpty() ? "Folder" : title.trim());
        values.put("partner_name", partnerName == null ? "" : partnerName.trim()); values.put("account_id", ""); values.put("partner_id", ""); values.put("state", NEW); values.put("reason", "");
        db().update("sources", values, "id=?", new String[]{id});
        if (!old.treeUri.equals(tree.toString()) && !keepOldGrant) releaseUnlessUsed(old.tree());
        return get(id);
    }

    /** Keeps Filen's stable partner id with a source ("" leaves it as it is). */
    void setPartnerId(String sourceId, String partnerId) {
        if (partnerId == null || partnerId.trim().isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("partner_id", partnerId.trim());
        db().update("sources", values, "id=?", new String[]{sourceId});
    }

    /** The partner id kept with the partner source, "" when none. */
    String partnerId() {
        Source partner = first(PARTNER);
        return partner == null ? "" : partner.partnerId;
    }

    /** The first source of a kind, or null. */
    Source first(String kind) {
        for (Source source : list()) if (kind.equals(source.kind)) return source;
        return null;
    }

    /** The partner's name when a partner source exists (the Filen display name Filen gave at setup), else null. */
    String partnerName() {
        Source partner = first(PARTNER);
        return partner == null ? null : partner.partnerName;
    }

    /**
     * "Use this account" on a source Filen now answers for as another account: forgets the account the source was read under and starts it over.
     * Nothing is removed here; the next scan reads the tree again under the signed-in account and the removal rules (I6) apply as for any scan.
     */
    void useCurrentAccount(String id) {
        ContentValues values = new ContentValues();
        values.put("account_id", ""); values.put("state", NEW); values.put("reason", "");
        db().update("sources", values, "id=?", new String[]{id});
    }

    /** Re-picked folder after "Access lost": the same source (its files, stacks and tags stay) with a new tree. Returns the source, or null when it is gone. */
    Source replaceTree(String id, Uri tree) throws IOException {
        Source old = get(id);
        if (old == null) return null;
        if (!validTree(tree)) throw new IOException("Only folders from Filen Files can be added.");
        if (!old.rootDocId().equals(DocumentsContract.getTreeDocumentId(tree))) throw new IOException("Choose the same folder again.");
        try { context.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException | IllegalArgumentException refused) { if (!granted(tree)) throw new IOException("Stack was not given access to this folder."); }
        if (!granted(tree)) throw new IOException("Stack was not given access to this folder.");
        Source other = byTree(tree.toString());
        if (other != null && !other.id.equals(id)) throw new IOException("This folder is already in the list.");
        ContentValues values = new ContentValues();
        values.put("tree_uri", tree.toString()); values.put("state", NEW); values.put("reason", "");
        db().update("sources", values, "id=?", new String[]{id});
        if (!old.treeUri.equals(tree.toString())) releaseUnlessUsed(old.tree());
        return get(id);
    }

    // ---- progress and files -------------------------------------------------------------------------------------------------------

    Progress progress(String id) {
        Source source = get(id);
        if (source == null) return null;
        int files = 0, indexed = 0, pending = 0, unsupported = 0, unreadable = 0, protectedFiles = 0, missing = 0;
        try (Cursor c = db().rawQuery("SELECT state, COUNT(*), SUM(missing_since>0) FROM source_files WHERE source_id=? AND is_dir=0 GROUP BY state", new String[]{id})) {
            while (c.moveToNext()) {
                int n = c.getInt(1); files += n; missing += c.getInt(2);
                switch (c.getString(0)) {
                    case INDEXED: indexed += n; break;
                    case PENDING: pending += n; break;
                    case UNSUPPORTED: unsupported += n; break;
                    case UNREADABLE: unreadable += n; break;
                    case PROTECTED: protectedFiles += n; break;
                    default: break;
                }
            }
        }
        int pageDone = 0, pageTotal = 0;
        if (pending > 0) {
            try (Cursor c = db().rawQuery("SELECT MAX(p.total), SUM(p.state<>'todo') FROM source_pages p JOIN source_files f ON f.id=p.file_id WHERE f.source_id=? AND f.state=? "
                    + "GROUP BY p.file_id HAVING SUM(p.state='todo')>0 LIMIT 1", new String[]{id, PENDING})) {
                if (c.moveToFirst()) { pageTotal = c.getInt(0); pageDone = c.getInt(1); }
            }
        }
        return new Progress(source.state, source.reason, files, indexed, pending, unsupported, unreadable, protectedFiles, missing, source.lastFreshScan, pageDone, pageTotal);
    }

    /** The files (not folders) of a source that are present (not waiting for a removal confirmation), by path. */
    List<FileRow> files(String sourceId) {
        List<FileRow> out = new ArrayList<>();
        try (Cursor c = db().rawQuery(FILE_SQL + " WHERE source_id=? AND is_dir=0 AND missing_since=0 ORDER BY path COLLATE NOCASE, id", new String[]{sourceId})) { while (c.moveToNext()) out.add(file(c)); }
        return out;
    }

    /** Reason kept on a "Shared by me" file that an own folder already covers (same Filen UUID): it has no text of its own. */
    static final String COVERED = "Read through your own folder.";
    /** True SQL condition: file `f` has the same Filen UUID as a present file of an own source. */
    private static final String COVERED_SQL = "EXISTS (SELECT 1 FROM source_files o JOIN sources os ON os.id=o.source_id WHERE os.kind='own' AND o.is_dir=0 AND o.missing_since=0 AND "
            + "o.provider_doc_id='filen:'||substr(f.provider_doc_id, instr(f.provider_doc_id, ':') + 1))";

    /** The pending files of a "Shared by me" source that an own source covers: they are not read a second time. */
    List<FileRow> coveredPending(String sourceId) {
        List<FileRow> out = new ArrayList<>();
        try (Cursor c = db().rawQuery(FILE_SQL + " WHERE source_id=? AND is_dir=0 AND state=? AND missing_since=0 AND id IN (SELECT f.id FROM source_files f WHERE f.source_id=? AND " + COVERED_SQL + ")",
                new String[]{sourceId, PENDING, sourceId})) { while (c.moveToNext()) out.add(file(c)); }
        return out;
    }

    /** Covered files whose own copy is gone become pending again (their text was never read). Returns how many. */
    int releaseUncovered(String sourceId) {
        SQLiteDatabase db = db();
        db.execSQL("UPDATE source_files SET state=?, reason='' WHERE source_id=? AND is_dir=0 AND state=? AND reason=? AND id NOT IN (SELECT f.id FROM source_files f WHERE f.source_id=? AND " + COVERED_SQL + ")",
                new Object[]{PENDING, sourceId, INDEXED, COVERED, sourceId});
        try (Cursor c = db.rawQuery("SELECT changes()", null)) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }

    FileRow file(String id) {
        try (Cursor c = db().rawQuery(FILE_SQL + " WHERE id=?", new String[]{id})) { return c.moveToFirst() ? file(c) : null; }
    }

    /** Every row (files and folders) of a source by provider document id; the scan's working copy. */
    java.util.Map<String, FileRow> rows(String sourceId) {
        java.util.Map<String, FileRow> out = new java.util.HashMap<>();
        try (Cursor c = db().rawQuery(FILE_SQL + " WHERE source_id=?", new String[]{sourceId})) { while (c.moveToNext()) { FileRow r = file(c); out.put(r.docId, r); } }
        return out;
    }

    private static final String FILE_SQL = "SELECT id,source_id,provider_doc_id,parent_doc_id,path,name,mime,version,handler_id,type_fingerprint,state,reason,size,modified,"
            + "missing_since,seen_scan,first_scan,handler_version,attempts,is_dir FROM source_files";

    private static FileRow file(Cursor c) {
        return new FileRow(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8),
                c.getString(9), c.getString(10), c.getString(11), c.getLong(12), c.getLong(13), c.getLong(14), c.getLong(15), c.getLong(16), c.getInt(17), c.getInt(18), c.getInt(19) == 1);
    }

    String text(String fileId) {
        try (Cursor c = db().rawQuery("SELECT body FROM source_search WHERE file_id=?", new String[]{fileId})) { return c.moveToFirst() ? c.getString(0) : ""; }
    }

    // ---- stacks and tags ----------------------------------------------------------------------------------------------------------

    /** Puts an item (document, link record or source file) in a stack. */
    void addToStack(String itemKind, String itemId, String stackId) {
        db().execSQL("INSERT OR IGNORE INTO item_stacks(item_kind,item_id,stack_id) VALUES(?,?,?)", new Object[]{itemKind, itemId, stackId});
    }

    void removeFromStack(String itemKind, String itemId, String stackId) {
        db().execSQL("DELETE FROM item_stacks WHERE item_kind=? AND item_id=? AND stack_id=?", new Object[]{itemKind, itemId, stackId});
    }

    List<String> stacksOf(String itemKind, String itemId) {
        List<String> out = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT stack_id FROM item_stacks WHERE item_kind=? AND item_id=? ORDER BY stack_id", new String[]{itemKind, itemId})) { while (c.moveToNext()) out.add(c.getString(0)); }
        return out;
    }

    void addTag(String itemKind, String itemId, String tagId) {
        db().execSQL("INSERT OR IGNORE INTO item_tags(item_kind,item_id,tag_id) VALUES(?,?,?)", new Object[]{itemKind, itemId, tagId});
    }

    List<String> tagsOf(String itemKind, String itemId) {
        List<String> out = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT tag_id FROM item_tags WHERE item_kind=? AND item_id=? ORDER BY tag_id", new String[]{itemKind, itemId})) { while (c.moveToNext()) out.add(c.getString(0)); }
        return out;
    }

    // ---- search -------------------------------------------------------------------------------------------------------------------

    /**
     * Source files whose name or text match `query`, newest sources first is not promised: by name. Files waiting for a removal confirmation are hidden.
     * `links` are the link records the same search already found: a source file with the same Filen UUID as one of them is left out, so the person sees
     * one result (the link record's locator `filen` carries the UUID).
     */
    List<Hit> search(String query, Collection<LinkStore.Link> links) { return search(query, links, null, null, null); }

    /** The Filen UUID of a document id, as SQL (the part after the first colon). */
    private static final String UUID_SQL = "substr(%s.provider_doc_id, instr(%s.provider_doc_id, ':') + 1)";
    private static String uuidSql(String alias) { return String.format(UUID_SQL, alias, alias); }

    /**
     * The same search, limited to the members of a stack (`stackId`, and also `alsoStackId`) and/or a tag (`tagId`): source files are members through
     * `item_stacks` / `item_tags`. With a filter and no query every member is listed (by name). One row per Filen UUID: an own source's row wins over
     * the same file seen through "Shared by me", and carries {@link Hit#sharedByMe} when Filen lists it as shared.
     */
    List<Hit> search(String query, Collection<LinkStore.Link> links, String stackId, String alsoStackId, String tagId) {
        boolean searching = query != null && !query.trim().isEmpty();
        String match = searching ? LibraryRepository.searchTerms(query) : "";
        boolean filtered = stackId != null || alsoStackId != null || tagId != null;
        if (searching ? match.isEmpty() : !filtered) return Collections.emptyList();
        Set<String> linked = filenUuids(links);
        List<String> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT f.id,f.source_id,f.provider_doc_id,f.name,f.path,f.mime,f.state,f.reason,s.kind,s.partner_name,s.title,s.tree_uri,");
        sql.append(searching ? "snippet(source_search,'','',' … ',2,18)" : "''");
        sql.append(", EXISTS (SELECT 1 FROM source_files x JOIN sources xs ON xs.id=x.source_id WHERE xs.kind='shared_by_me' AND x.is_dir=0 AND x.missing_since=0 AND ")
           .append(uuidSql("x")).append("=").append(uuidSql("f")).append(") FROM ");
        if (searching) { sql.append("source_search JOIN source_files f ON f.id=source_search.file_id "); } else sql.append("source_files f ");
        sql.append("JOIN sources s ON s.id=f.source_id WHERE f.is_dir=0 AND f.missing_since=0 ");
        if (searching) { sql.append("AND source_search MATCH ? "); args.add(match); }
        if (stackId != null) { sql.append("AND EXISTS (SELECT 1 FROM item_stacks m WHERE m.item_kind='source' AND m.item_id=f.id AND m.stack_id=?) "); args.add(stackId); }
        if (alsoStackId != null) { sql.append("AND EXISTS (SELECT 1 FROM item_stacks m2 WHERE m2.item_kind='source' AND m2.item_id=f.id AND m2.stack_id=?) "); args.add(alsoStackId); }
        if (tagId != null) { sql.append("AND EXISTS (SELECT 1 FROM item_tags t WHERE t.item_kind='source' AND t.item_id=f.id AND t.tag_id=?) "); args.add(tagId); }
        sql.append("ORDER BY f.name COLLATE NOCASE, f.id LIMIT 600");
        List<Hit> found = new ArrayList<>();
        try (Cursor c = db().rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                Hit hit = new Hit(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7),
                        c.getString(8), c.getString(9), c.getString(10), c.getString(11), c.getString(12), c.getInt(13) != 0 && OWN.equals(c.getString(8)));
                String uuid = hit.filenUuid();
                if (uuid != null && linked.contains(uuid)) continue;
                found.add(hit);
            }
        }
        // One row per Filen UUID; the own source's row is preferred (it has the text, and the share menu line).
        java.util.Map<String, Integer> byUuid = new java.util.HashMap<>();
        List<Hit> out = new ArrayList<>();
        for (Hit hit : found) {
            String uuid = hit.filenUuid();
            Integer at = uuid == null ? null : byUuid.get(uuid);
            if (at == null) { if (uuid != null) byUuid.put(uuid, out.size()); out.add(hit); }
            else if (OWN.equals(hit.kind) && !OWN.equals(out.get(at).kind)) out.set(at, hit);
        }
        return out.size() > 300 ? new ArrayList<>(out.subList(0, 300)) : out;
    }

    /** SQL for the number of source files in stack `s` (one per Filen UUID), for the stack tiles; see {@link LibraryRepository#stacks}. */
    static String memberCountSql(String stackExpr) {
        return "(SELECT COUNT(DISTINCT " + uuidSql("f") + ") FROM item_stacks m JOIN source_files f ON f.id=m.item_id "
                + "WHERE m.item_kind='source' AND f.is_dir=0 AND f.missing_since=0 AND m.stack_id=" + stackExpr + ")";
    }

    static Set<String> filenUuids(Collection<LinkStore.Link> links) {
        Set<String> out = new HashSet<>();
        if (links == null) return out;
        for (LinkStore.Link link : links) {
            try {
                org.json.JSONArray array = new org.json.JSONArray(link.locators);
                for (int i = 0; i < array.length(); i++) {
                    org.json.JSONObject locator = array.getJSONObject(i);
                    if ("filen".equals(locator.optString("type")) && !locator.optString("uuid").isEmpty()) out.add(locator.getString("uuid").toLowerCase(java.util.Locale.ROOT));
                }
            } catch (org.json.JSONException | RuntimeException unreadable) { /* a record without readable locators has no UUID to match */ }
        }
        return out;
    }

    /** The UUID part of a Filen document id (`filen:`, `filen-in:`, `filen-out:` + uuid), lower case; null for a root or another shape. */
    static String uuidOf(String docId) {
        if (docId == null) return null;
        int colon = docId.indexOf(':');
        if (colon < 0) return null;
        String tail = docId.substring(colon + 1);
        return tail.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") ? tail.toLowerCase(java.util.Locale.ROOT) : null;
    }

    // ---- rows (used by the scan) --------------------------------------------------------------------------------------------------

    void setState(String sourceId, String state, String reason) {
        ContentValues values = new ContentValues();
        values.put("state", state); values.put("reason", reason == null ? "" : reason);
        db().update("sources", values, "id=?", new String[]{sourceId});
    }

    void setAccount(String sourceId, String account) {
        ContentValues values = new ContentValues();
        values.put("account_id", account);
        db().update("sources", values, "id=?", new String[]{sourceId});
    }

    long nextScan(String sourceId) {
        db().execSQL("UPDATE sources SET scan_seq=scan_seq+1 WHERE id=?", new Object[]{sourceId});
        try (Cursor c = db().rawQuery("SELECT scan_seq FROM sources WHERE id=?", new String[]{sourceId})) { return c.moveToFirst() ? c.getLong(0) : 0; }
    }

    void setFreshScan(String sourceId, long when) {
        db().execSQL("UPDATE sources SET last_fresh_scan=? WHERE id=?", new Object[]{when, sourceId});
    }

    // ---- PDF pages read in chunks (SourceIndexer.readPdf) -------------------------------------------------------------------------

    /** Chunks of this many pages; a page that has already been part of a failed chunk is tried alone. */
    static final int CHUNK_PAGES = 4;
    /** A page is tried this many times (counted before each hand-over); then it is skipped ("not read"). */
    static final int PAGE_TRIES = 3;

    /** The page count of the PDF's plan, 0 when there is none yet. */
    int pageTotal(String fileId) {
        try (Cursor c = db().rawQuery("SELECT MAX(total) FROM source_pages WHERE file_id=?", new String[]{fileId})) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }

    /** What the content looked like when the plan was made, "" when there is no plan. */
    String planSignature(String fileId) {
        try (Cursor c = db().rawQuery("SELECT plan_sig FROM source_pages WHERE file_id=? LIMIT 1", new String[]{fileId})) { return c.moveToFirst() ? c.getString(0) : ""; }
    }

    /** Records the plan: a page with a text layer is done, one without waits for OCR. `layers` has one entry per page. */
    void planPages(String fileId, List<String> layers, String signature) {
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM source_pages WHERE file_id=?", new Object[]{fileId});
            int total = layers.size();
            for (int i = 0; i < total; i++) {
                String layer = layers.get(i);
                db.execSQL("INSERT INTO source_pages(file_id,page,total,state,text,plan_sig) VALUES(?,?,?,?,?,?)", new Object[]{fileId, i, total, layer == null ? "todo" : "ok", layer == null ? "" : layer, signature == null ? "" : signature});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** The next pages to OCR, in order: up to {@link #CHUNK_PAGES}, or one when the first has failed before. Pages out of tries are skipped here. Empty = none left. */
    int[] nextChunk(String fileId) {
        SQLiteDatabase db = db();
        db.execSQL("UPDATE source_pages SET state='skipped' WHERE file_id=? AND state='todo' AND tries>=?", new Object[]{fileId, PAGE_TRIES});
        List<int[]> rows = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT page, tries FROM source_pages WHERE file_id=? AND state='todo' ORDER BY page LIMIT ?", new String[]{fileId, String.valueOf(CHUNK_PAGES)})) {
            while (c.moveToNext()) rows.add(new int[]{c.getInt(0), c.getInt(1)});
        }
        if (rows.isEmpty()) return new int[0];
        int n = rows.get(0)[1] > 0 ? 1 : CHUNK_PAGES;
        for (int i = 0; i < Math.min(n, rows.size()); i++) if (i > 0 && rows.get(i)[1] > 0) { n = i; break; }
        n = Math.min(n, rows.size());
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = rows.get(i)[0];
        return out;
    }

    void addTries(String fileId, int[] pages, int delta) {
        for (int page : pages) db().execSQL("UPDATE source_pages SET tries=MAX(0,tries+?) WHERE file_id=? AND page=?", new Object[]{delta, fileId, page});
    }

    void savePages(String fileId, int[] pages, List<String> texts) {
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            for (int i = 0; i < pages.length; i++)
                db.execSQL("UPDATE source_pages SET state='ok', text=? WHERE file_id=? AND page=?", new Object[]{texts.get(i) == null ? "" : texts.get(i), fileId, pages[i]});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    int pageChars(String fileId) {
        try (Cursor c = db().rawQuery("SELECT COALESCE(SUM(LENGTH(text)),0) FROM source_pages WHERE file_id=?", new String[]{fileId})) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }

    /** The text cap was reached: the pages not read yet are left out. */
    void capPages(String fileId) { db().execSQL("UPDATE source_pages SET state='capped' WHERE file_id=? AND state='todo'", new Object[]{fileId}); }

    /** How many pages ended in a state (`skipped`, `capped`). */
    int pagesIn(String fileId, String state) {
        try (Cursor c = db().rawQuery("SELECT COUNT(*) FROM source_pages WHERE file_id=? AND state=?", new String[]{fileId, state})) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }

    /** The text of the pages that were read, in page order. */
    String pagesText(String fileId) {
        StringBuilder text = new StringBuilder();
        try (Cursor c = db().rawQuery("SELECT text FROM source_pages WHERE file_id=? AND state='ok' ORDER BY page", new String[]{fileId})) {
            while (c.moveToNext()) { if (text.length() > 0) text.append('\n'); text.append(c.getString(0)); }
        }
        return text.toString();
    }

    void clearPages(String fileId) { db().execSQL("DELETE FROM source_pages WHERE file_id=?", new Object[]{fileId}); db().execSQL("DELETE FROM source_budget WHERE file_id=?", new Object[]{fileId}); }

    /** Extract time already spent on this content version of the file. */
    long budgetUsed(String fileId) {
        try (Cursor c = db().rawQuery("SELECT ms FROM source_budget WHERE file_id=?", new String[]{fileId})) { return c.moveToFirst() ? c.getLong(0) : 0; }
    }

    void addBudget(String fileId, long ms) {
        db().execSQL("INSERT OR IGNORE INTO source_budget(file_id,ms) VALUES(?,0)", new Object[]{fileId});
        db().execSQL("UPDATE source_budget SET ms=ms+? WHERE file_id=?", new Object[]{Math.max(0, ms), fileId});
    }

    /** The pages not read yet are given up (the file's time budget is spent). */
    void skipRemaining(String fileId) { db().execSQL("UPDATE source_pages SET state='skipped' WHERE file_id=? AND state='todo'", new Object[]{fileId}); }

    /** A scan that stopped part way (the app was killed) is only waiting: its rows are still pending in the database. */
    void recoverInterrupted() {
        db().execSQL("UPDATE sources SET state=?, reason='' WHERE state=?", new Object[]{WAITING, SCANNING});
    }

    void indexName(String fileId, String title, String body) {
        SQLiteDatabase db = db();
        db.execSQL("DELETE FROM source_search WHERE file_id=?", new Object[]{fileId});
        db.execSQL("INSERT INTO source_search(file_id,title,body) VALUES(?,?,?)", new Object[]{fileId, title, body == null ? "" : body});
    }

    /** Removes one file or folder row: text, stacks, tags, preview. Only the removal rules of {@link SourceIndexer} call this. */
    void removeFile(FileRow row) {
        SQLiteDatabase db = db();
        if (!row.dir) keepMarks(row);
        db.execSQL("DELETE FROM source_search WHERE file_id=?", new Object[]{row.id});
        db.execSQL("DELETE FROM source_budget WHERE file_id=?", new Object[]{row.id});
        db.execSQL("DELETE FROM item_stacks WHERE item_kind='source' AND item_id=?", new Object[]{row.id});
        db.execSQL("DELETE FROM item_tags WHERE item_kind='source' AND item_id=?", new Object[]{row.id});
        db.delete("source_files", "id=?", new String[]{row.id});
        new File(previewDir(row.sourceId), row.id + ".png").delete();
    }

    /** Save-over carry-over lifetime when a listing lags: the old id is gone and the new one is not listed yet. */
    static final long CARRY_MS = 60L * 60 * 1000;

    /** Keeps the stacks and tags of a removed file for {@link #CARRY_MS}, unless a live file of the same parent and name already has them. */
    private void keepMarks(FileRow row) {
        SQLiteDatabase db = db();
        List<String> stacks = stacksOf(ITEM_SOURCE, row.id), tags = tagsOf(ITEM_SOURCE, row.id);
        if (stacks.isEmpty() && tags.isEmpty()) return;
        try (Cursor c = db.rawQuery("SELECT 1 FROM source_files WHERE source_id=? AND parent_doc_id=? AND name=? AND is_dir=0 AND id<>? AND missing_since=0",
                new String[]{row.sourceId, row.parentDocId, row.name, row.id})) { if (c.moveToFirst()) return; }
        db.execSQL("INSERT OR REPLACE INTO source_carry(source_id,parent_doc_id,name,stacks,tags,removed_at) VALUES(?,?,?,?,?,?)",
                new Object[]{row.sourceId, row.parentDocId, row.name, String.join("\n", stacks), String.join("\n", tags), Clock.now()});
    }

    /** A new file appeared: it takes the waiting stacks and tags of a same-named file removed from the same folder within the hour. */
    void claimCarry(String sourceId, String parentDocId, String name, String newId) {
        SQLiteDatabase db = db();
        long now = Clock.now();
        db.execSQL("DELETE FROM source_carry WHERE removed_at<?", new Object[]{now - CARRY_MS});
        String stacks = null, tags = null;
        try (Cursor c = db.rawQuery("SELECT stacks,tags FROM source_carry WHERE source_id=? AND parent_doc_id=? AND name=?", new String[]{sourceId, parentDocId, name})) {
            if (c.moveToFirst()) { stacks = c.getString(0); tags = c.getString(1); }
        }
        if (stacks == null) return;
        for (String s : stacks.split("\n")) if (!s.isEmpty()) db.execSQL("INSERT OR IGNORE INTO item_stacks(item_kind,item_id,stack_id) SELECT 'source',?,id FROM stacks WHERE id=?", new Object[]{newId, s});
        for (String t : tags.split("\n")) if (!t.isEmpty()) db.execSQL("INSERT OR IGNORE INTO item_tags(item_kind,item_id,tag_id) SELECT 'source',?,id FROM tags WHERE id=?", new Object[]{newId, t});
        db.execSQL("DELETE FROM source_carry WHERE source_id=? AND parent_doc_id=? AND name=?", new Object[]{sourceId, parentDocId, name});
    }

    /** Copies the stacks and tags of one item to another (a save-over gave the file a new id). */
    void carryOver(String fromId, String toId) {
        SQLiteDatabase db = db();
        db.execSQL("INSERT OR IGNORE INTO item_stacks(item_kind,item_id,stack_id) SELECT 'source',?,stack_id FROM item_stacks WHERE item_kind='source' AND item_id=?", new Object[]{toId, fromId});
        db.execSQL("INSERT OR IGNORE INTO item_tags(item_kind,item_id,tag_id) SELECT 'source',?,tag_id FROM item_tags WHERE item_kind='source' AND item_id=?", new Object[]{toId, fromId});
    }

    private static void deleteTree(File file) {
        File[] kids = file.listFiles();
        if (kids != null) for (File kid : kids) deleteTree(kid);
        file.delete();
    }
}
