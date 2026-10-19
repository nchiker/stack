package org.stackdocs.app;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Host driver; production validation and writing are extracted unchanged by the builder. */
public final class ArchiveHost {
    public static void main(String[] args) throws Exception {
        if(args[0].equals("write")) {
            File root=new File(args[1]);int version=Integer.parseInt(args[2]);
            JSONObject graph=ArchiveJson.read(Files.readString(root.toPath().resolve("manifest-v3.json")));
            if(version<3) for(int i=0;i<graph.getJSONArray("stacks").length();i++)graph.getJSONArray("stacks").getJSONObject(i).remove("sort_order");
            if(version==1) {
                for(int i=0;i<graph.getJSONArray("documents").length();i++) {JSONObject doc=graph.getJSONArray("documents").getJSONObject(i);for(String k:new String[]{"notes","notes_revision","metadata_revision"})doc.remove(k);}
                for(String k:new String[]{"details","tags","document_tags"})graph.remove(k);
            }
            graph.put("version",version).put("schema",version+5);
            try(OutputStream out=Files.newOutputStream(Path.of(args[3]),StandardOpenOption.CREATE_NEW)) {
                LibraryArchive.write(new LibraryArchive.Snapshot(root,graph),out,new LibraryArchive.Progress());
            }
            System.out.println("Java production writer: format "+version);
        } else if(args[0].equals("read")) {
            Path target=Path.of(args[2]);Files.createDirectory(target);
            JSONObject graph;
            try(DataInputStream input=new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(args[1]))))) {
                byte[] magic=new byte[8];input.readFully(magic);if(!Arrays.equals(magic,LibraryArchive.MAGIC))throw new IOException("magic");
                int length=input.readInt();if(length<2||length>LibraryArchive.MANIFEST_LIMIT)throw new IOException("length");
                byte[] hash=new byte[32],bytes=new byte[length];input.readFully(hash);input.readFully(bytes);
                if(!MessageDigest.isEqual(hash,MessageDigest.getInstance("SHA-256").digest(bytes)))throw new IOException("manifest hash");
                graph=ArchiveJson.read(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString());
                LibraryArchive.validate(graph);LibraryArchive.upgradeOlder(graph);
                JSONArray files=graph.getJSONArray("files");
                for(int i=0;i<files.length();i++) {
                    JSONObject file=files.getJSONObject(i);int size=input.readUnsignedShort();if(size<1||size>160)throw new IOException("path length");
                    byte[] name=new byte[size];input.readFully(name);String path=new String(name,StandardCharsets.UTF_8);
                    long count=input.readLong();if(!path.equals(file.getString("path"))||count!=file.getLong("length"))throw new IOException("entry mismatch");
                    Path output=target.resolve(path);Files.createDirectories(output.getParent());MessageDigest sha=MessageDigest.getInstance("SHA-256");
                    try(OutputStream out=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer=new byte[65536];while(count>0) {int n=input.read(buffer,0,(int)Math.min(count,buffer.length));if(n<=0)throw new EOFException();out.write(buffer,0,n);sha.update(buffer,0,n);count-=n;}
                    }
                    StringBuilder digest=new StringBuilder();for(byte b:sha.digest())digest.append(String.format(Locale.ROOT,"%02x",b&255));
                    if(!digest.toString().equals(file.getString("sha256")))throw new IOException("original hash");
                }
                if(input.read()!=-1)throw new IOException("trailing bytes");
            }
            Files.writeString(target.resolve("manifest-v3.json"),graph.toString());
            JSONObject evidence=new JSONObject();
            for(int i=0;i<graph.getJSONArray("documents").length();i++) {
                String id=graph.getJSONArray("documents").getJSONObject(i).getString("id");
                SuggestionStore.Digest digest=new SuggestionStore.Digest();digest.add(id);
                ArrayList<JSONObject> pages=new ArrayList<>();
                for(int j=0;j<graph.getJSONArray("pages").length();j++) {JSONObject page=graph.getJSONArray("pages").getJSONObject(j);if(page.getString("document_id").equals(id))pages.add(page);}
                pages.sort(Comparator.comparingInt(page->page.optInt("position")));
                for(JSONObject page:pages)for(String key:new String[]{"id","position","content_revision","text_revision","ocr_config","text_config","ocr_text"})digest.add(page.getString(key));
                evidence.put(id,digest.finish());
            }
            Files.writeString(target.resolve("evidence.json"),evidence.toString());
            try(OutputStream out=Files.newOutputStream(target.resolve("java-rewritten.stackbackup"),StandardOpenOption.CREATE_NEW)) {
                LibraryArchive.write(new LibraryArchive.Snapshot(target.toFile(),graph),out,new LibraryArchive.Progress());
            }
            System.out.println("Java validator/writer accepted "+graph.getJSONArray("documents").length()+" documents; native source decoding excluded.");
        } else if(args[0].equals("currencies")) {
            TreeSet<String> currencies=new TreeSet<>();for(Currency currency:Currency.getAvailableCurrencies())currencies.add(currency.getCurrencyCode());
            System.out.println(String.join(" ",currencies));
        } else throw new IllegalArgumentException("write FIXTURE_ROOT VERSION NEW_ARCHIVE | read ARCHIVE NEW_DIRECTORY | currencies");
    }
}
