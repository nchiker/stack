package org.stackdocs.app;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;

/** Explicit phases for a disposable Android ↔ desktop mTLS session. */
final class PairingTransportChecks {
    static JSONObject run(Instrumentation test,Bundle args)throws Exception {
        Context target=test.getTargetContext();
        if(!ScannerChecks.checksTarget(target))throw new SecurityException("Disposable Checks installation required");
        String name=args.getString("replicaName","sync-lan-r6");
        if(!name.matches("sync-lan-[a-z0-9-]+"))throw new IllegalArgumentException("Isolated LAN replica name required");
        Context isolated=new ScannerChecks.IsolatedContext(target,name);
        LibraryRepository repository=new LibraryRepository(isolated);repository.initialize();
        PairingTransport transport=new PairingTransport(isolated,repository);
        SyncStore store=new SyncStore(isolated,repository);
        String phase=args.getString("transportPhase","identity");JSONObject output;
        switch(phase) {
            case "identity": {
                String pin=transport.identityInit();byte[] cert=transport.identityCertificate();
                File file=new File(isolated.getFilesDir(),"lan-identity.der");
                try(FileOutputStream out=new FileOutputStream(file)){out.write(cert);out.getFD().sync();}
                output=store.status().put("fingerprint",pin).put("certificate_path",file.getAbsolutePath());break;
            }
            case "adopt": {
                File file=new File(isolated.getFilesDir(),"lan-baseline.stackexchange");
                String group=required(args,"group"),peer=required(args,"peer");
                SyncExchange exchange=new SyncExchange(isolated,repository);
                try(FileInputStream in=new FileInputStream(file);SyncExchange.Staged staged=exchange.stage(in)){
                    output=exchange.importFile(staged,group,Arrays.asList(peer,store.replica()));
                }break;
            }
            case "enroll": {
                byte[] peer=Files.readAllBytes(new File(isolated.getFilesDir(),"lan-peer.der").toPath());
                transport.enroll(required(args,"group"),required(args,"peer"),peer,required(args,"fingerprint"));
                output=transport.status();break;
            }
            case "wrong-fingerprint": {
                byte[] peer=Files.readAllBytes(new File(isolated.getFilesDir(),"lan-peer.der").toPath());
                boolean rejected=false;
                try{transport.enroll(required(args,"group"),required(args,"peer"),peer,"0".repeat(64));}
                catch(Exception expected){rejected=true;}
                if(!rejected)throw new AssertionError("Unverified peer enrolled");
                output=new JSONObject().put("rejected",true);break;
            }
            case "sync": output=transport.syncNow(required(args,"host"),Integer.parseInt(required(args,"port")));break;
            case "cancel": {
                PairingTransport.SessionControl control=new PairingTransport.SessionControl();long start=android.os.SystemClock.elapsedRealtime();boolean rejected=false;
                File signal=new File(isolated.getFilesDir(),"cancel-signal");signal.delete();boolean waitForSignal="true".equals(args.getString("waitForSignal"));
                Thread cancel=new Thread(()->{try{if(waitForSignal){long deadline=android.os.SystemClock.elapsedRealtime()+10000;while(!signal.isFile()&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(10);}else Thread.sleep(350);}catch(InterruptedException ignored){}control.cancel();},"checks-cancel");cancel.start();
                try{transport.syncNow(required(args,"host"),Integer.parseInt(required(args,"port")),control);}catch(Exception expected){rejected=true;}finally{cancel.join(1500);}
                long elapsed=android.os.SystemClock.elapsedRealtime()-start;if(!rejected||!control.canceled()||elapsed>5000)throw new AssertionError("Connection cancellation did not interrupt blocked TLS");
                output=new JSONObject().put("canceled",true).put("elapsed_ms",elapsed);break;
            }
            case "reject-session": {
                store.capture();String prior=SyncProtocol.canonical(store.status());boolean rejected=false;
                try{transport.syncNow(required(args,"host"),Integer.parseInt(required(args,"port")));}catch(Exception expected){rejected=true;output=new JSONObject().put("reason",String.valueOf(expected.getMessage()));}
                if(!rejected||!prior.equals(SyncProtocol.canonical(store.status())))throw new AssertionError("Rejected session changed causal state");
                output=new JSONObject().put("rejected",true);break;
            }
            case "identity-reset-refused": {
                String before=PairingTransport.fingerprint(transport.identityCertificate());boolean rejected=false;try{transport.identityReplace();}catch(Exception expected){rejected=true;}
                if(!rejected||!before.equals(PairingTransport.fingerprint(transport.identityCertificate())))throw new AssertionError("Active identity replaced without revocation");output=new JSONObject().put("rejected",true);break;
            }
            case "revoke": transport.revoke();output=transport.status();break;
            case "revoked-reject": {
                boolean rejected=false;try{transport.syncNow(required(args,"host"),Integer.parseInt(required(args,"port")));}
                catch(Exception expected){rejected=true;}
                if(!rejected)throw new AssertionError("Revoked peer was accepted");
                output=new JSONObject().put("rejected",true);break;
            }
            default:throw new IllegalArgumentException("Unknown phase");
        }
        return output;
    }
    private static String required(Bundle args,String key){String value=args.getString(key);if(value==null||value.isEmpty())throw new IllegalArgumentException(key+" required");return value;}
}
