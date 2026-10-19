package org.stackdocs.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** All methods are called on the application's single IO executor. */
final class LibraryRepository {
    private static final long MAX_IMPORT_BYTES = 256L * 1024 * 1024;
    // Explicit Unicode categories work on Android; UNICODE_CHARACTER_CLASS is unsupported.
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private final Context context;
    private final LibraryDatabase helper;
    private final File originals;
    private final File incoming;
    private RuntimeException initializationError;
    final SuggestionStore suggestions = new SuggestionStore(this::db);
    final BatchStore batches = new BatchStore(this);
    final MetadataStore metadata = new MetadataStore(this::db,suggestions);
    SQLiteDatabase database() { return db(); }
    private Runnable suggestionChanged = () -> {};
    void suggestionsAvailable() { if(!db().inTransaction())suggestionChanged.run(); }
    void setSuggestionChanged(Runnable listener) { suggestionChanged=listener; }
    private Runnable ocrChanged = () -> {};
    void setOcrChanged(Runnable listener) { ocrChanged = listener; }

    LibraryRepository(Context context) {
        this.context = context.getApplicationContext();
        helper = new LibraryDatabase(context);
        originals = new File(context.getFilesDir(), "documents");
        incoming = new File(context.getFilesDir(), "incoming");
    }

    void initialize() {
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            new LibraryArchive(context,this,db).recover();
            recoverOcr();
            suggestions.recover();
            metadata.recover();
            try(Cursor rows=db.rawQuery("SELECT id,title FROM documents",null)){while(rows.moveToNext())index(db,rows.getString(0),rows.getString(1));}
            mkdir(originals);
            mkdir(incoming);
            File[] abandoned = incoming.listFiles();
            if (abandoned != null) for (File file : abandoned) deleteTree(file);
            File[] owned = originals.listFiles();
            if (owned != null) for (File file : owned) {
                if (!exists(db, file.getName()) && !SyncStore.retainsOriginal(db,file.getName())) deleteTree(file);
            }
            File[] photoStaging=new File(context.getFilesDir(),"photo_staging").listFiles();if(photoStaging!=null)for(File file:photoStaging)deleteTree(file);
            File[] syncStaging=new File(context.getFilesDir(),"sync_stage").listFiles();if(syncStaging!=null)for(File file:syncStaging)deleteTree(file);
            File[] plans=new File(context.getCacheDir(),"pdf_plans").listFiles();if(plans!=null)for(File file:plans)deleteTree(file);
            File[] exports = new File(context.getCacheDir(), "exports").listFiles();
            if (exports != null) for (File file : exports) {
                if (System.currentTimeMillis() - file.lastModified() > 86_400_000L) deleteTree(file);
            }
        } catch (IOException | RuntimeException error) {
            initializationError = new IllegalStateException("The local library could not be opened. "
                    + "Free some storage and reopen Stack.", error);
            Log.e("StackLibrary", "Library initialization failed: "+error.getClass().getSimpleName());
        }
    }

    LibraryArchive archive() { return new LibraryArchive(context,this,db()); }
    void archiveChanged() { if(!db().inTransaction())ocrChanged.run(); if(!db().inTransaction())suggestionChanged.run(); }

    private SQLiteDatabase db() {
        if (initializationError != null) throw initializationError;
        return helper.getWritableDatabase();
    }

    List<StackCategory> stacks() {
        List<StackCategory> result = new ArrayList<>();
        try (Cursor cursor = db().rawQuery("SELECT s.*, COUNT(ds.document_id) AS total "
                + "FROM stacks s LEFT JOIN document_stacks ds ON ds.stack_id=s.id "
                + "GROUP BY s.id ORDER BY s.sort_order,s.rowid", null)) {
            while (cursor.moveToNext()) result.add(new StackCategory(string(cursor, "id"),
                    string(cursor, "name"), integer(cursor, "color"), integer(cursor, "visible") == 1,
                    integer(cursor, "total")));
        }
        return result;
    }

    List<Document> documents(String query, String stackId) { return documents(query,stackId,null); }
    List<Document> documents(String query, String stackId,String organization) {
        return documents(query,stackId,organization,"newest");
    }
    List<Document> documents(String query, String stackId,String organization,String order) {
        StringBuilder sql = new StringBuilder("SELECT d.*, (SELECT group_concat(m.stack_id) "
                + "FROM document_stacks m WHERE m.document_id=d.id) AS memberships, ");
        String match = searchTerms(query);
        boolean searching = query != null && !query.trim().isEmpty();
        if (searching && match.isEmpty()) return Collections.emptyList();
        sql.append(searching ? "snippet(search_index, '', '', ' … ', 2, 18)" : "''");
        sql.append(" AS excerpt FROM documents d ");
        if (searching) sql.append("JOIN search_index ON search_index.document_id=d.id ");
        sql.append("WHERE 1=1 ");
        List<String> arguments = new ArrayList<>();
        if (searching) {
            sql.append("AND search_index MATCH ? ");
            arguments.add(match);
        }
        if (stackId != null) {
            sql.append("AND EXISTS (SELECT 1 FROM document_stacks m WHERE m.document_id=d.id AND m.stack_id=?) ");
            arguments.add(stackId);
        }
        if(organization!=null){sql.append("AND EXISTS (SELECT 1 FROM document_tags t WHERE t.document_id=d.id AND t.tag_id=?) ");arguments.add(organization);}
        sql.append("name".equals(order)?"ORDER BY d.title COLLATE NOCASE, d.title, d.id":"ORDER BY d.added_at DESC, d.id");
        List<Document> result = new ArrayList<>();
        try (Cursor cursor = db().rawQuery(sql.toString(), arguments.toArray(new String[0]))) {
            while (cursor.moveToNext()) {
                Document found = readDocument(cursor);
                result.add(searching ? searchContext(found,match) : found);
            }
        }
        return result;
    }

    java.util.LinkedHashMap<String,String> organizations() {
        java.util.LinkedHashMap<String,String> out=new java.util.LinkedHashMap<>();
        try(Cursor rows=db().rawQuery("SELECT DISTINCT t.id,t.name FROM tags t JOIN document_tags d ON d.tag_id=t.id ORDER BY t.name COLLATE NOCASE",null)){while(rows.moveToNext())out.put(rows.getString(0),rows.getString(1));}return out;
    }

    private Document searchContext(Document document, String match) {
        String snippet=""; String pageId=null;
        boolean titleOnly;
        try (Cursor title = db().rawQuery("SELECT 1 FROM search_index WHERE document_id=? AND title MATCH ?",
                new String[]{document.id,match})) { titleOnly=title.moveToFirst(); }
        if (!titleOnly) {
            try (Cursor page = db().rawQuery("SELECT p.id,p.position,snippet(page_search,'','',' … ',3,18) "
                    + "FROM page_search JOIN pages p ON p.id=page_search.page_id "
                    + "WHERE page_search.document_id=? AND page_search MATCH ? ORDER BY p.position LIMIT 1",
                    new String[]{document.id,match})) {
                if (page.moveToFirst()) {
                    pageId=page.getString(0);
                    snippet="Page " + (page.getInt(1)+1) + " · " + page.getString(2).trim();
                } else {
                    String notes=metadata.view(document.id).notes;
                    snippet=(!notes.isEmpty()?"Notes / details · ":"Details / across document · ")+document.snippet;
                }
            }
        }
        return new Document(document.id,document.title,document.mime,document.addedAt,document.pageCount,
                document.stackIds,snippet,pageId);
    }

    Document document(String id) {
        try (Cursor cursor = db().rawQuery("SELECT d.*, group_concat(ds.stack_id) AS memberships, "
                + "'' AS excerpt FROM documents d LEFT JOIN document_stacks ds ON ds.document_id=d.id "
                + "WHERE d.id=? GROUP BY d.id", new String[]{id})) {
            if (!cursor.moveToFirst()) throw new IllegalArgumentException("This document is no longer in your library.");
            return readDocument(cursor);
        }
    }

    private static Document readDocument(Cursor cursor) {
        String memberships = string(cursor, "memberships");
        return new Document(string(cursor, "id"), string(cursor, "title"), string(cursor, "mime"),
                cursor.getLong(cursor.getColumnIndexOrThrow("added_at")), integer(cursor, "page_count"),
                memberships.isEmpty() ? Collections.emptyList() : Arrays.asList(memberships.split(",")),
                string(cursor, "excerpt").trim());
    }

    Document importDocument(Uri uri) throws IOException {return importDocument(uri,UUID.randomUUID().toString(),null);}
    Document importDocument(Uri uri,String id,String providedName) throws IOException {return importDocument(uri,id,providedName,() -> true,new Object());}
    Document importDocument(Uri uri,String id,String providedName,java.util.function.BooleanSupplier permitted,Object commitLock) throws IOException {
        db(); // Never ingest when the library is unavailable.
        if(!UUID.fromString(id).toString().equals(id))throw new IOException("Invalid import identity.");
        if(exists(db(),id))return document(id);
        File staging = new File(incoming, id);
        File destination = new File(originals, id);
        mkdir(staging);
        boolean committed = false;
        try {
            File source = new File(staging, "source");
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(source)) {
                if (input == null) throw new IOException("The selected file could not be opened.");
                copy(input, output, MAX_IMPORT_BYTES);
                output.getFD().sync();
            }
            String mime;
            int pages;
            byte[] prefix = new byte[5];
            try (InputStream input = new java.io.FileInputStream(source)) {
                if (input.read(prefix) < 5) throw new IOException("The selected file is empty or incomplete.");
            }
            if (Arrays.equals(prefix, new byte[]{'%', 'P', 'D', 'F', '-'})) {
                mime = "application/pdf";
                try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY);
                     PdfRenderer renderer = new PdfRenderer(descriptor)) {
                    pages = renderer.getPageCount();
                } catch (RuntimeException error) {
                    throw new IOException("This PDF could not be opened. It may be damaged or password-protected.", error);
                }
                if (pages < 1 || pages > 1000) throw new IOException("Choose a PDF with between 1 and 1,000 pages.");
            } else {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(source.getPath(), bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType == null) {
                    throw new IOException("Choose a supported image or a PDF document.");
                }
                if (!bounds.outMimeType.equals("image/jpeg") && !bounds.outMimeType.equals("image/png")
                        && !bounds.outMimeType.equals("image/webp")) {
                    throw new IOException("Choose a JPEG, PNG, or WebP image, or a PDF document.");
                }
                mime = bounds.outMimeType;
                pages = 1;
            }
            String title = providedName==null?displayName(uri):providedName.replaceAll("[\\p{Cntrl}]"," ").trim().replaceFirst("(?i)\\.(pdf|jpe?g|png|webp)$", "");
            if(title.isEmpty())title="Imported document";title=title.substring(0,Math.min(200,title.length()));
            if (!staging.renameTo(destination)) throw new IOException("The document could not be saved. Check available storage.");
            SQLiteDatabase database = db();
            synchronized(commitLock) {
            if(!permitted.getAsBoolean())throw new IOException("Photo import was disabled, locked or lost permission. Saved documents are kept.");
            database.beginTransaction();
            try {
                ContentValues values = new ContentValues();
                values.put("id", id);
                values.put("title", title);
                values.put("title_origin", "filename");
                values.put("mime", mime);
                values.put("added_at", System.currentTimeMillis());
                values.put("page_count", pages);
                values.put("initial_page_count", pages);
                database.insertOrThrow("documents", null, values);
                for (int position = 0; position < pages; position++) {
                    ContentValues page = new ContentValues();
                    page.put("id", UUID.randomUUID().toString());
                    page.put("document_id", id);
                    page.put("position", position);
                    page.put("ocr_state", "pending");
                    page.put("ocr_config", OcrModel.CONFIG);
                    page.put("source_page", position);
                    database.insertOrThrow("pages", null, page);
                }
                index(database, id, title);
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
            committed = true;
            }
            if(!db().inTransaction())ocrChanged.run();
            return document(id);
        } finally {
            if (!committed) {
                deleteTree(staging);
                deleteTree(destination);
            }
        }
    }

    private String displayName(Uri uri) {
        String name = "";
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = string(cursor, OpenableColumns.DISPLAY_NAME);
        } catch (RuntimeException ignored) {
            // The source has already been saved; an optional provider name must not prevent import.
        }
        name = name.replaceAll("[\\p{Cntrl}]", " ").trim().replaceFirst("(?i)\\.(pdf|jpe?g|png|webp)$", "");
        if (name.isEmpty()) name = "Document · " + DateFormat.getDateInstance().format(new Date());
        return name.substring(0, Math.min(name.length(), 200));
    }

    File source(Document document) throws IOException {
        try {
            UUID.fromString(document.id);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid document identifier.", error);
        }
        File file = new File(new File(originals, document.id), "source");
        if (!file.isFile()) throw new IOException("The original file is missing from this device.");
        return file;
    }

    List<PageState> pages(String documentId) {
        List<PageState> result = new ArrayList<>();
        try (Cursor cursor = db().rawQuery("SELECT * FROM pages WHERE document_id=? ORDER BY position", new String[]{documentId})) {
            while (cursor.moveToNext()) result.add(new PageState(string(cursor, "id"), string(cursor, "source_file"),
                    integer(cursor, "source_page"), integer(cursor, "rotation"),
                    PageGeometry.decode(string(cursor, "crop")), string(cursor, "enhancement"), integer(cursor, "flatten") != 0));
        }
        return result;
    }

    File originalFile(Document document,PageState page)throws IOException {return pageFile(document,page);}

    private File pageFile(Document document, PageState page) throws IOException {
        if (!UUID.fromString(document.id).toString().equals(document.id)
                || (!page.sourceFile.equals("source") && !page.sourceFile.matches("[a-f0-9-]{36}\\.jpg"))) {
            throw new IOException("Invalid stored page path.");
        }
        File file = new File(new File(originals, document.id), page.sourceFile);
        if (!file.isFile()) throw new IOException("The original page is missing from this device.");
        return file;
    }

    Bitmap renderPage(Document document, int position, int maxEdge) throws IOException {
        PageState page = pages(document.id).get(position);
        return PageRenderer.render(pageFile(document, page), document.mime, page, maxEdge);
    }

    Bitmap renderPage(Document document, PageState page, int maxEdge) throws IOException {
        return PageRenderer.render(pageFile(document, page), document.mime, page, maxEdge);
    }

    Bitmap originalPage(Document document, int position, int maxEdge) throws IOException {
        PageState page = pages(document.id).get(position);
        return PageRenderer.preview(pageFile(document, page), document.mime, page.sourcePage, maxEdge);
    }

    void editPage(String documentId, int position, PageState edited) {
        SQLiteDatabase database = db();
        database.beginTransaction();
        try {
            PageState current = pages(documentId).get(position);
            if (!current.id.equals(edited.id)) throw new IllegalArgumentException("This page changed. Reopen it to edit.");
            ContentValues values = new ContentValues();
            values.put("rotation", edited.rotation);
            values.put("crop", PageGeometry.encode(edited.crop));
            values.put("enhancement", edited.enhancement);
            values.put("flatten", edited.flatten ? 1 : 0);
            values.put("ocr_text", "");
            values.put("ocr_state", "pending");
            values.put("text_revision", -1);
            values.put("text_config", "");
            values.put("ocr_engine", "");
            values.put("language", "");
            values.put("ocr_claim", "");
            values.put("ocr_attempts", 0);
            values.put("ocr_error", "");
            values.put("ocr_config", OcrModel.CONFIG);
            database.execSQL("UPDATE pages SET content_revision=content_revision+1 WHERE id=?", new Object[]{edited.id});
            database.update("pages", values, "id=? AND document_id=?", new String[]{edited.id, documentId});
            index(database, documentId, document(documentId).title);
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
        if(!db().inTransaction())ocrChanged.run();
    }

    void removePage(String documentId, int position) {
        SQLiteDatabase database = db();
        database.beginTransaction();
        try {
            List<PageState> current = pages(documentId);
            if (current.size() < 2) throw new IllegalArgumentException("Keep at least one page, or delete the document from More.");
            PageState removed = current.remove(position);
            database.delete("page_search", "page_id=?", new String[]{removed.id});
            database.delete("pages", "id=? AND document_id=?", new String[]{removed.id, documentId});
            database.execSQL("UPDATE pages SET position=-position-1 WHERE document_id=?", new Object[]{documentId});
            for (int i = 0; i < current.size(); i++) {
                ContentValues order = new ContentValues(); order.put("position", i);
                database.update("pages", order, "id=?", new String[]{current.get(i).id});
            }
            ContentValues count = new ContentValues(); count.put("page_count", current.size());
            database.update("documents", count, "id=?", new String[]{documentId});
            index(database, documentId, document(documentId).title);
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
        if(!db().inTransaction())ocrChanged.run();
    }

    void exportPdf(Document document, OutputStream output) throws IOException {
        List<PageState> pages = pages(document.id);
        int originalCount;
        try (Cursor cursor = db().rawQuery("SELECT initial_page_count FROM documents WHERE id=?", new String[]{document.id})) {
            if (!cursor.moveToFirst()) throw new IOException("This document is no longer in your library.");
            originalCount = cursor.getInt(0);
        }
        boolean untouched = document.mime.equals("application/pdf") && pages.size() == originalCount;
        for (int i = 0; i < pages.size(); i++) {
            if (!pages.get(i).unchanged() || pages.get(i).sourcePage != i || !pages.get(i).sourceFile.equals("source")) untouched = false;
        }
        if (untouched) {
            try (FileInputStream input = new FileInputStream(source(document))) { copy(input, output, Long.MAX_VALUE); }
        } else {
            PageRenderer.exportPages(pages, page -> PageRenderer.render(pageFile(document, page), document.mime, page, 3200), output);
        }
    }

    Document saveScan(ScanDraftStore store, ScanDraftStore.Draft draft, String name) throws IOException {
        return saveScan(store,draft,name,true,java.util.Collections.emptyList(),false);
    }

    Document saveScan(ScanDraftStore store, ScanDraftStore.Draft draft, String name, boolean titleChosen,
                      List<String> memberships, boolean filingChosen) throws IOException {
        SQLiteDatabase database = db();
        UUID.fromString(draft.id);
        try (Cursor cursor = database.rawQuery("SELECT id FROM documents WHERE source_scan_id=?", new String[]{draft.id})) {
            if (cursor.moveToFirst()) return document(cursor.getString(0));
        }
        String title = cleanName(name);
        if (draft.pages.isEmpty() || draft.pages.size() > 100) throw new IOException("Add between 1 and 100 pages before saving.");
        String id = UUID.randomUUID().toString();
        File staging = new File(incoming, id), destination = new File(originals, id);
        mkdir(staging);
        boolean committed = false;
        try {
            long total = 0;
            for (PageState page : draft.pages) {
                File original = store.file(page);
                total += original.length();
                if (total > MAX_IMPORT_BYTES) throw new IOException("Keep a scan smaller than 256 MB.");
                try (FileInputStream input = new FileInputStream(original);
                     FileOutputStream output = new FileOutputStream(new File(staging, page.sourceFile))) {
                    copy(input, output, MAX_IMPORT_BYTES);
                    output.getFD().sync();
                }
            }
            if (!staging.renameTo(destination)) throw new IOException("Not enough space to save the scan.");
            database.beginTransaction();
            try {
                requireStacks(database,memberships);
                ContentValues values = new ContentValues();
                values.put("id", id); values.put("title", title); values.put("title_user_edited", titleChosen ? 1 : 0);
                values.put("title_origin", titleChosen ? "manual" : draft.choiceTracking ? "default" : "legacy");
                values.put("filing_origin", filingChosen ? "manual" : draft.choiceTracking ? "default" : "legacy");
                values.put("title_decision",titleChosen ? 1 : 0); values.put("filing_decision",filingChosen ? 1 : 0);
                values.put("mime", "application/x-stack-scan"); values.put("added_at", System.currentTimeMillis());
                values.put("page_count", draft.pages.size()); values.put("initial_page_count", draft.pages.size());
                values.put("source_scan_id", draft.id);
                database.insertOrThrow("documents", null, values);
                for (int i = 0; i < draft.pages.size(); i++) {
                    PageState page = draft.pages.get(i);
                    ContentValues row = new ContentValues();
                    row.put("id", page.id); row.put("document_id", id); row.put("position", i);
                    row.put("ocr_state", "pending");
                    row.put("ocr_config", OcrModel.CONFIG);
                    row.put("source_page", 0); row.put("source_file", page.sourceFile);
                    row.put("rotation", page.rotation); row.put("crop", PageGeometry.encode(page.crop));
                    row.put("enhancement", page.enhancement);
                    row.put("flatten", page.flatten ? 1 : 0);
                    database.insertOrThrow("pages", null, row);
                }
                for (String stackId : new java.util.LinkedHashSet<>(memberships)) {
                    ContentValues member=new ContentValues(); member.put("document_id",id); member.put("stack_id",stackId);
                    database.insertOrThrow("document_stacks",null,member);
                }
                index(database, id, title);
                database.setTransactionSuccessful();
            } finally { database.endTransaction(); }
            committed = true;
            if(!db().inTransaction())ocrChanged.run();
            return document(id);
        } finally {
            if (!committed) { deleteTree(staging); deleteTree(destination); }
        }
    }

    void rename(String id, String title) {
        title = cleanName(title);
        SQLiteDatabase database = db();
        database.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("title", title);
            values.put("title_user_edited", 1);
            if (database.update("documents", values, "id=?", new String[]{id}) != 1) {
                throw new IllegalArgumentException("This document is no longer in your library.");
            }
            database.execSQL("UPDATE documents SET title_origin='manual',title_decision=title_decision+1 WHERE id=?",new Object[]{id});
            suggestions.manualChoice(id,"title",title);
            index(database, id, title);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        if(!db().inTransaction())suggestionChanged.run();
    }

    void setMemberships(String id, List<String> stackIds) {
        SQLiteDatabase database = db();
        database.beginTransaction();
        try {
            if (!exists(database, id)) throw new IllegalArgumentException("This document is no longer in your library.");
            requireStacks(database,stackIds);
            database.delete("document_stacks", "document_id=?", new String[]{id});
            for (String stackId : new java.util.LinkedHashSet<>(stackIds)) {
                ContentValues values = new ContentValues();
                values.put("document_id", id);
                values.put("stack_id", stackId);
                database.insertOrThrow("document_stacks", null, values);
            }
            database.execSQL("UPDATE documents SET filing_origin='manual',filing_decision=filing_decision+1 WHERE id=?",new Object[]{id});
            suggestions.manualChoice(id,"filing",android.text.TextUtils.join(",",stackIds));
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        if(!db().inTransaction())suggestionChanged.run();
    }

    private static void requireStacks(SQLiteDatabase database,List<String> ids) {
        for(String id:new java.util.LinkedHashSet<>(ids)) {
            try(Cursor row=database.rawQuery("SELECT 1 FROM stacks WHERE id=?",new String[]{id})) {
                if(!row.moveToFirst()) throw new IllegalArgumentException("A selected stack was deleted. Choose stacks again before saving.");
            }
        }
    }

    void deleteDocument(String id) {
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid document identifier.");
        SQLiteDatabase database = db();
        database.beginTransaction();
        try {
            // Keep a deletion authoritative even if a photo process died between document commit and source acknowledgement.
            database.execSQL("UPDATE photo_history SET state='deleted' WHERE document_id=?",new Object[]{id});
            database.delete("page_search", "document_id=?", new String[]{id});
            database.delete("search_index", "document_id=?", new String[]{id});
            database.delete("documents", "id=?", new String[]{id});
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        File removedFiles=new File(originals,id);
        if (!SyncStore.retainsOriginal(database,id)) {
            deleteTree(removedFiles);
            if(removedFiles.exists())throw new IllegalStateException("The document was removed. Local original cleanup is pending; free storage and retry.");
        }
        if(!db().inTransaction())ocrChanged.run();
    }

    void createStack(String name) {
        ContentValues values = new ContentValues();
        values.put("id", UUID.randomUUID().toString());
        values.put("name", cleanName(name));
        values.put("color", 0xFFE4EDE2);
        values.put("sort_order", stacks().size());
        db().insertOrThrow("stacks", null, values);
        if(!db().inTransaction())suggestionChanged.run();
    }

    void setStackVisible(String id, boolean visible) {
        ContentValues values = new ContentValues();
        values.put("visible", visible ? 1 : 0);
        db().update("stacks", values, "id=?", new String[]{id});
    }

    void deleteStack(String id) {
        SQLiteDatabase database = db();
        database.beginTransaction();
        try {
            database.delete("stacks", "id=?", new String[]{id});
            writeStackOrder(stackIds(stacks()));
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
        if(!db().inTransaction())suggestionChanged.run();
    }

    static List<String> stackIds(List<StackCategory> catalog) {
        List<String> ids = new ArrayList<>();
        for (StackCategory stack : catalog) ids.add(stack.id);
        return ids;
    }

    static boolean sameStack(StackCategory a, StackCategory b) {
        return a.id.equals(b.id) && a.name.equals(b.name) && a.color == b.color && a.visible == b.visible;
    }

    private void requireStack(StackCategory expected) {
        for (StackCategory current : stacks()) if (current.id.equals(expected.id) && sameStack(current, expected)) return;
        throw new IllegalArgumentException("This stack changed. Reopen Edit stacks and try again.");
    }

    /** Compare-and-set: a stale drag/menu cannot overwrite a newer category decision. */
    void reorderStacks(List<StackCategory> expected, List<String> order) {
        SQLiteDatabase database = db();
        database.beginTransaction();
        try {
            List<StackCategory> current = stacks();
            if (current.size() != expected.size()) throw new IllegalArgumentException("Stacks changed. Review their order and try again.");
            for (int i = 0; i < current.size(); i++) if (!sameStack(current.get(i), expected.get(i)))
                throw new IllegalArgumentException("Stacks changed. Review their order and try again.");
            if (order.size() != current.size() || new java.util.HashSet<>(order).size() != order.size()
                    || !new java.util.HashSet<>(order).equals(new java.util.HashSet<>(stackIds(current))))
                throw new IllegalArgumentException("Choose each stack exactly once.");
            writeStackOrder(order);
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    private void writeStackOrder(List<String> order) {
        for (int i = 0; i < order.size(); i++) db().execSQL("UPDATE stacks SET sort_order=? WHERE id=?", new Object[]{i, order.get(i)});
    }

    void editStack(StackCategory expected, String name, boolean visible) {
        SQLiteDatabase database = db();
        database.beginTransaction();
        try {
            requireStack(expected);
            ContentValues values = new ContentValues();
            values.put("name", cleanName(name)); values.put("visible", visible ? 1 : 0);
            database.update("stacks", values, "id=?", new String[]{expected.id});
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
        if (!database.inTransaction()) suggestionChanged.run();
    }

    void deleteStack(StackCategory expected) {
        SQLiteDatabase database = db();
        database.beginTransaction();
        try { requireStack(expected); deleteStack(expected.id); database.setTransactionSuccessful(); }
        finally { database.endTransaction(); }
        if (!database.inTransaction()) suggestionChanged.run();
    }

    static String cleanName(String value) {
        String result = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (result.isEmpty()) throw new IllegalArgumentException("Enter a name before saving.");
        if (result.length() > 200) throw new IllegalArgumentException("Use a name with 200 characters or fewer.");
        return result;
    }

    static void index(SQLiteDatabase db, String id, String title) {
        StringBuilder body = new StringBuilder();
        try (Cursor cursor = db.rawQuery("SELECT id,ocr_text FROM pages WHERE document_id=? AND text_revision=content_revision ORDER BY position", new String[]{id})) {
            db.delete("page_search", "document_id=?", new String[]{id});
            while (cursor.moveToNext()) {
                body.append(cursor.getString(1)).append('\n');
                ContentValues page = new ContentValues();
                page.put("page_id", cursor.getString(0)); page.put("document_id", id); page.put("title",title); page.put("body", cursor.getString(1));
                db.insertOrThrow("page_search", null, page);
            }
        }
        try(Cursor row=db.rawQuery("SELECT notes FROM documents WHERE id=?",new String[]{id})){if(row.moveToFirst())body.append('\n').append(row.getString(0));}
        try(Cursor rows=db.rawQuery("SELECT label,value FROM details WHERE document_id=? AND state='saved' ORDER BY field_id",new String[]{id})){while(rows.moveToNext())body.append('\n').append(rows.getString(0)).append(' ').append(rows.getString(1));}
        db.delete("search_index", "document_id=?", new String[]{id});
        ContentValues values = new ContentValues();
        values.put("document_id", id);
        values.put("title", title);
        values.put("body", body.toString());
        db.insertOrThrow("search_index", null, values);
    }

    // All queue operations run on app.io. Heavy rendering/native work never does.
    static final int OCR_ATTEMPT_LIMIT = 3;

    void recoverOcr() {
        SQLiteDatabase database = db();
        database.beginTransaction();
        try {
            // A new engine/input signature is a one-time durable reprocess. Keep still-current old text searchable.
            database.execSQL("UPDATE pages SET ocr_config=?,ocr_state='pending',ocr_attempts=0,ocr_claim='',ocr_error='' WHERE ocr_config<>?",
                    new Object[]{OcrModel.CONFIG,OcrModel.CONFIG});
            database.execSQL("UPDATE pages SET ocr_state=CASE WHEN ocr_attempts>=? THEN 'failed' ELSE 'pending' END, "
                    + "ocr_claim='',ocr_error='interrupted' WHERE ocr_state='running'", new Object[]{OCR_ATTEMPT_LIMIT});
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    OcrJob claimOcr() {
        SQLiteDatabase database = db();
        database.beginTransaction();
        try (Cursor row = database.rawQuery("SELECT p.*,d.mime FROM pages p JOIN documents d ON d.id=p.document_id "
                + "WHERE p.ocr_state='pending' AND p.ocr_attempts<? ORDER BY d.added_at,p.position LIMIT 1",
                new String[]{String.valueOf(OCR_ATTEMPT_LIMIT)})) {
            if (!row.moveToFirst()) { database.setTransactionSuccessful(); return null; }
            String documentId = string(row, "document_id");
            PageState page = new PageState(string(row,"id"), string(row,"source_file"), integer(row,"source_page"),
                    integer(row,"rotation"), PageGeometry.decode(string(row,"crop")), string(row,"enhancement"), integer(row,"flatten") != 0);
            String claim = UUID.randomUUID().toString();
            long revision = row.getLong(row.getColumnIndexOrThrow("content_revision"));
            database.execSQL("UPDATE pages SET ocr_state='running',ocr_claim=?,ocr_attempts=ocr_attempts+1,ocr_error='' WHERE id=?",
                    new Object[]{claim,page.id});
            File source = null;
            try { source = pageFile(document(documentId), page); }
            catch (IOException | IllegalArgumentException unavailable) { /* Durable failed claim handled by worker. */ }
            OcrJob job = new OcrJob(documentId,page,claim,revision,string(row,"ocr_config"),string(row,"mime"),source);
            database.setTransactionSuccessful();
            return job;
        } finally { database.endTransaction(); }
    }

    boolean completeOcr(OcrJob job, String text, String errorCode) {
        SQLiteDatabase database = db();
        database.beginTransaction();
        try {
            ContentValues result = new ContentValues();
            boolean success = errorCode == null;
            result.put("ocr_state",success ? "succeeded" : "failed");
            result.put("ocr_claim", ""); result.put("ocr_error",success ? "" : errorCode.substring(0,Math.min(80,errorCode.length())));
            if (success) {
                result.put("ocr_text",text == null ? "" : text);
                result.put("text_revision",job.revision); result.put("text_config",job.config);
                result.put("ocr_engine","Tesseract4Android 4.9.0 / Tesseract 5.5.1 standard"); result.put("language","eng");
            }
            int count = database.update("pages",result,
                    "id=? AND document_id=? AND content_revision=? AND ocr_claim=? AND ocr_config=? AND ocr_state='running'",
                    new String[]{job.pageId,job.documentId,String.valueOf(job.revision),job.claim,job.config});
            if (count == 1) { index(database,job.documentId,document(job.documentId).title); }
            database.setTransactionSuccessful();
            return count == 1;
        } finally { database.endTransaction(); }
    }

    void retryOcr(String documentId, String pageId) {
        ContentValues retry = new ContentValues();
        retry.put("ocr_state","pending"); retry.put("ocr_claim", ""); retry.put("ocr_error", ""); retry.put("ocr_attempts",0);
        db().update("pages",retry,"id=? AND document_id=?",new String[]{pageId,documentId});
        // A still-current successful text revision survives retries and failures.
        if(!db().inTransaction())ocrChanged.run();
    }

    void retryFailedOcr(String documentId) {
        db().execSQL("UPDATE pages SET ocr_state='pending',ocr_claim='',ocr_error='',ocr_attempts=0 WHERE document_id=? AND ocr_state='failed'",new Object[]{documentId});
        if(!db().inTransaction())ocrChanged.run();
    }

    int pagePosition(String documentId, String pageId) {
        try (Cursor row = db().rawQuery("SELECT position FROM pages WHERE document_id=? AND id=?",new String[]{documentId,pageId})) {
            return row.moveToFirst() ? row.getInt(0) : 0;
        }
    }

    OcrStatus ocrStatus(String documentId, int position) {
        int total=0, finished=0, active=0, failed=0;
        String pageId="",state="pending"; boolean hasText=false;
        try (Cursor row = db().rawQuery("SELECT * FROM pages WHERE document_id=? ORDER BY position",new String[]{documentId})) {
            while (row.moveToNext()) {
                total++;
                String current=string(row,"ocr_state");
                if (current.equals("succeeded")) finished++;
                if (current.equals("pending") || current.equals("running")) active++;
                if (current.equals("failed")) failed++;
                if (integer(row,"position") == position) {
                    pageId=string(row,"id"); state=current;
                    hasText=row.getLong(row.getColumnIndexOrThrow("text_revision")) == row.getLong(row.getColumnIndexOrThrow("content_revision"))
                            && !string(row,"ocr_text").trim().isEmpty();
                }
            }
        }
        return new OcrStatus(pageId,state,hasText,total,finished,active,failed);
    }

    private static boolean exists(SQLiteDatabase db, String id) {
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM documents WHERE id=?", new String[]{id})) {
            return cursor.moveToFirst();
        }
    }

    private static String searchTerms(String query) {
        Matcher matcher = WORD.matcher(query == null ? "" : query);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 12 && matcher.find(); i++) {
            // Adjacency is AND in both FTS4 syntaxes; some Android builds treat "AND" as text.
            if (result.length() > 0) result.append(' ');
            String token = matcher.group();
            // FTS4 prefix operators belong inside the quoted token (unlike FTS5).
            result.append('"').append(token.substring(0, Math.min(80, token.length()))).append("*\"");
        }
        return result.toString();
    }

    static void copy(InputStream input, java.io.OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if(count==0)throw new IOException("The source stopped delivering data. Select it again to retry.");
            total += count;
            if (total > limit) throw new IOException("Choose a document smaller than 256 MB.");
            output.write(buffer, 0, count);
        }
    }

    private static void mkdir(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create local document storage.");
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (file.exists() && !file.delete()) Log.w("StackLibrary", "Deferred local file cleanup");
    }

    private static String string(Cursor cursor, String name) {
        int index = cursor.getColumnIndexOrThrow(name);
        return cursor.isNull(index) ? "" : cursor.getString(index);
    }

    private static int integer(Cursor cursor, String name) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(name));
    }
}
