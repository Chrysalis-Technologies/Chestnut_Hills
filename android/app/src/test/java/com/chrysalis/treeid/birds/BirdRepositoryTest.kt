package com.chrysalis.treeid.birds

import android.content.Context
import androidx.room.Room
import com.chrysalis.treeid.data.sightings.SightingLocation
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
class BirdRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: BirdDatabase
    private lateinit var repository: BirdRepository
    private lateinit var recordings: BirdRecordings
    private lateinit var directory: File
    private lateinit var databaseFile: File
    private val catalog = BirdCatalog(listOf(BirdLabel("robin", "Robin"), BirdLabel("chickadee", "Chickadee")))
    private val samples = ShortArray(BirdAudio.WINDOW) { 123 }

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        directory = File(context.noBackupFilesDir, "birds-test-${UUID.randomUUID()}").apply { mkdirs() }
        databaseFile = File(directory, "observations.db")
        database = open()
        recordings = BirdRecordings(File(directory, "audio"))
        repository = BirdRepository(database.birds(), recordings, catalog)
    }
    private fun open() = Room.databaseBuilder(context, BirdDatabase::class.java, databaseFile.absolutePath).build()
    @After fun cleanup() { database.close(); directory.deleteRecursively() }

    @Test fun savesAudioGpsAndUnknownRecordsWithoutInventingScoresOrLocations() = runBlocking {
        val known = repository.save(samples, "robin", 0.8f, 100, SightingLocation(44.0, -75.0, 4f, "gps"), "Beside barn")
        val unknown = repository.save(samples, null, 0.8f, 200, null, "Unknown")
        val rows = repository.observeAll().first()
        assertEquals(listOf(unknown, known), rows.map { it.id })
        assertNull(rows.first().score); assertNull(rows.first().latitude); assertNull(rows.first().longitude)
        assertEquals(44.0, rows.last().latitude); assertEquals(4f, rows.last().accuracyMeters)
        assertEquals("Beside barn", rows.last().notes)
        assertEquals(3, rows.last().durationSeconds)
        assertEquals(BirdClassifier.VERSION, rows.last().modelVersion)
        assertEquals(44L + samples.size * 2, recordings.file(rows.last().audioFileName).length())
        assertTrue(databaseFile.isFile)
    }

    @Test fun editsPersistAndCorrectionClearsScoreWhilePreservingAudioTimeAndGps() = runBlocking {
        val id = repository.save(samples, "robin", 0.8f, 100, SightingLocation(44.0, -75.0, 4f, "gps"), "")
        val audio = database.birds().find(id)!!.audioFileName
        repository.update(id, "robin", "Notes only")
        assertEquals(0.8f, database.birds().find(id)!!.score)
        repository.update(id, "chickadee", "Corrected")
        database.close(); database = open()
        val row = database.birds().find(id)!!
        assertEquals("chickadee", row.speciesKey); assertEquals("Corrected", row.notes); assertNull(row.score)
        assertEquals(audio, row.audioFileName); assertEquals(100L, row.observedAt); assertEquals(44.0, row.latitude)
    }

    @Test fun deletingRemovesAudioAndRowAndIsIdempotent() = runBlocking {
        val id = repository.save(samples, "robin", 0.8f, 100, null, "")
        val file = recordings.file(database.birds().find(id)!!.audioFileName)
        repository.delete(id); repository.delete(id)
        assertFalse(file.exists()); assertTrue(database.birds().all().isEmpty())
    }

    @Test fun failedInsertRemovesNewRecording() = runBlocking {
        val failing = object : BirdDao by database.birds() {
            override suspend fun insert(value: BirdObservation): Long = throw IOException("disk full")
        }
        try { BirdRepository(failing, recordings, catalog).save(samples, "robin", 0.8f, 100, null, ""); fail("Expected failure") }
        catch (_: IOException) { assertTrue(File(directory, "audio").listFiles().orEmpty().isEmpty()) }
    }

    @Test fun failedAudioWriteDoesNotCreateRow() = runBlocking {
        val failing = object : BirdRecordings(File(directory, "broken")) {
            override fun save(samples: ShortArray): String = throw IOException("disk full")
        }
        try { BirdRepository(database.birds(), failing, catalog).save(samples, "robin", 0.8f, 100, null, ""); fail("Expected failure") }
        catch (_: IOException) { assertTrue(database.birds().all().isEmpty()) }
    }

    @Test fun failedDatabaseDeleteKeepsRecording() = runBlocking {
        val id = repository.save(samples, "robin", 0.8f, 100, null, "")
        val file = recordings.file(database.birds().find(id)!!.audioFileName)
        val failing = object : BirdDao by database.birds() { override suspend fun delete(id: Long): Unit = throw IOException("disk error") }
        try { BirdRepository(failing, recordings, catalog).delete(id); fail("Expected failure") }
        catch (_: IOException) { assertTrue(file.exists()); assertNotNull(database.birds().find(id)) }
    }

    @Test fun orphanRecoveryKeepsReferencedRecordingsOnly() = runBlocking {
        val id = repository.save(samples, "robin", 0.8f, 100, null, "")
        val orphan = recordings.save(samples)
        repository.cleanOrphans()
        assertFalse(recordings.file(orphan).exists())
        assertTrue(recordings.file(database.birds().find(id)!!.audioFileName).exists())
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsPathTraversal() { recordings.file("../../outside.wav") }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownSpeciesKey() = runBlocking {
        repository.save(samples, "invalid", 0.8f, 100, null, "")
        Unit
    }
}
