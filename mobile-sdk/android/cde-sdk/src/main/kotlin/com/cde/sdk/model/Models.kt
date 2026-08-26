package com.cde.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A point in the coordinate space of the page or drawing it belongs to. */
@Serializable
data class Point(val x: Double, val y: Double)

/**
 * Every tool that can produce markup.
 *
 * The serialised names match the web viewer exactly, because `shapeData` is
 * stored as an opaque string the server never parses — the two clients agree
 * by convention or not at all.
 */
@Serializable
enum class MarkupTool {
    @SerialName("pan") PAN,
    @SerialName("select") SELECT,
    @SerialName("line") LINE,
    @SerialName("arrow") ARROW,
    @SerialName("rect") RECT,
    @SerialName("circle") CIRCLE,
    @SerialName("ellipse") ELLIPSE,
    @SerialName("polygon") POLYGON,
    @SerialName("polyline") POLYLINE,
    @SerialName("freehand") FREEHAND,
    @SerialName("cloud") CLOUD,
    @SerialName("text") TEXT,
    @SerialName("callout") CALLOUT,
    @SerialName("note") NOTE,
    @SerialName("stamp") STAMP,
    @SerialName("highlight") HIGHLIGHT,
    @SerialName("underline") UNDERLINE,
    @SerialName("strikeout") STRIKEOUT,
    @SerialName("squiggly") SQUIGGLY,
    @SerialName("dimension") DIMENSION,
    @SerialName("area") AREA,
    @SerialName("radius") RADIUS,
    @SerialName("calibrate") CALIBRATE,
    @SerialName("redact") REDACT,
    @SerialName("formfield") FORM_FIELD,
}

/**
 * One piece of markup.
 *
 * Deliberately a single type with optional geometry rather than a sealed
 * hierarchy per tool: this is what the web viewer writes into `shapeData`, and
 * the JSON has to round-trip byte-for-byte in meaning or markup drawn on a
 * phone will not open in a browser.
 */
@Serializable
data class ShapeData(
    val id: String,
    val tool: MarkupTool,
    val pageNumber: Int,
    val color: String,
    val strokeWidth: Double,
    val opacity: Double,

    // line, arrow
    val x1: Double? = null, val y1: Double? = null,
    val x2: Double? = null, val y2: Double? = null,
    // rect, highlight, redact, form field — origin plus extent
    val x: Double? = null, val y: Double? = null,
    val width: Double? = null, val height: Double? = null,
    // circle
    val cx: Double? = null, val cy: Double? = null, val r: Double? = null,
    // freehand, cloud, polygon, polyline, measurements
    val points: List<Point>? = null,

    val text: String? = null,
    /** Primary readout, e.g. "15300 px²". Already scaled — see MeasurementScale. */
    val measurement: String? = null,
    /** Secondary readout: perimeter for an area, diameter for a radius. */
    val measurementDetail: String? = null,
    val segmentLabels: List<String>? = null,

    val author: String? = null,
    val createdAt: String? = null,
    /** Server id once saved. Null while the shape exists only on this device. */
    val savedId: Long? = null,
)

@Serializable
enum class AnnotationType {
    COMMENT, MARKUP, DIMENSION, CLOUD, ARROW, STAMP,
    HIGHLIGHT, UNDERLINE, STRIKEOUT, SQUIGGLY
}

@Serializable
enum class AnnotationStatus { OPEN, RESOLVED, CLOSED }

@Serializable
data class CdeAnnotation(
    val id: Long,
    val documentId: Long,
    val author: String? = null,
    val type: AnnotationType,
    /** JSON, opaque to the server. Parse with MarkupCodec. */
    val shapeData: String,
    val comment: String? = null,
    val status: AnnotationStatus = AnnotationStatus.OPEN,
    val pageNumber: Int? = null,
    val createdAt: String? = null,
)

@Serializable
data class AnnotationRequest(
    val documentId: Long,
    val type: AnnotationType,
    val shapeData: String,
    val comment: String? = null,
    val pageNumber: Int? = null,
)

@Serializable
enum class DocumentType {
    DRAWING, SPECIFICATION, REPORT, SCHEDULE, BIM_MODEL, POINT_CLOUD, OTHER
}

@Serializable
enum class DocumentStatus { DRAFT, IN_REVIEW, APPROVED, SUPERSEDED, VOID }

@Serializable
data class CdeDocument(
    val id: Long,
    val name: String,
    val description: String? = null,
    val fileName: String,
    val fileType: String? = null,
    val fileSize: Long? = null,
    val documentType: DocumentType? = null,
    val status: DocumentStatus? = null,
    val revision: String? = null,
    val drawingNumber: String? = null,
    val sheetNumber: String? = null,
    val projectId: Long,
    val uploadedBy: String? = null,
    /** Server local time, no zone — see API-CONTRACT.md. */
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/**
 * One page of a document listing.
 *
 * <p>The counts are carried on the type rather than left to the caller to
 * infer, because there is no way to infer them: a page holding fewer items
 * than the page size is not necessarily the last page, and a full page is not
 * necessarily followed by another. A client that returned only [content] would
 * show a plausible, silently truncated list.
 */
@Serializable
data class DocumentPage(
    /** The documents on this page, in the order the server returned them. */
    val content: List<CdeDocument>,
    /** Zero-based index of this page. */
    val number: Int,
    /** Maximum documents a page of this listing holds. */
    val size: Int,
    /** Documents across every page, not just this one. */
    val totalElements: Long,
    val totalPages: Int,
    val first: Boolean,
    val last: Boolean,
)

@Serializable
data class Project(
    val id: Long,
    val name: String,
    val description: String? = null,
    val location: String? = null,
    val phase: String? = null,
    val ownerUsername: String? = null,
    val documentCount: Int = 0,
)

/**
 * How a document should be opened.
 *
 * Modelled as a sealed type because `/api/viewer/{id}` is polymorphic on
 * `type`: a PDF carries a URL to fetch, a drawing carries its whole content
 * inline, and an unsupported format carries neither. Flattening that into one
 * struct of optionals moves the decision to every call site.
 */
sealed interface ViewerSource {
    val name: String
    val revision: String?
    val drawingNumber: String?

    data class Pdf(
        override val name: String,
        override val revision: String?,
        override val drawingNumber: String?,
        val fileName: String?,
        /** Server-relative, carries ?v= so a new version is a new URL. */
        val pdfPath: String,
        val version: Int,
    ) : ViewerSource

    data class Drawing(
        override val name: String,
        override val revision: String?,
        override val drawingNumber: String?,
        /** The complete SVG. There is no second request. */
        val svg: String,
    ) : ViewerSource

    data class Image(
        override val name: String,
        override val revision: String?,
        override val drawingNumber: String?,
        val imagePath: String,
    ) : ViewerSource

    /** A format with no viewer. Reported rather than shown as a blank page. */
    data class Unsupported(
        override val name: String,
        override val revision: String?,
        override val drawingNumber: String?,
        val type: String,
        val fileName: String?,
    ) : ViewerSource
}
