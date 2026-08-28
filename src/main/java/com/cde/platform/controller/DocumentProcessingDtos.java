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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
 * Request bodies for the document processing and form authoring endpoints, lifted out of the controller because they were most of it.
 *
 * <p>Each record keeps its Bean Validation constraints and its OpenAPI
 * schema together. CLAUDE.md 3.5 requires the two to agree, and the only
 * reliable way to keep two things in step is to keep them adjacent.
 */
public final class DocumentProcessingDtos {

    private DocumentProcessingDtos() {
    }

    /** Below this, OCR accuracy collapses. */
    private static final int MIN_OCR_DPI = 150;

    /** Above this, the memory cost is not repaid in accuracy. */
    private static final int MAX_OCR_DPI = 600;

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

    @Schema(name = "RedactRequest", description = "Regions whose content is to be destroyed.")
    public record RedactRequest(
        @Schema(description = "Rectangles to redact. Coordinates are PDF points with a "
                            + "bottom-left origin.",
                example = "[{\"page\":2,\"x\":120.5,\"y\":338.0,\"width\":210.0,"
                        + "\"height\":18.0}]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty List<Map<String, Object>> regions
    ) {}

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
}
