package com.cde.platform.service;

import com.cde.platform.exception.DocumentProcessingException;
import com.cde.platform.model.Document;
import com.cde.platform.model.DocumentVersion;
import com.cde.platform.model.DocumentVersion.DocumentOperation;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.UserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the document-processing operations that rewrite a PDF: redaction, OCR,
 * annotation flattening and form filling.
 *
 * <p>Every operation follows the same three steps — read the document's
 * <em>current</em> file, have the converter write a new one, then commit that
 * file as a new version — and it is the first and last of those that make the
 * operations composable. Because each run starts from
 * {@link Document#getFilePath()} and ends by advancing it, running OCR then
 * redaction then form-fill leaves a single document carrying all three
 * changes, where previously each step returned an unrelated download built
 * from the same untouched original.
 *
 * <p>Converter output is written to a work path first and only enters the
 * version chain once the converter reports success, so a failed run leaves the
 * document exactly as it was.
 */
@Service
public class DocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);

    /** Rasterising and recognising every page is far slower than the rest. */
    private static final Duration OCR_TIMEOUT     = Duration.ofMinutes(10);
    private static final Duration REWRITE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration INSPECT_TIMEOUT = Duration.ofSeconds(30);

    private final DocumentRepository      documentRepo;
    private final UserRepository          userRepo;
    private final DocumentVersionService  versionService;
    private final ConverterService        converter;
    private final ObjectMapper            mapper = new ObjectMapper();

    public DocumentProcessingService(DocumentRepository documentRepo,
                                     UserRepository userRepo,
                                     DocumentVersionService versionService,
                                     ConverterService converter) {
        this.documentRepo   = documentRepo;
        this.userRepo       = userRepo;
        this.versionService = versionService;
        this.converter      = converter;
    }

    /** A committed version plus whatever counts the operation reported. */
    public record ProcessingResult(DocumentVersion version, Map<String, Object> details) {}

    // ── Redaction ────────────────────────────────────────────────

    /**
     * Burns opaque boxes over the given regions, destroying the content
     * underneath, and commits the result.
     *
     * @param regions rectangles in PDF points, origin bottom-left
     */
    public ProcessingResult redact(Long documentId, List<?> regions, String username) {
        if (regions == null || regions.isEmpty()) {
            throw new DocumentProcessingException("Select at least one region to redact.");
        }
        Document document = requireDocumentWithFile(documentId);

        return run(document, username, DocumentOperation.REDACT, "redacted", output -> {
            ObjectNode request = baseRequest(document, output);
            // Always burn. A non-destructive preview is drawn client-side by
            // the redaction panel, so a server-side preview would produce a
            // version whose content is still readable — the opposite of what
            // committing a redaction should mean.
            request.put("burn", true);
            request.set("regions", mapper.valueToTree(regions));
            return converter.callJson("/redact", request, REWRITE_TIMEOUT);
        }, (result, details) -> {
            int pages = result.path("redactedPages").asInt(0);
            details.put("redactedPages", pages);
            details.put("totalRegions",  result.path("totalRegions").asInt(regions.size()));
            return "Redacted %d region(s) across %d page(s)".formatted(regions.size(), pages);
        });
    }

    /**
     * Finds text without changing anything, so the client can show what would
     * be destroyed before it is.
     *
     * <p>A preview matters more here than elsewhere: redaction is the one
     * operation whose result cannot be recovered from inside the file, and a
     * pattern that over-matches removes content nobody asked to remove.
     */
    public JsonNode findText(Long documentId, TextSearch search) {
        Document document = requireDocumentWithFile(documentId);
        return converter.callJson("/find-text", searchRequest(document, search), INSPECT_TIMEOUT);
    }

    /**
     * Redacts every occurrence of the given terms, patterns or expressions.
     *
     * <p>The search runs server-side immediately before the redaction rather
     * than trusting rectangles from a client preview: between previewing and
     * applying, another user may have committed a version that moves the
     * text, and stale coordinates would black out the wrong part of the page
     * while leaving the sensitive content readable.
     */
    public ProcessingResult redactMatching(Long documentId, TextSearch search, String username) {
        Document document = requireDocumentWithFile(documentId);

        JsonNode found = converter.callJson(
            "/find-text", searchRequest(document, search), INSPECT_TIMEOUT);
        if (!found.path("success").asBoolean(false)) {
            throw new DocumentProcessingException(
                found.path("error").asText("The document could not be searched."));
        }

        JsonNode regions = found.path("matches");
        if (regions.isEmpty()) {
            throw new DocumentProcessingException(
                found.path("pagesWithoutText").asInt(0) > 0
                    ? "No matches found. Pages without a text layer cannot be searched — run OCR first."
                    : "No matches found, so there is nothing to redact.");
        }

        return run(document, username, DocumentOperation.REDACT, "redacted", output -> {
            ObjectNode request = baseRequest(document, output);
            request.put("burn", true);
            request.set("regions", regions);
            return converter.callJson("/redact", request, REWRITE_TIMEOUT);
        }, (result, details) -> {
            int pages = result.path("redactedPages").asInt(0);
            details.put("redactedPages", pages);
            details.put("totalRegions",  regions.size());
            return "Redacted %d match(es) of %s across %d page(s)"
                .formatted(regions.size(), search.describe(), pages);
        });
    }

    /** What to look for: literal terms, named presets, or regular expressions. */
    public record TextSearch(
        List<String> terms,
        List<String> presets,
        List<String> regexes,
        boolean matchCase,
        boolean wholeWord
    ) {
        /** Short description of the request, for the version summary. */
        public String describe() {
            List<String> parts = new java.util.ArrayList<>();
            if (terms   != null && !terms.isEmpty())   parts.add(String.join(", ", terms));
            if (presets != null && !presets.isEmpty()) parts.add(String.join(", ", presets));
            if (regexes != null && !regexes.isEmpty()) {
                parts.add(regexes.size() == 1 ? "1 expression"
                                              : regexes.size() + " expressions");
            }
            return parts.isEmpty() ? "the search" : String.join(" and ", parts);
        }
    }

    private ObjectNode searchRequest(Document document, TextSearch search) {
        ObjectNode request = mapper.createObjectNode();
        request.put("path", absolutePath(document).toString());
        request.set("terms",   mapper.valueToTree(search.terms()   != null ? search.terms()   : List.of()));
        request.set("presets", mapper.valueToTree(search.presets() != null ? search.presets() : List.of()));
        request.set("regexes", mapper.valueToTree(search.regexes() != null ? search.regexes() : List.of()));
        request.put("matchCase", search.matchCase());
        request.put("wholeWord", search.wholeWord());
        return request;
    }

    // ── OCR ──────────────────────────────────────────────────────

    /**
     * Adds an invisible text layer to scanned pages, making them searchable,
     * and commits the result. Pages that already carry text are left alone
     * unless {@code skipTextPages} is false.
     */
    public ProcessingResult runOcr(Long documentId,
                                   String language,
                                   int dpi,
                                   boolean skipTextPages,
                                   String username) {
        Document document = requireDocumentWithFile(documentId);

        return run(document, username, DocumentOperation.OCR, "ocr", output -> {
            ObjectNode request = baseRequest(document, output);
            request.put("lang",          language);
            request.put("dpi",           dpi);
            request.put("skipTextPages", skipTextPages);
            return converter.callJson("/ocr", request, OCR_TIMEOUT);
        }, (result, details) -> {
            int recognised = result.path("ocrPages").asInt(0);
            int skipped    = result.path("skippedPages").asInt(0);
            details.put("ocrPages",     recognised);
            details.put("skippedPages", skipped);
            details.put("language",     result.path("language").asText(language));
            return skipped > 0
                ? "Recognised %d page(s), skipped %d already containing text"
                      .formatted(recognised, skipped)
                : "Recognised %d page(s)".formatted(recognised);
        });
    }

    // ── Flatten ──────────────────────────────────────────────────

    /**
     * Bakes markup shapes into the page content so they survive in viewers
     * that ignore annotations, and commits the result.
     */
    public ProcessingResult flatten(Long documentId,
                                    List<?> shapes,
                                    String quality,
                                    String username) {
        if (shapes == null || shapes.isEmpty()) {
            throw new DocumentProcessingException("There are no annotations to flatten.");
        }
        Document document = requireDocumentWithFile(documentId);

        return run(document, username, DocumentOperation.FLATTEN, "flatten", output -> {
            ObjectNode request = baseRequest(document, output);
            request.put("quality", quality);
            request.set("shapes",  mapper.valueToTree(shapes));
            return converter.callJson("/flatten", request, REWRITE_TIMEOUT);
        }, (result, details) -> {
            int pages = result.path("flattenedPages").asInt(0);
            details.put("flattenedPages", pages);
            details.put("shapes",         shapes.size());
            return "Flattened %d annotation(s) onto %d page(s)".formatted(shapes.size(), pages);
        });
    }

    // ── Forms ────────────────────────────────────────────────────

    /** Describes every AcroForm field in the document's current version. */
    public JsonNode inspectForm(Long documentId) {
        Document document = requireDocumentWithFile(documentId);
        ObjectNode request = mapper.createObjectNode();
        request.put("path", absolutePath(document).toString());
        return converter.callJson("/form-fields", request, INSPECT_TIMEOUT);
    }

    /**
     * Writes values into the document's form fields and commits the result.
     *
     * @param flattenFields bake the values in and drop the interactive fields,
     *                      making the form no longer editable
     */
    public ProcessingResult fillForm(Long documentId,
                                     Map<String, ?> fields,
                                     boolean flattenFields,
                                     String username) {
        if (fields == null || fields.isEmpty()) {
            throw new DocumentProcessingException("No form values were supplied.");
        }
        Document document = requireDocumentWithFile(documentId);

        return run(document, username, DocumentOperation.FORM_FILL, "form", output -> {
            ObjectNode request = baseRequest(document, output);
            request.put("flatten", flattenFields);
            request.set("fields",  mapper.valueToTree(fields));
            return converter.callJson("/form-fill", request, REWRITE_TIMEOUT);
        }, (result, details) -> {
            int filled = result.path("filledFields").size();
            // Both are field-name -> detail objects, not arrays: skippedFields
            // maps each rejected name to why it was rejected.
            details.put("filledFields",  filled);
            details.put("skippedFields",
                mapper.convertValue(result.path("skippedFields"), Map.class));
            String note = "Filled %d field(s)".formatted(filled);
            int skipped = result.path("skippedFields").size();
            if (skipped > 0) note += ", skipped %d".formatted(skipped);
            return flattenFields ? note + " and flattened the form" : note;
        });
    }

    // ── Shared pipeline ──────────────────────────────────────────

    /** Issues the converter call for one operation, writing to {@code output}. */
    @FunctionalInterface
    private interface ConverterCall {
        JsonNode invoke(Path output) throws IOException;
    }

    /** Records per-operation counts and returns the version summary line. */
    @FunctionalInterface
    private interface ResultDescriber {
        String describe(JsonNode converterResult, Map<String, Object> details);
    }

    /**
     * The read-convert-commit pipeline every operation shares.
     *
     * <p>Kept in one place so the failure behaviour cannot drift between
     * operations: the work file is removed on any failure path, and a version
     * is only committed once the converter has reported success.
     */
    private ProcessingResult run(Document document,
                                 String username,
                                 DocumentOperation operation,
                                 String workLabel,
                                 ConverterCall call,
                                 ResultDescriber describer) {
        Path output = null;
        try {
            output = versionService.allocateWorkPath(document, workLabel);
            JsonNode result = call.invoke(output);

            if (!result.path("success").asBoolean(false)) {
                String reason = result.path("error").asText("The converter could not process this file.");
                log.warn("{} failed for document {}: {}", operation, document.getId(), reason);
                throw new DocumentProcessingException(reason);
            }

            Map<String, Object> details = new LinkedHashMap<>();
            String summary = describer.describe(result, details);

            DocumentVersion version = versionService.commit(
                document, output, operation, summary, resolveActor(username));
            output = null;   // ownership passed to the version chain

            return new ProcessingResult(version, details);

        } catch (IOException e) {
            throw new DocumentProcessingException(
                "The processed file could not be saved.", e);
        } finally {
            discardWorkFile(output);
        }
    }

    private void discardWorkFile(Path output) {
        if (output == null) return;
        try {
            Files.deleteIfExists(output);
        } catch (IOException e) {
            log.warn("Could not clean up work file {}: {}", output, e.getMessage());
        }
    }

    private ObjectNode baseRequest(Document document, Path output) {
        ObjectNode request = mapper.createObjectNode();
        // Both paths must be absolute: the converter is a separate process
        // with its own working directory, so a relative path resolves against
        // the wrong place and the write silently fails.
        request.put("path",   absolutePath(document).toString());
        request.put("output", output.toAbsolutePath().toString());
        return request;
    }

    private Document requireDocumentWithFile(Long documentId) {
        Document document = documentRepo.findById(documentId)
            .orElseThrow(() -> new DocumentProcessingException("Document not found."));
        if (document.getFilePath() == null || document.getFilePath().isBlank()) {
            throw new DocumentProcessingException("This document has no stored file.");
        }
        return document;
    }

    private Path absolutePath(Document document) {
        return Paths.get(document.getFilePath()).toAbsolutePath();
    }

    /** Null for unauthenticated/system runs — versions record that honestly. */
    private User resolveActor(String username) {
        if (username == null) return null;
        return userRepo.findByUsername(username).orElse(null);
    }
}
