package com.prodject.gloader.phoneinstaller;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;

final class AdbCrypto {
    private static final String PREFS = "adb_keys";
    private static final String PRIVATE_KEY = "private_key";

    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    private AdbCrypto(PrivateKey privateKey, PublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    static AdbCrypto loadOrCreate(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encoded = prefs.getString(PRIVATE_KEY, null);
        if (encoded != null) {
            byte[] keyBytes = Base64.decode(encoded, Base64.NO_WRAP);
            PrivateKey privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new java.security.spec.RSAPublicKeySpec(
                            ((java.security.interfaces.RSAPrivateCrtKey) privateKey).getModulus(),
                            ((java.security.interfaces.RSAPrivateCrtKey) privateKey).getPublicExponent()));
            return new AdbCrypto(privateKey, publicKey);
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        prefs.edit()
                .putString(PRIVATE_KEY, Base64.encodeToString(pair.getPrivate().getEncoded(), Base64.NO_WRAP))
                .apply();
        return new AdbCrypto(pair.getPrivate(), pair.getPublic());
    }

    byte[] sign(byte[] token) throws Exception {
        Signature signature = Signature.getInstance("SHA1withRSA");
        signature.initSign(privateKey);
        signature.update(token);
        return signature.sign();
    }

    byte[] publicKeyPayload() {
        RSAPublicKey rsa = (RSAPublicKey) publicKey;
        byte[] adbKey = encodeAdbPublicKey(rsa);
        String base64 = Base64.encodeToString(adbKey, Base64.NO_WRAP);
        return (base64 + " gloader-installer@android\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static byte[] encodeAdbPublicKey(RSAPublicKey key) {
        BigInteger modulus = key.getModulus();
        BigInteger exponent = key.getPublicExponent();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(modulus);
        BigInteger n0inv = modulus.mod(r32).modInverse(r32).negate().mod(r32);

        ByteBuffer buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(64);
        buffer.putInt(n0inv.intValue());
        buffer.put(littleEndianWords(modulus, 256));
        buffer.put(littleEndianWords(rr, 256));
        buffer.putInt(exponent.intValue());
        return buffer.array();
    }

    private static byte[] littleEndianWords(BigInteger value, int size) {
        byte[] bigEndian = value.toByteArray();
        byte[] output = new byte[size];
        for (int i = 0; i < size; i++) {
            int source = bigEndian.length - 1 - i;
            output[i] = source >= 0 ? bigEndian[source] : 0;
        }
        return output;
    }
}
