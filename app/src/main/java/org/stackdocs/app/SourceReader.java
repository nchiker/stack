package org.stackdocs.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One source tree as the scan sees it. The real reader ({@link Saf}) asks Filen Files through DocumentsContract; a check can supply another one to
 * play a provider's answers exactly (stale, error, another account). Nothing here ever throws for a bad answer: the scan decides what each answer
 * allows (docs/FOLDER_SHARING_INTERFACES.md I6).
 */
interface SourceReader {
    /** The tree grant is still held. */
    boolean granted();

    /** The children of one folder. Never throws; see {@link Listing#status}. */
    Listing list(String parentDocId);

    /** Copies the file to `to` (at most `maxBytes`, stopping when `signal` is cancelled). FileNotFoundException only for a trustworthy "gone" answer. */
    void fetch(String docId, File to, long maxBytes, CancellationSignal signal) throws IOException;

    /** True only when opening the id throws FileNotFoundException that Filen means as "this item is gone". Success, null, any other error: false. */
    boolean gone(String docId);

    final class Entry {
        final String docId, name, mime, version;
        final long size, modified;
        final boolean dir;
        Entry(String docId, String name, String mime, long size, long modified, String version, boolean dir) {
            this.docId = docId; this.name = name; this.mime = mime; this.size = size; this.modified = modified; this.version = version; this.dir = dir;
        }
    }

    enum Status {
        /** Listed from the server just now (FRESH=true) with an account id. The only status that allows removals. */
        FRESH,
        /** Answered, but not fresh (FRESH=false, or no account id): never a basis for removing anything. */
        STALE,
        /** Filen says signed out or locked. */
        SIGNED_OUT,
        /** Null cursor, an exception, the grant is missing, anything else. */
        ERROR
    }

    final class Listing {
        final Status status;
        final List<Entry> entries;
        final String account;
        /** The account id Filen 0.5.0 and earlier sent for the same account (ACCOUNT_LEGACY), "" when Filen sends none: a source stored under it moves to `account` once. */
        final String legacyAccount;
        Listing(Status status, List<Entry> entries, String account) { this(status, entries, account, ""); }
        Listing(Status status, List<Entry> entries, String account, String legacyAccount) { this.status = status; this.entries = entries; this.account = account == null ? "" : account; this.legacyAccount = legacyAccount == null ? "" : legacyAccount; }
        static Listing of(Status status) { return new Listing(status, new ArrayList<Entry>(), ""); }
    }

    /** Filen Files through the Storage Access Framework, with the READ grant Stack holds on `tree`. */
    final class Saf implements SourceReader {
        static final String EXTRA_FRESH = "app.filenfiles.extra.FRESH";
        static final String EXTRA_ACCOUNT = "app.filenfiles.extra.ACCOUNT";
        static final String EXTRA_ACCOUNT_LEGACY = "app.filenfiles.extra.ACCOUNT_LEGACY";
        static final String COLUMN_VERSION = "app.filenfiles.version";
        private static final String[] COLUMNS = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, COLUMN_VERSION};

        private final Context context;
        private final Uri tree;
        private final SourceStore store;

        Saf(Context context, SourceStore store, Uri tree) { this.context = context.getApplicationContext(); this.store = store; this.tree = tree; }

        @Override public boolean granted() { return store.granted(tree); }

        @Override public Listing list(String parentDocId) {
            if (!granted()) return Listing.of(Status.ERROR);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId);
            try (Cursor c = context.getContentResolver().query(children, COLUMNS, null, null, null)) {
                if (c == null) return Listing.of(Status.ERROR);
                Bundle extras = c.getExtras();
                boolean fresh = extras != null && extras.getBoolean(EXTRA_FRESH, false);
                String account = extras == null ? null : extras.getString(EXTRA_ACCOUNT);
                String legacy = extras == null ? null : extras.getString(EXTRA_ACCOUNT_LEGACY);
                int id = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID), name = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        mime = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE), size = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE),
                        modified = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED), version = c.getColumnIndex(COLUMN_VERSION);
                List<Entry> entries = new ArrayList<>();
                while (c.moveToNext()) {
                    String type = c.isNull(mime) ? "" : c.getString(mime);
                    String shown = c.isNull(name) ? "" : c.getString(name);
                    if (c.isNull(id) || shown.isEmpty()) continue;
                    entries.add(new Entry(c.getString(id), shown, type, size < 0 || c.isNull(size) ? 0 : c.getLong(size), modified < 0 || c.isNull(modified) ? 0 : c.getLong(modified),
                            version < 0 || c.isNull(version) ? "" : c.getString(version), DocumentsContract.Document.MIME_TYPE_DIR.equals(type)));
                }
                boolean known = account != null && !account.isEmpty();
                return new Listing(fresh && known ? Status.FRESH : Status.STALE, entries, account, legacy);
            } catch (android.app.AuthenticationRequiredException signedOut) {
                return Listing.of(Status.SIGNED_OUT);
            } catch (RuntimeException | AssertionError failed) {
                return Listing.of(Status.ERROR);
            }
        }

        @Override public void fetch(String docId, File to, long maxBytes, CancellationSignal signal) throws IOException {
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
            ParcelFileDescriptor descriptor;
            try { descriptor = context.getContentResolver().openFileDescriptor(uri, "r", signal); }
            catch (android.app.AuthenticationRequiredException signedOut) { throw new IOException("Filen is signed out"); }
            catch (FileNotFoundException error) { if (trustworthy(error)) throw error; throw new IOException("Could not open the file: " + error.getClass().getSimpleName()); }
            catch (SecurityException error) { throw new IOException("No access to the file"); }
            if (descriptor == null) throw new IOException("Filen did not answer");
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(descriptor); FileOutputStream out = new FileOutputStream(to)) {
                byte[] buffer = new byte[1 << 16];
                long total = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (signal != null && signal.isCanceled()) throw new IOException("Cancelled");
                    total += n;
                    if (total > maxBytes) throw new IOException("Larger than the limit");
                    out.write(buffer, 0, n);
                }
                out.getFD().sync();
            }
        }

        /** The kind of the last answer {@link #gone} got (exception class or "opened"), for checks and logs; never an id or a name. */
        volatile String lastGone = "";

        @Override public boolean gone(String docId) {
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
            try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "r")) {
                lastGone = descriptor == null ? "null" : "opened";
                return false;   // it opened (or the provider answered null): not gone
            } catch (FileNotFoundException error) {
                lastGone = "FileNotFoundException" + (trustworthy(error) ? "" : " (platform wording)");
                return trustworthy(error);
            } catch (IOException | RuntimeException other) {
                lastGone = other.getClass().getSimpleName();
                return false;
            }
        }

        /** The platform also throws FileNotFoundException when the provider is missing or its process died; those say nothing about the file. */
        static boolean trustworthy(FileNotFoundException error) {
            String message = error.getMessage();
            if (message == null) return false;
            return !(message.startsWith("No content provider") || message.startsWith("Failed opening content provider") || message.startsWith("Failed to open")
                    || message.startsWith("Unknown document") || message.contains(" is a folder"));
        }
    }
}
