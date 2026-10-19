package org.stackdocs.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.database.sqlite.SQLiteConstraintException;
import android.util.Log;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

abstract class LibraryActivity extends Activity {
    private boolean resumed,gatePending;
    private final java.util.List<Runnable> deferred=new java.util.ArrayList<>();
    private final Runnable libraryListener = () -> {if(!app().access.allowed())gate();else onLibraryChanged();};
    @Override protected void onCreate(android.os.Bundle state){super.onCreate(state);if(app().access.enabled())getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);if(!app().access.allowed())getWindow().getDecorView().setAlpha(0);}
    private void gate(){if(!app().access.allowed()&&!isFinishing()){getWindow().getDecorView().setAlpha(0);if(resumed&&!gatePending&&!app().access.authenticating(this)){gatePending=true;startActivity(new android.content.Intent(this,LockActivity.class));}}}
    final void whenAllowed(Runnable action){if(app().access.allowed())action.run();else deferred.add(action);}

    @Override protected void onResume() { super.onResume(); resumed=true; app().addLibraryListener(libraryListener);
        if(!app().access.allowed()){gatePending=false;gate();return;}
        gatePending=false;
        getWindow().getDecorView().setAlpha(1);
        if(app().access.enabled())getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        java.util.List<Runnable> pending=new java.util.ArrayList<>(deferred);deferred.clear();for(Runnable action:pending)action.run(); }
    @Override protected void onPause() { resumed=false;app().removeLibraryListener(libraryListener); super.onPause(); }
    @Override protected void onStop(){if(app().access.enabled())getWindow().getDecorView().setAlpha(0);super.onStop();}
    @Override protected void onActivityResult(int request,int result,android.content.Intent data){super.onActivityResult(request,result,data);if(request==AccessPolicy.CREDENTIAL)app().access.credentialResult(this,result);}
    protected void onLibraryChanged() {}

    final StackApplication app() { return (StackApplication) getApplication(); }

    final <T> void work(Callable<T> operation, Consumer<T> success) {
        work(operation, success, () -> {});
    }

    final <T> void work(Callable<T> operation, Consumer<T> success, Runnable failure) {
        if(!app().access.allowed()){deferred.add(() -> work(operation,success,failure));return;}
        app().io.execute(() -> {
            if(!app().access.allowed()){runOnUiThread(() -> deferred.add(() -> work(operation,success,failure)));return;}
            try {
                T result = operation.call();
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) whenAllowed(() -> success.accept(result));
                });
            } catch (Exception error) {
                Log.e("Stack", "Local operation failed: "+error.getClass().getSimpleName());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    whenAllowed(() -> {
                    failure.run();
                    String message = error instanceof SQLiteConstraintException
                            ? "That name is already in use. Choose another name."
                            : error.getMessage();
                    new AlertDialog.Builder(this).setTitle("Couldn't finish")
                            .setMessage(message == null ? "Please try again." : message)
                            .setPositiveButton("OK", null).show();
                    });
                });
            }
        });
    }
}
