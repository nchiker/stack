package org.stackdocs.app;

import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

/**
 * Keeps a folder grant for partner sharing. The system picker and the Filen Files handshake both end here, so a tree is stored the same way
 * whichever route produced it. Runs off the UI thread; `done` and `failed` are posted back to the main thread.
 */
final class PartnerGrant {
    private PartnerGrant() {}

    /** The folder this phone writes its share archive into (read and write). */
    static void sharedFolder(StackApplication app, Intent data, Runnable done, Runnable failed) { keep(app, data, true, done, failed); }

    /** The folder the partner shares with this phone (read only). */
    static void receivedFolder(StackApplication app, Intent data, Runnable done, Runnable failed) { keep(app, data, false, done, failed); }

    private static void keep(StackApplication app, Intent data, boolean write, Runnable done, Runnable failed) {
        Uri tree = data.getData();
        int flags = data.getFlags();
        new Thread(() -> {
            String name = "";
            try (android.database.Cursor c = app.getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
            } catch (RuntimeException ignored) { /* the name is only for display */ }
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            try { if (write) PartnerShare.enable(app, tree, flags, name); else PartnerImport.enable(app, tree, flags, name); }
            catch (RuntimeException error) { if (failed != null) main.post(failed); }
            if (done != null) main.post(done);
        }, write ? "Stack sharing folder" : "Stack received folder").start();
    }
}
