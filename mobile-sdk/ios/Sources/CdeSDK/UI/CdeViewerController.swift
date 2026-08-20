#if canImport(UIKit)
import PDFKit
import UIKit
import WebKit

/// The drop-in viewer: a document, pan and zoom, and markup over it.
///
/// ```swift
/// let viewer = CdeViewerController()
/// viewer.show(try await cde.open(documentId: id), shapes: existing)
/// viewer.activeTool = .area
/// viewer.onShapeCompleted = { shape in
///     Task { try await cde.addAnnotation(documentId: id, shape: shape) }
/// }
/// ```
///
/// PDFs are rendered by `PDFKit`, not by a bundled engine. `PDFView` is the
/// same component Files and Mail use: tiled, Metal-backed, and it already
/// handles scrolling, pinch-zoom, page layout and text selection to the
/// standard people expect. Reimplementing that on a canvas would be slower and
/// would still feel foreign.
///
/// Markup lives in an overlay pinned to the page, kept in page coordinates, so
/// PDFKit owns navigation and the SDK owns only what it adds.
public final class CdeViewerController: UIViewController {

    private let pdfView = PDFView()
    private let overlay = MarkupOverlayView(frame: .zero)
    private let drawingView = WKWebView()
    private let statusLabel = UILabel()

    private var currentPage: PDFPage?

    public var activeTool: MarkupTool {
        get { overlay.activeTool }
        set {
            overlay.activeTool = newValue
            // PDFKit's own gestures must yield while a drawing tool is in
            // hand, or a stroke turns into a scroll.
            let drawing = newValue != .pan && newValue != .select
            pdfView.isUserInteractionEnabled = !drawing
        }
    }

    public var onShapeCompleted: ((ShapeData) -> Void)? {
        get { overlay.onShapeCompleted }
        set { overlay.onShapeCompleted = newValue }
    }

    /// Fires while a shape is being drawn, for a live measurement readout.
    public var onShapeChanged: ((ShapeData?) -> Void)? {
        get { overlay.onShapeChanged }
        set { overlay.onShapeChanged = newValue }
    }

    public var onPageChanged: ((Int, Int) -> Void)?

    /// True while a click-built shape is waiting to be closed.
    public var hasUnfinishedShape: Bool { overlay.hasUnfinishedShape }

    // MARK: - Lifecycle

    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.10, green: 0.11, blue: 0.15, alpha: 1)

        pdfView.autoScales = true
        pdfView.displayMode = .singlePageContinuous
        pdfView.displayDirection = .vertical
        pdfView.backgroundColor = view.backgroundColor ?? .black
        add(pdfView)

        drawingView.isOpaque = false
        drawingView.backgroundColor = .clear
        drawingView.scrollView.maximumZoomScale = 8
        drawingView.isHidden = true
        add(drawingView)

        overlay.frame = view.bounds
        overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(overlay)

        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 0
        statusLabel.textColor = .secondaryLabel
        statusLabel.font = .preferredFont(forTextStyle: .callout)
        statusLabel.isHidden = true
        add(statusLabel)

        wireCoordinateMapping()

        NotificationCenter.default.addObserver(
            self, selector: #selector(pageChanged),
            name: .PDFViewPageChanged, object: pdfView)
        NotificationCenter.default.addObserver(
            self, selector: #selector(scaleChanged),
            name: .PDFViewScaleChanged, object: pdfView)
    }

    private func add(_ subview: UIView) {
        subview.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(subview)
        NSLayoutConstraint.activate([
            subview.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            subview.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            subview.topAnchor.constraint(equalTo: view.topAnchor),
            subview.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
    }

    // MARK: - Showing a document

    /// Shows a document.
    ///
    /// - Parameter shapes: markup already recorded against it, drawn
    ///   immediately so a reader sees existing comments without waiting for a
    ///   round trip.
    public func show(_ document: OpenedDocument, shapes: [ShapeData] = []) {
        overlay.shapes = shapes
        statusLabel.isHidden = true
        drawingView.isHidden = true
        pdfView.isHidden = false

        switch document {
        case .pdf(_, let fileURL):
            guard let fileURL, let pdf = PDFDocument(url: fileURL) else {
                report("This document could not be opened.")
                return
            }
            pdfView.document = pdf
            currentPage = pdf.page(at: 0)
            overlay.pageNumber = 1
            onPageChanged?(1, pdf.pageCount)
            overlay.setNeedsDisplay()

        case .drawing(let source):
            // A converted drawing is SVG. WKWebView renders it with the
            // system's own vector engine — the same one Safari uses — rather
            // than the SDK reimplementing SVG. Markup still sits in the
            // overlay above it, in the drawing's own coordinates.
            pdfView.isHidden = true
            drawingView.isHidden = false
            drawingView.loadHTMLString(wrap(svg: source.svg), baseURL: nil)
            overlay.pageNumber = 1
            onPageChanged?(1, 1)

        case .image:
            report("Image documents are shown by the host application.")

        case .unsupported(let source):
            report("There is no viewer for \(source.fileName ?? source.type) yet.")
        }
    }

    private func report(_ message: String) {
        pdfView.isHidden = true
        drawingView.isHidden = true
        statusLabel.text = message
        statusLabel.isHidden = false
    }

    /// Sizes the SVG to the viewport and stops the page bouncing, so the
    /// drawing behaves like a document rather than a web page.
    private func wrap(svg: String) -> String {
        """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1,
              maximum-scale=8, user-scalable=yes">
        <style>
          html,body { margin:0; padding:0; background:#1A1D27;
                      height:100%; overscroll-behavior:none; }
          svg { display:block; width:100%; height:auto; }
        </style></head><body>\(svg)</body></html>
        """
    }

    // MARK: - Coordinate mapping

    /// Ties the overlay to the page's coordinate space.
    ///
    /// PDFKit gives an exact conversion between a page and the view, so the
    /// overlay never has to track pan and zoom itself — which is what keeps
    /// markup pinned to the drawing rather than drifting during a pinch.
    private func wireCoordinateMapping() {
        overlay.pageToView = { [weak self] point in
            guard let self, let page = self.currentPage else {
                return CGPoint(x: point.x, y: point.y)
            }
            // PDF pages are y-up from the bottom left; UIKit is y-down from
            // the top left. Flipping here, once, is why every shape lands in
            // the same place as it does in the browser.
            let bounds = page.bounds(for: .mediaBox)
            let pagePoint = CGPoint(x: point.x, y: bounds.height - point.y)
            return self.pdfView.convert(pagePoint, from: page)
        }

        overlay.viewToPage = { [weak self] location in
            guard let self, let page = self.currentPage else {
                return CdePoint(x: location.x, y: location.y)
            }
            let bounds = page.bounds(for: .mediaBox)
            let pagePoint = self.pdfView.convert(location, to: page)
            return CdePoint(x: Double(pagePoint.x),
                            y: Double(bounds.height - pagePoint.y))
        }
    }

    @objc private func pageChanged() {
        guard let page = pdfView.currentPage, let document = pdfView.document else { return }
        currentPage = page
        let index = document.index(for: page)
        overlay.pageNumber = index + 1
        // Markup belongs to a page; showing another page's shapes over this
        // one would be worse than showing none.
        overlay.setNeedsDisplay()
        onPageChanged?(index + 1, document.pageCount)
    }

    @objc private func scaleChanged() {
        overlay.zoom = pdfView.scaleFactor
        overlay.setNeedsDisplay()
    }

    // MARK: - Controls

    /// Completes a shape being built by taps. Wire to a Done control.
    public func finishShape() { overlay.finish() }

    /// Abandons a shape being built.
    public func cancelShape() { overlay.cancelInProgress() }

    public func setShapes(_ shapes: [ShapeData]) { overlay.shapes = shapes }

    public func goToPage(_ index: Int) {
        guard let document = pdfView.document, let page = document.page(at: index) else { return }
        pdfView.go(to: page)
    }

    deinit { NotificationCenter.default.removeObserver(self) }
}
#endif
