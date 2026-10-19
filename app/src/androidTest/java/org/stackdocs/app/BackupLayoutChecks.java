package org.stackdocs.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.function.Consumer;

/** Real production layout in isolated narrow/large-text contexts, with synthetic screenshot contents. */
final class BackupLayoutChecks {
    static void run(Instrumentation instrumentation,Context context,Consumer<String> mark)throws Exception {
        Activity[] host=new Activity[1];BackupScreen[] screen=new BackupScreen[1];
        try {
            for(float scale:new float[]{1,2}) {
                Configuration configuration=new Configuration(instrumentation.getTargetContext().getResources().getConfiguration());configuration.fontScale=scale;configuration.screenWidthDp=320;IsolatedCheckActivity.configuration=configuration;
                IsolatedCheckActivity.content=activity->{host[0]=activity;screen[0]=new BackupScreen(activity,()->{},()->{},()->{},()->{},()->{},()->{});screen[0].render("idle","","","",false);FrameLayout frame=new FrameLayout(activity);frame.setBackgroundColor(android.graphics.Color.WHITE);frame.addView(screen[0].root,new FrameLayout.LayoutParams(Ui.dp(activity,320),-1));return frame;};
                instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),IsolatedCheckActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                instrumentation.waitForIdleSync();SystemClock.sleep(200);
                instrumentation.runOnMainSync(()->{screen[0].history("Last backup: Oct 17, 2026, 9:45 PM");check(screen[0].root,Ui.dp(host[0],320));});
                screenshot(instrumentation,context,screen[0].root,scale==1?"backup-idle.png":"backup-idle-large-text.png");
                instrumentation.runOnMainSync(()->screen[0].render("review","","","2 documents will be added. Existing documents and filing will be kept. Selecting this backup again imports another copy.",false));
                instrumentation.waitForIdleSync();SystemClock.sleep(400);instrumentation.runOnMainSync(()->check(screen[0].root,Ui.dp(host[0],320)));
                screenshot(instrumentation,context,screen[0].root,scale==1?"backup-review.png":"backup-large-text.png");
                instrumentation.runOnMainSync(()->{screen[0].render("failed","","Choose another location, or free some storage and retry.","",false);host[0].finish();});host[0]=null;
                mark.accept("Production backup screen at 320 dp / "+scale+"x text: circular Stack icons, action rows, readable scope/protection, reviewed commit/cancel and error layout fit; screenshot contents synthetic only");
            }
        } finally {
            if(host[0]!=null)instrumentation.runOnMainSync(host[0]::finish);IsolatedCheckActivity.content=null;IsolatedCheckActivity.configuration=null;
        }
    }
    private static void check(View view,int width) {
        if(view.getVisibility()==View.GONE)return;
        WorkflowRepositoryChecks.require(view.getWidth()<=width,"Backup control fits narrow width");
        if(view instanceof TextView){TextView text=(TextView)view;if(text.getLayout()!=null && text.length()>0){WorkflowRepositoryChecks.require(text.getHeight()>=text.getLayout().getHeight()+text.getCompoundPaddingTop()+text.getCompoundPaddingBottom(),"Text not vertically clipped: "+text.getText());for(int line=0;line<text.getLayout().getLineCount();line++)WorkflowRepositoryChecks.require(text.getLayout().getEllipsisCount(line)==0,"Text not ellipsized");}}
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)check(((ViewGroup)view).getChildAt(i),width);
    }
    private static void screenshot(Instrumentation instrumentation,Context context,View root,String name)throws Exception {
        Throwable[] failure={null};instrumentation.runOnMainSync(()->{
            try {
                root.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(root.getHeight(),View.MeasureSpec.EXACTLY));
                root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());check(root,root.getWidth());
                Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
                try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),name))){android.graphics.Canvas canvas=new android.graphics.Canvas(image);canvas.drawColor(android.graphics.Color.WHITE);root.draw(canvas);image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
            }catch(Throwable error){failure[0]=error;}
        });if(failure[0]!=null)throw new AssertionError("Synthetic backup View capture",failure[0]);
    }
}
