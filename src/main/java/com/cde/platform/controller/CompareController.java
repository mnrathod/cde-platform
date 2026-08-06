package com.cde.platform.controller;

import com.cde.platform.model.Document;
import com.cde.platform.repository.DocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/compare")
public class CompareController {

    private final DocumentRepository documentRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();
    private static final String CONVERTER_URL = "http://localhost:5001";

    public CompareController(DocumentRepository documentRepo) {
        this.documentRepo = documentRepo;
    }

    @PostMapping
    public ResponseEntity<?> compare(
        @RequestBody Map<String, Long> body
    ) {
        Long id1 = body.get("documentId1");
        Long id2 = body.get("documentId2");

        if (id1 == null || id2 == null)
            return ResponseEntity.badRequest().body(Map.of("error", "documentId1 and documentId2 required"));

        var doc1 = documentRepo.findById(id1).orElse(null);
        var doc2 = documentRepo.findById(id2).orElse(null);

        if (doc1 == null) return ResponseEntity.badRequest().body(Map.of("error", "Document 1 not found"));
        if (doc2 == null) return ResponseEntity.badRequest().body(Map.of("error", "Document 2 not found"));

        if (doc1.getFilePath() == null || doc2.getFilePath() == null)
            return ResponseEntity.ok(Map.of("success", false, "error", "One or both documents have no file on disk"));

        Path p1 = Paths.get(doc1.getFilePath());
        Path p2 = Paths.get(doc2.getFilePath());

        if (!Files.exists(p1)) return ResponseEntity.ok(Map.of("success", false, "error", "File 1 not found: " + p1));
        if (!Files.exists(p2)) return ResponseEntity.ok(Map.of("success", false, "error", "File 2 not found: " + p2));

        try {
            ObjectNode reqBody = mapper.createObjectNode();
            reqBody.put("path1", p1.toAbsolutePath().toString());
            reqBody.put("path2", p2.toAbsolutePath().toString());
            reqBody.put("contentType1", s(doc1.getFileType()));
            reqBody.put("contentType2", s(doc2.getFileType()));

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(CONVERTER_URL + "/compare"))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(reqBody)))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode result = mapper.readTree(resp.body());

            // Enrich with document metadata
            ObjectNode enriched = (ObjectNode) result;
            enriched.put("doc1Name",     s(doc1.getName()));
            enriched.put("doc2Name",     s(doc2.getName()));
            enriched.put("doc1FileName", s(doc1.getFileName()));
            enriched.put("doc2FileName", s(doc2.getFileName()));
            enriched.put("doc1Revision", s(doc1.getRevision()));
            enriched.put("doc2Revision", s(doc2.getRevision()));

            return ResponseEntity.ok(enriched);

        } catch (java.net.ConnectException e) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "error", "Converter service not running. Start converter/app.py first."));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "error", "Comparison error: " + e.getMessage()));
        }
    }

    private String s(String v) { return v != null ? v : ""; }
}
