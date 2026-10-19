package org.stackdocs.app;

import android.app.*;
import android.content.*;
import android.hardware.biometrics.*;
import android.os.*;
import android.view.WindowManager;
import java.util.function.Consumer;

/** Device-local access gate. No encryption, app PIN, biometric material or portable authorization. */
final class AccessPolicy implements Application.ActivityLifecycleCallbacks {
    static final int CREDENTIAL=7601;
    static final class Session {
        private volatile boolean authenticated;private long generation;
        synchronized long begin(){authenticated=false;return ++generation;}
        synchronized boolean result(long token,boolean success){if(token!=generation)return false;if(success)authenticated=true;return success;}
        synchronized void lock(){authenticated=false;generation++;}
        synchronized void cover(){authenticated=false;}
        boolean authenticated(){return authenticated;}
    }
    private final StackApplication app;private final SharedPreferences policy;final Session session=new Session();
    private int started;private Request request;
    private static final class Request{final Activity owner;final long token;final Consumer<Boolean> result;CancellationSignal cancellation;boolean credential;
        Request(Activity owner,long token,Consumer<Boolean> result){this.owner=owner;this.token=token;this.result=result;}}
    AccessPolicy(StackApplication app){this.app=app;policy=app.getSharedPreferences("local_policy",Context.MODE_PRIVATE);app.registerActivityLifecycleCallbacks(this);BroadcastReceiver screen=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){lock();}};if(Build.VERSION.SDK_INT>=33)app.registerReceiver(screen,new IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED);else app.registerReceiver(screen,new IntentFilter(Intent.ACTION_SCREEN_OFF));}
    boolean authenticating(Activity activity){return request!=null&&request.owner==activity;}
    boolean screenLockInProgress(Activity activity){return request!=null&&request.owner==activity&&request.credential;}
    boolean enabled(){return policy.getBoolean("app_lock",false);}
    boolean allowed(){return !enabled()||(session.authenticated()&&!app.getSystemService(KeyguardManager.class).isDeviceLocked());}
    void lock(){session.lock();if(!enabled())return;if(request!=null&&!request.credential)cancel(request);if(app.archives!=null&&app.archives.busy())app.archives.cancel();app.notifyLibraryChanged();}
    void authenticate(Activity activity,Consumer<Boolean> callback) {
        if(request!=null)cancel(request);
        KeyguardManager keyguard=app.getSystemService(KeyguardManager.class);
        if(!keyguard.isDeviceSecure()){new AlertDialog.Builder(activity).setTitle("Set up an Android screen lock").setMessage("Set an Android screen lock, then return to Stack.").setPositiveButton("OK",null).show();callback.accept(false);return;}
        Request next=new Request(activity,session.begin(),callback);request=next;
        try {
        if(Build.VERSION.SDK_INT>=30) {
            next.cancellation=new CancellationSignal();
            new BiometricPrompt.Builder(activity).setTitle("Unlock Stack").setSubtitle("Use your fingerprint or screen lock")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG|BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build().authenticate(next.cancellation,activity.getMainExecutor(),new BiometricPrompt.AuthenticationCallback(){
                        @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){complete(next,true);}
                        @Override public void onAuthenticationError(int error,CharSequence message){complete(next,false);}
                    });
        } else if(Build.VERSION.SDK_INT>=28 && activity.getSystemService(android.hardware.fingerprint.FingerprintManager.class)!=null
                && activity.getSystemService(android.hardware.fingerprint.FingerprintManager.class).hasEnrolledFingerprints()) {
            next.cancellation=new CancellationSignal();
            new BiometricPrompt.Builder(activity).setTitle("Unlock Stack").setNegativeButton("Use screen lock",activity.getMainExecutor(),(d,w) -> credential(next)).build()
                    .authenticate(next.cancellation,activity.getMainExecutor(),new BiometricPrompt.AuthenticationCallback(){
                        @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){complete(next,true);}
                        @Override public void onAuthenticationError(int error,CharSequence message){if(next.credential)return;if(error==BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED||error==BiometricPrompt.BIOMETRIC_ERROR_CANCELED)complete(next,false);else credential(next);}
                    });
        } else credential(next);
        } catch(RuntimeException unavailable) {complete(next,false);}
    }
    @SuppressWarnings("deprecation") private void credential(Request next){if(request!=next||next.credential)return;Intent intent=app.getSystemService(KeyguardManager.class).createConfirmDeviceCredentialIntent("Unlock Stack","Confirm your Android screen lock");if(intent==null){complete(next,false);return;}next.credential=true;try{next.owner.startActivityForResult(intent,CREDENTIAL);}catch(RuntimeException unavailable){complete(next,false);}}
    void credentialResult(Activity owner,int result){Request next=request;if(next!=null&&next.owner==owner&&next.credential)complete(next,result==Activity.RESULT_OK);}
    private void complete(Request next,boolean success){if(request!=next||next.owner.isDestroyed())return;request=null;boolean granted=session.result(next.token,success);next.result.accept(granted);app.notifyLibraryChanged();if(granted)resume();}
    private void cancel(Request next){if(request!=next)return;request=null;if(next.cancellation!=null)next.cancellation.cancel();session.lock();if(!next.owner.isDestroyed())next.result.accept(false);}
    private void resume(){app.io.execute(() -> {app.library.recoverOcr();app.library.suggestions.recover();app.library.metadata.recover();app.ocr.kick();app.suggestions.kick();app.metadata.kick();app.batches.recover();});app.photos.configure();app.photos.kick(null);app.notifyLibraryChanged();}
    void change(Activity activity,boolean enabled,Runnable changed){new AlertDialog.Builder(activity).setTitle(enabled?"Turn on app lock?":"Turn off app lock?").setMessage(enabled?"Unlock with Android when you return to Stack or turn on the screen.":"Unlock once to turn off app lock.").setNegativeButton("Cancel",null).setPositiveButton("Continue",(d,w) -> authenticate(activity,success -> {if(success){boolean previous=enabled();if(!policy.edit().putBoolean("app_lock",enabled).commit()){
                    // SharedPreferences mutates its in-memory map even when the disk write fails.
                    policy.edit().putBoolean("app_lock",previous).commit();
                    new AlertDialog.Builder(activity).setMessage("The setting couldn't be saved. Existing lock policy is kept; free storage and retry.").setPositiveButton("OK",null).show();
                }
                if(enabled())activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }changed.run();})).show();}
    @Override public void onActivityStarted(Activity activity){started++;}
    @Override public void onActivityStopped(Activity activity){started=Math.max(0,started-1);if(enabled()&&started==0&&!activity.isChangingConfigurations()){
        if(request!=null&&request.credential)session.cover();else lock();
    }}
    @Override public void onActivityDestroyed(Activity activity){if(request!=null&&request.owner==activity)cancel(request);}
    @Override public void onActivityCreated(Activity a,Bundle b){} @Override public void onActivityResumed(Activity a){} @Override public void onActivityPaused(Activity a){} @Override public void onActivitySaveInstanceState(Activity a,Bundle b){}
}
