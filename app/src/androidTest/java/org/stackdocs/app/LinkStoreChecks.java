package org.stackdocs.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Slice 2 (Stack side): the link tables, store, search, and that nothing existing (sync, documents) sees them. Synthetic data. */
final class LinkStoreChecks {
    private static LinkRecord record(String linkId, String title, String hash, String uuid) throws Exception {
        return LinkRecord.parse(new JSONObject()
            .put("contract", 1).put("owner", "app.stub.owner").put("link_id", linkId).put("kind", "doc").put("title", title)
            .put("mime", "application/x-amipro-document").put("modified", 1_800_000_000_000L).put("hash", hash)
            .put("locators", new JSONArray().put(new JSONObject().put("type", "filen").put("uuid", uuid).put("path", "/Health/" + title + ".sam"))).toString());
    }

    private static File pdf(File dir, String name) throws IOException {
        File file = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(("%PDF-1.7\n" + name).getBytes("US-ASCII")); }
        return file;
    }

    private static int count(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery("SELECT count(*) FROM " + table, null)) { c.moveToFirst(); return c.getInt(0); }
    }

    static void run(Context isolated, Consumer<String> mark) throws Exception {
        LibraryDatabase helper = new LibraryDatabase(isolated);
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            File links = new File(isolated.getFilesDir(), "links"), staging = new File(isolated.getFilesDir(), "link-staging");
            staging.mkdirs();
            LinkStore store = new LinkStore(() -> db, links);
            // Sync on: a link record must add nothing to the journal and nothing to documents.
            db.execSQL("INSERT OR REPLACE INTO sync_control(singleton,enabled,applying) VALUES(1,1,0)");
            int journal = count(db, "sync_change"), documents = count(db, "documents");

            String a = UUID.randomUUID().toString(), uuid = UUID.randomUUID().toString();
            LinkStore.Result first = store.publish(record(a, "Medicines and doctors", "1".repeat(64), uuid), "Dr Patel cardiology metformin 500 mg twice daily", pdf(staging, "one.pdf"));
            WorkflowRepositoryChecks.require(first.created && first.contentChanged && first.duplicateOf == null, "first publish creates a record");
            WorkflowRepositoryChecks.require(count(db, "sync_change") == journal && count(db, "documents") == documents, "a link record adds nothing to the sync journal or to documents");
            WorkflowRepositoryChecks.require(store.preview(first.id) != null && store.get(first.id).hasPreview, "the preview is cached");
            List<LinkStore.Link> found = store.list("metformin");
            WorkflowRepositoryChecks.require(found.size() == 1 && found.get(0).id.equals(first.id) && found.get(0).excerpt.contains("metformin"), "search finds the owner's text with an excerpt");
            WorkflowRepositoryChecks.require(store.list("doctors").size() == 1 && store.list("pate").size() == 1, "search finds the title and prefixes");
            WorkflowRepositoryChecks.require(store.list("").size() == 1 && store.list("zzzz").isEmpty() && store.list("!!").isEmpty(), "blank lists all; no match and punctuation-only give nothing");
            WorkflowRepositoryChecks.require(store.text(first.id).contains("cardiology"), "the text is kept for display");

            // The same bytes again: only small fields follow the owner; text and preview stay.
            File before = store.preview(first.id);
            long size = before.length();
            LinkStore.Result repeat = store.publish(record(a, "Medicines and doctors", "1".repeat(64), uuid), "different text must be ignored", pdf(staging, "ignored.pdf"));
            WorkflowRepositoryChecks.require(!repeat.created && !repeat.contentChanged && repeat.id.equals(first.id), "an exact repeat is not a new record or new content");
            WorkflowRepositoryChecks.require(store.text(first.id).contains("cardiology") && store.preview(first.id).length() == size, "an exact repeat keeps the text and preview");
            WorkflowRepositoryChecks.require(count(db, "link_records") == 1 && !new File(staging, "ignored.pdf").exists(), "no second record and the unused preview is discarded");

            // A user title survives; changed bytes replace text and preview.
            store.rename(first.id, "Health list");
            LinkStore.Result changed = store.publish(record(a, "Renamed by owner", "2".repeat(64), uuid), "Dr Okafor neurology levetiracetam", pdf(staging, "two-with-longer-name.pdf"));
            WorkflowRepositoryChecks.require(changed.contentChanged && !changed.created, "a new hash replaces the content");
            WorkflowRepositoryChecks.require(store.get(first.id).title.equals("Health list"), "the user's title is kept when the owner publishes again");
            WorkflowRepositoryChecks.require(store.list("metformin").isEmpty() && store.list("levetiracetam").size() == 1, "search follows the replaced text");
            WorkflowRepositoryChecks.require(store.list("health").size() == 1, "the user's title is searchable");
            WorkflowRepositoryChecks.require(store.preview(first.id).length() != size, "the preview was replaced");

            // Changed bytes with no preview remove the old one; identical bytes under another link id warn.
            store.publish(record(a, "Renamed by owner", "3".repeat(64), uuid), "Dr Okafor neurology levetiracetam", null);
            WorkflowRepositoryChecks.require(store.preview(first.id) == null && !store.get(first.id).hasPreview, "no preview with the new bytes removes the old preview");
            LinkStore.Result twin = store.publish(record(UUID.randomUUID().toString(), "Copy of list", "3".repeat(64), UUID.randomUUID().toString()), "copy text", null);
            WorkflowRepositoryChecks.require(twin.created && first.id.equals(twin.duplicateOf), "identical bytes under another link id report the earlier record");
            WorkflowRepositoryChecks.require(store.list("").size() == 2, "both records are listed");

            // Bad text stores nothing.
            try { store.publish(record(UUID.randomUUID().toString(), "Bad", "4".repeat(64), uuid), "a\0b", null); throw new AssertionError("NUL text must be refused"); }
            catch (IOException expected) { WorkflowRepositoryChecks.require(count(db, "link_records") == 2, "refused text stores no record"); }

            // Delete removes the row, the search entry and the preview.
            File keep = pdf(staging, "three.pdf");
            LinkStore.Result gone = store.publish(record(UUID.randomUUID().toString(), "Temporary", "5".repeat(64), uuid), "temporary words", keep);
            File cached = store.preview(gone.id);
            WorkflowRepositoryChecks.require(cached != null, "a preview exists before delete");
            store.delete(gone.id);
            WorkflowRepositoryChecks.require(store.get(gone.id) == null && store.list("temporary").isEmpty() && !cached.exists(), "delete removes the record, its search entry and its preview");
            WorkflowRepositoryChecks.require(count(db, "sync_change") == journal && count(db, "documents") == documents, "the journal and documents are still untouched after every operation");

            // A version 10 library gains the link tables without losing anything.
            db.execSQL("DROP TABLE link_search"); db.execSQL("DROP TABLE link_records"); db.setVersion(10);
        } finally { helper.close(); }
        LibraryDatabase upgraded = new LibraryDatabase(isolated);
        try {
            SQLiteDatabase db = upgraded.getWritableDatabase();
            WorkflowRepositoryChecks.require(db.getVersion() == 12 && count(db, "link_records") == 0 && count(db, "link_search") == 0, "a version 10 library upgrades to 12 with empty link tables");
        } finally { upgraded.close(); }
        mark.accept("Link store: publish/repeat/replace/rename/delete, text+title search with excerpt, duplicate-hash report, refused text stores nothing, sync journal and documents untouched, v10 to v11 upgrade");
    }
}
