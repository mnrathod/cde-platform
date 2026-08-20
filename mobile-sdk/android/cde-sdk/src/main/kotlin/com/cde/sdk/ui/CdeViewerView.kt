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
import com.cde.sdk.markup.MarkupEngine
import com.cde.sdk.model.MarkupTool
import com.cde.sdk.model.Point
import com.cde.sdk.model.ShapeData
import com.cde.sdk.render.PdfPageRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
 */
class CdeViewerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    private val pageView = PageCanvasView(context)
    private val overlay = MarkupOverlayView(context)
    private val scope = CoroutineScope(Dispatchers.Main)

    private var renderer: PdfPageRenderer? = null
    private var renderJob: Job? = null
    private var pageIndex = 0

    /** Pan and zoom, as a transform from page space to view space. */
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    /** Scale at which the current bitmap was rasterised. */
    private var rasterisedAt = 0f
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

    /** True while a click-built shape is waiting to be closed. */
    val hasUnfinishedShape: Boolean get() = overlay.hasUnfinishedShape

    init {
        addView(pageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setBackgroundColor(Color.parseColor("#1A1D27"))

        overlay.pageToView = { Point(
            (it.x * scale + offsetX), (it.y * scale + offsetY)) }
        overlay.viewToPage = { Point(
            ((it.x - offsetX) / scale), ((it.y - offsetY) / scale)) }
    }

    // ── Loading ──────────────────────────────────────────────────

    /**
     * Shows a document.
     *
     * @param shapes markup already recorded against it, drawn immediately so a
     *        reader sees existing comments without waiting for a round trip
     */
    fun show(document: OpenedDocument, shapes: List<ShapeData> = emptyList()) {
        overlay.shapes = shapes
        renderer?.close()
        renderer = null

        when (document) {
            is OpenedDocument.Pdf -> {
                val file = document.file ?: return
                renderer = PdfPageRenderer.open(file)
                pageIndex = 0
                loadPage(0, resetView = true)
            }
            // A drawing arrives as SVG. Rendering it is the host's job — see
            // CdeDrawingView — because an SVG needs a very different surface
            // from a rasterised page, and pretending otherwise would give both
            // a worse one.
            else -> Unit
        }
    }

    fun showPage(index: Int) {
        val total = renderer?.pageCount ?: return
        if (index !in 0 until total) return
        pageIndex = index
        overlay.pageNumber = index + 1
        loadPage(index, resetView = true)
    }

    val pageCount: Int get() = renderer?.pageCount ?: 0

    private fun loadPage(index: Int, resetView: Boolean) {
        val active = renderer ?: return
        renderJob?.cancel()

        val size = active.pageSize(index)
        pageWidth = size.width
        pageHeight = size.height

        if (resetView) post { fitToWidth() }

        renderJob = scope.launch {
            val target = renderScale()
            val bitmap = active.renderPage(index, target)
            rasterisedAt = target
            pageView.bitmap = bitmap
            overlay.pageNumber = index + 1
            onPageChanged?.invoke(index + 1, active.pageCount)
            invalidateTransform()
        }
    }

    /**
     * Rasterise at the zoom actually being viewed, capped.
     *
     * Rendering at 1x and letting the bitmap stretch is what makes a PDF look
     * soft when zoomed; rendering at arbitrary zoom is what makes it run out
     * of memory. The cap is the compromise, and the bitmap is stretched only
     * beyond it.
     */
    private fun renderScale(): Float =
        (scale * resources.displayMetrics.density).coerceIn(1f, MAX_RENDER_SCALE)

    // ── Gestures ─────────────────────────────────────────────────

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val previous = scale
                scale = (scale * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)

                // Keep the point under the fingers still. Without this the
                // page slides away from the pinch and feels unanchored.
                val focusX = detector.focusX
                val focusY = detector.focusY
                offsetX = focusX - (focusX - offsetX) * (scale / previous)
                offsetY = focusY - (focusY - offsetY) * (scale / previous)

                invalidateTransform()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // Re-rasterise once the pinch settles rather than on every
                // frame of it.
                if (kotlin.math.abs(renderScale() - rasterisedAt) > 0.1f) loadPage(pageIndex, false)
            }
        })

    private val panDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float,
            ): Boolean {
                offsetX -= distanceX
                offsetY -= distanceY
                invalidateTransform()
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
        if (pageWidth <= 0f || width == 0) return
        scale = width / pageWidth
        offsetX = 0f
        offsetY = 0f
        invalidateTransform()
    }

    private fun invalidateTransform() {
        overlay.zoom = scale
        pageView.setTransform(scale / rasterisedAtOrOne(), offsetX, offsetY)
        overlay.invalidate()
    }

    private fun rasterisedAtOrOne(): Float =
        if (rasterisedAt > 0f) rasterisedAt / resources.displayMetrics.density else 1f

    override fun onDetachedFromWindow() {
        renderJob?.cancel()
        renderer?.close()
        renderer = null
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
    }
}

/** Draws the rasterised page. Split out so markup can redraw without it. */
private class PageCanvasView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(value) { field = value; invalidate() }

    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun setTransform(scale: Float, offsetX: Float, offsetY: Float) {
        matrix.setScale(scale, scale)
        matrix.postTranslate(offsetX, offsetY)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        bitmap?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, matrix, paint) }
    }
}
