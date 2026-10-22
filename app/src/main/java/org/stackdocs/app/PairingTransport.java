package org.stackdocs.app;

import android.content.Context;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

/** Explicit foreground two-member mTLS client. The desktop provides the
 * one-shot listener. Bundle validation and durable apply stay in SyncExchange. */
final class PairingTransport {
    private static final String TLS_NAME="stackdocs.local";
    private static final byte[] MAGIC="STACKLAN1".getBytes(StandardCharsets.US_ASCII);
    private static final long BUNDLE_LIMIT=8L*1024*1024*1024+32L*1024*1024+12;
    private static final int CONTROL_LIMIT=16*1024, TIMEOUT_MS=45000, PROCESSING_TIMEOUT_MS=180000;
    static final class SessionControl {
        private final AtomicBoolean canceled=new AtomicBoolean();private Socket socket;
        synchronized void attach(Socket value)throws IOException {if(canceled.get())throw new IOException("Connection canceled");socket=value;}
        synchronized void release(Socket value){if(socket==value)socket=null;}
        synchronized void cancel(){canceled.set(true);if(socket!=null)try{socket.close();}catch(IOException ignored){}}
        boolean canceled(){return canceled.get();}
        void check()throws IOException {if(canceled())throw new IOException("Connection canceled");}
    }
    private final String alias;
    private final Context context;
    private final SyncStore store;
    private final AtomicFile policyFile;

    PairingTransport(Context context,LibraryRepository repository) {this(context,repository,"stack-pairing-v1");}
    PairingTransport(Context context,LibraryRepository repository,String alias) {
        this.alias=alias;
        this.context=context.getApplicationContext();
        store=new SyncStore(context,repository);
        File directory=new File(this.context.getFilesDir(),"pairing");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("Could not create private pairing directory");
        if(!directory.setReadable(false,false)||!directory.setWritable(false,false)||!directory.setExecutable(false,false)
            ||!directory.setReadable(true,true)||!directory.setWritable(true,true)||!directory.setExecutable(true,true))
            throw new IllegalStateException("Could not protect pairing directory");
        policyFile=new AtomicFile(new File(directory,"policy.json"));
    }

    static String fingerprint(byte[] der) {
        try {byte[] digest=MessageDigest.getInstance("SHA-256").digest(der);StringBuilder result=new StringBuilder(64);
            for(byte octet:digest)result.append(String.format(java.util.Locale.ROOT,"%02x",octet&255));return result.toString();
        }catch(java.security.NoSuchAlgorithmException error){throw new AssertionError(error);}
    }

    private KeyStore keys()throws Exception {KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);return keys;}

    /** Android Keystore signs its own public certificate; the private key is
     * nonexportable and remains outside portable Stack archives. */
    String identityInit()throws Exception {
        KeyStore keys=keys();if(!keys.containsAlias(alias)) {
            KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");
            long now=System.currentTimeMillis();byte[] serial=new byte[16];new SecureRandom().nextBytes(serial);
            generator.initialize(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                // Conscrypt hashes TLS's CertificateVerify message itself and
                // delegates NONEwithECDSA to the nonexportable Keystore key.
                .setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(new X500Principal("CN=Stack paired device"))
                .setCertificateSerialNumber(new BigInteger(1,serial))
                .setCertificateNotBefore(new Date(now-3600000L))
                .setCertificateNotAfter(new Date(now+10L*365*24*60*60*1000))
                .build());generator.generateKeyPair();
        }
        PrivateKey key=(PrivateKey)keys().getKey(alias,null);
        KeyInfo info=KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore").getKeySpec(key,KeyInfo.class);
        if(!Arrays.asList(info.getDigests()).contains(KeyProperties.DIGEST_NONE))
            throw new IOException("This pairing identity cannot sign TLS. Create a fresh identity and re-enroll the peer; the existing identity was kept.");
        return fingerprint(identityCertificate());
    }

    byte[] identityCertificate()throws Exception {
        Certificate certificate=keys().getCertificate(alias);
        if(certificate==null)throw new IOException("Create a pairing identity first");
        return certificate.getEncoded();
    }

    private static X509Certificate certificate(byte[] der)throws Exception {
        if(der.length==0||der.length>CONTROL_LIMIT)throw new IOException("Peer certificate size invalid");
        CertificateFactory factory=CertificateFactory.getInstance("X.509");
        X509Certificate cert=(X509Certificate)factory.generateCertificate(new ByteArrayInputStream(der));
        if(!Arrays.equals(der,cert.getEncoded()))throw new IOException("Peer certificate has trailing data");
        cert.checkValidity();cert.verify(cert.getPublicKey());
        if(cert.getBasicConstraints()>=0)throw new IOException("Peer certificate must be an end-entity identity");
        return cert;
    }

    /** The supplied fingerprint must have been compared through a separate,
     * trusted UI or QR channel. Network discovery only supplies an address. */
    void enroll(String group,String peerReplica,byte[] peerDer,String verifiedFingerprint)throws Exception {
        SyncProtocol.uuid(group);SyncProtocol.uuid(peerReplica);
        String local=store.replica();if(local.equals(peerReplica))throw new IOException("Cannot pair with this replica");
        X509Certificate peer=certificate(peerDer);
        String actual=fingerprint(peerDer);
        if(!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),verifiedFingerprint.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII))
            ||actual.equals(fingerprint(identityCertificate())))throw new IOException("Verified peer fingerprint does not match");
        JSONObject status=store.status();
        if(status.optBoolean("adopted")) {
            JSONArray members=status.getJSONArray("members");
            if(!group.equals(status.getString("library"))||members.length()!=2||!contains(members,local)||!contains(members,peerReplica))
                throw new IOException("Network transport requires the frozen two-member group");
        }
        if(policyFile.getBaseFile().exists())throw new IOException("Peer already enrolled; revoked identities cannot silently rejoin");
        JSONObject policy=new JSONObject().put("version",1).put("group",group).put("peer_replica",peerReplica)
            .put("fingerprint",actual).put("certificate",Base64.encodeToString(peer.getEncoded(),Base64.NO_WRAP)).put("revoked",false);
        savePolicy(policy);
    }


    // A resumed setup may reuse exactly the same verified identity and group.
    // Revoked or changed policies still require the explicit replacement flow.
    void enrollOrResume(String group,String peer,byte[] der)throws Exception {
        if(policyFile.getBaseFile().exists()) {
            JSONObject old=readPolicy();
            if(!group.equals(old.getString("group"))||!peer.equals(old.getString("peer_replica"))||!fingerprint(der).equals(old.getString("fingerprint")))
                throw new IOException("A different computer is already enrolled");
            if(old.getBoolean("revoked")){old.put("revoked",false);savePolicy(old);}
            ensurePolicyCurrent(old);return;
        }
        enroll(group,peer,der,fingerprint(der));
    }
    void checkSetupPeer(String peer,byte[] der)throws Exception {
        certificate(der);
        if(policyFile.getBaseFile().exists()) {
            JSONObject old=readPolicy();
            if(!peer.equals(old.getString("peer_replica"))||!fingerprint(der).equals(old.getString("fingerprint")))throw new IOException("A different computer is already enrolled");
        }
    }

    void revoke()throws Exception {JSONObject policy=readPolicy();policy.put("revoked",true);savePolicy(policy);}

    /** Only after explicit confirmation: discard this app's old pairing key.
     * Library originals and causal state are independent of the transport key. */
    String identityReplace()throws Exception {
        if(policyFile.getBaseFile().exists()&&!readPolicy().getBoolean("revoked"))throw new IOException("Revoke the current peer before creating a replacement identity");
        keys().deleteEntry(alias);policyFile.delete();return identityInit();
    }

    JSONObject status()throws Exception {
        return connectionDetails(store.status());
    }
    JSONObject connectionStatus()throws Exception {
        return connectionDetails(new JSONObject().put("adopted",android.database.DatabaseUtils.longForQuery(store.db,"SELECT count(*) FROM sync_state WHERE singleton=1",null)>0));
    }
    private JSONObject connectionDetails(JSONObject status)throws Exception {
        boolean identity=keys().containsAlias(alias);status.put("identity_created",identity);if(identity)status.put("identity_fingerprint",fingerprint(identityCertificate()));
        if(policyFile.getBaseFile().exists()) {JSONObject policy=readPolicy();status.put("paired_peer",policy.getString("peer_replica"));status.put("peer_fingerprint",policy.getString("fingerprint"));status.put("revoked",policy.getBoolean("revoked"));}
        status.put("setup_pending",status.has("paired_peer")&&!status.optBoolean("adopted"));
        File labels=new File(context.getFilesDir(),"pairing/names.json");
        if(labels.isFile()&&labels.length()<16384)try{JSONObject names=ArchiveJson.read(new String(new AtomicFile(labels).readFully(),StandardCharsets.UTF_8));if(status.optString("peer_fingerprint").equals(names.optString("fingerprint"))){status.put("peer_name",names.optString("peer_name"));status.put("own_name",names.optString("own_name"));status.put("host",names.optString("host"));status.put("setup_pending",!status.optBoolean("adopted")||names.optBoolean("setup_pending"));}}catch(Exception ignored){}
        return status;
    }

    void markComplete()throws Exception {
        AtomicFile file=new AtomicFile(new File(context.getFilesDir(),"pairing/names.json"));
        if(!file.getBaseFile().isFile())return;
        JSONObject names=ArchiveJson.read(new String(file.readFully(),StandardCharsets.UTF_8));
        if(!readPolicy().getString("fingerprint").equals(names.optString("fingerprint")))return;
        names.put("setup_pending",false);FileOutputStream output=file.startWrite();
        try{output.write(names.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(output);}catch(Exception error){file.failWrite(output);throw error;}
    }

    private JSONObject readPolicy()throws Exception {
        if(!policyFile.getBaseFile().isFile())throw new IOException("Enroll a verified peer first");
        byte[] bytes=policyFile.readFully();if(bytes.length>CONTROL_LIMIT)throw new IOException("Pairing policy too large");
        JSONObject policy=ArchiveJson.read(utf8(bytes));
        SyncProtocol.exact(policy,"version,group,peer_replica,fingerprint,certificate,revoked");
        if(policy.getInt("version")!=1)throw new IOException("Pairing policy unsupported");
        SyncProtocol.uuid(policy.getString("group"));SyncProtocol.uuid(policy.getString("peer_replica"));
        byte[] der=Base64.decode(policy.getString("certificate"),Base64.DEFAULT);
        if(!fingerprint(der).equals(policy.getString("fingerprint")))throw new IOException("Peer certificate changed");
        certificate(der);
        return policy;
    }

    JSONObject policy()throws Exception {
        JSONObject policy=readPolicy();
        if(policy.getBoolean("revoked"))throw new IOException("Peer revoked");
        return policy;
    }

    private void ensurePolicyCurrent(JSONObject expected)throws Exception {
        JSONObject current=policy();
        for(String field:new String[]{"group","peer_replica","fingerprint","certificate"})
            if(!current.getString(field).equals(expected.getString(field)))throw new IOException("Paired peer changed during session");
    }

    private void savePolicy(JSONObject policy)throws IOException {
        byte[] bytes=policy.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>CONTROL_LIMIT)throw new IOException("Pairing policy too large");
        FileOutputStream out=policyFile.startWrite();try{out.write(bytes);policyFile.finishWrite(out);}catch(IOException error){policyFile.failWrite(out);throw error;}
        File file=policyFile.getBaseFile();
        if(!file.setReadable(false,false)||!file.setWritable(false,false)
            ||!file.setReadable(true,true)||!file.setWritable(true,true))
            throw new IOException("Could not protect pairing policy");
    }

    private static boolean contains(JSONArray array,String value)throws JSONException {
        for(int i=0;i<array.length();i++)if(value.equals(array.getString(i)))return true;return false;
    }

    private JSONObject hello(JSONObject policy,SessionControl control)throws Exception {
        // Only group identity is needed; a full status also scans every document.
        SyncStore current=new SyncStore(context,store.repository,control::check);SyncProtocol.State state=current.load();String local=current.replica();
        if(state==null)throw new IOException("Start or adopt the group before Sync now");
        if(!policy.getString("group").equals(state.library)||state.members.size()!=2
            ||!state.members.contains(local)||!state.members.contains(policy.getString("peer_replica")))
            throw new IOException("Network transport requires the frozen two-member group");
        return new JSONObject().put("version",1).put("group",policy.getString("group")).put("replica",local);
    }

    SSLContext tls(JSONObject policy)throws Exception {
        KeyStore identity=keys();
        PrivateKey key=(PrivateKey)identity.getKey(alias,null);
        X509Certificate own=(X509Certificate)identity.getCertificate(alias);
        if(key==null||own==null)throw new IOException("Create a pairing identity first");
        X509Certificate peer=certificate(Base64.decode(policy.getString("certificate"),Base64.DEFAULT));
        KeyStore roots=KeyStore.getInstance(KeyStore.getDefaultType());roots.load(null);roots.setCertificateEntry("verified-peer",peer);
        TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(roots);
        // Standard platform PKIX validation, followed by exact enrollment pin.
        boolean verifier=false;for(javax.net.ssl.TrustManager manager:trust.getTrustManagers())if(manager instanceof X509TrustManager)verifier=true;
        if(!verifier)throw new IOException("Platform PKIX verifier unavailable");
        SSLContext tls=SSLContext.getInstance("TLS");
        tls.init(new KeyManager[]{new IdentityManager(alias,key,new X509Certificate[]{own})},trust.getTrustManagers(),null);
        return tls;
    }

    /** Must be called from the app's foreground IO executor after an explicit
     * user action. A manual address is used; no background discovery is active. */
    JSONObject syncNow(String host,int port)throws Exception {
        return syncNow(host,port,new SessionControl()).put("status",store.status());
    }
    JSONObject syncNow(String host,int port,SessionControl control)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("Run Sync now on the foreground IO executor");
        control.check();
        if(host==null||host.isEmpty()||port<1||port>65535)throw new IOException("Choose a valid peer address");
        JSONObject policy=policy(),local=hello(policy,control);
        SSLContext tls=tls(policy);
        try(Socket raw=new Socket()) {
            control.attach(raw);
            raw.connect(new InetSocketAddress(host,port),8000);raw.setSoTimeout(TIMEOUT_MS);
            try(SSLSocket socket=(SSLSocket)tls.getSocketFactory().createSocket(raw,TLS_NAME,port,true)) {
                socket.setSoTimeout(TIMEOUT_MS);
                socket.setEnabledProtocols(Arrays.stream(socket.getSupportedProtocols()).filter(value->value.equals("TLSv1.2")||value.equals("TLSv1.3")).toArray(String[]::new));
                SSLParameters parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(parameters);
                socket.startHandshake();
                Certificate[] peerChain=socket.getSession().getPeerCertificates();
                if(peerChain.length!=1||!MessageDigest.isEqual(
                    fingerprint(peerChain[0].getEncoded()).getBytes(StandardCharsets.US_ASCII),
                    policy.getString("fingerprint").getBytes(StandardCharsets.US_ASCII)))throw new IOException("Peer certificate pin mismatch");
                ensurePolicyCurrent(policy);
                DataInputStream in=new DataInputStream(new BufferedInputStream(socket.getInputStream(),65536));
                DataOutputStream out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(),65536));
                sendControl(out,'H',local);JSONObject remote=readControl(in,'H');
                SyncProtocol.exact(remote,"version,group,replica");
                if(remote.getInt("version")!=1||!policy.getString("group").equals(remote.getString("group"))
                    ||!policy.getString("peer_replica").equals(remote.getString("replica")))throw new IOException("Peer group or replica mismatch");
                // A populated peer may spend more than45s validating and durably
                // applying a bundle before it can send its receipt. Trust and
                // group are established first; cancel still closes this socket.
                socket.setSoTimeout(PROCESSING_TIMEOUT_MS);
                sendBundle(out,policy,control);JSONObject first=readReceipt(in);
                JSONObject incoming=receiveBundle(in,out,policy,control);
                sendBundle(out,policy,control);JSONObject last=readReceipt(in);
                ensurePolicyCurrent(policy);
                return new JSONObject().put("peer",policy.getString("peer_replica"))
                    .put("first_ack",first).put("received",incoming).put("final_ack",last);
            }
            finally{control.release(raw);}
        }
    }

    void sendBundle(DataOutputStream out,JSONObject policy,SessionControl control)throws Exception {
        ensurePolicyCurrent(policy);
        File directory=context.getCacheDir();File file=File.createTempFile("stack-lan-", ".exchange",directory);
        try {
            try(FileOutputStream output=new FileOutputStream(file)) {
                new SyncExchange(context,store.repository,control::check).export(policy.getString("peer_replica"),new LimitedOutput(output,BUNDLE_LIMIT));output.getFD().sync();
            }
            long size=file.length();if(size>BUNDLE_LIMIT)throw new IOException("Exchange exceeds STACKEX1 LAN limit");
            ensurePolicyCurrent(policy);
            header(out,'B',size);
            try(FileInputStream source=new FileInputStream(file)){byte[] chunk=new byte[65536];int n;while((n=source.read(chunk))!=-1){ensurePolicyCurrent(policy);out.write(chunk,0,n);}}
            out.flush();
        }finally{file.delete();}
    }

    private JSONObject receiveBundle(DataInputStream in,DataOutputStream out,JSONObject policy,SessionControl control)throws Exception {
        long size=readHeader(in,'B',BUNDLE_LIMIT);
        LimitedInput limited=new LimitedInput(in,size);
        JSONObject result;
        SyncExchange controlled=new SyncExchange(context,store.repository,control::check);
        try(SyncExchange.Staged stage=controlled.stage(limited)) {
            if(limited.remaining!=0)throw new IOException("Incomplete LAN bundle");
            ensurePolicyCurrent(policy);
            result=controlled.importFile(stage,null,null);
        }
        sendControl(out,'R',result);return result;
    }

    private static JSONObject readReceipt(DataInputStream in)throws Exception {
        JSONObject receipt=readControl(in,'R');if(!(receipt.opt("durable_frontier") instanceof JSONObject))throw new IOException("Peer omitted durable receipt");return receipt;
    }

    static void header(DataOutputStream out,char kind,long size)throws IOException {
        out.write(MAGIC);out.writeByte(kind);out.writeLong(size);
    }
    static long readHeader(DataInputStream in,char kind,long maximum)throws IOException {
        byte[] magic=new byte[MAGIC.length];in.readFully(magic);if(!Arrays.equals(magic,MAGIC)||in.readUnsignedByte()!=kind)throw new IOException("Unsupported LAN transport frame");
        long size=in.readLong();if(size<0||size>maximum)throw new IOException("LAN frame size limit exceeded");return size;
    }
    static void sendControl(DataOutputStream out,char kind,JSONObject value)throws IOException {
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>CONTROL_LIMIT)throw new IOException("LAN control frame too large");
        header(out,kind,bytes.length);out.write(bytes);out.flush();
    }
    static JSONObject readControl(DataInputStream in,char kind)throws Exception {
        long size=readHeader(in,kind,CONTROL_LIMIT);byte[] bytes=new byte[(int)size];in.readFully(bytes);
        return ArchiveJson.read(utf8(bytes));
    }

    private static String utf8(byte[] bytes)throws IOException {
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
        catch(java.nio.charset.CharacterCodingException error){throw new IOException("Invalid LAN UTF-8",error);}
    }

    static final class LimitedInput extends FilterInputStream {
        long remaining;
        LimitedInput(InputStream in,long count){super(in);remaining=count;}
        @Override public int read()throws IOException {if(remaining==0)return -1;int value=in.read();if(value>=0)remaining--;return value;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException {if(remaining==0)return -1;int n=in.read(bytes,offset,(int)Math.min(remaining,length));if(n>0)remaining-=n;return n;}
    }
    private static final class LimitedOutput extends FilterOutputStream {
        long remaining;
        LimitedOutput(OutputStream out,long count){super(out);remaining=count;}
        @Override public void write(int value)throws IOException {if(remaining==0)throw new IOException("Exchange exceeds STACKEX1 LAN limit");out.write(value);remaining--;}
        @Override public void write(byte[] bytes,int offset,int length)throws IOException {if(length>remaining)throw new IOException("Exchange exceeds STACKEX1 LAN limit");out.write(bytes,offset,length);remaining-=length;}
    }
    private static final class IdentityManager extends X509ExtendedKeyManager {
        final String alias;final PrivateKey key;final X509Certificate[] chain;
        IdentityManager(String alias,PrivateKey key,X509Certificate[] chain){this.alias=alias;this.key=key;this.chain=chain;}
        @Override public String[] getClientAliases(String type,java.security.Principal[] issuers){return "EC".equals(type)?new String[]{alias}:null;}
        @Override public String chooseClientAlias(String[] types,java.security.Principal[] issuers,Socket socket){if(types!=null)for(String type:types)if("EC".equals(type))return alias;return null;}
        @Override public String[] getServerAliases(String type,java.security.Principal[] issuers){return null;}
        @Override public String chooseServerAlias(String type,java.security.Principal[] issuers,Socket socket){return null;}
        @Override public X509Certificate[] getCertificateChain(String selected){return alias.equals(selected)?chain:null;}
        @Override public PrivateKey getPrivateKey(String selected){return alias.equals(selected)?key:null;}
        @Override public String chooseEngineClientAlias(String[] types,java.security.Principal[] issuers,javax.net.ssl.SSLEngine engine){return chooseClientAlias(types,issuers,null);}
    }
}
