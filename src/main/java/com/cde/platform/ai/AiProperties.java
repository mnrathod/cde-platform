package com.cde.platform.ai;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Which model provider this deployment uses, and on what terms.
 *
 * <p>All of it is deployment configuration rather than something a caller
 * chooses. The endpoint this replaced took the model identifier, the token
 * ceiling and the whole prompt from the request body, which meant the client
 * decided how much this deployment spent and what it spent it on. A browser is
 * not the right place for either decision.
 */
@ConfigurationProperties(prefix = "cde.ai")
@Validated
public class AiProperties {

    /**
     * The provider credential. No default — a key with a fallback value is a
     * published key, and an absent one simply means the feature reports itself
     * unavailable rather than the application refusing to start, because AI is
     * an optional feature and the rest of the platform does not depend on it.
     */
    private String apiKey = "";

    /**
     * The model to call. No default, deliberately: model identifiers carry
     * dated versions that go out of support, and one written into this file
     * would be wrong within a year and wrong silently — the provider would
     * simply start refusing calls. A deployment names the model it has
     * contracted for.
     */
    private String model = "";

    /**
     * Where to send the request. Configurable so that a deployment can point at
     * a self-hosted or in-region endpoint (§10.1) without a code change, which
     * is what makes local inference a configuration decision rather than a
     * fork.
     */
    private String endpoint = "https://api.anthropic.com/v1/messages";

    /**
     * Caps what one call can cost. Enforced here rather than accepted from the
     * caller, because the caller does not pay for it.
     */
    @Min(1)
    @Max(8192)
    private int maxOutputTokens = 1500;

    private Duration timeout = Duration.ofSeconds(60);

    @AssertTrue(message = """
        cde.ai.model is required when cde.ai.api-key is set — a provider \
        credential with no model names nothing to call. Set the model this \
        deployment has contracted for.""")
    boolean isModelNamedWhenConfigured() {
        return apiKey == null || apiKey.isBlank() || (model != null && !model.isBlank());
    }

    /**
     * @return whether this deployment has enough configuration to call a
     *         provider at all. Separate from whether it is <em>permitted</em>
     *         to, which is the deployment tier's decision and is checked
     *         independently.
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null ? "" : model.trim();
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
