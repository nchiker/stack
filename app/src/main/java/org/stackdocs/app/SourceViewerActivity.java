package org.stackdocs.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.stackdocs.app.types.FileType;
import org.stackdocs.app.types.FileTypes;
import org.stackdocs.app.types.Source;

/**
 * Stack's own viewer for a file found in a folder source (a content URI, never a library document), read-only.
 * The file is copied once to a private cache folder under a size cap, shown from there, and the copy is deleted when the screen closes;
 * a copy left by a crash is removed by the next start and after a day at the latest. The app lock is the one every Stack screen uses
 * (LibraryActivity): nothing is read or shown while Stack is locked.
 */
public final class SourceViewerActivity extends LibraryActivity {
    static final String DIR = "source-view";
    /** More than this is not copied (a phone is not for viewing a 1 GB scan); the file opens in another app's viewer instead. */
    static final long CAP = 96L * 1024 * 1024;
    private static final long MAX_AGE_MS = 24L * 3600 * 1000;
    /** A TextView lays out its whole text on the main thread: show the start of a long file only. */
    private static final int TEXT_CHARS = 60_000;

    private File copy;
    private FileType type;
    private int page, pages = 1;
    private ZoomView image;
    private TextView label, body, status;
    private View previous, next, navBar;
    private ScrollView textScroll;
    private volatile int generation;
    private boolean opened;
    private OpenWait wait;
    private View cancelWait;

    static Intent intent(android.content.Context context, Uri document, String mime, String title, String sourceId) {
        return new Intent(context, SourceViewerActivity.class).setDataAndType(document, mime == null || mime.isEmpty() ? "*/*" : mime)
                .putExtra("title", title == null ? "" : title).putExtra("source", sourceId == null ? "" : sourceId);
    }

    /** Removes copies left behind (a crash, a killed process) older than a day. */
    static void sweep(android.content.Context context, boolean everything) {
        File[] old = new File(context.getCacheDir(), DIR).listFiles();
        if (old == null) return;
        long limit = Clock.now() - MAX_AGE_MS;
        for (File f : old) if (everything || f.lastModified() < limit) f.delete();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            page = state.getInt("page", 0);
            File kept = state.getString("copy") == null ? null : new File(state.getString("copy"));
            if (kept != null && kept.isFile() && inside(kept)) copy = kept;   // a rotation keeps the copy: it is deleted only when the screen closes
        }
        buildUi();
        Ui.backHandler(this, this::finish);
        Uri uri = getIntent().getData();
        if (uri == null) { finish(); return; }
        sweep(this, false);
        if (copy != null) { status.setText(""); show(); return; }
        wait = new OpenWait((text, cancelable) -> { status.setText(text); cancelWait.setVisibility(cancelable ? View.VISIBLE : View.GONE); });
        wait.start();
        // Lock: work() waits until Stack is unlocked before it runs, and shows nothing until then.
        // Failures are answered in the screen itself (no dialog): the plain-words problem of SourceActions.openProblem.
        work(() -> {
            try { return new Object[]{copyToCache(uri), null}; }
            catch (IOException | RuntimeException error) {
                Throwable cause = error.getCause() instanceof SecurityException || error.getCause() instanceof android.app.AuthenticationRequiredException ? error.getCause() : error;
                String text = error.getMessage() != null && error.getMessage().startsWith("This file is too large") ? error.getMessage()
                        : SourceActions.openProblem((Exception) cause, SourceActions.online(this));
                return new Object[]{null, text};
            }
        }, result -> {
            if (!wait.live()) { if (result[0] != null) ((File) result[0]).delete(); return; }   // cancelled, or already told "Needs the internet"
            wait.done(); cancelWait.setVisibility(View.GONE);
            if (result[0] == null) { status.setText((String) result[1]); return; }
            copy = (File) result[0]; show();
        });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("page", page);
        if (copy != null) state.putString("copy", copy.getPath());
        super.onSaveInstanceState(state);
    }

    /** True for a file directly inside this app's viewer cache folder (a saved path is never trusted blindly). */
    private boolean inside(File file) {
        try { return new File(getCacheDir(), DIR).getCanonicalFile().equals(file.getCanonicalFile().getParentFile()); } catch (IOException unknown) { return false; }
    }

    private File copyToCache(Uri uri) throws IOException {
        File dir = new File(getCacheDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Free local storage and retry.");
        String source = getIntent().getStringExtra("source");
        File out = new File(dir, (source == null ? "" : source.replaceAll("[^0-9a-fA-F-]", "")) + "_" + UUID.randomUUID() + ".tmp");
        boolean done = false;
        try (InputStream in = openStream(uri); FileOutputStream to = new FileOutputStream(out)) {
            if (in == null) throw new IOException("no answer");
            byte[] buffer = new byte[64 * 1024]; long total = 0; int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > CAP) throw new IOException("This file is too large to view in Stack.");
                to.write(buffer, 0, n);
            }
            done = true;
            return out;
        } finally { if (!done) out.delete(); }
    }

    /** The file as a stream; Filen may take a while to fetch it, and the wait's Cancel stops that. */
    private InputStream openStream(Uri uri) throws IOException {
        ParcelFileDescriptor fd = wait == null ? getContentResolver().openFileDescriptor(uri, "r") : getContentResolver().openFileDescriptor(uri, "r", wait.signal);
        return fd == null ? null : new ParcelFileDescriptor.AutoCloseInputStream(fd);
    }

    private void show() {
        try {
            byte[] head = Source.of(copy).head(4096);
            type = FileTypes.match(head, getIntent().getStringExtra("title"), getIntent().getType());
        } catch (IOException error) { type = null; }
        if (type == null) { fail(); return; }
        if (!org.stackdocs.app.types.PageRender.drawable(type) && !type.id().equals("text")) { fail(); return; }
        render();   // a PDF's page count comes with its first page: nothing of the partner's file is decoded in this process
    }

    private void fail() { status.setText("This file could not be opened"); status.setVisibility(View.VISIBLE); image.setVisibility(View.GONE); }

    private void render() {
        final int mine = ++generation;
        previous.setEnabled(page > 0); next.setEnabled(page < pages - 1);
        navBar.setVisibility(pages > 1 ? View.VISIBLE : View.GONE); previous.setVisibility(View.VISIBLE); next.setVisibility(View.VISIBLE);
        label.setText(pages > 1 ? (page + 1) + " of " + pages : "");
        if (type.id().equals("text")) {
            work(() -> { try { return readText(); } catch (IOException error) { return null; } }, text -> {
                if (mine != generation) return;
                if (text == null) { fail(); return; }
                body.setText(text); textScroll.setVisibility(View.VISIBLE); image.setVisibility(View.GONE); status.setVisibility(View.GONE); });
            return;
        }
        final FileType shown = type; final File file = copy; final int index = page;
        if (renderSignal == null) renderSignal = new android.os.CancellationSignal();   // cancelled only when the screen closes: a page turned meanwhile just waits its turn
        final android.os.CancellationSignal signal = renderSignal;
        // One page at a time, in the isolated viewer process (never on the library's executor: a hard page can take its whole limit).
        check(() -> ExtractClient.viewer(this).render(file, shown.id(), index, org.stackdocs.app.types.PageRender.DEFAULT_EDGE, RENDER_MS, signal), result -> {
            if (mine != generation) { if (result.bitmap != null) result.bitmap.recycle(); return; }
            if (!result.drawn()) { fail(); return; }
            pages = result.pages; page = result.page;
            previous.setEnabled(page > 0); next.setEnabled(page < pages - 1);
            navBar.setVisibility(pages > 1 ? View.VISIBLE : View.GONE);
            label.setText(pages > 1 ? (page + 1) + " of " + pages : "");
            status.setVisibility(View.GONE); textScroll.setVisibility(View.GONE); image.setVisibility(View.VISIBLE);
            image.page(result.bitmap, "p" + page);
        });
    }

    /** One page's hard limit in the viewer's isolated process. */
    private static final long RENDER_MS = 30_000;
    private android.os.CancellationSignal renderSignal;

    private String readText() throws IOException {
        try (InputStream in = new java.io.FileInputStream(copy)) {
            byte[] data = new byte[TEXT_CHARS * 2 + 1]; int n = 0, r;
            while (n < data.length && (r = in.read(data, n, data.length - n)) > 0) n += r;
            boolean more = n == data.length;
            String text = new String(data, 0, Math.min(n, TEXT_CHARS * 2), java.nio.charset.StandardCharsets.UTF_8);
            if (text.length() > TEXT_CHARS) { text = text.substring(0, TEXT_CHARS); more = true; }
            return more ? text + "\n\n… (the rest of this long file is not shown)" : text;
        }
    }

    private void buildUi() {
        LinearLayout root = Ui.column(this);
        LinearLayout toolbar = Ui.toolbar(this);
        toolbar.addView(Ui.iconButton(this, Icons.Symbol.BACK, "Back", this::finish), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        TextView title = Ui.text(this, getIntent().getStringExtra("title"), 18, false);
        title.setMaxLines(1); title.setEllipsize(TextUtils.TruncateAt.END);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(toolbar);
        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(Ui.DOCUMENT_BG);
        image = new ZoomView(this);
        image.setContentDescription("File preview");
        content.addView(image, new FrameLayout.LayoutParams(-1, -1));
        body = Ui.text(this, "", 14, false);
        body.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        body.setTextIsSelectable(true);
        textScroll = new ScrollView(this);
        textScroll.addView(body);
        textScroll.setVisibility(View.GONE);
        content.addView(textScroll, new FrameLayout.LayoutParams(-1, -1));
        status = Ui.text(this, "", 14, false);
        status.setGravity(Gravity.CENTER);
        content.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        cancelWait = Ui.button(this, "Cancel", () -> { if (wait != null) wait.cancel(); finish(); });
        cancelWait.setVisibility(View.GONE);
        FrameLayout.LayoutParams cancelPosition = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        cancelPosition.topMargin = Ui.dp(this, 72);
        content.addView(cancelWait, cancelPosition);
        LinearLayout nav = Ui.row(this);
        nav.setGravity(Gravity.CENTER);
        nav.setBackground(Ui.rounded(this, Color.WHITE, 24));
        previous = Ui.iconButton(this, Icons.Symbol.PREVIOUS, "Previous page", () -> { page--; render(); });
        next = Ui.iconButton(this, Icons.Symbol.NEXT, "Next page", () -> { page++; render(); });
        nav.setVisibility(View.GONE);
        navBar = nav;
        label = Ui.text(this, "", 13, false);
        label.setGravity(Gravity.CENTER);
        label.setMinWidth(Ui.dp(this, 56));
        nav.addView(previous, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        nav.addView(label);
        nav.addView(next, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        previous.setVisibility(View.GONE); next.setVisibility(View.GONE);
        FrameLayout.LayoutParams navPosition = new FrameLayout.LayoutParams(-2, Ui.dp(this, 48), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        navPosition.bottomMargin = Ui.dp(this, 16);
        content.addView(nav, navPosition);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.install(this, root);
    }

    @Override protected void onDestroy() {
        generation++;
        if (renderSignal != null) renderSignal.cancel();
        if (isFinishing()) new Thread(() -> ExtractClient.viewer(getApplicationContext()).release(), "Stack viewer process").start();   // the isolated viewer process ends with the screen
        if (isFinishing() && wait != null && wait.live()) wait.cancel();
        if (isFinishing() && copy != null) { copy.delete(); copy = null; }
        image.release();
        super.onDestroy();
    }
}
