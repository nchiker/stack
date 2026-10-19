package org.stackdocs.app;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Host conformance runner. Never claims Android SQLite/renderer/lifecycle evidence. */
public final class SyncGolden {
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
        System.out.println(new JSONObject().put("status","passed").put("cases",cases.length()).put("android_runtime",false));
    }
}
