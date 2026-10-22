package org.stackdocs.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONObject;
import java.util.function.Consumer;

/** A single settings page. Transport internals have no interactive routes. */
final class SyncScreen {
    enum Action { REVIEW, CONNECT, REVOKE }
    final LinearLayout root;
    private final Activity activity;private final Consumer<Action> action;private final Runnable exit;
    private final LinearLayout body;private final ScrollView scroll;
    SyncScreen(Activity activity,Consumer<Action> action,Runnable exit,Bundle saved){
        this.activity=activity;this.action=action;this.exit=exit;root=Ui.column(activity);
        LinearLayout bar=Ui.toolbar(activity);bar.addView(Ui.iconButton(activity,Icons.Symbol.BACK,"Back",exit),new LinearLayout.LayoutParams(Ui.dp(activity,48),Ui.dp(activity,48)));
        bar.addView(Ui.text(activity,"Sync",18,false));root.addView(bar);
        body=Ui.column(activity);body.setPadding(Ui.dp(activity,24),Ui.dp(activity,24),Ui.dp(activity,24),Ui.dp(activity,24));
        scroll=new ScrollView(activity);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    }
    void save(Bundle state){state.putInt("sync_scroll",scroll.getScrollY());}
    void back(){exit.run();}
    void render(JSONObject library,JSONObject pairing,boolean running,String status,String last){
        body.removeAllViews();boolean paired=pairing!=null&&pairing.has("paired_peer")&&!pairing.optBoolean("revoked");
        if(paired){
            add(Ui.text(activity,pairing.optString("peer_name","Your computer"),20,true),0);
            add(Ui.text(activity,running?"Syncing…":(status==null||status.isEmpty()?"Connected · Syncs automatically":status),16,false),8);
            add(Ui.text(activity,last==null||last.isEmpty()?"Last sync: waiting for first sync":last,14,false),4);
            add(Ui.primary(activity,"Sync now",()->action.accept(Action.CONNECT)),32);
            add(Ui.button(activity,"Disconnect",()->action.accept(Action.REVOKE)),12);
            if(library!=null&&library.optJSONObject("conflicts")!=null&&library.optJSONObject("conflicts").length()>0)
                add(Ui.button(activity,"Review changes",()->action.accept(Action.REVIEW)),12);
        }else{
            add(Ui.text(activity,"No computer connected",20,true),0);
            add(Ui.text(activity,"On your computer, open Settings → Sync and select Connect phone. Approve the connection here. Your documents will then sync automatically over Wi-Fi.",16,false),12);
            if(status!=null&&!status.isEmpty())add(Ui.text(activity,status,14,false),12);
        }
    }
    private void add(android.view.View view,int gap){
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=Ui.dp(activity,gap);body.addView(view,params);
    }
}
