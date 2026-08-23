package com.cde.platform.dto;

import com.cde.platform.model.Annotation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** Markup laid over a document, and the reply threads hanging off it. */
public final class AnnotationDtos {

    private AnnotationDtos() {
    }

    @Schema(name = "AnnotationRequest", description = "Markup to place on a document.")
    public record AnnotationRequest(

        @Schema(description = "Document the markup belongs to.", example = "1180",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Long documentId,

        @Schema(description = "What kind of markup this is, which decides how it is drawn.",
                example = "HIGHLIGHT", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Annotation.AnnotationType type,

        @Schema(description = "Geometry and styling, as a JSON object encoded in a string. Its "
                            + "members depend on `type`, which is why it is carried opaquely "
                            + "rather than as a union the schema would have to enumerate and "
                            + "keep in step with the viewer.",
                example = "{\"x\":120.5,\"y\":338.0,\"width\":210.0,\"height\":18.0,\"colour\":\"#ffd400\"}",
                minLength = 1, maxLength = 65535, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 65535) String shapeData,

        @Schema(description = "Note attached to the markup.",
                example = "Confirm slab level against structural drawing.", maxLength = 4000)
        @Size(max = 4000) String comment,

        @Schema(description = "One-based page the markup sits on. Omitted for markup on a "
                            + "single-page document.",
                example = "3", minimum = "1")
        @Positive Integer pageNumber
    ) {}

    @Schema(name = "AnnotationResponse", description = "Markup as stored.")
    public record AnnotationResponse(

        @Schema(description = "Identifier of the markup.", example = "9042",
                accessMode = Schema.AccessMode.READ_ONLY, requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "Document the markup belongs to.", example = "1180",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long documentId,

        @Schema(description = "Sign-in name of the account that created it.", example = "j.okafor",
                accessMode = Schema.AccessMode.READ_ONLY)
        String author,

        @Schema(description = "What kind of markup this is.", example = "HIGHLIGHT",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Annotation.AnnotationType type,

        @Schema(description = "Geometry and styling, as a JSON object encoded in a string.",
                example = "{\"x\":120.5,\"y\":338.0,\"width\":210.0,\"height\":18.0,\"colour\":\"#ffd400\"}",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String shapeData,

        @Schema(description = "Note attached to the markup.",
                example = "Confirm slab level against structural drawing.")
        String comment,

        @Schema(description = "Whether the point raised has been dealt with.", example = "OPEN",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Annotation.AnnotationStatus status,

        @Schema(description = "One-based page the markup sits on.", example = "3", minimum = "1")
        Integer pageNumber,

        @Schema(description = "When the markup was created, UTC.", example = "2026-02-19T13:47:02",
                format = "date-time", accessMode = Schema.AccessMode.READ_ONLY,
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime createdAt
    ) {}

    @Schema(name = "ReplyRequest", description = "A reply to add to a markup thread.")
    public record ReplyRequest(

        @Schema(description = "What the reply says.",
                example = "Level confirmed at 24.150 — no change needed.",
                minLength = 1, maxLength = 4000, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 4000) String content
    ) {}

    @Schema(name = "ReplyResponse", description = "One reply in a markup thread.")
    public record ReplyResponse(

        @Schema(description = "Identifier of the reply.", example = "3311",
                accessMode = Schema.AccessMode.READ_ONLY, requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "Markup this reply belongs to.", example = "9042",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long annotationId,

        @Schema(description = "Sign-in name of the account that wrote it.", example = "a.silva",
                accessMode = Schema.AccessMode.READ_ONLY, requiredMode = Schema.RequiredMode.REQUIRED)
        String authorName,

        @Schema(description = "What the reply says.",
                example = "Level confirmed at 24.150 — no change needed.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String content,

        @Schema(description = "When the reply was written, UTC.", example = "2026-02-20T08:02:17",
                format = "date-time", accessMode = Schema.AccessMode.READ_ONLY,
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime createdAt
    ) {}

    @Schema(name = "XfdfImportResponse",
            description = "Result of importing markup from an XFDF file. A file containing no "
                        + "markup is not an error — it imports nothing and says so.")
    public record XfdfImportResponse(

        @Schema(description = "How many pieces of markup were created.", example = "14",
                minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        int imported,

        @Schema(description = "The markup that was created, in file order.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<AnnotationResponse> annotations,

        @Schema(description = "What happened, phrased for the person who chose the file.",
                example = "Imported 14 annotations.", requiredMode = Schema.RequiredMode.REQUIRED)
        String message
    ) {}
}
