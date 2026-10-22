package org.stackdocs.app;

import android.app.job.*;
import android.content.*;
import android.os.Handler;
import android.os.Looper;
import org.json.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Schedules the existing pinned exchange; the app's IO executor remains the writer. */
public final class SyncJob extends JobService {
    private static final int JOB=0x53594e43;
    static final AtomicBoolean active=new AtomicBoolean();
    private static volatile PairingTransport.SessionControl connection;
    static volatile String status="";
    private static final Handler ui=new Handler(Looper.getMainLooper());
    private static Runnable pending;
    static SharedPreferences preferences(Context context){return context.getSharedPreferences("SyncActivity",Context.MODE_PRIVATE);}
    static void restoreConnection(Context context,JSONObject paired){
        if(!paired.has("paired_peer")||paired.optBoolean("revoked")||paired.optString("host").isEmpty()||paired.optString("peer_name").isEmpty())return;
        preferences(context).edit().putString("sync_host",paired.optString("host")).putString("sync_peer_name",paired.optString("peer_name")).commit();
    }
    private static final int JOB_VERSION=2;
    static void schedule(Context context){
        JobScheduler jobs=context.getSystemService(JobScheduler.class);
        if(preferences(context).getString("sync_peer_name","").isEmpty()){jobs.cancel(JOB);return;}
        // This runs on every process start, including the one the job itself
        // starts, and scheduling again stops a running job. Keep a current one.
        JobInfo current=jobs.getPendingJob(JOB);
        if(current!=null&&current.getExtras().getInt("version")==JOB_VERSION)return;
        android.os.PersistableBundle extras=new android.os.PersistableBundle();extras.putInt("version",JOB_VERSION);
        JobInfo.Builder job=new JobInfo.Builder(JOB,new ComponentName(context,SyncJob.class)).setExtras(extras).setPersisted(true).setPeriodic(15*60*1000L);
        // The computer is on the local network: Wi-Fi or Ethernet, with or without internet.
        if(android.os.Build.VERSION.SDK_INT>=28)job.setRequiredNetwork(new android.net.NetworkRequest.Builder().removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).addTransportType(android.net.NetworkCapabilities.TRANSPORT_ETHERNET).build());
        else job.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
        jobs.schedule(job.build());
    }
    static void changed(StackApplication app){
        if(active.get()||app.resetRequested)return;
        synchronized(SyncJob.class){if(pending!=null)ui.removeCallbacks(pending);pending=()->kick(app,null);ui.postDelayed(pending,1000);}
    }
    static void kick(StackApplication app,Runnable finished){
        if(app.resetRequested||!app.access.allowed()||preferences(app).getString("sync_peer_name","").isEmpty()||!active.compareAndSet(false,true)){if(finished!=null)finished.run();return;}
        status="Syncing…";
        app.io.execute(()->{
            try {
                if(app.resetRequested||!app.access.allowed()){status="Unlock Stack to continue syncing";return;}
                PairingTransport transport=new PairingTransport(app,app.library);
                JSONObject paired=transport.connectionStatus();if(!paired.has("paired_peer")||paired.optBoolean("revoked")){status="Disconnected";return;}
                if(!paired.optBoolean("adopted")){status="Finishing connection · Keep Stack open on both devices";return;}
                SharedPreferences saved=preferences(app);String host=saved.getString("sync_host","");
                PairingTransport.SessionControl control=new PairingTransport.SessionControl();connection=control;
                // A lock may happen while this job waits behind repository work.
                // Publishing before rechecking closes the race with pause().
                if(app.resetRequested||!app.access.allowed()){control.cancel();status="Unlock Stack to continue syncing";return;}
                JSONObject result=null;
                try {result=transport.syncNow(host,45873,control);}
                catch(java.io.IOException offline){
                    if(control.canceled())throw offline;
                    // Reuse ordinary discovery only as an address hint. Pins are
                    // still checked before any library data can be exchanged.
                    NearbyPairing.Control discovery=new NearbyPairing.Control();JSONArray found=NearbyPairing.find(discovery,NearbyPairing.PORT);boolean synced=false;
                    for(int i=0;i<found.length();i++){JSONObject remote=found.getJSONObject(i);if(remote.getInt("port")==45873&&paired.optString("peer_fingerprint").startsWith(remote.getString("invite"))){result=transport.syncNow(remote.getString("host"),45873,control);saved.edit().putString("sync_host",remote.getString("host")).apply();synced=true;break;}}
                    if(!synced)throw offline;
                }
                transport.markComplete();
                String time="Last sync: "+java.text.DateFormat.getDateTimeInstance().format(new java.util.Date());
                saved.edit().putString("sync_last_ack",time).putString("sync_session_status","Up to date").apply();
                status="Up to date";
                // Refresh documents only when the computer sent changes; the
                // finally block still updates the sync status on every attempt.
                JSONObject received=result==null?null:result.optJSONObject("received");
                if(received!=null&&received.optInt("applied")>0)app.notifyLibraryChanged();
            } catch(Exception error){
                if(!app.access.allowed())status="Unlock Stack to continue syncing";
                else if(error instanceof android.database.sqlite.SQLiteFullException)status="Not enough storage · Free some space to continue syncing";
                else if(error instanceof java.net.ConnectException||error instanceof java.net.NoRouteToHostException||error instanceof java.net.SocketTimeoutException)status="Computer unavailable · Will retry automatically";
                else status="Could not finish syncing · Your documents are saved and Stack will retry";
            }
            finally{connection=null;active.set(false);ui.post(()->{app.notifySyncChanged();if(finished!=null)finished.run();});}
        });
    }
    static void pause(){PairingTransport.SessionControl current=connection;if(current!=null)current.cancel();}
    static void disconnect(StackApplication app,Runnable finished){
        PairingTransport.SessionControl current=connection;if(current!=null)current.cancel();
        app.io.execute(()->{try{new PairingTransport(app,app.library).revoke();preferences(app).edit().remove("sync_peer_name").remove("sync_host").remove("sync_last_ack").apply();schedule(app);status="Disconnected";}catch(Exception error){status="Could not disconnect. Please try again.";}ui.post(finished);});
    }
    @Override public boolean onStartJob(JobParameters parameters){StackApplication app=(StackApplication)getApplication();if(active.get()||!app.access.allowed()||app.resetRequested)return false;ui.post(()->kick(app,()->jobFinished(parameters,!status.equals("Up to date")&&!status.startsWith("Computer unavailable"))));return true;}
    @Override public boolean onStopJob(JobParameters parameters){PairingTransport.SessionControl current=connection;if(current!=null)current.cancel();return !preferences(this).getString("sync_peer_name","").isEmpty();}
}
