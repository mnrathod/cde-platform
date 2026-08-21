package com.cde.sdk.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.cde.sdk.markup.MarkupEngine
import com.cde.sdk.model.MarkupTool
import com.cde.sdk.model.Point
import com.cde.sdk.model.ShapeData
import kotlin.math.hypot

/**
 * Draws markup over a page, and turns touches into shapes.
 *
 * Coordinates are kept in the **page's** space, not the view's, and converted
 * on the way in and out. Storing view coordinates would mean every shape moved
 * when the user zoomed, and markup saved on a phone would not line up in a
 * browser — the shape data has to mean the same thing to both.
 *
 * Rendering is a plain `onDraw` against the platform canvas, which is
 * hardware-accelerated. Markup is tens of shapes, not thousands, so there is
 * nothing here that justifies a second rendering stack.
 */
class MarkupOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** Committed markup for the visible page. */
    var shapes: List<ShapeData> = emptyList()
        set(value) { field = value; invalidate() }

    var activeTool: MarkupTool = MarkupTool.PAN
        set(value) {
            field = value
            if (!MarkupEngine.isVertexTool(value)) cancelInProgress()
            invalidate()
        }

    var strokeColor: Int = Color.RED
    var strokeWidthPx: Float = 4f
    var fillOpacity: Float = 0.15f
    var pageNumber: Int = 1

    /** Maps page space to view space. Set by the host as it pans and zooms. */
    var pageToView: (Point) -> Point = { it }
    var viewToPage: (Point) -> Point = { it }
    /** Current zoom, so hit tolerances stay a constant size on screen. */
    var zoom: Float = 1f

    /** Called when a shape is completed and should be recorded. */
    var onShapeCompleted: ((ShapeData) -> Unit)? = null
    /** Called whenever the in-progress shape changes, for a live readout. */
    var onShapeChanged: ((ShapeData?) -> Unit)? = null

    private var inProgress: ShapeData? = null
    private var dragging = false

    /** True while a shape is being built by taps, so the host can offer Done. */
    val hasUnfinishedShape: Boolean get() = MarkupEngine.canFinish(inProgress)

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val vertexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // ── Touch ────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Pan and Select belong to the host's gesture detector; passing them
        // through here is what lets a two-finger pan work while a tool is
        // selected without the overlay swallowing it.
        if (activeTool == MarkupTool.PAN || activeTool == MarkupTool.SELECT) return false
        if (event.pointerCount > 1) { cancelInProgress(); return false }

        val at = viewToPage(Point(event.x.toDouble(), event.y.toDouble()))

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (MarkupEngine.isVertexTool(activeTool)) handleVertexTap(at)
                else beginDrag(at)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    inProgress = inProgress?.let { MarkupEngine.updateShape(it, at) }
                    notifyChanged()
                    invalidate()
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) { dragging = false; commitIfWorthwhile() }
                true
            }

            else -> false
        }
    }

    private fun beginDrag(at: Point) {
        dragging = true
        inProgress = MarkupEngine.startShape(
            tool = activeTool, at = at, pageNumber = pageNumber,
            color = colorToHex(strokeColor),
            strokeWidth = (strokeWidthPx / zoom).toDouble(),
            opacity = fillOpacity.toDouble(),
        )
        notifyChanged()
        invalidate()
    }

    /**
     * A tap while a click-built tool is active.
     *
     * Ending the shape by tapping its first or last vertex, rather than by
     * double-tapping: a double tap on a touch screen fights the platform's own
     * zoom gesture and demands two precise touches in one spot. The [finish]
     * control in the toolbar covers the rest.
     */
    private fun handleVertexTap(at: Point) {
        val current = inProgress
        val tolerance = (VERTEX_TARGET_DP * resources.displayMetrics.density / zoom).toDouble()

        if (current != null && current.tool == activeTool &&
            MarkupEngine.finishesShape(current, at, tolerance)) {
            finish()
            return
        }

        val shape = if (current != null && current.tool == activeTool) {
            MarkupEngine.addVertex(current, at)
        } else {
            MarkupEngine.startShape(
                tool = activeTool, at = at, pageNumber = pageNumber,
                color = colorToHex(strokeColor),
                strokeWidth = (strokeWidthPx / zoom).toDouble(),
                opacity = fillOpacity.toDouble(),
            )
        }

        // Radius and Calibrate are defined by exactly two points and complete
        // themselves, rather than waiting for a gesture the user has no reason
        // to expect.
        val required = MarkupEngine.requiredVertices(activeTool)
        inProgress = shape
        if (required != null && (shape.points?.size ?: 0) >= required) {
            finish()
            return
        }
        notifyChanged()
        invalidate()
    }

    /** Completes the shape being built. Safe to call when there is none. */
    fun finish() {
        val shape = inProgress ?: return
        if (!MarkupEngine.canFinish(shape)) return
        inProgress = null
        dragging = false
        if (MarkupEngine.hasMinimumSize(shape)) onShapeCompleted?.invoke(shape)
        notifyChanged()
        invalidate()
    }

    /** Abandons the shape being built, losing nothing already committed. */
    fun cancelInProgress() {
        if (inProgress == null) return
        inProgress = null
        dragging = false
        notifyChanged()
        invalidate()
    }

    private fun commitIfWorthwhile() {
        val shape = inProgress ?: return
        inProgress = null
        if (MarkupEngine.hasMinimumSize(shape)) onShapeCompleted?.invoke(shape)
        notifyChanged()
        invalidate()
    }

    private fun notifyChanged() = onShapeChanged?.invoke(inProgress)

    // ── Drawing ──────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        // Only this page's markup. Coordinates are page-relative, so another
        // page's shapes would not look obviously wrong — they would land at
        // plausible positions on the wrong drawing, which is worse.
        shapes.forEach { if (it.pageNumber == pageNumber) draw(canvas, it, inProgressShape = false) }
        inProgress?.let { draw(canvas, it, inProgressShape = true) }
    }

    private fun draw(canvas: Canvas, shape: ShapeData, inProgressShape: Boolean) {
        val color = parseColor(shape.color)
        strokePaint.color = color
        strokePaint.strokeWidth = (shape.strokeWidth * zoom).toFloat().coerceAtLeast(1f)
        fillPaint.color = color
        fillPaint.alpha = (shape.opacity.coerceIn(0.0, 1.0) * 255).toInt()

        when (shape.tool) {
            MarkupTool.LINE, MarkupTool.ARROW -> {
                val a = pageToView(Point(shape.x1 ?: 0.0, shape.y1 ?: 0.0))
                val b = pageToView(Point(shape.x2 ?: 0.0, shape.y2 ?: 0.0))
                canvas.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), strokePaint)
                if (shape.tool == MarkupTool.ARROW) drawArrowHead(canvas, a, b)
            }

            MarkupTool.CIRCLE -> {
                val centre = pageToView(Point(shape.cx ?: 0.0, shape.cy ?: 0.0))
                val radius = ((shape.r ?: 0.0) * zoom).toFloat()
                if (fillPaint.alpha > 0)
                    canvas.drawCircle(centre.x.toFloat(), centre.y.toFloat(), radius, fillPaint)
                canvas.drawCircle(centre.x.toFloat(), centre.y.toFloat(), radius, strokePaint)
            }

            MarkupTool.POLYGON, MarkupTool.POLYLINE, MarkupTool.FREEHAND,
            MarkupTool.CLOUD, MarkupTool.AREA, MarkupTool.DIMENSION,
            MarkupTool.RADIUS, MarkupTool.CALIBRATE -> drawPath(canvas, shape, inProgressShape)

            // An ellipse is given a bounding box by the engine, like a rect.
            // Without its own branch it fell through below and drew as a
            // rectangle — the wrong shape, and silently so.
            MarkupTool.ELLIPSE -> {
                val box = boundingBox(shape)
                if (fillPaint.alpha > 0) canvas.drawOval(box, fillPaint)
                canvas.drawOval(box, strokePaint)
            }

            else -> {
                val box = boundingBox(shape)
                if (fillPaint.alpha > 0) canvas.drawRect(box, fillPaint)
                canvas.drawRect(box, strokePaint)
            }
        }
    }

    /**
     * A shape's x/y/width/height in view coordinates.
     *
     * Sorted because the page's y axis runs opposite to the view's: converting
     * both corners can leave the far corner above the origin, and Canvas draws
     * nothing for a rect whose top is below its bottom.
     */
    private fun boundingBox(shape: ShapeData): RectF {
        val origin = pageToView(Point(shape.x ?: 0.0, shape.y ?: 0.0))
        val far = pageToView(Point(
            (shape.x ?: 0.0) + (shape.width ?: 0.0),
            (shape.y ?: 0.0) + (shape.height ?: 0.0)))
        return RectF(
            minOf(origin.x, far.x).toFloat(),
            minOf(origin.y, far.y).toFloat(),
            maxOf(origin.x, far.x).toFloat(),
            maxOf(origin.y, far.y).toFloat(),
        )
    }

    private fun drawPath(canvas: Canvas, shape: ShapeData, inProgressShape: Boolean) {
        val points = shape.points?.map(pageToView) ?: return
        if (points.isEmpty()) return

        val path = Path().apply {
            moveTo(points.first().x.toFloat(), points.first().y.toFloat())
            points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
            val closed = shape.tool == MarkupTool.AREA || shape.tool == MarkupTool.POLYGON ||
                         shape.tool == MarkupTool.CLOUD
            if (closed && points.size > 2) close()
        }
        if (fillPaint.alpha > 0 &&
            (shape.tool == MarkupTool.AREA || shape.tool == MarkupTool.POLYGON)) {
            canvas.drawPath(path, fillPaint)
        }
        canvas.drawPath(path, strokePaint)

        // While building, show where the taps landed and mark the vertex that
        // closes the shape — otherwise the gesture is undiscoverable.
        if (inProgressShape) {
            vertexPaint.color = parseColor(shape.color)
            val radius = VERTEX_DOT_DP * resources.displayMetrics.density
            points.forEachIndexed { index, p ->
                vertexPaint.alpha = if (index == 0 || index == points.lastIndex) 255 else 140
                canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), radius, vertexPaint)
            }
        }
    }

    private fun drawArrowHead(canvas: Canvas, from: Point, to: Point) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = hypot(dx, dy).takeIf { it > 0.0 } ?: return
        val ux = dx / length
        val uy = dy / length
        val size = strokePaint.strokeWidth * 4
        val baseX = to.x - ux * size
        val baseY = to.y - uy * size
        val offsetX = -uy * size * 0.5
        val offsetY = ux * size * 0.5

        val head = Path().apply {
            moveTo(to.x.toFloat(), to.y.toFloat())
            lineTo((baseX + offsetX).toFloat(), (baseY + offsetY).toFloat())
            lineTo((baseX - offsetX).toFloat(), (baseY - offsetY).toFloat())
            close()
        }
        canvas.drawPath(head, Paint(strokePaint).apply { style = Paint.Style.FILL })
    }

    private fun colorToHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    /** Falls back to red rather than throwing on markup with an odd colour. */
    private fun parseColor(value: String): Int =
        runCatching { Color.parseColor(value) }.getOrDefault(Color.RED)

    private companion object {
        /** Finger-sized target for closing a shape, in dp. */
        const val VERTEX_TARGET_DP = 22f
        const val VERTEX_DOT_DP = 4f
    }
}
