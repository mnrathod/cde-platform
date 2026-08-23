package com.cde.platform.dto;

import com.cde.platform.model.Document;
import com.cde.platform.model.DocumentVersion;
import com.cde.platform.service.FormDesignService.FormChange;
import com.cde.platform.service.PageManipulationService.ArrangementResult;
import com.cde.platform.service.PageManipulationService.ExtractionResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Replies from the operations that rewrite a document.
 *
 * <p>None of them return the rewritten file. Returning bytes made the
 * operations mutually exclusive — each built its download from the untouched
 * original, so two could never be combined. They report the version they
 * committed instead, and the client reloads.
 */
public final class ProcessingDtos {

    private ProcessingDtos() {
    }

    @Schema(name = "ProcessingResponse",
            description = "The version an operation committed. Fetch the bytes from "
                        + "`GET /api/documents/{documentId}/versions/{versionNumber}/file`.")
    public record ProcessingResponse(

        @Schema(description = "Always true. An operation that failed returns a problem document, "
                            + "not this shape.",
                example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean success,

        @Schema(description = "Document that was rewritten.", example = "1180",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long documentId,

        @Schema(description = "Version number this operation committed.", example = "4",
                minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer version,

        @Schema(description = "Which operation ran.", example = "OCR",
                requiredMode = Schema.RequiredMode.REQUIRED)
        DocumentVersion.DocumentOperation operation,

        @Schema(description = "What changed, in one line.",
                example = "Recognised text on 12 of 14 pages.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String summary,

        @Schema(description = "Size of the committed version in bytes.", example = "5242880",
                minimum = "0")
        Long fileSize,

        @Schema(description = "When the version was committed, UTC.", example = "2026-02-21T11:04:55",
                format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime createdAt,

        @Schema(description = "Counts and figures particular to the operation that ran — pages "
                            + "processed, regions redacted, fields filled. The members differ by "
                            + "operation, so they are carried here rather than spread across the "
                            + "shared shape as fields that are null most of the time.",
                example = "{\"pagesProcessed\":14,\"pagesWithText\":12}")
        Map<String, Object> details
    ) {
        public static ProcessingResponse from(Long documentId,
                                              DocumentVersion version,
                                              Map<String, Object> details) {
            return new ProcessingResponse(true, documentId, version.getVersionNumber(),
                version.getOperation(), version.getSummary(), version.getFileSize(),
                version.getCreatedAt(), details);
        }
    }

    @Schema(name = "FormChangeResponse", description = "The version a form-design change committed.")
    public record FormChangeResponse(

        @Schema(description = "Always true. A failure returns a problem document instead.",
                example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean success,

        @Schema(description = "Document whose form changed.", example = "1180",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long documentId,

        @Schema(description = "Version number this change committed.", example = "5", minimum = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Integer version,

        @Schema(description = "What changed, in one line.", example = "Added 4 fields on page 1.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String summary,

        @Schema(description = "Names of the fields added or removed, in the order they were "
                            + "processed.",
                example = "[\"contractor_name\",\"inspection_date\"]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> fields
    ) {
        public static FormChangeResponse from(Long documentId, FormChange change) {
            DocumentVersion version = change.version();
            return new FormChangeResponse(true, documentId, version.getVersionNumber(),
                version.getSummary(), change.fields());
        }
    }

    @Schema(name = "PageArrangementResponse",
            description = "The version a page change committed. Reordering, deleting, duplicating "
                        + "and rotating all report through this shape, because a batch of edits "
                        + "commits as one version described by its net effect.")
    public record PageArrangementResponse(

        @Schema(description = "Always true. A failure returns a problem document instead.",
                example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean success,

        @Schema(description = "Document whose pages changed.", example = "1180",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long documentId,

        @Schema(description = "Version number this change committed.", example = "6", minimum = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Integer version,

        @Schema(description = "What changed, in one line.",
                example = "Moved 2 pages, rotated 1, deleted 1.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String summary,

        @Schema(description = "How many pages the document now has.", example = "13", minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int pageCount,

        @Schema(description = "When the version was committed, UTC.", example = "2026-02-21T11:31:40",
                format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime createdAt
    ) {
        public static PageArrangementResponse from(Long documentId, ArrangementResult result) {
            DocumentVersion version = result.version();
            return new PageArrangementResponse(true, documentId, version.getVersionNumber(),
                version.getSummary(), result.pageCount(), version.getCreatedAt());
        }
    }

    @Schema(name = "PageExtractionResponse",
            description = "The new document that extracting pages created. The source document is "
                        + "unchanged — extraction copies, it does not move.")
    public record PageExtractionResponse(

        @Schema(description = "Always true. A failure returns a problem document instead.",
                example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean success,

        @Schema(description = "Identifier of the document that was created.", example = "1204",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long documentId,

        @Schema(description = "Name the new document was given.",
                example = "GA Plan — Level 02 (pages 3–5)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @Schema(description = "How many pages were extracted.", example = "3", minimum = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int pageCount,

        @Schema(description = "Size of the new document in bytes.", example = "918272", minimum = "0")
        Long fileSize
    ) {
        public static PageExtractionResponse from(ExtractionResult result) {
            Document created = result.document();
            return new PageExtractionResponse(true, created.getId(), created.getName(),
                result.pageCount(), created.getFileSize());
        }
    }
}
