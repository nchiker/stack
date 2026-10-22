package org.stackdocs.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Consumer;
import javax.net.ssl.*;

/** Public discovery is only a hint. Committed comparison, pinned mTLS and
 * authenticated mutual approval precede enrollment or document transfer. */
final class NearbyPairing {
    static final int PORT=45874;
    static final int LIMIT=16*1024;
    private static final long BUNDLE_LIMIT=8L*1024*1024*1024+32L*1024*1024+12;
    static final class Control {
        final PairingTransport.SessionControl session=new PairingTransport.SessionControl();
        private String token,accepted;private DatagramSocket discovery;
        synchronized void show(String value){token=value;accepted=null;}
        synchronized boolean confirm(String value){if(session.canceled()||value==null||!value.equals(token))return false;accepted=value;notifyAll();return true;}
        synchronized void cancel(){session.cancel();if(discovery!=null)discovery.close();notifyAll();}
        synchronized void attach(DatagramSocket socket)throws IOException {session.check();discovery=socket;}
        synchronized void await(long deadline)throws Exception {while(!Objects.equals(token,accepted)||token==null){session.check();if(android.os.SystemClock.elapsedRealtime()>=deadline)throw new IOException("Verification expired. Start setup again.");wait(100);}}
    }
    static final class Result implements AutoCloseable {
        final SyncExchange.Staged baseline;final JSONObject device;final String group,host;final int port;
        Result(SyncExchange.Staged baseline,JSONObject device,String group,String host,int port){this.baseline=baseline;this.device=device;this.group=group;this.host=host;this.port=port;}
        @Override public void close(){if(baseline!=null)baseline.close();}
    }
    private final Context context;private final LibraryRepository repository;private final PairingTransport transport;
    NearbyPairing(Context context,LibraryRepository repository){this(context,repository,new PairingTransport(context,repository));}
    NearbyPairing(Context context,LibraryRepository repository,PairingTransport transport){this.context=context;this.repository=repository;this.transport=transport;}
    static boolean validName(String value){return value!=null&&!value.trim().isEmpty()&&value.equals(value.trim())&&value.codePointCount(0,value.length())<=64&&value.codePoints().noneMatch(Character::isISOControl);}
    static String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
    static byte[] unhex(String text,int maximum)throws IOException {if(text.isEmpty()||text.length()%2!=0||text.length()>maximum*2||!text.matches("[0-9a-fA-F]+"))throw new IOException("Invalid setup data");byte[] bytes=new byte[text.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);return bytes;}
    private static String random(int bytes){byte[] value=new byte[bytes];new SecureRandom().nextBytes(value);return hex(value);}
    private JSONObject local(String name)throws Exception {
        if(!validName(name))throw new IOException("Use a device name of 1–64 characters without control characters.");
        transport.identityInit();JSONObject status=new SyncStore(context,repository).status();String local=status.getString("replica"),group="",peer="";
        if(status.optBoolean("adopted")){JSONArray members=status.getJSONArray("members");if(members.length()!=2)throw new IOException("Nearby setup requires a two-device group.");group=status.getString("library");for(int i=0;i<2;i++)if(!members.getString(i).equals(local))peer=members.getString(i);}
        return new JSONObject().put("name",name).put("replica",local).put("certificate",hex(transport.identityCertificate())).put("group",group).put("peer",peer).put("nonce",random(32));
    }
    private static void field(DataOutputStream out,byte[] bytes)throws IOException {out.writeInt(bytes.length);out.write(bytes);}
    static byte[] canonical(char role,String invite,JSONObject value)throws Exception {
        SyncProtocol.exact(value,"name,replica,certificate,group,peer,nonce");String name=value.getString("name"),group=value.getString("group"),peer=value.getString("peer");
        if(!validName(name)||group.isEmpty()!=peer.isEmpty())throw new IOException("Invalid device details");SyncProtocol.uuid(value.getString("replica"));if(!group.isEmpty()){SyncProtocol.uuid(group);SyncProtocol.uuid(peer);}
        byte[] cert=unhex(value.getString("certificate"),LIMIT),nonce=unhex(value.getString("nonce"),32);if(nonce.length!=32)throw new IOException("Invalid setup nonce");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.write("STACKPAIR1/device".getBytes(StandardCharsets.US_ASCII));out.writeByte(role);
        field(out,invite.getBytes(StandardCharsets.UTF_8));field(out,name.getBytes(StandardCharsets.UTF_8));field(out,value.getString("replica").getBytes(StandardCharsets.US_ASCII));field(out,cert);field(out,group.getBytes(StandardCharsets.US_ASCII));field(out,peer.getBytes(StandardCharsets.US_ASCII));field(out,nonce);return bytes.toByteArray();
    }
    static String commitment(char role,String invite,JSONObject value)throws Exception {return PairingTransport.fingerprint(canonical(role,invite,value));}
    static String transcript(String invite,JSONObject computer,JSONObject phone)throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.write("STACKPAIR1/transcript".getBytes(StandardCharsets.US_ASCII));field(out,canonical('D',invite,computer));field(out,canonical('P',invite,phone));return PairingTransport.fingerprint(bytes.toByteArray());}
    static String code(String digest)throws IOException {byte[] bytes=unhex(digest,32);if(bytes.length!=32)throw new IOException("Invalid verification digest");long n=(((long)bytes[0]&255)<<24)|(((long)bytes[1]&255)<<16)|(((long)bytes[2]&255)<<8)|((long)bytes[3]&255);n%=1000000;return String.format(Locale.ROOT,"%03d %03d",n/1000,n%1000);}
    static JSONObject qr(String text)throws Exception {
        if(text.length()>2048)throw new IOException("This is not a Stack connection QR code.");JSONObject qr=ArchiveJson.read(text);SyncProtocol.exact(qr,"stack_pair,invite,fingerprint,port,addresses");
        if(qr.getInt("stack_pair")!=1||unhex(qr.getString("invite"),16).length!=16||unhex(qr.getString("fingerprint"),32).length!=32||qr.getInt("port")<1||qr.getInt("port")>65535)throw new IOException("Unsupported Stack connection QR code.");
        JSONArray addresses=qr.getJSONArray("addresses");if(addresses.length()==0||addresses.length()>8)throw new IOException("The computer has no usable local address.");
        for(int i=0;i<addresses.length();i++)if(!numericAddress(addresses.getString(i)))throw new IOException("QR code address is invalid.");return qr;
    }
    static boolean numericAddress(String value){try{if(value.length()>64||!value.matches("[0-9a-fA-F:.]+"))return false;InetAddress ip=InetAddress.getByName(value);return !ip.isAnyLocalAddress()&&!ip.isMulticastAddress()&&!ip.isLoopbackAddress();}catch(Exception error){return false;}}
    static JSONArray find(Control control,int port)throws Exception {
        String nonce=random(16);byte[] request=new JSONObject().put("stack_find",1).put("nonce",nonce).toString().getBytes(StandardCharsets.UTF_8);JSONArray found=new JSONArray();Set<String> seen=new HashSet<>();
        try(DatagramSocket socket=new DatagramSocket()) {
            control.attach(socket);socket.setBroadcast(true);socket.setSoTimeout(250);Set<InetAddress> broadcasts=new LinkedHashSet<>();broadcasts.add(InetAddress.getByName("255.255.255.255"));
            Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();while(interfaces!=null&&interfaces.hasMoreElements())for(InterfaceAddress address:interfaces.nextElement().getInterfaceAddresses())if(address.getBroadcast()!=null)broadcasts.add(address.getBroadcast());
            long until=android.os.SystemClock.elapsedRealtime()+5000,next=0;
            while(android.os.SystemClock.elapsedRealtime()<until){control.session.check();if(android.os.SystemClock.elapsedRealtime()>=next){for(InetAddress address:broadcasts)try{socket.send(new DatagramPacket(request,request.length,address,port));}catch(IOException ignored){}next=android.os.SystemClock.elapsedRealtime()+1000;}
                byte[] buffer=new byte[1024];DatagramPacket packet=new DatagramPacket(buffer,buffer.length);try{socket.receive(packet);}catch(SocketTimeoutException ignored){continue;}
                try{JSONObject reply=ArchiveJson.read(new String(buffer,0,packet.getLength(),StandardCharsets.UTF_8));SyncProtocol.exact(reply,"stack_pair,invite,name,port,nonce");String invite=reply.getString("invite"),host=packet.getAddress().getHostAddress();int service=reply.getInt("port");
                    if(reply.getInt("stack_pair")==1&&nonce.equals(reply.getString("nonce"))&&validName(reply.getString("name"))&&unhex(invite,16).length==16&&service>0&&service<65536&&seen.add(host+":"+invite))found.put(new JSONObject().put("name",reply.getString("name")).put("host",host).put("port",service).put("invite",invite));
                }catch(Exception ignored){}if(found.length()>=16)break;
            }
        }return found;
    }
    Result connect(String name,String host,int port,String invite,String expectedPin,Control control,Consumer<JSONObject> progress)throws Exception {
        if(unhex(invite,16).length!=16)throw new IOException("Invitation is invalid.");
        JSONObject own=local(name);long deadline=android.os.SystemClock.elapsedRealtime()+120000;
        java.util.concurrent.ScheduledExecutorService watchdog=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"Stack pairing deadline");thread.setDaemon(true);return thread;});watchdog.schedule(control::cancel,120,java.util.concurrent.TimeUnit.SECONDS);
        try(Socket raw=new Socket()) {
            control.session.attach(raw);raw.connect(new InetSocketAddress(host,port),8000);raw.setSoTimeout(10000);
            DataInputStream clearIn=new DataInputStream(raw.getInputStream());DataOutputStream clearOut=new DataOutputStream(raw.getOutputStream());
            PairingTransport.sendControl(clearOut,'I',new JSONObject().put("version",1).put("invite",invite).put("commitment",commitment('P',invite,own)));
            JSONObject committed=PairingTransport.readControl(clearIn,'C');SyncProtocol.exact(committed,"version,commitment");if(committed.getInt("version")!=1||unhex(committed.getString("commitment"),32).length!=32)throw new IOException("Invalid setup commitment");
            PairingTransport.sendControl(clearOut,'D',own);JSONObject remote=PairingTransport.readControl(clearIn,'D');
            if(!commitment('D',invite,remote).equals(committed.getString("commitment")))throw new IOException("The computer's verification changed.");
            byte[] cert=unhex(remote.getString("certificate"),LIMIT);String pin=PairingTransport.fingerprint(cert);
            if(!expectedPin.isEmpty()&&!expectedPin.equals(pin))throw new IOException("This is not the computer in the QR code.");
            if(own.getString("replica").equals(remote.getString("replica"))||Arrays.equals(unhex(own.getString("certificate"),LIMIT),cert))throw new IOException("Cannot connect a device to itself.");
            if((!own.getString("group").isEmpty()&&!own.getString("peer").equals(remote.getString("replica")))||(!remote.getString("group").isEmpty()&&!remote.getString("peer").equals(own.getString("replica")))||(!own.getString("group").isEmpty()&&!remote.getString("group").isEmpty()&&!own.getString("group").equals(remote.getString("group"))))throw new IOException("These libraries already belong to different device groups.");
            transport.checkSetupPeer(remote.getString("replica"),cert);
            String digest=transcript(invite,remote,own);control.show(digest);progress.accept(new JSONObject().put("phase","compare").put("token",digest).put("name",remote.getString("name")).put("code",code(digest)));control.await(deadline);
            JSONObject ephemeral=new JSONObject().put("certificate",android.util.Base64.encodeToString(cert,android.util.Base64.NO_WRAP));
            raw.setSoTimeout((int)Math.max(1,deadline-android.os.SystemClock.elapsedRealtime()));
            try(SSLSocket tls=(SSLSocket)transport.tls(ephemeral).getSocketFactory().createSocket(raw,"stackdocs.local",port,true)) {
                tls.setSoTimeout((int)Math.max(1,deadline-android.os.SystemClock.elapsedRealtime()));tls.setEnabledProtocols(Arrays.stream(tls.getSupportedProtocols()).filter(p->p.equals("TLSv1.2")||p.equals("TLSv1.3")).toArray(String[]::new));SSLParameters parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(parameters);tls.startHandshake();
                java.security.cert.Certificate[] chain=tls.getSession().getPeerCertificates();if(chain.length!=1||!pin.equals(PairingTransport.fingerprint(chain[0].getEncoded())))throw new IOException("Computer identity mismatch");
                DataInputStream in=new DataInputStream(new BufferedInputStream(tls.getInputStream(),65536));DataOutputStream out=new DataOutputStream(new BufferedOutputStream(tls.getOutputStream(),65536));
                JSONObject acceptance=new JSONObject().put("transcript",digest).put("accepted",true);PairingTransport.sendControl(out,'V',acceptance);JSONObject approved=PairingTransport.readControl(in,'V');SyncProtocol.exact(approved,"transcript,accepted");if(!digest.equals(approved.getString("transcript"))||!approved.getBoolean("accepted"))throw new IOException("The computer did not approve this connection.");
                watchdog.shutdownNow();control.session.check();tls.setSoTimeout(45000);JSONObject group=PairingTransport.readControl(in,'G');SyncProtocol.exact(group,"group,computer,phone,computer_sends");SyncProtocol.uuid(group.getString("group"));
                if(!group.getString("computer").equals(remote.getString("replica"))||!group.getString("phone").equals(own.getString("replica"))||(!own.getString("group").isEmpty()&&!group.getString("group").equals(own.getString("group")))||(!remote.getString("group").isEmpty()&&!group.getString("group").equals(remote.getString("group"))))throw new IOException("Connection group mismatch");
                transport.enrollOrResume(group.getString("group"),remote.getString("replica"),cert);
                saveLabels(name,remote,host,port);progress.accept(new JSONObject().put("phase","transferring").put("name",remote.getString("name")));
                SyncExchange.Staged staged=null;
                try {
                    if(group.getBoolean("computer_sends")) {
                        if(!own.getString("group").isEmpty())throw new IOException("Unexpected initial library transfer");
                        long size=PairingTransport.readHeader(in,'B',BUNDLE_LIMIT);PairingTransport.LimitedInput limited=new PairingTransport.LimitedInput(in,size);
                        SyncExchange exchange=new SyncExchange(context,repository,control.session::check);staged=exchange.stage(limited);if(limited.remaining!=0)throw new IOException("Incomplete setup transfer");
                        JSONObject review=exchange.reviewAdoption(staged);JSONArray members=review.getJSONArray("members");
                        if(!staged.header.getString("sender").equals(remote.getString("replica"))||!review.getString("library").equals(group.getString("group"))||members.length()!=2||!contains(members,own.getString("replica"))||!contains(members,remote.getString("replica")))throw new IOException("Received library does not match the verified devices.");
                        PairingTransport.sendControl(out,'S',new JSONObject().put("staged",true));
                    } else if(remote.getString("group").isEmpty()) {
                        transport.sendBundle(out,transport.policy(),control.session);JSONObject response=PairingTransport.readControl(in,'S');SyncProtocol.exact(response,"staged");if(!response.getBoolean("staged"))throw new IOException("Computer did not receive the library for review.");
                    }
                    control.session.check();return new Result(staged,remote,group.getString("group"),host,port);
                }catch(Exception error){if(staged!=null)staged.close();throw error;}
            }finally{control.session.release(raw);}
        }finally{watchdog.shutdownNow();}
    }
    private static boolean contains(JSONArray array,String id){for(int i=0;i<array.length();i++)if(id.equals(array.optString(i)))return true;return false;}
    private void saveLabels(String own,JSONObject remote,String host,int port)throws Exception {
        JSONObject labels=new JSONObject().put("own_name",own).put("peer_name",remote.getString("name")).put("peer_replica",remote.getString("replica")).put("fingerprint",PairingTransport.fingerprint(unhex(remote.getString("certificate"),LIMIT))).put("host",host).put("port",port).put("setup_pending",true);
        android.util.AtomicFile file=new android.util.AtomicFile(new File(context.getFilesDir(),"pairing/names.json"));FileOutputStream output=file.startWrite();try{output.write(labels.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(output);}catch(Exception error){file.failWrite(output);throw error;}
        // Approval is already authenticated. Retain the address before transfer,
        // so interruption cannot leave enrollment dependent on a UI callback.
        SyncJob.preferences(context).edit().putString("sync_host",host).putString("sync_port","45873").putString("sync_peer_name",remote.getString("name")).commit();
    }
}
