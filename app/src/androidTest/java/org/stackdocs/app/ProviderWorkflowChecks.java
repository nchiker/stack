package org.stackdocs.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Cross-UID grants are exercised only with a unique authored PDF, not a private-library export. */
final class ProviderWorkflowChecks {
    static void run(Instrumentation instrumentation,Context context,Consumer<String> mark) throws Exception {
        LibraryRepository library=new LibraryRepository(context);library.initialize();
        Uri valid=Uri.parse("content://"+instrumentation.getContext().getPackageName()+".fixtures/valid");
        Document document=library.importDocument(valid);
        WorkflowRepositoryChecks.require(document.pageCount==2 && !document.title.contains("\n"),"Provider import probes actual bytes and sanitizes optional name");
        Document unnamed=library.importDocument(Uri.parse("content://"+instrumentation.getContext().getPackageName()+".fixtures/unnamed"));
        WorkflowRepositoryChecks.require(!unnamed.title.isEmpty() && unnamed.pageCount==2,"Optional name failure retains dated fallback");
        int count=library.documents("",null).size();
        for(String kind:Arrays.asList("denied","partial","unsupported")) {
            boolean rejected=false;try {library.importDocument(Uri.parse("content://"+instrumentation.getContext().getPackageName()+".fixtures/"+kind));}
            catch(Exception expected){rejected=true;}
            WorkflowRepositoryChecks.require(rejected && library.documents("",null).size()==count,"Provider "+kind+" failure adds no metadata");
        }
        WorkflowRepositoryChecks.require(new File(context.getFilesDir(),"incoming").list().length==0,"Failed provider imports leave no staging");
        byte[] original=ScannerChecks.bytes(library.source(document));
        // Release the external source process/grants: the app's private copy must still preview/export.
        android.graphics.Bitmap preview=library.renderPage(document,1,800);preview.recycle();
        File exported=new File(context.getCacheDir(),"provider-export.pdf");
        try(FileOutputStream out=new FileOutputStream(exported)){library.exportPdf(library.document(document.id),out);}
        WorkflowRepositoryChecks.require(Arrays.equals(original,ScannerChecks.bytes(exported)),"Untouched provider PDF exports byte-identically");
        mark.accept("Real external content provider: valid/unnamed two-page imports; denied/interrupted/unsupported inputs roll back cleanly; private-copy preview and byte-identical PDF export");

        Context target=instrumentation.getTargetContext();String token=UUID.randomUUID().toString();
        File directory=new File(target.getCacheDir(),"exports");WorkflowRepositoryChecks.require(directory.isDirectory() || directory.mkdirs(),"Export cache");
        File fixture=new File(directory,token+".pdf");
        try(FileOutputStream out=new FileOutputStream(fixture)){out.write(original);}
        Uri uri=Uri.parse("content://"+instrumentation.getTargetContext().getPackageName()+".exports/"+token+"/Synthetic-workflow.pdf");
        ArrayBlockingQueue<Intent> replies=new ArrayBlockingQueue<>(1);
        IsolatedCheckActivity.content=activity -> new android.widget.TextView(activity);
        IsolatedCheckActivity.result=(request,data) -> {if(data!=null)replies.offer(data);};
        Activity host=null;
        try {
            host=instrumentation.startActivitySync(new Intent(target,IsolatedCheckActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Intent denied=probe(instrumentation,host,uri,false,replies);
            WorkflowRepositoryChecks.require(denied.getIntExtra("uid",-1)!=android.os.Process.myUid() && denied.getStringExtra("status").equals("denied"),"Separate UID denied without grant");
            Intent granted=probe(instrumentation,host,uri,true,replies);
            WorkflowRepositoryChecks.require(granted.getStringExtra("status").equals("read") && granted.getBooleanExtra("read_only",false)
                    && granted.getIntExtra("bytes",-1)==original.length,"Read grant exposes only complete synthetic PDF, no write");
            try(java.io.InputStream input=target.getContentResolver().openInputStream(uri)) {
                java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
                byte[] bytes=new byte[4096];int n;while((n=input.read(bytes))!=-1)digest.update(bytes,0,n);
                StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                WorkflowRepositoryChecks.require(hex.toString().equals(granted.getStringExtra("sha256")),"Granted bytes match fixture hash");
            }
            target.revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            WorkflowRepositoryChecks.require(probe(instrumentation,host,uri,false,replies).getStringExtra("status").equals("denied"),"Revoked grant denies later receiver");
            try(Cursor row=target.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)) {
                WorkflowRepositoryChecks.require(row.moveToFirst() && row.getString(0).equals("Synthetic-workflow.pdf") && row.getLong(1)==original.length,"Only PDF display name/size exposed");
            }
            boolean writeDenied=false;try(ParcelFileDescriptor ignored=target.getContentResolver().openFileDescriptor(uri,"w")){}
            catch(java.io.FileNotFoundException expected){writeDenied=true;}
            WorkflowRepositoryChecks.require(writeDenied,"Owner also subject to provider read-only mode");
            for(String path:Arrays.asList("not-a-token/Synthetic.pdf",token+"/extra/Synthetic.pdf","%2e%2e/source")) {
                boolean invalidDenied=false;
                try(ParcelFileDescriptor ignored=target.getContentResolver().openFileDescriptor(Uri.parse("content://"+instrumentation.getTargetContext().getPackageName()+".exports/"+path),"r")){}
                catch(java.io.FileNotFoundException expected){invalidDenied=true;}
                WorkflowRepositoryChecks.require(invalidDenied,"Invalid/path tokens rejected");
            }
            WorkflowRepositoryChecks.require(fixture.delete(),"Remove exact synthetic export");
            try(Cursor row=target.getContentResolver().query(uri,null,null,null,null)){WorkflowRepositoryChecks.require(row.getCount()==0,"Expired URI exposes no metadata");}
            mark.accept("Actual separate-UID export recipient: no-grant/revoked denial, read-only grant, matching bytes/hash, metadata, malformed token and expired-file handling");
        }finally{
            target.revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);fixture.delete();
            Activity close=host;if(close!=null)instrumentation.runOnMainSync(close::finish);
            IsolatedCheckActivity.content=null;IsolatedCheckActivity.result=null;
        }
    }
    private static Intent probe(Instrumentation instrumentation,Activity host,Uri uri,boolean grant,ArrayBlockingQueue<Intent> replies) throws Exception {
        Intent intent=new Intent().setClassName(instrumentation.getContext().getPackageName(),"org.stackdocs.app.ExportGrantProbeActivity").setDataAndType(uri,"application/pdf");
        if(grant)intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).setClipData(ClipData.newRawUri("Synthetic PDF",uri));
        instrumentation.runOnMainSync(() -> host.startActivityForResult(intent,77));
        Intent reply=replies.poll(10,TimeUnit.SECONDS);WorkflowRepositoryChecks.require(reply!=null,"Cross-UID probe returned result");return reply;
    }
}
