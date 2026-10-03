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
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
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

    // ── The guards that fire when the tier itself is unset ─────────────────

    @Nested
    @DisplayName("a configuration with no tier named")
    class NoTierNamed {

        private DeploymentProperties withNoTier() {
            var properties = new DeploymentProperties();
            properties.setTier(null);
            return properties;
        }

        @Test
        @DisplayName("the outbound rule does not refuse it on the tier's behalf")
        void outboundRuleDefersToTheTierCheck() {
            // There is a separate @NotNull saying the tier is required, and it
            // produces the message an operator can act on. This rule answering
            // first would replace that with "a government or Defence deployment
            // may not call third-party services" on a configuration that names
            // no tier at all — which sends them looking for the wrong fault.
            assertThat(withNoTier().isOutboundUseAllowedByTier()).isTrue();
        }

        @Test
        @DisplayName("the contract-interval rule does not refuse it either")
        void contractRuleDefersToTheTierCheck() {
            var properties = withNoTier();
            properties.setContractPasswordExpiryDays(45);

            assertThat(properties.isContractExpiryWithinTierBounds()).isTrue();
        }

        @Test
        @DisplayName("a contract interval with no tier is not range-checked against nothing")
        void boundsCheckNeedsATier() {
            var properties = withNoTier();
            properties.setContractPasswordExpiryDays(100_000);

            assertThat(properties.isContractExpiryWithinTierBounds()).isTrue();
        }
    }

    @Nested
    @DisplayName("the contract interval's range")
    class ContractIntervalBounds {

        private DeploymentProperties defenceWith(Integer days) {
            var properties = new DeploymentProperties();
            properties.setTier(DeploymentTier.DEFENCE);
            properties.setContractPasswordExpiryDays(days);
            return properties;
        }

        @Test
        @DisplayName("no interval at all is not range-checked, only required")
        void absentIntervalIsNotRangeChecked() {
            // Two rules, two messages: one says the value is missing, the other
            // says it is out of range. Running both on an absent value would
            // report the second, which is not true of it.
            assertThat(defenceWith(null).isContractExpiryWithinTierBounds()).isTrue();
        }

        @Test
        @DisplayName("an interval inside the tier's range is accepted")
        void insideTheRangeIsAccepted() {
            assertThat(defenceWith(90).isContractExpiryWithinTierBounds()).isTrue();
        }

        @Test
        @DisplayName("the lowest permitted interval is inside the range")
        void theMinimumIsInclusive() {
            assertThat(defenceWith(DeploymentTier.DEFENCE.minimumExpiryDays())
                .isContractExpiryWithinTierBounds()).isTrue();
        }

        @Test
        @DisplayName("the highest permitted interval is inside the range")
        void theMaximumIsInclusive() {
            assertThat(defenceWith(DeploymentTier.DEFENCE.maximumExpiryDays())
                .isContractExpiryWithinTierBounds()).isTrue();
        }

        @Test
        @DisplayName("below the range is refused")
        void belowTheRangeIsRefused() {
            assertThat(defenceWith(DeploymentTier.DEFENCE.minimumExpiryDays() - 1)
                .isContractExpiryWithinTierBounds()).isFalse();
        }

        @Test
        @DisplayName("above the range is refused")
        void aboveTheRangeIsRefused() {
            assertThat(defenceWith(DeploymentTier.DEFENCE.maximumExpiryDays() + 1)
                .isContractExpiryWithinTierBounds()).isFalse();
        }

        @Test
        @DisplayName("a zero interval is refused, because expiry cannot be switched off")
        void zeroIsRefused() {
            // §4.2 is explicit: tenants choose the interval, not whether it
            // applies, and there is no "never expires" option. Zero would be
            // one.
            assertThat(defenceWith(0).isContractExpirySuppliedWhenRequired()).isFalse();
        }

        @Test
        @DisplayName("a negative interval is refused")
        void negativeIsRefused() {
            assertThat(defenceWith(-30).isContractExpirySuppliedWhenRequired()).isFalse();
        }

        @Test
        @DisplayName("a tier other than Defence needs no contract interval")
        void otherTiersNeedNoContractInterval() {
            var commercial = new DeploymentProperties();
            commercial.setTier(DeploymentTier.COMMERCIAL);

            assertThat(commercial.isContractExpirySuppliedWhenRequired()).isTrue();
        }
    }

    @Nested
    @DisplayName("which interval actually applies")
    class EffectiveInterval {

        @Test
        @DisplayName("Defence uses the contract value when one is set")
        void defenceUsesTheContractValue() {
            var properties = new DeploymentProperties();
            properties.setTier(DeploymentTier.DEFENCE);
            properties.setContractPasswordExpiryDays(45);

            assertThat(properties.defaultExpiryDays()).isEqualTo(45);
        }

        @Test
        @DisplayName("Defence falls back to the tier's own default without one")
        void defenceFallsBackWithoutAContractValue() {
            // Reachable only when validation has been bypassed, and still has
            // to produce a number rather than a null: a policy that cannot say
            // when a password expires is a policy that does not expire one.
            var properties = new DeploymentProperties();
            properties.setTier(DeploymentTier.DEFENCE);

            assertThat(properties.defaultExpiryDays())
                .isEqualTo(DeploymentTier.DEFENCE.defaultExpiryDays());
        }

        @Test
        @DisplayName("another tier ignores a contract value even if one is set")
        void otherTiersIgnoreTheContractValue() {
            // The contract interval is a Defence concept. Honouring it
            // elsewhere would let a commercial deployment pin an interval the
            // tenant is supposed to be able to change.
            var properties = new DeploymentProperties();
            properties.setTier(DeploymentTier.COMMERCIAL);
            properties.setContractPasswordExpiryDays(45);

            assertThat(properties.defaultExpiryDays())
                .isEqualTo(DeploymentTier.COMMERCIAL.defaultExpiryDays());
        }
    }
}
