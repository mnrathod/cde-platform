package com.cde.sdk.ui

import com.cde.sdk.model.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page and the markup over it must be drawn at the same size.
 *
 * These are the only checks in the Android view layer that run without a
 * device. The viewer's other faults show up as something visibly broken; this
 * one does not — the page renders, the markup renders, and they are simply at
 * different scales, which reads as "the markup is in the wrong place" long
 * after anyone would think to suspect the bitmap transform.
 */
class ViewportTransformTest {

    /** A4 in PDF points, and a phone's pixel width. */
    private val pageWidth = 595f
    private val viewWidth = 1080

    private fun fitted(): ViewportTransform {
        val fit = ViewportTransform().fitToWidth(viewWidth, pageWidth)
        return fit.copy(rasterisedAt = fit.renderScale(max = 4f))
    }

    @Test
    fun `a fitted page spans exactly the view width`() {
        val transform = ViewportTransform().fitToWidth(viewWidth, pageWidth)
        val right = transform.pageToView(Point(pageWidth.toDouble(), 0.0))
        assertEquals(viewWidth.toDouble(), right.x, 0.001)
    }

    /**
     * The regression this file exists for.
     *
     * The right edge of the page reached through the bitmap must land where
     * the right edge reached through the markup mapping lands. They disagreed
     * by exactly the display density, which put the page three times oversize
     * on a typical phone while the markup stayed correct.
     */
    @Test
    fun `the bitmap and the markup place the same page point together`() {
        val transform = fitted()

        val throughMarkup = transform.pageToView(Point(pageWidth.toDouble(), 0.0))
        val throughBitmap = transform.bitmapToView(pageWidth * transform.rasterisedAt, 0f)

        assertEquals(throughMarkup.x, throughBitmap.x, 0.001)
        assertEquals(viewWidth.toDouble(), throughBitmap.x, 0.001)
    }

    @Test
    fun `they still agree after a pinch that has not been re-rasterised`() {
        // The common case: the bitmap is from before the pinch, so scale and
        // rasterisedAt genuinely differ. The two must still agree.
        val transform = fitted().scaledBy(2.5f, focusX = 540f, focusY = 800f, min = 0.25f, max = 8f)

        val point = Point(120.0, 400.0)
        val throughMarkup = transform.pageToView(point)
        val throughBitmap = transform.bitmapToView(
            (point.x * transform.rasterisedAt).toFloat(),
            (point.y * transform.rasterisedAt).toFloat())

        assertEquals(throughMarkup.x, throughBitmap.x, 0.01)
        assertEquals(throughMarkup.y, throughBitmap.y, 0.01)
    }

    @Test
    fun `page and view coordinates round-trip`() {
        val transform = fitted().scaledBy(1.7f, focusX = 200f, focusY = 300f, min = 0.25f, max = 8f)
        val original = Point(321.5, 654.25)
        val back = transform.viewToPage(transform.pageToView(original))
        assertEquals(original.x, back.x, 0.0001)
        assertEquals(original.y, back.y, 0.0001)
    }

    @Test
    fun `a pinch leaves the point under the fingers where it was`() {
        val transform = fitted()
        val focusX = 400f
        val focusY = 900f
        val underFinger = transform.viewToPage(Point(focusX.toDouble(), focusY.toDouble()))

        val zoomed = transform.scaledBy(2f, focusX, focusY, min = 0.25f, max = 8f)
        val afterwards = zoomed.pageToView(underFinger)

        assertEquals(focusX.toDouble(), afterwards.x, 0.01)
        assertEquals(focusY.toDouble(), afterwards.y, 0.01)
    }

    @Test
    fun `zoom is bounded and the anchor still holds at the limit`() {
        val transform = fitted()
        val hugely = transform.scaledBy(1000f, 100f, 100f, min = 0.25f, max = 8f)
        assertEquals(8f, hugely.scale, 0.0001f)
        // Clamping must not break the anchor: the correction uses the scale
        // actually applied, not the one asked for.
        val underFinger = transform.viewToPage(Point(100.0, 100.0))
        assertEquals(100.0, hugely.pageToView(underFinger).x, 0.01)
    }

    @Test
    fun `render scale is capped and never below one to one`() {
        val zoomedOut = ViewportTransform(scale = 0.3f)
        assertEquals(1f, zoomedOut.renderScale(max = 4f), 0.0001f)

        val zoomedIn = ViewportTransform(scale = 40f)
        assertEquals(4f, zoomedIn.renderScale(max = 4f), 0.0001f)

        val ordinary = ViewportTransform(scale = 2.5f)
        assertEquals(2.5f, ordinary.renderScale(max = 4f), 0.0001f)
    }

    @Test
    fun `a page that has never been rasterised does not divide by zero`() {
        val transform = ViewportTransform(scale = 2f, rasterisedAt = 0f)
        assertTrue(transform.bitmapScale.isFinite())
        assertEquals(1f, transform.bitmapScale, 0.0001f)
    }

    @Test
    fun `degenerate input is returned unchanged rather than producing infinities`() {
        val zero = ViewportTransform(scale = 0f)
        assertEquals(Point(5.0, 5.0), zero.viewToPage(Point(5.0, 5.0)))
        assertEquals(zero, zero.scaledBy(2f, 0f, 0f, 0.25f, 8f))
        assertEquals(zero, zero.fitToWidth(0, pageWidth))
        assertEquals(zero, zero.fitToWidth(viewWidth, 0f))
    }
}
