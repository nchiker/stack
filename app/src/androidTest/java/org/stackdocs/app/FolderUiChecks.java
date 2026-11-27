package org.stackdocs.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Folder sharing screens and jobs (-e folderUi true), synthetic data only. Parts: the words for every source state, the Folders screen for each
 * state driven by the scripted reader, the Share switch reading Filen's share_state, the partner card and Settings row rules, the scan-share
 * writer against a scripted folder, and the retirement of the QR sharing on a version 18 library. Filen's own answers are scripted through
 * FilenPartner.api; the real Filen screens are checked on the phone.
 */
final class FolderUiChecks {
    private FolderUiChecks() {}

    static void require(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    /** Filen's two questions, scripted. */
    static final class Script implements FilenPartner.Api {
        FilenPartner.Status status; final Map<String, FilenPartner.Share> shares = new java.util.concurrent.ConcurrentHashMap<>();
        volatile int level = 1; volatile int failFirst; volatile long statusDelayMs; final java.util.concurrent.atomic.AtomicInteger contractCalls = new java.util.concurrent.atomic.AtomicInteger();
        @Override public int contract() { return contractCalls.incrementAndGet() <= failFirst ? FilenPartner.UNKNOWN : level; }
        @Override public FilenPartner.Status status() { if (statusDelayMs > 0) android.os.SystemClock.sleep(statusDelayMs); return status; }
        @Override public FilenPartner.Share share(String uuid) { FilenPartner.Share s = shares.get(uuid); return s == null ? FilenPartner.Share.UNKNOWN : s; }
    }

    static void run(Instrumentation i, Context target, Consumer<String> mark) throws Exception {
        require(target.getPackageName().startsWith("org.stackdocs.app.checks"), "Disposable test package only");
        StackApplication app = (StackApplication) target.getApplicationContext();
        P1BatchChecks.await(() -> app.access != null && app.library != null && app.sourceIndexer != null, "Application startup");
        target.getSharedPreferences("local_policy", Context.MODE_PRIVATE).edit().putBoolean("app_lock", false).commit();
        P1UiChecks.configuration = new Configuration(target.getResources().getConfiguration());
        P1UiChecks.configuration.screenWidthDp = 360;
        try {
            words(mark);
            clear(app);
            // The app's own scan runs at most once in two minutes unless forced: take that turn now, so it cannot touch the scripted sources below.
            app.sourceIndexer.kickAll(true);
            P1BatchChecks.await(() -> !app.sourceIndexer.busy(), "App scan finished");
            folderStates(i, app, mark);
            shareSwitch(i, app, mark);
            nameWithoutPartnerSource(app, mark);
            partnerRules(i, app, mark);
            contractGate(i, app, mark);
            partnerRowAfterAllow(i, app, mark);
            shareRetry(i, app, mark);
            openWait(app, mark);
            tagsSave(app, mark);
            whereAndLive(i, app, mark);
            stackMembers(i, app, mark);
            sharedTwice(i, app, mark);
        } finally { FilenPartner.api = null; FilenPartner.forgetName(); clear(app); P1UiChecks.configuration = null; }
        scanShare(new ScannerChecks.IsolatedContext(target), mark);
        scanShareSafety(target, mark);
        releaseRules(target, mark);
        qrRetirement(new ScannerChecks.IsolatedContext(target), mark);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------------

    static void clear(StackApplication app) {
        SQLiteDatabase db = app.library.sources.db();
        db.execSQL("DELETE FROM source_search WHERE file_id IN (SELECT id FROM source_files)");
        db.execSQL("DELETE FROM source_files");
        db.execSQL("DELETE FROM sources");
    }

    static String tree(String docId) { return DocumentsContract.buildTreeDocumentUri(SourceStore.AUTHORITY, docId).toString(); }

    /** A source row without a real grant (the real grant needs Filen's picker). */
    static SourceStore.Source seed(SourceStore store, String kind, String docId, String title, String partner) {
        String id = UUID.randomUUID().toString();
        ContentValues v = new ContentValues();
        v.put("id", id); v.put("kind", kind); v.put("tree_uri", tree(docId)); v.put("title", title); v.put("partner_name", partner); v.put("state", "new"); v.put("added_at", Clock.now() + store.list().size());
        store.db().insertOrThrow("sources", null, v);
        return store.get(id);
    }

    static void addRows(SourceStore store, String sourceId, int indexed, int pending) {
        for (int n = 0; n < indexed + pending; n++) {
            ContentValues v = new ContentValues();
            v.put("id", UUID.randomUUID().toString()); v.put("source_id", sourceId); v.put("provider_doc_id", "filen:" + UUID.randomUUID()); v.put("parent_doc_id", "p"); v.put("name", "f" + n + ".txt");
            v.put("state", n < indexed ? "indexed" : "pending");
            store.db().insertOrThrow("source_files", null, v);
        }
    }

    static List<String> texts(View view) {
        List<String> out = new ArrayList<>();
        if (view instanceof TextView && ((TextView) view).getText().length() > 0) out.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) for (int n = 0; n < ((ViewGroup) view).getChildCount(); n++) out.addAll(texts(((ViewGroup) view).getChildAt(n)));
        return out;
    }

    static View byTag(View view, Object tag) {
        if (tag.equals(view.getTag())) return view;
        if (view instanceof ViewGroup) for (int n = 0; n < ((ViewGroup) view).getChildCount(); n++) { View found = byTag(((ViewGroup) view).getChildAt(n), tag); if (found != null) return found; }
        return null;
    }

    static boolean has(List<String> texts, String wanted) { return texts.contains(wanted); }

    static Activity open(Instrumentation i, Class<?> type) {
        Activity a = P1UiChecks.open(i, new Intent(i.getTargetContext(), type));
        P1UiChecks.settle(i);
        return a;
    }

    static SourceStore.Progress progress(String state, String reason, int files, int pending) {
        return new SourceStore.Progress(state, reason, files, files - pending, pending, 0, 0, 0, 0, 0);
    }

    // ---- 1. the words -------------------------------------------------------------------------------------------------------------

    private static void words(Consumer<String> mark) {
        require(FoldersActivity.stateText(progress("ok", "", 57, 0)).equals("Up to date"), "ok reads Up to date");
        require(FoldersActivity.stateText(progress("scanning", "", 57, 45)).equals("Reading 12 of 57"), "scanning reads Reading 12 of 57");
        require(FoldersActivity.stateText(progress("ok", "", 57, 45)).equals("Reading 12 of 57"), "files still pending read as Reading, even when the scan is done");
        require(FoldersActivity.stateText(progress("new", "", 0, 0)).equals("Looking for files"), "a new source is looking");
        require(FoldersActivity.stateText(progress("waiting", "Locked. Reading continues when you unlock Stack.", 3, 1)).equals("Waiting: Stack is locked"), "waiting, locked");
        require(FoldersActivity.stateText(progress("waiting", "Waiting for the battery to charge.", 3, 1)).equals("Waiting: low battery"), "waiting, battery");
        require(FoldersActivity.stateText(progress("unreachable", "x", 3, 0)).equals("Can't reach Filen"), "unreachable");
        require(FoldersActivity.stateText(progress("signed_out", "x", 3, 0)).equals("Sign in to Filen"), "signed out");
        require(FoldersActivity.stateText(progress("account_changed", "x", 3, 0)).equals("Filen is signed in as someone else"), "account changed");
        require(FoldersActivity.stateText(progress("access_lost", "x", 3, 0)).equals("Access lost"), "access lost");
        require(FoldersActivity.stateText(progress("scanning", "", 0, 0), "scan_share").equals("Saving scans"), "scan share says Saving scans");
        require("Use this account".equals(FoldersActivity.actionText("account_changed")) && "Fix".equals(FoldersActivity.actionText("access_lost")) && FoldersActivity.actionText("ok") == null, "actions by state");
        require(PartnerSetup.settingsRow(null, null)[1].equals("Share with your partner"), "no partner: the quiet entry");
        require(PartnerSetup.settingsRow("Sam", null)[1].equals("Sharing with Sam"), "partner source: Sharing with Sam");
        require(PartnerSetup.settingsRow(null, new FilenPartner.Status("incoming", "Sam"))[1].equals("Sam wants to share files with you"), "incoming in Settings");
        require(ScanShare.fileName("Rent: May/June?", "12345678-aaaa-bbbb-cccc-dddddddddddd").equals("Rent May June [12345678].pdf"), "file name is readable, safe and carries the id");
        mark.accept("Folder words: every source state, Reading n of m, the two action buttons, scan-share wording, partner row wording, file names");
    }

    // ---- 2. the Folders screen for each state, scripted reader --------------------------------------------------------------------------

    private static void folderStates(Instrumentation i, StackApplication app, Consumer<String> mark) throws Exception {
        SourceStore store = app.library.sources;
        String[] states = {"ok", "unreachable", "signed_out", "account_changed", "access_lost", "waiting_locked", "waiting_battery", "reading"};
        Map<String, SourceStore.Source> sources = new TreeMap<>();
        Map<String, SourceChecks.Fake> fakes = new HashMap<>();
        for (String name : states) {
            String doc = "filen:" + UUID.randomUUID();
            SourceStore.Source s = seed(store, SourceStore.OWN, doc, "Folder " + name, "");
            SourceChecks.Fake fake = new SourceChecks.Fake();
            fake.file(doc, "a.txt", "alpha words"); fake.file(doc, "b.txt", "beta words"); fake.file(doc, "c.txt", "gamma words");
            sources.put(name, s); fakes.put(s.id, fake);
        }
        sources.put("partner", seed(store, SourceStore.PARTNER, "filen-in:root", "x", "Sam"));
        sources.put("mine", seed(store, SourceStore.SHARED_BY_ME, "filen-out:root", "x", "Sam"));
        sources.put("scans", seed(store, SourceStore.SCAN_SHARE, "filen:" + UUID.randomUUID(), "Shared scans", "Sam"));
        for (String name : new String[]{"partner", "mine"}) fakes.put(sources.get(name).id, new SourceChecks.Fake());
        SourceChecks.Env env = new SourceChecks.Env();
        SourceIndexer indexer = new SourceIndexer(i.getTargetContext(), store, src -> fakes.get(src.id), env);
        for (String name : new String[]{"ok", "unreachable", "signed_out", "account_changed", "access_lost", "partner", "mine"}) {
            SourceStore.Source s = sources.get(name);
            SourceChecks.Fake f = fakes.get(s.id);
            if ("unreachable".equals(name)) f.defaultStatus = SourceReader.Status.ERROR;
            if ("signed_out".equals(name)) f.defaultStatus = SourceReader.Status.SIGNED_OUT;
            if ("access_lost".equals(name)) f.granted = false;
            if ("account_changed".equals(name)) { indexer.scan(store.get(s.id), new SourceIndexer.Control()); f.account = "acct-other"; }
            indexer.scan(store.get(s.id), new SourceIndexer.Control());
        }
        env.allowed = false; indexer.scan(store.get(sources.get("waiting_locked").id), new SourceIndexer.Control());
        env.allowed = true; env.battery = false; indexer.scan(store.get(sources.get("waiting_battery").id), new SourceIndexer.Control());
        env.battery = true;
        SourceStore.Source reading = sources.get("reading");
        store.setState(reading.id, "ok", ""); addRows(store, reading.id, 12, 45);
        store.setState(sources.get("scans").id, "ok", "");
        String[] expect = {"Up to date", "Can't reach Filen", "Sign in to Filen", "Filen is signed in as someone else", "Access lost", "Waiting: Stack is locked", "Waiting: low battery", "Reading 12 of 57"};
        for (int n = 0; n < states.length; n++) require(store.get(sources.get(states[n]).id).state.length() > 0 && FoldersActivity.stateText(store.progress(sources.get(states[n]).id)).equals(expect[n]),
                states[n] + " source says '" + expect[n] + "' but says '" + FoldersActivity.stateText(store.progress(sources.get(states[n]).id)) + "'");
        Script script = new Script();
        FilenPartner.api = script;
        Activity screen = open(i, FoldersActivity.class);
        try {
            View root = screen.getWindow().getDecorView();
            for (int n = 0; n < states.length; n++) {
                List<String> t = texts(byTag(root, sources.get(states[n]).id));
                require(has(t, "Folder " + states[n]) && has(t, expect[n]), states[n] + " card shows its name and '" + expect[n] + "': " + t);
                require(has(t, "Remove from Stack"), states[n] + " card offers Remove from Stack");
                require(has(t, "Use this account") == "account_changed".equals(states[n]), states[n] + ": Use this account only for account_changed");
                require(has(t, "Fix") == "access_lost".equals(states[n]), states[n] + ": Fix only for access_lost");
            }
            require(has(texts(byTag(root, sources.get("partner").id)), "Sam's shared files"), "partner source is called Sam's shared files");
            require(has(texts(byTag(root, sources.get("mine").id)), "Shared by me"), "Shared by me");
            require(has(texts(byTag(root, sources.get("scans").id)), "Scans shared with Sam"), "Scans shared with Sam");
            require(P1UiChecks.findButton(root, "Add a Filen folder") != null, "Add a Filen folder is offered");
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI, FilenPartner.driveRoot());
            Uri start = pick.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI);
            require(start.getAuthority().equals("app.filenfiles.documents") && DocumentsContract.getDocumentId(start).equals("filen:root"), "the picker starts at the Filen Drive root");
            // Use this account: the store forgets the account and starts the source over.
            SourceStore.Source changed = sources.get("account_changed");
            require(!store.get(changed.id).accountId.isEmpty(), "the source was read under an account");
            i.runOnMainSync(() -> P1UiChecks.findButton(root, "Use this account").performClick());
            P1BatchChecks.await(() -> store.get(changed.id).accountId.isEmpty() || !SourceStore.ACCOUNT_CHANGED.equals(store.get(changed.id).state), "Use this account resets the stored account");
        } finally { i.runOnMainSync(screen::finish); }
        mark.accept("Folders screen: each state (up to date, reading 12 of 57, locked, battery, can't reach, signed out, other account with Use this account, access lost with Fix) from the scripted reader; names of partner, shared-by-me and scan sources; Add a Filen folder starts at filen:root");
    }

    // ---- 3. the Share switch reads share_state -------------------------------------------------------------------------------------

    private static void shareSwitch(Instrumentation i, StackApplication app, Consumer<String> mark) throws Exception {
        SourceStore store = app.library.sources;
        clear(app);
        String uuid = UUID.randomUUID().toString();
        SourceStore.Source own = seed(store, SourceStore.OWN, "filen:" + uuid, "Bills", "");
        seed(store, SourceStore.PARTNER, "filen-in:root", "x", "Sam");
        Script script = new Script();
        FilenPartner.api = script;
        Object[][] cases = {
                {new FilenPartner.Share(true, true, ""), true, "Share with Sam"},
                {new FilenPartner.Share(true, false, ""), false, "Share with Sam"},
                {new FilenPartner.Share(true, false, "Taxes"), null, "Shared with Sam in Taxes"},
                {FilenPartner.Share.UNKNOWN, null, "Can't check sharing right now"},
        };
        for (Object[] c : cases) {
            script.shares.put(uuid, (FilenPartner.Share) c[0]);
            Activity screen = open(i, FoldersActivity.class);
            try {
                P1UiChecks.settle(i);
                View root = screen.getWindow().getDecorView();
                View row = byTag(byTag(root, own.id), "share");
                require(row != null, "the own folder has a share row");
                require(((TextView) row).getText().toString().equals(c[2]), "share row reads '" + c[2] + "' but reads '" + ((TextView) row).getText() + "'");
                if (c[1] == null) require(!(row instanceof Switch), "no switch for: " + c[2]);
                else require(row instanceof Switch && ((Switch) row).isChecked() == (Boolean) c[1], "the switch shows Filen's answer " + c[1]);
            } finally { i.runOnMainSync(screen::finish); }
        }
        Activity foreground = open(i, FoldersActivity.class);
        require(app.foregroundScanning() && StackApplication.FOREGROUND_SCAN_MS == 5 * 60_000L, "while a screen is in front the sources are rescanned every 5 minutes");
        i.runOnMainSync(foreground::finish); P1UiChecks.settle(i);
        require(!app.foregroundScanning(), "the periodic rescan stops when Stack leaves the front");
        require(own.rootDocId().equals("filen:" + uuid) && SourceStore.uuidOf(own.rootDocId()).equals(uuid), "the folder's Filen uuid is the tree document id");
        Intent share = FilenPartner.shareRequest(uuid, true);
        require(FilenPartner.ACTION_SHARE.equals(share.getAction()) && uuid.equals(share.getStringExtra(FilenPartner.EXTRA_UUID)) && share.getBooleanExtra(FilenPartner.EXTRA_STOP, false), "F7 request carries the uuid and STOP");
        Intent create = FilenPartner.createFolderRequest(FilenPartner.scanFolderName("Sam"));
        require("Shared scans".equals(create.getStringExtra(FilenPartner.EXTRA_NAME)) && FilenPartner.ACTION_CREATE_FOLDER.equals(create.getAction()), "F11 request names the folder");
        mark.accept("Share switch: on, off, shared through a parent folder (text only), unknown; each read from share_state; F7 and F11 requests built as specified");
    }

    /** No partner source yet, but Filen says connected with a name: the labels use that name; no name means "your partner". */
    private static void nameWithoutPartnerSource(StackApplication app, Consumer<String> mark) {
        Script script = new Script();
        FilenPartner.api = script;
        FilenPartner.forgetName();
        script.status = new FilenPartner.Status(FilenPartner.NONE, "");
        require(FilenPartner.name(app, "").isEmpty() && PartnerSetup.lowerName(FilenPartner.name(app, "")).equals("your partner"), "no name known: your partner");
        script.status = new FilenPartner.Status(FilenPartner.CONNECTED, "Sam");
        require(FilenPartner.name(app, null).equals("Sam"), "connected Sam: the name from partner_status");
        script.status = null;
        require(FilenPartner.name(app, "").equals("Sam") && FilenPartner.name(app, "Ann").equals("Ann"), "the name is kept; a partner source's own name wins");
        require(ScanShare.confirmText(2, FilenPartner.name(app, "")).startsWith("Share 2 scans with Sam?"), "the confirm words use the name");
        FilenPartner.forgetName();
        mark.accept("Partner name without a partner source: partner_status name used, kept, \"your partner\" only when there is none");
    }

    // ---- 4. the partner card and Settings row ------------------------------------------------------------------------------------------

    private static void partnerRules(Instrumentation i, StackApplication app, Consumer<String> mark) throws Exception {
        SourceStore store = app.library.sources;
        clear(app);
        FilenPartner.Status incoming = new FilenPartner.Status("incoming", "Sam");
        require("Sam wants to share files with you.".equals(PartnerSetup.cardText(false, incoming)), "card text for incoming");
        require(PartnerSetup.cardText(true, incoming) == null, "no card when a partner source exists");
        require("See files Sam shares with you".equals(PartnerSetup.cardText(false, new FilenPartner.Status("connected", "Sam"))), "card text for connected");
        require(PartnerSetup.cardText(true, new FilenPartner.Status("connected", "Sam")) == null, "no card for connected when a partner source exists");
        require(PartnerSetup.cardHidden(1000, 999) && !PartnerSetup.cardHidden(1000, 1000), "Not now hides until the time passes");
        for (String state : new String[]{"none", "requested"}) require(PartnerSetup.cardText(false, new FilenPartner.Status(state, "Sam")) == null, "no card for " + state);
        require(PartnerSetup.cardText(false, null) == null, "no card when Filen does not answer");
        Script script = new Script();
        FilenPartner.api = script;
        app.getSharedPreferences("partner_card", Context.MODE_PRIVATE).edit().clear().commit();
        Object[][] cases = {{"incoming", false, true}, {"incoming", true, false}, {"connected", false, true}, {"connected", true, false}, {"none", false, false}, {"requested", false, false}, {null, false, false}};
        for (Object[] c : cases) {
            clear(app);
            if ((Boolean) c[1]) seed(store, SourceStore.PARTNER, "filen-in:root", "x", "Sam");
            script.status = c[0] == null ? null : new FilenPartner.Status((String) c[0], "Sam");
            Activity main = open(i, MainActivity.class);
            try {
                P1UiChecks.settle(i); P1UiChecks.settle(i);
                TextView card = P1UiChecks.findText(main.getWindow().getDecorView(), "connected".equals(c[0]) ? "See files Sam shares with you" : "Sam wants to share files with you.");
                boolean visible = card != null && card.isShown();
                require(visible == (Boolean) c[2], "card for " + c[0] + (((Boolean) c[1]) ? " with a partner source" : "") + " visible=" + visible);
                if (visible) require(P1UiChecks.findButton(main.getWindow().getDecorView(), "Show them here") != null, "the card has Show them here");
            } finally { i.runOnMainSync(main::finish); }
        }
        // Not now: hides the card for 30 days.
        clear(app);
        script.status = new FilenPartner.Status("connected", "Sam");
        final Activity hide = open(i, MainActivity.class);
        try {
            P1UiChecks.settle(i); P1UiChecks.settle(i);
            require(P1UiChecks.findText(hide.getWindow().getDecorView(), "See files Sam shares with you").isShown(), "card shown before Not now");
            i.runOnMainSync(() -> P1UiChecks.findButton(hide.getWindow().getDecorView(), "Not now").performClick());
            P1UiChecks.settle(i);
            require(!P1UiChecks.findText(hide.getWindow().getDecorView(), "See files Sam shares with you").isShown(), "card hidden after Not now");
        } finally { i.runOnMainSync(hide::finish); }
        long until = app.getSharedPreferences("partner_card", Context.MODE_PRIVATE).getLong("hidden_until", 0);
        require(until > Clock.now() + 29L * 24 * 3600_000 && until < Clock.now() + 31L * 24 * 3600_000, "hidden for about 30 days");
        final Activity again2 = open(i, MainActivity.class);
        try { P1UiChecks.settle(i); P1UiChecks.settle(i); TextView again = P1UiChecks.findText(again2.getWindow().getDecorView(), "See files Sam shares with you"); require(again == null || !again.isShown(), "card stays hidden on the next visit"); }
        finally { i.runOnMainSync(again2::finish); }
        app.getSharedPreferences("partner_card", Context.MODE_PRIVATE).edit().clear().commit();
        clear(app);
        script.status = new FilenPartner.Status("none", "");
        Activity settings = open(i, SettingsActivity.class);
        try { P1UiChecks.settle(i); View root = settings.getWindow().getDecorView(); require(has(texts(root), "Share with your partner") && has(texts(root), "Folders"), "Settings: quiet Share with your partner and the Folders row"); }
        finally { i.runOnMainSync(settings::finish); }
        seed(store, SourceStore.PARTNER, "filen-in:root", "x", "Sam");
        settings = open(i, SettingsActivity.class);
        try { P1UiChecks.settle(i); require(has(texts(settings.getWindow().getDecorView()), "Sharing with Sam"), "Settings: Sharing with Sam"); }
        finally { final Activity s = settings; i.runOnMainSync(s::finish); }
        mark.accept("Partner card: shown only for incoming with no partner source; hidden for partner source, none, requested and no answer; Settings row: Share with your partner / Sharing with Sam");
    }

    // ---- F12: contract level gates every partner and sharing screen ------------------------------------------------------------------

    private static void contractGate(Instrumentation i, StackApplication app, Consumer<String> mark) throws Exception {
        SourceStore store = app.library.sources;
        Script script = new Script();
        FilenPartner.api = script;
        app.getSharedPreferences("partner_card", Context.MODE_PRIVATE).edit().clear().commit();
        // The answer is kept for the session and asked again after a resume.
        FilenPartner.refreshLevel();
        script.contractCalls.set(0);
        require(FilenPartner.level(app) == 1 && FilenPartner.level(app) == 1 && script.contractCalls.get() == 1, "the level is asked once per session");
        FilenPartner.refreshLevel();
        require(FilenPartner.level(app) == 1 && script.contractCalls.get() == 2, "the level is asked again after a resume");
        // A first call that Filen does not answer (a cold start) is not "level 0": it is asked again after 1, 3 and 8 s (shortened here), a failure is never kept, and only a real answer below 1 says "update".
        long[] realBackoff = FilenPartner.backoffMs;
        try {
            require(java.util.Arrays.equals(realBackoff, new long[]{1000, 3000, 8000}), "the waits are 1, 3 and 8 seconds");
            FilenPartner.backoffMs = new long[]{10, 30, 80};
            FilenPartner.refreshLevel(); script.contractCalls.set(0); script.failFirst = 2;
            require(FilenPartner.level(app) == 1 && script.contractCalls.get() == 3, "two unanswered calls are asked again, the third answer is the level: " + script.contractCalls.get());
            require(FilenPartner.level(app) == 1 && script.contractCalls.get() == 3, "the answer is kept");
            FilenPartner.refreshLevel(); script.contractCalls.set(0); script.failFirst = 99;
            require(FilenPartner.level(app) == FilenPartner.UNKNOWN && script.contractCalls.get() == 4, "four unanswered calls end as unknown: " + script.contractCalls.get());
            require(!FilenPartner.supported(FilenPartner.UNKNOWN) && !FilenPartner.gateText(FilenPartner.UNKNOWN).startsWith("Update"), "unknown is never shown as an update");
            script.failFirst = 0; script.contractCalls.set(0);
            require(FilenPartner.level(app) == 1 && script.contractCalls.get() == 1, "the failure was not kept: the next ask is made and answered");
            script.level = 0; FilenPartner.refreshLevel(); script.contractCalls.set(0);
            require(FilenPartner.level(app) == 0 && script.contractCalls.get() == 1, "a real answer of level 0 is not asked again at once and says update");
            script.level = 1;
        } finally { FilenPartner.backoffMs = realBackoff; script.failFirst = 0; FilenPartner.refreshLevel(); }
        require(FilenPartner.gateText(0).equals("Update Filen Files to share with your partner") && FilenPartner.gateButton(0).equals("Update"), "level 0 words");
        require(FilenPartner.gateText(FilenPartner.NOT_INSTALLED).equals("Get Filen Files") && FilenPartner.gateButton(FilenPartner.NOT_INSTALLED).equals("Get Filen Files"), "Filen missing words");
        require(!FilenPartner.supported(0) && !FilenPartner.supported(-1) && FilenPartner.supported(1) && FilenPartner.supported(2), "level 1 and above share");
        Object[][] cases = {{0, "Update Filen Files to share with your partner", "Update"}, {-1, "Get Filen Files", "Get Filen Files"}};
        for (Object[] c : cases) {
            script.level = (Integer) c[0];
            FilenPartner.refreshLevel();
            // Settings: one row with the button; no "Share with your partner".
            clear(app);
            script.status = new FilenPartner.Status("connected", "Sam");
            Activity settings = open(i, SettingsActivity.class);
            try {
                P1UiChecks.settle(i);
                View root = settings.getWindow().getDecorView();
                require(has(texts(root), (String) c[1]), "Settings row says " + c[1] + " at level " + c[0]);
                require(P1UiChecks.findButton(root, (String) c[2]) != null, "Settings row has the " + c[2] + " button");
                require(!has(texts(root), "Share with your partner") && !has(texts(root), "Show Sam's files here"), "no partner row at level " + c[0]);
                require(has(texts(root), "Folders"), "Folders row stays");
            } finally { i.runOnMainSync(settings::finish); }
            // The card: only for an old Filen; a missing Filen shows no card (Settings says so).
            FilenPartner.refreshLevel();
            Activity main = open(i, MainActivity.class);
            try {
                P1UiChecks.settle(i); P1UiChecks.settle(i);
                View root = main.getWindow().getDecorView();
                TextView card = P1UiChecks.findText(root, "Update Filen Files to share with your partner");
                require((card != null && card.isShown()) == ((Integer) c[0] == 0), "card for level " + c[0]);
                TextView partnerCard = P1UiChecks.findText(root, "See files Sam shares with you");
                require(partnerCard == null || !partnerCard.isShown(), "no partner card at level " + c[0]);
            } finally { i.runOnMainSync(main::finish); }
            // Folders: the own folder is listed, search is untouched, the share row is the gate.
            String uuid = UUID.randomUUID().toString();
            SourceStore.Source own = seed(store, SourceStore.OWN, "filen:" + uuid, "Bills", "");
            script.shares.put(uuid, new FilenPartner.Share(true, false, ""));
            FilenPartner.refreshLevel();
            Activity folders = open(i, FoldersActivity.class);
            try {
                P1UiChecks.settle(i);
                View root = folders.getWindow().getDecorView();
                View row = byTag(byTag(root, own.id), "share");
                require(row != null && !(row instanceof Switch) && ((TextView) row).getText().toString().equals(c[1]), "Folders share row is the gate at level " + c[0]);
                require(P1UiChecks.findButton(root, (String) c[2]) != null, "Folders has the " + c[2] + " button");
                require(P1UiChecks.findText(root, "Bills") != null && P1UiChecks.findButton(root, "Add a Filen folder") != null && P1UiChecks.findButton(root, "Remove from Stack") != null, "the folder and its other actions stay");
            } finally { i.runOnMainSync(folders::finish); }
        }
        // Level 1 again: the normal screens come back.
        script.level = 1; FilenPartner.refreshLevel(); clear(app);
        script.status = new FilenPartner.Status("none", "");
        Activity settings = open(i, SettingsActivity.class);
        try { P1UiChecks.settle(i); require(has(texts(settings.getWindow().getDecorView()), "Share with your partner"), "level 1: the normal Partner row"); }
        finally { i.runOnMainSync(settings::finish); }
        script.shares.clear();
        app.getSharedPreferences("partner_card", Context.MODE_PRIVATE).edit().clear().commit();
        clear(app);
        mark.accept("F12: level asked once per session and again on resume; level 0 and Filen missing show one Settings row with the button, the old-Filen card, and the Folders share row as text with the button; folders and search stay; level 1 normal");
    }

    /** Settings, Partner: the row reads "Sharing with [name]" right after Allow, even while an earlier, slower status answer is still on its way. */
    private static void partnerRowAfterAllow(Instrumentation i, StackApplication app, Consumer<String> mark) throws Exception {
        SourceStore store = app.library.sources;
        clear(app);
        Script script = new Script();
        script.status = new FilenPartner.Status("connected", "Sam");
        FilenPartner.api = script;
        FilenPartner.refreshLevel(); FilenPartner.level(app);
        script.statusDelayMs = 1500;
        final SettingsActivity settings = (SettingsActivity) open(i, SettingsActivity.class);
        try {
            seed(store, SourceStore.PARTNER, "filen-in:root", "Sam's shared files", "Sam");   // Allow added the source while the first status call is still waiting
            i.runOnMainSync(() -> settings.onActivityResult(PartnerSetup.REQUEST, Activity.RESULT_CANCELED, null));
            P1BatchChecks.await(() -> has(visible(i, settings), "Sharing with Sam"), "Settings reads Sharing with Sam right after Allow");
            android.os.SystemClock.sleep(2500);   // the slow first answer arrives now and must not overwrite it
            P1UiChecks.settle(i);
            require(has(visible(i, settings), "Sharing with Sam") && !has(visible(i, settings), "Show Sam's files here"), "a late status answer does not replace Sharing with Sam");
        } finally { i.runOnMainSync(settings::finish); }
        clear(app);
        mark.accept("Settings Partner row: Sharing with Sam right after Allow, not overwritten by a slow earlier Filen answer");
    }

    private static List<String> visible(Instrumentation i, Activity a) { List<String>[] out = new List[]{null}; i.runOnMainSync(() -> out[0] = texts(a.getWindow().getDecorView())); return out[0]; }

    /** After a share or stop action share_state is read again (1 s, 3 s, 8 s in the app; shortened here) until it is known and matches. */
    private static void shareRetry(Instrumentation i, StackApplication app, Consumer<String> mark) throws Exception {
        SourceStore store = app.library.sources;
        clear(app);
        require(java.util.Arrays.equals(FoldersActivity.RETRY_MS, new long[]{1000, 3000, 8000}), "retry delays are 1 s, 3 s, 8 s");
        require(FoldersActivity.again(FilenPartner.Share.UNKNOWN, false) && FoldersActivity.again(new FilenPartner.Share(true, true, ""), false)
                && !FoldersActivity.again(new FilenPartner.Share(true, false, ""), false) && !FoldersActivity.again(new FilenPartner.Share(true, true, ""), true), "retry while unknown or not what the action should have made it");
        String uuid = UUID.randomUUID().toString();
        SourceStore.Source own = seed(store, SourceStore.OWN, "filen:" + uuid, "Bills", "");
        seed(store, SourceStore.PARTNER, "filen-in:root", "x", "Sam");
        Script script = new Script();
        FilenPartner.api = script;
        FilenPartner.refreshLevel();
        script.shares.put(uuid, FilenPartner.Share.UNKNOWN);
        long[] saved = FoldersActivity.RETRY_MS;
        FoldersActivity.RETRY_MS = new long[]{200, 400, 800};
        final FoldersActivity screen = (FoldersActivity) open(i, FoldersActivity.class);
        try {
            P1UiChecks.settle(i);
            require(((TextView) byTag(byTag(screen.getWindow().getDecorView(), own.id), "share")).getText().toString().equals("Can't check sharing right now"), "unknown reads Can't check sharing right now");
            i.runOnMainSync(() -> screen.afterShare(uuid, true));
            android.os.SystemClock.sleep(150);
            script.shares.put(uuid, new FilenPartner.Share(true, true, ""));   // Filen catches up a moment later
            P1BatchChecks.await(() -> { View[] row = {null}; i.runOnMainSync(() -> row[0] = byTag(byTag(screen.getWindow().getDecorView(), own.id), "share")); return row[0] instanceof Switch && ((Switch) row[0]).isChecked(); }, "the unknown answer is replaced by Filen's later answer");
        } finally { FoldersActivity.RETRY_MS = saved; i.runOnMainSync(screen::finish); }
        clear(app);
        mark.accept("Share retry: 'Can't check sharing right now' is replaced once Filen answers after the action (retry until known and matching)");
    }

    /** Opening a file: nothing for 5 s, then Waiting for Filen… with Cancel, after 30 s Needs the internet; Cancel stops the attempt. */
    private static void openWait(StackApplication app, Consumer<String> mark) throws Exception {
        require(OpenWait.text(0).equals("Opening\u2026") && OpenWait.text(4999).equals("Opening\u2026"), "Opening… before 5 s");
        require(OpenWait.text(5000).equals("Waiting for Filen\u2026") && OpenWait.text(29999).equals("Waiting for Filen\u2026"), "Waiting for Filen… from 5 s");
        require(OpenWait.text(30000).equals("Needs the internet"), "Needs the internet from 30 s");
        require(!OpenWait.cancelable(4999) && OpenWait.cancelable(5000) && OpenWait.cancelable(29999) && !OpenWait.cancelable(30000), "Cancel shows between 5 s and 30 s");
        List<String> seen = new ArrayList<>();
        final OpenWait[] wait = {null};
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        main.post(() -> {
            wait[0] = new OpenWait((text, cancelable) -> seen.add(text + (cancelable ? "+cancel" : "")));
            wait[0].start();
            wait[0].tick(5000); wait[0].tick(30000); wait[0].tick(31000);
            done.countDown();
        });
        done.await();
        require(seen.equals(java.util.Arrays.asList("Opening\u2026", "Waiting for Filen\u2026+cancel", "Needs the internet")), "shown in order: " + seen);
        require(!wait[0].live() && wait[0].signal.isCanceled(), "after 30 s the attempt is stopped and its late answer ignored");
        List<String> cancelled = new ArrayList<>();
        java.util.concurrent.CountDownLatch done2 = new java.util.concurrent.CountDownLatch(1);
        main.post(() -> {
            wait[0] = new OpenWait((text, cancelable) -> cancelled.add(text));
            wait[0].start(); wait[0].tick(6000); wait[0].cancel(); wait[0].tick(30000);
            done2.countDown();
        });
        done2.await();
        require(cancelled.equals(java.util.Arrays.asList("Opening\u2026", "Waiting for Filen\u2026")) && !wait[0].live() && wait[0].signal.isCanceled(), "Cancel stops the attempt and nothing more is shown");
        mark.accept("Opening a file: Opening… then Waiting for Filen… with Cancel at 5 s, Needs the internet at 30 s; Cancel stops the attempt");
    }

    /** The Tags dialog's Save: a new name typed in the box is created and applied; ticked and unticked tags still change. */
    private static void tagsSave(StackApplication app, Consumer<String> mark) throws Exception {
        SourceStore store = app.library.sources;
        clear(app);
        SourceStore.Source own = seed(store, SourceStore.OWN, "filen:" + UUID.randomUUID(), "Bills", "");
        addRows(store, own.id, 1, 0);
        String fileId;
        try (Cursor c = store.db().rawQuery("SELECT id FROM source_files WHERE source_id=?", new String[]{own.id})) { c.moveToFirst(); fileId = c.getString(0); }
        String old = SourceActions.tagFor(store, "Old");
        store.addTag(SourceStore.ITEM_SOURCE, fileId, old);
        List<String[]> tags = SourceActions.allTags(store);
        boolean[] on = new boolean[tags.size()];
        for (int n = 0; n < on.length; n++) on[n] = false;   // untick Old
        String added = SourceActions.saveTags(store, fileId, tags, on, store.tagsOf(SourceStore.ITEM_SOURCE, fileId), "  Receipts ");
        List<String> now = store.tagsOf(SourceStore.ITEM_SOURCE, fileId);
        require(added != null && now.contains(added) && !now.contains(old) && now.size() == 1, "a new name is created and applied, and an unticked tag is removed");
        boolean exists = false;
        for (String[] tag : SourceActions.allTags(store)) if (tag[1].equals("Receipts")) exists = true;
        require(exists, "the new tag exists in the tag list");
        List<String[]> more = SourceActions.allTags(store);
        String again = SourceActions.saveTags(store, fileId, more, new boolean[more.size()], store.tagsOf(SourceStore.ITEM_SOURCE, fileId), "receipts");
        require(again != null && again.equals(added) && SourceActions.allTags(store).size() == more.size(), "typing an existing name (any case) applies it without a second tag");
        require(SourceActions.saveTags(store, fileId, SourceActions.allTags(store), new boolean[SourceActions.allTags(store).size()], store.tagsOf(SourceStore.ITEM_SOURCE, fileId), "   ") == null, "an empty box changes nothing");
        clear(app);
        mark.accept("Tags: Save creates and applies a typed new tag in one step; existing name reuses the tag; empty box adds nothing");
    }

    // ---- 4b. where a result is, and live updates ----------------------------------------------------------------------------------

    private static SourceStore.Hit hit(String kind, String partner, String title, String path) {
        return new SourceStore.Hit("i", "s", "filen:x", path.substring(path.lastIndexOf('/') + 1), path, "text/plain", "indexed", "", kind, partner, title, "", "");
    }

    private static void whereAndLive(Instrumentation i, StackApplication app, Consumer<String> mark) throws Exception {
        require("From Sam \u00b7 Taxes/2024".equals(SourceActions.where(hit("partner", "Sam", "Sam's shared files", "Taxes/2024/a.pdf"))), "partner file: From name, folder");
        require("From Sam".equals(SourceActions.where(hit("partner", "Sam", "x", "a.pdf"))), "partner file at the top");
        require("From Sam \u00b7 Shared scans".equals(SourceActions.where(hit("partner", "Sam", "x", "Stack scans shared with junk2/a.pdf"))), "old scan folder name reads Shared scans");
        require("From Sam \u00b7 Shared scans".equals(SourceActions.where(hit("partner", "Sam", "x", "Shared scans/a.pdf"))), "scan folder reads Shared scans");
        require("Bills/2024".equals(SourceActions.where(hit("own", "", "Bills", "2024/a.pdf"))), "own file: folder");
        require("Bills".equals(SourceActions.where(hit("own", "", "Bills", "a.pdf"))), "own file at the top: the folder's own name");
        SourceStore store = app.library.sources;
        clear(app);
        SourceStore.Source own = seed(store, SourceStore.OWN, "filen:" + UUID.randomUUID(), "Bills", "");
        ContentValues v = new ContentValues();
        v.put("id", UUID.randomUUID().toString()); v.put("source_id", own.id); v.put("provider_doc_id", "filen:" + UUID.randomUUID()); v.put("parent_doc_id", "p");
        v.put("name", "zebrafile.txt"); v.put("path", "2024/zebrafile.txt"); v.put("state", "indexed");
        String fileId = v.getAsString("id");
        store.db().insertOrThrow("source_files", null, v);
        store.indexName(fileId, "zebrafile.txt", "");
        Activity main = open(i, MainActivity.class);
        try {
            i.runOnMainSync(() -> ((TextView) P1UiChecks.findInput(main.getWindow().getDecorView(), "Search document names and text")).setText("zebrafile"));
            P1UiChecks.settle(i); P1UiChecks.settle(i);
            require(P1UiChecks.findText(main.getWindow().getDecorView(), "Bills/2024") != null, "result row shows the folder");
            P1UiChecks.capture(i, main, "ux-row-own");
            // A file the indexer adds later appears with no touch: the change notice reaches the open screen.
            ContentValues w = new ContentValues(v);
            w.put("id", UUID.randomUUID().toString()); w.put("provider_doc_id", "filen:" + UUID.randomUUID()); w.put("name", "zebrafile2.txt"); w.put("path", "2025/zebrafile2.txt");
            store.db().insertOrThrow("source_files", null, w);
            store.indexName(w.getAsString("id"), "zebrafile2.txt", "");
            app.sourceIndexer.changed();
            P1BatchChecks.await(() -> { boolean[] f = {false}; i.runOnMainSync(() -> f[0] = P1UiChecks.findText(main.getWindow().getDecorView(), "Bills/2025") != null); return f[0]; }, "Open search results refreshed by the indexer");
        } finally { i.runOnMainSync(main::finish); }
        Activity folders = open(i, FoldersActivity.class);
        try {
            require(P1UiChecks.findText(folders.getWindow().getDecorView(), "Bills") != null, "Folders lists the source");
            store.setState(own.id, "unreachable", "x");
            app.sourceIndexer.changed();
            P1BatchChecks.await(() -> { boolean[] f = {false}; i.runOnMainSync(() -> f[0] = P1UiChecks.findText(folders.getWindow().getDecorView(), "Can't reach Filen") != null); return f[0]; }, "Folders screen refreshed by the indexer");
        } finally { i.runOnMainSync(folders::finish); }
        clear(app);
        mark.accept("Result row second line (partner, own, scan folder label) and live refresh of search and Folders from the indexer's change notice");
    }


    // ---- stacks and tags hold folder files; one row per Filen file ---------------------------------------------------------------

    private static String addFile(SourceStore store, String sourceId, String docId, String name, String path, String text) {
        ContentValues v = new ContentValues();
        String id = UUID.randomUUID().toString();
        v.put("id", id); v.put("source_id", sourceId); v.put("provider_doc_id", docId); v.put("parent_doc_id", "p"); v.put("name", name); v.put("path", path); v.put("state", "indexed");
        store.db().insertOrThrow("source_files", null, v);
        store.indexName(id, name, text);
        return id;
    }

    private static void stackMembers(Instrumentation i, StackApplication app, Consumer<String> mark) throws Exception {
        SourceStore store = app.library.sources;
        clear(app);
        SQLiteDatabase db = store.db();
        db.execSQL("DELETE FROM item_stacks"); db.execSQL("DELETE FROM item_tags");
        SourceStore.Source own = seed(store, SourceStore.OWN, "filen:" + UUID.randomUUID(), "Home files", "");
        SourceStore.Source partner = seed(store, SourceStore.PARTNER, "filen-in:root", "Sam's shared files", "Sam");
        String mine = addFile(store, own.id, "filen:" + UUID.randomUUID(), "gasbill.pdf", "Notes/gasbill.pdf", "gas statement for january");
        String theirs = addFile(store, partner.id, "filen-in:" + UUID.randomUUID(), "water.pdf", "water.pdf", "water statement");
        String other = addFile(store, own.id, "filen:" + UUID.randomUUID(), "recipe.txt", "recipe.txt", "pasta statement");
        int before = 0;
        for (StackCategory c : app.library.stacks()) if (c.id.equals("bills")) before = c.count;
        store.addToStack(SourceStore.ITEM_SOURCE, theirs, "bills");
        store.addToStack(SourceStore.ITEM_SOURCE, mine, "bills");
        store.addToStack(SourceStore.ITEM_SOURCE, mine, "house");
        int after = 0, house = 0;
        for (StackCategory c : app.library.stacks()) { if (c.id.equals("bills")) after = c.count; if (c.id.equals("house")) house = c.count; }
        require(after == before + 2, "the stack's count includes its folder files: " + before + " -> " + after);
        require(house >= 1, "a file in two stacks counts in both");
        List<SourceStore.Hit> inBills = store.search("", null, "bills", null, null);
        require(inBills.size() == 2 && inBills.get(0).name.equals("gasbill.pdf") && inBills.get(1).name.equals("water.pdf"), "the stack lists its folder files with no query: " + inBills.size());
        require(store.search("water", null, "bills", null, null).size() == 1 && store.search("recipe", null, "bills", null, null).isEmpty(), "stack-filtered search finds members only");
        require(store.search("statement", null, null, null, null).size() == 3 && store.search("statement", null, "bills", null, null).size() == 2, "the same words without a filter find all three");
        require(store.search("", null, null, null, null).isEmpty(), "no query and no filter lists nothing");
        require(store.search("", null, "bills", "house", null).size() == 1 && store.search("", null, "bills", "house", null).get(0).name.equals("gasbill.pdf"), "two stacks together: only the file in both");
        boolean overlap = false;
        for (StackCategory c : app.library.overlappingStacks("bills")) if (c.id.equals("house")) overlap = true;
        require(overlap, "the stacks that share files with this one are listed, files included");
        String tag = SourceActions.tagFor(store, "Tax");
        store.addTag(SourceStore.ITEM_SOURCE, theirs, tag);
        require(app.library.organizations().containsKey(tag), "a tag only folder files carry is offered in the filter");
        List<SourceStore.Hit> tagged = store.search("", null, null, null, tag);
        require(tagged.size() == 1 && tagged.get(0).name.equals("water.pdf") && store.search("statement", null, "house", null, tag).isEmpty(), "tag-filtered search and listing");
        // A file waiting for a removal confirmation is not counted or listed.
        db.execSQL("UPDATE source_files SET missing_since=5 WHERE id=?", new Object[]{theirs});
        int missing = 0; for (StackCategory c : app.library.stacks()) if (c.id.equals("bills")) missing = c.count;
        require(missing == before + 1 && store.search("", null, "bills", null, null).size() == 1, "a file waiting for removal is hidden from the stack");
        db.execSQL("UPDATE source_files SET missing_since=0 WHERE id=?", new Object[]{theirs});
        // The real screen: the Bills stack shows the file, and a tap opens it the way a search result opens.
        Activity main = open(i, MainActivity.class);
        try {
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("stackId"); field.setAccessible(true);
            java.lang.reflect.Method refresh = MainActivity.class.getDeclaredMethod("refresh"); refresh.setAccessible(true);
            i.runOnMainSync(() -> { try { ((TextView) P1UiChecks.findInput(main.getWindow().getDecorView(), "Search document names and text")).setText(""); field.set(main, "bills"); refresh.invoke(main); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); } });
            P1UiChecks.settle(i); P1UiChecks.settle(i);
            View root = main.getWindow().getDecorView();
            require(P1UiChecks.findText(root, "water.pdf") != null && P1UiChecks.findText(root, "gasbill.pdf") != null && P1UiChecks.findText(root, "recipe.txt") == null, "the Bills stack screen lists its folder files: " + texts(root));
            require(P1UiChecks.findText(root, "No documents yet") == null, "a stack with only folder files is not empty");
        } finally { i.runOnMainSync(main::finish); }
        db.execSQL("DELETE FROM item_stacks"); db.execSQL("DELETE FROM item_tags");
        clear(app);
        mark.accept("Stacks and tags hold folder files: counts, stack and tag listing with and without a query, two stacks, overlaps, hidden when waiting for removal, the Bills stack screen shows them");
    }

    private static void sharedTwice(Instrumentation i, StackApplication app, Consumer<String> mark) throws Exception {
        SourceStore store = app.library.sources;
        clear(app);
        String uuid = UUID.randomUUID().toString(), loneUuid = UUID.randomUUID().toString(), plainUuid = UUID.randomUUID().toString();
        SourceStore.Source own = seed(store, SourceStore.OWN, "filen:" + UUID.randomUUID(), "Home files", "");
        SourceStore.Source out = seed(store, SourceStore.SHARED_BY_ME, "filen-out:root", "Shared by me", "Sam");
        String ownRow = addFile(store, own.id, "filen:" + uuid, "budget.txt", "Notes/budget.txt", "quarterly zebra budget");
        addFile(store, out.id, "filen-out:" + uuid, "budget.txt", "Home files/Notes/budget.txt", "");
        addFile(store, out.id, "filen-out:" + loneUuid, "lonely.txt", "Elsewhere/lonely.txt", "lonely zebra file outside the added folders");
        addFile(store, own.id, "filen:" + plainUuid, "plain.txt", "plain.txt", "plain zebra file");
        List<SourceStore.Hit> hits = store.search("zebra", null);
        require(hits.size() == 3, "one row per Filen file: " + hits.size());
        SourceStore.Hit shared = null, lone = null, plain = null;
        for (SourceStore.Hit h : hits) { if (h.name.equals("budget.txt")) shared = h; if (h.name.equals("lonely.txt")) lone = h; if (h.name.equals("plain.txt")) plain = h; }
        require(shared != null && SourceStore.OWN.equals(shared.kind) && shared.id.equals(ownRow) && shared.sharedByMe, "the own source's row survives and knows it is shared");
        require("Shared by you \u00b7 Home files/Notes".equals(SourceActions.where(shared)), "label of the shared own file: " + SourceActions.where(shared));
        require(lone != null && SourceStore.SHARED_BY_ME.equals(lone.kind) && "Shared by you \u00b7 Elsewhere".equals(SourceActions.where(lone)), "a shared file outside the added folders keeps its own row: " + (lone == null ? "" : SourceActions.where(lone)));
        require(plain != null && !plain.sharedByMe && "Home files".equals(SourceActions.where(plain)), "an own file that is not shared is labelled as before");
        require(shared.filenUuid().equals(uuid) && SourceStore.OWN.equals(shared.kind), "the surviving row keeps the share menu line: an own row with a Filen uuid");
        // The same through a stack, in either order of the rows.
        String sharedCopy;
        try (Cursor c = store.db().rawQuery("SELECT id FROM source_files WHERE provider_doc_id=?", new String[]{"filen-out:" + uuid})) { c.moveToFirst(); sharedCopy = c.getString(0); }
        store.addToStack(SourceStore.ITEM_SOURCE, sharedCopy, "bills");
        store.addToStack(SourceStore.ITEM_SOURCE, ownRow, "bills");
        List<SourceStore.Hit> stacked = store.search("", null, "bills", null, null);
        require(stacked.size() == 1 && SourceStore.OWN.equals(stacked.get(0).kind), "a file in the stack through both rows is listed once, from the own source");
        int n = 0; for (StackCategory c : app.library.stacks()) if (c.id.equals("bills")) n = c.count;
        require(n == app.library.documents("", "bills").size() + 1, "and counted once: " + n);
        store.db().execSQL("DELETE FROM item_stacks");
        clear(app);
        mark.accept("Owner's search: a file shared by the owner is one row (own source, 'Shared by you · folder', share menu kept); shared files outside the added folders and unshared files unchanged; one row and one count in a stack");
    }

    // ---- 5. scan sharing writer -----------------------------------------------------------------------------------------------------

    /** A folder on disk that can fail on demand. */
    static final class DiskFolder implements ScanShare.Folder {
        final File dir; boolean offline; int puts; String account = "acct-1", legacy = ""; boolean fresh = true;
        /** Runs after a put has landed (a switch turned off mid-upload, a process dying before the row), and a delete that fails while the folder is otherwise reachable. */
        Runnable afterPut = () -> {}; boolean failDeletes;
        @Override public ScanShare.Names list() throws IOException { if (offline) throw new IOException("offline"); return new ScanShare.Names(fresh, account, legacy, names()); }
        DiskFolder(File dir) { this.dir = dir; dir.mkdirs(); }
        @Override public void put(String name, File content) throws IOException {
            if (offline) throw new IOException("offline");
            File part = new File(dir, name + ".part");
            try (java.io.FileInputStream in = new java.io.FileInputStream(content); FileOutputStream out = new FileOutputStream(part)) { byte[] b = new byte[4096]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); }
            File target = new File(dir, name); target.delete();
            if (!part.renameTo(target)) throw new IOException("rename");
            puts++;
            afterPut.run();
        }
        @Override public void delete(String name) throws IOException { if (offline || failDeletes) throw new IOException("offline"); new File(dir, name).delete(); }
        List<String> names() { List<String> out = new ArrayList<>(); String[] list = dir.list(); if (list != null) for (String s : list) out.add(s); java.util.Collections.sort(out); return out; }
        String text(String name) throws IOException { try (java.io.FileInputStream in = new java.io.FileInputStream(new File(dir, name))) { byte[] b = new byte[(int) new File(dir, name).length()]; int n = in.read(b); return new String(b, 0, Math.max(n, 0), "UTF-8"); } }
    }

    private static void doc(SQLiteDatabase db, String id, String title, int pages) {
        db.execSQL("INSERT INTO documents(id,title,mime,added_at,page_count,initial_page_count) VALUES(?,?,?,?,?,?)", new Object[]{id, title, "application/pdf", 1, pages, pages});
        for (int p = 0; p < pages; p++) db.execSQL("INSERT INTO pages(id,document_id,position,source_page) VALUES(?,?,?,?)", new Object[]{UUID.randomUUID().toString(), id, p, p});
    }

    private static void scanShare(Context iso, Consumer<String> mark) throws Exception {
        String dbName = "scan-share-" + UUID.randomUUID() + ".db";
        LibraryDatabase helper = new LibraryDatabase(iso, dbName);
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            SourceStore store = new SourceStore(iso, helper::getWritableDatabase, new File(iso.getFilesDir(), "sources"));
            SourceStore.Source folderSource = seed(store, SourceStore.SCAN_SHARE, "filen:" + UUID.randomUUID(), "Shared scans", "Sam");
            DiskFolder folder = new DiskFolder(new File(iso.getCacheDir(), "scan-folder-" + UUID.randomUUID()));
            boolean[] allowed = {true};
            ScanShare share = new ScanShare(iso, store, src -> folder, (id, out) -> {
                try (Cursor c = db.rawQuery("SELECT title,(SELECT COUNT(*) FROM pages WHERE document_id=?) FROM documents WHERE id=?", new String[]{id, id})) {
                    if (!c.moveToFirst()) throw new IllegalArgumentException("gone");
                    out.write(("PDF " + c.getString(0) + " pages=" + c.getInt(1)).getBytes("UTF-8"));
                }
            }, () -> allowed[0]);
            share.grantOk = src -> true;
            String d1 = "11111111-1111-1111-1111-111111111111", d2 = "22222222-2222-2222-2222-222222222222", d3 = "33333333-3333-3333-3333-333333333333";
            doc(db, d1, "Rent receipt", 1); doc(db, d2, "Lease", 2); doc(db, d3, "Private note", 1);
            db.execSQL("INSERT INTO document_stacks(document_id,stack_id) VALUES(?,?)", new Object[]{d1, "house"});
            db.execSQL("INSERT INTO document_stacks(document_id,stack_id) VALUES(?,?)", new Object[]{d3, "medical"});
            // Nothing is shared until the stack switch is on.
            require(share.run().written == 0 && folder.names().isEmpty(), "no switch, no files");
            share.setOn("house", true);
            ScanShare.Result r = share.run();
            require(r.written == 1 && r.complete && folder.names().equals(java.util.Arrays.asList("Rent receipt [11111111].pdf")), "a scan in a shared stack is written: " + folder.names());
            require(folder.text("Rent receipt [11111111].pdf").equals("PDF Rent receipt pages=1"), "the file holds the PDF");
            require("ok".equals(store.get(folderSource.id).state), "the source reads ok after a run");
            // Nothing changed: nothing is written again.
            int puts = folder.puts; share.run();
            require(folder.puts == puts, "an unchanged scan is not written again");
            // A scan added to the stack later; a scan in another stack stays private.
            db.execSQL("INSERT INTO document_stacks(document_id,stack_id) VALUES(?,?)", new Object[]{d2, "house"});
            r = share.run();
            require(r.written == 1 && folder.names().size() == 2 && !folder.names().toString().contains("Private"), "a later scan is added; other stacks stay private: " + folder.names());
            // Update: more pages, then a new title (the file is renamed, not duplicated).
            doc(db, "44444444-4444-4444-4444-444444444444", "Spare", 1);
            db.execSQL("INSERT INTO pages(id,document_id,position,source_page) VALUES(?,?,?,?)", new Object[]{UUID.randomUUID().toString(), d2, 2, 2});
            db.execSQL("UPDATE documents SET page_count=3 WHERE id=?", new Object[]{d2});
            r = share.run();
            require(r.written == 1 && folder.text("Lease [22222222].pdf").equals("PDF Lease pages=3"), "an added page rewrites the file");
            db.execSQL("UPDATE documents SET title='Lease 2026' WHERE id=?", new Object[]{d2});
            r = share.run();
            require(folder.names().equals(java.util.Arrays.asList("Lease 2026 [22222222].pdf", "Rent receipt [11111111].pdf")), "a new title renames the file without leaving the old one: " + folder.names());
            // Locked app: waits, writes nothing, then continues.
            db.execSQL("UPDATE documents SET title='Rent May' WHERE id=?", new Object[]{d1});
            allowed[0] = false; r = share.run();
            require("locked".equals(r.stopped) && "waiting".equals(store.get(folderSource.id).state) && folder.names().contains("Rent receipt [11111111].pdf"), "locked: nothing is written, state waiting");
            allowed[0] = true;
            // Offline: queued; the next run catches up and removes nothing wrongly.
            folder.offline = true; r = share.run();
            require("unreachable".equals(r.stopped) && "unreachable".equals(store.get(folderSource.id).state) && !folder.names().contains("Rent May [11111111].pdf"), "offline: the change waits, state unreachable");
            folder.offline = false; r = share.run();
            require(r.complete && folder.names().equals(java.util.Arrays.asList("Lease 2026 [22222222].pdf", "Rent May [11111111].pdf")) && "ok".equals(store.get(folderSource.id).state), "back online: the queued change is written: " + folder.names());
            // A scan leaves the stack: its file goes. A scan in Recently deleted: its file goes.
            db.execSQL("DELETE FROM document_stacks WHERE document_id=? AND stack_id='house'", new Object[]{d2});
            r = share.run();
            require(r.removed == 1 && folder.names().equals(java.util.Arrays.asList("Rent May [11111111].pdf")), "removing a scan from the stack removes its PDF");
            db.execSQL("INSERT INTO trash(document_id,deleted_at) VALUES(?,?)", new Object[]{d1, 1});
            r = share.run();
            require(folder.names().isEmpty(), "a scan moved to Recently deleted is removed");
            db.execSQL("DELETE FROM trash"); share.run();
            require(folder.names().size() == 1, "restored from Recently deleted: written again");
            // Switch off: every file goes, the originals stay.
            share.setOn("house", false); r = share.run();
            require(folder.names().isEmpty() && share.written().isEmpty(), "switch off removes the PDFs");
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM documents", null)) { c.moveToFirst(); require(c.getInt(0) == 4, "the scans themselves stay in Stack"); }
            // Access lost.
            share.setOn("house", true); share.grantOk = src -> false; r = share.run();
            require("access".equals(r.stopped) && "access_lost".equals(store.get(folderSource.id).state), "lost grant: access_lost, nothing removed");
            // The scan-share source is not read back into search by the indexer.
            SourceIndexer indexer = new SourceIndexer(iso, store, src -> { throw new AssertionError("scan share source must not be read"); }, new SourceChecks.Env());
            indexer.scanAll(null, new SourceIndexer.Control());
            // Deleting a stack cascades the switch.
            share.grantOk = src -> true;
            db.execSQL("DELETE FROM stacks WHERE id='house'");
            require(!share.isOn("house"), "deleting a stack drops its switch");
        } finally { helper.close(); }
        mark.accept("Scan sharing writer: add, update (pages, rename), remove from stack, trash and restore, switch off, locked waits, offline queues and catches up, access lost; indexer skips the scan folder");
    }



    // ---- 5b. nothing written after "stop"; orphans; identity; erase; replace; confirm; opening (security review) ------------------------------

    private static void scanShareSafety(Context target, Consumer<String> mark) throws Exception {
        Context iso = new ScannerChecks.IsolatedContext(target);
        target.getSharedPreferences("scan_share_old", Context.MODE_PRIVATE).edit().clear().commit();
        String dbName = "scan-safety-" + UUID.randomUUID() + ".db";
        LibraryDatabase helper = new LibraryDatabase(iso, dbName);
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            SourceStore store = new SourceStore(iso, helper::getWritableDatabase, new File(iso.getFilesDir(), "sources"));
            store.heldOverride = tree -> true;
            SourceStore.Source scanSource = seed(store, SourceStore.SCAN_SHARE, "filen:" + UUID.randomUUID(), "Shared scans", "Sam");
            store.setAccount(scanSource.id, "acct-1");
            DiskFolder folder = new DiskFolder(new File(iso.getCacheDir(), "scan-folder-" + UUID.randomUUID()));
            final Runnable[] during = {() -> {}};
            boolean[] allowed = {true};
            ScanShare share = new ScanShare(iso, store, src -> folder, (id, out) -> { during[0].run(); out.write(("PDF " + id).getBytes("UTF-8")); }, () -> allowed[0]);
            share.grantOk = src -> true;
            String[] d = {"a1111111-1111-1111-1111-111111111111", "b2222222-2222-2222-2222-222222222222", "c3333333-3333-3333-3333-333333333333"};
            for (int n = 0; n < 3; n++) { doc(db, d[n], "Doc " + n, 1); db.execSQL("INSERT INTO document_stacks(document_id,stack_id) VALUES(?,?)", new Object[]{d[n], "house"}); }
            // The switch goes off while the first scan is being made: nothing is written, not even that one.
            share.setOn("house", true);
            during[0] = () -> share.setOn("house", false);
            ScanShare.Result r = share.run();
            require(folder.puts == 0 && folder.names().isEmpty() && "changed".equals(r.stopped), "switch off in the middle of a run: nothing is written after it: " + r.stopped + " " + folder.names());
            // One scan goes to Recently deleted during the run: it is skipped, the others are written.
            share.setOn("house", true);
            final boolean[] once = {true};
            during[0] = () -> { if (once[0]) { once[0] = false; db.execSQL("INSERT INTO trash(document_id,deleted_at) VALUES(?,?)", new Object[]{d[0], 1}); } };
            r = share.run();
            require(folder.names().size() == 2 && !folder.names().toString().contains("Doc 0"), "a scan deleted during the run is not written: " + folder.names());
            db.execSQL("DELETE FROM trash");
            during[0] = () -> {};
            // Locked (the app lock) during the run: no write after it.
            for (String n : folder.names()) folder.delete(n);
            db.execSQL("DELETE FROM scan_share_files");
            during[0] = () -> allowed[0] = false;
            r = share.run(); allowed[0] = true; during[0] = () -> {};
            require(folder.names().isEmpty() && "locked".equals(r.stopped), "the app lock during a run stops it before the write: " + r.stopped);
            // AccessPolicy.lock() cancels like the indexer.
            during[0] = share::cancel; r = share.run(); during[0] = () -> {};
            require(folder.names().isEmpty() && "changed".equals(r.stopped), "cancel() ends a run before the next write");
            // Nothing is deleted by its look: files with Stack's naming and no row of Stack's own stay, and so do the user's own files.
            r = share.run();
            require(folder.names().size() == 3 && r.complete, "everything is written again: " + folder.names());
            for (String user : new String[]{"Old trip [abcd1234].pdf", "notes.pdf", "Odd [zzzzzzzz].pdf", "Taxes [20240415].pdf"})
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(folder.dir, user))) { out.write(1); }
            share.prefs().edit().putBoolean("reconcile", true).commit();
            r = share.run();
            require(r.orphans == 0 && folder.names().containsAll(java.util.Arrays.asList("Old trip [abcd1234].pdf", "notes.pdf", "Odd [zzzzzzzz].pdf", "Taxes [20240415].pdf")) && folder.names().size() == 7,
                    "reconcile deletes nothing it did not write, not even a user file named like ours (Taxes [20240415].pdf): " + folder.names());
            // A save cut short leaves "<name>.pdf.part" AND a journal line (written before the upload): exactly those names are removed. A file lost from the
            // listing is put again.
            java.util.List<String> beforeNames = folder.names();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(folder.dir, "Ghost [deadbeef].pdf.part"))) { out.write(1); }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(folder.dir, "Other [deadbeef].pdf.part"))) { out.write(1); }
            share.journal("ghost-doc", "Ghost [deadbeef].pdf");
            String lost = null; for (String n : beforeNames) if (n.startsWith("Doc ") && n.endsWith(".pdf")) { lost = n; break; }
            require(lost != null && new File(folder.dir, lost).delete(), "a written file to lose");
            share.prefs().edit().putBoolean("reconcile", true).commit();
            r = share.run();
            require(!folder.names().contains("Ghost [deadbeef].pdf.part") && folder.names().contains("Other [deadbeef].pdf.part") && folder.names().contains(lost) && r.orphans == 1 && share.journalRows().isEmpty(),
                    "the journalled .part leftover is deleted (an unjournalled one is not) and a file lost from the listing is put again: " + folder.names());
            new File(folder.dir, "Other [deadbeef].pdf.part").delete();
            // The journal: a switch turned off in the middle of an upload whose take-down delete fails, and a process that dies between the upload and its row.
            for (String n : new java.util.ArrayList<>(folder.names())) if (n.startsWith("Doc ")) folder.delete(n);
            db.execSQL("DELETE FROM scan_share_files");
            folder.afterPut = () -> { share.setOn("house", false); folder.failDeletes = true; };
            r = share.run();
            require("changed".equals(r.stopped) && share.journalRows().size() == 1 && share.written().isEmpty() && folder.names().stream().filter(n -> n.startsWith("Doc ")).count() == 1,
                    "a failed take-down leaves the file AND a journal line: " + r.stopped + " " + folder.names());
            require(share.anyOn() && share.removalPending(), "with every switch off, anyOn() and removalPending() are still true while a journal line exists");
            folder.afterPut = () -> {}; folder.failDeletes = false;
            r = share.run();
            require(share.journalRows().isEmpty() && folder.names().stream().noneMatch(n -> n.startsWith("Doc ")) && !share.removalPending(), "reachable again: the orphan is removed and the journal is empty: " + folder.names());
            share.prefs().edit().remove("pending").remove("reconcile").commit();
            require(!share.anyOn(), "nothing left to do or undo: anyOn() is false");
            share.prefs().edit().putBoolean("reconcile", true).commit();
            require(share.anyOn(), "the reconcile flag keeps anyOn() true");
            share.prefs().edit().remove("reconcile").putBoolean("pending", true).commit();
            require(share.anyOn(), "the pending flag keeps anyOn() true");
            share.prefs().edit().remove("pending").commit();
            share.setOn("house", true);
            folder.afterPut = () -> { throw new IllegalStateException("the process died"); };
            r = share.run();
            require("error".equals(r.stopped) && share.journalRows().size() >= 1 && share.written().isEmpty(), "a crash between the upload and its row leaves a journal line: " + r.stopped + " " + share.journalRows().size());
            folder.afterPut = () -> {};
            share.setOn("house", false);   // and the stacks are switched off afterwards: the file must still go
            r = share.run();
            require(share.journalRows().isEmpty() && folder.names().stream().noneMatch(n -> n.startsWith("Doc ")), "the next run removes what the crashed upload left, although nothing is switched on: " + folder.names());
            share.setOn("house", true); r = share.run();
            require(folder.names().stream().filter(n -> n.startsWith("Doc ")).count() == 3 && r.complete, "switched on again: written: " + folder.names());
            // Switch off while Filen is away: files stay, the pending state says so; back online they go.
            folder.offline = true; share.setOn("house", false); r = share.run();
            require("unreachable".equals(r.stopped) && r.removalPending && share.removalPending() && folder.names().size() == 7 && ScanShare.REMOVAL_PENDING.equals("Shared copies will be removed when Filen is reachable"), "switch off while offline keeps a pending state");
            folder.offline = false; r = share.run();
            require(!share.removalPending() && folder.names().equals(java.util.Arrays.asList("Odd [zzzzzzzz].pdf", "Old trip [abcd1234].pdf", "Taxes [20240415].pdf", "notes.pdf")), "reachable again: the shared copies are removed: " + folder.names());
            // Another Filen account: nothing is written or removed.
            share.setOn("house", true); folder.account = "acct-2"; r = share.run();
            require("identity".equals(r.stopped) && "account_changed".equals(store.get(scanSource.id).state) && folder.puts > 0 && folder.names().size() == 4, "another account pauses the folder with a message");
            folder.account = "acct-1";
            // Another partner: every switch goes off with a message, and the old copies are still removed (the account is the same).
            r = share.run();
            require(r.complete && folder.names().size() == 7 && share.isOn("house"), "written before the partner changes: " + folder.names());
            share.partnerLookup = () -> "";
            seed(store, SourceStore.PARTNER, "filen-in:root", "Alex's shared files", "Alex");
            r = share.run();
            require("identity".equals(r.stopped) && ScanShare.PARTNER_CHANGED.equals(store.get(scanSource.id).reason) && "access_lost".equals(store.get(scanSource.id).state), "another partner: the folder says so: " + store.get(scanSource.id).reason);
            require("Your partner changed. Scan sharing is off; turn it on again for each stack.".equals(ScanShare.PARTNER_CHANGED) && !share.isOn("house"), "every scan-share switch is off after a partner change");
            require(folder.names().equals(java.util.Arrays.asList("Odd [zzzzzzzz].pdf", "Old trip [abcd1234].pdf", "Taxes [20240415].pdf", "notes.pdf")) && share.written().isEmpty(), "the old partner's copies are removed from the old folder (only the partner changed): " + folder.names());
            share.setOn("house", true);   // somebody turns a stack on at the old folder: a new partner's run switches it off again before anything is written
            int putsBefore = folder.puts; r = share.run();
            require(folder.puts == putsBefore && !share.isOn("house"), "nothing is written for the new partner by a stale switch");
            // The partner AND the Filen account changed: nothing is removed.
            db.execSQL("DELETE FROM sources WHERE kind='partner'");
            share.setOn("house", true); r = share.run();
            require(r.complete && folder.names().size() == 7, "same partner again: written: " + folder.names());
            seed(store, SourceStore.PARTNER, "filen-in:root", "Alex's shared files", "Alex"); folder.account = "acct-2";
            r = share.run();
            require("identity".equals(r.stopped) && "account_changed".equals(store.get(scanSource.id).state) && folder.names().size() == 7 && !share.isOn("house"), "partner and account changed: nothing is removed, the switches are off: " + folder.names());
            folder.account = "acct-1"; r = share.run();
            require(folder.names().size() == 4 && "access_lost".equals(store.get(scanSource.id).state), "account back: the removal goes through: " + folder.names());
            // Filen's new account id: the folder recorded under the legacy id adopts the new one once and goes on.
            db.execSQL("DELETE FROM sources WHERE kind='partner'");
            share.setOn("house", true);
            folder.account = "acct-new"; folder.legacy = "acct-1";
            r = share.run();
            require(r.complete && "ok".equals(store.get(scanSource.id).state) && "acct-new".equals(store.get(scanSource.id).accountId), "the legacy account id moves to the new one without pausing: " + r.stopped + " " + store.get(scanSource.id).accountId);
            folder.account = "acct-other"; folder.legacy = "acct-unrelated"; share.prefs().edit().putBoolean("reconcile", true).commit(); r = share.run();
            require("identity".equals(r.stopped) && "account_changed".equals(store.get(scanSource.id).state), "another account still pauses");
            folder.account = "acct-1"; folder.legacy = ""; store.setAccount(scanSource.id, "acct-1"); store.setState(scanSource.id, SourceStore.OK, "");
            share.setOn("house", false); r = share.run();
            require(folder.names().size() == 4, "back to the old account, switched off: only the user's files are left: " + folder.names());
            // No partner source: Filen's partner_status name stands in; a folder with no partner recorded counts as changed once a partner is known; unknown is not guessed.
            db.execSQL("DELETE FROM sources WHERE kind='partner'");
            SourceStore.Source current = store.get(scanSource.id);
            share.partnerLookup = () -> "Sam"; require(!share.partnerChanged(current), "same name from partner_status: not changed");
            share.partnerLookup = () -> "Alex"; require(share.partnerChanged(current), "another name from partner_status: changed");
            share.partnerLookup = () -> ""; require(!share.partnerChanged(current), "Filen cannot say who the partner is (offline): nothing is assumed");
            db.execSQL("UPDATE sources SET partner_name='' WHERE id=?", new Object[]{scanSource.id});
            share.partnerLookup = () -> "Sam"; require(share.partnerChanged(store.get(scanSource.id)), "no partner recorded with the folder, partner known now: changed");
            db.execSQL("UPDATE sources SET partner_name='Sam' WHERE id=?", new Object[]{scanSource.id});
            share.partnerLookup = () -> "";
            // Partner state (F9): a definite answer other than "connected" is a change; no answer pauses the writes (removals still go ahead); only "connected" with the same partner writes.
            share.partnerStateLookup = () -> new FilenPartner.Status("connected", "Sam");
            share.setOn("house", true); r = share.run();
            require(r.complete && folder.names().size() == 7, "connected, same partner: written: " + folder.names());
            share.partnerStateLookup = () -> null;
            share.setOn("house", false); r = share.run();
            require("partner_unknown".equals(r.stopped) && folder.names().size() == 4 && "waiting".equals(store.get(scanSource.id).state) && ScanShare.PARTNER_UNKNOWN.equals(store.get(scanSource.id).reason) && "Waiting to check your partner in Filen".equals(ScanShare.PARTNER_UNKNOWN),
                "no answer from Filen: the removal goes ahead and the state says waiting: " + r.stopped + " " + folder.names().size() + " " + store.get(scanSource.id).reason);
            share.setOn("house", true); int unknownPuts = folder.puts; r = share.run();
            require("partner_unknown".equals(r.stopped) && folder.puts == unknownPuts && folder.names().size() == 4 && share.isOn("house") && share.anyOn(), "no answer from Filen: nothing is written, the switch stays, it is tried again later");
            share.partnerStateLookup = () -> new FilenPartner.Status("connected", "Sam"); r = share.run();
            require(r.complete && folder.names().size() == 7 && "ok".equals(store.get(scanSource.id).state), "Filen answers connected, same partner: written now: " + folder.names());
            share.partnerStateLookup = () -> new FilenPartner.Status("none", ""); r = share.run();
            require("identity".equals(r.stopped) && !share.isOn("house") && ScanShare.PARTNER_CHANGED.equals(store.get(scanSource.id).reason) && folder.names().size() == 4, "Filen says not connected: scan sharing is off with the message, copies removed: " + folder.names());
            for (String other : new String[]{"requested", "incoming"}) {
                share.partnerStateLookup = () -> new FilenPartner.Status("connected", "Sam"); share.setOn("house", true); r = share.run();
                share.partnerStateLookup = () -> new FilenPartner.Status(other, ""); r = share.run();
                require("identity".equals(r.stopped) && !share.isOn("house") && folder.names().size() == 4, "state " + other + " counts as not connected");
            }
            // The identity comes from the same partner_status call as "connected": a different id pauses nothing silently, no id and no name is unknown, the same id writes.
            db.execSQL("UPDATE sources SET partner_id='id-1', partner_name='Sam' WHERE id=?", new Object[]{scanSource.id});
            share.partnerLookup = () -> "Sam"; share.partnerIdLookup = () -> "id-1";   // other calls would say "same": they must not be used
            share.partnerStateLookup = () -> new FilenPartner.Status("connected", "Sam", "id-1"); share.setOn("house", true); r = share.run();
            require(r.complete && folder.names().size() == 7, "connected with the same id: written: " + folder.names());
            share.partnerStateLookup = () -> new FilenPartner.Status("connected", "Sam", "id-2"); r = share.run();
            require("identity".equals(r.stopped) && !share.isOn("house") && folder.names().size() == 4, "connected with a different id: a partner change, copies removed: " + folder.names());
            share.partnerStateLookup = () -> new FilenPartner.Status("connected", "Sam", "id-1"); store.setState(scanSource.id, SourceStore.OK, ""); share.setOn("house", true); r = share.run();
            require(r.complete && folder.names().size() == 7, "written again: " + folder.names());
            share.partnerStateLookup = () -> new FilenPartner.Status("connected", "", ""); int blindPuts = folder.puts; r = share.run();
            require("partner_unknown".equals(r.stopped) && folder.puts == blindPuts && share.isOn("house"), "connected with no id and no name is unknown: paused, switches stay: " + r.stopped);
            // A journal row plus an unknown partner: the leftover is removed, nothing is written.
            share.journal("ghost-doc", "Ghost [ghost000].pdf"); folder.put("Ghost [ghost000].pdf", java.io.File.createTempFile("ghost", ".pdf", iso.getCacheDir()));
            db.execSQL("DELETE FROM scan_share_files"); int ghostPuts = folder.puts;
            share.partnerStateLookup = () -> null; r = share.run();
            require(r.written == 0 && folder.puts == ghostPuts && share.journalRows().isEmpty() && !folder.names().contains("Ghost [ghost000].pdf") && "partner_unknown".equals(r.stopped),
                "journal row and unknown partner: 0 written, the leftover removed: " + r.written + " " + folder.names());
            share.partnerLookup = () -> ""; share.partnerIdLookup = () -> "";
            share.partnerStateLookup = null; store.setState(scanSource.id, SourceStore.OK, "");
            // Replace in place: old copies are removed from the old folder, rows cleared so everything is written into the new one.
            DiskFolder newFolder = new DiskFolder(new File(iso.getCacheDir(), "scan-folder-" + UUID.randomUUID()));
            Uri newTree = Uri.parse(tree("filen:" + UUID.randomUUID()));
            ScanShare moving = new ScanShare(iso, store, src -> src.treeUri.equals(newTree.toString()) ? newFolder : folder, (id, out) -> out.write(("PDF " + id).getBytes("UTF-8")), () -> true);
            moving.grantOk = src -> true;
            moving.partnerLookup = () -> "";
            moving.setOn("house", true);
            r = moving.run();
            require(r.complete && folder.names().size() == 7, "written into the old folder first: " + folder.names());
            newFolder.account = "acct-9";
            SourceStore.Source replaced = moving.setFolder(newTree, "Shared scans", "Sam");
            require(replaced.id.equals(scanSource.id) && replaced.treeUri.equals(newTree.toString()) && "acct-9".equals(replaced.accountId), "the folder is replaced in place and its account recorded: " + replaced.accountId);
            require(folder.names().equals(java.util.Arrays.asList("Odd [zzzzzzzz].pdf", "Old trip [abcd1234].pdf", "Taxes [20240415].pdf", "notes.pdf")), "the old folder's shared copies were removed, the user's files stay: " + folder.names());
            r = moving.run();
            require(r.complete && newFolder.names().size() == 3, "every scan is written again into the new folder: " + newFolder.names());
            // Old folder unreachable at the moment of replacing: remembered, removed later.
            DiskFolder third = new DiskFolder(new File(iso.getCacheDir(), "scan-folder-" + UUID.randomUUID()));
            newFolder.offline = true;
            Uri thirdTree = Uri.parse(tree("filen:" + UUID.randomUUID()));
            ScanShare again = new ScanShare(iso, store, src -> src.treeUri.equals(thirdTree.toString()) ? third : newFolder, (id, out) -> out.write(1), () -> true);
            again.grantOk = src -> true;
            again.setFolder(thirdTree, "Shared scans", "Sam");
            require(again.removalPending() && again.oldTrees().containsKey(newTree.toString()) && again.oldTrees().get(newTree.toString()).size() == 3, "an unreachable old folder is remembered with its file names");
            newFolder.offline = false; again.run();
            require(!again.removalPending() && newFolder.names().isEmpty(), "and removed when reachable");
            // Erase library: copies out of the folder, caches gone; an unreachable folder is kept as a pending record that survives.
            db.execSQL("DELETE FROM scan_share_files");
            db.execSQL("INSERT INTO scan_share_files(document_id,file_name,signature,written_at) VALUES('x','Doc 1 [b2222222].pdf','s',1)");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(third.dir, "Doc 1 [b2222222].pdf"))) { out.write(1); }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(third.dir, "Stray [01234567].pdf"))) { out.write(1); }
            File reading = new File(iso.getCacheDir(), "source-read"), viewing = new File(iso.getCacheDir(), "source-view"), sharing = new File(iso.getCacheDir(), "scan-share");
            for (File dir : new File[]{reading, viewing, sharing}) { dir.mkdirs(); try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(dir, "x"))) { out.write(1); } }
            try (Cursor c = helper.getWritableDatabase().rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) { c.moveToFirst(); }
            File dbFile = iso.getDatabasePath(dbName);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(third.dir, "Taxes [20240415].pdf"))) { out.write(1); }
            // Step one of an erase only LOOKS: nothing is deleted and no grant is given back (the library may still fail to move aside and be restored).
            java.util.List<Uri> released = new java.util.concurrent.CopyOnWriteArrayList<>();
            SourceStore.releaseProbe = released::add;
            try {
                SourcesErase.snapshot(iso, dbFile);
                require(SourcesErase.hasSnapshot(iso) && new File(third.dir, "Doc 1 [b2222222].pdf").exists() && released.isEmpty() && new File(reading, "x").exists(), "the snapshot deletes nothing and releases nothing");
                SourcesErase.discard(iso);
                require(!SourcesErase.hasSnapshot(iso) && new File(third.dir, "Doc 1 [b2222222].pdf").exists(), "a rolled-back reset drops the snapshot: nothing happened");
                third.offline = true;
                SourcesErase.run(iso, dbFile, t -> third);
            require(new File(third.dir, "Doc 1 [b2222222].pdf").exists() && share.oldTrees().containsKey(store.get(scanSource.id).treeUri) && !new File(reading, "x").exists() && !new File(viewing, "x").exists() && !new File(sharing, "x").exists(),
                    "erase while Filen is away: a pending record survives and the caches are gone");
                third.offline = false;
                SourcesErase.run(iso, dbFile, t -> third);
                require(third.names().equals(java.util.Arrays.asList("Doc 0 [a1111111].pdf", "Doc 2 [c3333333].pdf", "Stray [01234567].pdf", "Taxes [20240415].pdf")), "erase while reachable removes exactly the recorded copy (Doc 1); files with Stack's naming but no row of Stack's own survive: " + third.names());
                require(released.contains(Uri.parse(store.get(scanSource.id).treeUri)), "after a successful removal the folder's grant is given back (through the one central rule)");
            } finally { SourceStore.releaseProbe = null; }
            target.getSharedPreferences("scan_share_old", Context.MODE_PRIVATE).edit().clear().commit();
        } finally { helper.close(); target.getSharedPreferences("scan_share_old", Context.MODE_PRIVATE).edit().clear().commit(); }
        require(ScanShare.confirmText(3, "Sam").equals("Share 3 scans with Sam? They'll be copied to Shared scans in your Filen.") && ScanShare.confirmText(1, null).startsWith("Share 1 scan with your partner?"), "the confirm words");
        // Opening: never the partner's MIME to another app; Ami Pro only when pinned.
        require(SourceActions.viewIntent(target, Uri.parse("content://x/y"), null) == null && SourceActions.viewIntent(target, Uri.parse("content://x/y"), org.stackdocs.app.types.FileTypes.byId("pdf")) == null, "an unknown type, and a Stack-viewer type, are not handed to another app");
        android.content.Intent view = SourceActions.viewIntent(target, Uri.parse("content://x/y"), org.stackdocs.app.types.FileTypes.byId("amipro"));
        boolean pinned = LinkCallers.amiProInstalledAndPinned(target);
        require(pinned ? view != null && "app.amipro.editor".equals(view.getPackage()) && "application/octet-stream".equals(view.getType()) : view == null, "Ami Pro is opened only when its certificate matches the pin (pinned here: " + pinned + ")");
        mark.accept("Scan sharing safety: switch off, delete, lock and cancel during a run write nothing more; nothing is deleted by its look (user files named like ours survive reconcile and erase), the upload journal finds orphans after a failed take-down or a crash; offline removal is pending then done; another account pauses, another partner turns every switch off and old copies are still removed; replace in place and old folder cleanup; erase library; confirm words; open rules (Ami Pro pinned: " + pinned + ")");
    }

    // ---- 5c. one rule for giving a grant back; leftovers; Filen's stop result (security review) ----------------------------------------------------

    private static void releaseRules(Context target, Consumer<String> mark) throws Exception {
        Context iso = new ScannerChecks.IsolatedContext(target);
        StackApplication app = (StackApplication) target.getApplicationContext();
        String dbName = "release-" + UUID.randomUUID() + ".db";
        LibraryDatabase helper = new LibraryDatabase(iso, dbName);
        android.content.SharedPreferences backup = AutoBackup.prefs(target);
        String savedBackup = backup.getString("tree", "");
        android.content.SharedPreferences old = target.getSharedPreferences("scan_share_old", Context.MODE_PRIVATE);
        old.edit().clear().commit();
        List<Uri> released = new java.util.concurrent.CopyOnWriteArrayList<>();
        SourceStore.releaseProbe = released::add;
        try {
            SourceStore store = new SourceStore(iso, helper::getWritableDatabase, new File(iso.getFilesDir(), "sources"));
            store.heldOverride = t -> true;
            Uri free = Uri.parse(tree("filen:" + UUID.randomUUID()));
            store.releaseUnlessUsed(free);
            require(released.contains(free), "a tree nobody uses is released");
            released.clear();
            SourceStore.Source used = seed(store, SourceStore.OWN, "filen:" + UUID.randomUUID(), "Bills", "");
            store.releaseUnlessUsed(used.tree());
            require(released.isEmpty(), "a tree a source holds is not released");
            // The backup folder's tree: kept by every release site.
            SourceStore.Source other = seed(store, SourceStore.OWN, "filen:" + UUID.randomUUID(), "Backups", "");
            backup.edit().putString("tree", other.treeUri).commit();
            store.remove(other.id);
            require(released.isEmpty(), "removing a source whose tree is the automatic backup folder keeps the grant");
            store.releaseUnlessUsed(Uri.parse(other.treeUri));
            require(released.isEmpty(), "the central rule keeps the backup tree");
            // Shared copies still to remove: kept.
            Uri pending = Uri.parse(tree("filen:" + UUID.randomUUID()));
            ScanShare.rememberOld(target, pending.toString(), java.util.Collections.singletonList("Doc [11111111].pdf"));
            store.releaseUnlessUsed(pending);
            require(released.isEmpty(), "a folder with copies still to remove keeps its grant");
            old.edit().clear().commit();
            // A source removed with no other user: released, once.
            SourceStore.Source plain = seed(store, SourceStore.OWN, "filen:" + UUID.randomUUID(), "Plain", "");
            backup.edit().remove("tree").commit();
            store.remove(plain.id);
            require(released.size() == 1 && released.get(0).toString().equals(plain.treeUri), "a removed source's grant is released");
            released.clear();
            // Re-picking a source's folder gives the old tree back unless something else holds it.
            SourceStore.Source moved = seed(store, SourceStore.OWN, "filen:" + UUID.randomUUID(), "Moved", "");
            backup.edit().putString("tree", moved.treeUri).commit();
            Uri repicked = Uri.parse(moved.treeUri + "#again");
            store.heldOverride = t -> true;
            try { store.replaceTree(moved.id, repicked); } catch (IOException ignored) { /* the check only needs the release decision */ }
            require(released.isEmpty(), "replaceTree keeps an old tree that is the backup folder");
            backup.edit().remove("tree").commit();
            // The backup turned off: its grant goes unless a folder source holds the tree (the application's own sources decide).
            app.library.sources.heldOverride = t -> true;
            SourceStore.Source held = seed(app.library.sources, SourceStore.OWN, "filen:" + UUID.randomUUID(), "Held by a source", "");
            backup.edit().putString("tree", held.treeUri).commit();
            released.clear();
            AutoBackup.disable(target);
            require(released.isEmpty() && AutoBackup.tree(target) == null, "turning the backup off keeps a grant a folder source holds");
            Uri alone = Uri.parse(tree("filen:" + UUID.randomUUID()));
            backup.edit().putString("tree", alone.toString()).commit();
            AutoBackup.disable(target);
            require(released.contains(alone), "turning the backup off releases a tree nobody else uses");
            app.library.sources.remove(held.id);
            // QR retirement: the old archive is deleted before the grant goes; when that is not possible the one-time note is set.
            QrRetirement.takeNote(target);
            DiskFolder shared = new DiskFolder(new File(iso.getCacheDir(), "qr-folder-" + UUID.randomUUID()));
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(shared.dir, "Stack shared.stackshare"))) { out.write(1); }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(shared.dir, "Taxes [20240415].pdf"))) { out.write(1); }
            Uri qrTree = Uri.parse(tree("filen:" + UUID.randomUUID()));
            released.clear();
            target.getSharedPreferences("partner_share", Context.MODE_PRIVATE).edit().putString("tree", qrTree.toString()).commit();
            QrRetirement.releaseFolders(target, store, t -> shared);
            require(shared.names().equals(java.util.Collections.singletonList("Taxes [20240415].pdf")) && released.contains(qrTree) && QrRetirement.takeNote(target).isEmpty(), "the old archive is deleted, nothing else is, the grant is released: " + shared.names());
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(shared.dir, "Stack shared.stackshare"))) { out.write(1); }
            shared.offline = true; released.clear();
            target.getSharedPreferences("partner_share", Context.MODE_PRIVATE).edit().putString("tree", qrTree.toString()).commit();
            QrRetirement.releaseFolders(target, store, t -> shared);
            require(new File(shared.dir, "Stack shared.stackshare").exists() && released.contains(qrTree), "the folder is away: the archive stays, the grant is released");
            require(QrRetirement.OLD_ARCHIVE_NOTE.equals(QrRetirement.takeNote(target)) && QrRetirement.takeNote(target).isEmpty() && QrRetirement.OLD_ARCHIVE_NOTE.equals("Your old shared archive is still in the folder you shared before. Delete it in Filen if you no longer want your partner to see it."), "the one-time note is shown once");
            // The scan-share cache is swept at app start.
            File sharing = new File(iso.getCacheDir(), "scan-share"); sharing.mkdirs();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(sharing, "left.pdf"))) { out.write(1); }
            ScanShare.sweepCache(iso);
            require(!new File(sharing, "left.pdf").exists(), "a PDF a run left in the cache is deleted at start");
            // Filen's stop result when the item was shared through a folder.
            android.content.Intent via = new android.content.Intent().putExtra(FilenPartner.EXTRA_SHARED, false).putExtra(FilenPartner.EXTRA_VIA_FOLDER, "Taxes 2026");
            require("Stopped sharing the folder \u201cTaxes 2026\u201d".equals(FilenPartner.stoppedViaFolder(Activity.RESULT_OK, via)), "the stop-through-a-folder words: " + FilenPartner.stoppedViaFolder(Activity.RESULT_OK, via));
            require(FilenPartner.stoppedViaFolder(Activity.RESULT_OK, new android.content.Intent().putExtra(FilenPartner.EXTRA_SHARED, false)).isEmpty() && FilenPartner.stoppedViaFolder(Activity.RESULT_OK, new android.content.Intent().putExtra(FilenPartner.EXTRA_SHARED, true).putExtra(FilenPartner.EXTRA_VIA_FOLDER, "X")).isEmpty()
                    && FilenPartner.stoppedViaFolder(Activity.RESULT_CANCELED, via).isEmpty(), "no folder named, still shared, or cancelled: no note");
        } finally {
            SourceStore.releaseProbe = null; app.library.sources.heldOverride = null;
            if (savedBackup.isEmpty()) backup.edit().remove("tree").commit(); else backup.edit().putString("tree", savedBackup).commit();
            old.edit().clear().commit();
            target.getSharedPreferences("partner_share", Context.MODE_PRIVATE).edit().clear().commit();
            helper.close(); iso.deleteDatabase(dbName);
        }
        mark.accept("Grant release: one central rule (backup tree, pending copies and sources keep it) used by source removal, re-pick, backup off, QR retirement and erase; QR retirement deletes the old archive or sets the one-time note; scan-share cache swept; Filen's stop-through-a-folder words");
    }

    // ---- 6. retiring the QR sharing ------------------------------------------------------------------------------------------------

    private static void qrRetirement(Context iso, Consumer<String> mark) throws Exception {
        String name = "retire-" + UUID.randomUUID() + ".db";
        LibraryDatabase first = new LibraryDatabase(iso, name);
        try {
            SQLiteDatabase db = first.getWritableDatabase();
            for (String[] r : new String[][]{{"own1", "own"}, {"for1", "foreign"}, {"for2", "foreign"}}) {
                ContentValues v = new ContentValues();
                v.put("id", r[0]); v.put("owner", "app.amipro.editor"); v.put("link_id", "link-" + r[0]); v.put("kind", "doc"); v.put("title", "T " + r[0]); v.put("mime", "text/plain");
                v.put("modified", 1); v.put("hash", "h"); v.put("locators", "[]"); v.put("state", "live"); v.put("added_at", 1); v.put("received_at", 1); v.put("revision", 1);
                v.put("origin", r[1]); v.put("shared_by", "foreign".equals(r[1]) ? "Sam" : ""); v.put("partner_share", "own".equals(r[1]) ? 1 : 0);
                db.insertOrThrow("link_records", null, v);
                db.execSQL("INSERT INTO link_search(record_id,title,body) VALUES(?,?,?)", new Object[]{r[0], "T " + r[0], "words " + r[0]});
                db.execSQL("INSERT INTO item_stacks(item_kind,item_id,stack_id) VALUES('link',?,'bills')", new Object[]{r[0]});
            }
            db.execSQL("INSERT INTO partner_share_log(owner,link_id,state,since) VALUES('o','l','shared',1)");
            db.execSQL("DROP TABLE scan_share_stacks"); db.execSQL("DROP TABLE scan_share_files");
            db.setVersion(18);
            try (Cursor c = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) { c.moveToFirst(); }
        } finally { first.close(); }
        LibraryDatabase second = new LibraryDatabase(iso, name);
        try {
            SQLiteDatabase db = second.getWritableDatabase();
            require(db.getVersion() == LibraryDatabase.VERSION, "a version 18 library upgrades");
            require(count(db, "SELECT COUNT(*) FROM link_records WHERE origin='foreign'") == 0, "foreign rows are gone");
            require(count(db, "SELECT COUNT(*) FROM link_search WHERE record_id LIKE 'for%'") == 0, "their text is gone");
            require(count(db, "SELECT COUNT(*) FROM item_stacks WHERE item_kind='link' AND item_id LIKE 'for%'") == 0, "their stacks are gone");
            require(count(db, "SELECT COUNT(*) FROM link_records WHERE id='own1' AND title='T own1' AND origin='own'") == 1, "own rows are intact");
            require(count(db, "SELECT COUNT(*) FROM link_search WHERE record_id='own1'") == 1 && count(db, "SELECT COUNT(*) FROM item_stacks WHERE item_id='own1'") == 1, "own text and stacks are intact");
            require(count(db, "SELECT COUNT(*) FROM link_records WHERE partner_share<>0") == 0 && count(db, "SELECT COUNT(*) FROM partner_share_log") == 0, "the per-record switch and the log are cleared");
            require(count(db, "SELECT COUNT(*) FROM sqlite_master WHERE name IN ('scan_share_stacks','scan_share_files')") == 2, "the scan-share tables exist");
        } finally { second.close(); }
        // Keys: a sealed file and a Keystore key as the old code made them.
        String alias = QrRetirement.ALIAS_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
        javax.crypto.KeyGenerator generator = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new android.security.keystore.KeyGenParameterSpec.Builder(alias, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes("GCM").setEncryptionPaddings("NoPadding").build());
        generator.generateKey();
        File keyFile = new File(iso.getFilesDir(), "partner_keys.bin");
        try (FileOutputStream out = new FileOutputStream(keyFile)) { out.write(new byte[]{1, 2, 3}); }
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        require(store.containsAlias(alias) && keyFile.isFile(), "the old key exists before");
        int removed = QrRetirement.deleteKeys(iso);
        store.load(null);
        require(removed >= 1 && !store.containsAlias(alias) && !keyFile.exists(), "the Keystore key and the sealed file are deleted");
        require(QrRetirement.deleteKeys(iso) == 0, "running it again is harmless");
        // The retirement never releases a grant that a folder source holds, and clears the partner journey preferences.
        String sourceDb = "retire-sources-" + UUID.randomUUID() + ".db";
        LibraryDatabase sourceHelper = new LibraryDatabase(iso, sourceDb);
        try {
            SourceStore sources = new SourceStore(iso, sourceHelper::getWritableDatabase, new File(iso.getFilesDir(), "sources"));
            SourceStore.Source used = seed(sources, SourceStore.OWN, "filen:" + UUID.randomUUID(), "Bills", "");
            iso.getSharedPreferences("partner_share", Context.MODE_PRIVATE).edit().putString("tree", used.treeUri).commit();
            iso.getSharedPreferences("partner_import", Context.MODE_PRIVATE).edit().putString("tree", tree("filen:" + UUID.randomUUID())).commit();
            iso.getSharedPreferences("partner_journey", Context.MODE_PRIVATE).edit().putString("step", "x").commit();
            List<Uri> released = QrRetirement.releaseFolders(iso, sources);
            require(released.size() == 1 && !released.contains(Uri.parse(used.treeUri)), "a grant that is a source's tree is not released");
            QrRetirement.run(iso, null, sources);
            require(iso.getSharedPreferences("partner_journey", Context.MODE_PRIVATE).getAll().isEmpty(), "the partner journey preferences are cleared");
            require(QrRetirement.run(iso, null, sources) >= 0, "the key deletion reports success (the done marker is set only then)");
        } finally { sourceHelper.close(); }
        mark.accept("QR retirement: version 18 library with foreign rows upgrades to " + LibraryDatabase.VERSION + "; foreign rows, text and stacks gone; own rows intact; partner Keystore key and sealed file deleted; second run harmless");
    }

    private static int count(SQLiteDatabase db, String sql) { try (Cursor c = db.rawQuery(sql, null)) { c.moveToFirst(); return c.getInt(0); } }
}
