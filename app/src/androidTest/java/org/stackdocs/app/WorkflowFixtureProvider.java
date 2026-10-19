package org.stackdocs.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Installed only with the test APK; fixed authored fixtures, no caller-selected file paths. */
public final class WorkflowFixtureProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){return "application/pdf";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
        if(uri.getLastPathSegment().equals("unnamed")) throw new UnsupportedOperationException("Synthetic optional name unavailable");
        MatrixCursor row=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        row.addRow(new Object[]{"Synthetic/provider\nrecord.pdf"});return row;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!mode.equals("r"))throw new FileNotFoundException("Read-only synthetic fixture");
        String kind=uri.getLastPathSegment();
        if("denied".equals(kind))throw new SecurityException("Synthetic permission revoked");
        if(!"valid".equals(kind) && !"unnamed".equals(kind) && !"partial".equals(kind) && !"unsupported".equals(kind))throw new FileNotFoundException("Unknown synthetic fixture");
        try {
            if(kind.equals("partial")) {
                ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createReliablePipe();
                new Thread(() -> {
                    try {new FileOutputStream(pipe[1].getFileDescriptor()).write(new byte[]{'%', 'P', 'D', 'F', '-'});pipe[1].closeWithError("Synthetic provider stream interrupted");}
                    catch(IOException ignored){try{pipe[1].close();}catch(IOException ignoredAgain){}}
                }).start();return pipe[0];
            }
            File file=new File(getContext().getCacheDir(),"fixed-"+kind);
            if(!file.isFile()) {
                if(kind.equals("unsupported")) {try(FileOutputStream out=new FileOutputStream(file)){out.write("Authored unsupported format".getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
                else {
                    PdfDocument pdf=new PdfDocument();
                    try {
                        Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);ink.setColor(Color.BLACK);ink.setTextSize(20);
                        for(int number=1;number<=2;number++){
                            PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(600,800,number).create());page.getCanvas().drawColor(Color.WHITE);
                            page.getCanvas().drawText(number==1 ? "Synthetic provider invoice" : "Later page quartz lantern",40,100,ink);pdf.finishPage(page);
                        }
                        try(FileOutputStream out=new FileOutputStream(file)){pdf.writeTo(out);}
                    }finally{pdf.close();}
                }
            }
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
        }catch(IOException failure){throw new FileNotFoundException("Synthetic provider unavailable");}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException();}
}
