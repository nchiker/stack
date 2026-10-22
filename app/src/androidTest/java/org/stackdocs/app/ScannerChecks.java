package org.stackdocs.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import androidx.exifinterface.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Platform-only checks; real-library UI/camera flows require an explicitly disposable target. */
public final class ScannerChecks extends Instrumentation {
    private static boolean disposableCanonical;
    static boolean disposableLibraryTarget(Context context) {
        return checksTarget(context)||(disposableCanonical&&context.getPackageName().equals("org.stackdocs.app"));
    }
    static boolean checksTarget(Context context) {
        String name=context.getPackageName();
        return name.equals("org.stackdocs.app.checks")||name.matches("org\\.stackdocs\\.app\\.checks\\.[a-z][a-z0-9_]{0,31}");
    }
    private int passed;
    private String restartKey;
    private String suggestionCorpusAsset;
    private String p1Restart="",p1Boundary="",stackRestart="";
    private boolean p1Only,p1Corpus,p1Ui,p1Batch,p1Performance,googleMigration,stackEditor,lockUi,convenience;
    private boolean backupLayout,navigationOnly,backupUi,archivePerformance,archiveOnly,archivePrepare,archiveResume;
    private String archiveBoundary;
    private Bundle syncArguments;
    private boolean longWorkflow,workflowOnly,providerWorkflow,workflowUi,namingOnly;
    private boolean suggestionPrepare,suggestionResume, suggestionUi, suggestionCorpus, suggestionsOnly, baseline, qualityOnly, ocrOnly, preserveOnly, restartPrepare, restartResume, corpusOnly;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        disposableCanonical=arguments!=null&&"0A121JEC203530".equals(arguments.getString("disposableDevice"))
                &&"sunfish".equals(android.os.Build.DEVICE)&&"Pixel 4a".equals(android.os.Build.MODEL);
        archivePrepare=arguments!=null && "prepare".equals(arguments.getString("archiveRestart"));
        archiveResume=arguments!=null && "resume".equals(arguments.getString("archiveRestart"));
        archiveBoundary=arguments==null?"file":arguments.getString("archiveBoundary","file");
        syncArguments=arguments==null?new Bundle():new Bundle(arguments);
        if(!java.util.Arrays.asList("staged","journal","file","transaction","committed").contains(archiveBoundary))throw new IllegalArgumentException("Unknown boundary");
        backupLayout=arguments!=null && "true".equals(arguments.getString("backupLayout"));
        navigationOnly=arguments!=null && "true".equals(arguments.getString("navigationOnly"));
        backupUi=arguments!=null && "true".equals(arguments.getString("backupUi"));
        archivePerformance=arguments!=null && "true".equals(arguments.getString("archivePerformance"));
        archiveOnly=arguments!=null && "true".equals(arguments.getString("archiveOnly"));
        longWorkflow=arguments!=null && "true".equals(arguments.getString("longWorkflow"));
        workflowOnly=arguments!=null && "true".equals(arguments.getString("workflowOnly"));
        providerWorkflow=arguments!=null && "true".equals(arguments.getString("providerWorkflow"));
        workflowUi=arguments!=null && "true".equals(arguments.getString("workflowUi"));
        namingOnly=arguments!=null && "true".equals(arguments.getString("namingOnly"));
        suggestionCorpusAsset=arguments==null ? "suggestions-corpus.json" : arguments.getString("suggestionCorpusAsset","suggestions-corpus.json");
        if(!suggestionCorpusAsset.equals("suggestions-corpus.json") && !suggestionCorpusAsset.equals("naming-followup-corpus.json") && !suggestionCorpusAsset.equals("p0-final-corpus.json")) throw new IllegalArgumentException("Unknown authored corpus");
        suggestionPrepare=arguments != null && "prepare".equals(arguments.getString("suggestionRestart"));
        suggestionResume=arguments != null && "resume".equals(arguments.getString("suggestionRestart"));
        suggestionUi=arguments != null && "true".equals(arguments.getString("suggestionUi"));
        suggestionCorpus=arguments != null && "true".equals(arguments.getString("suggestionCorpus"));
        suggestionsOnly=arguments != null && "true".equals(arguments.getString("suggestionsOnly"));
        baseline = arguments != null && "true".equals(arguments.getString("baseline"));
        qualityOnly = arguments != null && "true".equals(arguments.getString("qualityOnly"));
        ocrOnly = arguments != null && "true".equals(arguments.getString("ocrOnly"));
        preserveOnly = arguments != null && "true".equals(arguments.getString("preserveOnly"));
        restartPrepare = arguments != null && "prepare".equals(arguments.getString("ocrRestart"));
        restartResume = arguments != null && "resume".equals(arguments.getString("ocrRestart"));
        corpusOnly = arguments != null && "true".equals(arguments.getString("ocrCorpus"));
        restartKey = arguments == null ? "default" : arguments.getString("ocrRestartKey","default");
        p1Restart=arguments==null?"":arguments.getString("p1Restart","");p1Boundary=arguments==null?"":arguments.getString("p1Boundary","");
        p1Performance=arguments!=null&&"true".equals(arguments.getString("p1Performance"));
        p1Only=arguments!=null&&"true".equals(arguments.getString("p1Only"));
        p1Corpus=arguments!=null&&"true".equals(arguments.getString("p1Corpus"));
        p1Ui=arguments!=null&&"true".equals(arguments.getString("p1Ui"));
        p1Batch=arguments!=null&&"true".equals(arguments.getString("p1Batch"));
        googleMigration=arguments!=null&&"true".equals(arguments.getString("googleMigration"));
        stackRestart=arguments==null?"":arguments.getString("stackRestart","");
        stackEditor=arguments!=null&&"true".equals(arguments.getString("stackEditor"));
        lockUi=arguments!=null&&"true".equals(arguments.getString("lockUi"));
        convenience=arguments!=null&&"true".equals(arguments.getString("convenience"));
        start();
    }

    @Override public Activity newActivity(ClassLoader loader,String name,android.content.Intent intent) throws InstantiationException,IllegalAccessException,ClassNotFoundException {
        Activity activity=(lockUi||p1Ui)&&getTargetContext().getPackageName().equals("org.stackdocs.app.checks")&&name.equals(LockActivity.class.getName())
                ?new LockUiChecks.Screen():super.newActivity(loader,name,intent);
        if(P1UiChecks.configuration!=null&&getTargetContext().getPackageName().equals("org.stackdocs.app.checks"))activity.applyOverrideConfiguration(P1UiChecks.configuration);
        return activity;
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!getTargetContext().getPackageName().equals("org.stackdocs.app") && !checksTarget(getTargetContext())) {
                throw new SecurityException("Checks require the canonical Stack installation");
            }
            StackApplication app = (StackApplication) getTargetContext().getApplicationContext();
            if(disposableCanonical||((p1Batch||p1Ui||stackEditor||lockUi||convenience||backupUi||backupLayout)&&getTargetContext().getPackageName().equals("org.stackdocs.app.checks"))) {
                runOnMainSync(() -> app.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks(){
                    @Override public void onActivityCreated(Activity activity,Bundle state){activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);}
                    public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
                }));
            }
            if(syncArguments.containsKey("disposableSmoke")) {
                if(!disposableCanonical||!getTargetContext().getPackageName().equals("org.stackdocs.app"))throw new SecurityException("Explicit disposable Pixel 4a authorization required");
                DisposableSmokeChecks.run(this,syncArguments.getString("disposableSmoke"),this::mark);
            }
            else if (syncArguments.containsKey("nearbyPhase")) mark("nearby "+syncArguments.getString("nearbyPhase")+": "+NearbyPairingChecks.run(this,syncArguments));
            else if ("true".equals(syncArguments.getString("resetUi"))) LibraryResetChecks.runUi(this,this::mark);
            else if ("true".equals(syncArguments.getString("libraryReset"))) LibraryResetChecks.run(this,this::mark);
            else if ("true".equals(syncArguments.getString("syncLargeState"))) SyncLargeStateChecks.run(this,this::mark);
            else if ("true".equals(syncArguments.getString("syncLabelsOnly"))) SyncAutoChecks.inspectLabels(this,this::mark);
            else if ("true".equals(syncArguments.getString("syncAuto"))) SyncAutoChecks.run(this,this::mark);
            else if ("true".equals(syncArguments.getString("simpleSyncPrepare"))) SyncAutoChecks.prepare(this,this::mark);
            else if ("true".equals(syncArguments.getString("syncUi"))) SyncUiChecks.run(this,this::mark);
            else if ("migrations".equals(syncArguments.getString("syncPhase"))) {
                if(!checksTarget(getTargetContext()))throw new SecurityException("Disposable Checks installation required");
                for(int version=1;version<=8;version++)migration(new IsolatedContext(getTargetContext()),version);
            }
            else if (syncArguments.containsKey("syncPhase")) {syncArguments.putString("phase",syncArguments.getString("syncPhase"));mark("sync "+syncArguments.getString("phase")+": "+SyncDeviceChecks.run(this,syncArguments));}
            else if (syncArguments.containsKey("transportPhase")) mark("transport "+syncArguments.getString("transportPhase")+": "+PairingTransportChecks.run(this,syncArguments));
            else if (preserveOnly) mark(PreservationChecks.fingerprint(getTargetContext()));
            else if(!p1Restart.isEmpty())P1RestartChecks.run(this,p1Restart,p1Boundary,restartKey,this::mark);
            else if(lockUi)LockUiChecks.run(this,this::mark);
            else if(convenience)ConvenienceChecks.run(this,this::mark);
            else if(!stackRestart.isEmpty())StackEditorChecks.restart(this,stackRestart,this::mark);
            else if(stackEditor) {for(int version=1;version<=7;version++)migration(new IsolatedContext(getTargetContext()),version);StackEditorChecks.run(this,this::mark);}
            else if(googleMigration)GoogleMigrationChecks.run(this,this::mark);
            else if(p1Performance)P1PerformanceChecks.run(this,this::mark);
            else if(p1Only)P1Checks.run(this,this::mark);
            else if(p1Corpus)P1CorpusChecks.run(this,this::mark);
            else if(p1Ui)P1UiChecks.run(this,this::mark);
            else if(p1Batch)P1BatchChecks.run(this,this::mark);
            else if(archivePrepare||archiveResume) ArchiveRestartChecks.run(new IsolatedContext(getTargetContext(),"archive-restart-"+restartKey),archivePrepare,archiveBoundary,this::mark);
            else if(backupLayout) BackupLayoutChecks.run(this,new IsolatedContext(getTargetContext(),"backup-layout"),this::mark);
            else if(navigationOnly) NavigationChecks.run(this,this::mark);
            else if(backupUi) BackupUiChecks.run(this,this::mark);
            else if(archivePerformance) ArchivePerformanceChecks.run(new IsolatedContext(getTargetContext()),this::mark);
            else if(archiveOnly) {ArchiveChecks.run(new IsolatedContext(getTargetContext()),this::mark);PdfExportChecks.run(new IsolatedContext(getTargetContext()),this::mark);}
            else if(longWorkflow) LongDocumentChecks.run(new IsolatedContext(getTargetContext()),this::mark);
            else if(workflowOnly) WorkflowRepositoryChecks.run(new IsolatedContext(getTargetContext()),this::mark);
            else if(providerWorkflow) ProviderWorkflowChecks.run(this,new IsolatedContext(getTargetContext()),this::mark);
            else if(workflowUi) WorkflowUiChecks.run(this,new IsolatedContext(getTargetContext()),this::mark);
            else if(namingOnly) NamingChecks.run(this::mark);
            else if (restartPrepare || restartResume) OcrRestartChecks.run(new IsolatedContext(getTargetContext(),"ocr-restart-"+restartKey),restartPrepare,this::mark);
            else if (corpusOnly) OcrCorpusChecks.run(new IsolatedContext(getTargetContext()),this::mark);
            else if(suggestionPrepare || suggestionResume) SuggestionRestartChecks.run(new IsolatedContext(getTargetContext(),"suggestion-restart-"+restartKey),suggestionPrepare,this::mark);
            else if(suggestionUi) SuggestionUiChecks.run(this,new IsolatedContext(getTargetContext()),this::mark);
            else if(suggestionCorpus) SuggestionCorpusChecks.run(new IsolatedContext(getTargetContext()),getContext(),suggestionCorpusAsset,this::mark);
            else if (suggestionsOnly) SuggestionChecks.run(new IsolatedContext(getTargetContext()),this::mark);
            else if (ocrOnly) OcrChecks.run(new IsolatedContext(getTargetContext()), this::mark);
            else app.io.submit(() -> {
                try {
                    Context isolated = new IsolatedContext(getTargetContext());
                    if (!qualityOnly) checks(isolated);
                    ScannerQualityChecks.run(isolated, baseline, this::mark);
                }
                catch (Exception error) { throw new RuntimeException(error); }
            }).get(120, TimeUnit.SECONDS);
            // Default fixtures stay isolated; explicitly disposable UI/camera flows
            // may exercise their opted-in real library and a physical camera scene.
            result.putString("stream", "\nPASS: " + passed + " scanner check groups.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL after " + passed + " groups: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void checks(Context context) throws Exception {
        geometry();
        migration(new IsolatedContext(context), 1);
        migration(new IsolatedContext(context), 2);
        migration(new IsolatedContext(context), 3);
        migration(new IsolatedContext(context), 4);
        migration(new IsolatedContext(context), 5);
        migration(new IsolatedContext(context), 6);
        migration(new IsolatedContext(context), 7);
        rendering(context);
        LibraryRepository library = new LibraryRepository(context);
        library.initialize();
        check(library.documents("", null).isEmpty(), "Isolated library must start empty");

        Bitmap fixture = fixture(Color.WHITE);
        float[] edges = BoundaryDetector.suggest(fixture);
        check(edges != null && edges[0] > .10f && edges[0] < .30f && edges[2] > .70f, "Bright-page boundary proposal");
        Bitmap blank = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
        blank.eraseColor(Color.WHITE);
        check(BoundaryDetector.suggest(blank) == null, "Whole-frame fallback on blank image");
        blank.recycle();
        mark("Boundary proposal and whole-frame fallback");

        byte[] jpeg = jpeg(fixture);
        fixture.recycle();
        ScanDraftStore store = new ScanDraftStore(context);
        ScanDraftStore.Draft draft = store.append(jpeg);
        String firstId = draft.pages.get(0).id;
        File legacyManifest = new File(store.directory, "draft.json");
        org.json.JSONObject legacy = new org.json.JSONObject(new String(bytes(legacyManifest), java.nio.charset.StandardCharsets.UTF_8));
        legacy.getJSONArray("pages").getJSONObject(0).remove("flatten");
        try (FileOutputStream output = new FileOutputStream(legacyManifest)) {
            output.write(legacy.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        check(!new ScanDraftStore(context).load().pages.get(0).flatten, "Legacy draft without flatten retains all existing settings");
        draft = store.append(jpeg);
        String secondId = draft.pages.get(1).id;
        draft = store.append(jpeg);
        draft = store.remove(1);
        check(draft.pages.size() == 2 && draft.pages.get(0).id.equals(firstId)
                && !draft.pages.get(1).id.equals(secondId), "Removing a middle draft page preserves order");
        float[] crop = new float[]{.2f, .2f, .8f, .2f, .8f, .8f, .2f, .8f};
        draft = store.edit(0, draft.pages.get(0).edited(90, crop, "bright").flattened(true));
        ScanDraftStore.Draft reloaded = new ScanDraftStore(context).load();
        check(reloaded.id.equals(draft.id) && reloaded.pages.get(0).rotation == 90
                && reloaded.pages.get(0).enhancement.equals("bright") && reloaded.pages.get(0).flatten, "Draft survives store recreation");
        check(Arrays.equals(jpeg, bytes(store.file(reloaded.pages.get(0)))), "Draft original bytes unchanged");
        mark("Durable multipage draft, removal, edits and original preservation");

        Document scan = library.saveScan(store, reloaded, "Synthetic receipt alpha");
        Document retry = library.saveScan(store, reloaded, "Synthetic retry");
        check(scan.id.equals(retry.id) && library.documents("", null).size() == 1, "Saving one draft twice is idempotent");
        check(library.pages(scan.id).get(0).flatten, "Flatten choice survives saving a scan");
        store.discard();
        check(new ScanDraftStore(context).load().pages.isEmpty(), "Draft cleared after save");
        File keptOriginal = new File(new File(new File(context.getFilesDir(), "documents"), scan.id), firstId + ".jpg");
        check(Arrays.equals(jpeg, bytes(keptOriginal)), "Saved original retained after clearing draft");
        File exported = new File(context.getCacheDir(), "scan.pdf");
        export(library, scan, exported);
        check(pageCount(exported) == 2, "Scan exports as two PDF pages");
        Bitmap rendered = library.renderPage(scan, 0, 1200);
        check(rendered.getWidth() > rendered.getHeight(), "Cropped portrait page rotated to landscape");
        rendered.recycle();
        mark("Idempotent scan commit, independent originals and multipage PDF export");

        library.setMemberships(scan.id, Arrays.asList("bills", "receipts"));
        library.initialize();
        check(library.document(scan.id).stackIds.size() == 2, "Memberships survive recovery");
        check(library.documents("synt rec", null).size() == 1, "Multiword FTS4 prefix search");
        check(library.documents("synt-rec", "bills").size() == 1, "Punctuation and stack filter");
        check(library.documents("\" OR *", null).isEmpty(), "Query punctuation cannot inject an operator");
        library.removePage(scan.id, 0);
        scan = library.document(scan.id);
        check(scan.pageCount == 1 && library.pages(scan.id).get(0).id.equals(reloaded.pages.get(1).id), "Saved page removal and order");
        boolean rejected = false;
        try { library.removePage(scan.id, 0); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Cannot remove the last document page");
        check(Arrays.equals(jpeg, bytes(keptOriginal)), "Removed page original retained");
        mark("FTS4 regression, memberships, recovery and safe page removal");

        File originalPdf = new File(context.getCacheDir(), "original.pdf");
        pdfFixture(originalPdf);
        Document pdf = library.importDocument(Uri.fromFile(originalPdf));
        File pdfOutput = new File(context.getCacheDir(), "export.pdf");
        export(library, pdf, pdfOutput);
        byte[] originalBytes = bytes(originalPdf);
        check(Arrays.equals(originalBytes, bytes(pdfOutput)), "Unedited PDF exports byte for byte");
        PageState first = library.pages(pdf.id).get(0);
        library.editPage(pdf.id, 0, first.edited(90, crop, "medium"));
        export(library, library.document(pdf.id), pdfOutput);
        check(pageCount(pdfOutput) == 2 && !Arrays.equals(originalBytes, bytes(pdfOutput)), "Edited PDF is regenerated");
        check(Arrays.equals(originalBytes, bytes(library.source(pdf))), "Editing PDF never changes original");
        library.removePage(pdf.id, 0);
        export(library, library.document(pdf.id), pdfOutput);
        check(pageCount(pdfOutput) == 1, "PDF export omits removed page");
        mark("Unedited/edited PDF export and original preservation");
    }

    private void geometry() {
        float[] corners = {.12f, .08f, .91f, .17f, .83f, .94f, .05f, .81f};
        for (int rotation : new int[]{0, 90, 180, 270}) {
            float[] result = PageGeometry.original(PageGeometry.display(corners, rotation), rotation);
            for (int i = 0; i < 8; i++) check(Math.abs(result[i] - corners[i]) < .00001f, "Corner rotation round trip");
        }
        for (float[] invalid : new float[][]{
                {0, 0, 1, 1, 1, 0, 0, 1}, {0, 0, .01f, 0, .01f, .01f, 0, .01f},
                {Float.NaN, 0, 1, 0, 1, 1, 0, 1}, {-1, 0, 1, 0, 1, 1, 0, 1}}) {
            boolean rejected = false;
            try { PageGeometry.checked(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Invalid quadrilateral rejected");
        }
        mark("Crop coordinate round trips and invalid inputs");
    }

    private void migration(Context context, int fromVersion) throws Exception {
        String id = UUID.randomUUID().toString();
        String pageId = UUID.randomUUID().toString();
        File file = context.getDatabasePath("library.db");
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            // Original independent-app v1 schema, not the proprietary reference application's schema.
            db.execSQL("CREATE TABLE documents(id TEXT PRIMARY KEY,title TEXT NOT NULL,mime TEXT NOT NULL,added_at INTEGER NOT NULL,page_count INTEGER NOT NULL,title_user_edited INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE pages(id TEXT PRIMARY KEY,document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,position INTEGER NOT NULL,source_page INTEGER NOT NULL,rotation INTEGER NOT NULL DEFAULT 0,crop TEXT NOT NULL DEFAULT '',enhancement TEXT NOT NULL DEFAULT 'original',ocr_text TEXT NOT NULL DEFAULT '',ocr_state TEXT NOT NULL DEFAULT 'unprocessed',ocr_engine TEXT NOT NULL DEFAULT '',language TEXT NOT NULL DEFAULT '',UNIQUE(document_id,position))");
            db.execSQL("CREATE TABLE stacks(id TEXT PRIMARY KEY,name TEXT,color INTEGER,visible INTEGER)");
            db.execSQL("CREATE TABLE document_stacks(document_id TEXT,stack_id TEXT,PRIMARY KEY(document_id,stack_id))");
            db.execSQL("CREATE VIRTUAL TABLE search_index USING fts4(document_id,title,body,notindexed=document_id,tokenize=unicode61)");
            db.execSQL("INSERT INTO documents VALUES(?, 'Migration specimen', 'application/pdf', 123, 1, 1)", new Object[]{id});
            db.execSQL("INSERT INTO pages(id,document_id,position,source_page,ocr_text,rotation,crop,enhancement) VALUES(?,?,0,0,'preserved text',90,?,'bright')",
                    new Object[]{pageId, id, PageGeometry.encode(new float[]{.1f,.1f,.9f,.1f,.9f,.9f,.1f,.9f})});
            db.execSQL("INSERT INTO stacks VALUES('bills','Bills',0,1)");
            db.execSQL("INSERT INTO document_stacks VALUES(?,'bills')", new Object[]{id});
            db.execSQL("INSERT INTO search_index VALUES(?,'Migration specimen','preserved text')", new Object[]{id});
            if (fromVersion >= 2) {
                db.execSQL("ALTER TABLE pages ADD COLUMN source_file TEXT NOT NULL DEFAULT 'source'");
                db.execSQL("ALTER TABLE documents ADD COLUMN initial_page_count INTEGER NOT NULL DEFAULT 0");
                db.execSQL("UPDATE documents SET initial_page_count=page_count");
                db.execSQL("ALTER TABLE documents ADD COLUMN source_scan_id TEXT");
                db.execSQL("CREATE UNIQUE INDEX scan_source ON documents(source_scan_id)");
            }
            if (fromVersion >= 2) db.execSQL("UPDATE pages SET source_file=?,source_page=4",new Object[]{pageId+".jpg"});
            if (fromVersion >= 3) {
                db.execSQL("ALTER TABLE pages ADD COLUMN flatten INTEGER NOT NULL DEFAULT 0");
                db.execSQL("UPDATE pages SET flatten=1,source_page=4");
            }
            if(fromVersion>=4) {
                String[] columns={"content_revision INTEGER NOT NULL DEFAULT 0","text_revision INTEGER NOT NULL DEFAULT -1",
                    "ocr_attempts INTEGER NOT NULL DEFAULT 0","ocr_claim TEXT NOT NULL DEFAULT ''","ocr_error TEXT NOT NULL DEFAULT ''",
                    "ocr_config TEXT NOT NULL DEFAULT ''","text_config TEXT NOT NULL DEFAULT ''"};
                for(String column:columns) db.execSQL("ALTER TABLE pages ADD COLUMN "+column);
                db.execSQL("UPDATE pages SET text_revision=content_revision,ocr_state='succeeded',ocr_config=?,text_config=?",new Object[]{OcrModel.CONFIG,OcrModel.CONFIG});
                db.execSQL("CREATE INDEX ocr_work ON pages(ocr_state,ocr_attempts)");
                db.execSQL("CREATE VIRTUAL TABLE page_search USING fts4(page_id,document_id,title,body,notindexed=page_id,notindexed=document_id,tokenize=unicode61)");
                db.execSQL("INSERT INTO page_search SELECT p.id,p.document_id,d.title,p.ocr_text FROM pages p JOIN documents d ON d.id=p.document_id");
            }
            if(fromVersion>=5) {java.lang.reflect.Method method=LibraryDatabase.class.getDeclaredMethod("addSuggestionSchema",SQLiteDatabase.class,boolean.class);method.setAccessible(true);method.invoke(null,db,true);}
            if(fromVersion>=6)db.execSQL("CREATE TABLE restore_commits (id TEXT PRIMARY KEY)");
            db.execSQL("INSERT INTO stacks VALUES('late','Later synthetic',42,0)");
            db.execSQL("INSERT INTO stacks VALUES('early','Early synthetic',99,1)");
            if(fromVersion>=7) {
                java.lang.reflect.Method method=LibraryDatabase.class.getDeclaredMethod("addP1Schema",SQLiteDatabase.class);method.setAccessible(true);method.invoke(null,db);
                db.execSQL("UPDATE documents SET notes='Saved migration notes',notes_revision=3,metadata_revision=4");
                db.execSQL("INSERT INTO details VALUES(?, 'date:primary','date','Date','2024-07-31','','edited','saved','{}',4)",new Object[]{id});
                db.execSQL("INSERT INTO details VALUES(?, 'amount:total','amount','Total','20','USD','edited','removed','{}',3)",new Object[]{id});
                db.execSQL("INSERT INTO editor_drafts VALUES(?,'notes','Unsaved draft',3)",new Object[]{id});
            }
            if(fromVersion>=8) {
                java.lang.reflect.Method method=LibraryDatabase.class.getDeclaredMethod("addStackOrder",SQLiteDatabase.class);method.setAccessible(true);method.invoke(null,db);
            }
            db.setVersion(fromVersion);
        }
        try (LibraryDatabase helper = new LibraryDatabase(context)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            check(db.getVersion() == 9, "Real SQLiteOpenHelper migration ran");
            try(Cursor sync=db.rawQuery("SELECT enabled,applying FROM sync_control WHERE singleton=1",null)) {
                check(sync.moveToFirst()&&sync.getInt(0)==0&&sync.getInt(1)==0,"Additive sync journal starts disabled before explicit baseline");
            }
            try (Cursor row = db.rawQuery("SELECT d.title,d.initial_page_count,p.source_file,p.ocr_text,p.flatten,p.rotation,p.crop,p.enhancement,p.source_page FROM documents d JOIN pages p ON p.document_id=d.id", null)) {
                check(row.moveToFirst() && row.getString(0).equals("Migration specimen") && row.getInt(1) == 1
                        && row.getString(2).equals(fromVersion == 1 ? "source" : pageId+".jpg") && row.getString(3).equals("preserved text") && row.getInt(4) == (fromVersion >= 3 ? 1 : 0) && row.getInt(5) == 90 && PageGeometry.decode(row.getString(6))[0] == .1f
                        && row.getString(7).equals("bright") && row.getInt(8)==(fromVersion == 1 ? 0 : 4), "Migration preserves metadata and adds defaults");
            }
            try(Cursor row=db.rawQuery("SELECT title_origin,filing_origin,title_user_edited,(SELECT count(*) FROM suggestions) FROM documents",null)) {
                check(row.moveToFirst() && row.getString(0).equals("legacy") && row.getString(1).equals("legacy") && row.getInt(2)==1 && row.getInt(3)==1,"Conservative additive provenance");
            }
            if(fromVersion>=4) try(Cursor row=db.rawQuery("SELECT ocr_state,text_config FROM pages",null)) {
                check(row.moveToFirst() && row.getString(0).equals("succeeded") && row.getString(1).equals(OcrModel.CONFIG),"Schema4 recognition untouched");
            }
            try(Cursor rows=db.rawQuery("SELECT id,sort_order FROM stacks ORDER BY sort_order",null)) {
                String[] expected={"bills","late","early"};int i=0;
                while(rows.moveToNext()){check(rows.getString(0).equals(expected[i])&&rows.getInt(1)==i,"Historical rowid order retained including hidden stack");i++;}
                check(i==3,"All stacks retained");
            }
            if(fromVersion>=7) {
                try(Cursor rows=db.rawQuery("SELECT notes,notes_revision,metadata_revision,(SELECT count(*) FROM details),(SELECT value FROM editor_drafts) FROM documents",null)) {
                    check(rows.moveToFirst()&&rows.getString(0).equals("Saved migration notes")&&rows.getInt(1)==3&&rows.getInt(2)==4&&rows.getInt(3)==2&&rows.getString(4).equals("Unsaved draft"),"Schema7 manual values, removals and draft preserved");
                }
            }
            try (Cursor rows = db.rawQuery("SELECT count(*) FROM document_stacks", null)) {
                check(rows.moveToFirst() && rows.getInt(0) == 1, "Migration preserves memberships");
            }
            try (Cursor rows = db.rawQuery("SELECT count(*) FROM search_index WHERE search_index MATCH 'preserved'", null)) {
                check(rows.moveToFirst() && rows.getInt(0) == 1, "Migration preserves search index");
            }
        }
        mark("Actual v" + fromVersion + "-to-v9 migration; text, metadata, memberships and flatten preserved");
    }

    private static Bitmap fixture(int paperColor) {
        Bitmap bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.DKGRAY);
        Paint paint = new Paint();
        paint.setColor(paperColor); canvas.drawRect(120, 100, 480, 700, paint);
        paint.setColor(Color.BLACK); paint.setTextSize(30); canvas.drawText("SYNTHETIC PAGE", 140, 220, paint);
        return bitmap;
    }

    static byte[] jpeg(Bitmap bitmap) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, bytes), "Fixture JPEG encoded");
        return bytes.toByteArray();
    }

    private static void pdfFixture(File file) throws Exception {
        PdfDocument pdf = new PdfDocument();
        try {
            Paint paint = new Paint(); paint.setTextSize(24);
            for (int i = 1; i <= 2; i++) {
                PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(400, 600, i).create());
                page.getCanvas().drawColor(Color.WHITE);
                page.getCanvas().drawText("Synthetic PDF page " + i, 20, 60, paint);
                pdf.finishPage(page);
            }
            try (FileOutputStream output = new FileOutputStream(file)) { pdf.writeTo(output); }
        } finally { pdf.close(); }
    }

    private static void export(LibraryRepository library, Document document, File file) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) { library.exportPdf(document, output); }
    }

    private static int pageCount(File file) throws Exception {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer pdf = new PdfRenderer(descriptor)) { return pdf.getPageCount(); }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    static byte[] bytes(File file) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(file)) { LibraryRepository.copy(input, output, 256L * 1024 * 1024); }
        return output.toByteArray();
    }

    private void rendering(Context context) throws Exception {
        Bitmap image = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        Paint paint = new Paint();
        int[] colors = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};
        for (int i = 0; i < 4; i++) {
            paint.setColor(colors[i]);
            int left = i == 1 || i == 2 ? 300 : 0;
            int top = i > 1 ? 400 : 0;
            canvas.drawRect(left, top, left + 300, top + 400, paint);
        }
        File source = new File(context.getCacheDir(), "quadrants.jpg");
        try (FileOutputStream output = new FileOutputStream(source)) { output.write(jpeg(image)); }
        image.recycle();
        float[] corners = {.1f, .1f, .9f, .15f, .85f, .9f, .12f, .85f};
        PageState state = new PageState(UUID.randomUUID().toString(), "source", 0, 90, corners, "original");
        Bitmap result = PageRenderer.render(source, "image/jpeg", state, 1200);
        int[] expected = {Color.YELLOW, Color.RED, Color.GREEN, Color.BLUE};
        for (int i = 0; i < 4; i++) {
            int x = result.getWidth() * (i == 1 || i == 2 ? 3 : 1) / 4;
            int y = result.getHeight() * (i > 1 ? 3 : 1) / 4;
            nearColor(result.getPixel(x, y), expected[i], "Perspective crop and clockwise rotation preserve content");
        }
        result.recycle();
        ExifInterface exif = new ExifInterface(source.getPath());
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(ExifInterface.ORIENTATION_ROTATE_90));
        exif.saveAttributes();
        Bitmap upright = PageRenderer.preview(source, "image/jpeg", 0, 1200);
        check(upright.getWidth() == 800 && upright.getHeight() == 600, "EXIF rotation applied exactly once");
        nearColor(upright.getPixel(200, 150), Color.YELLOW, "EXIF rotation keeps correct corner");
        upright.recycle();
        Bitmap gray = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888);
        gray.eraseColor(Color.rgb(100, 100, 100));
        File grayFile = new File(context.getCacheDir(), "gray.jpg");
        try (FileOutputStream output = new FileOutputStream(grayFile)) { output.write(jpeg(gray)); }
        gray.recycle();
        Bitmap medium = PageRenderer.render(grayFile, "image/jpeg", state.edited(0, null, "medium"), 100);
        Bitmap bright = PageRenderer.render(grayFile, "image/jpeg", state.edited(0, null, "bright"), 100);
        check(Color.red(medium.getPixel(40, 40)) > 105
                && Color.red(bright.getPixel(40, 40)) > Color.red(medium.getPixel(40, 40)), "Color presets affect exported pixels");
        medium.recycle(); bright.recycle();
        mark("Perspective/rotation content, EXIF orientation and enhancement pixels");
    }

    private static void nearColor(int actual, int expected, String message) {
        check(Math.abs(Color.red(actual) - Color.red(expected)) < 20
                && Math.abs(Color.green(actual) - Color.green(expected)) < 20
                && Math.abs(Color.blue(actual) - Color.blue(expected)) < 20, message);
    }

    private void mark(String name) {
        passed++;
        Bundle status = new Bundle(); status.putString("stream", "PASS " + passed + ": " + name + "\n");
        sendStatus(0, status);
    }

    /** Each check run owns a fresh child directory within the application package. */
    static final class IsolatedContext extends ContextWrapper {
        private final File root;
        IsolatedContext(Context base) {
            this(base,UUID.randomUUID().toString());
        }
        IsolatedContext(Context base, String name) {
            super(base);
            if (!name.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Invalid test directory");
            root = new File(base.getFilesDir(), "checks/" + name);
            check(root.isDirectory() || root.mkdirs(), "Create isolated check directory");
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return directory("files"); }
        @Override public File getCacheDir() { return directory("cache"); }
        @Override public File getDatabasePath(String name) { return new File(directory("databases"), name); }
        private File directory(String name) { File file = new File(root, name); if (!file.isDirectory()) check(file.mkdirs(), "Create check storage"); return file; }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
            return openOrCreateDatabase(name, mode, factory, null);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
            return SQLiteDatabase.openDatabase(getDatabasePath(name).getPath(), factory,
                    SQLiteDatabase.CREATE_IF_NECESSARY | SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING, errorHandler);
        }
    }
}
