package com.cde.platform.storage.local;

import com.cde.platform.storage.StorageKey;
import com.cde.platform.storage.StorageOperation;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

/**
 * The local provider's equivalent of a presigned URL.
 *
 * <p>Cloud backends sign a URL with a credential the storage service itself
 * verifies. There is no such service here, so the application signs a token
 * and verifies it on the way back in. The security properties that matter are
 * the same ones: the token names exactly one object and one operation, it
 * expires, and it cannot be altered or forged by whoever holds it.
 *
 * <h2>Why the encoding is length-prefixed</h2>
 * The signed message is built as {@code len:value;} per field rather than by
 * joining fields with a delimiter. Delimiter-joining is forgeable whenever any
 * field can contain the delimiter: an attacker choosing a value that embeds
 * the separator can produce the same canonical string as a different, more
 * privileged token. Object identifiers are constrained enough today that this
 * is not currently exploitable — but the encoding should not depend on a
 * constraint enforced somewhere else, because that constraint may be relaxed
 * by someone who never reads this file.
 *
 * <h2>Comparison</h2>
 * Signatures are compared with {@link MessageDigest#isEqual}, never
 * {@code String.equals}. A short-circuiting comparison leaks, through timing,
 * how many leading bytes of a guess were right, which turns forging a
 * signature into a byte-at-a-time search.
 */
public final class SignedObjectToken {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] signingKey;

    public SignedObjectToken(byte[] signingKey) {
        if (signingKey == null || signingKey.length < 32) {
            // 256 bits, matching the HMAC's own output. A shorter key does not
            // make HMAC-SHA-256 weak in an interesting way, but it is a
            // reliable sign that the value came from somewhere it shouldn't —
            // a config default, a truncated environment variable.
            throw new IllegalArgumentException(
                "The storage signing key must be at least 32 bytes");
        }
        this.signingKey = signingKey.clone();
    }

    /**
     * Mints a token granting {@code operation} on {@code key} until
     * {@code expiresAt}.
     */
    public String issue(StorageKey key, StorageOperation operation, Instant expiresAt) {
        String claims = canonicalise(key, operation, expiresAt);
        return ENCODER.encodeToString(claims.getBytes(StandardCharsets.UTF_8))
            + '.'
            + ENCODER.encodeToString(sign(claims));
    }

    /**
     * Checks a token against the object and operation being attempted.
     *
     * <p>Takes the key and operation from the <em>request</em> rather than
     * reading them out of the token, so a valid token for one object cannot
     * authorise another. Verification recomputes the signature over what is
     * actually being asked for; a token minted for something else simply will
     * not match.
     */
    public boolean isValidFor(String token, StorageKey key, StorageOperation operation, Instant now) {
        if (token == null) return false;

        int separator = token.indexOf('.');
        if (separator <= 0 || separator == token.length() - 1) return false;

        byte[] presented;
        String claims;
        try {
            claims = new String(DECODER.decode(token.substring(0, separator)), StandardCharsets.UTF_8);
            presented = DECODER.decode(token.substring(separator + 1));
        } catch (IllegalArgumentException malformed) {
            return false;
        }

        Instant expiresAt = expiryFrom(claims);
        if (expiresAt == null) return false;

        // Expiry is checked before the signature, and both are checked. A
        // correctly signed but expired token is exactly the case this exists
        // to refuse.
        if (!now.isBefore(expiresAt)) return false;

        byte[] expected = sign(canonicalise(key, operation, expiresAt));
        return MessageDigest.isEqual(expected, presented);
    }

    /**
     * The signed message. Each field is written as {@code length:value;} so
     * that no combination of field values can produce the same string as a
     * different combination.
     */
    private String canonicalise(StorageKey key, StorageOperation operation, Instant expiresAt) {
        StringBuilder canonical = new StringBuilder(128);
        appendField(canonical, key.path());
        appendField(canonical, operation.name());
        appendField(canonical, Long.toString(expiresAt.getEpochSecond()));
        return canonical.toString();
    }

    private static void appendField(StringBuilder canonical, String value) {
        canonical.append(value.length()).append(':').append(value).append(';');
    }

    /** Reads the expiry back out of a canonical claims string. */
    private static Instant expiryFrom(String claims) {
        try {
            int lastFieldStart = claims.lastIndexOf(';', claims.length() - 2);
            String tail = lastFieldStart < 0 ? claims : claims.substring(lastFieldStart + 1);
            int colon = tail.indexOf(':');
            int terminator = tail.lastIndexOf(';');
            if (colon < 0 || terminator <= colon) return null;
            return Instant.ofEpochSecond(Long.parseLong(tail.substring(colon + 1, terminator)));
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private byte[] sign(String message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            // HmacSHA256 is required of every JRE, and the key was length-checked
            // in the constructor.
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", impossible);
        }
    }
}
