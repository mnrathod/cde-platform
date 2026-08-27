package com.cde.platform.mfa;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * TOTP codes, per RFC 6238 and the HOTP construction in RFC 4226.
 *
 * <h2>Why this is written here rather than pulled in</h2>
 * TOTP is not cryptography we are inventing. The primitive is HMAC, taken
 * from the JDK, and this class is the counter derivation and truncation the
 * RFCs specify around it — about thirty lines of well-specified arithmetic
 * with published test vectors to check it against. Adding a dependency to
 * avoid writing it would mean a new supply-chain entry, a new licence to
 * clear and a new upgrade cadence, for code whose correctness is verifiable
 * against the standard itself. The tests use the RFC's own vectors, so this
 * is checked against the specification rather than against itself.
 *
 * <p>The rule this does not break is the one that matters: no HMAC, no
 * digest, no key derivation is implemented here. Those come from the JRE.
 *
 * <h2>SHA-1</h2>
 * The default is HMAC-SHA-1, which is what every authenticator app
 * interoperates on, and CLAUDE.md §4.4 names it explicitly for this purpose.
 * That is not in tension with the SHA-1 ban in §4.1: the ban is on SHA-1 as a
 * collision-resistant hash, and HMAC's security does not rest on collision
 * resistance — HMAC-SHA-1 remains unbroken and is still the RFC 6238 default.
 * SHA-256 is supported for deployments that require it, at the cost of
 * enrolment failing silently in some authenticator apps.
 */
public final class TimeBasedOneTimePassword {

    /** RFC 6238 default, and what every authenticator app expects. */
    public static final int DEFAULT_TIME_STEP_SECONDS = 30;

    /** RFC 6238 default. Six digits is what users are shown everywhere. */
    public static final int DEFAULT_DIGITS = 6;

    /**
     * How many steps either side of now are accepted.
     *
     * <p>One step, so a code is valid for at most 90 seconds across the
     * window. Wider windows are a real temptation when support tickets arrive
     * about clock drift, and each extra step multiplies an attacker's guessing
     * odds against a six-digit code by three.
     */
    public static final int DEFAULT_DRIFT_STEPS = 1;

    private final String macAlgorithm;
    private final int timeStepSeconds;
    private final int digits;

    public TimeBasedOneTimePassword() {
        this("HmacSHA1", DEFAULT_TIME_STEP_SECONDS, DEFAULT_DIGITS);
    }

    public TimeBasedOneTimePassword(String macAlgorithm, int timeStepSeconds, int digits) {
        if (digits < 6 || digits > 8) {
            throw new IllegalArgumentException("RFC 4226 defines 6 to 8 digits; got " + digits);
        }
        if (timeStepSeconds <= 0) {
            throw new IllegalArgumentException("Time step must be positive");
        }
        this.macAlgorithm = macAlgorithm;
        this.timeStepSeconds = timeStepSeconds;
        this.digits = digits;
    }

    /** The time step number a given instant falls in. */
    public long timeStepAt(Instant when) {
        return Math.floorDiv(when.getEpochSecond(), timeStepSeconds);
    }

    /**
     * The code for one specific time step.
     *
     * <p>Public because verification needs to generate codes across the drift
     * window, and enrolment needs to show the user the current one.
     */
    public String generateForStep(byte[] secret, long timeStep) {
        byte[] mac = hmac(secret, ByteBuffer.allocate(Long.BYTES).putLong(timeStep).array());

        // Dynamic truncation, RFC 4226 section 5.3: the low nibble of the last
        // byte selects a 4-byte window, whose top bit is masked off so the
        // result is positive regardless of the platform's signed arithmetic.
        int offset = mac[mac.length - 1] & 0x0F;
        int binary = ((mac[offset] & 0x7F) << 24)
                   | ((mac[offset + 1] & 0xFF) << 16)
                   | ((mac[offset + 2] & 0xFF) << 8)
                   | (mac[offset + 3] & 0xFF);

        return String.format("%0" + digits + "d", binary % (int) Math.pow(10, digits));
    }

    /** The code for right now. */
    public String generate(byte[] secret, Instant when) {
        return generateForStep(secret, timeStepAt(when));
    }

    /**
     * Whether a presented code is valid, and for which time step.
     *
     * <p>Returns the matching step rather than a boolean because replay
     * protection needs it: the caller records the step a code was accepted for
     * and refuses anything at or below it. Without that, a code observed over
     * someone's shoulder stays usable for its whole 90-second window.
     *
     * @param notBeforeStep the last step already used by this account, or a
     *                      negative value if none. Steps at or below it are
     *                      refused even when the code is arithmetically correct.
     * @return the accepted step, or empty if the code is not valid
     */
    public java.util.OptionalLong verify(byte[] secret, String presented, Instant when, long notBeforeStep) {
        if (presented == null || presented.length() != digits) {
            return java.util.OptionalLong.empty();
        }

        long current = timeStepAt(when);
        for (long step = current - DEFAULT_DRIFT_STEPS; step <= current + DEFAULT_DRIFT_STEPS; step++) {
            if (step <= notBeforeStep) {
                // Already used. Checked before the comparison so a replayed
                // code costs the same as a wrong one.
                continue;
            }
            // Constant-time: a short-circuiting comparison leaks, through
            // timing, how many leading digits of a guess were right, which
            // turns a 10^6 search into six 10-way searches.
            if (MessageDigest.isEqual(
                    generateForStep(secret, step).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    presented.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                return java.util.OptionalLong.of(step);
            }
        }
        return java.util.OptionalLong.empty();
    }

    private byte[] hmac(byte[] secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance(macAlgorithm);
            mac.init(new SecretKeySpec(secret, macAlgorithm));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | InvalidKeyException failure) {
            throw new IllegalStateException(macAlgorithm + " is unavailable", failure);
        }
    }

    public int digits() {
        return digits;
    }

    public int timeStepSeconds() {
        return timeStepSeconds;
    }

    /** The algorithm name as it appears in an {@code otpauth://} URI. */
    public String algorithmLabel() {
        return switch (macAlgorithm) {
            case "HmacSHA1" -> "SHA1";
            case "HmacSHA256" -> "SHA256";
            case "HmacSHA512" -> "SHA512";
            default -> throw new IllegalStateException("Unmapped algorithm " + macAlgorithm);
        };
    }
}
