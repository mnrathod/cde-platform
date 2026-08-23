package com.cde.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Read-only descriptions of a document's contents: where text sits, what form
 * fields it has, and how its pages are laid out.
 *
 * <p>These shapes mirror what the converter service actually returns. They are
 * modelled rather than passed through as free JSON so that a client generated
 * from the specification gets types, and so that a change in the converter's
 * output shows up as a compilation failure here rather than as a client that
 * silently reads a member that is no longer sent.
 */
public final class InspectionDtos {

    private InspectionDtos() {
    }

    @Schema(name = "TextMatch",
            description = "One occurrence of a search term. Coordinates are PDF points with a "
                        + "bottom-left origin, padded slightly so a redaction covering the box "
                        + "does not leave the glyph edges showing.")
    public record TextMatch(

        @Schema(description = "One-based page the match sits on.", example = "4", minimum = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int page,

        @Schema(description = "The matched text exactly as it appears in the document.",
                example = "j.okafor@example.test", requiredMode = Schema.RequiredMode.REQUIRED)
        String text,

        @Schema(description = "Which term, preset or expression matched.", example = "email",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String pattern,

        @Schema(description = "Distance from the left edge, in points.", example = "120.5",
                requiredMode = Schema.RequiredMode.REQUIRED)
        double x,

        @Schema(description = "Distance from the bottom edge, in points.", example = "338.0",
                requiredMode = Schema.RequiredMode.REQUIRED)
        double y,

        @Schema(description = "Width of the match box, in points.", example = "146.2",
                minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        double width,

        @Schema(description = "Height of the match box, in points.", example = "11.8",
                minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        double height
    ) {}

    @Schema(name = "TextSearchResponse",
            description = "Where a set of search terms occurs in a document. Pages carrying no "
                        + "extractable text at all are listed separately, because a page that "
                        + "matched nothing and a page that could not be read are different "
                        + "answers — the second usually means the document needs OCR first.")
    public record TextSearchResponse(

        @Schema(description = "Whether the search ran. False means the document could not be read.",
                example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean success,

        @Schema(description = "How many occurrences were found.", example = "7", minimum = "0")
        Integer matchCount,

        @Schema(description = "Every occurrence, in page order.")
        List<TextMatch> matches,

        @Schema(description = "One-based pages that carry no extractable text. A scanned page "
                            + "appears here until OCR has run over it.",
                example = "[13,14]")
        List<Integer> pagesWithoutText,

        @Schema(description = "Why the search could not run. Present only when `success` is false.",
                example = "File not found")
        String error
    ) {}

    @Schema(name = "FormFieldDescription",
            description = "One interactive field on a form. Members beyond the common ones depend "
                        + "on the field's kind: a checkbox carries `onState` and `checked`, a "
                        + "dropdown carries `options` and `multiSelect`, a text field carries "
                        + "`multiline` and possibly `maxLength`.")
    public record FormFieldDescription(

        @Schema(description = "Field name as held in the document, used to address it when filling.",
                example = "contractor_name", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @Schema(description = "Kind of control, normalised across the several ways a PDF can "
                            + "express the same thing.",
                example = "text",
                allowableValues = {"text", "textarea", "password", "checkbox", "radio",
                                   "dropdown", "listbox", "signature", "button"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String kind,

        @Schema(description = "Raw PDF field type.", example = "/Tx")
        String type,

        @Schema(description = "PDF field flags, as a bit set. Present for diagnosis; the derived "
                            + "members above are what a client should read.",
                example = "0")
        Integer flags,

        @Schema(description = "Whether the field refuses input.", example = "false")
        Boolean readOnly,

        @Schema(description = "Whether the field must be filled before the form is complete.",
                example = "true")
        Boolean required,

        @Schema(description = "One-based page the field sits on.", example = "1", minimum = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int page,

        @Schema(description = "Current value, as text.", example = "(none)")
        String value,

        @Schema(description = "Value a checkbox takes when ticked. Checkboxes only.", example = "/Yes")
        String onState,

        @Schema(description = "Whether a checkbox is currently ticked. Checkboxes only.",
                example = "false")
        Boolean checked,

        @Schema(description = "Choices a dropdown or list offers. Choice fields only.",
                example = "[\"Structural\",\"Architectural\",\"Services\"]")
        List<String> options,

        @Schema(description = "Whether a list accepts more than one choice. Choice fields only.",
                example = "false")
        Boolean multiSelect,

        @Schema(description = "Whether a text field accepts line breaks. Text fields only.",
                example = "false")
        Boolean multiline,

        @Schema(description = "Character limit the document imposes. Text fields only, and only "
                            + "when the document sets one.",
                example = "120", minimum = "1")
        Integer maxLength
    ) {}

    @Schema(name = "FormFieldsResponse",
            description = "The interactive fields a document carries, ordered by page and then by "
                        + "name so a form UI does not reshuffle between requests.")
    public record FormFieldsResponse(

        @Schema(description = "Whether the document could be inspected.", example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean success,

        @Schema(description = "The fields, in page-then-name order. Empty for a flat document.")
        List<FormFieldDescription> fields,

        @Schema(description = "How many pages the document has.", example = "14", minimum = "0")
        Integer pageCount,

        @Schema(description = "Why the document could not be inspected. Present only when "
                            + "`success` is false.",
                example = "File not found")
        String error
    ) {}

    @Schema(name = "PageDescription", description = "One page's size and orientation.")
    public record PageDescription(

        @Schema(description = "One-based page number.", example = "1", minimum = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int page,

        @Schema(description = "Media box width in points, before rotation is applied.",
                example = "841.9", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        double width,

        @Schema(description = "Media box height in points, before rotation is applied.",
                example = "595.3", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        double height,

        @Schema(description = "Rotation the page declares, in degrees clockwise. A renderer may "
                            + "already have applied this, which is why it is reported rather than "
                            + "inferred from a thumbnail.",
                example = "0", minimum = "0", maximum = "270", multipleOf = 90,
                requiredMode = Schema.RequiredMode.REQUIRED)
        int rotation
    ) {}

    @Schema(name = "PageLayoutResponse",
            description = "How a document's pages are laid out. The page organiser needs this "
                        + "before it can offer to reorder or rotate anything.")
    public record PageLayoutResponse(

        @Schema(description = "Whether the document could be read.", example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean success,

        @Schema(description = "How many pages the document has.", example = "14", minimum = "0")
        Integer pageCount,

        @Schema(description = "Each page, in document order.")
        List<PageDescription> pages,

        @Schema(description = "Why the document could not be read. Present only when `success` is "
                            + "false.",
                example = "File not found")
        String error
    ) {}

    @Schema(name = "ComparisonResponse",
            description = "The difference between two documents. The comparison itself is produced "
                        + "by the conversion service and its members vary with the document kinds "
                        + "being compared, so the shape carries the metadata this API adds plus "
                        + "whatever the comparison produced.")
    public record ComparisonResponse(

        @Schema(description = "Whether a comparison was produced.", example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean success,

        @Schema(description = "Name of the first document.", example = "GA Plan — Level 02")
        String doc1Name,

        @Schema(description = "Name of the second document.", example = "GA Plan — Level 02")
        String doc2Name,

        @Schema(description = "File name of the first document.",
                example = "RVD-XX-02-DR-A-1200_P01.pdf")
        String doc1FileName,

        @Schema(description = "File name of the second document.",
                example = "RVD-XX-02-DR-A-1200_P02.pdf")
        String doc2FileName,

        @Schema(description = "Revision of the first document.", example = "P01")
        String doc1Revision,

        @Schema(description = "Revision of the second document.", example = "P02")
        String doc2Revision,

        @Schema(description = "Why no comparison was produced. Present only when `success` is false.",
                example = "The conversion service is not running.")
        String error,

        @Schema(description = "The comparison itself — page images, changed regions, or a textual "
                            + "diff, depending on what was compared.",
                example = "{\"pagesCompared\":14,\"pagesChanged\":[3,7]}")
        Map<String, Object> comparison
    ) {}
}
