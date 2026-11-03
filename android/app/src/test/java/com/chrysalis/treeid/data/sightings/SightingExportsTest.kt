package com.chrysalis.treeid.data.sightings

import com.chrysalis.treeid.data.Species
import com.chrysalis.treeid.data.SpeciesCatalog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SightingExportsTest {
    private val catalog = SpeciesCatalog("Test", listOf(Species(
        "oak", "Oak, white", "Quercus alba", "Fagaceae", "broadleaf", "native", emptyList(), emptyList(),
    )))
    private fun sighting() = Sighting(
        id = 7, treeKey = "oak", confidence = 0.65f, observedAt = 0,
        latitude = 44.25, longitude = -75.5, accuracyMeters = 8f, locationSource = "gps", photoFileName = "test.jpg",
    )

    @Test fun csvEscapesNamesQuotesNewlinesAndUnicode() {
        val csv = SightingExports.csv(listOf(sighting().copy(notes = "Said \"oak\", café\nSecond line")), catalog)
        assertEquals(
            "date,tree_common_name,tree_scientific_name,lat,lon,accuracy_m,notes\r\n" +
                "\"1970-01-01T00:00:00Z\",\"Oak, white\",\"Quercus alba\",44.25,-75.5,8.0," +
                "\"Said \"\"oak\"\", café\nSecond line\"\r\n", csv,
        )
    }

    @Test fun csvKeepsMissingLocationBlankAndUnknownTreeExplicit() {
        val row = sighting().copy(treeKey = null, latitude = null, longitude = null, accuracyMeters = null)
        assertTrue(SightingExports.csv(listOf(row), catalog).endsWith(
            "\"1970-01-01T00:00:00Z\",\"Unknown tree\",\"\",,,,\"\"\r\n",
        ))
    }

    @Test fun csvUsesDecimalPointsRegardlessOfPhoneLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertTrue(SightingExports.csv(listOf(sighting()), catalog).contains(",44.25,-75.5,8.0,"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun csvTreatsFormulaLikeNotesAsTextButGeoJsonKeepsOriginalNotes() {
        listOf("=1+1", "+SUM(A1)", "-1+2", "@SUM(A1)", "  =1+1", "\t=1+1").forEach { note ->
            val row = sighting().copy(notes = note)
            assertTrue(SightingExports.csv(listOf(row), catalog).endsWith("\"'$note\"\r\n"))
            val properties = JSONObject(SightingExports.geoJson(listOf(row), catalog))
                .getJSONArray("features").getJSONObject(0).getJSONObject("properties")
            assertEquals(note, properties.getString("notes"))
        }
    }

    @Test fun geoJsonIsAFeatureCollectionWithLongitudeBeforeLatitudeAndTypedProperties() {
        val row = sighting().copy(notes = "A \"quote\"\nBackslash \\ and é")
        val json = JSONObject(SightingExports.geoJson(listOf(row), catalog))
        assertEquals("FeatureCollection", json.getString("type"))
        val feature = json.getJSONArray("features").getJSONObject(0)
        assertEquals("Feature", feature.getString("type"))
        assertEquals(7L, feature.getLong("id"))
        val geometry = feature.getJSONObject("geometry")
        assertEquals("Point", geometry.getString("type"))
        assertEquals(-75.5, geometry.getJSONArray("coordinates").getDouble(0), 0.0)
        assertEquals(44.25, geometry.getJSONArray("coordinates").getDouble(1), 0.0)
        val properties = feature.getJSONObject("properties")
        assertEquals("1970-01-01T00:00:00Z", properties.getString("date"))
        assertEquals("Oak, white", properties.getString("tree_common_name"))
        assertEquals("Quercus alba", properties.getString("tree_scientific_name"))
        assertEquals(8.0, properties.getDouble("accuracy_m"), 0.0)
        assertEquals(0.65, properties.getDouble("confidence"), 0.0001)
        assertEquals(row.notes, properties.getString("notes"))
    }

    @Test fun geoJsonRetainsUnlocatedSightingsWithNullGeometryAndDoesNotConfuseZeroWithMissing() {
        val rows = listOf(
            sighting().copy(treeKey = null, confidence = null, latitude = null, longitude = null, accuracyMeters = null),
            sighting().copy(latitude = 0.0, longitude = 0.0),
        )
        val features = JSONObject(SightingExports.geoJson(rows, catalog)).getJSONArray("features")
        assertEquals(2, features.length())
        assertTrue(features.getJSONObject(0).isNull("geometry"))
        val properties = features.getJSONObject(0).getJSONObject("properties")
        assertTrue(properties.isNull("tree_key"))
        assertTrue(properties.isNull("accuracy_m"))
        assertTrue(properties.isNull("confidence"))
        assertEquals("Unknown tree", properties.getString("tree_common_name"))
        assertEquals(0.0, features.getJSONObject(1).getJSONObject("geometry").getJSONArray("coordinates").getDouble(0), 0.0)
    }

    @Test fun emptyExportsAreStillValid() {
        assertEquals(1, SightingExports.csv(emptyList(), catalog).trimEnd().lines().size)
        assertEquals(0, JSONObject(SightingExports.geoJson(emptyList(), catalog)).getJSONArray("features").length())
    }
}
