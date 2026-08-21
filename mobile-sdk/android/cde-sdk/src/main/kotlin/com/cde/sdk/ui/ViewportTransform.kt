package com.cde.sdk.ui

import com.cde.sdk.model.Point

/**
 * Pan and zoom for one page, and the single place where page, view and bitmap
 * coordinates are related to one another.
 *
 * Three coordinate systems meet in the viewer, and all three have to agree or
 * the markup drifts away from the page it describes:
 *
 *  - **page** — PDF points. What shape data is stored in, and what the browser
 *    reads back.
 *  - **view** — device pixels on screen. Where touches arrive.
 *  - **bitmap** — pixels of the rasterised page, which exists at whatever
 *    scale it was last rendered at, not at the scale currently being viewed.
 *
 * Holding the arithmetic here rather than inside the `View` is what makes that
 * agreement testable: [pageToView] and [bitmapScale] must put the same page
 * point in the same place, and checking that needs no Android at all. A
 * disagreement between them does not throw — it draws the page at one size and
 * the markup over it at another.
 *
 * Immutable: gestures produce a new transform rather than mutating four
 * fields that can be updated in the wrong order.
 */
data class ViewportTransform(
    /** View pixels per page point. */
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** Bitmap pixels per page point, for the page as it was last rasterised. */
    val rasterisedAt: Float = 0f,
) {

    fun pageToView(point: Point): Point =
        Point(point.x * scale + offsetX, point.y * scale + offsetY)

    fun viewToPage(point: Point): Point =
        if (scale == 0f) point
        else Point((point.x - offsetX) / scale, (point.y - offsetY) / scale)

    /**
     * The factor the rasterised bitmap must be drawn at.
     *
     * The bitmap holds [rasterisedAt] pixels per page point; the page has to
     * occupy [scale] view pixels per page point. Any other value draws the
     * page and the markup over it at different sizes.
     */
    val bitmapScale: Float
        get() = if (rasterisedAt > 0f) scale / rasterisedAt else 1f

    /** Where a pixel of the rasterised bitmap lands on screen. */
    fun bitmapToView(x: Float, y: Float): Point =
        Point((x * bitmapScale + offsetX).toDouble(), (y * bitmapScale + offsetY).toDouble())

    /**
     * Applies a pinch, keeping the point under the fingers still.
     *
     * Without the offset correction the page slides out from under the pinch
     * and the gesture feels unanchored.
     */
    fun scaledBy(factor: Float, focusX: Float, focusY: Float, min: Float, max: Float): ViewportTransform {
        if (scale == 0f) return this
        val updated = (scale * factor).coerceIn(min, max)
        val ratio = updated / scale
        return copy(
            scale = updated,
            offsetX = focusX - (focusX - offsetX) * ratio,
            offsetY = focusY - (focusY - offsetY) * ratio,
        )
    }

    fun pannedBy(dx: Float, dy: Float): ViewportTransform =
        copy(offsetX = offsetX + dx, offsetY = offsetY + dy)

    fun fitToWidth(viewWidth: Int, pageWidth: Float): ViewportTransform =
        if (viewWidth <= 0 || pageWidth <= 0f) this
        else copy(scale = viewWidth / pageWidth, offsetX = 0f, offsetY = 0f)

    /**
     * The scale to rasterise the page at.
     *
     * [scale] is already view pixels per page point, so it is exactly what
     * gives one bitmap pixel per screen pixel. Display density does not enter
     * into it — `View.getWidth()` is already in device pixels, so multiplying
     * by density again renders the page density times too large in each
     * direction: the memory cost squared, and a bitmap that no longer matches
     * the markup drawn over it.
     *
     * Capped because rasterising at an arbitrary zoom is how a viewer runs out
     * of memory. Past the cap the bitmap is stretched, which is soft but
     * survivable.
     */
    fun renderScale(max: Float): Float = scale.coerceIn(MIN_RENDER_SCALE, max)

    private companion object {
        /** Below 1:1 there is nothing to gain; the page is only shrinking. */
        const val MIN_RENDER_SCALE = 1f
    }
}
