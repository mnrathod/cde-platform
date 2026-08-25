package com.cde.platform.controller;

import com.cde.platform.dto.DocumentDtos.*;
import com.cde.platform.dto.PageResponse;
import com.cde.platform.exception.ApiProblem;
import com.cde.platform.exception.ResourceNotFoundException;
import com.cde.platform.model.*;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import com.cde.platform.service.DocumentDeletionService;
import com.cde.platform.repository.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/documents")
@Tag(name = ApiDocumentation.TAG_DOCUMENTS)
@StandardErrorResponses
public class DocumentController {

    /**
     * Columns a listing may be sorted by.
     *
     * <p>An allow-list rather than a pass-through: the value reaches a
     * {@code Sort}, which becomes an {@code ORDER BY}, and anything a caller
     * can put in a query string is something a caller chose.
     */
    private static final Set<String> SORTABLE_FIELDS =
        Set.of("createdAt", "updatedAt", "name", "fileSize", "revision", "drawingNumber");

    private static final String DEFAULT_SORT = "createdAt,desc";

    private final DocumentRepository documentRepo;
    private final ProjectRepository projectRepo;
    private final UserRepository userRepo;
    private final DocumentDeletionService deletionService;

    @Value("${cde.storage.upload-dir}")
    private String uploadDir;

    public DocumentController(DocumentRepository documentRepo, ProjectRepository projectRepo,
                              UserRepository userRepo, DocumentDeletionService deletionService) {
        this.documentRepo = documentRepo;
        this.projectRepo = projectRepo;
        this.userRepo = userRepo;
        this.deletionService = deletionService;
    }

    @Operation(
        operationId = "listDocumentsByProject",
        summary = "List the documents in a project",
        description = """
            Always returns a page envelope, including when the page holds everything. An endpoint \
            that returned a bare array in some circumstances and an envelope in others could not \
            be described by one schema, and a client had to work out which it had received by \
            inspecting what it had just parsed.

            Sortable by `createdAt`, `updatedAt`, `name`, `fileSize`, `revision` and \
            `drawingNumber`. Any other field is rejected rather than ignored, so a misspelled \
            sort does not quietly return an arbitrary order.

            Requires the `document:read` permission.""")
    @ApiResponse(responseCode = "200", description = "One page of the project's documents.")
    @ApiResponse(responseCode = "404",
        description = "No project with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping("/project/{projectId}")
    public PageResponse<DocumentResponse> listByProject(
        @Parameter(description = "Identifier of the project whose documents to list.", example = "42")
        @PathVariable Long projectId,

        @Parameter(description = "Zero-based page index.", example = "0",
                   schema = @Schema(type = "integer", format = "int32", defaultValue = "0", minimum = "0"))
        @RequestParam(defaultValue = "0") int page,

        @Parameter(description = "Maximum documents in the page.", example = "50",
                   schema = @Schema(type = "integer", format = "int32", defaultValue = "50",
                                    minimum = "1", maximum = "200"))
        @RequestParam(defaultValue = "50") int size,

        @Parameter(description = "Field and direction, as `field,asc` or `field,desc`.",
                   example = "createdAt,desc",
                   schema = @Schema(type = "string", defaultValue = DEFAULT_SORT,
                                    pattern = "^[a-zA-Z]+(,(asc|desc))?$"))
        @RequestParam(defaultValue = DEFAULT_SORT) String sort
    ) {
        if (!projectRepo.existsById(projectId)) {
            throw new ResourceNotFoundException("No such project.");
        }

        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), parseSort(sort));

        return PageResponse.from(documentRepo.findByProject_Id(projectId, pageable), this::toResponse);
    }

    /**
     * Turns {@code field,direction} into a {@code Sort}, refusing any field not
     * on the allow-list.
     *
     * @throws IllegalArgumentException if the field is not sortable
     */
    private Sort parseSort(String sort) {
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();

        if (!SORTABLE_FIELDS.contains(field)) {
            throw new IllegalArgumentException("Not a sortable field: " + field);
        }
        boolean descending = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());
        return descending ? Sort.by(field).descending() : Sort.by(field).ascending();
    }

    @Operation(
        operationId = "getDocument",
        summary = "Read one document's metadata",
        description = """
            Metadata only — the file itself comes from the viewer or version endpoints, so a \
            listing never carries content.

            Requires the `document:read` permission.""")
    @ApiResponse(responseCode = "200", description = "The document's metadata.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> get(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long id
    ) {
        return documentRepo.findById(id)
            .map(d -> ResponseEntity.ok(toResponse(d)))
            .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
        operationId = "deleteDocument",
        summary = "Delete a document and everything attached to it",
        description = """
            Cascades through the document's annotations, replies, versions and signatures, which \
            reference it with non-null foreign keys. This is not reversible, and it removes the \
            signed record along with the document.

            Requires the `document:write` permission.""")
    @ApiResponse(responseCode = "204", description = "The document and its dependents are gone.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long id
    ) {
        // Delegated: annotations, replies and signatures reference the
        // document with non-null, non-cascading foreign keys, so deleting it
        // directly fails on a referential-integrity constraint.
        return deletionService.deleteDocument(id)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }


    private DocumentResponse toResponse(Document d) {
        return new DocumentResponse(
            d.getId(), d.getName(), d.getDescription(), d.getFileName(),
            d.getFileType(), d.getFileSize(), d.getDocumentType(), d.getStatus(),
            d.getRevision(), d.getDrawingNumber(), d.getSheetNumber(),
            d.getProject() != null ? d.getProject().getId() : null,
            d.getUploadedBy() != null ? d.getUploadedBy().getUsername() : null,
            d.getCreatedAt(), d.getUpdatedAt()
        );
    }
}
