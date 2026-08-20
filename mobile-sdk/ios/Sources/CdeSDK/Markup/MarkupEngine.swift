import Foundation

/// The geometry behind drawing markup, and the rules for when a shape is
/// finished.
///
/// A direct port of the web viewer's engine, kept deliberately in step with
/// it: both clients write the same `shapeData` and the server never validates
/// it, so a divergence here does not fail — it produces markup that reads
/// differently in a browser than on the phone that drew it. The parity tests
/// carry the same vectors as the TypeScript suite for that reason.
///
/// Pure geometry, no UIKit, so it is testable without a simulator.
public enum MarkupEngine {

    /// Tools built by tapping a series of points rather than by dragging.
    private static let vertexTools: Set<MarkupTool> = [
        .polygon, .polyline, .dimension, .area, .radius, .calibrate
    ]

    public static func isVertexTool(_ tool: MarkupTool) -> Bool {
        vertexTools.contains(tool)
    }

    /// Vertex tools that end on a fixed count rather than a closing gesture.
    /// Radius and Calibrate are defined by exactly two points.
    public static func requiredVertices(_ tool: MarkupTool) -> Int? {
        switch tool {
        case .radius, .calibrate: return 2
        default: return nil
        }
    }

    /// The fewest vertices a shape needs before it can be closed at all.
    public static func minimumVertices(_ tool: MarkupTool) -> Int {
        (tool == .area || tool == .polygon) ? 3 : 2
    }

    /// True when a click-built shape holds enough vertices to be committed.
    public static func canFinish(_ shape: ShapeData?) -> Bool {
        guard let shape, isVertexTool(shape.tool) else { return false }
        return (shape.points?.count ?? 0) >= minimumVertices(shape.tool)
    }

    /// Whether a tap ends the shape rather than adding another vertex.
    ///
    /// On the web this also accepts a double-click, but a double *tap* is a
    /// poor gesture on a touch screen: it fights the platform's own
    /// double-tap-to-zoom and asks for two precise touches in one spot. The
    /// two reliable gestures are kept:
    ///
    /// - the tap lands on the first vertex, closing the outline;
    /// - the tap lands on the vertex just placed.
    ///
    /// Both are what a finger can actually do, and neither depends on timing.
    /// A Done control in the viewer covers the rest.
    ///
    /// - Parameter tolerance: radius in the shape's own coordinate space.
    ///   Callers derive it from the current zoom so the target stays a
    ///   constant size under the finger.
    public static func finishesShape(
        _ shape: ShapeData?, at point: CdePoint, tolerance: Double
    ) -> Bool {
        guard canFinish(shape), let shape else { return false }
        guard requiredVertices(shape.tool) == nil else { return false }
        guard let points = shape.points, let first = points.first, let last = points.last
        else { return false }

        func near(_ vertex: CdePoint) -> Bool {
            hypot(point.x - vertex.x, point.y - vertex.y) <= tolerance
        }
        return near(first) || near(last)
    }

    public static func addVertex(_ shape: ShapeData, at point: CdePoint) -> ShapeData {
        var updated = shape
        updated.points = (shape.points ?? []) + [point]
        return updated
    }

    public static func removeLastVertex(_ shape: ShapeData) -> ShapeData {
        var updated = shape
        updated.points = (shape.points ?? []).dropLast()
        return updated
    }

    /// Starts a shape at `point`. Geometry is filled per tool.
    public static func startShape(
        tool: MarkupTool,
        at point: CdePoint,
        pageNumber: Int,
        color: String,
        strokeWidth: Double,
        opacity: Double,
        author: String? = nil
    ) -> ShapeData {
        var shape = ShapeData(
            id: newId(), tool: tool, pageNumber: pageNumber,
            color: color, strokeWidth: strokeWidth, opacity: opacity,
            author: author)

        switch tool {
        case .line, .arrow:
            shape.x1 = point.x; shape.y1 = point.y
            shape.x2 = point.x; shape.y2 = point.y
        case .circle:
            shape.cx = point.x; shape.cy = point.y; shape.r = 0
        case .freehand, .cloud:
            shape.points = [point]
        default:
            if isVertexTool(tool) {
                shape.points = [point]
            } else {
                shape.x = point.x; shape.y = point.y
                shape.width = 0; shape.height = 0
            }
        }
        return shape
    }

    /// Applies a drag to a shape being drawn.
    public static func updateShape(_ shape: ShapeData, at point: CdePoint) -> ShapeData {
        var updated = shape

        switch shape.tool {
        case .line, .arrow:
            updated.x2 = point.x
            updated.y2 = point.y

        case .circle:
            updated.r = hypot(point.x - (shape.cx ?? 0), point.y - (shape.cy ?? 0))

        case .freehand, .cloud:
            updated.points = (shape.points ?? []) + [point]

        default:
            guard !isVertexTool(shape.tool) else { return shape }
            // Normalised so dragging up or left still yields a positive
            // extent rather than an invisible shape.
            let originX = shape.x ?? point.x
            let originY = shape.y ?? point.y
            updated.x = min(originX, point.x)
            updated.y = min(originY, point.y)
            updated.width = abs(point.x - originX)
            updated.height = abs(point.y - originY)
        }
        return updated
    }

    /// Whether the shape is big enough to be worth committing.
    public static func hasMinimumSize(_ shape: ShapeData) -> Bool {
        let minimum = 3.0

        switch shape.tool {
        case .line, .arrow:
            return hypot((shape.x2 ?? 0) - (shape.x1 ?? 0),
                         (shape.y2 ?? 0) - (shape.y1 ?? 0)) > minimum
        case .dimension, .area, .calibrate, .radius:
            return (shape.points?.count ?? 0) >= minimumVertices(shape.tool)
        case .circle:
            return (shape.r ?? 0) > minimum
        case .freehand, .cloud, .polygon:
            return (shape.points?.count ?? 0) > 2
        case .polyline:
            return (shape.points?.count ?? 0) > 1
        default:
            return (shape.width ?? 0) > minimum && (shape.height ?? 0) > minimum
        }
    }

    // MARK: - Measurement geometry

    /// Total length along a path, in the coordinate space of the points.
    public static func pathLength(_ points: [CdePoint]) -> Double {
        guard points.count > 1 else { return 0 }
        return zip(points, points.dropFirst())
            .reduce(0) { $0 + hypot($1.1.x - $1.0.x, $1.1.y - $1.0.y) }
    }

    /// Area of a closed polygon by the shoelace formula.
    ///
    /// The path is treated as closed whether or not the last point repeats the
    /// first, so a shape finished by tapping the opening vertex and one
    /// finished on its last vertex report the same area.
    public static func polygonArea(_ points: [CdePoint]) -> Double {
        guard points.count >= 3 else { return 0 }
        var sum = 0.0
        for index in points.indices {
            let next = points[(index + 1) % points.count]
            sum += points[index].x * next.y - next.x * points[index].y
        }
        return abs(sum / 2)
    }

    /// Perimeter of the closed polygon, including the closing segment.
    public static func polygonPerimeter(_ points: [CdePoint]) -> Double {
        guard points.count >= 2, let first = points.first, let last = points.last
        else { return 0 }
        return pathLength(points) + hypot(first.x - last.x, first.y - last.y)
    }

    // MARK: - Identity

    private static let counter = Counter()

    /// Ids are only ever compared, never ordered, and are namespaced with
    /// `s-` to match the web viewer's format so a shape drawn on either
    /// client is indistinguishable to the other.
    public static func newId() -> String {
        let tick = counter.next()
        let milliseconds = Int(Date().timeIntervalSince1970 * 1000)
        let suffix = String(UInt64(bitPattern: Int64(tick &* 2_654_435_761)) % 0xFFFF_FFFF,
                            radix: 36)
        return "s-\(milliseconds)-\(suffix)"
    }

    /// Serialised counter so ids stay unique when markup is created from more
    /// than one task.
    private final class Counter: @unchecked Sendable {
        private var value: Int = 0
        private let lock = NSLock()

        func next() -> Int {
            lock.lock()
            defer { lock.unlock() }
            value &+= 1
            return value
        }
    }
}
