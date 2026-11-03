package com.chrysalis.treeid.nature

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExpandedNatureAssetsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun text(name: String) = context.assets.open("nature-expanded/$name").bufferedReader().use { it.readText() }
    @Test fun labelOrderMatchesEachPublishedModelAndOldKeysStillResolve() {
        val catalog = NatureCatalog.load(context)
        val names = JSONObject(text("iris-model.json")).getJSONObject("species")
        val plants = text("iris-labels.txt").lines().filter { it.isNotBlank() }
        val fungi = text("fungi-labels.txt").lines().filter { it.isNotBlank() }
        assertEquals(1569,plants.size); assertEquals(2827,fungi.size)
        plants.forEachIndexed { index,label -> assertEquals(names.getString(label),catalog.plants[index].scientificName) }
        fungi.forEachIndexed { index,label -> assertEquals(label,catalog.fungi[index].scientificName) }
        catalog.taxa.forEach { assertEquals(it.scientificName,catalog[it.key]!!.scientificName) }
    }
    @Test fun coverageCountsAndNewYorkSnapshotAgree() {
        val catalog = NatureCatalog.load(context)
        assertEquals("2026-11-03",catalog.snapshotDate)
        assertEquals(808,catalog.supported(NatureKind.FLOWER).size)
        assertEquals(806,catalog.supported(NatureKind.FLOWER,true).size)
        assertEquals(900,catalog.supported(NatureKind.MUSHROOM,true).size)
        assertEquals(14,catalog.supported(NatureKind.FERN,true).size)
        assertEquals(28,catalog.supported(NatureKind.VINE,true).size)
        assertTrue(catalog.supported(NatureKind.FERN).any { it.scientificName == "Matteuccia struthiopteris" })
        assertTrue(catalog.supported(NatureKind.FERN).any { it.scientificName == "Polystichum acrostichoides" })
        assertTrue(catalog.supported(NatureKind.VINE).any { it.scientificName == "Wisteria floribunda" })
        assertFalse(catalog.supported(NatureKind.VINE).any { it.scientificName == "Lonicera tatarica" })
        assertFalse(catalog.supported(NatureKind.MUSHROOM).any { it.scientificName == "Xanthoria parietina" })
        // NY presence is a browsing annotation, never a new probability distribution.
        assertTrue(catalog.supported(NatureKind.FLOWER,true).all { it.nyObservations!! > 0 })
    }
    @Test fun weightsMatchThePinnedPublisherDigests() {
        mapOf("plants.tflite" to "a919ae6a24f7e7717a36d5213a800a3410f7cee0f694f5014ba8eb07f0a6caa3",
            "mushroom_model_fp16.tflite" to "b3c129430abb62d22853781cef3302f301ef7e348e453793c0e05c04ad937610").forEach { (name,expected) ->
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("nature-expanded/$name").use { input ->
                val bytes = ByteArray(65536)
                while(true) { val n=input.read(bytes); if(n<0) break; digest.update(bytes,0,n) }
            }
            assertEquals(expected,digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
