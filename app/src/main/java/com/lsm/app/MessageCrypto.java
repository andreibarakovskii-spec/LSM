package com.lsm.app;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class MessageCrypto {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final SecureRandom RNG = new SecureRandom();

    private MessageCrypto() {}

    static String encrypt(String plainText, String sharedSecret) throws Exception {
        byte[] iv = new byte[12];
        RNG.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key(sharedSecret), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(iv, Base64.NO_WRAP) + "." + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    static String decrypt(String payload, String sharedSecret) throws Exception {
        String[] parts = payload.split("\\.", 2);
        if (parts.length != 2) throw new IllegalArgumentException("bad payload");
        byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key(sharedSecret), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private static SecretKeySpec key(String sharedSecret) throws Exception {
        if (sharedSecret == null || sharedSecret.trim().length() < 8) {
            throw new IllegalArgumentException("shared secret must be at least 8 characters");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] key = digest.digest(("LSM-test-v1|" + sharedSecret).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }
}
