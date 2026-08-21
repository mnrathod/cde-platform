package com.cde.sdk.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import com.cde.sdk.OpenedDocument
import com.cde.sdk.model.MarkupTool
import com.cde.sdk.model.Point
import com.cde.sdk.model.ShapeData
import com.cde.sdk.render.PdfPageRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The drop-in viewer: a document, pan and zoom, and markup over it.
 *
 * ```kotlin
 * viewer.show(cde.open(documentId), existingShapes)
 * viewer.activeTool = MarkupTool.AREA
 * viewer.onShapeCompleted = { scope.launch { cde.addAnnotation(documentId, it) } }
 * ```
 *
 * The page is rasterised by the platform's PDF renderer and drawn as a bitmap;
 * markup lives in a separate overlay on top. Keeping them apart is what lets
 * markup redraw at 60fps while a page is only re-rasterised when the zoom
 * settles — re-rendering a PDF page on every frame of a pinch is the usual
 * cause of a viewer that feels heavy.
 *
 * All three coordinate systems involved live in [ViewportTransform], which is
 * plain arithmetic and covered by tests that need no device.
 */
class CdeViewerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    private val pageView = PageCanvasView(context)
    private val overlay = MarkupOverlayView(context)

    // SupervisorJob: a page that fails to render must not cancel the scope and
    // leave the viewer permanently unable to render anything else.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var renderer: PdfPageRenderer? = null
    private var renderJob: Job? = null
    private var pageIndex = 0

    /** Kept so the view can reopen the document if it is reattached. */
    private var openFile: File? = null

    private var transform = ViewportTransform()
    private var pageWidth = 0f
    private var pageHeight = 0f

    var activeTool: MarkupTool
        get() = overlay.activeTool
        set(value) { overlay.activeTool = value }

    var onShapeCompleted: ((ShapeData) -> Unit)?
        get() = overlay.onShapeCompleted
        set(value) { overlay.onShapeCompleted = value }

    /** Fires while a shape is being drawn, for a live measurement readout. */
    var onShapeChanged: ((ShapeData?) -> Unit)?
        get() = overlay.onShapeChanged
        set(value) { overlay.onShapeChanged = value }

    var onPageChanged: ((Int, Int) -> Unit)? = null

    /**
     * Reports a document that could not be opened or rendered.
     *
     * Without this the failure is silent: a blank grey view, and no way for
     * the host to tell that apart from a page still loading.
     */
    var onError: ((Throwable) -> Unit)? = null

    /** True while a click-built shape is waiting to be closed. */
    val hasUnfinishedShape: Boolean get() = overlay.hasUnfinishedShape

    val pageCount: Int get() = renderer?.pageCount ?: 0

    init {
        addView(pageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setBackgroundColor(BACKGROUND)

        overlay.pageToView = { transform.pageToView(it) }
        overlay.viewToPage = { transform.viewToPage(it) }
    }

    // ── Loading ──────────────────────────────────────────────────

    /**
     * Shows a document.
     *
     * @param shapes markup already recorded against it, drawn immediately so a
     *        reader sees existing comments without waiting for a round trip
     */
    fun show(document: OpenedDocument, shapes: List<ShapeData> = emptyList()) {
        // Cancel before closing: a render still in flight against the outgoing
        // renderer throws once the document underneath it is closed.
        renderJob?.cancel()
        renderer?.close()
        renderer = null
        openFile = null

        // The previous document's page would otherwise stay on screen beneath
        // the new document's markup.
        pageView.bitmap = null
        pageIndex = 0
        overlay.pageNumber = 1
        overlay.shapes = shapes
        transform = ViewportTransform()

        when (document) {
            is OpenedDocument.Pdf -> {
                val file = document.file ?: return
                openFile = file
                open(file)
            }
            // A drawing arrives as SVG. Rendering it is the host's job — see
            // CdeDrawingView — because an SVG needs a very different surface
            // from a rasterised page, and pretending otherwise would give both
            // a worse one.
            else -> Unit
        }
    }

    private fun open(file: File) {
        val opened = runCatching { PdfPageRenderer.open(file) }
            .onFailure { onError?.invoke(it) }
            .getOrNull() ?: return
        renderer = opened
        loadPage(0, resetView = true)
    }

    fun showPage(index: Int) {
        val total = renderer?.pageCount ?: return
        if (index !in 0 until total) return
        loadPage(index, resetView = true)
    }

    /**
     * Measures and rasterises a page.
     *
     * Both steps happen off the main thread. `pageSize` opens a page in the
     * native renderer and takes the same lock a render holds, so calling it
     * inline would block the main thread behind whatever is currently being
     * rasterised — an ANR on exactly the large drawings that take longest.
     */
    private fun loadPage(index: Int, resetView: Boolean) {
        val active = renderer ?: return
        renderJob?.cancel()

        renderJob = scope.launch {
            try {
                val size = withContext(Dispatchers.IO) { active.pageSize(index) }
                pageWidth = size.width
                pageHeight = size.height
                pageIndex = index

                if (resetView || transform.scale == 0f) {
                    transform = transform.fitToWidth(width, pageWidth)
                }

                val target = transform.renderScale(MAX_RENDER_SCALE)
                val bitmap = active.renderPage(index, target)

                transform = transform.copy(rasterisedAt = target)
                pageView.bitmap = bitmap
                overlay.pageNumber = index + 1
                onPageChanged?.invoke(index + 1, active.pageCount)
                applyTransform()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // A corrupt page must not take the host app down with it.
                onError?.invoke(failure)
            }
        }
    }

    // ── Gestures ─────────────────────────────────────────────────

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                transform = transform.scaledBy(
                    detector.scaleFactor, detector.focusX, detector.focusY,
                    MIN_ZOOM, MAX_ZOOM)
                applyTransform()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // Re-rasterise once the pinch settles rather than on every
                // frame of it.
                val wanted = transform.renderScale(MAX_RENDER_SCALE)
                if (kotlin.math.abs(wanted - transform.rasterisedAt) > RERENDER_THRESHOLD) {
                    loadPage(pageIndex, resetView = false)
                }
            }
        })

    private val panDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float,
            ): Boolean {
                transform = transform.pannedBy(-distanceX, -distanceY)
                applyTransform()
                return true
            }
        })

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        // A second finger always means pan or zoom, whatever tool is selected,
        // so a two-finger gesture is never swallowed by drawing.
        return event.pointerCount > 1 ||
            overlay.activeTool == MarkupTool.PAN ||
            overlay.activeTool == MarkupTool.SELECT
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) panDetector.onTouchEvent(event)
        return true
    }

    // ── Layout ───────────────────────────────────────────────────

    fun fitToWidth() {
        transform = transform.fitToWidth(width, pageWidth)
        applyTransform()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The first layout is usually where the view finally has a width, so
        // a document shown before then has nothing to fit to.
        if (transform.scale == 1f && pageWidth > 0f) fitToWidth()
    }

    private fun applyTransform() {
        overlay.zoom = transform.scale
        pageView.setTransform(transform.bitmapScale, transform.offsetX, transform.offsetY)
        overlay.invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Reopened rather than kept open across a detach: a file descriptor
        // held by an off-screen view is a leak, but losing the document on a
        // recycle would be a bug the host cannot work around.
        val file = openFile
        if (renderer == null && file != null) open(file)
    }

    override fun onDetachedFromWindow() {
        renderJob?.cancel()
        renderer?.close()
        renderer = null
        pageView.bitmap = null
        super.onDetachedFromWindow()
    }

    /** Completes a shape being built by taps. Wire to a Done control. */
    fun finishShape() = overlay.finish()

    /** Abandons a shape being built. */
    fun cancelShape() = overlay.cancelInProgress()

    fun setShapes(shapes: List<ShapeData>) { overlay.shapes = shapes }

    private companion object {
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 8f
        /** Beyond this the bitmap is stretched rather than re-rasterised. */
        const val MAX_RENDER_SCALE = 4f
        /** Below this a re-render would not be visible but would still cost. */
        const val RERENDER_THRESHOLD = 0.1f
        const val BACKGROUND = 0xFF1A1D27.toInt()
    }
}

/** Draws the rasterised page. Split out so markup can redraw without it. */
private class PageCanvasView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(value) { field = value; invalidate() }

    private val pageMatrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun setTransform(scale: Float, offsetX: Float, offsetY: Float) {
        pageMatrix.setScale(scale, scale)
        pageMatrix.postTranslate(offsetX, offsetY)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        bitmap?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, pageMatrix, paint) }
    }
}
