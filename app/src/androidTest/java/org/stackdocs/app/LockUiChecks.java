package org.stackdocs.app;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.os.*;
import android.view.*;
import java.util.*;
import java.util.function.Consumer;

/** Test APK only. Substitute the Android prompt boundary, never authenticate a real installation. */
final class LockUiChecks {
    public static final class Screen extends LockActivity {
        final List<Consumer<Boolean>> replies=new ArrayList<>();
        boolean granted,credential;
        Consumer<Boolean> pending;
        @Override boolean accessAllowed(){return granted;}
        @Override boolean screenLockInProgress(){return credential&&pending!=null;}
        @Override void authenticate(Consumer<Boolean> reply){pending=reply;replies.add(reply);}
        void answer(boolean success){Consumer<Boolean> reply=pending;pending=null;credential=false;granted=success;P1Checks.require(reply!=null,"Synthetic pending prompt");reply.accept(success);}
    }
    static void run(Instrumentation i,Consumer<String> mark)throws Exception {
        Context target=i.getTargetContext();P1Checks.require(target.getPackageName().equals("org.stackdocs.app.checks"),"Disposable unlock UI only");
        i.runOnMainSync(() -> {});StackApplication app=(StackApplication)target.getApplicationContext();
        P1BatchChecks.await(() -> app.access!=null&&app.library!=null,"Application startup");
        target.getSharedPreferences("local_policy",Context.MODE_PRIVATE).edit().putBoolean("app_lock",false).commit();
        P1UiChecks.configuration=new Configuration(target.getResources().getConfiguration());P1UiChecks.configuration.screenWidthDp=320;
        Screen screen=open(i);awaitRequests(i,screen,1);
        i.runOnMainSync(() -> {
            P1Checks.require((screen.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0,"Gate remains secure");
            P1Checks.require(P1UiChecks.findText(screen.getWindow().getDecorView(),"Stack is locked")==null,"Redundant locked-page text removed");
            P1Checks.require(P1UiChecks.findButton(screen.getWindow().getDecorView(),"Try again").getVisibility()==View.VISIBLE
                    &&!P1UiChecks.findButton(screen.getWindow().getDecorView(),"Try again").isShown(),"Recovery hidden during initial prompt");
            screen.onResume();screen.onResume();
        });P1UiChecks.settle(i);P1Checks.require(screen.replies.size()==1,"Resume cannot duplicate in-flight prompt");
        i.runOnMainSync(() -> screen.answer(false));P1UiChecks.settle(i);
        i.runOnMainSync(() -> P1Checks.require(P1UiChecks.findButton(screen.getWindow().getDecorView(),"Try again").isShown(),"Cancellation exposes only explicit recovery"));
        i.runOnMainSync(screen::onResume);P1UiChecks.settle(i);P1Checks.require(screen.replies.size()==1,"Cancellation does not immediately prompt again");
        i.runOnMainSync(() -> P1UiChecks.findButton(screen.getWindow().getDecorView(),"Try again").performClick());awaitRequests(i,screen,2);
        i.runOnMainSync(() -> {
            screen.replies.get(0).accept(true);
            P1Checks.require(!screen.isFinishing(),"Stale callback cannot finish newer attempt");
            screen.answer(true);
        });P1BatchChecks.await(screen::isDestroyed,"Successful synthetic result returns to caller");
        mark.accept("Production unlock host starts prompt automatically without a tap; secure/blank initial host; duplicate resume suppressed; cancel offers explicit retry without loop; stale callback ignored and success returns");

        Screen credentials=open(i);awaitRequests(i,credentials,1);
        i.runOnMainSync(() -> {credentials.credential=true;credentials.onPause();credentials.onStop();credentials.answer(false);credentials.onResume();});
        P1UiChecks.settle(i);P1Checks.require(credentials.replies.size()==1,"Canceled separate credential result does not loop");
        i.runOnMainSync(() -> {credentials.onPause();credentials.onStop();credentials.onResume();});awaitRequests(i,credentials,2);
        Instrumentation.ActivityMonitor recreation=i.addMonitor(Screen.class.getName(),null,false);
        i.runOnMainSync(credentials::recreate);Screen fresh=(Screen)recreation.waitForActivityWithTimeout(10000);i.removeMonitor(recreation);
        P1Checks.require(fresh!=null,"Recreated unlock host");awaitRequests(i,fresh,1);
        i.runOnMainSync(() -> credentials.replies.get(1).accept(true));P1Checks.require(!fresh.isFinishing(),"Destroyed owner callback cannot release new gate");
        i.runOnMainSync(() -> fresh.answer(true));P1BatchChecks.await(fresh::isDestroyed,"Recreated synthetic host finished");
        mark.accept("Credential handoff/cancel avoids auto-prompt loop; actual background return rearms; recreation starts one replacement prompt and rejects destroyed-owner result");

        for(int mode=0;mode<3;mode++) {
            P1UiChecks.configuration=new Configuration(target.getResources().getConfiguration());P1UiChecks.configuration.screenWidthDp=mode==2?600:320;P1UiChecks.configuration.fontScale=mode==1?2:1;
            P1UiChecks.configuration.orientation=mode==2?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT;
            String suffix=mode==0?"narrow":mode==1?"large-text":"landscape";
            Screen visual=open(i);awaitRequests(i,visual,1);P1UiChecks.settle(i);P1UiChecks.capture(i,visual,"unlock-prompt-host-"+suffix);
            i.runOnMainSync(() -> visual.answer(false));P1UiChecks.settle(i);P1UiChecks.capture(i,visual,"unlock-retry-"+suffix);
            i.runOnMainSync(visual::finish);
            mark.accept("Synthetic direct-unlock blank host and cancel/error recovery at "+suffix+"; text/touch targets checked, no OS enrollment/authentication or personal screenshot");
        }
        P1UiChecks.configuration=null;
    }
    private static Screen open(Instrumentation i){Activity activity=P1UiChecks.open(i,new Intent(i.getTargetContext(),LockActivity.class));P1Checks.require(activity instanceof Screen,"Only test-APK subclass substitutes system callback");return (Screen)activity;}
    private static void awaitRequests(Instrumentation i,Screen screen,int count)throws Exception {P1BatchChecks.await(() -> {int[] actual={0};i.runOnMainSync(() -> actual[0]=screen.replies.size());return actual[0]==count;},"Automatic synthetic prompt count "+count);}
}
