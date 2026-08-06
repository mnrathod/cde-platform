package com.cde.platform.controller;

import com.cde.platform.repository.DocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Document processing endpoints:
 *   POST /api/viewer/{id}/flatten  — flatten annotations into PDF
 *   POST /api/documents/{id}/redact — redact regions from PDF
 *   GET  /api/documents/{id}/form-fields — inspect PDF form fields
 *   POST /api/documents/{id}/form-fill  — fill PDF form fields
 */
@RestController
public class DocumentProcessingController {

    private final DocumentRepository documentRepo;
    private final ObjectMapper        mapper = new ObjectMapper();
    private final HttpClient          http   = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${cde.converter.url:http://localhost:5001}")
    private String converterUrl;

    @Value("${cde.storage.upload-dir:./uploads}")
    private String uploadDir;

    public DocumentProcessingController(DocumentRepository documentRepo) {
        this.documentRepo = documentRepo;
    }

    // ── Flatten annotations into PDF ─────────────────────────────
    @PostMapping("/api/viewer/{documentId}/flatten")
    public ResponseEntity<?> flattenAnnotations(
        @PathVariable Long documentId,
        @RequestBody Map<String,Object> body
    ) {
        var docOpt = documentRepo.findById(documentId);
        if (docOpt.isEmpty()) return ResponseEntity.notFound().build();
        var doc = docOpt.get();

        if (doc.getFilePath() == null)
            return ResponseEntity.badRequest().body(Map.of("error","No file path"));

        try {
            // Build output path
            Path orig    = Paths.get(doc.getFilePath());
            String name  = orig.getFileName().toString().replaceAll("\\.[^.]+$","");
            Path outDir  = orig.getParent();
            String outFile = outDir + "/" + name + "_annotated.pdf";

            ObjectNode req = mapper.createObjectNode();
            req.put("path",    orig.toAbsolutePath().toString());
            req.put("output",  outFile);
            req.put("quality", body.getOrDefault("quality","screen").toString());
            req.set("shapes",  mapper.valueToTree(body.getOrDefault("shapes", List.of())));

            HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(converterUrl + "/flatten"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req)))
                .build();

            HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            var result = mapper.readTree(resp.body());

            if (result.path("success").asBoolean(false)) {
                // Return the flattened PDF as download
                byte[] bytes = Files.readAllBytes(Paths.get(result.path("outputPath").asText()));
                String fn    = name + "_annotated.pdf";
                return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fn + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(bytes);
            }
            return ResponseEntity.ok(result);

        } catch (java.net.ConnectException e) {
            return ResponseEntity.status(503).body(Map.of("error","Converter offline"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // ── Redact PDF ───────────────────────────────────────────────
    @PostMapping("/api/documents/{documentId}/redact")
    public ResponseEntity<?> redactDocument(
        @PathVariable Long documentId,
        @RequestBody Map<String,Object> body
    ) {
        var docOpt = documentRepo.findById(documentId);
        if (docOpt.isEmpty()) return ResponseEntity.notFound().build();
        var doc = docOpt.get();

        if (doc.getFilePath() == null)
            return ResponseEntity.badRequest().body(Map.of("error","No file path"));

        try {
            Path orig    = Paths.get(doc.getFilePath());
            String name  = orig.getFileName().toString().replaceAll("\\.[^.]+$","");
            String outFile = orig.getParent() + "/" + name + "_redacted.pdf";

            ObjectNode req = mapper.createObjectNode();
            req.put("path",   orig.toAbsolutePath().toString());
            req.put("output", outFile);
            req.put("burn",   Boolean.TRUE.equals(body.getOrDefault("burn", true)));
            req.set("regions", mapper.valueToTree(body.getOrDefault("regions", List.of())));

            HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(converterUrl + "/redact"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req)))
                .build();

            HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            var result = mapper.readTree(resp.body());

            if (result.path("success").asBoolean(false) &&
                Boolean.TRUE.equals(body.getOrDefault("burn", true))) {
                byte[] bytes = Files.readAllBytes(Paths.get(result.path("outputPath").asText()));
                String fn    = name + "_redacted.pdf";
                return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fn + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(bytes);
            }
            return ResponseEntity.ok(result);

        } catch (java.net.ConnectException e) {
            return ResponseEntity.status(503).body(Map.of("error","Converter offline"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // ── Inspect PDF form fields ───────────────────────────────────
    @GetMapping("/api/documents/{documentId}/form-fields")
    public ResponseEntity<?> getFormFields(@PathVariable Long documentId) {
        var docOpt = documentRepo.findById(documentId);
        if (docOpt.isEmpty()) return ResponseEntity.notFound().build();
        var doc = docOpt.get();

        if (doc.getFilePath() == null)
            return ResponseEntity.ok(Map.of("fields", List.of(), "count", 0));

        try {
            ObjectNode req = mapper.createObjectNode();
            req.put("path", Paths.get(doc.getFilePath()).toAbsolutePath().toString());

            HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(converterUrl + "/form-fields"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req)))
                .build();

            HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.ok(mapper.readTree(resp.body()));

        } catch (java.net.ConnectException e) {
            return ResponseEntity.status(503).body(Map.of("error","Converter offline"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // ── Fill PDF form ─────────────────────────────────────────────
    @PostMapping("/api/documents/{documentId}/form-fill")
    public ResponseEntity<?> fillForm(
        @PathVariable Long documentId,
        @RequestBody Map<String,Object> body
    ) {
        var docOpt = documentRepo.findById(documentId);
        if (docOpt.isEmpty()) return ResponseEntity.notFound().build();
        var doc = docOpt.get();

        if (doc.getFilePath() == null)
            return ResponseEntity.badRequest().body(Map.of("error","No file path"));

        try {
            Path orig    = Paths.get(doc.getFilePath());
            String name  = orig.getFileName().toString().replaceAll("\\.[^.]+$","");
            String outFile = orig.getParent() + "/" + name + "_filled.pdf";

            ObjectNode req = mapper.createObjectNode();
            req.put("path",   orig.toAbsolutePath().toString());
            req.put("output", outFile);
            req.set("fields", mapper.valueToTree(body.getOrDefault("fields", Map.of())));

            HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(converterUrl + "/form-fill"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req)))
                .build();

            HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            var result = mapper.readTree(resp.body());

            if (result.path("success").asBoolean(false)) {
                byte[] bytes = Files.readAllBytes(Paths.get(result.path("outputPath").asText()));
                String fn    = name + "_filled.pdf";
                return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fn + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(bytes);
            }
            return ResponseEntity.ok(result);

        } catch (java.net.ConnectException e) {
            return ResponseEntity.status(503).body(Map.of("error","Converter offline"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
