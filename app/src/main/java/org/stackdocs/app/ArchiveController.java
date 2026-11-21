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
    private boolean unencrypted;
    ArchiveController(StackApplication app){this.app=app;}
    boolean busy(){return state.equals("exporting")||state.equals("validating")||state.equals("restoring");}
    String review(){return review;}
    /** True while a reviewed archive has no encryption (so no tamper check); restoring it needs an explicit confirmation. */
    boolean reviewUnencrypted(){return unencrypted;}
    void export(Uri destination) {export(destination,false);}
    /** {@code plainLinks}: the user agreed to put linked documents into an unencrypted backup (ignored when encryption is set up). */
    void export(Uri destination,boolean plainLinks) {
        if(busy()||state.equals("review"))return;
        progress=new LibraryArchive.Progress();state="exporting";message="";
        transfers.execute(() -> {
            try {
                writeArchive(destination,plainLinks);
                boolean recorded;
                try{recorded=BackupHistory.completed(app,Clock.now());}catch(RuntimeException ignored){recorded=false;}
                message="Library backup saved. "+note+(recorded?"":" The backup date could not be saved.");state="success";
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
                        +" "+note+" If the folder belongs to a cloud app, that app finishes the upload.";
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
    private volatile String note="";
    private long writeArchive(Uri destination) throws Exception {return writeArchive(destination,false);}
    private long writeArchive(Uri destination,boolean plainLinks) throws Exception {
        long written;
        // A configured but unusable key is an error: a backup is never silently written unencrypted instead.
        BackupKeys.Slots slots=BackupKeys.configured(app)?BackupKeys.load(app):null;
        int mode=slots!=null?LibraryArchive.LINKS_ENCRYPTED:plainLinks?LibraryArchive.LINKS_INCLUDED:LibraryArchive.LINKS_NONE;
        int left=mode==LibraryArchive.LINKS_NONE?app.io.submit(() -> app.library.links.list("").size()).get():0;
        note=slots!=null?"Encrypted. Keep the recovery key safe in Key Vault; without it this backup cannot be opened."
                :"Keep it private; the archive is not encrypted."+(left>0?" "+left+" linked "+(left==1?"document was":"documents were")+" left out. Turn on backup encryption to include them.":"");
        try(LibraryArchive.Snapshot snapshot=app.io.submit(() -> app.library.archive().snapshot(progress,false,mode)).get()) {
            try(OutputStream raw=app.getContentResolver().openOutputStream(destination,"wt")) {
                if(raw==null)throw new IOException("The destination could not be opened. Choose another location.");stream=raw;
                CountingOutput output=new CountingOutput(raw);
                if(slots!=null) {
                    BackupEnvelope.Writer writer=BackupEnvelope.encrypt(output,slots.salt,slots.iterations,slots.passphraseKey,slots.recoveryKey,new java.security.SecureRandom()::nextBytes);
                    LibraryArchive.write(snapshot,writer,progress);writer.finish();
                } else LibraryArchive.write(snapshot,output,progress);
                output.flush();written=output.count;
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
    void validate(Uri source) {validate(source,null,null);}
    /** {@code passphrase} or {@code recoveryKey} (32 bytes) opens an encrypted backup; both are ignored for a plain one. */
    void validate(Uri source,String passphrase,byte[] recoveryKey) {
        if(busy()||state.equals("review"))return;
        progress=new LibraryArchive.Progress();state="validating";message="";
        transfers.execute(() -> {
            LibraryArchive.Staged candidate=null;
            try {
                archive=app.io.submit(() -> app.library.archive()).get();
                boolean encrypted=false;
                try(InputStream input=app.getContentResolver().openInputStream(source)) {
                    if(input==null)throw new IOException("The backup could not be opened. Select it again.");stream=input;
                    java.io.BufferedInputStream buffered=new java.io.BufferedInputStream(input,65536);buffered.mark(16);
                    byte[] head=new byte[BackupEnvelope.MAGIC.length];int got=0;while(got<head.length){int n=buffered.read(head,got,head.length-got);if(n<0)break;got+=n;}buffered.reset();
                    encrypted=java.util.Arrays.equals(head,BackupEnvelope.MAGIC);
                    if(encrypted) {
                        if(passphrase==null&&recoveryKey==null)throw new IOException("This backup is encrypted. Enter the passphrase or the recovery key.");
                        try(BackupEnvelope.Reader plain=BackupEnvelope.decrypt(buffered,passphrase,recoveryKey)) {
                            candidate=archive.stage(plain,progress);plain.finish();
                            if(candidate.manifest.getInt("version")!=4)throw new IOException("The encrypted backup has an unexpected inner format.");
                        }
                    } else candidate=archive.stage(buffered,progress);
                } finally {stream=null;}
                unencrypted=!encrypted;
                progress.check();LibraryArchive.Staged selected=candidate;
                LibraryArchive.Plan plan=app.io.submit(() -> archive.plan(selected)).get();
                progress.check();staged=candidate;String made="";try{made="This backup was made "+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(new java.util.Date(selected.manifest.getLong("created_at")))+". Check it is the one you expect. ";}catch(org.json.JSONException ignored){}
                review=made+plan.summary()+(unencrypted?" This backup is not encrypted, so it cannot be checked for tampering. Make sure it is yours.":"");state="review";
            } catch(Exception error) {if(candidate!=null)candidate.close();failure(error,"");}
        });
    }
    void restore() {
        if(!state.equals("review")||staged==null)return;
        state="restoring";LibraryArchive.Staged selected=staged;
        transfers.execute(() -> {
            try {
                int linkCount=selected.plan==null?0:selected.plan.links.size(),skipped=selected.plan==null?0:selected.plan.skippedLinks;
                int count=app.io.submit(() -> archive.commit(selected,progress)).get();
                state="success";message=count+" documents restored"+(linkCount>0?" and "+linkCount+" linked documents":"")+(skipped>0?"; "+skipped+" linked documents already here were kept":"")+". Existing documents and the unfinished scan were kept.";
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
