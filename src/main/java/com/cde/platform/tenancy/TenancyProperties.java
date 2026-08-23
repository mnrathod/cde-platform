package com.cde.platform.tenancy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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
