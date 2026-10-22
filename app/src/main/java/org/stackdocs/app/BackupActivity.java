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
    private static final int BACKUP=71,RESTORE=72;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable tick=() -> {render();schedule();};
    private BackupScreen screen;
    private boolean resumed;
    private Runnable removeBack;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        screen=new BackupScreen(this,this::finish,() -> pick(true),() -> pick(false),
                () -> {app().archives.restore();render();},() -> {app().archives.cancel();render();},this::aboutBackups);
        screen.reset(() -> whenAllowed(() -> startActivity(new Intent(this,LibraryResetActivity.class))));
        Ui.install(this,screen.root);removeBack=Ui.backHandler(this,this::finish);
        if(state!=null && state.getBoolean("active") && app().archives.state.equals("idle")) app().archives.message="The previous operation was interrupted. Existing data was kept; select the file or destination again. Startup recovery removes uncommitted restored files.";
        render();
    }
    @Override protected void onResume(){super.onResume();resumed=true;render();schedule();}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(tick);if(removeBack!=null)removeBack.run();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("active",app().archives.busy()||app().archives.state.equals("review"));super.onSaveInstanceState(state);}
    private void schedule(){if(resumed)handler.postDelayed(tick,300);}
    private void render() {
        ArchiveController controller=app().archives;
        screen.history(BackupHistory.label(this));
        screen.render(controller.state,controller.progress.label,controller.message,controller.review(),controller.progress.canceled);
    }
    private void aboutBackups() {
        new AlertDialog.Builder(this).setTitle("About library backups")
                .setMessage("Your saved originals, page order and edits, names, notes, typed details, organization tags, stacks, recognized text and review choices are included. No account is needed.\n\nUnfinished scans and local consolidation recovery archives are excluded and stay on this device. Save an unfinished scan first to include it.\n\nBackup files contain private documents and are not encrypted. Protect the location you choose. Automatic Android cloud backup and device transfer are disabled.\n\nRestore adds documents and keeps your existing library. Selecting the same backup again imports another copy.\n\nIf saving fails or is canceled, your storage provider may leave an incomplete file. Delete that file and choose a destination again to retry.")
                .setPositiveButton("Got it",null).show();
    }
    private void pick(boolean saving) {
        Intent intent=new Intent(saving?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream");
        if(saving) intent.putExtra(Intent.EXTRA_TITLE,"Stack-"+new SimpleDateFormat("yyyy-MM-dd-HHmmss",Locale.ROOT).format(new Date())+".stackbackup");
        else intent.setType("*/*");
        try{startActivityForResult(intent,saving?BACKUP:RESTORE);}catch(ActivityNotFoundException error){new AlertDialog.Builder(this).setMessage("No document provider is available. Install or enable a local document picker, then retry.").setPositiveButton("OK",null).show();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        whenAllowed(() -> {if(request==BACKUP)app().archives.export(data.getData());if(request==RESTORE)app().archives.validate(data.getData());render();});
    }
}
