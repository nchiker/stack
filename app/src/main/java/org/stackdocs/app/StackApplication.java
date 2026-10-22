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
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    void addLibraryListener(Runnable listener) { listeners.add(listener); }
    void removeLibraryListener(Runnable listener) { listeners.remove(listener); }
    void notifyLibraryChanged() { SyncJob.changed(this); notifySyncChanged(); }
    void notifySyncChanged() { ui.post(() -> { for (Runnable listener : listeners) listener.run(); }); }
    private android.app.Activity foreground;
    private final java.util.concurrent.atomic.AtomicBoolean finding=new java.util.concurrent.atomic.AtomicBoolean();
    private final Runnable automaticSync=new Runnable(){public void run(){
        if(foreground!=null&&access.allowed()&&!resetRequested){
            if(!SyncJob.preferences(StackApplication.this).getString("sync_peer_name","").isEmpty())SyncJob.kick(StackApplication.this,null);
            if(foreground instanceof LibraryActivity&&finding.compareAndSet(false,true)){
                android.app.Activity owner=foreground;
                new Thread(()->{try{org.json.JSONArray found=NearbyPairing.find(new NearbyPairing.Control(),NearbyPairing.PORT);boolean invitation=false;for(int i=0;i<found.length();i++)if(found.optJSONObject(i).optInt("port")==NearbyPairing.PORT)invitation=true;if(invitation)ui.post(()->{if(foreground==owner&&!owner.isFinishing()&&access.allowed()){if(owner instanceof SyncActivity)((SyncActivity)owner).connectionRequested();else owner.startActivity(new android.content.Intent(owner,SyncActivity.class));}});}catch(Exception ignored){}finally{finding.set(false);}},"Stack connection discovery").start();
            }
        }
        // Local saves request sync immediately; this only polls for computer changes.
        ui.postDelayed(this,30000);
    }};

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
            public void onActivityResumed(android.app.Activity activity){if(activity instanceof LibraryActivity&&!activity.getClass().getSimpleName().equals("IsolatedCheckActivity")){foreground=activity;ui.removeCallbacks(automaticSync);ui.post(automaticSync);}}
            public void onActivityPaused(android.app.Activity activity){if(foreground==activity)foreground=null;}
            public void onActivityCreated(android.app.Activity a,android.os.Bundle b){}public void onActivityStarted(android.app.Activity a){}public void onActivityStopped(android.app.Activity a){}public void onActivitySaveInstanceState(android.app.Activity a,android.os.Bundle b){}public void onActivityDestroyed(android.app.Activity a){}
        });
        access=new AccessPolicy(this);
        library = new LibraryRepository(this);
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
        io.execute(() -> { try { new LibraryReset(this).initialize(library);new SyncStore(this,library).cleanLegacyStacks(); } catch(Exception error){library.blockAfterResetFailure(error);return;} finally{libraryReady.countDown();} try{SyncJob.restoreConnection(this,new PairingTransport(this,library).connectionStatus());}catch(Exception ignored){} SyncJob.schedule(this); batches.recover(); ocr.kick(); suggestions.kick(); metadata.kick(); new android.os.Handler(android.os.Looper.getMainLooper()).post(photos::configure); });
    }
}
