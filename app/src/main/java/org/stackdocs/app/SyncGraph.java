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
            if(isLinkKey(cell)){validateLinkCell(cell,value);continue;}
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
        JSONObject manifest=new JSONObject().put("format","org.stackdocs.library").put("version",3).put("schema",8).put("created_at",Clock.now());
        for(String table:TABLES)manifest.put(table,new JSONArray());
        manifest.put("decision_evidence",new JSONObject());Set<String> docs=new HashSet<>();
        for(String key:SyncProtocol.keys(cells)) {
            if(cells.isNull(key)||isLinkKey(key)||key.startsWith("page_order/")||key.startsWith("catalog_order/")||key.startsWith("filing_review/")||decision(key))continue;
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

    // ---------------------------------------------------------------------------------------------------------------
    // Link records (slice 6, docs/STACK_LINK_SLICE6_DESIGN.md sections 2, 4, 8). Pure functions only: no database, no
    // files, no clock. The receive-side validator checks shape, arity, JSON types, safe-integer range and string bounds
    // for memory safety and nothing else (a receiver that rejects poisons the whole contiguous operation chain). Every
    // semantic limit lives in the writer self-check (linkWriter) and is applied again, as a skip, by projectLinks.
    // It deliberately bypasses validatePrimitives (its integer list lacks modified/shared/protected/since, finding M4).
    // ---------------------------------------------------------------------------------------------------------------
    static final String LINK_PREFIX="link_records/",LINK_MARKER_PREFIX="link_records/enabled/";
    static final int LINK_LIMIT=1000,LINK_TITLE_UNITS=200,LINK_OWNER_UNITS=200,LINK_PATH_UNITS=1024;
    static final long LINK_TEXT_BYTES=1L<<20,LINK_PREVIEW_BYTES=32L<<20;
    private static final String[] LINK_SUFFIXES={"","content","text","preview","title","filen","presence"};
    private static final java.util.regex.Pattern LINK_UUID=java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final java.util.regex.Pattern LINK_OWNER=java.util.regex.Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+");
    private static final java.util.regex.Pattern LINK_KIND=java.util.regex.Pattern.compile("[a-z]{1,16}");
    private static final java.util.regex.Pattern LINK_MIME=java.util.regex.Pattern.compile("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*");
    private static final java.util.regex.Pattern LINK_HASH=java.util.regex.Pattern.compile("[0-9a-f]{64}");
    private static final List<String> LINK_REASONS=java.util.Arrays.asList("","deleted","trashed","user","forgotten");
    private static final List<String> LINK_STATES=java.util.Arrays.asList("live","trashed","gone");

    static boolean isLinkKey(String key){return key.startsWith(LINK_PREFIX);}
    /** `link_records/["<owner>","<link_id>"]` (canonical JSON array, the same form as `memberships/`). */
    static String linkKey(String owner,String linkId)throws IOException,JSONException {
        return LINK_PREFIX+SyncProtocol.canonical(new JSONArray().put(owner).put(linkId));
    }
    /** `link_records/enabled/<replica>`; the marker value is {"since": ms}. */
    static String linkMarkerKey(String replica){return LINK_MARKER_PREFIX+replica;}

    // ----- receive side -----
    private static void validateLinkCell(String cell,Object value)throws IOException,JSONException {
        String rest=cell.substring(LINK_PREFIX.length());
        if(rest.startsWith("enabled/")) {
            String replica=rest.substring(8);if(replica.isEmpty()||replica.length()>128)throw SyncProtocol.invalid("invalid link marker replica");
            JSONObject marker=linkObject(value,"link marker must be an object");SyncProtocol.exact(marker,"since");SyncProtocol.integer(marker.get("since"));return;
        }
        int end=rest.lastIndexOf(']');if(!rest.startsWith("[")||end<0)throw SyncProtocol.invalid("invalid link identity");
        String array=rest.substring(0,end+1),tail=rest.substring(end+1),suffix;
        if(tail.isEmpty())suffix="";else if(tail.startsWith("/"))suffix=tail.substring(1);else throw SyncProtocol.invalid("invalid link identity");
        if(!java.util.Arrays.asList(LINK_SUFFIXES).contains(suffix))throw SyncProtocol.invalid("unsupported link suffix");
        JSONArray ids;try{ids=new JSONArray(array);}catch(JSONException error){throw SyncProtocol.invalid("invalid link identity array");}
        if(!SyncProtocol.canonical(ids).equals(array))throw SyncProtocol.invalid("noncanonical link identity");
        if(ids.length()!=2)throw SyncProtocol.invalid("wrong link identity arity");
        if(!(ids.get(0) instanceof String)||!(ids.get(1) instanceof String))throw SyncProtocol.invalid("non-string link identity");
        if(!LINK_UUID.matcher(ids.getString(1)).matches())throw SyncProtocol.invalid("link identity needs a canonical UUID");
        if(ids.getString(0).isEmpty()||ids.getString(0).length()>1024)throw SyncProtocol.invalid("link owner too long");
        if(value==JSONObject.NULL){if(suffix.isEmpty()||suffix.equals("presence"))throw SyncProtocol.invalid("link cell is not nullable");return;}
        JSONObject row=linkObject(value,"link cell must be an object");
        switch(suffix) {
            case "":
                SyncProtocol.exact(row,"owner,link_id,kind,added_at");linkString(row,"owner",1024);linkString(row,"link_id",36);linkString(row,"kind",64);SyncProtocol.integer(row.get("added_at"));
                if(!ids.getString(0).equals(row.getString("owner"))||!ids.getString(1).equals(row.getString("link_id")))throw SyncProtocol.invalid("link row identity changed");break;
            case "content":
                SyncProtocol.exact(row,"hash,mime,modified,shared,protected");linkString(row,"hash",64);linkString(row,"mime",100);SyncProtocol.integer(row.get("modified"));linkBit(row,"shared");linkBit(row,"protected");break;
            case "text":SyncProtocol.exact(row,"sha256,length");linkString(row,"sha256",64);linkLength(row,LINK_TEXT_BYTES);break;
            case "preview":SyncProtocol.exact(row,"sha256,length");linkString(row,"sha256",64);linkLength(row,LINK_PREVIEW_BYTES);break;
            case "title":SyncProtocol.exact(row,"title,title_user_edited");linkString(row,"title",LINK_TITLE_UNITS);linkBit(row,"title_user_edited");break;
            case "filen":SyncProtocol.exact(row,"uuid,path");linkString(row,"uuid",64);linkString(row,"path",LINK_PATH_UNITS);break;
            default:SyncProtocol.exact(row,"state,reason,since");linkString(row,"state",32);linkString(row,"reason",32);SyncProtocol.integer(row.get("since"));
        }
    }
    private static JSONObject linkObject(Object value,String message)throws IOException {
        if(!(value instanceof JSONObject))throw SyncProtocol.invalid(message);return (JSONObject)value;
    }
    /** String of at most `max` UTF-16 units (Java String.length() counts UTF-16 units). */
    private static void linkString(JSONObject row,String field,int max)throws IOException,JSONException {
        Object value=row.get(field);if(!(value instanceof String)||((String)value).length()>max)throw SyncProtocol.invalid("link text field has another type or is too long");
    }
    private static void linkBit(JSONObject row,String field)throws IOException,JSONException {
        if(SyncProtocol.integer(row.get(field))>1)throw SyncProtocol.invalid("link flag must be 0 or 1");
    }
    private static void linkLength(JSONObject row,long max)throws IOException,JSONException {
        long length=SyncProtocol.integer(row.get("length"));if(length<1||length>max)throw SyncProtocol.invalid("link blob length outside its limit");
    }

    // ----- semantic checks, shared by the writer self-check and the receive-side projection -----
    /** LinkRecord.free: non-empty, at most `max` UTF-16 units, no control or direction characters, no unpaired surrogate. Surrounding spaces are fine. */
    static boolean linkFree(Object value,int max) {
        if(!(value instanceof String))return false;String s=(String)value;if(s.isEmpty()||s.length()>max)return false;
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if(c<0x20||(c>=0x7f&&c<=0x9f)||(c>=0x202a&&c<=0x202e)||(c>=0x2066&&c<=0x2069)||c==0x200e||c==0x200f||c==0x061c)return false;
            if(Character.isHighSurrogate(c)){if(i+1>=s.length()||!Character.isLowSurrogate(s.charAt(i+1)))return false;i++;}
            else if(Character.isLowSurrogate(c))return false;
        }
        return true;
    }
    private static boolean linkMatch(java.util.regex.Pattern pattern,Object value,int max){
        return value instanceof String&&((String)value).length()<=max&&pattern.matcher((String)value).matches();
    }
    private static boolean linkSafe(Object value,long min,long max) {
        return (value instanceof Integer||value instanceof Long)&&((Number)value).longValue()>=min&&((Number)value).longValue()<=max;
    }
    private static boolean linkIdentityOk(JSONObject row) {
        return linkMatch(LINK_OWNER,row.opt("owner"),LINK_OWNER_UNITS)&&linkMatch(LINK_UUID,row.opt("link_id"),36)&&linkMatch(LINK_KIND,row.opt("kind"),16)
                &&linkSafe(row.opt("added_at"),0,SyncProtocol.MAX_SEQUENCE);
    }
    private static boolean linkContentOk(JSONObject row) {
        return linkMatch(LINK_HASH,row.opt("hash"),64)&&linkMatch(LINK_MIME,row.opt("mime"),100)&&linkSafe(row.opt("modified"),0,SyncProtocol.MAX_SEQUENCE)
                &&linkSafe(row.opt("shared"),0,1)&&linkSafe(row.opt("protected"),0,1);
    }
    private static boolean linkPresenceOk(JSONObject row) {
        return LINK_STATES.contains(row.opt("state"))&&LINK_REASONS.contains(row.opt("reason"))&&linkSafe(row.opt("since"),0,SyncProtocol.MAX_SEQUENCE);
    }
    private static boolean linkFilenOk(JSONObject row) {
        Object path=row.opt("path");return linkMatch(LINK_UUID,row.opt("uuid"),36)&&path instanceof String&&((String)path).length()>=1&&((String)path).length()<=LINK_PATH_UNITS;
    }
    private static boolean linkTitleOk(JSONObject row){return linkFree(row.opt("title"),LINK_TITLE_UNITS)&&linkSafe(row.opt("title_user_edited"),0,1);}
    private static boolean linkBlobOk(JSONObject row,long max){return linkMatch(LINK_HASH,row.opt("sha256"),64)&&linkSafe(row.opt("length"),1,max);}

    /**
     * The content checks `linkWriter` applies, on a full cell set of one link (full keys `base` + suffix, as the resolution writes it):
     * identity, presence, and for a live link its content, title, filen, text and preview cells. A null cell is always fine.
     */
    static boolean linkCellsOk(JSONObject cells,String base) {
        JSONObject identity=cells.optJSONObject(base);if(identity==null||!linkIdentityOk(identity))return false;
        JSONObject presence=cells.optJSONObject(base+"/presence");if(presence!=null&&!linkPresenceOk(presence))return false;
        if(presence!=null&&"gone".equals(presence.optString("state")))return true;
        JSONObject content=cells.optJSONObject(base+"/content");if(content==null||!linkContentOk(content))return false;
        JSONObject title=cells.optJSONObject(base+"/title");if(title!=null&&!linkTitleOk(title))return false;
        JSONObject filen=cells.optJSONObject(base+"/filen");if(filen!=null&&!linkFilenOk(filen))return false;
        JSONObject text=cells.optJSONObject(base+"/text");if(text!=null&&!linkBlobOk(text,LINK_TEXT_BYTES))return false;
        JSONObject preview=cells.optJSONObject(base+"/preview");if(preview!=null&&!linkBlobOk(preview,LINK_PREVIEW_BYTES))return false;
        return true;
    }

    // ----- writer self-check -----
    /**
     * The writer side of the link graph: turns one link row into its cells, or refuses/skips what the receiver would skip.
     * Input row (a JSON object, the golden `input.row` shape): owner, link_id, kind, added_at, hash, mime, modified, shared (0/1),
     * protected (0/1), title (String or null), title_user_edited (0/1), filen ({uuid,path} or null) or filen_locators (list of
     * {uuid,path}; the FIRST is used), text (String or null; the caller writes the UTF-8 bytes of this String as the blob),
     * preview ({sha256,length} of the caller's already hashed file, or null), presence ({state,reason,since}; absent = omit the
     * presence cell, the desktop enable op). `liveOrTrashedAlreadyCaptured` counts identities already emitted whose presence is not gone.
     * Returns {outcome: "emitted"|"row_refused"|"cell_skipped", cells: {suffix: value|null} where suffix is "" (identity) or "/content", "/text", "/preview", "/title", "/filen", "/presence", reasons: [..], key: "link_records/[..]"}.
     * Skipped cells are ABSENT from `cells`; null cells are JSONObject.NULL. A refused row has no cells and no key.
     */
    static JSONObject linkWriter(JSONObject row,long liveOrTrashedAlreadyCaptured)throws JSONException,IOException {
        JSONArray reasons=new JSONArray();JSONObject cells=new JSONObject(),result=new JSONObject();
        JSONObject presence=row.optJSONObject("presence");
        if(!linkIdentityOk(row))return refused(result,"identity");
        if(presence!=null&&!linkPresenceOk(presence))return refused(result,"presence");
        boolean gone=presence!=null&&"gone".equals(presence.getString("state"));
        if(!gone) {
            if(liveOrTrashedAlreadyCaptured>=LINK_LIMIT)return refused(result,"link limit");
            if(!linkContentOk(row))return refused(result,"content");
            Object title=row.opt("title");
            if(title!=null&&title!=JSONObject.NULL&&!linkTitleOk(new JSONObject().put("title",title).put("title_user_edited",row.opt("title_user_edited")==null?0:row.opt("title_user_edited"))))return refused(result,"title");
        }
        cells.put("",new JSONObject().put("owner",row.getString("owner")).put("link_id",row.getString("link_id")).put("kind",row.getString("kind")).put("added_at",row.get("added_at")));
        if(gone) {
            for(String suffix:new String[]{"/content","/text","/preview","/title","/filen"})cells.put(suffix,JSONObject.NULL);
        } else {
            cells.put("/content",new JSONObject().put("hash",row.get("hash")).put("mime",row.get("mime")).put("modified",row.get("modified")).put("shared",row.get("shared")).put("protected",row.get("protected")));
            Object title=row.opt("title");
            cells.put("/title",title==null||title==JSONObject.NULL?JSONObject.NULL:new JSONObject().put("title",title).put("title_user_edited",row.opt("title_user_edited")==null?0:row.get("title_user_edited")));
            JSONObject filen=firstFilen(row);
            if(filen==null)cells.put("/filen",JSONObject.NULL);
            else if(linkFilenOk(filen))cells.put("/filen",new JSONObject().put("uuid",filen.getString("uuid")).put("path",filen.getString("path")));
            else reasons.put("filen cell skipped");
            boolean isProtected=((Number)row.get("protected")).longValue()==1;
            if(isProtected){cells.put("/text",JSONObject.NULL).put("/preview",JSONObject.NULL);}   // metadata only (decision 6)
            else {
                Object text=row.opt("text");
                if(text==null||text==JSONObject.NULL||"".equals(text))cells.put("/text",JSONObject.NULL);   // empty text is a null cell, never length 0
                else if(!(text instanceof String)||((String)text).indexOf('\0')>=0)reasons.put("text cell skipped");
                else {
                    byte[] bytes=((String)text).getBytes(StandardCharsets.UTF_8);
                    if(bytes.length>LINK_TEXT_BYTES)reasons.put("text cell skipped");
                    else cells.put("/text",new JSONObject().put("sha256",sha256(bytes)).put("length",bytes.length));
                }
                JSONObject preview=row.optJSONObject("preview");
                if(preview==null)cells.put("/preview",JSONObject.NULL);
                else if(linkSafe(preview.opt("length"),0,0))cells.put("/preview",JSONObject.NULL);
                else if(linkBlobOk(preview,LINK_PREVIEW_BYTES))cells.put("/preview",new JSONObject().put("sha256",preview.getString("sha256")).put("length",preview.get("length")));
                else reasons.put("preview cell skipped");
            }
        }
        if(presence!=null) {
            String state=presence.getString("state");boolean live=state.equals("live");
            cells.put("/presence",new JSONObject().put("state",state).put("reason",live?"":presence.getString("reason")).put("since",live?0:presence.get("since")));
        }
        return result.put("outcome",reasons.length()==0?"emitted":"cell_skipped").put("cells",cells).put("reasons",reasons).put("key",linkKey(row.getString("owner"),row.getString("link_id")));
    }
    private static JSONObject refused(JSONObject result,String reason)throws JSONException {
        return result.put("outcome","row_refused").put("cells",new JSONObject()).put("reasons",new JSONArray().put(reason));
    }
    private static JSONObject firstFilen(JSONObject row) {
        JSONArray locators=row.optJSONArray("filen_locators");
        if(locators!=null&&locators.length()>0)return locators.optJSONObject(0);
        return row.optJSONObject("filen");
    }
    private static String sha256(byte[] bytes) {
        try{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException error){throw new AssertionError(error);}
    }
    /** A writer result as a changes map: full cell keys (`key` + `/suffix`). Empty for a refused row. */
    static JSONObject linkChanges(JSONObject writerResult)throws JSONException {
        JSONObject changes=new JSONObject();if(!writerResult.has("key"))return changes;
        String key=writerResult.getString("key");JSONObject cells=writerResult.getJSONObject("cells");
        for(String suffix:SyncProtocol.keys(cells))changes.put(key+suffix,cells.get(suffix));
        return changes;
    }
    /** The enable marker cell: {key: {"since": ms}}. Written once per replica. */
    static JSONObject linkMarker(String replica,long since)throws JSONException {
        return new JSONObject().put(linkMarkerKey(replica),new JSONObject().put("since",since));
    }

    // ----- receive-side projection -----
    /** True when `cells` holds a valid enable marker for `replica` (the proof that the device enabled links). */
    static boolean linkEnabled(JSONObject cells,String replica) {
        JSONObject marker=cells.optJSONObject(linkMarkerKey(replica));return marker!=null&&linkSafe(marker.opt("since"),0,SyncProtocol.MAX_SEQUENCE);
    }
    /**
     * Projects the link cells of a causal projection into link rows. Never throws on bad data and never deletes by absence: it
     * returns only the links it saw. A link is skipped (counted in `skipped`) when any used cell fails a semantic check; a link with a
     * conflicted cell is passed over (counted in `conflicted`). Presence absent = live. A protected link is applied metadata only:
     * text and preview cells are ignored (nulled). `gone` links carry only identity and presence.
     * Returns {links: [{key, owner, link_id, kind, added_at, outcome: applied|applied_metadata_only|gone, state, reason, since,
     * presence_absent, content, title, filen, text, preview}], skipped: n, conflicted: n}; absent cells are null.
     */
    static JSONObject projectLinks(JSONObject cells,Set<String> conflicted)throws JSONException,IOException {
        List<String> bases=new ArrayList<>();
        for(String key:SyncProtocol.keys(cells)) {
            if(!isLinkKey(key)||key.startsWith(LINK_MARKER_PREFIX))continue;
            int end=key.lastIndexOf(']');if(end<0||!key.startsWith(LINK_PREFIX+"["))continue;
            String base=key.substring(0,end+1);if(!bases.contains(base))bases.add(base);
        }
        JSONArray links=new JSONArray();int skipped=0,passed=0;
        for(String base:bases) {
            boolean conflict=false;for(String suffix:LINK_SUFFIXES)if(conflicted!=null&&conflicted.contains(suffix.isEmpty()?base:base+"/"+suffix))conflict=true;
            if(conflict){passed++;continue;}
            JSONObject link=projectLink(cells,base);if(link==null)skipped++;else links.put(link);
        }
        return new JSONObject().put("links",links).put("skipped",skipped).put("conflicted",passed);
    }
    private static JSONObject projectLink(JSONObject cells,String base)throws JSONException {
        JSONObject identity=cells.optJSONObject(base);if(identity==null||!linkIdentityOk(identity))return null;
        JSONObject presence=cells.optJSONObject(base+"/presence");
        String state="live",reason="";long since=0;
        if(presence!=null){if(!linkPresenceOk(presence))return null;state=presence.getString("state");reason=presence.getString("reason");since=((Number)presence.get("since")).longValue();}
        else if(cells.has(base+"/presence")&&!cells.isNull(base+"/presence"))return null;
        JSONObject out=new JSONObject().put("key",base).put("owner",identity.getString("owner")).put("link_id",identity.getString("link_id")).put("kind",identity.getString("kind"))
                .put("added_at",identity.get("added_at")).put("state",state).put("reason",reason).put("since",since).put("presence_absent",presence==null);
        for(String suffix:new String[]{"content","title","filen","text","preview"})out.put(suffix,JSONObject.NULL);
        if(state.equals("gone"))return out.put("outcome","gone");
        JSONObject content=cells.optJSONObject(base+"/content");
        if(content!=null){if(!linkContentOk(content))return null;out.put("content",content);}
        JSONObject title=cells.optJSONObject(base+"/title");if(title!=null){if(!linkTitleOk(title))return null;out.put("title",title);}
        JSONObject filen=cells.optJSONObject(base+"/filen");if(filen!=null){if(!linkFilenOk(filen))return null;out.put("filen",filen);}
        boolean metadataOnly=content!=null&&((Number)content.get("protected")).longValue()==1;
        if(!metadataOnly) {
            JSONObject text=cells.optJSONObject(base+"/text");if(text!=null){if(!linkBlobOk(text,LINK_TEXT_BYTES))return null;out.put("text",text);}
            JSONObject preview=cells.optJSONObject(base+"/preview");if(preview!=null){if(!linkBlobOk(preview,LINK_PREVIEW_BYTES))return null;out.put("preview",preview);}
        }
        return out.put("outcome",metadataOnly?"applied_metadata_only":"applied");
    }
}
