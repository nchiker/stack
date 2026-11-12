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
                writeArchive(destination);
                boolean recorded;
                try{recorded=BackupHistory.completed(app,Clock.now());}catch(RuntimeException ignored){recorded=false;}
                message="Library backup saved. Keep it private; the archive is not encrypted."+(recorded?"":" The backup date could not be saved.");state="success";
            } catch(Exception error) {
                // A selected provider may not permit deletion or atomic replacement.
                if(error instanceof SizeMismatch){failure(error,"");return;}
                boolean removed=false;try{removed=DocumentsContract.deleteDocument(app.getContentResolver(),destination);}catch(Exception ignored){}
                failure(error," The destination "+(removed?"was removed.":"may contain an incomplete file. Delete it before using a retry."));
            }
        });
    }
    /** Receives the outcome of a scheduled backup on the transfer thread. */
    interface Done { void finished(boolean ok,String message,String name,long size); }
    /**
     * Scheduled backup: creates a new timestamped file in a folder the user picked earlier, checks the size the
     * folder reports, then removes older scheduled copies beyond {@code keep}. Returns false when another archive
     * operation is running, so the caller can retry later.
     */
    boolean exportToFolder(Uri tree,int keepDays,Done done) {
        if(busy()||state.equals("review"))return false;
        progress=new LibraryArchive.Progress();state="exporting";message="";
        transfers.execute(() -> {
            Uri file=null;long size=0;
            try {
                android.content.ContentResolver resolver=app.getContentResolver();
                Uri folder=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
                String name=AutoBackup.newName(Clock.now());
                file=DocumentsContract.createDocument(resolver,folder,"application/octet-stream",name);
                if(file==null)throw new IOException("The folder does not accept new files. Choose another folder.");
                size=writeArchive(file);
                int removed=AutoBackup.prune(resolver,tree,name,keepDays);
                try{BackupHistory.completed(app,Clock.now());}catch(RuntimeException ignored){}
                message="Library backup saved as "+name+"."+(removed>0?" Removed "+removed+" older automatic "+(removed==1?"backup.":"backups."):"")
                        +" Keep it private; the archive is not encrypted. If the folder belongs to a cloud app, that app finishes the upload.";
                state="success";done.finished(true,message,name,size);
            } catch(Exception error) {
                boolean kept=error instanceof SizeMismatch,removed=false;if(file!=null&&!kept)try{removed=DocumentsContract.deleteDocument(app.getContentResolver(),file);}catch(Exception ignored){}
                failure(error,file==null||kept?"":" The incomplete file "+(removed?"was removed.":"may still be in the folder."));
                done.finished(false,message,"",0);
            }
        });
        return true;
    }
    /** Streams a consistent snapshot to {@code destination}, then checks that the provider kept every byte. */
    private long writeArchive(Uri destination) throws Exception {
        long written;
        try(LibraryArchive.Snapshot snapshot=app.io.submit(() -> app.library.archive().snapshot(progress)).get()) {
            try(OutputStream raw=app.getContentResolver().openOutputStream(destination,"wt")) {
                if(raw==null)throw new IOException("The destination could not be opened. Choose another location.");stream=raw;
                CountingOutput output=new CountingOutput(raw);
                LibraryArchive.write(snapshot,output,progress);output.flush();written=output.count;
            } finally {stream=null;}
        }
        // Some providers (Google Drive) report 0 right after a write; only a non-zero mismatch is a failure.
        long reported=AutoBackup.reportedSize(app.getContentResolver(),destination);
        if(reported>0&&reported!=written)throw new SizeMismatch("The destination reports "+reported+" bytes but Stack wrote "+written+". The file was left in place; check it before relying on it.");
        return written;
    }
    /** The file may well be fine (providers report stale sizes), so it is never deleted on this failure. */
    private static final class SizeMismatch extends IOException { SizeMismatch(String message){super(message);} }
    private static final class CountingOutput extends java.io.FilterOutputStream {
        long count;
        CountingOutput(OutputStream out){super(out);}
        @Override public void write(int b) throws IOException {out.write(b);count++;}
        @Override public void write(byte[] b,int off,int len) throws IOException {out.write(b,off,len);count+=len;}
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
