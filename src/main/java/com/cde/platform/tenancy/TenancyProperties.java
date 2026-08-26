package com.cde.platform.tenancy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Settings for how tenant isolation is applied to database connections.
 */
@ConfigurationProperties(prefix = "cde.tenancy")
@Validated
public class TenancyProperties {

    /**
     * The restricted PostgreSQL role every application query runs as.
     *
     * <p>It must not be a superuser, must not hold BYPASSRLS, and must not own
     * the tables — any of those makes PostgreSQL skip Row-Level Security
     * entirely and silently, leaving every policy in place and enforcing none
     * of them.
     */
    @NotBlank
    @Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_$]*$",
             message = "cde.tenancy.application-role must be a plain SQL identifier")
    private String applicationRole = "cde_app";

    /** The tenant that owns rows created before tenancy existed. */
    @NotBlank
    private String defaultTenantSlug = "default";

    /**
     * What an anonymous caller reaching {@code POST /api/auth/register} gets.
     *
     * <p>It used to get an account in {@link #defaultTenantSlug}, which meant
     * anyone who could reach the endpoint could read every project in the
     * deployment. Isolation was enforced correctly throughout; there was simply
     * nothing to isolate, because everyone was on the same side of it.
     */
    @NotNull
    private SelfRegistration selfRegistration = SelfRegistration.CREATE_TENANT;

    /** How long an unredeemed invitation stays valid. */
    @NotNull
    private Duration invitationValidity = Duration.ofDays(7);

    public enum SelfRegistration {

        /**
         * A registration with no invitation creates its own tenant, and the
         * registrant administers it.
         *
         * <p>The default, because a product nobody can sign up to has a
         * different problem, and because an organisation has to be able to
         * reach the core workflow without an operator provisioning anything.
         */
        CREATE_TENANT,

        /**
         * Only an invitation admits anyone. Suits a deployment serving named
         * organisations, where an unrecognised signup is a mistake rather than
         * a customer.
         */
        INVITATION_ONLY,

        /**
         * The endpoint refuses everyone. Accounts are provisioned out of band —
         * the expected setting for a sovereign or air-gapped deployment, where
         * an account created by anyone who can reach the network is exactly
         * the thing the deployment exists to prevent.
         */
        DISABLED
    }

    public SelfRegistration getSelfRegistration() {
        return selfRegistration;
    }

    public void setSelfRegistration(SelfRegistration selfRegistration) {
        this.selfRegistration = selfRegistration;
    }

    public Duration getInvitationValidity() {
        return invitationValidity;
    }

    public void setInvitationValidity(Duration invitationValidity) {
        this.invitationValidity = invitationValidity;
    }

    public String getApplicationRole() {
        return applicationRole;
    }

    public void setApplicationRole(String applicationRole) {
        this.applicationRole = applicationRole;
    }

    public String getDefaultTenantSlug() {
        return defaultTenantSlug;
    }

    public void setDefaultTenantSlug(String defaultTenantSlug) {
        this.defaultTenantSlug = defaultTenantSlug;
    }
}
