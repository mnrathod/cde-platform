package com.cde.sdk.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** A page's intrinsic size, in PDF points. */
data class PageSize(val width: Float, val height: Float)

/**
 * Renders PDF pages using the platform's own renderer.
 *
 * `android.graphics.pdf.PdfRenderer` is part of the framework and is backed by
 * the same native PDF stack the system uses elsewhere, so pages are rasterised
 * in C++ against a hardware-friendly bitmap rather than decoded in a VM. That
 * is the whole reason this class exists instead of shipping a JavaScript
 * engine in a WebView: on a mid-range phone the difference on a dense drawing
 * is the difference between a viewer that pans and one that stutters.
 *
 * `PdfRenderer` has two constraints that shape everything here:
 *
 *  - **Only one page may be open at a time.** Opening a second while the first
 *    is open throws. All access is therefore serialised through this class.
 *  - **It needs a seekable file**, not a stream, so the caller must have the
 *    document on disk. The offline cache already puts it there, which is why
 *    caching and rendering are not independent choices.
 *
 * Not thread-safe by design — call from one coroutine context. [renderPage]
 * already moves work off the main thread.
 */
class PdfPageRenderer private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {

    val pageCount: Int get() = renderer.pageCount

    /**
     * Rendered pages, bounded by memory rather than by count: one A0 drawing
     * at full zoom can outweigh fifty small pages, so counting pages would
     * bound the wrong thing.
     */
    private val bitmaps = object : LruCache<String, Bitmap>(memoryBudgetBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount

        // Deliberately no recycle() on eviction. A bitmap handed out by
        // renderPage is held and drawn by a view, and eviction happens on
        // whichever thread renders next — so recycling here frees a bitmap
        // that is still on screen. The visible result is a page that goes
        // blank after a few zooms, with no exception to explain it. Letting
        // the collector take them costs a little memory for a little longer
        // and cannot pull a page out from under the view drawing it.
    }

    /** Intrinsic size, for laying out before anything is rasterised. */
    fun pageSize(index: Int): PageSize = synchronized(this) {
        renderer.openPage(index).use { PageSize(it.width.toFloat(), it.height.toFloat()) }
    }

    /**
     * Rasterises a page at [scale].
     *
     * @param scale device pixels per PDF point. Pass the display density times
     *        the zoom so text stays sharp when zoomed rather than being an
     *        upscaled blur — rendering at 1x and stretching is the single most
     *        common cause of a PDF viewer looking soft.
     */
    suspend fun renderPage(index: Int, scale: Float): Bitmap = withContext(Dispatchers.IO) {
        val key = "$index@${"%.3f".format(scale)}"
        bitmaps[key]?.takeIf { !it.isRecycled }?.let { return@withContext it }

        val bitmap = synchronized(this@PdfPageRenderer) {
            renderer.openPage(index).use { page ->
                // Bounded before allocating, not after. A large sheet at high
                // zoom asks for a bitmap of hundreds of megabytes, and
                // Bitmap.createBitmap answers that with OutOfMemoryError —
                // which kills the host app rather than degrading the page.
                val safe = scaleWithin(page.width, page.height, scale)
                val width = (page.width * safe).toInt().coerceAtLeast(1)
                val height = (page.height * safe).toInt().coerceAtLeast(1)

                // ARGB_8888 rather than RGB_565: markup is drawn over this and
                // 565 banding shows badly on the flat greys of a drawing.
                val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                // PdfRenderer does not clear the bitmap, and a PDF page is
                // implicitly white. Without this, anything the page does not
                // paint shows through as transparent black.
                Canvas(output).drawColor(Color.WHITE)

                page.render(
                    output,
                    Rect(0, 0, width, height),
                    Matrix().apply { setScale(safe, safe) },
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                )
                output
            }
        }

        // Only cached if it fits. LruCache trims on insert, so a bitmap larger
        // than the whole budget would be evicted the instant it was added —
        // pointless work, and previously it took the page being displayed with
        // it. Returning it uncached shows the page and simply does not keep it.
        if (bitmap.byteCount <= bitmaps.maxSize()) bitmaps.put(key, bitmap)
        bitmap
    }

    /**
     * Caps the render scale so one page cannot exhaust the heap.
     *
     * Past the cap the bitmap is stretched, which is soft but survivable — the
     * alternative is an OutOfMemoryError on precisely the large-format sheets
     * this viewer exists to show.
     */
    private fun scaleWithin(pageWidth: Int, pageHeight: Int, scale: Float): Float {
        if (pageWidth <= 0 || pageHeight <= 0) return scale
        val requested = pageWidth.toLong() * pageHeight.toLong() *
            scale.toDouble() * scale.toDouble() * BYTES_PER_PIXEL
        val budget = memoryBudgetBytes().toDouble()
        if (requested <= budget) return scale
        return (scale * kotlin.math.sqrt(budget / requested)).toFloat().coerceAtLeast(MIN_SCALE)
    }

    /** Frees rasterised pages without closing the document. */
    fun trimMemory() = bitmaps.evictAll()

    override fun close() {
        bitmaps.evictAll()
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        /** ARGB_8888, the config every page is rasterised into. */
        private const val BYTES_PER_PIXEL = 4
        /** Never render smaller than this, however tight memory is. */
        private const val MIN_SCALE = 0.1f

        /**
         * A quarter of what the app is allowed. Bitmaps are the largest thing
         * a viewer holds, and a cache that competes with the rest of the app
         * for the last quarter of the heap trades stutter for termination.
         */
        private fun memoryBudgetBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        /**
         * Opens a PDF already on disk.
         *
         * @throws java.io.IOException if the file is absent or not a PDF —
         *         including the case of a download that returned an error page,
         *         which is why the cache writes through a temporary file.
         */
        fun open(file: File): PdfPageRenderer {
            val descriptor = ParcelFileDescriptor.open(
                file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                PdfPageRenderer(descriptor, PdfRenderer(descriptor))
            } catch (e: Throwable) {
                runCatching { descriptor.close() }
                throw e
            }
        }
    }
}
