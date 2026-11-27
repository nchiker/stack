package org.stackdocs.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.CancellationSignal;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import org.stackdocs.app.types.Extracted;
import org.stackdocs.app.types.FileType;
import org.stackdocs.app.types.FileTypes;
import org.stackdocs.app.types.Limits;
import org.stackdocs.app.types.NativePdfText;
import org.stackdocs.app.types.Preview;
import org.stackdocs.app.types.Source;

/** Type registry checks (-e types true): every handler against its synthetic files (assets/types/&lt;id&gt;/) and against bad input. */
final class TypeChecks {
    private TypeChecks() {}

    private static final List<String> IDS = Arrays.asList("stack-scan", "pdf", "jpeg", "png", "webp", "amipro", "text");

    private static void require(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    private static byte[] asset(Context test, String path) throws IOException {
        try (InputStream in = test.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    private static File write(File dir, String name, byte[] bytes) throws IOException {
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes); }
        return f;
    }

    private static String mimeHint(String id) { return id.equals("stack-scan") ? "application/x-stack-scan" : null; }

    private static String joined(Extracted e) { return String.join("\n", e.pages); }

    /** One extract: a status, or an IOException (an I/O failure); never any other exception. */
    private static Extracted extract(FileType t, Context app, File f, Limits l) {
        try { return t.extract(Source.of(app, f), l, new CancellationSignal()); }
        catch (IOException e) { return Extracted.unreadable("io: " + e.getMessage()); }
    }

    static void run(Context test, Context app, Consumer<String> mark) throws Exception {
        File dir = new File(app.getCacheDir(), "type-checks-" + UUID.randomUUID());
        require(dir.mkdirs(), "work dir");
        try { runIn(test, app, dir, mark); }
        finally { for (File f : dir.listFiles()) f.delete(); dir.delete(); }
    }

    private static void runIn(Context test, Context app, File dir, Consumer<String> mark) throws Exception {
        // Registry shape.
        require(FileTypes.all().size() == IDS.size(), "handler count");
        for (int i = 0; i < IDS.size(); i++) require(FileTypes.all().get(i).id().equals(IDS.get(i)), "handler order " + IDS.get(i));
        String fp = FileTypes.fingerprint();
        for (FileType t : FileTypes.all()) require(fp.contains(t.id() + ":" + t.version()), "fingerprint has " + t.id() + " (" + t.id() + ":" + t.version() + " in " + fp + ")");
        boolean arm64 = Arrays.asList(android.os.Build.SUPPORTED_ABIS).contains("arm64-v8a");
        require(FileTypes.byId("pdf").version() == (arm64 ? 2001 : 2000), "pdf version = type 2 + text reader 1, got " + FileTypes.byId("pdf").version() + " reader " + (NativePdfText.ifAvailable() == null ? "null" : "v" + NativePdfText.ifAvailable().version()));
        require(FileTypes.byId("pdf").limits().maxChars == 1_000_000 && FileTypes.byId("pdf").limits().maxBytes <= 160L * 1024 * 1024 && FileTypes.byId("pdf").limits().maxMillis == 60_000, "PDF limits: 1,000,000 characters, at most the native 160 MB input, 60 s");
        final int amiProBefore = FileTypes.byId("amipro").version();   // before the library is loaded in this process: the value restored from the extract process's last report
        final int[] loadedVersions = org.stackdocs.app.types.NativeText.versions();
        require(FileTypes.byId("amipro").version() == 1000 + (arm64 ? loadedVersions[1] : 0) && (!arm64 || loadedVersions[1] >= 1), "amipro version = type 1 + the loaded amipro-text VERSION, got " + FileTypes.byId("amipro").version() + " (restored value before loading: " + amiProBefore + "), native " + java.util.Arrays.toString(loadedVersions));
        mark.accept("Registry: seven handlers, fingerprint " + fp);

        for (String id : IDS) {
            FileType type = FileTypes.byId(id);
            String[] names = test.getAssets().list("types/" + id);
            require(names != null && names.length > 0, id + ": has synthetic files");
            for (String name : names) {
                if (id.equals("pdf") && name.startsWith("hostile-")) continue; // read on their own below
                byte[] bytes = asset(test, "types/" + id + "/" + name);
                File f = write(dir, name, bytes);
                byte[] head = Arrays.copyOf(bytes, Math.min(4096, bytes.length));
                FileType found = FileTypes.match(head, name, mimeHint(id));
                require(found != null && found.id().equals(id), id + "/" + name + ": matched as " + (found == null ? "nothing" : found.id()));
                Extracted e = extract(type, app, f, type.limits());
                if (name.equals("protected.sam")) {
                    require(e.status == Extracted.Status.PROTECTED && e.pages.isEmpty(), id + "/" + name + ": protected, no text");
                } else {
                    require(e.status == Extracted.Status.TEXT, id + "/" + name + ": text, got " + e.status + " " + e.reason);
                    String all = joined(e).toLowerCase();
                    if (id.equals("amipro")) require(all.contains("synthetic"), id + "/" + name + ": text " + all);
                    else if (id.equals("text")) require(all.contains("second line") && all.contains("café"), id + "/" + name + ": text");
                    else require(all.contains("types"), id + "/" + name + ": OCR text '" + all.trim() + "'");
                }
                Preview p = type.preview(Source.of(f), 300);
                if (id.equals("amipro") || id.equals("text")) require(p == null, id + ": no preview");
                else { require(p != null && p.bitmap.getWidth() > 0 && p.bitmap.getWidth() <= 300, id + ": preview"); p.bitmap.recycle(); }
                require(type.opener() != null, id + ": opener");
                new File(dir, name).delete();
            }
            mark.accept(id + ": matches, reads, previews its synthetic files (" + names.length + ")");
        }
        require(FileTypes.byId("amipro").opener().packageName.equals("app.amipro.editor"), "amipro opens in Ami Pro");
        require(FileTypes.byId("pdf").opener().isStackViewer(), "pdf opens in Stack");
        mark.accept("Openers");

        pdfTextLayer(test, app, dir, mark);

        // Content beats name and MIME.
        byte[] jpeg = asset(test, "types/jpeg/synthetic.jpg"), pdf = asset(test, "types/pdf/synthetic.pdf"), sam = asset(test, "types/amipro/synthetic.sam");
        require(FileTypes.match(jpeg, "scan.pdf", "application/pdf").id().equals("jpeg"), "jpeg content named .pdf");
        require(FileTypes.match(pdf, "x.txt", "text/plain").id().equals("pdf"), "pdf content named .txt");
        require(FileTypes.match(sam, "notes.txt", "text/plain").id().equals("amipro"), "SAM content named .txt");
        require(FileTypes.match(new byte[0], "a.pdf", "application/pdf") == null, "empty head is nothing");
        require(FileTypes.match(new byte[]{1, 2, 3, 0, 5, 6}, "a.txt", "text/plain") == null, "binary named .txt is nothing");
        require(FileTypes.match("plain words\n".getBytes("UTF-8"), "readme", null).id().equals("text"), "text by content");
        require(FileTypes.match("plain words\n".getBytes("UTF-8"), "data.bin", null) == null, "text content, other name: no");
        mark.accept("Content first, name and MIME only as hints");

        // Bad input for every handler.
        Random random = new Random(20261126L);
        byte[] noise = new byte[5000]; random.nextBytes(noise);
        for (String id : IDS) {
            FileType type = FileTypes.byId(id);
            String sample = id.equals("amipro") ? "synthetic.sam" : id.equals("text") ? "utf8.txt" : id.equals("pdf") ? "synthetic.pdf" : id.equals("jpeg") ? "synthetic.jpg" : id.equals("png") ? "synthetic.png" : id.equals("webp") ? "synthetic.webp" : "page.jpg";
            byte[] good = asset(test, "types/" + id + "/" + sample);
            byte[] withMagic = Arrays.copyOf(good, 5000);
            int from = Math.min(good.length, 24); System.arraycopy(noise, 40, withMagic, from, 5000 - from - 40);
            byte[][] cases = {new byte[0], Arrays.copyOf(good, 1), Arrays.copyOf(good, good.length / 2), Arrays.copyOf(good, Math.max(1, good.length - 7)), noise, withMagic};
            String[] labels = {"empty", "one byte", "half", "tail cut", "random", "valid start then random"};
            for (int i = 0; i < cases.length; i++) {
                File f = write(dir, "bad-" + id + "-" + i, cases[i]);
                Extracted e = extract(type, app, f, type.limits());
                // Empty and random never give text; one byte only for the non-text types; a cut picture or document may legitimately read in part.
                if (i == 0 || i == 4 || (i == 1 && !id.equals("text"))) require(e.status != Extracted.Status.TEXT, id + " " + labels[i] + ": not text, got " + e.status);
                require(e.status != null, id + " " + labels[i]);
                boolean m; // matches() must not throw on any bytes
                m = type.matches(Arrays.copyOf(cases[i], Math.min(4096, cases[i].length)), "bad", null);
                Preview p;
                try { p = type.preview(Source.of(f), 200); if (p != null) p.bitmap.recycle(); } catch (IOException expected) { }
                f.delete();
            }
            // Over the limit.
            File f = write(dir, "limit-" + id, good);
            Limits small = type.limits().withMaxBytes(Math.max(1, good.length - 1));
            require(extract(type, app, f, small).status == Extracted.Status.TOO_LARGE, id + ": over maxBytes is TOO_LARGE");
            if (id.equals("pdf")) require(extract(type, app, f, type.limits().withMaxPages(0)).status == Extracted.Status.TOO_LARGE, "pdf: over maxPages");
            if (id.equals("text")) {
                Extracted cut = extract(type, app, f, type.limits().withMaxChars(7));
                require(cut.status == Extracted.Status.TEXT && joined(cut).length() == 7 && cut.reason != null, "text: maxChars cuts and says so");
            }
            if (id.equals("amipro")) {
                Extracted cut = extract(type, app, f, type.limits().withMaxChars(5));
                require(cut.status == Extracted.Status.TEXT && joined(cut).length() <= 5, "amipro: maxChars cuts");
            }
            f.delete();
            mark.accept(id + ": bad input (empty, truncated, random, over limit) never crashes and never gives text");
        }

        // Text specifics.
        require(TextChecksSupport.reads(app, dir, new byte[]{'a', 0, 'b'}) == Extracted.Status.UNREADABLE, "NUL byte is binary");
        require(TextChecksSupport.reads(app, dir, new byte[]{(byte) 0xC3, (byte) 0x28, 'a'}) == Extracted.Status.UNREADABLE, "invalid UTF-8 refused");
        require(TextChecksSupport.reads(app, dir, new byte[]{'a', 1, 2, 'b'}) == Extracted.Status.UNREADABLE, "control bytes refused");
        require(TextChecksSupport.reads(app, dir, new byte[]{'h', 'i', '\r', '\n', '\t', 'x'}) == Extracted.Status.TEXT, "CRLF and tab are fine");
        require(TextChecksSupport.reads(app, dir, new byte[]{(byte) 0xFF, (byte) 0xFE, 'a'}) == Extracted.Status.UNREADABLE, "odd UTF-16 refused");
        mark.accept("Text: binary, NUL, invalid UTF-8, odd UTF-16 refused");

        // The library still takes the same documents and refuses the same ones.
        Context isolated = new ScannerChecks.IsolatedContext(app);
        LibraryRepository library = new LibraryRepository(isolated);
        library.initialize();
        String[][] good = {{"types/pdf/synthetic.pdf", "application/pdf", "Report.PDF", "Report"}, {"types/jpeg/synthetic.jpg", "image/jpeg", "Photo.JPEG", "Photo"},
                {"types/png/synthetic.png", "image/png", "shot.png", "shot"}, {"types/webp/synthetic.webp", "image/webp", "pic.WebP", "pic"}};
        for (String[] g : good) {
            File f = write(dir, "import-" + UUID.randomUUID(), asset(test, g[0]));
            Document d = library.importDocument(Uri.fromFile(f), UUID.randomUUID().toString(), g[2]);
            require(d.mime.equals(g[1]) && d.title.equals(g[3]) && d.pageCount == 1, "import " + g[0] + " -> " + d.mime + " '" + d.title + "'");
            Bitmap b = library.originalPage(d, 0, 200);
            require(b != null && b.getWidth() > 0, "preview via the registry " + g[0]);
            f.delete();
        }
        for (String path : new String[]{"types/amipro/synthetic.sam", "types/text/utf8.txt", "types/amipro/protected.sam"}) {
            File f = write(dir, "import-" + UUID.randomUUID(), asset(test, path));
            try { library.importDocument(Uri.fromFile(f), UUID.randomUUID().toString(), "x"); require(false, path + " must not import as a library document"); }
            catch (IOException expected) { require(expected.getMessage().startsWith("Choose a supported image or a PDF"), "message unchanged: " + expected.getMessage()); }
            f.delete();
        }
        File empty = write(dir, "import-empty", new byte[0]);
        try { library.importDocument(Uri.fromFile(empty), UUID.randomUUID().toString(), "x"); require(false, "empty import"); }
        catch (IOException expected) { require(expected.getMessage().contains("empty or incomplete"), "empty message unchanged"); }
        require(FileTypes.stripExtension("a.b.pdf").equals("a.b") && FileTypes.stripExtension("noext").equals("noext") && FileTypes.stripExtension("x.docx").equals("x.docx"), "extension strip");
        mark.accept("Library import and PageRenderer through the registry: same documents, same refusals and messages");

        // Whitelists stay exactly what archives and sync have always accepted.
        for (String m : new String[]{"application/pdf", "application/x-stack-scan", "image/jpeg", "image/png", "image/webp"}) require(FileTypes.isLibraryMime(m), m);
        for (String m : new String[]{"text/plain", "application/x-amipro", "image/gif", "", "application/pdf; x", "image/*"}) require(!FileTypes.isLibraryMime(m), "not " + m);
        require(!FileTypes.isLibraryMime(null), "null");
        require(Arrays.equals(FileTypes.imageMimes(), new String[]{"image/jpeg", "image/png", "image/webp"}), "photo picker list unchanged: " + Arrays.toString(FileTypes.imageMimes()));
        mark.accept("Archive/sync MIME whitelist and photo list unchanged");
    }

    private static String[] layerOf(File f, int maxPages, int millis) throws IOException {
        try (android.os.ParcelFileDescriptor fd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)) {
            return NativePdfText.ifAvailable().pageTexts(fd, maxPages, 0, millis);
        }
    }

    /** PDF text layer first, OCR only for pages without text; hostile files cost time and memory within bounds and never crash. */
    private static void pdfTextLayer(Context test, Context app, File dir, Consumer<String> mark) throws Exception {
        FileType pdf = FileTypes.byId("pdf");
        boolean native_ = NativePdfText.ifAvailable() != null;
        mark.accept("PDF text layer reader: " + (native_ ? "present" : "not available on this device (OCR only)"));
        if (native_) {
            for (String name : new String[]{"born-digital.pdf", "born-digital-objstm.pdf"}) {
                File f = write(dir, name, asset(test, "types/pdf/" + name));
                long t0 = android.os.SystemClock.elapsedRealtime();
                String[] layer = layerOf(f, 1000, 20000);
                long layerMs = android.os.SystemClock.elapsedRealtime() - t0;
                require(layer != null && layer.length == 3 && layer[0] != null && layer[1] != null && layer[2] != null, name + ": text layer on all three pages");
                require(layer[0].contains("Stack types: born digital page one") && layer[0].contains("café and naïve") && layer[0].contains("Courier fixed width: 1234567890"), name + ": fonts " + layer[0]);
                require(layer[0].contains("first office costs € 5 – a long dash") && layer[0].contains("Mac Roman: é è ö") && layer[0].contains("T01yab"), name + ": encodings " + layer[0]);
                require(layer[1].contains("Invoice 2026-1126 total 1,234.50"), name + ": page 2");
                long t1 = android.os.SystemClock.elapsedRealtime();
                Extracted e = extract(pdf, app, f, pdf.limits());
                long extractMs = android.os.SystemClock.elapsedRealtime() - t1;
                require(e.status == Extracted.Status.TEXT && e.pages.size() == 3 && e.pages.get(0).equals(layer[0]), name + ": handler uses the text layer, got " + e.status + " " + e.reason);
                mark.accept(name + ": 3 pages from the text layer in " + layerMs + " ms (whole extract " + extractMs + " ms, no OCR)");
                f.delete();
            }
            File mixed = write(dir, "mixed.pdf", asset(test, "types/pdf/mixed-text-and-image.pdf"));
            String[] layer = layerOf(mixed, 1000, 20000);
            require(layer != null && layer.length == 2 && layer[0] != null && layer[1] == null, "mixed: text page has a layer, image page does not");
            long t0 = android.os.SystemClock.elapsedRealtime();
            Extracted e = extract(pdf, app, mixed, pdf.limits());
            require(e.status == Extracted.Status.TEXT && e.pages.size() == 2, "mixed: " + e.status + " " + e.reason);
            require(e.pages.get(0).equals(layer[0]) && e.pages.get(1).toLowerCase().contains("types"), "mixed: page 1 from the layer, page 2 by OCR: '" + e.pages.get(1).trim() + "'");
            mark.accept("mixed file: page 1 from the text layer, page 2 by OCR (" + (android.os.SystemClock.elapsedRealtime() - t0) + " ms)");
            mixed.delete();
            File scan = write(dir, "scan.pdf", asset(test, "types/pdf/image-only.pdf"));
            String[] none = layerOf(scan, 1000, 20000);
            require(none != null && none.length == 1 && none[0] == null, "image-only: no text layer");
            scan.delete();
        }
        String[] hostile = {"hostile-deep-nesting.pdf", "hostile-huge-stream.pdf", "hostile-xref-loop.pdf", "hostile-broken-flate.pdf"};
        for (String name : hostile) {
            File f = write(dir, name, asset(test, "types/pdf/" + name));
            long t0 = android.os.SystemClock.elapsedRealtime();
            if (native_) {
                String[] layer = layerOf(f, 1000, 20000);
                require(layer == null || layer.length >= 1, name + ": layer answers");
                if (!name.equals("hostile-broken-flate.pdf")) require(layer != null && layer[0] != null && layer[0].contains("the types survive"), name + ": good page still read");
                layerOf(f, 1000, 100); // a tiny time budget answers too (pages or null), never hangs
            }
            long layerMs = android.os.SystemClock.elapsedRealtime() - t0;
            Extracted e = extract(pdf, app, f, pdf.limits());
            long total = android.os.SystemClock.elapsedRealtime() - t0;
            require(e.status != null && total < 120_000, name + ": ended in " + total + " ms");
            mark.accept(name + ": layer " + layerMs + " ms, whole extract " + total + " ms, status " + e.status);
            f.delete();
        }
    }

    /** Small helper: extract bytes as a text file. */
    private static final class TextChecksSupport {
        static Extracted.Status reads(Context app, File dir, byte[] bytes) throws IOException {
            File f = write(dir, "t-" + UUID.randomUUID(), bytes);
            try { return extract(FileTypes.byId("text"), app, f, FileTypes.byId("text").limits()).status; } finally { f.delete(); }
        }
    }
}
