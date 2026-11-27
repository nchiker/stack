package org.stackdocs.app.types;

import android.os.CancellationSignal;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** A single picture file: OCR for text, decoded for the preview. JPEG, PNG and WebP differ only in their signature. */
abstract class ImageType implements FileType {
    private final String id, mime;
    private final Set<String> extensions;

    ImageType(String id, String mime, String... extensions) {
        this.id = id; this.mime = mime;
        this.extensions = Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(extensions)));
    }

    @Override public String id() { return id; }
    @Override public int version() { return 1; }
    @Override public Set<String> extensions() { return extensions; }
    @Override public Set<String> mimeTypes() { return Collections.singleton(mime); }
    @Override public Limits limits() { return new Limits(256L * 1024 * 1024, 1, 120_000, 0); }
    @Override public Opener opener() { return Opener.STACK_VIEWER; }
    @Override public boolean storedAsDocument() { return true; }
    @Override public boolean isImage() { return true; }

    @Override public Extracted extract(Source in, Limits l, CancellationSignal c) throws IOException {
        if (in.length() > l.maxBytes) return Extracted.tooLarge("This picture is too large to read.");
        try { return OcrText.recognize(in, this, 1, l, c, null, android.os.SystemClock.elapsedRealtime()); }
        catch (OutOfMemoryError e) { return Extracted.tooLarge("This picture is too large to read."); }
        catch (IOException e) { return Extracted.unreadable("This picture could not be read."); }
    }

    @Override public Preview preview(Source in, int maxPx) throws IOException {
        return new Preview(ImageDecode.decode(in, maxPx));
    }

    static boolean starts(byte[] head, int at, int... bytes) {
        if (head == null || head.length < at + bytes.length) return false;
        for (int i = 0; i < bytes.length; i++) if ((head[at + i] & 0xff) != bytes[i]) return false;
        return true;
    }
}
