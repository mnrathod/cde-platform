import Foundation

/// A point in the coordinate space of the page or drawing it belongs to.
public struct CdePoint: Codable, Equatable, Sendable {
    public var x: Double
    public var y: Double

    public init(x: Double, y: Double) {
        self.x = x
        self.y = y
    }
}

/// Every tool that can produce markup.
///
/// The raw values match the web viewer exactly. `shapeData` is stored by the
/// server as an opaque string it never parses, so the clients agree by
/// convention or not at all.
public enum MarkupTool: String, Codable, Sendable, CaseIterable {
    case pan, select
    case line, arrow, rect, circle, ellipse
    case polygon, polyline, freehand, cloud
    case text, callout, note, stamp
    case highlight, underline, strikeout, squiggly
    case dimension, area, radius, calibrate
    case redact
    case formfield
}

/// One piece of markup.
///
/// A single type with optional geometry rather than a case per tool: this is
/// what the web viewer writes into `shapeData`, and the JSON has to round-trip
/// or markup drawn on a phone will not open in a browser.
public struct ShapeData: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var tool: MarkupTool
    public var pageNumber: Int
    public var color: String
    public var strokeWidth: Double
    public var opacity: Double

    // line, arrow
    public var x1: Double?
    public var y1: Double?
    public var x2: Double?
    public var y2: Double?
    // rect-like — origin plus extent
    public var x: Double?
    public var y: Double?
    public var width: Double?
    public var height: Double?
    // circle
    public var cx: Double?
    public var cy: Double?
    public var r: Double?
    // freehand, cloud, polygon, polyline, measurements
    public var points: [CdePoint]?

    public var text: String?
    /// Primary readout, e.g. "15300 px²". Already scaled.
    public var measurement: String?
    /// Secondary readout: perimeter for an area, diameter for a radius.
    public var measurementDetail: String?
    public var segmentLabels: [String]?

    public var author: String?
    public var createdAt: String?
    /// Server id once saved. Nil while the shape exists only on this device.
    public var savedId: Int64?

    public init(
        id: String,
        tool: MarkupTool,
        pageNumber: Int,
        color: String,
        strokeWidth: Double,
        opacity: Double,
        x1: Double? = nil, y1: Double? = nil, x2: Double? = nil, y2: Double? = nil,
        x: Double? = nil, y: Double? = nil, width: Double? = nil, height: Double? = nil,
        cx: Double? = nil, cy: Double? = nil, r: Double? = nil,
        points: [CdePoint]? = nil,
        text: String? = nil,
        measurement: String? = nil,
        measurementDetail: String? = nil,
        segmentLabels: [String]? = nil,
        author: String? = nil,
        createdAt: String? = nil,
        savedId: Int64? = nil
    ) {
        self.id = id
        self.tool = tool
        self.pageNumber = pageNumber
        self.color = color
        self.strokeWidth = strokeWidth
        self.opacity = opacity
        self.x1 = x1; self.y1 = y1; self.x2 = x2; self.y2 = y2
        self.x = x; self.y = y; self.width = width; self.height = height
        self.cx = cx; self.cy = cy; self.r = r
        self.points = points
        self.text = text
        self.measurement = measurement
        self.measurementDetail = measurementDetail
        self.segmentLabels = segmentLabels
        self.author = author
        self.createdAt = createdAt
        self.savedId = savedId
    }
}

public enum AnnotationType: String, Codable, Sendable {
    case comment = "COMMENT"
    case markup = "MARKUP"
    case dimension = "DIMENSION"
    case cloud = "CLOUD"
    case arrow = "ARROW"
    case stamp = "STAMP"
    case highlight = "HIGHLIGHT"
    case underline = "UNDERLINE"
    case strikeout = "STRIKEOUT"
    case squiggly = "SQUIGGLY"
}

public enum AnnotationStatus: String, Codable, Sendable {
    case open = "OPEN"
    case resolved = "RESOLVED"
    case closed = "CLOSED"
}

public struct Annotation: Codable, Identifiable, Sendable {
    public let id: Int64
    public let documentId: Int64
    public let author: String?
    public let type: AnnotationType
    /// JSON, opaque to the server. Parse with `MarkupCodec`.
    public let shapeData: String
    public let comment: String?
    public let status: AnnotationStatus
    public let pageNumber: Int?
    public let createdAt: String?

    public init(
        id: Int64, documentId: Int64, author: String? = nil,
        type: AnnotationType, shapeData: String, comment: String? = nil,
        status: AnnotationStatus = .open, pageNumber: Int? = nil,
        createdAt: String? = nil
    ) {
        self.id = id
        self.documentId = documentId
        self.author = author
        self.type = type
        self.shapeData = shapeData
        self.comment = comment
        self.status = status
        self.pageNumber = pageNumber
        self.createdAt = createdAt
    }
}

public struct AnnotationRequest: Codable, Sendable {
    public let documentId: Int64
    public let type: AnnotationType
    public let shapeData: String
    public let comment: String?
    public let pageNumber: Int?

    public init(
        documentId: Int64, type: AnnotationType, shapeData: String,
        comment: String? = nil, pageNumber: Int? = nil
    ) {
        self.documentId = documentId
        self.type = type
        self.shapeData = shapeData
        self.comment = comment
        self.pageNumber = pageNumber
    }
}

public enum DocumentType: String, Codable, Sendable {
    case drawing = "DRAWING"
    case specification = "SPECIFICATION"
    case report = "REPORT"
    case schedule = "SCHEDULE"
    case bimModel = "BIM_MODEL"
    case pointCloud = "POINT_CLOUD"
    case other = "OTHER"
}

public enum DocumentStatus: String, Codable, Sendable {
    case draft = "DRAFT"
    case inReview = "IN_REVIEW"
    case approved = "APPROVED"
    case superseded = "SUPERSEDED"
    case void = "VOID"
}

public struct CdeDocument: Codable, Identifiable, Sendable {
    public let id: Int64
    public let name: String
    public let description: String?
    public let fileName: String
    public let fileType: String?
    public let fileSize: Int64?
    public let documentType: DocumentType?
    public let status: DocumentStatus?
    public let revision: String?
    public let drawingNumber: String?
    public let sheetNumber: String?
    public let projectId: Int64
    public let uploadedBy: String?
    /// Server local time, no zone — see API-CONTRACT.md.
    public let createdAt: String?
    public let updatedAt: String?
}

/// One page of a document listing.
///
/// The counts are carried on the type rather than left to the caller to infer,
/// because there is no way to infer them: a page holding fewer items than the
/// page size is not necessarily the last page, and a full page is not
/// necessarily followed by another. A client that returned only `content`
/// would show a plausible, silently truncated list.
public struct DocumentPage: Codable, Sendable {
    /// The documents on this page, in the order the server returned them.
    public let content: [CdeDocument]
    /// Zero-based index of this page.
    public let number: Int
    /// Maximum documents a page of this listing holds.
    public let size: Int
    /// Documents across every page, not just this one.
    public let totalElements: Int64
    public let totalPages: Int
    public let first: Bool
    public let last: Bool

    public init(content: [CdeDocument], number: Int, size: Int,
                totalElements: Int64, totalPages: Int, first: Bool, last: Bool) {
        self.content = content
        self.number = number
        self.size = size
        self.totalElements = totalElements
        self.totalPages = totalPages
        self.first = first
        self.last = last
    }
}

public struct Project: Codable, Identifiable, Sendable {
    public let id: Int64
    public let name: String
    public let description: String?
    public let location: String?
    public let phase: String?
    public let ownerUsername: String?
    public let documentCount: Int
}

/// How a document should be opened.
///
/// An enum rather than a struct of optionals because `/api/viewer/{id}` is
/// polymorphic on `type`: a PDF carries a URL, a drawing carries its content
/// inline, and an unsupported format carries neither. Flattening those into
/// one shape moves the decision to every call site.
public enum ViewerSource: Sendable {
    case pdf(PdfSource)
    case drawing(DrawingSource)
    case image(ImageSource)
    /// A format with no viewer. Reported rather than shown as a blank page.
    case unsupported(UnsupportedSource)

    public struct PdfSource: Sendable {
        public let name: String
        public let revision: String?
        public let drawingNumber: String?
        public let fileName: String?
        /// Server-relative, carries `?v=` so a new version is a new URL.
        public let path: String
        public let version: Int
    }

    public struct DrawingSource: Sendable {
        public let name: String
        public let revision: String?
        public let drawingNumber: String?
        /// The complete SVG. There is no second request.
        public let svg: String
    }

    public struct ImageSource: Sendable {
        public let name: String
        public let revision: String?
        public let drawingNumber: String?
        public let path: String
    }

    public struct UnsupportedSource: Sendable {
        public let name: String
        public let revision: String?
        public let drawingNumber: String?
        public let type: String
        public let fileName: String?
    }

    public var name: String {
        switch self {
        case .pdf(let s): return s.name
        case .drawing(let s): return s.name
        case .image(let s): return s.name
        case .unsupported(let s): return s.name
        }
    }
}

/// Reads and writes the `shapeData` string.
///
/// The single place the mobile clients and the web viewer agree on markup
/// format, since nothing on the wire enforces it.
public enum MarkupCodec {

    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        // Optionals are omitted rather than written as null, matching what the
        // web viewer produces.
        return encoder
    }()

    private static let decoder = JSONDecoder()

    public static func encode(_ shape: ShapeData) throws -> String {
        let data = try encoder.encode(shape)
        return String(decoding: data, as: UTF8.self)
    }

    /// Parses stored markup, returning nil rather than throwing.
    ///
    /// An annotation written by a future client is one annotation that cannot
    /// be drawn — not a reason to fail loading the document and hide every
    /// other annotation on it.
    public static func decode(_ shapeData: String) -> ShapeData? {
        guard let data = shapeData.data(using: .utf8) else { return nil }
        return try? decoder.decode(ShapeData.self, from: data)
    }

    /// Decodes a list, dropping any entry that cannot be read.
    public static func decodeAll(_ annotations: [Annotation]) -> [ShapeData] {
        annotations.compactMap { annotation in
            guard var shape = decode(annotation.shapeData) else { return nil }
            shape.savedId = annotation.id
            return shape
        }
    }
}
