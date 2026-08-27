package com.cde.platform.mfa;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;

/**
 * Wires the second factor, only when a deployment has configured one.
 *
 * <p>Gated on {@code cde.mfa.enabled} so that a deployment without an
 * encryption key starts without the feature rather than failing to start. The
 * alternative — shipping a default key so it always works — would make every
 * deployment's second factors forgeable by anyone reading this repository.
 */
@Configuration
@EnableConfigurationProperties(MfaProperties.class)
@ConditionalOnProperty(prefix = "cde.mfa", name = "enabled", havingValue = "true")
public class MfaConfiguration {

    @Bean
    public SecretEncryption mfaSecretEncryption(MfaProperties properties) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(properties.getSecretEncryptionKey());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException(
                "cde.mfa.secret-encryption-key must be base64. Generate one with "
                + "`openssl rand -base64 32`.", notBase64);
        }
        // The length check lives in SecretEncryption, so it applies wherever
        // the class is constructed rather than only on this path.
        return new SecretEncryption(key);
    }

    @Bean
    public TimeBasedOneTimePassword timeBasedOneTimePassword(MfaProperties properties) {
        return new TimeBasedOneTimePassword(
            properties.macAlgorithm(),
            TimeBasedOneTimePassword.DEFAULT_TIME_STEP_SECONDS,
            TimeBasedOneTimePassword.DEFAULT_DIGITS);
    }
}
