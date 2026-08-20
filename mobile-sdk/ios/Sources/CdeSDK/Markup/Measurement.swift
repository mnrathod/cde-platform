import Foundation

/// Units a drawing can be calibrated to.
public enum MeasurementUnit: String, Codable, Sendable, CaseIterable {
    case millimetre = "mm"
    case centimetre = "cm"
    case metre = "m"
    case kilometre = "km"
    case inch = "in"
    case foot = "ft"
    case yard = "yd"
    case pixel = "px"
}

/// What one pixel of the rendered document is worth in real units.
///
/// Uncalibrated is not an error state — it is the honest one. A drawing
/// carries no inherent scale, so until someone marks a known distance every
/// reading is in pixels and is labelled as such rather than presented as a
/// length.
public struct MeasurementScale: Codable, Equatable, Sendable {
    public var unitsPerPixel: Double
    public var unit: MeasurementUnit

    public var isCalibrated: Bool { unit != .pixel }

    public static let uncalibrated = MeasurementScale(unitsPerPixel: 1, unit: .pixel)

    public init(unitsPerPixel: Double, unit: MeasurementUnit) {
        self.unitsPerPixel = unitsPerPixel
        self.unit = unit
    }
}

/// A committed reading, kept so earlier results stay reviewable.
public struct MeasurementEntry: Identifiable, Sendable {
    public enum Kind: String, Sendable { case linear, area, radius }

    public let id: String
    public let kind: Kind
    public let value: String
    public let detail: String
    public let page: Int
}

/// Turns drawn geometry into readings.
///
/// Every calculation happens in page pixels first and is scaled afterwards, so
/// a calibration applied later cannot retrospectively change a measurement
/// already taken — the number on screen and the number in the record stay the
/// same.
public enum Measurement {

    /// Builds a scale from a line drawn over a distance the user knows.
    ///
    /// - Returns: nil when the input cannot define a scale, rather than a
    ///   scale of zero or infinity that would poison every later reading.
    public static func calibrate(
        pixels: Double, realDistance: Double, unit: MeasurementUnit
    ) -> MeasurementScale? {
        guard pixels > 0, realDistance > 0, realDistance.isFinite else { return nil }
        return MeasurementScale(unitsPerPixel: realDistance / pixels, unit: unit)
    }

    /// Describes a measurement shape, returning it with its readouts filled in
    /// alongside the entry for the measurement list.
    ///
    /// - Parameter zoom: the zoom the geometry was captured at, divided out so
    ///   a measurement taken at 200% equals the same one taken at 100%.
    public static func describe(
        _ shape: ShapeData, scale: MeasurementScale, zoom: Double
    ) -> (shape: ShapeData, entry: MeasurementEntry) {
        let points = shape.points ?? []
        let z = zoom > 0 ? zoom : 1
        var described = shape

        switch shape.tool {
        case .area:
            let areaPixels = MarkupEngine.polygonArea(points) / (z * z)
            let perimeterPixels = MarkupEngine.polygonPerimeter(points) / z
            let value = formatArea(areaPixels, scale: scale)
            let detail = formatLength(perimeterPixels, scale: scale)
            described.measurement = value
            described.measurementDetail = detail
            return (described, MeasurementEntry(
                id: shape.id, kind: .area, value: value, detail: detail,
                page: shape.pageNumber))

        case .radius:
            let radiusPixels = points.count >= 2
                ? hypot(points[1].x - points[0].x, points[1].y - points[0].y) / z
                : 0
            let value = formatLength(radiusPixels, scale: scale)
            let detail = "⌀ " + formatLength(radiusPixels * 2, scale: scale)
            described.measurement = value
            described.measurementDetail = detail
            return (described, MeasurementEntry(
                id: shape.id, kind: .radius, value: value, detail: detail,
                page: shape.pageNumber))

        default:
            let lengthPixels = MarkupEngine.pathLength(points) / z
            let value = formatLength(lengthPixels, scale: scale)
            var segments: [String]?
            if points.count > 2 {
                segments = zip(points, points.dropFirst()).map { pair in
                    formatLength(hypot(pair.1.x - pair.0.x, pair.1.y - pair.0.y) / z,
                                 scale: scale)
                }
            }
            let detail = segments?.joined(separator: " + ") ?? ""
            described.measurement = value
            described.measurementDetail = detail
            described.segmentLabels = segments
            return (described, MeasurementEntry(
                id: shape.id, kind: .linear, value: value, detail: detail,
                page: shape.pageNumber))
        }
    }

    public static func formatLength(_ pixels: Double, scale: MeasurementScale) -> String {
        "\(trim(pixels * scale.unitsPerPixel)) \(scale.unit.rawValue)"
    }

    /// Areas scale with the square of the linear factor. Getting this wrong
    /// yields a number that looks plausible and is out by the scale itself,
    /// which is the worst kind of wrong on a quantity take-off.
    public static func formatArea(_ squarePixels: Double, scale: MeasurementScale) -> String {
        let value = squarePixels * scale.unitsPerPixel * scale.unitsPerPixel
        return "\(trim(value)) \(scale.unit.rawValue)²"
    }

    /// Enough precision to be useful without implying more than the drawing
    /// supports: whole numbers when large, more places as values get small.
    private static func trim(_ value: Double) -> String {
        guard value.isFinite else { return "—" }
        let magnitude = abs(value)
        let text: String
        switch magnitude {
        case 1000...: text = String(Int(value.rounded()))
        case 10..<1000: text = String(format: "%.1f", value)
        case 1..<10: text = String(format: "%.2f", value)
        default: text = String(format: "%.3f", value)
        }
        guard text.contains(".") else { return text }
        var trimmed = text
        while trimmed.hasSuffix("0") { trimmed.removeLast() }
        if trimmed.hasSuffix(".") { trimmed.removeLast() }
        return trimmed.isEmpty ? "0" : trimmed
    }
}
