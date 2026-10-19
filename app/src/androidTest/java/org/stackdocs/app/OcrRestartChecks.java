package org.stackdocs.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import java.util.function.Consumer;

/** Two instrumentation processes separated by a deliberate external force-stop. */
final class OcrRestartChecks {
    static void run(Context context, boolean prepare, Consumer<String> mark) throws Exception {
        LibraryRepository library=new LibraryRepository(context); library.initialize();
        if (prepare) {
            OcrQueueChecks.require(library.documents("",null).isEmpty(),"Restart fixture must start fresh");
            Document document=library.importDocument(Uri.fromFile(OcrQueueChecks.fixture(context,"restart.pdf")));
            try(OcrEngine engine=new OcrEngine(context)) {
                for(int i=0;i<2;i++) {
                    OcrJob job=library.claimOcr();
                    Bitmap image=PageRenderer.render(job.source,job.mime,job.page,2400);
                    try { library.completeOcr(job,engine.recognize(image),null); } finally { image.recycle(); }
                }
                library.retryOcr(document.id,library.pages(document.id).get(0).id);
                OcrJob running=library.claimOcr();
                Bitmap dense=Bitmap.createBitmap(2400,3200,Bitmap.Config.ARGB_8888); dense.eraseColor(Color.WHITE);
                Canvas canvas=new Canvas(dense); Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG); ink.setTextSize(26);
                for(int line=0;line<100;line++) canvas.drawText("Synthetic interruption fixture row "+line+" reading printed text before restart.",50,80+line*30,ink);
                mark.accept("OCR_RESTART_READY: durable running claim; entering real native recognition");
                try { engine.recognize(dense); } finally { dense.recycle(); }
                throw new AssertionError("Restart check was not interrupted during native work");
            }
        } else {
            Document document=library.documents("",null).get(0);
            OcrJob recovered=library.claimOcr(); OcrQueueChecks.require(recovered!=null,"Interrupted claim requeued on launch");
            try(OcrEngine engine=new OcrEngine(context)) {
                Bitmap image=PageRenderer.render(recovered.source,recovered.mime,recovered.page,2400);
                try { OcrQueueChecks.require(library.completeOcr(recovered,engine.recognize(image),null),"Recovered real result commits once"); }
                finally { image.recycle(); }
            }
            Document hit=library.documents("quartz",null).get(0);
            OcrQueueChecks.require(library.pagePosition(document.id,hit.matchPageId)==1 && hit.snippet.contains("Page 2"),"Later-page result survives process death");
            library.initialize();
            OcrQueueChecks.require(library.claimOcr()==null && library.documents("",null).size()==1,"Repeated startup idempotent");
            mark.accept("Force-stop/native interruption recovered; later-page snippet/navigation survives new process; no duplicate jobs/documents");
        }
    }
}
