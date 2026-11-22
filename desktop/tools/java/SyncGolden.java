package org.stackdocs.app;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Host conformance runner. Never claims Android SQLite/renderer/lifecycle evidence. */
public final class SyncGolden {
    /** D3 landed (Java SyncGraph link arm and writer self-check): the link checks always run. `--enforce-graph` stays accepted as a no-op. */
    static final boolean GRAPH_LINK_ENFORCED=true;
    public static void main(String[] args)throws Exception {
        JSONObject golden=ArchiveJson.read(Files.readString(Path.of(args[0]),StandardCharsets.UTF_8));JSONArray cases=golden.getJSONArray("cases");
        for(int n=0;n<cases.length();n++) {
            JSONObject test=cases.getJSONObject(n);JSONArray members=test.getJSONArray("members");List<String> ids=new ArrayList<>();for(int i=0;i<members.length();i++)ids.add(members.getString(i));
            SyncProtocol.State state=new SyncProtocol.State(test.getString("library"),ids);JSONArray operations=test.getJSONArray("operations");
            for(int i=0;i<operations.length();i++) {
                state.receive(Collections.singletonList(new SyncProtocol.Operation(operations.getJSONObject(i))));
                state=SyncProtocol.State.restore(ArchiveJson.read(SyncProtocol.canonical(state.saved())));
            }
            JSONObject actual=new JSONObject().put("values",state.values()).put("frontier",SyncProtocol.json(state.frontier)).put("conflicts",new JSONArray(SyncProtocol.keys(state.conflicts()))).put("pending",state.pending.size());
            if(!SyncProtocol.canonical(actual).equals(SyncProtocol.canonical(test.getJSONObject("expected"))))throw new AssertionError(test.getString("name")+": "+actual);
            if(operations.length()>0) {
                JSONObject corrupt=SyncProtocol.copy(operations.getJSONObject(0));corrupt.put("evidence","another payload");String before=SyncProtocol.canonical(state.saved());
                try {state.receive(Collections.singletonList(new SyncProtocol.Operation(corrupt)));throw new AssertionError("reused operation accepted");}catch(java.io.IOException expected){}
                if(!before.equals(SyncProtocol.canonical(state.saved())))throw new AssertionError("failed batch mutated state");
            }
        }
        JSONArray rejects=golden.getJSONArray("reject_cases");
        for(int n=0;n<rejects.length();n++) {
            JSONObject test=rejects.getJSONObject(n);List<String> ids=new ArrayList<>();JSONArray members=test.getJSONArray("members");for(int i=0;i<members.length();i++)ids.add(members.getString(i));
            SyncProtocol.State state=new SyncProtocol.State(test.getString("library"),ids);String before=SyncProtocol.canonical(state.saved());
            try{state.receive(Collections.singletonList(new SyncProtocol.Operation(test.getJSONObject("operation"))));throw new AssertionError(test.getString("name"));}catch(java.io.IOException expected){}
            if(!before.equals(SyncProtocol.canonical(state.saved())))throw new AssertionError("rejected vector mutated state");
        }
        JSONObject graph=graphCases(golden.getJSONObject("graph_cases"),GRAPH_LINK_ENFORCED||(args.length>1&&"--enforce-graph".equals(args[1])));
        System.out.println(new JSONObject().put("status","passed").put("graph_cases",graph).put("cases",cases.length()).put("rejected_cases",rejects.length()).put("android_runtime",false));
    }

    /** `{"repeat":s,"count":n}` stands for s repeated n times. */
    static Object expand(Object value)throws Exception {
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;
            if(object.length()==2&&object.has("repeat")){String unit=object.getString("repeat");StringBuilder out=new StringBuilder();for(int i=0,n=object.getInt("count");i<n;i++)out.append(unit);return out.toString();}
            JSONObject copy=new JSONObject();for(Iterator<String> keys=object.keys();keys.hasNext();){String key=keys.next();copy.put(key,expand(object.get(key)));}return copy;
        }
        if(value instanceof JSONArray) {JSONArray array=(JSONArray)value,copy=new JSONArray();for(int i=0;i<array.length();i++)copy.put(expand(array.get(i)));return copy;}
        return value;
    }
    /** Parses and schema-checks graph_cases. Link receiver/writer checks run only when enforced (D3); otherwise reported "pending D3". */
    static final List<String> FAILURES=new ArrayList<>();
    static void fail(String message){FAILURES.add(message);}
    static JSONObject graphCases(JSONObject graph,boolean enforce)throws Exception {
        if(!"org.stackdocs.link-graph-golden".equals(graph.getString("format")))throw new AssertionError("graph_cases format");
        graph=(JSONObject)expand(graph);Set<String> seen=new HashSet<>();JSONObject counts=new JSONObject();
        for(String list:new String[]{"receiver_rejects","receiver_accepts_skips","writer_refuses","enable_ops"}) {
            JSONArray items=graph.getJSONArray(list);if(items.length()==0)throw new AssertionError(list+" empty");counts.put(list,items.length());
            for(int i=0;i<items.length();i++) {
                JSONObject test=items.getJSONObject(i);String name=test.getString("name");if(!seen.add(list+"/"+name))throw new AssertionError("duplicate "+name);
                switch(list) {
                    case "receiver_rejects":test.getJSONObject("changes");test.getString("why");break;
                    case "receiver_accepts_skips":test.getJSONObject("changes");test.getString("projection");break;
                    case "writer_refuses":test.getJSONObject("input").getJSONObject("row");test.getJSONObject("expect").getString("outcome");break;
                    default:test.getJSONObject("operation_changes");test.getJSONObject("expect").getString("kernel");test.getJSONObject("expect").getString("receiver");
                }
            }
        }
        // Kernel-only half of the enable ops: runs today.
        JSONArray enable=graph.getJSONArray("enable_ops");
        for(int i=0;i<enable.length();i++) {
            JSONObject test=enable.getJSONObject(i);String library="10000000-0000-0000-0000-000000000000",replica="20000000-0000-0000-0000-000000000001";
            SyncProtocol.State state=new SyncProtocol.State(library,Collections.singletonList(replica));
            JSONObject operation=new JSONObject().put("version",1).put("library",library).put("epoch",1).put("id",new JSONObject().put("replica",replica).put("sequence",1)).put("context",new JSONObject()).put("changes",test.getJSONObject("operation_changes")).put("evidence",JSONObject.NULL);
            boolean accepted;try{state.receive(Collections.singletonList(new SyncProtocol.Operation(operation)));accepted=true;}catch(java.io.IOException rejected){accepted=false;}
            if(accepted!="accepted".equals(test.getJSONObject("expect").getString("kernel")))throw new AssertionError("kernel enable op: "+test.getString("name"));
        }
        counts.put("status",enforce?"enforced":"not enforced");
        if(!enforce)return counts;
        for(Object item:iterable(graph.getJSONArray("receiver_rejects"))) {
            JSONObject test=(JSONObject)item;boolean accepted;
            try{graphValidate(test.getJSONObject("changes"));accepted=true;}catch(java.io.IOException|org.json.JSONException rejected){accepted=false;}
            if(accepted)fail("must reject: "+test.getString("name"));
        }
        for(Object item:iterable(graph.getJSONArray("receiver_accepts_skips"))) {
            JSONObject test=(JSONObject)item;graphValidate(test.getJSONObject("changes"));
            JSONObject projected=graphProject(test.getJSONObject("changes"));String want=test.getString("projection");JSONArray links=projected.getJSONArray("links");
            int skipped=projected.getInt("skipped");
            if(want.equals("skipped")){if(links.length()!=0||skipped!=1)fail("projection must skip: "+test.getString("name")+" "+projected);}
            else if(want.equals("not_applicable")){if(links.length()!=0||skipped!=0)fail("projection not applicable: "+test.getString("name")+" "+projected);}
            else if(links.length()!=1||skipped!=0||!want.equals(links.getJSONObject(0).getString("outcome")))fail("projection "+want+": "+test.getString("name")+" "+projected);
            if(test.has("skip_count")&&test.getInt("skip_count")!=skipped)fail("skip_count: "+test.getString("name"));
        }
        for(Object item:iterable(graph.getJSONArray("enable_ops"))) {
            JSONObject test=(JSONObject)item;String want=test.getJSONObject("expect").getString("receiver");if(!"accepted".equals(want)&&!"rejected".equals(want))continue;
            boolean accepted;try{graphValidate(test.getJSONObject("operation_changes"));accepted=true;}catch(java.io.IOException|org.json.JSONException rejected){accepted=false;}
            if(accepted!="accepted".equals(want))fail("enable op receiver: "+test.getString("name"));
        }
        for(Object item:iterable(graph.getJSONArray("writer_refuses"))) {
            JSONObject test=(JSONObject)item;JSONObject actual=writerSelfCheck(test.getJSONObject("input"));JSONObject expect=test.getJSONObject("expect");
            if(!actual.getString("outcome").equals(expect.getString("outcome")))fail("writer outcome: "+test.getString("name"));
            if(!"row_refused".equals(actual.getString("outcome")))try{graphValidate(graphChanges(actual));}catch(java.io.IOException|org.json.JSONException rejected){fail("writer emitted what the receiver rejects: "+test.getString("name")+" "+rejected.getMessage());}
            JSONObject cells=expect.optJSONObject("cells");
            if(cells!=null)for(Iterator<String> keys=cells.keys();keys.hasNext();){String suffix=keys.next();if(!SyncProtocol.canonical(new JSONObject().put("v",cells.get(suffix))).equals(SyncProtocol.canonical(new JSONObject().put("v",actual.getJSONObject("cells").opt(suffix)==null?JSONObject.NULL:actual.getJSONObject("cells").get(suffix)))))fail("writer cell "+suffix+": "+test.getString("name"));}
            JSONArray absent=expect.optJSONArray("absent");
            if(absent!=null)for(int i=0;i<absent.length();i++){Object v=actual.getJSONObject("cells").opt(absent.getString(i));if(v!=null&&v!=JSONObject.NULL)fail("writer must not emit "+absent.getString(i)+": "+test.getString("name"));}
        }
        if(!FAILURES.isEmpty())throw new AssertionError(FAILURES.size()+" graph case failures: "+FAILURES);
        return counts;
    }
    /** Reflective so this file compiles with SyncGraph (which needs the archive host classes) only on the classpath. */
    static void graphValidate(JSONObject changes)throws Exception {
        try{Class.forName("org.stackdocs.app.SyncGraph").getDeclaredMethod("validateChanges",JSONObject.class).invoke(null,changes);}
        catch(java.lang.reflect.InvocationTargetException failed){if(failed.getCause() instanceof Exception)throw (Exception)failed.getCause();throw failed;}
    }
    static List<Object> iterable(JSONArray array)throws Exception {List<Object> out=new ArrayList<>();for(int i=0;i<array.length();i++)out.add(array.get(i));return out;}
    /** The real writer self-check (SyncGraph.linkWriter); input has "row" and optional "context". Returns {"outcome":..., "cells":{suffix:value}}. */
    static JSONObject writerSelfCheck(JSONObject input)throws Exception {
        long counted=input.optJSONObject("context")==null?0:input.getJSONObject("context").optLong("live_or_trashed_links_already_captured",0);
        try{return (JSONObject)Class.forName("org.stackdocs.app.SyncGraph").getDeclaredMethod("linkWriter",JSONObject.class,long.class).invoke(null,input.getJSONObject("row"),counted);}
        catch(java.lang.reflect.InvocationTargetException failed){if(failed.getCause() instanceof Exception)throw (Exception)failed.getCause();throw failed;}
    }
    static JSONObject graphChanges(JSONObject writerResult)throws Exception {
        try{return (JSONObject)Class.forName("org.stackdocs.app.SyncGraph").getDeclaredMethod("linkChanges",JSONObject.class).invoke(null,writerResult);}
        catch(java.lang.reflect.InvocationTargetException failed){if(failed.getCause() instanceof Exception)throw (Exception)failed.getCause();throw failed;}
    }
    static JSONObject graphProject(JSONObject changes)throws Exception {
        try{return (JSONObject)Class.forName("org.stackdocs.app.SyncGraph").getDeclaredMethod("projectLinks",JSONObject.class,Set.class).invoke(null,changes,null);}
        catch(java.lang.reflect.InvocationTargetException failed){if(failed.getCause() instanceof Exception)throw (Exception)failed.getCause();throw failed;}
    }
}
