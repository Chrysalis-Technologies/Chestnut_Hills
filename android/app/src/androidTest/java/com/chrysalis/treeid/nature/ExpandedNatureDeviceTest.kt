package com.chrysalis.treeid.nature

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android inference smoke tests, not accuracy or edibility validation. */
@RunWith(AndroidJUnit4::class)
class ExpandedNatureDeviceTest {
    private fun identify(name: String, kind: NatureKind): NatureAnalysis {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val photo = instrumentation.context.assets.open("nature/$name.jpg").use { BitmapFactory.decodeStream(it)!! }
        return try { ExpandedNatureClassifier.identify(context, NatureCatalog.load(context), photo, kind) }
        finally { photo.recycle() }
    }
    @Test fun irisRunsRedCloverFixtureWithoutFallingBack() {
        val result = identify("flower", NatureKind.FLOWER)
        assertEquals(ExpandedNatureClassifier.PLANTS_VERSION, result.modelVersion)
        assertEquals("51875", result.predictions.first().taxon.key)
    }
    @Test fun dinov3RunsFlyAgaricFixtureWithoutFallingBack() {
        val result = identify("mushroom", NatureKind.MUSHROOM)
        assertEquals(ExpandedNatureClassifier.FUNGI_VERSION, result.modelVersion)
        assertEquals("48715", result.predictions.first().taxon.key)
    }
}
