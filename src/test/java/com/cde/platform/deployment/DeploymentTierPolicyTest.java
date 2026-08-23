package com.cde.platform.deployment;

import com.cde.platform.deployment.EffectivePasswordExpiry.PolicySource;
import com.cde.platform.deployment.PasswordExpiryPolicyResolver.PolicyCeilingExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ceilings, and the startup refusals that keep a sovereign deployment from
 * booting into a configuration its contract forbids.
 */
class DeploymentTierPolicyTest {

    private PasswordExpiryPolicyResolver resolverFor(DeploymentTier tier, Integer contractDays) {
        var properties = new DeploymentProperties();
        properties.setTier(tier);
        properties.setContractPasswordExpiryDays(contractDays);
        return new PasswordExpiryPolicyResolver(properties);
    }

    @Nested
    @DisplayName("commercial")
    class Commercial {

        private final PasswordExpiryPolicyResolver resolver =
            resolverFor(DeploymentTier.COMMERCIAL, null);

        @Test
        void defaultsToNinetyDays() {
            EffectivePasswordExpiry effective = resolver.resolve(null);

            assertThat(effective.days()).isEqualTo(90);
            assertThat(effective.source()).isEqualTo(PolicySource.SYSTEM_DEFAULT);
        }

        @Test
        void honoursATenantChoiceInsideTheRange() {
            EffectivePasswordExpiry effective = resolver.resolve(180);

            assertThat(effective.days()).isEqualTo(180);
            assertThat(effective.source()).isEqualTo(PolicySource.TENANT_OVERRIDE);
        }

        @Test
        @DisplayName("expiry cannot be switched off — there is no 'never' option")
        void thereIsNoNeverExpires() {
            // The guidelines make the interval configurable and the expiry
            // itself mandatory. A resolver that could return "no expiry" would
            // make that a matter of configuration.
            assertThat(resolver.resolve(null).days()).isPositive();
            assertThat(resolver.resolve(999_999).days())
                .isLessThanOrEqualTo(DeploymentTier.COMMERCIAL.maximumExpiryDays());
        }
    }

    @Nested
    @DisplayName("government")
    class Government {

        private final PasswordExpiryPolicyResolver resolver =
            resolverFor(DeploymentTier.GOVERNMENT, null);

        @Test
        @DisplayName("a tenant cannot exceed the 90-day ceiling")
        void clampsAChoiceAboveTheCeiling() {
            EffectivePasswordExpiry effective = resolver.resolve(365);

            assertThat(effective.days()).isEqualTo(90);
            assertThat(effective.source()).isEqualTo(PolicySource.DEPLOYMENT_POLICY);
            assertThat(effective.explanation()).contains("deployment policy");
        }

        @Test
        @DisplayName("a value stored under a looser tier does not survive the move")
        void aStoredValueOutsideTheRangeIsNotHonoured() {
            // The realistic path to a bad value: nobody edits anything, the
            // deployment tightens underneath a choice made earlier.
            assertThat(resolver.resolve(365).days()).isEqualTo(90);
        }

        @Test
        void refusesAnAdministratorSettingSomethingLooser() {
            assertThatThrownBy(() -> resolver.validateTenantChoice(180))
                .isInstanceOf(PolicyCeilingExceededException.class)
                .hasMessageContaining("between 30 and 90 days");
        }

        @Test
        void permitsAnAdministratorSettingSomethingTighter() {
            resolver.validateTenantChoice(30);
        }
    }

    @Nested
    @DisplayName("defence")
    class Defence {

        private final PasswordExpiryPolicyResolver resolver =
            resolverFor(DeploymentTier.DEFENCE, 45);

        @Test
        @DisplayName("the contract value applies whatever the tenant stored")
        void tenantChoiceIsIgnoredEntirely() {
            assertThat(resolver.resolve(365).days()).isEqualTo(45);
            assertThat(resolver.resolve(30).days()).isEqualTo(45);
            assertThat(resolver.resolve(null).days()).isEqualTo(45);
        }

        @Test
        void reportsThatItCannotBeChangedHere() {
            EffectivePasswordExpiry effective = resolver.resolve(null);

            assertThat(effective.tenantAdjustable()).isFalse();
            assertThat(effective.source()).isEqualTo(PolicySource.DEPLOYMENT_POLICY);
            assertThat(effective.explanation()).contains("cannot be changed here");
        }

        @Test
        void refusesEveryAdministratorChange() {
            assertThatThrownBy(() -> resolver.validateTenantChoice(45))
                .isInstanceOf(PolicyCeilingExceededException.class);
        }
    }

    @Nested
    @DisplayName("outbound calls are refused at startup, not at first use")
    class EgressValidation {

        private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class,
                ValidationAutoConfiguration.class))
            .withUserConfiguration(BindDeploymentProperties.class);

        @Configuration
        @EnableConfigurationProperties(DeploymentProperties.class)
        static class BindDeploymentProperties {
        }

        @Test
        void commercialMayCallOut() {
            contextRunner
                .withPropertyValues("cde.security.deployment.tier=commercial")
                .run(context -> assertThat(context).hasNotFailed());
        }

        @ParameterizedTest
        @EnumSource(value = DeploymentTier.class, names = {"GOVERNMENT", "DEFENCE"})
        @DisplayName("a sovereign deployment will not start configured to call a third party")
        void sovereignTiersRefuseOnlineServices(DeploymentTier tier) {
            contextRunner
                .withPropertyValues(
                    "cde.security.deployment.tier=" + tier.name().toLowerCase(),
                    "cde.security.deployment.contract-password-expiry-days=45",
                    "cde.security.deployment.breached-password-check=online_api")
                .run(context -> assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("may not call third-party services"));
        }

        @Test
        @DisplayName("the same deployment starts happily against a local dataset")
        void localDatasetIsAcceptedOnSovereignTiers() {
            contextRunner
                .withPropertyValues(
                    "cde.security.deployment.tier=government",
                    "cde.security.deployment.breached-password-check=local_dataset",
                    "cde.security.deployment.ai-features=disabled",
                    "cde.security.deployment.telemetry=disabled")
                .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("a Defence deployment without its contract interval will not start")
        void defenceRequiresItsContractInterval() {
            contextRunner
                .withPropertyValues(
                    "cde.security.deployment.tier=defence",
                    "cde.security.deployment.breached-password-check=local_dataset",
                    "cde.security.deployment.ai-features=disabled",
                    "cde.security.deployment.telemetry=disabled")
                .run(context -> assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("fixes the password expiry interval by contract"));
        }

        @Test
        @DisplayName("the shipped default is the permissive tier, so nothing is silently locked down")
        void defaultsToCommercial() {
            assertThat(new DeploymentProperties().getTier()).isEqualTo(DeploymentTier.COMMERCIAL);
        }
    }

    @Nested
    @DisplayName("tier capabilities")
    class Capabilities {

        @Test
        void onlyCommercialPermitsEgress() {
            assertThat(DeploymentTier.COMMERCIAL.permitsOutboundCalls()).isTrue();
            assertThat(DeploymentTier.GOVERNMENT.permitsOutboundCalls()).isFalse();
            assertThat(DeploymentTier.DEFENCE.permitsOutboundCalls()).isFalse();
        }

        @Test
        void sovereignTiersRequireExplicitAiOptIn() {
            assertThat(DeploymentTier.COMMERCIAL.requiresExplicitAiOptIn()).isFalse();
            assertThat(DeploymentTier.GOVERNMENT.requiresExplicitAiOptIn()).isTrue();
            assertThat(DeploymentTier.DEFENCE.requiresExplicitAiOptIn()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(DeploymentTier.class)
        @DisplayName("every tier has a coherent range containing its default")
        void rangesAreSane(DeploymentTier tier) {
            assertThat(tier.minimumExpiryDays()).isPositive();
            assertThat(tier.maximumExpiryDays()).isGreaterThanOrEqualTo(tier.minimumExpiryDays());
            assertThat(tier.defaultExpiryDays())
                .isBetween(tier.minimumExpiryDays(), tier.maximumExpiryDays());
        }
    }
}
