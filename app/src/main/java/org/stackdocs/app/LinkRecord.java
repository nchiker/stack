package org.stackdocs.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The small, JSON part of a Stack Link record (docs/STACK_LINK_CONTRACT.md, contract 1). `text` and `preview` are
 * streamed separately and only size-checked here. Strict: unknown or missing fields, wrong types, duplicate keys
 * and out-of-range values are all refused with a plain message. Nothing here touches the library.
 */
final class LinkRecord {
    static final int CONTRACT = 1;
    static final int MAX_TITLE = 200, MAX_LOCATORS = 8, MAX_MIME = 100, MAX_PATH = 1024, MAX_URI = 2048, MAX_TOKEN = 512;
    static final int MAX_TEXT = 1 << 20, MAX_PREVIEW = 32 << 20;

    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern PACKAGE = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+");
    private static final Pattern KIND = Pattern.compile("[a-z]{1,16}");
    private static final Pattern MIME = Pattern.compile("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> REQUIRED = set("contract", "owner", "link_id", "kind", "title", "mime", "locators", "modified", "hash");
    private static final Set<String> OPTIONAL = set("shared", "protected");

    /** The record carries a contract number this Stack does not speak; the caller says "Update Stack/AmiPro". */
    static final class UnsupportedContract extends IOException {
        final int version;
        UnsupportedContract(int version) { super("This document uses link contract " + version + ", which this version of Stack does not support."); this.version = version; }
    }

    static final class Locator {
        static final String FILEN = "filen", SAF = "saf", OWNER = "owner";
        final String type, uuid, path, uri, token;
        private Locator(String type, String uuid, String path, String uri, String token) { this.type = type; this.uuid = uuid; this.path = path; this.uri = uri; this.token = token; }
        /** Only the filen locator is shared between a person's own devices; saf and owner are per device. */
        boolean syncs() { return FILEN.equals(type); }
    }

    final String owner, linkId, kind, title, mime, hash;
    final long modified;
    final boolean shared, isProtected;
    final List<Locator> locators;

    private LinkRecord(String owner, String linkId, String kind, String title, String mime, String hash, long modified,
                       boolean shared, boolean isProtected, List<Locator> locators) {
        this.owner = owner; this.linkId = linkId; this.kind = kind; this.title = title; this.mime = mime; this.hash = hash;
        this.modified = modified; this.shared = shared; this.isProtected = isProtected;
        this.locators = Collections.unmodifiableList(locators);
    }

    static LinkRecord parse(String json) throws IOException {
        JSONObject o = ArchiveJson.read(json);
        // The contract number is read first so an older Stack gives the "update" message, not "unknown field".
        Object number = o.opt("contract");
        if (!(number instanceof Integer) && !(number instanceof Long)) throw bad("contract must be a whole number");
        long version = ((Number) number).longValue();
        if (version != CONTRACT) throw new UnsupportedContract((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, version)));
        exactKeys(o, REQUIRED, OPTIONAL, "record");
        String owner = text(o, "owner", PACKAGE, 200);
        String linkId = text(o, "link_id", UUID, 36);
        String kind = text(o, "kind", KIND, 16);
        String title = free(o, "title", MAX_TITLE);
        String mime = text(o, "mime", MIME, MAX_MIME);
        String hash = text(o, "hash", HASH, 64);
        Object modified = o.opt("modified");
        if (!(modified instanceof Integer) && !(modified instanceof Long) || ((Number) modified).longValue() < 0) throw bad("modified must be a whole number of milliseconds");
        boolean shared = optionalBoolean(o, "shared"), isProtected = optionalBoolean(o, "protected");
        Object list = o.opt("locators");
        if (!(list instanceof JSONArray)) throw bad("locators must be a list");
        JSONArray array = (JSONArray) list;
        if (array.length() == 0 || array.length() > MAX_LOCATORS) throw bad("locators must hold 1 to " + MAX_LOCATORS + " entries");
        List<Locator> locators = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object entry = array.opt(i);
            if (!(entry instanceof JSONObject)) throw bad("each locator must be an object");
            locators.add(locator((JSONObject) entry));
        }
        return new LinkRecord(owner, linkId, kind, title, mime, hash, ((Number) modified).longValue(), shared, isProtected, locators);
    }

    /** The owner's `removed` message: exactly contract, owner, link_id, reason. Returns {owner, link_id, reason}. */
    static String[] parseRemoval(String json) throws IOException {
        if (json == null || json.length() > 4096) throw bad("the message is missing or too large");
        JSONObject o = ArchiveJson.read(json);
        Object number = o.opt("contract");
        if (!(number instanceof Integer) && !(number instanceof Long)) throw bad("contract must be a whole number");
        long version = ((Number) number).longValue();
        if (version != CONTRACT) throw new UnsupportedContract((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, version)));
        exactKeys(o, set("contract", "owner", "link_id", "reason"), set(), "removal");
        String reason = text(o, "reason", Pattern.compile("deleted|trashed"), 16);
        return new String[]{text(o, "owner", PACKAGE, 200), text(o, "link_id", UUID, 36), reason};
    }

    /** The locators of a `stat` reply (a JSON array of at most 16 KiB), validated like a record's; null if they are not valid. */
    static List<Locator> parseLocators(String json) {
        try {
            if (json == null || json.length() > 16 * 1024) return null;
            Object parsed = new org.json.JSONTokener(json).nextValue();
            if (!(parsed instanceof JSONArray)) return null;
            JSONArray array = (JSONArray) parsed;
            if (array.length() == 0 || array.length() > MAX_LOCATORS) return null;
            List<Locator> out = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                Object entry = array.opt(i);
                if (!(entry instanceof JSONObject)) return null;
                out.add(locator((JSONObject) entry));
            }
            return out;
        } catch (IOException | org.json.JSONException | RuntimeException error) { return null; }
    }

    /** The streamed search text: at most 1 MiB of UTF-16 text and no NUL. */
    static void checkText(String text) throws IOException {
        if (text == null || text.length() > MAX_TEXT || text.indexOf('\0') >= 0) throw bad("text is missing, over 1 MiB or contains NUL");
    }

    /** The streamed preview PDF: at most 32 MiB, and it must look like a PDF. */
    static void checkPreview(long length, byte[] firstBytes) throws IOException {
        if (length <= 0 || length > MAX_PREVIEW) throw bad("preview is empty or over 32 MiB");
        byte[] magic = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (firstBytes == null || firstBytes.length < magic.length || !Arrays.equals(Arrays.copyOf(firstBytes, magic.length), magic)) throw bad("preview is not a PDF");
    }

    private static Locator locator(JSONObject o) throws IOException {
        Object type = o.opt("type");
        if (!(type instanceof String)) throw bad("locator type must be text");
        switch ((String) type) {
            case Locator.FILEN:
                exactKeys(o, set("type", "uuid", "path"), Collections.<String>emptySet(), "filen locator");
                return new Locator(Locator.FILEN, text(o, "uuid", UUID, 36), free(o, "path", MAX_PATH), null, null);
            case Locator.SAF:
                exactKeys(o, set("type", "uri"), Collections.<String>emptySet(), "saf locator");
                return new Locator(Locator.SAF, null, null, free(o, "uri", MAX_URI), null);
            case Locator.OWNER:
                exactKeys(o, set("type", "token"), Collections.<String>emptySet(), "owner locator");
                return new Locator(Locator.OWNER, null, null, null, free(o, "token", MAX_TOKEN));
            default:
                throw bad("unknown locator type");
        }
    }

    private static void exactKeys(JSONObject o, Set<String> required, Set<String> optional, String what) throws IOException {
        Set<String> seen = new HashSet<>();
        for (Iterator<String> keys = o.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!required.contains(key) && !optional.contains(key)) throw bad("unknown field in " + what + ": " + shorten(key));
            seen.add(key);
        }
        for (String key : required) if (!seen.contains(key)) throw bad("missing field in " + what + ": " + key);
    }

    private static String text(JSONObject o, String key, Pattern pattern, int max) throws IOException {
        String value = free(o, key, max);
        if (!pattern.matcher(value).matches()) throw bad(key + " has the wrong form");
        return value;
    }

    /** A non-empty string without control characters or unpaired surrogates, at most `max` UTF-16 units. */
    private static String free(JSONObject o, String key, int max) throws IOException {
        Object value = o.opt(key);
        if (!(value instanceof String)) throw bad(key + " must be text");
        String s = (String) value;
        if (s.isEmpty() || s.length() > max) throw bad(key + " is empty or longer than " + max);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || (c >= 0x7f && c <= 0x9f) || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069) || c == 0x200e || c == 0x200f || c == 0x061c)
                throw bad(key + " contains a control or direction-changing character");
            if (Character.isHighSurrogate(c)) { if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) throw bad(key + " is not valid text"); i++; }
            else if (Character.isLowSurrogate(c)) throw bad(key + " is not valid text");
        }
        return s;
    }

    private static boolean optionalBoolean(JSONObject o, String key) throws IOException {
        if (!o.has(key)) return false;
        Object value = o.opt(key);
        if (!(value instanceof Boolean)) throw bad(key + " must be true or false");
        return (Boolean) value;
    }

    private static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static String shorten(String s) { return s.length() <= 40 ? s : s.substring(0, 40); }
    private static IOException bad(String message) { return new IOException("Invalid link record: " + message + "."); }
}
