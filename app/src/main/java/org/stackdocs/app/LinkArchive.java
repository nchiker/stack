package org.stackdocs.app;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Link records in archive format 4: row capture, strict validation and the restore rules (see STACK_ARCHIVE_FORMAT.md). */
final class LinkArchive {
    static final int LINK_LIMIT = 1000;
    static final String[] FIELDS = "id,owner,link_id,kind,title,mime,modified,hash,shared,protected,locators,state,title_user_edited,added_at,text".split(",");
    private LinkArchive() {}

    static String previewPath(String id) { return "previews/" + id + ".pdf"; }

    /** All link rows with their text, in id order. Runs inside the snapshot transaction. */
    static JSONArray capture(SQLiteDatabase db) throws JSONException {
        JSONArray out = new JSONArray();
        try (Cursor c = db.rawQuery("SELECT r.id,r.owner,r.link_id,r.kind,r.title,r.mime,r.modified,r.hash,r.shared,r.protected,r.locators,r.state,"
                + "r.title_user_edited,r.added_at,COALESCE((SELECT body FROM link_search WHERE record_id=r.id),'') FROM link_records r ORDER BY r.id", null)) {
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < FIELDS.length; i++) {
                    int type = c.getType(i);
                    row.put(FIELDS[i], type == Cursor.FIELD_TYPE_INTEGER ? (Object) c.getLong(i) : c.getString(i));
                }
                out.put(row);
            }
        }
        return out;
    }

    /** Strict check of every row; returns the ids so previews can be matched to links. */
    static Set<String> validate(JSONArray links) throws IOException {
        Set<String> ids = new HashSet<>(), keys = new HashSet<>();
        try {
            if (links.length() > LINK_LIMIT) throw new IOException("Backup contains too many linked documents.");
            for (int i = 0; i < links.length(); i++) {
                JSONObject row = links.getJSONObject(i);
                if (row.length() != FIELDS.length) throw invalid();
                for (String field : FIELDS) if (!row.has(field)) throw invalid();
                String id = string(row, "id", 36);
                try { if (!UUID.fromString(id).toString().equals(id)) throw invalid(); } catch (IllegalArgumentException e) { throw invalid(); }
                if (!ids.add(id)) throw invalid();
                // LinkRecord does the strict field checks (owner, link_id, kind, title, mime, hash, locators, modified).
                JSONObject record = new JSONObject();
                record.put("contract", LinkRecord.CONTRACT);
                for (String f : new String[]{"owner", "link_id", "kind", "title", "mime", "hash"}) record.put(f, string(row, f, 4096));
                record.put("modified", row.get("modified"));
                record.put("shared", bool(row, "shared")).put("protected", bool(row, "protected"));
                String locators = string(row, "locators", 16 * 1024);
                record.put("locators", new JSONArray(locators));
                LinkRecord parsed = LinkRecord.parse(record.toString());
                if (!keys.add(parsed.owner + "\n" + parsed.linkId)) throw invalid();
                String state = string(row, "state", 32);
                if (!state.matches("[a-z_]{1,32}")) throw invalid();
                bool(row, "title_user_edited");
                Object added = row.get("added_at");
                if (!(added instanceof Integer || added instanceof Long) || ((Number) added).longValue() < 0) throw invalid();
                LinkRecord.checkText(string(row, "text", LinkRecord.MAX_TEXT));
            }
        } catch (JSONException e) { throw invalid(); }
        return ids;
    }

    private static boolean bool(JSONObject row, String key) throws JSONException, IOException {
        Object v = row.get(key);
        if (!(v instanceof Integer || v instanceof Long)) throw invalid();
        long n = ((Number) v).longValue();
        if (n != 0 && n != 1) throw invalid();
        return n == 1;
    }

    private static String string(JSONObject row, String key, int max) throws JSONException, IOException {
        Object v = row.get(key);
        if (!(v instanceof String) || ((String) v).length() > max) throw invalid();
        return (String) v;
    }

    private static IOException invalid() {
        return new IOException("The backup has invalid or unsupported metadata, paths or relationships. Choose an unchanged Stack backup.");
    }
}
