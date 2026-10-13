package com.dennis.g3connect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Limited, locally executed Ninebot Encryption2 (Gen3) protocol implementation.
 * Ported independently to Java from the MIT-licensed public protocol description:
 * https://codeberg.org/NootNooot/segway-ninebot-ble
 *
 * THIS CLASS NEVER GENERATES OR SENDS SET_PWD, CONFIGURATION, OR FIRMWARE COMMANDS.
 * All commands constructed by this class are PRE_COMM, AUTH or READ.
 *
 * These are experimental diagnostics on unverified G3 firmware. No key is persisted.
 */
final class NinebotProbe {
    static final int PRE_COMM = 0x5B, AUTH = 0x5D, READ = 0x01, READ_RESP = 0x04;
    private static final int BOARD_BLE = 4, BOARD_VCU = 0x16;
    private final Crypto crypto = new Crypto();
    private byte[] authParameter;
    private String serial = "";
    private boolean paired;
    private boolean authenticated;

    static final class Reply {
        final int command, index;
        final byte[] payload;
        Reply(int command, int index, byte[] payload) {
            this.command=command; this.index=index; this.payload=payload;
        }
    }
    static final class Packet {
        final byte[] plaintext;
        final int error;
        Packet(byte[] data,int err) { plaintext=data;error=err; }
    }
    void reset() {
        authParameter=null; serial=""; paired=false;authenticated=false;crypto.reset();
    }
    boolean hasChallenge() { return authParameter!=null; }
    boolean hasStoredPassword() { return paired; }
    boolean isAuthenticated() { return authenticated; }
    String getSerialMasked() {
        if(serial.length()<5) return "[unknown]";
        return serial.substring(0,Math.min(3,serial.length())) + "…" +
                serial.substring(serial.length()-3);
    }
    String getSerial() {return serial;}
    byte[] beginProbe(String advertisedName) throws GeneralSecurityException {
        if(advertisedName==null || advertisedName.isEmpty()) throw new GeneralSecurityException("Missing device name");
        reset();
        crypto.setKey(advertisedName.getBytes(StandardCharsets.US_ASCII),null);
        return crypto.encrypt(frame(BOARD_BLE,PRE_COMM,0,new byte[0]));
    }
    byte[] beginAuthentication(String passwordHex) throws GeneralSecurityException {
        if(authParameter==null) throw new GeneralSecurityException("Probe first");
        byte[] key=parseKey(passwordHex);
        crypto.setKey(key,authParameter);
        Arrays.fill(key,(byte)0);
        // The vehicle's own serial must be exactly 14 bytes, right-padded.
        byte[] serialBytes=Arrays.copyOf(serial.getBytes(StandardCharsets.US_ASCII),14);
        return crypto.encrypt(frame(BOARD_BLE,AUTH,0,serialBytes));
    }
    static byte[] parseKey(String hex) {
        if(hex==null || !hex.matches("(?i)[0-9a-f]{32}"))
            throw new IllegalArgumentException("Bluetooth key must be exactly 32 hexadecimal characters (16 bytes)");
        byte[] out=new byte[16];
        for(int i=0;i<16;i++)out[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
        return out;
    }
    byte[] readRegister(int register, int length) throws GeneralSecurityException {
        if(!authenticated) throw new GeneralSecurityException("Not authenticated");
        if((register!=0x55 && register!=0x62 && register!=0x5F) || (length!=2 && length!=4))
            throw new GeneralSecurityException("Register not on read-only allowlist");
        return crypto.encrypt(frame(BOARD_VCU,READ,register,new byte[]{(byte)length}));
    }
    Packet decrypt(byte[] data) throws GeneralSecurityException {return crypto.decrypt(data);}
    Reply parse(byte[] clear) throws GeneralSecurityException {
        if(clear.length<7 || clear[0]!=(byte)0x5A || clear[1]!=(byte)0xB5)
            throw new GeneralSecurityException("Unrecognised response frame");
        int length=clear[2]&255;
        if(clear.length!=length+7)throw new GeneralSecurityException("Invalid frame size");
        return new Reply(clear[5]&255,clear[6]&255,Arrays.copyOfRange(clear,7,7+length));
    }
    String onPreComm(Reply r) throws GeneralSecurityException {
        if(r.command!=PRE_COMM || r.payload.length<30)throw new GeneralSecurityException("Malformed PRE_COMM response");
        authParameter=Arrays.copyOfRange(r.payload,0,16);
        serial=new String(r.payload,16,14,StandardCharsets.US_ASCII).replace("\u0000","").trim();
        paired=r.index!=0;
        crypto.setAuth(authParameter);
        crypto.startSn();
        return "G3 protocol reply verified; serial "+getSerialMasked()+"; stored password: "+paired;
    }
    void onAuth(Reply r) throws GeneralSecurityException {
        if(r.command!=AUTH || r.index!=1) throw new GeneralSecurityException("Scooter did not confirm AUTH");
        authenticated=true;
    }
    static byte[] frame(int board,int cmd,int index,byte[] payload) {
        byte[] f=new byte[7+payload.length];
        f[0]=0x5A;f[1]=(byte)0xB5;f[2]=(byte)payload.length;f[3]=0x3E;
        f[4]=(byte)board;f[5]=(byte)cmd;f[6]=(byte)index;
        System.arraycopy(payload,0,f,7,payload.length);
        return f;
    }
    static long unsignedLE(byte[] bytes) {
        long v=0;
        for(int i=0;i<bytes.length && i<4;i++)v|=((long)(bytes[i]&255))<<(8*i);
        return v;
    }

    /** Byte-equivalent Encryption2 Gen3 crypto, AES-128 ECB block primitive. */
    private static final class Crypto {
        private static final byte[] ZERO16=new byte[16];
        private byte[] aesKey;
        private byte[] auth=new byte[16];
        private int counter, lastRx=-1;
        private boolean sn;
        void reset() { auth=new byte[16];counter=0;lastRx=-1;sn=false;aesKey=null; }
        void setKey(byte[] k1,byte[] k2) throws GeneralSecurityException {
            MessageDigest sha1=MessageDigest.getInstance("SHA-1");
            sha1.update(Arrays.copyOf(k1,16));
            sha1.update(k2==null?ZERO16:Arrays.copyOf(k2,16));
            aesKey=Arrays.copyOf(sha1.digest(),16);
        }
        void setAuth(byte[] value) { auth=Arrays.copyOf(value,16); }
        void startSn() {sn=true;counter=1;lastRx=-1;}
        byte[] encrypt(byte[] clear) throws GeneralSecurityException {
            if(aesKey==null)throw new GeneralSecurityException("Key not initialized");
            byte[] pay=Arrays.copyOfRange(clear,3,clear.length);
            if(!sn) {
                byte[] enc=xorSegments(pay,ecb(ZERO16));
                int sum=0;for(byte b:pay)sum+=(b&255);
                int check=(~sum)&65535;
                return concat(Arrays.copyOf(clear,3),enc,
                    new byte[]{0,0,(byte)check,(byte)(check>>>8),0,0});
            }
            counter++;
            byte[] nonce=nonce(counter);
            byte[] tag=mac(clear,nonce);
            byte[] encrypted=new byte[pay.length];
            for(int offset=0,idx=1;offset<pay.length;offset+=16,idx++) {
                byte[] keystream=ecb(counterBlock(nonce,idx));
                for(int i=offset;i<Math.min(offset+16,pay.length);i++)
                    encrypted[i]=(byte)(pay[i]^keystream[i-offset]);
            }
            byte[] a0=ecb(counterBlock(nonce,0));
            for(int i=0;i<4;i++)tag[i]^=a0[i];
            return concat(Arrays.copyOf(clear,3),encrypted,Arrays.copyOf(tag,4),
                    new byte[]{(byte)(counter>>>8),(byte)counter});
        }
        Packet decrypt(byte[] data) throws GeneralSecurityException {
            if(data.length<13 || aesKey==null) return new Packet(null,-2);
            byte[] head=Arrays.copyOf(data,3);
            int ctr=((data[data.length-2]&255)<<8)|(data[data.length-1]&255);
            byte[] pay=Arrays.copyOfRange(data,3,data.length-6);
            if(ctr==0)return new Packet(concat(head,xorSegments(pay,ecb(ZERO16))),0);
            if(ctr<=lastRx)return new Packet(null,-3);
            byte[] n=nonce(ctr);
            byte[] plain=new byte[pay.length];
            for(int offset=0,idx=1;offset<pay.length;offset+=16,idx++) {
                byte[] ks=ecb(counterBlock(n,idx));
                for(int i=offset;i<Math.min(offset+16,pay.length);i++)
                    plain[i]=(byte)(pay[i]^ks[i-offset]);
            }
            byte[] clear=concat(head,plain);
            byte[] received=Arrays.copyOfRange(data,data.length-6,data.length-2);
            byte[] a0=ecb(counterBlock(n,0));
            for(int i=0;i<4;i++)received[i]^=a0[i];
            if(!MessageDigest.isEqual(received,mac(clear,n)))return new Packet(null,-2);
            lastRx=ctr;
            return new Packet(clear,0);
        }
        byte[] nonce(int ctr) {
            byte[] n=new byte[13];n[0]=(byte)(ctr>>>24);n[1]=(byte)(ctr>>>16);
            n[2]=(byte)(ctr>>>8);n[3]=(byte)ctr;
            System.arraycopy(auth,0,n,4,8);
            return n;
        }
        byte[] counterBlock(byte[] nonce,int index) {
            byte[] block=new byte[16];block[0]=1;
            System.arraycopy(nonce,0,block,1,13);
            block[15]=(byte)index;
            return block;
        }
        byte[] mac(byte[] clear,byte[] nonce) throws GeneralSecurityException {
            byte[] pay=Arrays.copyOfRange(clear,3,clear.length);
            byte[] b0=new byte[16];b0[0]=0x59;
            System.arraycopy(nonce,0,b0,1,13);
            b0[15]=(byte)pay.length;
            byte[] x=ecb(b0);
            byte[] header=new byte[16];System.arraycopy(clear,0,header,0,3);
            x=ecb(xor16(x,header));
            for(int offset=0;offset<pay.length;offset+=16) {
                byte[] blk=new byte[16];
                System.arraycopy(pay,offset,blk,0,Math.min(16,pay.length-offset));
                x=ecb(xor16(x,blk));
            }
            return Arrays.copyOf(x,4);
        }
        byte[] ecb(byte[] block) throws GeneralSecurityException {
            Cipher c=Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(aesKey,"AES"));
            return c.doFinal(block);
        }
        static byte[] xor16(byte[] a,byte[] b) {
            byte[] out=new byte[16];for(int i=0;i<16;i++)out[i]=(byte)(a[i]^b[i]);return out;
        }
        static byte[] xorSegments(byte[] in,byte[] key) {
            byte[] out=new byte[in.length];
            for(int i=0;i<in.length;i++)out[i]=(byte)(in[i]^key[i%16]);
            return out;
        }
        static byte[] concat(byte[]... parts) {
            int n=0;for(byte[] p:parts)n+=p.length;
            byte[] result=new byte[n];int off=0;
            for(byte[] p:parts){System.arraycopy(p,0,result,off,p.length);off+=p.length;}
            return result;
        }
    }
}
