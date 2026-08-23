package com.cde.platform.controller;

import com.cde.platform.dto.ProcessingDtos.ProcessingResponse;
import com.cde.platform.service.DocumentProcessingService;
import com.cde.platform.service.DocumentProcessingService.ProcessingResult;
import com.cde.platform.service.DocumentProcessingService.TextSearch;
import com.cde.platform.dto.ProcessingDtos.FormChangeResponse;
import com.cde.platform.service.FormDesignService;
import com.cde.platform.service.FormDesignService.FormChange;
import com.cde.platform.service.FormFieldBuilder.FieldKind;
import com.cde.platform.service.FormFieldBuilder.FieldPlacement;
import com.cde.platform.dto.InspectionDtos.FormFieldsResponse;
import com.cde.platform.dto.RedactionPreset;
import com.cde.platform.dto.InspectionDtos.TextSearchResponse;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Document processing endpoints. Each one rewrites the document and commits
 * the result as a new version:
 * <pre>
 *   POST /api/viewer/{id}/flatten        — bake annotations into the page
 *   POST /api/documents/{id}/redact      — destroy content under regions
 *   POST /api/documents/{id}/ocr         — scanned pages -> searchable text
 *   GET  /api/documents/{id}/form-fields — describe AcroForm fields
 *   POST /api/documents/{id}/form-fill   — write form values
 * </pre>
 *
 * <p>All four rewriting operations reply with the committed version rather
 * than the file itself. Returning the bytes made them mutually exclusive —
 * each produced a download built from the untouched original, so they could
 * never be combined. The client now reloads the document and can run the next
 * operation on the previous one's result. The file remains available from
 * {@code /api/documents/{id}/versions/{n}/file}.
 */
@RestController
@Tag(name = ApiDocumentation.TAG_DOCUMENT_PROCESSING)
@StandardErrorResponses
public class DocumentProcessingController {

    private static final int MIN_OCR_DPI = 150;   // below this, accuracy collapses
    private static final int MAX_OCR_DPI = 600;   // above this, memory use is not worth it

    private final DocumentProcessingService processing;
    private final FormDesignService         formDesign;
    private final ObjectMapper              mapper = new ObjectMapper();

    public DocumentProcessingController(DocumentProcessingService processing,
                                        FormDesignService formDesign) {
        this.processing = processing;
        this.formDesign = formDesign;
    }

    // ── Flatten annotations into the page ────────────────────────
    @Operation(
        operationId = "flattenAnnotations",
        summary = "Bake markup into the page",
        description = """
            Draws the supplied shapes into the document itself, so they are part of the page \
            rather than an overlay this application knows about. Anyone opening the file \
            elsewhere then sees the markup.

            Not reversible within the document, but the version before flattening stays \
            downloadable from the version history.

            Requires the `document:process` permission.""")
    @ApiResponse(responseCode = "200", description = "The version the flattening committed.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The document is not a PDF, or the shapes could not be drawn.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "503",
        description = "The conversion service is not reachable. The request was fine and will "
                    + "succeed once it is back.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/api/viewer/{documentId}/flatten")
    public ResponseEntity<ProcessingResponse> flattenAnnotations(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId,
        @Valid @RequestBody FlattenRequest request,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        ProcessingResult result = processing.flatten(
            documentId, request.shapes(), request.qualityOrDefault(), usernameOf(principal));
        return respond(documentId, result);
    }

    @Schema(name = "FlattenRequest", description = "Shapes to draw into the document.")
    public record FlattenRequest(
        @Schema(description = "The shapes to draw, in the viewer's own geometry format. Carried "
                            + "opaquely because the members depend on each shape's type, which "
                            + "the viewer owns.",
                example = "[{\"type\":\"rect\",\"page\":1,\"x\":80,\"y\":420,"
                        + "\"width\":180,\"height\":40,\"colour\":\"#d32f2f\"}]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty List<Map<String, Object>> shapes,

        @Schema(description = "Rendering quality. `print` keeps more detail and produces a larger "
                            + "file; anything else is treated as `screen`.",
                example = "screen", allowableValues = {"screen", "print"}, defaultValue = "screen")
        String quality
    ) {
        String qualityOrDefault() {
            return "print".equalsIgnoreCase(quality) ? "print" : "screen";
        }
    }

    // ── Redact ───────────────────────────────────────────────────
    @Operation(
        operationId = "redactRegions",
        summary = "Destroy the content under named regions",
        description = """
            Removes the content beneath each region, it does not draw a black box over it. Text \
            under a redaction is gone from the file, not merely hidden.

            The version before redaction stays in the history and stays downloadable, so \
            redacting does not by itself put the document beyond recovery — deleting the earlier \
            versions is a separate act.

            Requires the `document:process` permission.""")
    @ApiResponse(responseCode = "200", description = "The version the redaction committed.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The document is not a PDF, or no regions were given.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "503",
        description = "The conversion service is not reachable.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/api/documents/{documentId}/redact")
    public ResponseEntity<ProcessingResponse> redactDocument(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId,
        @Valid @RequestBody RedactRequest request,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        ProcessingResult result = processing.redact(
            documentId, request.regions(), usernameOf(principal));
        return respond(documentId, result);
    }

    @Schema(name = "RedactRequest", description = "Regions whose content is to be destroyed.")
    public record RedactRequest(
        @Schema(description = "Rectangles to redact. Coordinates are PDF points with a "
                            + "bottom-left origin.",
                example = "[{\"page\":2,\"x\":120.5,\"y\":338.0,\"width\":210.0,"
                        + "\"height\":18.0}]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty List<Map<String, Object>> regions
    ) {}

    // ── Find text, and redact everything that matches ────────────
    @Operation(
        operationId = "findText",
        summary = "Find where text occurs in a document",
        description = """
            Reports every occurrence of the given terms, named presets or regular expressions, \
            with the coordinates of each. Read-only — nothing is changed.

            Pages carrying no extractable text are listed separately from pages that simply did \
            not match, because those call for different next steps: a page with no text usually \
            needs OCR before a search over it means anything.

            Requires the `document:read` permission.""")
    @ApiResponse(responseCode = "200", description = "Where the terms occur.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The document is not searchable, or a supplied expression is not valid.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "503",
        description = "The conversion service is not reachable.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/api/documents/{documentId}/find-text")
    public ResponseEntity<TextSearchResponse> findText(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId,
        @Valid @RequestBody TextSearchRequest request
    ) {
        JsonNode found = processing.findText(documentId, request.toSearch());
        return ResponseEntity.ok(mapper.convertValue(found, TextSearchResponse.class));
    }

    /**
     * Redacts every occurrence of a term, pattern or expression.
     *
     * <p>Separate from region redaction because it is a different act: one
     * blacks out places a person pointed at, the other blacks out everything
     * matching a rule, wherever it turns out to be.
     */
    @Operation(
        operationId = "redactMatchingText",
        summary = "Destroy every occurrence of a term or pattern",
        description = """
            Separate from region redaction because it is a different act: one blacks out places a \
            person pointed at, the other blacks out everything matching a rule, wherever it turns \
            out to be.

            The search runs server-side immediately before the redaction rather than trusting \
            rectangles from a client preview. Between previewing and applying, another user may \
            have committed a version that moves the text, and stale coordinates would black out \
            the wrong part of the page while leaving the sensitive content readable.

            Requires the `document:process` permission.""")
    @ApiResponse(responseCode = "200", description = "The version the redaction committed.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "Nothing matched, the document is not searchable, or an expression is not valid.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "503",
        description = "The conversion service is not reachable.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/api/documents/{documentId}/redact-matching")
    public ResponseEntity<ProcessingResponse> redactMatching(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId,
        @Valid @RequestBody TextSearchRequest request,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        ProcessingResult result = processing.redactMatching(
            documentId, request.toSearch(), usernameOf(principal));
        return respond(documentId, result);
    }

    /**
     * @param presets named categories — email, phone, creditCard, ssn,
     *                niNumber, postcode, iban
     */
    @Schema(name = "TextSearchRequest",
            description = "What to look for. At least one of `terms`, `presets` or `regexes` must "
                        + "be given; supplying several searches for all of them.")
    public record TextSearchRequest(
        @Schema(description = "Literal strings to look for.",
                example = "[\"Confidential\",\"Draft only\"]")
        List<String> terms,

        @Schema(description = "Named categories of sensitive value, matched by patterns "
                            + "maintained by the conversion service rather than by the caller. "
                            + "An unrecognised name is rejected rather than quietly matching "
                            + "nothing.",
                example = "[\"email\",\"niNumber\"]")
        List<RedactionPreset> presets,

        @Schema(description = "Regular expressions to match. Evaluated server-side against page "
                            + "text, so an expression that backtracks badly costs the request its "
                            + "own time budget and nothing else.",
                example = "[\"[A-Z]{3}-[0-9]{4}\"]")
        List<String> regexes,

        @Schema(description = "Whether letter case must match. Applies to `terms` only.",
                example = "false", defaultValue = "false")
        Boolean matchCase,

        @Schema(description = "Whether a match must be a whole word rather than part of a longer "
                            + "one. Applies to `terms` only.",
                example = "true", defaultValue = "false")
        Boolean wholeWord
    ) {
        TextSearch toSearch() {
            List<String> presetNames = presets == null ? List.of()
                : presets.stream().map(Enum::name).toList();
            return new TextSearch(terms, presetNames, regexes,
                Boolean.TRUE.equals(matchCase), Boolean.TRUE.equals(wholeWord));
        }
    }

    // ── OCR ──────────────────────────────────────────────────────
    @Operation(
        operationId = "runOcr",
        summary = "Recognise text on scanned pages",
        description = """
            Adds a searchable text layer under the existing image, so the pages look the same and \
            become searchable and selectable. The scan itself is not replaced.

            Pages that already carry text are skipped by default: running recognition over them \
            would replace good text with a guess at the same words.

            Requires the `document:process` permission.""")
    @ApiResponse(responseCode = "200", description = "The version recognition committed.")
    @ApiResponse(responseCode = "404",
        description = "No document with that id is visible to the caller.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "422",
        description = "The document is not a PDF, or the requested language is not installed.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @ApiResponse(responseCode = "503",
        description = "The conversion service is not reachable.",
        content = @Content(mediaType = ApiDocumentation.PROBLEM_MEDIA_TYPE,
                           schema = @Schema(ref = ApiDocumentation.PROBLEM_REF)))
    @PostMapping("/api/documents/{documentId}/ocr")
    public ResponseEntity<ProcessingResponse> ocrDocument(
        @Parameter(description = "Identifier of the document.", example = "1180")
        @PathVariable Long documentId,
        @Valid @RequestBody(required = false) OcrRequest request,
        @Parameter(hidden = true) @AuthenticationPrincipal UserDetails principal
    ) {
        OcrRequest options = request != null ? request : new OcrRequest(null, null, null);
        ProcessingResult result = processing.runOcr(
            documentId,
            options.languageOrDefault(),
            options.dpiOrDefault(),
            options.skipTextPagesOrDefault(),
            usernameOf(principal));
        return respond(documentId, result);
    }

    /**
     * DPI is clamped rather than rejected: a caller asking for 1200 wants the
     * best available quality, and failing the request would only make them
     * guess the limit.
     */
    @Schema(name = "OcrRequest",
            description = "How to run recognition. Every member has a working default, so an "
                        + "empty body is a valid request.")
    public record OcrRequest(
        @Schema(description = "Language to recognise, as a three-letter code. The language pack "
                            + "must be installed on the conversion service.",
                example = "eng", defaultValue = "eng", pattern = "^[a-z]{3}(\\+[a-z]{3})*$")
        String lang,

        @Schema(description = "Resolution to rasterise at before recognition. Below 150 accuracy "
                            + "collapses; above 600 the extra memory buys nothing. A value "
                            + "outside the range is clamped rather than refused, because a caller "
                            + "asking for 1200 wants the best available and should not have to "
                            + "guess the limit.",
                example = "300", defaultValue = "300", minimum = "150", maximum = "600")
        Integer dpi,

        @Schema(description = "Whether to leave pages that already carry text alone. Recognising "
                            + "over real text replaces it with a guess at the same words.",
                example = "true", defaultValue = "true")
        Boolean skipTextPages
    ) {
        String languageOrDefault() {
            return lang == null || lang.isBlank() ? "eng" : lang;
        }
        int dpiOrDefault() {
            if (dpi == null) return 300;
            return Math.clamp(dpi, MIN_OCR_DPI, MAX_OCR_DPI);
        }
        boolean skipTextPagesOrDefault() {
            return skipTextPages == null || skipTextPages;
        }
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

    @Schema(name = "AddFieldsRequest", description = "Fields to place on the document.")
    public record AddFieldsRequest(
        @Schema(description = "The fields to add.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty @Valid List<FieldRequest> fields
    ) {
        List<FieldPlacement> toPlacements() {
            if (fields == null) return List.of();
            return fields.stream().map(FieldRequest::toPlacement).toList();
        }
    }

    @Schema(name = "FieldRequest",
            description = "One field to place. Coordinates are PDF points with a bottom-left "
                        + "origin.")
    public record FieldRequest(
        @Schema(description = "Name to address the field by when filling it. Must be unique "
                            + "within the document.",
                example = "contractor_name", minLength = 1, maxLength = 120,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 120) String name,

        @Schema(description = "What kind of control to place.", example = "TEXT",
                allowableValues = {"TEXT", "TEXTAREA", "CHECKBOX", "DROPDOWN"},
                defaultValue = "TEXT")
        String kind,

        @Schema(description = "One-based page to place it on.", example = "1", minimum = "1",
                defaultValue = "1")
        @Positive Integer page,

        @Schema(description = "Distance from the left edge, in points.", example = "72.0")
        Float x,

        @Schema(description = "Distance from the bottom edge, in points.", example = "540.0")
        Float y,

        @Schema(description = "Width of the field, in points.", example = "220.0", minimum = "0")
        Float width,

        @Schema(description = "Height of the field, in points.", example = "18.0", minimum = "0")
        Float height,

        @Schema(description = "Whether the field must be filled before the form is complete.",
                example = "true", defaultValue = "false")
        Boolean required,

        @Schema(description = "Choices offered. Meaningful for `DROPDOWN` only.",
                example = "[\"Structural\",\"Architectural\",\"Services\"]")
        List<String> options
    ) {
        FieldPlacement toPlacement() {
            return new FieldPlacement(
                name, parseKind(kind),
                page != null ? page : 1,
                orZero(x), orZero(y), orZero(width), orZero(height),
                Boolean.TRUE.equals(required),
                options != null ? options : List.of());
        }

        private static FieldKind parseKind(String kind) {
            try {
                return FieldKind.valueOf(kind == null ? "TEXT" : kind.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "'%s' is not a field kind. Use TEXT, TEXTAREA, CHECKBOX or DROPDOWN."
                        .formatted(kind));
            }
        }

        private static float orZero(Float value) {
            return value != null ? value : 0f;
        }
    }

    @Schema(name = "RemoveFieldsRequest", description = "Fields to remove, by name.")
    public record RemoveFieldsRequest(
        @Schema(description = "Names of the fields to remove.",
                example = "[\"contractor_name\",\"inspection_date\"]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty List<String> names
    ) {}

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

    @Schema(name = "FormFillRequest", description = "Values to write into a document's form.")
    public record FormFillRequest(
        @Schema(description = "Field name to value. A checkbox takes a boolean, a dropdown one of "
                            + "its options, a text field a string. Fields not named are left "
                            + "alone.",
                example = "{\"contractor_name\":\"Riverside Civils Ltd\","
                        + "\"inspection_date\":\"2026-02-21\",\"defects_found\":false}",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty Map<String, Object> fields,

        @Schema(description = "Whether to bake the values in and drop the interactive fields, "
                            + "making the form no longer editable.",
                example = "false", defaultValue = "false")
        Boolean flatten
    ) {
        boolean flattenOrDefault() {
            return Boolean.TRUE.equals(flatten);
        }
    }

    // ── Shared ───────────────────────────────────────────────────
    private ResponseEntity<ProcessingResponse> respond(Long documentId, ProcessingResult result) {
        return ResponseEntity.ok(
            ProcessingResponse.from(documentId, result.version(), result.details()));
    }

    private String usernameOf(UserDetails principal) {
        return principal != null ? principal.getUsername() : null;
    }
}
