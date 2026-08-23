package com.cde.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;

/**
 * Signing configuration for the tokens issued by {@link
 * com.cde.platform.security.JwtTokenService}.
 *
 * <p>The secret has no default. A signing key checked into the repository is
 * a published key: anyone holding it can mint a token for any user, and
 * rotating it afterwards is a coordinated deploy rather than a config change.
 * The application therefore refuses to start without one rather than falling
 * back to a value an attacker can read on GitHub.
 *
 * <p>Rejecting a weak or previously-committed key at startup matters more than
 * rejecting a missing one. A missing key is obvious the first time anybody
 * tries to log in; a key that is present but public authenticates everyone
 * exactly as if it were sound.
 */
@ConfigurationProperties(prefix = "cde.jwt")
@Validated
public class JwtProperties {

    /**
     * HMAC-SHA256 derives no more strength than the key carries, and RFC 7518
     * §3.2 requires a key at least as long as the digest for HS256.
     */
    static final int MINIMUM_SECRET_LENGTH_BYTES = 32;

    /**
     * The value that shipped in {@code application.yml} until this class
     * existed. It is in the git history permanently, so it is refused by name:
     * anyone deploying from an older checkout, or copying the old default into
     * an environment variable, is stopped rather than silently insecure.
     */
    static final String RETIRED_DEFAULT_SECRET =
        "cde-platform-super-secret-key-change-in-production-2024";

    @NotBlank(message = """
        cde.jwt.secret is required and has no default. Generate one with \
        `openssl rand -base64 48` and supply it as the CDE_JWT_SECRET \
        environment variable.""")
    private String secret;

    @Positive(message = "cde.jwt.expiration-ms must be a positive duration in milliseconds")
    private long expirationMs = 86_400_000L;

    @AssertTrue(message = """
        cde.jwt.secret is too short. HS256 needs at least 32 bytes of key \
        material; generate one with `openssl rand -base64 48`.""")
    boolean isSecretLongEnough() {
        // A blank secret is reported by @NotBlank; saying it twice buries the
        // message that actually tells the operator what to do.
        return secret == null || secret.isBlank()
            || secret.getBytes(StandardCharsets.UTF_8).length >= MINIMUM_SECRET_LENGTH_BYTES;
    }

    @AssertTrue(message = """
        cde.jwt.secret is the placeholder that was previously committed to \
        this repository and is public. Generate a new one with \
        `openssl rand -base64 48`.""")
    boolean isSecretNotTheRetiredDefault() {
        return !RETIRED_DEFAULT_SECRET.equals(secret);
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public void setExpirationMs(long expirationMs) {
        this.expirationMs = expirationMs;
    }
}
