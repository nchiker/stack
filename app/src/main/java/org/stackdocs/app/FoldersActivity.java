package org.stackdocs.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Folders screen (docs/FOLDER_SHARING_DESIGN.md section 1): the folders Stack reads, each with its state in plain words, Add a Filen folder,
 * a Share switch on the person's own folders (read from Filen, never remembered here) and Remove from Stack. The words and the switch rules are
 * plain functions ({@link #stateText}, {@link #shareNote}) so the checks can test every state.
 */
public final class FoldersActivity extends LibraryActivity {
    private static final int ADD = 7311, FIX = 7312, SHARE = 7313;

    private LinearLayout body;
    private final Map<String, FilenPartner.Share> shares = new HashMap<>();
    private String fixing, sharingUuid;
    private boolean sharingStop;
    private boolean pendingShareAfterSetup;
    private volatile String notice = "";
    private int generation;
    private int level = FilenPartner.REQUIRED_LEVEL;
    private boolean levelKnown;
    private List<Row> lastRows;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this), bar = Ui.toolbar(this);
        bar.addView(Ui.iconButton(this, Icons.Symbol.BACK, "Back", this::finish), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        bar.addView(Ui.text(this, "Folders", 18, false), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar);
        ScrollView scroll = new ScrollView(this);
        body = Ui.column(this);
        body.setPadding(Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.install(this, root);
    }

    private final Runnable sourcesChanged = this::refresh;
    @Override protected void onResume() { super.onResume(); app().sourceIndexer.addListener(sourcesChanged); refresh(); }
    @Override protected void onPause() { app().sourceIndexer.removeListener(sourcesChanged); handler.removeCallbacksAndMessages(null); super.onPause(); }
    @Override protected void onLibraryChanged() { refresh(); }

    // ---- words --------------------------------------------------------------------------------------------------------------------

    /** What a source is called on the screen. */
    static String title(SourceStore.Source source) {
        String partner = PartnerSetup.displayName(source.partnerName);
        switch (source.kind) {
            case SourceStore.PARTNER: return partner + "'s shared files";
            case SourceStore.SHARED_BY_ME: return "Shared by me";
            case SourceStore.SCAN_SHARE: return "Scans shared with " + partner;
            default: return source.title;
        }
    }

    /** The state in plain words, from the source's state and its progress. */
    static String stateText(SourceStore.Progress p) { return stateText(p, ""); }

    /** As above; the folder Stack saves scans into is written, not read, so it says "Saving scans". */
    static String stateText(SourceStore.Progress p, String kind) {
        if (SourceStore.SCAN_SHARE.equals(kind) && (SourceStore.SCANNING.equals(p.state) || SourceStore.NEW.equals(p.state))) return "Saving scans";
        switch (p.state) {
            case SourceStore.UNREACHABLE: return "Can't reach Filen";
            case SourceStore.SIGNED_OUT: return "Sign in to Filen";
            case SourceStore.ACCOUNT_CHANGED: return "Filen is signed in as someone else";
            case SourceStore.ACCESS_LOST: return "Access lost";
            case SourceStore.WAITING: return p.reason.startsWith("Locked") ? "Waiting: Stack is locked" : p.reason.startsWith("Waiting for the battery") ? "Waiting: low battery" : "Waiting";
            case SourceStore.SCANNING: case SourceStore.NEW: return p.files == 0 ? "Looking for files" : reading(p);
            default: return p.pending > 0 ? reading(p) : "Up to date";
        }
    }

    /** "Reading 3 of 57", or while a long PDF is read in chunks "Reading page 12 of 100". */
    private static String reading(SourceStore.Progress p) {
        if (p.pageTotal > 0) return "Reading page " + Math.min(p.pageDone + 1, p.pageTotal) + " of " + p.pageTotal;
        return "Reading " + p.done() + " of " + p.files;
    }

    /** The button a state offers, or null: "Use this account", "Fix". */
    static String actionText(String state) {
        return SourceStore.ACCOUNT_CHANGED.equals(state) ? "Use this account" : SourceStore.ACCESS_LOST.equals(state) ? "Fix" : null;
    }

    /**
     * What the Share row says for an own folder, from Filen's share_state: `[label, mode]` where mode is "switch" (a switch, `on` says which way),
     * "text" (no switch) or "unknown". `via` set means a parent folder is already shared.
     */
    static String shareNote(FilenPartner.Share share, String partnerName) {
        String name = PartnerSetup.lowerName(partnerName);
        if (share == null || !share.known) return "Can't check sharing right now";
        if (!share.shared && !share.via.isEmpty()) return "Shared with " + name + " in " + share.via;
        return "Share with " + name;
    }

    /** True when the folder can have a Share switch: an own folder that is not the whole Drive (no Filen UUID) and not already shared through a parent. */
    static boolean hasSwitch(SourceStore.Source source, FilenPartner.Share share) {
        return SourceStore.OWN.equals(source.kind) && SourceStore.uuidOf(source.rootDocId()) != null && share != null && share.known && (share.shared || share.via.isEmpty());
    }

    // ---- screen -------------------------------------------------------------------------------------------------------------------

    private static final class Row { SourceStore.Source source; SourceStore.Progress progress; boolean removalPending; }

    private void refresh() {
        if (body == null) return;
        final int mine = ++generation;
        read(() -> {
            String oldArchive = QrRetirement.takeNote(this);   // shown once
            if (!oldArchive.isEmpty()) notice = oldArchive;
            List<Row> rows = new ArrayList<>();
            for (SourceStore.Source source : app().library.sources.list()) {
                Row row = new Row(); row.source = source; row.progress = app().library.sources.progress(source.id);
                row.removalPending = SourceStore.SCAN_SHARE.equals(source.kind) && app().scanShare.removalPending();
                if (row.progress != null) rows.add(row);
            }
            return rows;
        }, rows -> {
            if (mine != generation) return;
            show(rows);
            askShareStates(rows, mine);
        });
    }

    /** Asks Filen about each own folder, off the main thread; the answers redraw the screen. */
    private void askShareStates(List<Row> rows, int mine) {
        final List<String> uuids = new ArrayList<>();
        for (Row row : rows) { String uuid = SourceStore.OWN.equals(row.source.kind) ? SourceStore.uuidOf(row.source.rootDocId()) : null; if (uuid != null) uuids.add(uuid); }
        if (uuids.isEmpty()) return;
        check(() -> {
            // F12 first: with a Filen that cannot share, nothing is asked and the share row says so.
            Object[] answer = {FilenPartner.level(this), new HashMap<String, FilenPartner.Share>()};
            if (!FilenPartner.supported((Integer) answer[0])) return answer;
            @SuppressWarnings("unchecked") Map<String, FilenPartner.Share> found = (Map<String, FilenPartner.Share>) answer[1];
            FilenPartner.Api api = FilenPartner.api(this);
            for (String uuid : uuids) found.put(uuid, api.share(uuid));
            return answer;
        }, answer -> {
            if (mine != generation) return;
            @SuppressWarnings("unchecked") Map<String, FilenPartner.Share> found = (Map<String, FilenPartner.Share>) answer[1];
            level = (Integer) answer[0]; levelKnown = true;
            shares.clear(); shares.putAll(found); show(rows);
        });
    }

    /** Retry delays for share_state after a share or stop action: Filen can lag behind its own answer. */
    static volatile long[] RETRY_MS = {1000, 3000, 8000};

    /** True while the answer is not yet what the action should have made it (unknown, or still the old state). */
    static boolean again(FilenPartner.Share answer, boolean expectShared) { return answer == null || !answer.known || answer.shared != expectShared; }

    /** A share or stop action finished: read share_state again now, then after 1 s, 3 s and 8 s, until it is known and matches. */
    void afterShare(String uuid, boolean expectShared) {
        shares.remove(uuid);
        refresh();
        retryShare(uuid, expectShared, 0);
    }

    private void retryShare(String uuid, boolean expectShared, int attempt) {
        long[] delays = RETRY_MS;
        if (attempt >= delays.length) return;
        handler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            check(() -> FilenPartner.supported(FilenPartner.level(this)) ? FilenPartner.api(this).share(uuid) : null, answer -> {
                if (answer == null) return;
                shares.put(uuid, answer);
                if (lastRows != null) show(lastRows);
                if (again(answer, expectShared)) retryShare(uuid, expectShared, attempt + 1);
            });
        }, delays[attempt]);
    }

    private void show(List<Row> rows) {
        lastRows = rows;
        body.removeAllViews();
        String partner = null;
        for (Row row : rows) if (SourceStore.PARTNER.equals(row.source.kind)) partner = row.source.partnerName;
        if (partner == null) for (Row row : rows) if (!row.source.partnerName.isEmpty()) partner = row.source.partnerName;
        body.addView(Ui.primary(this, "Add a Filen folder", this::addFolder));
        if (!notice.isEmpty()) { TextView note = Ui.text(this, notice, 14, false); note.setTextColor(0xFFB3261E); note.setPadding(0, Ui.dp(this, 12), 0, 0); body.addView(note); }
        if (rows.isEmpty()) {
            TextView empty = Ui.text(this, "No folders yet. Add a Filen folder to find its files in search.", 15, false);
            empty.setTextColor(Ui.MUTED); empty.setPadding(0, Ui.dp(this, 16), 0, 0); body.addView(empty);
        }
        for (Row row : rows) body.addView(card(row, partner));
    }

    private View card(Row row, String partner) {
        SourceStore.Source source = row.source;
        LinearLayout card = Ui.column(this);
        card.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 8));
        card.setTag(source.id);
        card.addView(Ui.divider(this), new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        TextView name = Ui.text(this, title(source), 17, true);
        name.setPadding(0, Ui.dp(this, 12), 0, 0);
        card.addView(name);
        TextView state = Ui.text(this, stateText(row.progress, source.kind), 14, false);
        state.setTextColor(SourceStore.OK.equals(source.state) ? Ui.MUTED : Ui.INK); state.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(state);
        if (SourceStore.SCAN_SHARE.equals(source.kind) && !row.progress.reason.isEmpty() && !SourceStore.OK.equals(source.state) && !SourceStore.SCANNING.equals(source.state)) {
            TextView why = Ui.text(this, row.progress.reason, 13, false); why.setTextColor(Ui.MUTED); why.setPadding(0, Ui.dp(this, 2), 0, 0); card.addView(why);
        }
        if (row.removalPending) {
            TextView pending = Ui.text(this, ScanShare.REMOVAL_PENDING, 13, false); pending.setTextColor(Ui.MUTED); pending.setPadding(0, Ui.dp(this, 2), 0, 0); pending.setTag("removal"); card.addView(pending);
        }
        String action = actionText(source.state);
        if (action != null) card.addView(Ui.button(this, action, () -> { if (SourceStore.ACCOUNT_CHANGED.equals(source.state)) useAccount(source); else fix(source); }));
        if (SourceStore.OWN.equals(source.kind)) {
            String uuid = SourceStore.uuidOf(source.rootDocId());
            if (uuid != null) {
                FilenPartner.Share share = shares.get(uuid);
                String note = shareNote(share, partner);
                if (levelKnown && !FilenPartner.supported(level)) {
                    TextView need = Ui.text(this, FilenPartner.gateText(level), 14, false); need.setTextColor(Ui.MUTED); need.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4)); need.setTag("share");
                    card.addView(need);
                    card.addView(Ui.button(this, FilenPartner.gateButton(level), () -> FilenPartner.openGate(this, level)));
                } else if (share == null) {
                    // Filen has not answered yet: no row, rather than a wrong one.
                } else if (hasSwitch(source, share)) {
                    Switch toggle = new Switch(this);
                    toggle.setText(note); toggle.setTextSize(16); toggle.setTextColor(Ui.INK); toggle.setChecked(share.shared);
                    toggle.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
                    toggle.setOnClickListener(v -> { boolean wantOn = toggle.isChecked(); toggle.setChecked(!wantOn); toggleShare(uuid, wantOn, partner); });
                    toggle.setTag("share");
                    card.addView(toggle, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
                } else {
                    TextView text = Ui.text(this, note, 14, false); text.setTextColor(Ui.MUTED); text.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4)); text.setTag("share");
                    card.addView(text);
                }
            }
            card.addView(Ui.button(this, "Remove from Stack", () -> confirmRemove(source)));
        }
        return card;
    }

    // ---- actions ------------------------------------------------------------------------------------------------------------------

    private void addFolder() {
        notice = "";
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI, FilenPartner.driveRoot())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(pick, ADD); } catch (RuntimeException unavailable) { notice = "Android could not open the folder picker."; refresh(); }
    }

    private void fix(SourceStore.Source source) {
        if (SourceStore.SCAN_SHARE.equals(source.kind)) {
            // The folder is made for the partner there is NOW (after a change the source still carries the old name).
            work(() -> { String now = FilenPartner.freshName(this); return now.isEmpty() ? FilenPartner.name(this, app().library.sources.partnerName()) : now; }, name -> ScanShare.startCreate(this, name.isEmpty() ? null : name, problem -> { notice = problem; refresh(); }));
            return;
        }
        fixing = source.id;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI, source.tree())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(pick, FIX); } catch (RuntimeException unavailable) { notice = "Android could not open the folder picker."; refresh(); }
    }

    private void useAccount(SourceStore.Source source) {
        work(() -> {
            app().library.sources.useCurrentAccount(source.id);
            if (SourceStore.SCAN_SHARE.equals(source.kind)) app().scanShare.start(); else app().sourceIndexer.kickSource(source.id);
            return true;
        }, ok -> refresh());
    }

    private void confirmRemove(SourceStore.Source source) {
        new AlertDialog.Builder(this).setTitle("Remove from Stack?")
                .setMessage("Stack stops showing the files of " + title(source) + ". Nothing is deleted in Filen.")
                .setPositiveButton("Remove", (d, w) -> work(() -> { app().library.sources.remove(source.id); return true; }, ok -> refresh()))
                .setNegativeButton("Cancel", null).show();
    }

    private void toggleShare(String uuid, boolean on, String partner) {
        sharingUuid = uuid; sharingStop = !on;
        if (partner == null) {
            // No partner yet: Filen's partner setup first, then the confirm sheet.
            String[] problem = {""};
            pendingShareAfterSetup = true;
            if (!PartnerSetup.start(this, problem)) { pendingShareAfterSetup = false; notice = problem[0]; refresh(); }
            return;
        }
        startShare();
    }

    private void startShare() {
        Intent request = FilenPartner.shareRequest(sharingUuid, sharingStop);
        if (!FilenPartner.available(this, request)) { notice = "Update Filen Files to share folders."; refresh(); return; }
        try { startActivityForResult(request, SHARE); } catch (RuntimeException unavailable) { notice = "Filen Files could not be opened."; refresh(); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == ScanShare.REQUEST) { ScanShare.afterCreate(this, result, data, problem -> { notice = problem; refresh(); }); return; }
        if (request == PartnerSetup.REQUEST) {
            boolean share = pendingShareAfterSetup; pendingShareAfterSetup = false;
            PartnerSetup.finish(this, result, data, problem -> { notice = problem; if (share && result == RESULT_OK && problem.isEmpty()) startShare(); else refresh(); });
        } else if (request == SHARE) {
            // The switch shows what Filen says now, never what was asked; Filen may lag, so it is read again a few times.
            String stopped = FilenPartner.stoppedViaFolder(result, data);
            if (!stopped.isEmpty()) notice = stopped;
            afterShare(sharingUuid, !sharingStop);
        } else if ((request == ADD || request == FIX) && result == RESULT_OK && data != null && data.getData() != null) {
            final Uri tree = data.getData();
            try { getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (RuntimeException refused) { /* reported by the store */ }
            final String id = request == FIX ? fixing : null;
            work(() -> {
                String problem = id != null ? null : app().library.sources.pickProblem(tree);
                if (problem != null) { app().library.sources.releaseUnlessUsed(tree); return problem; }
                if (id != null) app().library.sources.replaceTree(id, tree);
                else app().library.sources.add(SourceStore.OWN, tree, folderName(tree), "");
                app().sourceIndexer.kickAll(true);
                return "";
            }, message -> { notice = message; refresh(); }, () -> { notice = "That folder could not be added."; });
        }
    }

    private String folderName(Uri tree) {
        try (android.database.Cursor c = getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0) && !c.getString(0).trim().isEmpty()) return c.getString(0).trim();
        } catch (RuntimeException ignored) { /* the name is only for display */ }
        return "Filen folder";
    }
}
