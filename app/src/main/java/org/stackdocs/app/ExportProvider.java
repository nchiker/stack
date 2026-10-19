package org.stackdocs.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.UUID;

/** Temporary read-only grants expose individual cached PDF exports only. */
public final class ExportProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File file(Uri uri) throws FileNotFoundException {
        if (uri.getPathSegments().size() != 2) throw new FileNotFoundException("Unknown export");
        String token = uri.getPathSegments().get(0);
        try {
            if (!UUID.fromString(token).toString().equals(token)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw new FileNotFoundException("Unknown export");
        }
        File file = new File(new File(getContext().getCacheDir(), "exports"), token + (uri.getLastPathSegment()!=null&&uri.getLastPathSegment().endsWith(".zip")?".zip":".pdf"));
        if (!file.isFile()) throw new FileNotFoundException("This export has expired");
        return file;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Exports are read-only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return uri.getLastPathSegment()!=null&&uri.getLastPathSegment().endsWith(".zip")?"application/zip":"application/pdf"; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        try {
            File file = file(uri);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = uri.getLastPathSegment();
                if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
            }
            cursor.addRow(values);
        } catch (FileNotFoundException ignored) {
            // An expired grant has no document metadata.
        }
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException("Read-only"); }
}
