package org.stackdocs.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Slice 1 of docs/STACK_LINK_CONTRACT.md: the record shape and its strict validation, driven by a stub owner app.
 * Synthetic data only. Nothing here touches a library.
 */
final class LinkContractChecks {
    /** Stands in for AmiPro: builds the small JSON part of a record the way a real owner app would. */
    private static final class StubOwner {
        static final String PACKAGE = "app.stub.owner";
        static final String LINK_ID = "3f2b8c1e-5a47-4d0b-9c6e-1a2b3c4d5e6f";
        static final String FILEN_UUID = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

        static JSONObject record() throws Exception {
            return new JSONObject()
                .put("contract", 1).put("owner", PACKAGE).put("link_id", LINK_ID).put("kind", "doc")
                .put("title", "Medicines and doctors").put("mime", "application/x-amipro-document")
                .put("modified", 1_800_000_000_000L).put("hash", "a".repeat(64))
                .put("locators", new JSONArray()
                    .put(new JSONObject().put("type", "filen").put("uuid", FILEN_UUID).put("path", "/Health/Medicines.sam"))
                    .put(new JSONObject().put("type", "saf").put("uri", "content://stub/document/1"))
                    .put(new JSONObject().put("type", "owner").put("token", "private/1")));
        }
    }

    static void run(Consumer<String> mark) throws Exception {
        LinkRecord ok = LinkRecord.parse(StubOwner.record().toString());
        WorkflowRepositoryChecks.require(ok.owner.equals(StubOwner.PACKAGE) && ok.linkId.equals(StubOwner.LINK_ID) && ok.kind.equals("doc")
            && ok.title.equals("Medicines and doctors") && ok.modified == 1_800_000_000_000L && ok.locators.size() == 3, "A well-formed stub record parses with every field");
        WorkflowRepositoryChecks.require(!ok.shared && !ok.isProtected, "shared and protected default to false");
        WorkflowRepositoryChecks.require(ok.locators.get(0).syncs() && !ok.locators.get(1).syncs() && !ok.locators.get(2).syncs(), "Only the filen locator syncs between devices");
        WorkflowRepositoryChecks.require(LinkRecord.parse(StubOwner.record().put("shared", true).put("protected", true).toString()).isProtected, "shared and protected are read when present");

        // Strictness: each mutation of a good record must be refused, with nothing half-accepted.
        rejects("an unknown field", StubOwner.record().put("revision", 3));
        rejects("a missing title", without("title"));
        rejects("a missing hash", without("hash"));
        rejects("contract as text", StubOwner.record().put("contract", "1"));
        rejects("modified as text", StubOwner.record().put("modified", "5"));
        rejects("negative modified", StubOwner.record().put("modified", -1));
        rejects("modified as a fraction", StubOwner.record().put("modified", 1.5));
        rejects("shared as text", StubOwner.record().put("shared", "true"));
        rejects("an uppercase link id", StubOwner.record().put("link_id", StubOwner.LINK_ID.toUpperCase()));
        rejects("a short link id", StubOwner.record().put("link_id", "3f2b8c1e"));
        rejects("a bad package name", StubOwner.record().put("owner", "Stub Owner"));
        rejects("a bad kind", StubOwner.record().put("kind", "Doc"));
        rejects("a bad mime type", StubOwner.record().put("mime", "notamime"));
        rejects("an empty title", StubOwner.record().put("title", ""));
        rejects("a title over 200", StubOwner.record().put("title", "t".repeat(201)));
        rejects("a control character in the title", StubOwner.record().put("title", "a\u0007b"));
        rejects("a lone surrogate in the title", StubOwner.record().put("title", "a\ud800b"));
        rejects("a bidi override in the title", StubOwner.record().put("title", "a\u202eb"));
        rejects("a C1 control in the title", StubOwner.record().put("title", "a\u0085b"));
        rejects("an uppercase hash", StubOwner.record().put("hash", "A".repeat(64)));
        rejects("a short hash", StubOwner.record().put("hash", "a".repeat(63)));
        rejects("no locators", StubOwner.record().put("locators", new JSONArray()));
        rejects("locators as an object", StubOwner.record().put("locators", new JSONObject()));
        JSONArray nine = new JSONArray();
        for (int i = 0; i < 9; i++) nine.put(new JSONObject().put("type", "owner").put("token", "t" + i));
        rejects("nine locators", StubOwner.record().put("locators", nine));
        rejects("an unknown locator type", locators(new JSONObject().put("type", "dropbox").put("token", "x")));
        rejects("a filen locator without a uuid", locators(new JSONObject().put("type", "filen").put("path", "/a")));
        rejects("a filen locator with a bad uuid", locators(new JSONObject().put("type", "filen").put("uuid", "nope").put("path", "/a")));
        rejects("an extra key in a locator", locators(new JSONObject().put("type", "saf").put("uri", "content://x").put("hint", "y")));
        rejects("a locator that is not an object", StubOwner.record().put("locators", new JSONArray().put("filen")));

        // Raw text the strict gate must refuse before any field is read.
        String valid = StubOwner.record().toString();
        refuses("a duplicate key", valid.replace("\"kind\":\"doc\"", "\"kind\":\"doc\",\"kind\":\"note\""));
        refuses("trailing text", valid + " x");
        refuses("not JSON", "{\"contract\":1,");

        // A newer contract is its own, recognisable error so the user is told which app to update.
        try {
            LinkRecord.parse(StubOwner.record().put("contract", 2).put("somethingNew", true).toString());
            throw new AssertionError("A newer contract must be refused");
        } catch (LinkRecord.UnsupportedContract error) {
            WorkflowRepositoryChecks.require(error.version == 2, "Unsupported contract reports its number and not 'unknown field'");
        }

        // Streamed parts: size and shape limits.
        LinkRecord.checkText("x".repeat(LinkRecord.MAX_TEXT));
        mustThrow("text over 1 MiB", () -> LinkRecord.checkText("x".repeat(LinkRecord.MAX_TEXT + 1)));
        mustThrow("text with NUL", () -> LinkRecord.checkText("a\0b"));
        mustThrow("missing text", () -> LinkRecord.checkText(null));
        byte[] pdf = "%PDF-1.7\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        LinkRecord.checkPreview(LinkRecord.MAX_PREVIEW, pdf);
        mustThrow("preview over 32 MiB", () -> LinkRecord.checkPreview(LinkRecord.MAX_PREVIEW + 1L, pdf));
        mustThrow("empty preview", () -> LinkRecord.checkPreview(0, pdf));
        mustThrow("preview that is not a PDF", () -> LinkRecord.checkPreview(100, "GIF89a...".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));

        mark.accept("Link contract 1: stub owner record parses; 32 malformed variants (unknown/missing fields, wrong types, bad ids, control characters, lone surrogates, locator shapes, duplicate keys, trailing text) are all refused; a newer contract gives a distinct error; text and preview limits hold");
    }

    private static JSONObject without(String key) throws Exception { JSONObject o = StubOwner.record(); o.remove(key); return o; }
    private static JSONObject locators(JSONObject first) throws Exception { return StubOwner.record().put("locators", new JSONArray().put(first)); }

    private static void rejects(String what, JSONObject record) { refuses(what, record.toString()); }

    private static void refuses(String what, String json) {
        try { LinkRecord.parse(json); } catch (LinkRecord.UnsupportedContract error) { throw new AssertionError("Wrong error for " + what); } catch (IOException expected) { return; }
        throw new AssertionError("Accepted " + what);
    }

    private interface Action { void run() throws IOException; }

    private static void mustThrow(String what, Action action) {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Accepted " + what);
    }
}
