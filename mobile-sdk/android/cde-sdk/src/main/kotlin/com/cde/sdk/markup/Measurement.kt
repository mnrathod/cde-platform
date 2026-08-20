package com.cde.sdk.markup

import com.cde.sdk.model.MarkupTool
import com.cde.sdk.model.Point
import com.cde.sdk.model.ShapeData
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Units a drawing can be calibrated to. */
enum class MeasurementUnit(val symbol: String) {
    MILLIMETRE("mm"), CENTIMETRE("cm"), METRE("m"), KILOMETRE("km"),
    INCH("in"), FOOT("ft"), YARD("yd"), PIXEL("px");

    companion object {
        fun fromSymbol(symbol: String): MeasurementUnit =
            entries.firstOrNull { it.symbol == symbol } ?: PIXEL
    }
}

/**
 * What one pixel of the rendered document is worth in real units.
 *
 * Uncalibrated is not an error state — it is the honest one. A drawing carries
 * no inherent scale, so until someone marks a known distance every reading is
 * in pixels and is labelled as such rather than being presented as a length.
 */
data class MeasurementScale(
    val unitsPerPixel: Double,
    val unit: MeasurementUnit,
) {
    val isCalibrated: Boolean get() = unit != MeasurementUnit.PIXEL

    companion object {
        val UNCALIBRATED = MeasurementScale(1.0, MeasurementUnit.PIXEL)
    }
}

/** A committed reading, kept so earlier results stay reviewable. */
data class MeasurementEntry(
    val id: String,
    val kind: Kind,
    val value: String,
    val detail: String,
    val page: Int,
) {
    enum class Kind { LINEAR, AREA, RADIUS }
}

/**
 * Turns drawn geometry into readings.
 *
 * Every calculation happens in page pixels first and is scaled afterwards, so
 * a calibration applied later cannot retrospectively change a measurement that
 * was already taken — the number on screen and the number in the record stay
 * the same.
 */
object Measurement {

    /**
     * Builds a scale from a line drawn over a distance the user knows.
     *
     * @param pixels length of the drawn reference line, in page pixels
     * @param realDistance what that line represents
     * @return null when the input cannot define a scale, rather than a
     *         scale of zero or infinity that would poison every later reading
     */
    fun calibrate(pixels: Double, realDistance: Double, unit: MeasurementUnit): MeasurementScale? {
        if (pixels <= 0.0 || realDistance <= 0.0 || !realDistance.isFinite()) return null
        return MeasurementScale(realDistance / pixels, unit)
    }

    /**
     * Describes a measurement shape, returning the shape with its readouts
     * filled in alongside the entry for the measurement list.
     *
     * @param zoom the zoom the geometry was captured at, divided out so a
     *        measurement taken at 200% equals the same one taken at 100%
     */
    fun describe(shape: ShapeData, scale: MeasurementScale, zoom: Double): Pair<ShapeData, MeasurementEntry> {
        val points = shape.points ?: emptyList()
        val z = if (zoom > 0.0) zoom else 1.0

        return when (shape.tool) {
            MarkupTool.AREA -> {
                val areaPx = MarkupEngine.polygonArea(points) / (z * z)
                val perimeterPx = MarkupEngine.polygonPerimeter(points) / z
                val value = formatArea(areaPx, scale)
                val detail = formatLength(perimeterPx, scale)
                shape.copy(measurement = value, measurementDetail = detail) to
                    MeasurementEntry(shape.id, MeasurementEntry.Kind.AREA, value, detail, shape.pageNumber)
            }

            MarkupTool.RADIUS -> {
                val radiusPx = if (points.size >= 2)
                    hypot(points[1].x - points[0].x, points[1].y - points[0].y) / z else 0.0
                val value = formatLength(radiusPx, scale)
                val detail = "⌀ " + formatLength(radiusPx * 2, scale)
                shape.copy(measurement = value, measurementDetail = detail) to
                    MeasurementEntry(shape.id, MeasurementEntry.Kind.RADIUS, value, detail, shape.pageNumber)
            }

            else -> {
                val lengthPx = MarkupEngine.pathLength(points) / z
                val value = formatLength(lengthPx, scale)
                val segments = if (points.size > 2) points.indices.drop(1).map { i ->
                    formatLength(
                        hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y) / z,
                        scale)
                } else null
                val detail = segments?.joinToString(" + ") ?: ""
                shape.copy(
                    measurement = value,
                    measurementDetail = detail,
                    segmentLabels = segments,
                ) to MeasurementEntry(
                    shape.id, MeasurementEntry.Kind.LINEAR, value, detail, shape.pageNumber)
            }
        }
    }

    fun formatLength(pixels: Double, scale: MeasurementScale): String {
        val value = pixels * scale.unitsPerPixel
        return "${trim(value)} ${scale.unit.symbol}"
    }

    /**
     * Areas scale with the square of the linear factor. Getting this wrong
     * yields a number that looks plausible and is out by the scale itself,
     * which is the worst kind of wrong on a quantity take-off.
     */
    fun formatArea(squarePixels: Double, scale: MeasurementScale): String {
        val value = squarePixels * scale.unitsPerPixel * scale.unitsPerPixel
        return "${trim(value)} ${scale.unit.symbol}²"
    }

    /**
     * Enough precision to be useful without implying more than the drawing
     * supports: whole numbers when large, one more place as values get small.
     */
    private fun trim(value: Double): String {
        if (!value.isFinite()) return "—"
        val magnitude = kotlin.math.abs(value)
        return when {
            magnitude >= 1000 -> value.roundToInt().toString()
            magnitude >= 10 -> String.format("%.1f", value)
            magnitude >= 1 -> String.format("%.2f", value)
            else -> String.format("%.3f", value)
        }.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    /** Distance between two points, for callers that only need the number. */
    fun distance(a: Point, b: Point): Double = hypot(b.x - a.x, b.y - a.y)
}
