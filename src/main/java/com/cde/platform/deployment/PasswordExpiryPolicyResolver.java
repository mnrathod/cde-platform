package com.cde.platform.deployment;

import com.cde.platform.deployment.EffectivePasswordExpiry.PolicySource;
import org.springframework.stereotype.Service;

/**
 * Resolves the password expiry interval that actually applies, following the
 * hierarchy system default → deployment policy → tenant override.
 *
 * <p>Implemented once, centrally, rather than consulted feature by feature.
 * A ceiling that each caller has to remember to apply is a ceiling that one
 * caller will not apply.
 */
@Service
public class PasswordExpiryPolicyResolver {

    private final DeploymentProperties deployment;

    public PasswordExpiryPolicyResolver(DeploymentProperties deployment) {
        this.deployment = deployment;
    }

    /**
     * @param tenantChoiceDays what the tenant has stored, or {@code null} if
     *                         they have never chosen
     */
    public EffectivePasswordExpiry resolve(Integer tenantChoiceDays) {
        DeploymentTier tier = deployment.getTier();

        // A tier that fixes the interval ignores whatever is stored. This
        // matters most when it was not always fixed: a tenant that chose 365
        // days under a commercial deployment, later moved into a sovereign one,
        // must not carry that choice across.
        if (!tier.isTenantAdjustable()) {
            return new EffectivePasswordExpiry(
                deployment.defaultExpiryDays(), PolicySource.DEPLOYMENT_POLICY,
                false, tier.minimumExpiryDays(), tier.maximumExpiryDays());
        }

        if (tenantChoiceDays == null) {
            return new EffectivePasswordExpiry(
                deployment.defaultExpiryDays(), PolicySource.SYSTEM_DEFAULT,
                true, tier.minimumExpiryDays(), tier.maximumExpiryDays());
        }

        // Clamped rather than rejected, deliberately. This method reads values
        // that are already stored, and a stored value can fall out of range
        // without anyone editing it — because the deployment tier tightened
        // underneath it. Refusing to answer would take the login policy down;
        // applying the bound keeps it correct.
        int clamped = Math.clamp(tenantChoiceDays, tier.minimumExpiryDays(), tier.maximumExpiryDays());
        PolicySource source = clamped == tenantChoiceDays
            ? PolicySource.TENANT_OVERRIDE
            : PolicySource.DEPLOYMENT_POLICY;

        return new EffectivePasswordExpiry(
            clamped, source, true, tier.minimumExpiryDays(), tier.maximumExpiryDays());
    }

    /**
     * Checks a value an administrator is trying to set, as opposed to one
     * already stored.
     *
     * <p>Separate from {@link #resolve} because the right answer differs. A
     * stored value out of range is clamped so the system keeps working; a value
     * being typed in now is refused, so the administrator finds out immediately
     * rather than saving something that will not be honoured.
     *
     * @throws PolicyCeilingExceededException if the tier forbids the value
     */
    public void validateTenantChoice(int requestedDays) {
        DeploymentTier tier = deployment.getTier();

        if (!tier.isTenantAdjustable()) {
            throw new PolicyCeilingExceededException(
                "The password expiry interval is fixed at " + deployment.defaultExpiryDays()
                + " days by deployment policy and cannot be changed here.");
        }
        if (requestedDays < tier.minimumExpiryDays() || requestedDays > tier.maximumExpiryDays()) {
            throw new PolicyCeilingExceededException(
                "This deployment permits a password expiry interval between "
                + tier.minimumExpiryDays() + " and " + tier.maximumExpiryDays() + " days.");
        }
    }

    /**
     * Raised when a tenant administrator asks for something the deployment tier
     * does not allow. The message names the permitted range and nothing about
     * the deployment beyond that.
     */
    public static class PolicyCeilingExceededException extends RuntimeException {
        public PolicyCeilingExceededException(String message) {
            super(message);
        }
    }
}
