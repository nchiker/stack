package org.stackdocs.app;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.util.function.Consumer;

/** External force-stop at file/transaction boundaries; never targets the canonical package. */
final class ArchiveRestartChecks {
    static void run(Context context,boolean prepare,String boundary,Consumer<String> mark)throws Exception {
        if(!context.getPackageName().equals("org.stackdocs.app.checks"))throw new SecurityException("Archive termination checks require disposable package");
        LibraryRepository library=new LibraryRepository(context);library.initialize();
        if(prepare) {
            Document fixture=library.importDocument(Uri.fromFile(OcrQueueChecks.fixture(context,"restore-restart.pdf")));library.rename(fixture.id,"Archive imported record");
            byte[] bytes;try(LibraryArchive.Snapshot snapshot=library.archive().snapshot(new LibraryArchive.Progress())){ByteArrayOutputStream out=new ByteArrayOutputStream();LibraryArchive.write(snapshot,out,new LibraryArchive.Progress());bytes=out.toByteArray();}
            library.rename(fixture.id,"Existing record must stay");
            LibraryArchive archive=library.archive();
            archive.probe=at->{if(at.equals(boundary)){mark.accept("ARCHIVE_RESTART_READY "+boundary);try{Thread.sleep(30000);}catch(InterruptedException interrupted){throw new IOException(interrupted);}throw new AssertionError("External force-stop did not occur");}};
            LibraryArchive.Staged staged=archive.stage(new ByteArrayInputStream(bytes),new LibraryArchive.Progress());archive.plan(staged);
            archive.commit(staged,new LibraryArchive.Progress());throw new AssertionError("Boundary not reached");
        } else {
            int expected=boundary.equals("committed")?2:1;
            WorkflowRepositoryChecks.require(library.documents("",null).size()==expected && library.documents("Existing record",null).size()==1,"New process restore visibility and existing preservation");
            WorkflowRepositoryChecks.require(new File(context.getFilesDir(),"documents").list().length==expected,"No orphan original directories");
            library.initialize();WorkflowRepositoryChecks.require(library.documents("",null).size()==expected,"Second recovery idempotent");
            mark.accept("External force-stop/new-process restore recovery passed at "+boundary+"; originals="+expected+", no partial records, existing name retained, repeat recovery idempotent");
        }
    }
}
