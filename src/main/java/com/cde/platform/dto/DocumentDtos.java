package com.cde.platform.dto;

import com.cde.platform.model.Document;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** Document metadata and the replies from the two upload routes. */
public final class DocumentDtos {

    private DocumentDtos() {
    }

    @Schema(name = "DocumentResponse",
            description = "A document's metadata. The file itself is fetched separately, so a "
                        + "listing never carries content.")
    public record DocumentResponse(

        @Schema(description = "Identifier of the document.", example = "1180",
                accessMode = Schema.AccessMode.READ_ONLY, requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "Display name, chosen at upload and independent of the file name.",
                example = "GA Plan — Level 02", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @Schema(description = "Free text about this document.",
                example = "Issued for coordination.")
        String description,

        @Schema(description = "Name of the uploaded file, kept for display and for the download "
                            + "header. It is never used as a storage path — the stored name is "
                            + "generated server-side.",
                example = "RVD-XX-02-DR-A-1200.pdf", accessMode = Schema.AccessMode.READ_ONLY)
        String fileName,

        @Schema(description = "Media type detected from the file's own content rather than from "
                            + "its extension or the browser's claim.",
                example = "application/pdf", accessMode = Schema.AccessMode.READ_ONLY)
        String fileType,

        @Schema(description = "Size of the current version in bytes.", example = "4718592",
                minimum = "0", accessMode = Schema.AccessMode.READ_ONLY)
        Long fileSize,

        @Schema(description = "What kind of document this is, which decides how the viewer opens it.",
                example = "DRAWING", requiredMode = Schema.RequiredMode.REQUIRED)
        Document.DocumentType documentType,

        @Schema(description = "Review state of the document.", example = "DRAFT",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Document.DocumentStatus status,

        @Schema(description = "Revision identifier as the originator issued it.", example = "P02.1")
        String revision,

        @Schema(description = "Drawing number as it appears in the title block.",
                example = "RVD-XX-02-DR-A-1200")
        String drawingNumber,

        @Schema(description = "Sheet number where the drawing spans several sheets.", example = "2 of 6")
        String sheetNumber,

        @Schema(description = "Identifier of the project holding this document.", example = "42",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long projectId,

        @Schema(description = "Sign-in name of the account that uploaded it.", example = "j.okafor",
                accessMode = Schema.AccessMode.READ_ONLY)
        String uploadedBy,

        @Schema(description = "When the document was uploaded, UTC.", example = "2026-02-02T09:14:38",
                format = "date-time", accessMode = Schema.AccessMode.READ_ONLY,
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime createdAt,

        @Schema(description = "When the document or its metadata last changed, UTC.",
                example = "2026-02-19T13:47:02", format = "date-time",
                accessMode = Schema.AccessMode.READ_ONLY)
        LocalDateTime updatedAt
    ) {}

    @Schema(name = "ChunkAccepted",
            description = "Acknowledgement of one chunk of a chunked upload. Returned for every "
                        + "chunk except the last; the last returns the created document instead.")
    public record ChunkAccepted(

        @Schema(description = "Identifier the client generated for this upload, echoed back so a "
                            + "concurrent upload's acknowledgement cannot be mistaken for this one.",
                example = "0d4c1f8e-2b7a-4c31-9de6-5a0b83f27c14",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String uploadId,

        @Schema(description = "How many distinct chunks have arrived so far. Chunks may arrive out "
                            + "of order, so this is a count and not a high-water mark.",
                example = "7", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        int received,

        @Schema(description = "How many chunks the client said it would send.",
                example = "12", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        int total
    ) {}
}
