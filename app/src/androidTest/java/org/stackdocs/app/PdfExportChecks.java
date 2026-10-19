package org.stackdocs.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

final class PdfExportChecks {
    static void run(Context context,Consumer<String> mark)throws Exception {
        List<PageState> pages=new ArrayList<>();for(int i=0;i<24;i++)pages.add(new PageState(UUID.randomUUID().toString(),"source",i,0,null,"original"));
        Bitmap[] previous=new Bitmap[1];File file=new File(context.getCacheDir(),"streamed.pdf");int[] colors={Color.RED,Color.GREEN,Color.BLUE};
        long start=android.os.SystemClock.elapsedRealtime();
        try(FileOutputStream out=new FileOutputStream(file)) {
            PageRenderer.exportPages(pages,page->{if(previous[0]!=null)require(previous[0].isRecycled(),"Previous bitmap released before next page");Bitmap image=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888);image.eraseColor(colors[page.sourcePage%3]);previous[0]=image;return image;},out);
            out.write('\n');out.flush(); // Writer does not close the caller's stream.
        }
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer pdf=new PdfRenderer(fd)) {
            require(pdf.getPageCount()==24,"Streaming PDF page count");
            for(int i=0;i<24;i++)try(PdfRenderer.Page page=pdf.openPage(i)) {
                Bitmap image=Bitmap.createBitmap(300,400,Bitmap.Config.ARGB_8888);try{page.render(image,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);require(image.getPixel(150,200)==colors[i%3],"Lossless RGB/order "+i);}finally{image.recycle();}
            }
        }
        boolean failed=false;try{PageRenderer.exportPages(pages,page->{Bitmap image=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888);previous[0]=image;return image;},new OutputStream(){int count;public void write(int b)throws IOException{if(++count>400)throw new IOException("synthetic write failure");}});}catch(IOException expected){failed=true;}
        require(failed&&previous[0].isRecycled(),"Failed streaming export releases current bitmap");
        mark.accept("24-page lossless streaming PDF renders in order; one bitmap at a time; output remains caller-owned and failure recycles current bitmap; bytes="+file.length()+" elapsed_ms="+(android.os.SystemClock.elapsedRealtime()-start));
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
