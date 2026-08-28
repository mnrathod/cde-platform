package com.cde.platform.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A signing key that is present but weak authenticates every request exactly as
 * if it were sound, so these assert on the startup failure rather than on any
 * runtime behaviour — by the time a token is being verified it is too late for
 * the check to mean anything.
 */
class JwtPropertiesTest {

    private static final String STRONG_SECRET =
        "9f2c8d41b7e6a3059c1d8b2f4e7a6c3d05b9e8f1a2c4d6e8b0f3a5c7d9e1b3f5";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            ConfigurationPropertiesAutoConfiguration.class,
            ValidationAutoConfiguration.class))
        .withUserConfiguration(BindJwtProperties.class);

    @Configuration
    @EnableConfigurationProperties(JwtProperties.class)
    static class BindJwtProperties {
    }

    @Test
    @DisplayName("a sound secret binds")
    void acceptsAStrongSecret() {
        contextRunner
            .withPropertyValues("cde.jwt.secret=" + STRONG_SECRET)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(JwtProperties.class).getSecret()).isEqualTo(STRONG_SECRET);
            });
    }

    @Test
    @DisplayName("no secret stops startup rather than defaulting")
    void refusesToStartWithoutASecret() {
        contextRunner
            .withPropertyValues("cde.jwt.secret=")
            .run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                // The validation message is on a nested cause, not the top
                // frame, so hasMessageContaining would silently look past it.
                .hasStackTraceContaining("cde.jwt.secret is required"));
    }

    @Test
    @DisplayName("a short secret is refused — HS256 derives no more strength than the key carries")
    void refusesASecretShorterThanTheDigestWidth() {
        contextRunner
            .withPropertyValues("cde.jwt.secret=too-short")
            .run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                // The validation message is on a nested cause, not the top
                // frame, so hasMessageContaining would silently look past it.
                .hasStackTraceContaining("too short"));
    }

    @Test
    @DisplayName("exactly 32 bytes is accepted; the boundary is inclusive")
    void acceptsASecretOfExactlyTheMinimumLength() {
        String exactlyMinimum = "a".repeat(JwtProperties.MINIMUM_SECRET_LENGTH_BYTES);

        contextRunner
            .withPropertyValues("cde.jwt.secret=" + exactlyMinimum)
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("one byte under the minimum is refused")
    void refusesASecretOneByteShort() {
        String oneShort = "a".repeat(JwtProperties.MINIMUM_SECRET_LENGTH_BYTES - 1);

        contextRunner
            .withPropertyValues("cde.jwt.secret=" + oneShort)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("the key that was committed to this repository is refused by name")
    void refusesThePreviouslyPublishedDefault() {
        // It is long enough to pass the length check, which is exactly why it
        // needs its own rule: nothing else about it looks wrong.
        assertThat(JwtProperties.RETIRED_DEFAULT_SECRET.length())
            .isGreaterThan(JwtProperties.MINIMUM_SECRET_LENGTH_BYTES);

        contextRunner
            .withPropertyValues("cde.jwt.secret=" + JwtProperties.RETIRED_DEFAULT_SECRET)
            .run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                // The validation message is on a nested cause, not the top
                // frame, so hasMessageContaining would silently look past it.
                .hasStackTraceContaining("previously committed"));
    }

    @Test
    void refusesANonPositiveExpiry() {
        contextRunner
            .withPropertyValues(
                "cde.jwt.secret=" + STRONG_SECRET,
                "cde.jwt.expiration-ms=0")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("no default value is compiled into the class")
    void secretHasNoFallbackValue() {
        // Guards the actual regression: a future edit adding `= "something"`
        // to the field would restore the published-key problem silently.
        assertThat(new JwtProperties().getSecret()).isNull();
    }
}
