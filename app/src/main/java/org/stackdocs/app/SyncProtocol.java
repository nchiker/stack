package org.stackdocs.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Version-1 causal kernel shared with Rust golden cases. This class alone is
 * not Android synchronization: its state must be committed with library rows. */
final class SyncProtocol {
    static final long MAX_SEQUENCE=9007199254740991L;
    static final int MAX_OPERATIONS=4096, MAX_CHANGES=65536, MAX_JSON=32*1024*1024;
    static boolean entityBase(String key){return (key.startsWith("documents/[")||key.startsWith("stacks/["))&&key.endsWith("]");}
    static String derivedCounter(String key){for(String field:new String[]{"metadata_revision","filing_decision"})if(key.endsWith("/"+field))return field;return null;}
    static IOException invalid(String reason){return new IOException("Sync rejected: "+reason);}
    static List<String> keys(JSONObject object){List<String> out=new ArrayList<>();object.keys().forEachRemaining(out::add);Collections.sort(out);return out;}
    static void exact(JSONObject object,String names)throws IOException {
        if(!new TreeSet<>(keys(object)).equals(new TreeSet<>(Arrays.asList(names.split(",")))))throw invalid("unsupported or missing fields");
    }
    static void uuid(String value)throws IOException {
        try{if(!UUID.fromString(value).toString().equals(value))throw invalid("canonical UUID required");}
        catch(IllegalArgumentException error){throw invalid("canonical UUID required");}
    }
    static long integer(Object value)throws IOException {
        if(!(value instanceof Integer || value instanceof Long))throw invalid("integer required");
        long number=((Number)value).longValue();if(number<0 || number>MAX_SEQUENCE)throw invalid("counter outside exact integer range");return number;
    }
    static void unicode(String value)throws IOException {
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)){if(++i>=value.length() || !Character.isLowSurrogate(value.charAt(i)))throw invalid("unpaired surrogate");}
            else if(Character.isLowSurrogate(c))throw invalid("unpaired surrogate");
        }
    }
    static String canonical(Object value)throws JSONException,IOException {
        if(value==null || value==JSONObject.NULL)return "null";
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;StringBuilder out=new StringBuilder("{");boolean first=true;for(String key:keys(object)){if(!first)out.append(',');first=false;out.append(quote(key)).append(':').append(canonical(object.get(key)));}return out.append('}').toString();}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonical(array.get(i)));}return out.append(']').toString();}
        if(value instanceof String)return quote((String)value);
        if(value instanceof Boolean)return value.toString();
        if(value instanceof Integer || value instanceof Long){long n=((Number)value).longValue();if(n < -MAX_SEQUENCE || n>MAX_SEQUENCE)throw invalid("unsafe number");return Long.toString(n);}
        throw invalid("wire values require integers or exact typed strings");
    }
    /** Wire identity arrays use the same spelling as serde_json. Android's
     * JSONObject.quote adds optional slash escapes that change nested cell keys. */
    private static String quote(String value)throws IOException {
        unicode(value);StringBuilder out=new StringBuilder("\"");
        final String hex="0123456789abcdef";
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            switch(c) {
                case '"':out.append("\\\"");break;
                case '\\':out.append("\\\\");break;
                case '\b':out.append("\\b");break;
                case '\f':out.append("\\f");break;
                case '\n':out.append("\\n");break;
                case '\r':out.append("\\r");break;
                case '\t':out.append("\\t");break;
                default:
                    if(c<0x20)out.append("\\u00").append(hex.charAt(c>>>4)).append(hex.charAt(c&15));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }
    static JSONObject copy(JSONObject value)throws JSONException,IOException{return ArchiveJson.read(canonical(value));}
    static TreeMap<String,Long> frontier(JSONObject object)throws JSONException,IOException {
        if(object.length()>64)throw invalid("too many replicas");TreeMap<String,Long> out=new TreeMap<>();for(String key:keys(object)){uuid(key);out.put(key,integer(object.get(key)));}return out;
    }
    static JSONObject json(Map<String,Long> values)throws JSONException {JSONObject out=new JSONObject();for(Map.Entry<String,Long> entry:values.entrySet())out.put(entry.getKey(),entry.getValue());return out;}

    static final class Operation {
        final JSONObject json,changes,evidenceHolder;
        final String library,replica,key;
        final long sequence;
        final TreeMap<String,Long> context;
        Operation(JSONObject value)throws IOException,JSONException {
            json=copy(value);exact(json,"version,library,epoch,id,context,changes,evidence");
            if(integer(json.get("version"))!=1 || integer(json.get("epoch"))!=1)throw invalid("unsupported version or epoch");
            library=json.getString("library");uuid(library);JSONObject id=json.getJSONObject("id");exact(id,"replica,sequence");replica=id.getString("replica");uuid(replica);sequence=integer(id.get("sequence"));
            if(sequence==0)throw invalid("sequence starts at one");context=frontier(json.getJSONObject("context"));if(context.getOrDefault(replica,0L)!=sequence-1)throw invalid("origin predecessor is required");
            changes=json.getJSONObject("changes");if(changes.length()==0 || changes.length()>MAX_CHANGES)throw invalid("change limit");
            for(String field:keys(changes)){if(field.isEmpty() || field.getBytes(StandardCharsets.UTF_8).length>1024)throw invalid("change key limit");for(int i=0;i<field.length();i++)if(Character.isISOControl(field.charAt(i)))throw invalid("control character in key");}
            if(canonical(json).getBytes(StandardCharsets.UTF_8).length>MAX_JSON)throw invalid("operation too large");
            evidenceHolder=new JSONObject().put("evidence",json.get("evidence"));key=replica+":"+String.format(Locale.ROOT,"%016d",sequence);
        }
        JSONObject alternative(String field)throws JSONException {return new JSONObject().put("id",json.getJSONObject("id")).put("value",changes.get(field)).put("evidence",evidenceHolder.get("evidence"));}
    }
    static final class State {
        final String library;
        final TreeSet<String> members;
        TreeMap<String,Long> frontier=new TreeMap<>();
        TreeMap<String,Operation> history=new TreeMap<>(),pending=new TreeMap<>();
        TreeMap<String,List<JSONObject>> registers=new TreeMap<>();
        State(String group,Collection<String> members)throws IOException {uuid(group);library=group;this.members=new TreeSet<>(members);if(this.members.isEmpty() || this.members.size()>64)throw invalid("invalid membership");for(String id:this.members)uuid(id);}
        State duplicate()throws IOException,JSONException {
            State copy=new State(library,members);copy.frontier.putAll(frontier);copy.history.putAll(history);copy.pending.putAll(pending);
            for(Map.Entry<String,List<JSONObject>> entry:registers.entrySet())copy.registers.put(entry.getKey(),new ArrayList<>(entry.getValue()));return copy;
        }
        int receive(List<Operation> batch)throws IOException,JSONException {
            if(batch.size()>MAX_OPERATIONS)throw invalid("too many operations");State next=duplicate();
            for(Operation op:batch) {
                if(!op.library.equals(library) || !members.contains(op.replica) || !members.containsAll(op.context.keySet()))throw invalid("unknown library or origin");
                Operation old=next.history.containsKey(op.key)?next.history.get(op.key):next.pending.get(op.key);
                if(old!=null){if(!canonical(old.json).equals(canonical(op.json)))throw invalid("operation ID reused");continue;}
                long current=next.frontier.getOrDefault(op.replica,0L);
                if(op.sequence<=current)throw invalid("missing retained history");
                for(Map.Entry<String,Long> dependency:op.context.entrySet())if(dependency.getValue()>next.frontier.getOrDefault(dependency.getKey(),0L)+MAX_OPERATIONS)throw invalid("pending dependency gap limit");
                if(next.pending.size()>=MAX_OPERATIONS || op.sequence-current>MAX_OPERATIONS)throw invalid("pending gap limit");
                next.pending.put(op.key,op);
            }
            int before=next.history.size();
            while(true) {
                Operation ready=null;
                for(Operation op:next.pending.values()) {
                    if(op.sequence!=next.frontier.getOrDefault(op.replica,0L)+1)continue;boolean satisfied=true;
                    for(Map.Entry<String,Long> entry:op.context.entrySet())if(next.frontier.getOrDefault(entry.getKey(),0L)<entry.getValue()){satisfied=false;break;}
                    if(satisfied){ready=op;break;}
                }
                if(ready==null)break;
                for(String field:keys(ready.changes)) {
                    List<JSONObject> alternatives=next.registers.computeIfAbsent(field,ignored->new ArrayList<>());
                    Iterator<JSONObject> iterator=alternatives.iterator();while(iterator.hasNext()){JSONObject id=iterator.next().getJSONObject("id");if(ready.context.getOrDefault(id.getString("replica"),0L)>=id.getLong("sequence"))iterator.remove();}
                    alternatives.add(ready.alternative(field));alternatives.sort((a,b)->{try {JSONObject x=a.getJSONObject("id"),y=b.getJSONObject("id");int order=x.getString("replica").compareTo(y.getString("replica"));return order==0?Long.compare(x.getLong("sequence"),y.getLong("sequence")):order;}catch(JSONException error){throw new IllegalStateException(error);}});
                }
                next.pending.remove(ready.key);next.history.put(ready.key,ready);next.frontier.put(ready.replica,ready.sequence);
            }
            frontier=next.frontier;history=next.history;pending=next.pending;registers=next.registers;return history.size()-before;
        }
        Operation local(String replica,JSONObject changes,Object evidence)throws IOException,JSONException {
            long sequence=frontier.getOrDefault(replica,0L)+1;
            Operation op=new Operation(new JSONObject().put("version",1).put("library",library).put("epoch",1).put("id",new JSONObject().put("replica",replica).put("sequence",sequence)).put("context",json(frontier)).put("changes",changes).put("evidence",evidence));receive(Collections.singletonList(op));return op;
        }
        String editedFiling(List<JSONObject> alternatives)throws JSONException {
            if(alternatives.size()<2)return null;
            for(JSONObject alternative:alternatives){Object raw=alternative.opt("value");if(!(raw instanceof JSONObject))return null;JSONObject value=(JSONObject)raw;Object text=value.opt("value");if(!"filing".equals(value.optString("field"))||!"edited".equals(value.optString("choice"))||!(text instanceof String)||((String)text).startsWith("{"))return null;}
            return alternatives.get(0).getJSONObject("value").optString("document_id",null);
        }
        boolean concurrent(Operation left,Operation right){return !left.key.equals(right.key)&&left.context.getOrDefault(right.replica,0L)<right.sequence&&right.context.getOrDefault(left.replica,0L)<left.sequence;}
        Operation origin(JSONObject alternative)throws JSONException {JSONObject id=alternative.getJSONObject("id");return history.get(id.getString("replica")+":"+String.format(Locale.ROOT,"%016d",id.getLong("sequence")));}
        boolean ownedBy(String entity,String field,Object raw)throws JSONException {
            if(field.startsWith(entity+"/"))return true;int split=entity.indexOf('/');if(split<0)return false;
            JSONArray identity;try{identity=new JSONArray(entity.substring(split+1));}catch(JSONException error){return false;}if(identity.length()==0)return false;String id=identity.optString(0);String kind=entity.substring(0,split);JSONObject value=raw instanceof JSONObject?(JSONObject)raw:new JSONObject();
            if(kind.equals("documents")) {
                if(field.equals("page_order/"+id)||field.equals("filing_review/"+id)||id.equals(value.optString("document_id")))return true;
                int slash=field.indexOf('/');if(slash<0)return false;String table=field.substring(0,slash);
                if(Arrays.asList("memberships","details","document_tags","choices","suggestions").contains(table)){try{if(id.equals(new JSONArray(field.substring(slash+1)).optString(0)))return true;}catch(JSONException ignored){}}
                if(table.equals("pages")){int end=field.indexOf("]/",slash);String base=end<0?field:field.substring(0,end+1);for(Operation op:history.values()){JSONObject row=op.changes.optJSONObject(base);if(row!=null&&id.equals(row.optString("document_id")))return true;}}
            } else if(kind.equals("stacks")) {
                if(id.equals(value.optString("stack_id")))return true;
                if(field.equals("catalog_order/stacks")&&raw instanceof JSONArray){JSONArray order=(JSONArray)raw;for(int i=0;i<order.length();i++)if(id.equals(order.optString(i)))return true;}
                if(field.startsWith("memberships/")){try{return id.equals(new JSONArray(field.substring("memberships/".length())).optString(1));}catch(JSONException ignored){}}
            }return false;
        }
        JSONObject values()throws JSONException {
            JSONObject out=new JSONObject();for(Map.Entry<String,List<JSONObject>> entry:registers.entrySet()) {
                String key=entry.getKey();boolean deleted=false;
                if(entityBase(key))for(Operation op:history.values())if(op.changes.has(key)&&op.changes.isNull(key)){deleted=true;break;}
                List<JSONObject> alternatives=entry.getValue();Object selected=deleted?JSONObject.NULL:alternatives.get(alternatives.size()-1).get("value");
                String counter=derivedCounter(key);if(counter!=null){long maximum=Long.MIN_VALUE;for(JSONObject alternative:alternatives){Object value=alternative.opt("value");if(value instanceof JSONObject){Object number=((JSONObject)value).opt(counter);if(number instanceof Number)maximum=Math.max(maximum,((Number)number).longValue());}}if(maximum!=Long.MIN_VALUE)selected=new JSONObject().put(counter,maximum);}String document=editedFiling(alternatives);if(document!=null&&selected instanceof JSONObject){TreeSet<String> stacks=new TreeSet<>();for(Map.Entry<String,List<JSONObject>> member:registers.entrySet())if(member.getKey().startsWith("memberships/")){List<JSONObject> rows=member.getValue();JSONObject row=rows.get(rows.size()-1).optJSONObject("value");if(row!=null&&document.equals(row.optString("document_id"))&&row.opt("stack_id") instanceof String)stacks.add(row.getString("stack_id"));}try{selected=copy((JSONObject)selected);}catch(IOException error){throw new IllegalStateException(error);}((JSONObject)selected).put("value",String.join(",",stacks));}out.put(key,selected);
            }return out;
        }
        JSONObject conflicts()throws JSONException,IOException {
            JSONObject out=new JSONObject();for(Map.Entry<String,List<JSONObject>> entry:registers.entrySet()) {
                List<JSONObject> alternatives=entry.getValue();String first=canonical(alternatives.get(0).get("value"));boolean conflict=false;for(JSONObject value:alternatives)if(!first.equals(canonical(value.get("value")))){conflict=true;break;}
                if(conflict && derivedCounter(entry.getKey())==null && editedFiling(alternatives)==null)out.put(entry.getKey(),new JSONArray(alternatives));
                String key=entry.getKey();if(!entityBase(key))continue;
                for(JSONObject deletion:alternatives)if(deletion.isNull("value")) {
                    JSONObject dot=deletion.getJSONObject("id");String replica=dot.getString("replica");long seq=dot.getLong("sequence");Operation delete=history.get(replica+":"+String.format(Locale.ROOT,"%016d",seq));JSONArray review=new JSONArray().put(deletion);
                    for(Operation op:history.values())if(!op.key.equals(delete.key)&&op.context.getOrDefault(replica,0L)<seq&&delete.context.getOrDefault(op.replica,0L)<op.sequence)for(String field:keys(op.changes))if(ownedBy(key,field,op.changes.get(field))&&!"page.text.commit".equals(op.evidenceHolder.optJSONObject("evidence")==null?null:op.evidenceHolder.optJSONObject("evidence").optString("kind"))){review.put(op.alternative(field));break;}
                    if(review.length()>1)out.put(key,review);break;
                }
            }
            for(Map.Entry<String,List<JSONObject>> entry:registers.entrySet())if(entry.getKey().startsWith("filing_review/")) {
                String document=entry.getKey().substring("filing_review/".length());List<JSONObject> review=new ArrayList<>(entry.getValue());
                for(JSONObject decision:entry.getValue()) {
                    Operation replace=origin(decision);JSONObject evidence=replace.evidenceHolder.optJSONObject("evidence");if(evidence!=null&&evidence.optString("kind").startsWith("baseline"))continue;
                    JSONArray original=decision.optJSONArray("value");if(original==null)continue;
                    for(Operation op:history.values())if(concurrent(op,replace)) {
                        boolean present=false;for(JSONObject row:review)if(origin(row).key.equals(op.key)){present=true;break;}if(present)continue;
                        TreeMap<String,Object> candidate=new TreeMap<>();for(int i=0;i<original.length();i++)candidate.put(canonical(original.get(i)),original.get(i));boolean touched=false;
                        for(String field:keys(op.changes))if(field.startsWith("memberships/")) {
                            JSONArray ids;try{ids=new JSONArray(field.substring("memberships/".length()));}catch(JSONException ignored){continue;}if(ids.length()!=2||!document.equals(ids.optString(0)))continue;
                            String stack=ids.getString(1),key=canonical(stack);candidate.remove(key);if(!op.changes.isNull(field))candidate.put(key,stack);touched=true;
                        }
                        if(touched)review.add(new JSONObject().put("id",op.json.getJSONObject("id")).put("value",new JSONArray(candidate.values())).put("evidence",op.evidenceHolder.get("evidence")));
                    }
                }
                boolean conflict=false;String first=canonical(review.get(0).get("value"));for(JSONObject row:review)if(!first.equals(canonical(row.get("value")))){conflict=true;break;}
                if(conflict){review.sort((a,b)->{try{return origin(a).key.compareTo(origin(b).key);}catch(JSONException error){throw new IllegalStateException(error);}});out.put(entry.getKey(),new JSONArray(review));}
            }
            for(Map.Entry<String,List<JSONObject>> entry:registers.entrySet())if(entry.getKey().startsWith("page_order/")) {
                String key=entry.getKey();List<JSONObject> review=new ArrayList<>(entry.getValue());
                for(JSONObject decision:entry.getValue()) {
                    JSONArray order=decision.optJSONArray("value");if(order==null)continue;Operation replace=origin(decision);
                    for(Operation op:history.values())if(concurrent(op,replace)) {
                        JSONObject evidence=op.evidenceHolder.optJSONObject("evidence");if(evidence!=null&&"page.text.commit".equals(evidence.optString("kind")))continue;
                        boolean present=false;for(JSONObject row:review)if(origin(row).key.equals(op.key)){present=true;break;}if(present)continue;
                        JSONArray observed=null;
                        for(Operation past:history.values())if(op.context.getOrDefault(past.replica,0L)>=past.sequence&&past.changes.optJSONArray(key)!=null) {
                            boolean superseded=false;
                            for(Operation later:history.values())if(op.context.getOrDefault(later.replica,0L)>=later.sequence&&later.context.getOrDefault(past.replica,0L)>=past.sequence&&later.changes.has(key)){superseded=true;break;}
                            if(!superseded)observed=past.changes.getJSONArray(key);
                        }
                        if(observed==null)continue;boolean touched=false;
                        for(String field:keys(op.changes))if(field.startsWith("pages/")) {
                            String raw=field.substring("pages/".length());int end=raw.indexOf("]/");if(end>=0)raw=raw.substring(0,end+1);
                            JSONArray ids;try{ids=new JSONArray(raw);}catch(JSONException ignored){continue;}if(ids.length()==0)continue;
                            String page=ids.optString(0);boolean wasPresent=false,isPresent=false;
                            for(int i=0;i<observed.length();i++)if(page.equals(observed.optString(i)))wasPresent=true;
                            for(int i=0;i<order.length();i++)if(page.equals(order.optString(i)))isPresent=true;
                            if(wasPresent&&!isPresent){touched=true;break;}
                        }
                        if(touched)review.add(new JSONObject().put("id",op.json.getJSONObject("id")).put("value",observed).put("evidence",op.evidenceHolder.get("evidence")));
                    }
                }
                boolean conflict=false;String first=canonical(review.get(0).get("value"));for(JSONObject row:review)if(!first.equals(canonical(row.get("value")))){conflict=true;break;}
                if(conflict){review.sort((a,b)->{try{return origin(a).key.compareTo(origin(b).key);}catch(JSONException error){throw new IllegalStateException(error);}});out.put(key,new JSONArray(review));}
            }return out;
        }
        JSONObject saved()throws JSONException {
            JSONArray operations=new JSONArray(),waiting=new JSONArray(),memberList=new JSONArray();for(String member:members)memberList.put(member);for(Operation op:history.values())operations.put(op.json);for(Operation op:pending.values())waiting.put(op.json);
            return new JSONObject().put("version",1).put("library",library).put("members",memberList).put("operations",operations).put("pending",waiting);
        }
        static State restore(JSONObject json)throws IOException,JSONException {
            exact(json,"version,library,members,operations,pending");if(integer(json.get("version"))!=1)throw invalid("unknown local state version");JSONArray members=json.getJSONArray("members");List<String> ids=new ArrayList<>();for(int i=0;i<members.length();i++)ids.add(members.getString(i));State state=new State(json.getString("library"),ids);
            for(String kind:new String[]{"operations","pending"}){JSONArray array=json.getJSONArray(kind);for(int start=0;start<array.length();start+=MAX_OPERATIONS){List<Operation> batch=new ArrayList<>();for(int i=start;i<Math.min(array.length(),start+MAX_OPERATIONS);i++)batch.add(new Operation(array.getJSONObject(i)));state.receive(batch);}}
            return state;
        }
    }
}
