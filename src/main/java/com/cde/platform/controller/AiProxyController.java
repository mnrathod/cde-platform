package com.cde.platform.controller;

import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@RestController
@RequestMapping("/api/ai")
@Tag(name = ApiDocumentation.TAG_ASSISTANCE)
@StandardErrorResponses
public class AiProxyController {

    // Read from application.yml first, then fall back to env var directly
    @Value("${cde.anthropic.api-key:}")
    private String apiKeyFromConfig;

    private String apiKey;

    @PostConstruct
    public void init() {
        // Priority: application.yml value → ANTHROPIC_API_KEY env var
        if (apiKeyFromConfig != null && !apiKeyFromConfig.isBlank()) {
            apiKey = apiKeyFromConfig.trim();
        } else {
            String envKey = System.getenv("ANTHROPIC_API_KEY");
            if (envKey != null && !envKey.isBlank()) {
                apiKey = envKey.trim();
            }
        }
        if (apiKey != null && !apiKey.isBlank()) {
            System.out.println("[AI] Anthropic API key loaded (" + apiKey.length() + " chars)");
        } else {
            System.out.println("[AI] WARNING: No Anthropic API key found. Set ANTHROPIC_API_KEY env var or cde.anthropic.api-key in application.yml");
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    @Operation(
        operationId = "sendAssistantMessage",
        summary = "Send a message to the assistant",
        description = """
            Forwards the request to the configured model provider and returns its reply verbatim. \
            The request and response bodies are the provider's own formats, which is why they are \
            described here as opaque JSON rather than modelled: this endpoint does not interpret \
            them.

            **The payload is forwarded as supplied.** The redaction layer that the data-handling \
            rules require — allow-list field selection, personal-data pseudonymisation, and a \
            hard refusal on classified content — is not implemented here yet, so a caller is \
            responsible for what it sends. On a deployment where the provider may not be called \
            at all, the feature is switched off rather than relied upon to filter.

            Requires authentication. Calls are chargeable and are attributed to the caller.""")
    @ApiResponse(responseCode = "200",
        description = "The provider's reply, passed through unchanged.",
        content = @Content(mediaType = "application/json",
                           schema = @Schema(ref = ApiDocumentation.ASSISTANT_PAYLOAD_REF)))
    @ApiResponse(responseCode = "503",
        description = "No provider credential is configured on this deployment, or the provider "
                    + "could not be reached.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The provider rejected the payload. Its own error format is returned "
                    + "unchanged, so the members are the provider's, not this API's.",
        content = @Content(mediaType = "application/json",
                           schema = @Schema(ref = ApiDocumentation.ASSISTANT_PAYLOAD_REF)))
    @PostMapping("/messages")
    public ResponseEntity<String> proxy(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "The provider's own request format, forwarded unchanged.",
            content = @Content(mediaType = "application/json",
                               schema = @Schema(ref = ApiDocumentation.ASSISTANT_PAYLOAD_REF)))
        @RequestBody String body) {
        return call(body);
    }

    @Operation(
        operationId = "streamAssistantMessage",
        summary = "Send a message to the assistant, asking for a streamed reply",
        description = """
            Same contract as the non-streaming route and, at present, the same behaviour: the \
            reply is collected in full before it is returned, so a caller sees no incremental \
            output. The separate path exists so streaming can be added behind it without a \
            client change.

            The same caveat about unredacted payloads applies.

            Requires authentication. Calls are chargeable and are attributed to the caller.""")
    @ApiResponse(responseCode = "200",
        description = "The provider's reply, passed through unchanged.",
        content = @Content(mediaType = "application/json",
                           schema = @Schema(ref = ApiDocumentation.ASSISTANT_PAYLOAD_REF)))
    @ApiResponse(responseCode = "503",
        description = "No provider credential is configured on this deployment, or the provider "
                    + "could not be reached.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The provider rejected the payload. Its own error format is returned "
                    + "unchanged, so the members are the provider's, not this API's.",
        content = @Content(mediaType = "application/json",
                           schema = @Schema(ref = ApiDocumentation.ASSISTANT_PAYLOAD_REF)))
    @PostMapping("/messages/stream")
    public ResponseEntity<String> proxyStream(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "The provider's own request format, forwarded unchanged.",
            content = @Content(mediaType = "application/json",
                               schema = @Schema(ref = ApiDocumentation.ASSISTANT_PAYLOAD_REF)))
        @RequestBody String body) {
        return call(body);
    }

    private ResponseEntity<String> call(String body) {
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("{\"error\":{\"message\":\"Anthropic API key not configured. "
                    + "Set ANTHROPIC_API_KEY environment variable or "
                    + "cde.anthropic.api-key in application.yml.\"}}");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type",      "application/json")
                .header("anthropic-version", "2023-06-01")
                .header("x-api-key",         apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            return ResponseEntity.status(resp.statusCode())
                .header("Content-Type",
                    resp.headers().firstValue("Content-Type").orElse("application/json"))
                .body(resp.body());

        } catch (Exception e) {
            // The provider's own failure detail is logged, not returned: it can
            // name internal hosts, and concatenating it into a JSON string
            // produced malformed JSON whenever it contained a quote.
            System.err.println("[AI] Provider call failed: " + e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Content-Type", "application/json")
                .body("{\"error\":{\"message\":\"The assistant is unavailable. Try again shortly.\"}}");
        }
    }
}
