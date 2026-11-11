package org.stackdocs.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Local storage-access flows. Selecting/validating a backup never mutates the library. */
public final class BackupActivity extends LibraryActivity {
    private static final int BACKUP=71,RESTORE=72,AUTO_FOLDER=73,FILEN_FOLDER=74;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable tick=() -> {render();schedule();};
    private BackupScreen screen;
    private boolean resumed;
    private Runnable removeBack;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        screen=new BackupScreen(this,this::finish,() -> pick(true),() -> pick(false),
                () -> {app().archives.restore();render();},() -> {app().archives.cancel();render();},this::aboutBackups);
        screen.automatic(AutoBackup.label(this),() -> whenAllowed(this::autoBackup));
        screen.keep(() -> whenAllowed(this::keepFor));
        screen.reset(() -> whenAllowed(() -> startActivity(new Intent(this,LibraryResetActivity.class))));
        Ui.install(this,screen.root);removeBack=Ui.backHandler(this,this::finish);
        if(state!=null && state.getBoolean("active") && app().archives.state.equals("idle")) app().archives.message="The previous operation was interrupted. Existing data was kept; select the file or destination again. Startup recovery removes uncommitted restored files.";
        render();
    }
    @Override protected void onResume(){super.onResume();resumed=true;render();schedule();AutoBackup.verifyPendingAsync(app());}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(tick);if(removeBack!=null)removeBack.run();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("active",app().archives.busy()||app().archives.state.equals("review"));super.onSaveInstanceState(state);}
    private void schedule(){if(resumed)handler.postDelayed(tick,300);}
    private void render() {
        ArchiveController controller=app().archives;
        screen.history(BackupHistory.label(this));
        screen.automaticCaption(AutoBackup.label(this));
        screen.keepCaption(AutoBackup.enabled(this),AutoBackup.keepLabel(this));
        screen.render(controller.state,controller.progress.label,controller.message,controller.review(),controller.progress.canceled);
    }
    private void aboutBackups() {
        new AlertDialog.Builder(this).setTitle("About library backups")
                .setMessage("Your saved originals, page order and edits, names, notes, typed details, organization tags, stacks, recognized text and review choices are included. No account is needed.\n\nUnfinished scans and local consolidation recovery archives are excluded and stay on this device. Save an unfinished scan first to include it.\n\nBackup files contain private documents and are not encrypted. Protect the location you choose. Automatic Android cloud backup and device transfer are disabled.\n\nRestore adds documents and keeps your existing library. Selecting the same backup again imports another copy.\n\nIf saving fails or is canceled, your storage provider may leave an incomplete file. Delete that file and choose a destination again to retry.")
                .setPositiveButton("Got it",null).show();
    }
    private void autoBackup() {
        if(!AutoBackup.enabled(this)) {chooseDestination();return;}
        String error=AutoBackup.lastError(this);
        new AlertDialog.Builder(this).setTitle("Automatic backup")
                .setMessage(AutoBackup.label(this)+(error.isEmpty()?"":"\n\n"+error))
                .setPositiveButton("Back up now",(d,w) -> {AutoBackup.run(app(),null);render();})
                .setNeutralButton("Change folder",(d,w) -> {if(FilenFolder.available(this))chooseDestination();else pickFolder();})
                .setNegativeButton("Turn off",(d,w) -> {AutoBackup.disable(this);render();}).show();
    }
    /** Filen Drive in one tap when Filen Files offers it; otherwise the system folder picker. */
    private void chooseDestination() {
        String kept=AutoBackup.keepDays(this)<0?"kept forever":"kept for "+AutoBackup.keepLabel(this);
        if(FilenFolder.available(this)) {
            new AlertDialog.Builder(this).setTitle("Automatic backup")
                    .setMessage("A backup every week in Filen Drive, each "+kept+".")
                    .setPositiveButton("Use Filen Drive",(d,w) -> filenFolder())
                    .setNeutralButton("Other folder",(d,w) -> pickFolder())
                    .setNegativeButton("Cancel",null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Automatic backup")
                .setMessage("Choose a folder and Stack saves a new backup there about once a week, each "+kept+". The folder can be on this phone, a USB drive or a cloud app such as Filen Files or Google Drive.\n\nStack does not encrypt backup files, so choose a private folder.")
                .setNegativeButton("Cancel",null).setPositiveButton("Choose folder",(d,w) -> pickFolder()).show();
    }
    private void pickFolder() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try{startActivityForResult(intent,AUTO_FOLDER);}catch(ActivityNotFoundException error){new AlertDialog.Builder(this).setMessage("No folder picker is available on this phone.").setPositiveButton("OK",null).show();}
    }
    /** One choice, applied at the next backup. The newest backup is never removed by this. */
    private void keepFor() {
        int current=0;for(int i=0;i<AutoBackup.KEEP_DAYS.length;i++)if(AutoBackup.KEEP_DAYS[i]==AutoBackup.keepDays(this))current=i;
        new AlertDialog.Builder(this).setTitle("Keep backups for")
                .setSingleChoiceItems(AutoBackup.KEEP_LABELS,current,(d,which) -> {AutoBackup.setKeepDays(this,AutoBackup.KEEP_DAYS[which]);d.dismiss();render();})
                .setNegativeButton("Cancel",null).show();
    }
    /** Asks Filen Files for its "Stack backups" folder: one Allow tap there, no browsing. */
    private void filenFolder() {
        try{startActivityForResult(FilenFolder.request(),FILEN_FOLDER);}catch(ActivityNotFoundException error){pickFolder();}
    }
    /** Keeps the folder permission (the provider may be slow, so off the UI thread), then makes the first backup. */
    private void folderChosen(Intent data) {
        android.net.Uri tree=data.getData();int flags=data.getFlags();
        new Thread(() -> {
            String name="";
            try(android.database.Cursor c=getContentResolver().query(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,android.provider.DocumentsContract.getTreeDocumentId(tree)),new String[]{android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)) {
                if(c!=null&&c.moveToFirst()&&!c.isNull(0))name=c.getString(0);
            } catch(RuntimeException ignored) {}
            String shown=name;
            try {
                AutoBackup.enable(this,tree,flags,shown);
                handler.post(() -> {AutoBackup.run(app(),null);render();});
            } catch(RuntimeException error) {
                handler.post(() -> new AlertDialog.Builder(this).setMessage("Stack could not keep access to that folder. Choose another folder.").setPositiveButton("OK",null).show());
            }
        },"Stack backup folder").start();
    }
    private void pick(boolean saving) {
        Intent intent=new Intent(saving?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream");
        if(saving) intent.putExtra(Intent.EXTRA_TITLE,"Stack-"+new SimpleDateFormat("yyyy-MM-dd-HHmmss",Locale.ROOT).format(new Date())+".stackbackup");
        else intent.setType("*/*");
        try{startActivityForResult(intent,saving?BACKUP:RESTORE);}catch(ActivityNotFoundException error){new AlertDialog.Builder(this).setMessage("No document provider is available. Install or enable a local document picker, then retry.").setPositiveButton("OK",null).show();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==FILEN_FOLDER && (result!=RESULT_OK||data==null||data.getData()==null)) {
            String why=FilenFolder.reason(data);
            if(!why.isEmpty()) new AlertDialog.Builder(this).setTitle("Filen Drive").setMessage(why)
                    .setPositiveButton("OK",null).setNeutralButton("Other folder",(d,w) -> pickFolder()).show();
            return;
        }
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(request==AUTO_FOLDER||request==FILEN_FOLDER){whenAllowed(() -> folderChosen(data));return;}
        whenAllowed(() -> {if(request==BACKUP)app().archives.export(data.getData());if(request==RESTORE)app().archives.validate(data.getData());render();});
    }
}
