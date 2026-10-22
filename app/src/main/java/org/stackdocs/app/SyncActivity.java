package org.stackdocs.app;

import android.app.AlertDialog;
import android.os.Bundle;
import org.json.*;
import java.util.*;

/** Settings → Sync; pairing approves the merge and everyday exchange is automatic. */
public final class SyncActivity extends LibraryActivity {
    private NearbyPairingUi nearby;private SyncScreen screen;private JSONObject library,pairing;
    private Runnable removeBack;private final android.os.Handler ui=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){public void run(){refreshSync();ui.postDelayed(this,3000);}};
    @Override public void onCreate(Bundle state){super.onCreate(state);nearby=new NearbyPairingUi(this);screen=new SyncScreen(this,this::action,this::finish,state);Ui.install(this,screen.root);removeBack=Ui.backHandler(this,this::finish);refreshSync();}
    private volatile boolean setupForeground;
    @Override protected void onResume(){super.onResume();setupForeground=true;whenAllowed(nearby::resume);ui.post(refresh);SyncJob.kick(app(),null);}
    @Override protected void onPause(){ui.removeCallbacks(refresh);super.onPause();}
    @Override protected void onStop(){setupForeground=false;nearby.cancel();keepSyncAwake(false);super.onStop();}
    @Override protected void onDestroy(){nearby.cancel();if(removeBack!=null)removeBack.run();super.onDestroy();}
    @android.annotation.SuppressLint("GestureBackNavigation") @Override public void onBackPressed(){finish();}
    @Override protected void onLibraryChanged(){refreshSync();}
    void refreshSync(){if(SyncJob.active.get()){renderSyncScreen();return;}work(()->new JSONObject().put("library",new SyncStore(this,app().library).status()).put("pairing",new PairingTransport(this,app().library).status()),value->{library=value.optJSONObject("library");pairing=value.optJSONObject("pairing");renderSyncScreen();if(setupForeground&&(pairing==null||!pairing.has("paired_peer")||pairing.optBoolean("revoked")||!pairing.optBoolean("adopted"))&&!nearby.active()&&!nearby.stopped())nearby.start();});}
    void connectionRequested(){if(!nearby.active())nearby.start();}
    void renderSyncScreen(){if(screen!=null)screen.render(library,pairing,SyncJob.active.get(),SyncJob.status,SyncJob.preferences(this).getString("sync_last_ack",""));}
    void keepSyncAwake(boolean active){if(active)getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void action(SyncScreen.Action action){whenAllowed(()->{switch(action){case CONNECT:SyncJob.kick(app(),this::refreshSync);renderSyncScreen();break;case REVOKE:SyncJob.disconnect(app(),this::refreshSync);break;case REVIEW:review();break;default:break;}});}
    void acceptNearby(NearbyPairing.Result result){
        work(()->{try{if(result.baseline!=null)new SyncExchange(this,app().library,()->{if(!setupForeground||!app().access.allowed())throw new java.io.IOException("Connection paused. Open Stack to continue.");}).mergeConnected(result.baseline);SyncJob.schedule(this);return true;}finally{result.close();}},ok->{keepSyncAwake(false);SyncJob.kick(app(),this::refreshSync);refreshSync();});
    }
    private void review(){work(()->new SyncStore(this,app().library).status(),state->{
        JSONObject conflicts=state.optJSONObject("conflicts");if(conflicts==null||conflicts.length()==0){refreshSync();return;}
        String key=SyncProtocol.keys(conflicts).get(0);for(String field:SyncProtocol.keys(conflicts))if(field.startsWith("documents/")&&field.endsWith("/title")){key=field;break;}final String selectedKey=key;JSONArray choices=conflicts.optJSONArray(key);if(choices==null)return;
        StringBuilder comparison=new StringBuilder("This was changed on both devices. Choose the version to keep.\n\n");
        String local=state.optString("replica");List<String> labels=new ArrayList<>();
        for(int i=0;i<choices.length();i++){JSONObject choice=choices.optJSONObject(i);String device=local.equals(choice.optJSONObject("id").optString("replica"))?"phone":"desktop";labels.add("Keep "+device+" version");comparison.append(device.equals("phone")?"Phone:\n":"Desktop:\n").append(readable(choice.opt("value"),state)).append("\n\n");}
        new AlertDialog.Builder(this).setTitle("Choose which change to keep").setMessage(comparison.toString()).setNegativeButton(labels.get(0),(d,w)->resolve(selectedKey,choices,0)).setPositiveButton(labels.size()>1?labels.get(1):labels.get(0),(d,w)->resolve(selectedKey,choices,Math.min(1,choices.length()-1))).setNeutralButton("Decide later",null).show();
    });}
    private void resolve(String key,JSONArray choices,int selected){JSONArray expected=new JSONArray();for(int i=0;i<choices.length();i++)expected.put(choices.optJSONObject(i).optJSONObject("id"));work(()->new SyncStore(this,app().library).resolve(key,expected,choices.optJSONObject(selected).opt("value")),ok->{SyncJob.changed(app());review();});}
    static String readable(Object value,JSONObject status){
        if(value==null||value==JSONObject.NULL)return "Removed";
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;for(String field:new String[]{"notes","title","name","value","label"})if(object.has(field))return object.optString(field);
            if(object.has("rotation"))return "Rotation: "+object.optInt("rotation")+"°\nColor: "+object.optString("enhancement","Original")+"\nFlattening: "+(object.optInt("flatten")==1?"On":"Off")+"\nCrop: "+object.optString("crop","Full page");
            if(object.has("visible"))return object.optInt("visible")==1?"Visible in the library":"Hidden from the library";
            return "Saved document change";
        }
        if(value instanceof JSONArray){JSONArray values=(JSONArray)value;if(values.length()==0)return "None";List<String> labels=new ArrayList<>();JSONObject stacks=status.optJSONObject("stack_names"),pages=status.optJSONObject("page_names");for(int i=0;i<values.length();i++){String id=values.optString(i),label=stacks==null?"":stacks.optString(id);if(label.isEmpty()&&pages!=null)label=pages.optString(id);labels.add(label.isEmpty()?"Item "+(i+1):label);}return android.text.TextUtils.join(" → ",labels);}
        return String.valueOf(value);
    }
}
