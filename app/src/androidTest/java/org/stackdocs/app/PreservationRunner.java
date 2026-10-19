package org.stackdocs.app;

/** Read-only upgrade probe, serialized with repository writes for a consistent pair of fingerprints. */
public final class PreservationRunner extends android.app.Instrumentation {
    @Override public void onCreate(android.os.Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){android.os.Bundle result=new android.os.Bundle();try{
        runOnMainSync(() -> {}); // Application.onCreate finishes and queues repository initialization first.
        StackApplication app=(StackApplication)getTargetContext().getApplicationContext();
        String evidence=app.io.submit(() -> "\n"+PreservationChecks.fingerprint(getTargetContext())+"\nInstalled schema="+schema()+"\nPROJECTION_JSON "+PreservationProjection.read(getTargetContext())+"\n").get(120,java.util.concurrent.TimeUnit.SECONDS);
        result.putString("stream",evidence);finish(android.app.Activity.RESULT_OK,result);
    }catch(Exception failure){result.putString("stream","Fingerprint failed: "+failure.getClass().getSimpleName());finish(android.app.Activity.RESULT_CANCELED,result);}}
    private int schema(){try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(getTargetContext().getDatabasePath("library.db").getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY)){return db.getVersion();}}
}
