package org.stackdocs.app;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.database.Cursor;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** Authored fixtures and production native gestures; never touches the canonical library. */
final class StackEditorChecks {
    static void run(Instrumentation i, Consumer<String> mark) throws Exception {
        Context parent=i.getTargetContext();
        P1Checks.require(parent.getPackageName().equals("org.stackdocs.app.checks"),"Disposable package only");
        repository(parent,mark); ui(i,mark);
    }
    static void restart(Instrumentation i,String phase,Consumer<String> mark)throws Exception {
        Context target=i.getTargetContext();P1Checks.require(target.getPackageName().equals("org.stackdocs.app.checks"),"Disposable restart only");
        Context child=new ScannerChecks.IsolatedContext(target,"stack-order-restart");LibraryRepository library=new LibraryRepository(child);library.initialize();
        File expected=new File(child.getCacheDir(),"expected-order-preservation.txt");
        if(phase.equals("prepare")) {
            Document doc=library.importDocument(android.net.Uri.fromFile(OcrQueueChecks.fixture(child,"restart-order.pdf")));
            library.rename(doc.id,"Retained synthetic restart name");library.metadata.saveNotes(doc.id,"Retained notes",0);
            library.setMemberships(doc.id,Arrays.asList("medical","starred"));library.setStackVisible("medical",false);
            library.createStack("Restart custom "+SystemClock.uptimeMillis());
            List<StackCategory> catalog=library.stacks();List<String> order=LibraryRepository.stackIds(catalog);Collections.reverse(order);library.reorderStacks(catalog,order);
            try(FileOutputStream out=new FileOutputStream(expected)){out.write(PreservationChecks.fingerprint(child).getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
            mark.accept("Stack order restart fixture durably committed: reordered hidden/custom categories plus explicit name/notes/filing/originals, ready for Checks process stop");
        } else {
            P1Checks.require(phase.equals("resume")&&expected.isFile(),"Prepared restart fixture required");
            P1Checks.require(new String(ScannerChecks.bytes(expected),java.nio.charset.StandardCharsets.UTF_8).equals(PreservationChecks.fingerprint(child)),"Process-stop recovery matches every aggregate and exact stack order");
            mark.accept("Actual Checks process-stop/relaunch: saved category order, names/notes/filing/decisions, OCR and original/draft/recovery fingerprints all match");
        }
    }
    private static void repository(Context parent, Consumer<String> mark) throws Exception {
        Context context=new ScannerChecks.IsolatedContext(parent);LibraryRepository library=new LibraryRepository(context);library.initialize();
        library.createStack("A synthetic custom stack");library.setStackVisible("medical",false);
        Document retained=library.importDocument(android.net.Uri.fromFile(OcrQueueChecks.fixture(context,"retained.pdf")));
        library.rename(retained.id,"Explicit retained name");library.setMemberships(retained.id,Arrays.asList("medical","starred"));
        library.metadata.saveNotes(retained.id,"Explicit retained notes",0);
        OcrJob ocr;while((ocr=library.claimOcr())!=null)library.completeOcr(ocr,"Invoice\nAmount due 20",null);
        byte[] retainedBytes=ScannerChecks.bytes(library.source(retained));
        String protectedRows=rows(library,"SELECT id,title,notes,title_decision,filing_decision FROM documents ORDER BY id")+rows(library,"SELECT document_id,stack_id FROM document_stacks ORDER BY document_id,stack_id");
        String generations=rows(library,"SELECT document_id,generation,status FROM suggestions ORDER BY document_id");
        List<StackCategory> original=library.stacks();List<String> order=LibraryRepository.stackIds(original);Collections.reverse(order);
        library.reorderStacks(original,order);
        P1Checks.require(LibraryRepository.stackIds(library.stacks()).equals(order),"Reverse every stack including hidden");
        P1Checks.require(generations.equals(rows(library,"SELECT document_id,generation,status FROM suggestions ORDER BY document_id")),"Initial reorder leaves active suggestion generations untouched");
        LibraryRepository reopened=new LibraryRepository(context);reopened.initialize();
        P1Checks.require(LibraryRepository.stackIds(reopened.stacks()).equals(order),"Order persists across repository recreation");
        String suggestions=rows(library,"SELECT document_id,generation,status FROM suggestions ORDER BY document_id");
        List<StackCategory> before=library.stacks();List<String> valid=LibraryRepository.stackIds(before);Collections.rotate(valid,1);
        List<String> duplicate=new ArrayList<>(valid);duplicate.set(0,duplicate.get(1));
        reject(() -> library.reorderStacks(before,duplicate));reject(() -> library.reorderStacks(before,valid.subList(1,valid.size())));
        P1Checks.require(LibraryRepository.stackIds(library.stacks()).equals(order),"Invalid permutations leave order unchanged");
        library.database().execSQL("CREATE TRIGGER synthetic_order_failure BEFORE UPDATE OF sort_order ON stacks WHEN OLD.id='medical' BEGIN SELECT RAISE(ABORT,'synthetic order failure'); END");
        reject(() -> library.reorderStacks(before,valid));library.database().execSQL("DROP TRIGGER synthetic_order_failure");
        P1Checks.require(LibraryRepository.stackIds(library.stacks()).equals(order),"Mid-save failure rolls every earlier update back");
        library.reorderStacks(before,valid);P1Checks.require(suggestions.equals(rows(library,"SELECT document_id,generation,status FROM suggestions ORDER BY document_id")),"Order does not invalidate suggestion workers");
        reject(() -> library.reorderStacks(before,order));
        List<StackCategory> renamedBefore=library.stacks();StackCategory changed=renamedBefore.get(0);
        SuggestionJob inFlight=library.suggestions.claim();P1Checks.require(inFlight!=null,"Synthetic active proposal claim");
        library.editStack(changed,"Renamed synthetic",changed.visible);
        P1Checks.require(!library.suggestions.complete(inFlight,SuggestionRules.analyze(inFlight),false),"Rename rejects stale worker");
        SuggestionJob fresh;while((fresh=library.suggestions.claim())!=null)library.suggestions.complete(fresh,SuggestionRules.analyze(fresh),false);
        SuggestionStore.View oldView=library.suggestions.view(retained.id);
        StackCategory renamed=library.stacks().get(0);library.editStack(renamed,"Renamed synthetic again",renamed.visible);
        P1Checks.require(!library.suggestions.decide(oldView,"filing","accepted",null,null),"Rename rejects old Apply action");
        List<StackCategory> stale=renamedBefore;
        reject(() -> library.reorderStacks(stale,order));reject(() -> library.editStack(changed,"Stale name",true));reject(() -> library.deleteStack(changed));
        List<StackCategory> staleAdded=library.stacks();library.createStack("Appended synthetic");
        reject(() -> library.reorderStacks(staleAdded,LibraryRepository.stackIds(staleAdded)));
        P1Checks.require(library.stacks().get(library.stacks().size()-1).name.equals("Appended synthetic"),"New stack appended");
        library.deleteStack(library.stacks().get(library.stacks().size()-1));assertContiguous(library);
        P1Checks.require(protectedRows.equals(rows(library,"SELECT id,title,notes,title_decision,filing_decision FROM documents ORDER BY id")+rows(library,"SELECT document_id,stack_id FROM document_stacks ORDER BY document_id,stack_id")),"Reorder and catalog edits preserve document naming/notes/filing decisions");
        P1Checks.require(Arrays.equals(retainedBytes,ScannerChecks.bytes(library.source(retained))),"Original PDF retained exactly");
        mark.accept("Stack order durable; hidden/custom categories retained; malformed/stale permutations and stale rename/delete rejected; interrupted transaction rolls back; new stacks append and deletion compacts");

        Document doc=library.importDocument(android.net.Uri.fromFile(OcrQueueChecks.fixture(context,"ordered.pdf")));
        library.metadata.saveNotes(doc.id,"Ordered archive notes",0);
        byte[] bytes=archive(library); JSONObject manifest=manifest(bytes);
        P1Checks.require(manifest.getInt("version")==3&&manifest.getInt("schema")==8,"Portable format3/schema8");
        List<String> sourceOrder=LibraryRepository.stackIds(library.stacks());
        LibraryRepository empty=new LibraryRepository(new ScannerChecks.IsolatedContext(parent));empty.initialize();restore(empty,bytes);
        P1Checks.require(LibraryRepository.stackIds(empty.stacks()).equals(sourceOrder),"Empty restore preserves source order");
        P1Checks.require(empty.metadata.view(doc.id).notes.equals("Ordered archive notes"),"Prior detail/notes contract retained");
        for(int version:new int[]{1,2}) {
            byte[] old=P1Checks.rewrite(bytes,json -> {
                json.put("version",version).put("schema",version==1?6:7);
                for(int n=0;n<json.getJSONArray("stacks").length();n++)json.getJSONArray("stacks").getJSONObject(n).remove("sort_order");
                if(version==1){for(String key:new String[]{"details","tags","document_tags"})json.remove(key);
                    for(int n=0;n<json.getJSONArray("documents").length();n++){JSONObject row=json.getJSONArray("documents").getJSONObject(n);for(String key:new String[]{"notes","notes_revision","metadata_revision"})row.remove(key);}}
            });
            LibraryRepository target=new LibraryRepository(new ScannerChecks.IsolatedContext(parent));target.initialize();restore(target,old);
            P1Checks.require(LibraryRepository.stackIds(target.stacks()).equals(sourceOrder),"Old format array order adopted safely");
            P1Checks.require(target.metadata.view(doc.id).notes.equals(version==1?"":"Ordered archive notes"),"Old format notes defaults/preservation");
        }
        mark.accept("Archive3 roundtrip keeps notes and exact category order; format1/schema6 and format2/schema7 adopt legacy array order with safe notes defaults");

        LibraryRepository additive=new LibraryRepository(new ScannerChecks.IsolatedContext(parent));additive.initialize();
        List<StackCategory> catalog=additive.stacks();List<String> own=LibraryRepository.stackIds(catalog);Collections.reverse(own);additive.reorderStacks(catalog,own);
        // Even an empty but manually rearranged starter catalog is authoritative.
        restore(additive,bytes);List<String> merged=LibraryRepository.stackIds(additive.stacks());
        P1Checks.require(merged.subList(0,own.size()).equals(own),"Restore keeps manually ordered destination, even when empty");
        List<String> ownNames=new ArrayList<>();for(StackCategory stack:catalog)ownNames.add(stack.name);
        List<String> expectedAppend=new ArrayList<>();for(StackCategory stack:library.stacks())if(!ownNames.contains(stack.name))expectedAppend.add(stack.name);
        List<String> actualAppend=new ArrayList<>();List<StackCategory> mergedCatalog=additive.stacks();for(int n=own.size();n<mergedCatalog.size();n++)actualAppend.add(mergedCatalog.get(n).name);
        P1Checks.require(actualAppend.equals(expectedAppend),"Missing category names append in source order, including ID remaps");assertContiguous(additive);
        LibraryArchive restore=additive.archive();try(LibraryArchive.Staged stage=restore.stage(new ByteArrayInputStream(bytes),new LibraryArchive.Progress())) {
            restore.plan(stage);List<StackCategory> current=additive.stacks();List<String> next=LibraryRepository.stackIds(current);Collections.rotate(next,1);additive.reorderStacks(current,next);
            int count=additive.documents("",null).size();reject(() -> restore.commit(stage,new LibraryArchive.Progress()));
            P1Checks.require(additive.documents("",null).size()==count,"Stale archive review after reorder cannot commit");
        }
        for(P1Checks.Mutation mutation:Arrays.<P1Checks.Mutation>asList(
                json -> json.put("version",4), json -> json.put("schema",9),
                json -> json.getJSONArray("stacks").getJSONObject(0).remove("sort_order"),
                json -> json.getJSONArray("stacks").getJSONObject(0).put("sort_order",-1),
                json -> json.getJSONArray("stacks").getJSONObject(0).put("sort_order","0"),
                json -> json.getJSONArray("stacks").getJSONObject(1).put("sort_order",0))) {
            byte[] bad=P1Checks.rewrite(bytes,mutation);int count=additive.documents("",null).size();
            reject(() -> {try(LibraryArchive.Staged ignored=additive.archive().stage(new ByteArrayInputStream(bad),new LibraryArchive.Progress())){}});
            P1Checks.require(additive.documents("",null).size()==count,"Unsupported/malformed archive no mutation");
        }
        mark.accept("Additive restore keeps destination order and appends missing categories in source order; stale reviewed restore rejects reorder; future versions/schema and missing/negative/string/duplicate order reject without mutation");
    }
    private static void ui(Instrumentation i, Consumer<String> mark) throws Exception {
        trace(i,"UI setup");
        i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        trace(i,"Automation connected");
        Context target=i.getTargetContext();StackApplication app=(StackApplication)target.getApplicationContext();
        P1BatchChecks.await(() -> app.library!=null&&app.metadata!=null,"Application startup");
        target.getSharedPreferences("local_policy",Context.MODE_PRIVATE).edit().putBoolean("app_lock",false).commit();
        app.io.submit(() -> {for(int n=1;n<=12;n++) {String name="Synthetic category "+n;boolean exists=false;for(StackCategory s:app.library.stacks())if(s.name.equals(name))exists=true;if(!exists)app.library.createStack(name);}return true;}).get();
        P1UiChecks.configuration=new Configuration(target.getResources().getConfiguration());P1UiChecks.configuration.screenWidthDp=320;
        Activity activity=P1UiChecks.open(i,new Intent(target,StackEditorActivity.class));waitLoaded(i,activity);
        List<String> old=app.io.submit(() -> LibraryRepository.stackIds(app.library.stacks())).get();
        View first=tile(i,activity,old.get(0)), second=tile(i,activity,old.get(1));
        P1UiChecks.capture(i,activity,"stack-editor-before-drag");
        gesture(i,first,second,false);
        P1BatchChecks.await(() -> app.io.submit(() -> LibraryRepository.stackIds(app.library.stacks()).get(1).equals(old.get(0))).get(),"Actual native long press / drag / drop persisted");waitLoaded(i,activity);
        List<String> reordered=app.io.submit(() -> LibraryRepository.stackIds(app.library.stacks())).get();
        View moved=tile(i,activity,reordered.get(0));View toolbar=P1UiChecks.findText(activity.getWindow().getDecorView(),"Edit stacks");
        gesture(i,moved,toolbar,false);P1UiChecks.settle(i);
        P1Checks.require(app.io.submit(() -> LibraryRepository.stackIds(app.library.stacks())).get().equals(reordered),"Drop outside grid does not save");
        // Options are the standard keyboard/TalkBack alternative to a spatial gesture.
        String name=app.io.submit(() -> app.library.stacks().get(0).name).get();
        i.runOnMainSync(() -> P1UiChecks.findDescription(activity.getWindow().getDecorView(),"Options for "+name).performClick());P1UiChecks.settle(i);
        AccessibilityNodeInfo root=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();List<AccessibilityNodeInfo> matches=root.findAccessibilityNodeInfosByText("Move later");
        P1Checks.require(!matches.isEmpty()&&matches.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK),"Accessible Move later exposed and activated");
        P1BatchChecks.await(() -> app.io.submit(() -> LibraryRepository.stackIds(app.library.stacks()).get(1).equals(reordered.get(0))).get(),"Accessible move durably saved");waitLoaded(i,activity);
        final StackCategory visibilityBefore=app.io.submit(() -> app.library.stacks().get(0)).get();
        final String currentName=visibilityBefore.name;
        i.runOnMainSync(() -> P1UiChecks.findDescription(activity.getWindow().getDecorView(),"Show "+currentName+" on Home").performClick());
        P1BatchChecks.await(() -> app.io.submit(() -> app.library.stacks().get(0).visible!=visibilityBefore.visible).get(),"Inline Home checkbox saves visibility");waitLoaded(i,activity);
        String edgeId=app.io.submit(() -> app.library.stacks().get(0).id).get();
        gesture(i,tile(i,activity,edgeId),P1UiChecks.find(activity.getWindow().getDecorView(),ScrollView.class),true);
        P1BatchChecks.await(() -> app.io.submit(() -> LibraryRepository.stackIds(app.library.stacks()).indexOf(edgeId)>1).get(),"Native drag edge-scroll moves beyond the first row");waitLoaded(i,activity);
        List<String> saved=app.io.submit(() -> LibraryRepository.stackIds(app.library.stacks())).get();
        Instrumentation.ActivityMonitor monitor=i.addMonitor(StackEditorActivity.class.getName(),null,false);i.runOnMainSync(activity::recreate);
        Activity recreated=monitor.waitForActivityWithTimeout(10000);i.removeMonitor(monitor);P1Checks.require(recreated!=null,"Editor recreated");waitLoaded(i,recreated);
        P1Checks.require(app.io.submit(() -> LibraryRepository.stackIds(app.library.stacks())).get().equals(saved),"Recreation keeps saved order");
        i.runOnMainSync(recreated::finish);
        mark.accept("Production tile editor: injected native long-press/drag/drop saves order; outside drop cancels; Options Move later and inline Home checkbox save; native edge-scroll reaches later categories; activity recreation retains order");

        for(int mode=0;mode<3;mode++) {
            P1UiChecks.configuration=new Configuration(target.getResources().getConfiguration());
            P1UiChecks.configuration.screenWidthDp=mode==2?640:320;P1UiChecks.configuration.fontScale=mode==1?2f:1f;
            if(mode==2){P1UiChecks.configuration.orientation=Configuration.ORIENTATION_LANDSCAPE;P1UiChecks.configuration.screenHeightDp=320;}
            Activity screen=P1UiChecks.open(i,new Intent(target,StackEditorActivity.class));waitLoaded(i,screen);
            String suffix=mode==0?"narrow":mode==1?"large-text":"landscape";
            P1UiChecks.capture(i,screen,"stack-editor-"+suffix);
            i.runOnMainSync(() -> P1UiChecks.find(screen.getWindow().getDecorView(),ScrollView.class).fullScroll(View.FOCUS_DOWN));P1UiChecks.settle(i);
            P1UiChecks.capture(i,screen,"stack-editor-bottom-"+suffix);i.runOnMainSync(screen::finish);
            mark.accept("Synthetic stack tile editor top/bottom at "+suffix+"; native view capture and text/touch-target checks; no personal screenshot");
        }
        P1UiChecks.configuration=null;
    }
    private static void waitLoaded(Instrumentation i,Activity activity)throws Exception {
        P1BatchChecks.await(() -> {boolean[] loaded={false};i.runOnMainSync(() -> loaded[0]=P1UiChecks.findButton(activity.getWindow().getDecorView(),"New stack").isEnabled()&&P1UiChecks.findText(activity.getWindow().getDecorView(),"Home")!=null);return loaded[0];},"Stack editor loaded");P1UiChecks.settle(i);
    }
    private static View tile(Instrumentation i,Activity a,String id){View[] result={null};i.runOnMainSync(() -> result[0]=a.getWindow().getDecorView().findViewWithTag(id));P1Checks.require(result[0]!=null,"Synthetic tile exists");return result[0];}
    private static void gesture(Instrumentation i,View source,View target,boolean edge)throws Exception {
        int[] from=new int[2],to=new int[2];i.runOnMainSync(() -> {source.getLocationOnScreen(from);target.getLocationOnScreen(to);from[0]+=source.getWidth()/2;from[1]+=Ui.dp((Activity)source.getContext(),32);to[0]+=target.getWidth()/2;to[1]+=edge?target.getHeight()-Ui.dp((Activity)source.getContext(),12):target.getHeight()/2;});
        P1Checks.require(source.hasWindowFocus(),"Synthetic editor owns focus before gesture");
        trace(i,"Native gesture begins");
        command(i,"input touchscreen motionevent DOWN "+from[0]+" "+from[1]);
        // Give the UI thread a margin beyond its long-press deadline before MOVE.
        i.runOnMainSync(() -> ((StackApplication)i.getTargetContext().getApplicationContext()).notifyLibraryChanged());
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout()+300);
        P1Checks.require(((Activity)source.getContext()).getWindow().getDecorView().findViewWithTag(source.getTag())==source,"Background progress must not replace a held tile");
        java.lang.reflect.Field held=StackEditorActivity.class.getDeclaredField("drag");held.setAccessible(true);
        P1Checks.require(held.get(source.getContext())!=null,"Injected native hold starts the production drag");
        for(int n=1;n<=8;n++){
            int x=from[0]+(to[0]-from[0])*n/8,y=from[1]+(to[1]-from[1])*n/8;
            command(i,"input touchscreen motionevent MOVE "+x+" "+y);SystemClock.sleep(35);
        }
        if(edge)SystemClock.sleep(900);
        command(i,"input touchscreen motionevent UP "+to[0]+" "+to[1]);
        if(edge)P1Checks.require(((ScrollView)target).getScrollY()>0,"Held native edge drag actually scrolls the editor");
        trace(i,"Native gesture ends");
        P1UiChecks.settle(i);
    }
    private static void command(Instrumentation i,String command)throws Exception {
        try(android.os.ParcelFileDescriptor fd=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command);
            InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            while(input.read()!=-1) { /* Generic input command status only. */ }
        }
    }
    private static void trace(Instrumentation i,String step){Bundle status=new Bundle();status.putString("stream","TRACE "+step+"\n");i.sendStatus(0,status);}
    private interface Action {void run()throws Exception;}
    private static void reject(Action action)throws Exception{try{action.run();}catch(IOException|IllegalArgumentException|android.database.SQLException expected){return;}throw new AssertionError("Expected safe rejection");}
    private static void assertContiguous(LibraryRepository library){try(Cursor c=library.database().rawQuery("SELECT sort_order FROM stacks ORDER BY sort_order",null)){int n=0;while(c.moveToNext())P1Checks.require(c.getInt(0)==n++,"Order remains contiguous");}}
    private static String rows(LibraryRepository library,String sql){StringBuilder out=new StringBuilder();try(Cursor c=library.database().rawQuery(sql,null)){while(c.moveToNext())for(int n=0;n<c.getColumnCount();n++)out.append(c.getString(n)).append('|');}return out.toString();}
    private static byte[] archive(LibraryRepository library)throws Exception{try(LibraryArchive.Snapshot snap=library.archive().snapshot(new LibraryArchive.Progress())){ByteArrayOutputStream out=new ByteArrayOutputStream();LibraryArchive.write(snap,out,new LibraryArchive.Progress());return out.toByteArray();}}
    private static JSONObject manifest(byte[] bytes)throws Exception{DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));in.skipBytes(8);int length=in.readInt();in.skipBytes(32);byte[] json=new byte[length];in.readFully(json);return new JSONObject(new String(json,java.nio.charset.StandardCharsets.UTF_8));}
    private static void restore(LibraryRepository library,byte[] bytes)throws Exception{LibraryArchive a=library.archive();try(LibraryArchive.Staged s=a.stage(new ByteArrayInputStream(bytes),new LibraryArchive.Progress())){a.plan(s);a.commit(s,new LibraryArchive.Progress());}}
}
