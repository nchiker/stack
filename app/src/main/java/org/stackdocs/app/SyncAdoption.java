package org.stackdocs.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Pure, source-matched identity review; no database or file mutations. */
final class SyncAdoption {
    static final String[] IDENTITIES={"documents","pages","stacks","tags","scans"};
    private SyncAdoption() {}
    static String digest(String text) {
        try{return SyncGraph.hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    static String stable(String group,String name)throws IOException {
        try {
            UUID namespace=UUID.fromString(group);MessageDigest digest=MessageDigest.getInstance("SHA-1");
            digest.update(ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits()).putLong(namespace.getLeastSignificantBits()).array());
            byte[] bytes=digest.digest(name.getBytes(StandardCharsets.UTF_8));bytes[6]=(byte)((bytes[6]&15)|80);bytes[8]=(byte)((bytes[8]&63)|128);
            ByteBuffer id=ByteBuffer.wrap(bytes);return new UUID(id.getLong(),id.getLong()).toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    static JSONObject review(JSONObject header,List<SyncProtocol.Operation> operations,JSONObject local,String replica)throws IOException,JSONException {
        TreeSet<String> members=new TreeSet<>(Arrays.asList(replica,header.getString("sender")));
        for(SyncProtocol.Operation op:operations){members.add(op.replica);members.addAll(op.context.keySet());}
        SyncProtocol.State incoming=new SyncProtocol.State(header.getString("library"),members);incoming.receive(operations);
        if(incoming.history.isEmpty()||!incoming.pending.isEmpty())throw SyncProtocol.invalid("review requires a complete baseline");
        JSONObject source=SyncGraph.project(incoming.values()),maps=new JSONObject(),names=new JSONObject();
        for(String table:IDENTITIES){JSONObject map=new JSONObject();for(JSONObject row:entities(local,table)){
            String id=row.getString("id");map.put(id,stable(incoming.library,"adopt:"+replica+":"+table+":"+id));
        }maps.put(table,map);}
        for(String table:new String[]{"stacks","tags"}){
            JSONObject chosen=new JSONObject();Set<String> used=new HashSet<>();for(JSONObject row:entities(source,table))used.add(row.getString("name").toLowerCase(Locale.ROOT));
            for(JSONObject row:entities(local,table)){
                String original=row.getString("name"),name=original;int count=0;
                while(table.equals("stacks")&&used.contains(name.toLowerCase(Locale.ROOT))){count++;int end=original.offsetByCodePoints(0,Math.min(60,original.codePointCount(0,original.length())));name=original.substring(0,end)+" (adopted "+count+")";}
                used.add(name.toLowerCase(Locale.ROOT));chosen.put(row.getString("id"),new JSONObject().put("original",original).put("chosen",name));
            }names.put(table,chosen);
        }
        JSONObject normalized=SyncProtocol.copy(local);normalized.put("created_at",0);
        return new JSONObject().put("version",1).put("library",incoming.library).put("sender",header.getString("sender"))
            .put("members",new JSONArray(members)).put("source_sha256",digest(SyncProtocol.canonical(header)))
            .put("local_sha256",digest(SyncProtocol.canonical(normalized))).put("mappings",maps).put("catalog_names",names)
            .put("source_entities",entityLists(source)).put("local_entities",entityLists(local))
            .put("local_counts",counts(local)).put("source_counts",counts(source));
    }
    static JSONObject merge(JSONObject review,JSONObject local)throws Exception {
        JSONObject source=review.getJSONObject("source_entities"),maps=review.getJSONObject("mappings");
        for(String table:IDENTITIES)for(String id:SyncProtocol.keys(maps.getJSONObject(table))) {
            JSONObject own=find(local,table,id),match=null;
            JSONArray incoming=source.getJSONArray(table);for(int i=0;i<incoming.length();i++){JSONObject remote=incoming.getJSONObject(i);if(id.equals(remote.optString("id"))||((table.equals("stacks")||table.equals("tags"))&&own!=null&&own.getString("name").trim().equalsIgnoreCase(remote.optString("name").trim()))){match=remote;break;}}
            if(match!=null){maps.getJSONObject(table).put(id,match.getString("id"));if(table.equals("stacks")||table.equals("tags"))review.getJSONObject("catalog_names").getJSONObject(table).getJSONObject(id).put("chosen",match.getString("name"));}
        }
        review.put("automatic_merge",true);return review;
    }
    static void revalidate(JSONObject current,JSONObject reviewed)throws IOException,JSONException {
        for(String field:new String[]{"version","library","sender","source_sha256","local_sha256","local_counts","source_counts"})
            if(!SyncProtocol.canonical(current.get(field)).equals(SyncProtocol.canonical(reviewed.get(field))))throw SyncProtocol.invalid("library or exchange changed after adoption review; review again");
    }
    static void addLocal(SyncProtocol.State state,JSONObject local,JSONObject review,String replica)throws IOException,JSONException {
        JSONObject source=SyncGraph.project(state.values()),maps=review.getJSONObject("mappings");SyncProtocol.exact(maps,"documents,pages,stacks,tags,scans");
        for(String table:IDENTITIES){JSONObject map=maps.getJSONObject(table);Set<String> expected=new HashSet<>(),targets=new HashSet<>();for(JSONObject row:entities(local,table))expected.add(row.getString("id"));
            if(!expected.equals(new HashSet<>(SyncProtocol.keys(map))))throw SyncProtocol.invalid("review must map every local identity exactly once");
            for(String id:SyncProtocol.keys(map)){
                Object value=map.get(id);if(!(value instanceof String)||!targets.add((String)value))throw SyncProtocol.invalid("reviewed mapping must be one to one");
                if(find(source,table,(String)value)==null)SyncProtocol.uuid((String)value);
            }
        }
        for(JSONObject page:entities(local,"pages")){
            JSONObject existing=find(source,"pages",maps.getJSONObject("pages").getString(page.getString("id")));
            if(existing!=null&&!existing.getString("document_id").equals(maps.getJSONObject("documents").getString(page.getString("document_id"))))throw SyncProtocol.invalid("matched page belongs to another reviewed document");
        }
        JSONObject remapped=remap(local,maps);
        for(String table:new String[]{"stacks","tags"})for(JSONObject row:entities(remapped,table)){
            String old=row.getString("id"),name=review.getJSONObject("catalog_names").getJSONObject(table).getJSONObject(old).getString("chosen");
            if(!LibraryRepository.cleanName(name).equals(name))throw SyncProtocol.invalid("reviewed catalog name is not normalized");
            row.put("name",name).put("id",maps.getJSONObject(table).getString(old));
        }
        LibraryArchive.validate(remapped);JSONObject localCells=SyncGraph.cells(remapped),sourceCells=state.values();boolean distinctCatalog=false;
        if(review.optBoolean("automatic_merge"))for(String table:new String[]{"stacks","tags"})for(String old:SyncProtocol.keys(maps.getJSONObject(table))){String target=maps.getJSONObject(table).getString(old);if(find(source,table,target)!=null){String base=table+"/"+SyncProtocol.canonical(new JSONArray().put(target));for(String key:SyncProtocol.keys(sourceCells))if(key.equals(base)||key.startsWith(base+"/"))localCells.put(key,sourceCells.get(key));}}

        for(String id:SyncProtocol.keys(maps.getJSONObject("stacks")))if(find(source,"stacks",maps.getJSONObject("stacks").getString(id))==null)distinctCatalog=true;
        Object reviewedOrder=null;
        if(distinctCatalog){JSONArray order=new JSONArray(sourceCells.getJSONArray("catalog_order/stacks").toString()),localOrder=localCells.getJSONArray("catalog_order/stacks");Set<String> included=new HashSet<>();for(int i=0;i<order.length();i++)included.add(order.getString(i));for(int i=0;i<localOrder.length();i++)if(included.add(localOrder.getString(i)))order.put(localOrder.getString(i));localCells.put("catalog_order/stacks",order);}
        JSONObject changes=new JSONObject();for(String key:SyncProtocol.keys(localCells))if(!sourceCells.has(key)||!SyncProtocol.canonical(localCells.get(key)).equals(SyncProtocol.canonical(sourceCells.get(key))))changes.put(key,localCells.get(key));
        if(distinctCatalog)reviewedOrder=changes.remove("catalog_order/stacks");
        if(changes.length()>0){JSONObject operation=new JSONObject().put("version",1).put("library",state.library).put("epoch",1)
            .put("id",new JSONObject().put("replica",replica).put("sequence",1)).put("context",new JSONObject()).put("changes",changes)
            .put("evidence",new JSONObject().put("kind","baseline.adopt.local").put("graph_model",SyncGraph.MODEL).put("review",review));
            SyncGraph.validateChanges(changes);state.receive(java.util.Collections.singletonList(new SyncProtocol.Operation(operation)));
        }
        if(reviewedOrder!=null)state.local(replica,new JSONObject().put("catalog_order/stacks",reviewedOrder),new JSONObject().put("kind","baseline.adopt.catalog").put("review",review));
        try{SyncGraph.project(state.values());}catch(IOException|JSONException invalid){throw SyncProtocol.invalid("reviewed identities and names do not form a valid combined library; review matching organizations, catalog names and page owners");}
    }
    private static JSONObject remap(JSONObject local,JSONObject maps)throws IOException,JSONException {
        JSONObject graph=SyncProtocol.copy(local),docs=maps.getJSONObject("documents"),pages=maps.getJSONObject("pages"),stacks=maps.getJSONObject("stacks");
        for(JSONObject doc:entities(graph,"documents")){doc.put("id",docs.getString(doc.getString("id")));if(!doc.isNull("source_scan_id"))doc.put("source_scan_id",maps.getJSONObject("scans").getString(doc.getString("source_scan_id")));}
        for(String table:new String[]{"pages","memberships","suggestions","choices","details","document_tags"})for(JSONObject row:entities(graph,table)){
            row.put("document_id",docs.getString(row.getString("document_id")));
            if(table.equals("pages"))row.put("id",pages.getString(row.getString("id")));
            if(table.equals("memberships"))row.put("stack_id",stacks.getString(row.getString("stack_id")));
            if(table.equals("document_tags"))row.put("tag_id",maps.getJSONObject("tags").getString(row.getString("tag_id")));
            if(table.equals("details")){JSONObject evidence=ArchiveJson.read(row.getString("evidence"));String old=evidence.optString("page_id");if(pages.has(old)&&!old.equals(pages.getString(old))){evidence.put("page_id",pages.getString(old));row.put("evidence",evidence.toString());}}
            if(table.equals("choices")&&row.getString("field").equals("filing"))row.put("value",remapChoice(row.getString("value"),stacks));
        }
        for(JSONObject file:entities(graph,"files")){String[] path=file.getString("path").split("/");file.put("path","originals/"+docs.getString(path[1])+"/"+path[2]);}
        SyncGraph.refreshEvidence(graph);
        for(String old:SyncProtocol.keys(docs))for(JSONObject choice:entities(graph,"choices"))if(choice.getString("document_id").equals(docs.getString(old))&&choice.getString("evidence").equals(local.getJSONObject("decision_evidence").getString(old)))choice.put("evidence",graph.getJSONObject("decision_evidence").getString(docs.getString(old)));
        return graph;
    }
    private static String csv(String raw,JSONObject stacks)throws JSONException {List<String> out=new ArrayList<>();for(String id:raw.split(","))if(!id.isEmpty())out.add(stacks.has(id)?stacks.getString(id):id);return String.join(",",out);}
    private static String remapChoice(String raw,JSONObject stacks)throws IOException,JSONException {
        boolean changed=false;for(String id:SyncProtocol.keys(stacks))if(!id.equals(stacks.getString(id)))changed=true;if(!changed)return raw;
        if(!raw.startsWith("{"))return csv(raw,stacks);
        JSONObject value=ArchiveJson.read(raw);value.put("chosen",csv(value.optString("chosen"),stacks));JSONObject proposed=value.optJSONObject("proposed");
        if(proposed!=null&&proposed.optJSONObject("stacks")!=null){JSONObject previous=proposed.getJSONObject("stacks"),next=new JSONObject();for(String id:SyncProtocol.keys(previous))next.put(stacks.has(id)?stacks.getString(id):id,previous.get(id));proposed.put("stacks",next);}
        return value.toString();
    }
    private static JSONObject counts(JSONObject graph)throws JSONException {JSONObject out=new JSONObject();for(String table:SyncGraph.TABLES)out.put(table,graph.getJSONArray(table).length());return out;}
    private static JSONObject entityLists(JSONObject graph)throws JSONException {
        JSONObject out=new JSONObject();for(String table:IDENTITIES){JSONArray list=new JSONArray();for(JSONObject row:entities(graph,table)){
            JSONObject item=new JSONObject().put("id",row.getString("id"));
            if(table.equals("documents"))item.put("title",row.getString("title"));
            else if(table.equals("pages"))item.put("document_id",row.getString("document_id")).put("position",row.getInt("position"));
            else if(table.equals("scans"))item.put("document_id",row.getString("document_id"));
            else item.put("name",row.getString("name"));list.put(item);
        }out.put(table,list);}return out;
    }
    private static JSONObject find(JSONObject graph,String table,String id)throws JSONException {for(JSONObject row:entities(graph,table))if(row.getString("id").equals(id))return row;return null;}
    private static List<JSONObject> entities(JSONObject graph,String table)throws JSONException {
        List<JSONObject> out=new ArrayList<>();JSONArray rows=graph.getJSONArray(table.equals("scans")?"documents":table);
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(table.equals("scans")){if(!row.isNull("source_scan_id"))out.add(new JSONObject().put("id",row.getString("source_scan_id")).put("document_id",row.getString("id")));}else out.add(row);}
        return out;
    }
}
