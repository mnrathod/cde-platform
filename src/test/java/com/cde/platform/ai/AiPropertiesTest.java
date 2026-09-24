package com.cde.platform.ai;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What counts as a configured model provider.
 *
 * <p>§13 asks that configuration be validated at startup and that the
 * application fail loudly rather than boot half-configured. The interesting
 * case here is the half that is not loud: a credential with no model beside
 * it names nothing to call, so the deployment would start, offer the feature,
 * and fail on the first request — which reads to whoever configured it as a
 * broken provider rather than as a line they left out.
 *
 * <p>The absent-credential case is deliberately the opposite, and worth
 * holding as such: AI is optional and nothing else depends on it, so no
 * configuration at all means the feature reports itself off, not that the
 * application refuses to start.
 */
@DisplayName("configuring a model provider")
class AiPropertiesTest {

    private final Validator validator =
        Validation.buildDefaultValidatorFactory().getValidator();

    private AiProperties configured(String apiKey, String model) {
        AiProperties properties = new AiProperties();
        properties.setApiKey(apiKey);
        properties.setModel(model);
        return properties;
    }

    private boolean isValid(AiProperties properties) {
        return validator.validate(properties).isEmpty();
    }

    @Test
    @DisplayName("a key with a model beside it is configured")
    void keyAndModelIsConfigured() {
        assertThat(configured("a-key", "a-model").isConfigured()).isTrue();
    }

    @Test
    @DisplayName("no configuration at all means the feature is simply off")
    void noConfigurationIsOff() {
        // Not a validation failure. AI is optional, and refusing to boot
        // over an unset optional feature takes the whole platform down for
        // something nobody asked for.
        AiProperties properties = new AiProperties();

        assertThat(properties.isConfigured()).isFalse();
        assertThat(isValid(properties)).isTrue();
    }

    @Test
    @DisplayName("a key with no model fails validation rather than failing later")
    void keyWithoutModelIsRejected() {
        // The half-configured case §13 exists for: this would otherwise
        // start, advertise the feature, and fail on first use.
        assertThat(isValid(configured("a-key", ""))).isFalse();
    }

    @Test
    @DisplayName("the message says what to set")
    void rejectionExplainsItself() {
        var violations = validator.validate(configured("a-key", ""));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
            .contains("cde.ai.model is required");
    }

    @Test
    @DisplayName("a model with no key is allowed, because it calls nothing")
    void modelWithoutKeyIsAllowed() {
        AiProperties properties = configured("", "a-model");

        assertThat(isValid(properties)).isTrue();
        assertThat(properties.isConfigured()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t", "\n"})
    @DisplayName("whitespace is not a credential")
    void whitespaceIsNotAKey(String blank) {
        assertThat(configured(blank, "a-model").isConfigured()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "\t"})
    @DisplayName("whitespace is not a model name either")
    void whitespaceIsNotAModel(String blank) {
        assertThat(configured("a-key", blank).isConfigured()).isFalse();
    }

    @Test
    @DisplayName("a null key is stored as empty rather than kept as null")
    void nullKeyBecomesEmpty() {
        // So every reader downstream can treat it as a string. A null that
        // survives into isConfigured is a NullPointerException at startup.
        AiProperties properties = new AiProperties();
        properties.setApiKey(null);
        properties.setModel(null);

        assertThat(properties.getApiKey()).isEmpty();
        assertThat(properties.getModel()).isEmpty();
        assertThat(properties.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed off a pasted credential")
    void credentialsAreTrimmed() {
        // Keys are pasted, and a trailing newline from a shell here is an
        // authentication failure nobody can see in a config file.
        AiProperties properties = configured("  a-key\n", " a-model ");

        assertThat(properties.getApiKey()).isEqualTo("a-key");
        assertThat(properties.getModel()).isEqualTo("a-model");
    }

    @Test
    @DisplayName("the token ceiling has a working default")
    void tokenCeilingHasADefault() {
        assertThat(new AiProperties().getMaxOutputTokens()).isEqualTo(1500);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 8193, 100_000})
    @DisplayName("a token ceiling outside the provider's range is rejected")
    void tokenCeilingIsBounded(int outOfRange) {
        // What one call can cost. Enforced here because the caller does not
        // pay for it.
        AiProperties properties = configured("a-key", "a-model");
        properties.setMaxOutputTokens(outOfRange);

        assertThat(isValid(properties)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 1500, 8192})
    @DisplayName("a token ceiling inside the range is accepted")
    void validTokenCeilingsAreAccepted(int inRange) {
        AiProperties properties = configured("a-key", "a-model");
        properties.setMaxOutputTokens(inRange);

        assertThat(isValid(properties)).isTrue();
    }

    @Test
    @DisplayName("the endpoint can be pointed at a self-hosted provider")
    void endpointIsConfigurable() {
        // What makes local or in-region inference a configuration decision
        // rather than a fork (§10.1).
        AiProperties properties = new AiProperties();
        properties.setEndpoint("https://inference.internal/v1/messages");

        assertThat(properties.getEndpoint()).isEqualTo("https://inference.internal/v1/messages");
    }

    @Test
    @DisplayName("there is a timeout by default, so a call cannot hang forever")
    void timeoutHasADefault() {
        assertThat(new AiProperties().getTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("the timeout can be shortened for a deployment that wants it tighter")
    void timeoutIsConfigurable() {
        AiProperties properties = new AiProperties();
        properties.setTimeout(Duration.ofSeconds(5));

        assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("no model identifier is shipped as a default")
    void noModelIsShipped() {
        // Model identifiers carry dated versions that go out of support. One
        // written into the file would be wrong within a year and wrong
        // silently — the provider would simply start refusing calls.
        assertThat(new AiProperties().getModel()).isEmpty();
    }

    @Test
    @DisplayName("no credential is shipped as a default either")
    void noCredentialIsShipped() {
        // A key with a fallback value is a published key.
        assertThat(new AiProperties().getApiKey()).isEmpty();
    }
}
