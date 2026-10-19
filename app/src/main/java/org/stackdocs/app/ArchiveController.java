package org.stackdocs.app;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-owned transfer state survives rotation/backgrounding without retaining an Activity. */
final class ArchiveController {
    private final StackApplication app;
    private final ExecutorService transfers=Executors.newSingleThreadExecutor();
    volatile String state="idle",message="";
    volatile LibraryArchive.Progress progress=new LibraryArchive.Progress();
    private volatile Closeable stream;
    private LibraryArchive.Staged staged;
    private LibraryArchive archive;
    private String review="";
    ArchiveController(StackApplication app){this.app=app;}
    boolean busy(){return state.equals("exporting")||state.equals("validating")||state.equals("restoring");}
    String review(){return review;}
    void export(Uri destination) {
        if(busy()||state.equals("review"))return;
        progress=new LibraryArchive.Progress();state="exporting";message="";
        transfers.execute(() -> {
            try {
              try(LibraryArchive.Snapshot snapshot=app.io.submit(() -> app.library.archive().snapshot(progress)).get()) {
                try(OutputStream output=app.getContentResolver().openOutputStream(destination,"wt")) {
                    if(output==null)throw new IOException("The destination could not be opened. Choose another location.");stream=output;
                    LibraryArchive.write(snapshot,output,progress);output.flush();
                } finally {stream=null;}
              }
                boolean recorded;
                try{recorded=BackupHistory.completed(app,System.currentTimeMillis());}catch(RuntimeException ignored){recorded=false;}
                message="Library backup saved. Keep it private; the archive is not encrypted."+(recorded?"":" The backup date could not be saved.");state="success";
            } catch(Exception error) {
                // A selected provider may not permit deletion or atomic replacement.
                boolean removed=false;try{removed=DocumentsContract.deleteDocument(app.getContentResolver(),destination);}catch(Exception ignored){}
                failure(error," The destination "+(removed?"was removed.":"may contain an incomplete file. Delete it before using a retry."));
            }
        });
    }
    void validate(Uri source) {
        if(busy()||state.equals("review"))return;
        progress=new LibraryArchive.Progress();state="validating";message="";
        transfers.execute(() -> {
            LibraryArchive.Staged candidate=null;
            try {
                archive=app.io.submit(() -> app.library.archive()).get();
                try(InputStream input=app.getContentResolver().openInputStream(source)) {
                    if(input==null)throw new IOException("The backup could not be opened. Select it again.");stream=input;candidate=archive.stage(input,progress);
                } finally {stream=null;}
                progress.check();LibraryArchive.Staged selected=candidate;
                LibraryArchive.Plan plan=app.io.submit(() -> archive.plan(selected)).get();
                progress.check();staged=candidate;review=plan.summary();state="review";
            } catch(Exception error) {if(candidate!=null)candidate.close();failure(error,"");}
        });
    }
    void restore() {
        if(!state.equals("review")||staged==null)return;
        state="restoring";LibraryArchive.Staged selected=staged;
        transfers.execute(() -> {
            try {
                int count=app.io.submit(() -> archive.commit(selected,progress)).get();
                state="success";message=count+" documents restored. Existing documents and the unfinished scan were kept.";
            } catch(Exception error) {failure(error," Reopen Stack if cleanup is pending; existing documents are kept.");}
            finally {selected.close();staged=null;}
        });
    }
    void cancel() {
        if(state.equals("review")) {
            LibraryArchive.Staged selected=staged;staged=null;state="canceled";message="Restore canceled. Your library and unfinished scan are unchanged.";
            if(selected!=null)transfers.execute(selected::close);
        } else if(busy()) {
            progress.canceled=true;
            Closeable active=stream;
            // Closing a provider may block. Never close it on the UI/repository executor.
            if(active!=null)new Thread(() -> {try{active.close();}catch(IOException ignored){}},"Stack archive cancel").start();
        }
    }
    private void failure(Exception exception,String suffix) {
        Throwable error=exception;while(error.getCause()!=null)error=error.getCause();
        state=progress.canceled?"canceled":"failed";
        message=(progress.canceled?"Operation canceled.":error.getMessage()==null?"The operation failed. Free local space or choose another file/location and retry.":error.getMessage())+suffix;
    }
}
