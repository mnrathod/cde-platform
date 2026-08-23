package com.cde.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * What the viewer needs in order to open a document.
 *
 * <p>The reply is genuinely polymorphic: a drawing arrives as SVG markup, a PDF
 * as a pointer to its bytes, a model as geometry, and an unopenable file as an
 * explanation of why. The {@code type} member discriminates between them, and
 * every variant carries it.
 *
 * <p>The variants that report a problem are returned with {@code 200}, not with
 * an error status. That is a defect rather than a design: the whole frontend
 * branches on {@code type}, so changing it is a coordinated change on both
 * sides. It is documented here as it behaves, not as it should behave.
 */
public final class ViewerDtos {

    private ViewerDtos() {
    }

    @Schema(name = "ViewerPayload",
            description = """
                What the viewer needs to open a document. Read `type` first — it decides which \
                other members are present.

                Note that the failure variants (`error`, `dwg_binary`, `office_error`, \
                `3d_error`, `revit_binary`, `unsupported`) are returned with `200`, so a \
                successful status does not mean the document opened. Branch on `type`.""",
            discriminatorProperty = "type",
            oneOf = {
                SvgPayload.class, PdfPayload.class, ModelPayload.class,
                UnopenablePayload.class, ViewerErrorPayload.class
            })
    public sealed interface ViewerPayload
        permits SvgPayload, PdfPayload, ModelPayload, UnopenablePayload, ViewerErrorPayload {

        /** Which variant this is. */
        String type();
    }

    @Schema(name = "SvgPayload",
            description = "A drawing, as SVG markup ready to render. Used for uploaded SVG and "
                        + "for CAD converted to it.")
    public record SvgPayload(

        @Schema(description = "Always `svg`.", example = "svg", allowableValues = {"svg"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String type,

        @Schema(description = "The SVG markup itself.",
                example = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 841 595\"/>",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String content,

        @Schema(description = "The document's display name.", example = "GA Plan — Level 02",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @Schema(description = "Drawing number from the title block, empty when none is held.",
                example = "RVD-XX-02-DR-A-1200")
        String drawingNumber,

        @Schema(description = "Revision identifier, empty when none is held.", example = "P02.1")
        String revision,

        @Schema(description = "Which converter produced the markup. Present for converted CAD "
                            + "only, and useful when a drawing renders oddly — the fallback "
                            + "renderer supports less than the primary one.",
                example = "ezdxf")
        String renderedBy
    ) implements ViewerPayload {}

    @Schema(name = "PdfPayload",
            description = "A PDF. The bytes are fetched separately so the viewer can stream them; "
                        + "this carries the pointer and the metadata around it.")
    public record PdfPayload(

        @Schema(description = "Always `pdf`.", example = "pdf", allowableValues = {"pdf"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String type,

        @Schema(description = "The document's display name.", example = "GA Plan — Level 02",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @Schema(description = "Name of the underlying file.", example = "RVD-XX-02-DR-A-1200.pdf")
        String fileName,

        @Schema(description = "Drawing number from the title block, empty when none is held.",
                example = "RVD-XX-02-DR-A-1200")
        String drawingNumber,

        @Schema(description = "Revision identifier, empty when none is held.", example = "P02.1")
        String revision,

        @Schema(description = "Version the pointer refers to.", example = "3", minimum = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int version,

        @Schema(description = """
                Where to fetch the bytes. The version is in the query string because processing \
                replaces the bytes behind a fixed URL, and without it a browser would serve the \
                previous version from cache.""",
                example = "/api/viewer/1180/pdf?v=3", format = "uri-reference",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String pdfUrl
    ) implements ViewerPayload {}

    @Schema(name = "ModelPayload",
            description = """
                Geometry and hierarchy for an IFC model, as the conversion service produced it. \
                Its remaining members are the service's own and vary with what the model \
                contains, so they are carried opaquely rather than enumerated here — an \
                enumeration would go stale the first time the extractor learned something new.

                The structured hierarchy is the primary interface; the rendered view is a layer \
                over it.""")
    public record ModelPayload(

        @Schema(description = "Always `ifc3d`.", example = "ifc3d", allowableValues = {"ifc3d"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String type,

        @Schema(description = "Whether the extraction produced usable geometry.", example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean success,

        @Schema(description = "Geometry, hierarchy and properties, in the conversion service's "
                            + "own format.",
                example = "{\"meshes\":12,\"storeys\":4}")
        Map<String, Object> geometry
    ) implements ViewerPayload {}

    @Schema(name = "UnopenablePayload",
            description = """
                The document exists but this viewer cannot open it, and why. Returned with `200` \
                — read `type` rather than the status.

                `dwg_binary` means a DWG whose converter is not installed; `office_error` an \
                Office document that could not be converted; `revit_binary` a Revit file, which \
                needs Revit; `3d_error` a model the conversion service could not read; \
                `unsupported` a format nothing here handles.""")
    public record UnopenablePayload(

        @Schema(description = "Which kind of unopenable this is.", example = "revit_binary",
                allowableValues = {"dwg_binary", "office_error", "revit_binary", "3d_error",
                                   "unsupported"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String type,

        @Schema(description = "The document's display name.", example = "Depot — Structural",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @Schema(description = "Name of the underlying file.", example = "depot-structural.rvt")
        String fileName,

        @Schema(description = "The file's extension, for a client choosing what to suggest.",
                example = "rvt")
        String ext,

        @Schema(description = "Why it could not be opened, where a reason is known.",
                example = "converter_offline")
        String error,

        @Schema(description = "Whether the ODA converter is installed. `dwg_binary` only.",
                example = "false")
        Boolean odaInstalled,

        @Schema(description = "Whether LibreDWG is installed. `dwg_binary` only.", example = "true")
        Boolean libredwgInstalled,

        @Schema(description = "Which DWG release the file is, where it could be read from the "
                            + "header. `dwg_binary` only.",
                example = "AC1032")
        String version,

        @Schema(description = "Whether LibreOffice is installed. `office_error` only.",
                example = "true")
        Boolean loInstalled
    ) implements ViewerPayload {}

    @Schema(name = "ModelTreeNode",
            description = """
                One node of a model's hierarchy. The tree is the accessible route to the model's \
                information: it must support the same navigation, search and selection the \
                rendered view does, because a canvas on its own cannot.""")
    public record ModelTreeNode(

        @Schema(description = "Identifier of this element within the model.",
                example = "3Vw$Kq2Kn0zeF1yZ8bQwXt", requiredMode = Schema.RequiredMode.REQUIRED)
        String id,

        @Schema(description = "Name to show for this element.", example = "Basic Wall — 215mm",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @Schema(description = "The element's class in the source model.", example = "IfcWall",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String type,

        @Schema(description = "Whether a client should show this node's children initially.",
                example = "false")
        boolean expanded,

        @Schema(description = "Whether this node is currently selected.", example = "false")
        boolean selected,

        @Schema(description = "Whether this element is currently shown in the rendered view.",
                example = "true")
        boolean visible,

        @Schema(description = """
                Set only on the root, and only when the hierarchy could not be extracted from the \
                model itself — the conversion service was unreachable, so what is returned is a \
                placeholder outline of the element classes a model of this kind usually contains, \
                not this model's contents.

                Absent or false means the hierarchy came from the model. A client must not \
                present a synthetic tree as the model's structure.""",
                example = "true")
        Boolean synthetic,

        @Schema(description = "Elements contained by this one.")
        java.util.List<ModelTreeNode> children
    ) {}

    @Schema(name = "ViewerErrorPayload",
            description = "The document could not be read at all — no file recorded, or the file "
                        + "is missing from storage. Returned with `200`; read `type`.")
    public record ViewerErrorPayload(

        @Schema(description = "Always `error`.", example = "error", allowableValues = {"error"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String type,

        @Schema(description = "What went wrong, phrased for a person.",
                example = "This document has no file behind it.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String error
    ) implements ViewerPayload {}
}
