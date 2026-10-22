package org.stackdocs.app;

import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Command phases for actual disposable Android↔Rust files; never opens the real library. */
public final class SyncDeviceChecks {
    static JSONObject run(Instrumentation test,Bundle args)throws Exception {
        Context target=test.getTargetContext();if(!ScannerChecks.checksTarget(target))throw new SecurityException("Disposable Checks installation required");
        String name=args.getString("replicaName","sync-device");if(!name.matches("sync-[a-z0-9-]+"))throw new IllegalArgumentException("Isolated replica name required");
        Context isolated=new ScannerChecks.IsolatedContext(target,name);LibraryRepository library=new LibraryRepository(isolated);library.initialize();SyncStore store=new SyncStore(isolated,library);SyncExchange exchange=new SyncExchange(isolated,library);
        JSONObject output=new JSONObject();String phase=args.getString("phase","status");File inbox=new File(isolated.getFilesDir(),"inbox.stackexchange"),outbox=new File(isolated.getFilesDir(),"outbox.stackexchange");
        switch(phase) {
            case "identity": output=store.status();break;
            case "seed-local":
            case "seed": {
                if(!library.documents("",null).isEmpty())throw new IllegalStateException("Seed requires a fresh isolated library");
                File fixture=OcrQueueChecks.fixture(isolated,"sync-original.pdf");Document doc=library.importDocument(Uri.fromFile(fixture));
                library.rename(doc.id,"Android original atlas");library.setMemberships(doc.id,java.util.Arrays.asList("bills"));
                output=phase.equals("seed")?store.start(java.util.Collections.singletonList(args.getString("peer"))):store.status();output.put("document",doc.id).put("original_sha256",SyncStore.hash(library.source(doc),library.source(doc).length()));
                if(phase.equals("seed"))export(exchange,args.getString("peer"),outbox);break;
            }
            case "restore-local": {
                if(!library.documents("",null).isEmpty()||store.load()!=null)throw new IllegalStateException("Fresh Checks library required");
                try(FileInputStream input=new FileInputStream(new File(isolated.getFilesDir(),"authored.stackbackup"));LibraryArchive.Staged staged=library.archive().stage(input,new LibraryArchive.Progress())){library.archive().plan(staged);library.archive().commit(staged,new LibraryArchive.Progress());}
                output=store.status();break;
            }
            case "review-adoption": {
                try(FileInputStream input=new FileInputStream(inbox);SyncExchange.Staged staged=exchange.stage(input)){output=exchange.reviewAdoption(staged);}
                try(FileOutputStream file=new FileOutputStream(new File(isolated.getFilesDir(),"review.json"))){file.write(output.toString().getBytes(StandardCharsets.UTF_8));file.getFD().sync();}break;
            }
            case "adopt-reviewed":
            case "reject-review": {
                JSONObject reviewed=ArchiveJson.read(new String(java.nio.file.Files.readAllBytes(new File(isolated.getFilesDir(),"review.json").toPath()),StandardCharsets.UTF_8));
                String prior=SyncProtocol.canonical(store.status());boolean rejected=false;
                try(FileInputStream input=new FileInputStream(inbox);SyncExchange.Staged staged=exchange.stage(input)){output=exchange.importReviewed(staged,reviewed);}
                catch(Exception error){if(!phase.equals("reject-review"))throw error;rejected=true;output.put("reason",error.getMessage());}
                if(phase.equals("reject-review")){if(!rejected||!prior.equals(SyncProtocol.canonical(store.status())))throw new AssertionError("Invalid review was accepted or changed causal state");output.put("rejected",true);}break;
            }
            case "export":export(exchange,args.getString("peer"),outbox);output=store.status().put("outbox_bytes",outbox.length());break;
            case "import": {
                try(FileInputStream input=new FileInputStream(inbox);SyncExchange.Staged staged=exchange.stage(input)){
                    String group=args.getString("group");output=exchange.importFile(staged,group,group==null?null:members(args.getString("members","")));
                }break;
            }
            case "stop-import": {
                String boundary=args.getString("boundary");if(!java.util.Arrays.asList("originals_installed","transaction","rows_written","committed","receipt").contains(boundary))throw new IllegalArgumentException("Unknown stop boundary");
                exchange.probe(reached->{if(!reached.equals(boundary))return;try(FileOutputStream ready=new FileOutputStream(new File(isolated.getFilesDir(),"stop-ready"))){ready.write(boundary.getBytes(StandardCharsets.US_ASCII));ready.getFD().sync();}for(;;)try{Thread.sleep(1000);}catch(InterruptedException stopped){throw new java.io.InterruptedIOException("Check interrupted");}});
                try(FileInputStream input=new FileInputStream(inbox);SyncExchange.Staged staged=exchange.stage(input)){
                    if("true".equals(args.getString("reviewed"))){JSONObject review=ArchiveJson.read(new String(java.nio.file.Files.readAllBytes(new File(isolated.getFilesDir(),"review.json").toPath()),StandardCharsets.UTF_8));output=exchange.importReviewed(staged,review);}
                    else output=exchange.importFile(staged,null,null);
                }break;
            }
            case "reject": {
                String prior=SyncProtocol.canonical(store.status());boolean rejected=false;
                try(FileInputStream input=new FileInputStream(inbox);SyncExchange.Staged staged=exchange.stage(input)){exchange.importFile(staged,null,null);}catch(Exception expected){rejected=true;output.put("reason",expected.getMessage());}
                if(!rejected||!prior.equals(SyncProtocol.canonical(store.status())))throw new AssertionError("Hostile bundle changed sync status");output.put("rejected",true);break;
            }
            case "rename": {
                String id=args.getString("document"),title=args.getString("title");library.rename(id,title);store.capture();output=store.status().put("title",library.document(id).title);break;
            }
            case "delete": {
                String id=args.getString("document");library.deleteDocument(id);store.capture();output=store.status().put("deleted",id);break;
            }
            case "inspect": {
                output=store.status();JSONObject manifest=store.manifest();long generation=0;JSONArray suggestions=manifest.getJSONArray("suggestions");for(int i=0;i<suggestions.length();i++)generation=Math.max(generation,suggestions.getJSONObject(i).getLong("generation"));output.put("max_suggestion_generation",generation);
                JSONArray files=manifest.getJSONArray("files"),docs=new JSONArray();for(Document doc:library.documents("",null)){
                    JSONObject info=new JSONObject().put("id",doc.id).put("title",doc.title).put("notes",library.metadata.view(doc.id).notes);
                    for(int i=0;i<files.length();i++)if(files.getJSONObject(i).getString("path").equals("originals/"+doc.id+"/source"))info.put("sha256",files.getJSONObject(i).getString("sha256"));
                    docs.put(info);
                }output.put("documents",docs).put("files",files);
                try(android.database.Cursor row=library.database().rawQuery("SELECT count(*) FROM sync_change",null)){row.moveToFirst();output.put("raw_events",row.getLong(0));}
                break;
            }
            case "resolve": {
                JSONObject conflicts=store.status().getJSONObject("conflicts");String key=args.getString("key");JSONArray alternatives=conflicts.getJSONArray(key),expected=new JSONArray();int chosen=Integer.parseInt(args.getString("choice","0"));
                for(int i=0;i<alternatives.length();i++)expected.put(alternatives.getJSONObject(i).getJSONObject("id"));
                output=store.resolve(key,expected,alternatives.getJSONObject(chosen).get("value"));break;
            }
            default:throw new IllegalArgumentException("Unknown phase");
        }
        return output;
    }
    private static List<String> members(String csv){List<String> out=new ArrayList<>();for(String part:csv.split(","))if(!part.trim().isEmpty())out.add(part.trim());return out;}
    private static void export(SyncExchange exchange,String peer,File output)throws Exception {if(peer==null)throw new IllegalArgumentException("peer required");try(FileOutputStream file=new FileOutputStream(output)){exchange.export(peer,file);file.getFD().sync();}}
}
