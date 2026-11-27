package org.stackdocs.app;

import android.content.ContentResolver;
import android.content.Context;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Test code only (folder sharing option A measurement): the probe steps, shared by FolderProbeActivity (foreground, picker) and FolderProbeReceiver (background user). */
final class FolderProbeCore {
    static final String TAG = "FP";
    private static final String[] COLS = FolderProbeActivity.COLS;
    private final Context ctx; private final String step, a, b, c;
    FolderProbeCore(Context ctx, String step, String a, String b, String c) { this.ctx = ctx; this.step = step; this.a = a; this.b = b; this.c = c; }
    private ContentResolver getContentResolver() { return ctx.getContentResolver(); }
    private File getFilesDir() { return ctx.getFilesDir(); }
    private SharedPreferences prefs0() { return ctx.getSharedPreferences("fp", Context.MODE_PRIVATE); }
    private static final int MODE_PRIVATE = Context.MODE_PRIVATE;
    private SharedPreferences getSharedPreferences(String n, int m) { return ctx.getSharedPreferences(n, m); }

    private SharedPreferences prefs() { return getSharedPreferences("fp", MODE_PRIVATE); }
    private Uri tree(String key) { return Uri.parse(prefs().getString(key == null ? "tree" : key, "")); }
    private Uri doc(String key, String id) { return DocumentsContract.buildDocumentUriUsingTree(tree(key), id); }
    private Uri kids(String key, String id) { return DocumentsContract.buildChildDocumentsUriUsingTree(tree(key), id); }

    void run() throws Exception {
        ContentResolver cr = getContentResolver();
        switch (step) {
            case "perms": {
                for (android.content.UriPermission p : cr.getPersistedUriPermissions()) Log.i(TAG, "PERM " + p.getUri() + " r=" + p.isReadPermission() + " w=" + p.isWritePermission());
                Log.i(TAG, "PERM count=" + cr.getPersistedUriPermissions().size());
                break;
            }
            case "release": {
                for (android.content.UriPermission p : cr.getPersistedUriPermissions()) {
                    cr.releasePersistableUriPermission(p.getUri(), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    Log.i(TAG, "RELEASED " + p.getUri());
                }
                prefs0().edit().clear().apply();
                break;
            }
            case "account": case "status": {
                Bundle r = cr.call(Uri.parse("content://app.filenfiles.stacklink"), step.equals("account") ? "account" : "partner_status", null, null);
                if (r == null) { Log.i(TAG, "CALL " + step + " null"); break; }
                for (String k : r.keySet()) {
                    String v = String.valueOf(r.get(k));
                    Log.i(TAG, "CALL " + step + " " + k + "=" + (k.equals("account_id") || k.equals("partner_id") ? "len" + v.length() + ":" + v.substring(0, Math.min(6, v.length())) : v));
                }
                break;
            }
            case "delegate": { // test only: pass this (Filen-pinned) test package's persisted READ grants on to the app under test, whose Android user id Filen does not pin
                String target = ctx.getPackageName().replaceAll("\\.test$", "");
                for (android.content.UriPermission p : cr.getPersistedUriPermissions()) {
                    if (!p.isReadPermission()) continue;
                    ctx.grantUriPermission(target, p.getUri(), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    Log.i(TAG, "DELEGATED " + p.getUri());
                }
                break;
            }
            case "put": { // a=key b=parentDocId c="name@@text": create a file and write the text
                String[] parts = c.split("@@", 2);
                Uri u = DocumentsContract.createDocument(cr, doc(a, b), "text/plain", parts[0]);
                try (ParcelFileDescriptor fd = cr.openFileDescriptor(u, "w"); OutputStream o = new ParcelFileDescriptor.AutoCloseOutputStream(fd)) { o.write(parts[1].getBytes("UTF-8")); }
                Log.i(TAG, "PUT " + parts[0] + " -> " + (u == null ? null : DocumentsContract.getDocumentId(u)));
                break;
            }
            case "walk": walk(a, b, false); break;
            case "readall": walk(a, b, true); break;
            case "list": {
                long t0 = SystemClock.elapsedRealtime();
                List<String[]> items = list(a, b, true);
                Log.i(TAG, "LIST " + b + " n=" + items.size() + " ms=" + (SystemClock.elapsedRealtime() - t0));
                break;
            }
            case "stat": {
                long t0 = SystemClock.elapsedRealtime();
                try (Cursor q = cr.query(doc(a, b), COLS, null, null, null)) {
                    Log.i(TAG, "STAT " + b + " ms=" + (SystemClock.elapsedRealtime() - t0) + " rows=" + (q == null ? -1 : q.getCount()));
                    if (q != null && q.moveToFirst()) Log.i(TAG, "STAT row " + row(q));
                }
                break;
            }
            case "open": {
                long t0 = SystemClock.elapsedRealtime();
                long[] r = readFile(doc(a, b));
                Log.i(TAG, "OPEN " + b + " bytes=" + r[0] + " ms=" + (SystemClock.elapsedRealtime() - t0));
                break;
            }
            case "rename": {
                Uri r = DocumentsContract.renameDocument(cr, doc(a, b), c);
                Log.i(TAG, "RENAME " + b + " -> " + r + " docId=" + (r == null ? null : DocumentsContract.getDocumentId(r)));
                break;
            }
            case "move": { // a=key b=docId c=srcParent:dstParent
                String[] p = c.split("@@");
                Uri r = DocumentsContract.moveDocument(cr, doc(a, b), doc(a, p[0]), doc(a, p[1]));
                Log.i(TAG, "MOVE " + b + " -> " + r + " docId=" + (r == null ? null : DocumentsContract.getDocumentId(r)));
                break;
            }
            case "delete": Log.i(TAG, "DELETE " + b + " ok=" + DocumentsContract.deleteDocument(cr, doc(a, b))); break;
            case "create": { // a=key b=parentDocId c=name ; empty file with text
                Uri u = DocumentsContract.createDocument(cr, doc(a, b), "application/octet-stream", c);
                Log.i(TAG, "CREATE " + c + " -> " + u);
                break;
            }
            case "overwrite": { // a=key b=docId c=text; opens "wt"
                long t0 = SystemClock.elapsedRealtime();
                try (ParcelFileDescriptor fd = cr.openFileDescriptor(doc(a, b), "wt"); OutputStream o = new ParcelFileDescriptor.AutoCloseOutputStream(fd)) { o.write(c.replace("\\r", "\r").replace("\\n", "\n").replace("\\t", "\t").getBytes("UTF-8")); }
                Log.i(TAG, "OVERWRITE " + b + " ms=" + (SystemClock.elapsedRealtime() - t0));
                break;
            }
            case "uptree": { // a=key (drive root grant), b=parentDocId, c=relative dir under external files dir
                File src = new File(getFilesDir(), c);
                long t0 = SystemClock.elapsedRealtime();
                int[] n = new int[2]; long[] bytes = new long[1];
                upload(a, b, src, n, bytes);
                long ms = SystemClock.elapsedRealtime() - t0;
                Log.i(TAG, "UPTREE done dirs=" + n[0] + " files=" + n[1] + " bytes=" + bytes[0] + " ms=" + ms);
                break;
            }
            default: Log.i(TAG, "unknown step " + step);
        }
    }

    private void upload(String key, String parentId, File dir, int[] n, long[] bytes) throws Exception {
        ContentResolver cr = getContentResolver();
        File[] kids = dir.listFiles();
        if (kids == null) return;
        java.util.Arrays.sort(kids);
        for (File f : kids) {
            if (f.isDirectory()) {
                Uri u = DocumentsContract.createDocument(cr, doc(key, parentId), DocumentsContract.Document.MIME_TYPE_DIR, f.getName());
                String id = DocumentsContract.getDocumentId(u); n[0]++;
                Log.i(TAG, "UP dir " + f.getName() + " -> " + id);
                upload(key, id, f, n, bytes);
            } else {
                String name = f.getName().toLowerCase(java.util.Locale.ROOT);
                String mime = name.endsWith(".pdf") ? "application/pdf" : name.endsWith(".jpg") ? "image/jpeg" : name.endsWith(".png") ? "image/png" : name.endsWith(".webp") ? "image/webp" : "application/octet-stream";
                long t0 = SystemClock.elapsedRealtime();
                Uri u = DocumentsContract.createDocument(cr, doc(key, parentId), mime, f.getName());
                try (InputStream in = new FileInputStream(f); ParcelFileDescriptor fd = cr.openFileDescriptor(u, "w"); OutputStream o = new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
                    byte[] buf = new byte[1 << 16]; int k; while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
                }
                n[1]++; bytes[0] += f.length();
                Log.i(TAG, "UP file " + f.getName() + " bytes=" + f.length() + " ms=" + (SystemClock.elapsedRealtime() - t0) + " id=" + DocumentsContract.getDocumentId(u));
            }
        }
    }

    private static String row(Cursor q) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < q.getColumnCount(); i++) s.append(q.getColumnName(i).replace("COLUMN_", "")).append('=').append(q.isNull(i) ? "null" : q.getString(i)).append(" | ");
        return s.toString();
    }

    /** Lists children (docId, name, mime, size, modified, flags). */
    private List<String[]> list(String key, String id, boolean verbose) {
        List<String[]> out = new ArrayList<>();
        try (Cursor q = getContentResolver().query(kids(key, id), COLS, null, null, null)) {
            if (q == null) { Log.i(TAG, "LIST " + id + " cursor=null"); return out; }
            Bundle ex = q.getExtras();
            if (verbose && out.isEmpty()) Log.i(TAG, "LIST " + id + " columns=" + java.util.Arrays.toString(q.getColumnNames()) + " extras=" + (ex == null ? "none" : ex.keySet())
                + " FRESH=" + (ex == null ? null : ex.get("app.filenfiles.extra.FRESH")) + " ACCOUNT=" + (ex == null || ex.get("app.filenfiles.extra.ACCOUNT") == null ? null : ex.getString("app.filenfiles.extra.ACCOUNT").substring(0, Math.min(6, ex.getString("app.filenfiles.extra.ACCOUNT").length()))));
            while (q.moveToNext()) {
                String[] r = new String[COLS.length];
                for (int i = 0; i < r.length; i++) r[i] = q.isNull(i) ? null : q.getString(i);
                out.add(r);
                if (verbose) Log.i(TAG, "  item " + row(q));
            }
        }
        return out;
    }

    private long[] readFile(Uri uri) throws Exception {
        long bytes = 0;
        try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r"); InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            byte[] buf = new byte[1 << 16]; int k;
            while ((k = in.read(buf)) > 0) bytes += k;
        }
        return new long[]{bytes};
    }

    private void walk(String key, String rootDoc, boolean download) throws Exception {
        String root = rootDoc != null && !rootDoc.isEmpty() ? rootDoc : DocumentsContract.getTreeDocumentId(tree(key));
        long t0 = SystemClock.elapsedRealtime();
        int[] counts = new int[2]; long[] total = new long[2]; // total[0]=bytes listed, total[1]=ms spent in listings
        File dl = download ? new File(getFilesDir(), "dl") : null;
        walkRec(key, root, 0, counts, total, dl);
        Log.i(TAG, "WALK " + (download ? "readall " : "") + "root=" + root + " dirs=" + counts[0] + " files=" + counts[1] + " bytes=" + total[0]
            + " listing_ms=" + total[1] + " wall_ms=" + (SystemClock.elapsedRealtime() - t0));
    }

    private void walkRec(String key, String id, int depth, int[] counts, long[] total, File dl) throws Exception {
        long t0 = SystemClock.elapsedRealtime();
        List<String[]> items = list(key, id, counts[0] == 0);
        long ms = SystemClock.elapsedRealtime() - t0;
        total[1] += ms; counts[0]++;
        Log.i(TAG, "DIR depth=" + depth + " id=" + id + " children=" + items.size() + " list_ms=" + ms);
        for (String[] r : items) {
            boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(r[2]);
            if (dir) walkRec(key, r[0], depth + 1, counts, total, dl);
            else {
                counts[1]++; try { total[0] += Long.parseLong(r[3]); } catch (Exception ignored) {}
                if (dl != null) {
                    long f0 = SystemClock.elapsedRealtime();
                    try {
                        long[] res = readFile(doc(key, r[0]));
                        long fms = SystemClock.elapsedRealtime() - f0;
                        double mb = res[0] / 1048576.0;
                        Log.i(TAG, "DL name=" + r[1] + " id=" + r[0] + " listed_size=" + r[3] + " read=" + res[0] + " ms=" + fms + " ms_per_MB=" + (mb > 0 ? Math.round(fms / mb) : -1));
                    } catch (Throwable t) { Log.i(TAG, "DL FAIL name=" + r[1] + " id=" + r[0] + " " + t.getClass().getName() + ": " + t.getMessage()); }
                }
            }
        }
    }
}
