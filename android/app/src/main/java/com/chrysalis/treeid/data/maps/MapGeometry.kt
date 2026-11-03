package com.chrysalis.treeid.data.maps

import com.chrysalis.treeid.data.sightings.Sighting
import kotlin.math.*

data class MapPoint(val x: Double, val y: Double)
data class MapViewport(val x: Double, val y: Double, val zoom: Double) {
    val scale: Double get() = 256.0 * 2.0.pow(zoom)
    fun screen(point: MapPoint, width: Float, height: Float): MapPoint = MapPoint(
        width / 2 + MapGeometry.wrappedDelta(point.x - x) * scale,
        height / 2 + (point.y - y) * scale,
    )

    fun move(dx: Double, dy: Double, factor: Double, focusX: Double = 0.0, focusY: Double = 0.0): MapViewport {
        val nextZoom = (zoom + log2(factor.coerceIn(0.5, 2.0))).coerceIn(2.0, 20.0)
        val nextScale = 256.0 * 2.0.pow(nextZoom)
        return MapViewport(
            MapGeometry.wrap(x + focusX / scale - (focusX + dx) / nextScale),
            (y + focusY / scale - (focusY + dy) / nextScale).coerceIn(0.0, 1.0), nextZoom,
        )
    }
}

object MapGeometry {
    const val MAX_LATITUDE = 85.05112878
    val NEW_YORK = project(43.0, -75.5)
    fun project(latitude: Double, longitude: Double): MapPoint {
        val radians = Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))
        return MapPoint((longitude + 180.0) / 360.0, (1 - ln(tan(radians) + 1 / cos(radians)) / PI) / 2)
    }
    fun latitude(y: Double): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y))))
    fun longitude(x: Double): Double = wrap(x) * 360 - 180
    fun wrap(x: Double): Double = ((x % 1) + 1) % 1
    fun wrappedDelta(x: Double): Double = x - floor(x + 0.5)
    fun located(sighting: Sighting): Boolean = sighting.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
        sighting.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true
    fun tmsRow(zoom: Int, xyzRow: Int): Int {
        require(zoom in 0..22 && xyzRow in 0 until (1 shl zoom))
        return (1 shl zoom) - 1 - xyzRow
    }

    /** Use the smallest longitude arc, including sightings either side of the date line. */
    fun fit(points: List<MapPoint>, width: Float, height: Float): MapViewport {
        if (points.isEmpty()) return MapViewport(NEW_YORK.x, NEW_YORK.y, 6.0)
        val xs = points.map { wrap(it.x) }.sorted()
        val gapIndex = xs.indices.maxBy { i -> (if (i == xs.lastIndex) xs[0] + 1 else xs[i + 1]) - xs[i] }
        val start = xs[(gapIndex + 1) % xs.size]
        val unwrapped = xs.map { if (it < start) it + 1 else it }
        val minX = unwrapped.min(); val maxX = unwrapped.max()
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        val usableWidth = (width - 96).coerceAtLeast(64f)
        val usableHeight = (height - 96).coerceAtLeast(64f)
        val zoom = min(log2(usableWidth / (256 * (maxX - minX).coerceAtLeast(0.00001))),
            log2(usableHeight / (256 * (maxY - minY).coerceAtLeast(0.00001)))).coerceIn(2.0, 16.0)
        return MapViewport(wrap((minX + maxX) / 2), (minY + maxY) / 2, zoom)
    }
}
