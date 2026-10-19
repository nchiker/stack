package org.stackdocs.app;

import android.net.Uri;
import android.system.Os;
import java.io.*;
import java.util.*;

/** Pinned immutable originals and page settings allow heavy exports off repository IO. */
final class PdfCopies {
    static final class Plan implements AutoCloseable {
        final File directory;final Document document;final List<PageState> pages;final boolean untouched;
        Plan(File dir,Document doc,List<PageState> pages,boolean untouched){directory=dir;document=doc;this.pages=pages;this.untouched=untouched;}
        void write(OutputStream output)throws IOException {
            if(untouched){try(InputStream input=new FileInputStream(new File(directory,"source"))){LibraryRepository.copy(input,output,Long.MAX_VALUE);}}
            else PageRenderer.exportPages(pages,page -> PageRenderer.render(new File(directory,page.sourceFile),document.mime,page,3200),output);
        }
        @Override public void close(){LibraryArchive.remove(directory);}
    }
    static Plan prepare(LibraryRepository library,File root,String id)throws IOException {
        Document doc=library.document(id);List<PageState> pages=library.pages(id);File directory=new File(root,UUID.randomUUID().toString());if(!directory.mkdirs())throw new IOException("Free local storage and retry.");boolean ready=false;
        try{
            Set<String> sources=new HashSet<>();boolean untouched=doc.mime.equals("application/pdf");int initial;
            try(android.database.Cursor row=library.database().rawQuery("SELECT initial_page_count FROM documents WHERE id=?",new String[]{id})){row.moveToFirst();initial=row.getInt(0);}
            untouched&=pages.size()==initial;
            for(int i=0;i<pages.size();i++){PageState page=pages.get(i);untouched&=page.unchanged()&&page.sourcePage==i&&page.sourceFile.equals("source");
                if(sources.add(page.sourceFile)){File from=library.originalFile(doc,page),to=new File(directory,page.sourceFile);try{Os.link(from.getPath(),to.getPath());}catch(android.system.ErrnoException unsupported){try(InputStream in=new FileInputStream(from);FileOutputStream out=new FileOutputStream(to)){LibraryRepository.copy(in,out,256L*1024*1024);out.getFD().sync();}}}}
            ready=true;return new Plan(directory,doc,pages,untouched);
        }finally{if(!ready)LibraryArchive.remove(directory);}
    }
    static String name(String title,int order,Set<String> used){String base=title.replaceAll("[^\\p{L}\\p{N} ._-]","_").replaceAll("^[ .]+|[ .]+$","");if(base.isEmpty())base="Document";base=base.substring(0,Math.min(100,base.length()));String value=String.format(Locale.ROOT,"%02d - %s.pdf",order,base);while(!used.add(value.toLowerCase(Locale.ROOT)))value="copy-"+value;return value;}
    static Uri uri(android.content.Context context,String token,String name){return new Uri.Builder().scheme("content").authority(context.getPackageName()+".exports").appendPath(token).appendPath(name).build();}
}
