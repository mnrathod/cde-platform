package com.cde.sdk.markup

import com.cde.sdk.model.MarkupTool
import com.cde.sdk.model.Point
import com.cde.sdk.model.ShapeData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity with the web viewer's markup engine.
 *
 * These carry the same vectors as `markup-engine.service.spec.ts`. Both
 * clients write the same `shapeData` and the server never parses it, so a
 * divergence here does not fail anywhere — it produces markup that reads
 * differently in a browser than on the phone that drew it. Asserting the same
 * inputs give the same answers is the only thing that keeps them honest.
 *
 * One difference is deliberate and asserted below: the web engine also closes
 * a shape on a double-click, which the touch clients drop. See
 * [touchDropsDoubleClickDeliberately].
 */
class MarkupEngineParityTest {

    private val square = listOf(
        Point(100.0, 100.0), Point(200.0, 100.0), Point(200.0, 200.0))

    private fun area(points: List<Point> = square, tool: MarkupTool = MarkupTool.AREA) =
        ShapeData(
            id = "c1", tool = tool, pageNumber = 1, color = "#F00",
            strokeWidth = 2.0, opacity = 0.15, points = points)

    // ── finishesShape ─────────────────────────────────────────────

    @Test
    fun `closes when the tap lands on the first vertex`() {
        assertTrue(MarkupEngine.finishesShape(area(), Point(100.0, 100.0), 10.0))
        // A finger is not exact — near enough must also close, or the gesture
        // is no more usable than the double-click it replaces.
        assertTrue(MarkupEngine.finishesShape(area(), Point(106.0, 96.0), 10.0))
    }

    @Test
    fun `closes when the tap lands on the vertex just placed`() {
        assertTrue(MarkupEngine.finishesShape(area(), Point(200.0, 200.0), 10.0))
        assertTrue(MarkupEngine.finishesShape(area(), Point(197.0, 204.0), 10.0))
    }

    @Test
    fun `adds a vertex for a tap that is merely nearby`() {
        assertFalse(MarkupEngine.finishesShape(area(), Point(240.0, 150.0), 10.0))
    }

    @Test
    fun `will not close a shape with too few points to be anything`() {
        val twoPoints = listOf(Point(100.0, 100.0), Point(200.0, 100.0))
        assertFalse(MarkupEngine.finishesShape(area(twoPoints), Point(200.0, 100.0), 10.0))
    }

    @Test
    fun `leaves fixed-click-count tools alone`() {
        // Radius and Calibrate complete on their own second point. Closing
        // them early would cost the point that defines them.
        for (tool in listOf(MarkupTool.RADIUS, MarkupTool.CALIBRATE)) {
            val shape = area(listOf(Point(100.0, 100.0)), tool)
            assertFalse(tool.name, MarkupEngine.finishesShape(shape, Point(100.0, 100.0), 10.0))
        }
    }

    @Test
    fun `ignores tools that are dragged rather than tapped`() {
        val rect = ShapeData(
            id = "r1", tool = MarkupTool.RECT, pageNumber = 1, color = "#F00",
            strokeWidth = 2.0, opacity = 0.0, x = 100.0, y = 100.0,
            width = 50.0, height = 50.0)
        assertFalse(MarkupEngine.finishesShape(rect, Point(100.0, 100.0), 10.0))
    }

    @Test
    fun `closes nothing when no shape is being drawn`() {
        assertFalse(MarkupEngine.finishesShape(null, Point(1.0, 1.0), 10.0))
    }

    /**
     * The web engine also treats a recognised double-click as a close. Touch
     * does not: a double *tap* collides with the platform's own zoom gesture
     * and demands two precise touches in one spot. The two positional
     * gestures are kept, and the viewer offers a Done control besides.
     *
     * Recorded as a test so the divergence is a decision on the record rather
     * than something that looks like an omission to whoever reads this next.
     */
    @Test
    fun touchDropsDoubleClickDeliberately() {
        // Far from any vertex: on the web a double-click here would close the
        // shape; on touch nothing does.
        assertFalse(MarkupEngine.finishesShape(area(), Point(500.0, 500.0), 10.0))
    }

    // ── canFinish ─────────────────────────────────────────────────

    @Test
    fun `requires three points for an area and two for a line`() {
        fun withPoints(tool: MarkupTool, count: Int) = ShapeData(
            id = "f1", tool = tool, pageNumber = 1, color = "#F00",
            strokeWidth = 2.0, opacity = 0.0,
            points = (0 until count).map { Point(it * 10.0, 0.0) })

        assertFalse(MarkupEngine.canFinish(withPoints(MarkupTool.AREA, 2)))
        assertTrue(MarkupEngine.canFinish(withPoints(MarkupTool.AREA, 3)))
        assertFalse(MarkupEngine.canFinish(withPoints(MarkupTool.DIMENSION, 1)))
        assertTrue(MarkupEngine.canFinish(withPoints(MarkupTool.DIMENSION, 2)))
        assertFalse(MarkupEngine.canFinish(null))
    }

    // ── geometry ──────────────────────────────────────────────────

    @Test
    fun `polygon area matches the shoelace result the web viewer reports`() {
        // A 100x100 square: three drawn corners plus the implicit closing edge.
        val closed = listOf(
            Point(100.0, 100.0), Point(200.0, 100.0),
            Point(200.0, 200.0), Point(100.0, 200.0))
        assertEquals(10_000.0, MarkupEngine.polygonArea(closed), 0.001)
        assertEquals(400.0, MarkupEngine.polygonPerimeter(closed), 0.001)
    }

    @Test
    fun `a shape closed on its opening vertex measures the same as one that is not`() {
        // Tapping the first vertex to close must not add a zero-length edge or
        // a duplicate point that changes the area.
        val open = listOf(Point(0.0, 0.0), Point(100.0, 0.0), Point(100.0, 100.0))
        val explicitlyClosed = open + Point(0.0, 0.0)
        assertEquals(
            MarkupEngine.polygonArea(open),
            MarkupEngine.polygonArea(explicitlyClosed),
            0.001)
    }

    @Test
    fun `path length sums the segments`() {
        val path = listOf(Point(0.0, 0.0), Point(3.0, 4.0), Point(3.0, 14.0))
        assertEquals(15.0, MarkupEngine.pathLength(path), 0.001)
    }

    // ── ids ───────────────────────────────────────────────────────

    @Test
    fun `ids are unique and carry the web viewer's prefix`() {
        val ids = (1..500).map { MarkupEngine.newId() }.toSet()
        assertEquals(500, ids.size)
        assertTrue(ids.all { it.startsWith("s-") })
    }
}
