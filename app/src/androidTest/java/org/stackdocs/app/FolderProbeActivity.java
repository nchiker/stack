package org.stackdocs.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Test code only (folder sharing option A measurement). Platform classes only, separate test-APK UID, like ExportGrantProbeActivity.
 * Driven by `am start --user N -n PKG/org.stackdocs.app.FolderProbeActivity --es step NAME --es a .. --es b .. --es c ..`.
 * Results go to logcat tag FP (never document contents, never keys). Tree grants are remembered by key in preferences.
 * Steps: pick perms walk readall list stat open uptree rename move delete overwrite create
 */
public final class FolderProbeActivity extends Activity {
    static final String TAG = "FP";
    static final String[] COLS = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS, "app.filenfiles.version"};
    private String step, a, b, c;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        step = in.getStringExtra("step"); a = in.getStringExtra("a"); b = in.getStringExtra("b"); c = in.getStringExtra("c");
        if (step == null) { finish(); return; }
        Log.i(TAG, "BEGIN " + step + " a=" + a + " b=" + b + " c=" + c);
        if (step.equals("pick")) {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | ("rw".equals(c) ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0));
            startActivityForResult(pick, 1);
            return;
        }
        if (step.equals("share") || step.equals("setup")) { // a=uuid (share), c="stop" -> STOP
            Intent i = new Intent(step.equals("share") ? "app.filenfiles.action.SHARE_WITH_PARTNER" : "app.filenfiles.action.PARTNER_SETUP").setPackage("app.filenfiles");
            if (step.equals("share")) { i.putExtra("app.filenfiles.extra.FILEN_UUID", a); if ("stop".equals(c)) i.putExtra("app.filenfiles.extra.STOP", true); }
            try { startActivityForResult(i, 2); } catch (Throwable t) { Log.i(TAG, "FAIL " + step + " " + t.getClass().getName() + ": " + t.getMessage()); finish(); }
            return;
        }
        new Thread(() -> {
            try { run(); } catch (Throwable t) { Log.i(TAG, "FAIL " + step + " " + t.getClass().getName() + ": " + t.getMessage()); }
            Log.i(TAG, "END " + step);
            runOnUiThread(this::finish);
        }).start();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == 2) { // share / setup result
            Log.i(TAG, "RESULT " + step + " code=" + res + " (OK=-1, CANCELED=0)");
            if (data != null) {
                Bundle ex = data.getExtras();
                if (ex != null) for (String k : ex.keySet()) Log.i(TAG, "RESULT extra " + k + "=" + ex.get(k));
                List<Uri> uris = new ArrayList<>();
                if (data.getData() != null) uris.add(data.getData());
                if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
                for (Uri u : uris) {
                    try {
                        getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        String id = DocumentsContract.getTreeDocumentId(u);
                        prefs().edit().putString(id.startsWith("filen-in") ? "in" : id.startsWith("filen-out") ? "out" : id, u.toString()).apply();
                        Log.i(TAG, "RESULT grant persisted treeDocId=" + id + " flags=" + data.getFlags());
                    } catch (Exception e) { Log.i(TAG, "RESULT grant FAIL " + e.getClass().getName() + ": " + e.getMessage()); }
                }
            }
            Log.i(TAG, "END " + step);
            finish();
            return;
        }
        Uri uri = data == null ? null : data.getData();
        Log.i(TAG, "PICK result=" + res + " uri=" + uri);
        if (uri != null) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | (data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
                prefs().edit().putString(a == null ? "tree" : a, uri.toString()).apply();
                Log.i(TAG, "PICK persisted key=" + (a == null ? "tree" : a) + " treeDocId=" + DocumentsContract.getTreeDocumentId(uri) + " flags=" + data.getFlags());
            } catch (Exception e) { Log.i(TAG, "PICK persist FAIL " + e.getClass().getName() + ": " + e.getMessage()); }
        }
        Log.i(TAG, "END pick");
        finish();
    }

    private SharedPreferences prefs() { return getSharedPreferences("fp", MODE_PRIVATE); }

    private void run() throws Exception { new FolderProbeCore(this, step, a, b, c).run(); }
}
