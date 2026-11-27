package org.stackdocs.app;

import android.app.Application;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class StackApplication extends Application {
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final ExecutorService reads = Executors.newSingleThreadExecutor();
    final java.util.concurrent.CountDownLatch libraryReady=new java.util.concurrent.CountDownLatch(1);
    volatile boolean resetRequested;
    LibraryRepository library;
    AccessPolicy access;
    PhotoMonitor photos;
    MetadataWorker metadata;
    BatchController batches;
    ArchiveController archives;
    ImportController imports;
    OcrWorker ocr;
    SuggestionWorker suggestions;
    SourceIndexer sourceIndexer;
    ScanShare scanShare;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    void addLibraryListener(Runnable listener) { listeners.add(listener); }
    void removeLibraryListener(Runnable listener) { listeners.remove(listener); }
    void notifyLibraryChanged() { SyncJob.changed(this); notifySyncChanged(); }
    void notifySyncChanged() { if (scanShare != null) scanShare.changed(); refreshListeners(); }
    /** Tells open screens to redraw. */
    void refreshListeners() { ui.post(() -> { for (Runnable listener : listeners) listener.run(); }); }
    private android.app.Activity foreground;
    private final java.util.concurrent.atomic.AtomicBoolean finding=new java.util.concurrent.atomic.AtomicBoolean();
    private final Runnable automaticSync=new Runnable(){public void run(){
        if(foreground!=null&&access.allowed()&&!resetRequested){
            if(!SyncJob.preferences(StackApplication.this).getString("sync_peer_name","").isEmpty())SyncJob.kick(StackApplication.this,null);
            AutoBackup.kickIfDue(StackApplication.this);
            if(scanShare!=null)scanShare.retryIfNeeded();
            UpdateCheck.checkIfDue(StackApplication.this);
            if(foreground instanceof LibraryActivity&&finding.compareAndSet(false,true)){
                android.app.Activity owner=foreground;
                new Thread(()->{try{org.json.JSONArray found=NearbyPairing.find(new NearbyPairing.Control(),NearbyPairing.PORT);boolean invitation=false;for(int i=0;i<found.length();i++)if(found.optJSONObject(i).optInt("port")==NearbyPairing.PORT)invitation=true;if(invitation)ui.post(()->{if(foreground==owner&&!owner.isFinishing()&&access.allowed()){if(owner instanceof SyncActivity)((SyncActivity)owner).connectionRequested();else owner.startActivity(new android.content.Intent(owner,SyncActivity.class));}});}catch(Exception ignored){}finally{finding.set(false);}},"Stack connection discovery").start();
            }
        }
        // Local saves request sync immediately; this only polls for computer changes.
        ui.postDelayed(this,30000);
    }};

    /** While a Stack screen is in the foreground the folder sources are rescanned this often (the 2-minute resume rule still applies; unchanged files are not read again). */
    static final long FOREGROUND_SCAN_MS = 5 * 60_000L;
    private boolean foregroundScanning;
    private final Runnable foregroundScan = new Runnable() { @Override public void run() {
        if (!foregroundScanning) return;
        if (access != null && access.allowed() && sourceIndexer != null) sourceIndexer.kickAll(false);
        ui.postDelayed(this, FOREGROUND_SCAN_MS);
    }};
    void startForegroundScans() { ui.removeCallbacks(foregroundScan); foregroundScanning = true; ui.postDelayed(foregroundScan, FOREGROUND_SCAN_MS); }
    void stopForegroundScans() { foregroundScanning = false; ui.removeCallbacks(foregroundScan); }
    boolean foregroundScanning() { return foregroundScanning; }

    @Override public void onCreate() {
        super.onCreate();
        // The extract process (ExtractService) reads untrusted files and gets no library, database, lock or jobs: nothing below runs in it.
        if (ExtractService.inExtractProcess(this)) return;
        org.stackdocs.app.types.NativeText.restore(this);
        // A viewer copy of a folder file cannot be in use at process start: remove any left by a crash.
        new Thread(() -> { SourceViewerActivity.sweep(this, true); ScanShare.sweepCache(this); }, "Viewer cache sweep").start();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
            public void onActivityResumed(android.app.Activity activity){if(activity instanceof LibraryActivity&&!activity.getClass().getSimpleName().equals("IsolatedCheckActivity")){foreground=activity;ui.removeCallbacks(automaticSync);ui.post(automaticSync);if(access.allowed()&&sourceIndexer!=null)sourceIndexer.kickAll(false);startForegroundScans();}}
            public void onActivityPaused(android.app.Activity activity){if(foreground==activity){foreground=null;stopForegroundScans();}}
            public void onActivityCreated(android.app.Activity a,android.os.Bundle b){}public void onActivityStarted(android.app.Activity a){}public void onActivityStopped(android.app.Activity a){}public void onActivitySaveInstanceState(android.app.Activity a,android.os.Bundle b){}public void onActivityDestroyed(android.app.Activity a){}
        });
        access=new AccessPolicy(this);
        library = new LibraryRepository(this);
        sourceIndexer = new SourceIndexer(this, library.sources, SourceIndexer.safReaders(this, library.sources), new SourceIndexer.Env() {
            @Override public boolean allowed() { return !resetRequested && access.allowed(); }
            @Override public boolean batteryOk() { return SourceIndexer.batteryOk(StackApplication.this); }
        });
        scanShare = ScanShare.forApp(this);
        photos=new PhotoMonitor(this);
        metadata=new MetadataWorker(this);
        batches=new BatchController(this);
        archives = new ArchiveController(this);
        imports = new ImportController(this);
        // This runs before any activity work submitted to the same executor.
        ocr = new OcrWorker(this);
        suggestions = new SuggestionWorker(library.suggestions,io,this::notifyLibraryChanged,access::allowed);
        library.setSuggestionChanged(() -> { suggestions.kick(); metadata.kick(); notifyLibraryChanged(); });
        library.setOcrChanged(() -> { ocr.kick(); suggestions.kick(); metadata.kick(); notifyLibraryChanged(); });
        io.execute(() -> { try { new LibraryReset(this).initialize(library);new SyncStore(this,library).cleanLegacyStacks(); } catch(Exception error){library.blockAfterResetFailure(error);return;} finally{libraryReady.countDown();} try{SyncJob.restoreConnection(this,new PairingTransport(this,library).connectionStatus());}catch(Exception ignored){} SyncJob.schedule(this); AutoBackup.schedule(this); try{library.purgeExpiredTrash();}catch(RuntimeException ignored){} try{library.links.sweep(Clock.now());}catch(RuntimeException ignored){} try{QrRetirement.runOnce(this,library.links);}catch(RuntimeException ignored){} batches.recover(); ocr.kick(); suggestions.kick(); metadata.kick(); try{library.sources.recoverInterrupted();sourceIndexer.sweepRead();SourceIndexer.schedule(this);if(access.allowed())sourceIndexer.kickAll(false);}catch(RuntimeException ignored){} new android.os.Handler(android.os.Looper.getMainLooper()).post(photos::configure); });
    }
}
