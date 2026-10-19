package org.stackdocs.app;

import android.app.Activity;
import android.content.*;
import android.os.*;
import java.io.*;

/** Separate test UID requests a PDF through the actual GET_CONTENT activity-result contract. */
public final class PdfRequestProbeActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);if(state==null){Intent request=new Intent(Intent.ACTION_GET_CONTENT).setType(getIntent().getStringExtra("mime")).addCategory(Intent.CATEGORY_OPENABLE).setClassName(getIntent().getStringExtra("target"),"org.stackdocs.app.PdfSelectionActivity");request.putExtra("document_id","../../private/database");request.setDataAndType(android.net.Uri.parse("content://malicious/private"),getIntent().getStringExtra("mime"));startActivityForResult(request,91);}}
    @Override protected void onActivityResult(int request,int code,Intent data){super.onActivityResult(request,code,data);Intent report=new Intent().putExtra("result_code",code).putExtra("uid",android.os.Process.myUid());
        if(code==RESULT_OK&&data!=null&&data.getData()!=null){report.putExtra("flags",data.getFlags());report.putExtra("mime",data.getType());report.putExtra("uri",data.getData().toString());try(InputStream in=getContentResolver().openInputStream(data.getData())){long size=0;byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)size+=n;report.putExtra("bytes",size);boolean readOnly=false;try(ParcelFileDescriptor ignored=getContentResolver().openFileDescriptor(data.getData(),"w")){}catch(Exception expected){readOnly=true;}report.putExtra("read_only",readOnly);}catch(Exception failure){report.putExtra("error",failure.getClass().getSimpleName());}}
        setResult(RESULT_OK,report);finish();
    }
}
