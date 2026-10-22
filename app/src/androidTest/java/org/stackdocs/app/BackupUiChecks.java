package org.stackdocs.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Production application workers/controller and Backup screen; disposable package only. No picker simulation claim. */
final class BackupUiChecks {
    static void run(Instrumentation instrumentation,Consumer<String> mark)throws Exception {
        android.content.Context target=instrumentation.getTargetContext();
        if(!ScannerChecks.disposableLibraryTarget(target))throw new SecurityException("Production-flow check requires an explicitly disposable library");
        StackApplication app=(StackApplication)target.getApplicationContext();
        instrumentation.runOnMainSync(()->{});await(()->app.library!=null && app.archives!=null && app.imports!=null,"Application startup");app.io.submit(()->{}).get();
        WorkflowRepositoryChecks.require(app.io.submit(()->app.library.documents("",null).isEmpty()).get(),"Disposable first saved library is empty");
        WorkflowRepositoryChecks.require(OcrModel.valid(new File(OcrModel.install(target),"tessdata/eng.traineddata")),"Bundled model is locally available; host separately verifies offline radios");
        File fixture=OcrQueueChecks.fixture(new ScannerChecks.IsolatedContext(target),"production-flow.pdf");
        instrumentation.runOnMainSync(()->app.imports.start(Uri.fromFile(fixture)));
        await(()->!app.imports.busy,"Production import completes");WorkflowRepositoryChecks.require(app.imports.failure.isEmpty(),"Production import succeeded");String id=app.imports.documentId;
        await(()->app.io.submit(()->app.library.ocrStatus(id,0).finished==2 && app.library.suggestions.view(id).status.equals("ready")).get(),"Production bundled OCR/suggestions finish without downloads");
        app.io.submit(()->{app.library.rename(id,"Disposable manual name");app.library.setMemberships(id,Collections.emptyList());}).get();
        WorkflowRepositoryChecks.require(app.io.submit(()->app.library.documents("quartz",null).get(0).matchPageId.equals(app.library.pages(id).get(1).id)).get(),"Production later-page search");
        File backup=new File(target.getCacheDir(),"production-synthetic.stackbackup");
        target.getSharedPreferences("backup_history",android.content.Context.MODE_PRIVATE).edit().clear().commit();
        WorkflowRepositoryChecks.require(BackupHistory.last(target)==0,"No invented local backup history");
        Activity screen=instrumentation.startActivitySync(new Intent(target,BackupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(()->app.archives.export(Uri.fromFile(backup)));await(()->app.archives.state.equals("success")||app.archives.state.equals("failed"),"Production backup completion");WorkflowRepositoryChecks.require(app.archives.state.equals("success"),"Production backup success: "+app.archives.message);
            long completed=BackupHistory.last(target);WorkflowRepositoryChecks.require(completed>0,"Successful closed archive records date");
            SystemClock.sleep(400);instrumentation.runOnMainSync(()->WorkflowRepositoryChecks.require(hasText(screen.getWindow().getDecorView(),"Last backup:"),"Backup date visible"));
            instrumentation.runOnMainSync(()->app.archives.validate(Uri.fromFile(backup)));await(()->app.archives.state.equals("review")||app.archives.state.equals("failed"),"Production restore review");WorkflowRepositoryChecks.require(app.archives.state.equals("review"),"Production validate success: "+app.archives.message);
            WorkflowRepositoryChecks.require(app.io.submit(()->app.library.documents("",null).size()==1).get(),"Review leaves library unchanged");
            SystemClock.sleep(400);instrumentation.runOnMainSync(()->{WorkflowRepositoryChecks.require(hasText(screen.getWindow().getDecorView(),"Add restored documents"),"Discoverable reviewed commit control");WorkflowRepositoryChecks.require(hasText(screen.getWindow().getDecorView(),"not encrypted"),"Protection limits visible");});
            instrumentation.runOnMainSync(app.archives::cancel);WorkflowRepositoryChecks.require(app.archives.state.equals("canceled"),"Cancel review");
            instrumentation.runOnMainSync(()->app.archives.validate(Uri.fromFile(backup)));await(()->app.archives.state.equals("review")||app.archives.state.equals("failed"),"Retry review");instrumentation.runOnMainSync(app.archives::restore);await(()->app.archives.state.equals("success")||app.archives.state.equals("failed"),"Production restore completion");WorkflowRepositoryChecks.require(app.archives.state.equals("success"),"Production restore success: "+app.archives.message);
            WorkflowRepositoryChecks.require(app.io.submit(()->app.library.documents("Disposable manual",null).size()==2 && app.library.document(id).stackIds.isEmpty()).get(),"Production additive restore preserves manual name/explicit empty filing");
            WorkflowRepositoryChecks.require(BackupHistory.last(target)==completed,"Restore/cancel does not claim a new backup");
            instrumentation.runOnMainSync(()->app.archives.export(Uri.fromFile(new File(target.getCacheDir(),"missing-parent/denied.stackbackup"))));await(()->!app.archives.busy(),"Failed export completes");
            WorkflowRepositoryChecks.require(app.archives.state.equals("failed")&&BackupHistory.last(target)==completed&&backup.isFile(),"Failed destination keeps last successful date and prior archive");
            mark.accept("Local successful-backup date: empty default, visible after provider write/flush/close, unchanged by canceled review/additive restore/failed destination; synthetic archive only");
            mark.accept("Fresh disposable saved-library production import→bundled OCR/suggestions→manual choices→later-page search→local backup→review/cancel/retry/additive restore; bundled model, no account/download; optional LAN permission does not establish a core network dependency (file URI boundary; host records radio state separately, no system-picker claim)");
        }finally{instrumentation.runOnMainSync(screen::finish);}
    }
    private static boolean hasText(View view,String needle){if(view instanceof TextView && ((TextView)view).getText().toString().contains(needle))return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hasText(((ViewGroup)view).getChildAt(i),needle))return true;return false;}
    private static void await(Callable<Boolean> condition,String message)throws Exception {long until=SystemClock.elapsedRealtime()+60000;while(SystemClock.elapsedRealtime()<until){if(condition.call())return;Thread.sleep(50);}throw new AssertionError(message);}
}
