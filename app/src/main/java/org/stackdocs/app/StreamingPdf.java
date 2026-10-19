package org.stackdocs.app;

import android.graphics.Bitmap;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.zip.DeflaterOutputStream;

/** Independent image-only PDF writer. One bitmap + one scanline; no retained native PDF pages. */
final class StreamingPdf {
    private static final class Counted extends OutputStream {
        final OutputStream out; long count;
        Counted(OutputStream out){this.out=out;}
        @Override public void write(int value)throws IOException{out.write(value);count++;}
        @Override public void write(byte[] bytes,int start,int length)throws IOException{out.write(bytes,start,length);count+=length;}
        void text(String value)throws IOException{write(value.getBytes(StandardCharsets.ISO_8859_1));}
    }
    static void write(List<PageState> pages,PageRenderer.BitmapSource source,OutputStream destination)throws IOException {
        Counted out=new Counted(destination);int objects=2+pages.size()*4;long[] offsets=new long[objects+1];
        out.text("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n");object(out,offsets,1);out.text("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        object(out,offsets,2);out.text("<< /Type /Pages /Count "+pages.size()+" /Kids [");for(int i=0;i<pages.size();i++)out.text((3+i*4)+" 0 R ");out.text("] >>\nendobj\n");
        for(int i=0;i<pages.size();i++) {
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("PDF export interrupted. Choose a destination again to retry.");
            Bitmap bitmap=source.load(pages.get(i));
            try {
                int width=bitmap.getWidth(),height=bitmap.getHeight(),page=3+i*4,image=page+1,content=page+2,length=page+3;
                int paperWidth=Math.max(1,Math.round(width*.5f)),paperHeight=Math.max(1,Math.round(height*.5f));
                object(out,offsets,page);out.text("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "+paperWidth+" "+paperHeight+"] /Resources << /XObject << /Im0 "+image+" 0 R >> >> /Contents "+content+" 0 R >>\nendobj\n");
                object(out,offsets,image);out.text("<< /Type /XObject /Subtype /Image /Width "+width+" /Height "+height+" /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length "+length+" 0 R >>\nstream\n");
                long start=out.count;DeflaterOutputStream compressed=new DeflaterOutputStream(out);int[] pixels=new int[width];byte[] row=new byte[width*3];
                try {
                    for(int y=0;y<height;y++) {
                        bitmap.getPixels(pixels,0,width,0,y,width,1);
                        for(int x=0;x<width;x++){int pixel=pixels[x],alpha=pixel>>>24;for(int c=0;c<3;c++){int channel=(pixel>>>(16-c*8))&255;row[x*3+c]=(byte)((channel*alpha+255*(255-alpha)+127)/255);}}
                        compressed.write(row);
                    }
                    compressed.finish();
                } finally { // close releases the Deflater without closing the caller's destination.
                    compressed.close();
                }
                long bytes=out.count-start;out.text("\nendstream\nendobj\n");object(out,offsets,length);out.text(bytes+"\nendobj\n");
                String commands="q\n"+paperWidth+" 0 0 "+paperHeight+" 0 0 cm\n/Im0 Do\nQ\n";object(out,offsets,content);out.text("<< /Length "+commands.length()+" >>\nstream\n"+commands+"endstream\nendobj\n");
            } finally {bitmap.recycle();}
        }
        long xref=out.count;out.text("xref\n0 "+offsets.length+"\n0000000000 65535 f \n");for(int i=1;i<offsets.length;i++)out.text(String.format(Locale.ROOT,"%010d 00000 n \n",offsets[i]));out.text("trailer\n<< /Size "+offsets.length+" /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n");
    }
    private static void object(Counted out,long[] offsets,int object)throws IOException{offsets[object]=out.count;out.text(object+" 0 obj\n");}
}
