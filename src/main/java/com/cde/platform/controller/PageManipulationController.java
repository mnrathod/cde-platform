package com.cde.platform.controller;

import com.cde.platform.dto.ProcessingDtos.PageArrangementResponse;
import com.cde.platform.dto.ProcessingDtos.PageExtractionResponse;
import com.cde.platform.service.PageArrangement;
import com.cde.platform.service.PageArrangement.PageRef;
import com.cde.platform.service.PageManipulationService;
import com.cde.platform.service.PageManipulationService.ArrangementResult;
import com.cde.platform.service.PageManipulationService.ExtractionResult;
import com.cde.platform.dto.InspectionDtos.PageLayoutResponse;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Page manipulation:
 * <pre>
 *   GET  /api/documents/{id}/pages          — page count, sizes and rotations
 *   POST /api/documents/{id}/pages/arrange  — reorder, delete, duplicate, rotate
 *   POST /api/documents/{id}/pages/insert   — bring pages in from another document
 *   POST /api/documents/{id}/pages/extract  — copy pages into a new document
 * </pre>
 *
 * <p>Reordering, deleting, duplicating and rotating share one endpoint
 * because they are one operation: the client sends the page layout it wants
 * and the server works out what changed. A batch of edits in the page
 * organiser then commits as a single version described by its net effect,
 * rather than one version per drag.
 */
@RestController
@RequestMapping("/api/documents/{documentId}/pages")
@Tag(name = ApiDocumentation.TAG_PAGES)
@StandardErrorResponses
public class PageManipulationController {

    private final PageManipulationService pageService;
    private final ObjectMapper mapper = new ObjectMapper();

    public PageManipulationController(PageManipulationService pageService) {
        this.pageService = pageService;
    }

    @Operation(
        operationId = "describePages",
        summary = "Read how a document's pages are laid out",
        description = """
            Page count, each page's size in points, and the rotation each page declares.

            The page organiser needs this before it can offer to reorder or rotate anything, and \
            it must not infer rotation from the rendered thumbnails: a renderer may already have \
            applied a page's declared rotation, so a thumbnail that looks upright says nothing \
            about what the page holds.

            Requires the `document:read` permission.""")
    @ApiResponse(responseCode = "200", description = "The page layout.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping
    public ResponseEntity<PageLayoutResponse> describePages(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId
    ) {
        JsonNode described = pageService.describePages(documentId);
        return ResponseEntity.ok(mapper.convertValue(described, PageLayoutResponse.class));
    }

    // ── Rearrange in place ───────────────────────────────────────
    @Operation(
        operationId = "arrangePages",
        summary = "Reorder, delete, duplicate or rotate pages",
        description = """
            Send the page layout you want, not the edits that get there. The server works out what \
            changed, which is why one endpoint covers four operations: a page listed twice is \
            duplicated, one left out is deleted, and the order of the list is the order of the \
            result.

            A batch of edits in the page organiser therefore commits as one version described by \
            its net effect, rather than one version per drag.

            Requires the `document:process` permission.""")
    @ApiResponse(responseCode = "200", description = "The version the change committed.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The layout names a page the document does not have, or would leave it empty.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/arrange")
    public ResponseEntity<PageArrangementResponse> arrangePages(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId,
        @Valid @RequestBody ArrangeRequest request,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        ArrangementResult result = pageService.arrange(
            documentId, request.toArrangement(), usernameOf(principal));
        return ResponseEntity.ok(PageArrangementResponse.from(documentId, result));
    }

    /**
     * @param pages the layout wanted, in order. Each entry names a source page
     *              and an optional rotation relative to its current one.
     */
    @Schema(name = "ArrangeRequest",
            description = "The page layout wanted, in order. Every page of the result must appear; "
                        + "pages left out are deleted, and a page listed twice is duplicated.")
    public record ArrangeRequest(
        @Schema(description = "The wanted layout, in order.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty @Valid List<PageEntry> pages
    ) {
        PageArrangement toArrangement() {
            if (pages == null) return new PageArrangement(List.of());
            return new PageArrangement(pages.stream().map(PageEntry::toRef).toList());
        }
    }

    @Schema(name = "PageEntry", description = "One page of the wanted layout.")
    public record PageEntry(
        @Schema(description = "One-based page in the document as it stands now.", example = "3",
                minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Positive Integer page,

        @Schema(description = "Rotation to apply relative to the page's current one, in degrees "
                            + "clockwise. Omit or send 0 to leave it as it is.",
                example = "90", minimum = "0", maximum = "270", multipleOf = 90)
        Integer rotate
    ) {
        PageRef toRef() {
            return new PageRef(null, page != null ? page : 0, rotate != null ? rotate : 0);
        }
    }

    // ── Insert from another document ─────────────────────────────
    @Operation(
        operationId = "insertPages",
        summary = "Bring pages in from another document",
        description = """
            Copies the named pages out of the source document and inserts them at a position in \
            this one. The source is unchanged.

            Both documents must be visible to the caller and in the caller's tenant; the source \
            being in another tenant reports `404` rather than `403`.

            Requires the `document:process` permission on this document and `document:read` on \
            the source.""")
    @ApiResponse(responseCode = "200", description = "The version the insertion committed.")
    @ApiResponse(responseCode = "404",
        description = "Either document is not visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The source does not have the pages named, or is not a PDF.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/insert")
    public ResponseEntity<PageArrangementResponse> insertPages(
        @Parameter(description = "Identifier of the document to insert into.", example = "1180")
        @PathVariable Long documentId,
        @Valid @RequestBody InsertRequest request,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        ArrangementResult result = pageService.insertPages(
            documentId,
            request.sourceDocumentId(),
            request.pages(),
            request.positionOrEnd(),
            usernameOf(principal));
        return ResponseEntity.ok(PageArrangementResponse.from(documentId, result));
    }

    @Schema(name = "InsertRequest", description = "Pages to copy in from another document.")
    public record InsertRequest(
        @Schema(description = "Document to copy the pages from. It is not modified.",
                example = "1195", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Long sourceDocumentId,

        @Schema(description = "One-based pages of the source to copy, in the order they should "
                            + "appear.",
                example = "[1,2,5]", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty List<Integer> pages,

        @Schema(description = "One-based position in this document to insert before. Omit to "
                            + "append; a position past the end appends.",
                example = "4", minimum = "1")
        @Positive Integer position
    ) {
        /** Absent position means append, which the service clamps for. */
        int positionOrEnd() {
            return position != null ? position : Integer.MAX_VALUE;
        }
    }

    // ── Extract into a new document ──────────────────────────────
    @Operation(
        operationId = "extractPages",
        summary = "Copy pages into a new document",
        description = """
            Creates a new document in the same project holding copies of the named pages. The \
            source is unchanged — extraction copies, it does not move.

            Requires the `document:process` permission on the source and `document:write` on its \
            project.""")
    @ApiResponse(responseCode = "201", description = "The document that was created.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The document does not have the pages named, or is not a PDF.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/extract")
    public ResponseEntity<PageExtractionResponse> extractPages(
        @Parameter(description = "Identifier of the document to extract from.", example = "1180")
        @PathVariable Long documentId,
        @Valid @RequestBody ExtractRequest request,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        ExtractionResult result = pageService.extractPages(
            documentId, request.pages(), request.name(), usernameOf(principal));
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(PageExtractionResponse.from(result));
    }

    @Schema(name = "ExtractRequest", description = "Pages to copy into a new document.")
    public record ExtractRequest(
        @Schema(description = "One-based pages to extract, in the order they should appear.",
                example = "[3,4,5]", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty List<Integer> pages,

        @Schema(description = "Name for the new document. Derived from the source's name and the "
                            + "page range when omitted.",
                example = "GA Plan — Level 02 (pages 3–5)", maxLength = 200)
        @Size(max = 200) String name
    ) {}

    private String usernameOf(UserDetails principal) {
        return principal != null ? principal.getUsername() : null;
    }
}
