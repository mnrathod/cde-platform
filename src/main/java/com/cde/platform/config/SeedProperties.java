package com.cde.platform.config;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Whether this deployment seeds a demonstration account and sample data, and
 * with what password.
 *
 * <p>Seeding is off unless someone turns it on. It used to be unconditional,
 * which meant every deployment — including a production one — booted with an
 * administrator whose username and password were both written in this
 * repository. That is the default-credentials finding that appears in every
 * penetration-test report ever written, and it is not mitigated by nobody
 * having documented the account: the value is public, so it is known.
 *
 * <p>When seeding <em>is</em> on, the password has no default and no fallback,
 * for the same reason {@link JwtProperties#getSecret()} has none. A default
 * that exists is a default that ships. The application therefore refuses to
 * start rather than inventing one, and refuses the retired value by name so
 * that a checkout of an older revision, or a copied-forward environment file,
 * is stopped instead of quietly restoring the account it replaced.
 */
@ConfigurationProperties(prefix = "cde.seed")
@Validated
public class SeedProperties {

    /**
     * Matches the tenant password policy's minimum (§4.2). A seeded account is
     * a real account — it can sign in, and it holds ADMIN — so it is held to
     * the same rule as one a person chooses.
     */
    static final int MINIMUM_PASSWORD_LENGTH = 12;

    /**
     * The password this seeder used to hard-code. Named here so that setting
     * it explicitly is refused: it is in the git history permanently and is
     * therefore public, exactly like a retired signing key.
     */
    static final String RETIRED_DEFAULT_PASSWORD = "admin123";

    /**
     * Off by default. A deployment that wants demonstration data asks for it;
     * one that says nothing gets an empty database and creates its first
     * organisation through registration, which is the path a real tenant uses.
     */
    private boolean enabled = false;

    private String adminUsername = "admin";

    private String adminEmail = "admin@cde.invalid";

    /** No default, deliberately. See the class comment. */
    private String adminPassword;

    @AssertTrue(message = """
        cde.seed.admin-password is required when cde.seed.enabled is true, and \
        has no default. Generate one with `openssl rand -base64 24` and supply \
        it as the CDE_SEED_ADMIN_PASSWORD environment variable — or leave \
        cde.seed.enabled at its default of false and register an account \
        instead.""")
    boolean isPasswordPresentWhenSeeding() {
        return !enabled || (adminPassword != null && !adminPassword.isBlank());
    }

    @AssertTrue(message = """
        cde.seed.admin-password is shorter than the 12 characters the password \
        policy requires. A seeded administrator is a real administrator.""")
    boolean isPasswordLongEnough() {
        // A missing password is reported by the check above; repeating it here
        // buries the message that tells the operator what to do.
        return !enabled || adminPassword == null || adminPassword.isBlank()
            || adminPassword.length() >= MINIMUM_PASSWORD_LENGTH;
    }

    @AssertTrue(message = """
        cde.seed.admin-password is the value this seeder used to hard-code. It \
        is in this repository's history and is public. Generate a new one with \
        `openssl rand -base64 24`.""")
    boolean isPasswordNotTheRetiredDefault() {
        return !RETIRED_DEFAULT_PASSWORD.equals(adminPassword);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    public String getAdminEmail() {
        return adminEmail;
    }

    public void setAdminEmail(String adminEmail) {
        this.adminEmail = adminEmail;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }
}
