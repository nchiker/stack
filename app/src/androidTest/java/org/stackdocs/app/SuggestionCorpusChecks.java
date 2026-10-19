package org.stackdocs.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Debug;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** Same independently authored labels scored on rule text and on rendered-page real OCR, separately. */
final class SuggestionCorpusChecks {
    static void run(Context context,Context testContext,String asset,Consumer<String> mark) throws Exception {
        JSONObject corpus;
        try(java.io.InputStream input=testContext.getAssets().open(asset)) {
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(); LibraryRepository.copy(input,out,1024*1024);
            corpus=new JSONObject(out.toString("UTF-8"));
        }
        JSONArray documents=corpus.getJSONArray("documents"),rows=new JSONArray();
        List<String> catalog=Arrays.asList("bills","banking","ids","house","medical","receipts","starred");
        LibraryRepository library=new LibraryRepository(context); library.initialize();
        long maxPss=0;
        try(OcrEngine engine=new OcrEngine(context)) {
            for(int i=0;i<documents.length();i++) {
                JSONObject item=documents.getJSONObject(i); JSONArray source=item.getJSONArray("pages");
                List<String> text=new ArrayList<>(); for(int p=0;p<source.length();p++) text.add(source.getString(p));
                long start=SystemClock.elapsedRealtimeNanos(); SuggestionRules.Result direct=SuggestionRules.analyze(text,catalog,false,false);
                double directMs=(SystemClock.elapsedRealtimeNanos()-start)/1e6;
                File file=render(context,item.getString("id"),text);
                Document document=library.importDocument(Uri.fromFile(file));
                long ocrStart=SystemClock.elapsedRealtime(); SuggestionJob claimed;
                OcrJob page; while((page=library.claimOcr())!=null) {
                    Bitmap image=PageRenderer.render(page.source,page.mime,page.page,2400);
                    try { SuggestionChecks.require(library.completeOcr(page,engine.recognize(image),null),"Corpus OCR current"); }
                    finally {image.recycle();}
                }
                long ocrMs=SystemClock.elapsedRealtime()-ocrStart;
                start=SystemClock.elapsedRealtimeNanos(); claimed=library.suggestions.claim();
                SuggestionChecks.require(claimed!=null,"Corpus proposal");
                SuggestionRules.Result recognized=SuggestionRules.analyze(claimed);
                SuggestionChecks.require(library.suggestions.complete(claimed,recognized,false),"Corpus commit");
                double pipelineMs=(SystemClock.elapsedRealtimeNanos()-start)/1e6;
                JSONObject row=new JSONObject(); row.put("id",item.getString("id")); row.put("split",item.getString("split")); row.put("labels",item.getJSONArray("labels"));
                row.put("rule_text",new JSONObject(direct.encode())); row.put("ocr",new JSONObject(recognized.encode()));
                row.put("rule_ms",directMs); row.put("proposal_snapshot_analysis_commit_ms",pipelineMs); row.put("ocr_ms",ocrMs);
                if(item.has("expected_name")) row.put("expected_name",item.getString("expected_name"));
                row.put("characters",android.text.TextUtils.join("\n",text).length()); row.put("pages",text.size()); rows.put(row);
                maxPss=Math.max(maxPss,Debug.getPss());
                library.deleteDocument(document.id);
            }
        }
        JSONObject report=new JSONObject(); report.put("rules",SuggestionRules.VERSION); report.put("device",android.os.Build.MODEL); report.put("api",android.os.Build.VERSION.SDK_INT);
        report.put("provenance",corpus.getString("provenance")); report.put("post_document_max_pss_kib",maxPss); report.put("documents",rows);
        mark.accept("SUGGESTION_CORPUS_JSON "+report);
    }
    private static File render(Context context,String id,List<String> pages) throws Exception {
        File target=new File(context.getCacheDir(),id+".pdf"); PdfDocument pdf=new PdfDocument();
        try {
            Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG); ink.setColor(Color.BLACK); ink.setTextSize(20);
            for(int p=0;p<pages.size();p++) {
                PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(600,800,p+1).create()); page.getCanvas().drawColor(Color.WHITE);
                int y=70; for(String line:pages.get(p).split("\n")) {page.getCanvas().drawText(line,40,y,ink); y+=36;}
                pdf.finishPage(page);
            }
            try(FileOutputStream out=new FileOutputStream(target)){pdf.writeTo(out);}
        } finally {pdf.close();}
        return target;
    }
}
