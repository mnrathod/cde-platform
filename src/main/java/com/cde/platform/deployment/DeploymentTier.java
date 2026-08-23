package com.cde.platform.deployment;

/**
 * The regulatory envelope a deployment operates in.
 *
 * <p>This is set at deployment, not by a tenant, and it is what makes the
 * policy ceilings meaningful: a tenant administrator in a sovereign deployment
 * can tighten a setting but cannot loosen it past what the tier permits, and no
 * amount of administrative access changes that, because the bound is not stored
 * anywhere a tenant can write.
 */
public enum DeploymentTier {

    /**
     * Ordinary commercial hosting. External services are permitted, subject to
     * the per-tenant kill switches.
     */
    COMMERCIAL(30, 365, 90, true),

    /**
     * IRAP-scoped or equivalent government work, typically at OFFICIAL:
     * Sensitive or PROTECTED. Outbound calls to third parties are prohibited,
     * so compromised-password checking runs from a local dataset and AI
     * features run locally or not at all.
     */
    GOVERNMENT(30, 90, 90, true),

    /**
     * UK MOD or Australian Defence. Air-gapped: no outbound dependency of any
     * kind, and the password policy is fixed by contract rather than chosen by
     * an administrator. Changing it requires a redeployment, which is the
     * point — a contractual control that an administrator can edit is not a
     * contractual control.
     */
    DEFENCE(1, 365, 90, false);

    private final int minimumExpiryDays;
    private final int maximumExpiryDays;
    private final int defaultExpiryDays;
    private final boolean tenantAdjustable;

    DeploymentTier(int minimumExpiryDays, int maximumExpiryDays,
                   int defaultExpiryDays, boolean tenantAdjustable) {
        this.minimumExpiryDays = minimumExpiryDays;
        this.maximumExpiryDays = maximumExpiryDays;
        this.defaultExpiryDays = defaultExpiryDays;
        this.tenantAdjustable = tenantAdjustable;
    }

    public int minimumExpiryDays() {
        return minimumExpiryDays;
    }

    public int maximumExpiryDays() {
        return maximumExpiryDays;
    }

    public int defaultExpiryDays() {
        return defaultExpiryDays;
    }

    /**
     * Whether a tenant administrator may choose the expiry interval at all, as
     * opposed to being shown the value the deployment fixed.
     */
    public boolean isTenantAdjustable() {
        return tenantAdjustable;
    }

    /**
     * Whether this tier may call third-party services over the internet.
     *
     * <p>False for government and Defence alike. The guidelines group
     * IRAP PROTECTED with UK MOD and Australian Defence precisely because the
     * prohibition is the same: no outbound dependency, whatever the value it
     * would add.
     */
    public boolean permitsOutboundCalls() {
        return this == COMMERCIAL;
    }

    /**
     * Whether AI features are off unless a tenant administrator explicitly and
     * auditably turns them on.
     */
    public boolean requiresExplicitAiOptIn() {
        return this != COMMERCIAL;
    }
}
