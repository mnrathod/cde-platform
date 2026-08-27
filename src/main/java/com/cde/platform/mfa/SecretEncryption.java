package com.cde.platform.mfa;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Field-level encryption for TOTP secrets, per §5.2.
 *
 * <p>A TOTP secret is a bearer credential: anyone holding it can generate that
 * user's codes indefinitely, and unlike a password hash there is nothing to
 * crack — the plaintext is the credential. So a database dump containing them
 * is equivalent to a dump containing plaintext second factors, which is why
 * these are encrypted in the application rather than relying on encryption at
 * rest alone. Volume encryption protects a stolen disk; it does nothing about
 * a SQL injection, a mistaken backup share, or a support engineer running a
 * SELECT.
 *
 * <h2>AES-256-GCM</h2>
 * Authenticated, so a tampered ciphertext fails to decrypt rather than
 * producing plausible garbage that would then be used as a secret. A fresh
 * 96-bit nonce per encryption, from {@link SecureRandom}: GCM fails
 * catastrophically on nonce reuse — two messages under the same key and nonce
 * leak the XOR of their plaintexts and, worse, the authentication subkey — so
 * the nonce is never derived from anything and never reused.
 *
 * <h2>Key management</h2>
 * The key comes from configuration. §5.2 wants it from a KMS with envelope
 * encryption and per-tenant data keys, which would additionally allow
 * per-tenant crypto-shredding on offboarding. That is not built, and the gap
 * is recorded rather than papered over: today, one key protects every tenant's
 * secrets, and rotating it requires re-encrypting them all.
 */
public final class SecretEncryption {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int AES_256_KEY_BYTES = 32;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretEncryption(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != AES_256_KEY_BYTES) {
            throw new IllegalArgumentException(
                "AES-256 needs exactly 32 bytes of key; got "
                + (keyBytes == null ? "none" : keyBytes.length + " bytes"));
        }
        this.key = new SecretKeySpec(keyBytes, KEY_ALGORITHM);
    }

    /**
     * Encrypts, returning base64 of {@code nonce || ciphertext || tag}.
     *
     * <p>The nonce travels with the ciphertext because it must: it is not
     * secret, and decryption needs it. Prefixing keeps the stored value a
     * single self-contained string rather than two columns that could drift
     * apart.
     */
    public String encrypt(byte[] plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plaintext);

            return Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(nonce.length + sealed.length)
                    .put(nonce).put(sealed).array());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Could not encrypt the secret", failure);
        }
    }

    /**
     * Decrypts what {@link #encrypt} produced.
     *
     * @throws IllegalStateException if the value was tampered with, truncated,
     *         or encrypted under a different key — all of which GCM detects,
     *         and none of which should be recoverable into a usable secret
     */
    public byte[] decrypt(String stored) {
        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(stored);
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException("Stored secret is not valid base64", notBase64);
        }
        if (combined.length <= NONCE_BYTES) {
            throw new IllegalStateException("Stored secret is too short to contain a nonce");
        }

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(TAG_BITS,
                    java.util.Arrays.copyOfRange(combined, 0, NONCE_BYTES)));
            return cipher.doFinal(combined, NONCE_BYTES, combined.length - NONCE_BYTES);
        } catch (GeneralSecurityException failure) {
            // Deliberately uninformative about which check failed. Telling a
            // caller whether the tag or the padding was wrong is a decryption
            // oracle, and there is no legitimate caller that needs to know.
            throw new IllegalStateException("Could not decrypt the secret", failure);
        }
    }

    /** A fresh 160-bit TOTP secret, which is what RFC 4226 recommends. */
    public byte[] generateTotpSecret() {
        byte[] secret = new byte[20];
        random.nextBytes(secret);
        return secret;
    }
}
