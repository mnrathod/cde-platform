package com.cde.platform.service;

import com.cde.platform.exception.ConverterOfflineException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ConverterService.class);

    private final String converterUrl;
    private final DxfToSvgService fallback;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    public record ConvertResult(boolean success, String svg, String error, JsonNode rawJson) {}

    /**
     * Asks the converter for a file rather than for something to render.
     *
     * <p>The same endpoint serves the viewer, which wants a drawing as SVG it
     * can mark up, and an export, which wants a PDF. Only the caller knows
     * which, so it says.
     */
    private static final String TARGET_PDF = "PDF";

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

            log.warn("Converter refused the drawing ({}); falling back to the Java DXF parser", error);

        } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) {
            // Expected whenever the optional converter sidecar is not deployed,
            // so this is a condition to note rather than an incident to raise.
            log.info("Converter unreachable at {}; using the Java DXF parser", converterUrl);
        } catch (Exception e) {
            log.warn("Converter call failed; using the Java DXF parser", e);
        }

        // Java fallback for DXF
        var r = fallback.convert(filePath, originalName);
        return new ConvertResult(r.success(), r.svg(), r.error(), null);
    }

    /** Convert an Office, PDF or CAD file — returns raw PDF bytes */
    public byte[] convertToPdf(Path filePath, String contentType) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("path", filePath.toAbsolutePath().toString());
        body.put("contentType", contentType != null ? contentType : "");
        body.put("targetFormat", TARGET_PDF);

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

    /**
     * Converts to PDF, streaming the result to {@code destination}.
     *
     * <p>The variant above returns the PDF as a {@code byte[]}, which is fine
     * for the interactive path where the file is small and already in memory,
     * and wrong for a job: a converted federated model would sit whole in heap
     * (§7.7). This writes it out as it arrives, so a conversion of any size
     * costs one buffer. The existing {@code byte[]} method is left in place —
     * its callers are the synchronous endpoints, and changing them is a
     * separate change with its own tests.
     *
     * <p>A partial file is deleted on failure: a truncated PDF that stayed on
     * disk is one the next step could not tell from a converted document.
     *
     * @return the number of bytes written
     * @throws ConverterOfflineException if the converter cannot be reached
     */
    public long convertToPdfFile(Path source, String contentType, Path destination,
                                 Duration timeout) {
        ObjectNode body = mapper.createObjectNode();
        body.put("path", source.toAbsolutePath().toString());
        body.put("contentType", contentType == null ? "" : contentType);
        // Says what the reply is for, which for a drawing decides the whole
        // output: without it the converter renders the SVG the viewer wants
        // and answers a job asking for a file with something to draw.
        body.put("targetFormat", TARGET_PDF);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(converterUrl + "/convert"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();

        try {
            HttpResponse<java.io.InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return writePdfOrExplain(response, destination);
        } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) {
            throw new ConverterOfflineException(converterUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The conversion was interrupted", e);
        } catch (java.io.IOException e) {
            throw new ConverterOfflineException(converterUrl);
        }
    }

    private long writePdfOrExplain(HttpResponse<java.io.InputStream> response, Path destination)
            throws java.io.IOException {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (response.statusCode() != 200 || !contentType.contains("pdf")) {
            // The converter reports refusals as JSON with an `error` field.
            // Bounded read: this is an error body, and an unbounded one would
            // be a way to make a failure cost more than a success.
            try (java.io.InputStream errorBody = response.body()) {
                JsonNode json = mapper.readTree(
                    new String(errorBody.readNBytes(8192), java.nio.charset.StandardCharsets.UTF_8));
                String reason = json.path("error").asText("");
                if (!reason.isBlank()) {
                    throw new ConversionRefusedException(reason);
                }
                // Success, but not a PDF. Saying "could not convert" here would
                // be untrue — it converted, into something the caller did not
                // ask for — and untrue is worse than unhelpful, because it
                // sends whoever reads it to look at their file.
                throw new ConversionRefusedException(
                    "The converter produced " + json.path("type").asText("something else")
                    + " rather than a PDF. Support can trace it from the job id.");
            }
        }

        java.nio.file.Files.createDirectories(destination.getParent());
        try (java.io.InputStream in = response.body();
             java.io.OutputStream out = java.nio.file.Files.newOutputStream(destination)) {
            return in.transferTo(out);
        } catch (java.io.IOException | RuntimeException e) {
            java.nio.file.Files.deleteIfExists(destination);
            throw e;
        }
    }

    /** The converter answered, and the answer was no. */
    public static class ConversionRefusedException extends RuntimeException {
        public ConversionRefusedException(String message) {
            super(message);
        }
    }

    /**
     * Posts a JSON body to a converter endpoint and returns the parsed reply.
     *
     * <p>Shared by every document-processing call so the timeout, headers and
     * offline handling are defined once rather than repeated per operation.
     *
     * @param endpoint path beginning with {@code /}, e.g. {@code /redact}
     * @param timeout  per-call ceiling; OCR needs far longer than the rest
     * @throws ConverterOfflineException if the converter cannot be reached
     */
    public JsonNode callJson(String endpoint, ObjectNode body, Duration timeout) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(converterUrl + endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

            return mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());

        } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) {
            throw new ConverterOfflineException(converterUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Converter call to " + endpoint + " was interrupted", e);
        } catch (java.io.IOException e) {
            throw new ConverterOfflineException(converterUrl);
        }
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
