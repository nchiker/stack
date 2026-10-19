package org.stackdocs.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Consumer;

/** Portable v3 saved library (v1/v2 readable). Repository snapshot/plan/commit/recover run only on app.io.
 * Immutable snapshots and hostile-input staging stream on the separate archive executor. */
final class LibraryArchive {
    static final byte[] MAGIC={'S','T','A','C','K','B','K',1};
    static final int MANIFEST_LIMIT=32*1024*1024, DOCUMENT_LIMIT=1000, PAGE_LIMIT=10000, FILE_LIMIT=20000;
    static final long FILE_BYTES=256L*1024*1024, TOTAL_BYTES=8L*1024*1024*1024;
    static final String[] DOC_V1=cols("id,title,mime,added_at,page_count,title_user_edited,initial_page_count,source_scan_id,title_origin,filing_origin,title_decision,filing_decision");
    static final String[] DOC=cols(android.text.TextUtils.join(",",DOC_V1)+",notes,notes_revision,metadata_revision");
    static final String[] DETAIL=cols("document_id,field_id,kind,label,value,qualifier,origin,state,evidence,revision");
    static final String[] TAG=cols("id,name"), DOCUMENT_TAG=cols("document_id,tag_id,field_id");
    static final String[] PAGE=cols("id,document_id,position,source_page,rotation,crop,enhancement,ocr_text,ocr_state,ocr_engine,language,source_file,flatten,content_revision,text_revision,ocr_error,ocr_config,text_config");
    static final String[] STACK_OLD=cols("id,name,color,visible");
    static final String[] STACK=cols("id,name,color,visible,sort_order");
    static final String[] MEMBER=cols("document_id,stack_id");
    static final String[] SUGGESTION=cols("document_id,generation,status,input,evidence,rule,title_revision,filing_revision,payload,title_state,filing_state");
    static final String[] CHOICE=cols("document_id,evidence,field,choice,value");
    private final File root, originals;
    private final Context context;
    private final LibraryRepository repository;
    private final SQLiteDatabase db;
    interface Probe { void reached(String boundary) throws IOException; }
    Probe probe=boundary -> {};
    LibraryArchive(Context context,LibraryRepository repository,SQLiteDatabase db) {
        this.context=context.getApplicationContext();this.repository=repository;this.db=db;
        root=new File(context.getFilesDir(),"archive_operations"); originals=new File(context.getFilesDir(),"documents");
    }
    static final class Progress {
        volatile boolean canceled;
        volatile String label="Preparing…";
        void check() throws IOException {if(canceled || Thread.currentThread().isInterrupted())throw new InterruptedIOException("Operation canceled. Existing documents are unchanged.");}
        void update(String value) throws IOException {check();label=value;}
    }
    static final class Snapshot implements AutoCloseable {
        final File directory; final JSONObject manifest;
        Snapshot(File directory,JSONObject manifest){this.directory=directory;this.manifest=manifest;}
        @Override public void close(){remove(directory);}
    }
    static final class Staged implements AutoCloseable {
        final File directory; final JSONObject manifest;
        Plan plan;
        Staged(File directory,JSONObject manifest){this.directory=directory;this.manifest=manifest;}
        @Override public void close(){if(plan==null || !new File(directory,"journal.json").exists())remove(directory);}
    }
    static final class Plan {
        final String operation;
        final Map<String,String> documents=new LinkedHashMap<>(),pages=new LinkedHashMap<>(),stacks=new LinkedHashMap<>(),scans=new LinkedHashMap<>();
        final JSONArray newStacks=new JSONArray();
        final boolean pristine;
        int collisions,mergedStacks;
        String catalog;
        Plan(String operation,boolean pristine){this.operation=operation;this.pristine=pristine;}
        String summary(){return documents.size()+" documents will be added. "+collisions+" document ID conflicts will get new IDs. "+mergedStacks+" stacks match existing stacks; their current visibility is kept. Existing documents, names and filing stay as they are. Selecting this archive again imports another copy.";}
    }
    Snapshot snapshot(Progress progress) throws IOException { return snapshot(progress,false); }
    Snapshot snapshot(Progress progress,boolean syncInternal) throws IOException {
        if(!syncInternal)try {
            SyncProtocol.State sync=new SyncStore(context,repository).load();
            if(sync!=null) {
                if(sync.conflicts().length()>0)throw new IOException("Resolve sync conflicts before exporting a complete portable backup; document PDF copies remain available.");
                try { SyncGraph.project(sync.values()); }
                catch(IOException|JSONException invalid){throw new IOException("Resolve the invalid sync graph before exporting a complete portable backup.",invalid);}
            }
        }catch(JSONException error){throw new IOException("Sync review could not be checked",error);}
        progress.update("Capturing saved library…"); mkdir(root);File directory=new File(root,UUID.randomUUID().toString());mkdir(directory);
        boolean ready=false;
        db.beginTransaction();
        try {
            JSONObject json=new JSONObject();json.put("format","org.stackdocs.library");json.put("version",3);json.put("schema",8);json.put("created_at",System.currentTimeMillis());
            json.put("documents",rows("documents",DOC,"id")); json.put("pages",rows("pages",PAGE,"document_id,position"));
            json.put("stacks",rows("stacks",STACK,"sort_order,rowid"));json.put("memberships",rows("document_stacks",MEMBER,"document_id,stack_id"));
            json.put("suggestions",rows("suggestions",SUGGESTION,"document_id"));json.put("choices",rows("suggestion_choices",CHOICE,"document_id,evidence,field"));
            json.put("details",rows("details",DETAIL,"document_id,field_id"));
            json.put("tags",rows("tags",TAG,"id"));json.put("document_tags",rows("document_tags",DOCUMENT_TAG,"document_id,field_id"));
            JSONObject evidence=new JSONObject();JSONArray files=new JSONArray();
            for(JSONObject document:objects(json.getJSONArray("documents"))) {
                progress.check();String id=document.getString("id");uuid(id);evidence.put(id,repository.suggestions.currentEvidence(id));
                File sourceDir=new File(originals,id);File[] sources=sourceDir.listFiles();if(sources==null)throw new IOException("An original is missing. Reopen the document before backing up.");
                Arrays.sort(sources,Comparator.comparing(File::getName));
                for(File source:sources) {
                    if(source.getName().equals(".restore_owner"))continue;
                    path("originals/"+id+"/"+source.getName());regular(source);
                    File target=new File(directory,"originals/"+id+"/"+source.getName());mkdir(target.getParentFile());
                    try {Os.link(source.getAbsolutePath(),target.getAbsolutePath());}
                    catch(android.system.ErrnoException unavailable) {copyFile(source,target,progress);}
                    JSONObject entry=new JSONObject();entry.put("path","originals/"+id+"/"+source.getName());entry.put("length",source.length());
                    // Hash after pinning; files are immutable, and repository writes are serialized.
                    files.put(entry);
                }
            }
            json.put("decision_evidence",evidence);json.put("files",files);
            db.setTransactionSuccessful();ready=true;return new Snapshot(directory,json);
        } catch(JSONException error){throw invalid();}
        finally {db.endTransaction();if(!ready)remove(directory);}
    }
    private JSONArray rows(String table,String[] columns,String order) throws JSONException {
        JSONArray out=new JSONArray();
        try(Cursor cursor=db.query(table,columns,null,null,null,null,order)) {
            while(cursor.moveToNext()) {
                JSONObject row=new JSONObject();
                for(int i=0;i<columns.length;i++) row.put(columns[i],cursor.isNull(i)?JSONObject.NULL:cursor.getType(i)==Cursor.FIELD_TYPE_INTEGER?cursor.getLong(i):cursor.getString(i));
                out.put(row);
            }
        }
        return out;
    }
    static void write(Snapshot snapshot,OutputStream destination,Progress progress) throws IOException {
        try {
            JSONArray files=snapshot.manifest.getJSONArray("files");
            for(JSONObject entry:objects(files)) {progress.update("Checking original "+(filesIndex(files,entry)+1)+" of "+files.length()+"…");entry.put("sha256",hex(hash(new File(snapshot.directory,entry.getString("path")),progress)));}
            validate(snapshot.manifest);byte[] manifest=snapshot.manifest.toString().getBytes(StandardCharsets.UTF_8);
            if(manifest.length>MANIFEST_LIMIT)throw new IOException("This library exceeds the 32 MiB backup metadata limit.");
            DataOutputStream out=new DataOutputStream(destination);out.write(MAGIC);out.writeInt(manifest.length);out.write(digest().digest(manifest));out.write(manifest);
            int count=0;
            for(JSONObject entry:objects(files)) {
                progress.update("Writing original "+(++count)+" of "+files.length()+"…");String path=entry.getString("path");byte[] name=path.getBytes(StandardCharsets.UTF_8);
                out.writeShort(name.length);out.write(name);out.writeLong(entry.getLong("length"));
                try(InputStream input=new FileInputStream(new File(snapshot.directory,path))) {transfer(input,out,entry.getLong("length"),progress,null);if(input.read()!=-1)throw invalid();}
            }
            progress.check();out.flush();
        } catch(JSONException error){throw invalid();}
    }
    private static int filesIndex(JSONArray files,JSONObject entry) {for(int i=0;i<files.length();i++)if(files.opt(i)==entry)return i;return 0;}
    Staged stage(InputStream source,Progress progress) throws IOException {
        mkdir(root);File directory=new File(root,UUID.randomUUID().toString());mkdir(directory);boolean ready=false;
        try {
            DataInputStream input=new DataInputStream(new BufferedInputStream(source,65536));byte[] magic=new byte[MAGIC.length];input.readFully(magic);if(!Arrays.equals(magic,MAGIC))throw new IOException("Choose a supported Stack .stackbackup file (formats 1, 2 or 3).");
            int length=input.readInt();if(length<2 || length>MANIFEST_LIMIT)throw new IOException("Backup metadata exceeds the 32 MiB limit.");
            byte[] expected=new byte[32];input.readFully(expected);byte[] bytes=new byte[length];input.readFully(bytes);progress.check();
            if(!MessageDigest.isEqual(expected,digest().digest(bytes)))throw new IOException("The backup manifest checksum does not match.");
            JSONObject json=ArchiveJson.read(utf8(bytes));validate(json);upgradeOlder(json);
            JSONArray files=json.getJSONArray("files");int count=0;
            for(JSONObject entry:objects(files)) {
                progress.update("Validating original "+(++count)+" of "+files.length()+"…");
                int nameLength=input.readUnsignedShort();if(nameLength<1||nameLength>160)throw invalid();byte[] name=new byte[nameLength];input.readFully(name);
                String path=utf8(name);path(path);long size=input.readLong();if(!path.equals(entry.getString("path")) || size!=entry.getLong("length"))throw invalid();
                File target=new File(directory,path);mkdir(target.getParentFile());space(target.getParentFile(),size);MessageDigest hash=digest();
                try(FileOutputStream out=new FileOutputStream(target)) {transfer(input,out,size,progress,hash);out.getFD().sync();}
                if(!hex(hash.digest()).equals(entry.getString("sha256")))throw new IOException("An original checksum does not match. Choose a complete, unchanged backup.");
            }
            if(input.read()!=-1)throw new IOException("Unexpected or duplicate data follows the backup.");
            validateSources(directory,json,progress);probe.reached("staged");ready=true;return new Staged(directory,json);
        } catch(JSONException error){throw invalid();}
        finally {if(!ready)remove(directory);}
    }
    Plan plan(Staged staged) throws IOException {
        try {
            Plan plan=new Plan(staged.directory.getName(),pristine());plan.catalog=rows("stacks",STACK,"id").toString();
            Set<String> docIds=ids("documents","id"),pageIds=ids("pages","id"),scanIds=ids("documents","source_scan_id");
            for(JSONObject row:objects(staged.manifest.getJSONArray("documents"))) {
                String id=row.getString("id"),mapped=id;int attempt=0;
                while(docIds.contains(mapped)||new File(originals,mapped).exists())mapped=mapped(plan.operation,"document",id,++attempt);
                if(!mapped.equals(id))plan.collisions++;docIds.add(mapped);plan.documents.put(id,mapped);
                if(!row.isNull("source_scan_id")) {String scan=row.getString("source_scan_id"),out=scan;attempt=0;while(scanIds.contains(out))out=mapped(plan.operation,"scan",scan,++attempt);scanIds.add(out);plan.scans.put(scan,out);}
            }
            for(JSONObject row:objects(staged.manifest.getJSONArray("pages"))) {String id=row.getString("id"),mapped=id;int attempt=0;while(pageIds.contains(mapped))mapped=mapped(plan.operation,"page",id,++attempt);pageIds.add(mapped);plan.pages.put(id,mapped);}
            List<JSONObject> existing=plan.pristine?new ArrayList<>():objects(rows("stacks",STACK,"id"));Set<String> stackIds=new HashSet<>();for(JSONObject stack:existing)stackIds.add(stack.getString("id"));
            for(JSONObject stack:objects(staged.manifest.getJSONArray("stacks"))) {
                String id=stack.getString("id"),name=stack.getString("name"),match=null;
                for(JSONObject other:existing) if(other.getString("name").equalsIgnoreCase(name)) {match=other.getString("id");break;}
                if(match!=null) {plan.stacks.put(id,match);plan.mergedStacks++;continue;}
                String mapped=id;int attempt=0;while(stackIds.contains(mapped))mapped=mapped(plan.operation,"stack",id,++attempt);
                JSONObject fresh=new JSONObject(stack.toString());fresh.put("id",mapped);fresh.put("sort_order",existing.size());plan.newStacks.put(fresh);existing.add(fresh);stackIds.add(mapped);plan.stacks.put(id,mapped);
            }
            staged.plan=plan;return plan;
        } catch(JSONException error){throw invalid();}
    }
    int commit(Staged staged,Progress progress) throws IOException {
        Plan plan=staged.plan;if(plan==null)throw new IOException("Review the backup before restoring.");
        try {
            if(committed(plan.operation)){remove(staged.directory);return plan.documents.size();}
            if(plan.pristine && !pristine())throw new IOException("The library changed during review. Cancel and review the backup again.");
            if(!rows("stacks",STACK,"id").toString().equals(plan.catalog))throw new IOException("Stacks changed during review. Cancel and review the backup again.");
            for(String id:plan.documents.values())if(ids("documents","id").contains(id)||new File(originals,id).exists())throw new IOException("The library changed during review. Cancel and select the backup again.");
            for(String id:plan.pages.values())if(ids("pages","id").contains(id))throw new IOException("Pages changed during review. Review again.");
            progress.update("Installing restored originals…");
            JSONObject journal=new JSONObject();journal.put("operation",plan.operation);journal.put("documents",new JSONArray(plan.documents.values()));atomic(new File(staged.directory,"journal.json"),journal.toString());syncDirectory(staged.directory);probe.reached("journal");
            for(Map.Entry<String,String> doc:plan.documents.entrySet()) {
                progress.check();File from=new File(staged.directory,"originals/"+doc.getKey()),to=new File(originals,doc.getValue());
                atomic(new File(from,".restore_owner"),plan.operation);syncDirectory(from);
                if(to.exists() || !from.renameTo(to))throw new IOException("Not enough storage to install restored originals. Retry with more free space.");
                syncDirectory(originals);probe.reached("file");
            }
            progress.update("Committing restored library…");
            db.beginTransaction();
            try {
                if(plan.pristine)db.delete("stacks",null,null);
                for(JSONObject stack:objects(plan.newStacks))insert("stacks",stack,STACK);
                for(JSONObject original:objects(staged.manifest.getJSONArray("documents"))) {
                    JSONObject row=new JSONObject(original.toString());String old=row.getString("id");row.put("id",plan.documents.get(old));
                    if(!row.isNull("source_scan_id"))row.put("source_scan_id",plan.scans.get(row.getString("source_scan_id")));
                    insert("documents",row,DOC);
                }
                for(JSONObject original:objects(staged.manifest.getJSONArray("pages"))) {
                    JSONObject row=new JSONObject(original.toString());row.put("id",plan.pages.get(row.getString("id")));row.put("document_id",plan.documents.get(row.getString("document_id")));
                    // Claims and attempts are never portable. Missing/stale engine work queues after commit.
                    boolean compatible=OcrModel.CONFIG.equals(row.getString("ocr_config")) && OcrModel.CONFIG.equals(row.getString("text_config")) && row.getLong("content_revision")==row.getLong("text_revision");
                    String state=row.getString("ocr_state");if(!compatible || state.equals("running") || state.equals("pending"))row.put("ocr_state","pending");
                    row.put("ocr_config",OcrModel.CONFIG);if(!compatible){row.put("text_revision",-1);row.put("text_config","");}insert("pages",row,PAGE);
                }
                for(JSONObject original:objects(staged.manifest.getJSONArray("memberships"))) {
                    JSONObject row=new JSONObject();row.put("document_id",plan.documents.get(original.getString("document_id")));row.put("stack_id",plan.stacks.get(original.getString("stack_id")));insert("document_stacks",row,MEMBER);
                }
                for(JSONObject original:objects(staged.manifest.getJSONArray("suggestions"))) {
                    JSONObject row=new JSONObject(original.toString());row.put("document_id",plan.documents.get(row.getString("document_id")));row.put("status","pending");row.put("generation",UUID.randomUUID().getMostSignificantBits() & 0x3fffffffffffffffL);
                    ContentValues values=values(row,SUGGESTION);values.put("claim","");db.update("suggestions",values,"document_id=?",new String[]{row.getString("document_id")});
                }
                JSONObject oldEvidence=staged.manifest.getJSONObject("decision_evidence");
                for(JSONObject original:objects(staged.manifest.getJSONArray("choices"))) {
                    JSONObject row=new JSONObject(original.toString());String old=row.getString("document_id"),id=plan.documents.get(old);row.put("document_id",id);
                    // Evidence includes IDs. Rebase only the choice for the archived CURRENT evidence.
                    if(row.getString("evidence").equals(oldEvidence.getString(old)))row.put("evidence",repository.suggestions.currentEvidence(id));
                    row.put("value",remapChoice(row.getString("field"),row.getString("value"),plan.stacks));insert("suggestion_choices",row,CHOICE);
                }
                Map<String,String> tagMap=new HashMap<>();
                for(JSONObject original:objects(staged.manifest.getJSONArray("tags"))) {
                    String old=original.getString("id"),name=original.getString("name"),target=null;
                    try(Cursor row=db.rawQuery("SELECT id FROM tags WHERE name=? COLLATE NOCASE",new String[]{name})) {if(row.moveToFirst())target=row.getString(0);}
                    if(target==null) {target=old;int attempt=0;Set<String> used=ids("tags","id");while(used.contains(target))target=mapped(plan.operation,"tag",old,++attempt);
                        JSONObject row=new JSONObject(original.toString());row.put("id",target);insert("tags",row,TAG);}
                    tagMap.put(old,target);
                }
                for(JSONObject original:objects(staged.manifest.getJSONArray("details"))) {
                    JSONObject row=new JSONObject(original.toString());row.put("document_id",plan.documents.get(row.getString("document_id")));
                    // Preserve raw source evidence but rebase stable page IDs when present. Historical/deleted-page evidence stays historical.
                    JSONObject evidence=ArchiveJson.read(row.getString("evidence"));
                    String oldPage=evidence.optString("page_id","");if(plan.pages.containsKey(oldPage))evidence.put("page_id",plan.pages.get(oldPage));
                    row.put("evidence",evidence.toString());insert("details",row,DETAIL);
                }
                for(JSONObject original:objects(staged.manifest.getJSONArray("document_tags"))) {
                    JSONObject row=new JSONObject(original.toString());row.put("document_id",plan.documents.get(row.getString("document_id")));row.put("tag_id",tagMap.get(row.getString("tag_id")));insert("document_tags",row,DOCUMENT_TAG);
                }
                for(String id:plan.documents.values())LibraryRepository.index(db,id,repository.document(id).title);
                ContentValues marker=new ContentValues();marker.put("id",plan.operation);db.insertOrThrow("restore_commits",null,marker);
                progress.check();probe.reached("transaction");db.setTransactionSuccessful();
            } finally {db.endTransaction();}
            probe.reached("committed");cleanupCommitted(staged.directory,plan.documents.values());repository.archiveChanged();return plan.documents.size();
        } catch(JSONException error){throw invalid();}
        catch(IOException|RuntimeException failure) {recoverOne(staged.directory);throw failure;}
    }
    private static String remapChoice(String field,String value,Map<String,String> stacks) {
        if(!field.equals("filing") || value.isEmpty())return value;
        if(value.startsWith("{")) {
            try {JSONObject json=new JSONObject(value);json.put("chosen",mapCsv(json.optString("chosen"),stacks));JSONObject proposed=json.optJSONObject("proposed");if(proposed!=null){JSONObject old=proposed.optJSONObject("stacks");if(old!=null){JSONObject mapped=new JSONObject();for(Iterator<String> it=old.keys();it.hasNext();){String key=it.next();mapped.put(stacks.getOrDefault(key,key),old.get(key));}proposed.put("stacks",mapped);}}return json.toString();}catch(JSONException invalid){return value;}
        }
        return mapCsv(value,stacks);
    }
    private static String mapCsv(String value,Map<String,String> stacks){List<String> out=new ArrayList<>();for(String id:value.split(","))if(!id.isEmpty())out.add(stacks.getOrDefault(id,id));return android.text.TextUtils.join(",",out);}
    void recover() throws IOException {mkdir(root);mkdir(originals);File[] operations=root.listFiles();if(operations!=null)for(File operation:operations){uuid(operation.getName());recoverOne(operation);}}
    private void recoverOne(File operation) throws IOException {
        File journal=new File(operation,"journal.json");
        if(journal.exists()) {
            try {
                byte[] bytes=readBounded(journal,128*1024);JSONObject json=ArchiveJson.read(utf8(bytes));if(!operation.getName().equals(json.getString("operation")))throw invalid();
                List<String> ids=new ArrayList<>();JSONArray documents=json.getJSONArray("documents");if(documents.length()>DOCUMENT_LIMIT)throw invalid();for(int i=0;i<documents.length();i++){String id=documents.getString(i);uuid(id);ids.add(id);}
                if(committed(operation.getName())) {cleanupCommitted(operation,ids);return;}
                for(String id:ids) {
                    File owned=new File(originals,id),tag=new File(owned,".restore_owner");
                    if(!ids("documents","id").contains(id) && tag.isFile() && utf8(readBounded(tag,128)).equals(operation.getName()))remove(owned);
                }
                syncDirectory(originals);
            } catch(JSONException error){throw invalid();}
        }
        remove(operation);
    }
    private void cleanupCommitted(File operation,Collection<String> ids) throws IOException {
        for(String id:ids){File tag=new File(new File(originals,id),".restore_owner");if(tag.exists()&&!tag.delete())throw new IOException("Restore is saved. Reopen Stack to finish local cleanup.");}
        remove(operation);
    }
    private boolean committed(String operation) {try(Cursor row=db.rawQuery("SELECT 1 FROM restore_commits WHERE id=?",new String[]{operation})){return row.moveToFirst();}}
    private Set<String> ids(String table,String column) {Set<String> ids=new HashSet<>();try(Cursor row=db.query(table,new String[]{column},null,null,null,null,null)){while(row.moveToNext())if(!row.isNull(0))ids.add(row.getString(0));}return ids;}
    private boolean pristine() throws JSONException {
        if(!ids("documents","id").isEmpty())return false;
        JSONArray stacks=rows("stacks",STACK,"sort_order,rowid");if(stacks.length()!=7)return false;
        Map<String,String> starter=new HashMap<>();String[] ids={"bills","banking","ids","house","medical","receipts","starred"},names={"Bills","Banking","IDs","House","Medical","Receipts","Starred"};for(int i=0;i<ids.length;i++)starter.put(ids[i],names[i]);
        int position=0;for(JSONObject stack:objects(stacks)){if(!stack.getString("id").equals(ids[position])||stack.getInt("sort_order")!=position||!stack.getString("name").equals(starter.get(stack.getString("id")))||stack.getInt("visible")!=1)return false;position++;}return true;
    }
    private void insert(String table,JSONObject row,String[] columns) throws JSONException {db.insertOrThrow(table,null,values(row,columns));}
    private static ContentValues values(JSONObject row,String[] columns) throws JSONException {ContentValues values=new ContentValues();for(String column:columns){Object value=row.get(column);if(value==JSONObject.NULL)values.putNull(column);else if(value instanceof Number)values.put(column,((Number)value).longValue());else values.put(column,(String)value);}return values;}
    private static String mapped(String op,String kind,String old,int attempt){return UUID.nameUUIDFromBytes((op+":"+kind+":"+old+":"+attempt).getBytes(StandardCharsets.UTF_8)).toString();}
    static void validate(JSONObject json) throws IOException {
        try {
            long version=number(json,"version",1,Long.MAX_VALUE);
            if(!"org.stackdocs.library".equals(str(json,"format",64)) || !((version==1 && number(json,"schema",0,Long.MAX_VALUE)==6) || (version==2 && number(json,"schema",0,Long.MAX_VALUE)==7) || (version==3 && number(json,"schema",0,Long.MAX_VALUE)==8)))throw new IOException("This backup version is unsupported. Keep it for a compatible Stack version.");
            keys(json,cols("format,version,schema,created_at,documents,pages,stacks,memberships,suggestions,choices,decision_evidence,files"+(version>=2?",details,tags,document_tags":"")));
            number(json,"created_at",0,Long.MAX_VALUE);
            JSONArray docs=array(json,"documents",DOCUMENT_LIMIT),pages=array(json,"pages",PAGE_LIMIT),stacks=array(json,"stacks",1000),members=array(json,"memberships",100000),suggestions=array(json,"suggestions",DOCUMENT_LIMIT),choices=array(json,"choices",20000),files=array(json,"files",FILE_LIMIT);
            Map<String,JSONObject> documents=new HashMap<>();Map<String,Set<Integer>> positions=new HashMap<>();Set<String> pageIds=new HashSet<>(),stackIds=new HashSet<>(),stackNames=new TreeSet<>(String.CASE_INSENSITIVE_ORDER),scans=new HashSet<>();
            Set<String> origins=new HashSet<>(Arrays.asList("default","filename","legacy","manual","accepted","edited"));
            for(JSONObject row:objects(docs)) {
                keys(row,version==1?DOC_V1:DOC);String id=str(row,"id",36);uuid(id);if(documents.put(id,row)!=null)throw invalid();positions.put(id,new HashSet<>());
                name(row,"title");String mime=str(row,"mime",40);if(!Arrays.asList("application/pdf","application/x-stack-scan","image/jpeg","image/png","image/webp").contains(mime))throw invalid();
                if(version>=2){str(row,"notes",MetadataStore.NOTES_LIMIT);number(row,"notes_revision",0,Long.MAX_VALUE-1);number(row,"metadata_revision",0,Long.MAX_VALUE-1);}
                number(row,"added_at",0,Long.MAX_VALUE);long count=number(row,"page_count",1,1000);number(row,"initial_page_count",count,1000);number(row,"title_user_edited",0,1);
                if(!row.isNull("source_scan_id")){String scan=str(row,"source_scan_id",36);uuid(scan);if(!scans.add(scan))throw invalid();}
                if(!origins.contains(str(row,"title_origin",16))||!origins.contains(str(row,"filing_origin",16)))throw invalid();number(row,"title_decision",0,Long.MAX_VALUE-1);number(row,"filing_decision",0,Long.MAX_VALUE-1);
            }
            JSONObject evidence=json.getJSONObject("decision_evidence");if(evidence.length()!=documents.size())throw invalid();for(String id:documents.keySet())sha(str(evidence,id,64));
            int stackPosition=0;
            for(JSONObject row:objects(stacks)) {keys(row,version==3?STACK:STACK_OLD);if(version==3 && number(row,"sort_order",0,stacks.length()-1)!=stackPosition++)throw invalid();String id=str(row,"id",80);stackId(id);if(!stackIds.add(id))throw invalid();name(row,"name");if(!stackNames.add(row.getString("name")))throw invalid();number(row,"color",Integer.MIN_VALUE,Integer.MAX_VALUE);number(row,"visible",0,1);}
            for(JSONObject row:objects(pages)) {
                keys(row,PAGE);String id=str(row,"id",36);uuid(id);if(!pageIds.add(id))throw invalid();String doc=str(row,"document_id",36);JSONObject document=documents.get(doc);if(document==null)throw invalid();
                int position=(int)number(row,"position",0,document.getLong("page_count")-1);if(!positions.get(doc).add(position))throw invalid();
                number(row,"source_page",0,document.getLong("initial_page_count")-1);String source=str(row,"source_file",40);path("originals/"+doc+"/"+source);
                if(!document.getString("mime").equals("application/x-stack-scan") && !source.equals("source"))throw invalid();
                if(!document.getString("mime").equals("application/pdf") && row.getLong("source_page")!=0)throw invalid();
                new PageState(id,source,row.getInt("source_page"),(int)number(row,"rotation",0,270),PageGeometry.decode(str(row,"crop",256)),str(row,"enhancement",32),number(row,"flatten",0,1)==1);
                String crop=row.getString("crop");if(!crop.isEmpty() && PageGeometry.decode(crop)==null)throw invalid();
                str(row,"ocr_text",1024*1024);str(row,"ocr_engine",256);str(row,"language",64);str(row,"ocr_error",80);str(row,"ocr_config",256);str(row,"text_config",256);
                number(row,"content_revision",0,Long.MAX_VALUE-1);number(row,"text_revision",-1,row.getLong("content_revision"));
                if(!Arrays.asList("pending","running","succeeded","failed").contains(str(row,"ocr_state",16)))throw invalid();
            }
            for(String id:documents.keySet())if(positions.get(id).size()!=documents.get(id).getInt("page_count"))throw invalid();
            Set<String> relations=new HashSet<>();
            for(JSONObject row:objects(members)){keys(row,MEMBER);String doc=str(row,"document_id",36),stack=str(row,"stack_id",80);if(!documents.containsKey(doc)||!stackIds.contains(stack)||!relations.add(doc+":"+stack))throw invalid();}
            Set<String> proposed=new HashSet<>();
            for(JSONObject row:objects(suggestions)) {
                keys(row,SUGGESTION);String id=str(row,"document_id",36);if(!documents.containsKey(id)||!proposed.add(id))throw invalid();number(row,"generation",0,Long.MAX_VALUE-1);number(row,"title_revision",0,Long.MAX_VALUE-1);number(row,"filing_revision",0,Long.MAX_VALUE-1);
                if(!Arrays.asList("pending","running","waiting","ready","empty","failed").contains(str(row,"status",16)))throw invalid();
                for(String field:new String[]{"input","evidence"}){String value=str(row,field,64);if(!value.isEmpty())sha(value);}
                str(row,"rule",128);String payload=str(row,"payload",1024*1024);ArchiveJson.read(payload);SuggestionRules.Result.decode(payload);
                decision(str(row,"title_state",16));decision(str(row,"filing_state",16));
            }
            if(proposed.size()!=documents.size())throw invalid();Set<String> decisions=new HashSet<>();
            for(JSONObject row:objects(choices)){keys(row,CHOICE);String id=str(row,"document_id",36),e=str(row,"evidence",64),field=str(row,"field",16);sha(e);if(!documents.containsKey(id)||!Arrays.asList("title","filing").contains(field)||!decisions.add(id+e+field))throw invalid();decision(str(row,"choice",16));str(row,"value",1024*1024);}
            if(version>=2) {
                Set<String> fields=new HashSet<>(),activeOrganizations=new HashSet<>(),tagIds=new HashSet<>(),tagNames=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                Map<String,String> detailNames=new HashMap<>(),tagNamesById=new HashMap<>();
                for(JSONObject row:objects(array(json,"details",20000))) {
                    keys(row,DETAIL);String doc=str(row,"document_id",36),field=str(row,"field_id",100);
                    if(!documents.containsKey(doc)||!field.matches("[A-Za-z0-9:_-]{1,100}")||!fields.add(doc+":"+field))throw invalid();
                    DetailValue.validate(str(row,"kind",20),str(row,"label",200),str(row,"value",2000),str(row,"qualifier",16));
                    if(!Arrays.asList("manual","accepted","edited","dismissed").contains(str(row,"origin",16))||!Arrays.asList("saved","removed","rejected").contains(str(row,"state",16)))throw invalid();
                    number(row,"revision",1,Long.MAX_VALUE-1);ArchiveJson.read(str(row,"evidence",8192));
                    if(row.getString("state").equals("saved")&&row.getString("kind").equals("organization")){activeOrganizations.add(doc+":"+field);detailNames.put(doc+":"+field,row.getString("value"));}
                }
                for(JSONObject row:objects(array(json,"tags",20000))) {keys(row,TAG);String id=str(row,"id",36);uuid(id);if(!tagIds.add(id)||!tagNames.add(str(row,"name",200)))throw invalid();name(row,"name");tagNamesById.put(id,row.getString("name"));}
                Set<String> links=new HashSet<>();
                for(JSONObject row:objects(array(json,"document_tags",20000))) {keys(row,DOCUMENT_TAG);String key=str(row,"document_id",36)+":"+str(row,"field_id",100);
                    if(!activeOrganizations.contains(key)||!tagIds.contains(str(row,"tag_id",36))||!links.add(key))throw invalid();
                    // A relation may never contradict its authoritative organization value.
                    String detailName=detailNames.get(key),tagName=tagNamesById.get(row.getString("tag_id"));
                    if(detailName==null||tagName==null||!detailName.equalsIgnoreCase(tagName))throw invalid();
                }
                if(!links.equals(activeOrganizations))throw invalid();
            }
            Set<String> paths=new HashSet<>();Map<String,Integer> counts=new HashMap<>();long total=0;
            for(JSONObject row:objects(files)){keys(row,cols("path,length,sha256"));String name=str(row,"path",160);path(name);String doc=name.split("/")[1];if(!documents.containsKey(doc)||!paths.add(name))throw invalid();counts.put(doc,counts.getOrDefault(doc,0)+1);total+=number(row,"length",1,FILE_BYTES);if(total>TOTAL_BYTES)throw new IOException("Backup exceeds the 8 GiB original-byte limit.");sha(str(row,"sha256",64));}
            for(String id:documents.keySet())if(!counts.containsKey(id))throw invalid();
            for(JSONObject row:objects(pages))if(!paths.contains("originals/"+row.getString("document_id")+"/"+row.getString("source_file")))throw invalid();
        } catch(JSONException|IllegalArgumentException error){throw invalid();}
    }
    private static void upgradeOlder(JSONObject json) throws JSONException {
        int version=json.getInt("version");
        if(version==1) {
            for(JSONObject row:objects(json.getJSONArray("documents")))row.put("notes","").put("notes_revision",0).put("metadata_revision",0);
            json.put("details",new JSONArray()).put("tags",new JSONArray()).put("document_tags",new JSONArray());
        }
        if(version<3) {
            int position=0;
            for(JSONObject row:objects(json.getJSONArray("stacks")))row.put("sort_order",position++);
            json.put("version",3).put("schema",8);
        }
    }
    private static void validateSources(File directory,JSONObject json,Progress progress) throws IOException,JSONException {
        Map<String,String> mime=new HashMap<>();Map<String,Integer> initial=new HashMap<>();
        for(JSONObject doc:objects(json.getJSONArray("documents"))){mime.put(doc.getString("id"),doc.getString("mime"));initial.put(doc.getString("id"),doc.getInt("initial_page_count"));}
        for(JSONObject entry:objects(json.getJSONArray("files"))) {
            progress.check();String path=entry.getString("path");File source=new File(directory,path);String id=path.split("/")[1],type=mime.get(id);regular(source);
            if(path.endsWith("/source") && type.equals("application/pdf")) {
                try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(descriptor)) {
                    if(renderer.getPageCount()!=initial.get(id))throw invalid();
                } catch(RuntimeException error){throw new IOException("A backed-up PDF is damaged or protected.");}
            } else {
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(source.getPath(),bounds);
                if(bounds.outWidth<1||bounds.outHeight<1||bounds.outMimeType==null)throw new IOException("A backed-up image cannot be opened.");
                String expected=path.endsWith(".jpg")?"image/jpeg":type;
                if(!expected.equals(bounds.outMimeType))throw new IOException("A backed-up original has an unexpected file type.");
            }
        }
    }
    private static void decision(String value) throws IOException {if(!Arrays.asList("offered","accepted","edited","dismissed").contains(value))throw invalid();}
    private static void name(JSONObject row,String key) throws IOException,JSONException {String value=str(row,key,200);if(!LibraryRepository.cleanName(value).equals(value))throw invalid();}
    private static JSONArray array(JSONObject json,String key,int limit) throws JSONException,IOException {JSONArray array=json.getJSONArray(key);if(array.length()>limit)throw new IOException("Backup contains too many "+key+". See the documented archive limits.");return array;}
    private static long number(JSONObject json,String key,long min,long max) throws JSONException,IOException {Object value=json.get(key);if(!(value instanceof Integer||value instanceof Long))throw invalid();long number=((Number)value).longValue();if(number<min||number>max)throw invalid();return number;}
    private static String str(JSONObject json,String key,int limit) throws JSONException,IOException {Object value=json.get(key);if(!(value instanceof String)||((String)value).length()>limit)throw invalid();return (String)value;}
    private static void keys(JSONObject json,String[] fields) throws IOException {if(json.length()!=fields.length)throw invalid();for(String field:fields)if(!json.has(field))throw invalid();}
    private static void sha(String text) throws IOException {if(!text.matches("[a-f0-9]{64}"))throw invalid();}
    private static void uuid(String text) throws IOException {try{if(!UUID.fromString(text).toString().equals(text))throw invalid();}catch(IllegalArgumentException error){throw invalid();}}
    private static void stackId(String text) throws IOException {if(!text.matches("[A-Za-z0-9_-]{1,80}"))throw invalid();}
    private static void path(String value) throws IOException {
        String[] parts=value.split("/",-1);if(parts.length!=3||!parts[0].equals("originals"))throw invalid();uuid(parts[1]);
        if(!parts[2].equals("source")){if(!parts[2].endsWith(".jpg"))throw invalid();uuid(parts[2].substring(0,parts[2].length()-4));}
    }
    private static String[] cols(String value){return value.split(",");}
    private static List<JSONObject> objects(JSONArray array) throws JSONException {List<JSONObject> out=new ArrayList<>();for(int i=0;i<array.length();i++)out.add(array.getJSONObject(i));return out;}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte value:bytes)out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();}
    private static byte[] hash(File file,Progress progress) throws IOException {MessageDigest hash=digest();try(InputStream input=new FileInputStream(file)){transfer(input,null,file.length(),progress,hash);}return hash.digest();}
    private static void transfer(InputStream input,OutputStream output,long length,Progress progress,MessageDigest digest) throws IOException {
        byte[] buffer=new byte[65536];long remaining=length;
        while(remaining>0){progress.check();int count=input.read(buffer,0,(int)Math.min(buffer.length,remaining));if(count<0)throw new EOFException("The backup or original is incomplete.");if(count==0)throw new IOException("The selected provider stopped delivering data. Choose the file again.");if(digest!=null)digest.update(buffer,0,count);if(output!=null)output.write(buffer,0,count);remaining-=count;}
    }
    private static void copyFile(File from,File to,Progress progress) throws IOException {space(to.getParentFile(),from.length());try(InputStream input=new FileInputStream(from);FileOutputStream output=new FileOutputStream(to)){transfer(input,output,from.length(),progress,null);output.getFD().sync();}}
    private static void regular(File file) throws IOException {try{if(!OsConstants.S_ISREG(Os.lstat(file.getPath()).st_mode))throw invalid();}catch(android.system.ErrnoException error){throw new IOException("An original could not be read. Check local storage.");}}
    private static void mkdir(File file) throws IOException {if(!file.isDirectory()&&!file.mkdirs())throw new IOException("Not enough local storage. Free space and retry.");}
    private static void space(File file,long bytes) throws IOException {if(new StatFs(file.getPath()).getAvailableBytes()<bytes+16L*1024*1024)throw new IOException("Not enough local storage to stage this backup. Free space and retry.");}
    private static void atomic(File file,String text) throws IOException {AtomicFile atomic=new AtomicFile(file);FileOutputStream out=atomic.startWrite();try{out.write(text.getBytes(StandardCharsets.UTF_8));out.getFD().sync();atomic.finishWrite(out);}catch(IOException error){atomic.failWrite(out);throw error;}}
    private static void syncDirectory(File directory) throws IOException {try{FileDescriptor fd=Os.open(directory.getPath(),OsConstants.O_RDONLY,0);try{Os.fsync(fd);}finally{Os.close(fd);}}catch(android.system.ErrnoException error){throw new IOException("Local storage could not make restore durable. Free space and retry.");}}
    private static byte[] readBounded(File file,int max) throws IOException {if(file.length()>max)throw invalid();try(InputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()){LibraryRepository.copy(input,output,max);return output.toByteArray();}}
    private static String utf8(byte[] bytes) throws IOException {try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException error){throw invalid();}}
    static void remove(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);if(file.exists())file.delete();}
    private static IOException invalid(){return new IOException("The backup has invalid or unsupported metadata, paths or relationships. Choose an unchanged Stack backup.");}
}
