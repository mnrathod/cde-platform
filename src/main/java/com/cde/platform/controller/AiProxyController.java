package com.cde.platform.controller;

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

    @PostMapping("/messages")
    public ResponseEntity<String> proxy(@RequestBody String body) {
        return call(body);
    }

    @PostMapping("/messages/stream")
    public ResponseEntity<String> proxyStream(@RequestBody String body) {
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
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("{\"error\":{\"message\":\"Proxy error: " + e.getMessage() + "\"}}");
        }
    }
}
