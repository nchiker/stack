package org.stackdocs.app.types;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The one list of file types. The indexer, search, list screens and sharing ask here, never test a MIME or extension themselves. */
public final class FileTypes {
    private FileTypes() {}

    private static volatile List<FileType> list;

    /** Built once, under a lock, with the PDF text-layer reader of this device (null without the native library: OCR only).
     *  No start-up step is needed, so a caller that runs before Application.onCreate (a provider) sees the same list, and the fingerprint
     *  is the same on every call for one ABI. */
    public static List<FileType> all() {
        List<FileType> value = list;
        if (value == null) {
            synchronized (FileTypes.class) {
                value = list;
                if (value == null) {
                    // Order matters for match(): the stack scan has no content signature and is told apart by its MIME alone, so it goes first.
                    value = Collections.unmodifiableList(Arrays.<FileType>asList(
                            new StackScanType(), new PdfType(NativePdfText.ifAvailable()), new JpegType(), new PngType(), new WebpType(), new AmiProType(), new TextType()));
                    list = value;
                }
            }
        }
        return value;
    }

    /** The type of a file: by content first (`head` is its first bytes), the name and MIME only as hints. Null when none. */
    public static FileType match(byte[] head, String name, String mime) {
        for (FileType type : all()) if (type.matches(head, name, mime)) return type;
        return null;
    }

    public static FileType byId(String id) {
        for (FileType type : all()) if (type.id().equals(id)) return type;
        return null;
    }

    public static FileType byMime(String mime) {
        if (mime == null) return null;
        for (FileType type : all()) if (type.mimeTypes().contains(mime)) return type;
        return null;
    }

    /** Ids and versions, stored with each indexed file: a change means the files are read again. */
    public static String fingerprint() {
        StringBuilder out = new StringBuilder();
        for (FileType type : all()) { if (out.length() > 0) out.append(','); out.append(type.id()).append(':').append(type.version()); }
        return out.toString();
    }

    /** True for a MIME of a kind kept as a library document: the whitelist of archives and sync (their format does not widen with new handlers). */
    public static boolean isLibraryMime(String mime) {
        FileType type = byMime(mime);
        return type != null && type.storedAsDocument();
    }

    /** MIME types of the single-picture handlers, in registry order (photo picker, photo import). */
    public static String[] imageMimes() {
        List<String> out = new ArrayList<>();
        for (FileType type : all()) if (type.isImage()) out.addAll(type.mimeTypes());
        return out.toArray(new String[0]);
    }

    /** The name without one trailing extension that belongs to a registered type (case-insensitive). */
    public static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return name;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (FileType type : all()) if (type.extensions().contains(ext)) return name.substring(0, dot);
        return name;
    }
}
