package com.cde.platform.controller;

import com.cde.platform.dto.Dtos.ProcessingResponse;
import com.cde.platform.service.DocumentProcessingService;
import com.cde.platform.service.DocumentProcessingService.ProcessingResult;
import com.cde.platform.service.DocumentProcessingService.TextSearch;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Document processing endpoints. Each one rewrites the document and commits
 * the result as a new version:
 * <pre>
 *   POST /api/viewer/{id}/flatten        — bake annotations into the page
 *   POST /api/documents/{id}/redact      — destroy content under regions
 *   POST /api/documents/{id}/ocr         — scanned pages -> searchable text
 *   GET  /api/documents/{id}/form-fields — describe AcroForm fields
 *   POST /api/documents/{id}/form-fill   — write form values
 * </pre>
 *
 * <p>All four rewriting operations reply with the committed version rather
 * than the file itself. Returning the bytes made them mutually exclusive —
 * each produced a download built from the untouched original, so they could
 * never be combined. The client now reloads the document and can run the next
 * operation on the previous one's result. The file remains available from
 * {@code /api/documents/{id}/versions/{n}/file}.
 */
@RestController
public class DocumentProcessingController {

    private static final int MIN_OCR_DPI = 150;   // below this, accuracy collapses
    private static final int MAX_OCR_DPI = 600;   // above this, memory use is not worth it

    private final DocumentProcessingService processing;

    public DocumentProcessingController(DocumentProcessingService processing) {
        this.processing = processing;
    }

    // ── Flatten annotations into the page ────────────────────────
    @PostMapping("/api/viewer/{documentId}/flatten")
    public ResponseEntity<ProcessingResponse> flattenAnnotations(
        @PathVariable Long documentId,
        @RequestBody FlattenRequest request,
        @AuthenticationPrincipal UserDetails principal
    ) {
        ProcessingResult result = processing.flatten(
            documentId, request.shapes(), request.qualityOrDefault(), usernameOf(principal));
        return respond(documentId, result);
    }

    public record FlattenRequest(List<Map<String, Object>> shapes, String quality) {
        String qualityOrDefault() {
            return "print".equalsIgnoreCase(quality) ? "print" : "screen";
        }
    }

    // ── Redact ───────────────────────────────────────────────────
    @PostMapping("/api/documents/{documentId}/redact")
    public ResponseEntity<ProcessingResponse> redactDocument(
        @PathVariable Long documentId,
        @RequestBody RedactRequest request,
        @AuthenticationPrincipal UserDetails principal
    ) {
        ProcessingResult result = processing.redact(
            documentId, request.regions(), usernameOf(principal));
        return respond(documentId, result);
    }

    public record RedactRequest(List<Map<String, Object>> regions) {}

    // ── Find text, and redact everything that matches ────────────
    @PostMapping("/api/documents/{documentId}/find-text")
    public ResponseEntity<JsonNode> findText(
        @PathVariable Long documentId,
        @RequestBody TextSearchRequest request
    ) {
        return ResponseEntity.ok(processing.findText(documentId, request.toSearch()));
    }

    /**
     * Redacts every occurrence of a term, pattern or expression.
     *
     * <p>Separate from region redaction because it is a different act: one
     * blacks out places a person pointed at, the other blacks out everything
     * matching a rule, wherever it turns out to be.
     */
    @PostMapping("/api/documents/{documentId}/redact-matching")
    public ResponseEntity<ProcessingResponse> redactMatching(
        @PathVariable Long documentId,
        @RequestBody TextSearchRequest request,
        @AuthenticationPrincipal UserDetails principal
    ) {
        ProcessingResult result = processing.redactMatching(
            documentId, request.toSearch(), usernameOf(principal));
        return respond(documentId, result);
    }

    /**
     * @param presets named categories — email, phone, creditCard, ssn,
     *                niNumber, postcode, iban
     */
    public record TextSearchRequest(
        List<String> terms,
        List<String> presets,
        List<String> regexes,
        Boolean matchCase,
        Boolean wholeWord
    ) {
        TextSearch toSearch() {
            return new TextSearch(terms, presets, regexes,
                Boolean.TRUE.equals(matchCase), Boolean.TRUE.equals(wholeWord));
        }
    }

    // ── OCR ──────────────────────────────────────────────────────
    @PostMapping("/api/documents/{documentId}/ocr")
    public ResponseEntity<ProcessingResponse> ocrDocument(
        @PathVariable Long documentId,
        @RequestBody(required = false) OcrRequest request,
        @AuthenticationPrincipal UserDetails principal
    ) {
        OcrRequest options = request != null ? request : new OcrRequest(null, null, null);
        ProcessingResult result = processing.runOcr(
            documentId,
            options.languageOrDefault(),
            options.dpiOrDefault(),
            options.skipTextPagesOrDefault(),
            usernameOf(principal));
        return respond(documentId, result);
    }

    /**
     * DPI is clamped rather than rejected: a caller asking for 1200 wants the
     * best available quality, and failing the request would only make them
     * guess the limit.
     */
    public record OcrRequest(String lang, Integer dpi, Boolean skipTextPages) {
        String languageOrDefault() {
            return lang == null || lang.isBlank() ? "eng" : lang;
        }
        int dpiOrDefault() {
            if (dpi == null) return 300;
            return Math.clamp(dpi, MIN_OCR_DPI, MAX_OCR_DPI);
        }
        boolean skipTextPagesOrDefault() {
            return skipTextPages == null || skipTextPages;
        }
    }

    // ── Inspect form fields ──────────────────────────────────────
    @GetMapping("/api/documents/{documentId}/form-fields")
    public ResponseEntity<JsonNode> getFormFields(@PathVariable Long documentId) {
        return ResponseEntity.ok(processing.inspectForm(documentId));
    }

    // ── Fill form ────────────────────────────────────────────────
    @PostMapping("/api/documents/{documentId}/form-fill")
    public ResponseEntity<ProcessingResponse> fillForm(
        @PathVariable Long documentId,
        @RequestBody FormFillRequest request,
        @AuthenticationPrincipal UserDetails principal
    ) {
        ProcessingResult result = processing.fillForm(
            documentId, request.fields(), request.flattenOrDefault(), usernameOf(principal));
        return respond(documentId, result);
    }

    public record FormFillRequest(Map<String, Object> fields, Boolean flatten) {
        boolean flattenOrDefault() {
            return Boolean.TRUE.equals(flatten);
        }
    }

    // ── Shared ───────────────────────────────────────────────────
    private ResponseEntity<ProcessingResponse> respond(Long documentId, ProcessingResult result) {
        return ResponseEntity.ok(
            ProcessingResponse.from(documentId, result.version(), result.details()));
    }

    private String usernameOf(UserDetails principal) {
        return principal != null ? principal.getUsername() : null;
    }
}
