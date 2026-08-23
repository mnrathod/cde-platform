package com.cde.platform.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Which key derivation function protects stored passwords, and with what cost.
 *
 * <p>Two are offered because the choice is a deployment concern rather than an
 * engineering one. PBKDF2-HMAC-SHA-256 is FIPS 140-validated and on the ASD
 * ISM approved list, which government and Defence deployments are frequently
 * required to use. Argon2id resists GPU and ASIC attack considerably better
 * because it is memory-hard, and is the better choice wherever a validated
 * module is not contractually required.
 *
 * <p>Both are registered for verification at all times regardless of this
 * setting — it selects only what newly-set passwords are hashed with. Changing
 * it does not invalidate a single existing password; those are re-hashed
 * individually on each user's next successful login.
 */
@ConfigurationProperties(prefix = "cde.security.password")
@Validated
public class PasswordEncodingProperties {

    /**
     * The floor is OWASP's current PBKDF2-HMAC-SHA-256 recommendation. It is
     * enforced rather than merely defaulted because the iteration count is the
     * only thing standing between a stolen hash table and an offline crack —
     * SHA-256 is deliberately fast, and a commodity GPU tests billions of raw
     * candidates per second.
     */
    static final int MINIMUM_PBKDF2_ITERATIONS = 600_000;

    /** OWASP's Argon2id baseline: 19 MiB, expressed in kibibytes. */
    static final int MINIMUM_ARGON2_MEMORY_KIB = 19 * 1024;

    public enum KeyDerivationFunction {
        /** FIPS 140-validated and ASD ISM approved. The default. */
        PBKDF2_SHA256("pbkdf2-sha256"),
        /** Memory-hard, stronger against GPU attack, not FIPS-validated. */
        ARGON2ID("argon2id");

        private final String encoderId;

        KeyDerivationFunction(String encoderId) {
            this.encoderId = encoderId;
        }

        /** The {@code {id}} prefix written into every hash this function produces. */
        public String encoderId() {
            return encoderId;
        }
    }

    @NotNull(message = "cde.security.password.kdf must be pbkdf2-sha256 or argon2id")
    private KeyDerivationFunction kdf = KeyDerivationFunction.PBKDF2_SHA256;

    @Min(value = MINIMUM_PBKDF2_ITERATIONS, message = """
        cde.security.password.pbkdf2-iterations is below the 600000 floor. \
        Tune it upward to roughly 500ms on production hardware; never down.""")
    private int pbkdf2Iterations = MINIMUM_PBKDF2_ITERATIONS;

    @Min(value = MINIMUM_ARGON2_MEMORY_KIB,
         message = "cde.security.password.argon2-memory-kib is below the OWASP baseline of 19456")
    private int argon2MemoryKib = MINIMUM_ARGON2_MEMORY_KIB;

    @Min(value = 2, message = "cde.security.password.argon2-iterations must be at least 2")
    private int argon2Iterations = 2;

    public KeyDerivationFunction getKdf() {
        return kdf;
    }

    public void setKdf(KeyDerivationFunction kdf) {
        this.kdf = kdf;
    }

    public int getPbkdf2Iterations() {
        return pbkdf2Iterations;
    }

    public void setPbkdf2Iterations(int pbkdf2Iterations) {
        this.pbkdf2Iterations = pbkdf2Iterations;
    }

    public int getArgon2MemoryKib() {
        return argon2MemoryKib;
    }

    public void setArgon2MemoryKib(int argon2MemoryKib) {
        this.argon2MemoryKib = argon2MemoryKib;
    }

    public int getArgon2Iterations() {
        return argon2Iterations;
    }

    public void setArgon2Iterations(int argon2Iterations) {
        this.argon2Iterations = argon2Iterations;
    }
}
