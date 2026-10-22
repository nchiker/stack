package org.stackdocs.app;

import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public final class DocumentActivity extends LibraryActivity {
    private static final int SAVE_PDF = 20;
    private static final int EDIT_PAGE = 21;
    private String documentId;
    private Document document;
    private int page;
    private volatile int renderGeneration;
    private static final java.util.concurrent.ExecutorService previews=java.util.concurrent.Executors.newSingleThreadExecutor();
    private ZoomView image;
    private TextView title;
    private TextView metadata;
    private TextView textStatus;
    private String requestedPageId;
    private int textStatusGeneration;
    private TextView pageLabel;
    private LinearLayout memberships;
    private DetailsSheet detailsSheet;
    private SuggestionReview suggestions;
    private MetadataPanel metadataPanel;
    private View moreButton;
    private Runnable removeBackCallback;
    private ProgressBar progress;
    private View previous;
    private View next;
    private boolean exporting,reading,suggestionsExpanded;
    private android.widget.Button readingButton;
    private Runnable layoutPreview=() -> {};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        documentId = getIntent().getStringExtra("document_id");
        if (documentId == null) { finish(); return; }
        if (state != null) {page = state.getInt("page", 0);reading=state.getBoolean("reading");suggestionsExpanded=state.getBoolean("suggestions_expanded");}
        else requestedPageId = getIntent().getStringExtra("page_id");
        buildUi();
        if(state!=null){image.restore(state);String stable=state.getString("zoom_page");requestedPageId=stable==null||stable.isEmpty()?null:stable;}
        if (state != null) detailsSheet.setExpanded(state.getBoolean("details_expanded"), false);
        removeBackCallback = Ui.backHandler(this, this::back);
        load();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("page", page);
        state.putBoolean("reading",reading);
        state.putBoolean("suggestions_expanded",suggestionsExpanded);
        image.save(state);
        state.putBoolean("details_expanded", detailsSheet.expanded());
        super.onSaveInstanceState(state);
    }

    private void buildUi() {
        LinearLayout root = Ui.column(this);
        LinearLayout toolbar = Ui.toolbar(this);
        toolbar.addView(Ui.iconButton(this, Icons.Symbol.BACK, "Back", this::finish), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        title = Ui.text(this, "Document", 18, false);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(Ui.iconButton(this, Icons.Symbol.SHARE, "Share PDF", this::share), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        moreButton = Ui.iconButton(this, Icons.Symbol.MORE, "More document actions", this::more);
        toolbar.addView(moreButton, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        root.addView(toolbar);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.BLUE));
        root.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(this, 2)));
        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(Ui.DOCUMENT_BG);
        image = new ZoomView(this);

        image.setContentDescription("Document page preview");
        content.addView(image, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout zoomControls=Ui.row(this);
        zoomControls.setBackground(Ui.rounded(this,Color.WHITE,20));
        zoomControls.addView(Ui.button(this,"−",() -> image.changeZoom(image.zoom()/1.5f)));
        zoomControls.getChildAt(0).setContentDescription("Zoom out");
        zoomControls.addView(Ui.button(this,"Fit",image::reset));
        zoomControls.addView(Ui.button(this,"+",() -> image.changeZoom(image.zoom()*1.5f)));
        zoomControls.getChildAt(2).setContentDescription("Zoom in");
        readingButton=Ui.button(this,reading?"Details":"Read",this::toggleReading);readingButton.setContentDescription(reading?"Show document details":"Hide details for reading");zoomControls.addView(readingButton);
        content.addView(zoomControls,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        LinearLayout pages = Ui.row(this);
        pages.setGravity(Gravity.CENTER);
        pages.setBackground(Ui.rounded(this, Color.WHITE, 24));
        previous = Ui.iconButton(this, Icons.Symbol.PREVIOUS, "Previous page", () -> { page--; renderPage(); });
        next = Ui.iconButton(this, Icons.Symbol.NEXT, "Next page", () -> { page++; renderPage(); });
        previous.setEnabled(false);
        next.setEnabled(false);
        pageLabel = Ui.text(this, "", 13, false);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setMinWidth(Ui.dp(this, 56));
        pages.addView(previous, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        pages.addView(pageLabel);
        pages.addView(next, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        FrameLayout.LayoutParams pagePosition = new FrameLayout.LayoutParams(-2, Ui.dp(this, 48), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        content.addView(pages, pagePosition);
        View edit = Ui.iconButton(this, Icons.Symbol.TUNE, "Edit this page", this::editPage);
        edit.setBackground(Ui.ripple(this, Color.WHITE, 28));
        edit.setElevation(Ui.dp(this, 4));
        FrameLayout.LayoutParams editPosition = new FrameLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56), Gravity.BOTTOM | Gravity.END);
        editPosition.setMargins(0, 0, Ui.dp(this, 16), 0);
        content.addView(edit, editPosition);
        LinearLayout details = Ui.column(this);
        details.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 24));
        metadataPanel=new MetadataPanel(this,documentId);details.addView(metadataPanel.root);
        LinearLayout stackHeading = Ui.row(this);
        stackHeading.addView(Ui.text(this, "Stacks", 14, true), new LinearLayout.LayoutParams(0, -2, 1));
        stackHeading.addView(Ui.button(this, "Edit", this::chooseStacks));
        details.addView(stackHeading);
        memberships = Ui.column(this);
        details.addView(memberships);
        metadata = Ui.text(this, "", 12, false);
        metadata.setTextColor(Ui.MUTED);
        metadata.setPadding(0, Ui.dp(this, 12), 0, 0);
        textStatus = Ui.text(this,"Reading text…",13,false);
        textStatus.setTextColor(Ui.MUTED);
        textStatus.setPadding(0,Ui.dp(this,12),0,0);
        textStatus.setMinimumHeight(Ui.dp(this,48));
        textStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        suggestions=new SuggestionReview(this,documentId,this::load);
        android.widget.Button reviewNames=Ui.button(this,"Name & stack suggestions",() -> {suggestionsExpanded=!suggestionsExpanded;suggestions.root.setVisibility(suggestionsExpanded?View.VISIBLE:View.GONE);});
        details.addView(reviewNames);suggestions.root.setVisibility(suggestionsExpanded?View.VISIBLE:View.GONE);details.addView(suggestions.root);
        details.addView(metadata);details.addView(textStatus);
        detailsSheet = new DetailsSheet(this, details);
        content.addView(detailsSheet, new FrameLayout.LayoutParams(-1, Ui.dp(this, 160), Gravity.BOTTOM));
        layoutPreview=() -> {
            detailsSheet.fit(content.getHeight());detailsSheet.setVisibility(reading?View.GONE:View.VISIBLE);
            int peek=reading?0:detailsSheet.peekHeight();
            image.setPadding(Ui.dp(this,20),Ui.dp(this,56),Ui.dp(this,20),peek+Ui.dp(this,76));
            pagePosition.bottomMargin=peek+Ui.dp(this,12);pages.setLayoutParams(pagePosition);
            editPosition.bottomMargin=peek+Ui.dp(this,76);edit.setLayoutParams(editPosition);
        };
        content.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom) -> {if(right-left!=oldRight-oldLeft||bottom-top!=oldBottom-oldTop)layoutPreview.run();});
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.install(this, root);
    }

    private void load() {
        String requested=requestedPageId;
        int currentPage=page;
        read(() -> new Details(app().library.document(documentId),app().library.stacks(),
                requested == null ? currentPage : app().library.pagePosition(documentId,requested)), details -> {
            requestedPageId=null;
            page=details.position;
            document = details.document;
            title.setText(document.title);
            metadata.setText(getString(R.string.document_metadata,
                    getResources().getQuantityString(R.plurals.page_count, document.pageCount, document.pageCount),
                    DateFormat.getDateInstance().format(new Date(document.addedAt))));
            memberships.removeAllViews();
            LinearLayout row = null;
            int count = 0;
            for (StackCategory category : details.stacks) if (document.stackIds.contains(category.id)) {
                if (count++ % 2 == 0) {
                    row = Ui.row(this);
                    // Centre gravity with bottom-only child margins can lift a chip above this row.
                    row.setGravity(Gravity.TOP);
                    memberships.addView(row);
                }
                LinearLayout chip = Ui.row(this);
                chip.setGravity(Gravity.CENTER_VERTICAL);
                chip.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
                chip.setBackground(Ui.outlined(this, Color.WHITE, Ui.stackColor(category), 20, 1));
                chip.addView(Ui.icon(this, Icons.category(category), Ui.INK), new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));
                TextView label = Ui.text(this, category.name, 13, false); label.setPadding(Ui.dp(this, 8), 0, 0, 0);
                label.setMaxLines(1); label.setEllipsize(TextUtils.TruncateAt.END);
                chip.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
                chip.setContentDescription(category.name + ", edit document stacks");
                chip.setMinimumHeight(Ui.dp(this, 48)); chip.setFocusable(true);
                chip.setOnClickListener(view -> chooseStacks());
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
                size.setMargins(0, 0, Ui.dp(this, 8), Ui.dp(this, 8)); row.addView(chip, size);
            }
            if (count == 0) {
                TextView unfiled = Ui.text(this, "Not in a stack", 14, false); unfiled.setTextColor(Ui.MUTED); memberships.addView(unfiled);
            } else if (count % 2 != 0) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            page = Math.max(0, Math.min(page, document.pageCount - 1));
            renderPage();
        }, () -> progress.setVisibility(View.INVISIBLE));
    }

    private void renderPage() {
        if (document == null) return;
        page = Math.max(0, Math.min(page, document.pageCount - 1));
        refreshTextStatus();
        suggestions.refresh();
        metadataPanel.refresh();
        int current = page;
        int generation = ++renderGeneration;
        Document snapshot = document;

        image.setContentDescription("Loading page " + (page + 1));
        pageLabel.setText(getString(R.string.page_position, page + 1, document.pageCount));
        previous.setEnabled(page > 0);
        next.setEnabled(page + 1 < document.pageCount);
        progress.setVisibility(View.VISIBLE);
        image.release();
        read(() -> PdfCopies.prepare(app().library,new File(getCacheDir(),"pdf_plans"),snapshot.id),plan -> previews.execute(() -> {
            android.graphics.Bitmap bitmap=null;
            try(plan) {
                if(generation!=renderGeneration||!app().access.allowed())return;
                PageState selected=plan.pages.get(current);
                try{bitmap=PageRenderer.render(new File(plan.directory,selected.sourceFile),plan.document.mime,selected,3200);}
                catch(OutOfMemoryError constrained){bitmap=PageRenderer.render(new File(plan.directory,selected.sourceFile),plan.document.mime,selected,1600);}
                android.graphics.Bitmap result=bitmap;
                runOnUiThread(() -> {
                    if(isDestroyed()||isFinishing()||generation!=renderGeneration||!app().access.allowed()){result.recycle();return;}
                    image.page(result,selected.id);image.setOnClickListener(null);
                    image.setContentDescription("Page "+(current+1)+" of "+snapshot.pageCount+": "+snapshot.title);
                    progress.setVisibility(View.INVISIBLE);
                });
            }catch(Exception|OutOfMemoryError unavailable){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();runOnUiThread(() -> previewFailed(generation));}
        }),() -> previewFailed(generation));
    }
    private void previewFailed(int generation){
        if(isDestroyed()||generation!=renderGeneration)return;
        progress.setVisibility(View.INVISIBLE);image.setContentDescription("Preview unavailable. Tap to retry.");image.setOnClickListener(view -> renderPage());
    }

    @Override protected void onResume(){super.onResume();if(metadataPanel!=null)metadataPanel.refresh();if(document!=null&&app().access.allowed())renderPage();}

    @Override protected void onLibraryChanged() { refreshTextStatus(); suggestions.refresh(); metadataPanel.refresh(); }

    private void refreshTextStatus() {
        if (document == null) return;
        int selected=page, generation=++textStatusGeneration;
        read(() -> app().library.ocrStatus(documentId,selected), status -> {
            if (generation != textStatusGeneration) return;
            textStatus.setText(status.label());
            boolean retry = status.failed > 0;
            textStatus.setOnClickListener(retry ? view ->
                    work(() -> { app().library.retryFailedOcr(documentId); return true; }, ignored -> refreshTextStatus()) : null);
            textStatus.setClickable(retry);
            textStatus.setFocusable(retry);
        });
    }

    private void rename() {
        if (document == null) return;
        work(() -> {MetadataStore.View view=app().library.metadata.view(documentId);SuggestionStore.View old=app().library.suggestions.view(documentId);
            return MetadataRules.completions(old==null?document.title:old.result.title,document.addedAt,view.saved,view.offered);},names ->
            NameEditor.show(this,"Rename document",document.title,"Save",app().io,value -> app().library.rename(documentId,value),this::load,names));
    }

    private void chooseStacks() {
        if (document == null) return;
        work(() -> app().library.stacks(), stacks -> StackPicker.show(this, stacks, document.stackIds, selected ->
                work(() -> { app().library.setMemberships(documentId, selected); return true; }, ignored -> load())));
    }

    private void more() {
        if (document == null) return;
        PopupMenu menu = new PopupMenu(this, moreButton);
        menu.getMenu().add("Rename").setOnMenuItemClickListener(item -> { rename(); return true; });
        menu.getMenu().add("Add to stacks").setOnMenuItemClickListener(item -> { chooseStacks(); return true; });
        menu.getMenu().add("Save PDF to device").setOnMenuItemClickListener(item -> { save(); return true; });
        menu.getMenu().add("Crop, rotate & color").setOnMenuItemClickListener(item -> { editPage(); return true; });
        menu.getMenu().add("Remove this page").setOnMenuItemClickListener(item -> { removePage(); return true; });
        menu.getMenu().add("Delete document").setOnMenuItemClickListener(item -> { deleteDocument(); return true; });
        menu.show();
    }

    private void editPage() {
        if (document == null) return;
        Runnable edit = () -> startActivityForResult(new Intent(this, PageEditorActivity.class)
                .putExtra("document_id", documentId).putExtra("page", page), EDIT_PAGE);
        if (document.mime.equals("application/pdf")) {
            new AlertDialog.Builder(this).setTitle("Edit this PDF page?")
                    .setMessage("Edited PDFs are exported as page images. Existing selectable text, links and form fields will not carry into the export. Your original PDF is kept.")
                    .setPositiveButton("Continue", (confirm, action) -> edit.run()).setNegativeButton("Cancel", null).show();
        } else edit.run();
    }

    private void removePage() {
        if (document == null) return;
        if (document.pageCount < 2) {
            Toast.makeText(this, "A document needs at least one page", Toast.LENGTH_SHORT).show(); return;
        }
        int selectedPage = page;
        new AlertDialog.Builder(this).setTitle("Remove page " + (selectedPage + 1) + "?")
                .setMessage("The original file will be kept on this device. Exports will omit this page.")
                .setPositiveButton("Remove", (confirm, action) -> work(() -> {
                    app().library.removePage(documentId, selectedPage); return true;
                }, ignored -> load())).setNegativeButton("Cancel", null).show();
    }

    private void deleteDocument() {
        new AlertDialog.Builder(this).setTitle("Delete this document?")
                .setMessage("The original and its information will be removed from this device.")
                .setPositiveButton("Delete", (confirm, action) -> work(() -> {
                    app().library.deleteDocument(documentId); return true;
                }, ignored -> finish())).setNegativeButton("Cancel", null).show();
    }

    private void toggleReading(){reading=!reading;readingButton.setText(reading?"Details":"Read");readingButton.setContentDescription(reading?"Show document details":"Hide details for reading");layoutPreview.run();}
    private void back() { if(reading){toggleReading();return;} if (detailsSheet.expanded()) detailsSheet.setExpanded(false, true); else finish(); }
    @SuppressLint("GestureBackNavigation") // Native back callback is registered through Ui.backHandler.
    @Override public void onBackPressed() { back(); }
    @Override protected void onDestroy() {
        renderGeneration++;
        if(image!=null)image.release();
        if (detailsSheet != null) detailsSheet.release();
        if (removeBackCallback != null) removeBackCallback.run();
        super.onDestroy();
    }

    private void save() {
        if (document == null || exporting) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/pdf").putExtra(Intent.EXTRA_TITLE, pdfName());
        try {
            startActivityForResult(intent, SAVE_PDF);
        } catch (ActivityNotFoundException error) {
            new AlertDialog.Builder(this).setMessage("No document destination is available on this device.")
                    .setPositiveButton("OK", null).show();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EDIT_PAGE && result == RESULT_OK) { load(); return; }
        if (request != SAVE_PDF || result != RESULT_OK || data == null || data.getData() == null) return;
        if (exporting) return;
        exporting = true;
        Uri destination = data.getData();
        progress.setVisibility(View.VISIBLE);
        // Reload by stable ID: the activity may have been recreated while the picker was open.
        work(() -> {
            Document current = app().library.document(documentId);
            try (OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException("The selected destination could not be opened.");
                app().library.exportPdf(current, output);
                output.flush();
            }
            return true;
        }, ignored -> {
            progress.setVisibility(View.INVISIBLE);
            exporting = false;
            Toast.makeText(this, "PDF saved", Toast.LENGTH_SHORT).show();
        }, () -> { exporting = false; progress.setVisibility(View.INVISIBLE); });
    }

    private void share() {
        if (document == null || exporting) return;
        exporting = true;
        Document snapshot = document;
        String name = pdfName();
        progress.setVisibility(View.VISIBLE);
        work(() -> {
            File directory = new File(getCacheDir(), "exports");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Not enough space to share this PDF.");
            String token = UUID.randomUUID().toString();
            File file = new File(directory, token + ".pdf");
            try (FileOutputStream output = new FileOutputStream(file)) {
                app().library.exportPdf(snapshot, output);
                output.flush();
            } catch (Exception failure) {
                if (file.exists()) file.delete();
                throw failure;
            }
            return new Uri.Builder().scheme("content").authority(getPackageName() + ".exports")
                    .appendPath(token).appendPath(name).build();
        }, uri -> {
            exporting = false;
            progress.setVisibility(View.INVISIBLE);
            if(!app().access.allowed())return;
            Intent intent = new Intent(Intent.ACTION_SEND).setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("Document PDF", uri));
            try {
                startActivity(Intent.createChooser(intent, "Share document"));
            } catch (ActivityNotFoundException error) {
                Toast.makeText(this, "No app is available to receive this PDF.", Toast.LENGTH_LONG).show();
            }
        }, () -> { exporting = false; progress.setVisibility(View.INVISIBLE); });
    }

    private String pdfName() {
        return document.title.replaceAll("[\\p{Cntrl}/\\\\]", " ").trim() + ".pdf";
    }

    private static final class Rendered {final android.graphics.Bitmap bitmap;final String pageId;Rendered(android.graphics.Bitmap bitmap,String pageId){this.bitmap=bitmap;this.pageId=pageId;}}

    private static final class Details {
        final Document document;
        final List<StackCategory> stacks;
        final int position;
        Details(Document document, List<StackCategory> stacks, int position) { this.document = document; this.stacks = stacks; this.position=position; }
    }
}
