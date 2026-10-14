package com.dennis.g3connect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Synthetic wire-format smoke test. No real scooter, account or secrets.
 * Construct inbound responses separately to verify the decrypt keystream.
 */
public final class CryptoSmokeTest {
    private static final String NAME="TEST1234567890";
    private static final byte[] FW=new byte[]{
        (byte)0x97,(byte)0xCF,(byte)0xB8,0x02,(byte)0x84,0x41,0x43,(byte)0xDE,
        0x56,0,0x2B,0x3B,0x34,0x78,0x0A,0x5D
    };
    public static void main(String[] args) throws Exception {
        require(NinebotProbe.allowedSettingWrite(0x49,30),"Auto-off 30 should be allowed");
        require(NinebotProbe.allowedSettingWrite(0x76,80),"Volume 80 should be allowed");
        require(!NinebotProbe.allowedSettingWrite(0x49,0),"Invalid auto-off must be blocked");
        require(!NinebotProbe.allowedSettingWrite(0x76,101),"Invalid volume must be blocked");
        require(NinebotProbe.allowedSettingWrite(0x70,0),"Regen OFF allowed");
        require(NinebotProbe.allowedSettingWrite(0x70,1),"Regen LOW allowed");
        require(NinebotProbe.allowedSettingWrite(0x70,2),"Regen STANDARD allowed");
        require(!NinebotProbe.allowedSettingWrite(0x70,3),"Unsupported regen level must be blocked");
        require(!NinebotProbe.allowedSettingWrite(0x42,5),"Kick-start modification must be blocked");
        require(!NinebotProbe.allowedSettingWrite(0x4A,8),"Unverified custom button blocked");
        require(!NinebotProbe.allowedSettingWrite(0x82,85),"BMS modifications must be blocked");
        NinebotProbe virgin=new NinebotProbe();
        boolean blocked=false;
        try {virgin.readBmsRegister(0x07,0x8C);}catch(java.security.GeneralSecurityException e){blocked=true;}
        require(blocked,"BMS access before authentication must be blocked");
        System.out.println("PASS settings write allowlist and bounds");
        for(boolean gen2:new boolean[]{false,true}) {
            NinebotProbe probe=new NinebotProbe();
            probe.setGeneration(gen2);
            byte[] command=probe.beginProbe(NAME);
            require(command.length==13,"PRE_COMM packet should be 13 bytes");
            require(command[0]==0x5A && command[1]==(byte)(gen2?0xA5:0xB5),
                "Unexpected request sync");
            NinebotProbe.Packet packet=probe.decrypt(reply(gen2,NAME));
            require(packet.error==0,"Decode failed gen2="+gen2+" error="+packet.error);
            NinebotProbe.Reply response=probe.parse(packet.plaintext);
            require(response.command==NinebotProbe.PRE_COMM && response.payload.length==30,
                "Incorrect response command/payload on gen2="+gen2);
            probe.onPreComm(response);
            require(probe.hasStoredPassword(),"Stored-password bit was lost");
            require(probe.getSerial().equals(NAME),"Serial could not be decoded correctly");
            NinebotProbe wrong=new NinebotProbe();
            wrong.setGeneration(gen2);
            wrong.beginProbe("OTHER123456789");
            NinebotProbe.Packet rejected=wrong.decrypt(reply(gen2,NAME));
            require(rejected.error!=0,"Incorrect key must fail checksum");
            System.out.println("PASS "+(gen2?"Gen2/Nordic":"Gen3/Ninebot")+
                " encrypted PRE_COMM, checksum and wrong-key detection");
        }
    }
    private static byte[] reply(boolean gen2,String serial) throws Exception {
        byte[] clear=new byte[37];
        clear[0]=0x5A;clear[1]=(byte)(gen2?0xA5:0xB5);
        clear[2]=30;clear[3]=0x04;clear[4]=0x3E;clear[5]=0x5B;clear[6]=1;
        for(int i=0;i<16;i++)clear[7+i]=(byte)(i+1);
        System.arraycopy(serial.getBytes(StandardCharsets.US_ASCII),0,clear,23,14);
        byte[] keyInput=gen2?FW:new byte[16];
        MessageDigest sha=MessageDigest.getInstance("SHA-1");
        sha.update(Arrays.copyOf(serial.getBytes(StandardCharsets.US_ASCII),16));
        sha.update(keyInput);
        byte[] key=Arrays.copyOf(sha.digest(),16);
        Cipher aes=Cipher.getInstance("AES/ECB/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"));
        byte[] stream=aes.doFinal(keyInput);
        byte[] result=new byte[clear.length+6];
        System.arraycopy(clear,0,result,0,3);
        int sum=0;
        for(int i=3;i<clear.length;i++){
            result[i]=(byte)(clear[i]^stream[(i-3)%16]);
            sum+=clear[i]&255;
        }
        int checksum=(~sum)&65535;
        result[result.length-4]=(byte)checksum;
        result[result.length-3]=(byte)(checksum>>>8);
        return result;
    }
    private static void require(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
}
