package org.stackdocs.app;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/** D4a host checks of the production HelloGate (pure logic; no sockets, no Android). */
public final class HelloGateHost {
    static int failures;
    static void check(boolean ok, String name) { if (!ok) { failures++; System.out.println("FAIL " + name); } }
    static JSONObject base() throws Exception { return new JSONObject().put("version", 1).put("group", "g").put("replica", "r"); }
    static boolean rejects(JSONObject hello) { try { HelloGate.parse(hello); return false; } catch (java.io.IOException e) { return true; } }

    public static void main(String[] args) throws Exception {
        JSONObject frontier = new JSONObject().put("a", 3);
        // existing hellos stay valid
        HelloGate.Peer plain = HelloGate.parse(base());
        check(!plain.announcesLinks && plain.enabled == null && !plain.hasFrontier, "plain hello valid");
        HelloGate.Peer old = HelloGate.parse(base().put("frontier", frontier));
        check(!old.announcesLinks && old.enabled == null && old.hasFrontier, "frontier hello valid");
        // new fields
        HelloGate.Peer on = HelloGate.parse(base().put("frontier", frontier).put("features", new JSONArray().put("links")).put("enabled", true));
        check(on.announcesLinks && on.linksEnabled(), "links enabled parsed");
        HelloGate.Peer off = HelloGate.parse(base().put("frontier", frontier).put("features", new JSONArray().put("links")).put("enabled", false));
        check(off.announcesLinks && !off.linksEnabled() && Boolean.FALSE.equals(off.enabled), "links disabled parsed");
        check(!HelloGate.parse(base().put("features", new JSONArray())).announcesLinks, "empty features");
        // Slice 7 (S7-4): `link_foreign` is a second feature; it counts only next to `links`, and this phone never announces it itself.
        HelloGate.Peer both = HelloGate.parse(base().put("features", new JSONArray().put("links").put("link_foreign")).put("enabled", false));
        check(both.announcesLinks && both.announcesLinkForeign, "links + link_foreign parsed");
        check(!on.announcesLinkForeign && !off.announcesLinkForeign && !old.announcesLinkForeign && !plain.announcesLinkForeign, "an older computer does not announce link_foreign");
        check(!HelloGate.parse(base().put("features", new JSONArray().put("link_foreign"))).announcesLinkForeign, "link_foreign alone is not enough");
        check(HelloGate.parse(base().put("features", new JSONArray().put("link_foreign").put("links").put("future"))).announcesLinkForeign, "order and unknown names do not matter");
        check(rejects(base().put("features", new JSONArray().put("link_foreign").put("link_foreign"))), "duplicate link_foreign");
        check(!HelloGate.parse(base().put("features", new JSONArray().put("other"))).announcesLinks, "unknown feature ignored");
        check(!HelloGate.parse(base().put("enabled", true)).linksEnabled(), "enabled without features is not links");
        // malformed
        check(rejects(base().put("features", "links")), "features not array");
        check(rejects(base().put("features", new JSONArray().put(1))), "feature not string");
        check(rejects(base().put("features", new JSONArray().put("links").put("links"))), "duplicate feature");
        check(rejects(base().put("features", new JSONArray().put(""))), "empty feature");
        check(rejects(base().put("enabled", "true")), "enabled string");
        check(rejects(base().put("enabled", 1)), "enabled number");
        check(rejects(base().put("extra", 1)), "unknown field");
        check(rejects(base().put("features", JSONObject.NULL)), "null features");
        check(rejects(new JSONObject().put("version", 1).put("group", "g")), "missing replica");

        // equal-frontier skip
        check(HelloGate.mayEndEqual(false, false, old), "no local announce: skip");
        check(HelloGate.mayEndEqual(true, true, old), "peer silent: skip");
        check(HelloGate.mayEndEqual(true, true, on), "both enabled: skip");
        check(!HelloGate.mayEndEqual(true, false, on), "local disabled: no skip");
        check(!HelloGate.mayEndEqual(true, true, off), "peer disabled: no skip");
        check(HelloGate.mayEndEqual(false, true, off), "local silent beats peer disabled: skip");

        // what we send: never features without announce, at any level
        JSONObject fr = new JSONObject().put("a", 1);
        for (int level = HelloGate.FRONTIER_FEATURES; level <= HelloGate.PLAIN; level++) {
            JSONObject h = HelloGate.apply(base(), level, false, fr, true);
            check(!h.has("features") && !h.has("enabled"), "announce=false level " + level + " sends nothing");
            check(h.has("frontier") == (level != HelloGate.PLAIN), "announce=false level " + level + " frontier");
        }
        check(PairingTransportAnnounces.value(), "PairingTransport.ANNOUNCE_LINKS is true");
        JSONObject f = HelloGate.apply(base(), HelloGate.FRONTIER_FEATURES, true, fr, true);
        check(f.has("frontier") && f.getJSONArray("features").length() == 1 && "links".equals(f.getJSONArray("features").getString(0)) && f.getBoolean("enabled"), "announce level shape");
        check(!HelloGate.apply(base(), HelloGate.FRONTIER, true, fr, true).has("features"), "frontier level has no features");
        check(HelloGate.firstLevel(false) == HelloGate.FRONTIER && HelloGate.firstLevel(true) == HelloGate.FRONTIER_FEATURES, "first level");

        // ladder: full walk within one Sync, 15 minute memory
        HelloGate.forget();
        long t = 1_000_000;
        int level = HelloGate.startLevel(true, t), steps = 0;
        check(level == HelloGate.FRONTIER_FEATURES, "ladder starts at features");
        while (HelloGate.hasLowerLevel(level)) { level++; HelloGate.refused(level, t); steps++; }
        check(level == HelloGate.PLAIN && steps == 2, "ladder walks all levels");
        check(!HelloGate.hasLowerLevel(HelloGate.PLAIN), "plain refusal is final");
        check(HelloGate.startLevel(true, t + HelloGate.REFUSAL_MEMORY_MS - 1) == HelloGate.PLAIN, "remembered inside 15 min");
        check(HelloGate.startLevel(true, t + HelloGate.REFUSAL_MEMORY_MS) == HelloGate.FRONTIER_FEATURES, "forgotten after 15 min");
        HelloGate.forget();
        check(HelloGate.startLevel(false, t) == HelloGate.FRONTIER, "no announce starts at frontier");
        HelloGate.refused(HelloGate.PLAIN, t);
        check(HelloGate.startLevel(false, t + 1) == HelloGate.PLAIN, "frontier refusal remembered");
        HelloGate.forget();

        if (failures != 0) { System.out.println("hello gate: " + failures + " failures"); System.exit(1); }
        System.out.println("hello gate: ok");
    }

    /** Reads the production constant from source, since PairingTransport needs Android to load. */
    static final class PairingTransportAnnounces {
        static boolean value() throws Exception {
            String src = Files.readString(Path.of(System.getProperty("pairing.source")));
            return src.contains("private static final boolean ANNOUNCE_LINKS=true");
        }
    }
}
