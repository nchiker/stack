package org.stackdocs.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Test helper for the Filen Files device checks: seed one linked document with a given Filen UUID, and show what Stack now holds. */
final class FilenSeedChecks {
    private FilenSeedChecks() {}

    /** Publishes (as Stack itself) a shared document whose filen locator has this UUID and path. Prints nothing secret. */
    static String seed(Context context, String uuid, String path) throws Exception {
        StackApplication app = (StackApplication) context.getApplicationContext();
        app.libraryReady.await();
        String hash = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
        String json = new JSONObject().put("contract", 1).put("owner", context.getPackageName()).put("link_id", UUID.randomUUID().toString())
            .put("kind", "doc").put("title", "Filen seed " + uuid.substring(0, 8)).put("mime", "application/x-amipro-document")
            .put("modified", System.currentTimeMillis()).put("hash", hash).put("shared", true)
            .put("locators", new JSONArray().put(new JSONObject().put("type", "filen").put("uuid", uuid).put("path", path))).toString();
        app.io.submit(() -> app.library.links.publish(LinkRecord.parse(json), "filen seed text", null)).get();
        return show(context, uuid);
    }

    /** Calls Filen Files' provider as Stack (a pinned caller): method `locate` (argument = document URI) or `lookup` (argument = UUID). Prints the reply fields. */
    static String call(Context context, String method, String argument) {
        try {
            android.os.Bundle reply = context.getContentResolver().call(android.net.Uri.parse("content://app.filenfiles.stacklink"), method, argument, null);
            if (reply == null) return method + ": no answer (provider missing or refused)\n";
            StringBuilder out = new StringBuilder(method).append(':');
            for (String key : new java.util.TreeSet<>(reply.keySet())) out.append(' ').append(key).append('=').append(String.valueOf(reply.get(key)));
            return out.append('\n').toString();
        } catch (RuntimeException error) { return method + ": " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"; }
    }

    /** One line per record that carries this UUID: state, shared flag, removed reason, locators. */
    static String show(Context context, String uuid) throws Exception {
        StackApplication app = (StackApplication) context.getApplicationContext();
        app.libraryReady.await();
        return app.io.submit(() -> {
            StringBuilder out = new StringBuilder();
            try (android.database.Cursor c = app.library.links.debugRows(uuid)) {
                while (c.moveToNext()) out.append("state=").append(c.getString(0)).append(" shared=").append(c.getInt(1))
                    .append(" reason=").append(c.getString(2)).append(" locators=").append(c.getString(3).replace("\\/", "/")).append('\n');
            }
            return out.length() == 0 ? "no record with that Filen UUID\n" : out.toString();
        }).get();
    }
}
