package com.cde.platform.controller;

import com.cde.platform.controller.DocumentProcessingDtos.AddFieldsRequest;
import com.cde.platform.controller.DocumentProcessingDtos.FormFillRequest;
import com.cde.platform.controller.DocumentProcessingDtos.RemoveFieldsRequest;
import com.cde.platform.dto.InspectionDtos.FormFieldsResponse;
import com.cde.platform.dto.ProcessingDtos.FormChangeResponse;
import com.cde.platform.dto.ProcessingDtos.ProcessingResponse;
import com.cde.platform.openapi.ApiDocumentation;
import com.cde.platform.openapi.StandardErrorResponses;
import com.cde.platform.service.DocumentProcessingService;
import com.cde.platform.service.DocumentProcessingService.ProcessingResult;
import com.cde.platform.service.FormDesignService;
import com.cde.platform.service.FormDesignService.FormChange;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A document's interactive form: describing it, changing it, filling it.
 *
 * <p>Split from {@link DocumentProcessingController}, which had grown past
 * the §3.3 limit. The operations there change a document's *content* —
 * flattening markup into it, covering text, adding an OCR layer. These change
 * or read its *form structure*, which is a different thing a document has,
 * with its own service behind it.
 *
 * <p>No path moves: each mapping carries its full path as it did before, so
 * which class serves it is not something a caller can observe (§3.4).
 */
@RestController
@Tag(name = ApiDocumentation.TAG_DOCUMENT_PROCESSING)
@StandardErrorResponses
public class DocumentFormFieldsController {

    private final DocumentProcessingService processing;
    private final FormDesignService         formDesign;
    private final ObjectMapper              mapper = new ObjectMapper();

    public DocumentFormFieldsController(DocumentProcessingService processing,
                                        FormDesignService formDesign) {
        this.processing = processing;
        this.formDesign = formDesign;
    }

    // ── Inspect form fields ──────────────────────────────────────
    @Operation(
        operationId = "describeFormFields",
        summary = "Describe a document's interactive form fields",
        description = """
            Read-only. Returns each field's name, kind, page and current value, plus the members \
            particular to its kind — a checkbox's on-state, a dropdown's options, a text field's \
            character limit.

            Ordered by page and then by name so a form UI does not reshuffle between requests: \
            the order fields appear in a PDF's own structures is arbitrary.

            A flat document is not an error — it simply has no fields.

            Requires the `document:read` permission.""")
    @ApiResponse(responseCode = "200", description = "The document's form fields, possibly none.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "503",
        description = "The conversion service is not reachable.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @GetMapping("/api/documents/{documentId}/form-fields")
    public ResponseEntity<FormFieldsResponse> getFormFields(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId
    ) {
        JsonNode described = processing.inspectForm(documentId);
        return ResponseEntity.ok(mapper.convertValue(described, FormFieldsResponse.class));
    }

    // ── Design the form ──────────────────────────────────────────
    /**
     * Places new fields on the document, making a flat PDF fillable.
     *
     * <p>Coordinates are PDF points with a bottom-left origin, the same space
     * every other geometry endpoint uses.
     */
    @Operation(
        operationId = "addFormFields",
        summary = "Place interactive fields on a document",
        description = """
            Makes a flat PDF fillable. Coordinates are PDF points with a bottom-left origin, the \
            same space every other geometry endpoint here uses.

            Requires the `document:process` permission.""")
    @ApiResponse(responseCode = "200", description = "The version the change committed.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "A field names an unknown kind, sits on a page the document does not have, "
                    + "or reuses a name already in the form.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/api/documents/{documentId}/form-fields")
    public ResponseEntity<FormChangeResponse> addFormFields(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId,
        @Valid @RequestBody AddFieldsRequest request,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        FormChange change = formDesign.addFields(
            documentId, request.toPlacements(), usernameOf(principal));
        return ResponseEntity.ok(FormChangeResponse.from(documentId, change));
    }

    @Operation(
        operationId = "removeFormFields",
        summary = "Remove interactive fields from a document",
        description = """
            Removes the named fields and any values in them. The page content underneath is left \
            alone, so the printed boxes and labels remain — this removes the interactivity, not \
            the form's appearance.

            Requires the `document:process` permission.""")
    @ApiResponse(responseCode = "200", description = "The version the change committed.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The document has no such field.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @DeleteMapping("/api/documents/{documentId}/form-fields")
    public ResponseEntity<FormChangeResponse> removeFormFields(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId,
        @Valid @RequestBody RemoveFieldsRequest request,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        FormChange change = formDesign.removeFields(
            documentId, request.names(), usernameOf(principal));
        return ResponseEntity.ok(FormChangeResponse.from(documentId, change));
    }

    // ── Fill form ────────────────────────────────────────────────
    @Operation(
        operationId = "fillForm",
        summary = "Write values into a document's form fields",
        description = """
            Fills the named fields and commits the result. Fields not named are left as they are.

            Flattening bakes the values into the page and drops the interactive fields, which \
            makes the form no longer editable — appropriate for issuing a completed form, wrong \
            for one still being worked on. The version before flattening stays in the history.

            Requires the `document:process` permission.""")
    @ApiResponse(responseCode = "200", description = "The version the fill committed.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "No values were supplied, the document has no such field, or a value does "
                    + "not suit the field's kind.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/api/documents/{documentId}/form-fill")
    public ResponseEntity<ProcessingResponse> fillForm(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId,
        @Valid @RequestBody FormFillRequest request,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        ProcessingResult result = processing.fillForm(
            documentId, request.fields(), request.flattenOrDefault(), usernameOf(principal));
        return respond(documentId, result);
    }


    private ResponseEntity<ProcessingResponse> respond(Long documentId, ProcessingResult result) {
        return ResponseEntity.ok(
            ProcessingResponse.from(documentId, result.version(), result.details()));
    }

    private String usernameOf(UserDetails principal) {
        return principal != null ? principal.getUsername() : null;
    }
}
