package org.stackdocs.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

final class OcrQueueChecks {
    static void run(Context context, Consumer<String> mark) throws Exception {
        LibraryRepository library=new LibraryRepository(context); library.initialize();
        File source=fixture(context,"source.pdf");
        byte[] original=ScannerChecks.bytes(source);
        Document document=library.importDocument(Uri.fromFile(source));
        library.rename(document.id,"Specimen Atlas"); library.setMemberships(document.id,Arrays.asList("bills"));
        List<PageState> pages=library.pages(document.id);
        require(library.documents("Atlas",null).size()==1,"Pending title search");
        try(OcrEngine engine=new OcrEngine(context)) {
            for(int i=0;i<2;i++) {
                OcrJob job=library.claimOcr(); require(job!=null,"Durable claim");
                Bitmap bitmap=PageRenderer.render(job.source,job.mime,job.page,2400);
                try { require(library.completeOcr(job,engine.recognize(bitmap),null),"Real result accepted"); }
                finally { bitmap.recycle(); }
                require(!library.completeOcr(job,"duplicate",null),"Completed claim cannot commit twice");
            }
        }
        require(library.claimOcr()==null,"No duplicate work");
        Document hit=library.documents("quartz",null).get(0);
        require(hit.matchPageId.equals(pages.get(1).id) && hit.snippet.contains("Page 2") && hit.snippet.contains("quartz"),"Later-page snippet/stable id");
        require(library.pagePosition(document.id,hit.matchPageId)==1,"Viewer resolves page 2");
        require(library.documents("Atlas quartz",null).get(0).matchPageId.equals(pages.get(1).id),"Mixed title/body navigation");
        require(library.documents("Atlas",null).get(0).matchPageId==null,"Title-only normal opening");
        require(library.documents("cedar quartz",null).get(0).matchPageId==null,"Cross-page query does not invent one page");
        require(library.documents("quartz!","bills").size()==1 && library.documents("quartz","receipts").isEmpty(),"Punctuation/stack filter");
        require(library.documents("?!",null).isEmpty() && library.documents("unfindable",null).isEmpty(),"Punctuation-only/no results");
        File exported=new File(context.getCacheDir(),"export.pdf");
        try(FileOutputStream out=new FileOutputStream(exported)){ library.exportPdf(library.document(document.id),out); }
        require(Arrays.equals(original,ScannerChecks.bytes(exported)),"OCR-only PDF byte preservation");
        library=new LibraryRepository(context); library.initialize();
        require(library.documents("quartz",null).get(0).matchPageId.equals(pages.get(1).id) && library.claimOcr()==null,"Repository restart/idempotent backfill");
        mark.accept("Real multipage OCR, mixed/cross-page/title/filtered search, stable page navigation, restart and byte-identical PDF export");

        String second=pages.get(1).id;
        library.retryOcr(document.id,second); OcrJob wrongConfig=library.claimOcr();
        try(LibraryDatabase helper=new LibraryDatabase(context)) {
            helper.getWritableDatabase().execSQL("UPDATE pages SET ocr_config='synthetic-other-config' WHERE id=?",new Object[]{second});
        }
        require(!library.completeOcr(wrongConfig,"wrong configuration",null),"Configuration signature mismatch rejected");
        library.recoverOcr();
        require(library.documents("quartz",null).size()==1,"Configuration reprocess retains still-current text");
        OcrJob configured=library.claimOcr();
        require(configured.config.equals(OcrModel.CONFIG) && library.completeOcr(configured,"quartz",null),"Configuration change backfill runs once");
        library.retryOcr(document.id,second); OcrJob retry=library.claimOcr();
        require(library.completeOcr(retry,null,"synthetic_failure"),"Failure accepted");
        require(library.documents("quartz",null).size()==1 && library.ocrStatus(document.id,1).state.equals("failed"),"Failed retry preserves current successful text");
        library.retryOcr(document.id,second); OcrJob old=library.claimOcr();
        library.editPage(document.id,1,pages.get(1).edited(90,new float[]{.1f,.1f,.9f,.1f,.9f,.9f,.1f,.9f},"bright").flattened(true));
        require(library.documents("quartz",null).isEmpty() && library.documents("cedar",null).size()==1,"Edit invalidates only edited text");
        require(!library.completeOcr(old,"stale",null) && !library.completeOcr(old,null,"late_failure"),"Stale edit success/failure rejected");
        OcrJob fresh=library.claimOcr();
        require(fresh.revision==old.revision+1 && fresh.page.rotation==90 && fresh.page.flatten,"Revision/immutable edits snapshot");
        library.retryOcr(document.id,second); OcrJob newer=library.claimOcr();
        require(!library.completeOcr(fresh,"stale",null) && !library.completeOcr(fresh,null,"late_failure"),"Superseded token rejected");
        require(library.completeOcr(newer,"amethyst",null),"Newer retry accepted");
        require(library.documents("amethyst",null).size()==1,"Fresh result indexed");
        mark.accept("Retry failure retains valid text; edit/retry races reject late success and failure");

        library.retryOcr(document.id,second); OcrJob removed=library.claimOcr();
        library.removePage(document.id,1);
        require(!library.completeOcr(removed,"resurrect",null) && !library.completeOcr(removed,null,"late_failure"),"Removed page cannot resurrect");
        require(library.documents("amethyst",null).isEmpty(),"Removed page index cleared");
        library.retryOcr(document.id,pages.get(0).id); OcrJob deleted=library.claimOcr();
        library.deleteDocument(document.id);
        require(!library.completeOcr(deleted,"resurrect",null) && !library.completeOcr(deleted,null,"late_failure"),"Deleted document cannot resurrect");
        require(library.documents("",null).isEmpty(),"Deletion durable");
        mark.accept("Page removal/document deletion reject stale success and failure, with FTS cleanup");

        document=library.importDocument(Uri.fromFile(source)); pages=library.pages(document.id);
        for(int attempt=0;attempt<LibraryRepository.OCR_ATTEMPT_LIMIT;attempt++) {
            OcrJob interrupted=library.claimOcr();
            require(interrupted.pageId.equals(pages.get(0).id),"Recover same stable page");
            library=new LibraryRepository(context); library.initialize();
            require(!library.completeOcr(interrupted,"stale",null),"Interrupted token cleared");
        }
        require(library.ocrStatus(document.id,0).state.equals("failed"),"Crash loop bounded");
        OcrJob other=library.claimOcr(); require(other.pageId.equals(pages.get(1).id),"Poison page does not block next page");
        library.completeOcr(other,"otherpage",null);
        library.retryOcr(document.id,pages.get(0).id); OcrJob recovered=library.claimOcr();
        require(library.completeOcr(recovered,"",null),"Blank success after durable manual retry");
        require(library.claimOcr()==null && library.documents("otherpage",null).size()==1,"No duplicate work or lost successful pages");
        String moved=library.pages(document.id).get(1).id;
        library.removePage(document.id,0);
        Document movedHit=library.documents("otherpage",null).get(0);
        require(movedHit.matchPageId.equals(moved) && library.pagePosition(document.id,moved)==0 && movedHit.snippet.contains("Page 1"),"Stable hit follows current page order");
        require(library.pages(document.id).get(0).sourcePage==1,"Original PDF source page independent of displayed order");
        mark.accept("Startup interruption recovery, three-attempt crash budget, other-page progress and durable manual retry");
    }

    static File fixture(Context context,String name) throws Exception {
        File file=new File(context.getCacheDir(),name);
        PdfDocument pdf=new PdfDocument();
        try {
            Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG); ink.setColor(Color.BLACK); ink.setTextSize(22);
            for(int number=1;number<=2;number++) {
                PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(600,800,number).create());
                page.getCanvas().drawColor(Color.WHITE);
                page.getCanvas().drawText(number==1 ? "The cedar garden is peaceful." : "A bright quartz lantern shines.",40,120,ink);
                page.getCanvas().drawText("Invoice total 123.45",40,170,ink);
                pdf.finishPage(page);
            }
            try(FileOutputStream out=new FileOutputStream(file)){pdf.writeTo(out);}
        } finally { pdf.close(); }
        return file;
    }
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
