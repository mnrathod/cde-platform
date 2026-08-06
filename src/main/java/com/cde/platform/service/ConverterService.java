package com.cde.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

@Service
public class ConverterService {

    private final String converterUrl;
    private final DxfToSvgService fallback;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    public record ConvertResult(boolean success, String svg, String error, JsonNode rawJson) {}

    public ConverterService(
        @Value("${cde.converter.url:http://localhost:5001}") String converterUrl,
        DxfToSvgService fallback) {
        this.converterUrl = converterUrl;
        this.fallback = fallback;
    }

    /** Convert a CAD file (DXF/DWG) to SVG */
    public ConvertResult convert(Path filePath, String originalName) {
        try {
            // POST with JSON body — avoids all path encoding issues on Windows
            ObjectNode body = mapper.createObjectNode();
            body.put("path", filePath.toAbsolutePath().toString());
            body.put("contentType", "application/dxf");

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(converterUrl + "/convert"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(resp.body());
            boolean success = json.path("success").asBoolean(false);

            if (success) return new ConvertResult(true, json.path("svg").asText(), null, json);

            String error = json.path("error").asText("");
            if (error.startsWith("DWG_BINARY:") || error.equals("DWG_NEED_CONVERTER"))
                return new ConvertResult(false, null, error, json);

            System.err.println("[Converter] Error: " + error + " — trying Java fallback");

        } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) {
            System.err.println("[Converter] Not running on " + converterUrl + " — using Java DXF parser");
        } catch (Exception e) {
            System.err.println("[Converter] Error: " + e.getMessage() + " — using Java DXF parser");
        }

        // Java fallback for DXF
        var r = fallback.convert(filePath, originalName);
        return new ConvertResult(r.success(), r.svg(), r.error(), null);
    }

    /** Convert an Office/PDF file — returns raw PDF bytes */
    public byte[] convertToPdf(Path filePath, String contentType) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("path", filePath.toAbsolutePath().toString());
        body.put("contentType", contentType != null ? contentType : "");

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(converterUrl + "/convert"))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();

        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        String respCt = resp.headers().firstValue("Content-Type").orElse("");

        if (respCt.contains("pdf") && resp.statusCode() == 200) {
            return resp.body();
        }

        // Converter returned a JSON error
        JsonNode json = mapper.readTree(resp.body());
        throw new RuntimeException(json.path("error").asText("Conversion failed"));
    }

    public boolean isConverterRunning() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(converterUrl + "/health"))
                .timeout(Duration.ofSeconds(3)).GET().build();
            return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (Exception e) { return false; }
    }
}
