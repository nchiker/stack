package org.stackdocs.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.stackdocs.app.types.FileType;
import org.stackdocs.app.types.FileTypes;

/**
 * What a person can do with a file found in a folder source (design section 1, "What Sam sees"): open it, add it to a stack, tag it, share it.
 * Opening builds the document URI from the source's tree grant and hands it to the type's opener. Stack's own viewer cannot take a content URI
 * (it shows library documents only), so a Stack-viewer type is opened by SourceViewerActivity (Stack's own read-only viewer, from a private
 * cache copy), and Ami Pro files by ACTION_VIEW with a READ grant to its package.
 */
final class SourceActions {
    /**
     * The second line of a result row: where the file is. A partner's file reads "From [name] · [folder]"; the partner's scan folder is shown
     * as "Shared scans". Your own file reads "[source folder]/[subfolder]". The file's own name is not repeated.
     */
    static String where(SourceStore.Hit hit) {
        String path = hit.path == null ? "" : hit.path;
        int slash = path.lastIndexOf('/');
        String folder = slash < 0 ? "" : path.substring(0, slash);
        if (SourceStore.PARTNER.equals(hit.kind)) {
            String first = folder, rest = "";
            int cut = folder.indexOf('/');
            if (cut >= 0) { first = folder.substring(0, cut); rest = folder.substring(cut); }
            if (FilenPartner.isScanFolder(first)) folder = FilenPartner.SCAN_FOLDER + rest;
            return "From " + PartnerSetup.lowerName(hit.partnerName) + (folder.isEmpty() ? "" : " \u00b7 " + folder);
        }
        String base = SourceStore.SHARED_BY_ME.equals(hit.kind) ? "Shared by you" : hit.sourceTitle;
        if (SourceStore.SHARED_BY_ME.equals(hit.kind)) return folder.isEmpty() ? base : base + " \u00b7 " + folder;
        String own = folder.isEmpty() ? base : base + "/" + folder;
        return hit.sharedByMe ? "Shared by you \u00b7 " + own : own;   // an own file Filen lists as shared (one row for both sources)
    }

    static final int SHARE_REQUEST = 7321;
    private SourceActions() {}

    /** The document URI of a file inside its source's tree. */
    static Uri documentUri(String treeUri, String docId) { return DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), docId); }

    static final String CANT_OPEN = "Can't open this file here";

    /**
     * The VIEW request for a file that another app opens (Ami Pro). The MIME type is never the partner's: it comes from the registry's type
     * (a partner can name anything), the request goes only to the type's package, and only when that package carries the pinned signing certificate.
     * Null when there is no such app.
     */
    static Intent viewIntent(Context context, Uri document, FileType type) {
        if (type == null || type.opener().isStackViewer()) return null;
        String pkg = type.opener().packageName;
        if (!LinkCallers.amiProInstalledAndPinned(context) || !AmiProPackage.equals(pkg)) return null;
        String mime = type.mimeTypes().isEmpty() ? "application/octet-stream" : type.mimeTypes().iterator().next();
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(document, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK).setPackage(pkg);
        view.setClipData(android.content.ClipData.newRawUri("", document));
        return view;
    }

    private static final String AmiProPackage = LinkCallers.AMIPRO_PACKAGE;

    /** Plain words for a file that could not be opened. `online` is whether the phone has a network. */
    static String openProblem(Exception error, boolean online) {
        if (error instanceof android.app.AuthenticationRequiredException) return "Sign in to Filen to open this file";
        if (error instanceof SecurityException) return "Stack no longer has access to this folder";
        return online ? "This file could not be opened" : "Needs the internet";
    }

    static boolean online(android.content.Context context) {
        try { ConnectivityManager cm = context.getSystemService(ConnectivityManager.class); return cm != null && cm.getActiveNetwork() != null; }
        catch (RuntimeException unknown) { return true; }
    }

    /**
     * Tap on a result: check the file can be read (this also asks Filen to download it), then open it. The wait is shown by {@link OpenWait}: after 5 s
     * "Waiting for Filen…" with Cancel, after 30 s "Needs the internet"; Cancel stops the attempt.
     */
    static void open(LibraryActivity activity, SourceStore.Hit hit) {
        activity.check(() -> {
            SourceStore.FileRow row = activity.app().library.sources.file(hit.id);
            return new Object[]{row == null || row.handlerId.isEmpty() ? null : FileTypes.byId(row.handlerId), documentUri(hit.treeUri, hit.docId)};
        }, prepared -> {
            final FileType type = (FileType) prepared[0];
            final Uri uri = (Uri) prepared[1];
            final android.app.AlertDialog[] dialog = {null};
            final OpenWait[] wait = {null};
            wait[0] = new OpenWait((text, cancelable) -> {
                if (OpenWait.OPENING.equals(text)) return;   // quick opens show nothing at all
                if (dialog[0] == null) {
                    dialog[0] = new android.app.AlertDialog.Builder(activity).setMessage(text).setNegativeButton("Cancel", (d, w) -> wait[0].cancel()).setOnCancelListener(d -> wait[0].cancel()).create();
                    dialog[0].show();
                } else dialog[0].setMessage(text);
                android.widget.Button button = dialog[0].getButton(android.app.AlertDialog.BUTTON_NEGATIVE);
                if (button != null) button.setText(OpenWait.NEEDS_INTERNET.equals(text) ? "OK" : "Cancel");
            });
            wait[0].start();
            new Thread(() -> {
                Object[] result;
                try (ParcelFileDescriptor probe = activity.getContentResolver().openFileDescriptor(uri, "r", wait[0].signal)) {
                    if (probe == null) throw new java.io.IOException("no answer");
                    result = decide(activity, type, uri, hit);
                } catch (Exception error) { result = new Object[]{null, openProblem(error, online(activity))}; }
                final Object[] answer = result;
                activity.runOnUiThread(() -> {
                    if (!wait[0].live() || activity.isFinishing() || activity.isDestroyed()) return;   // cancelled, or already told "Needs the internet"
                    wait[0].done();
                    if (dialog[0] != null) dialog[0].dismiss();
                    if (answer[0] == null) { Toast.makeText(activity, (String) answer[1], Toast.LENGTH_LONG).show(); return; }
                    try { activity.startActivity((Intent) answer[0]); }
                    catch (RuntimeException none) { Toast.makeText(activity, "No app can open this file", Toast.LENGTH_LONG).show(); }
                });
            }, "Open file").start();
        });
    }

    /** What to start for a file that can be read: Stack's own viewer, or Ami Pro; or the plain words of why not. */
    private static Object[] decide(LibraryActivity activity, FileType type, Uri uri, SourceStore.Hit hit) {
        // Stack-viewer types open in Stack's own read-only viewer (it copies the file to a private cache and deletes the copy on close).
        // Anything the registry does not know is never handed to another app, whatever MIME type the partner gave it.
        if (type == null) return new Object[]{null, CANT_OPEN};
        if (type.opener().isStackViewer()) return new Object[]{SourceViewerActivity.intent(activity, uri, null, hit.name, hit.sourceId), null};
        Intent view = viewIntent(activity, uri, type);
        return new Object[]{view, view == null ? "Ami Pro is needed to open this file" : null};
    }

    /** The three-dot menu of a result: Add to stack, Tags, and Share (own files only; the label comes from Filen's share_state). */
    static void menu(LibraryActivity activity, View anchor, SourceStore.Hit hit, Runnable refresh) {
        final String uuid = SourceStore.OWN.equals(hit.kind) ? hit.filenUuid() : null;
        activity.check(() -> {
            String partner = activity.app().library.sources.partnerName();
            int level = uuid == null ? FilenPartner.REQUIRED_LEVEL : FilenPartner.level(activity);   // F12 first: nothing about sharing is asked of an old Filen
            FilenPartner.Share share = uuid == null || !FilenPartner.supported(level) ? null : FilenPartner.api(activity).share(uuid);
            return new Object[]{uuid == null ? partner : FilenPartner.name(activity, partner), share, level};
        }, answer -> {
            String partner = (String) answer[0];
            FilenPartner.Share share = (FilenPartner.Share) answer[1];
            int level = (Integer) answer[2];
            PopupMenu menu = new PopupMenu(activity, anchor);
            menu.getMenu().add("Add to stack").setOnMenuItemClickListener(i -> { addToStack(activity, hit, refresh); return true; });
            menu.getMenu().add("Tags").setOnMenuItemClickListener(i -> { tags(activity, hit); return true; });
            if (uuid != null && !FilenPartner.supported(level)) {
                menu.getMenu().add(FilenPartner.gateText(level)).setOnMenuItemClickListener(i -> { FilenPartner.openGate(activity, level); return true; });
            } else if (uuid != null) {
                boolean stop = share != null && share.known && share.shared;
                String label = (stop ? "Stop sharing with " : "Share with ") + PartnerSetup.lowerName(partner);
                if (share != null && share.known && !share.shared && !share.via.isEmpty()) label = "Shared with " + PartnerSetup.lowerName(partner) + " in " + share.via;
                menu.getMenu().add(label).setEnabled(share == null || !share.known || share.shared || share.via.isEmpty())
                        .setOnMenuItemClickListener(i -> { share(activity, uuid, stop); return true; });
            }
            menu.show();
        });
    }

    static void share(android.app.Activity activity, String uuid, boolean stop) {
        Intent request = FilenPartner.shareRequest(uuid, stop);
        if (!FilenPartner.available(activity, request)) { Toast.makeText(activity, "Update Filen Files to share files", Toast.LENGTH_LONG).show(); return; }
        try { activity.startActivityForResult(request, SHARE_REQUEST); } catch (RuntimeException unavailable) { Toast.makeText(activity, "Filen Files could not be opened", Toast.LENGTH_LONG).show(); }
    }

    private static void addToStack(LibraryActivity activity, SourceStore.Hit hit, Runnable refresh) {
        activity.work(() -> new Object[]{activity.app().library.stacks(), activity.app().library.sources.stacksOf(SourceStore.ITEM_SOURCE, hit.id)}, data -> {
            @SuppressWarnings("unchecked") List<StackCategory> stacks = (List<StackCategory>) data[0];
            @SuppressWarnings("unchecked") List<String> current = (List<String>) data[1];
            StackPicker.show(activity, stacks, current, ids -> activity.work(() -> {
                SourceStore store = activity.app().library.sources;
                for (String id : current) if (!ids.contains(id)) store.removeFromStack(SourceStore.ITEM_SOURCE, hit.id, id);
                for (String id : ids) store.addToStack(SourceStore.ITEM_SOURCE, hit.id, id);
                return true;
            }, ok -> refresh.run()));
        });
    }

    // ---- tags ---------------------------------------------------------------------------------------------------------------------

    static List<String[]> allTags(SourceStore store) {
        List<String[]> out = new ArrayList<>();
        try (Cursor c = store.db().rawQuery("SELECT id,name FROM tags ORDER BY name COLLATE NOCASE", null)) { while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1)}); }
        return out;
    }

    /** The id of the tag with this name, made when it does not exist yet. */
    static String tagFor(SourceStore store, String raw) {
        String name = raw.replaceAll("[\\p{Cc}]", "").trim();
        if (name.isEmpty() || name.length() > 60) return null;
        try (Cursor c = store.db().rawQuery("SELECT id FROM tags WHERE name=?", new String[]{name})) { if (c.moveToFirst()) return c.getString(0); }
        String id = UUID.randomUUID().toString();
        store.db().execSQL("INSERT INTO tags(id,name) VALUES(?,?)", new Object[]{id, name});
        return id;
    }

    /**
     * Saves the Tags dialog: the ticked tags are applied, the unticked removed, and a new name typed in the box is made (when it does not exist yet)
     * and applied, all in one step. `typed` may be empty. Returns the id of the new or existing tag from `typed`, or null.
     */
    static String saveTags(SourceStore store, String hitId, List<String[]> tags, boolean[] on, List<String> mine, String typed) {
        store.db().beginTransaction();
        try {
            for (int i = 0; i < tags.size(); i++) {
                if (on[i] && !mine.contains(tags.get(i)[0])) store.addTag(SourceStore.ITEM_SOURCE, hitId, tags.get(i)[0]);
                if (!on[i] && mine.contains(tags.get(i)[0])) store.db().execSQL("DELETE FROM item_tags WHERE item_kind='source' AND item_id=? AND tag_id=?", new Object[]{hitId, tags.get(i)[0]});
            }
            String added = typed == null ? null : tagFor(store, typed);
            if (added != null) store.addTag(SourceStore.ITEM_SOURCE, hitId, added);
            store.db().setTransactionSuccessful();
            return added;
        } finally { store.db().endTransaction(); }
    }

    private static void tags(LibraryActivity activity, SourceStore.Hit hit) {
        activity.work(() -> {
            SourceStore store = activity.app().library.sources;
            return new Object[]{allTags(store), store.tagsOf(SourceStore.ITEM_SOURCE, hit.id)};
        }, data -> {
            @SuppressWarnings("unchecked") List<String[]> tags = (List<String[]>) data[0];
            @SuppressWarnings("unchecked") List<String> mine = (List<String>) data[1];
            boolean[] on = new boolean[tags.size()];
            // One view holds the existing tags and the box for a new one (a dialog cannot show a list and a custom view together).
            android.widget.LinearLayout box = Ui.column(activity); box.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 8), Ui.dp(activity, 20), 0);
            android.widget.LinearLayout list = Ui.column(activity);
            for (int i = 0; i < on.length; i++) {
                final int index = i; on[i] = mine.contains(tags.get(i)[0]);
                android.widget.CheckBox check = new android.widget.CheckBox(activity); check.setText(tags.get(i)[1]); check.setTextSize(16); check.setChecked(on[i]);
                check.setOnCheckedChangeListener((button, checked) -> on[index] = checked);
                list.addView(check);
            }
            if (on.length > 0) { android.widget.ScrollView scroll = new android.widget.ScrollView(activity); scroll.addView(list); box.addView(scroll, new android.widget.LinearLayout.LayoutParams(-1, -2)); }
            android.widget.EditText fresh = new android.widget.EditText(activity); fresh.setHint("New tag"); fresh.setSingleLine(true); box.addView(fresh);
            new AlertDialog.Builder(activity).setTitle("Tags").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
                final String typed = fresh.getText().toString();
                activity.work(() -> { saveTags(activity.app().library.sources, hit.id, tags, on, mine, typed); return true; }, ok -> {});
            }).show();
        });
    }
}
