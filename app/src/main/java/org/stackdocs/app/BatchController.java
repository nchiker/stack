package org.stackdocs.app;

import android.content.Intent;
import android.net.Uri;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Process-owned executor plus durable journal. URI copies are owned before repository import/OCR. */
final class BatchController {
    private final StackApplication app;private final ExecutorService transfer=Executors.newSingleThreadExecutor();
    private final Set<String> active=ConcurrentHashMap.newKeySet(),canceled=ConcurrentHashMap.newKeySet();
    final Map<String,List<Uri>> outputs=new ConcurrentHashMap<>();
    volatile String progress="";
    private volatile java.io.Closeable currentInput;
    private volatile String copying="";
    BatchController(StackApplication app){this.app=app;}
    void recover(){app.library.batches.recover();for(String id:app.library.batches.pending())run(id);}
    void importUris(List<Uri> uris,java.util.function.Consumer<String> started){
        if(uris.isEmpty()||uris.size()>BatchStore.LIMIT){started.accept("");return;}
        List<String> sources=new ArrayList<>();
        for(Uri uri:uris){sources.add(uri.toString());try{app.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}}
        app.io.execute(() -> {String id=app.library.batches.create("import",sources,null);run(id);new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> started.accept(id));});
    }
    void cancel(String id){canceled.add(id);java.io.Closeable stream=currentInput;if(id.equals(copying)&&stream!=null)new Thread(() -> {try{stream.close();}catch(IOException ignored){}},"Stack batch cancel").start();app.io.execute(() -> {app.library.batches.state(id,"canceled");app.notifyLibraryChanged();});}
    void retry(String id){canceled.remove(id);app.io.execute(() -> {app.library.batches.retry(id);run(id);});}
    void run(String id){if(!active.add(id))return;transfer.execute(() -> {try{execute(id);}catch(Exception ignored){app.io.execute(() -> {app.library.batches.state(id,"failed");app.notifyLibraryChanged();});}finally{active.remove(id);}});}
    private void execute(String id)throws Exception {
        BatchStore.Operation op=app.io.submit(() -> app.library.batches.operation(id)).get();if(op.state.equals("canceled")||!app.access.allowed())return;
        app.io.submit(() -> app.library.batches.state(id,"running")).get();
        File root=new File(app.getFilesDir(),"batch_sources/"+id);if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Free local storage and retry.");
        List<Uri> completed=new ArrayList<>();Set<String> names=new HashSet<>();List<File> ownedOutputs=new ArrayList<>();boolean share=op.kind.equals("pdf")||op.kind.equals("zip");
        // Export retries regenerate every complete output; no partial or expired result is delivered.
        List<BatchStore.Item> items=op.items;
        for(BatchStore.Item item:items) {
            if(!share&&item.state.equals("done"))continue;
            if(canceled.contains(id)||!app.access.allowed())break;
            progress=(item.position+1)+" of "+items.size();app.io.submit(() -> {app.library.batches.item(item,"running","");app.notifyLibraryChanged();}).get();
            try {
                if(op.kind.equals("import")) {
                    File source=new File(root,item.position+".source");
                    if(!source.isFile()) {
                        File part=new File(root,item.position+".part");
                        try(InputStream input=app.getContentResolver().openInputStream(Uri.parse(item.source));FileOutputStream output=new FileOutputStream(part)){
                            copying=id;currentInput=input;
                            if(input==null)throw new IOException("Select this source again; access is unavailable.");copy(input,output,id,256L*1024*1024);output.getFD().sync();
                        }catch(Exception failure){part.delete();throw failure;}finally{currentInput=null;copying="";}
                        if(!part.renameTo(source))throw new IOException("Free storage and retry this item.");
                    }
                    app.io.submit(() -> atomic(item,() -> {check(id);if(!app.library.batches.exists(item.document))app.library.importDocument(Uri.fromFile(source),item.document,sourceName(Uri.parse(item.source)));return true;})).get();
                    app.photos.notifyImport(item.document);
                    source.delete();
                } else if(op.kind.equals("delete"))app.io.submit(() -> {check(id);app.library.deleteDocument(item.document);return true;}).get();
                else if(op.kind.startsWith("file-"))app.io.submit(() -> atomic(item,() -> {
                    check(id);org.json.JSONObject selected=new org.json.JSONObject(item.source);long expected=selected.getLong("revision");
                    try(android.database.Cursor row=app.library.database().rawQuery("SELECT filing_decision FROM documents WHERE id=?",new String[]{item.document})){if(!row.moveToFirst()||row.getLong(0)!=expected)throw new IOException("Filing changed. Review this document again.");}
                    LinkedHashSet<String> ids=new LinkedHashSet<>();if(op.kind.equals("file-add"))ids.addAll(app.library.document(item.document).stackIds);
                    org.json.JSONArray values=selected.getJSONArray("stacks");for(int i=0;i<values.length();i++)ids.add(values.getString(i));app.library.setMemberships(item.document,new ArrayList<>(ids));return true;
                })).get();
                else if(share) {
                    PdfCopies.Plan plan=app.io.submit(() -> {check(id);return PdfCopies.prepare(app.library,new File(app.getCacheDir(),"pdf_plans"),item.document);}).get();
                    String token=UUID.randomUUID().toString(),name=PdfCopies.name(plan.document.title,item.position+1,names);File directory=new File(app.getCacheDir(),"exports");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Free storage and retry sharing.");
                    File target=new File(directory,token+".pdf");ownedOutputs.add(target);
                    try(plan;FileOutputStream output=new FileOutputStream(target)){plan.write(new CheckedOutput(output,id));output.getFD().sync();}
                    completed.add(PdfCopies.uri(app,token,name));
                }
                app.io.submit(() -> {app.library.batches.item(item,"done","");app.notifyLibraryChanged();}).get();
            } catch(Exception failure){Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();String message=cause instanceof SecurityException?"Source permission unavailable. Select this file again.":cause.getMessage()==null?"Couldn't finish this item. Free storage or select its source again.":cause.getMessage();app.io.submit(() -> app.library.batches.item(item,"failed","Item "+(item.position+1)+": "+message)).get();}
        }
        boolean all=app.io.submit(() -> {for(BatchStore.Item item:app.library.batches.items(id))if(!item.state.equals("done"))return false;return true;}).get();
        try {
            if(share&&all&&!canceled.contains(id)) {
                if(op.kind.equals("zip")) {
                    File directory=new File(app.getCacheDir(),"exports");String token=UUID.randomUUID().toString();File zip=new File(directory,token+".zip");ownedOutputs.add(zip);
                    try(FileOutputStream file=new FileOutputStream(zip);ZipOutputStream output=new ZipOutputStream(new CheckedOutput(file,id))) {
                        for(int i=0;i<completed.size();i++){check(id);output.putNextEntry(new ZipEntry(completed.get(i).getLastPathSegment()));try(InputStream input=new FileInputStream(ownedOutputs.get(i))){copy(input,output,id,2L*1024*1024*1024);}output.closeEntry();}
                        output.finish();output.flush();file.getFD().sync();
                    }
                    for(int i=0;i<completed.size();i++)ownedOutputs.get(i).delete();completed=Collections.singletonList(PdfCopies.uri(app,token,"Stack PDFs.zip"));
                }
                outputs.put(id,completed);
            }else if(share)for(File file:ownedOutputs)file.delete();
        }catch(Exception failure){for(File file:ownedOutputs)file.delete();app.io.submit(() -> app.library.batches.state(id,"failed")).get();throw failure;}
        String state=canceled.contains(id)?"canceled":all?"done":"failed";
        app.io.submit(() -> {app.library.batches.state(id,state);app.notifyLibraryChanged();}).get();
        if(op.kind.equals("import"))for(BatchStore.Item item:items) {
            boolean stillNeeded=app.io.submit(() -> {try(android.database.Cursor row=app.library.database().rawQuery("SELECT 1 FROM operation_items i JOIN operations o ON o.id=i.operation_id WHERE i.source=? AND o.id<>? AND o.state IN ('pending','running') AND i.state<>'done'",new String[]{item.source,id})){return row.moveToFirst();}}).get();
            if(!stillNeeded)try{app.getContentResolver().releasePersistableUriPermission(Uri.parse(item.source),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}
        }
        if(!share&&all)LibraryArchive.remove(root);
    }
    private boolean atomic(BatchStore.Item item,Callable<Boolean> operation)throws Exception {
        android.database.sqlite.SQLiteDatabase db=app.library.database();db.beginTransaction();boolean committed=false;
        try{boolean result=operation.call();app.library.batches.item(item,"done","");db.setTransactionSuccessful();committed=true;return result;}
        finally{db.endTransaction();if(committed)app.library.archiveChanged();}
    }
    private String sourceName(Uri uri){try(android.database.Cursor row=app.getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(row!=null&&row.moveToFirst())return row.getString(0);}catch(RuntimeException ignored){}return "Imported document";}
    private void check(String id)throws IOException{if(canceled.contains(id)||!app.access.allowed())throw new IOException("Operation paused or canceled. Saved items are kept.");}
    private void copy(InputStream in,OutputStream out,String id,long limit)throws IOException{byte[] buffer=new byte[65536];long total=0;int n;while((n=in.read(buffer))!=-1){if(canceled.contains(id)||!app.access.allowed())throw new IOException("Operation paused or canceled.");if(n==0)throw new IOException("Source stopped delivering data.");total+=n;if(total>limit)throw new IOException("This item exceeds the size limit.");out.write(buffer,0,n);}}
    private final class CheckedOutput extends FilterOutputStream{final String id;long count;CheckedOutput(OutputStream out,String id){super(out);this.id=id;}@Override public void write(int b)throws IOException{write(new byte[]{(byte)b},0,1);}@Override public void write(byte[] b,int off,int n)throws IOException{if(canceled.contains(id)||!app.access.allowed())throw new IOException("Sharing canceled or locked.");count+=n;if(count>2L*1024*1024*1024)throw new IOException("Export exceeds the 2 GiB limit.");out.write(b,off,n);}}
}
