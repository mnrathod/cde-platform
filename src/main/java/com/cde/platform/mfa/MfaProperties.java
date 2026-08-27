package com.cde.platform.mfa;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Second-factor settings.
 *
 * <p>{@code enabled} exists because MFA needs a key to encrypt secrets with,
 * and a deployment that has not provisioned one should start without the
 * feature rather than not start at all — or, worse, start with a built-in key.
 */
@ConfigurationProperties(prefix = "cde.mfa")
@Validated
public class MfaProperties {

    /** Whether the second-factor endpoints are available at all. */
    private boolean enabled = false;

    /**
     * Name shown beside the account in an authenticator app.
     *
     * <p>Users commonly hold several accounts across several products; without
     * a distinct issuer their list is a column of identical usernames.
     */
    @NotBlank
    private String issuer = "CDE Platform";

    /**
     * AES-256 key protecting TOTP secrets at rest. Base64, decoding to exactly
     * 32 bytes. No default.
     *
     * <p>Required when MFA is enabled. A TOTP secret is a bearer credential
     * with nothing to crack — the plaintext is the credential — so a built-in
     * key would mean every deployment's second factors are forgeable by anyone
     * who reads this repository. Generate with {@code openssl rand -base64 32}.
     *
     * <p>§5.2 wants this from a KMS, with envelope encryption and per-tenant
     * data keys so a tenant can be crypto-shredded on offboarding. That is not
     * built: one key protects every tenant, and rotating it means re-encrypting
     * every secret. Recorded rather than implied.
     */
    private String secretEncryptionKey;

    /**
     * TOTP MAC algorithm. SHA1 is the RFC 6238 default and what every
     * authenticator app interoperates on; SHA256 is available where a
     * deployment requires it, at the cost of some apps failing enrolment
     * silently.
     */
    private String algorithm = "SHA1";

    @AssertTrue(message = """
        cde.mfa.secret-encryption-key is required when cde.mfa.enabled is true, \
        and has no default. TOTP secrets are bearer credentials with nothing to \
        crack, so a built-in key would make every deployment's second factors \
        forgeable. Generate one with `openssl rand -base64 32` and supply it as \
        the CDE_MFA_SECRET_ENCRYPTION_KEY environment variable.""")
    boolean isEncryptionKeyPresentWhenEnabled() {
        return !enabled || (secretEncryptionKey != null && !secretEncryptionKey.isBlank());
    }

    @AssertTrue(message = """
        cde.mfa.algorithm must be SHA1, SHA256 or SHA512. SHA1 is the RFC 6238 \
        default and the only one every authenticator app supports.""")
    boolean isAlgorithmSupported() {
        return java.util.Set.of("SHA1", "SHA256", "SHA512").contains(algorithm);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getSecretEncryptionKey() {
        return secretEncryptionKey;
    }

    public void setSecretEncryptionKey(String secretEncryptionKey) {
        this.secretEncryptionKey = secretEncryptionKey;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    /** The JCE name for the configured algorithm. */
    public String macAlgorithm() {
        return "Hmac" + algorithm;
    }
}
