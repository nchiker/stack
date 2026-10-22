package org.stackdocs.app;

import android.app.AlertDialog;
import org.json.JSONArray;
import org.json.JSONObject;

/** One foreground attempt, with stale callbacks discarded and stages closed. */
final class NearbyPairingUi {
    private final SyncActivity activity;private NearbyPairing.Control control;private AlertDialog dialog;
    private int generation;private String name="";private boolean stopped,findingScheduled,resuming;
    NearbyPairingUi(SyncActivity activity){this.activity=activity;}
    boolean active(){return control!=null||findingScheduled;}
    boolean stopped(){return stopped;}
    void resume(){stopped=false;}
    void start(){
        if(active())return;stopped=false;
        name=activity.getPreferences(0).getString("nearby_phone_name",android.os.Build.MODEL);
        if(!NearbyPairing.validName(name))name=android.os.Build.MODEL;
        find();
    }
    private void busy(String message,int ticket,NearbyPairing.Control selected){
        activity.keepSyncAwake(true);
        if(dialog!=null)dialog.dismiss();dialog=new AlertDialog.Builder(activity).setTitle("Connect your computer").setMessage(message).setNegativeButton("Cancel",(d,w)->cancel()).setOnCancelListener(d->cancel()).create();dialog.show();
    }
    private boolean current(int ticket,NearbyPairing.Control selected){return ticket==generation&&control==selected&&!activity.isFinishing()&&!activity.isDestroyed()&&activity.app().access.allowed();}
    private void find(){findingScheduled=false;if(stopped)return;NearbyPairing.Control selected=new NearbyPairing.Control();control=selected;int ticket=++generation;
        activity.app().io.execute(()->{try{JSONArray responses=NearbyPairing.find(selected,NearbyPairing.PORT),found=new JSONArray();for(int i=0;i<responses.length();i++)if(responses.optJSONObject(i).optInt("port")==NearbyPairing.PORT)found.put(responses.get(i));activity.runOnUiThread(()->{
            if(!current(ticket,selected))return;control=null;if(dialog!=null)dialog.dismiss();if(found.length()==0){findingScheduled=true;activity.keepSyncAwake(false);new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->{if(!activity.isFinishing()&&!activity.isDestroyed())find();},3000);return;}
            if(found.length()==1){JSONObject target=found.optJSONObject(0);if(target.optInt("port")==NearbyPairing.PORT){connect(target.optString("host"),target.optInt("port"),target.optString("invite"),"");return;}}
            String[] labels=new String[found.length()];for(int i=0;i<labels.length;i++)labels[i]=found.optJSONObject(i).optString("name");
            new AlertDialog.Builder(activity).setTitle("Choose your computer").setItems(labels,(d,w)->{JSONObject target=found.optJSONObject(w);connect(target.optString("host"),target.optInt("port"),target.optString("invite"),"");}).setNegativeButton("Cancel",(d,w)->cancel()).setOnCancelListener(d->cancel()).show();
        });}catch(Exception error){failure(ticket,selected,error);}});
    }
    private void connect(String host,int port,String invite,String pin){connectQr(new JSONArray().put(host),port,invite,pin);}
    private void connectQr(JSONArray hosts,int port,String invite,String pin){NearbyPairing.Control selected=new NearbyPairing.Control();control=selected;int ticket=++generation;busy("Connecting to your computer…",ticket,selected);
        activity.app().io.execute(()->{
            NearbyPairing.Result result=null;Exception failure=null;
            String verifiedPin=pin;
            try{JSONObject saved=new PairingTransport(activity,activity.app().library).connectionStatus();resuming=saved.has("paired_peer")&&!saved.optBoolean("revoked");if(resuming)verifiedPin=saved.getString("peer_fingerprint");}catch(Exception error){failure(ticket,selected,error);return;}
            for(int i=0;i<hosts.length();i++){
                try{result=new NearbyPairing(activity,activity.app().library).connect(name,hosts.optString(i),port,invite,verifiedPin,selected,progress->activity.runOnUiThread(()->progress(ticket,selected,progress)));break;}
                catch(java.net.ConnectException|java.net.NoRouteToHostException error){failure=error;}
                catch(Exception error){failure=error;break;}
            }
            if(result==null){failure(ticket,selected,failure==null?new java.io.IOException("No usable computer address."):failure);return;}
            NearbyPairing.Result received=result;activity.runOnUiThread(()->{if(!current(ticket,selected)){received.close();return;}control=null;dialog.dismiss();
                SyncJob.preferences(activity).edit().putString("sync_host",received.host).putString("sync_port","45873").putString("sync_peer_name",received.device.optString("name")).apply();activity.acceptNearby(received);
            });
        });
    }
    private void progress(int ticket,NearbyPairing.Control selected,JSONObject view){if(!current(ticket,selected))return;
        if(view.optString("phase").equals("compare")){
            if(resuming){selected.confirm(view.optString("token"));busy("Finishing your connection…",ticket,selected);return;}
            if(dialog!=null)dialog.dismiss();String token=view.optString("token");dialog=new AlertDialog.Builder(activity).setTitle("Connect to "+view.optString("name")+"?").setMessage("Allow this computer to sync documents, edits and stacks with your phone? Existing documents on both devices will be kept.\n\nVerification code: "+view.optString("code"))
                .setNegativeButton("Cancel",(d,w)->cancel()).setPositiveButton("Approve connection",(d,w)->{if(current(ticket,selected)&&selected.confirm(token))busy("Connecting…",ticket,selected);}).setOnCancelListener(d->cancel()).create();dialog.show();
        }else busy("Connecting and combining your saved documents…",ticket,selected);
    }
    void cancel(){findingScheduled=false;stopped=true;generation++;NearbyPairing.Control old=control;control=null;if(old!=null)old.cancel();if(dialog!=null){dialog.dismiss();dialog=null;}activity.keepSyncAwake(false);activity.renderSyncScreen();}
    private void failure(int ticket,NearbyPairing.Control selected,Exception error){activity.runOnUiThread(()->{if(!current(ticket,selected))return;control=null;stopped=true;if(dialog!=null)dialog.dismiss();activity.refreshSync();showError(error);});}
    private void showError(Exception error){new AlertDialog.Builder(activity).setTitle("Could not connect").setMessage("Check that Stack is open on your computer and both devices are on the same network. Your documents are still saved.").setPositiveButton("Try again",(d,w)->start()).setNegativeButton("Cancel",(d,w)->cancel()).show();}
}
