package org.stackdocs.app;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** Production activities in disposable library, local configuration overrides, synthetic view captures only. */
final class P1UiChecks {
    static volatile Configuration configuration;
    static void run(Instrumentation instrumentation,Consumer<String> mark)throws Exception {
        Context target=instrumentation.getTargetContext();P1Checks.require(target.getPackageName().equals("org.stackdocs.app.checks"),"Disposable UI package");target.getSharedPreferences("local_policy",Context.MODE_PRIVATE).edit().putBoolean("app_lock",false).commit();StackApplication app=(StackApplication)target.getApplicationContext();instrumentation.runOnMainSync(() -> {});P1BatchChecks.await(() -> app.library!=null&&app.batches!=null&&app.metadata!=null&&app.ocr!=null&&app.suggestions!=null,"Application startup");app.io.submit(() -> {}).get();
        String id=app.io.submit(() -> {Document doc=app.library.importDocument(android.net.Uri.fromFile(P1CorpusChecks.render(new ScannerChecks.IsolatedContext(target),"visual-p1",Arrays.asList("Willow Credit Union\nInvoice date: 2026-10-08\nTotal: USD 24.50","Later page fine-print quartz"))));
            app.library.database().execSQL("UPDATE pages SET ocr_text='Willow Credit Union'||char(10)||'Invoice date: 2026-10-08'||char(10)||'Total: USD 24.50',ocr_state='succeeded',text_revision=content_revision,text_config=ocr_config,ocr_claim='' WHERE document_id=?",new Object[]{doc.id});
            app.library.metadata.save(doc.id,new DetailValue("amount:total","amount","Total","24.50","USD","{}","dismissed","rejected",0),0);
            app.library.rename(doc.id,"Synthetic fine-print statement");app.library.metadata.saveNotes(doc.id,"Optional notes · synthetic quartz reminder",0);app.metadata.kick();return doc.id;}).get();
        P1BatchChecks.await(() -> app.io.submit(() -> app.library.metadata.view(id).status.equals("ready")).get(),"Production metadata worker ready");
        // Exercise actual editor validation/save, with no global clipboard or permission mutation.
        configuration=new Configuration(target.getResources().getConfiguration());configuration.screenWidthDp=320;
        Activity notesEditor=open(instrumentation,new Intent(target,MetadataEditorActivity.class).putExtra("document_id",id).putExtra("field_id","notes").putExtra("kind","text"));
        P1BatchChecks.await(() -> {boolean[] ready={false};instrumentation.runOnMainSync(() -> ready[0]=findButton(notesEditor.getWindow().getDecorView(),"Save").isEnabled());return ready[0];},"Notes editor loaded");
        instrumentation.runOnMainSync(() -> {findInput(notesEditor.getWindow().getDecorView(),"Notes (up to 20,000 characters)").setText("Synthetic UI notes quartz reminder");findButton(notesEditor.getWindow().getDecorView(),"Save").performClick();});
        P1BatchChecks.await(() -> app.io.submit(() -> app.library.metadata.view(id).notes.equals("Synthetic UI notes quartz reminder")).get(),"Notes saved through production editor");
        P1BatchChecks.await(notesEditor::isDestroyed,"Notes editor finished before next launch");
        Activity detailEditor=open(instrumentation,new Intent(target,MetadataEditorActivity.class).putExtra("document_id",id).putExtra("field_id","manual:ui-saved").putExtra("kind","amount"));
        P1BatchChecks.await(() -> {boolean[] ready={false};instrumentation.runOnMainSync(() -> ready[0]=findButton(detailEditor.getWindow().getDecorView(),"Save").isEnabled());return ready[0];},"Detail editor loaded");
        instrumentation.runOnMainSync(() -> {findInput(detailEditor.getWindow().getDecorView(),"Value").setText("invalid amount");findButton(detailEditor.getWindow().getDecorView(),"Save").performClick();});
        P1Checks.require(app.io.submit(() -> app.library.metadata.view(id).saved.stream().noneMatch(v -> v.field.equals("manual:ui-saved"))).get(),"Invalid detail did not save");
        instrumentation.runOnMainSync(() -> {findInput(detailEditor.getWindow().getDecorView(),"Value").setText("23.40");findInput(detailEditor.getWindow().getDecorView(),"Currency (USD or unknown)").setText("USD");findButton(detailEditor.getWindow().getDecorView(),"Save").performClick();});
        P1BatchChecks.await(() -> app.io.submit(() -> app.library.metadata.view(id).saved.stream().anyMatch(v -> v.field.equals("manual:ui-saved")&&v.value.equals("23.40")&&v.qualifier.equals("USD"))).get(),"Detail saved through production editor");
        P1BatchChecks.await(detailEditor::isDestroyed,"Detail editor finished before viewer launch");
        app.io.submit(() -> {MetadataStore.View view=app.library.metadata.view(id);P1Checks.require(view.dismissed.size()==1,"Existing dismissal survives automatic fill");app.metadata.kick();return true;}).get();
        P1BatchChecks.await(() -> app.io.submit(() -> app.library.metadata.view(id).dismissed.size()==1).get(),"Dismissed candidate available only for explicit reconsideration");
        Activity reconsiderEditor=open(instrumentation,new Intent(target,MetadataEditorActivity.class).putExtra("document_id",id).putExtra("field_id","amount:total").putExtra("kind","amount").putExtra("offered",true).putExtra("reconsider",true));
        P1BatchChecks.await(() -> {boolean[] ready={false};instrumentation.runOnMainSync(() -> ready[0]=findButton(reconsiderEditor.getWindow().getDecorView(),"Save").isEnabled());return ready[0];},"Reconsider editor loaded");
        instrumentation.runOnMainSync(() -> {P1Checks.require(findInput(reconsiderEditor.getWindow().getDecorView(),"Value").getText().toString().equals("24.50"),"Reconsider loads current evidence");findInput(reconsiderEditor.getWindow().getDecorView(),"Value").setText("25.60");findButton(reconsiderEditor.getWindow().getDecorView(),"Save").performClick();});
        P1BatchChecks.await(() -> app.io.submit(() -> app.library.metadata.view(id).saved.stream().anyMatch(v -> v.field.equals("amount:total")&&v.value.equals("25.60")&&v.origin.equals("edited"))).get(),"Explicit reconsider editor Save persists correction");
        P1BatchChecks.await(reconsiderEditor::isDestroyed,"Reconsider editor finished before viewer launch");
        P1BatchChecks.await(() -> app.io.submit(() -> app.library.metadata.view(id).status.equals("ready")).get(),"Fresh proposals after correction");
        mark.accept("Production Notes/typed detail/reconsider editors: validation retains invalid input, explicit Save persists notes/exact decimal/currency and explicitly reconsiders a dismissed proposal; system clipboard untouched");
        for(int mode=0;mode<3;mode++) {
            configuration=new Configuration(target.getResources().getConfiguration());configuration.fontScale=mode==1?2:1;configuration.screenWidthDp=mode==2?600:320;configuration.screenHeightDp=mode==2?320:700;configuration.orientation=mode==2?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT;
            String suffix=mode==0?"narrow":mode==1?"large-text":"landscape";
            Activity viewer=open(instrumentation,new Intent(target,DocumentActivity.class).putExtra("document_id",id));
            try {
                settle(instrumentation);instrumentation.runOnMainSync(() -> {ZoomView zoom=find(viewer.getWindow().getDecorView(),ZoomView.class);P1Checks.require(zoom!=null,"Zoom view integrated");zoom.changeZoom(2);Bundle saved=new Bundle();zoom.save(saved);zoom.reset();zoom.restore(saved);P1Checks.require(zoom.zoom()==2,"Zoom state restore");});capture(instrumentation,viewer,"viewer-zoom-"+suffix);
                instrumentation.runOnMainSync(() -> findButton(viewer.getWindow().getDecorView(),"Read").performClick());instrumentation.runOnMainSync(() -> {ZoomView zoom=find(viewer.getWindow().getDecorView(),ZoomView.class);for(int step=0;step<12;step++)zoom.performAccessibilityAction(0x01010006,null);});settle(instrumentation);capture(instrumentation,viewer,"viewer-reading-"+suffix);
                instrumentation.runOnMainSync(() -> findButton(viewer.getWindow().getDecorView(),"Details").performClick());
                instrumentation.runOnMainSync(() -> {DetailsSheet sheet=find(viewer.getWindow().getDecorView(),DetailsSheet.class);sheet.setExpanded(true,false);});settle(instrumentation);
                instrumentation.runOnMainSync(() -> {View sheet=find(viewer.getWindow().getDecorView(),DetailsSheet.class);P1Checks.require(findText(sheet,"Document date · Detected")!=null&&findText(sheet,"2026-10-08")!=null&&findButton(sheet,"Add manually")!=null,"Automatically saved label/value and manual option are inline in Details without Apply");});capture(instrumentation,viewer,"details-"+suffix);
                instrumentation.runOnMainSync(() -> {DetailsSheet sheet=find(viewer.getWindow().getDecorView(),DetailsSheet.class);ScrollView scroll=find(sheet,ScrollView.class);scroll.fullScroll(View.FOCUS_DOWN);});settle(instrumentation);capture(instrumentation,viewer,"proposals-"+suffix);
                final AlertDialog[] rename={null};instrumentation.runOnMainSync(() -> rename[0]=NameEditor.show(viewer,"Rename document","Synthetic fine-print statement","Save",app.io,value -> {},() -> {},Arrays.asList("Synthetic suggested name","Synthetic second name")));settle(instrumentation);captureDialog(instrumentation,rename[0],"rename-"+suffix);
                instrumentation.runOnMainSync(() -> rename[0].cancel());
            }finally{instrumentation.runOnMainSync(viewer::finish);}
            for(String field:new String[]{"notes","manual:visual"}) {
                Activity editor=open(instrumentation,new Intent(target,MetadataEditorActivity.class).putExtra("document_id",id).putExtra("field_id",field).putExtra("kind",field.equals("notes")?"text":"amount"));
                settle(instrumentation);capture(instrumentation,editor,(field.equals("notes")?"notes-editor-":"detail-editor-")+suffix);instrumentation.runOnMainSync(editor::finish);
            }
            for(Class<?> type:new Class<?>[]{SettingsActivity.class,BatchActivity.class,MainActivity.class}){Activity screen=open(instrumentation,new Intent(target,type));settle(instrumentation);capture(instrumentation,screen,type.getSimpleName()+"-"+suffix);if(type==SettingsActivity.class){instrumentation.runOnMainSync(() -> {ScrollView scroll=find(screen.getWindow().getDecorView(),ScrollView.class);scroll.fullScroll(View.FOCUS_DOWN);});settle(instrumentation);capture(instrumentation,screen,"settings-bottom-"+suffix);}instrumentation.runOnMainSync(screen::finish);}
            Activity chooser=open(instrumentation,new Intent(Intent.ACTION_GET_CONTENT).setClass(target,PdfSelectionActivity.class).setType("application/pdf"));settle(instrumentation);capture(instrumentation,chooser,"pdf-chooser-"+suffix);instrumentation.runOnMainSync(chooser::finish);
            // Gate routing with synthetic enabled policy only. No enrollment or OS prompt is invoked.
            target.getSharedPreferences("local_policy",Context.MODE_PRIVATE).edit().putBoolean("app_lock",true).commit();app.access.session.lock();
            Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(LockUiChecks.Screen.class.getName(),null,false);
            Instrumentation.ActivityMonitor privateMonitor=instrumentation.addMonitor(DocumentActivity.class.getName(),null,false);
            instrumentation.runOnMainSync(() -> target.startActivity(new Intent(target,DocumentActivity.class).putExtra("document_id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            Activity locked=privateMonitor.waitForActivityWithTimeout(10000);Activity gate=monitor.waitForActivityWithTimeout(10000);instrumentation.removeMonitor(privateMonitor);P1Checks.require(gate!=null,"Locked document routes to common gate");settle(instrumentation);capture(instrumentation,gate,"lock-"+suffix);
            instrumentation.runOnMainSync(() -> P1Checks.require(locked.getWindow().getDecorView().getAlpha()==0,"Private view hidden behind gate"));target.getSharedPreferences("local_policy",Context.MODE_PRIVATE).edit().putBoolean("app_lock",false).commit();instrumentation.runOnMainSync(() -> {gate.finish();locked.finish();});instrumentation.removeMonitor(monitor);
            mark.accept("Production synthetic viewer/zoom/details/proposals/notes/detail editor/settings/batch/library/PDF chooser/common lock gate at "+suffix+"; private view covered; no global settings, enrollment or personal screenshot");
        }
        configuration=new Configuration(target.getResources().getConfiguration());configuration.screenWidthDp=320;
        target.getSharedPreferences("navigation",Context.MODE_PRIVATE).edit().putString("tab","home").remove("query").putString("stack","bills").remove("organization").commit();
        Activity filtered=open(instrumentation,new Intent(target,MainActivity.class));settle(instrumentation);capture(instrumentation,filtered,"library-filter-active-narrow");instrumentation.runOnMainSync(filtered::finish);
        target.getSharedPreferences("navigation",Context.MODE_PRIVATE).edit().putString("tab","home").remove("query").remove("stack").remove("organization").commit();
        Activity home=open(instrumentation,new Intent(target,MainActivity.class));settle(instrumentation);instrumentation.runOnMainSync(() -> findDescription(home.getWindow().getDecorView(),"Home").performClick());settle(instrumentation);capture(instrumentation,home,"library-home-narrow");instrumentation.runOnMainSync(home::finish);
        Activity modalHost=open(instrumentation,new Intent(target,SettingsActivity.class));
        AlertDialog[] modal={null};instrumentation.runOnMainSync(() -> modal[0]=new AlertDialog.Builder(modalHost).setTitle("Synthetic open editor").setMessage("Synthetic draft only").setPositiveButton("OK",null).show());
        Instrumentation.ActivityMonitor gateMonitor=instrumentation.addMonitor(LockUiChecks.Screen.class.getName(),null,false);
        target.getSharedPreferences("local_policy",Context.MODE_PRIVATE).edit().putBoolean("app_lock",true).commit();instrumentation.runOnMainSync(app.access::lock);
        Activity modalGate=gateMonitor.waitForActivityWithTimeout(10000);P1Checks.require(modalGate!=null,"Open dialog cannot prevent app-lock gate routing");
        instrumentation.runOnMainSync(() -> P1Checks.require(modalHost.getWindow().getDecorView().getAlpha()==0,"Dialog owner private content covered"));
        target.getSharedPreferences("local_policy",Context.MODE_PRIVATE).edit().putBoolean("app_lock",false).commit();instrumentation.runOnMainSync(() -> {modal[0].dismiss();modalGate.finish();modalHost.finish();});instrumentation.removeMonitor(gateMonitor);
        mark.accept("Synthetic lock transition with an already-open dialog routes to the common gate and covers its owner; no OS authentication or enrollment invoked");
        configuration=null;
    }
    static Activity open(Instrumentation instrumentation,Intent intent){
        Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(intent.getComponent().getClassName().equals(LockActivity.class.getName())?LockUiChecks.Screen.class.getName():intent.getComponent().getClassName(),null,false);
        Activity activity;
        try{instrumentation.getTargetContext().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));activity=monitor.waitForActivityWithTimeout(10000);}
        finally{instrumentation.removeMonitor(monitor);}
        P1Checks.require(activity!=null,"Synthetic activity opened within timeout; leave the phone idle during UI checks");
        instrumentation.runOnMainSync(() -> {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            activity.getWindow().setLayout(Ui.dp(activity,configuration.screenWidthDp),configuration.orientation==Configuration.ORIENTATION_LANDSCAPE?Ui.dp(activity,320):WindowManager.LayoutParams.MATCH_PARENT);
        });
        return activity;
    }
    static void settle(Instrumentation instrumentation){instrumentation.waitForIdleSync();SystemClock.sleep(450);instrumentation.waitForIdleSync();}
    static View findDescription(View view,String text){if(view.getContentDescription()!=null&&text.contentEquals(view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=findDescription(((ViewGroup)view).getChildAt(i),text);if(result!=null)return result;}return null;}
    static EditText findInput(View view,String description){if(view instanceof EditText&&description.contentEquals(view.getContentDescription()))return (EditText)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){EditText result=findInput(((ViewGroup)view).getChildAt(i),description);if(result!=null)return result;}return null;}
    static TextView findText(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView result=findText(((ViewGroup)view).getChildAt(i),text);if(result!=null)return result;}return null;}
    static Button findButton(View view,String text){if(view instanceof Button&&((Button)view).getText().toString().equals(text))return (Button)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button result=findButton(((ViewGroup)view).getChildAt(i),text);if(result!=null)return result;}return null;}
    static <T extends View>T find(View view,Class<T> type){if(type.isInstance(view))return type.cast(view);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){T result=find(group.getChildAt(i),type);if(result!=null)return result;}}return null;}
    static void captureDialog(Instrumentation instrumentation,AlertDialog dialog,String name)throws Exception {
        File directory=new File(instrumentation.getTargetContext().getFilesDir(),"checks/p1-visuals");
        P1Checks.require(directory.isDirectory()||directory.mkdirs(),"Synthetic dialog visual directory");
        Throwable[] failure={null};instrumentation.runOnMainSync(() -> {try{View root=dialog.getWindow().getDecorView();check(root);Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);try(FileOutputStream out=new FileOutputStream(new File(directory,name+".png"))){root.draw(new Canvas(bitmap));bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}}catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new AssertionError("Synthetic dialog layout "+name,failure[0]);
    }
    static void capture(Instrumentation instrumentation,Activity activity,String name)throws Exception {
        File directory=new File(instrumentation.getTargetContext().getFilesDir(),"checks/p1-visuals");P1Checks.require(directory.isDirectory()||directory.mkdirs(),"Synthetic visual directory");
        Throwable[] failure={null};
        instrumentation.runOnMainSync(() -> {try{View root=activity.findViewById(android.R.id.content);P1Checks.require(root.getWidth()>0&&root.getHeight()>0,"Visible production layout");root.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(root.getHeight(),View.MeasureSpec.EXACTLY));root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());check(root);Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);try{root.draw(new Canvas(image));try(FileOutputStream out=new FileOutputStream(new File(directory,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}}catch(IOException error){throw new RuntimeException(error);}finally{image.recycle();}}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError("Synthetic visual check "+name,failure[0]);
    }
    private static void check(View view){if(view.getVisibility()==View.GONE)return;if(view instanceof TextView){TextView text=(TextView)view;if(text.getLayout()!=null&&text.length()>0&&text.getMaxLines()!=1)P1Checks.require(text.getHeight()>=text.getLayout().getHeight()+text.getCompoundPaddingTop()+text.getCompoundPaddingBottom(),"Text vertically readable: "+text.getText());}
        if(view.isClickable()&&view instanceof Button)P1Checks.require(view.getHeight()>=Math.round(48*view.getResources().getDisplayMetrics().density),"Button touch target at least 48 dp: "+((Button)view).getText()+" height="+view.getHeight()+" min="+Math.round(48*view.getResources().getDisplayMetrics().density));if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)check(group.getChildAt(i));}}
}
