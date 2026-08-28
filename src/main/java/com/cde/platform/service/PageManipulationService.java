package com.cde.platform.service;

import com.cde.platform.exception.DocumentProcessingException;
import com.cde.platform.model.Document;
import com.cde.platform.model.DocumentVersion;
import com.cde.platform.model.DocumentVersion.DocumentOperation;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.service.PageArrangement.PageRef;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reorders, deletes, rotates, duplicates, inserts and extracts pages.
 *
 * <p>Everything that changes a document's own page tree goes through
 * {@link #arrange}, because reordering, deleting, duplicating and rotating are
 * one operation described differently — see {@link PageArrangement}. Extract
 * is the same rearrangement written to a new document instead of committed
 * back, which is why it lives here rather than in a class of its own.
 *
 * <p>Rearranging commits a version like every other processing operation, so
 * page edits chain with redaction, OCR and form-filling and can be rolled back
 * from the same history.
 */
@Service
public class PageManipulationService {

    private static final Logger log = LoggerFactory.getLogger(PageManipulationService.class);

    private static final Duration REARRANGE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration INSPECT_TIMEOUT   = Duration.ofSeconds(30);

    /** Key used for the donor document when inserting pages from elsewhere. */
    private static final String INSERT_SOURCE = "insert";

    private final DocumentRepository     documentRepo;
    private final UserRepository         userRepo;
    private final DocumentVersionService versionService;
    private final ConverterService       converter;
    private final ObjectMapper           mapper = new ObjectMapper();

    @Value("${cde.storage.upload-dir:./uploads}")
    private String uploadDir;

    public PageManipulationService(DocumentRepository documentRepo,
                                   UserRepository userRepo,
                                   DocumentVersionService versionService,
                                   ConverterService converter) {
        this.documentRepo   = documentRepo;
        this.userRepo       = userRepo;
        this.versionService = versionService;
        this.converter      = converter;
    }

    /** A committed rearrangement, or a document created by extracting pages. */
    public record ArrangementResult(DocumentVersion version, int pageCount) {}
    public record ExtractionResult(Document document, int pageCount) {}

    // ── Reading ──────────────────────────────────────────────────

    /** Page count plus each page's size and rotation, for the page organiser. */
    public JsonNode describePages(Long documentId) {
        Document document = requireDocumentWithFile(documentId);
        ObjectNode request = mapper.createObjectNode();
        request.put("path", absolutePath(document).toString());
        return converter.callJson("/page-info", request, INSPECT_TIMEOUT);
    }

    // ── Rearranging in place ─────────────────────────────────────

    /**
     * Applies a page layout to the document and commits it as a new version.
     *
     * <p>The organiser sends the whole intended layout rather than a sequence
     * of commands, so a batch of edits lands as one version described by its
     * net effect instead of one version per drag.
     */
    @Transactional
    public ArrangementResult arrange(Long documentId, PageArrangement arrangement, String username) {
        Document document = requireDocumentWithFile(documentId);
        rejectEmpty(arrangement);

        int pageCountBefore = pageCount(documentId);
        String summary = arrangement.describeChangeFrom(pageCountBefore);

        DocumentVersion version = applyToDocument(document, arrangement, Map.of(), summary, username);
        return new ArrangementResult(version, arrangement.size());
    }

    /**
     * Inserts pages from another document, commits the result.
     *
     * @param position 1-based page number the inserted block should start at;
     *                 clamped so a position past the end appends
     */
    @Transactional
    public ArrangementResult insertPages(Long documentId,
                                         Long sourceDocumentId,
                                         List<Integer> sourcePages,
                                         int position,
                                         String username) {
        Document target = requireDocumentWithFile(documentId);
        Document donor  = requireDocumentWithFile(sourceDocumentId);

        if (documentId.equals(sourceDocumentId)) {
            // Self-insert is duplication, which arrange already does without
            // needing the file opened twice.
            throw new DocumentProcessingException(
                "To repeat pages from this document, duplicate them instead.");
        }
        if (sourcePages == null || sourcePages.isEmpty()) {
            throw new DocumentProcessingException("Select at least one page to insert.");
        }

        int existing = pageCount(documentId);
        int at = Math.clamp(position, 1, existing + 1);

        List<PageRef> pages = new java.util.ArrayList<>();
        for (int page = 1; page < at; page++) pages.add(PageRef.of(page));
        for (Integer page : sourcePages) pages.add(new PageRef(INSERT_SOURCE, page, 0));
        for (int page = at; page <= existing; page++) pages.add(PageRef.of(page));

        PageArrangement arrangement = new PageArrangement(pages);
        String summary = "Inserted %d page(s) from \"%s\" at page %d"
            .formatted(sourcePages.size(), donor.getName(), at);

        DocumentVersion version = applyToDocument(
            target, arrangement,
            Map.of(INSERT_SOURCE, absolutePath(donor).toString()),
            summary, username);

        return new ArrangementResult(version, arrangement.size());
    }

    // ── Extracting to a new document ─────────────────────────────

    /**
     * Copies the given pages into a new document, leaving this one untouched.
     *
     * <p>Splitting is this operation run once per range, so it needs no
     * separate implementation.
     *
     * @param name name for the new document; falls back to the source's name
     *             with a page-range suffix
     */
    @Transactional
    public ExtractionResult extractPages(Long documentId,
                                         List<Integer> pages,
                                         String name,
                                         String username) {
        Document source = requireDocumentWithFile(documentId);
        if (pages == null || pages.isEmpty()) {
            throw new DocumentProcessingException("Select at least one page to extract.");
        }

        PageArrangement arrangement = new PageArrangement(
            pages.stream().map(PageRef::of).toList());

        Path extracted = null;
        try {
            extracted = newDocumentPath(source);
            runConverter(source, arrangement, Map.of(), extracted);

            User actor = resolveActor(username);
            Document created = documentRepo.save(Document.builder()
                .name(name != null && !name.isBlank() ? name.trim() : defaultExtractName(source, pages))
                .description("Extracted from \"%s\" (pages %s)"
                    .formatted(source.getName(), describeSelection(pages)))
                .fileName(extracted.getFileName().toString())
                .filePath(extracted.toString())
                .fileType("application/pdf")
                .fileSize(Files.size(extracted))
                .documentType(source.getDocumentType())
                .status(Document.DocumentStatus.DRAFT)
                .project(source.getProject())
                .uploadedBy(actor != null ? actor : source.getUploadedBy())
                .build());

            versionService.currentVersion(created, actor);
            extracted = null;   // ownership passed to the new document

            log.info("Extracted {} page(s) from document {} into document {}",
                     pages.size(), documentId, created.getId());
            return new ExtractionResult(created, arrangement.size());

        } catch (IOException e) {
            throw new DocumentProcessingException("The extracted pages could not be saved.", e);
        } finally {
            discard(extracted);
        }
    }

    // ── Internals ────────────────────────────────────────────────

    /** Runs the rearrangement and commits the output as a new version. */
    private DocumentVersion applyToDocument(Document document,
                                            PageArrangement arrangement,
                                            Map<String, String> sources,
                                            String summary,
                                            String username) {
        Path output = null;
        try {
            output = versionService.allocateWorkPath(document, "pages");
            runConverter(document, arrangement, sources, output);

            DocumentVersion version = versionService.commit(
                document, output, DocumentOperation.PAGES, summary, resolveActor(username));
            output = null;   // ownership passed to the version chain
            return version;

        } catch (IOException e) {
            throw new DocumentProcessingException("The rearranged document could not be saved.", e);
        } finally {
            discard(output);
        }
    }

    private void runConverter(Document document,
                              PageArrangement arrangement,
                              Map<String, String> sources,
                              Path output) {
        ObjectNode request = mapper.createObjectNode();
        request.put("path",   absolutePath(document).toString());
        request.put("output", output.toAbsolutePath().toString());
        request.set("plan",    mapper.valueToTree(arrangement.toPlan()));
        request.set("sources", mapper.valueToTree(sources));

        JsonNode result = converter.callJson("/rearrange-pages", request, REARRANGE_TIMEOUT);
        if (!result.path("success").asBoolean(false)) {
            // The converter validates the whole plan before writing, so its
            // message names the offending entry — more use than a generic one.
            throw new DocumentProcessingException(
                result.path("error").asText("The pages could not be rearranged."));
        }
    }

    private int pageCount(Long documentId) {
        JsonNode info = describePages(documentId);
        if (!info.path("success").asBoolean(false)) {
            throw new DocumentProcessingException(
                info.path("error").asText("This document's pages could not be read."));
        }
        return info.path("pageCount").asInt(0);
    }

    private void rejectEmpty(PageArrangement arrangement) {
        if (arrangement == null || arrangement.isEmpty()) {
            throw new DocumentProcessingException(
                "A document must keep at least one page.");
        }
    }

    /** Storage path for a document created by extraction. */
    private Path newDocumentPath(Document source) throws IOException {
        Path directory = source.getProject() != null
            ? Paths.get(uploadDir, source.getProject().getId().toString())
            : absolutePath(source).getParent();
        Files.createDirectories(directory);
        return directory.resolve(UUID.randomUUID() + "_extract.pdf").toAbsolutePath();
    }

    private String defaultExtractName(Document source, List<Integer> pages) {
        return "%s (pages %s)".formatted(source.getName(), describeSelection(pages));
    }

    /** "1-3" for a run, "1, 4, 7" otherwise — how people write page selections. */
    private String describeSelection(List<Integer> pages) {
        List<Integer> sorted = pages.stream().sorted().distinct().toList();
        boolean contiguous = sorted.size() > 1
            && sorted.get(sorted.size() - 1) - sorted.get(0) == sorted.size() - 1;
        return contiguous
            ? sorted.get(0) + "-" + sorted.get(sorted.size() - 1)
            : sorted.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
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

    private User resolveActor(String username) {
        return username == null ? null : userRepo.findByUsername(username).orElse(null);
    }

    private void discard(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not clean up {}: {}", path, e.getMessage());
        }
    }
}
