package com.chrysalis.treeid.data.maps

import com.chrysalis.treeid.data.sightings.Sighting
import org.junit.Assert.*
import org.junit.Test

class MapGeometryTest {
    @Test fun projectsCoordinatesAndClampsPolesWithoutInfinity() {
        assertEquals(MapPoint(0.5, 0.5), MapGeometry.project(0.0, 0.0))
        val ny = MapGeometry.project(43.0, -75.5)
        assertEquals(43.0, MapGeometry.latitude(ny.y), 0.000001)
        assertEquals(-75.5, MapGeometry.longitude(ny.x), 0.000001)
        assertTrue(MapGeometry.project(90.0, 10.0).y.isFinite())
        assertEquals(MapGeometry.project(MapGeometry.MAX_LATITUDE, 10.0), MapGeometry.project(90.0, 10.0))
    }

    @Test fun tmsInvertsRowsAtEveryZoom() {
        assertEquals(0, MapGeometry.tmsRow(0, 0))
        assertEquals(3, MapGeometry.tmsRow(2, 0))
        assertEquals(0, MapGeometry.tmsRow(2, 3))
        assertEquals(16382, MapGeometry.tmsRow(14, 1))
        assertThrows(IllegalArgumentException::class.java) { MapGeometry.tmsRow(2, 4) }
    }

    @Test fun fitIncludesDistantPinsAndUsesShortArcAtDateLine() {
        val points = listOf(MapGeometry.project(40.5, -79.8), MapGeometry.project(45.0, -71.8))
        val view = MapGeometry.fit(points, 400f, 600f)
        points.map { view.screen(it, 400f, 600f) }.forEach {
            assertTrue(it.x in 47.0..353.0); assertTrue(it.y in 47.0..553.0)
        }
        val crossing = MapGeometry.fit(listOf(MapGeometry.project(0.0, 179.9), MapGeometry.project(0.0, -179.9)), 400f, 600f)
        assertTrue(crossing.zoom > 8)
        assertTrue(crossing.x < 0.001 || crossing.x > 0.999)
        assertEquals(6.0, MapGeometry.fit(emptyList(), 400f, 600f).zoom, 0.0)
    }

    @Test fun pinchKeepsAnchorAndPanMovesPinsWithFinger() {
        val view = MapViewport(0.4, 0.4, 10.0)
        val anchor = MapPoint(view.x + 80 / view.scale, view.y + 50 / view.scale)
        val zoomed = view.move(0.0, 0.0, 2.0, 80.0, 50.0).screen(anchor, 400f, 600f)
        assertEquals(280.0, zoomed.x, 0.000001); assertEquals(350.0, zoomed.y, 0.000001)
        val panned = view.move(20.0, 30.0, 1.0).screen(MapPoint(view.x, view.y), 400f, 600f)
        assertEquals(220.0, panned.x, 0.000001); assertEquals(330.0, panned.y, 0.000001)
    }

    @Test fun missingInvalidAndUnknownLocationsAreHandledSeparately() {
        val s = Sighting(treeKey = null, confidence = null, observedAt = 0, latitude = 0.0, longitude = 0.0,
            accuracyMeters = null, locationSource = "photo", photoFileName = "photo.jpg")
        assertTrue(MapGeometry.located(s)) // Unknown trees and the equator are real pins.
        assertFalse(MapGeometry.located(s.copy(latitude = null)))
        assertFalse(MapGeometry.located(s.copy(longitude = Double.NaN)))
        assertFalse(MapGeometry.located(s.copy(latitude = 91.0)))
    }
}
