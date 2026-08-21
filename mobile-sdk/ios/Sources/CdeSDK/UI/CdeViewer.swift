#if canImport(SwiftUI) && canImport(UIKit)
import SwiftUI

/// SwiftUI wrapper around ``CdeViewerController``.
///
/// ```swift
/// CdeViewer(document: opened, shapes: shapes, tool: $tool) { shape in
///     Task { try await cde.addAnnotation(documentId: id, shape: shape) }
/// }
/// ```
///
/// The controller does the work; this exists so a SwiftUI host does not have
/// to wire `UIViewControllerRepresentable` itself for the common case.
@available(iOS 14.0, *)
public struct CdeViewer: UIViewControllerRepresentable {

    private let document: OpenedDocument
    private let shapes: [ShapeData]
    @Binding private var tool: MarkupTool
    private let onShapeCompleted: (ShapeData) -> Void
    private let onPageChanged: ((Int, Int) -> Void)?

    public init(
        document: OpenedDocument,
        shapes: [ShapeData] = [],
        tool: Binding<MarkupTool>,
        onPageChanged: ((Int, Int) -> Void)? = nil,
        onShapeCompleted: @escaping (ShapeData) -> Void
    ) {
        self.document = document
        self.shapes = shapes
        self._tool = tool
        self.onPageChanged = onPageChanged
        self.onShapeCompleted = onShapeCompleted
    }

    public func makeUIViewController(context: Context) -> CdeViewerController {
        let controller = CdeViewerController()
        controller.loadViewIfNeeded()
        controller.onShapeCompleted = onShapeCompleted
        controller.onPageChanged = onPageChanged
        controller.show(document, shapes: shapes)
        controller.activeTool = tool
        return controller
    }

    public func updateUIViewController(_ controller: CdeViewerController, context: Context) {
        // Only the parts that can change from the outside. Re-showing the
        // document on every update would reset the reader's scroll position
        // every time an unrelated piece of state moved.
        //
        // The callbacks are re-assigned rather than left as wired in `make`:
        // SwiftUI rebuilds the struct on every state change, and the closure
        // captured at creation keeps the values it closed over then. Leaving
        // it would send new markup to a callback holding stale state.
        controller.onShapeCompleted = onShapeCompleted
        controller.onPageChanged = onPageChanged
        if controller.activeTool != tool { controller.activeTool = tool }
        controller.setShapes(shapes)
    }
}
#endif
