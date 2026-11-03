package com.chrysalis.treeid.data.maps

import com.chrysalis.treeid.Screen
import com.chrysalis.treeid.nature.*
import org.junit.Assert.*
import org.junit.Test

class NatureMarkerTest {
    private val catalog = NatureCatalog(listOf(NatureTaxon(0, "flower", "Flower", "Flower", NatureKind.FLOWER)))
    private fun row(kind: String = "flower", latitude: Double? = 44.0) = NatureObservation(1, kind, null, null, 100, latitude, -75.0, null, "gps", "", "test.jpg")
    @Test fun categoryPinsHaveNamespacedKeysAndCorrectDestinations() {
        val flower = ObservationMarker.nature(row(), catalog)
        assertEquals("nature:1", flower.key); assertEquals(Screen.NatureDetail(1), flower.destination)
        assertEquals(MarkerKind.FLOWER, flower.kind); assertTrue(flower.unknown); assertTrue(flower.located)
        assertEquals(MarkerKind.MUSHROOM, ObservationMarker.nature(row("mushroom"), catalog).kind)
        assertNotEquals("tree:1", flower.key); assertNotEquals("bird:1", flower.key)
    }
    @Test fun missingAndInvalidCoordinatesDoNotMakePins() {
        assertFalse(ObservationMarker.nature(row(latitude = null), catalog).located)
        assertFalse(ObservationMarker.nature(row(latitude = Double.NaN), catalog).located)
    }
}
