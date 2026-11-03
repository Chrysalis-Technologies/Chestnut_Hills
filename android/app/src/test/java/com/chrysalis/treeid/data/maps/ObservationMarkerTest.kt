package com.chrysalis.treeid.data.maps

import com.chrysalis.treeid.Screen
import com.chrysalis.treeid.birds.*
import org.junit.Assert.*
import org.junit.Test

class ObservationMarkerTest {
    private val catalog = BirdCatalog(listOf(BirdLabel("robin", "American Robin")))
    private fun row(lat: Double? = 44.0, lon: Double? = -75.0) = BirdObservation(1, "robin", 0.8f, 100, lat, lon, 5f, "", "test.wav", 12)
    @Test fun birdPinsHaveDistinctIdsAndOpenTheirOwnDetail() {
        val pin = ObservationMarker.bird(row(), catalog)
        assertTrue(pin.located); assertTrue(pin.bird)
        assertEquals("bird:1", pin.key); assertNotEquals("tree:1", pin.key)
        assertEquals("American Robin", pin.name); assertEquals(Screen.BirdDetail(1), pin.destination)
    }
    @Test fun missingInvalidOrPartialCoordinatesNeverProducePins() {
        listOf(row(null), row(lon = null), row(Double.NaN), row(91.0), row(lon = Double.POSITIVE_INFINITY)).forEach {
            assertFalse(ObservationMarker.bird(it, catalog).located)
        }
    }
}
