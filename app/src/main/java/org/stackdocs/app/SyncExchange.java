package org.stackdocs.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** STACKEX1 byte envelope. Its caller supplies a user-selected local file. */
final class SyncExchange {
    interface Control {void check()throws IOException;}
    private static final byte[] MAGIC={'S','T','A','C','K','E','X',1};
    private final Context context;private final SyncStore store;private final Control control;
    SyncExchange(Context context,LibraryRepository repository){this(context,repository,()->{});}
    SyncExchange(Context context,LibraryRepository repository,Control control){this.context=context.getApplicationContext();store=new SyncStore(context,repository,control);this.control=control;}
    void probe(LibraryArchive.Probe probe){store.probe=probe;}
    static final class Staged implements AutoCloseable {
        final File directory;final JSONObject header;final List<SyncProtocol.Operation> operations;
        Staged(File directory,JSONObject header,List<SyncProtocol.Operation> operations){this.directory=directory;this.header=header;this.operations=operations;}
        public void close(){remove(directory);}
    }
    private static final class Ref {
        final String path,sha;final long length;
        Ref(String path,String sha,long length){this.path=path;this.sha=sha;this.length=length;}
    }
    void export(String peer,OutputStream destination)throws IOException,JSONException {
        control.check();
        store.capture();SyncProtocol.State state=store.load();if(state==null)throw SyncProtocol.invalid("start sync before exporting");if(!state.members.contains(peer)||peer.equals(store.replica()))throw SyncProtocol.invalid("choose a reviewed peer");
        TreeMap<String,Long> ack=store.receipt(peer);JSONArray operations=new JSONArray();List<SyncProtocol.Operation> outgoing=new ArrayList<>();
        for(SyncProtocol.Operation op:state.history.values())if(op.sequence>ack.getOrDefault(op.replica,0L)){operations.put(op.json);outgoing.add(op);}
        if(operations.length()>SyncProtocol.MAX_OPERATIONS)throw SyncProtocol.invalid("exchange operation limit; import a peer receipt first");
        Map<String,Ref> refs=refs(outgoing);TreeMap<String,Ref> blobs=new TreeMap<>();for(Ref ref:refs.values())blobs.putIfAbsent(ref.sha,ref);
        if(blobs.size()>10000)throw SyncProtocol.invalid("exchange blob limit");
        JSONArray descriptors=new JSONArray();for(Ref ref:blobs.values())descriptors.put(new JSONObject().put("sha256",ref.sha).put("length",ref.length));
        JSONObject header=new JSONObject().put("format","org.stackdocs.exchange").put("version",1).put("library",state.library).put("sender",store.replica()).put("acknowledged",SyncProtocol.json(state.frontier)).put("operations",operations).put("blobs",descriptors);
        byte[] metadata=SyncProtocol.canonical(header).getBytes(StandardCharsets.UTF_8);if(metadata.length>SyncProtocol.MAX_JSON)throw SyncProtocol.invalid("exchange metadata limit");
        File stage=new File(context.getCacheDir(),"exchange-"+UUID.randomUUID());if(!stage.mkdir())throw new IOException("Could not prepare exchange file");
        try {
            File complete=new File(stage,"complete");try(FileOutputStream stream=new FileOutputStream(complete)){
                DataOutputStream output=new DataOutputStream(stream);output.write(MAGIC);output.writeInt(metadata.length);output.write(metadata);
                for(Ref ref:blobs.values()) {File original=file(ref.path),cached=new File(context.getFilesDir(),"sync_blobs/"+ref.sha);File source=original.isFile()?original:cached;
                    if(!SyncStore.hash(source,ref.length,control).equals(ref.sha))throw SyncProtocol.invalid("retained original changed");
                    try(FileInputStream input=new FileInputStream(source)){copy(input,output,ref.length);}
                }
                output.flush();stream.getFD().sync();
            }
            try(FileInputStream source=new FileInputStream(complete)){copy(source,destination,complete.length());}destination.flush();
        }finally{remove(stage);}
    }
    Staged stage(InputStream source)throws IOException,JSONException {
        control.check();
        File directory=new File(context.getFilesDir(),"sync_stage/"+UUID.randomUUID());if(!directory.mkdirs())throw new IOException("Could not prepare sync staging");boolean complete=false;
        try {
            DataInputStream input=new DataInputStream(new BufferedInputStream(source,65536));byte[] magic=new byte[8];input.readFully(magic);if(!java.util.Arrays.equals(magic,MAGIC))throw SyncProtocol.invalid("not a STACKEX1 file");
            int length=input.readInt();if(length<2||length>SyncProtocol.MAX_JSON)throw SyncProtocol.invalid("exchange metadata limit");byte[] bytes=new byte[length];input.readFully(bytes);
            String json=utf8(bytes);JSONObject header=ArchiveJson.read(json);SyncProtocol.exact(header,"format,version,library,sender,acknowledged,operations,blobs");
            if(!"org.stackdocs.exchange".equals(header.getString("format"))||SyncProtocol.integer(header.get("version"))!=1)throw SyncProtocol.invalid("unsupported exchange version");
            SyncProtocol.uuid(header.getString("library"));SyncProtocol.uuid(header.getString("sender"));SyncProtocol.frontier(header.getJSONObject("acknowledged"));
            JSONArray operations=header.getJSONArray("operations"),blobs=header.getJSONArray("blobs");if(operations.length()>SyncProtocol.MAX_OPERATIONS||blobs.length()>10000)throw SyncProtocol.invalid("exchange count limit");
            List<SyncProtocol.Operation> parsed=new ArrayList<>();for(int i=0;i<operations.length();i++){SyncProtocol.Operation op=new SyncProtocol.Operation(operations.getJSONObject(i));if(!op.library.equals(header.getString("library")))throw SyncProtocol.invalid("foreign operation");SyncGraph.validateChanges(op.changes);parsed.add(op);}
            Set<String> unique=new HashSet<>();long total=0;
            for(int i=0;i<blobs.length();i++) {
                JSONObject blob=blobs.getJSONObject(i);SyncProtocol.exact(blob,"sha256,length");String sha=blob.getString("sha256");if(!sha.matches("[0-9a-f]{64}")||!unique.add(sha))throw SyncProtocol.invalid("invalid blob digest");
                long size=SyncProtocol.integer(blob.get("length"));if(size>LibraryArchive.FILE_BYTES||total>LibraryArchive.TOTAL_BYTES-size)throw SyncProtocol.invalid("exchange byte limit");total+=size;
                File output=new File(directory,sha);try(FileOutputStream file=new FileOutputStream(output)){MessageDigest digest=digest();transfer(input,file,size,digest);file.getFD().sync();if(!sha.equals(SyncGraph.hex(digest.digest())))throw SyncProtocol.invalid("blob digest mismatch");}
            }
            if(input.read()!=-1)throw SyncProtocol.invalid("trailing exchange bytes");syncDir(directory);complete=true;return new Staged(directory,header,parsed);
        }finally{if(!complete)remove(directory);}
    }
    JSONObject reviewAdoption(Staged staged)throws IOException,JSONException {
        if(store.load()!=null)throw SyncProtocol.invalid("library already belongs to a group");
        return SyncAdoption.review(staged.header,staged.operations,store.manifest(),store.replica());
    }
    JSONObject mergeConnected(Staged staged)throws Exception {
        return importReviewed(staged,SyncAdoption.merge(reviewAdoption(staged),store.manifest()));
    }
    JSONObject importReviewed(Staged staged,JSONObject review)throws IOException,JSONException {
        JSONObject current=reviewAdoption(staged);SyncAdoption.revalidate(current,review);List<String> members=new ArrayList<>();JSONArray list=review.getJSONArray("members");for(int i=0;i<list.length();i++)members.add(list.getString(i));
        return importFile(staged,review.getString("library"),members,review);
    }
    JSONObject importFile(Staged staged,String reviewedLibrary,Collection<String> reviewedMembers)throws IOException,JSONException {
        return importFile(staged,reviewedLibrary,reviewedMembers,null);
    }
    private JSONObject importFile(Staged staged,String reviewedLibrary,Collection<String> reviewedMembers,JSONObject review)throws IOException,JSONException {
        control.check();
        JSONObject header=staged.header;String library=header.getString("library"),sender=header.getString("sender"),local=store.replica();
        if(store.load()!=null)store.capture();
        SyncProtocol.State state=store.load();boolean adopting=state==null;JSONObject baseline=null;
        if(adopting) {
            if(reviewedLibrary==null||!reviewedLibrary.equals(library)||reviewedMembers==null)throw SyncProtocol.invalid("review group and complete member list before adoption");
            if(review==null)try(android.database.Cursor row=store.db.rawQuery("SELECT 1 FROM documents LIMIT 1",null)){if(row.moveToFirst())throw SyncProtocol.invalid("existing documents need reviewed identity mapping");}
            state=new SyncProtocol.State(library,reviewedMembers);if(!state.members.contains(local)||!state.members.contains(sender))throw SyncProtocol.invalid("reviewed members omit a peer");
        }
        if(!state.library.equals(library)||!state.members.contains(sender))throw SyncProtocol.invalid("unreviewed sender or group");
        for(SyncProtocol.Operation op:staged.operations)if(op.replica.equals(local)&&!state.history.containsKey(op.key)&&!state.pending.containsKey(op.key))throw SyncProtocol.invalid("incoming peer cannot create a local-origin operation");
        String before=state.saved().toString();int applied=state.receive(staged.operations);boolean changed=!before.equals(state.saved().toString());
        if(adopting) {
            if(!state.pending.isEmpty()||state.history.isEmpty())throw SyncProtocol.invalid("complete baseline operations required");
            for(SyncProtocol.Operation op:state.history.values())if(op.sequence==1&&"baseline.explicit".equals(op.json.getJSONObject("evidence").optString("kind"))&&op.json.getJSONObject("evidence").optInt("graph_model")==SyncGraph.MODEL){baseline=op.changes;break;}
            if(baseline==null)throw SyncProtocol.invalid("model 2 explicit baseline required");SyncGraph.project(baseline);
            if(review!=null)SyncAdoption.addLocal(state,store.manifest(),review,local);
        }
        List<SyncProtocol.Operation> accepted=new ArrayList<>(state.history.values());accepted.addAll(state.pending.values());Map<String,Ref> declared=refs(accepted);
        File cache=new File(context.getFilesDir(),"sync_blobs");if(!cache.isDirectory()&&!cache.mkdirs())throw new IOException("Could not retain incoming originals");
        if(adopting&&review!=null){JSONArray files=store.manifest().getJSONArray("files");for(int i=0;i<files.length();i++){
            JSONObject entry=files.getJSONObject(i);String sha=entry.getString("sha256");long length=entry.getLong("length");File original=file(entry.getString("path")),target=new File(cache,sha);
            if(!SyncStore.hash(original,length,control).equals(sha))throw SyncProtocol.invalid("local original changed after adoption review");
            if(!target.exists())copyFile(original,target,length);if(!SyncStore.hash(target,length,control).equals(sha))throw SyncProtocol.invalid("retained local original changed");
        }}
        JSONArray received=header.getJSONArray("blobs");for(int i=0;i<received.length();i++){
            JSONObject blob=received.getJSONObject(i);String sha=blob.getString("sha256");long size=blob.getLong("length");File target=new File(cache,sha);
            boolean used=false;for(Ref ref:declared.values())if(ref.sha.equals(sha)&&ref.length==size){used=true;break;}if(!used)throw SyncProtocol.invalid("exchange contains an unreferenced original blob");
            if(target.exists()){if(!SyncStore.hash(target,size,control).equals(sha))throw SyncProtocol.invalid("retained blob changed");}
            else {copyFile(new File(staged.directory,sha),target,size);if(!SyncStore.hash(target,size,control).equals(sha))throw SyncProtocol.invalid("retained blob changed");}
        }
        syncDir(cache);
        for(Ref ref:refs(state).values()){
            File output=file(ref.path);if(output.exists()){if(!SyncStore.hash(output,ref.length,control).equals(ref.sha))throw SyncProtocol.invalid("original path collision");continue;}
            File source=new File(cache,ref.sha);if(!source.isFile())throw SyncProtocol.invalid("required original missing");File parent=output.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Could not install original");
            copyFile(source,output,ref.length);if(!SyncStore.hash(output,ref.length,control).equals(ref.sha))throw SyncProtocol.invalid("installed original changed");syncDir(parent);
        }
        store.probe.reached("originals_installed");
        TreeMap<String,Long> receipt=new TreeMap<>();TreeMap<String,Long> claimed=SyncProtocol.frontier(header.getJSONObject("acknowledged"));for(Map.Entry<String,Long> entry:claimed.entrySet()){
            String origin=entry.getKey();long available=state.frontier.getOrDefault(origin,0L);if(!state.members.contains(origin)||(origin.equals(local)&&entry.getValue()>available))throw SyncProtocol.invalid("impossible acknowledgment");receipt.put(origin,Math.min(entry.getValue(),available));
        }
        control.check();if(adopting||applied>0)store.install(state,baseline);else if(changed)store.savePending(state);
        control.check();
        for(Map.Entry<String,Long> prior:store.receipt(sender).entrySet())receipt.merge(prior.getKey(),prior.getValue(),Math::max);
        store.receipt(sender,receipt);
        store.probe.reached("receipt");
        return new JSONObject().put("applied",applied).put("pending_gaps",state.pending.size()).put("durable_frontier",SyncProtocol.json(state.frontier)).put("conflicts",state.conflicts().length()).put("adopted",adopting);
    }
    private Map<String,Ref> refs(SyncProtocol.State state)throws IOException,JSONException {return refs(state.history.values());}
    private Map<String,Ref> refs(Collection<SyncProtocol.Operation> operations)throws IOException,JSONException {
        TreeMap<String,Ref> result=new TreeMap<>();for(SyncProtocol.Operation op:operations)for(String key:SyncProtocol.keys(op.changes))if(key.startsWith("files/")&&!op.changes.isNull(key)) {
            JSONObject value=op.changes.getJSONObject(key);String path=value.getString("path"),sha=value.getString("sha256");long length=SyncProtocol.integer(value.get("length"));file(path);if(!sha.matches("[0-9a-f]{64}")||length>LibraryArchive.FILE_BYTES)throw SyncProtocol.invalid("invalid file reference");
            Ref old=result.putIfAbsent(path,new Ref(path,sha,length));if(old!=null&&(!old.sha.equals(sha)||old.length!=length))throw SyncProtocol.invalid("immutable original changed");
        }return result;
    }
    private File file(String relative)throws IOException {
        String[] parts=relative.split("/");if(parts.length!=3||!parts[0].equals("originals")||!parts[2].matches("source|[a-f0-9-]{36}\\.jpg"))throw SyncProtocol.invalid("invalid original path");SyncProtocol.uuid(parts[1]);
        return new File(new File(new File(context.getFilesDir(),"documents"),parts[1]),parts[2]);
    }
    private void transfer(InputStream in,OutputStream out,long length,MessageDigest hash)throws IOException {byte[] buffer=new byte[65536];long left=length;while(left>0){control.check();int n=in.read(buffer,0,(int)Math.min(left,buffer.length));if(n<0)throw SyncProtocol.invalid("truncated blob");out.write(buffer,0,n);hash.update(buffer,0,n);left-=n;}}
    private void copy(InputStream in,OutputStream out,long length)throws IOException {byte[] buffer=new byte[65536];long left=length;while(left>0){control.check();int n=in.read(buffer,0,(int)Math.min(left,buffer.length));if(n<0)throw SyncProtocol.invalid("truncated original");out.write(buffer,0,n);left-=n;}}
    private void copyFile(File source,File target,long length)throws IOException {File temporary=new File(target.getParentFile(),".sync-"+UUID.randomUUID()+".part");boolean done=false;try(FileInputStream input=new FileInputStream(source);FileOutputStream output=new FileOutputStream(temporary)){copy(input,output,length);if(input.read()!=-1)throw SyncProtocol.invalid("original length changed");output.getFD().sync();done=true;}finally{if(!done)temporary.delete();}if(target.exists()||!temporary.renameTo(target)){temporary.delete();throw new IOException("Original path appeared during install");}syncDir(target.getParentFile());}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException error){throw new AssertionError(error);}}
    private static String utf8(byte[] bytes)throws IOException {try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException error){throw SyncProtocol.invalid("invalid UTF-8");}}
    private static void syncDir(File directory)throws IOException {java.io.FileDescriptor fd=null;try{fd=android.system.Os.open(directory.getAbsolutePath(),android.system.OsConstants.O_RDONLY,0);android.system.Os.fsync(fd);}catch(android.system.ErrnoException error){throw new IOException("Could not sync original directory",error);}finally{if(fd!=null)try{android.system.Os.close(fd);}catch(android.system.ErrnoException ignored){}}}
    private static void remove(File file){if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);}file.delete();}
}
