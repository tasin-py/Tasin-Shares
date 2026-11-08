package com.tasin.shares;

import android.content.Context;
import android.util.Base64;

import java.io.File;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/** The app's own ADB key: generated once, kept in app storage, approved once on the TV's "Allow debugging?" prompt. */
final class AdbKey {
    private static final int KEY_BITS = 2048;
    private static final int KEY_BYTES = KEY_BITS / 8;
    // ASN.1 DigestInfo prefix for SHA-1; adbd signs its 20-byte token as if it were a SHA-1 digest.
    private static final byte[] SHA1_PREFIX = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final RSAPrivateKey priv;
    private final RSAPublicKey pub;

    private AdbKey(RSAPrivateKey priv, RSAPublicKey pub) {
        this.priv = priv;
        this.pub = pub;
    }

    static synchronized AdbKey load(Context ctx) throws Exception {
        File privFile = new File(ctx.getFilesDir(), "adbkey");
        File pubFile = new File(ctx.getFilesDir(), "adbkey.x509");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (privFile.exists() && pubFile.exists()) {
            RSAPrivateKey priv = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privFile.toPath())));
            RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pubFile.toPath())));
            return new AdbKey(priv, pub);
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(new RSAKeyGenParameterSpec(KEY_BITS, RSAKeyGenParameterSpec.F4));
        KeyPair kp = gen.generateKeyPair();
        Files.write(privFile.toPath(), kp.getPrivate().getEncoded());
        Files.write(pubFile.toPath(), kp.getPublic().getEncoded());
        return new AdbKey((RSAPrivateKey) kp.getPrivate(), (RSAPublicKey) kp.getPublic());
    }

    /** PKCS#1 v1.5 type-1 signature of the token, padded by hand (adbd expects the raw SHA-1 layout). */
    byte[] sign(byte[] token) throws Exception {
        byte[] block = new byte[KEY_BYTES];
        int tokenAt = KEY_BYTES - token.length;
        int prefixAt = tokenAt - SHA1_PREFIX.length;
        block[0] = 0;
        block[1] = 1;
        for (int i = 2; i < prefixAt - 1; i++) block[i] = (byte) 0xff;
        block[prefixAt - 1] = 0;
        System.arraycopy(SHA1_PREFIX, 0, block, prefixAt, SHA1_PREFIX.length);
        System.arraycopy(token, 0, block, tokenAt, token.length);
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, priv);
        return c.doFinal(block);
    }

    /** The public key in Android's mincrypt layout, base64, as adbd stores it in adb_keys. */
    byte[] publicKeyPayload() {
        BigInteger n = pub.getModulus();
        int words = KEY_BYTES / 4;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n);
        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + KEY_BYTES * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(words);
        bb.putInt(n0inv.intValue());
        for (int i = 0; i < words; i++) bb.putInt(n.shiftRight(32 * i).intValue());
        for (int i = 0; i < words; i++) bb.putInt(rr.shiftRight(32 * i).intValue());
        bb.putInt(pub.getPublicExponent().intValue());
        String s = Base64.encodeToString(bb.array(), Base64.NO_WRAP) + " tvlink@tv\u0000";
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
