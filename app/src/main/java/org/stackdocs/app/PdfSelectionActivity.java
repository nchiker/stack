package org.stackdocs.app;

import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Exported GET_CONTENT gateway: ignore incoming IDs/paths, return only a user-selected copy. */
public final class PdfSelectionActivity extends LibraryActivity {
    private Runnable removeBack;
    private static final ExecutorService export=Executors.newSingleThreadExecutor();
    private static final ConcurrentHashMap<String,Result> requests=new ConcurrentHashMap<>();
    private String token;private LinearLayout body;private TextView status;private boolean delivered;
    private static final class Result{volatile Uri uri;volatile String error="";volatile boolean busy=true;volatile boolean canceled;File file;}
    static boolean accepts(Intent intent){if(!Intent.ACTION_GET_CONTENT.equals(intent.getAction()))return false;String type=intent.getType();if("application/pdf".equals(type))return true;if(!"*/*".equals(type)&&!"application/*".equals(type))return false;String[] types=intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);if(types==null)return true;for(String value:types)if(value.equals("application/pdf")||value.equals("application/*")||value.equals("*/*"))return true;return false;}
    @Override public void onCreate(Bundle state){super.onCreate(state);setResult(RESULT_CANCELED);if(!accepts(getIntent())){finish();return;}token=state==null?UUID.randomUUID().toString():state.getString("request",UUID.randomUUID().toString());
        LinearLayout root=Ui.column(this),bar=Ui.toolbar(this);bar.addView(Ui.iconButton(this,Icons.Symbol.CLOSE,"Cancel PDF selection",this::cancel),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));bar.addView(Ui.text(this,"Choose a PDF from Stack",18,false),new LinearLayout.LayoutParams(0,-2,1));root.addView(bar);ScrollView scroll=new ScrollView(this);body=Ui.column(this);body.setPadding(Ui.dp(this,20),Ui.dp(this,12),Ui.dp(this,20),Ui.dp(this,24));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));status=Ui.text(this,"Choose a document. A PDF copy with saved edits will be shared with the requesting app.",14,false);root.addView(status);root.addView(Ui.button(this,"Cancel",this::cancel));Ui.install(this,root);removeBack=Ui.backHandler(this,this::cancel);}
    @Override protected void onResume(){super.onResume();refresh();}
    @Override protected void onLibraryChanged(){refresh();}
    private void refresh(){if(!app().access.allowed())return;Result pending=requests.get(token);
        if(pending!=null){status.setText(pending.busy?"Preparing the selected PDF…":pending.error);if(pending.uri!=null&&!pending.canceled&&!pending.busy&&!delivered){delivered=true;Intent data=new Intent().setDataAndType(pending.uri,"application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);data.setClipData(ClipData.newRawUri("Selected document PDF",pending.uri));setResult(RESULT_OK,data);requests.remove(token);finish();}if(pending.busy)return;}
        work(() -> app().library.documents("",null),documents -> {body.removeAllViews();if(documents.isEmpty())body.addView(Ui.text(this,"No saved documents. Cancel and add a document in Stack first.",16,false));for(Document doc:documents)body.addView(Ui.button(this,doc.title+" · "+doc.pageCount+" pages",() -> choose(doc.id)));});
    }
    private void choose(String id){if(!app().access.allowed())return;Result result=new Result();if(requests.putIfAbsent(token,result)!=null){Result previous=requests.get(token);if(previous.busy)return;requests.put(token,result);}String request=token;StackApplication application=app();status.setText("Preparing selected PDF…");body.removeAllViews();
        export.execute(() -> {try(PdfCopies.Plan plan=application.io.submit(() -> PdfCopies.prepare(application.library,new File(application.getCacheDir(),"pdf_plans"),id)).get()) {
            File directory=new File(application.getCacheDir(),"exports");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Free storage and retry.");String outputToken=UUID.randomUUID().toString();result.file=new File(directory,outputToken+".pdf");
            try(FileOutputStream output=new FileOutputStream(result.file)){plan.write(output);output.getFD().sync();}
            if(result.canceled||!application.access.allowed()){result.file.delete();throw new IOException("Selection paused or canceled. Unlock Stack and select again.");}
            result.uri=PdfCopies.uri(application,outputToken,PdfCopies.name(plan.document.title,1,new HashSet<>()));
        }catch(Exception failure){if(result.file!=null)result.file.delete();result.error="Couldn't prepare this PDF. Unlock Stack or free storage, then select again.";}finally{result.busy=false;application.notifyLibraryChanged();}});
    }
    private void cancel(){Result result=requests.remove(token);if(result!=null){result.canceled=true;if(!result.busy&&result.file!=null)result.file.delete();}setResult(RESULT_CANCELED);finish();}
    @android.annotation.SuppressLint("GestureBackNavigation") // Native back callback uses Ui.backHandler.
    @Override public void onBackPressed(){cancel();}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("request",token);super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){if(removeBack!=null)removeBack.run();if(token!=null&&isFinishing()&&!delivered){Result result=requests.remove(token);if(result!=null){result.canceled=true;if(!result.busy&&result.file!=null)result.file.delete();}}super.onDestroy();}
}
