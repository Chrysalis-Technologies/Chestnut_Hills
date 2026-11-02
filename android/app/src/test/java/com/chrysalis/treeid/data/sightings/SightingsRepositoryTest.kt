package com.chrysalis.treeid.data.sightings

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SightingsRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: SightingsDatabase
    private lateinit var repository: SightingsRepository
    private lateinit var photos: TestPhotos
    private lateinit var photo: Bitmap
    private val databaseName = "sightings-test-${UUID.randomUUID()}.db"
    private val keys = setOf("oak", "maple")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = openDatabase()
        photos = TestPhotos()
        repository = SightingsRepository(database.sightings(), photos, keys)
        photo = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        photo.recycle()
    }

    private fun openDatabase() = Room.databaseBuilder(context, SightingsDatabase::class.java, databaseName).build()

    @Test fun savesAllFieldsAndObservesNewestFirstIncludingTimestampTies() = runBlocking {
        val first = repository.save(photo, "oak", 0.7f, 100, SightingLocation(44.0, -75.0, 5f, "photo"))
        val second = repository.save(photo, "maple", 0.4f, 200, null)
        val third = repository.save(photo, null, null, 200, null)
        val records = repository.observeAll().first()
        assertEquals(listOf(third, second, first), records.map { it.id })
        val saved = records.last()
        assertEquals("oak", saved.treeKey)
        assertEquals(0.7f, saved.confidence)
        assertEquals(100L, saved.observedAt)
        assertEquals(44.0, saved.latitude)
        assertEquals(-75.0, saved.longitude)
        assertEquals(5f, saved.accuracyMeters)
        assertEquals("photo", saved.locationSource)
        assertEquals("", saved.notes)
        assertTrue(saved.photoFileName in photos.saved)
        assertSame(photo, photos.lastPhoto)
    }

    @Test fun unknownAndUnlocatedSightingsDoNotInventCoordinatesOrConfidence() = runBlocking {
        repository.save(photo, null, 0.7f, 100, null)
        val saved = repository.all().single()
        assertNull(saved.treeKey)
        assertNull(saved.confidence)
        assertNull(saved.latitude)
        assertNull(saved.longitude)
        assertNull(saved.accuracyMeters)
        assertNull(saved.locationSource)
    }

    @Test fun editsPersistAcrossDatabaseReopenAndChangingTreeClearsModelConfidence() = runBlocking {
        val id = repository.save(photo, "oak", 0.7f, 100, SightingLocation(44.0, -75.0, 3f, "gps"))
        repository.update(id, "oak", "Old oak\nBeside the fence")
        assertEquals(0.7f, repository.all().single().confidence)
        val photoName = repository.all().single().photoFileName
        repository.update(id, "maple", "Corrected after checking bark")
        database.close()
        database = openDatabase()
        repository = SightingsRepository(database.sightings(), photos, keys)
        val saved = repository.all().single()
        assertEquals("maple", saved.treeKey)
        assertEquals("Corrected after checking bark", saved.notes)
        assertNull(saved.confidence)
        assertEquals(100L, saved.observedAt)
        assertEquals(44.0, saved.latitude)
        assertEquals(photoName, saved.photoFileName)
        repository.update(id, null, saved.notes)
        assertNull(repository.all().single().treeKey)
    }

    @Test fun deleteRemovesRowAndPhotoAndIsIdempotent() = runBlocking {
        val id = repository.save(photo, "oak", 0.7f, 100, null)
        repository.delete(id)
        repository.delete(id)
        assertTrue(repository.all().isEmpty())
        assertTrue(photos.saved.isEmpty())
        assertEquals(1, photos.deleted.size)
    }

    @Test fun failedInsertRollsBackTheNewPhoto() = runBlocking {
        val failingDao = object : SightingDao by database.sightings() {
            override suspend fun insert(sighting: Sighting): Long = throw IOException("disk full")
        }
        val failing = SightingsRepository(failingDao, photos, keys)
        try {
            failing.save(photo, "oak", 0.7f, 100, null)
            fail("Expected database failure")
        } catch (_: IOException) {
            assertTrue(repository.all().isEmpty())
            assertTrue(photos.saved.isEmpty())
            assertEquals(1, photos.deleted.size)
        }
    }

    @Test fun failedPhotoWriteDoesNotInsertARow() = runBlocking {
        val brokenPhotos = object : SightingPhotos by photos {
            override fun save(photo: Bitmap): String = throw IOException("disk full")
        }
        try {
            SightingsRepository(database.sightings(), brokenPhotos, keys).save(photo, "oak", 0.7f, 100, null)
            fail("Expected photo failure")
        } catch (_: IOException) {
            assertTrue(repository.all().isEmpty())
        }
    }

    @Test fun failedDatabaseDeleteKeepsPhotoAndRow() = runBlocking {
        val id = repository.save(photo, "oak", 0.7f, 100, null)
        val failingDao = object : SightingDao by database.sightings() {
            override suspend fun delete(id: Long): Unit = throw IOException("disk error")
        }
        try {
            SightingsRepository(failingDao, photos, keys).delete(id)
            fail("Expected database failure")
        } catch (_: IOException) {
            assertEquals(1, repository.all().size)
            assertEquals(1, photos.saved.size)
        }
    }

    @Test fun rejectsKeysOutsideTheGuideBeforeWritingAPhoto() = runBlocking {
        try {
            repository.save(photo, "_other", 0.7f, 100, null)
            fail("Expected invalid tree")
        } catch (_: IllegalArgumentException) {
            assertTrue(photos.saved.isEmpty())
            assertTrue(repository.all().isEmpty())
        }
    }

    private class TestPhotos : SightingPhotos {
        val saved = mutableSetOf<String>()
        val deleted = mutableListOf<String>()
        var lastPhoto: Bitmap? = null
        override fun save(photo: Bitmap): String {
            lastPhoto = photo
            return "${UUID.randomUUID()}.jpg".also { saved += it }
        }
        override fun delete(name: String) { saved -= name; deleted += name }
        override fun file(name: String) = File(name)
    }
}
