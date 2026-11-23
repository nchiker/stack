package org.stackdocs.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypted backup envelope (see docs/STACK_ARCHIVE_FORMAT.md, "Format 4 and the encrypted envelope").
 * Platform javax.crypto only; no Android classes, so it also runs on a host JDK for the shared test vectors.
 */
final class BackupEnvelope {
    static final byte[] MAGIC={'S','T','A','C','K','E','N',1};
    static final int CHUNK=65536, TAG=16, KDF_PBKDF2=1, SLOT_PASSPHRASE=1, SLOT_RECOVERY=2, SLOT_PARTNER=3;
    static final int MIN_ITERATIONS=600_000, MAX_ITERATIONS=2_000_000, SALT=16, MAX_CHUNKS=131_200;
    private static final int SLOT_BYTES=1+12+32+TAG;
    private static final byte[] RECOVERY_LABEL="stack-backup-recovery-v1".getBytes(StandardCharsets.US_ASCII);

    interface Rand { void fill(byte[] bytes); }
    static final class Damaged extends IOException { Damaged(String message){super(message);} }
    static final class NoKey extends IOException { NoKey(String message){super(message);} }

    private BackupEnvelope(){}

    // ---- key derivation -------------------------------------------------------------------------------------

    /** PBKDF2-HMAC-SHA256 over the exact NFC UTF-8 bytes, 32 bytes out (one block). Never SecretKeyFactory. */
    static byte[] passphraseSlotKey(String passphrase,byte[] salt,int iterations) throws GeneralSecurityException {
        if(passphrase==null||passphrase.isEmpty())throw new IllegalArgumentException("The passphrase is empty.");
        if(iterations<MIN_ITERATIONS||iterations>MAX_ITERATIONS)throw new IllegalArgumentException("Unsupported iteration count.");
        byte[] password=Normalizer.normalize(passphrase,Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8);
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(password,"HmacSHA256"));
        mac.update(salt);mac.update(new byte[]{0,0,0,1});
        byte[] u=mac.doFinal(),result=u.clone();
        for(int i=1;i<iterations;i++) {
            u=mac.doFinal(u);
            for(int j=0;j<result.length;j++)result[j]^=u[j];
        }
        java.util.Arrays.fill(password,(byte)0);
        return result;
    }

    /** HMAC-SHA256(recovery key, "stack-backup-recovery-v1" || salt). */
    static byte[] recoverySlotKey(byte[] recoveryKey,byte[] salt) throws GeneralSecurityException {
        if(recoveryKey==null||recoveryKey.length!=32)throw new IllegalArgumentException("The recovery key must be 32 bytes.");
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(recoveryKey,"HmacSHA256"));
        mac.update(RECOVERY_LABEL);return mac.doFinal(salt);
    }

    /** Hex, case-insensitive, spaces and dashes removed, exactly 32 bytes. */
    static byte[] parseRecoveryKey(String text) {
        StringBuilder clean=new StringBuilder();
        for(char c:text.toCharArray())if(!Character.isWhitespace(c)&&c!='-')clean.append(c);
        if(!clean.toString().matches("[0-9a-fA-F]{64}"))throw new IllegalArgumentException("The recovery key is 64 hex characters.");
        if(clean.length()!=64)throw new IllegalArgumentException("The recovery key is 64 hex characters.");
        byte[] out=new byte[32];
        for(int i=0;i<32;i++) {
            int hi=Character.digit(clean.charAt(2*i),16),lo=Character.digit(clean.charAt(2*i+1),16);
            if(hi<0||lo<0)throw new IllegalArgumentException("The recovery key is 64 hex characters.");
            out[i]=(byte)(hi<<4|lo);
        }
        return out;
    }

    // ---- writing --------------------------------------------------------------------------------------------

    /** Slot keys are the already derived keys; either may be null only if that slot is not wanted (not both). */
    static Writer encrypt(OutputStream out,byte[] salt,int iterations,byte[] passphraseKey,byte[] recoveryKey,Rand random) throws IOException {
        if(salt.length!=SALT||iterations<MIN_ITERATIONS||iterations>MAX_ITERATIONS)throw new IllegalArgumentException("Bad envelope parameters.");
        if(passphraseKey==null&&recoveryKey==null)throw new IllegalArgumentException("At least one slot is required.");
        try {
            byte[] dataKey=new byte[32];random.fill(dataKey);
            ByteArrayOutputStream header=new ByteArrayOutputStream();
            header.write(MAGIC);header.write(KDF_PBKDF2);header.write(ByteBuffer.allocate(4).putInt(iterations).array());header.write(salt);
            int count=(passphraseKey==null?0:1)+(recoveryKey==null?0:1);header.write(count);
            if(passphraseKey!=null)slot(header,SLOT_PASSPHRASE,passphraseKey,dataKey,salt,iterations,random);
            if(recoveryKey!=null)slot(header,SLOT_RECOVERY,recoveryKey,dataKey,salt,iterations,random);
            byte[] h=header.toByteArray(),hash=MessageDigest.getInstance("SHA-256").digest(h);
            out.write(h);out.write(hash);
            return new Writer(out,dataKey,hash);
        } catch(GeneralSecurityException error){throw new IOException("Encryption is unavailable.",error);}
    }

    /** Partner (sharing) envelope: exactly one slot, type 3, whose key is the 32-byte sharing key itself (no KDF is run on it). */
    static Writer encryptPartner(OutputStream out,byte[] salt,int iterations,byte[] sharingKey,Rand random) throws IOException {
        if(salt.length!=SALT||iterations<MIN_ITERATIONS||iterations>MAX_ITERATIONS)throw new IllegalArgumentException("Bad envelope parameters.");
        if(sharingKey==null||sharingKey.length!=32)throw new IllegalArgumentException("The sharing key must be 32 bytes.");
        try {
            byte[] dataKey=new byte[32];random.fill(dataKey);
            ByteArrayOutputStream header=new ByteArrayOutputStream();
            header.write(MAGIC);header.write(KDF_PBKDF2);header.write(ByteBuffer.allocate(4).putInt(iterations).array());header.write(salt);
            header.write(1);slot(header,SLOT_PARTNER,sharingKey,dataKey,salt,iterations,random);
            byte[] h=header.toByteArray(),hash=MessageDigest.getInstance("SHA-256").digest(h);
            out.write(h);out.write(hash);
            return new Writer(out,dataKey,hash);
        } catch(GeneralSecurityException error){throw new IOException("Encryption is unavailable.",error);}
    }

    private static byte[] slotAad(int type,byte[] salt,int iterations) {
        return ByteBuffer.allocate(MAGIC.length+1+4+SALT+1).put(MAGIC).put((byte)KDF_PBKDF2).putInt(iterations).put(salt).put((byte)type).array();
    }

    private static void slot(ByteArrayOutputStream header,int type,byte[] key,byte[] dataKey,byte[] salt,int iterations,Rand random) throws GeneralSecurityException,IOException {
        byte[] nonce=new byte[12];random.fill(nonce);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        cipher.updateAAD(slotAad(type,salt,iterations));
        header.write(type);header.write(nonce);header.write(cipher.doFinal(dataKey));
    }

    private static byte[] chunkNonce(long index) {return ByteBuffer.allocate(12).putInt(0).putLong(index).array();}
    private static byte[] chunkAad(byte[] headerHash,long index,boolean last) {
        return ByteBuffer.allocate(32+8+1).put(headerHash).putLong(index).put((byte)(last?1:0)).array();
    }
    private static Cipher chunkCipher(int mode,byte[] dataKey,byte[] headerHash,long index,boolean last) throws GeneralSecurityException {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode,new SecretKeySpec(dataKey,"AES"),new GCMParameterSpec(128,chunkNonce(index)));
        cipher.updateAAD(chunkAad(headerHash,index,last));return cipher;
    }

    /** Holds back one chunk so the final one can carry the last flag. Call {@link #finish()}; it does not close the sink. */
    static final class Writer extends OutputStream {
        private final OutputStream out;private final byte[] dataKey,headerHash,buffer=new byte[CHUNK];
        private int filled;private long index;private boolean finished;
        Writer(OutputStream out,byte[] dataKey,byte[] headerHash){this.out=out;this.dataKey=dataKey;this.headerHash=headerHash;}
        @Override public void write(int b) throws IOException {write(new byte[]{(byte)b},0,1);}
        @Override public void write(byte[] bytes,int off,int len) throws IOException {
            if(finished)throw new IOException("The envelope is finished.");
            while(len>0) {
                if(filled==CHUNK)emit(false);
                int n=Math.min(len,CHUNK-filled);System.arraycopy(bytes,off,buffer,filled,n);filled+=n;off+=n;len-=n;
            }
        }
        private void emit(boolean last) throws IOException {
            if(index>MAX_CHUNKS)throw new IOException("The backup is too large.");
            try {
                out.write(chunkCipher(Cipher.ENCRYPT_MODE,dataKey,headerHash,index,last).doFinal(buffer,0,filled));
            } catch(GeneralSecurityException error){throw new IOException("Encryption failed.",error);}
            index++;filled=0;
        }
        void finish() throws IOException {
            if(finished)return;
            if(filled==0&&index==0)throw new IOException("An empty backup cannot be encrypted.");
            if(filled==0)throw new IllegalStateException();
            emit(true);finished=true;out.flush();java.util.Arrays.fill(dataKey,(byte)0);
        }
        @Override public void flush(){}
        @Override public void close() throws IOException {finish();}
    }

    // ---- reading --------------------------------------------------------------------------------------------

    /** Exactly one of passphrase or recoveryKey is used. */
    static Reader decrypt(InputStream raw,String passphrase,byte[] recoveryKey) throws IOException {
        boolean pass=passphrase!=null;
        return open(raw,pass?SLOT_PASSPHRASE:SLOT_RECOVERY,
            "This backup has no key for that "+(pass?"passphrase.":"recovery key."),
            "Wrong passphrase or recovery key, or the backup is damaged.",
            (salt,iterations)->pass?passphraseSlotKey(passphrase,salt,iterations):recoverySlotKey(recoveryKey,salt));
    }

    /** Opens a partner (sharing) envelope with the 32-byte sharing key, which is the slot key itself. */
    static Reader decryptPartner(InputStream raw,byte[] sharingKey) throws IOException {
        if(sharingKey==null||sharingKey.length!=32)throw new IllegalArgumentException("The sharing key must be 32 bytes.");
        return open(raw,SLOT_PARTNER,"This file is not shared with this key.","Wrong sharing key, or the shared file is damaged.",(salt,iterations)->sharingKey.clone());
    }

    private interface SlotKey { byte[] get(byte[] salt,int iterations) throws GeneralSecurityException; }

    private static Reader open(InputStream raw,int wantedType,String noSlot,String wrongKey,SlotKey keyFor) throws IOException {
        try {
            DigestingIn in=new DigestingIn(raw);
            byte[] magic=in.exactly(MAGIC.length);
            if(!java.util.Arrays.equals(magic,MAGIC))throw new Damaged("This is not an encrypted Stack backup.");
            int kdf=in.exactly(1)[0]&0xff;
            int iterations=ByteBuffer.wrap(in.exactly(4)).getInt();
            byte[] salt=in.exactly(SALT);
            int count=in.exactly(1)[0]&0xff;
            if(kdf!=KDF_PBKDF2||iterations<MIN_ITERATIONS||iterations>MAX_ITERATIONS||count<1||count>4)throw new Damaged("The backup header is damaged or unsupported.");
            byte[][] nonces=new byte[count][],wrapped=new byte[count][];int[] types=new int[count];
            for(int i=0;i<count;i++) {
                types[i]=in.exactly(1)[0]&0xff;nonces[i]=in.exactly(12);wrapped[i]=in.exactly(32+TAG);
                for(int j=0;j<i;j++)if(types[j]==types[i])throw new Damaged("The backup header is damaged.");
            }
            byte[] expected=in.digest();byte[] stored=in.exactlyRaw(32);
            if(!MessageDigest.isEqual(expected,stored))throw new Damaged("The backup header is damaged.");
            int slot=-1;
            for(int i=0;i<count;i++)if(types[i]==wantedType)slot=i;
            if(slot<0)throw new NoKey(noSlot);
            byte[] key=keyFor.get(salt,iterations);
            byte[] dataKey;
            try {
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonces[slot]));
                cipher.updateAAD(slotAad(types[slot],salt,iterations));dataKey=cipher.doFinal(wrapped[slot]);
            } catch(GeneralSecurityException wrong){throw new NoKey(wrongKey);}
            return new Reader(raw,dataKey,stored);
        } catch(GeneralSecurityException error){throw new IOException("Decryption is unavailable.",error);}
    }

    /** Plaintext stream. Reading to the end verifies the final chunk; callers must not commit before {@link #finish()}. */
    static final class Reader extends InputStream {
        private final PushbackInputStream in;private final byte[] dataKey,headerHash;
        private byte[] plain=new byte[0];private int pos;private long index;private boolean last,done;
        Reader(InputStream raw,byte[] dataKey,byte[] headerHash){this.in=new PushbackInputStream(raw,1);this.dataKey=dataKey;this.headerHash=headerHash;}
        private boolean fill() throws IOException {
            if(done)return false;
            if(index>MAX_CHUNKS)throw new Damaged("The backup is damaged (too many chunks).");
            byte[] block=new byte[CHUNK+TAG];int n=0;
            while(n<block.length) {int r=in.read(block,n,block.length-n);if(r<0)break;n+=r;}
            boolean isLast=n<block.length;
            if(!isLast) {int peek=in.read();if(peek<0)isLast=true;else in.unread(peek);}
            if(n<TAG+1)throw new Damaged("The backup is damaged (truncated).");
            try {
                plain=chunkCipher(Cipher.DECRYPT_MODE,dataKey,headerHash,index,isLast).doFinal(block,0,n);
            } catch(GeneralSecurityException bad){throw new Damaged("The backup is damaged (a block failed its check).");}
            pos=0;index++;if(isLast){last=true;done=true;}return true;
        }
        @Override public int read() throws IOException {
            byte[] one=new byte[1];int r=read(one,0,1);return r<0?-1:one[0]&0xff;
        }
        @Override public int read(byte[] bytes,int off,int len) throws IOException {
            if(len==0)return 0;
            while(pos>=plain.length)if(!fill())return -1;
            int n=Math.min(len,plain.length-pos);System.arraycopy(plain,pos,bytes,off,n);pos+=n;return n;
        }
        /** Drains and verifies to the final chunk. Throws if the backup was truncated or extended. */
        void finish() throws IOException {
            byte[] sink=new byte[8192];while(read(sink,0,sink.length)>=0){}
            if(!last)throw new Damaged("The backup is damaged (truncated).");
        }
        @Override public void close() throws IOException {java.util.Arrays.fill(dataKey,(byte)0);in.close();}
    }

    private static final class DigestingIn {
        private final InputStream in;private final MessageDigest digest;
        DigestingIn(InputStream in) throws GeneralSecurityException {this.in=in;digest=MessageDigest.getInstance("SHA-256");}
        byte[] exactly(int n) throws IOException {byte[] b=exactlyRaw(n);digest.update(b);return b;}
        byte[] exactlyRaw(int n) throws IOException {
            byte[] b=new byte[n];int got=0;
            while(got<n) {int r=in.read(b,got,n-got);if(r<0)throw new Damaged("The backup header is damaged (truncated).");got+=r;}
            return b;
        }
        byte[] digest(){return digest.digest();}
    }
}
