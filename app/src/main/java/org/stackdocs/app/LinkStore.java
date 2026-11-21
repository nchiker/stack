package org.stackdocs.app;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Link records (docs/STACK_LINK_CONTRACT.md): the cached text, preview and locators of documents that other apps own.
 * Kept apart from `documents` so backup, restore and sync are unaffected in slice 2. Like the repository, every
 * method is meant to run on the application's single IO executor. Nothing here touches the owner's file.
 */
final class LinkStore {
    /** One stored record as the screens need it. */
    static final class Link {
        final String id, owner, linkId, kind, title, mime, hash, locators, state, excerpt;
        final long modified, addedAt;
        final boolean shared, isProtected, hasPreview;
        Link(String id, String owner, String linkId, String kind, String title, String mime, String hash, String locators, String state,
             String excerpt, long modified, long addedAt, boolean shared, boolean isProtected, boolean hasPreview) {
            this.id = id; this.owner = owner; this.linkId = linkId; this.kind = kind; this.title = title; this.mime = mime; this.hash = hash;
            this.locators = locators; this.state = state; this.excerpt = excerpt; this.modified = modified; this.addedAt = addedAt;
            this.shared = shared; this.isProtected = isProtected; this.hasPreview = hasPreview;
        }
    }

    /** What a publish did: the local id, whether it was new, whether text and preview were replaced, and any other record with the same bytes. */
    static final class Result {
        final String id; final boolean created, contentChanged; final String duplicateOf;
        Result(String id, boolean created, boolean contentChanged, String duplicateOf) { this.id = id; this.created = created; this.contentChanged = contentChanged; this.duplicateOf = duplicateOf; }
    }

    private static final String COLUMNS = "id,owner,link_id,kind,title,mime,hash,locators,state,modified,added_at,shared,protected,preview_file";
    private final Supplier<SQLiteDatabase> database;
    private final File directory;

    /** `directory` holds the cached previews (one `<id>.pdf` each); it is created on first use. */
    LinkStore(Supplier<SQLiteDatabase> database, File directory) { this.database = database; this.directory = directory; }

    /**
     * Creates or updates the record for (owner, link_id). The hash decides what is replaced: a different hash replaces
     * the text and preview, the same hash keeps them (an exact repeat), and small fields (locators, flags, modified, kind,
     * mime) always follow the owner. A title the user edited is kept. `previewFile` is consumed (moved or deleted).
     */
    Result publish(LinkRecord record, String text, File previewFile) throws IOException {
        LinkRecord.checkText(text);
        SQLiteDatabase db = database.get();
        String id = null, storedHash = null, storedPreview = "";
        boolean titleEdited = false;
        try (Cursor c = db.rawQuery("SELECT id,hash,preview_file,title_user_edited FROM link_records WHERE owner=? AND link_id=?", new String[]{record.owner, record.linkId})) {
            if (c.moveToFirst()) { id = c.getString(0); storedHash = c.getString(1); storedPreview = c.getString(2); titleEdited = c.getInt(3) != 0; }
        }
        boolean created = id == null;
        boolean contentChanged = created || !record.hash.equals(storedHash);
        if (created) id = UUID.randomUUID().toString();
        String previewName = contentChanged ? "" : storedPreview;
        if (contentChanged) {
            new File(directory, id + ".rendering").delete();
            // The file is placed before the database commit: a stop in between leaves a new preview under the old hash,
            // and the owner's next publish (new hash) simply replaces it again.
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create the link folder.");
            File target = new File(directory, id + ".pdf");
            if (previewFile != null) {
                if (!previewFile.renameTo(target)) { copy(previewFile, target); previewFile.delete(); }
                previewName = target.getName();
            } else if (target.exists() && !target.delete()) throw new IOException("Cannot remove the old preview.");
        } else if (previewFile != null) previewFile.delete();

        String duplicateOf = null;
        try (Cursor c = db.rawQuery("SELECT id FROM link_records WHERE hash=? AND NOT (owner=? AND link_id=?) LIMIT 1", new String[]{record.hash, record.owner, record.linkId})) {
            if (c.moveToFirst()) duplicateOf = c.getString(0);
        }
        long now = Clock.now();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("kind", record.kind); values.put("mime", record.mime); values.put("modified", record.modified);
            values.put("shared", record.shared ? 1 : 0); values.put("protected", record.isProtected ? 1 : 0);
            values.put("locators", locatorsJson(record)); values.put("received_at", now);
            if (created || !titleEdited) values.put("title", record.title);
            if (contentChanged) { values.put("hash", record.hash); values.put("preview_file", previewName); }
            if (created) {
                values.put("id", id); values.put("owner", record.owner); values.put("link_id", record.linkId); values.put("added_at", now);
                db.insertOrThrow("link_records", null, values);
            } else db.update("link_records", values, "id=?", new String[]{id});
            if (contentChanged || !titleEdited) {
                String title = created || !titleEdited ? record.title : null;
                if (title == null) try (Cursor c = db.rawQuery("SELECT title FROM link_records WHERE id=?", new String[]{id})) { c.moveToFirst(); title = c.getString(0); }
                String body = contentChanged ? text : body(db, id);
                db.execSQL("DELETE FROM link_search WHERE record_id=?", new Object[]{id});
                db.execSQL("INSERT INTO link_search(record_id,title,body) VALUES(?,?,?)", new Object[]{id, title, body});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return new Result(id, created, contentChanged, duplicateOf);
    }

    /** Newest first. A blank query lists everything; otherwise title and text are searched like documents are. */
    List<Link> list(String query) {
        SQLiteDatabase db = database.get();
        String match = LibraryRepository.searchTerms(query);
        boolean searching = query != null && !query.trim().isEmpty();
        if (searching && match.isEmpty()) return Collections.emptyList();
        String sql = "SELECT " + prefixed(COLUMNS) + ", " + (searching ? "snippet(link_search, '', '', ' … ', 2, 18)" : "''")
            + " FROM link_records r " + (searching ? "JOIN link_search ON link_search.record_id=r.id " : "")
            + (searching ? "WHERE link_search MATCH ? " : "") + "ORDER BY r.added_at DESC, r.id";
        List<Link> result = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, searching ? new String[]{match} : new String[0])) {
            while (c.moveToNext()) result.add(row(c, c.getString(14)));
        }
        return result;
    }

    Link get(String id) {
        try (Cursor c = database.get().rawQuery("SELECT " + COLUMNS + ", '' FROM link_records WHERE id=?", new String[]{id})) {
            return c.moveToFirst() ? row(c, "") : null;
        }
    }

    /** The owner's extracted text, for the detail screen. */
    String text(String id) { return body(database.get(), id); }

    /** The cached preview PDF, or null if the record has none. */
    File preview(String id) {
        File file = new File(directory, id + ".pdf");
        return file.isFile() ? file : null;
    }

    /**
     * Rendering a preview runs PDFium inside Stack. The marker is written BEFORE the attempt and removed only after it
     * succeeds, so a crash or a refusal leaves it behind and the same file is never tried again. A new preview clears it.
     */
    boolean previewFailed(String id) { return new File(directory, id + ".rendering").exists(); }
    void previewAttempt(String id) throws IOException { if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create the link folder."); new File(directory, id + ".rendering").createNewFile(); }
    void previewSucceeded(String id) { new File(directory, id + ".rendering").delete(); }

    /** Removes the record and its cached copies. The owner's file is never touched. */
    void delete(String id) {
        SQLiteDatabase db = database.get();
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM link_search WHERE record_id=?", new Object[]{id});
            db.execSQL("DELETE FROM link_records WHERE id=?", new Object[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        new File(directory, id + ".pdf").delete();
        new File(directory, id + ".rendering").delete();
    }

    /** The user's own title; later publishes from the owner will not overwrite it. */
    void rename(String id, String title) {
        SQLiteDatabase db = database.get();
        db.beginTransaction();
        try {
            db.execSQL("UPDATE link_records SET title=?, title_user_edited=1 WHERE id=?", new Object[]{title, id});
            db.execSQL("UPDATE link_search SET title=? WHERE record_id=?", new Object[]{title, id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static String body(SQLiteDatabase db, String id) {
        try (Cursor c = db.rawQuery("SELECT body FROM link_search WHERE record_id=?", new String[]{id})) { return c.moveToFirst() ? c.getString(0) : ""; }
    }

    private Link row(Cursor c, String excerpt) {
        String id = c.getString(0);
        return new Link(id, c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8), excerpt,
            c.getLong(9), c.getLong(10), c.getInt(11) != 0, c.getInt(12) != 0, !c.getString(13).isEmpty());
    }

    private static String prefixed(String columns) {
        StringBuilder out = new StringBuilder();
        for (String column : columns.split(",")) { if (out.length() > 0) out.append(','); out.append("r.").append(column); }
        return out.toString();
    }

    private static String locatorsJson(LinkRecord record) {
        org.json.JSONArray array = new org.json.JSONArray();
        try {
            for (LinkRecord.Locator l : record.locators) {
                org.json.JSONObject o = new org.json.JSONObject().put("type", l.type);
                if (l.uuid != null) o.put("uuid", l.uuid);
                if (l.path != null) o.put("path", l.path);
                if (l.uri != null) o.put("uri", l.uri);
                if (l.token != null) o.put("token", l.token);
                array.put(o);
            }
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        return array.toString();
    }

    private static void copy(File from, File to) throws IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(from); java.io.OutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        }
    }
}
