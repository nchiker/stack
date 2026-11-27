package org.stackdocs.app.types;

/**
 * JNI to libstacknative (amipro-text). Panics inside are caught natively and come back as "damaged".
 *
 * The library is loaded on first use, never when the type list is built: Stack's main process does not read folder files (the `:extract`
 * process does, see ExtractService), so for them it never loads this library. The versions the registry needs for its fingerprint are then
 * answered without the library: the real value once the library is loaded in this process, otherwise the last one the extract process
 * reported ({@link #remember}), otherwise the value this build was made with.
 */
public final class NativeText {
    private NativeText() {}

    private static Boolean loaded;
    private static volatile int pdfSeen = 1, amiProSeen = 1;

    /** True when the native library is loaded in this process (loads it on first call). */
    static synchronized boolean available() {
        if (loaded == null) {
            boolean ok;
            try { System.loadLibrary("stacknative"); ok = true; }
            catch (UnsatisfiedLinkError e) { ok = false; }
            loaded = ok;
        }
        return loaded;
    }

    private static synchronized boolean isLoaded() { return loaded != null && loaded; }

    /** True on a device that has the library (arm64-v8a), without loading it. */
    static boolean supported() {
        for (String abi : android.os.Build.SUPPORTED_ABIS) if ("arm64-v8a".equals(abi)) return true;
        return false;
    }

    /** The versions of the PDF text reader and of amipro-text, read in the process that has the library loaded (the extract process or a check). */
    public static int[] versions() { return available() ? new int[]{pdfVersion(), version()} : new int[]{0, 0}; }

    /** The extract process told the main process its reader versions: used for the fingerprint there. Kept across restarts. */
    public static void remember(android.content.Context context, int pdf, int amiPro) {
        if (pdf <= 0 || amiPro <= 0) return;
        pdfSeen = pdf; amiProSeen = amiPro;
        context.getSharedPreferences("extract_versions", android.content.Context.MODE_PRIVATE).edit().putInt("pdf", pdf).putInt("amipro", amiPro).apply();
    }

    public static void restore(android.content.Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("extract_versions", android.content.Context.MODE_PRIVATE);
        pdfSeen = prefs.getInt("pdf", 1); amiProSeen = prefs.getInt("amipro", 1);
    }

    static int pdfReaderVersion() { return isLoaded() ? pdfVersion() : pdfSeen; }
    static int amiProVersion() { return isLoaded() ? version() : amiProSeen; }

    /** [status, title, text]; status is "text", "protected", "too_large", "not_amipro" or "damaged". */
    static native String[] amiPro(byte[] bytes, int maxChars);

    /** = amipro-text VERSION */
    static native int version();

    /** Text layer per page of the PDF behind `fd` (only read, never closed); null entries need OCR; null overall = not read. */
    static native String[] pdfPageTexts(int fd, int maxPages, int maxChars, int budgetMillis);

    /** Bumped when the PDF text reader's output changes. */
    static native int pdfVersion();
}
