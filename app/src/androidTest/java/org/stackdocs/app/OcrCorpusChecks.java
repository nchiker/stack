package org.stackdocs.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Debug;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.function.Consumer;

/** Thirty independently authored documents, all synthetic. Never benchmarks personal documents. */
final class OcrCorpusChecks {
    private static final String[] SENTINELS={"marigold","tangerine","blueberry","pineapple","jellyfish","waterfall"};
    static void run(Context context, Consumer<String> report) throws Exception {
        LibraryRepository library=new LibraryRepository(context); library.initialize();
        try(OcrEngine engine=new OcrEngine(context)) {
            for(int doc=0;doc<30;doc++) {
                String group=doc<10 ? "clean" : doc<18 ? "receipt" : doc<24 ? "form" : "multipage";
                int count=doc>=24 ? 2 : 1;
                String[] truth=new String[count];
                File source=new File(context.getCacheDir(),"corpus-"+doc+(count==2 ? ".pdf" : ".png"));
                if(count==2) {
                    PdfDocument pdf=new PdfDocument();
                    try {
                        for(int p=0;p<count;p++) {
                            truth[p]=text(doc,p,group);
                            PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(1200,1600,p+1).create());
                            draw(page.getCanvas(),truth[p],doc,group);
                            pdf.finishPage(page);
                        }
                        try(FileOutputStream out=new FileOutputStream(source)){pdf.writeTo(out);}
                    } finally { pdf.close(); }
                } else {
                    truth[0]=text(doc,0,group);
                    Bitmap image=Bitmap.createBitmap(1800,2400,Bitmap.Config.ARGB_8888);
                    Canvas canvas=new Canvas(image); canvas.scale(1.5f,1.5f); draw(canvas,truth[0],doc,group);
                    try(FileOutputStream out=new FileOutputStream(source)){OcrQueueChecks.require(image.compress(Bitmap.CompressFormat.PNG,100,out),"Corpus image encoding");}
                    finally {image.recycle();}
                }
                Document document=library.importDocument(Uri.fromFile(source));
                library.rename(document.id,"Corpus specimen "+doc);
                String sourceHash=hash(source);
                for(int p=0;p<count;p++) {
                    // Variants exercise the established renderer without changing defaults globally.
                    PageState page=library.pages(document.id).get(p);
                    if(group.equals("form") && doc%2==0) library.editPage(document.id,p,page.edited(0,null,"black_white"));
                    if(group.equals("form") && doc%2==1) library.editPage(document.id,p,page.edited(0,null,"medium").flattened(true));
                    for(int edge:new int[]{1600,2400}) {
                        long began=SystemClock.elapsedRealtime();
                        Bitmap image=library.renderPage(library.document(document.id),p,edge);
                        int width=image.getWidth(),height=image.getHeight();
                        String actual;
                        try {actual=engine.recognize(image);} finally {image.recycle();}
                        long elapsed=SystemClock.elapsedRealtime()-began;
                        if(edge==2400) {
                            OcrJob job=library.claimOcr();
                            OcrQueueChecks.require(job!=null && job.documentId.equals(document.id) && job.pageId.equals(library.pages(document.id).get(p).id),"Corpus stable page claim");
                            OcrQueueChecks.require(library.completeOcr(job,actual,null),"Corpus real result committed");
                        }
                        JSONObject result=new JSONObject();
                        result.put("document",doc); result.put("page",p+1); result.put("group",group);
                        result.put("edge",edge); result.put("width",width); result.put("height",height);
                        result.put("truth",truth[p]); result.put("actual",actual); result.put("elapsed_ms",elapsed);
                        result.put("sampled_pss_kib",Debug.getPss()); result.put("source_sha256",sourceHash);
                        result.put("model_sha256",OcrModel.SHA256); result.put("config",OcrModel.CONFIG);
                        report.accept("OCR_CORPUS "+result);
                    }
                }
                if(count==2) {
                    Document hit=library.documents(SENTINELS[doc-24],null).get(0);
                    OcrQueueChecks.require(library.pagePosition(document.id,hit.matchPageId)==1,"Later-page corpus sentinel");
                    File exported=new File(context.getCacheDir(),"export.pdf");
                    try(FileOutputStream out=new FileOutputStream(exported)){library.exportPdf(library.document(document.id),out);}
                    OcrQueueChecks.require(hash(exported).equals(sourceHash),"Corpus untouched PDF original hash");
                }
                OcrQueueChecks.require(hash(library.source(document)).equals(sourceHash),"Corpus source hash preserved");
                OcrQueueChecks.require(source.delete(),"Synthetic staging cleanup");
            }
        }
        LibraryRepository reopened=new LibraryRepository(context); reopened.initialize();
        OcrQueueChecks.require(reopened.documents("",null).size()==30 && reopened.claimOcr()==null,"Corpus restart complete/idempotent");
        report.accept("Corpus complete: 30 authored documents, 36 pages, 72 paired rendering/engine samples; 6 later-page sentinels; synthetic sources only");
    }

    private static String text(int doc,int page,String group) {
        if(group.equals("receipt")) return "SYNTHETIC MARKET\nReceipt number "+(1000+doc)+"\nDate 2026-10-16\nApples 3.25\nBread 4.50\nSubtotal 7.75\nTax 0.62\nTotal 8.37\nPaid by card\nThank you for shopping.";
        if(group.equals("form")) return "SYNTHETIC REGISTRATION\nReference number "+(42000+doc)+"\nName: Sample Person\nAccount: DEMO-2718\nPostal code: 10001\nAmount due: 123.45\nDue date: 2026-11-15\nPlease keep this form for your records.";
        if(group.equals("multipage") && page==1) return "ARCHIVE SECOND PAGE\nA quartz lantern marks "+SENTINELS[doc-24]+".\nKeep this printed page in the document.\nThe reference amount is 456.78.";
        return "PRINTED ENGLISH SPECIMEN\nDocument number "+(doc+1)+"\nThe quiet cedar garden receives morning light.\nBlue folders contain invoices and letters.\nPlease retain the original pages for your records.\nThe total amount is 123.45.\nNo account or network connection is required.";
    }

    private static void draw(Canvas canvas,String text,int doc,String group) {
        canvas.drawColor(Color.WHITE);
        Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean difficult=group.equals("form") || group.equals("receipt") && doc%2==0;
        if(difficult) {
            ink.setShader(new LinearGradient(0,0,1200,0,Color.rgb(190,190,180),Color.WHITE,Shader.TileMode.CLAMP));
            canvas.drawRect(0,0,1200,1600,ink); ink.setShader(null);
            canvas.rotate(doc%2==0 ? 1.4f : -1.2f,600,800);
        }
        ink.setColor(group.equals("form") ? Color.rgb(125,125,125) : Color.BLACK);
        ink.setTextSize(group.equals("form") ? 24 : group.equals("receipt") ? 32 : 34);
        int line=0;
        for(String value:text.split("\n")) canvas.drawText(value,70,150+(line++)*64,ink);
    }
    private static String hash(File source) throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try(java.io.InputStream in=new java.io.FileInputStream(source)) {
            byte[] bytes=new byte[32768]; for(int n;(n=in.read(bytes))!=-1;)hash.update(bytes,0,n);
        }
        StringBuilder hex=new StringBuilder();for(byte b:hash.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();
    }
}
