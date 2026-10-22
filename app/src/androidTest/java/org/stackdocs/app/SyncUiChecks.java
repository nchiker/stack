package org.stackdocs.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Actual native views with authored state; no enrollment, sync or library writes. */
final class SyncUiChecks {
    private static final String PHONE="10000000-0000-4000-8000-000000000001",COMPUTER="20000000-0000-4000-8000-000000000002";
    static void run(Instrumentation instrumentation,Consumer<String> mark)throws Exception {
        if(!ScannerChecks.disposableLibraryTarget(instrumentation.getTargetContext()))throw new SecurityException("Explicit disposable target required for sync UI checks");
        Activity[] host=new Activity[1];SyncScreen[] screen=new SyncScreen[1];List<SyncScreen.Action> actions=new ArrayList<>();int[] exits={0};
        ScannerChecks.IsolatedContext files=new ScannerChecks.IsolatedContext(instrumentation.getTargetContext(),"sync-navigation");
        JSONObject unadopted=new JSONObject().put("replica",PHONE).put("adopted",false);
        JSONObject adopted=new JSONObject(unadopted.toString()).put("adopted",true).put("library","30000000-0000-4000-8000-000000000003")
            .put("members",new JSONArray().put(PHONE).put(COMPUTER)).put("conflicts",new JSONObject().put("authored conflict",new JSONArray()))
            .put("pending_gaps",0).put("frontier",new JSONObject()).put("acknowledged_by",new JSONObject());
        JSONObject identity=new JSONObject().put("identity_created",true).put("identity_fingerprint",repeat('a',64));
        JSONObject paired=new JSONObject(identity.toString()).put("paired_peer",COMPUTER).put("peer_fingerprint",repeat('b',64)).put("revoked",false).put("peer_name","Authored computer");
        try {
            for(float scale:new float[]{1,2}) {
                Configuration config=new Configuration(instrumentation.getTargetContext().getResources().getConfiguration());config.fontScale=scale;config.screenWidthDp=320;IsolatedCheckActivity.configuration=config;
                IsolatedCheckActivity.content=activity->{host[0]=activity;screen[0]=new SyncScreen(activity,actions::add,()->exits[0]++,null);screen[0].render(unadopted,new JSONObject(),false,"","");FrameLayout frame=new FrameLayout(activity);frame.addView(screen[0].root,new FrameLayout.LayoutParams(Ui.dp(activity,320),-1));return frame;};
                instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),IsolatedCheckActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(()->{
                    String first=text(screen[0].root);require(first.contains("Settings → Sync")&&first.contains("Connect phone"),"One connection flow matches desktop settings");
                    require(!first.contains("More options")&&!first.contains("Exchange files")&&!first.contains("fingerprint")&&!first.contains(PHONE),"No retired workflows or internal identifiers are visible");
                    screen[0].render(adopted,paired,false,"Up to date","Last sync: just now");
                    click(screen[0].root,"Sync now");click(screen[0].root,"Disconnect");
                    require(actions.contains(SyncScreen.Action.CONNECT)&&actions.contains(SyncScreen.Action.REVOKE),"Only everyday actions dispatch");
                    screen[0].render(adopted,paired,true,"Syncing…","Last sync: just now");
                    require(find(screen[0].root,"Disconnect")!=null,"Disconnect stays available during automatic exchange");
                    String displayed=text(screen[0].root).toLowerCase(java.util.Locale.ROOT);
                    for(String internal:new String[]{"adoption","receipt","session","fingerprint","baseline","sync direction"})require(!displayed.contains(internal),"Technical mechanism stays internal: "+internal);
                    Bundle saved=new Bundle();screen[0].save(saved);screen[0].back();require(exits[0]>0,"Back leaves the single settings page");
                });
                capture(instrumentation,files,screen[0].root,"simple-sync-"+scale+".png");mark.accept("Single Sync page at320dp andfontscale"+scale+": device/status/last-sync/simple actions; retired setup/file/identity workflows absent");
                instrumentation.runOnMainSync(()->host[0].finish());instrumentation.waitForIdleSync();
            }
            browseDuringSync(instrumentation,mark);
            SyncActivity activity=(SyncActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),SyncActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            NearbyPairing.Control pending=new NearbyPairing.Control();
            try {
                java.lang.reflect.Field field=SyncActivity.class.getDeclaredField("nearby");field.setAccessible(true);NearbyPairingUi nearby=(NearbyPairingUi)field.get(activity);
                java.lang.reflect.Field control=NearbyPairingUi.class.getDeclaredField("control");control.setAccessible(true);
                instrumentation.runOnMainSync(()->{try{nearby.cancel();control.set(nearby,pending);activity.keepSyncAwake(true);instrumentation.callActivityOnStop(activity);}catch(IllegalAccessException error){throw new AssertionError(error);}});
                require(pending.session.canceled(),"Stopping foreground setup cancels its independent transport before destruction");
                require((activity.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)==0,"Stopped setup releases its screen flag");
                mark.accept("Actual SyncActivity stop cancels setup and releases its screen flag before destruction");
            }finally{instrumentation.runOnMainSync(activity::finish);instrumentation.waitForIdleSync();}
        } finally {IsolatedCheckActivity.content=null;IsolatedCheckActivity.configuration=null;}
    }
    private static void browseDuringSync(Instrumentation test,Consumer<String> mark)throws Exception {
        if(!test.getTargetContext().getPackageName().equals("org.stackdocs.app.checks.autosync"))throw new SecurityException("Dedicated authored library required");
        StackApplication app=(StackApplication)test.getTargetContext().getApplicationContext();
        app.io.submit(()->{try{if(app.library.documents("",null).isEmpty())app.library.importDocument(android.net.Uri.fromFile(OcrQueueChecks.fixture(app,"browse-during-sync.pdf")));}catch(Exception error){throw new RuntimeException(error);}}).get(60,java.util.concurrent.TimeUnit.SECONDS);
        java.util.concurrent.CountDownLatch blocked=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        app.io.execute(()->{blocked.countDown();try{release.await(15,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
        MainActivity activity=null;boolean wasActive=SyncJob.active.getAndSet(true);
        try {
            require(blocked.await(10,java.util.concurrent.TimeUnit.SECONDS),"Sync owns the repository writer during the browse check");
            activity=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            java.lang.reflect.Field rows=MainActivity.class.getDeclaredField("rows");rows.setAccessible(true);MainActivity visible=activity;boolean[] populated={false};long deadline=android.os.SystemClock.elapsedRealtime()+5000;
            while(!populated[0]&&android.os.SystemClock.elapsedRealtime()<deadline){test.runOnMainSync(()->{try{for(Object row:(List<?>)rows.get(visible))if(row.getClass().getSimpleName().equals("DocumentRow"))populated[0]=true;}catch(IllegalAccessException error){throw new AssertionError(error);}});if(!populated[0])Thread.sleep(50);}
            require(populated[0]&&release.getCount()==1,"Actual Home renders document rows while the sync writer is still occupied");
            mark.accept("Actual populated MainActivity loads independently while synchronization owns the writer");
        }finally{release.countDown();SyncJob.active.set(wasActive);if(activity!=null){MainActivity done=activity;test.runOnMainSync(done::finish);test.waitForIdleSync();}}
    }
    private static void capture(Instrumentation instrumentation,android.content.Context context,View root,String name)throws Exception {
        Throwable[] error={null};instrumentation.waitForIdleSync();instrumentation.runOnMainSync(()->{try{
            root.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(root.getHeight(),View.MeasureSpec.EXACTLY));root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());check(root,root.getWidth());
            View sync=find(root,"Sync now");if(sync!=null){android.graphics.Rect bounds=new android.graphics.Rect();require(sync.getGlobalVisibleRect(bounds)&&bounds.height()==sync.getHeight(),"Primary sync action fully visible without scrolling, including enlarged text");}
            Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);try(FileOutputStream output=new FileOutputStream(new File(context.getFilesDir(),name))){root.draw(new android.graphics.Canvas(bitmap));bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}finally{bitmap.recycle();}
        }catch(Throwable failure){error[0]=failure;}});if(error[0]!=null)throw new AssertionError("Synthetic sync capture",error[0]);
    }
    private static void check(View view,int width){if(view.getVisibility()==View.GONE)return;require(view.getWidth()<=width,"Control fits narrow width");if(view instanceof TextView){TextView t=(TextView)view;if(t.getLayout()!=null&&t.length()>0){require(t.getHeight()>=t.getLayout().getHeight()+t.getCompoundPaddingTop()+t.getCompoundPaddingBottom(),"Text is not vertically clipped: "+t.getText());for(int n=0;n<t.getLayout().getLineCount();n++)require(t.getLayout().getEllipsisCount(n)==0,"Text not ellipsized");}}if(view instanceof ViewGroup)for(int n=0;n<((ViewGroup)view).getChildCount();n++)check(((ViewGroup)view).getChildAt(n),width);}
    private static void click(View root,String label){View view=find(root,label);require(view!=null&&view.isEnabled(),"Reachable action: "+label);view.performClick();}
    private static View find(View view,String label){if(view.getVisibility()!=View.VISIBLE)return null;String description=String.valueOf(view.getContentDescription());if(description.equals(label)||description.startsWith(label+". ")||(view instanceof TextView&&((TextView)view).getText().toString().equals(label)))return view;if(view instanceof ViewGroup)for(int n=0;n<((ViewGroup)view).getChildCount();n++){View found=find(((ViewGroup)view).getChildAt(n),label);if(found!=null)return found;}return null;}
    private static String text(View view){StringBuilder value=new StringBuilder();if(view.getVisibility()!=View.VISIBLE)return "";if(view instanceof TextView)value.append(((TextView)view).getText());if(view instanceof ViewGroup)for(int n=0;n<((ViewGroup)view).getChildCount();n++)value.append(text(((ViewGroup)view).getChildAt(n)));return value.toString();}
    private static String repeat(char value,int count){char[] chars=new char[count];java.util.Arrays.fill(chars,value);return new String(chars);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
