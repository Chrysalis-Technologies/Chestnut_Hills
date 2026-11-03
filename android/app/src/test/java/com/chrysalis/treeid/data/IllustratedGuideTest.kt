package com.chrysalis.treeid.data

import android.graphics.BitmapFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IllustratedGuideTest {
    @Test fun everyTreeHasFourSeasonsAndLicensedLocalImagesForEachPart() {
        val context = RuntimeEnvironment.getApplication()
        val catalog = SpeciesCatalog.load(context)
        val guide = IllustratedGuide.load(context)
        assertEquals(catalog.trees.map { it.key }.toSet(), guide.entries.keys)
        val required = setOf("Bark", "Leaves / needles", "Branches / buds", "Fruit / nuts / cones", "Seasonal changes")
        val decoded = mutableSetOf<String>()
        guide.entries.forEach { (key, entry) ->
            assertEquals(key, setOf("Spring", "Summer", "Autumn", "Winter"), entry.seasons.keys)
            assertTrue(key, entry.seasons.values.all { it.length > 40 })
            assertTrue(key, entry.photos.map { it.part }.containsAll(required))
            assertTrue(key, entry.sources.isNotEmpty())
            entry.photos.forEach { photo ->
                assertTrue(photo.asset, photo.asset.matches(Regex("guide/photos/[a-z0-9-]+\\.jpg")))
                assertTrue(photo.asset, photo.caption.isNotBlank() && photo.credit.isNotBlank() && photo.taxon.isNotBlank())
                assertTrue(photo.asset, photo.sourceUrl.startsWith("https://") && photo.licenseUrl.startsWith("https://"))
                assertTrue(photo.sourceTitle.isNotBlank())
                assertTrue(photo.license, photo.license.matches(Regex("CC BY(?:-SA)? [0-9.]+|CC0(?: [0-9.]+)?|Public domain|Attribution only", RegexOption.IGNORE_CASE)))
                if (decoded.add(photo.asset)) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.assets.open(photo.asset).use { BitmapFactory.decodeStream(it, null, bounds) }
                    assertEquals(photo.asset, "image/jpeg", bounds.outMimeType)
                    assertTrue(photo.asset, bounds.outWidth in 1..800 && bounds.outHeight in 1..800)
                }
            }
        }
    }

    @Test fun bundledOverviewIsNewYorkAndUsesLongitudeLatitudeOrder() {
        val context = RuntimeEnvironment.getApplication()
        val feature = JSONObject(context.assets.open("maps/new_york.geojson").bufferedReader().use { it.readText() })
        assertEquals("New York", feature.getJSONObject("properties").getString("name"))
        assertEquals("MultiPolygon", feature.getJSONObject("geometry").getString("type"))
        val first = feature.getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0).getJSONArray(0).getJSONArray(0)
        assertTrue(first.getDouble(0) in -81.0..-71.0)
        assertTrue(first.getDouble(1) in 40.0..46.0)
    }
}
