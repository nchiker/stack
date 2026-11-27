package org.stackdocs.app.types;

import android.os.CancellationSignal;
import java.io.IOException;
import java.util.Set;

/** One kind of file Stack knows. Add a handler = one class in FileTypes.all() plus synthetic files under androidTest/assets/types/&lt;id&gt;/. */
public interface FileType {
    String id();
    /** Bump when what is read from the same file changes: files of this type are read again. */
    int version();
    Set<String> extensions();
    Set<String> mimeTypes();
    /** Content first; the name and MIME are only hints. `head` is the first (up to 4096) bytes. */
    boolean matches(byte[] head, String name, String mime);
    Limits limits();
    Extracted extract(Source in, Limits l, CancellationSignal c) throws IOException;
    /** A picture of the file (`in.page()` for page n), or null when this type has none. */
    Preview preview(Source in, int maxPx) throws IOException;
    Opener opener();

    /** True for the kinds Stack keeps as a library document (and so allows in archives and sync). Not part of I3: additive. */
    default boolean storedAsDocument() { return false; }
    /** True for a single picture type (the photo picker and photo import accept exactly these). Additive. */
    default boolean isImage() { return false; }
}
