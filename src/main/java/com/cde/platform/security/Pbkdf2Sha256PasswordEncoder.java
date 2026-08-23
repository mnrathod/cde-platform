package com.cde.platform.security;

import org.springframework.security.crypto.password.PasswordEncoder;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * PBKDF2-HMAC-SHA-256 that records its own cost parameters in the hash it
 * produces, in the form {@code $iterations$salt$hash}.
 *
 * <p>Spring Security ships {@code Pbkdf2PasswordEncoder}, and it is not used
 * here for one reason: it stores only salt and digest, and re-derives using
 * whatever iteration count is configured at verification time. Raising the
 * count — which the ISM and OWASP both say to do as hardware improves —
 * therefore makes every existing password stop verifying, and its
 * {@code upgradeEncoding} returns {@code false}, so nothing detects the
 * staleness either. The failure is silent and total: a routine cost increase
 * locks out the entire user base and reports it as a wave of wrong passwords.
 *
 * <p>Embedding the iteration count fixes that. A hash is verified with the
 * parameters that produced it, and is re-hashed to the current ones on the next
 * successful login (see {@link RehashingUserDetailsPasswordService}), so the
 * cost can be raised at any time with no user-visible event.
 *
 * <p>This is not a bespoke construction. The primitive is the JDK's own
 * {@code PBKDF2WithHmacSHA256}, the format mirrors the widely-used PHC layout,
 * and comparison is constant-time via {@link MessageDigest#isEqual}.
 */
public class Pbkdf2Sha256PasswordEncoder implements PasswordEncoder {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    /** 128-bit per-user salt, generated fresh on every encode. */
    private static final int SALT_LENGTH_BYTES = 16;

    /** 256-bit derived key, matching the digest width of SHA-256. */
    private static final int DERIVED_KEY_LENGTH_BITS = 256;

    private static final int EXPECTED_FIELD_COUNT = 4;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder base64Encoder = Base64.getEncoder().withoutPadding();
    private final Base64.Decoder base64Decoder = Base64.getDecoder();

    private final int iterations;

    public Pbkdf2Sha256PasswordEncoder(int iterations) {
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be positive");
        }
        this.iterations = iterations;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        secureRandom.nextBytes(salt);
        byte[] derivedKey = deriveKey(rawPassword, salt, iterations);

        return "$" + iterations
             + "$" + base64Encoder.encodeToString(salt)
             + "$" + base64Encoder.encodeToString(derivedKey);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        StoredHash stored = parse(encodedPassword);
        if (stored == null || rawPassword == null) {
            return false;
        }

        byte[] candidate = deriveKey(rawPassword, stored.salt(), stored.iterations());
        // Constant-time: a byte-by-byte comparison that returns early leaks how
        // much of the digest matched, which is enough to reconstruct it.
        return MessageDigest.isEqual(candidate, stored.derivedKey());
    }

    /**
     * Reports a hash as stale when it was derived with fewer iterations than
     * are configured now, which is what triggers the transparent re-hash.
     *
     * <p>A hash that used <em>more</em> iterations than currently configured is
     * left alone. Re-hashing it would quietly weaken a stored credential, and
     * §4.1 says the count is never reduced.
     */
    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        StoredHash stored = parse(encodedPassword);
        return stored != null && stored.iterations() < iterations;
    }

    private byte[] deriveKey(CharSequence rawPassword, byte[] salt, int iterationCount) {
        char[] password = toCharArray(rawPassword);
        var keySpec = new PBEKeySpec(password, salt, iterationCount, DERIVED_KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(keySpec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // Not recoverable and not something a caller can act on: the JRE is
            // missing a mandatory algorithm, or the parameters are malformed.
            throw new IllegalStateException("Unable to derive PBKDF2 key", e);
        } finally {
            // Clears the PBEKeySpec's internal copy; the array above is ours.
            keySpec.clearPassword();
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static char[] toCharArray(CharSequence rawPassword) {
        char[] chars = new char[rawPassword.length()];
        for (int i = 0; i < rawPassword.length(); i++) {
            chars[i] = rawPassword.charAt(i);
        }
        return chars;
    }

    /**
     * @return the parsed hash, or {@code null} for anything that is not a
     *         well-formed hash of this scheme. Malformed input is a
     *         non-matching password, never an exception — a stored value
     *         corrupted by a bad migration should fail one login, not return a
     *         500 that tells the caller the format it failed to parse.
     */
    private StoredHash parse(String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isEmpty() || encodedPassword.charAt(0) != '$') {
            return null;
        }

        String[] fields = encodedPassword.split("\\$");
        // A leading '$' makes split() produce an empty first element.
        if (fields.length != EXPECTED_FIELD_COUNT) {
            return null;
        }

        try {
            int storedIterations = Integer.parseInt(fields[1]);
            if (storedIterations < 1) {
                return null;
            }
            return new StoredHash(
                storedIterations,
                base64Decoder.decode(fields[2]),
                base64Decoder.decode(fields[3]));
        } catch (IllegalArgumentException e) {
            // Covers both a non-numeric iteration count and a malformed Base64
            // field; NumberFormatException is a subclass.
            return null;
        }
    }

    private record StoredHash(int iterations, byte[] salt, byte[] derivedKey) {
    }
}
