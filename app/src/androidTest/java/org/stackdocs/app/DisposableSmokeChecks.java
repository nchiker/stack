package org.stackdocs.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Explicitly opted-in disposable physical handset only. Leaves authored examples in its real library. */
final class DisposableSmokeChecks {
    static void run(Instrumentation instrumentation,String phase,Consumer<String> mark)throws Exception {
        StackApplication app=(StackApplication)instrumentation.getTargetContext().getApplicationContext();
        app.io.submit(()->{}).get(30,TimeUnit.SECONDS);
        File proof=new File(app.getFilesDir(),"disposable-smoke.json");
        if("resume".equals(phase)) {
            verify(app,new JSONObject(new String(ScannerChecks.bytes(proof),java.nio.charset.StandardCharsets.UTF_8)));
            app.getSharedPreferences("navigation",0).edit().putString("query","").putString("tab","all").remove("stack").commit();
            mark.accept("New-process canonical library retains saved camera/scan originals, two-page OCR and page-two search after actual host force-stop");
            return;
        }
        if(!"exercise".equals(phase))throw new IllegalArgumentException("Unknown disposable smoke phase");
        Activity main=instrumentation.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {await(()->ui(instrumentation,()->find(main.getWindow().getDecorView(),"Search document names and text")!=null),"Original-package MainActivity launched");}
        finally {instrumentation.runOnMainSync(main::finish);}
        mark.accept("Original-package production MainActivity launches with native document search");

        // Authored printed pages prove OCR/search independently of whatever the camera sees.
        Document authored=app.io.submit(()->{
            ScanDraftStore store=new ScanDraftStore(app);
            // An interrupted disposable run may leave its captured review draft behind.
            // This branch is available only on the explicitly authorized spare phone.
            store.discard();
            for(String text:new String[]{"First authored page", "The blue lantern shines brightly"}) {
                Bitmap bitmap=Bitmap.createBitmap(1200,1600,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);
                Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);ink.setColor(Color.BLACK);ink.setTextSize(54);
                Canvas canvas=new Canvas(bitmap);canvas.drawText(text,70,200,ink);canvas.drawText("Invoice total 123.45",70,300,ink);
                try {store.append(ScannerChecks.jpeg(bitmap));}finally{bitmap.recycle();}
            }
            ScanDraftStore.Draft draft=store.load();
            Document saved=app.library.saveScan(store,draft,"Disposable OCR example",true,Collections.emptyList(),true);
            store.discard();return saved;
        }).get(30,TimeUnit.SECONDS);
        await(()->app.io.submit(()->{OcrStatus status=app.library.ocrStatus(authored.id,1);return status.finished==2&&status.failed==0;}).get(),"Real bundled worker finishes both authored pages");
        require(app.io.submit(()->{List<Document> hits=app.library.documents("lantern",null);return hits.stream().anyMatch(d->d.id.equals(authored.id)&&app.library.pages(authored.id).get(1).id.equals(d.matchPageId));}).get(),"OCR-only page-two query returns saved scan");
        mark.accept("Production scan commit and bundled OCR worker recognize authored pages; text-only lantern search identifies page two");
        Activity search=instrumentation.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            await(()->ui(instrumentation,()->find(search.getWindow().getDecorView(),"Search document names and text") instanceof EditText),"Search input ready");
            instrumentation.runOnMainSync(()->((EditText)find(search.getWindow().getDecorView(),"Search document names and text")).setText("lantern"));
            await(()->ui(instrumentation,()->find(search.getWindow().getDecorView(),"Disposable OCR example")!=null),"Production UI displays OCR search result");
        }finally{instrumentation.runOnMainSync(search::finish);}
        mark.accept("Production search field displays the document found through OCR text, not its title");

        List<String> before=app.io.submit(()->{List<String> ids=new ArrayList<>();for(Document d:app.library.documents("",null))ids.add(d.id);return ids;}).get();
        Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(ScanActivity.class.getName(),null,false);
        Activity camera=instrumentation.startActivitySync(new Intent(app,ScanActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            Activity active=camera;
            await(()->ui(instrumentation,()->{View capture=find(active.getWindow().getDecorView(),"Capture page");return capture!=null&&capture.isEnabled();}),"Physical rear camera reaches ready state");
            instrumentation.runOnMainSync(()->find(active.getWindow().getDecorView(),"Capture page").performClick());
            await(()->ui(instrumentation,()->{View save=find(active.getWindow().getDecorView(),"Save scan");return save!=null&&save.isEnabled();}),"Actual camera JPEG persisted into scan review");
            String photoHash=app.io.submit(()->{ScanDraftStore store=new ScanDraftStore(app);return SyncStore.hash(store.file(store.load().pages.get(0)),store.file(store.load().pages.get(0)).length());}).get();
            // Recreate the production review, retaining exactly one captured page.
            instrumentation.removeMonitor(monitor);monitor=instrumentation.addMonitor(ScanActivity.class.getName(),null,false);
            instrumentation.runOnMainSync(active::recreate);
            Activity recreated=monitor.waitForActivityWithTimeout(15000);require(recreated!=null&&recreated!=active,"Scan review recreated");camera=recreated;
            await(()->ui(instrumentation,()->{View save=find(recreated.getWindow().getDecorView(),"Save scan");return save!=null&&save.isEnabled();}),"Review returns after activity recreation");
            require(app.io.submit(()->{ScanDraftStore store=new ScanDraftStore(app);ScanDraftStore.Draft draft=store.load();return draft.pages.size()==1&&photoHash.equals(SyncStore.hash(store.file(draft.pages.get(0)),store.file(draft.pages.get(0)).length()));}).get(),"Recreation preserves single captured original");
            instrumentation.runOnMainSync(()->{EditText title=findInput(recreated.getWindow().getDecorView(),"Document name");require(title!=null,"Editable document name available");title.setText("Disposable camera example");find(recreated.getWindow().getDecorView(),"Save scan").performClick();});
            await(()->app.io.submit(()->app.library.documents("",null).stream().anyMatch(d->!before.contains(d.id))).get(),"Physical camera scan committed through Save scan");
            mark.accept("Physical rear camera opens/captures a JPEG; production review recreation preserves it; Save scan commits a durable camera document (scene/text quality not graded)");
        }finally{instrumentation.removeMonitor(monitor);Activity current=camera;instrumentation.runOnMainSync(()->{if(!current.isFinishing())current.finish();});}

        JSONObject saved=app.io.submit(()->{
            JSONObject data=new JSONObject().put("authored",authored.id);JSONArray originals=new JSONArray();
            for(Document doc:app.library.documents("",null))if(doc.id.equals(authored.id)||!before.contains(doc.id)){
                File directory=new File(new File(app.getFilesDir(),"documents"),doc.id);
                for(File file:directory.listFiles())if(file.isFile())originals.put(new JSONObject().put("document",doc.id).put("name",file.getName()).put("length",file.length()).put("sha256",SyncStore.hash(file,file.length())));
            }
            return data.put("originals",originals);
        }).get();
        try(FileOutputStream output=new FileOutputStream(proof)){output.write(saved.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));output.getFD().sync();}
        verify(app,saved);
        mark.accept("Saved original hashes and real OCR search verified; ready for host force-stop/relaunch verification");
    }
    private static void verify(StackApplication app,JSONObject data)throws Exception {
        app.io.submit(()->{try{
            JSONArray originals=data.getJSONArray("originals");require(originals.length()>=3,"Both authored and camera originals retained");
            for(int i=0;i<originals.length();i++){JSONObject row=originals.getJSONObject(i);require(app.library.document(row.getString("document"))!=null,"Saved document survives reopen");File file=new File(new File(new File(app.getFilesDir(),"documents"),row.getString("document")),row.getString("name"));require(SyncStore.hash(file,row.getLong("length")).equals(row.getString("sha256")),"Original digest survives reopen");}
            String id=data.getString("authored");require(app.library.documents("lantern",null).stream().anyMatch(d->d.id.equals(id)&&app.library.pages(id).get(1).id.equals(d.matchPageId)),"Page-two OCR query survives reopen");return true;
        }catch(Exception error){throw new RuntimeException(error);}}).get(30,TimeUnit.SECONDS);
    }
    private static EditText findInput(View view,String description){if(view instanceof EditText&&description.equals(String.valueOf(view.getContentDescription())))return (EditText)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){EditText found=findInput(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;}return null;}
    private static View find(View view,String text){if(text.equals(String.valueOf(view.getContentDescription()))||(view instanceof TextView&&text.equals(((TextView)view).getText().toString())))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;}
    private static <T>T ui(Instrumentation instrumentation,Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);instrumentation.runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private static void await(Callable<Boolean> condition,String label)throws Exception{long end=SystemClock.elapsedRealtime()+90000;while(SystemClock.elapsedRealtime()<end){if(condition.call())return;SystemClock.sleep(100);}throw new AssertionError(label);}
    private static void require(boolean success,String message){if(!success)throw new AssertionError(message);}
}
