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
        /** Stack's own "Share with partner" switch: 0 off, 1 on, 2 on with a protected link confirmed. */
        final int partnerShare;
        /** A record the partner shares with this phone (read-only, never checked with the owner app), and the name to show on its badge. */
        final boolean foreign; final String sharedBy;
        /** An own record (not shared by the partner). */
        Link(String id, String owner, String linkId, String kind, String title, String mime, String hash, String locators, String state,
             String excerpt, long modified, long addedAt, boolean shared, boolean isProtected, boolean hasPreview, int partnerShare) {
            this(id, owner, linkId, kind, title, mime, hash, locators, state, excerpt, modified, addedAt, shared, isProtected, hasPreview, partnerShare, false, "");
        }
        Link(String id, String owner, String linkId, String kind, String title, String mime, String hash, String locators, String state,
             String excerpt, long modified, long addedAt, boolean shared, boolean isProtected, boolean hasPreview, int partnerShare, boolean foreign, String sharedBy) {
            this.foreign = foreign; this.sharedBy = sharedBy;
            this.id = id; this.owner = owner; this.linkId = linkId; this.kind = kind; this.title = title; this.mime = mime; this.hash = hash;
            this.locators = locators; this.state = state; this.excerpt = excerpt; this.modified = modified; this.addedAt = addedAt;
            this.shared = shared; this.isProtected = isProtected; this.hasPreview = hasPreview; this.partnerShare = partnerShare;
        }
    }

    /** What a publish did: the local id, whether it was new, whether text and preview were replaced, and any other record with the same bytes. */
    static final class Result {
        final String id; final boolean created, contentChanged; final String duplicateOf;
        Result(String id, boolean created, boolean contentChanged, String duplicateOf) { this.id = id; this.created = created; this.contentChanged = contentChanged; this.duplicateOf = duplicateOf; }
    }

    private static final String COLUMNS = "id,owner,link_id,kind,title,mime,hash,locators,state,modified,added_at,shared,protected,preview_file,partner_share,origin,shared_by";
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
        String id = null, storedHash = null, storedPreview = "", priorState = "live";
        boolean titleEdited = false;
        try (Cursor c = db.rawQuery("SELECT id,hash,preview_file,title_user_edited,state,origin FROM link_records WHERE owner=? AND link_id=?", new String[]{record.owner, record.linkId})) {
            if (c.moveToFirst()) {
                // A document the partner shares with us holds this identity; the owner app's publish must not overwrite it.
                if ("foreign".equals(c.getString(5))) throw new IOException("This document id is already used by a document shared by your partner.");
                id = c.getString(0); storedHash = c.getString(1); storedPreview = c.getString(2); titleEdited = c.getInt(3) != 0; priorState = c.getString(4);
            }
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
        try (Cursor c = db.rawQuery("SELECT id FROM link_records WHERE hash=? AND origin='own' AND state<>'trashed' AND NOT (owner=? AND link_id=?) LIMIT 1", new String[]{record.hash, record.owner, record.linkId})) {
            if (c.moveToFirst()) duplicateOf = c.getString(0);
        }
        long now = Clock.now();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("kind", record.kind); values.put("mime", record.mime); values.put("modified", record.modified);
            values.put("shared", record.shared ? 1 : 0); values.put("protected", record.isProtected ? 1 : 0);
            values.put("locators", locatorsJson(record)); values.put("received_at", now);
            // A publish is the owner saying the file exists: it brings a trashed, missing or unreachable record back to live.
            values.put("state", "live");
            if (created || !"live".equals(priorState)) { values.put("state_since", now); values.put("removed_reason", ""); }
            if (created || !titleEdited) values.put("title", record.title);
            if (contentChanged) {
                values.put("hash", record.hash); values.put("preview_file", previewName);
                // The saved text and preview are new, so the digests of the old ones must not be reused (M7): capture recomputes them.
                values.put("text_sha", ""); values.put("text_len", 0); values.put("preview_sha", ""); values.put("preview_len", 0); values.put("preview_mtime", 0); values.put("preview_pending", 0);
            }
            if (created) {
                values.put("id", id); values.put("owner", record.owner); values.put("link_id", record.linkId); values.put("added_at", now);
                db.insertOrThrow("link_records", null, values);
            } else {
                db.update("link_records", values, "id=?", new String[]{id});
                db.execSQL("UPDATE link_records SET revision=revision+1 WHERE id=?", new Object[]{id});
            }
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
            + (searching ? "WHERE link_search MATCH ? AND r.state<>'trashed' " : "WHERE r.state<>'trashed' ") + "ORDER BY r.added_at DESC, r.id";
        List<Link> result = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, searching ? new String[]{match} : new String[0])) {
            while (c.moveToNext()) result.add(row(c, c.getString(17)));
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
        // L2: the row decides. A file the database no longer claims (a crash between a commit that dropped it and its removal) is stale.
        try (Cursor c = database.get().rawQuery("SELECT preview_file FROM link_records WHERE id=?", new String[]{id})) {
            if (!c.moveToFirst() || c.getString(0).isEmpty()) return null;
        }
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

    // ---- Lifecycle (slice 4) ----------------------------------------------------------------------------------------

    static final long KEEP_MILLIS = 30L * 24 * 60 * 60 * 1000;
    static final String UNSHARED = "unshared";
    static final String LIVE = "live", UNREACHABLE = "unreachable", MISSING = "missing", TRASHED = "trashed";

    /** The record's revision (bumped by publish, removal and user actions), or -1 if it is gone. */
    long revision(String id) {
        try (Cursor c = database.get().rawQuery("SELECT revision FROM link_records WHERE id=?", new String[]{id})) { return c.moveToFirst() ? c.getLong(0) : -1; }
    }

    /** Links in Recently deleted, newest removal first. */
    List<Trashed> trashed() {
        List<Trashed> out = new ArrayList<>();
        try (Cursor c = database.get().rawQuery("SELECT id,title,owner,state_since,removed_reason,shared_by FROM link_records WHERE state='trashed' ORDER BY state_since DESC, id", null)) {
            while (c.moveToNext()) out.add(new Trashed(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4), c.getString(5)));
        }
        return out;
    }

    /** A link in Recently deleted. */
    static final class Trashed {
        final String id, title, owner, reason, sharedBy; final long deletedAt;
        Trashed(String id, String title, String owner, long deletedAt, String reason, String sharedBy) { this.id = id; this.title = title; this.owner = owner; this.deletedAt = deletedAt; this.reason = reason; this.sharedBy = sharedBy; }
        /** A shared document the partner stopped sharing (removed_reason 'unshared'); shown as "no longer shared". */
        boolean noLongerShared() { return UNSHARED.equals(reason); }
    }

    /** What a check learned. `locators` and `modified` apply only to LIVE and only when they passed validation (otherwise null / -1). */
    static final class Outcome {
        final String state, locators, hash; final long modified;
        Outcome(String state, String locators, long modified) { this(state, locators, modified, null); }
        /** `hash` is the owner's current hash for a live answer; the stored copy's `modified` is only moved when it matches the stored hash. */
        Outcome(String state, String locators, long modified, String hash) { this.state = state; this.locators = locators; this.modified = modified; this.hash = hash; }
    }

    /**
     * Writes a check result only if nothing else changed the record since `expectedRevision` was read, and never to a trashed
     * record: a publish, a removal or a user action that ran in between wins. Returns whether it was applied.
     */
    boolean applyCheck(String id, long expectedRevision, Outcome outcome) { return applyCheckDetailed(id, expectedRevision, outcome) > 0; }

    /** 0 = not applied, 1 = applied, 2 = applied and the owner's hash differs from the stored copy's (the owner's file changed). */
    int applyCheckDetailed(String id, long expectedRevision, Outcome outcome) {
        if (!LIVE.equals(outcome.state) && !UNREACHABLE.equals(outcome.state) && !MISSING.equals(outcome.state)) return 0;
        long now = Clock.now();
        SQLiteDatabase db = database.get();
        db.beginTransaction();
        try {
            String stored;
            try (Cursor c = db.rawQuery("SELECT hash FROM link_records WHERE id=? AND revision=? AND state<>'trashed'", new String[]{id, Long.toString(expectedRevision)})) {
                if (!c.moveToFirst()) return 0;
                stored = c.getString(0);
            }
            boolean differs = LIVE.equals(outcome.state) && outcome.hash != null && !outcome.hash.equals(stored);
            ContentValues values = new ContentValues();
            values.put("state", outcome.state); values.put("checked_at", now);
            if (LIVE.equals(outcome.state)) {
                if (outcome.locators != null) values.put("locators", outcome.locators);
                // The saved text is of the old file, so its "changed" date must stay with it until a refresh brings the new copy.
                if (outcome.modified >= 0 && !differs) values.put("modified", outcome.modified);
            }
            // state_since moves only when the state really changes, so a repeated check does not restart the 30 days.
            db.execSQL("UPDATE link_records SET state_since=? WHERE id=? AND revision=? AND state<>'trashed' AND state<>?", new Object[]{now, id, expectedRevision, outcome.state});
            int changed = db.update("link_records", values, "id=? AND revision=? AND state<>'trashed'", new String[]{id, Long.toString(expectedRevision)});
            db.setTransactionSuccessful();
            return changed == 0 ? 0 : differs ? 2 : 1;
        } finally { db.endTransaction(); }
    }

    /** Delete for good from Recently deleted: only a record that is still trashed, so a record the owner brought back meanwhile is safe. */
    boolean deleteTrashed(String id) {
        try (android.database.sqlite.SQLiteStatement st = database.get().compileStatement("SELECT COUNT(*) FROM link_records WHERE id=? AND state='trashed'")) {
            st.bindString(1, id);
            if (st.simpleQueryForLong() == 0) return false;
        }
        delete(id);
        return true;
    }

    /** The owner says the file is gone. Unknown records and already-trashed ones are left alone. Returns whether a record changed. */
    boolean markRemoved(String owner, String linkId, String reason) {
        boolean changed = moveToTrash("owner=? AND link_id=? AND origin='own'", new String[]{owner, linkId}, reason);
        if (!changed && "deleted".equals(reason)) {
            // The file was in Filen's trash (reason `trashed`, restorable) and is now gone for good: it can no longer come back.
            try (android.database.sqlite.SQLiteStatement st = database.get().compileStatement(
                    "UPDATE link_records SET removed_reason='deleted', revision=revision+1 WHERE owner=? AND link_id=? AND origin='own' AND state='trashed' AND removed_reason='trashed'")) {
                st.bindString(1, owner); st.bindString(2, linkId);
                changed = st.executeUpdateDelete() > 0;
            }
        }
        return changed;
    }

    /**
     * The owner (or Filen) says the file now lives elsewhere: the locators are replaced and nothing else changes (stacks, tags,
     * notes, the user's title stay). A record that was missing or unreachable, or in Recently deleted only because the file sat in
     * Filen's trash, is live again. A record the owner reported deleted, or the user removed, stays where it is.
     * Returns whether a record changed.
     */
    boolean markMoved(String owner, String linkId, List<LinkRecord.Locator> locators) {
        SQLiteDatabase db = database.get();
        String json = locatorsJson(locators);
        db.beginTransaction();
        try {
            String id = null, state = null, reason = null, old = null;
            try (Cursor c = db.rawQuery("SELECT id,state,removed_reason,locators FROM link_records WHERE owner=? AND link_id=? AND origin='own'", new String[]{owner, linkId})) {
                if (c.moveToFirst()) { id = c.getString(0); state = c.getString(1); reason = c.getString(2); old = c.getString(3); }
            }
            if (id == null) return false;
            boolean back = "missing".equals(state) || "unreachable".equals(state) || ("trashed".equals(state) && "trashed".equals(reason));
            if (json.equals(old) && !back) return false;
            if (back) db.execSQL("UPDATE link_records SET locators=?, state='live', state_since=?, removed_reason='', revision=revision+1 WHERE id=?", new Object[]{json, Clock.now(), id});
            else db.execSQL("UPDATE link_records SET locators=?, revision=revision+1 WHERE id=?", new Object[]{json, id});
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    /** Access for others ended; the file still exists. Returns whether a record changed. */
    boolean markUnshared(String owner, String linkId) {
        try (android.database.sqlite.SQLiteStatement statement = database.get().compileStatement(
                "UPDATE link_records SET shared=0, revision=revision+1 WHERE owner=? AND link_id=? AND shared<>0 AND origin='own'")) {
            statement.bindString(1, owner); statement.bindString(2, linkId);
            return statement.executeUpdateDelete() > 0;
        }
    }

    /** For the device checks: state, shared, removed reason and locators of every record whose locators mention this UUID. */
    android.database.Cursor debugRows(String uuid) {
        return database.get().rawQuery("SELECT state,shared,removed_reason,locators FROM link_records WHERE locators LIKE ?", new String[]{"%" + uuid + "%"});
    }

    /** The (owner, link_id, locators) of every record that has this Filen UUID as its filen locator. */
    private List<String[]> byFilenUuid(String uuid) {
        List<String[]> found = new ArrayList<>();
        try (Cursor c = database.get().rawQuery("SELECT owner,link_id,locators FROM link_records WHERE origin='own' AND locators LIKE ?", new String[]{"%" + uuid + "%"})) {
            while (c.moveToNext()) {
                List<LinkRecord.Locator> list = LinkRecord.parseLocators(c.getString(2));
                if (list == null) continue;
                for (LinkRecord.Locator l : list) if (LinkRecord.Locator.FILEN.equals(l.type) && uuid.equals(l.uuid)) { found.add(new String[]{c.getString(0), c.getString(1), c.getString(2)}); break; }
            }
        }
        return found;
    }

    /** Filen moved or renamed the file: only the filen locator is replaced (saf/owner entries stay). Returns how many records changed. */
    int filenMoved(String uuid, String path) throws org.json.JSONException {
        int changed = 0;
        for (String[] r : byFilenUuid(uuid)) {
            String merged = locatorsFor(r[2], new org.json.JSONObject().put("uuid", uuid).put("path", path));
            List<LinkRecord.Locator> list = LinkRecord.parseLocators(merged);
            if (list != null && markMoved(r[0], r[1], list)) changed++;
        }
        return changed;
    }

    /** Filen deleted or trashed the file. Returns how many records changed. */
    int filenRemoved(String uuid, String reason) {
        int changed = 0;
        for (String[] r : byFilenUuid(uuid)) if (markRemoved(r[0], r[1], reason)) changed++;
        return changed;
    }

    /** Filen ended access for others. Returns how many records changed. */
    int filenUnshared(String uuid) {
        int changed = 0;
        for (String[] r : byFilenUuid(uuid)) if (markUnshared(r[0], r[1])) changed++;
        return changed;
    }

    /** The user removed it from Stack, or chose to forget a missing one. */
    boolean trash(String id, String reason) { return moveToTrash("id=?", new String[]{id}, reason); }

    private boolean moveToTrash(String where, String[] args, String reason) {
        try (android.database.sqlite.SQLiteStatement statement = database.get().compileStatement(
                "UPDATE link_records SET state='trashed', state_since=?, removed_reason=?, revision=revision+1 WHERE " + where + " AND state<>'trashed'")) {
            statement.bindLong(1, Clock.now()); statement.bindString(2, reason);
            for (int i = 0; i < args.length; i++) statement.bindString(3 + i, args[i]);
            return statement.executeUpdateDelete() > 0;
        }
    }

    /** Back from Recently deleted. A record the owner reported gone returns as missing, the rest as unreachable; the next check settles it. */
    void restore(String id) {
        // A shared document the partner stopped sharing has no owner app to ask: it comes back as live, and the next import settles it.
        database.get().execSQL("UPDATE link_records SET state='live', state_since=?, removed_reason='', revision=revision+1 WHERE id=? AND state='trashed' AND origin='foreign'", new Object[]{Clock.now(), id});
        database.get().execSQL("UPDATE link_records SET state=CASE WHEN removed_reason IN ('deleted','trashed','forgotten') THEN 'missing' ELSE 'unreachable' END,"
            + " state_since=?, removed_reason='', revision=revision+1 WHERE id=? AND state='trashed' AND origin='own'", new Object[]{Clock.now(), id});
    }

    /** Missing for 30 days becomes trashed; trashed for 30 days is deleted for good. Returns how many were deleted. */
    int sweep(long now) {
        SQLiteDatabase db = database.get();
        long cutoff = now - KEEP_MILLIS;
        db.execSQL("UPDATE link_records SET state='trashed', state_since=?, removed_reason='forgotten', revision=revision+1 WHERE state='missing' AND state_since<=?", new Object[]{now, cutoff});
        List<String> expired = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id FROM link_records WHERE state='trashed' AND state_since<=?", new String[]{Long.toString(cutoff)})) { while (c.moveToNext()) expired.add(c.getString(0)); }
        for (String id : expired) delete(id);
        return expired.size();
    }

    /** Deletes every link in Recently deleted for good. */
    void emptyTrash() { for (Trashed t : trashed()) delete(t.id); }


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

    // ---- Partner sharing, publish side (slice 7, S7-2) ----------------------------------------------------------------

    /** A link the person switched "Share with partner" on, with what the publisher needs. */
    static final class Candidate {
        final String id, owner, linkId, kind, title, mime, hash, locators, state;
        final long modified, revision; final boolean isProtected; final int mode;
        Candidate(String id, String owner, String linkId, String kind, String title, String mime, String hash, String locators, String state,
                  long modified, long revision, boolean isProtected, int mode) {
            this.id = id; this.owner = owner; this.linkId = linkId; this.kind = kind; this.title = title; this.mime = mime; this.hash = hash;
            this.locators = locators; this.state = state; this.modified = modified; this.revision = revision; this.isProtected = isProtected; this.mode = mode;
        }
    }

    /**
     * Sets Stack's own sharing switch: 0 off, 1 on, 2 on with a protected link confirmed. A protected link cannot be switched on without
     * the confirmation (mode 1 is refused for it); mode 2 on an unprotected link is stored as 1. Returns whether the stored value is now `mode`
     * (after that normalisation) and the link exists. Does not touch `revision`, so a running check is not discarded.
     */
    boolean setPartnerShare(String id, int mode) {
        if (mode < 0 || mode > 2) return false;
        SQLiteDatabase db = database.get();
        boolean isProtected;
        try (Cursor c = db.rawQuery("SELECT protected FROM link_records WHERE id=?", new String[]{id})) {
            if (!c.moveToFirst()) return false;
            isProtected = c.getInt(0) != 0;
        }
        if (mode == 1 && isProtected) return false;
        if (mode == 2 && !isProtected) mode = 1;
        db.execSQL("UPDATE link_records SET partner_share=? WHERE id=?", new Object[]{mode, id});
        return true;
    }

    /** Every link with the switch on, in any state; the publisher decides which of them go into the file. */
    List<Candidate> partnerFlagged() {
        List<Candidate> out = new ArrayList<>();
        try (Cursor c = database.get().rawQuery("SELECT id,owner,link_id,kind,title,mime,hash,locators,state,modified,revision,protected,partner_share FROM link_records WHERE partner_share>0 AND origin='own' ORDER BY owner,link_id", null)) {
            while (c.moveToNext()) out.add(new Candidate(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6),
                c.getString(7), c.getString(8), c.getLong(9), c.getLong(10), c.getInt(11) != 0, c.getInt(12)));
        }
        return out;
    }

    /**
     * Brings the sharing log up to date with `sharedNow` (keys `owner\nlink_id` of everything that goes into the file now) and returns the
     * removal notices still inside their 30 days. Something in the log as 'shared' that is no longer shared becomes 'removed' as of `now`;
     * something shared again leaves its notice; notices older than 30 days are forgotten. Local only.
     */
    List<ShareArchive.Tombstone> partnerTombstones(java.util.Set<String> sharedNow, long now) {
        SQLiteDatabase db = database.get();
        List<ShareArchive.Tombstone> out = new ArrayList<>();
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM partner_share_log WHERE state='removed' AND since<=?", new Object[]{now - KEEP_MILLIS});
            List<String[]> rows = new ArrayList<>();
            try (Cursor c = db.rawQuery("SELECT owner,link_id,state FROM partner_share_log", null)) { while (c.moveToNext()) rows.add(new String[]{c.getString(0), c.getString(1), c.getString(2)}); }
            java.util.Set<String> known = new java.util.HashSet<>();
            for (String[] r : rows) {
                String key = r[0] + "\n" + r[1]; known.add(key);
                boolean shared = sharedNow.contains(key);
                if (shared && !"shared".equals(r[2])) db.execSQL("UPDATE partner_share_log SET state='shared', since=? WHERE owner=? AND link_id=?", new Object[]{now, r[0], r[1]});
                else if (!shared && "shared".equals(r[2])) db.execSQL("UPDATE partner_share_log SET state='removed', since=? WHERE owner=? AND link_id=?", new Object[]{now, r[0], r[1]});
            }
            for (String key : sharedNow) {
                if (known.contains(key)) continue;
                int cut = key.indexOf('\n');
                db.execSQL("INSERT INTO partner_share_log(owner,link_id,state,since) VALUES(?,?,'shared',?)", new Object[]{key.substring(0, cut), key.substring(cut + 1), now});
            }
            try (Cursor c = db.rawQuery("SELECT owner,link_id,since FROM partner_share_log WHERE state='removed' ORDER BY owner,link_id", null)) {
                while (c.moveToNext()) out.add(new ShareArchive.Tombstone(c.getString(0), c.getString(1), c.getLong(2)));
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return out;
    }

    /** The cached preview bytes of a record, or null when it has none (or it is over the preview limit). */
    byte[] previewBytes(String id) throws IOException {
        File file = preview(id);
        if (file == null || file.length() > LinkRecord.MAX_PREVIEW) return null;
        byte[] bytes = new byte[(int) file.length()];
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(file))) { in.readFully(bytes); }
        return bytes;
    }

    // ---- Partner sharing, import side (slice 7, S7-3) -------------------------------------------------------------------

    /** What one import changed. */
    static final class Imported {
        int inserted, updated, unshared, back, ownKept, stayedRemoved;
        int changed() { return inserted + updated + unshared + back; }
    }

    /**
     * Applies a share archive from the partner in ONE transaction (run on the IO executor). Rules:
     * - a record whose (owner, link_id) is one of this phone's OWN records is never touched (counted in ownKept);
     * - a new record is inserted foreign, live, with `shared_by` set; its text goes into the search table, its preview is placed after the commit;
     * - an existing foreign record is updated only when the archive's generation is higher (the person's own title stays when edited);
     * - a tombstoned record, or a foreign record the archive no longer lists, moves to Recently deleted with removed_reason 'unshared';
     * - a foreign record in Recently deleted as 'unshared' that is listed again comes back live; one the person removed ('user') stays removed;
     * - the person's own tags, stacks and notes are not in link_records' content columns and are never touched.
     * Since S7-4 the sync triggers cover foreign rows ({@link LibraryDatabase#addForeignSync}): every change here raises the link dirty flag, and {@link LinkSync} writes the cells only for a computer that announced it can show them.
     * Nothing is written when anything throws: the transaction rolls back and any half-written preview files are removed.
     */
    Imported applyPartnerArchive(ShareArchive.Contents contents, String sharedBy, long now) throws IOException {
        SQLiteDatabase db = database.get();
        Imported out = new Imported();
        List<File[]> placements = new ArrayList<>();   // {partFile, finalFile}
        List<File> removals = new ArrayList<>();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create the link folder.");
        boolean done = false;
        db.beginTransaction();
        try {
            java.util.Map<String, String[]> existing = new java.util.HashMap<>();   // owner\nlink_id -> id, origin, state, reason, title_user_edited, preview_file
            java.util.Map<String, Long> generations = new java.util.HashMap<>();
            try (Cursor c = db.rawQuery("SELECT id,owner,link_id,origin,state,removed_reason,title_user_edited,preview_file,foreign_generation FROM link_records", null)) {
                while (c.moveToNext()) {
                    String key = c.getString(1) + "\n" + c.getString(2);
                    existing.put(key, new String[]{c.getString(0), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7)});
                    generations.put(key, c.getLong(8));
                }
            }
            java.util.Set<String> listed = new java.util.HashSet<>();
            for (ShareArchive.Record r : contents.records) {
                String key = r.key();
                listed.add(key);
                // Text from the partner never keeps direction-changing characters, so a title or a search hit cannot be made to read backwards.
                final String shownTitle = LinkRecord.stripDirection(r.title), bodyText = LinkRecord.stripDirection(r.text);
                String[] row = existing.get(key);
                String locators = new org.json.JSONArray().put(new org.json.JSONObject().put("type", "filen").put("uuid", r.filenUuid).put("path", r.filenPath)).toString();
                if (row == null) {
                    String id = UUID.randomUUID().toString();
                    ContentValues v = new ContentValues();
                    v.put("id", id); v.put("owner", r.owner); v.put("link_id", r.linkId); v.put("kind", r.kind); v.put("title", shownTitle); v.put("mime", r.mime);
                    v.put("modified", r.modified); v.put("hash", r.hash); v.put("shared", 1); v.put("protected", r.isProtected ? 1 : 0); v.put("locators", locators);
                    v.put("state", LIVE); v.put("state_since", now); v.put("removed_reason", ""); v.put("added_at", now); v.put("received_at", now);
                    v.put("checked_at", 0); v.put("revision", 1); v.put("origin", "foreign"); v.put("shared_by", sharedBy); v.put("foreign_generation", r.generation);
                    v.put("preview_file", r.preview == null ? "" : id + ".pdf");
                    db.insertOrThrow("link_records", null, v);
                    db.execSQL("INSERT INTO link_search(record_id,title,body) VALUES(?,?,?)", new Object[]{id, shownTitle, bodyText});
                    if (r.preview != null) placements.add(stagePreview(id, r.preview));
                    out.inserted++;
                    continue;
                }
                if (!"foreign".equals(row[1])) { out.ownKept++; continue; }
                String id = row[0];
                boolean trashed = TRASHED.equals(row[2]);
                if (trashed && !UNSHARED.equals(row[3])) { out.stayedRemoved++; continue; }   // the person removed it: it stays removed
                boolean higher = r.generation > generations.get(key);
                boolean previewLost = !higher && r.preview != null && !new File(directory, id + ".pdf").isFile();
                ContentValues v = new ContentValues();
                if (higher) {
                    v.put("kind", r.kind); v.put("mime", r.mime); v.put("modified", r.modified); v.put("hash", r.hash); v.put("protected", r.isProtected ? 1 : 0);
                    v.put("locators", locators); v.put("foreign_generation", r.generation); v.put("shared", 1);
                    v.put("preview_file", r.preview == null ? "" : id + ".pdf");
                    // The saved text and preview may change without the hash changing: the digests the sync keeps for them are reset (as publish does), so capture hashes the new files.
                    v.put("text_sha", ""); v.put("text_len", 0); v.put("preview_sha", ""); v.put("preview_len", 0); v.put("preview_mtime", 0);
                    if (!"1".equals(row[4])) v.put("title", shownTitle);
                } else if (previewLost) v.put("preview_file", id + ".pdf");
                if (trashed) { v.put("state", LIVE); v.put("state_since", now); v.put("removed_reason", ""); }
                if (v.size() == 0) continue;
                v.put("shared_by", sharedBy);
                db.update("link_records", v, "id=?", new String[]{id});
                db.execSQL("UPDATE link_records SET revision=revision+1 WHERE id=?", new Object[]{id});
                if (higher) {
                    String title = "1".equals(row[4]) ? titleOf(db, id) : shownTitle;
                    db.execSQL("DELETE FROM link_search WHERE record_id=?", new Object[]{id});
                    db.execSQL("INSERT INTO link_search(record_id,title,body) VALUES(?,?,?)", new Object[]{id, title, bodyText});
                }
                if (higher || previewLost) {
                    if (r.preview != null) placements.add(stagePreview(id, r.preview));
                    else { removals.add(new File(directory, id + ".pdf")); removals.add(new File(directory, id + ".rendering")); }
                    if (r.preview != null) new File(directory, id + ".rendering").delete();
                }
                if (trashed) out.back++; else out.updated++;
            }
            // Removed notices and records that are simply no longer listed.
            java.util.Set<String> gone = new java.util.HashSet<>();
            for (ShareArchive.Tombstone t : contents.tombstones) gone.add(t.key());
            for (java.util.Map.Entry<String, String[]> e : existing.entrySet()) {
                String[] row = e.getValue();
                if (!"foreign".equals(row[1]) || TRASHED.equals(row[2])) continue;
                if (listed.contains(e.getKey())) continue;
                // Tombstoned or not listed: both mean the partner stopped sharing it.
                db.execSQL("UPDATE link_records SET state='trashed', state_since=?, removed_reason=?, revision=revision+1 WHERE id=? AND state<>'trashed'", new Object[]{now, UNSHARED, row[0]});
                out.unshared++;
            }
            // The badge name follows the name chosen at pairing.
            db.execSQL("UPDATE link_records SET shared_by=? WHERE origin='foreign' AND shared_by<>?", new Object[]{sharedBy, sharedBy});
            if (out.changed() > 0 || out.stayedRemoved > 0) LinkSync.markDirty(db);
            db.setTransactionSuccessful();
            done = true;
        } catch (org.json.JSONException error) {
            throw new IOException("A shared document could not be stored.");
        } finally {
            db.endTransaction();
            if (!done) for (File[] p : placements) p[0].delete();
        }
        for (File[] p : placements) if (!p[0].renameTo(p[1])) { copyOrDrop(p[0], p[1]); }
        for (File f : removals) f.delete();
        LinkSync.syncDir(directory);
        return out;
    }

    private File[] stagePreview(String id, byte[] bytes) throws IOException {
        File part = new File(directory, id + ".pdf.part");
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(part)) { stream.write(bytes); stream.getFD().sync(); }
        return new File[]{part, new File(directory, id + ".pdf")};
    }

    private static void copyOrDrop(File from, File to) {
        try { copy(from, to); } catch (IOException ignored) { /* the preview is missing until the next import re-places it */ }
        from.delete();
    }

    // ---- Applying synced link records (slice 6, P1) --------------------------------------------------------------------

    /** What `applySynced` did, per link key (`link_records/["owner","link_id"]`), and which rows still need their preview placed. */
    static final class Applied {
        int inserted, updated, ignoredGone, skipped;
        /** key -> "inserted" | "updated" | "gone_ignored" | "skipped". */
        final java.util.Map<String, String> status = new java.util.LinkedHashMap<>();
        /** Row ids whose preview file must be placed after the commit (`preview_pending=1`). */
        final List<String> pending = new ArrayList<>();
        /** Row ids whose old preview file must be removed after the commit (the new content has none). */
        final List<String> dropped = new ArrayList<>();
        /** True once the link being applied has written to the database (a later failure is then not a per-link skip). */
        boolean writing;
    }

    /**
     * Applies link rows projected from the causal state (`SyncGraph.projectLinks(...).links`) to this phone's rows, with the
     * sync `applying` flag raised so the capture triggers stay silent (design sections 4 and 7). It never touches `saf`/`owner`
     * locators (the synced filen locator is merged in), and every applied change bumps `revision`, so an in-flight LinkChecker
     * write based on the old revision loses. Rules:
     * - an identity whose presence is `gone` is never inserted and a received `gone` never deletes a phone row;
     * - a row the phone does not have is inserted `unreachable` when its presence says live, or `trashed` with reason `user`
     *   when it came with NO presence cell (a link only the desktop has: never live); a row with no filen cell is always inserted trashed with
     *   the inert placeholder locator (`LinkBudget.PLACEHOLDER_LOCATORS`), because a backup refuses a record without a locator;
     * - an existing row follows the received content, title, filen, text and preview; presence live brings a trashed row back as
     *   `unreachable`, presence trashed trashes a live one;
     * - a link whose text or preview blob is missing or fails its check is skipped (counted), never half applied.
     * Previews are NOT written here: the row gets `preview_sha` and `preview_pending=1` in the transaction and
     * `placePreviews` writes the file after the commit.
     * Conflicted links are never passed in: `LinkSync.install` leaves them out (P2 resolves the decided conflicts first; a tie waits for
     * the per-link review, `SyncStore.resolveLink`).
     */
    Applied applySynced(SQLiteDatabase db, org.json.JSONArray links, File blobs) throws IOException {
        Applied result = new Applied();
        long flag = android.database.DatabaseUtils.longForQuery(db, "SELECT applying FROM sync_control WHERE singleton=1", null);
        // L7: called inside the document install's transaction. A nested transaction that ends without success would mark the OUTER one
        // failed (Android), so one bad link would roll back the whole document install. So there is no nested transaction: everything
        // that can fail because of a link (reading cells, blobs, validation) happens BEFORE the first write of that link (`writing`),
        // and such a failure only skips that link. A failure after the first write is a database failure and is rethrown (the install
        // fails as a whole, as it always did). Only a call made with no transaction open starts its own.
        boolean own = !db.inTransaction();
        if (own) db.beginTransaction();
        try {
            db.execSQL("UPDATE sync_control SET applying=1 WHERE singleton=1");
            for (int i = 0; i < links.length(); i++) {
                String key = "";
                result.writing = false;
                try {
                    org.json.JSONObject link = links.getJSONObject(i);
                    key = link.optString("key", "");
                    applyOne(db, link, blobs, result);
                } catch (org.json.JSONException | IOException | RuntimeException error) {
                    if (result.writing) {
                        if (error instanceof IOException) throw (IOException) error;
                        if (error instanceof RuntimeException) throw (RuntimeException) error;
                        throw new IOException("Invalid projected link", error);
                    }
                    result.skipped++; result.status.put(key, "skipped");
                }
            }
            db.execSQL("UPDATE sync_control SET applying=? WHERE singleton=1", new Object[]{flag});
            if (own) db.setTransactionSuccessful();
        } finally { if (own) db.endTransaction(); }
        return result;
    }

    private void applyOne(SQLiteDatabase db, org.json.JSONObject link, File blobs, Applied result) throws org.json.JSONException, IOException {
        String key = link.getString("key"), owner = link.getString("owner"), linkId = link.getString("link_id"), outcome = link.getString("outcome");
        String id = null, state = null, storedHash = null, locators = null; long titleEdited = 0, wasProtected = 0;
        try (Cursor c = db.rawQuery("SELECT id,state,hash,locators,title_user_edited,protected,origin FROM link_records WHERE owner=? AND link_id=?", new String[]{owner, linkId})) {
            if (c.moveToFirst()) { id = c.getString(0); state = c.getString(1); storedHash = c.getString(2); locators = c.getString(3); titleEdited = c.getLong(4); wasProtected = c.getLong(5);
                // A shared copy of the partner's document is phone-authoritative (S7-4): the computer never changes it, and its cells never apply here.
                if ("foreign".equals(c.getString(6))) { result.skipped++; result.status.put(key, "skipped"); return; } }
        }
        if ("gone".equals(outcome)) {
            // Only the phone produces `gone`. A received one never deletes a row, and never creates one.
            result.ignoredGone++; result.status.put(key, "gone_ignored"); return;
        }
        org.json.JSONObject content = link.optJSONObject("content"), title = link.optJSONObject("title"), filen = link.optJSONObject("filen");
        org.json.JSONObject text = link.optJSONObject("text"), preview = link.optJSONObject("preview");
        boolean metadataOnly = "applied_metadata_only".equals(outcome);
        String body = null;
        try {
            if (text != null && !metadataOnly) body = readText(blobs, text);
            if (preview != null && !metadataOnly) checkPreview(blobs, preview);
        } catch (IOException bad) { result.skipped++; result.status.put(key, "skipped"); return; }
        long now = Clock.now();
        String presenceState = link.getString("state"), reason = link.getString("reason"); long since = link.getLong("since");
        boolean absent = link.optBoolean("presence_absent", false);
        if (id == null) {
            if (content == null || title == null) { result.skipped++; result.status.put(key, "skipped"); return; }
            // M4: a link only the computer has, with no filen cell, is kept for the record but never live: it gets the same inert
            // placeholder locator as on the computer (a backup refuses a record with no locator) and sits in Recently deleted.
            boolean placeholder = filen == null;
            id = UUID.randomUUID().toString();
            ContentValues v = new ContentValues();
            v.put("id", id); v.put("owner", owner); v.put("link_id", linkId); v.put("kind", link.getString("kind"));
            v.put("title", title.getString("title")); v.put("title_user_edited", title.getLong("title_user_edited"));
            putContent(v, content);
            v.put("locators", placeholder ? LinkBudget.PLACEHOLDER_LOCATORS : locatorsFor("[]", filen));
            v.put("added_at", link.getLong("added_at")); v.put("received_at", now); v.put("checked_at", 0); v.put("revision", 1);
            if (placeholder) { v.put("state", TRASHED); v.put("removed_reason", "trashed".equals(presenceState) && !reason.isEmpty() ? reason : "user"); v.put("state_since", "trashed".equals(presenceState) && since > 0 ? since : now); }
            else if (absent) { v.put("state", TRASHED); v.put("removed_reason", "user"); v.put("state_since", now); }
            else if ("trashed".equals(presenceState)) { v.put("state", TRASHED); v.put("removed_reason", reason.isEmpty() ? "user" : reason); v.put("state_since", since > 0 ? since : now); }
            else { v.put("state", UNREACHABLE); v.put("removed_reason", ""); v.put("state_since", now); }
            v.put("preview_file", ""); v.put("preview_mtime", 0);
            digests(v, metadataOnly ? null : text, metadataOnly ? null : preview);
            result.writing = true;
            db.insertOrThrow("link_records", null, v);
            db.execSQL("INSERT INTO link_search(record_id,title,body) VALUES(?,?,?)", new Object[]{id, title.getString("title"), body == null ? "" : body});
            if (preview != null && !metadataOnly) result.pending.add(id);
            result.inserted++; result.status.put(key, "inserted"); return;
        }
        ContentValues v = new ContentValues();
        boolean changedHash = content != null && !content.getString("hash").equals(storedHash);
        if (content != null) putContent(v, content);
        String newTitle = null;
        if (title != null && !(titleEdited != 0 && title.getLong("title_user_edited") == 0)) {
            newTitle = title.getString("title"); v.put("title", newTitle); v.put("title_user_edited", title.getLong("title_user_edited"));
        }
        v.put("locators", locatorsFor(locators, filen));
        if ("live".equals(presenceState) && TRASHED.equals(state)) { v.put("state", UNREACHABLE); v.put("removed_reason", ""); v.put("state_since", now); }
        else if ("trashed".equals(presenceState) && !TRASHED.equals(state)) { v.put("state", TRASHED); v.put("removed_reason", reason.isEmpty() ? "user" : reason); v.put("state_since", since > 0 ? since : now); }
        // A new hash (or a protected link) replaces text and preview wholesale; the same hash replaces only what was received.
        // M2: a metadata-only outcome for a link that was already protected here (a desktop /filen or title change) leaves the phone's
        // own text, preview and digests alone; they are cleared only when the hash changed or the link BECOMES protected.
        boolean resetText = LinkBudget.replacesBlob(metadataOnly, wasProtected != 0, changedHash, text != null);
        boolean resetPreview = LinkBudget.replacesBlob(metadataOnly, wasProtected != 0, changedHash, preview != null);
        boolean havePreview = preview != null && !metadataOnly;
        if (resetText) { v.put("text_sha", metadataOnly || text == null ? "" : text.getString("sha256")); v.put("text_len", metadataOnly || text == null ? 0 : text.getLong("length")); }
        if (resetPreview) {
            v.put("preview_sha", havePreview ? preview.getString("sha256") : ""); v.put("preview_len", havePreview ? preview.getLong("length") : 0); v.put("preview_mtime", 0);
            v.put("preview_pending", havePreview ? 1 : 0); v.put("preview_file", "");
        }
        result.writing = true;
        db.update("link_records", v, "id=?", new String[]{id});
        db.execSQL("UPDATE link_records SET revision=revision+1 WHERE id=?", new Object[]{id});
        if (resetText || newTitle != null) {
            String currentTitle = newTitle != null ? newTitle : titleOf(db, id);
            String newBody = resetText ? (body == null ? "" : body) : body(db, id);
            db.execSQL("DELETE FROM link_search WHERE record_id=?", new Object[]{id});
            db.execSQL("INSERT INTO link_search(record_id,title,body) VALUES(?,?,?)", new Object[]{id, currentTitle, newBody});
        }
        if (resetPreview && havePreview) result.pending.add(id);
        else if (resetPreview) result.dropped.add(id);
        result.updated++; result.status.put(key, "updated");
    }

    private static void putContent(ContentValues v, org.json.JSONObject content) throws org.json.JSONException {
        v.put("hash", content.getString("hash")); v.put("mime", content.getString("mime")); v.put("modified", content.getLong("modified"));
        v.put("shared", content.getLong("shared")); v.put("protected", content.getLong("protected"));
    }

    /** Stores the digests of the received blobs; a preview digest comes with `preview_pending=1` until the file is placed. */
    private static void digests(ContentValues v, org.json.JSONObject text, org.json.JSONObject preview) throws org.json.JSONException {
        v.put("text_sha", text == null ? "" : text.getString("sha256")); v.put("text_len", text == null ? 0 : text.getLong("length"));
        v.put("preview_sha", preview == null ? "" : preview.getString("sha256")); v.put("preview_len", preview == null ? 0 : preview.getLong("length"));
        v.put("preview_pending", preview == null ? 0 : 1);
    }

    private static String titleOf(SQLiteDatabase db, String id) {
        try (Cursor c = db.rawQuery("SELECT title FROM link_records WHERE id=?", new String[]{id})) { c.moveToFirst(); return c.getString(0); }
    }

    /** The existing locators with only the filen entry replaced (first position), or removed when `filen` is null. saf/owner entries stay. */
    static String locatorsFor(String existing, org.json.JSONObject filen) throws org.json.JSONException {
        org.json.JSONArray out = new org.json.JSONArray();
        if (filen != null) out.put(new org.json.JSONObject().put("type", "filen").put("uuid", filen.getString("uuid")).put("path", filen.getString("path")));
        org.json.JSONArray old = new org.json.JSONArray(existing == null ? "[]" : existing);
        for (int i = 0; i < old.length(); i++) if (!"filen".equals(old.getJSONObject(i).optString("type"))) out.put(old.getJSONObject(i));
        // A record needs at least one locator (backups refuse an empty list), so the last filen entry is not removed.
        return out.length() == 0 && old.length() > 0 ? existing : out.toString();
    }

    private static String readText(File blobs, org.json.JSONObject cell) throws IOException, org.json.JSONException {
        String sha = cell.getString("sha256"); long length = cell.getLong("length");
        File file = new File(blobs, sha);
        if (!file.isFile() || file.length() != length || length > LinkRecord.MAX_TEXT * 3L) throw new IOException("text blob missing");
        byte[] bytes = new byte[(int) length];
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(file))) { in.readFully(bytes); }
        try {
            if (!sha.equals(SyncGraph.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))) throw new IOException("text blob changed");
        } catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
        String text;
        try {
            text = java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException error) { throw new IOException("text blob is not UTF-8"); }
        LinkRecord.checkText(text);
        return text;
    }

    private static void checkPreview(File blobs, org.json.JSONObject cell) throws IOException, org.json.JSONException {
        File file = new File(blobs, cell.getString("sha256"));
        if (!file.isFile() || file.length() != cell.getLong("length")) throw new IOException("preview blob missing");
        byte[] first = new byte[8]; int n;
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) { n = in.read(first); }
        LinkRecord.checkPreview(file.length(), n < 0 ? new byte[0] : java.util.Arrays.copyOf(first, n));
    }

    /** After the commit that applied `applied`: removes previews the new content no longer has, then places the pending ones. */
    void afterApply(Applied applied, File blobs) {
        for (String id : applied.dropped) { new File(directory, id + ".pdf").delete(); new File(directory, id + ".rendering").delete(); }
        placePreviews(blobs);
    }

    /**
     * Places the preview files of rows with `preview_pending=1` after the commit: copy `sync_blobs/<sha>` to a temp file,
     * fsync, rename to `<id>.pdf`, fsync the directory, then set `preview_file` and clear the marker only while `preview_sha` is
     * unchanged (L2: the marker is cleared only after the rename is durable). Touches ONLY pending rows (start-up repair calls this
     * too); restored or migrated rows with an empty digest are never examined. A blob that is not there is left pending. Also
     * removes what a stop can leave behind: `.pdf.part` temp files, and a `<id>.pdf` of a row that no longer claims a preview
     * (dropped by an apply whose cleanup did not run). Returns how many were placed.
     */
    int placePreviews(File blobs) {
        SQLiteDatabase db = database.get();
        cleanLeftovers(db);
        List<String[]> pending = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id,preview_sha,preview_len FROM link_records WHERE preview_pending=1", null)) {
            while (c.moveToNext()) pending.add(new String[]{c.getString(0), c.getString(1), Long.toString(c.getLong(2))});
        }
        int placed = 0;
        for (String[] item : pending) {
            File source = new File(blobs, item[1]);
            if (item[1].isEmpty() || !source.isFile() || source.length() != Long.parseLong(item[2])) continue;
            File target = new File(directory, item[0] + ".pdf"), temporary = new File(directory, item[0] + ".pdf.part");
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create the link folder.");
                try (java.io.FileInputStream in = new java.io.FileInputStream(source); java.io.FileOutputStream out = new java.io.FileOutputStream(temporary)) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                    out.getFD().sync();
                }
                if (!temporary.renameTo(target)) throw new IOException("Cannot place the preview.");
                LinkSync.syncDir(directory);
                new File(directory, item[0] + ".rendering").delete();
                // The capture triggers must stay silent: placing a received preview is not a change of this phone's own.
                long flag = android.database.DatabaseUtils.longForQuery(db, "SELECT applying FROM sync_control WHERE singleton=1", null);
                db.beginTransaction();
                try {
                    db.execSQL("UPDATE sync_control SET applying=1 WHERE singleton=1");
                    db.execSQL("UPDATE link_records SET preview_file=?, preview_pending=0 WHERE id=? AND preview_sha=? AND preview_pending=1", new Object[]{target.getName(), item[0], item[1]});
                    db.execSQL("UPDATE sync_control SET applying=? WHERE singleton=1", new Object[]{flag});
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                placed++;
            } catch (IOException | RuntimeException failure) { temporary.delete(); }
        }
        return placed;
    }

    /** Removes `*.pdf.part` temp files and previews whose row says it has none (and is not waiting for one). */
    private void cleanLeftovers(SQLiteDatabase db) {
        File[] files = directory.listFiles();
        if (files == null) return;
        java.util.Set<String> claimed = new java.util.HashSet<>();
        try (Cursor c = db.rawQuery("SELECT id FROM link_records WHERE preview_file<>'' OR preview_pending=1", null)) { while (c.moveToNext()) claimed.add(c.getString(0)); }
        catch (RuntimeException unreadable) { return; }
        java.util.Set<String> known = new java.util.HashSet<>();
        try (Cursor c = db.rawQuery("SELECT id FROM link_records", null)) { while (c.moveToNext()) known.add(c.getString(0)); }
        catch (RuntimeException unreadable) { return; }
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".pdf.part")) { file.delete(); continue; }
            // Only a file of a row that exists: a publish places its file before its commit, so an unknown id may be in flight.
            if (name.endsWith(".pdf")) { String id = name.substring(0, name.length() - 4); if (known.contains(id) && !claimed.contains(id)) file.delete(); }
        }
    }

    private static String body(SQLiteDatabase db, String id) {
        try (Cursor c = db.rawQuery("SELECT body FROM link_search WHERE record_id=?", new String[]{id})) { return c.moveToFirst() ? c.getString(0) : ""; }
    }

    private Link row(Cursor c, String excerpt) {
        String id = c.getString(0);
        return new Link(id, c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8), excerpt,
            c.getLong(9), c.getLong(10), c.getInt(11) != 0, c.getInt(12) != 0, !c.getString(13).isEmpty(), c.getInt(14), "foreign".equals(c.getString(15)), c.getString(16));
    }

    private static String prefixed(String columns) {
        StringBuilder out = new StringBuilder();
        for (String column : columns.split(",")) { if (out.length() > 0) out.append(','); out.append("r.").append(column); }
        return out.toString();
    }

    private static String locatorsJson(LinkRecord record) { return locatorsJson(record.locators); }

    static String locatorsJson(List<LinkRecord.Locator> locators) {
        org.json.JSONArray array = new org.json.JSONArray();
        try {
            for (LinkRecord.Locator l : locators) {
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
