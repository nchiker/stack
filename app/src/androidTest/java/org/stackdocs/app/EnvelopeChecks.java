package org.stackdocs.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.function.Consumer;

/** Pure-Java checks for the encrypted backup envelope. Run on the phone via instrumentation or on a host JDK via main. */
final class EnvelopeChecks {
    private EnvelopeChecks(){}
    /** Deterministic bytes for the shared test vectors (never used outside tests). */
    static BackupEnvelope.Rand counter(int start) {
        int[] n={start};
        return bytes -> {for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(n[0]++);};
    }
    static byte[] body(int length) {byte[] b=new byte[length];for(int i=0;i<length;i++)b[i]=(byte)(i*31+7);return b;}
    static void require(boolean ok,String what){if(!ok)throw new AssertionError(what);}
    static byte[] encrypt(byte[] data,byte[] salt,int iterations,byte[] passKey,byte[] recKey,BackupEnvelope.Rand rand) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(BackupEnvelope.Writer writer=BackupEnvelope.encrypt(out,salt,iterations,passKey,recKey,rand)){writer.write(data,0,data.length);}
        return out.toByteArray();
    }
    static byte[] decrypt(byte[] file,String passphrase,byte[] recovery) throws Exception {
        try(BackupEnvelope.Reader reader=BackupEnvelope.decrypt(new ByteArrayInputStream(file),passphrase,recovery)) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[5000];int n;while((n=reader.read(buf))>=0)out.write(buf,0,n);
            reader.finish();return out.toByteArray();
        }
    }
    static byte[] decryptBytes(byte[] file,String passphrase,byte[] recovery) throws Exception {return decrypt(file,passphrase,recovery);}
    static boolean fails(byte[] file,String passphrase,byte[] recovery,Class<? extends IOException> kind) {
        try{decrypt(file,passphrase,recovery);return false;}catch(IOException e){return kind.isInstance(e);}catch(Exception e){return false;}
    }
    static void run(Consumer<String> mark) throws Exception {
        int it=BackupEnvelope.MIN_ITERATIONS;byte[] salt=new byte[16];counter(100).fill(salt);
        // Cross-check my PBKDF2 against the platform one for an ASCII passphrase, and the NFC rule.
        byte[] mine=BackupEnvelope.passphraseSlotKey("correct horse",salt,it);
        byte[] platform=javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new javax.crypto.spec.PBEKeySpec("correct horse".toCharArray(),salt,it,256)).getEncoded();
        require(Arrays.equals(mine,platform),"PBKDF2 matches platform");
        require(Arrays.equals(BackupEnvelope.passphraseSlotKey("café",salt,it),BackupEnvelope.passphraseSlotKey("café",salt,it)),"NFC normalised");
        mark.accept("pbkdf2");
        String pass="correct horse";byte[] recovery=new byte[32];counter(200).fill(recovery);
        byte[] pk=mine,rk=BackupEnvelope.recoverySlotKey(recovery,salt);
        for(int size:new int[]{1,65535,65536,65537,200000}) {
            byte[] data=body(size),file=encrypt(data,salt,it,pk,rk,counter(1));
            require(Arrays.equals(decrypt(file,pass,null),data),"passphrase round trip "+size);
            require(Arrays.equals(decrypt(file,null,recovery),data),"recovery round trip "+size);
        }
        mark.accept("round-trip");
        byte[] data=body(200000),file=encrypt(data,salt,it,pk,rk,counter(1));
        require(fails(file,"wrong",null,BackupEnvelope.NoKey.class),"wrong passphrase");
        byte[] other=recovery.clone();other[0]^=1;
        require(fails(file,null,other,BackupEnvelope.NoKey.class),"wrong recovery key");
        mark.accept("wrong-secrets");
        // Layout: header = 8+1+4+16+1+2*61 = 152, hash 32, then chunks of 65552.
        int head=152+32,chunk=65536+16;
        byte[] cut=Arrays.copyOf(file,head+chunk);
        require(fails(cut,pass,null,BackupEnvelope.Damaged.class),"truncated at a chunk boundary");
        require(fails(Arrays.copyOf(file,file.length-1),pass,null,BackupEnvelope.Damaged.class),"truncated by a byte");
        byte[] extended=Arrays.copyOf(file,file.length+1);
        require(fails(extended,pass,null,BackupEnvelope.Damaged.class),"extended by a byte");
        byte[] swapped=file.clone();
        System.arraycopy(file,head+chunk,swapped,head,chunk);System.arraycopy(file,head,swapped,head+chunk,chunk);
        require(fails(swapped,pass,null,BackupEnvelope.Damaged.class),"reordered chunks");
        byte[] flipped=file.clone();flipped[head+100]^=1;
        require(fails(flipped,pass,null,BackupEnvelope.Damaged.class),"tampered chunk");
        mark.accept("chunks");
        for(int offset:new int[]{0,8,9,12,13,20,30,40,60,100,151,152,170}) {
            byte[] bad=file.clone();bad[offset]^=1;
            require(fails(bad,pass,null,IOException.class)&&fails(bad,null,recovery,IOException.class),"tampered header at "+offset);
        }
        mark.accept("header");
        // Reader bounds: iterations outside the range are refused before any key work.
        byte[] low=file.clone();low[9]=0;low[10]=0;low[11]=0;low[12]=1;
        require(fails(low,pass,null,BackupEnvelope.Damaged.class),"iteration bound");
        // A single-slot file only opens with its own secret.
        byte[] only=encrypt(body(10),salt,it,pk,null,counter(1));
        require(Arrays.equals(decrypt(only,pass,null),body(10)),"passphrase-only");
        require(fails(only,null,recovery,BackupEnvelope.NoKey.class),"missing slot");
        require(fails("STACKBK\u0001 not encrypted".getBytes(StandardCharsets.ISO_8859_1),pass,null,BackupEnvelope.Damaged.class),"plain archive is not an envelope");
        mark.accept("bounds");
        require(BackupEnvelope.parseRecoveryKey("00-11 22 33 44 55 66 77 88 99 aa BB cc dd ee ff 00 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff")[11]==(byte)0xbb,"recovery hex parse");
        // Shared vector: fixed inputs give fixed bytes; the desktop must reproduce this digest.
        byte[] vector=encrypt(body(70000),salt,it,pk,rk,counter(1));
        mark.accept("vector sha256="+hex(MessageDigest.getInstance("SHA-256").digest(vector))+" length="+vector.length);
    }
    static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}
    public static void main(String[] args) throws Exception {run(System.out::println);System.out.println("ALL PASS");}
}
