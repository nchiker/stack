package org.stackdocs.app;

import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** External process death with durable synthetic state, disposable package only. */
final class P1RestartChecks {
    static void run(Instrumentation instrumentation,String mode,String boundary,String key,Consumer<String> mark)throws Exception {
        Context target=instrumentation.getTargetContext();P1Checks.require(target.getPackageName().equals("org.stackdocs.app.checks"),"Disposable restart package");StackApplication app=(StackApplication)target.getApplicationContext();instrumentation.runOnMainSync(() -> {});P1BatchChecks.await(() -> app.library!=null&&app.batches!=null&&app.metadata!=null&&app.ocr!=null&&app.suggestions!=null,"Application startup");app.io.submit(() -> {}).get();
        File manifest=new File(new ScannerChecks.IsolatedContext(target,"p1-restart-"+key).getFilesDir(),"state.json");
        if(mode.equals("prepare"))app.io.submit(() -> {try{
            File fixture=P1CorpusChecks.render(new ScannerChecks.IsolatedContext(target),"p1-restart-fixture",Collections.singletonList("SYNTHETIC RESTART ORIGINAL"));
            Document keep=app.library.importDocument(Uri.fromFile(fixture));app.library.rename(keep.id,"Keep P1 restart");app.library.metadata.saveNotes(keep.id,"Restart preserved notes",0);app.library.metadata.draft(keep.id,"notes","Recovered pending input",1);
            String operation="",itemId="";
            if(boundary.equals("staged")||boundary.equals("committed")){
                operation=app.library.batches.create("import",Collections.singletonList("content://unavailable-after-death/source"),null);BatchStore.Item item=app.library.batches.items(operation).get(0);itemId=item.document;
                File source=new File(target.getFilesDir(),"batch_sources/"+operation+"/0.source");source.getParentFile().mkdirs();try(InputStream in=new FileInputStream(fixture);FileOutputStream out=new FileOutputStream(source)){LibraryRepository.copy(in,out,256L*1024*1024);out.getFD().sync();}
                if(boundary.equals("committed")){app.library.importDocument(Uri.fromFile(source),itemId,"Committed before death");app.library.metadata.saveNotes(itemId,"Saved item survives",0);}
                app.library.batches.item(item,"running","");app.library.batches.state(operation,"running");
            }else if(boundary.equals("delete")){
                Document doomed=app.library.importDocument(Uri.fromFile(fixture));itemId=doomed.id;operation=app.library.batches.create("delete",Collections.singletonList(itemId),Collections.singletonList(itemId));app.library.deleteDocument(itemId);app.library.batches.item(app.library.batches.items(operation).get(0),"running","");app.library.batches.state(operation,"running");
            }else if(boundary.equals("metadata")){
                app.library.metadata.save(keep.id,new DetailValue("manual:restart","text","Reference","User correction","","{}","manual","saved",0),0);
                app.library.database().execSQL("UPDATE metadata_work SET status='running',claim='interrupted',attempts=1 WHERE document_id=?",new Object[]{keep.id});
            }else throw new IllegalArgumentException("Unknown P1 boundary");
            JSONObject json=new JSONObject().put("operation",operation).put("item",itemId).put("keep",keep.id);try(FileOutputStream out=new FileOutputStream(manifest)){out.write(json.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            mark.accept("P1_RESTART_READY "+boundary);try{Thread.sleep(30000);}catch(InterruptedException interrupted){throw new IOException(interrupted);}throw new AssertionError("External stop not received");
        }catch(Exception error){throw new RuntimeException(error);}}).get();
        else {
            JSONObject json=new JSONObject(new String(ScannerChecks.bytes(manifest),StandardCharsets.UTF_8));String op=json.getString("operation"),keep=json.getString("keep"),item=json.getString("item");
            if(!op.isEmpty())P1BatchChecks.await(() -> app.io.submit(() -> {String state=app.library.batches.operation(op).state;return state.equals("done")||state.equals("failed");}).get(),"Recovered batch terminal");
            app.io.submit(() -> {P1Checks.require(app.library.document(keep).title.equals("Keep P1 restart")&&app.library.metadata.view(keep).notes.equals("Restart preserved notes")&&app.library.metadata.draft(keep,"notes",1,"").equals("Recovered pending input"),"Names/notes/draft survive actual process death");
                if(boundary.equals("delete"))P1Checks.require(!app.library.batches.exists(item),"Selected deletion recovery");
                else if(!op.isEmpty()){P1Checks.require(app.library.batches.operation(op).state.equals("done")&&app.library.batches.exists(item),"Durable staged/committed import recovered once");if(boundary.equals("committed"))P1Checks.require(app.library.metadata.view(item).notes.equals("Saved item survives"),"Existing committed item never replaced");}
                else P1Checks.require(app.library.metadata.view(keep).saved.stream().anyMatch(v -> v.value.equals("User correction")),"Manual detail survives interrupted claim");return true;}).get();
            mark.accept("P1 external force-stop/new process "+boundary+": stable import identity / scoped delete / authoritative notes, details and editor drafts preserved");
        }
    }
}
