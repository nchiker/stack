package org.stackdocs.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Host entry point for the unchanged production identity-review implementation. */
public final class SyncAdoptionHost {
    public static void main(String[] args)throws Exception {
        JSONObject header=ArchiveJson.read(Files.readString(Path.of(args[1]))),local=ArchiveJson.read(Files.readString(Path.of(args[2])));
        List<SyncProtocol.Operation> operations=new ArrayList<>();JSONArray ops=header.getJSONArray("operations");for(int i=0;i<ops.length();i++)operations.add(new SyncProtocol.Operation(ops.getJSONObject(i)));
        String replica=args[3];JSONObject current=SyncAdoption.review(header,operations,local,replica);
        if(args[0].equals("review")){System.out.println(current);return;}
        JSONObject review=ArchiveJson.read(Files.readString(Path.of(args[4])));SyncAdoption.revalidate(current,review);
        List<String> members=new ArrayList<>();JSONArray list=review.getJSONArray("members");for(int i=0;i<list.length();i++)members.add(list.getString(i));
        SyncProtocol.State state=new SyncProtocol.State(header.getString("library"),members);state.receive(operations);SyncAdoption.addLocal(state,local,review,replica);
        JSONArray all=new JSONArray();for(SyncProtocol.Operation operation:state.history.values())all.put(operation.json);
        System.out.println(new JSONObject().put("graph",SyncGraph.project(state.values())).put("operations",all).put("frontier",SyncProtocol.json(state.frontier)).put("conflicts",state.conflicts()));
    }
}
