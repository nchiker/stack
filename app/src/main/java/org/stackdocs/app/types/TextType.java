package org.stackdocs.app.types;

import android.os.CancellationSignal;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Plain text, UTF-8 (with or without BOM) or UTF-16 with a BOM. Anything binary, or not valid in those encodings, is refused. */
final class TextType implements FileType {
    private static final Set<String> EXT = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("txt", "text", "md", "log")));
    private static final Set<String> MIME = Collections.singleton("text/plain");

    @Override public String id() { return "text"; }
    @Override public int version() { return 1; }
    @Override public Set<String> extensions() { return EXT; }
    @Override public Set<String> mimeTypes() { return MIME; }
    @Override public Limits limits() { return new Limits(8L * 1024 * 1024, 1, 30_000, 1_000_000); }
    @Override public Opener opener() { return Opener.STACK_VIEWER; }
    @Override public Preview preview(Source in, int maxPx) { return null; }

    @Override public boolean matches(byte[] head, String name, String mime) {
        if (head == null || head.length == 0) return false;
        if (decode(head, true) == null) return false;
        // Content looks like text. With a name or MIME given it must also say text; with neither, content alone decides.
        boolean hasHint = (name != null && name.indexOf('.') >= 0) || mime != null;
        if (!hasHint) return true;
        if (mime != null && MIME.contains(mime.toLowerCase(java.util.Locale.ROOT))) return true;
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && EXT.contains(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    @Override public Extracted extract(Source in, Limits l, CancellationSignal c) throws IOException {
        if (c != null) c.throwIfCanceled();
        byte[] bytes = in.readAll(l.maxBytes);
        if (bytes == null) return Extracted.tooLarge("This text file is too large to read.");
        if (bytes.length == 0) return Extracted.unreadable("This file is empty.");
        String text = decode(bytes, false);
        if (text == null) return Extracted.unreadable("This file is not plain text.");
        String note = null;
        if (l.maxChars > 0 && text.length() > l.maxChars) {
            int cut = l.maxChars;
            if (Character.isHighSurrogate(text.charAt(cut - 1))) cut--;
            text = text.substring(0, cut);
            note = "Only the first part of this file was read.";
        }
        return Extracted.text(Collections.singletonList(text), null, note);
    }

    /** The decoded text, or null when the bytes are not plain text. `partial`: the bytes may end in the middle of a character. */
    static String decode(byte[] bytes, boolean partial) {
        int offset = 0;
        Charset charset = StandardCharsets.UTF_8;
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xFF && (bytes[1] & 0xff) == 0xFE) { charset = StandardCharsets.UTF_16LE; offset = 2; }
        else if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xFE && (bytes[1] & 0xff) == 0xFF) { charset = StandardCharsets.UTF_16BE; offset = 2; }
        else if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB && (bytes[2] & 0xff) == 0xBF) offset = 3;
        CharsetDecoder decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
        CharBuffer out = CharBuffer.allocate((int) Math.min(Integer.MAX_VALUE - 16L, (long) (bytes.length - offset) + 4));
        CoderResult result = decoder.decode(in, out, !partial);
        if (result.isError()) return null;
        if (result.isOverflow()) return null;
        if (!partial) {
            try { if (decoder.flush(out).isError()) return null; } catch (IllegalStateException e) { return null; }
        } else if (in.hasRemaining() && in.remaining() > 3) return null;
        out.flip();
        for (int i = 0; i < out.length(); i++) {
            char ch = out.charAt(i);
            if (ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' && ch != '\f') return null;
            if (ch == 0x7F || ch == 0xFFFE || ch == 0xFFFF) return null;
        }
        return out.toString();
    }
}
