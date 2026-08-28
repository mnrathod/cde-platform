package com.cde.platform.ai;

import com.cde.platform.ai.AiPayloadSanitiser.ComparisonFacts;
import com.cde.platform.ai.AiPayloadSanitiser.SanitisedPayload;
import com.cde.platform.audit.AuditAction;
import com.cde.platform.audit.AuditOutcome;
import com.cde.platform.audit.AuditRequest;
import com.cde.platform.audit.AuditableChange;
import com.cde.platform.audit.RequestAuditor;
import com.cde.platform.deployment.DeploymentProperties;
import com.cde.platform.deployment.ExternalServiceMode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The single path from this platform to a model provider.
 *
 * <p>Four gates, and a request passes all four or nothing leaves:
 *
 * <ol>
 *   <li>The deployment tier permits an outbound call at all. A government or
 *       Defence deployment does not, and that is checked here as well as at
 *       startup — a startup check catches a misconfiguration, and this catches
 *       a code path that forgot.</li>
 *   <li>A provider and model are actually configured.</li>
 *   <li>{@link AiPayloadSanitiser} produced something sendable, rather than
 *       refusing on a classification marking.</li>
 *   <li>The call is audited, including when it is refused — a refusal is the
 *       event most worth having, because it means somebody tried.</li>
 * </ol>
 *
 * <p>The audit record carries who, when, the feature, the model, the provider
 * host and whether redaction fired. It never carries the prompt or the reply.
 */
@Service
public class AiAssistanceService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistanceService.class);

    /** Names the feature in the audit trail, so spend can be attributed. */
    private static final String COMPARISON_REPORT_FEATURE = "comparison-report";

    private final AiProperties properties;
    private final DeploymentProperties deployment;
    private final AiPayloadSanitiser sanitiser;
    private final RequestAuditor auditor;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public AiAssistanceService(AiProperties properties,
                               DeploymentProperties deployment,
                               AiPayloadSanitiser sanitiser,
                               RequestAuditor auditor,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.deployment = deployment;
        this.sanitiser = sanitiser;
        this.auditor = auditor;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // Never follow a redirect to somewhere else. The endpoint is
            // configured, and a provider that redirects is a provider whose
            // destination this deployment did not approve (§5.12 A10).
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /**
     * @return whether this deployment can produce an assisted summary at all,
     *         for the UI to hide the feature rather than offer one that fails
     */
    public boolean isAvailable() {
        return deployment.getAiFeatures() == ExternalServiceMode.ONLINE_API
            && deployment.getTier().permitsOutboundCalls()
            && properties.isConfigured();
    }

    public AssistanceOutcome summariseComparison(ComparisonFacts facts,
                                                 Long actorUserId,
                                                 String actorLabel,
                                                 HttpServletRequest httpRequest) {
        if (!isAvailable()) {
            // Not an error and not silent: the caller is told the feature is
            // off on this deployment, and the attempt is recorded, because on a
            // sovereign deployment "somebody tried to use AI" is itself the
            // interesting event.
            audit(actorUserId, actorLabel, httpRequest, AuditOutcome.FAILURE,
                  AuditableChange.of("feature", COMPARISON_REPORT_FEATURE)
                      .and("reason", "unavailable-on-this-deployment"));
            return AssistanceOutcome.unavailable();
        }

        SanitisedPayload payload = sanitiser.sanitise(facts);
        if (payload.isRefused()) {
            audit(actorUserId, actorLabel, httpRequest, AuditOutcome.DENIED,
                  AuditableChange.of("feature", COMPARISON_REPORT_FEATURE)
                      .and("reason", "classified-content-refused")
                      // The refusals name which field, not what was in it.
                      .and("refusedFields", String.join(", ", payload.refusals())));
            return AssistanceOutcome.refused(payload.refusalDetail());
        }

        try {
            String reply = call(payload.prompt());
            audit(actorUserId, actorLabel, httpRequest, AuditOutcome.SUCCESS,
                  AuditableChange.of("feature", COMPARISON_REPORT_FEATURE)
                      .and("model", properties.getModel())
                      .and("provider", URI.create(properties.getEndpoint()).getHost())
                      .and("redactionFired", payload.redacted())
                      .and("placeholdersApplied", payload.placeholders().count()));

            // Re-hydrated locally, so the real values existed only on this side
            // of the boundary throughout.
            return AssistanceOutcome.produced(payload.placeholders().rehydrate(reply));

        } catch (Exception e) {
            // The provider's own failure detail is logged, not returned: it can
            // name internal hosts, and it is not something the caller can act
            // on beyond trying again.
            log.warn("The model provider call failed for feature {}",
                     COMPARISON_REPORT_FEATURE, e);
            audit(actorUserId, actorLabel, httpRequest, AuditOutcome.FAILURE,
                  AuditableChange.of("feature", COMPARISON_REPORT_FEATURE)
                      .and("reason", "provider-unreachable"));
            return AssistanceOutcome.unavailable();
        }
    }

    private String call(String prompt) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.getModel());
        body.put("max_tokens", properties.getMaxOutputTokens());
        ArrayNode messages = body.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(properties.getEndpoint()))
            .timeout(properties.getTimeout())
            .header("Content-Type", "application/json")
            .header("anthropic-version", "2023-06-01")
            .header("x-api-key", properties.getApiKey())
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException(
                "The model provider answered " + response.statusCode());
        }
        return extractText(objectMapper.readTree(response.body()));
    }

    /**
     * Pulls the text out of the provider's reply.
     *
     * <p>Treated as untrusted input (§10.1): it is read as JSON, the text is
     * taken as text, and it is never executed, interpolated into a query, or
     * allowed to decide anything. The caller renders it as content.
     */
    private String extractText(JsonNode reply) {
        JsonNode content = reply.path("content");
        if (!content.isArray()) {
            return "";
        }
        var text = new StringBuilder();
        content.forEach(block -> {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        });
        return text.toString();
    }

    private void audit(Long actorUserId, String actorLabel, HttpServletRequest httpRequest,
                       AuditOutcome outcome, AuditableChange change) {
        auditor.recordIfTenantBound(
            AuditRequest.by(actorUserId, actorLabel)
                .did(AuditAction.AI_REQUEST)
                .outcome(outcome)
                .changing(change),
            httpRequest);
    }

    /**
     * @param report        the assisted summary, or null when none was produced
     * @param refusalDetail why not, when the content was refused
     * @param available     false when this deployment does not offer the feature
     */
    public record AssistanceOutcome(String report, String refusalDetail, boolean available) {

        static AssistanceOutcome produced(String report) {
            return new AssistanceOutcome(report, null, true);
        }

        static AssistanceOutcome refused(String refusalDetail) {
            return new AssistanceOutcome(null, refusalDetail, true);
        }

        static AssistanceOutcome unavailable() {
            return new AssistanceOutcome(null, null, false);
        }

        public boolean wasRefused() {
            return refusalDetail != null;
        }
    }
}
