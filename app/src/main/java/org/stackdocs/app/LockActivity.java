package org.stackdocs.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.function.Consumer;

/** A secure, content-free host for Android's unlock prompt. Recovery appears only after failure. */
public class LockActivity extends Activity {
    private Runnable removeBack;
    private View recovery;
    private boolean resumed,attempted,active,destroyed;
    private int generation;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout content=Ui.column(this);
        content.setGravity(Gravity.CENTER);
        content.setPadding(Ui.dp(this,24),Ui.dp(this,24),Ui.dp(this,24),Ui.dp(this,24));
        TextView title=Ui.text(this,getString(R.string.unlock_stack),22,true);
        title.setGravity(Gravity.CENTER);content.addView(title,new LinearLayout.LayoutParams(-1,-2));
        TextView status=Ui.text(this,getString(R.string.unlock_retry_message),15,false);
        status.setGravity(Gravity.CENTER);status.setTextColor(Ui.MUTED);
        status.setPadding(0,Ui.dp(this,12),0,Ui.dp(this,16));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status,new LinearLayout.LayoutParams(-1,-2));
        content.addView(Ui.primary(this,getString(R.string.unlock_try_again),this::unlock),new LinearLayout.LayoutParams(-1,-2));
        content.addView(Ui.button(this,getString(R.string.unlock_security_settings),() -> startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))));
        content.addView(Ui.button(this,getString(R.string.unlock_close),this::finishAffinity));
        recovery=content;recovery.setVisibility(View.GONE);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(content);
        LinearLayout frame=Ui.column(this);frame.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        Ui.install(this,frame);removeBack=Ui.backHandler(this,this::finishAffinity);
    }
    private StackApplication app(){return (StackApplication)getApplication();}
    // The platform boundary can be substituted only by test-APK activity subclasses.
    boolean accessAllowed(){return app().access.allowed();}
    boolean screenLockInProgress(){return app().access.screenLockInProgress(this);}
    void authenticate(Consumer<Boolean> callback){app().access.authenticate(this,callback);}

    @Override protected void onResume() {
        super.onResume();resumed=true;
        if(accessAllowed()){finish();return;}
        getWindow().getDecorView().post(() -> {if(resumed&&!attempted&&!isFinishing()&&!destroyed)unlock();});
    }
    private void unlock() {
        if(!resumed||active||isFinishing()||destroyed)return;
        if(accessAllowed()){finish();return;}
        attempted=true;active=true;int token=++generation;
        recovery.setVisibility(View.GONE);
        authenticate(success -> {
            if(destroyed||isFinishing()||token!=generation||!active)return;
            active=false;
            if(success&&accessAllowed())finish();
            else recovery.setVisibility(View.VISIBLE);
        });
    }
    @Override protected void onPause(){resumed=false;super.onPause();}
    @Override protected void onStop(){
        super.onStop();
        // A real background exit starts a fresh prompt on return. The separate
        // Android credential activity keeps its pending request until its result;
        // canceling that result must not immediately launch it again.
        if(!isChangingConfigurations()&&!screenLockInProgress())attempted=false;
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==AccessPolicy.CREDENTIAL)app().access.credentialResult(this,result);}
    @android.annotation.SuppressLint("GestureBackNavigation") // Native back callback uses Ui.backHandler.
    @Override public void onBackPressed(){finishAffinity();}
    @Override protected void onDestroy(){destroyed=true;generation++;if(removeBack!=null)removeBack.run();super.onDestroy();}
}
