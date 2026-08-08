package com.cde.platform.dto;

import com.cde.platform.model.*;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public class Dtos {

    // ── Auth ─────────────────────────────────────────────────────────────────
    public record RegisterRequest(
        @NotBlank String username,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 6) String password,
        User.Role role
    ) {}

    public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
    ) {}

    public record AuthResponse(String token, String username, String role) {}

    // ── Project ───────────────────────────────────────────────────────────────
    public record ProjectRequest(
        @NotBlank String name,
        String description,
        String location,
        Project.ProjectPhase phase
    ) {}

    public record ProjectResponse(
        Long id, String name, String description, String location,
        Project.ProjectPhase phase, String ownerUsername,
        LocalDateTime createdAt, LocalDateTime updatedAt,
        int documentCount
    ) {}

    // ── Document ──────────────────────────────────────────────────────────────
    public record DocumentResponse(
        Long id, String name, String description, String fileName,
        String fileType, Long fileSize, Document.DocumentType documentType,
        Document.DocumentStatus status, String revision,
        String drawingNumber, String sheetNumber,
        Long projectId, String uploadedBy,
        LocalDateTime createdAt, LocalDateTime updatedAt
    ) {}

    // ── Annotation Reply ─────────────────────────────────────────────
    public record ReplyResponse(
        Long id, Long annotationId, String authorName,
        String content, java.time.LocalDateTime createdAt
    ) {}

    // ── Annotation ────────────────────────────────────────────────────────────
    public record AnnotationRequest(
        @NotNull Long documentId,
        @NotNull Annotation.AnnotationType type,
        @NotBlank String shapeData,
        String comment,
        Integer pageNumber
    ) {}

    public record AnnotationResponse(
        Long id, Long documentId, String author,
        Annotation.AnnotationType type, String shapeData,
        String comment, Annotation.AnnotationStatus status,
        Integer pageNumber, LocalDateTime createdAt
    ) {}

    // ── Document version ──────────────────────────────────────────────────────
    public record DocumentVersionResponse(
        Integer version,
        DocumentVersion.DocumentOperation operation,
        String summary,
        String fileName,
        Long fileSize,
        String contentHash,
        String createdBy,
        LocalDateTime createdAt,
        boolean current
    ) {
        public static DocumentVersionResponse from(DocumentVersion version, Integer headVersion) {
            return new DocumentVersionResponse(
                version.getVersionNumber(),
                version.getOperation(),
                version.getSummary(),
                version.getFileName(),
                version.getFileSize(),
                version.getContentHash(),
                version.getCreatedBy() != null ? version.getCreatedBy().getUsername() : null,
                version.getCreatedAt(),
                version.getVersionNumber().equals(headVersion)
            );
        }
    }

    // ── Form design ───────────────────────────────────────────────────────────
    /** Reply from placing or removing form fields. */
    public record FormChangeResponse(
        boolean success,
        Long documentId,
        Integer version,
        String summary,
        List<String> fields
    ) {
        public static FormChangeResponse from(
            Long documentId,
            com.cde.platform.service.FormDesignService.FormChange change
        ) {
            DocumentVersion version = change.version();
            return new FormChangeResponse(true, documentId, version.getVersionNumber(),
                version.getSummary(), change.fields());
        }
    }

    // ── Page manipulation ─────────────────────────────────────────────────────
    /** Reply from an in-place page change: the version it committed. */
    public record PageArrangementResponse(
        boolean success,
        Long documentId,
        Integer version,
        String summary,
        int pageCount,
        LocalDateTime createdAt
    ) {
        public static PageArrangementResponse from(
            Long documentId,
            com.cde.platform.service.PageManipulationService.ArrangementResult result
        ) {
            DocumentVersion version = result.version();
            return new PageArrangementResponse(
                true, documentId, version.getVersionNumber(),
                version.getSummary(), result.pageCount(), version.getCreatedAt());
        }
    }

    /** Reply from extracting pages: the document that was created. */
    public record PageExtractionResponse(
        boolean success,
        Long documentId,
        String name,
        int pageCount,
        Long fileSize
    ) {
        public static PageExtractionResponse from(
            com.cde.platform.service.PageManipulationService.ExtractionResult result
        ) {
            Document created = result.document();
            return new PageExtractionResponse(
                true, created.getId(), created.getName(),
                result.pageCount(), created.getFileSize());
        }
    }

    /**
     * Reply from any operation that rewrites a document. These used to stream
     * the new PDF back as a download; they now report the version they
     * committed, so the client reloads the document instead of collecting a
     * detached copy.
     */
    public record ProcessingResponse(
        boolean success,
        Long documentId,
        Integer version,
        DocumentVersion.DocumentOperation operation,
        String summary,
        Long fileSize,
        LocalDateTime createdAt,
        java.util.Map<String, Object> details
    ) {
        public static ProcessingResponse from(Long documentId,
                                              DocumentVersion version,
                                              java.util.Map<String, Object> details) {
            return new ProcessingResponse(
                true,
                documentId,
                version.getVersionNumber(),
                version.getOperation(),
                version.getSummary(),
                version.getFileSize(),
                version.getCreatedAt(),
                details
            );
        }
    }
}
