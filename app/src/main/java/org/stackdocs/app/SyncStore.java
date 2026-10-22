package org.stackdocs.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Causal state and portable rows commit together. Only the application's IO executor calls this. */
final class SyncStore {
    final Context context;final LibraryRepository repository;final SQLiteDatabase db;private final SyncExchange.Control control;
    LibraryArchive.Probe probe=boundary->{};
    SyncStore(Context context,LibraryRepository repository){this(context,repository,()->{});}
    SyncStore(Context context,LibraryRepository repository,SyncExchange.Control control){this.context=context.getApplicationContext();this.repository=repository;db=repository.database();this.control=control;}
    static boolean retainsOriginal(SQLiteDatabase db,String id) {
        try(Cursor cursor=db.rawQuery("SELECT enabled FROM sync_control WHERE singleton=1",null)){return cursor.moveToFirst()&&cursor.getInt(0)==1;}
    }
    String replica() {
        try(Cursor cursor=db.rawQuery("SELECT value FROM sync_meta WHERE key='replica_id'",null)){if(cursor.moveToFirst())return cursor.getString(0);}
        String id=UUID.randomUUID().toString();ContentValues row=new ContentValues();row.put("key","replica_id");row.put("value",id);db.insertWithOnConflict("sync_meta",null,row,SQLiteDatabase.CONFLICT_IGNORE);
        try(Cursor cursor=db.rawQuery("SELECT value FROM sync_meta WHERE key='replica_id'",null)){cursor.moveToFirst();return cursor.getString(0);}
    }
    // The saved state is megabytes of JSON and takes about a second to parse on
    // a phone. Reuse the last parse while the stored text is unchanged; callers
    // get a copy, so an abandoned transaction cannot alter the cached state.
    private static final Object cacheLock=new Object();
    private static String cachedText;private static SyncProtocol.State cachedState;
    private static void remember(String text,SyncProtocol.State state)throws IOException,JSONException {SyncProtocol.State copy=state.duplicate();synchronized(cacheLock){cachedText=text;cachedState=copy;}}
    SyncProtocol.State load()throws IOException,JSONException {
        final String saved;
        try {saved=DatabaseUtils.stringForQuery(db,"SELECT state FROM sync_state WHERE singleton=1",null);}
        catch(SQLiteDoneException absent){return null;}
        synchronized(cacheLock){if(saved.equals(cachedText))return cachedState.duplicate();}
        SyncProtocol.State state=SyncProtocol.State.restore(ArchiveJson.read(saved));
        boolean model=false;for(SyncProtocol.Operation op:state.history.values())if(op.json.optJSONObject("evidence")!=null&&"baseline.explicit".equals(op.json.getJSONObject("evidence").optString("kind"))&&op.json.getJSONObject("evidence").optInt("graph_model")==SyncGraph.MODEL){model=true;break;}
        if(!model)throw SyncProtocol.invalid("saved graph needs reviewed migration");remember(saved,state);return state;
    }

    int cleanLegacyStacks()throws IOException,JSONException {
        int repaired=0;
        String[] ids={"bills","banking","ids","house","medical","receipts","starred"},names={"Bills","Banking","IDs","House","Medical","Receipts","Starred"};
        for(int i=0;i<ids.length;i++) {
            List<StackCategory> catalog=repository.stacks();StackCategory original=null,duplicate=null;int matches=0;
            for(StackCategory stack:catalog){if(stack.id.equals(ids[i])&&stack.name.equals(names[i]))original=stack;
                boolean generated=false;try{generated=UUID.fromString(stack.id).version()==5;}catch(IllegalArgumentException ignored){}if(generated&&stack.name.matches(java.util.regex.Pattern.quote(names[i])+" [(]adopted [1-9][0-9]*[)]")){duplicate=stack;matches++;}}
            if(matches!=1)continue;
            if(original!=null){if(android.database.DatabaseUtils.longForQuery(db,"SELECT count(*) FROM document_stacks WHERE stack_id=?",new String[]{original.id})!=0)continue;repository.deleteStack(original);}
            else {boolean collision=false;for(StackCategory stack:catalog)if(stack.name.equalsIgnoreCase(names[i]))collision=true;if(collision)continue;}
            repository.editStack(duplicate,names[i],duplicate.visible);repaired++;
        }
        // The ordinary journal already records these edits. Capture them at the
        // next exchange, rather than replaying the library before Home can load.
        return repaired;
    }
    JSONObject status()throws IOException,JSONException {
        String local=replica();SyncProtocol.State state=load();if(state==null)return new JSONObject().put("adopted",false).put("replica",local);
        JSONObject receipts=new JSONObject();try(Cursor rows=db.rawQuery("SELECT peer,frontier FROM sync_receipts ORDER BY peer",null)){while(rows.moveToNext())receipts.put(rows.getString(0),ArchiveJson.read(rows.getString(1)));}
        JSONObject names=new JSONObject(),stacks=new JSONObject(),pages=new JSONObject();
        try(Cursor rows=db.rawQuery("SELECT id,title FROM documents",null)){while(rows.moveToNext())names.put(rows.getString(0),rows.getString(1));}
        try(Cursor rows=db.rawQuery("SELECT id,name FROM stacks",null)){while(rows.moveToNext())stacks.put(rows.getString(0),rows.getString(1));}
        try(Cursor rows=db.rawQuery("SELECT id,position FROM pages",null)){while(rows.moveToNext())pages.put(rows.getString(0),"Page "+(rows.getInt(1)+1));}

        return new JSONObject().put("document_names",names).put("stack_names",stacks).put("page_names",pages).put("adopted",true).put("replica",local).put("library",state.library).put("members",new JSONArray(state.members)).put("frontier",SyncProtocol.json(state.frontier)).put("operations",state.history.size()).put("pending_gaps",state.pending.size()).put("conflicts",state.conflicts()).put("acknowledged_by",receipts);
    }
    JSONObject manifest()throws IOException,JSONException {
        LibraryArchive.Progress progress=new LibraryArchive.Progress(){@Override void check()throws IOException {control.check();super.check();}};
        try(LibraryArchive.Snapshot snapshot=repository.archive().snapshot(progress,true)) {
            JSONObject json=snapshot.manifest;
            JSONArray files=json.getJSONArray("files");for(int i=0;i<files.length();i++){
                JSONObject entry=files.getJSONObject(i);String path=entry.getString("path");File file=new File(new File(context.getFilesDir(),"documents"),path.substring("originals/".length()));long length=entry.getLong("length");
                // Originals are immutable, so a capture only hashes files it has not seen.
                String key=path+":"+length+":"+file.lastModified(),sha=hashes.get(key);
                if(sha==null){sha=hash(file,length,control);hashes.put(key,sha);}
                entry.put("sha256",sha);
            }
            LibraryArchive.validate(json);return json;
        }
    }
    private static final Map<String,String> hashes=new java.util.concurrent.ConcurrentHashMap<>();
    static String hash(File file,long expected)throws IOException {
        return hash(file,expected,()->{});
    }
    static String hash(File file,long expected,SyncExchange.Control control)throws IOException {
        if(!file.isFile()||file.length()!=expected)throw SyncProtocol.invalid("original length changed");
        try {MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];
            try(FileInputStream input=new FileInputStream(file)){int n;while((n=input.read(buffer))!=-1){control.check();digest.update(buffer,0,n);}}return SyncGraph.hex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException error){throw new AssertionError(error);}
    }
    JSONObject start(Collection<String> peers)throws IOException,JSONException {
        if(load()!=null)throw SyncProtocol.invalid("library already adopted");String local=replica();List<String> members=new ArrayList<>(peers);members.add(local);
        SyncProtocol.State state=new SyncProtocol.State(UUID.randomUUID().toString(),members);JSONObject graph=SyncGraph.cells(manifest());
        state.local(local,graph,new JSONObject().put("kind","baseline.explicit").put("graph_model",SyncGraph.MODEL));
        db.beginTransaction();try {saveNew(state,graph,graph,maxChange());db.execSQL("UPDATE sync_control SET enabled=1 WHERE singleton=1");db.setTransactionSuccessful();}finally{db.endTransaction();}
        return status();
    }
    void capture()throws IOException,JSONException {
        control.check();
        final long cursor;
        try{cursor=DatabaseUtils.longForQuery(db,"SELECT journal_cursor FROM sync_state WHERE singleton=1",null);}catch(SQLiteDoneException absent){return;}
        if(maxChange()<=cursor)return;
        SyncProtocol.State state=load();if(state==null)return;
        // Sync JSON grows with the library and can exceed Android's CursorWindow.
        // Scalar statements read the complete value without putting it in a cursor row.
        String saved=DatabaseUtils.stringForQuery(db,"SELECT projection FROM sync_state WHERE singleton=1",null);
        JSONObject current=manifest();db.beginTransaction();try {
            SyncJournal.Replay replay=SyncJournal.capture(db,state,replica(),ArchiveJson.read(saved),cursor,current,control);
            control.check();
            String text=null;
            if(replay.operations>0||replay.cursor>cursor) {
                text=state.saved().toString();ContentValues value=new ContentValues();value.put("state",text);value.put("projection",replay.projection.toString());value.put("journal_cursor",replay.cursor);
                db.update("sync_state",value,"singleton=1",null);
            }
            db.setTransactionSuccessful();
            if(text!=null)remember(text,state);
        } finally {db.endTransaction();}
    }
    private long maxChange(){try(Cursor row=db.rawQuery("SELECT ifnull(max(sequence),0) FROM sync_change",null)){row.moveToFirst();return row.getLong(0);}}
    private void saveNew(SyncProtocol.State state,JSONObject projection,JSONObject baseline,long cursor)throws JSONException {
        ContentValues value=new ContentValues();value.put("singleton",1);value.put("state",state.saved().toString());value.put("projection",projection.toString());value.put("baseline",baseline.toString());value.put("journal_cursor",cursor);db.insertOrThrow("sync_state",null,value);
    }
    void install(SyncProtocol.State state,JSONObject baseline)throws IOException,JSONException {
        JSONObject fallback=baseline;if(fallback==null)fallback=ArchiveJson.read(DatabaseUtils.stringForQuery(db,"SELECT baseline FROM sync_state WHERE singleton=1",null));
        JSONObject values=state.values(),projected;
        try {projected=SyncGraph.project(values);}catch(IOException|JSONException invalid) {
            JSONObject safe=SyncProtocol.copy(fallback);for(String key:SyncProtocol.keys(values))if(SyncProtocol.entityBase(key)&&values.isNull(key))safe.put(key,JSONObject.NULL);
            projected=SyncGraph.project(safe);
        }
        if(hasDrafts())throw SyncProtocol.invalid("finish or discard saved editor drafts before applying an exchange");
        JSONObject cells=SyncGraph.cells(projected);
        db.beginTransaction();try {
            probe.reached("transaction");
            db.execSQL("UPDATE sync_control SET applying=1 WHERE singleton=1");write(projected);
            probe.reached("rows_written");
            String text=state.saved().toString();
            ContentValues row=new ContentValues();row.put("state",text);row.put("projection",cells.toString());row.put("baseline",fallback.toString());row.put("journal_cursor",maxChange());
            if(db.update("sync_state",row,"singleton=1",null)==0){row.put("singleton",1);db.insertOrThrow("sync_state",null,row);}
            db.execSQL("UPDATE sync_control SET enabled=1,applying=0 WHERE singleton=1");db.setTransactionSuccessful();
            remember(text,state);
        }finally{db.endTransaction();}
        probe.reached("committed");
        repository.archiveChanged();
    }
    private boolean hasDrafts(){try(Cursor row=db.rawQuery("SELECT 1 FROM editor_drafts LIMIT 1",null)){return row.moveToFirst();}}
    private void write(JSONObject graph)throws JSONException,IOException {
        db.delete("documents",null,null);db.delete("stacks",null,null);db.delete("tags",null,null);
        db.delete("search_index",null,null);db.delete("page_search",null,null);
        insertRows(graph,"stacks","stacks",LibraryArchive.STACK);
        insertRows(graph,"tags","tags",LibraryArchive.TAG);
        insertRows(graph,"documents","documents",LibraryArchive.DOC);
        insertRows(graph,"pages","pages",LibraryArchive.PAGE);
        insertRows(graph,"memberships","document_stacks",LibraryArchive.MEMBER);
        insertRows(graph,"details","details",LibraryArchive.DETAIL);
        insertRows(graph,"document_tags","document_tags",LibraryArchive.DOCUMENT_TAG);
        insertRows(graph,"choices","suggestion_choices",LibraryArchive.CHOICE);
        insertRows(graph,"suggestions","suggestions",LibraryArchive.SUGGESTION);
        JSONArray documents=graph.getJSONArray("documents");for(int i=0;i<documents.length();i++){JSONObject doc=documents.getJSONObject(i);LibraryRepository.index(db,doc.getString("id"),doc.getString("title"));}
    }
    private void insertRows(JSONObject graph,String key,String table,String[] columns)throws JSONException,IOException {
        JSONArray rows=graph.getJSONArray(key);for(int i=0;i<rows.length();i++){
            control.check();
            JSONObject row=rows.getJSONObject(i);ContentValues values=new ContentValues();
            for(String column:columns){Object value=row.get(column);if(value==JSONObject.NULL)values.putNull(column);else if(value instanceof Number)values.put(column,((Number)value).longValue());else values.put(column,(String)value);}
            db.insertWithOnConflict(table,null,values,SQLiteDatabase.CONFLICT_REPLACE);
        }
    }
    JSONObject resolve(String key,JSONArray expected,Object selected)throws IOException,JSONException {
        capture();SyncProtocol.State state=load();if(state==null)throw SyncProtocol.invalid("no baseline");JSONArray alternatives=state.conflicts().optJSONArray(key);if(alternatives==null)throw SyncProtocol.invalid("conflict changed");
        if(expected.length()!=alternatives.length())throw SyncProtocol.invalid("review changed");for(int i=0;i<expected.length();i++)if(!SyncProtocol.canonical(expected.get(i)).equals(SyncProtocol.canonical(alternatives.getJSONObject(i).getJSONObject("id"))))throw SyncProtocol.invalid("review changed");
        JSONObject changes=new JSONObject().put(key,selected);
        // Naming provenance follows the selected title, without a duplicate prompt.
        if(key.startsWith("documents/")&&key.endsWith("/title")&&selected instanceof JSONObject) {
            String document=new JSONArray(key.substring("documents/".length(),key.length()-"/title".length())).getString(0),title=((JSONObject)selected).getString("title");
            JSONObject conflicts=state.conflicts();for(String field:SyncProtocol.keys(conflicts))if(field.startsWith("choices/")){
                JSONArray values=conflicts.getJSONArray(field);for(int i=0;i<values.length();i++){JSONObject choice=values.getJSONObject(i).optJSONObject("value");if(choice!=null&&document.equals(choice.optString("document_id"))&&choice.optString("field").equals("title")&&choice.optString("value").equals(title)){changes.put(field,choice);break;}}
            }
        }
        if(key.startsWith("filing_review/")) {
            String doc=key.substring("filing_review/".length());JSONArray chosen=(JSONArray)selected;JSONObject values=state.values();
            for(String field:SyncProtocol.keys(values))if(field.startsWith("memberships/")) {
                JSONArray pair=new JSONArray(field.substring("memberships/".length()));if(doc.equals(pair.getString(0)))changes.put(field,contains(chosen,pair.getString(1))?new JSONObject().put("document_id",doc).put("stack_id",pair.getString(1)):JSONObject.NULL);
            }
            for(int i=0;i<chosen.length();i++){String stack=chosen.getString(i);changes.put("memberships/"+SyncProtocol.canonical(new JSONArray().put(doc).put(stack)),new JSONObject().put("document_id",doc).put("stack_id",stack));}
        }
        state.local(replica(),changes,new JSONObject().put("kind","conflict.resolve").put("reviewed",expected));install(state,null);return status();
    }
    private static boolean contains(JSONArray array,String value)throws JSONException {for(int i=0;i<array.length();i++)if(value.equals(array.getString(i)))return true;return false;}
    void receipt(String peer,TreeMap<String,Long> acknowledged)throws JSONException {
        ContentValues row=new ContentValues();row.put("peer",peer);row.put("frontier",SyncProtocol.json(acknowledged).toString());db.insertWithOnConflict("sync_receipts",null,row,SQLiteDatabase.CONFLICT_REPLACE);
    }
    void savePending(SyncProtocol.State state)throws IOException,JSONException {
        String text=state.saved().toString();ContentValues row=new ContentValues();row.put("state",text);db.update("sync_state",row,"singleton=1",null);remember(text,state);
    }
    /** The pinned peer advertised exactly our committed frontier, so it durably holds every operation we do. */
    void acknowledgeEqual(String peer,TreeMap<String,Long> frontier)throws IOException,JSONException {
        SyncProtocol.State state=load();if(state==null||!state.members.contains(peer)||!state.frontier.equals(frontier))throw SyncProtocol.invalid("peer frontier differs");
        TreeMap<String,Long> merged=new TreeMap<>(frontier);for(Map.Entry<String,Long> prior:receipt(peer).entrySet())merged.merge(prior.getKey(),prior.getValue(),Math::max);receipt(peer,merged);
    }
    TreeMap<String,Long> receipt(String peer)throws IOException,JSONException {
        try(Cursor row=db.rawQuery("SELECT frontier FROM sync_receipts WHERE peer=?",new String[]{peer})){return row.moveToFirst()?SyncProtocol.frontier(ArchiveJson.read(row.getString(0))):new TreeMap<>();}
    }
}
