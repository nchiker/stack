package org.stackdocs.app;

import android.net.Uri;
import java.util.Collections;
import java.util.UUID;

/** Ordinary one-document flow uses the same durable staging/identity ledger as multi-import. */
final class ImportController {
    private final StackApplication app;
    volatile boolean busy;volatile String token="",documentId="",failure="";private String operation="";
    ImportController(StackApplication app){this.app=app;app.addLibraryListener(this::observe);}
    void start(Uri uri){if(busy||!app.access.allowed())return;busy=true;token=UUID.randomUUID().toString();documentId="";failure="";app.batches.importUris(Collections.singletonList(uri),id -> {operation=id;observe();});}
    private void observe(){if(!busy||operation.isEmpty())return;String id=operation;app.io.execute(() -> {BatchStore.Operation op=app.library.batches.operation(id);if(op.state.equals("done")||op.state.equals("failed")||op.state.equals("canceled")){if(!op.items.isEmpty()){BatchStore.Item item=op.items.get(0);if(item.state.equals("done"))documentId=item.document;else failure=item.error.isEmpty()?"Import interrupted. Open Imports & batch actions to retry.":item.error;}operation="";busy=false;app.notifyLibraryChanged();}});}
}
