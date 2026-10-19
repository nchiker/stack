package org.stackdocs.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.util.function.Consumer;

/** Production navigation with a deliberately deleted filter, in disposable synthetic library only. */
final class NavigationChecks {
    static void run(Instrumentation instrumentation,Consumer<String> mark)throws Exception {
        android.content.Context context=instrumentation.getTargetContext();if(!context.getPackageName().equals("org.stackdocs.app.checks"))throw new SecurityException("Disposable package only");
        StackApplication app=(StackApplication)context.getApplicationContext();
        P1BatchChecks.await(() -> app.library!=null,"Navigation application startup");
        app.io.submit(() -> {Document doc=app.library.importDocument(android.net.Uri.fromFile(OcrQueueChecks.fixture(new ScannerChecks.IsolatedContext(context),"navigation.pdf")));app.library.rename(doc.id,"Disposable manual name");return true;}).get();
        SharedPreferences prefs=context.getSharedPreferences("navigation",0);prefs.edit().putString("tab","all").putString("stack","deleted-synthetic-stack").putString("query","Disposable").remove("organization").commit();
        Activity activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            await(instrumentation,activity,"Disposable manual name");
            Activity current=activity;instrumentation.runOnMainSync(()->{EditText search=findInput(current.getWindow().getDecorView());WorkflowRepositoryChecks.require(search!=null && search.getText().toString().equals("Disposable"),"Saved query restored");current.finish();});instrumentation.waitForIdleSync();
            WorkflowRepositoryChecks.require(prefs.getString("stack",null)==null && prefs.getString("tab","").equals("all"),"Deleted filter falls back and All documents selection persists");
            activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));await(instrumentation,activity,"Disposable manual name");
            mark.accept("Production All documents/query survives activity finish/relaunch; deleted stack filter falls back to live library instead of hiding documents");
        }finally{Activity current=activity;instrumentation.runOnMainSync(current::finish);}
    }
    private static void await(Instrumentation instrumentation,Activity activity,String text)throws Exception {long until=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<until){java.util.concurrent.atomic.AtomicBoolean found=new java.util.concurrent.atomic.AtomicBoolean();instrumentation.runOnMainSync(()->found.set(hasText(activity.getWindow().getDecorView(),text)));if(found.get())return;Thread.sleep(50);}throw new AssertionError("Saved query shows synthetic document after stale filter removal");}
    private static boolean hasText(View view,String text){if(view instanceof TextView && ((TextView)view).getText().toString().equals(text))return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hasText(((ViewGroup)view).getChildAt(i),text))return true;return false;}
    private static EditText findInput(View view){if(view instanceof EditText)return (EditText)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){EditText input=findInput(((ViewGroup)view).getChildAt(i));if(input!=null)return input;}return null;}
}
