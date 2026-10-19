package org.stackdocs.app;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Button;
import android.widget.ImageView;
import android.app.AlertDialog;
import java.util.Arrays;

public final class PageEditorActivity extends LibraryActivity {
    private CropView crop;
    private ProgressBar progress;
    private PageState working;
    private String documentId;
    private int position;
    private int generation;
    private boolean saving;
    private boolean ready;
    private Button flatten;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        documentId = getIntent().getStringExtra("document_id");
        position = getIntent().getIntExtra("page", 0);
        LinearLayout root = Ui.column(this);
        LinearLayout top = Ui.toolbar(this);
        top.addView(Ui.iconButton(this, Icons.Symbol.CLOSE, "Cancel page edits", this::finish), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        top.addView(Ui.text(this, "Crop & rotate", 18, false), new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(Ui.button(this, "Apply", this::save));
        root.addView(top);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.BLUE));
        root.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(this, 2)));
        crop = new CropView(this);
        crop.setBackgroundColor(Ui.DOCUMENT_BG);
        root.addView(crop, new LinearLayout.LayoutParams(-1, 0, 1));
        android.widget.TextView instruction = Ui.text(this, "Drag the corners to the page edges", 14, false);
        instruction.setGravity(android.view.Gravity.CENTER);
        instruction.setTextColor(Ui.MUTED);
        instruction.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), 0);
        root.addView(instruction);
        LinearLayout tools = Ui.row(this);
        tools.addView(Ui.tool(this, Icons.Symbol.CROP, "Auto crop", this::autoCrop), new LinearLayout.LayoutParams(0, -2, 1));
        tools.addView(Ui.tool(this, Icons.Symbol.ROTATE, "Rotate", this::rotate), new LinearLayout.LayoutParams(0, -2, 1));
        tools.addView(Ui.tool(this, Icons.Symbol.COLOR, "Color", this::color), new LinearLayout.LayoutParams(0, -2, 1));
        flatten = Ui.tool(this, Icons.Symbol.DOCUMENT, getString(R.string.flatten_page), this::flatten);
        tools.addView(flatten, new LinearLayout.LayoutParams(0, -2, 1));
        tools.addView(Ui.tool(this, Icons.Symbol.BACK, "Reset", () -> {
            if (!ready || saving) return;
            working = working.edited(0, null, "original").flattened(false);
            reload();
        }), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(tools);
        Ui.install(this, root);
        work(() -> documentId == null ? new ScanDraftStore(this).load().pages.get(position)
                : app().library.pages(documentId).get(position), page -> {
            working = page;
            if (state != null && state.containsKey("crop")) working = page.edited(state.getInt("rotation"),
                    PageGeometry.decode(state.getString("crop")), state.getString("enhancement", "original"))
                    .flattened(state.getBoolean("flatten", page.flatten));
            reload();
        });
    }

    private Bitmap original() throws java.io.IOException {
        if (documentId != null) return app().library.originalPage(app().library.document(documentId), position, 1600);
        return PageRenderer.preview(new ScanDraftStore(this).file(working), "image/jpeg", 0, 1600);
    }

    private void reload() {
        flatten.setText(working.flatten ? R.string.flatten_page_enabled : R.string.flatten_page);
        ready = false;
        crop.setEnabled(false);
        int ticket = ++generation;
        PageState snapshot = working;
        progress.setVisibility(View.VISIBLE);
        work(() -> PageRenderer.enhance(PageRenderer.rotated(original(), snapshot.rotation), snapshot.enhancement), bitmap -> {
            if (ticket != generation) { bitmap.recycle(); return; }
            crop.setImage(bitmap, PageGeometry.display(snapshot.crop, snapshot.rotation));
            ready = true;
            crop.setEnabled(true);
            progress.setVisibility(View.INVISIBLE);
        }, () -> progress.setVisibility(View.INVISIBLE));
    }

    private void readCorners() {
        float[] points = PageGeometry.original(crop.points(), working.rotation);
        working = working.edited(working.rotation, Arrays.equals(points, PageGeometry.full()) ? null : points, working.enhancement);
    }

    private void rotate() {
        if (!ready || saving) return;
        readCorners();
        working = working.edited((working.rotation + 90) % 360, working.crop, working.enhancement);
        reload();
    }

    private void color() {
        if (!ready || saving) return;
        ColorOptions.show(this, working.enhancement, value -> {
            readCorners();
            working = working.edited(working.rotation, working.crop, value);
            reload();
        });
    }

    private void autoCrop() {
        if (!ready || saving) return;
        readCorners();
        PageState snapshot = working;
        int ticket = ++generation;
        ready = false;
        crop.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        work(() -> {
            Bitmap image = original();
            try { return BoundaryDetector.suggest(image); } finally { image.recycle(); }
        }, points -> {
            if (ticket != generation) return;
            if (points != null) crop.setPoints(PageGeometry.display(points, snapshot.rotation));
            else android.widget.Toast.makeText(this, "No clear page edges found. Adjust the corners manually.", android.widget.Toast.LENGTH_LONG).show();
            ready = true;
            crop.setEnabled(true);
            progress.setVisibility(View.INVISIBLE);
        }, () -> { ready = true; crop.setEnabled(true); progress.setVisibility(View.INVISIBLE); });
    }

    private void flatten() {
        if (!ready || saving) return;
        readCorners();
        PageState snapshot = working;
        int ticket = ++generation;
        ready = false; crop.setEnabled(false); progress.setVisibility(View.VISIBLE);
        work(() -> {
            PageState geometry = snapshot.edited(snapshot.rotation, snapshot.crop, "original").flattened(false);
            Bitmap image = documentId == null
                    ? PageRenderer.render(new ScanDraftStore(this).file(snapshot), "image/jpeg", geometry, 1600)
                    : app().library.renderPage(app().library.document(documentId), geometry, 1600);
            try {
                PageFlattening.Model model = PageFlattening.estimate(image);
                if (model == null) return null;
                Bitmap corrected = PageFlattening.apply(image, model);
                return PageRenderer.enhance(corrected, snapshot.enhancement);
            } finally { image.recycle(); }
        }, image -> {
            if (ticket != generation) { if (image != null) image.recycle(); return; }
            ready = true; crop.setEnabled(true); progress.setVisibility(View.INVISIBLE);
            AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("Flatten text curves")
                    .setNegativeButton("Cancel", null);
            if (image == null) {
                dialog.setMessage("No reliable text curve found. Crop the page and rotate the text upright first. Severe folds and curls need a flatter capture.")
                        .setPositiveButton("OK", null);
            } else {
                ImageView preview = new ImageView(this); preview.setAdjustViewBounds(true);
                preview.setScaleType(ImageView.ScaleType.FIT_CENTER); preview.setImageBitmap(image);
                preview.setMaxHeight(getResources().getDisplayMetrics().heightPixels * 2 / 3);
                dialog.setView(preview).setPositiveButton("Use flattening", (d, which) -> {
                    working = working.flattened(true);
                    flatten.setText(R.string.flatten_page_enabled);
                });
            }
            if (snapshot.flatten) dialog.setNeutralButton("Remove flattening", (d, which) -> {
                working = working.flattened(false); flatten.setText(R.string.flatten_page);
            });
            AlertDialog shown = dialog.create();
            shown.setOnDismissListener(d -> { if (image != null) image.recycle(); });
            shown.show();
        }, () -> { ready = true; crop.setEnabled(true); progress.setVisibility(View.INVISIBLE); });
    }

    private void save() {
        if (!ready || saving) return;
        readCorners();
        PageState snapshot = working;
        saving = true;
        crop.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        work(() -> {
            if (documentId == null) new ScanDraftStore(this).edit(position, snapshot);
            else app().library.editPage(documentId, position, snapshot);
            return true;
        }, ignored -> { setResult(RESULT_OK); finish(); }, () -> { saving = false; crop.setEnabled(true); progress.setVisibility(View.INVISIBLE); });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (working != null) {
            if (ready) readCorners();
            state.putString("crop", PageGeometry.encode(working.crop));
            state.putInt("rotation", working.rotation);
            state.putString("enhancement", working.enhancement);
            state.putBoolean("flatten", working.flatten);
        }
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        generation++;
        crop.release();
        super.onDestroy();
    }
}
