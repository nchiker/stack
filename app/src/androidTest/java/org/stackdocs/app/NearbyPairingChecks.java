package org.stackdocs.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Authored child libraries only; never enroll, reset or apply to the live library. */
final class NearbyPairingChecks {
    static final class FixtureContext extends ContextWrapper {
        private final String name;
        FixtureContext(Context base,String name){super(new ScannerChecks.IsolatedContext(base,name));this.name=name;}
        @Override public Context getApplicationContext(){return this;}
        @Override public File getNoBackupFilesDir(){File dir=new File(getFilesDir(),"no-backup");if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("Fixture no-backup directory");return dir;}
        @Override public android.content.SharedPreferences getSharedPreferences(String key,int mode){return super.getSharedPreferences("fixture-"+name+"-"+key,mode);}
    }
    static JSONObject run(Instrumentation test,Bundle args)throws Exception {
        if(!ScannerChecks.disposableLibraryTarget(test.getTargetContext()))throw new SecurityException("Explicit disposable target required");
        String name=args.getString("nearbyFixture","nearby-authored");if(!name.matches("nearby-[a-z0-9-]+"))throw new IllegalArgumentException("Authored fixture name required");
        Context context=new FixtureContext(test.getTargetContext(),name);LibraryRepository repository=new LibraryRepository(context);repository.initialize();
        PairingTransport transport=new PairingTransport(context,repository,"stack-checks-"+name);SyncStore store=new SyncStore(context,repository);
        String phase=args.getString("nearbyPhase","unit");
        switch(phase){
            case "unit":return unit(context);
            case "prepare":{
                if(repository.documents("",null).isEmpty())repository.importDocument(android.net.Uri.fromFile(OcrQueueChecks.fixture(context,"nearby-phone.pdf")));
                return store.status().put("files",context.getFilesDir().getAbsolutePath());
            }
            case "start-group":return store.start(Collections.singletonList(required(args,"peer")));
            case "discover":{
                JSONArray found=NearbyPairing.find(new NearbyPairing.Control(),Integer.parseInt(args.getString("port","45874")));
                write(new File(context.getFilesDir(),"discovery.json"),found.toString());return new JSONObject().put("found",found);
            }
            case "connect":case "reject":case "decline":{
                NearbyPairing.Control control=new NearbyPairing.Control();File approval=new File(context.getFilesDir(),"pair-approve.txt");approval.delete();File preview=new File(context.getFilesDir(),"pair-preview.json");preview.delete();
                String before=SyncProtocol.canonical(store.status());boolean rejecting=phase.equals("reject");
                try(NearbyPairing.Result received=new NearbyPairing(context,repository,transport).connect("Authored phone",required(args,"host"),Integer.parseInt(required(args,"port")),required(args,"invite"),args.getString("pin",""),control,event->{try{
                    write(preview,event.toString());if(event.optString("phase").equals("compare")){if(rejecting){control.cancel();return;}long until=System.currentTimeMillis()+45000;while(!approval.isFile()&&System.currentTimeMillis()<until)Thread.sleep(25);if(!approval.isFile()||!new String(java.nio.file.Files.readAllBytes(approval.toPath()),StandardCharsets.UTF_8).trim().equals(event.optString("token")))throw new IOException("Host did not approve this authored transcript");control.confirm(event.optString("token"));}
                }catch(Exception error){throw new RuntimeException(error);}})){
                    if(rejecting)throw new AssertionError("Rejected setup completed");
                    JSONObject result=new JSONObject().put("verified",true).put("group",received.group).put("staged",received.baseline!=null);
                    if(received.baseline!=null){SyncExchange exchange=new SyncExchange(context,repository);JSONObject review=exchange.reviewAdoption(received.baseline);result.put("review",review);
                        if(!phase.equals("decline")){exchange.importReviewed(received.baseline,review);result.put("adopted",true);}
                        else {if(!SyncProtocol.canonical(store.status()).equals(before))throw new AssertionError("Declined review changed the library");result.put("declined_without_library_change",true);}
                    }
                    return result.put("status",store.status());
                }catch(Exception error){if(!rejecting)throw error;if(!SyncProtocol.canonical(store.status()).equals(before)||new File(context.getFilesDir(),"pairing/policy.json").exists())throw new AssertionError("Rejected verification enrolled or changed data",error);return new JSONObject().put("rejected_without_enrollment",true).put("status",store.status());}
            }
            case "sync":return transport.syncNow(required(args,"host"),Integer.parseInt(args.getString("port","45873")));
            case "inspect":return store.status().put("documents",repository.documents("",null).size());
            default:throw new IllegalArgumentException("Unknown nearby phase");
        }
    }
    static JSONObject unit(Context context)throws Exception {
        require(NearbyPairing.validName("Charlie's computer 🦀")&&!NearbyPairing.validName(" ")&&!NearbyPairing.validName("fake\nname"),"Friendly-name bounds");
        JSONObject computer=new JSONObject().put("name","Authored computer 🦀").put("replica","10000000-0000-4000-8000-000000000001").put("certificate","010203").put("group","").put("peer","").put("nonce","11".repeat(32));
        JSONObject phone=new JSONObject(computer.toString()).put("name","Authored phone").put("replica","20000000-0000-4000-8000-000000000002").put("certificate","040506").put("nonce","22".repeat(32));String invite="33".repeat(16);
        String digest=NearbyPairing.transcript(invite,computer,phone);write(new File(context.getFilesDir(),"canonical-vector.json"),new JSONObject().put("computer_commitment",NearbyPairing.commitment('D',invite,computer)).put("phone_commitment",NearbyPairing.commitment('P',invite,phone)).put("transcript",digest).put("code",NearbyPairing.code(digest)).toString());
        NearbyPairing.Control control=new NearbyPairing.Control();control.show(digest);require(!control.confirm("stale"),"Stale approval rejected");require(control.confirm(digest),"Current approval accepted");control.cancel();boolean canceled=false;try{control.session.check();}catch(IOException expected){canceled=true;}require(canceled,"Cancel state");
        String text=new JSONObject().put("stack_pair",1).put("invite",invite).put("fingerprint","44".repeat(32)).put("port",45874).put("addresses",new JSONArray().put("192.168.1.20")).toString();require(NearbyPairing.qr(text).getInt("port")==45874,"Stack QR parsed");boolean bad=false;try{NearbyPairing.qr("https://example.invalid");}catch(Exception expected){bad=true;}require(bad,"Other QR rejected");
        com.google.zxing.common.BitMatrix matrix=new com.google.zxing.qrcode.QRCodeWriter().encode(text,com.google.zxing.BarcodeFormat.QR_CODE,512,512);android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(512,512,android.graphics.Bitmap.Config.ARGB_8888);try{for(int y=0;y<512;y++)for(int x=0;x<512;x++)bitmap.setPixel(x,y,matrix.get(x,y)?android.graphics.Color.BLACK:android.graphics.Color.WHITE);require(PairingQrActivity.decodeBitmap(bitmap).equals(text),"Actual QR image decoder roundtrip");}finally{bitmap.recycle();}
        File rustQr=new File(context.getFilesDir(),"rust-pairing-qr.png");if(rustQr.isFile()){android.graphics.Bitmap image=android.graphics.BitmapFactory.decodeFile(rustQr.getPath());try{NearbyPairing.qr(PairingQrActivity.decodeBitmap(image));}finally{image.recycle();}}
        return new JSONObject().put("passed",true).put("transcript",digest).put("code",NearbyPairing.code(digest));
    }
    static void write(File file,String value)throws Exception{try(FileOutputStream output=new FileOutputStream(file)){output.write(value.getBytes(StandardCharsets.UTF_8));output.getFD().sync();}}
    static String required(Bundle args,String key){String value=args.getString(key);if(value==null||value.isEmpty())throw new IllegalArgumentException(key+" required");return value;}
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
