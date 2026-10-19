package org.stackdocs.app;

import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Only the authored tiny fixture may enter this destructive isolated restore check. */
final class GoogleMigrationChecks {
    static void run(Instrumentation test,Consumer<String> mark)throws Exception {
        Context target=test.getTargetContext();P1Checks.require(target.getPackageName().equals("org.stackdocs.app.checks"),"Disposable migration checks only");
        File fixture=new File(target.getFilesDir(),"checks/legacy-fixture.stackbackup");P1Checks.require(fixture.length()>48&&fixture.length()<100000,"Tiny synthetic fixture required");
        byte[] bytes;try(FileInputStream input=new FileInputStream(fixture)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)out.write(buffer,0,count);bytes=out.toByteArray();}
        JSONObject manifest;try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes))){byte[] magic=new byte[8];input.readFully(magic);int size=input.readInt();P1Checks.require(size>0&&size<50000,"Synthetic metadata bound");input.skipBytes(32);byte[] json=new byte[size];input.readFully(json);manifest=ArchiveJson.read(new String(json,StandardCharsets.UTF_8));}
        P1Checks.require(manifest.getJSONArray("documents").length()==1&&manifest.getJSONArray("documents").getJSONObject(0).getString("title").equals("Synthetic receipt")&&manifest.getJSONArray("documents").getJSONObject(0).getString("notes").equals("Synthetic notes quartz"),"Authored fixture identity, never personal archive");
        LibraryRepository fresh=new LibraryRepository(new ScannerChecks.IsolatedContext(target));fresh.initialize();
        LibraryArchive freshArchive=fresh.archive();
        try(LibraryArchive.Staged freshStage=freshArchive.stage(new ByteArrayInputStream(bytes),new LibraryArchive.Progress())) {
            freshArchive.plan(freshStage);freshArchive.commit(freshStage,new LibraryArchive.Progress());
        }
        for(StackCategory category:fresh.stacks()) {
            P1Checks.require(!category.id.equals("bills"),"Fresh migration keeps opaque source category IDs");
            P1Checks.require(Icons.category(category)==(category.name.equals("Bills")?Icons.Symbol.BILLS:Icons.Symbol.FOLDER),"Fresh phone migration resolves familiar icons by name and keeps custom fallback");
            P1Checks.require(StackStyle.ringColor(category)!=0xFFE4EDE2,"Fresh phone migration shows colored rings for standard and custom stacks");
            if(category.name.equals("Bills"))P1Checks.require(StackStyle.ringColor(category)==0xFFB5E2ED,"Imported Bills retains standard blue ring");
        }
        mark.accept("Fresh phone restore retains imported category IDs and resolves Bills icon without changing the archive");
        Context isolated=new ScannerChecks.IsolatedContext(target);LibraryRepository library=new LibraryRepository(isolated);library.initialize();
        Document existing=library.importDocument(Uri.fromFile(OcrQueueChecks.fixture(isolated,"existing.pdf")));library.rename(existing.id,"Existing preserved");
        LibraryArchive archive=library.archive();LibraryArchive.Staged staged=archive.stage(new ByteArrayInputStream(bytes),new LibraryArchive.Progress());LibraryArchive.Plan plan=archive.plan(staged);
        P1Checks.require(library.documents("",null).size()==1&&plan.mergedStacks==1,"Review preserves existing library and matches Bills by name");archive.commit(staged,new LibraryArchive.Progress());
        String id=plan.documents.get(manifest.getJSONArray("documents").getJSONObject(0).getString("id"));Document imported=library.document(id);
        P1Checks.require(library.documents("",null).size()==2&&library.document(existing.id).title.equals("Existing preserved"),"Additive import keeps existing identity/name");
        P1Checks.require(imported.title.equals("Synthetic receipt")&&imported.stackIds.size()==1&&!imported.stackIds.contains("bills"),"Current membership imported; excluded Bills not resurrected");
        MetadataStore.View view=library.metadata.view(id);P1Checks.require(view.notes.equals("Synthetic notes quartz")&&view.saved.stream().anyMatch(v -> v.label.equals("Amount")&&v.value.equals("$24.50")&&v.kind.equals("text")),"Exact detail text/type provenance and notes survive production restore");
        try(android.database.Cursor row=library.database().rawQuery("SELECT count(*) FROM details WHERE document_id=? AND state='removed'",new String[]{id})){row.moveToFirst();P1Checks.require(row.getInt(0)==2,"Detail and category-exclusion tombstones retained");}
        P1Checks.require(library.documents("quartz",null).stream().anyMatch(d -> d.id.equals(id)),"Derived notes search rebuilt");
        try(LibraryArchive.Snapshot snapshot=archive.snapshot(new LibraryArchive.Progress())){P1Checks.require(snapshot.manifest.getJSONArray("files").length()==3,"Existing original, imported PDF and retained page JPEG survive snapshot");}
        mark.accept("Authored Google Stack conversion fixture stages/reviews/restores through production archive 2: additive name-matched stacks, exact values/provenance/notes, excluded filing/tombstones, retained PDF/JPEG and rebuilt search; no personal archive used");
    }
}
