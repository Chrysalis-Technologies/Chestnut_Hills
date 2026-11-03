package com.chrysalis.treeid.nature

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NatureAssetsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun catalogPreservesOutputOrderAndSeparatesSupportedGroups() {
        val catalog = NatureCatalog.load(context)
        assertEquals(174, catalog.supported(NatureKind.FLOWER).size)
        assertEquals(18, catalog.supported(NatureKind.MUSHROOM).size)
        assertEquals("Trifolium pratense", catalog.taxa[135].scientificName)
        assertEquals("Amanita muscaria", catalog.taxa[53].scientificName)
        assertEquals(NatureKind.MUSHROOM, catalog["48715"]?.kind)
        assertTrue(catalog.taxa.filter { it.scientificName in setOf("Parmelia sulcata", "Flavoparmelia caperata", "Xanthoria parietina") }.all { it.kind == null })
    }
    @Test fun bundledWeightsHaveThePinnedUpstreamHash() {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open("nature/inat-small-2.tflite").use { input ->
            val bytes = ByteArray(65536)
            while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        }
        assertEquals("eae277b24efa629b998d5f9c091da0576162cb1aad498786087acd001dc86d2c", digest.digest().joinToString("") { "%02x".format(it) })
    }
}
