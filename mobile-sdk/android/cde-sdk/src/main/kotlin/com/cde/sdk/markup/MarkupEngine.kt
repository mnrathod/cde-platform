package com.cde.sdk.markup

import com.cde.sdk.model.MarkupTool
import com.cde.sdk.model.Point
import com.cde.sdk.model.ShapeData
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The geometry behind drawing markup, and the rules for when a shape is
 * finished.
 *
 * A direct port of the web viewer's engine, kept deliberately in step with it:
 * both clients write the same `shapeData` and the server never validates it,
 * so a divergence here does not fail — it produces markup that reads
 * differently in a browser than on the phone that drew it. The parity tests
 * carry the same vectors as the TypeScript suite for that reason.
 *
 * Pure geometry, no Android types, so it is testable on the JVM without a
 * device.
 */
object MarkupEngine {

    /** Tools built by clicking a series of points rather than by dragging. */
    private val VERTEX_TOOLS = setOf(
        MarkupTool.POLYGON, MarkupTool.POLYLINE, MarkupTool.DIMENSION,
        MarkupTool.AREA, MarkupTool.RADIUS, MarkupTool.CALIBRATE,
    )

    fun isVertexTool(tool: MarkupTool): Boolean = tool in VERTEX_TOOLS

    /**
     * Vertex tools that end on a fixed count rather than on a closing gesture.
     * Radius and Calibrate are defined by exactly two points.
     */
    fun requiredVertices(tool: MarkupTool): Int? = when (tool) {
        MarkupTool.RADIUS, MarkupTool.CALIBRATE -> 2
        else -> null
    }

    /** The fewest vertices a shape needs before it can be closed at all. */
    fun minimumVertices(tool: MarkupTool): Int =
        if (tool == MarkupTool.AREA || tool == MarkupTool.POLYGON) 3 else 2

    /** True when a click-built shape holds enough vertices to be committed. */
    fun canFinish(shape: ShapeData?): Boolean {
        if (shape == null || !isVertexTool(shape.tool)) return false
        return (shape.points?.size ?: 0) >= minimumVertices(shape.tool)
    }

    /**
     * Whether a tap ends the shape rather than adding another vertex.
     *
     * On the web this also accepts a double-click, but a double *tap* is a
     * poor gesture on a touch screen: it collides with the platform's own
     * double-tap-to-zoom and asks for two precise touches in the same spot.
     * The two reliable gestures are kept:
     *
     *  - the tap lands on the first vertex, closing the outline;
     *  - the tap lands on the vertex just placed.
     *
     * Both are what a finger can actually do, and neither depends on timing.
     * A Done control in the viewer's toolbar covers the case where neither is
     * convenient — see [canFinish].
     *
     * @param tolerance radius in the shape's own coordinate space. Callers
     *        pass a value derived from the current zoom so the target stays a
     *        constant size under the finger.
     */
    fun finishesShape(shape: ShapeData?, at: Point, tolerance: Double): Boolean {
        if (!canFinish(shape)) return false
        if (requiredVertices(shape!!.tool) != null) return false

        val points = shape.points ?: return false
        fun near(v: Point) = hypot(at.x - v.x, at.y - v.y) <= tolerance
        return near(points.first()) || near(points.last())
    }

    fun addVertex(shape: ShapeData, at: Point): ShapeData =
        shape.copy(points = (shape.points ?: emptyList()) + at)

    fun removeLastVertex(shape: ShapeData): ShapeData =
        shape.copy(points = (shape.points ?: emptyList()).dropLast(1))

    /** Starts a shape at [at]. Geometry is filled per tool. */
    fun startShape(
        tool: MarkupTool,
        at: Point,
        pageNumber: Int,
        color: String,
        strokeWidth: Double,
        opacity: Double,
        author: String? = null,
    ): ShapeData {
        val base = ShapeData(
            id = newId(),
            tool = tool,
            pageNumber = pageNumber,
            color = color,
            strokeWidth = strokeWidth,
            opacity = opacity,
            author = author,
        )
        return when {
            tool == MarkupTool.LINE || tool == MarkupTool.ARROW ->
                base.copy(x1 = at.x, y1 = at.y, x2 = at.x, y2 = at.y)

            tool == MarkupTool.CIRCLE ->
                base.copy(cx = at.x, cy = at.y, r = 0.0)

            isVertexTool(tool) || tool == MarkupTool.FREEHAND || tool == MarkupTool.CLOUD ->
                base.copy(points = listOf(at))

            else -> base.copy(x = at.x, y = at.y, width = 0.0, height = 0.0)
        }
    }

    /** Applies a drag to a shape being drawn. */
    fun updateShape(shape: ShapeData, at: Point): ShapeData = when {
        shape.tool == MarkupTool.LINE || shape.tool == MarkupTool.ARROW ->
            shape.copy(x2 = at.x, y2 = at.y)

        shape.tool == MarkupTool.CIRCLE ->
            shape.copy(r = hypot(at.x - (shape.cx ?: 0.0), at.y - (shape.cy ?: 0.0)))

        shape.tool == MarkupTool.FREEHAND || shape.tool == MarkupTool.CLOUD ->
            shape.copy(points = (shape.points ?: emptyList()) + at)

        isVertexTool(shape.tool) -> shape   // built by taps, not by dragging

        else -> {
            // Rectangle-like. Normalised so dragging up or left still yields a
            // positive extent rather than an invisible shape.
            val originX = shape.x ?: at.x
            val originY = shape.y ?: at.y
            shape.copy(
                x = minOf(originX, at.x),
                y = minOf(originY, at.y),
                width = abs(at.x - originX),
                height = abs(at.y - originY),
            )
        }
    }

    /** Whether the shape is big enough to be worth committing. */
    fun hasMinimumSize(shape: ShapeData): Boolean {
        val min = 3.0
        return when (shape.tool) {
            MarkupTool.LINE, MarkupTool.ARROW ->
                hypot((shape.x2 ?: 0.0) - (shape.x1 ?: 0.0),
                      (shape.y2 ?: 0.0) - (shape.y1 ?: 0.0)) > min

            MarkupTool.DIMENSION, MarkupTool.AREA, MarkupTool.CALIBRATE,
            MarkupTool.RADIUS ->
                (shape.points?.size ?: 0) >= minimumVertices(shape.tool)

            MarkupTool.CIRCLE -> (shape.r ?: 0.0) > min

            MarkupTool.FREEHAND, MarkupTool.CLOUD, MarkupTool.POLYGON ->
                (shape.points?.size ?: 0) > 2

            MarkupTool.POLYLINE -> (shape.points?.size ?: 0) > 1

            else -> (shape.width ?: 0.0) > min && (shape.height ?: 0.0) > min
        }
    }

    // ── Measurement geometry ─────────────────────────────────────

    /** Total length along a path, in the coordinate space of the points. */
    fun pathLength(points: List<Point>): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            total += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        return total
    }

    /**
     * Area of a closed polygon by the shoelace formula.
     *
     * The path is treated as closed whether or not the last point repeats the
     * first, so a shape finished by tapping the opening vertex and one
     * finished on its last vertex report the same area.
     */
    fun polygonArea(points: List<Point>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        for (i in points.indices) {
            val j = (i + 1) % points.size
            sum += points[i].x * points[j].y - points[j].x * points[i].y
        }
        return abs(sum / 2.0)
    }

    /** Perimeter of the closed polygon, including the closing segment. */
    fun polygonPerimeter(points: List<Point>): Double {
        if (points.size < 2) return 0.0
        return pathLength(points) +
            hypot(points.first().x - points.last().x, points.first().y - points.last().y)
    }

    private var counter = 0L

    /**
     * Ids are only ever compared, never ordered, and are namespaced with `s-`
     * to match the web viewer's format so a shape drawn on either client is
     * indistinguishable to the other.
     */
    @Synchronized
    fun newId(): String {
        counter += 1
        val suffix = java.lang.Long.toString(
            (System.nanoTime() xor counter) and 0xFFFFFFFFL, 36)
        return "s-${System.currentTimeMillis()}-$suffix"
    }
}
