package org.stackdocs.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;

final class LibraryDatabase extends SQLiteOpenHelper {
    LibraryDatabase(Context context) {
        super(context, "library.db", null, 9);
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
        if (oldVersion < 1 || oldVersion > 8 || newVersion != 9) {
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
        addSyncSchema(db);
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
