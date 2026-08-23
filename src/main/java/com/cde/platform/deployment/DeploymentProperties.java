package com.cde.platform.deployment;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * What kind of deployment this is, and what that permits.
 *
 * <p>Every constraint here is checked at startup rather than at the moment a
 * feature is used. A Defence deployment configured to call an external API
 * should fail to boot, loudly, in front of whoever deployed it — not succeed
 * and then make its first outbound call weeks later, in production, to a
 * service the contract forbids it from contacting at all.
 */
@ConfigurationProperties(prefix = "cde.security.deployment")
@Validated
public class DeploymentProperties {

    @NotNull(message = "cde.security.deployment.tier must be commercial, government or defence")
    private DeploymentTier tier = DeploymentTier.COMMERCIAL;

    /**
     * The password expiry interval fixed by contract. Required on the Defence
     * tier, where an administrator cannot choose one, and ignored elsewhere.
     */
    private Integer contractPasswordExpiryDays;

    /** Compromised-password checking against Have I Been Pwned (§4.3). */
    @NotNull
    private ExternalServiceMode breachedPasswordCheck = ExternalServiceMode.ONLINE_API;

    /** Generative AI features (§10). */
    @NotNull
    private ExternalServiceMode aiFeatures = ExternalServiceMode.ONLINE_API;

    /** Product telemetry sent off the deployment. */
    @NotNull
    private ExternalServiceMode telemetry = ExternalServiceMode.ONLINE_API;

    @AssertTrue(message = """
        A government or Defence deployment may not call third-party services. \
        Set cde.security.deployment.breached-password-check, .ai-features and \
        .telemetry to local-dataset or disabled.""")
    boolean isOutboundUseAllowedByTier() {
        if (tier == null || tier.permitsOutboundCalls()) {
            return true;
        }
        return !breachedPasswordCheck.requiresEgress()
            && !aiFeatures.requiresEgress()
            && !telemetry.requiresEgress();
    }

    @AssertTrue(message = """
        A Defence deployment fixes the password expiry interval by contract. \
        Set cde.security.deployment.contract-password-expiry-days.""")
    boolean isContractExpirySuppliedWhenRequired() {
        if (tier != DeploymentTier.DEFENCE) {
            return true;
        }
        return contractPasswordExpiryDays != null && contractPasswordExpiryDays > 0;
    }

    @AssertTrue(message = """
        cde.security.deployment.contract-password-expiry-days is outside the \
        range the tier permits.""")
    boolean isContractExpiryWithinTierBounds() {
        if (contractPasswordExpiryDays == null || tier == null) {
            return true;
        }
        return contractPasswordExpiryDays >= tier.minimumExpiryDays()
            && contractPasswordExpiryDays <= tier.maximumExpiryDays();
    }

    /**
     * The expiry interval this deployment applies when a tenant has not chosen
     * one — or, on the Defence tier, regardless of what a tenant has chosen.
     */
    public int defaultExpiryDays() {
        if (tier == DeploymentTier.DEFENCE && contractPasswordExpiryDays != null) {
            return contractPasswordExpiryDays;
        }
        return tier.defaultExpiryDays();
    }

    public DeploymentTier getTier() {
        return tier;
    }

    public void setTier(DeploymentTier tier) {
        this.tier = tier;
    }

    public Integer getContractPasswordExpiryDays() {
        return contractPasswordExpiryDays;
    }

    public void setContractPasswordExpiryDays(Integer contractPasswordExpiryDays) {
        this.contractPasswordExpiryDays = contractPasswordExpiryDays;
    }

    public ExternalServiceMode getBreachedPasswordCheck() {
        return breachedPasswordCheck;
    }

    public void setBreachedPasswordCheck(ExternalServiceMode breachedPasswordCheck) {
        this.breachedPasswordCheck = breachedPasswordCheck;
    }

    public ExternalServiceMode getAiFeatures() {
        return aiFeatures;
    }

    public void setAiFeatures(ExternalServiceMode aiFeatures) {
        this.aiFeatures = aiFeatures;
    }

    public ExternalServiceMode getTelemetry() {
        return telemetry;
    }

    public void setTelemetry(ExternalServiceMode telemetry) {
        this.telemetry = telemetry;
    }
}
