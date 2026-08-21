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
/// standard people expect.
///
/// Markup is drawn into a **per-page overlay** supplied through
/// ``PDFKit/PDFPageOverlayViewProvider``. PDFKit owns each overlay's placement,
/// which is what allows continuous scrolling: every page carries its own
/// markup, so nothing has to guess which page a shape belongs to from the
/// scroll position. A single overlay across the whole view cannot do this — it
/// would have to map every shape through whichever page happened to be
/// "current", and quietly draw the wrong page's markup during a scroll.
public final class CdeViewerController: UIViewController {

    private let pdfView = PDFView()
    private let drawingView = WKWebView()
    private let statusLabel = UILabel()

    /// The overlay for a converted drawing, which has no PDF pages to hang
    /// overlays from. PDFs never use this one.
    private let drawingOverlay = MarkupOverlayView(frame: .zero)

    /// Live page overlays, keyed by page identity.
    ///
    /// Keyed by `ObjectIdentifier` rather than the page itself so this map
    /// never keeps a `PDFPage` alive; entries are dropped as PDFKit stops
    /// displaying them, which is what keeps a thousand-page document from
    /// accumulating a thousand views.
    private var pageOverlays: [ObjectIdentifier: MarkupOverlayView] = [:]

    /// Every shape for the document, handed to each page overlay, which draws
    /// only its own page's.
    private var shapes: [ShapeData] = []

    public var activeTool: MarkupTool = .pan {
        didSet { liveOverlays.forEach { $0.activeTool = activeTool } }
    }

    /// Stroke colour for new markup.
    public var strokeColor: UIColor = UIColor(red: 1, green: 0.23, blue: 0.19, alpha: 1) {
        didSet { liveOverlays.forEach { $0.strokeColor = strokeColor } }
    }

    public var onShapeCompleted: ((ShapeData) -> Void)?

    /// Fires while a shape is being drawn, for a live measurement readout.
    public var onShapeChanged: ((ShapeData?) -> Void)?

    public var onPageChanged: ((Int, Int) -> Void)?

    /// True while a click-built shape is waiting to be closed, on any page.
    public var hasUnfinishedShape: Bool { liveOverlays.contains { $0.hasUnfinishedShape } }

    private var liveOverlays: [MarkupOverlayView] {
        Array(pageOverlays.values) + [drawingOverlay]
    }

    // MARK: - Lifecycle

    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.10, green: 0.11, blue: 0.15, alpha: 1)

        pdfView.autoScales = true
        pdfView.displayMode = .singlePageContinuous
        pdfView.displayDirection = .vertical
        pdfView.backgroundColor = view.backgroundColor ?? .black
        pdfView.pageOverlayViewProvider = self
        // Without this the overlays are laid out but never hit-tested, so
        // markup draws and touches do nothing — the failure looks like a
        // broken gesture recogniser rather than a missing flag.
        pdfView.isInMarkupMode = true
        add(pdfView)

        drawingView.isOpaque = false
        drawingView.backgroundColor = .clear
        drawingView.scrollView.maximumZoomScale = 8
        drawingView.isHidden = true
        add(drawingView)

        drawingOverlay.isHidden = true
        configure(drawingOverlay, pageNumber: 1)
        add(drawingOverlay)

        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 0
        statusLabel.textColor = .secondaryLabel
        statusLabel.font = .preferredFont(forTextStyle: .callout)
        statusLabel.isHidden = true
        add(statusLabel)

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
        self.shapes = shapes
        // Overlays belong to the outgoing document's pages. Keeping them would
        // show the previous document's markup over the new one.
        pageOverlays.removeAll()

        statusLabel.isHidden = true
        drawingView.isHidden = true
        drawingOverlay.isHidden = true
        pdfView.isHidden = false

        switch document {
        case .pdf(_, let fileURL):
            guard let fileURL, let pdf = PDFDocument(url: fileURL) else {
                report("This document could not be opened.")
                return
            }
            pdfView.document = pdf
            onPageChanged?(1, pdf.pageCount)

        case .drawing(let source):
            // A converted drawing is SVG. WKWebView renders it with the
            // system's own vector engine — the same one Safari uses — rather
            // than the SDK reimplementing SVG. Markup sits in a single overlay
            // above it: there are no PDF pages here to hang one from.
            pdfView.isHidden = true
            pdfView.document = nil
            drawingView.isHidden = false
            drawingOverlay.isHidden = false
            drawingOverlay.shapes = shapes
            drawingView.loadHTMLString(wrap(svg: source.svg), baseURL: nil)
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
        drawingOverlay.isHidden = true
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

    // MARK: - Overlays

    /// Prepares an overlay: appearance, callbacks, and which page it draws.
    private func configure(_ overlay: MarkupOverlayView, pageNumber: Int) {
        overlay.pageNumber = pageNumber
        overlay.activeTool = activeTool
        overlay.strokeColor = strokeColor
        overlay.shapes = shapes
        overlay.onShapeCompleted = { [weak self] shape in
            guard let self else { return }
            // Held here as well as sent on, so a page scrolled away and back
            // redraws the shape instead of losing it until the next fetch.
            self.shapes.append(shape)
            self.onShapeCompleted?(shape)
        }
        overlay.onShapeChanged = { [weak self] shape in self?.onShapeChanged?(shape) }
    }

    /// Ties an overlay to one page's coordinate space.
    ///
    /// Two conversions, both documented API. `PDFView.convert(_:from:)` gives
    /// the point in the PDF view; `UIView.convert(_:from:)` then takes it into
    /// the overlay, whatever frame and transform PDFKit has given it. Going
    /// through the view hierarchy this way means the mapping does not depend on
    /// what PDFKit does with the overlay — which Apple does not document.
    private func wireCoordinates(
        _ overlay: MarkupOverlayView, to page: PDFPage
    ) {
        overlay.pageToView = { [weak self, weak overlay, weak page] point in
            guard let self, let overlay, let page else {
                return CGPoint(x: point.x, y: point.y)
            }
            // Shape data is y-down from the top left; a PDF page is y-up from
            // the bottom left. The flip happens here, once, and is the reason
            // markup drawn on a phone lands where the browser puts it.
            let height = page.bounds(for: .mediaBox).height
            let onPage = CGPoint(x: point.x, y: height - point.y)
            return overlay.convert(self.pdfView.convert(onPage, from: page), from: self.pdfView)
        }

        overlay.viewToPage = { [weak self, weak overlay, weak page] location in
            guard let self, let overlay, let page else {
                return CdePoint(x: location.x, y: location.y)
            }
            let inPDFView = overlay.convert(location, to: self.pdfView)
            let onPage = self.pdfView.convert(inPDFView, to: page)
            let height = page.bounds(for: .mediaBox).height
            return CdePoint(x: Double(onPage.x), y: Double(height - onPage.y))
        }
    }

    @objc private func pageChanged() {
        guard let page = pdfView.currentPage, let document = pdfView.document else { return }
        onPageChanged?(document.index(for: page) + 1, document.pageCount)
    }

    /// Redraws markup at the new zoom.
    ///
    /// Nothing here recomputes a scale — each overlay measures its own. This
    /// only asks for a repaint, so strokes are re-rendered at the size they
    /// are now being viewed at instead of being magnified as an image.
    @objc private func scaleChanged() {
        liveOverlays.forEach { $0.setNeedsDisplay() }
    }

    // MARK: - Controls

    /// Completes a shape being built by taps. Wire to a Done control.
    public func finishShape() {
        liveOverlays.first { $0.hasUnfinishedShape }?.finish()
    }

    /// Abandons any shape being built.
    public func cancelShape() {
        liveOverlays.forEach { $0.cancelInProgress() }
    }

    public func setShapes(_ shapes: [ShapeData]) {
        self.shapes = shapes
        liveOverlays.forEach { $0.shapes = shapes }
    }

    public func goToPage(_ index: Int) {
        guard let document = pdfView.document, let page = document.page(at: index) else { return }
        pdfView.go(to: page)
    }

    deinit { NotificationCenter.default.removeObserver(self) }
}

/// Supplies each page its own markup overlay.
///
/// `@preconcurrency`: PDFKit's protocol is an Objective-C one, and whether the
/// SDK in use isolates it to the main actor varies by version. The attribute
/// keeps this compiling either way — PDFKit only ever calls these on the main
/// thread. Drop it once the deployed SDK annotates the protocol.
extension CdeViewerController: @preconcurrency PDFPageOverlayViewProvider {

    public func pdfView(_ view: PDFView, overlayViewFor page: PDFPage) -> UIView? {
        let key = ObjectIdentifier(page)
        if let existing = pageOverlays[key] { return existing }

        let overlay = MarkupOverlayView(frame: .zero)
        let pageNumber = (view.document?.index(for: page) ?? 0) + 1
        configure(overlay, pageNumber: pageNumber)
        wireCoordinates(overlay, to: page)
        pageOverlays[key] = overlay
        return overlay
    }

    public func pdfView(
        _ pdfView: PDFView, willEndDisplayingOverlayView overlayView: UIView, for page: PDFPage
    ) {
        // A shape half-built on a page being scrolled away has no home to
        // return to; dropping it is better than committing something the user
        // stopped drawing.
        (overlayView as? MarkupOverlayView)?.cancelInProgress()
        pageOverlays.removeValue(forKey: ObjectIdentifier(page))
    }
}
#endif
