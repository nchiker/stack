package org.stackdocs.app.types;

import android.os.CancellationSignal;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Ami Pro `.SAM` and `.amipro`: text read by amipro-text in the native library. The original opens in Ami Pro. */
final class AmiProType implements FileType {
    static final String PACKAGE = "app.amipro.editor";
    private static final Set<String> EXT = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("sam", "amipro")));

    @Override public String id() { return "amipro"; }
    /** The type's own version plus the reader's, so a reader change queues the files again. */
    @Override public int version() { return 1 * 1000 + (NativeText.supported() ? NativeText.amiProVersion() : 0); }
    @Override public Set<String> extensions() { return EXT; }
    @Override public Set<String> mimeTypes() { return Collections.emptySet(); }
    @Override public Limits limits() { return new Limits(8L * 1024 * 1024, 1, 30_000, 1_000_000)   /* the native reader's own limit is 30 s */; }
    @Override public Opener opener() { return Opener.app(PACKAGE); }

    @Override public boolean matches(byte[] head, String name, String mime) {
        if (head == null || head.length == 0) return false;
        if (startsWith(head, "AMIPRO-LINUX/") || startsWith(head, "[encrypt]") || startsWith(head, "[ver]")) return true;
        // A SAM may start with a byte-order mark or blank lines: the name is only a hint, and only for text content.
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.endsWith(".sam") || lower.endsWith(".amipro"))) return false;
        for (byte b : head) if (b == 0) return false;
        return true;
    }

    private static boolean startsWith(byte[] head, String prefix) {
        int at = 0;
        if (head.length >= 3 && (head[0] & 0xff) == 0xEF && (head[1] & 0xff) == 0xBB && (head[2] & 0xff) == 0xBF) at = 3;
        if (head.length < at + prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) if (head[at + i] != (byte) prefix.charAt(i)) return false;
        return true;
    }

    @Override public Extracted extract(Source in, Limits l, CancellationSignal c) throws IOException {
        if (!NativeText.available()) return Extracted.unreadable("Reading Ami Pro files is not available in this build.");
        if (c != null) c.throwIfCanceled();
        byte[] bytes = in.readAll(l.maxBytes);
        if (bytes == null) return Extracted.tooLarge("This Ami Pro file is too large to read.");
        String[] result;
        try { result = NativeText.amiPro(bytes, l.maxChars); }
        catch (UnsatisfiedLinkError e) { return Extracted.unreadable("Reading Ami Pro files is not available in this build."); }
        if (result == null || result.length < 1 || result[0] == null) return Extracted.unreadable("This Ami Pro file could not be read.");
        switch (result[0]) {
            case "text": {
                String title = result.length > 1 && result[1] != null && !result[1].isEmpty() ? result[1] : null;
                String text = result.length > 2 && result[2] != null ? result[2] : "";
                return Extracted.text(Collections.singletonList(text), title, null);
            }
            case "protected": return Extracted.protectedFile();
            case "too_large": return Extracted.tooLarge("This Ami Pro file is too large to read.");
            case "timeout": return Extracted.timedOut("Reading this Ami Pro file took too long.");
            case "not_amipro": return Extracted.unsupported("This is not an Ami Pro file.");
            default: return Extracted.unreadable("This Ami Pro file is damaged or could not be read.");
        }
    }

    @Override public Preview preview(Source in, int maxPx) { return null; }
}
