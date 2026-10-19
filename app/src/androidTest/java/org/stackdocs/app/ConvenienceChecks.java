package org.stackdocs.app;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.view.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** Real repository ordering and production chooser/recreation, disposable package only. */
final class ConvenienceChecks {
    static void run(Instrumentation i,Consumer<String> mark)throws Exception {
        Context target=i.getTargetContext();P1Checks.require(target.getPackageName().equals("org.stackdocs.app.checks"),"Disposable convenience checks");
        i.runOnMainSync(()->{});StackApplication app=(StackApplication)target.getApplicationContext();P1BatchChecks.await(()->app.library!=null,"Startup");app.io.submit(()->{}).get();
        Context context=new ScannerChecks.IsolatedContext(target);LibraryRepository isolated=new LibraryRepository(context);isolated.initialize();
        String[] isolatedIds=seed(isolated,context);
        P1Checks.require(ids(isolated.documents("",null,null,"name")).equals(Arrays.asList(isolatedIds[1],isolatedIds[2],isolatedIds[0])),"Name sort case-insensitive with deterministic ties");
        P1Checks.require(ids(isolated.documents("",null)).equals(Arrays.asList(isolatedIds[0],isolatedIds[2],isolatedIds[1])),"Existing default newest order");
        P1Checks.require(ids(isolated.documents("",null,null,"bad SQL")).equals(ids(isolated.documents("",null))),"Unknown mode falls back to safe fixed newest SQL");
        isolated.setMemberships(isolatedIds[0],Collections.singletonList("bills"));isolated.setMemberships(isolatedIds[1],Collections.singletonList("bills"));
        isolated.metadata.saveNotes(isolatedIds[0],"quartz filter",0);isolated.metadata.saveNotes(isolatedIds[1],"quartz filter",0);
        P1Checks.require(ids(isolated.documents("quartz","bills",null,"name")).equals(Arrays.asList(isolatedIds[1],isolatedIds[0])),"Name ordering composes with query and stack");
        mark.accept("Fixed SQL Newest/Name ordering: case-insensitive names, deterministic ties, newest default/invalid mode fallback, query/stack composition; isolated synthetic documents");
        app.io.submit(()->seed(app.library,target)).get();
        target.getSharedPreferences("navigation",Context.MODE_PRIVATE).edit().clear().putString("tab","all").commit();
        for(int mode=0;mode<2;mode++) {
            P1UiChecks.configuration=new Configuration(target.getResources().getConfiguration());P1UiChecks.configuration.screenWidthDp=320;P1UiChecks.configuration.fontScale=mode==1?2:1;
            Activity screen=P1UiChecks.open(i,new Intent(target,MainActivity.class));awaitLabel(i,screen,"Newest ▾");
            AlertDialog[] dialog={null};i.runOnMainSync(()->{try{Method action=MainActivity.class.getDeclaredMethod("sortDocuments");action.setAccessible(true);dialog[0]=(AlertDialog)action.invoke(screen);}catch(Exception e){throw new RuntimeException(e);}});
            P1UiChecks.settle(i);P1UiChecks.captureDialog(i,dialog[0],"document-sort-"+mode);
            i.runOnMainSync(()->dialog[0].getListView().performItemClick(dialog[0].getListView().getChildAt(1),1,1));awaitLabel(i,screen,"Name ▾");
            List<String> expected=app.io.submit(()->ids(app.library.documents("",null,null,"name"))).get();assertRows(i,screen,expected);
            P1UiChecks.capture(i,screen,"documents-name-"+mode);
            Instrumentation.ActivityMonitor monitor=i.addMonitor(MainActivity.class.getName(),null,false);i.runOnMainSync(screen::recreate);
            Activity fresh=monitor.waitForActivityWithTimeout(10000);i.removeMonitor(monitor);P1Checks.require(fresh!=null,"Recreated library");awaitLabel(i,fresh,"Name ▾");assertRows(i,fresh,expected);
            i.runOnMainSync(()->P1UiChecks.findDescription(fresh.getWindow().getDecorView(),"Home").performClick());
            P1BatchChecks.await(()->{boolean[] ready={false};i.runOnMainSync(()->ready[0]=P1UiChecks.findText(fresh.getWindow().getDecorView(),"Recent documents")!=null);return ready[0];},"Home recent loaded");
            List<String> recent=app.io.submit(()->ids(app.library.documents("",null))).get();assertRows(i,fresh,recent.subList(0,Math.min(6,recent.size())));
            i.runOnMainSync(fresh::finish);
            P1BatchChecks.await(fresh::isDestroyed,"Finished library before changing next fixture preferences");
            target.getSharedPreferences("navigation",Context.MODE_PRIVATE).edit().clear().putString("tab","all").commit();
            mark.accept("Production sort chooser, visible Name indicator and actual document rows; persistent selection through recreation; Home Recent remains newest at 320 dp / "+(mode==1?"2x":"1x")+" text; synthetic view captures");
        }
        P1Checks.require(BackupHistory.last(target)>0,"Prior successful synthetic backup date survives instrumentation process restart");
        Activity backup=P1UiChecks.open(i,new Intent(target,BackupActivity.class));P1UiChecks.settle(i);
        i.runOnMainSync(()->P1Checks.require(P1UiChecks.findText(backup.getWindow().getDecorView(),BackupHistory.label(target))!=null,"Date visible after process restart"));i.runOnMainSync(backup::finish);
        mark.accept("Successful local backup date persists across instrumentation process restart and is displayed when Backup & restore reopens");
        P1UiChecks.configuration=null;
    }
    private static String[] seed(LibraryRepository library,Context context)throws Exception {
        String[] names={"Zulu synthetic","Alpha synthetic","alpha synthetic"};long[] times={3000,1000,2000};String[] ids=new String[3];
        for(int n=0;n<3;n++){Document doc=library.importDocument(android.net.Uri.fromFile(OcrQueueChecks.fixture(context,"sort-"+UUID.randomUUID()+".pdf")));ids[n]=doc.id;library.rename(doc.id,names[n]);library.database().execSQL("UPDATE documents SET added_at=? WHERE id=?",new Object[]{times[n],doc.id});}
        return ids;
    }
    private static List<String> ids(List<Document> docs){List<String> ids=new ArrayList<>();for(Document doc:docs)ids.add(doc.id);return ids;}
    private static void awaitLabel(Instrumentation i,Activity a,String label)throws Exception {P1BatchChecks.await(()->{boolean[] ready={false};i.runOnMainSync(()->ready[0]=P1UiChecks.findButton(a.getWindow().getDecorView(),label)!=null);return ready[0];},"Library sort label "+label);}
    private static void assertRows(Instrumentation i,Activity a,List<String> expected){i.runOnMainSync(()->{try{Field field=MainActivity.class.getDeclaredField("rows");field.setAccessible(true);List<String> actual=new ArrayList<>();for(Object row:(List<?>)field.get(a)){if(row instanceof Document)actual.add(((Document)row).id);else if(row.getClass().getSimpleName().equals("DocumentRow")){Field docs=row.getClass().getDeclaredField("documents");docs.setAccessible(true);actual.addAll(ids((List<Document>)docs.get(row)));}}P1Checks.require(actual.equals(expected),"Rendered rows match chosen order");}catch(ReflectiveOperationException e){throw new RuntimeException(e);}});}
}
