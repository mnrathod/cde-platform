#if canImport(UIKit)
import UIKit

/// Draws markup over a page, and turns touches into shapes.
///
/// Coordinates are kept in the **page's** space, not the view's, and converted
/// on the way in and out. Storing view coordinates would mean every shape
/// moved when the user zoomed, and markup saved on a phone would not line up
/// in a browser — the shape data has to mean the same thing to both.
///
/// Drawing is Core Graphics in `draw(_:)`. Markup is tens of shapes, not
/// thousands, so there is nothing here that justifies a second rendering
/// stack; the page underneath is PDFKit's problem and is left to it.
public final class MarkupOverlayView: UIView {

    /// Committed markup for the visible page.
    public var shapes: [ShapeData] = [] {
        didSet { setNeedsDisplay() }
    }

    public var activeTool: MarkupTool = .pan {
        didSet {
            if !MarkupEngine.isVertexTool(activeTool) { cancelInProgress() }
            isUserInteractionEnabled = activeTool != .pan && activeTool != .select
            setNeedsDisplay()
        }
    }

    public var strokeColor: UIColor = .systemRed
    public var strokeWidth: CGFloat = 2
    public var fillOpacity: CGFloat = 0.15
    public var pageNumber: Int = 1

    /// Maps page space to view space. Set by the host as it pans and zooms.
    public var pageToView: (CdePoint) -> CGPoint = { CGPoint(x: $0.x, y: $0.y) }
    public var viewToPage: (CGPoint) -> CdePoint = { CdePoint(x: $0.x, y: $0.y) }
    /// Current zoom, so hit tolerances stay a constant size on screen.
    public var zoom: CGFloat = 1

    /// Called when a shape is completed and should be recorded.
    public var onShapeCompleted: ((ShapeData) -> Void)?
    /// Called whenever the in-progress shape changes, for a live readout.
    public var onShapeChanged: ((ShapeData?) -> Void)?

    private var inProgress: ShapeData?
    private var isDragging = false

    /// True while a shape is being built by taps, so the host can offer Done.
    public var hasUnfinishedShape: Bool { MarkupEngine.canFinish(inProgress) }

    /// Finger-sized target for closing a shape, in points.
    private let vertexTarget: CGFloat = 22
    private let vertexDot: CGFloat = 4

    public override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("Use init(frame:)") }

    // MARK: - Touch

    public override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first, touches.count == 1 else {
            cancelInProgress()
            return
        }
        let at = viewToPage(touch.location(in: self))

        if MarkupEngine.isVertexTool(activeTool) {
            handleVertexTap(at)
        } else {
            beginDrag(at)
        }
    }

    public override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard isDragging, let touch = touches.first, let current = inProgress else { return }
        inProgress = MarkupEngine.updateShape(current, at: viewToPage(touch.location(in: self)))
        onShapeChanged?(inProgress)
        setNeedsDisplay()
    }

    public override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard isDragging else { return }
        isDragging = false
        commitIfWorthwhile()
    }

    public override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        isDragging = false
        cancelInProgress()
    }

    private func beginDrag(_ at: CdePoint) {
        isDragging = true
        inProgress = MarkupEngine.startShape(
            tool: activeTool, at: at, pageNumber: pageNumber,
            color: strokeColor.hexString,
            strokeWidth: Double(strokeWidth / zoom),
            opacity: Double(fillOpacity))
        onShapeChanged?(inProgress)
        setNeedsDisplay()
    }

    /// A tap while a click-built tool is active.
    ///
    /// The shape ends by tapping its first or last vertex rather than by
    /// double-tapping: a double tap fights the platform's own zoom gesture and
    /// demands two precise touches in one spot. `finish()` covers the rest.
    private func handleVertexTap(_ at: CdePoint) {
        let tolerance = Double(vertexTarget / zoom)

        if let current = inProgress, current.tool == activeTool,
           MarkupEngine.finishesShape(current, at: at, tolerance: tolerance) {
            finish()
            return
        }

        let shape: ShapeData
        if let current = inProgress, current.tool == activeTool {
            shape = MarkupEngine.addVertex(current, at: at)
        } else {
            shape = MarkupEngine.startShape(
                tool: activeTool, at: at, pageNumber: pageNumber,
                color: strokeColor.hexString,
                strokeWidth: Double(strokeWidth / zoom),
                opacity: Double(fillOpacity))
        }
        inProgress = shape

        // Radius and Calibrate are defined by exactly two points and complete
        // themselves, rather than waiting for a gesture the user has no reason
        // to expect.
        if let required = MarkupEngine.requiredVertices(activeTool),
           (shape.points?.count ?? 0) >= required {
            finish()
            return
        }

        onShapeChanged?(inProgress)
        setNeedsDisplay()
    }

    /// Completes the shape being built. Safe to call when there is none.
    public func finish() {
        guard let shape = inProgress, MarkupEngine.canFinish(shape) else { return }
        inProgress = nil
        isDragging = false
        if MarkupEngine.hasMinimumSize(shape) { onShapeCompleted?(shape) }
        onShapeChanged?(nil)
        setNeedsDisplay()
    }

    /// Abandons the shape being built, losing nothing already committed.
    public func cancelInProgress() {
        guard inProgress != nil else { return }
        inProgress = nil
        isDragging = false
        onShapeChanged?(nil)
        setNeedsDisplay()
    }

    private func commitIfWorthwhile() {
        guard let shape = inProgress else { return }
        inProgress = nil
        if MarkupEngine.hasMinimumSize(shape) { onShapeCompleted?(shape) }
        onShapeChanged?(nil)
        setNeedsDisplay()
    }

    // MARK: - Drawing

    public override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        for shape in shapes { draw(shape, in: context, inProgress: false) }
        if let current = inProgress { draw(current, in: context, inProgress: true) }
    }

    private func draw(_ shape: ShapeData, in context: CGContext, inProgress isBuilding: Bool) {
        let colour = UIColor(hex: shape.color) ?? .systemRed
        context.setStrokeColor(colour.cgColor)
        context.setLineWidth(max(1, CGFloat(shape.strokeWidth) * zoom))
        context.setLineCap(.round)
        context.setLineJoin(.round)
        context.setFillColor(colour.withAlphaComponent(CGFloat(shape.opacity)).cgColor)

        switch shape.tool {
        case .line, .arrow:
            let from = pageToView(CdePoint(x: shape.x1 ?? 0, y: shape.y1 ?? 0))
            let to = pageToView(CdePoint(x: shape.x2 ?? 0, y: shape.y2 ?? 0))
            context.move(to: from)
            context.addLine(to: to)
            context.strokePath()
            if shape.tool == .arrow { drawArrowHead(from: from, to: to, in: context) }

        case .circle:
            let centre = pageToView(CdePoint(x: shape.cx ?? 0, y: shape.cy ?? 0))
            let radius = CGFloat(shape.r ?? 0) * zoom
            let box = CGRect(x: centre.x - radius, y: centre.y - radius,
                             width: radius * 2, height: radius * 2)
            if shape.opacity > 0 { context.fillEllipse(in: box) }
            context.strokeEllipse(in: box)

        case .polygon, .polyline, .freehand, .cloud,
             .area, .dimension, .radius, .calibrate:
            drawPath(shape, in: context, inProgress: isBuilding)

        default:
            let origin = pageToView(CdePoint(x: shape.x ?? 0, y: shape.y ?? 0))
            let far = pageToView(CdePoint(
                x: (shape.x ?? 0) + (shape.width ?? 0),
                y: (shape.y ?? 0) + (shape.height ?? 0)))
            let box = CGRect(x: origin.x, y: origin.y,
                             width: far.x - origin.x, height: far.y - origin.y)
            if shape.opacity > 0 { context.fill(box) }
            context.stroke(box)
        }
    }

    private func drawPath(_ shape: ShapeData, in context: CGContext, inProgress isBuilding: Bool) {
        guard let points = shape.points?.map(pageToView), let first = points.first
        else { return }

        context.move(to: first)
        for point in points.dropFirst() { context.addLine(to: point) }

        let closed = shape.tool == .area || shape.tool == .polygon || shape.tool == .cloud
        if closed && points.count > 2 { context.closePath() }

        if shape.opacity > 0 && (shape.tool == .area || shape.tool == .polygon) {
            context.drawPath(using: .fillStroke)
        } else {
            context.strokePath()
        }

        // While building, show where the taps landed and mark the vertex that
        // closes the shape — otherwise the gesture is undiscoverable.
        guard isBuilding else { return }
        let colour = UIColor(hex: shape.color) ?? .systemRed
        for (index, point) in points.enumerated() {
            let isEnd = index == 0 || index == points.count - 1
            context.setFillColor(colour.withAlphaComponent(isEnd ? 1 : 0.55).cgColor)
            context.fillEllipse(in: CGRect(
                x: point.x - vertexDot, y: point.y - vertexDot,
                width: vertexDot * 2, height: vertexDot * 2))
        }
    }

    private func drawArrowHead(from: CGPoint, to: CGPoint, in context: CGContext) {
        let dx = to.x - from.x
        let dy = to.y - from.y
        let length = hypot(dx, dy)
        guard length > 0 else { return }

        let ux = dx / length
        let uy = dy / length
        let size = context.lineWidth * 4
        let base = CGPoint(x: to.x - ux * size, y: to.y - uy * size)
        let offset = CGPoint(x: -uy * size * 0.5, y: ux * size * 0.5)

        context.move(to: to)
        context.addLine(to: CGPoint(x: base.x + offset.x, y: base.y + offset.y))
        context.addLine(to: CGPoint(x: base.x - offset.x, y: base.y - offset.y))
        context.closePath()
        context.fillPath()
    }
}

extension UIColor {
    /// Parses `#RRGGBB`, falling back to nil rather than throwing on markup
    /// with a colour this client does not recognise.
    convenience init?(hex: String) {
        var value = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.hasPrefix("#") { value.removeFirst() }
        guard value.count == 6, let rgb = UInt32(value, radix: 16) else { return nil }
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1)
    }

    var hexString: String {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        return String(format: "#%02X%02X%02X",
                      Int(red * 255), Int(green * 255), Int(blue * 255))
    }
}
#endif
