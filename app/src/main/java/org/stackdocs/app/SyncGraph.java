package org.stackdocs.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The Android spelling of stack-sync graph model 2. Values are portable backup rows. */
final class SyncGraph {
    static final int MODEL=2;
    static final String[] TABLES={"documents","pages","stacks","memberships","suggestions","choices","details","tags","document_tags","files"};
    private SyncGraph() {}
    private static String[] ids(String table) {
        switch(table) {
            case "documents": case "pages": case "stacks": case "tags": return new String[]{"id"};
            case "files": return new String[]{"path"};
            case "suggestions": return new String[]{"document_id"};
            case "memberships": return new String[]{"document_id","stack_id"};
            case "choices": return new String[]{"document_id","evidence","field"};
            case "details": case "document_tags": return new String[]{"document_id","field_id"};
            default: throw new IllegalArgumentException(table);
        }
    }
    private static String key(String table,JSONObject row)throws JSONException,IOException {
        JSONArray values=new JSONArray();for(String field:ids(table))values.put(row.get(field));
        return table+"/"+SyncProtocol.canonical(values);
    }
    private static JSONObject take(JSONObject row,String... fields)throws JSONException {
        JSONObject out=new JSONObject();for(String field:fields)if(row.has(field)){out.put(field,row.get(field));row.remove(field);}return out;
    }
    private static void extend(JSONObject row,JSONObject more)throws JSONException {
        for(String key:SyncProtocol.keys(more))row.put(key,more.get(key));
    }
    private static JSONObject copy(JSONObject object)throws JSONException {return new JSONObject(object.toString());}
    static JSONObject cells(JSONObject manifest)throws IOException,JSONException {
        JSONObject out=new JSONObject();
        for(String table:TABLES) {
            JSONArray rows=manifest.getJSONArray(table);
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i),base=copy(row);String key=key(table,row);
                switch(table) {
                    case "documents":
                        out.put(key+"/title",take(base,"title","title_origin","title_user_edited","title_decision"));
                        out.put(key+"/notes",take(base,"notes","notes_revision"));base.remove("page_count");
                        for(String field:new String[]{"filing_origin","filing_decision","metadata_revision"})out.put(key+"/"+field,take(base,field));
                        String id=row.getString("id");JSONArray filing=new JSONArray(),order=new JSONArray();List<String> memberships=new ArrayList<>();
                        JSONArray all=manifest.getJSONArray("memberships");for(int m=0;m<all.length();m++)if(id.equals(all.getJSONObject(m).getString("document_id")))memberships.add(all.getJSONObject(m).getString("stack_id"));
                        Collections.sort(memberships);for(String stack:memberships)filing.put(stack);
                        List<JSONObject> pages=new ArrayList<>();JSONArray allPages=manifest.getJSONArray("pages");for(int p=0;p<allPages.length();p++)if(id.equals(allPages.getJSONObject(p).getString("document_id")))pages.add(allPages.getJSONObject(p));
                        pages.sort((a,b)->Integer.compare(a.optInt("position"),b.optInt("position")));for(JSONObject page:pages)order.put(page.getString("id"));
                        out.put("filing_review/"+id,filing).put("page_order/"+id,order);break;
                    case "pages":
                        JSONObject source=take(base,"id","document_id","source_file","source_page");base.remove("position");
                        JSONObject appearance=take(base,"rotation","crop","enhancement","flatten","content_revision");
                        JSONObject input=copy(source);extend(input,appearance);
                        out.put(key+"/text",new JSONObject().put("input",input).put("row",base));out.put(key+"/appearance",appearance);base=source;break;
                    case "stacks":
                        base.remove("sort_order");for(String field:new String[]{"name","color","visible"})out.put(key+"/"+field,take(base,field));break;
                }
                out.put(key,base);
            }
        }
        JSONArray catalog=new JSONArray();JSONArray stacks=manifest.getJSONArray("stacks");List<JSONObject> sorted=new ArrayList<>();for(int i=0;i<stacks.length();i++)sorted.add(stacks.getJSONObject(i));
        sorted.sort((a,b)->Integer.compare(a.optInt("sort_order"),b.optInt("sort_order")));for(JSONObject stack:sorted)catalog.put(stack.getString("id"));out.put("catalog_order/stacks",catalog);
        return out;
    }
    static JSONObject diff(JSONObject before,JSONObject after)throws JSONException,IOException {
        JSONObject changes=new JSONObject();for(String key:SyncProtocol.keys(after))if(!before.has(key)||!SyncProtocol.canonical(before.get(key)).equals(SyncProtocol.canonical(after.get(key))))changes.put(key,after.get(key));
        for(String key:SyncProtocol.keys(before))if(!after.has(key))changes.put(key,JSONObject.NULL);return changes;
    }
    static void validateChanges(JSONObject changes)throws IOException,JSONException {
        for(String cell:SyncProtocol.keys(changes)) {
            Object value=changes.get(cell);
            if(cell.startsWith("page_order/")||cell.startsWith("filing_review/")) {
                String document=cell.substring(cell.indexOf('/')+1);SyncProtocol.uuid(document);
                if(value!=JSONObject.NULL)validateIds(value,cell.startsWith("page_order/"));continue;
            }
            if(cell.equals("catalog_order/stacks")){if(value!=JSONObject.NULL)validateIds(value,false);continue;}
            int slash=cell.indexOf('/');if(slash<0)throw SyncProtocol.invalid("invalid graph identity");String table=cell.substring(0,slash),rest=cell.substring(slash+1),suffix=null;
            int boundary=rest.indexOf("]/");if(boundary>=0){suffix=rest.substring(boundary+2);rest=rest.substring(0,boundary+1);}
            JSONArray ids;try{ids=new JSONArray(rest);}catch(JSONException error){throw SyncProtocol.invalid("invalid graph identity array");}
            if(!SyncProtocol.canonical(ids).equals(rest))throw SyncProtocol.invalid("noncanonical graph identity");
            for(int i=0;i<ids.length();i++)if(!(ids.get(i) instanceof String))throw SyncProtocol.invalid("non-string graph identity");
            String fields=fields(table,suffix);int count=identityCount(table);if(ids.length()!=count)throw SyncProtocol.invalid("wrong graph identity arity");
            if(value==JSONObject.NULL)continue;
            if(!(value instanceof JSONObject))throw SyncProtocol.invalid("graph decision must be an object");JSONObject row=(JSONObject)value;SyncProtocol.exact(row,fields);
            validatePrimitives(row);
            if(table.equals("details")) {
                if(!row.getString("field_id").matches("[A-Za-z0-9:_-]{1,100}"))throw SyncProtocol.invalid("invalid detail identity");
                try{DetailValue.validate(row.getString("kind"),row.getString("label"),row.getString("value"),row.getString("qualifier"));}
                catch(IllegalArgumentException invalid){throw SyncProtocol.invalid("invalid exact detail value");}
                if(!java.util.Arrays.asList("manual","accepted","edited","dismissed").contains(row.getString("origin"))
                        ||!java.util.Arrays.asList("saved","removed","rejected").contains(row.getString("state"))
                        ||row.getLong("revision")<1)throw SyncProtocol.invalid("invalid detail decision");
                ArchiveJson.read(row.getString("evidence"));
            }
            if(suffix==null&&java.util.Arrays.asList("documents","pages","tags").contains(table))SyncProtocol.uuid(row.getString("id"));
            if(suffix==null&&table.equals("stacks")&&!row.getString("id").matches("[A-Za-z0-9_-]{1,80}"))throw SyncProtocol.invalid("invalid stack identity");
            if(table.equals("choices")&&!row.getString("evidence").matches("[a-f0-9]{64}"))throw SyncProtocol.invalid("invalid choice evidence");
            if(table.equals("suggestions"))for(String field:new String[]{"input","evidence"}){
                String text=row.getString(field);if(!text.isEmpty()&&!text.matches("[a-f0-9]{64}"))throw SyncProtocol.invalid("invalid suggestion evidence");
            }
            if(suffix==null&&!key(table,row).equals(cell))throw SyncProtocol.invalid("graph row identity changed");
            if(table.equals("pages")&&"text".equals(suffix)){
                SyncProtocol.exact(row.getJSONObject("input"),"id,document_id,source_file,source_page,rotation,crop,enhancement,flatten,content_revision");
                SyncProtocol.exact(row.getJSONObject("row"),"ocr_text,ocr_state,ocr_engine,language,text_revision,ocr_error,ocr_config,text_config");
            }
        }
    }
    /** Validate partial rows before storing a causal gap. A failed whole-graph
     * projection can represent conflicts, but cannot legitimize malformed cells. */
    private static void validatePrimitives(JSONObject row)throws IOException,JSONException {
        for(String field:SyncProtocol.keys(row)) {
            Object value=row.get(field);
            if((field.equals("input")||field.equals("row"))&&value instanceof JSONObject){validatePrimitives((JSONObject)value);continue;}
            if(field.equals("source_scan_id")&&value==JSONObject.NULL)continue;
            if(java.util.Arrays.asList("added_at","initial_page_count","title_user_edited","title_decision","notes_revision",
                    "filing_decision","metadata_revision","source_page","rotation","flatten","content_revision","text_revision",
                    "color","visible","generation","title_revision","filing_revision","revision","length").contains(field)) {
                if(!(value instanceof Integer||value instanceof Long))throw SyncProtocol.invalid("integer field has another type");
                long number=((Number)value).longValue();long minimum=field.equals("color")?Integer.MIN_VALUE:field.equals("text_revision")?-1:0;
                if(number<minimum||number>SyncProtocol.MAX_SEQUENCE
                        ||field.equals("color")&&number>Integer.MAX_VALUE
                        ||java.util.Arrays.asList("visible","flatten","title_user_edited").contains(field)&&number>1
                        ||field.equals("rotation")&&number!=0&&number!=90&&number!=180&&number!=270
                        ||field.equals("initial_page_count")&&(number<1||number>1000)
                        ||field.equals("source_page")&&number>999
                        ||field.equals("length")&&(number<1||number>LibraryArchive.FILE_BYTES))throw SyncProtocol.invalid("decision integer outside supported range");
                continue;
            }
            if(!(value instanceof String))throw SyncProtocol.invalid("text field has another type");String text=(String)value;int maximum;
            switch(field) {
                case "notes":maximum=20000;break;
                case "ocr_text":case "payload":case "value":maximum=1024*1024;break;
                case "ocr_engine":case "ocr_config":case "text_config":case "crop":maximum=256;break;
                case "language":case "input":maximum=64;break;
                case "ocr_error":maximum=80;break;case "rule":maximum=128;break;
                case "title":case "name":case "label":maximum=200;break;
                case "mime":case "source_file":maximum=40;break;case "path":maximum=160;break;
                case "field_id":maximum=100;break;case "source_scan_id":case "document_id":case "tag_id":maximum=36;break;
                case "status":case "title_state":case "filing_state":case "choice":case "field":case "title_origin":case "filing_origin":maximum=16;break;
                default:maximum=8192;
            }
            if(text.length()>maximum)throw SyncProtocol.invalid("decision text exceeds field limit");
            if(field.equals("title")||field.equals("name"))try{
                if(!LibraryRepository.cleanName(text).equals(text))throw SyncProtocol.invalid("decision name is not normalized");
            }catch(IllegalArgumentException invalid){throw SyncProtocol.invalid("invalid decision name");}
            if(java.util.Arrays.asList("document_id","tag_id","source_scan_id").contains(field))SyncProtocol.uuid(text);
            if(field.equals("source_file"))originalPath("originals/00000000-0000-0000-0000-000000000000/"+text);
            if(field.equals("path"))originalPath(text);
            if(field.equals("mime")&&!java.util.Arrays.asList("application/pdf","application/x-stack-scan","image/jpeg","image/png","image/webp").contains(text))throw SyncProtocol.invalid("unsupported document MIME");
            if(field.equals("status")&&!java.util.Arrays.asList("pending","running","waiting","ready","empty","failed").contains(text))throw SyncProtocol.invalid("unsupported suggestion status");
            if(java.util.Arrays.asList("title_state","filing_state","choice").contains(field)&&!java.util.Arrays.asList("offered","accepted","edited","dismissed").contains(text))throw SyncProtocol.invalid("unsupported decision");
            if(field.equals("field")&&!text.equals("title")&&!text.equals("filing"))throw SyncProtocol.invalid("unsupported choice field");
            if(field.equals("payload"))ArchiveJson.read(text);
            if(field.equals("crop"))try{PageGeometry.decode(text);}catch(IllegalArgumentException invalid){throw SyncProtocol.invalid("invalid saved crop");}
            if(field.equals("sha256")&&!text.matches("[a-f0-9]{64}"))throw SyncProtocol.invalid("invalid original digest");
            if(field.equals("enhancement")&&!java.util.Arrays.asList("original","medium","bright","black_white").contains(text))throw SyncProtocol.invalid("unsupported saved enhancement");
            if(field.equals("ocr_state")&&!java.util.Arrays.asList("pending","running","succeeded","failed").contains(text))throw SyncProtocol.invalid("unsupported text state");
            if(java.util.Arrays.asList("title_origin","filing_origin").contains(field)&&!java.util.Arrays.asList("default","filename","legacy","manual","accepted","edited").contains(text))throw SyncProtocol.invalid("unsupported decision provenance");
        }
    }
    private static void originalPath(String path)throws IOException {
        String[] parts=path.split("/",-1);if(parts.length!=3||!parts[0].equals("originals"))throw SyncProtocol.invalid("invalid original path");
        SyncProtocol.uuid(parts[1]);if(!parts[2].equals("source")){
            if(!parts[2].endsWith(".jpg"))throw SyncProtocol.invalid("invalid original filename");
            SyncProtocol.uuid(parts[2].substring(0,parts[2].length()-4));
        }
    }
    private static void validateIds(Object value,boolean uuid)throws IOException,JSONException {
        if(!(value instanceof JSONArray))throw SyncProtocol.invalid("order decision must be an array");JSONArray ids=(JSONArray)value;if(ids.length()>1000)throw SyncProtocol.invalid("order limit");Set<String> seen=new HashSet<>();
        for(int i=0;i<ids.length();i++){if(!(ids.get(i) instanceof String))throw SyncProtocol.invalid("order identity must be a string");String id=ids.getString(i);if(!seen.add(id))throw SyncProtocol.invalid("duplicate order identity");if(uuid)SyncProtocol.uuid(id);}
    }
    private static int identityCount(String table)throws IOException {
        switch(table){case "documents":case "pages":case "stacks":case "tags":case "files":case "suggestions":return 1;
            case "memberships":case "details":case "document_tags":return 2;case "choices":return 3;
            default:throw SyncProtocol.invalid("unsupported graph table");}
    }
    private static String fields(String table,String suffix)throws IOException {
        if(table.equals("documents")) {
            if(suffix==null)return "id,mime,added_at,initial_page_count,source_scan_id";
            switch(suffix){case "title":return "title,title_origin,title_user_edited,title_decision";case "notes":return "notes,notes_revision";case "filing_origin":return "filing_origin";case "filing_decision":return "filing_decision";case "metadata_revision":return "metadata_revision";}
        }
        if(table.equals("pages")){if(suffix==null)return "id,document_id,source_file,source_page";if(suffix.equals("appearance"))return "rotation,crop,enhancement,flatten,content_revision";if(suffix.equals("text"))return "input,row";}
        if(table.equals("stacks")){if(suffix==null)return "id";if(suffix.equals("name")||suffix.equals("color")||suffix.equals("visible"))return suffix;}
        if(suffix!=null)throw SyncProtocol.invalid("unsupported graph suffix");
        switch(table){case "memberships":return "document_id,stack_id";case "suggestions":return android.text.TextUtils.join(",",LibraryArchive.SUGGESTION);
            case "choices":return "document_id,evidence,field,choice,value";case "details":return android.text.TextUtils.join(",",LibraryArchive.DETAIL);
            case "tags":return "id,name";case "document_tags":return "document_id,tag_id,field_id";case "files":return "path,length,sha256";
            default:throw SyncProtocol.invalid("unsupported graph decision");}
    }
    static JSONObject project(JSONObject cells)throws IOException,JSONException {
        JSONObject manifest=new JSONObject().put("format","org.stackdocs.library").put("version",3).put("schema",8).put("created_at",System.currentTimeMillis());
        for(String table:TABLES)manifest.put(table,new JSONArray());
        manifest.put("decision_evidence",new JSONObject());Set<String> docs=new HashSet<>();
        for(String key:SyncProtocol.keys(cells)) {
            if(cells.isNull(key)||key.startsWith("page_order/")||key.startsWith("catalog_order/")||key.startsWith("filing_review/")||decision(key))continue;
            int slash=key.indexOf('/');if(slash<0)throw SyncProtocol.invalid("bad graph key");String table=key.substring(0,slash);if(!supported(table))throw SyncProtocol.invalid("unsupported graph row");
            JSONObject row=copy(cells.getJSONObject(key));
            if(table.equals("documents")) {
                for(String suffix:new String[]{"title","notes","filing_origin","filing_decision","metadata_revision"})extend(row,cells.getJSONObject(key+"/"+suffix));
                docs.add(row.getString("id"));
            } else if(table.equals("stacks")) {
                for(String suffix:new String[]{"name","color","visible"})extend(row,cells.getJSONObject(key+"/"+suffix));
            } else if(table.equals("pages")) {
                JSONObject appearance=cells.getJSONObject(key+"/appearance"),expected=copy(row);extend(expected,appearance);extend(row,appearance);
                JSONObject text=cells.getJSONObject(key+"/text");extend(row,text.getJSONObject("row"));
                if(!SyncProtocol.canonical(expected).equals(SyncProtocol.canonical(text.getJSONObject("input"))))row.put("ocr_text","").put("ocr_state","pending").put("text_revision",-1).put("text_config","").put("ocr_error","");
            }
            manifest.getJSONArray(table).put(row);
        }
        for(String table:TABLES)if(!table.equals("documents")&&!table.equals("stacks")&&!table.equals("tags")) {
            JSONArray filtered=new JSONArray(),rows=manifest.getJSONArray(table);
            for(int i=0;i<rows.length();i++) {JSONObject row=rows.getJSONObject(i);String owner=table.equals("files")?fileOwner(row.getString("path")):row.getString("document_id");if(docs.contains(owner))filtered.put(row);}
            manifest.put(table,filtered);
        }
        JSONArray documents=manifest.getJSONArray("documents"),pages=manifest.getJSONArray("pages");
        for(int i=0;i<documents.length();i++) {
            JSONObject doc=documents.getJSONObject(i);String id=doc.getString("id");JSONArray order=cells.getJSONArray("page_order/"+id);if(order.length()==0)throw SyncProtocol.invalid("empty page order");
            doc.put("page_count",order.length());Set<String> seen=new HashSet<>();for(int j=0;j<order.length();j++){String pageId=order.getString(j);if(!seen.add(pageId))throw SyncProtocol.invalid("duplicate page order");JSONObject page=find(pages,"id",pageId);if(page==null||!id.equals(page.getString("document_id")))throw SyncProtocol.invalid("page order references unavailable page");page.put("position",j);}
            JSONArray kept=new JSONArray();for(int j=0;j<pages.length();j++){JSONObject page=pages.getJSONObject(j);if(!id.equals(page.getString("document_id"))||seen.contains(page.getString("id")))kept.put(page);}pages=kept;
        }
        manifest.put("pages",pages);
        JSONArray catalog=cells.getJSONArray("catalog_order/stacks"),stacks=manifest.getJSONArray("stacks");Set<String> seen=new HashSet<>();
        for(int i=0;i<catalog.length();i++){String id=catalog.getString(i);if(!seen.add(id))throw SyncProtocol.invalid("duplicate stack order");JSONObject row=find(stacks,"id",id);if(row==null)throw SyncProtocol.invalid("catalog references unavailable stack");row.put("sort_order",i);}
        if(seen.size()!=stacks.length())throw SyncProtocol.invalid("catalog omits a stack");
        JSONArray orderedStacks=new JSONArray();for(int i=0;i<catalog.length();i++)orderedStacks.put(find(stacks,"id",catalog.getString(i)));
        manifest.put("stacks",orderedStacks);
        List<JSONObject> orderedPages=new ArrayList<>();for(int i=0;i<pages.length();i++)orderedPages.add(pages.getJSONObject(i));
        orderedPages.sort((a,b)->{int owner=a.optString("document_id").compareTo(b.optString("document_id"));return owner!=0?owner:Integer.compare(a.optInt("position"),b.optInt("position"));});
        JSONArray sortedPages=new JSONArray();for(JSONObject page:orderedPages)sortedPages.put(page);manifest.put("pages",sortedPages);
        refreshEvidence(manifest);
        LibraryArchive.validate(manifest);return manifest;
    }
    static void refreshEvidence(JSONObject manifest)throws IOException,JSONException {
        JSONObject hashes=new JSONObject();JSONArray documents=manifest.getJSONArray("documents");
        for(int i=0;i<documents.length();i++){String id=documents.getJSONObject(i).getString("id");hashes.put(id,evidence(manifest,id));}
        manifest.put("decision_evidence",hashes);
    }
    static void normalize(JSONObject manifest)throws JSONException {
        JSONArray stacks=manifest.getJSONArray("stacks");List<JSONObject> ordered=new ArrayList<>();for(int i=0;i<stacks.length();i++)ordered.add(stacks.getJSONObject(i));
        ordered.sort((a,b)->Integer.compare(a.optInt("sort_order"),b.optInt("sort_order")));JSONArray sorted=new JSONArray();for(JSONObject row:ordered)sorted.put(row);manifest.put("stacks",sorted);
        JSONArray pages=manifest.getJSONArray("pages");ordered.clear();for(int i=0;i<pages.length();i++)ordered.add(pages.getJSONObject(i));
        ordered.sort((a,b)->{int owner=a.optString("document_id").compareTo(b.optString("document_id"));return owner!=0?owner:Integer.compare(a.optInt("position"),b.optInt("position"));});sorted=new JSONArray();for(JSONObject row:ordered)sorted.put(row);manifest.put("pages",sorted);
    }
    private static boolean supported(String table){for(String allowed:TABLES)if(allowed.equals(table))return true;return false;}
    private static boolean decision(String key){for(String suffix:new String[]{"/title","/notes","/appearance","/text","/name","/color","/visible","/filing_origin","/filing_decision","/metadata_revision"})if(key.endsWith(suffix))return true;return false;}
    private static String fileOwner(String path)throws IOException {String[] bits=path.split("/");if(bits.length!=3||!bits[0].equals("originals"))throw SyncProtocol.invalid("invalid original path");SyncProtocol.uuid(bits[1]);return bits[1];}
    private static JSONObject find(JSONArray rows,String key,String value)throws JSONException {for(int i=0;i<rows.length();i++)if(value.equals(rows.getJSONObject(i).getString(key)))return rows.getJSONObject(i);return null;}
    private static String evidence(JSONObject manifest,String id)throws IOException,JSONException {
        try {MessageDigest digest=MessageDigest.getInstance("SHA-256");add(digest,id);JSONArray rows=manifest.getJSONArray("pages");List<JSONObject> pages=new ArrayList<>();for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("document_id")))pages.add(rows.getJSONObject(i));pages.sort((a,b)->Integer.compare(a.optInt("position"),b.optInt("position")));
            for(JSONObject row:pages)for(String field:new String[]{"id","position","content_revision","text_revision","ocr_config","text_config","ocr_text"})add(digest,String.valueOf(row.get(field)));return hex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException error){throw new AssertionError(error);}
    }
    private static void add(MessageDigest digest,String value){digest.update((value.length()+":").getBytes(StandardCharsets.UTF_8));digest.update(value.getBytes(StandardCharsets.UTF_8));}
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte value:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}
}
