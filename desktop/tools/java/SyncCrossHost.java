package org.stackdocs.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Cross-implementation link sync host (slice 6, D4a/D5). Runs the unchanged production kernel (SyncProtocol) and link
 * graph code (SyncGraph) on a host JVM; not Android SQLite/lifecycle evidence.
 *
 *   replay  scenario.json   feeds every operation through validateChanges and State.receive, prints the expectation shape.
 *   capture outdir          builds operations with the phone's link writer (linkWriter/linkChanges/linkMarker) and writes
 *                           B-*.json scenarios (operations plus the Java expectation) for the Rust side to replay.
 *
 * Expectation shape (identical to the Rust side): links (projectLinks, normalized), skipped, conflicted, conflict_keys,
 * link_values (every link_records/ register value), alternatives (alternative count per link register),
 * enable_ops (id, context and keys of every links.enable operation).
 */
public final class SyncCrossHost {
    static final String OWNER = "org.example.amipro";
    static final String LIBRARY = "10000000-0000-0000-0000-000000000000";
    static final String PHONE = "20000000-0000-0000-0000-000000000001";
    static final String DESKTOP = "20000000-0000-0000-0000-000000000002";

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "replay": {
                JSONObject scenario = ArchiveJson.read(Files.readString(Path.of(args[1])));
                System.out.println(SyncProtocol.canonical(replay(scenario)));
                return;
            }
            case "capture":
                capture(Path.of(args[1]));
                return;
            default:
                throw new IllegalArgumentException("Unknown cross probe");
        }
    }

    static JSONObject replay(JSONObject scenario) throws Exception {
        JSONArray members = scenario.getJSONArray("members");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < members.length(); i++) ids.add(members.getString(i));
        SyncProtocol.State state = new SyncProtocol.State(scenario.getString("library"), ids);
        JSONArray wire = scenario.getJSONArray("operations");
        List<SyncProtocol.Operation> batch = new ArrayList<>();
        for (int i = 0; i < wire.length(); i++) {
            SyncProtocol.Operation operation = new SyncProtocol.Operation(wire.getJSONObject(i));
            SyncGraph.validateChanges(operation.changes);
            batch.add(operation);
        }
        state.receive(batch);
        if (!state.pending.isEmpty()) throw new AssertionError("operations left pending: " + state.pending.keySet());
        return expectation(state);
    }

    static JSONObject expectation(SyncProtocol.State state) throws Exception {
        JSONObject values = state.values(), conflicts = state.conflicts();
        Set<String> conflicted = new TreeSet<>(SyncProtocol.keys(conflicts));
        JSONObject projected = SyncGraph.projectLinks(values, conflicted);
        List<JSONObject> links = new ArrayList<>();
        JSONArray raw = projected.getJSONArray("links");
        for (int i = 0; i < raw.length(); i++) {
            JSONObject link = new JSONObject(raw.getJSONObject(i).toString());
            link.remove("presence_absent");
            if ("gone".equals(link.getString("outcome"))) {
                link = new JSONObject().put("key", link.getString("key")).put("owner", link.getString("owner"))
                        .put("link_id", link.getString("link_id")).put("outcome", "gone");
            }
            links.add(link);
        }
        links.sort((a, b) -> a.optString("key").compareTo(b.optString("key")));
        JSONObject linkValues = new JSONObject(), alternatives = new JSONObject();
        for (String key : SyncProtocol.keys(values)) if (SyncGraph.isLinkKey(key)) linkValues.put(key, values.get(key));
        for (String key : state.registers.keySet()) if (SyncGraph.isLinkKey(key)) alternatives.put(key, state.registers.get(key).size());
        List<JSONObject> enables = new ArrayList<>();
        for (SyncProtocol.Operation op : state.history.values()) {
            JSONObject evidence = op.json.optJSONObject("evidence");
            if (evidence == null || !"links.enable".equals(evidence.optString("kind"))) continue;
            JSONArray keys = new JSONArray();
            for (String key : SyncProtocol.keys(op.changes)) keys.put(key);
            enables.add(new JSONObject().put("id", op.json.getJSONObject("id")).put("context", op.json.getJSONObject("context")).put("keys", keys));
        }
        enables.sort((a, b) -> {
            try {
                int order = a.getJSONObject("id").getString("replica").compareTo(b.getJSONObject("id").getString("replica"));
                return order != 0 ? order : Long.compare(a.getJSONObject("id").getLong("sequence"), b.getJSONObject("id").getLong("sequence"));
            } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        });
        JSONArray conflictKeys = new JSONArray();
        for (String key : conflicted) conflictKeys.put(key);
        return new JSONObject().put("links", new JSONArray(links)).put("skipped", projected.getInt("skipped"))
                .put("conflicted", projected.getInt("conflicted")).put("conflict_keys", conflictKeys)
                .put("link_values", linkValues).put("alternatives", alternatives).put("enable_ops", new JSONArray(enables));
    }

    // ----- capture ---------------------------------------------------------------------------------------------

    static String uuid(int n) { return String.format("00000000-0000-0000-0000-%012d", n); }
    static String repeat(String unit, int count) { StringBuilder out = new StringBuilder(); for (int i = 0; i < count; i++) out.append(unit); return out.toString(); }

    static JSONObject row(int n, String title, String text) throws Exception {
        return new JSONObject().put("owner", OWNER).put("link_id", uuid(7000 + n)).put("kind", "doc").put("added_at", 10 + n)
                .put("hash", repeat("a", 64)).put("mime", "application/pdf").put("modified", 1700000000000L).put("shared", 0).put("protected", 0)
                .put("title", title).put("title_user_edited", 0)
                .put("filen", new JSONObject().put("uuid", uuid(9000 + n)).put("path", "/docs/" + n + ".pdf"))
                .put("text", text).put("preview", JSONObject.NULL)
                .put("presence", new JSONObject().put("state", "live").put("reason", "").put("since", 0));
    }
    /** The row as the phone's link sync reads a record the partner shares (origin, name on the badge, archive generation). */
    static JSONObject foreign(JSONObject row, String sharedBy, long generation) throws Exception {
        return row.put("shared", 1).put("origin", "foreign").put("shared_by", sharedBy).put("foreign_generation", generation);
    }
    static JSONObject presence(JSONObject row, String state, String reason, long since) throws Exception {
        return row.put("presence", new JSONObject().put("state", state).put("reason", reason).put("since", since));
    }

    /** Runs the phone writer on the rows, merges the cells into `changes`, collects a note per row. */
    static void write(JSONObject changes, JSONArray notes, String name, JSONObject row, long captured) throws Exception {
        JSONObject result = SyncGraph.linkWriter(row, captured);
        JSONObject cells = SyncGraph.linkChanges(result);
        for (String key : SyncProtocol.keys(cells)) changes.put(key, cells.get(key));
        notes.put(new JSONObject().put("row", name).put("outcome", result.getString("outcome")).put("reasons", result.getJSONArray("reasons")).put("cells", cells.length()));
    }

    static JSONObject scenario(String name, List<JSONObject> operations, SyncProtocol.State state, JSONArray notes) throws Exception {
        JSONArray wire = new JSONArray();
        for (JSONObject operation : operations) wire.put(operation);
        return new JSONObject().put("name", name).put("library", LIBRARY).put("members", new JSONArray().put(PHONE).put(DESKTOP))
                .put("operations", wire).put("notes", notes).put("expect", expectation(state));
    }

    static void capture(Path out) throws Exception {
        SyncProtocol.State phone = new SyncProtocol.State(LIBRARY, java.util.Arrays.asList(PHONE, DESKTOP));
        List<JSONObject> operations = new ArrayList<>();
        JSONArray notes = new JSONArray();
        long captured = 0;

        // Operation 1: the enable marker and every row shape the writer handles.
        JSONObject first = SyncGraph.linkMarker(PHONE, 1700000000000L);
        write(first, notes, "plain", row(1, "Budget", "quarterly zebra budget")
                .put("preview", new JSONObject().put("sha256", repeat("b", 64)).put("length", 4096)), captured++);
        write(first, notes, "protected", row(2, "Secret", "never leaves the phone").put("protected", 1), captured++);
        write(first, notes, "empty-text", row(3, "Plain", ""), captured++);
        write(first, notes, "text-over-1MiB", row(4, "Big text", repeat("a", (1 << 20) + 1)), captured++);
        write(first, notes, "preview-over-32MiB", row(5, "Big preview", "x")
                .put("preview", new JSONObject().put("sha256", repeat("c", 64)).put("length", (32L << 20) + 1)), captured++);
        write(first, notes, "title-over-limit-refused", row(6, repeat("t", 201), "x"), 0);
        write(first, notes, "filen-path-over-1024", row(7, "Long path", "x")
                .put("filen", new JSONObject().put("uuid", uuid(9007)).put("path", repeat("p", 1025))), captured++);
        write(first, notes, "gone-from-start", presence(row(8, "Gone", "x"), "gone", "forgotten", 1700000000001L), 0);
        write(first, notes, "trashed", presence(row(9, "Trashed", "trashed words"), "trashed", "user", 1700000000002L), captured++);
        write(first, notes, "text-with-nul", row(10, "Nul", "a\u0000b"), captured++);
        write(first, notes, "over-link-limit-refused", row(11, "Too many", "x"), 1000);
        write(first, notes, "no-filen", row(12, "No filen", "words").put("filen", JSONObject.NULL), captured++);
        write(first, notes, "protected-with-preview", row(13, "Secret preview", "s").put("protected", 1)
                .put("preview", new JSONObject().put("sha256", repeat("d", 64)).put("length", 10)), captured++);
        // Slice 7 (S7-4): the partner's shared records. 15 live with text; 16 unshared (trashed); 17 removed for good later.
        write(first, notes, "foreign-live", foreign(row(15, "Shared budget", "shared giraffe words"), "Sam", 3), captured++);
        write(first, notes, "foreign-unshared-trashed", presence(foreign(row(16, "Shared old", "old shared words"), "Sam", 2), "trashed", "deleted", 1700000000004L), captured++);
        write(first, notes, "foreign-to-be-gone", foreign(row(17, "Shared short-lived", "short words"), "Sam", 1), captured++);
        write(first, notes, "foreign-protected", foreign(row(18, "Shared secret", "never copied").put("protected", 1), "Sam", 1), captured++);
        write(first, notes, "foreign-empty-name-refused", foreign(row(19, "Nameless", "x"), "", 1), 0);
        operations.add(phone.local(PHONE, first, new JSONObject().put("kind", "links.enable")).json);

        // Operation 2: a later capture. The trashed row goes gone (null cells), the plain row is edited, a new row is added.
        JSONObject second = new JSONObject();
        JSONArray later = new JSONArray();
        write(second, later, "trashed-now-gone", presence(row(9, "Trashed", "trashed words"), "gone", "deleted", 1700000000003L), 0);
        write(second, later, "plain-edited", row(1, "Budget 2", "quarterly zebra budget revised").put("hash", repeat("e", 64)).put("title_user_edited", 1)
                .put("preview", new JSONObject().put("sha256", repeat("b", 64)).put("length", 4096)), 0);
        write(second, later, "new-row", row(14, "Added later", "later words"), captured++);
        write(second, later, "foreign-updated", foreign(row(15, "Shared budget v2", "shared giraffe words revised").put("hash", repeat("f", 64)), "Samantha", 4), 0);
        write(second, later, "foreign-now-gone", presence(foreign(row(17, "Shared short-lived", "short words"), "Sam", 1), "gone", "deleted", 1700000000005L), 0);
        operations.add(phone.local(PHONE, second, new JSONObject().put("kind", "link.capture")).json);
        for (int i = 0; i < later.length(); i++) notes.put(later.get(i));

        JSONObject whole = scenario("java-capture", operations, phone, notes);
        // The same operations delivered in reverse order must project the same (the kernel holds the later one pending).
        JSONObject reversed = new JSONObject(whole.toString());
        JSONArray flipped = new JSONArray();
        for (int i = operations.size() - 1; i >= 0; i--) flipped.put(operations.get(i));
        reversed.put("name", "java-capture-reversed").put("operations", flipped);
        Files.writeString(out.resolve("B-java-capture.json"), SyncProtocol.canonical(whole) + "\n", StandardCharsets.UTF_8);
        Files.writeString(out.resolve("B-java-capture-reversed.json"), SyncProtocol.canonical(reversed) + "\n", StandardCharsets.UTF_8);
    }
}
