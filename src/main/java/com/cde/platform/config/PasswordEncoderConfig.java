package com.cde.platform.config;

import com.cde.platform.config.PasswordEncodingProperties.KeyDerivationFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.cde.platform.security.Pbkdf2Sha256PasswordEncoder;

import java.util.Map;

/**
 * Builds the password encoder from {@link PasswordEncodingProperties}.
 *
 * <p>The encoder is a {@link DelegatingPasswordEncoder}, which prefixes every
 * hash it writes with the algorithm that produced it — {@code
 * {pbkdf2-sha256}...}. That prefix is what makes the parameters evolvable: a
 * hash carries its own provenance, so raising the iteration count or switching
 * function is a configuration change rather than a mass password reset.
 *
 * <p>All three functions stay registered for verification whatever the
 * configured default is. Removing one would lock out every user whose password
 * was last set under it.
 */
@Configuration
public class PasswordEncoderConfig {

    /** Argon2 salt and output widths, in bytes: 128-bit salt, 256-bit hash. */
    private static final int ARGON2_SALT_LENGTH_BYTES = 16;
    private static final int ARGON2_HASH_LENGTH_BYTES = 32;

    /**
     * Argon2 parallelism. OWASP's baseline is 1, and raising it without also
     * raising memory weakens the function rather than strengthening it.
     */
    private static final int ARGON2_PARALLELISM = 1;

    @Bean
    public PasswordEncoder passwordEncoder(PasswordEncodingProperties properties) {
        Map<String, PasswordEncoder> encodersById = Map.of(
            KeyDerivationFunction.PBKDF2_SHA256.encoderId(),
                new Pbkdf2Sha256PasswordEncoder(properties.getPbkdf2Iterations()),
            KeyDerivationFunction.ARGON2ID.encoderId(), argon2Encoder(properties),
            // Retained for verification only. Passwords hashed with BCrypt
            // before this configuration existed must still authenticate; each
            // one is re-hashed to the configured function on its owner's next
            // successful login, by RehashingUserDetailsPasswordService.
            "bcrypt", new BCryptPasswordEncoder()
        );

        var delegating = new DelegatingPasswordEncoder(
            properties.getKdf().encoderId(), encodersById);

        // Hashes written before the delegating encoder existed carry no {id}
        // prefix at all. Without this they would be rejected outright as
        // malformed, locking out every account that predates this change.
        delegating.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());

        return delegating;
    }

    private PasswordEncoder argon2Encoder(PasswordEncodingProperties properties) {
        return new Argon2PasswordEncoder(
            ARGON2_SALT_LENGTH_BYTES,
            ARGON2_HASH_LENGTH_BYTES,
            ARGON2_PARALLELISM,
            properties.getArgon2MemoryKib(),
            properties.getArgon2Iterations());
    }
}
