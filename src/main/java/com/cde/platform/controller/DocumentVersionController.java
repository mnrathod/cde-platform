package com.cde.platform.controller;

import com.cde.platform.dto.VersionDtos.DocumentVersionResponse;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.cde.platform.exception.DocumentProcessingException;
import com.cde.platform.model.Document;
import com.cde.platform.model.DocumentVersion;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.service.DocumentVersionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * Read and navigate a document's version history.
 *
 * <pre>
 *   GET  /api/documents/{id}/versions            — full history, newest first
 *   GET  /api/documents/{id}/versions/{n}/file   — download one version
 *   POST /api/documents/{id}/versions/{n}/restore — make an earlier version current
 * </pre>
 *
 * <p>Restoring copies the chosen version forward rather than discarding the
 * ones after it, so nothing that references a later version — a signature in
 * particular — is left pointing at bytes that no longer exist.
 */
@RestController
@RequestMapping("/api/documents/{documentId}/versions")
@Tag(name = ApiDocumentation.TAG_DOCUMENT_VERSIONS)
@StandardErrorResponses
public class DocumentVersionController {

    private final DocumentVersionService versionService;
    private final DocumentRepository     documentRepo;
    private final UserRepository         userRepo;

    public DocumentVersionController(DocumentVersionService versionService,
                                     DocumentRepository documentRepo,
                                     UserRepository userRepo) {
        this.versionService = versionService;
        this.documentRepo   = documentRepo;
        this.userRepo       = userRepo;
    }

    @Operation(
        operationId = "listDocumentVersions",
        summary = "Read a document's version history",
        description = """
            Every version the document has had, newest first, each with what produced it and who \
            committed it.

            A document nothing has processed yet still has a history: its upload is recorded as \
            version 1 on first read, so the original is downloadable whether or not anyone has \
            opened the history panel.

            Requires the `document:read` permission.""")
    @ApiResponse(responseCode = "200", description = "The version history, newest first.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping
    public ResponseEntity<List<DocumentVersionResponse>> listVersions(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId
    ) {
        Optional<Document> documentOpt = ensureHistory(documentId);
        if (documentOpt.isEmpty()) return ResponseEntity.notFound().build();

        Integer head = documentOpt.get().getCurrentVersion();
        return ResponseEntity.ok(versionService.listVersions(documentId).stream()
            .map(version -> DocumentVersionResponse.from(version, head))
            .toList());
    }

    @Operation(
        operationId = "downloadDocumentVersion",
        summary = "Download one version of a document",
        description = """
            Returns the bytes as they stood at that version, not the current ones. This is how a \
            signature on an earlier version stays checkable after the document has moved on.

            The reply is a file download, not JSON.

            Requires the `document:read` permission.""")
    @ApiResponse(responseCode = "200", description = "The version's bytes.",
        content = @Content(mediaType = "application/pdf",
                           schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404",
        description = "No such document, no such version, or its file is no longer on disk.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping("/{versionNumber}/file")
    public ResponseEntity<byte[]> downloadVersion(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId,
        @Parameter(description = "Version to download, counting from 1.", example = "2")
        @PathVariable int versionNumber) throws IOException {
        // Backfill first: on a document nothing has processed yet, version 1
        // exists as a file but not yet as a row, and downloading it would 404
        // purely because nobody had opened the history panel.
        ensureHistory(documentId);

        Optional<DocumentVersion> versionOpt = versionService.findVersion(documentId, versionNumber);
        if (versionOpt.isEmpty()) return ResponseEntity.notFound().build();

        DocumentVersion version = versionOpt.get();
        Path path = Paths.get(version.getFilePath());
        if (!Files.exists(path)) return ResponseEntity.notFound().build();

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + downloadName(version) + "\"")
            .body(Files.readAllBytes(path));
    }

    @Operation(
        operationId = "restoreDocumentVersion",
        summary = "Make an earlier version current again",
        description = """
            Copies the chosen version forward as a new one rather than discarding the versions \
            after it. Nothing that references a later version — a signature above all — is left \
            pointing at bytes that no longer exist.

            The reply describes the version this created, not the one that was restored from.

            Requires the `document:write` permission.""")
    @ApiResponse(responseCode = "200", description = "The new version, holding the restored content.")
    @ApiResponse(responseCode = "404",
        description = "No such document, or no such version of it.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/{versionNumber}/restore")
    public ResponseEntity<DocumentVersionResponse> restoreVersion(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId,
        @Parameter(description = "Version to restore, counting from 1.", example = "2")
        @PathVariable int versionNumber,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) throws IOException {
        Document document = documentRepo.findById(documentId)
            .orElseThrow(() -> new DocumentProcessingException("Document not found."));

        var actor = principal == null ? null
            : userRepo.findByUsername(principal.getUsername()).orElse(null);

        return versionService.restore(document, versionNumber, actor)
            .map(restored -> ResponseEntity.ok(
                DocumentVersionResponse.from(restored, restored.getVersionNumber())))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Loads the document, recording its upload as version 1 if it has no
     * history yet.
     *
     * <p>A document that has never been processed has a file but no rows, so
     * without this its history reads as empty and its original is not
     * downloadable — both wrong, and both dependent on which endpoint the
     * client happened to call first.
     *
     * @return empty if no such document exists
     */
    private Optional<Document> ensureHistory(Long documentId) {
        Optional<Document> documentOpt = documentRepo.findById(documentId);
        documentOpt.filter(document -> document.getFilePath() != null)
                   .ifPresent(document -> versionService.currentVersion(document, null));
        return documentOpt;
    }

    /** {@code plan_v3.pdf} — the document's own name, tagged with the version. */
    private String downloadName(DocumentVersion version) {
        String name = version.getFileName() != null ? version.getFileName() : "document.pdf";
        String base = name.replaceAll("\\.[^.]+$", "");
        return base + "_v" + version.getVersionNumber() + ".pdf";
    }
}
