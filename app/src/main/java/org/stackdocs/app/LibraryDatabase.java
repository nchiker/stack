package org.stackdocs.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;

final class LibraryDatabase extends SQLiteOpenHelper {
    static final int VERSION = 24;

    LibraryDatabase(Context context) { this(context, "library.db"); }

    /** A different file name exists only so a test can build and migrate a throwaway library next to the real one. */
    LibraryDatabase(Context context, String name) {
        super(context, name, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE documents (id TEXT PRIMARY KEY, title TEXT NOT NULL, "
                + "mime TEXT NOT NULL, added_at INTEGER NOT NULL, page_count INTEGER NOT NULL, "
                + "title_user_edited INTEGER NOT NULL DEFAULT 0, initial_page_count INTEGER NOT NULL, "
                + "source_scan_id TEXT UNIQUE)");
        db.execSQL("CREATE TABLE pages (id TEXT PRIMARY KEY, document_id TEXT NOT NULL "
                + "REFERENCES documents(id) ON DELETE CASCADE, position INTEGER NOT NULL, "
                + "source_page INTEGER NOT NULL, rotation INTEGER NOT NULL DEFAULT 0, "
                + "crop TEXT NOT NULL DEFAULT '', enhancement TEXT NOT NULL DEFAULT 'original', "
                + "ocr_text TEXT NOT NULL DEFAULT '', ocr_state TEXT NOT NULL DEFAULT 'pending', "
                + "ocr_engine TEXT NOT NULL DEFAULT '', language TEXT NOT NULL DEFAULT '', "
                + "source_file TEXT NOT NULL DEFAULT 'source', flatten INTEGER NOT NULL DEFAULT 0, "
                + "UNIQUE(document_id, position))");
        db.execSQL("CREATE TABLE stacks (id TEXT PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE "
                + "UNIQUE, color INTEGER NOT NULL, visible INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE TABLE document_stacks (document_id TEXT NOT NULL REFERENCES "
                + "documents(id) ON DELETE CASCADE, stack_id TEXT NOT NULL REFERENCES stacks(id) "
                + "ON DELETE CASCADE, PRIMARY KEY(document_id, stack_id))");
        db.execSQL("CREATE INDEX stack_documents ON document_stacks(stack_id)");
        db.execSQL("CREATE VIRTUAL TABLE search_index USING fts4(document_id, title, body, "
                + "notindexed=document_id, tokenize=unicode61)");
        db.execSQL("CREATE TABLE restore_commits (id TEXT PRIMARY KEY)");
        addOcrSchema(db);
        addSuggestionSchema(db, false);
        addP1Schema(db);
        addStackOrder(db);
        addSyncSchema(db);
        addTrash(db);
        addLinks(db);
        addLinkLifecycle(db);
        addLinkSync(db);
        addPartnerShare(db);
        addPartnerImport(db);
        addForeignSync(db);
        addRestoredLinks(db);
        addSources(db);
        addPartnerId(db);
        addScanShare(db);
        addSourcePages(db);
        addSourceCarry(db);
        String[] ids = {"bills", "banking", "ids", "house", "medical", "receipts", "starred"};
        String[] names = {"Bills", "Banking", "IDs", "House", "Medical", "Receipts", "Starred"};
        String[] colors = {"#DCEADB", "#E5E8FA", "#F7E6D2", "#F1DDE8", "#DCECF2", "#F7EDCA", "#E2E5E0"};
        for (int i = 0; i < ids.length; i++) {
            ContentValues values = new ContentValues();
            values.put("id", ids[i]);
            values.put("name", names[i]);
            values.put("color", Color.parseColor(colors[i]));
            values.put("sort_order", i);
            db.insertOrThrow("stacks", null, values);
        }
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1 || oldVersion > 23 || newVersion != VERSION) {
            throw new IllegalStateException("No migration from library version " + oldVersion);
        }
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE pages ADD COLUMN source_file TEXT NOT NULL DEFAULT 'source'");
            db.execSQL("ALTER TABLE documents ADD COLUMN initial_page_count INTEGER NOT NULL DEFAULT 0");
            db.execSQL("UPDATE documents SET initial_page_count=page_count");
            db.execSQL("ALTER TABLE documents ADD COLUMN source_scan_id TEXT");
            db.execSQL("CREATE UNIQUE INDEX scan_source ON documents(source_scan_id)");
        }
        if (oldVersion < 3) db.execSQL("ALTER TABLE pages ADD COLUMN flatten INTEGER NOT NULL DEFAULT 0");
        if (oldVersion < 4) addOcrSchema(db);
        if (oldVersion < 5) addSuggestionSchema(db, true);
        if (oldVersion < 6) db.execSQL("CREATE TABLE restore_commits (id TEXT PRIMARY KEY)");
        if (oldVersion < 7) addP1Schema(db);
        if (oldVersion < 8) addStackOrder(db);
        if (oldVersion < 9) addSyncSchema(db);
        if (oldVersion < 10) addTrash(db);
        if (oldVersion < 11) addLinks(db);
        if (oldVersion < 12) addLinkLifecycle(db);
        if (oldVersion < 13) addLinkSync(db);
        if (oldVersion < 14) addPartnerShare(db);
        if (oldVersion < 15) addPartnerImport(db);
        if (oldVersion < 16) addForeignSync(db);
        if (oldVersion < 17) addRestoredLinks(db);
        if (oldVersion < 18) addSources(db);
        if (oldVersion < 19) { retireQrSharing(db); addScanShare(db); }
        if (oldVersion < 20) addSourcePages(db);
        if (oldVersion < 21) addSourceCarry(db);
        if (oldVersion < 22) addSourceBudget(db);
        if (oldVersion < 23) { addScanShare(db); addPlanSignature(db); }   // the journal table (CREATE IF NOT EXISTS) and the plan signature
        if (oldVersion < 24) addPartnerId(db);                              // the partner's stable id, kept with the source
    }

    /**
     * Folder sharing replaces the QR pairing (docs/FOLDER_SHARING_DESIGN.md section 6). The partner's shared records (origin 'foreign') go with their
     * text, stacks and tags, the per-record sharing switch is cleared and the sharing log is emptied. The sync triggers see the deletes, so a paired
     * computer drops the records too. Own records are not touched. The preview files, the sealed partner keys and the old folder grants are removed
     * by {@link QrRetirement} when the app starts (they are not in the database). The columns stay, so older sync and backup files still read.
     */
    static void retireQrSharing(SQLiteDatabase db) {
        db.execSQL("DELETE FROM link_search WHERE record_id IN (SELECT id FROM link_records WHERE origin='foreign')");
        db.execSQL("DELETE FROM item_stacks WHERE item_kind='link' AND item_id IN (SELECT id FROM link_records WHERE origin='foreign')");
        db.execSQL("DELETE FROM item_tags WHERE item_kind='link' AND item_id IN (SELECT id FROM link_records WHERE origin='foreign')");
        db.execSQL("DELETE FROM link_records WHERE origin='foreign'");
        db.execSQL("UPDATE link_records SET partner_share=0 WHERE partner_share<>0");
        db.execSQL("DELETE FROM partner_share_log");
    }

    /**
     * Scan sharing (design section 1, Scans). `scan_share_stacks` is the per-stack switch ("Share with [partner]"); `scan_share_files` is what the
     * writer put into the shared Filen folder: one row per scan, with the file name used and a signature of the scan's pages and title, so a change
     * is noticed and a removed scan's file can be deleted by name. Local only: not synced, not in backups. No foreign key to documents on purpose:
     * a scan that is deleted must still be found here so its PDF is removed from the folder.
     */
    static void addScanShare(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS scan_share_stacks (stack_id TEXT PRIMARY KEY REFERENCES stacks(id) ON DELETE CASCADE, added_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS scan_share_files (document_id TEXT PRIMARY KEY, file_name TEXT NOT NULL, signature TEXT NOT NULL, written_at INTEGER NOT NULL DEFAULT 0)");
        // A name is written here BEFORE its upload and removed only once the row above exists or the delete in Filen is confirmed (state `uploading`):
        // what is left after a crash or a failed take-down is exactly what must still be removed from the shared folder.
        db.execSQL("CREATE TABLE IF NOT EXISTS scan_share_journal (file_name TEXT PRIMARY KEY, document_id TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'uploading', started_at INTEGER NOT NULL DEFAULT 0)");
    }

    /**
     * Pages of a PDF being read in chunks (docs/FOLDER_SHARING_DESIGN.md section 7): one row per page once the text layers are known. `state` is
     * `todo` (needs OCR), `ok` (text kept in `text`), `skipped` (three tries failed: "not read") or `capped` (the text cap was reached first);
     * `tries` counts the OCR tries the page was part of, committed before each hand-over; `total` is the page count. Rows are deleted when the file
     * gets its final answer or changes, and with the file. Local only.
     */
    static void addSourcePages(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS source_pages (file_id TEXT NOT NULL REFERENCES source_files(id) ON DELETE CASCADE, page INTEGER NOT NULL, "
                + "total INTEGER NOT NULL, state TEXT NOT NULL, tries INTEGER NOT NULL DEFAULT 0, text TEXT NOT NULL DEFAULT '', plan_sig TEXT NOT NULL DEFAULT '', PRIMARY KEY(file_id, page))");
    }

    /** `plan_sig` (DB 23): what the fetched content looked like when the plan was made (listing version, size, digest). A resume with other content starts over. */
    /** `sources.partner_id`: Filen's stable id of the partner contact a source was made for (empty when Filen did not send one). */
    static void addPartnerId(SQLiteDatabase db) {
        try (android.database.Cursor c = db.rawQuery("PRAGMA table_info(sources)", null)) {
            while (c.moveToNext()) if ("partner_id".equals(c.getString(1))) return;
        }
        db.execSQL("ALTER TABLE sources ADD COLUMN partner_id TEXT NOT NULL DEFAULT ''");
    }

    static void addPlanSignature(SQLiteDatabase db) {
        try (android.database.Cursor c = db.rawQuery("PRAGMA table_info(source_pages)", null)) {
            while (c.moveToNext()) if ("plan_sig".equals(c.getString(1))) return;
        }
        db.execSQL("ALTER TABLE source_pages ADD COLUMN plan_sig TEXT NOT NULL DEFAULT ''");
    }

    /**
     * Save-over carry-over that survives listing lag (docs/FOLDER_SHARING_INTERFACES.md I6). When a source file with stacks or tags is removed, the
     * marks wait here for one hour; a new file with the same parent and name in the same source takes them. Local only, additive.
     */
    static void addSourceCarry(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS source_carry (source_id TEXT NOT NULL, parent_doc_id TEXT NOT NULL, name TEXT NOT NULL, "
                + "stacks TEXT NOT NULL DEFAULT '', tags TEXT NOT NULL DEFAULT '', removed_at INTEGER NOT NULL, PRIMARY KEY(source_id, parent_doc_id, name))");
        // extract time spent on one content version of a file (PDF chunks): a file has a total budget, see SourceIndexer.TOTAL_BUDGET_MS
        addSourceBudget(db);
    }

    /** Extract time spent on one content version of a file (PDF chunks); see SourceIndexer.TOTAL_BUDGET_MS. Local only, additive. */
    static void addSourceBudget(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS source_budget (file_id TEXT PRIMARY KEY, ms INTEGER NOT NULL DEFAULT 0)");
    }

    /**
     * Folder sources (docs/FOLDER_SHARING_DESIGN.md section 5), additive. A source is a Filen folder tree Stack reads through a persisted READ grant.
     * `source_files` holds every file and folder seen in it (`is_dir`), `source_search` the names and text, and `item_stacks` / `item_tags` let
     * documents, link records and source files share one stack and tag mechanism (`item_kind` = document, link or source). `seen_scan` is the scan
     * number that last listed the row; `missing_since` is when a FRESH parent listing first omitted it (0 = present); `type_fingerprint` is
     * FileTypes.fingerprint() when the row was read. Local only: no sync triggers, not in backups or the library archive, not synced.
     */
    static void addSources(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sources (id TEXT PRIMARY KEY, kind TEXT NOT NULL, tree_uri TEXT NOT NULL UNIQUE, title TEXT NOT NULL, "
                + "partner_name TEXT NOT NULL DEFAULT '', account_id TEXT NOT NULL DEFAULT '', state TEXT NOT NULL DEFAULT 'new', "
                + "last_fresh_scan INTEGER NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT '', scan_seq INTEGER NOT NULL DEFAULT 0, "
                + "added_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS source_files (id TEXT PRIMARY KEY, source_id TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE, "
                + "provider_doc_id TEXT NOT NULL, parent_doc_id TEXT NOT NULL, path TEXT NOT NULL DEFAULT '', name TEXT NOT NULL, "
                + "mime TEXT NOT NULL DEFAULT '', size INTEGER NOT NULL DEFAULT 0, modified INTEGER NOT NULL DEFAULT 0, "
                + "version TEXT NOT NULL DEFAULT '', is_dir INTEGER NOT NULL DEFAULT 0, handler_id TEXT NOT NULL DEFAULT '', "
                + "handler_version INTEGER NOT NULL DEFAULT 0, type_fingerprint TEXT NOT NULL DEFAULT '', state TEXT NOT NULL DEFAULT 'pending', "
                + "reason TEXT NOT NULL DEFAULT '', text_sha TEXT NOT NULL DEFAULT '', preview_file TEXT NOT NULL DEFAULT '', "
                + "missing_since INTEGER NOT NULL DEFAULT 0, seen_scan INTEGER NOT NULL DEFAULT 0, first_scan INTEGER NOT NULL DEFAULT 0, "
                + "attempts INTEGER NOT NULL DEFAULT 0, added_at INTEGER NOT NULL DEFAULT 0, UNIQUE(source_id, provider_doc_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS source_files_state ON source_files(source_id, state)");
        db.execSQL("CREATE INDEX IF NOT EXISTS source_files_parent ON source_files(source_id, parent_doc_id)");
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS source_search USING fts4(file_id, title, body, notindexed=file_id, tokenize=unicode61)");
        db.execSQL("CREATE TABLE IF NOT EXISTS item_stacks (item_kind TEXT NOT NULL, item_id TEXT NOT NULL, stack_id TEXT NOT NULL REFERENCES stacks(id) ON DELETE CASCADE, "
                + "PRIMARY KEY(item_kind, item_id, stack_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS item_stacks_stack ON item_stacks(stack_id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS item_tags (item_kind TEXT NOT NULL, item_id TEXT NOT NULL, tag_id TEXT NOT NULL REFERENCES tags(id) ON DELETE CASCADE, "
                + "PRIMARY KEY(item_kind, item_id, tag_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS item_tags_tag ON item_tags(tag_id)");
    }

    /**
     * "Set up again" (docs/STACK_LINK_CONTRACT.md, Restore and setup). `restored` marks a link row that came back from a backup and whose
     * owner app has not confirmed its link_id yet: 1 = restored, not yet checked; 2 = checked and the owner does not know it (the
     * detail screen offers "Set up again" and the owner's next share is adopted into this row); 0 = an ordinary link. Local only: it is
     * not in the sync triggers, the backup or the link sync.
     */
    static void addRestoredLinks(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE link_records ADD COLUMN restored INTEGER NOT NULL DEFAULT 0");
    }

    /**
     * Partner sharing, sync to the computer (docs/STACK_LINK_SLICE7_DESIGN.md, S7-4). The three link sync triggers of schema 15 ignored foreign
     * rows; now that the partner's shared records are written into link cells (only for a computer that announced `link_foreign`, see
     * `LinkSync`), the triggers cover every row again, own and foreign, so an import, an update, Recently deleted ('unshared' or removed by
     * the person) and a delete for good all raise the link dirty flag. Nothing else changes: with no such computer, capture leaves foreign
     * rows alone. The column list and the IS NOT comparison of the update trigger are those of schema 15.
     */
    static void addForeignSync(SQLiteDatabase db) {
        db.execSQL("DROP TRIGGER IF EXISTS sync_link_records_insert");
        db.execSQL("DROP TRIGGER IF EXISTS sync_link_records_delete");
        db.execSQL("DROP TRIGGER IF EXISTS sync_link_records_update");
        String enabled = "(SELECT enabled=1 AND applying=0 FROM sync_control WHERE singleton=1)";
        String body = " BEGIN INSERT INTO sync_change(table_name,action) VALUES('link_records','%s'); "
                + "INSERT OR REPLACE INTO sync_meta(key,value) VALUES('link_dirty','1'); END";
        db.execSQL("CREATE TRIGGER sync_link_records_insert AFTER INSERT ON link_records WHEN " + enabled + String.format(java.util.Locale.ROOT, body, "INSERT"));
        db.execSQL("CREATE TRIGGER sync_link_records_delete AFTER DELETE ON link_records WHEN " + enabled + String.format(java.util.Locale.ROOT, body, "DELETE"));
        String[] synced = {"owner", "link_id", "kind", "title", "mime", "modified", "hash", "shared", "protected", "locators",
                "preview_file", "title_user_edited", "removed_reason", "origin", "shared_by", "foreign_generation"};
        StringBuilder changed = new StringBuilder("(OLD.state='trashed') IS NOT (NEW.state='trashed')");
        for (String column : synced) changed.append(" OR OLD.").append(column).append(" IS NOT NEW.").append(column);
        db.execSQL("CREATE TRIGGER sync_link_records_update AFTER UPDATE OF owner,link_id,kind,title,mime,modified,hash,shared,protected,"
                + "locators,state,preview_file,title_user_edited,removed_reason,origin,shared_by,foreign_generation ON link_records"
                + " WHEN " + enabled + " AND (" + changed + ")"
                + String.format(java.util.Locale.ROOT, body, "UPDATE"));
    }

    /**
     * Partner sharing, publish side (docs/STACK_LINK_SLICE7_DESIGN.md, S7-2). `partner_share` is Stack's own switch per link, separate from the owner
     * app's `shared` flag: 0 = off, 1 = shared, 2 = shared and the person confirmed that a protected link's text and preview are readable by the
     * partner. It is NOT in the sync triggers or the library archive, so it stays on this phone. `partner_share_log` remembers what was last
     * shared (state 'shared') and what stopped being shared (state 'removed', `since` = when), which becomes the 30-day tombstones. Local only.
     */
    static void addPartnerShare(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE link_records ADD COLUMN partner_share INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE TABLE partner_share_log (owner TEXT NOT NULL, link_id TEXT NOT NULL, state TEXT NOT NULL, since INTEGER NOT NULL, PRIMARY KEY(owner, link_id))");
    }

    /**
     * Partner sharing, import side (docs/STACK_LINK_SLICE7_DESIGN.md, S7-3). A record the partner shares with us is stored as a link record with
     * `origin='foreign'`, the partner's display name in `shared_by` and the archive's generation number in `foreign_generation` (an own record keeps the
     * defaults 'own', '' and 0). Foreign records are read-only copies: they are never checked with the owner app, and, until S7-4 adds them
     * deliberately, they stay out of the sync cells, the sync journal and every backup. The three sync triggers are therefore replaced by versions that
     * ignore foreign rows; the update trigger keeps its column list.
     */
    static void addPartnerImport(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE link_records ADD COLUMN origin TEXT NOT NULL DEFAULT 'own'");
        db.execSQL("ALTER TABLE link_records ADD COLUMN shared_by TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE link_records ADD COLUMN foreign_generation INTEGER NOT NULL DEFAULT 0");
        db.execSQL("DROP TRIGGER IF EXISTS sync_link_records_insert");
        db.execSQL("DROP TRIGGER IF EXISTS sync_link_records_delete");
        db.execSQL("DROP TRIGGER IF EXISTS sync_link_records_update");
        String enabled = "(SELECT enabled=1 AND applying=0 FROM sync_control WHERE singleton=1)";
        String body = " BEGIN INSERT INTO sync_change(table_name,action) VALUES('link_records','%s'); "
                + "INSERT OR REPLACE INTO sync_meta(key,value) VALUES('link_dirty','1'); END";
        db.execSQL("CREATE TRIGGER sync_link_records_insert AFTER INSERT ON link_records WHEN " + enabled + " AND NEW.origin='own'" + String.format(java.util.Locale.ROOT, body, "INSERT"));
        db.execSQL("CREATE TRIGGER sync_link_records_delete AFTER DELETE ON link_records WHEN " + enabled + " AND OLD.origin='own'" + String.format(java.util.Locale.ROOT, body, "DELETE"));
        String[] synced = {"owner", "link_id", "kind", "title", "mime", "modified", "hash", "shared", "protected", "locators",
                "preview_file", "title_user_edited", "removed_reason"};
        StringBuilder changed = new StringBuilder("(OLD.state='trashed') IS NOT (NEW.state='trashed')");
        for (String column : synced) changed.append(" OR OLD.").append(column).append(" IS NOT NEW.").append(column);
        db.execSQL("CREATE TRIGGER sync_link_records_update AFTER UPDATE OF owner,link_id,kind,title,mime,modified,hash,shared,protected,"
                + "locators,state,preview_file,title_user_edited,removed_reason ON link_records"
                + " WHEN " + enabled + " AND NEW.origin='own' AND OLD.origin='own' AND (" + changed + ")"
                + String.format(java.util.Locale.ROOT, body, "UPDATE"));
    }

    /**
     * Link sync, phone side (docs/STACK_LINK_SLICE6_DESIGN.md sections 7 and 15, item P1). Additive only, so every existing
     * library opens and migrates. `text_sha`/`text_len`/`preview_sha`/`preview_len` are the digests of the saved text (UTF-8
     * bytes of `link_search.body`) and preview file; they are reset whenever a publish changes the content and are verified by
     * length and hash before capture reuses them. `preview_pending` marks a synced preview whose file is not placed yet. `preview_mtime` is the preview file's modified time when
     * `preview_sha` was verified against it, so the check survives a restart (a different time means the file is hashed again).
     * `sync_link_projection` holds the link cells as last synced (one row per cell key, canonical JSON value); only link capture
     * and install read or write it, so the document projection in `sync_state` is never touched by links.
     * The triggers record a change only when something that is SYNCED changed (an IS NOT comparison, and trashed-ness for
     * `state`), so writes of only `checked_at`, `state_since`, `revision` or `received_at`, or a live/unreachable/missing
     * flip by LinkChecker, never fire. Each trigger also raises the `link_dirty` flag that makes capture run.
     */
    static void addLinkSync(SQLiteDatabase db) {
        for (String column : new String[]{"text_sha TEXT NOT NULL DEFAULT ''", "text_len INTEGER NOT NULL DEFAULT 0",
                "preview_sha TEXT NOT NULL DEFAULT ''", "preview_len INTEGER NOT NULL DEFAULT 0", "preview_pending INTEGER NOT NULL DEFAULT 0",
                "preview_mtime INTEGER NOT NULL DEFAULT 0"})
            db.execSQL("ALTER TABLE link_records ADD COLUMN " + column);
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_link_projection (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        String guard = " WHEN (SELECT enabled=1 AND applying=0 FROM sync_control WHERE singleton=1)";
        String body = " BEGIN INSERT INTO sync_change(table_name,action) VALUES('link_records','%s'); "
                + "INSERT OR REPLACE INTO sync_meta(key,value) VALUES('link_dirty','1'); END";
        db.execSQL("CREATE TRIGGER IF NOT EXISTS sync_link_records_insert AFTER INSERT ON link_records" + guard + String.format(java.util.Locale.ROOT, body, "INSERT"));
        db.execSQL("CREATE TRIGGER IF NOT EXISTS sync_link_records_delete AFTER DELETE ON link_records" + guard + String.format(java.util.Locale.ROOT, body, "DELETE"));
        String[] synced = {"owner", "link_id", "kind", "title", "mime", "modified", "hash", "shared", "protected", "locators",
                "preview_file", "title_user_edited", "removed_reason"};
        StringBuilder changed = new StringBuilder("(OLD.state='trashed') IS NOT (NEW.state='trashed')");
        for (String column : synced) changed.append(" OR OLD.").append(column).append(" IS NOT NEW.").append(column);
        db.execSQL("CREATE TRIGGER IF NOT EXISTS sync_link_records_update AFTER UPDATE OF owner,link_id,kind,title,mime,modified,hash,shared,protected,"
                + "locators,state,preview_file,title_user_edited,removed_reason ON link_records"
                + " WHEN (SELECT enabled=1 AND applying=0 FROM sync_control WHERE singleton=1) AND (" + changed + ")"
                + String.format(java.util.Locale.ROOT, body, "UPDATE"));
    }

    /**
     * Link records (docs/STACK_LINK_CONTRACT.md): documents owned by other apps. Deliberately NOT part of `documents`
     * and not captured by the sync triggers or exported by the archive, so existing backup, restore and sync are
     * untouched. `link_search` keeps the owner's extracted text (its `body`) for search and display.
     */
    static void addLinks(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE link_records (id TEXT PRIMARY KEY, owner TEXT NOT NULL, link_id TEXT NOT NULL, "
                + "kind TEXT NOT NULL, title TEXT NOT NULL, mime TEXT NOT NULL, modified INTEGER NOT NULL, "
                + "hash TEXT NOT NULL, shared INTEGER NOT NULL DEFAULT 0, protected INTEGER NOT NULL DEFAULT 0, "
                + "locators TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'live', preview_file TEXT NOT NULL DEFAULT '', "
                + "added_at INTEGER NOT NULL, received_at INTEGER NOT NULL, title_user_edited INTEGER NOT NULL DEFAULT 0, "
                + "UNIQUE(owner, link_id))");
        db.execSQL("CREATE VIRTUAL TABLE link_search USING fts4(record_id, title, body, notindexed=record_id, tokenize=unicode61)");
    }

    /**
     * Link lifecycle (slice 4). `state_since` is when the current state began (it drives both 30-day periods: missing, then
     * trashed); `revision` is bumped by every publish, removal and user action so a slow check cannot overwrite them;
     * `removed_reason` is why the record is trashed (`deleted`, `trashed`, `user`, `forgotten`). Local only.
     */
    static void addLinkLifecycle(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE link_records ADD COLUMN state_since INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE link_records ADD COLUMN checked_at INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE link_records ADD COLUMN revision INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE link_records ADD COLUMN removed_reason TEXT NOT NULL DEFAULT ''");
        db.execSQL("CREATE INDEX link_records_state ON link_records(state)");
    }

    /**
     * Recently deleted. Local only: it has no sync triggers, so the desktop protocol never sees it, and it is not in
     * backups. A trashed document stays in every synced table until it is deleted for good; the trigger forgets the
     * trash entry whenever the document goes away (permanent delete, or a deletion arriving from the computer).
     */
    static void addTrash(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trash (document_id TEXT PRIMARY KEY, deleted_at INTEGER NOT NULL)");
        db.execSQL("CREATE TRIGGER trash_cleanup AFTER DELETE ON documents BEGIN DELETE FROM trash WHERE document_id=OLD.id; END");
    }

    /** Raw before/after rows survive a process stop even if no exchange is prepared. */
    private static void addSyncSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sync_control (singleton INTEGER PRIMARY KEY CHECK(singleton=1), enabled INTEGER NOT NULL DEFAULT 0, applying INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("INSERT INTO sync_control(singleton) VALUES(1)");
        db.execSQL("CREATE TABLE sync_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE sync_state (singleton INTEGER PRIMARY KEY CHECK(singleton=1), state TEXT NOT NULL, projection TEXT NOT NULL, baseline TEXT NOT NULL, journal_cursor INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE sync_receipts (peer TEXT PRIMARY KEY, frontier TEXT NOT NULL)");
        db.execSQL("CREATE TABLE sync_change (sequence INTEGER PRIMARY KEY AUTOINCREMENT, table_name TEXT NOT NULL, action TEXT NOT NULL)");
        String[][] tables = {
                {"documents", join(LibraryArchive.DOC)}, {"pages", join(LibraryArchive.PAGE)},
                {"stacks", join(LibraryArchive.STACK)}, {"document_stacks", join(LibraryArchive.MEMBER)},
                {"suggestions", join(LibraryArchive.SUGGESTION)}, {"suggestion_choices", join(LibraryArchive.CHOICE)},
                {"details", join(LibraryArchive.DETAIL)}, {"tags", join(LibraryArchive.TAG)},
                {"document_tags", join(LibraryArchive.DOCUMENT_TAG)}
        };
        for (String[] table : tables) {
            String name = table[0], columns = table[1];
            db.execSQL("CREATE TABLE sync_raw_" + name + " AS SELECT CAST(NULL AS INTEGER) sequence, CAST(NULL AS TEXT) side," + columns + " FROM " + name + " WHERE 0");
            db.execSQL("CREATE INDEX sync_raw_" + name + "_sequence ON sync_raw_" + name + "(sequence)");
            for (String action : new String[]{"INSERT", "UPDATE", "DELETE"}) {
                StringBuilder body = new StringBuilder("INSERT INTO sync_change(table_name,action) VALUES('" + name + "','" + action + "');");
                if (!action.equals("INSERT")) body.append(rawInsert(name, columns, "OLD", "old"));
                if (!action.equals("DELETE")) body.append(rawInsert(name, columns, "NEW", "new"));
                db.execSQL("CREATE TRIGGER sync_" + name + "_" + action.toLowerCase(java.util.Locale.ROOT)
                        + " AFTER " + action + " ON " + name
                        + " WHEN (SELECT enabled=1 AND applying=0 FROM sync_control WHERE singleton=1) BEGIN "
                        + body + " END");
            }
        }
    }

    private static String rawInsert(String table, String columns, String side, String label) {
        StringBuilder values = new StringBuilder();
        for (String column : columns.split(",")) {
            if (values.length() > 0) values.append(',');
            values.append(side).append('.').append(column);
        }
        return "INSERT INTO sync_raw_" + table + "(sequence,side," + columns
                + ") VALUES((SELECT max(sequence) FROM sync_change),'" + label + "'," + values + ");";
    }

    private static String join(String[] columns) { return android.text.TextUtils.join(",", columns); }

    private static void addStackOrder(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE stacks ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0");
        // Historical lists used rowid, including hidden categories. Keep that exact order.
        db.execSQL("UPDATE stacks SET sort_order=(SELECT COUNT(*) FROM stacks preceding WHERE preceding.rowid<stacks.rowid)");
        db.execSQL("CREATE TRIGGER suggestion_stack_rename AFTER UPDATE OF name ON stacks WHEN OLD.name<>NEW.name BEGIN "
                + "UPDATE suggestions SET status='pending',generation=generation+1,claim=''; END");
    }

    private static void addP1Schema(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE documents ADD COLUMN notes TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE documents ADD COLUMN notes_revision INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE documents ADD COLUMN metadata_revision INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE TABLE details (document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE, field_id TEXT NOT NULL, kind TEXT NOT NULL, label TEXT NOT NULL, value TEXT NOT NULL, qualifier TEXT NOT NULL, origin TEXT NOT NULL, state TEXT NOT NULL, evidence TEXT NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(document_id,field_id))");
        db.execSQL("CREATE TABLE tags (id TEXT PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE UNIQUE)");
        db.execSQL("CREATE TABLE document_tags (document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE, tag_id TEXT NOT NULL REFERENCES tags(id), field_id TEXT NOT NULL, PRIMARY KEY(document_id,field_id))");
        db.execSQL("CREATE TABLE editor_drafts (document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE, field_id TEXT NOT NULL, value TEXT NOT NULL, base_revision INTEGER NOT NULL, PRIMARY KEY(document_id,field_id))");
        db.execSQL("CREATE TABLE metadata_work (document_id TEXT PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE, status TEXT NOT NULL DEFAULT 'pending', claim TEXT NOT NULL DEFAULT '', input TEXT NOT NULL DEFAULT '', rule TEXT NOT NULL DEFAULT '', payload TEXT NOT NULL DEFAULT '[]', attempts INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("INSERT INTO metadata_work(document_id) SELECT id FROM documents");
        db.execSQL("CREATE TRIGGER metadata_insert AFTER INSERT ON documents BEGIN INSERT INTO metadata_work(document_id) VALUES(NEW.id); END");
        db.execSQL("CREATE TRIGGER metadata_dirty AFTER UPDATE OF generation ON suggestions BEGIN UPDATE metadata_work SET status='pending',claim='',attempts=0 WHERE document_id=NEW.document_id; END");
        db.execSQL("CREATE TRIGGER metadata_decision AFTER UPDATE OF metadata_revision ON documents BEGIN UPDATE metadata_work SET status='pending',claim='',attempts=0 WHERE document_id=NEW.id; END");
        db.execSQL("CREATE TABLE operations (id TEXT PRIMARY KEY, kind TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending', created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE operation_items (operation_id TEXT NOT NULL REFERENCES operations(id) ON DELETE CASCADE, position INTEGER NOT NULL, source TEXT NOT NULL, document_id TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending', error TEXT NOT NULL DEFAULT '', PRIMARY KEY(operation_id,position))");
        db.execSQL("CREATE TABLE photo_history (source TEXT PRIMARY KEY, digest TEXT NOT NULL DEFAULT '', state TEXT NOT NULL, document_id TEXT NOT NULL DEFAULT '')");
    }

    private static void addSuggestionSchema(SQLiteDatabase db, boolean legacy) {
        db.execSQL("ALTER TABLE documents ADD COLUMN title_origin TEXT NOT NULL DEFAULT 'default'");
        db.execSQL("ALTER TABLE documents ADD COLUMN filing_origin TEXT NOT NULL DEFAULT 'default'");
        db.execSQL("ALTER TABLE documents ADD COLUMN title_decision INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE documents ADD COLUMN filing_decision INTEGER NOT NULL DEFAULT 0");
        if (legacy) db.execSQL("UPDATE documents SET title_origin='legacy',filing_origin='legacy'");
        db.execSQL("CREATE TABLE suggestions (document_id TEXT PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE, "
                + "generation INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'pending', claim TEXT NOT NULL DEFAULT '', "
                + "input TEXT NOT NULL DEFAULT '', evidence TEXT NOT NULL DEFAULT '', rule TEXT NOT NULL DEFAULT '', "
                + "title_revision INTEGER NOT NULL DEFAULT 0, filing_revision INTEGER NOT NULL DEFAULT 0, "
                + "payload TEXT NOT NULL DEFAULT '{}', title_state TEXT NOT NULL DEFAULT 'offered', filing_state TEXT NOT NULL DEFAULT 'offered')");
        db.execSQL("CREATE TABLE suggestion_choices (document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE, "
                + "evidence TEXT NOT NULL, field TEXT NOT NULL, choice TEXT NOT NULL, value TEXT NOT NULL, "
                + "PRIMARY KEY(document_id,evidence,field))");
        db.execSQL("INSERT INTO suggestions(document_id) SELECT id FROM documents");
        db.execSQL("CREATE TRIGGER suggestion_document_insert AFTER INSERT ON documents BEGIN "
                + "INSERT INTO suggestions(document_id) VALUES(NEW.id); END");
        String dirty = "UPDATE suggestions SET status='pending',generation=generation+1,claim='' WHERE document_id=";
        db.execSQL("CREATE TRIGGER suggestion_decision AFTER UPDATE OF title_decision,filing_decision ON documents BEGIN "
                + dirty + "NEW.id; END");
        db.execSQL("CREATE TRIGGER suggestion_page_insert AFTER INSERT ON pages BEGIN " + dirty + "NEW.document_id; END");
        db.execSQL("CREATE TRIGGER suggestion_page_delete AFTER DELETE ON pages BEGIN " + dirty + "OLD.document_id; END");
        db.execSQL("CREATE TRIGGER suggestion_page_update AFTER UPDATE OF position,content_revision,text_revision,ocr_text,ocr_config,text_config,ocr_state,ocr_claim,ocr_attempts ON pages BEGIN "
                + dirty + "NEW.document_id; END");
        db.execSQL("CREATE TRIGGER suggestion_stack_delete AFTER DELETE ON stacks BEGIN "
                + "UPDATE suggestions SET status='pending',generation=generation+1,claim=''; END");
        db.execSQL("CREATE TRIGGER suggestion_stack_insert AFTER INSERT ON stacks BEGIN "
                + "UPDATE suggestions SET status='pending',generation=generation+1,claim=''; END");
    }

    private static void addOcrSchema(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE pages ADD COLUMN content_revision INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE pages ADD COLUMN text_revision INTEGER NOT NULL DEFAULT -1");
        db.execSQL("ALTER TABLE pages ADD COLUMN ocr_attempts INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE pages ADD COLUMN ocr_claim TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE pages ADD COLUMN ocr_error TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE pages ADD COLUMN ocr_config TEXT NOT NULL DEFAULT '" + OcrModel.CONFIG + "'");
        db.execSQL("ALTER TABLE pages ADD COLUMN text_config TEXT NOT NULL DEFAULT ''");
        db.execSQL("UPDATE pages SET text_revision=content_revision WHERE ocr_text<>''");
        // One additive backfill. Do not discard previously stored text or change page edits.
        db.execSQL("UPDATE pages SET ocr_state='pending',ocr_attempts=0,ocr_claim=''");
        db.execSQL("CREATE INDEX ocr_work ON pages(ocr_state,ocr_attempts)");
        db.execSQL("CREATE VIRTUAL TABLE page_search USING fts4(page_id,document_id,title,body, "
                + "notindexed=page_id,notindexed=document_id,tokenize=unicode61)");
        db.execSQL("INSERT INTO page_search(page_id,document_id,title,body) "
                + "SELECT p.id,p.document_id,d.title,p.ocr_text FROM pages p JOIN documents d ON d.id=p.document_id WHERE p.text_revision=p.content_revision");
    }
}
