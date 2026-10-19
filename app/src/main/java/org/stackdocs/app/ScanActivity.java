package org.stackdocs.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

/** Capture and review one recoverable, multipage document draft. */
public final class ScanActivity extends LibraryActivity {
    private boolean requestedLight;
    private static final int CAMERA_PERMISSION = 30;
    private LinearLayout root;
    private CameraController camera;
    private ScanDraftStore.Draft draft;
    private int page;
    private int generation;
    private boolean resumed;
    private boolean cameraMode;
    private boolean permissionRequested;
    private boolean busy;
    private boolean persistingPhoto;
    private boolean capturing;
    private boolean cameraReady;
    private Runnable removeBackCallback;
    private String name;
    private boolean titleChosen,filingChosen,choicesLoaded;
    private EditText title;
    private Bitmap displayed;
    private ArrayList<String> selectedStacks = new ArrayList<>();
    private Button stacksButton;
    private Button saveButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(state!=null)requestedLight=state.getBoolean("requested_light",false);
        if (state != null) {
            page = state.getInt("page");
            cameraMode = state.getBoolean("camera");
            name = state.getString("name");
            titleChosen=state.getBoolean("title_chosen"); filingChosen=state.getBoolean("filing_chosen");
            choicesLoaded=true;
            permissionRequested = state.getBoolean("permission_requested");
            ArrayList<String> savedStacks = state.getStringArrayList("stacks");
            if (savedStacks != null) selectedStacks = savedStacks;
        }
        if (name == null) name = "Scan " + new SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.ROOT).format(new Date());
        root = Ui.column(this);
        root.addView(Ui.text(this, "Opening scan…", 20, true));
        Ui.install(this, root);
        if (Build.VERSION.SDK_INT >= 33) removeBackCallback = ModernBack.register(this, this::back);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (busy) return;
        busy = true;
        work(() -> new ScanDraftStore(this).load(), loaded -> {
            busy = false;
            draft = loaded;
            if(!choicesLoaded) {
                if(!loaded.name.isEmpty()) name=loaded.name;
                titleChosen=loaded.titleChosen; filingChosen=loaded.filingChosen;
                selectedStacks=new ArrayList<>(loaded.stackIds); choicesLoaded=true;
            }
            if (cameraMode || draft.pages.isEmpty()) showCamera(); else showReview();
        }, () -> {
            busy = false;
            clear();
            cameraMode = false;
            root.setBackgroundColor(Color.WHITE);
            Ui.darkBars(this, false);
            root.addView(Ui.text(this, "Your unfinished scan could not be opened. Its photos have been kept.", 20, true));
            root.addView(Ui.button(this, "Retry", this::recreate));
            root.addView(Ui.button(this, "Discard unfinished scan", this::discard));
            root.addView(Ui.button(this, "Close", this::finish));
        });
    }

    @Override protected void onPause() {
        resumed = false;
        if (capturing && !persistingPhoto) { busy = false; capturing = false; }
        closeCamera();
        super.onPause();
    }

    private void closeCamera() { if (camera != null) { camera.close(); camera = null; } }

    private void clear() {
        generation++;
        cameraReady = false;
        closeCamera();
        if (title != null) {
            getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(title.getWindowToken(), 0);
            name = title.getText().toString(); title = null;
        }
        root.removeAllViews();
        if (displayed != null) { displayed.recycle(); displayed = null; }
    }

    private void showCamera() {
        clear();
        cameraMode = true;
        root.setBackgroundColor(0xFF17181A);
        Ui.darkBars(this, true);
        LinearLayout top = Ui.row(this);
        top.setMinimumHeight(Ui.dp(this, 56));
        android.widget.ImageButton close = Ui.iconButton(this, Icons.Symbol.CLOSE, "Close camera", this::back);
        close.setImageDrawable(new Icons(Icons.Symbol.CLOSE, Color.WHITE));
        top.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        TextView cameraTitle = Ui.text(this, "Scan document", 18, false);
        cameraTitle.setTextColor(Color.WHITE);
        top.addView(cameraTitle, new LinearLayout.LayoutParams(0, -2, 1));
        Button review = Ui.button(this, "Review " + (draft == null ? 0 : draft.pages.size()), this::showReview);
        review.setTextColor(Color.WHITE);
        review.setEnabled(draft != null && !draft.pages.isEmpty());
        top.addView(review);
        root.addView(top);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF17181A);
        SurfaceView surface = new SurfaceView(this);
        frame.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView status = Ui.text(this, "Place the whole page in view. Hold steady, then capture.", 15, false);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setPadding(Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 12));
        root.addView(status);
        LinearLayout controls = Ui.row(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 24));
        Button light = Ui.button(this, "Light off", () -> {});
        light.setTextColor(Color.WHITE);
        Icons lightIcon = new Icons(Icons.Symbol.LIGHT, Color.WHITE);
        lightIcon.setBounds(0, 0, Ui.dp(this, 24), Ui.dp(this, 24));
        light.setCompoundDrawables(null, lightIcon, null, null);
        light.setTextSize(12);
        light.setEnabled(false);
        light.setOnClickListener(view -> {
            if (camera == null || busy) return;
            requestedLight=!light.isSelected();
            camera.torch(requestedLight);
        });
        controls.addView(light, new LinearLayout.LayoutParams(0, -2, 1));
        Button capture = Ui.button(this, "…", () -> {});
        capture.setContentDescription("Opening camera");
        capture.setEnabled(false);
        capture.setTextSize(12);
        capture.setTextColor(Ui.BLUE);
        capture.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        capture.setBackground(Ui.outlined(this, Color.WHITE, 0xFF9AA0A6, 40, 3));
        controls.addView(capture, new LinearLayout.LayoutParams(Ui.dp(this, 80), Ui.dp(this, 80)));
        View balance = new View(this);
        controls.addView(balance, new LinearLayout.LayoutParams(0, 1, 1));
        root.addView(controls);
        Runnable open = () -> {
            if (!resumed || busy || !cameraMode) return;
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                status.setText(R.string.camera_access_needed);
                capture.setText("Allow");
                capture.setContentDescription(getString(R.string.allow_camera));
                capture.setEnabled(true);
                if (!permissionRequested) {
                    permissionRequested = true;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
                }
                return;
            }
            if (camera != null) camera.open();
        };
        int ticket = generation;
        camera = new CameraController(this, surface, new CameraController.Listener() {
            @Override public void ready(boolean flashAvailable) {
                if (ticket != generation) return;
                cameraReady = true;
                capture.setText("");
                capture.setContentDescription(getString(R.string.capture_page));
                capture.setEnabled(true);
                light.setEnabled(flashAvailable);
                light.setText(flashAvailable?"Light off":"No light");
                light.setContentDescription(flashAvailable?"Continuous camera light off":"Camera has no light");
                camera.torch(requestedLight&&flashAvailable);
            }
            @Override public void light(boolean enabled){if(ticket!=generation)return;light.setSelected(enabled);light.setText(enabled?"Light on":"Light off");light.setContentDescription("Continuous camera light "+(enabled?"on":"off"));}
            @Override public void photo(byte[] jpeg) {
                if (ticket != generation) return;
                persistingPhoto = true;
                capturing = false;
                closeCamera();
                status.setText(R.string.keeping_scan_page);
                work(() -> new ScanDraftStore(ScanActivity.this).append(jpeg), loaded -> {
                    busy = false;
                    persistingPhoto = false;
                    draft = loaded;
                    page = draft.pages.size() - 1;
                    showReview();
                }, () -> { busy = false; persistingPhoto = false; showCamera(); });
            }
            @Override public void error(String message) {
                if (ticket != generation) return;
                busy = false;
                capturing = false;
                cameraReady = false;
                status.setText(message);
                capture.setText("Retry");
                capture.setContentDescription(getString(R.string.retry_camera));
                capture.setEnabled(true);
                light.setEnabled(false);
                light.setSelected(false);
                light.setText(R.string.light_off);
                review.setEnabled(draft != null && !draft.pages.isEmpty());
            }
        });
        capture.setOnClickListener(view -> {
            if (busy) return;
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                if (permissionRequested && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    new AlertDialog.Builder(this).setTitle("Allow camera in Android settings")
                            .setMessage("Open Permissions, then allow Camera for scanning. Your existing documents remain available without it.")
                            .setPositiveButton("Open settings", (dialog, which) -> startActivity(new Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName()))))
                            .setNegativeButton("Cancel", null).show();
                    return;
                }
                permissionRequested = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            } else if (cameraReady) {
                busy = true;
                capturing = true;
                capture.setEnabled(false);
                light.setEnabled(false);
                review.setEnabled(false);
                status.setText(R.string.hold_steady);
                camera.capture();
            } else { capture.setEnabled(false); open.run(); }
        });
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { if (ticket == generation) open.run(); }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (ticket == generation && camera != null) { camera.fitPreview(); open.run(); }
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { if (ticket == generation) closeCamera(); }
        });
        surface.addOnLayoutChangeListener((view, left, topEdge, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (ticket == generation && camera != null) camera.fitPreview();
        });
    }

    private void showReview() {
        if (busy) return;
        clear();
        cameraMode = false;
        root.setBackgroundColor(Color.WHITE);
        Ui.darkBars(this, false);
        LinearLayout top = Ui.toolbar(this);
        top.addView(Ui.iconButton(this, Icons.Symbol.CLOSE, "Close scan and keep draft", this::back), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        top.addView(Ui.text(this, "Preview", 18, false), new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(Ui.iconButton(this, Icons.Symbol.DELETE, "Discard scan", this::discard), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        root.addView(top);
        FrameLayout preview = new FrameLayout(this);
        preview.setBackgroundColor(Ui.DOCUMENT_BG);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 68));
        preview.addView(image, new FrameLayout.LayoutParams(-1, -1));
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        int count = draft == null ? 0 : draft.pages.size();
        page = Math.max(0, Math.min(page, count - 1));
        LinearLayout pages = Ui.row(this);
        pages.setGravity(Gravity.CENTER);
        pages.setBackground(Ui.rounded(this, Color.WHITE, 24));
        View previous = Ui.iconButton(this, Icons.Symbol.PREVIOUS, "Previous scan page", () -> { if (!busy) { page--; showReview(); } });
        previous.setEnabled(page > 0);
        pages.addView(previous, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        TextView label = Ui.text(this, count == 0 ? "No pages" : (page + 1) + " / " + count, 13, false);
        label.setGravity(Gravity.CENTER); label.setMinWidth(Ui.dp(this, 48));
        pages.addView(label);
        View next = Ui.iconButton(this, Icons.Symbol.NEXT, "Next scan page", () -> { if (!busy) { page++; showReview(); } });
        next.setEnabled(page + 1 < count);
        pages.addView(next, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        FrameLayout.LayoutParams pagePosition = new FrameLayout.LayoutParams(-2, Ui.dp(this, 48), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pagePosition.bottomMargin = Ui.dp(this, 12);
        preview.addView(pages, pagePosition);
        View remove = Ui.iconButton(this, Icons.Symbol.DELETE, "Remove this scan page", this::removePage);
        remove.setEnabled(count > 0);
        FrameLayout.LayoutParams removePosition = new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48), Gravity.BOTTOM | Gravity.END);
        removePosition.setMargins(0, 0, Ui.dp(this, 8), Ui.dp(this, 12));
        preview.addView(remove, removePosition);
        LinearLayout tools = Ui.row(this);
        Button color = Ui.tool(this, Icons.Symbol.COLOR, "Color", this::chooseColor);
        color.setEnabled(count > 0);
        tools.addView(color, new LinearLayout.LayoutParams(0, -2, 1));
        Button crop = Ui.tool(this, Icons.Symbol.CROP, "Crop & rotate", this::edit);
        crop.setEnabled(count > 0);
        tools.addView(crop, new LinearLayout.LayoutParams(0, -2, 1));
        tools.addView(Ui.tool(this, Icons.Symbol.ADD, "Add page", () -> { if (!busy) showCamera(); }), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(tools);
        LinearLayout filing = Ui.column(this);
        filing.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 12));
        TextView nameLabel = Ui.text(this, "Document name", 12, false);
        nameLabel.setTextColor(Ui.MUTED);
        nameLabel.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), 0, Ui.dp(this, 4));
        LinearLayout nameBox = Ui.column(this);
        nameBox.setBackground(Ui.rounded(this, Ui.DOCUMENT_BG, 4));
        nameBox.addView(nameLabel);
        title = new EditText(this);
        title.setSingleLine(true); title.setTextSize(16); title.setTextColor(Ui.INK);
        title.setHint("Document name"); title.setContentDescription("Document name"); title.setText(name);
        title.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 8));
        title.setBackgroundColor(Color.TRANSPARENT);
        title.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence text,int start,int count,int after) {}
            public void onTextChanged(CharSequence text,int start,int before,int count) {
                name=text.toString(); titleChosen=true; persistChoices();
            }
            public void afterTextChanged(android.text.Editable text) {}
        });
        nameBox.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(this, 36)));
        filing.addView(nameBox);
        LinearLayout actions = Ui.row(this);
        stacksButton = Ui.button(this, "Choose stacks", this::chooseStacks);
        stacksButton.setCompoundDrawablePadding(Ui.dp(this, 8));
        actions.addView(stacksButton, new LinearLayout.LayoutParams(0, -2, 1));
        Button save = Ui.primary(this, "Save scan", this::save);
        saveButton = save;
        save.setEnabled(count > 0);
        actions.addView(save);
        filing.addView(actions);
        root.addView(filing);
        updateStackLabel();
        persistChoices();
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        if (count == 0) return;
        int ticket = generation;
        PageState current = draft.pages.get(page);
        image.setContentDescription("Loading scan page " + (page + 1));
        work(() -> PageRenderer.render(new ScanDraftStore(this).file(current), "image/jpeg", current, 1600), bitmap -> {
            if (ticket != generation) { bitmap.recycle(); return; }
            displayed = bitmap;
            image.setImageBitmap(bitmap);
            image.setContentDescription("Scan page " + (page + 1));
        });
    }

    private void edit() {
        if (!busy) startActivity(new Intent(this, PageEditorActivity.class).putExtra("page", page));
    }

    private void chooseColor() {
        if (busy || draft == null || draft.pages.isEmpty()) return;
        int position = page;
        PageState current = draft.pages.get(position);
        ColorOptions.show(this, current.enhancement, value -> {
            busy = true;
            work(() -> new ScanDraftStore(this).edit(position, current.edited(current.rotation, current.crop, value)), loaded -> {
                busy = false;
                draft = loaded;
                showReview();
            }, () -> busy = false);
        });
    }

    private void removePage() {
        if (busy || draft == null || draft.pages.isEmpty()) return;
        int selectedPage = page;
        new AlertDialog.Builder(this).setTitle("Remove this scan page?")
                .setPositiveButton("Remove", (dialog, which) -> {
                    busy = true;
                    work(() -> new ScanDraftStore(this).remove(selectedPage), loaded -> {
                        busy = false; draft = loaded; showReview();
                    }, () -> busy = false);
                }).setNegativeButton("Cancel", null).show();
    }

    private void chooseStacks() {
        if (busy) return;
        work(() -> app().library.stacks(), stacks -> {
            if (busy) return;
            StackPicker.show(this, stacks, selectedStacks, selected -> {
                selectedStacks = new ArrayList<>(selected); filingChosen=true; persistChoices(); updateStackLabel();
            });
        });
    }

    private void persistChoices() {
        if(draft==null || draft.pages.isEmpty()) return;
        String id=draft.id, value=name; boolean titleTouch=titleChosen, filingTouch=filingChosen;
        List<String> stacks=new ArrayList<>(selectedStacks);
        work(() -> new ScanDraftStore(this).choices(id,value,titleTouch,stacks,filingTouch), ignored -> {});
    }

    private void updateStackLabel() {
        if (stacksButton == null) return;
        String label = selectedStacks.isEmpty() ? "Choose stacks" : selectedStacks.size() == 1 ? "1 stack" : selectedStacks.size() + " stacks";
        stacksButton.setText(label);
        stacksButton.setContentDescription(label + ", edit selection");
    }

    private void save() {
        if (busy || draft == null || draft.pages.isEmpty()) return;
        name = title.getText().toString();
        ScanDraftStore.Draft snapshot = draft;
        String chosenName = name;
        List<String> memberships = new ArrayList<>(selectedStacks);
        boolean chosenTitle = titleChosen, chosenFiling = filingChosen;
        busy = true;
        title.setEnabled(false);
        stacksButton.setEnabled(false);
        saveButton.setEnabled(false);
        saveButton.setText(R.string.saving_scan);
        Toast.makeText(this, "Saving scan…", Toast.LENGTH_SHORT).show();
        work(() -> {
            ScanDraftStore store = new ScanDraftStore(this);
            Document document = app().library.saveScan(store, snapshot, chosenName, chosenTitle, memberships, chosenFiling);
            store.discard();
            return document;
        }, document -> {
            busy = false;
            startActivity(new Intent(this, DocumentActivity.class).putExtra("document_id", document.id));
            finish();
        }, () -> {
            busy = false;
            title.setEnabled(true);
            stacksButton.setEnabled(true);
            saveButton.setEnabled(true);
            saveButton.setText(R.string.save_scan);
        });
    }

    private void discard() {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle("Discard this unfinished scan?")
                .setMessage("All of its captured pages will be removed.")
                .setPositiveButton("Discard", (dialog, which) -> {
                    busy = true;
                    work(() -> { new ScanDraftStore(this).discard(); return true; }, ignored -> { busy = false; finish(); }, () -> busy = false);
                }).setNegativeButton("Keep scan", null).show();
    }

    private void back() {
        if (busy) { Toast.makeText(this, "Please wait while your page is saved", Toast.LENGTH_SHORT).show(); return; }
        if (cameraMode && draft != null && !draft.pages.isEmpty()) showReview();
        else {
            if (draft != null && !draft.pages.isEmpty()) Toast.makeText(this, "Scan kept. Open Scan to continue.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @SuppressLint("GestureBackNavigation") // Native OnBackInvokedDispatcher handles API 33+ above.
    @Override public void onBackPressed() { back(); }

    @androidx.annotation.RequiresApi(33)
    private static final class ModernBack {
        static Runnable register(ScanActivity activity, Runnable action) {
            android.window.OnBackInvokedCallback callback = action::run;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return () -> activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == CAMERA_PERMISSION && cameraMode && !busy && !isFinishing()) showCamera();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("page", page);
        state.putStringArrayList("stacks", selectedStacks);
        state.putBoolean("title_chosen",titleChosen); state.putBoolean("filing_chosen",filingChosen);
        state.putBoolean("camera", cameraMode);
        state.putBoolean("permission_requested", permissionRequested);
        state.putString("name", title == null ? name : title.getText().toString());
        state.putBoolean("requested_light",requestedLight);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (removeBackCallback != null) removeBackCallback.run();
        clear();
        super.onDestroy();
    }
}
