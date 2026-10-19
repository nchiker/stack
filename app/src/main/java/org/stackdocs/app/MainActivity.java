package org.stackdocs.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends LibraryActivity {
    private static final int IMPORT = 10;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable searchChanged = this::refresh;
    private final List<Object> rows = new ArrayList<>();
    private List<StackCategory> categories = new ArrayList<>();
    private String tab = "home";
    private String stackId;
    private final java.util.LinkedHashSet<String> selected=new java.util.LinkedHashSet<>();
    private android.widget.Button selection;
    private String organization;
    private String documentSort="newest";
    private FrameLayout filters;
    private ImageView filterIcon;
    private boolean shareConsumed;
    private boolean importing;
    private String seenImport="";
    private int requestGeneration;
    private EditText search;
    private ImageButton searchAction;
    private ImageButton clearSearch;
    private View libraryMenu;
    private TextView status;
    private ProgressBar progress;
    private ListView list;
    private FrameLayout content;
    private FrameLayout addMenu;
    private ImageButton add;
    private View home;
    private View all;
    private ImageView homeIcon;
    private ImageView allIcon;
    private TextView homeLabel;
    private TextView allLabel;
    private DocumentThumbnails thumbnails;
    private Runnable removeBackCallback;
    private final RowsAdapter adapter = new RowsAdapter();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        thumbnails = new DocumentThumbnails(this);
        if(state!=null){java.util.ArrayList<String> ids=state.getStringArrayList("selected");if(ids!=null)selected.addAll(ids);}
        if (state != null) {
            tab = state.getString("tab", "home");
            stackId = state.getString("stack");
            shareConsumed = state.getBoolean("shareConsumed");
            seenImport=state.getString("seen_import", "");
        }
        if(state==null) {
            android.content.SharedPreferences navigation=getSharedPreferences("navigation",MODE_PRIVATE);
            tab=navigation.getString("tab","home");stackId=navigation.getString("stack",null);
        }
        organization=state!=null?state.getString("organization"):getSharedPreferences("navigation",MODE_PRIVATE).getString("organization",null);
        documentSort="name".equals(getSharedPreferences("navigation",MODE_PRIVATE).getString("document_sort","newest"))?"name":"newest";
        buildUi();
        if(state==null)search.setText(getSharedPreferences("navigation",MODE_PRIVATE).getString("query",""));
        removeBackCallback = Ui.backHandler(this, this::back);
        if (state != null) search.setText(state.getString("query", ""));
        if (!shareConsumed && (Intent.ACTION_SEND.equals(getIntent().getAction())||Intent.ACTION_SEND_MULTIPLE.equals(getIntent().getAction()))) {
            shareConsumed = true;
            ArrayList<Uri> incoming=Intent.ACTION_SEND_MULTIPLE.equals(getIntent().getAction())?getIntent().getParcelableArrayListExtra(Intent.EXTRA_STREAM):new ArrayList<>();
            if(incoming==null)incoming=new ArrayList<>();
            if(Intent.ACTION_SEND.equals(getIntent().getAction())){Uri uri=getIntent().getParcelableExtra(Intent.EXTRA_STREAM);if(uri!=null)incoming.add(uri);}
            incoming.removeIf(uri -> !"content".equals(uri.getScheme()));
            if(incoming.size()==1)importUri(incoming.get(0));else if(!incoming.isEmpty())importBatch(incoming);
        }
    }

    @Override protected void onResume() { super.onResume(); thumbnails.invalidate(); refresh(); observeImport(); }

    @Override protected void onPause() {
        if(search!=null)getSharedPreferences("navigation",MODE_PRIVATE).edit().putString("tab",tab)
                .putString("stack",stackId).putString("organization",organization).putString("query",search.getText().toString()).apply();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putStringArrayList("selected",new ArrayList<>(selected));
        state.putString("tab", tab);
        state.putString("organization",organization);
        state.putString("stack", stackId);
        state.putString("query", search.getText().toString());
        state.putBoolean("shareConsumed", shareConsumed);
        state.putString("seen_import",seenImport);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(searchChanged);
        thumbnails.invalidate();
        if (removeBackCallback != null) removeBackCallback.run();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = Ui.column(this);
        root.setFocusableInTouchMode(true);
        LinearLayout bar = Ui.row(this);
        bar.setBackground(Ui.outlined(this, Color.WHITE, 0xFFE8EAED, 8, 1));
        bar.setElevation(Ui.dp(this, 2));
        searchAction = Ui.iconButton(this, Icons.Symbol.SEARCH, "Search documents", () -> {
            if (stackId != null || search.hasFocus() || search.length() > 0) back();
            else { search.requestFocus(); getSystemService(InputMethodManager.class).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT); }
        });
        bar.addView(searchAction, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setTextColor(Ui.INK);
        search.setHintTextColor(Ui.MUTED);
        search.setHint("Search your documents");
        search.setContentDescription("Search document names and text");
        search.setBackgroundColor(Color.TRANSPARENT);
        search.setPadding(0, 0, 0, 0);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((view, action, event) -> { hideKeyboard(); refresh(); return true; });
        bar.addView(search, new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1));
        clearSearch = Ui.iconButton(this, Icons.Symbol.CLOSE, "Clear search", () -> search.setText(""));
        clearSearch.setVisibility(View.GONE);
        bar.addView(clearSearch, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        FrameLayout options = new FrameLayout(this);
        TextView avatar = Ui.text(this, "S", 17, true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,17);
        avatar.setTextColor(Ui.BLUE);
        avatar.setBackground(Ui.rounded(this, 0xFFE8F0FE, 18));
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        options.addView(avatar, new FrameLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32), Gravity.CENTER));
        options.setContentDescription("Library options");
        options.setBackground(Ui.ripple(this, Color.TRANSPARENT, 24));
        options.setFocusable(true);
        options.setOnClickListener(view -> libraryOptions(view));
        libraryMenu = options;
        filters=new FrameLayout(this);
        filterIcon=Ui.icon(this,Icons.Symbol.FILTER,Ui.MUTED);
        filterIcon.setPadding(Ui.dp(this,6),Ui.dp(this,6),Ui.dp(this,6),Ui.dp(this,6));
        filterIcon.setBackground(Ui.rounded(this,Ui.DOCUMENT_BG,16));
        filters.addView(filterIcon,new FrameLayout.LayoutParams(Ui.dp(this,32),Ui.dp(this,32),Gravity.CENTER));
        filters.setContentDescription("Filter documents");filters.setFocusable(true);
        filters.setBackground(Ui.ripple(this,Color.TRANSPARENT,24));filters.setOnClickListener(view -> filters());
        bar.addView(filters,new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        bar.addView(options, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        LinearLayout.LayoutParams barLayout = new LinearLayout.LayoutParams(-1, -2);
        barLayout.setMargins(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        root.addView(bar, barLayout);
        search.setOnFocusChangeListener((view, focused) -> { hideAddMenu(); updateSearchChrome(); });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                updateSearchChrome();
                handler.removeCallbacks(searchChanged);
                handler.postDelayed(searchChanged, 200);
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        selection=Ui.button(this,"Selected documents",this::selectionActions);selection.setVisibility(View.GONE);root.addView(selection);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.BLUE));
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.BLUE));
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(this, 2)));
        status = Ui.text(this, "", 14, false);
        status.setTextColor(Ui.MUTED);
        status.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        status.setVisibility(View.GONE);
        root.addView(status);
        content = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setItemsCanFocus(true);
        list.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 88));
        list.setAdapter(adapter);
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));
        addMenu = new FrameLayout(this);
        addMenu.setVisibility(View.GONE);
        addMenu.setElevation(Ui.dp(this, 4));
        content.addView(addMenu, new FrameLayout.LayoutParams(-1, -1));
        add = Ui.iconButton(this, Icons.Symbol.ADD, "Add document", this::addDocument);
        add.setImageDrawable(new Icons(Icons.Symbol.ADD, Ui.BLUE));
        add.setBackground(Ui.ripple(this, Color.WHITE, 28));
        add.setElevation(Ui.dp(this, 6));
        add.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        FrameLayout.LayoutParams fab = new FrameLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56), Gravity.BOTTOM | Gravity.END);
        fab.setMargins(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        content.addView(add, fab);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(Ui.divider(this), new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        LinearLayout navigation = Ui.row(this);
        home = navigationItem(true);
        all = navigationItem(false);
        navigation.addView(home, new LinearLayout.LayoutParams(0, -2, 1));
        navigation.addView(all, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(navigation);
        Ui.install(this, root);
        root.requestFocus();
    }

    private View navigationItem(boolean isHome) {
        LinearLayout item = Ui.column(this);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));
        item.setMinimumHeight(Ui.dp(this, 56));
        item.setBackground(Ui.ripple(this, Color.TRANSPARENT, 0));
        ImageView icon = Ui.icon(this, isHome ? Icons.Symbol.HOME : Icons.Symbol.DOCUMENTS, Ui.MUTED);
        TextView label = Ui.text(this, isHome ? "Home" : "All documents", 12, false);
        label.setPadding(0, Ui.dp(this, 4), 0, 0);
        label.setGravity(Gravity.CENTER);
        item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        item.addView(label,new LinearLayout.LayoutParams(-1,-2));
        item.setFocusable(true);
        item.setContentDescription(isHome ? "Home" : "All documents");
        item.setOnClickListener(view -> selectTab(isHome ? "home" : "all"));
        if (isHome) { homeIcon = icon; homeLabel = label; }
        else { allIcon = icon; allLabel = label; }
        return item;
    }

    private void selectTab(String value) {
        hideAddMenu(); hideKeyboard(); search.clearFocus();
        tab = value; stackId = null; search.setText(""); refresh();
    }

    private void updateSearchChrome() {
        boolean back = stackId != null || search.hasFocus() || search.length() > 0;
        searchAction.setImageDrawable(new Icons(back ? Icons.Symbol.BACK : Icons.Symbol.SEARCH, Ui.MUTED));
        searchAction.setContentDescription(back ? "Back to documents" : "Search documents");
        clearSearch.setVisibility(search.length() > 0 ? View.VISIBLE : View.GONE);
        libraryMenu.setVisibility(search.hasFocus() ? View.GONE : View.VISIBLE);
        String hint = "Search your documents";
        for (StackCategory category : categories) if (category.id.equals(stackId)) hint = "Search " + category.name;
        search.setHint(hint);
    }

    @Override protected void onLibraryChanged() { observeImport(); refresh(); }

    private void refresh() {
        if (search == null) return;
        final int generation = ++requestGeneration;
        final String query = search.getText().toString().trim();
        final String filter = stackId;
        final String orgFilter=organization;
        final boolean homeTab=tab.equals("home");
        final String selectedOrder=documentSort;
        progress.setVisibility(View.VISIBLE);
        work(() -> {
            List<StackCategory> catalog=app().library.stacks();
            boolean present=filter==null;
            for(StackCategory category:catalog)if(category.id.equals(filter))present=true;
            java.util.LinkedHashMap<String,String> orgs=app().library.organizations();
            String org=orgs.containsKey(orgFilter)?orgFilter:null;
            boolean recent=homeTab&&(!present||filter==null)&&org==null&&query.isEmpty();
            String order=recent?"newest":selectedOrder;
            return new Snapshot(catalog,app().library.documents(query,present?filter:null,org,order),orgs,org);
        }, snapshot -> {
            if (generation != requestGeneration) return;
            selection.setText(selected.size()+" selected · Actions");
            selection.setVisibility(selected.isEmpty()?View.GONE:View.VISIBLE);
            categories = snapshot.categories;organization=snapshot.organization;
            filterIcon.setImageDrawable(new Icons(Icons.Symbol.FILTER,stackId==null&&organization==null?Ui.MUTED:Ui.BLUE));
            filters.setContentDescription("Filter documents"+(stackId==null?"":" · Stack filter active")+(organization==null?"":" · "+snapshot.orgs.get(organization)));
            filterIcon.setBackground(Ui.rounded(this,stackId==null&&organization==null?Ui.DOCUMENT_BG:0xFFE8F0FE,16));
            rows.clear();
            StackCategory current = null;
            for (StackCategory category : categories) if (category.id.equals(stackId)) current = category;
            if(stackId!=null && current==null)stackId=null;
            boolean showingStacks = tab.equals("home") && stackId == null && organization==null && query.isEmpty();
            if (showingStacks) {
                rows.add(new Header("Your Stacks", "Edit", this::manageStacks));
                List<StackCategory> visible = new ArrayList<>();
                for (StackCategory category : categories) if (category.visible) visible.add(category);
                visible.add(null); // Independent New stack control occupies a regular grid cell.
                int columns = stackColumns();
                for (int start = 0; start < visible.size(); start += columns) {
                    rows.add(new StackRow(new ArrayList<>(visible.subList(start, Math.min(start + columns, visible.size()))), columns));
                }
                if (!snapshot.documents.isEmpty()) rows.add(new Header("Recent documents", null, null));
            } else if (current != null && query.isEmpty()) {
                StackCategory selected = current;
                rows.add(new Header(selected.name, "Edit", () -> editStack(selected)));
            } else rows.add(new Header(query.isEmpty() ? "All documents" : "Search results", documentSort.equals("name")?"Name ▾":"Newest ▾", this::sortDocuments));
            int max = showingStacks ? Math.min(6, snapshot.documents.size()) : snapshot.documents.size();
            int columns = Math.max(2, getResources().getConfiguration().screenWidthDp / 180);
            if (!query.isEmpty()) {
                for (Document document : snapshot.documents) rows.add(document);
            } else for (int start = 0; start < max; start += columns) {
                rows.add(new DocumentRow(new ArrayList<>(snapshot.documents.subList(start, Math.min(start + columns, max))), columns));
            }
            if (snapshot.documents.isEmpty() && !showingStacks) rows.add(query.isEmpty()
                    ? "No documents yet" : "No documents found");
            status.setText(R.string.saving_document);
            status.setVisibility(importing ? View.VISIBLE : View.GONE);
            status.setOnClickListener(null);
            progress.setVisibility(importing ? View.VISIBLE : View.INVISIBLE);
            boolean onHome = tab.equals("home");
            home.setSelected(onHome); all.setSelected(!onHome);
            homeIcon.setImageDrawable(new Icons(Icons.Symbol.HOME, onHome ? Ui.BLUE : Ui.MUTED));
            allIcon.setImageDrawable(new Icons(Icons.Symbol.DOCUMENTS, onHome ? Ui.MUTED : Ui.BLUE));
            homeLabel.setTextColor(onHome ? Ui.BLUE : Ui.MUTED);
            allLabel.setTextColor(onHome ? Ui.MUTED : Ui.BLUE);
            updateSearchChrome();
            adapter.notifyDataSetChanged();
        }, () -> {
            if (generation != requestGeneration) return;
            progress.setVisibility(View.INVISIBLE);
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.library_error_retry);
            status.setOnClickListener(view -> refresh());
        });
    }

    private int stackColumns() {
        return Math.max(2, Math.min(4, (int) ((getResources().getConfiguration().screenWidthDp - 24)
                / (84 * Math.max(1, getResources().getConfiguration().fontScale)))));
    }

    private void addDocument() {
        if (importing) return;
        if (addMenu.getVisibility() == View.VISIBLE) { hideAddMenu(); return; }
        hideKeyboard(); search.clearFocus();
        addMenu.removeAllViews();
        View scrim = new View(this);
        scrim.setBackgroundColor(0x33000000);
        scrim.setOnClickListener(view -> hideAddMenu());
        addMenu.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout menu = Ui.column(this);
        menu.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        menu.setBackground(Ui.rounded(this, Color.WHITE, 12));
        menu.setElevation(Ui.dp(this, 8));
        menu.addView(addOption(Icons.Symbol.DOCUMENT, "PDF", "Import a PDF", () -> pickImport(false)));
        menu.addView(addOption(Icons.Symbol.PHOTO, "Photo", "Import a photo", () -> pickImport(true)));
        menu.addView(addOption(Icons.Symbol.CAMERA, "Camera", "Scan a document", () -> {
            hideAddMenu(); startActivity(new Intent(this, ScanActivity.class));
        }));
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(Ui.dp(this, 208), -2, Gravity.BOTTOM | Gravity.END);
        position.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 88));
        addMenu.addView(menu, position);
        addMenu.setVisibility(View.VISIBLE);
        list.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        add.setImageDrawable(new Icons(Icons.Symbol.CLOSE, Ui.BLUE));
        add.setContentDescription("Close add menu");
        menu.getChildAt(0).requestFocus();
    }

    private View addOption(Icons.Symbol symbol, String label, String description, Runnable action) {
        LinearLayout item = Ui.row(this);
        item.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        item.setMinimumHeight(Ui.dp(this, 52));
        item.setBackground(Ui.ripple(this, Color.TRANSPARENT, 8));
        item.addView(Ui.icon(this, symbol, Ui.BLUE), new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        TextView text = Ui.text(this, label, 16, false);
        text.setPadding(Ui.dp(this, 20), 0, 0, 0);
        item.addView(text);
        item.setContentDescription(description);
        item.setFocusable(true);
        item.setOnClickListener(view -> action.run());
        return item;
    }

    private void hideAddMenu() {
        if (addMenu == null) return;
        addMenu.setVisibility(View.GONE);
        list.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        add.setImageDrawable(new Icons(Icons.Symbol.ADD, Ui.BLUE));
        add.setContentDescription("Add document");
    }

    private void pickImport(boolean photo) {
        hideAddMenu();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(photo ? "image/*" : "application/pdf").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        if (photo) intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png", "image/webp"});
        try { startActivityForResult(intent, IMPORT); }
        catch (ActivityNotFoundException error) {
            new AlertDialog.Builder(this).setMessage("No document picker is available on this device.").setPositiveButton("OK", null).show();
        }
    }

    private void filters(){
        work(() -> app().library.organizations(),orgs -> {
            java.util.List<String> ids=new ArrayList<>(orgs.keySet()),labels=new ArrayList<>();labels.add("Clear organization filter");for(String name:orgs.values())labels.add("Organization · "+name);
            new AlertDialog.Builder(this).setTitle("Filter documents").setItems(new String[]{"Organization", "Stack / category", "Clear all filters"},(d,w) -> {
                if(w==0)new AlertDialog.Builder(this).setTitle("Organization").setItems(labels.toArray(new String[0]),(dialog,index) -> {organization=index==0?null:ids.get(index-1);refresh();}).setNegativeButton("Cancel",null).show();
                else if(w==1){List<String> names=new ArrayList<>();names.add("Clear stack filter");for(StackCategory category:categories)names.add("Stack · "+category.name);
                    new AlertDialog.Builder(this).setTitle("Stack / category").setItems(names.toArray(new String[0]),(dialog,index) -> {stackId=index==0?null:categories.get(index-1).id;refresh();}).setNegativeButton("Cancel",null).show();}
                else{organization=null;stackId=null;refresh();}
            }).setNegativeButton("Cancel",null).show();
        });
    }

    private void libraryOptions(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Select documents").setOnMenuItemClickListener(item -> {selectionActions();return true;});
        menu.getMenu().add("Sort documents").setOnMenuItemClickListener(item -> {sortDocuments();return true;});
        menu.getMenu().add("Settings").setOnMenuItemClickListener(item -> {startActivity(new Intent(this,SettingsActivity.class));return true;});
        menu.getMenu().add("Imports & batch actions").setOnMenuItemClickListener(item -> {startActivity(new Intent(this,BatchActivity.class));return true;});
        menu.getMenu().add("Edit stacks").setOnMenuItemClickListener(item -> { manageStacks(); return true; });
        menu.getMenu().add("Backup & restore").setOnMenuItemClickListener(item -> {
            startActivity(new Intent(this,BackupActivity.class));return true;
        });
        menu.getMenu().add("About Stack").setOnMenuItemClickListener(item -> {
            new AlertDialog.Builder(this).setTitle("Stack")
                    .setMessage("An independent, open-source document library. Documents are stored on this device. No account is needed.\n\nPrinted-English text recognition, full-document search and local library backup/restore work offline. Backups exclude unfinished scans and are not encrypted.")
                    .setPositiveButton("OK", null).show();
            return true;
        });
        menu.show();
    }

    private AlertDialog sortDocuments() {
        return new AlertDialog.Builder(this).setTitle("Sort documents")
            .setSingleChoiceItems(new String[]{"Newest first","Name (A–Z)"},documentSort.equals("name")?1:0,(dialog,index) -> {
                documentSort=index==1?"name":"newest";
                getSharedPreferences("navigation",MODE_PRIVATE).edit().putString("document_sort",documentSort).apply();
                dialog.dismiss();refresh();
            }).setNegativeButton("Cancel",null).show();
    }

    private void hideKeyboard() { getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0); }

    private void back() {
        if (addMenu.getVisibility() == View.VISIBLE) { hideAddMenu(); return; }
        if (search.hasFocus() || search.length() > 0) { hideKeyboard(); search.clearFocus(); search.setText(""); updateSearchChrome(); return; }
        if (stackId != null) { stackId = null; refresh(); return; }
        if (!tab.equals("home")) { selectTab("home"); return; }
        finish();
    }

    @SuppressLint("GestureBackNavigation") // Ui.backHandler uses the native API on Android 13+.
    @Override public void onBackPressed() { back(); }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if(request==IMPORT&&result==RESULT_OK&&data!=null){ArrayList<Uri> uris=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)uris.add(data.getData());
            uris.removeIf(uri -> !"content".equals(uri.getScheme()));if(uris.size()==1)importUri(uris.get(0));else if(!uris.isEmpty())importBatch(uris);
        }
    }

    private void importBatch(List<Uri> uris){if(!app().access.allowed()){whenAllowed(() -> importBatch(uris));return;}if(uris.size()>BatchStore.LIMIT){new AlertDialog.Builder(this).setMessage("Select at most 50 files per import.").setPositiveButton("OK",null).show();return;}app().batches.importUris(uris,id -> {if(!id.isEmpty())startActivity(new Intent(this,BatchActivity.class).putExtra("operation_id",id));});}
    private void toggle(String id){if(!selected.remove(id)){if(selected.size()>=BatchStore.LIMIT){android.widget.Toast.makeText(this,"Select up to 50 documents",android.widget.Toast.LENGTH_SHORT).show();return;}selected.add(id);}refresh();}
    private void selectionActions(){
        if(selected.isEmpty()){
            work(() -> app().library.documents(search.getText().toString(),stackId,organization),docs -> {
                String[] labels=new String[docs.size()];boolean[] checked=new boolean[docs.size()];for(int i=0;i<docs.size();i++)labels[i]=docs.get(i).title;
                new AlertDialog.Builder(this).setTitle("Select up to 50 · tap order is output order").setMultiChoiceItems(labels,checked,(d,index,on) -> {String id=docs.get(index).id;if(on&&selected.size()<BatchStore.LIMIT)selected.add(id);else {selected.remove(id);((AlertDialog)d).getListView().setItemChecked(index,false);}}).setPositiveButton("Actions",(d,w) -> {refresh();if(!selected.isEmpty())selectionActions();}).setNegativeButton("Cancel",(d,w) -> {selected.clear();refresh();}).show();
            });return;
        }
        new AlertDialog.Builder(this).setTitle(selected.size()+" selected · selection order").setItems(new String[]{"Add to stacks","Replace all selected filing…","Share PDFs","Share ZIP of PDFs","Delete selected…","Clear selection"},(d,w) -> {
            if(w<=1)work(() -> app().library.stacks(),stacks -> StackPicker.show(this,stacks,java.util.Collections.emptyList(),ids -> batch(w==0?"file-add":"file-replace",ids)));
            else if(w<=3)batch(w==2?"pdf":"zip",java.util.Collections.emptyList());
            else if(w==4)new AlertDialog.Builder(this).setTitle("Delete "+selected.size()+" selected documents?").setMessage("Remove these documents, their details, notes, memberships and owned originals from this device. Other documents stay saved.").setPositiveButton("Delete selected",(confirm,action) -> batch("delete",java.util.Collections.emptyList())).setNegativeButton("Cancel",null).show();
            else{selected.clear();refresh();}
        }).setNegativeButton("Cancel",null).show();
    }
    private void batch(String kind,List<String> stacks){List<String> ids=new ArrayList<>(selected);work(() -> {List<String> sources=new ArrayList<>();for(String id:ids){if(kind.startsWith("file-")){long rev;try(android.database.Cursor row=app().library.database().rawQuery("SELECT filing_decision FROM documents WHERE id=?",new String[]{id})){if(!row.moveToFirst())throw new IllegalArgumentException("A selected document was deleted. Review the selection.");rev=row.getLong(0);}sources.add(new org.json.JSONObject().put("revision",rev).put("stacks",new org.json.JSONArray(stacks)).toString());}else sources.add(id);}String operation=app().library.batches.create(kind,sources,ids);app().batches.run(operation);return operation;},operation -> {selected.clear();startActivity(new Intent(this,BatchActivity.class).putExtra("operation_id",operation));refresh();});}

    private void importUri(Uri uri) {
        if(!app().access.allowed()){whenAllowed(() -> importUri(uri));return;}
        if(app().imports.busy)return;
        app().imports.start(uri);importing=true;refresh();
    }

    private void observeImport() {
        ImportController transfer=app().imports;importing=transfer.busy;
        if(transfer.busy || transfer.token.isEmpty() || transfer.token.equals(seenImport))return;
        seenImport=transfer.token;
        if(!transfer.documentId.isEmpty())startActivity(new Intent(this,DocumentActivity.class).putExtra("document_id",transfer.documentId));
        else if(!transfer.failure.isEmpty())new AlertDialog.Builder(this).setTitle("Couldn't import document")
                .setMessage(transfer.failure+" Select the source again to retry.").setPositiveButton("OK",null).show();
    }

    private void open(Document document) {
        startActivity(new Intent(this, DocumentActivity.class).putExtra("document_id", document.id).putExtra("page_id", document.matchPageId));
    }

    private void manageStacks() {
        startActivity(new Intent(this, StackEditorActivity.class));
    }

    private void createStack() {
        NameEditor.show(this,"Create a stack","","Create",app().io,
                value -> app().library.createStack(value),this::refresh);
    }

    private void editStack(StackCategory category) {
        new AlertDialog.Builder(this).setTitle(category.name)
                .setItems(new String[]{category.visible ? "Hide from Home" : "Show on Home", "Delete stack"},
                        (dialog, position) -> {
                            if (position == 0) {
                                work(() -> { app().library.setStackVisible(category.id, !category.visible); return true; }, ignored -> refresh());
                            } else new AlertDialog.Builder(this).setTitle("Delete this stack?")
                                    .setMessage("Its documents will stay in All documents.")
                                    .setPositiveButton("Delete", (confirm, which) -> work(() -> {
                                        app().library.deleteStack(category.id); return true;
                                    }, ignored -> {
                                        if (category.id.equals(stackId)) stackId = null;
                                        refresh();
                                    })).setNegativeButton("Cancel", null).show();
                        }).setNegativeButton("Cancel", null).show();
    }

    private static final class Snapshot {
        final List<StackCategory> categories;
        final List<Document> documents;
        final java.util.LinkedHashMap<String,String> orgs;final String organization;
        Snapshot(List<StackCategory> categories, List<Document> documents,java.util.LinkedHashMap<String,String> orgs,String organization) { this.categories = categories; this.documents = documents;this.orgs=orgs;this.organization=organization; }
    }
    private static final class Header {
        final String title, action;
        final Runnable click;
        Header(String title, String action, Runnable click) { this.title = title; this.action = action; this.click = click; }
    }
    private static final class StackRow {
        final List<StackCategory> stacks;
        final int columns;
        StackRow(List<StackCategory> stacks, int columns) { this.stacks = stacks; this.columns = columns; }
    }
    private static final class DocumentRow {
        final List<Document> documents;
        final int columns;
        DocumentRow(List<Document> documents, int columns) { this.documents = documents; this.columns = columns; }
    }

    private View stackTile(StackCategory category) {
        LinearLayout tile = Ui.column(this);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 10));
        tile.setBackground(Ui.ripple(this, Color.TRANSPARENT, 12));
        View badge;
        if (category != null) badge = Ui.stackBadge(this, category, 72);
        else {
            ImageView plus = Ui.icon(this, Icons.Symbol.ADD, Ui.BLUE);
            plus.setPadding(Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22));
            plus.setBackground(Ui.outlined(this, Color.WHITE, 0xFFE8F0FE, 36, 2));
            badge = plus;
        }
        tile.addView(badge, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)));
        TextView label = Ui.text(this, category == null ? "New stack" : category.name, 14, false);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(0, Ui.dp(this, 8), 0, 0);
        if (category == null) label.setTextColor(Ui.BLUE);
        tile.addView(label, new LinearLayout.LayoutParams(-1, -2));
        tile.setFocusable(true);
        tile.setContentDescription(category == null ? "Create a new stack" : category.name + ", " + category.count + " documents");
        tile.setOnClickListener(view -> {
            if (category == null) createStack();
            else { hideAddMenu(); hideKeyboard(); search.clearFocus(); stackId = category.id; refresh(); list.setSelection(0); }
        });
        if (category != null) tile.setOnLongClickListener(view -> { editStack(category); return true; });
        return tile;
    }

    private View documentTile(Document document, boolean result) {
        LinearLayout tile = result ? Ui.row(this) : Ui.column(this);
        tile.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 12));
        tile.setBackground(Ui.ripple(this, Color.TRANSPARENT, 8));
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackground(Ui.outlined(this, 0xFFFAFAFA, 0xFFE8EAED, 6, 1));
        preview.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        preview.setImageDrawable(new Icons(Icons.Symbol.DOCUMENT, Ui.LINE));
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(preview, new LinearLayout.LayoutParams(result ? Ui.dp(this, 88) : -1, Ui.dp(this, result ? 100 : 156)));
        thumbnails.bind(preview, document);
        LinearLayout words = Ui.column(this);
        words.setPadding(result ? Ui.dp(this, 16) : 0, Ui.dp(this, 8), 0, 0);
        TextView name = Ui.text(this, document.title, result ? 17 : 13, false);
        name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(result ? Gravity.START : Gravity.CENTER);
        words.addView(name, new LinearLayout.LayoutParams(-1, -2));
        if (result) {
            TextView snippet = Ui.text(this, document.snippet.isEmpty() ? document.pageCount + (document.pageCount == 1 ? " page" : " pages") : document.snippet, 14, false);
            snippet.setTextColor(Ui.MUTED); snippet.setMaxLines(3); snippet.setPadding(0, Ui.dp(this, 8), 0, 0);
            words.addView(snippet);
        }
        tile.addView(words, result ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2));
        tile.setFocusable(true);
        tile.setContentDescription(document.title + ", " + document.pageCount + (document.pageCount == 1 ? " page" : " pages"));
        tile.setSelected(selected.contains(document.id));
        if(selected.contains(document.id))tile.setBackground(Ui.outlined(this,0xFFE8F0FE,Ui.BLUE,8,2));
        tile.setContentDescription((selected.contains(document.id)?"Selected, ":"")+document.title+", "+document.pageCount+" pages. Hold to select.");
        tile.setOnLongClickListener(view -> {toggle(document.id);return true;});
        tile.setOnClickListener(view -> {hideKeyboard();if(selected.isEmpty())open(document);else toggle(document.id);});
        return tile;
    }

    private final class RowsAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return !(rows.get(position) instanceof String); }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Object item = rows.get(position);
            if (item instanceof Header) {
                Header header = (Header) item;
                LinearLayout row = Ui.row(MainActivity.this);
                row.setMinimumHeight(Ui.dp(MainActivity.this, 48));
                row.setPadding(Ui.dp(MainActivity.this, 8), Ui.dp(MainActivity.this, 2), 0, 0);
                row.addView(Ui.text(MainActivity.this, header.title, 14, true), new LinearLayout.LayoutParams(0, -2, 1));
                if (header.action != null) row.addView(Ui.button(MainActivity.this, header.action, header.click));
                return row;
            }
            if (item instanceof Document) return documentTile((Document) item, true);
            if (item instanceof String) {
                LinearLayout empty = Ui.column(MainActivity.this); empty.setGravity(Gravity.CENTER);
                empty.setPadding(Ui.dp(MainActivity.this, 24), Ui.dp(MainActivity.this, 72), Ui.dp(MainActivity.this, 24), Ui.dp(MainActivity.this, 48));
                empty.addView(Ui.icon(MainActivity.this, Icons.Symbol.DOCUMENTS, Ui.LINE), new LinearLayout.LayoutParams(Ui.dp(MainActivity.this, 64), Ui.dp(MainActivity.this, 64)));
                TextView title = Ui.text(MainActivity.this, (String) item, 18, false); title.setPadding(0, Ui.dp(MainActivity.this, 24), 0, Ui.dp(MainActivity.this, 12)); empty.addView(title);
                TextView hint = Ui.text(MainActivity.this, search.length() == 0 ? "Tap + to scan a page or import a photo or PDF." : "Try another name or text phrase, or clear your search.", 14, false);
                hint.setTextColor(Ui.MUTED); hint.setGravity(Gravity.CENTER); empty.addView(hint);
                return empty;
            }
            LinearLayout row = Ui.row(MainActivity.this); row.setGravity(Gravity.TOP);
            int columns = item instanceof StackRow ? ((StackRow) item).columns : ((DocumentRow) item).columns;
            for (int i = 0; i < columns; i++) {
                View cell;
                if (item instanceof StackRow) {
                    StackRow group = (StackRow) item;
                    cell = i < group.stacks.size() ? stackTile(group.stacks.get(i)) : new View(MainActivity.this);
                } else {
                    DocumentRow group = (DocumentRow) item;
                    cell = i < group.documents.size() ? documentTile(group.documents.get(i), false) : new View(MainActivity.this);
                }
                row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
            }
            return row;
        }
    }
}
