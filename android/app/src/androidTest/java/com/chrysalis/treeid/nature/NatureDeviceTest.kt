package com.chrysalis.treeid.nature

import android.graphics.BitmapFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chrysalis.treeid.data.sightings.PrivateSightingPhotos
import com.chrysalis.treeid.data.sightings.SightingLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NatureDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun fixture(name: String) = instrumentation.context.assets.open("nature/$name.jpg").use { BitmapFactory.decodeStream(it)!! }

    @Test fun flowerPhotoRunsThroughBundledModelAndCorrectCategory() {
        val photo = fixture("flower")
        val catalog = NatureCatalog.load(context)
        try { NatureClassifier.load(context, catalog).use { classifier ->
            val scores = classifier.scores(photo)
            assertEquals("51875", NatureScores.rank(catalog, scores, NatureKind.FLOWER).first().taxon.key)
            assertTrue(NatureScores.rank(catalog, scores, NatureKind.MUSHROOM).isEmpty())
        } } finally { photo.recycle() }
    }

    @Test fun mushroomPhotoRunsThroughBundledModelAndCorrectCategory() {
        val photo = fixture("mushroom")
        val catalog = NatureCatalog.load(context)
        try { NatureClassifier.load(context, catalog).use { classifier ->
            val scores = classifier.scores(photo)
            assertEquals("48715", NatureScores.rank(catalog, scores, NatureKind.MUSHROOM).first().taxon.key)
            assertTrue(NatureScores.rank(catalog, scores, NatureKind.FLOWER).isEmpty())
        } } finally { photo.recycle() }
    }

    @Test fun privatePhotoAndObservationSurviveReopenThenDeleteCleanly() = runBlocking {
        val root = File(context.noBackupFilesDir, "nature-device-test-${UUID.randomUUID()}").apply { mkdirs() }
        val path = File(root, "test.db").absolutePath
        val directory = File(root, "photos")
        val photos = PrivateSightingPhotos(directory)
        val catalog = NatureCatalog.load(context)
        var db = Room.databaseBuilder(context, NatureDatabase::class.java, path).build()
        val photo = fixture("flower")
        try {
            val repository = NatureRepository(db.nature(), photos, catalog, directory)
            val id = repository.save(photo, NatureKind.FLOWER, "51875", 0.9f, 100,
                SightingLocation(44.0, -75.0, 5f, "gps"), "Test fixture, not a field observation")
            db.close(); db = Room.databaseBuilder(context, NatureDatabase::class.java, path).build()
            val row = db.nature().find(id)!!
            assertEquals("flower", row.kind); assertEquals("51875", row.taxonKey)
            assertEquals(44.0, row.latitude); assertTrue(photos.file(row.photoFileName).isFile)
            val reopened = NatureRepository(db.nature(), photos, catalog, directory)
            reopened.update(id, null, "Unknown")
            assertNull(db.nature().find(id)!!.score)
            reopened.delete(id)
            assertFalse(photos.file(row.photoFileName).exists()); assertTrue(db.nature().all().isEmpty())
        } finally { photo.recycle(); db.close(); root.deleteRecursively() }
    }
}
