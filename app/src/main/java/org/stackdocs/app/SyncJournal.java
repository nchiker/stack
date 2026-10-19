package org.stackdocs.app;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;

/** Replays committed raw row transitions into causal operations in database order. */
final class SyncJournal {
    private SyncJournal() {}
    static final class Replay {
        final JSONObject projection; final long cursor; final int operations;
        Replay(JSONObject projection,long cursor,int operations){this.projection=projection;this.cursor=cursor;this.operations=operations;}
    }
    static Replay capture(SQLiteDatabase db,SyncProtocol.State state,String replica,JSONObject previous,long cursor,JSONObject current)throws IOException,JSONException {
        JSONObject manifest=SyncGraph.project(previous),projected=previous;
        long last=cursor;int emitted=0;
        try(Cursor events=db.rawQuery("SELECT sequence,table_name,action FROM sync_change WHERE sequence>? ORDER BY sequence",new String[]{Long.toString(cursor)})) {
            while(events.moveToNext()) {
                long sequence=events.getLong(0);String source=events.getString(1),action=events.getString(2),table=table(source);
                JSONObject row=raw(db,source,sequence,action.equals("DELETE")?"old":"new");
                JSONObject candidate=new JSONObject(manifest.toString());replace(candidate.getJSONArray(table),row,table,action.equals("DELETE"));
                if(table.equals("documents")) {
                    String id=row.getString("id");JSONArray files=new JSONArray(),old=candidate.getJSONArray("files"),available=current.getJSONArray("files");
                    for(int i=0;i<old.length();i++)if(!id.equals(owner(old.getJSONObject(i).getString("path"))))files.put(old.getJSONObject(i));
                    if(!action.equals("DELETE"))for(int i=0;i<available.length();i++)if(id.equals(owner(available.getJSONObject(i).getString("path"))))files.put(available.getJSONObject(i));
                    candidate.put("files",files);
                }
                SyncGraph.refreshEvidence(candidate);
                try {
                    LibraryArchive.validate(candidate);
                    JSONObject cells=SyncGraph.cells(candidate),changes=SyncGraph.diff(projected,cells);
                    // Removed pages remain in causal history. Their whole-order decision
                    // controls visibility, so retained originals survive both alternatives.
                    if(source.equals("pages") && action.equals("DELETE"))for(String key:SyncProtocol.keys(changes))if(key.startsWith("pages/")&&changes.isNull(key))changes.remove(key);
                    if(changes.length()>0) {state.local(replica,changes,new JSONObject().put("kind","android.raw-row").put("table",source).put("sequence",sequence));emitted++;}
                    projected=cells;
                } catch(IOException invalidIntermediate) {
                    // An incomplete multi-row transaction may temporarily violate the
                    // portable graph. Keep replaying raw rows until it is valid.
                }
                manifest=candidate;last=sequence;
            }
        }
        JSONObject finalCells=SyncGraph.cells(current),changes=SyncGraph.diff(projected,finalCells);
        if(changes.length()>0) {state.local(replica,changes,new JSONObject().put("kind","android.raw-row.final"));emitted++;}
        return new Replay(finalCells,last,emitted);
    }
    private static String table(String source)throws IOException {
        switch(source){case "document_stacks":return "memberships";case "suggestion_choices":return "choices";
            case "documents":case "pages":case "stacks":case "suggestions":case "details":case "tags":case "document_tags":return source;
            default:throw SyncProtocol.invalid("unknown journal table");}
    }
    private static String[] columns(String source) {
        switch(source){case "documents":return LibraryArchive.DOC;case "pages":return LibraryArchive.PAGE;case "stacks":return LibraryArchive.STACK;
            case "document_stacks":return LibraryArchive.MEMBER;case "suggestions":return LibraryArchive.SUGGESTION;case "suggestion_choices":return LibraryArchive.CHOICE;
            case "details":return LibraryArchive.DETAIL;case "tags":return LibraryArchive.TAG;case "document_tags":return LibraryArchive.DOCUMENT_TAG;
            default:throw new IllegalArgumentException(source);}
    }
    private static JSONObject raw(SQLiteDatabase db,String source,long sequence,String side)throws IOException,JSONException {
        String[] names=columns(source);
        try(Cursor cursor=db.query("sync_raw_"+source,names,"sequence=? AND side=?",new String[]{Long.toString(sequence),side},null,null,null)) {
            if(!cursor.moveToFirst())throw SyncProtocol.invalid("missing raw row");JSONObject row=new JSONObject();
            for(int i=0;i<names.length;i++)row.put(names[i],cursor.isNull(i)?JSONObject.NULL:cursor.getType(i)==Cursor.FIELD_TYPE_INTEGER?cursor.getLong(i):cursor.getString(i));
            if(cursor.moveToNext())throw SyncProtocol.invalid("duplicate raw row");return row;
        }
    }
    private static void replace(JSONArray rows,JSONObject row,String table,boolean remove)throws JSONException,IOException {
        String id=identity(table,row);for(int i=rows.length()-1;i>=0;i--)if(id.equals(identity(table,rows.getJSONObject(i))))rows.remove(i);
        if(!remove)rows.put(row);
    }
    private static String identity(String table,JSONObject row)throws JSONException,IOException {
        JSONArray id=new JSONArray();String[] fields;
        switch(table){case "memberships":fields=new String[]{"document_id","stack_id"};break;case "choices":fields=new String[]{"document_id","evidence","field"};break;
            case "details":case "document_tags":fields=new String[]{"document_id","field_id"};break;case "suggestions":fields=new String[]{"document_id"};break;
            default:fields=new String[]{"id"};}
        for(String field:fields)id.put(row.get(field));return SyncProtocol.canonical(id);
    }
    private static String owner(String path)throws IOException {String[] parts=path.split("/");if(parts.length!=3||!parts[0].equals("originals"))throw SyncProtocol.invalid("bad original path");return parts[1];}
}
