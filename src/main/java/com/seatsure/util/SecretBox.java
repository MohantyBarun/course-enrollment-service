package com.seatsure.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for sensitive values stored at rest, such as the responses kept for
 * idempotent replays (they can contain personal data). Output is base64(iv || ciphertext || tag).
 */
@Component
public class SecretBox {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] DERIVATION_LABEL = "seatsure/data-encryption/v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(@Value("${app.crypto.key:}") String configuredKey, @Value("${jwt.secret}") String jwtSecret) {
        this.key = new SecretKeySpec(resolveKey(configuredKey, jwtSecret), "AES");
    }

    /**
     * Uses app.crypto.key when set. Otherwise derives a separate key from the JWT secret
     * (HMAC-SHA256 over a fixed label), so the signing secret itself is never used as a cipher key.
     */
    static byte[] resolveKey(String configuredKey, String fallbackSecret) {
        if (configuredKey != null && !configuredKey.isBlank()) {
            byte[] key = Base64.getDecoder().decode(configuredKey.trim());
            if (key.length != 32) {
                throw new IllegalStateException("app.crypto.key must be 32 bytes, base64-encoded");
            }
            return key;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(fallbackSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(DERIVATION_LABEL);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not derive the data encryption key", e);
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /** @throws IllegalStateException if the value was tampered with or encrypted under another key */
    public String decrypt(String token) {
        try {
            byte[] data = Base64.getDecoder().decode(token);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            return new String(cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
