package com.chrysalis.treeid.data.maps

import com.chrysalis.treeid.Screen
import com.chrysalis.treeid.birds.*
import com.chrysalis.treeid.nature.*
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
    @Test fun fernAndVinePinsRetainTheirCategoryAndOpenNatureDetails() {
        val nature = NatureCatalog(listOf(
            NatureTaxon(0, "fern", "Fern", "Christmas fern", NatureKind.FLOWER, fern = true),
            NatureTaxon(1, "vine", "Vine", "Virginia creeper", NatureKind.FLOWER, vine = true)))
        listOf("fern" to MarkerKind.FERN, "vine" to MarkerKind.VINE).forEach { (key, kind) ->
            val observation = NatureObservation(7, key, key, 0.9f, 100, 44.0, -75.0, 4f, "gps", photoFileName = "photo.jpg")
            val pin = ObservationMarker.nature(observation, nature)
            assertEquals(kind, pin.kind); assertEquals("nature:7", pin.key)
            assertEquals(Screen.NatureDetail(7), pin.destination); assertTrue(pin.located)
            assertFalse(ObservationMarker.nature(observation.copy(latitude = null), nature).located)
        }
    }
}
