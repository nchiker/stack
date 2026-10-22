package org.stackdocs.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reset only at cold startup, before any controller is allowed to use storage.
 * Old data is quarantined until the replacement database passes integrity checks. */
final class LibraryReset {
    static final String ERASE="erase",CONNECTION="connection";
    private static final String[] FILES={"consolidation_recovery","documents","incoming","archive_operations","batch_sources","photo_staging","sync_stage","sync_blobs","scan_draft","pairing/policy.json","pairing/policy.json.bak","pairing/policy.json.new","pairing/names.json","pairing/names.json.bak","pairing/names.json.new"};
    interface Probe {void reached(String boundary)throws Exception;}
    Probe probe=boundary->{};
    private final Context context;private final AtomicFile request;private final File quarantine;
    LibraryReset(Context context){this.context=context;request=new AtomicFile(new File(real(context.getNoBackupFilesDir()),"stack-library-reset.json"));quarantine=new File(real(context.getNoBackupFilesDir()),"stack-library-reset-quarantine");}
    private static File real(File dir){try{return dir.getCanonicalFile();}catch(IOException error){throw new IllegalStateException("Storage path is unavailable",error);}}
    boolean pending(){return request.getBaseFile().exists()||new File(request.getBaseFile()+".bak").exists();}
    private List<File> owned()throws Exception{List<File> files=new ArrayList<>();for(String suffix:new String[]{"","-wal","-shm","-journal"})files.add(new File(context.getDatabasePath("library.db").getParentFile().getCanonicalFile(),"library.db"+suffix));for(String name:FILES)files.add(new File(context.getFilesDir().getCanonicalFile(),name));for(String name:new String[]{"pdf_plans","preview_tiles"})files.add(new File(context.getCacheDir().getCanonicalFile(),name));return files;}
    static void safe(File file)throws Exception {
        android.system.StructStat info;try{info=Os.lstat(file.getPath());}catch(android.system.ErrnoException error){if(error.errno==OsConstants.ENOENT)return;throw error;}
        if(OsConstants.S_ISLNK(info.st_mode)||(!OsConstants.S_ISREG(info.st_mode)&&!OsConstants.S_ISDIR(info.st_mode)))throw new IOException("Reset refuses symbolic links and special files.");
        // Check every ancestor too; an owned leaf must not traverse a link.
        File parent=file.getAbsoluteFile().getParentFile();if(parent!=null&&!parent.getCanonicalFile().equals(parent))throw new IOException("Reset refuses linked directories.");
        if(OsConstants.S_ISDIR(info.st_mode)){File[] children=file.listFiles();if(children==null)throw new IOException("Could not inspect reset data.");for(File child:children)safe(child);}
    }
    private static void remove(File file)throws Exception {safe(file);if(!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("Could not read reset data.");for(File child:children)remove(child);}if(!file.delete())throw new IOException("Could not remove reset data.");}
    private static void sync(File dir)throws Exception {java.io.FileDescriptor fd=Os.open(dir.getPath(),OsConstants.O_RDONLY,0);try{Os.fsync(fd);}finally{Os.close(fd);}}
    private void save(JSONObject value)throws Exception {
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);FileOutputStream output=request.startWrite();boolean finished=false;
        try{output.write(bytes);output.getFD().sync();request.finishWrite(output);finished=true;if(!Arrays.equals(bytes,request.readFully()))throw new IOException("Could not publish reset journal.");sync(context.getNoBackupFilesDir());}
        catch(Exception error){if(!finished)request.failWrite(output);throw error;}
    }

    private JSONObject read()throws Exception {byte[] bytes=request.readFully();if(bytes.length>4096)throw new IOException("Reset request is invalid.");JSONObject value=ArchiveJson.read(new String(bytes,StandardCharsets.UTF_8));SyncProtocol.exact(value,"version,mode,phase,replica,originals");String phase=value.getString("phase"),mode=value.getString("mode");if(value.getInt("version")!=1||(!mode.equals(ERASE)&&!mode.equals(CONNECTION))||!Arrays.asList("requested","moving","preparing","committed","rolling_back").contains(phase))throw new IOException("Reset request version or phase is invalid.");SyncProtocol.uuid(value.getString("replica"));org.json.JSONArray originals=value.getJSONArray("originals");Set<Integer> indices=new HashSet<>();for(int i=0;i<originals.length();i++){int index=originals.getInt(i);if(index<0||index>=owned().size()||!indices.add(index))throw new IOException("Reset original-path inventory is invalid.");}return value;}
    void schedule(String mode)throws Exception {
        if(!mode.equals(ERASE)&&!mode.equals(CONNECTION))throw new IOException("Invalid reset choice.");if(pending()||quarantine.exists())throw new IOException("A reset is already pending. Close and reopen Stack.");List<File> files=owned();org.json.JSONArray originals=new org.json.JSONArray();for(int i=0;i<files.size();i++){safe(files.get(i));if(files.get(i).exists())originals.put(i);}
        save(new JSONObject().put("version",1).put("mode",mode).put("phase","requested").put("replica",UUID.randomUUID().toString()).put("originals",originals));
    }
    /** Called from the first app.io task in the new process. Other storage work
     * remains queued behind it. Failure blocks the repository, never opens an
     * accidentally half-reset library. */
    void initialize(LibraryRepository library)throws Exception {
        if(!pending()){library.initialize();return;}JSONObject intent=read();
        if(intent.getString("mode").equals(CONNECTION)){library.initialize();library.database();resetConnection(library.database(),intent.getString("replica"));clearPairing();completed(CONNECTION);request.delete();sync(context.getNoBackupFilesDir());return;}
        String phase=intent.getString("phase");List<File> files=owned();if(phase.equals("rolling_back")){rollback(files,intent);library.initialize();return;}if(phase.equals("committed")){prepareEraseCompletion();remove(quarantine);completed(ERASE);request.delete();library.initialize();return;}
        if(!quarantine.exists()&&!quarantine.mkdirs())throw new IOException("Could not prepare reset recovery.");safe(quarantine);
        boolean committing=false;
        try {
            if(phase.equals("requested")||phase.equals("moving")){
                for(File file:files)safe(file);if(phase.equals("requested")){org.json.JSONArray originals=new org.json.JSONArray();for(int i=0;i<files.size();i++)if(files.get(i).exists())originals.put(i);intent.put("originals",originals);}intent.put("phase","moving");save(intent);
                for(int i=0;i<files.size();i++){File file=files.get(i),saved=new File(quarantine,String.valueOf(i));if(file.exists()){if(saved.exists())throw new IOException("Reset found conflicting recovery files.");if(!file.renameTo(saved))throw new IOException("Could not prepare reset. Original data remains recoverable.");sync(file.getParentFile());sync(quarantine);probe.reached("moved-"+i);}}
                intent.put("phase","preparing");save(intent);probe.reached("preparing");
            }else for(File file:files)remove(file);
            library.initialize();SQLiteDatabase db=library.database();
            try(android.database.Cursor check=db.rawQuery("PRAGMA quick_check(1)",null)){if(!check.moveToFirst()||!check.getString(0).equals("ok"))throw new IOException("Replacement database integrity check failed.");}
            try(android.database.Cursor check=db.rawQuery("SELECT count(*) FROM documents",null)){check.moveToFirst();if(check.getLong(0)!=0)throw new IOException("Replacement library is not empty.");}
            probe.reached("initialized");prepareEraseCompletion();committing=true;intent.put("phase","committed");save(intent);probe.reached("committed");
        }catch(Exception failure){library.closeForReset();if(!committing)try{intent.put("phase","rolling_back");save(intent);probe.reached("rolling-back");rollback(files,intent);}catch(Exception rollback){failure.addSuppressed(rollback);}throw failure;}
        // After the durable commit, cleanup is retried on next startup if needed.
        remove(quarantine);completed(ERASE);request.delete();sync(context.getNoBackupFilesDir());
    }
    private void rollback(List<File> files,JSONObject intent)throws Exception {Set<Integer> originals=new HashSet<>();org.json.JSONArray inventory=intent.getJSONArray("originals");for(int i=0;i<inventory.length();i++)originals.add(inventory.getInt(i));for(int i=0;i<files.size();i++){if(!originals.contains(i))remove(files.get(i));File saved=new File(quarantine,String.valueOf(i));if(saved.exists()){File target=files.get(i);remove(target);File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Could not recover original library.");if(!saved.renameTo(target))throw new IOException("Original library recovery needs another restart.");sync(parent);sync(quarantine);probe.reached("restored-"+i);}}remove(quarantine);request.delete();sync(context.getNoBackupFilesDir());}
    private void clearPairing()throws Exception {for(String name:FILES)if(name.startsWith("pairing/"))remove(new File(context.getFilesDir().getCanonicalFile(),name));File dir=new File(context.getFilesDir(),"pairing");if(dir.exists())sync(dir);if(!context.getSharedPreferences("SyncActivity",0).edit().clear().commit())throw new IOException("Could not clear old connection preferences.");}
    private static void resetConnection(SQLiteDatabase db,String replica)throws Exception {db.beginTransaction();try{
        db.execSQL("UPDATE sync_control SET enabled=0,applying=0 WHERE singleton=1");for(String table:new String[]{"sync_state","sync_receipts","sync_change","sync_meta","sync_raw_documents","sync_raw_pages","sync_raw_stacks","sync_raw_document_stacks","sync_raw_suggestions","sync_raw_suggestion_choices","sync_raw_details","sync_raw_tags","sync_raw_document_tags"})db.execSQL("DELETE FROM "+table);
        db.execSQL("INSERT INTO sync_meta(key,value) VALUES('replica_id',?)",new Object[]{replica});db.setTransactionSuccessful();
    }finally{db.endTransaction();}}
    private void prepareEraseCompletion()throws IOException {
        if(!context.getSharedPreferences("local_policy",0).edit().putBoolean("photos",false).putLong("photo_epoch",context.getSharedPreferences("local_policy",0).getLong("photo_epoch",0)+1).commit())throw new IOException("Could not pause photo import; reset remains recoverable.");
        if(!context.getSharedPreferences("SyncActivity",0).edit().clear().commit())throw new IOException("Could not reset connection preferences.");
        if(!context.getSharedPreferences("navigation",0).edit().clear().commit())throw new IOException("Could not reset library navigation.");
    }
    private void completed(String mode)throws IOException {if(!context.getSharedPreferences("library_reset",0).edit().putString("completed",mode).commit())throw new IOException("Could not save reset completion; reopen Stack to finish.");}
}
