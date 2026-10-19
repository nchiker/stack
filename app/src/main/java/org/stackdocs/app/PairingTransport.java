package org.stackdocs.app;

import android.content.Context;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.security.KeyPairGenerator;
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
import java.util.UUID;
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
    private static final String ALIAS="stack-pairing-v1", TLS_NAME="stackdocs.local";
    private static final byte[] MAGIC="STACKLAN1".getBytes(StandardCharsets.US_ASCII);
    private static final int BUNDLE_LIMIT=64*1024*1024, CONTROL_LIMIT=16*1024, TIMEOUT_MS=45000;
    private final Context context;
    private final SyncStore store;
    private final SyncExchange exchange;
    private final AtomicFile policyFile;

    PairingTransport(Context context,LibraryRepository repository) {
        this.context=context.getApplicationContext();
        store=new SyncStore(context,repository);exchange=new SyncExchange(context,repository);
        policyFile=new AtomicFile(new File(this.context.getDir("pairing",Context.MODE_PRIVATE),"policy.json"));
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
        KeyStore keys=keys();if(!keys.containsAlias(ALIAS)) {
            KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");
            long now=System.currentTimeMillis();byte[] serial=new byte[16];new SecureRandom().nextBytes(serial);
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(new X500Principal("CN=Stack paired device"))
                .setCertificateSerialNumber(new BigInteger(1,serial))
                .setCertificateNotBefore(new Date(now-3600000L))
                .setCertificateNotAfter(new Date(now+10L*365*24*60*60*1000))
                .build());generator.generateKeyPair();
        }
        return fingerprint(identityCertificate());
    }

    byte[] identityCertificate()throws Exception {
        Certificate certificate=keys().getCertificate(ALIAS);
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

    void revoke()throws Exception {JSONObject policy=policy();policy.put("revoked",true);savePolicy(policy);}

    JSONObject status()throws Exception {
        JSONObject status=store.status();status.put("identity_fingerprint",fingerprint(identityCertificate()));
        if(policyFile.getBaseFile().exists()) {JSONObject policy=policy();status.put("paired_peer",policy.getString("peer_replica"));status.put("revoked",policy.getBoolean("revoked"));}
        return status;
    }

    private JSONObject policy()throws Exception {
        if(!policyFile.getBaseFile().isFile())throw new IOException("Enroll a verified peer first");
        byte[] bytes=policyFile.readFully();if(bytes.length>CONTROL_LIMIT)throw new IOException("Pairing policy too large");
        JSONObject policy=ArchiveJson.read(new String(bytes,StandardCharsets.UTF_8));
        SyncProtocol.exact(policy,"version,group,peer_replica,fingerprint,certificate,revoked");
        if(policy.getInt("version")!=1||policy.getBoolean("revoked"))throw new IOException("Peer revoked or pairing policy unsupported");
        SyncProtocol.uuid(policy.getString("group"));SyncProtocol.uuid(policy.getString("peer_replica"));
        byte[] der=Base64.decode(policy.getString("certificate"),Base64.DEFAULT);
        if(!fingerprint(der).equals(policy.getString("fingerprint")))throw new IOException("Peer certificate changed");
        certificate(der);
        return policy;
    }

    private void savePolicy(JSONObject policy)throws IOException {
        byte[] bytes=policy.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>CONTROL_LIMIT)throw new IOException("Pairing policy too large");
        FileOutputStream out=policyFile.startWrite();try{out.write(bytes);policyFile.finishWrite(out);}catch(IOException error){policyFile.failWrite(out);throw error;}
    }

    private static boolean contains(JSONArray array,String value)throws JSONException {
        for(int i=0;i<array.length();i++)if(value.equals(array.getString(i)))return true;return false;
    }

    private JSONObject hello(JSONObject policy)throws Exception {
        JSONObject status=store.status();String local=status.getString("replica");
        if(!status.optBoolean("adopted"))throw new IOException("Start or adopt the group before Sync now");
        JSONArray members=status.getJSONArray("members");
        if(!policy.getString("group").equals(status.getString("library"))||members.length()!=2
            ||!contains(members,local)||!contains(members,policy.getString("peer_replica")))
            throw new IOException("Network transport requires the frozen two-member group");
        return new JSONObject().put("version",1).put("group",policy.getString("group")).put("replica",local);
    }

    private SSLContext tls(JSONObject policy)throws Exception {
        KeyStore identity=keys();
        PrivateKey key=(PrivateKey)identity.getKey(ALIAS,null);
        X509Certificate own=(X509Certificate)identity.getCertificate(ALIAS);
        if(key==null||own==null)throw new IOException("Create a pairing identity first");
        X509Certificate peer=certificate(Base64.decode(policy.getString("certificate"),Base64.DEFAULT));
        KeyStore roots=KeyStore.getInstance(KeyStore.getDefaultType());roots.load(null);roots.setCertificateEntry("verified-peer",peer);
        TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(roots);
        // Standard platform PKIX validation, followed by exact enrollment pin.
        boolean verifier=false;for(javax.net.ssl.TrustManager manager:trust.getTrustManagers())if(manager instanceof X509TrustManager)verifier=true;
        if(!verifier)throw new IOException("Platform PKIX verifier unavailable");
        SSLContext tls=SSLContext.getInstance("TLS");
        tls.init(new KeyManager[]{new IdentityManager(ALIAS,key,new X509Certificate[]{own})},trust.getTrustManagers(),null);
        return tls;
    }

    /** Must be called from the app's foreground IO executor after an explicit
     * user action. A manual address is used; no background discovery is active. */
    JSONObject syncNow(String host,int port)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("Run Sync now on the foreground IO executor");
        if(host==null||host.isEmpty()||port<1||port>65535)throw new IOException("Choose a valid peer address");
        JSONObject policy=policy(),local=hello(policy);
        SSLContext tls=tls(policy);
        try(Socket raw=new Socket()) {
            raw.connect(new InetSocketAddress(host,port),TIMEOUT_MS);raw.setSoTimeout(TIMEOUT_MS);
            try(SSLSocket socket=(SSLSocket)tls.getSocketFactory().createSocket(raw,TLS_NAME,port,true)) {
                socket.setSoTimeout(TIMEOUT_MS);
                SSLParameters parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(parameters);
                socket.startHandshake();
                Certificate[] peerChain=socket.getSession().getPeerCertificates();
                if(peerChain.length!=1||!MessageDigest.isEqual(
                    fingerprint(peerChain[0].getEncoded()).getBytes(StandardCharsets.US_ASCII),
                    policy.getString("fingerprint").getBytes(StandardCharsets.US_ASCII)))throw new IOException("Peer certificate pin mismatch");
                DataInputStream in=new DataInputStream(new BufferedInputStream(socket.getInputStream(),65536));
                DataOutputStream out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(),65536));
                sendControl(out,'H',local);JSONObject remote=readControl(in,'H');
                SyncProtocol.exact(remote,"version,group,replica");
                if(remote.getInt("version")!=1||!policy.getString("group").equals(remote.getString("group"))
                    ||!policy.getString("peer_replica").equals(remote.getString("replica")))throw new IOException("Peer group or replica mismatch");
                sendBundle(out,policy.getString("peer_replica"));JSONObject first=readReceipt(in);
                JSONObject incoming=receiveBundle(in,out,policy);
                sendBundle(out,policy.getString("peer_replica"));JSONObject last=readReceipt(in);
                return new JSONObject().put("peer",policy.getString("peer_replica"))
                    .put("first_ack",first).put("received",incoming).put("final_ack",last).put("status",store.status());
            }
        }
    }

    private void sendBundle(DataOutputStream out,String peer)throws Exception {
        File directory=context.getCacheDir();File file=File.createTempFile("stack-lan-", ".exchange",directory);
        try {
            try(FileOutputStream output=new FileOutputStream(file)) {
                exchange.export(peer,new LimitedOutput(output,BUNDLE_LIMIT));output.getFD().sync();
            }
            long size=file.length();if(size>BUNDLE_LIMIT)throw new IOException("Bundle exceeds 64 MiB LAN limit");
            header(out,'B',size);
            try(FileInputStream source=new FileInputStream(file)){byte[] chunk=new byte[65536];int n;while((n=source.read(chunk))!=-1)out.write(chunk,0,n);}
            out.flush();
        }finally{file.delete();}
    }

    private JSONObject receiveBundle(DataInputStream in,DataOutputStream out,JSONObject policy)throws Exception {
        long size=readHeader(in,'B',BUNDLE_LIMIT);
        LimitedInput limited=new LimitedInput(in,size);
        JSONObject result;
        try(SyncExchange.Staged stage=exchange.stage(limited)) {
            if(limited.remaining!=0)throw new IOException("Incomplete LAN bundle");
            result=exchange.importFile(stage,null,null);
        }
        sendControl(out,'R',result);return result;
    }

    private static JSONObject readReceipt(DataInputStream in)throws Exception {
        JSONObject receipt=readControl(in,'R');if(!(receipt.opt("durable_frontier") instanceof JSONObject))throw new IOException("Peer omitted durable receipt");return receipt;
    }

    private static void header(DataOutputStream out,char kind,long size)throws IOException {
        out.write(MAGIC);out.writeByte(kind);out.writeLong(size);
    }
    private static long readHeader(DataInputStream in,char kind,long maximum)throws IOException {
        byte[] magic=new byte[MAGIC.length];in.readFully(magic);if(!Arrays.equals(magic,MAGIC)||in.readUnsignedByte()!=kind)throw new IOException("Unsupported LAN transport frame");
        long size=in.readLong();if(size<0||size>maximum)throw new IOException("LAN frame size limit exceeded");return size;
    }
    private static void sendControl(DataOutputStream out,char kind,JSONObject value)throws IOException {
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>CONTROL_LIMIT)throw new IOException("LAN control frame too large");
        header(out,kind,bytes.length);out.write(bytes);out.flush();
    }
    private static JSONObject readControl(DataInputStream in,char kind)throws Exception {
        long size=readHeader(in,kind,CONTROL_LIMIT);byte[] bytes=new byte[(int)size];in.readFully(bytes);
        return ArchiveJson.read(new String(bytes,StandardCharsets.UTF_8));
    }

    private static final class LimitedInput extends FilterInputStream {
        long remaining;
        LimitedInput(InputStream in,long count){super(in);remaining=count;}
        @Override public int read()throws IOException {if(remaining==0)return -1;int value=in.read();if(value>=0)remaining--;return value;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException {if(remaining==0)return -1;int n=in.read(bytes,offset,(int)Math.min(remaining,length));if(n>0)remaining-=n;return n;}
    }
    private static final class LimitedOutput extends FilterOutputStream {
        long remaining;
        LimitedOutput(OutputStream out,long count){super(out);remaining=count;}
        @Override public void write(int value)throws IOException {if(remaining==0)throw new IOException("Bundle exceeds 64 MiB LAN limit");out.write(value);remaining--;}
        @Override public void write(byte[] bytes,int offset,int length)throws IOException {if(length>remaining)throw new IOException("Bundle exceeds 64 MiB LAN limit");out.write(bytes,offset,length);remaining-=length;}
    }
    private static final class IdentityManager extends X509ExtendedKeyManager {
        final String alias;final PrivateKey key;final X509Certificate[] chain;
        IdentityManager(String alias,PrivateKey key,X509Certificate[] chain){this.alias=alias;this.key=key;this.chain=chain;}
        @Override public String[] getClientAliases(String type,java.security.Principal[] issuers){return new String[]{alias};}
        @Override public String chooseClientAlias(String[] types,java.security.Principal[] issuers,Socket socket){return alias;}
        @Override public String[] getServerAliases(String type,java.security.Principal[] issuers){return null;}
        @Override public String chooseServerAlias(String type,java.security.Principal[] issuers,Socket socket){return null;}
        @Override public X509Certificate[] getCertificateChain(String selected){return alias.equals(selected)?chain:null;}
        @Override public PrivateKey getPrivateKey(String selected){return alias.equals(selected)?key:null;}
        @Override public String chooseEngineClientAlias(String[] types,java.security.Principal[] issuers,javax.net.ssl.SSLEngine engine){return alias;}
    }
}
