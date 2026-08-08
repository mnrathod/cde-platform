package com.cde.platform.controller;

import com.cde.platform.dto.Dtos.DocumentVersionResponse;
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

    @GetMapping
    public ResponseEntity<List<DocumentVersionResponse>> listVersions(@PathVariable Long documentId) {
        Optional<Document> documentOpt = ensureHistory(documentId);
        if (documentOpt.isEmpty()) return ResponseEntity.notFound().build();

        Integer head = documentOpt.get().getCurrentVersion();
        return ResponseEntity.ok(versionService.listVersions(documentId).stream()
            .map(version -> DocumentVersionResponse.from(version, head))
            .toList());
    }

    @GetMapping("/{versionNumber}/file")
    public ResponseEntity<byte[]> downloadVersion(@PathVariable Long documentId,
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

    @PostMapping("/{versionNumber}/restore")
    public ResponseEntity<DocumentVersionResponse> restoreVersion(
        @PathVariable Long documentId,
        @PathVariable int versionNumber,
        @AuthenticationPrincipal UserDetails principal
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
