package com.cde.platform.config;

import com.cde.platform.config.PasswordEncodingProperties.KeyDerivationFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The upgrade path matters more than the algorithm choice here. Every account
 * that existed before this change carries a bare BCrypt hash with no {@code
 * {id}} prefix, and a delegating encoder rejects unprefixed input as malformed
 * by default — which would present as every single user's password suddenly
 * being wrong.
 */
class PasswordEncoderConfigTest {

    private static final String PASSWORD = "correct horse battery staple";

    /**
     * Low cost so the suite stays fast; the production floor of 600,000 is
     * enforced by {@link PasswordEncodingProperties} and covered separately.
     */
    private PasswordEncoder encoderFor(KeyDerivationFunction kdf) {
        var properties = new PasswordEncodingProperties();
        properties.setKdf(kdf);
        properties.setPbkdf2Iterations(1_000);
        return new PasswordEncoderConfig().passwordEncoder(properties);
    }

    @Test
    @DisplayName("a password hashed before this change still authenticates")
    void verifiesLegacyUnprefixedBcryptHashes() {
        String legacyHash = new BCryptPasswordEncoder().encode(PASSWORD);
        assertThat(legacyHash).doesNotStartWith("{");

        assertThat(encoderFor(KeyDerivationFunction.PBKDF2_SHA256).matches(PASSWORD, legacyHash))
            .as("existing users must not be locked out by the KDF change")
            .isTrue();
    }

    @Test
    void flagsLegacyBcryptHashesForUpgrade() {
        String legacyHash = new BCryptPasswordEncoder().encode(PASSWORD);

        assertThat(encoderFor(KeyDerivationFunction.PBKDF2_SHA256).upgradeEncoding(legacyHash))
            .as("a BCrypt hash should be re-hashed to the configured KDF on next login")
            .isTrue();
    }

    @Test
    void newHashesCarryTheConfiguredAlgorithmPrefix() {
        assertThat(encoderFor(KeyDerivationFunction.PBKDF2_SHA256).encode(PASSWORD))
            .startsWith("{pbkdf2-sha256}");

        assertThat(encoderFor(KeyDerivationFunction.ARGON2ID).encode(PASSWORD))
            .startsWith("{argon2id}");
    }

    @Test
    @DisplayName("switching the configured KDF does not invalidate existing hashes")
    void bothFunctionsRemainVerifiableWhicheverIsConfigured() {
        String hashedUnderPbkdf2 = encoderFor(KeyDerivationFunction.PBKDF2_SHA256).encode(PASSWORD);
        String hashedUnderArgon2 = encoderFor(KeyDerivationFunction.ARGON2ID).encode(PASSWORD);

        PasswordEncoder nowArgon2 = encoderFor(KeyDerivationFunction.ARGON2ID);
        PasswordEncoder nowPbkdf2 = encoderFor(KeyDerivationFunction.PBKDF2_SHA256);

        assertThat(nowArgon2.matches(PASSWORD, hashedUnderPbkdf2)).isTrue();
        assertThat(nowPbkdf2.matches(PASSWORD, hashedUnderArgon2)).isTrue();
    }

    @Test
    void rejectsAWrongPasswordUnderEveryRegisteredFunction() {
        PasswordEncoder encoder = encoderFor(KeyDerivationFunction.PBKDF2_SHA256);

        assertThat(encoder.matches("wrong", encoderFor(KeyDerivationFunction.PBKDF2_SHA256).encode(PASSWORD))).isFalse();
        assertThat(encoder.matches("wrong", encoderFor(KeyDerivationFunction.ARGON2ID).encode(PASSWORD))).isFalse();
        assertThat(encoder.matches("wrong", new BCryptPasswordEncoder().encode(PASSWORD))).isFalse();
    }

    @Test
    @DisplayName("the shipped default is the FIPS-validated function")
    void defaultsToPbkdf2() {
        // Argon2id is the stronger function, but PBKDF2-HMAC-SHA-256 is what
        // ISM- and FIPS-bound deployments are permitted to use, so it is the
        // default that works everywhere.
        assertThat(new PasswordEncodingProperties().getKdf())
            .isEqualTo(KeyDerivationFunction.PBKDF2_SHA256);
    }
}
