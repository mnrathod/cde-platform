package com.cde.platform.config;

import com.cde.platform.repository.UserRepository;
import com.cde.platform.tenancy.TenancyProperties;
import com.cde.platform.tenancy.TenantContext;
import com.cde.platform.repository.TenantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a deployment starts with.
 *
 * <p>It used to start with an administrator called {@code admin} whose
 * password was written in {@code DataSeeder}, created unconditionally, in every
 * environment. Nothing failed because of it and nothing tested it, so it
 * survived from the first commit — which is how default credentials always
 * survive.
 */
class SeedingTest {

    @Nested
    @SpringBootTest
    @DisplayName("by default")
    class SeedingIsOff {

        @Autowired
        private UserRepository users;

        @Autowired
        private TenantRepository tenants;

        @Autowired
        private TenancyProperties tenancyProperties;

        @Autowired
        private SeedProperties seedProperties;

        @Test
        @DisplayName("the demonstration administrator is not created")
        void createsNoSeedAccount() {
            Long defaultTenantId = tenants
                .findBySlug(tenancyProperties.getDefaultTenantSlug()).orElseThrow().getId();

            var seedAccount = TenantContext.callAsTenant(defaultTenantId,
                () -> users.findByUsername(seedProperties.getAdminUsername()));

            // Asserted against the seed username rather than against an empty
            // table. The table is not empty: this suite shares one database,
            // and the fixtures other tests create land in the same default
            // tenant — so "no users at all" passed alone and failed in the
            // suite, describing test ordering rather than the seeder.
            assertThat(seedAccount).isEmpty();
        }
    }

    @Nested
    @DisplayName("configuration")
    class Validation {

        @Test
        @DisplayName("seeding without a password is refused")
        void requiresAPasswordWhenEnabled() {
            var properties = new SeedProperties();
            properties.setEnabled(true);

            assertThat(properties.isPasswordPresentWhenSeeding()).isFalse();
        }

        @Test
        @DisplayName("a password shorter than the policy minimum is refused")
        void requiresPolicyLength() {
            var properties = new SeedProperties();
            properties.setEnabled(true);
            properties.setAdminPassword("short");

            assertThat(properties.isPasswordLongEnough()).isFalse();
        }

        @Test
        @DisplayName("the password this seeder used to hard-code is refused by name")
        void refusesTheRetiredDefault() {
            var properties = new SeedProperties();
            properties.setEnabled(true);
            properties.setAdminPassword(SeedProperties.RETIRED_DEFAULT_PASSWORD);

            // It is in the git history permanently, so it is public. Someone
            // deploying from an older checkout, or copying the old value into
            // an environment file, is stopped rather than quietly restoring
            // the account this change removed.
            assertThat(properties.isPasswordNotTheRetiredDefault()).isFalse();
        }

        @Test
        @DisplayName("nothing is validated while seeding is off")
        void staysQuietWhenDisabled() {
            // The default deployment supplies no seed password and must not be
            // told off for it.
            var properties = new SeedProperties();

            assertThat(properties.isPasswordPresentWhenSeeding()).isTrue();
            assertThat(properties.isPasswordLongEnough()).isTrue();
        }
    }

    @Nested
    @DisplayName("binding")
    class FailsFast {

        private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SeedPropertiesConfiguration.class);

        @Test
        @DisplayName("seeding enabled with no password stops the application")
        void refusesToBootWithoutAPassword() {
            contextRunner
                .withPropertyValues("cde.seed.enabled=true")
                .run(context -> assertThat(context)
                    .hasFailed()
                    .getFailure()
                    // Asserted against the whole chain, not the top message:
                    // Spring's outermost wrapper says only "could not bind
                    // properties", and the sentence that tells an operator
                    // what to actually do is the validation message beneath
                    // it. Checking the top message would pass on any binding
                    // failure at all, including one with no guidance in it.
                    .hasStackTraceContaining("CDE_SEED_ADMIN_PASSWORD")
                    .hasStackTraceContaining("cde.seed.enabled"));
        }

        @Test
        @DisplayName("seeding enabled with a real password starts")
        void acceptsAConfiguredPassword() {
            contextRunner
                .withPropertyValues("cde.seed.enabled=true",
                                    "cde.seed.admin-password=a-locally-generated-value")
                .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("the default configuration starts with nothing set")
        void acceptsTheDefault() {
            contextRunner.run(context -> assertThat(context).hasNotFailed());
        }

        @EnableConfigurationProperties(SeedProperties.class)
        static class SeedPropertiesConfiguration {
        }
    }
}
