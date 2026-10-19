package org.stackdocs.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Clicks the production review view with fresh isolated dependencies in a non-exported debug host. */
final class SuggestionUiChecks {
    static void run(Instrumentation instrumentation,Context context,Consumer<String> mark) throws Exception {
        ExecutorService io=Executors.newSingleThreadExecutor(); LibraryRepository library=new LibraryRepository(context);
        io.submit(library::initialize).get();
        Document document=io.submit(() -> library.importDocument(android.net.Uri.fromFile(OcrQueueChecks.fixture(context,"ui.pdf")))).get();
        io.submit(() -> SuggestionChecks.finishOcr(library,"Cedar Clinic\nMedical invoice\nPatient name Example\nAmount due 10\nDue date May 5")).get();
        io.submit(() -> { SuggestionJob job=library.suggestions.claim(); library.suggestions.complete(job,null,true); }).get();
        SuggestionReview[] review=new SuggestionReview[1];
        SuggestionWorker worker=new SuggestionWorker(library.suggestions,io,() -> new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {if(review[0]!=null)review[0].refresh();}));
        IsolatedCheckActivity.content=activity -> {
            review[0]=new SuggestionReview(activity,library,io,worker::kick,document.id,() -> {});
            android.widget.ScrollView scroll=new android.widget.ScrollView(activity); scroll.addView(review[0].root); review[0].refresh(); return scroll;
        };
        android.app.Activity host=null;
        try {
            host=instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),IsolatedCheckActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            click(instrumentation,"Retry suggestions"); waitText(instrumentation,"Suggested name:");
            click(instrumentation,"Edit");
            AccessibilityNodeInfo input=waitClass(instrumentation,"android.widget.EditText");
            Bundle text=new Bundle(); text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"UI chosen title");
            SuggestionChecks.require(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text),"Set isolated title editor");
            click(instrumentation,"Save"); waitAbsent(instrumentation,"Suggested name:"); waitText(instrumentation,"Suggested stacks:");
            click(instrumentation,"Accept"); waitText(instrumentation,"Suggestions reviewed.");
            SuggestionChecks.require(io.submit(() -> library.document(document.id).title.equals("UI chosen title") && library.document(document.id).stackIds.contains("medical")).get(),"UI changes committed");
            io.submit(() -> {library.retryOcr(document.id,library.pages(document.id).get(0).id);SuggestionChecks.finishOcr(library,"Willow Market\nSales receipt\nTotal 5\nCash");worker.kick();}).get();
            waitText(instrumentation,"Suggested name:"); click(instrumentation,"Dismiss"); waitAbsent(instrumentation,"Suggested name:");
            waitText(instrumentation,"Suggested stacks:"); click(instrumentation,"Edit"); waitText(instrumentation,"Edit all document stacks");
            click(instrumentation,"Cancel"); click(instrumentation,"Dismiss"); waitText(instrumentation,"Suggestions reviewed.");
            SuggestionChecks.require(io.submit(() -> library.document(document.id).title.equals("UI chosen title") && library.document(document.id).stackIds.contains("medical") && !library.document(document.id).stackIds.contains("receipts")).get(),"Dismiss/cancel preserve current choices");
            mark.accept("Production review UI in isolated debug host: failure/retry, title edit/save, filing accept, separate dismissal and filing-editor Cancel preserve choices");
        } catch(Throwable failure) {
            SuggestionStore.View state=io.submit(() -> library.suggestions.view(document.id)).get();
            throw new AssertionError(failure.getMessage()+"; isolated status="+state.status+" title="+state.titleState+" filing="+state.filingState,failure);
        } finally {
            android.app.Activity activity=host; if(activity!=null) instrumentation.runOnMainSync(activity::finish);
            IsolatedCheckActivity.content=null;
            io.submit(worker::shutdown).get();
            io.shutdown(); io.awaitTermination(5,TimeUnit.SECONDS);
        }
    }
    static AccessibilityNodeInfo waitText(Instrumentation instrumentation,String text) throws Exception {
        long until=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<until) {
            AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();
            if(root!=null) for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))
                if(node.getPackageName()!=null && node.getPackageName().toString().equals(instrumentation.getTargetContext().getPackageName())) return node;
            Thread.sleep(100);
        }
        throw new AssertionError("Missing isolated UI control: "+text);
    }
    static void waitAbsent(Instrumentation instrumentation,String text) throws Exception {
        long until=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<until) {
            AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();
            if(root!=null && root.findAccessibilityNodeInfosByText(text).isEmpty()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Isolated old control still present: "+text);
    }
    static void click(Instrumentation instrumentation,String text) throws Exception {
        long until=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<until) {
            AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();
            if(root!=null) for(AccessibilityNodeInfo found:root.findAccessibilityNodeInfosByText(text)) {
                boolean matchesText=found.getText()!=null && text.equalsIgnoreCase(found.getText().toString());
                boolean matchesDescription=found.getContentDescription()!=null && text.equalsIgnoreCase(found.getContentDescription().toString());
                if(!matchesText && !matchesDescription) continue;
                if(found.getPackageName()==null || !found.getPackageName().toString().equals(instrumentation.getTargetContext().getPackageName())) continue;
                AccessibilityNodeInfo node=found;
                while(node!=null && !node.isClickable()) node=node.getParent();
                if(node==null) continue;
                if(node.isVisibleToUser() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    instrumentation.waitForIdleSync(); return;
                }
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
            }
            Thread.sleep(100); // Scrolling/async refresh invalidates old accessibility nodes.
        }
        throw new AssertionError("Click "+text);
    }
    static AccessibilityNodeInfo waitClass(Instrumentation instrumentation,String className) throws Exception {
        long until=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<until) {
            AccessibilityNodeInfo result=find(instrumentation.getUiAutomation().getRootInActiveWindow(),className);
            if(result!=null) return result; Thread.sleep(100);
        }
        throw new AssertionError("Missing isolated editor");
    }
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node,String className) {
        if(node==null) return null;
        if(className.contentEquals(node.getClassName())) return node;
        for(int i=0;i<node.getChildCount();i++) { AccessibilityNodeInfo found=find(node.getChild(i),className); if(found!=null)return found; }
        return null;
    }
}
