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
}
