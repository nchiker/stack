package org.stackdocs.app;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Replays committed raw row transitions into causal operations in database order. */
final class SyncJournal {
    private SyncJournal() {}
    static final class Replay {
        final JSONObject projection; final long cursor; final int operations;
        Replay(JSONObject projection,long cursor,int operations){this.projection=projection;this.cursor=cursor;this.operations=operations;}
    }
    static Replay capture(SQLiteDatabase db,SyncProtocol.State state,String replica,JSONObject previous,long cursor,JSONObject current,SyncExchange.Control control)throws IOException,JSONException {
        control.check();
        JSONObject manifest=SyncGraph.project(previous),projected=previous;
        long last=cursor;int emitted=0;Set<String> fullFiling=new HashSet<>(),pageOrders=new HashSet<>();boolean catalogOrder=false,manifestValid=true;
        try(Cursor events=db.rawQuery("SELECT sequence,table_name,action FROM sync_change WHERE sequence>? ORDER BY sequence",new String[]{Long.toString(cursor)})) {
            while(events.moveToNext()) {
                control.check();
                long sequence=events.getLong(0);String source=events.getString(1),action=events.getString(2),table=table(source);
                JSONObject row=raw(db,source,sequence,action.equals("DELETE")?"old":"new");
                JSONObject old=action.equals("UPDATE")?raw(db,source,sequence,"old"):null;
                // Local queue invalidations change only this independent portable
                // row. Preserve every causal operation without copying, hashing
                // and validating the entire document library for every queue bump.
                if(manifestValid&&source.equals("suggestions")&&old!=null&&queueOnly(old,row)) {
                    JSONArray rows=manifest.getJSONArray("suggestions");String id=row.getString("document_id"),key="suggestions/"+SyncProtocol.canonical(new JSONArray().put(id));
                    JSONObject previousRow=null;for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("document_id"))){previousRow=rows.getJSONObject(i);break;}
                    if(previousRow!=null&&SyncProtocol.canonical(previousRow).equals(SyncProtocol.canonical(old))) {
                        JSONObject changes=new JSONObject();if(!projected.has(key)||!SyncProtocol.canonical(projected.get(key)).equals(SyncProtocol.canonical(row)))changes.put(key,row);
                        if(changes.length()>0){SyncGraph.validateChanges(changes);state.local(replica,changes,new JSONObject().put("kind","android.raw-row").put("table",source).put("sequence",sequence));emitted++;}
                        replace(rows,row,"suggestions",false);projected.put(key,row);last=sequence;continue;
                    }
                }
                if(source.equals("documents")&&old!=null&&old.getLong("filing_decision")!=row.getLong("filing_decision"))fullFiling.add(row.getString("id"));
                if(source.equals("stacks")&&old!=null&&old.getLong("sort_order")!=row.getLong("sort_order"))catalogOrder=true;
                if(source.equals("pages")&&(action.equals("DELETE")||old!=null&&old.getLong("position")!=row.getLong("position")))pageOrders.add(row.getString("document_id"));
                JSONObject candidate=new JSONObject(manifest.toString());replace(candidate.getJSONArray(table),row,table,action.equals("DELETE"));
                if(table.equals("documents")) {
                    String id=row.getString("id");JSONArray files=new JSONArray(),previousFiles=candidate.getJSONArray("files"),available=current.getJSONArray("files");
                    for(int i=0;i<previousFiles.length();i++)if(!id.equals(owner(previousFiles.getJSONObject(i).getString("path"))))files.put(previousFiles.getJSONObject(i));
                    if(!action.equals("DELETE"))for(int i=0;i<available.length();i++)if(id.equals(owner(available.getJSONObject(i).getString("path"))))files.put(available.getJSONObject(i));
                    candidate.put("files",files);
                }
                SyncGraph.normalize(candidate);SyncGraph.refreshEvidence(candidate);
                try {
                    LibraryArchive.validate(candidate);
                    JSONObject cells=SyncGraph.cells(candidate),changes=SyncGraph.diff(projected,cells);
                    // Removed pages remain in causal history. Their whole-order decision
                    // controls visibility, so retained originals survive both alternatives.
                    if(source.equals("pages") && action.equals("DELETE"))for(String key:SyncProtocol.keys(changes))if(key.startsWith("pages/")&&changes.isNull(key))changes.remove(key);
                    if(source.equals("document_stacks"))for(String key:SyncProtocol.keys(changes))if(key.startsWith("filing_review/"))changes.remove(key);
                    if(catalogOrder){changes.put("catalog_order/stacks",cells.get("catalog_order/stacks"));catalogOrder=false;}
                    for(String id:pageOrders)if(cells.has("page_order/"+id))changes.put("page_order/"+id,cells.get("page_order/"+id));pageOrders.clear();
                    for(String id:fullFiling)if(cells.has("filing_review/"+id)) {
                        changes.put("filing_review/"+id,cells.get("filing_review/"+id));
                        for(String key:state.registers.keySet())if(key.startsWith("memberships/")){
                            JSONArray pair=new JSONArray(key.substring("memberships/".length()));
                            if(pair.length()==2&&id.equals(pair.getString(0)))changes.put(key,cells.has(key)?cells.get(key):JSONObject.NULL);
                        }
                        for(String key:SyncProtocol.keys(cells))if(key.startsWith("memberships/")){
                            JSONArray pair=new JSONArray(key.substring("memberships/".length()));if(pair.length()==2&&id.equals(pair.getString(0)))changes.put(key,cells.get(key));
                        }
                    }fullFiling.clear();
                    if(changes.length()>0) {
                        boolean textOnly=true;for(String key:SyncProtocol.keys(changes))if(!key.startsWith("pages/")||!key.endsWith("/text")){textOnly=false;break;}
                        state.local(replica,changes,new JSONObject().put("kind",textOnly?"page.text.commit":"android.raw-row").put("table",source).put("sequence",sequence));emitted++;
                    }
                    projected=cells;
                    manifestValid=true;
                } catch(IOException invalidIntermediate) {
                    manifestValid=false;
                    // An incomplete multi-row transaction may temporarily violate the
                    // portable graph. Keep replaying raw rows until it is valid.
                }
                manifest=candidate;last=sequence;
            }
        }
        control.check();
        JSONObject finalCells=SyncGraph.cells(current),changes=SyncGraph.diff(projected,finalCells);
        if(catalogOrder)changes.put("catalog_order/stacks",finalCells.get("catalog_order/stacks"));
        for(String id:pageOrders)if(finalCells.has("page_order/"+id))changes.put("page_order/"+id,finalCells.get("page_order/"+id));
        for(String id:fullFiling)if(finalCells.has("filing_review/"+id)){
            changes.put("filing_review/"+id,finalCells.get("filing_review/"+id));
            for(String key:state.registers.keySet())if(key.startsWith("memberships/")){
                JSONArray pair=new JSONArray(key.substring("memberships/".length()));if(pair.length()==2&&id.equals(pair.getString(0)))changes.put(key,finalCells.has(key)?finalCells.get(key):JSONObject.NULL);
            }
        }
        if(changes.length()>0) {state.local(replica,changes,new JSONObject().put("kind","android.raw-row.final"));emitted++;}
        return new Replay(finalCells,last,emitted);
    }
    private static boolean queueOnly(JSONObject old,JSONObject row)throws IOException,JSONException {
        if(!row.getString("status").equals("pending")||row.getLong("generation")<0||row.getLong("generation")>SyncProtocol.MAX_SEQUENCE)return false;
        for(String field:SyncProtocol.keys(old))if(!field.equals("generation")&&!field.equals("status")&&!SyncProtocol.canonical(old.get(field)).equals(SyncProtocol.canonical(row.get(field))))return false;
        return true;
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
