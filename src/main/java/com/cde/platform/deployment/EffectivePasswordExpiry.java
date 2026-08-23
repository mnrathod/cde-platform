package com.cde.platform.deployment;

/**
 * A resolved password expiry interval, together with why it is that value.
 *
 * <p>The provenance is not decoration. An administrator who sets 365 days and
 * sees 90 needs to know the deployment capped it, or they will file a support
 * ticket — or, worse, keep trying. Showing the effective value without its
 * origin is how a settings screen becomes something people distrust.
 *
 * @param days             the interval actually applied
 * @param source           where that value came from
 * @param tenantAdjustable whether an administrator may change it at all
 * @param minimumDays      the tightest interval this deployment allows
 * @param maximumDays      the loosest interval this deployment allows
 */
public record EffectivePasswordExpiry(
    int days,
    PolicySource source,
    boolean tenantAdjustable,
    int minimumDays,
    int maximumDays
) {

    public enum PolicySource {
        /** Nobody has chosen; this is the shipped default for the tier. */
        SYSTEM_DEFAULT,
        /** The tenant chose this, and it is within what the deployment allows. */
        TENANT_OVERRIDE,
        /**
         * The deployment decided, either because the tier fixes the value or
         * because the tenant's choice fell outside the permitted range.
         */
        DEPLOYMENT_POLICY
    }

    /**
     * A sentence fit to put under the field on a settings screen.
     */
    public String explanation() {
        return switch (source) {
            case SYSTEM_DEFAULT -> "Using the default of " + days + " days.";
            case TENANT_OVERRIDE -> "Set for this organisation.";
            case DEPLOYMENT_POLICY -> tenantAdjustable
                ? "Limited to " + days + " days by deployment policy."
                : "Fixed at " + days + " days by deployment policy and cannot be changed here.";
        };
    }
}
