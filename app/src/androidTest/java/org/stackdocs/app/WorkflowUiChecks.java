package org.stackdocs.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Production editors/picker/review against an injected synthetic library; no app.library swap. */
final class WorkflowUiChecks {
    static void run(Instrumentation instrumentation,Context context,Consumer<String> mark) throws Exception {
        ExecutorService io=Executors.newSingleThreadExecutor();LibraryRepository library=new LibraryRepository(context);library.initialize();
        Document document=library.importDocument(android.net.Uri.fromFile(OcrQueueChecks.fixture(context,"editor.pdf")));
        String original=document.title;Activity[] host=new Activity[1];AlertDialog[] editor=new AlertDialog[1];
        try {
            IsolatedCheckActivity.content=activity -> {host[0]=activity;return new android.widget.TextView(activity);};
            instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),IsolatedCheckActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AtomicInteger saves=new AtomicInteger();
            ui(instrumentation,() -> {editor[0]=NameEditor.show(host[0],"Rename document",original,"Save",io,name -> {
                if(saves.getAndIncrement()==0)throw new java.io.IOException("Synthetic name storage failure");
                library.rename(document.id,name);
            },() -> {});return true;});
            EditText input=ui(instrumentation,() -> findInput(editor[0].getWindow().getDecorView()));
            setAndSave(instrumentation,editor[0],input,"  ");
            WorkflowRepositoryChecks.require(ui(instrumentation,() -> editor[0].isShowing() && input.getError()!=null) && saves.get()==0,"Blank validation retains editor without save");
            setAndSave(instrumentation,editor[0],input,new String(new char[201]).replace('\0','X'));
            WorkflowRepositoryChecks.require(ui(instrumentation,() -> editor[0].isShowing() && input.getText().length()==201 && input.getError()!=null) && saves.get()==0,"Oversized entry retained, no silent truncation/save");
            setAndSave(instrumentation,editor[0],input,"Recoverable chosen name");
            await(() -> ui(instrumentation,() -> input.isEnabled() && input.getError()!=null),"Save failure shown inline");
            WorkflowRepositoryChecks.require(ui(instrumentation,() -> editor[0].isShowing() && input.getText().toString().equals("Recoverable chosen name")) && library.document(document.id).title.equals(original),"IO failure keeps text and existing title");
            ui(instrumentation,() -> editor[0].getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            await(() -> !ui(instrumentation,() -> editor[0].isShowing()),"Retry succeeds");
            WorkflowRepositoryChecks.require(library.document(document.id).title.equals("Recoverable chosen name"),"Name retry durable");
            ui(instrumentation,() -> {editor[0]=NameEditor.show(host[0],"Create a stack","Bills","Create",io,library::createStack,() -> {});return true;});
            EditText duplicate=ui(instrumentation,() -> findInput(editor[0].getWindow().getDecorView()));
            ui(instrumentation,() -> editor[0].getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            await(() -> ui(instrumentation,() -> duplicate.isEnabled() && duplicate.getError()!=null),"Duplicate stack shown inline");
            WorkflowRepositoryChecks.require(ui(instrumentation,() -> duplicate.getText().toString().equals("Bills") && editor[0].isShowing()),"Duplicate retains edit value");
            ui(instrumentation,() -> editor[0].getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            mark.accept("Production name editor: blank/201-character validation retains input; injected storage failure keeps current name and typed text; retry commits; duplicate stack remains editable and Cancel preserves state");

            library.setMemberships(document.id,Arrays.asList("bills"));library.setStackVisible("banking",false);
            @SuppressWarnings("unchecked") List<String>[] selected=new List[1];
            ui(instrumentation,() -> {StackPicker.show(host[0],library.stacks(),Arrays.asList("bills","deleted-stack"),ids -> selected[0]=ids);return true;});
            SuggestionUiChecks.click(instrumentation,"Bills");SuggestionUiChecks.click(instrumentation,"Save");
            await(() -> selected[0]!=null,"Picker selection returns");
            WorkflowRepositoryChecks.require(selected[0].isEmpty(),"Invisible deleted stack is not retained by picker Save");
            library.setMemberships(document.id,selected[0]);selected[0]=null;
            ui(instrumentation,() -> {StackPicker.show(host[0],library.stacks(),Collections.emptyList(),ids -> selected[0]=ids);return true;});
            android.view.accessibility.AccessibilityNodeInfo banking=SuggestionUiChecks.waitText(instrumentation,"Banking");
            WorkflowRepositoryChecks.require(banking.isCheckable() && !banking.isChecked(),"Hidden stack checkbox announced once with check state");
            SuggestionUiChecks.click(instrumentation,"Banking");SuggestionUiChecks.click(instrumentation,"Save");await(() -> selected[0]!=null,"Hidden selection returns");
            library.setMemberships(document.id,selected[0]);
            LibraryRepository reopened=new LibraryRepository(context);reopened.initialize();
            WorkflowRepositoryChecks.require(reopened.document(document.id).stackIds.equals(Arrays.asList("banking")) && reopened.document(document.id).title.equals("Recoverable chosen name"),"Filing/name reopen durable");
            mark.accept("Production stack picker: deleted invisible initial ID removed only by explicit Save, explicit empty choice, hidden Banking selectable/announced as checkbox, name and filing persist on reopen");
            ui(instrumentation,() -> {host[0].finish();return true;});host[0]=null;

            Document reviewDocument=library.importDocument(android.net.Uri.fromFile(OcrQueueChecks.fixture(context,"large-type.pdf")));
            SuggestionChecks.finishOcr(library,"Cedar Clinic\nMedical invoice\nPatient name Synthetic\nAmount due 10\nDue date June 2");SuggestionChecks.drain(library);
            for(float scale:new float[]{1f,2f}) {
                Configuration configuration=new Configuration(instrumentation.getTargetContext().getResources().getConfiguration());configuration.fontScale=scale;
                IsolatedCheckActivity.configuration=configuration;
                SuggestionReview[] review=new SuggestionReview[1];
                IsolatedCheckActivity.content=activity -> {
                    host[0]=activity;review[0]=new SuggestionReview(activity,library,io,() -> {},reviewDocument.id,() -> {});
                    review[0].root.setPadding(Ui.dp(activity,20),0,Ui.dp(activity,20),0);
                    android.widget.FrameLayout frame=new android.widget.FrameLayout(activity);android.widget.ScrollView scroll=new android.widget.ScrollView(activity);
                    scroll.addView(review[0].root);frame.addView(scroll,new android.widget.FrameLayout.LayoutParams(Ui.dp(activity,320),-1));review[0].refresh();return frame;
                };
                instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),IsolatedCheckActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                await(() -> ui(instrumentation,() -> countButtons(review[0].root)==6 && laidOut(review[0].root)),"Review controls at font scale "+scale);
                ui(instrumentation,() -> {checkBounds(review[0].root,Ui.dp(host[0],48));return true;});
                Activity previous=host[0];
                ui(instrumentation,() -> {host[0].recreate();return true;});
                await(() -> ui(instrumentation,() -> host[0]!=previous && countButtons(review[0].root)==6 && laidOut(review[0].root)),"Review recreation");
                ui(instrumentation,() -> {checkBounds(review[0].root,Ui.dp(host[0],48));host[0].finish();return true;});host[0]=null;
            }
            mark.accept("Production suggestion controls at 320 dp width, 1x/2x text and activity recreation: all six actions fit without horizontal clipping, retain 48 dp touch height and field-specific accessibility labels; no handset font settings changed");
        }finally {
            if(host[0]!=null)ui(instrumentation,() -> {host[0].finish();return true;});
            IsolatedCheckActivity.content=null;IsolatedCheckActivity.configuration=null;io.shutdown();io.awaitTermination(5,TimeUnit.SECONDS);
        }
    }
    private static void setAndSave(Instrumentation instrumentation,AlertDialog dialog,EditText input,String text) throws Exception {
        ui(instrumentation,() -> {input.setText(text);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();return true;});
    }
    private static EditText findInput(View view) {
        if(view instanceof EditText)return (EditText)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){EditText found=findInput(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}
        return null;
    }
    private static boolean laidOut(View view) {
        if(view.getWidth()==0 || view.getHeight()==0)return false;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(!laidOut(((ViewGroup)view).getChildAt(i)))return false;
        return true;
    }
    private static int countButtons(View view){int count=view instanceof android.widget.Button ? 1 : 0;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)count+=countButtons(((ViewGroup)view).getChildAt(i));return count;}
    private static void checkBounds(View view,int height) {
        if(view instanceof android.widget.Button){
            WorkflowRepositoryChecks.require(view.getWidth()>0 && view.getHeight()>=height,"Touch dimensions");
            View parent=(View)view.getParent();WorkflowRepositoryChecks.require(view.getLeft()>=0 && view.getRight()<=parent.getWidth(),"No horizontally clipped actions");
            WorkflowRepositoryChecks.require(view.getContentDescription()!=null && view.getContentDescription().toString().startsWith(((android.widget.Button)view).getText().toString()+" suggested "),"Action names include the proposal field");
        }
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)checkBounds(((ViewGroup)view).getChildAt(i),height);
    }
    private static void await(Callable<Boolean> condition,String description) throws Exception {
        long until=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<until){if(condition.call())return;Thread.sleep(50);}throw new AssertionError(description);
    }
    private static <T> T ui(Instrumentation instrumentation,Callable<T> call) throws Exception {
        FutureTask<T> task=new FutureTask<>(call);instrumentation.runOnMainSync(task);return task.get(5,TimeUnit.SECONDS);
    }
}
