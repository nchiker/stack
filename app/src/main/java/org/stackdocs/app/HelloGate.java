package org.stackdocs.app;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Hello gate for link sync (design section 5, D4a). Pure logic, no Android classes, so host
 * tests run the production code. The hello gains two optional fields:
 * {@code "features":["links"]} and {@code "enabled":true|false}; absent means "does not announce".
 */
final class HelloGate {
    private HelloGate() {}

    /** Ladder levels, most capable first. */
    static final int FRONTIER_FEATURES = 0, FRONTIER = 1, PLAIN = 2;
    static final long REFUSAL_MEMORY_MS = 15 * 60 * 1000L;
    static final String LINKS = "links";
    private static final int MAX_FEATURES = 16, MAX_FEATURE_UNITS = 64;

    /** What the peer's hello said. Package-private API for P1 and D5. */
    static final class Peer {
        /** The peer's hello carried a frontier. */
        final boolean hasFrontier;
        /** The peer announced {@code links} in {@code features}. */
        final boolean announcesLinks;
        /** The peer's {@code enabled}; null when absent. */
        final Boolean enabled;
        Peer(boolean hasFrontier, boolean announcesLinks, Boolean enabled) {
            this.hasFrontier = hasFrontier; this.announcesLinks = announcesLinks; this.enabled = enabled;
        }
        /** True only for a peer that announced links and reports enabled=true. */
        boolean linksEnabled() { return announcesLinks && Boolean.TRUE.equals(enabled); }
    }

    /** Called from the session after the peer hello is validated, before the equal-frontier decision. */
    interface Listener { void peerHello(Peer peer) throws Exception; }

    /** Validates the field set and the two optional fields. Every pre-D4a hello stays valid. */
    static Peer parse(JSONObject remote) throws IOException {
        boolean frontier = remote.has("frontier"), features = remote.has("features"), enabled = remote.has("enabled");
        String names = "version,group,replica" + (frontier ? ",frontier" : "") + (features ? ",features" : "") + (enabled ? ",enabled" : "");
        SyncProtocol.exact(remote, names);
        boolean links = false;
        if (features) {
            Object value = remote.opt("features");
            if (!(value instanceof JSONArray)) throw SyncProtocol.invalid("hello features must be an array");
            JSONArray array = (JSONArray) value;
            if (array.length() > MAX_FEATURES) throw SyncProtocol.invalid("too many hello features");
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                if (!(item instanceof String) || ((String) item).isEmpty() || ((String) item).length() > MAX_FEATURE_UNITS
                    || !seen.add((String) item)) throw SyncProtocol.invalid("hello features must be distinct short strings");
                if (LINKS.equals(item)) links = true; // unknown names are ignored: a newer peer may announce more
            }
        }
        Boolean flag = null;
        if (enabled) {
            Object value = remote.opt("enabled");
            if (!(value instanceof Boolean)) throw SyncProtocol.invalid("hello enabled must be a boolean");
            flag = (Boolean) value;
        }
        return new Peer(frontier, links, flag);
    }

    /** Levels this build starts from: the features level only when announcing is switched on. */
    static int firstLevel(boolean announce) { return announce ? FRONTIER_FEATURES : FRONTIER; }

    /** Adds the level's optional fields. {@code frontier} is the JSON frontier; never adds features unless announce. */
    static JSONObject apply(JSONObject hello, int level, boolean announce, JSONObject frontier, boolean linksEnabled) throws JSONException {
        if (level <= FRONTIER_FEATURES && !announce) level = FRONTIER;
        if (level <= FRONTIER) hello.put("frontier", frontier);
        if (level == FRONTIER_FEATURES) hello.put("features", new JSONArray().put(LINKS)).put("enabled", linksEnabled);
        return hello;
    }

    /** True when {@code level} sent a frontier (so a refusal can still fall down a level). */
    static boolean hasLowerLevel(int level) { return level < PLAIN; }

    /** Equal-frontier skip (M1): skip unless both announce links and not both report enabled. */
    static boolean mayEndEqual(boolean localAnnounced, boolean localEnabled, Peer peer) {
        if (!localAnnounced || !peer.announcesLinks) return true;
        return localEnabled && Boolean.TRUE.equals(peer.enabled);
    }

    // ---- 15 minute memory of a refused level ----
    private static volatile int rememberedLevel = FRONTIER_FEATURES;
    private static volatile long rememberedUntil;

    /** Level to start at: the remembered one while the refusal is fresh, never above this build's first level. */
    static int startLevel(boolean announce, long now) {
        int first = firstLevel(announce);
        return now < rememberedUntil ? Math.max(first, rememberedLevel) : first;
    }
    /** A level was refused: start at {@code next} for 15 minutes. */
    static void refused(int next, long now) { rememberedLevel = next; rememberedUntil = now + REFUSAL_MEMORY_MS; }
    static void forget() { rememberedLevel = FRONTIER_FEATURES; rememberedUntil = 0; }
}
