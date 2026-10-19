package org.stackdocs.app;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

final class P1CorpusChecks {
    static void run(Instrumentation instrumentation,Consumer<String> mark)throws Exception {
        Context context=new ScannerChecks.IsolatedContext(instrumentation.getTargetContext());JSONObject corpus;
        try(InputStream input=instrumentation.getContext().getAssets().open("p1-details-corpus.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();LibraryRepository.copy(input,out,1024*1024);corpus=new JSONObject(out.toString("UTF-8"));}
        JSONArray reports=new JSONArray();LibraryRepository library=new LibraryRepository(context);library.initialize();
        try(OcrEngine engine=new OcrEngine(context)) {
            JSONArray cases=corpus.getJSONArray("cases");for(int i=0;i<cases.length();i++) {
                JSONObject item=cases.getJSONObject(i);JSONArray pages=item.getJSONArray("pages");List<String> source=new ArrayList<>();for(int p=0;p<pages.length();p++)source.add(pages.getString(p));
                SuggestionJob text=new SuggestionJob("synthetic","",0,"synthetic","synthetic",0,0,source,Collections.emptyList(),false,false,false);
                long start=android.os.SystemClock.elapsedRealtimeNanos();List<DetailValue> direct=MetadataRules.analyze(new MetadataStore.Snapshot(text,0,""));double sourceMs=(android.os.SystemClock.elapsedRealtimeNanos()-start)/1e6;
                File pdf=render(context,item.getString("id"),source);Document doc=library.importDocument(Uri.fromFile(pdf));long ocrStart=android.os.SystemClock.elapsedRealtime();OcrJob job;
                while((job=library.claimOcr())!=null){Bitmap bitmap=PageRenderer.render(job.source,job.mime,job.page,2400);try{library.completeOcr(job,engine.recognize(bitmap),null);}finally{bitmap.recycle();}}
                long ocrMs=android.os.SystemClock.elapsedRealtime()-ocrStart;MetadataStore.Snapshot snap=library.metadata.claim();start=android.os.SystemClock.elapsedRealtimeNanos();List<DetailValue> recognized=MetadataRules.analyze(snap);library.metadata.complete(snap,recognized,false);double pipelineMs=(android.os.SystemClock.elapsedRealtimeNanos()-start)/1e6;
                List<String> completions=MetadataRules.completions(SuggestionRules.analyze(snap.text).title,doc.addedAt,Collections.emptyList(),recognized);
                reports.put(new JSONObject().put("id",item.getString("id")).put("split",item.getString("split")).put("expected",item.getJSONObject("expected")).put("source",values(direct)).put("rendered_ocr",values(recognized)).put("source_details",records(direct)).put("rendered_ocr_details",records(recognized)).put("source_rule_ms",sourceMs).put("snapshot_analysis_commit_ms",pipelineMs).put("ocr_ms",ocrMs).put("pages",pages.length()).put("name_completions",new JSONArray(completions)).put("post_case_pss_kib",android.os.Debug.getPss()));library.deleteDocument(doc.id);
            }
        }
        JSONArray photos=new JSONArray();for(int i=0;i<20;i++){Bitmap bitmap=photoFixture(i);long start=android.os.SystemClock.elapsedRealtimeNanos();boolean candidate=PhotoMonitor.candidate(bitmap);double ms=(android.os.SystemClock.elapsedRealtimeNanos()-start)/1e6;bitmap.recycle();photos.put(new JSONObject().put("id","rendered-"+i).put("expected_document",i<10).put("candidate",candidate).put("ms",ms));}
        mark.accept("P1_CORPUS_JSON "+new JSONObject().put("rules",MetadataRules.VERSION).put("device",android.os.Build.MODEL).put("api",android.os.Build.VERSION.SDK_INT).put("provenance",corpus.getString("provenance")).put("cases",reports).put("photo_detector",photos));
    }
    private static JSONArray records(List<DetailValue> values){JSONArray records=new JSONArray();for(DetailValue value:values)records.put(value.json());return records;}
    private static JSONObject values(List<DetailValue> values)throws Exception{JSONObject json=new JSONObject();for(DetailValue value:values)json.put(value.field,value.value+"|"+value.qualifier);return json;}
    static File render(Context context,String id,List<String> pages)throws Exception{File file=new File(context.getCacheDir(),id+".pdf");PdfDocument pdf=new PdfDocument();try{Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);ink.setColor(Color.BLACK);ink.setTextSize(20);for(int i=0;i<pages.size();i++){PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(600,800,i+1).create());page.getCanvas().drawColor(Color.WHITE);int y=70;for(String line:pages.get(i).split("\n")){page.getCanvas().drawText(line,40,y,ink);y+=36;}pdf.finishPage(page);}try(FileOutputStream out=new FileOutputStream(file)){pdf.writeTo(out);}}finally{pdf.close();}return file;}
    static Bitmap photoFixture(int index){Bitmap bitmap=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);canvas.drawColor(index<10?Color.rgb(65+index*3,65,65):Color.rgb(70,90,140));
        if(index<10){if(index>=7)canvas.rotate((index-6)*2,400,500);ink.setColor(index==6?Color.rgb(235,230,215):Color.WHITE);canvas.drawRect(100,100,700,900,ink);ink.setColor(index==5?0xFF777777:Color.BLACK);ink.setTextSize(index%2==0?22:18);for(int y=170;y<840;y+=32)canvas.drawText("SYNTHETIC INVOICE 24.50",130,y,ink);}
        else if(index<13){ink.setColor(Color.WHITE);canvas.drawRect(100,100,700,900,ink);}
        else if(index<17){for(int x=0;x<800;x+=40){ink.setColor(Color.rgb((x*3)%255,180,60));canvas.drawRect(x,0,x+20,1000,ink);}}
        else {java.util.Random random=new java.util.Random(index);for(int i=0;i<2500;i++){ink.setColor(Color.rgb(random.nextInt(255),random.nextInt(255),random.nextInt(255)));canvas.drawCircle(random.nextInt(800),random.nextInt(1000),12,ink);}}
        return bitmap;
    }
}
