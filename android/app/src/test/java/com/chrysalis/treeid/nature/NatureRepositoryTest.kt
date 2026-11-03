package com.chrysalis.treeid.nature

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import com.chrysalis.treeid.data.sightings.PrivateSightingPhotos
import com.chrysalis.treeid.data.sightings.SightingLocation
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
class NatureRepositoryTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var db: NatureDatabase
    private lateinit var photos: PrivateSightingPhotos
    private lateinit var repository: NatureRepository
    private lateinit var photo: Bitmap
    private val catalog = NatureCatalog(listOf(NatureTaxon(0, "flower", "Flower", "Flower", NatureKind.FLOWER),
        NatureTaxon(1, "fungus", "Fungus", "Fungus", NatureKind.MUSHROOM),
        NatureTaxon(2, "fern", "Fern", "Fern", NatureKind.FLOWER, fern = true),
        NatureTaxon(3, "vine", "Vine", "Vine", NatureKind.FLOWER, vine = true)))
    private fun open() = Room.databaseBuilder(context, NatureDatabase::class.java, File(root, "test.db").absolutePath).build()
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        root = File(context.noBackupFilesDir, "nature-test-${UUID.randomUUID()}").apply { mkdirs() }
        db = open(); photos = PrivateSightingPhotos(File(root, "photos"))
        repository = NatureRepository(db.nature(), photos, catalog, File(root, "photos"))
        photo = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
    }
    @After fun cleanup() { db.close(); photo.recycle(); root.deleteRecursively() }
    @Test fun savesKindsGpsAndUnknownWithoutInventedScores() = runBlocking {
        val id = repository.save(photo, NatureKind.FLOWER, "flower", 0.8f, 100, SightingLocation(44.0, -75.0, 4f, "photo"), "Flower notes")
        val unknown = repository.save(photo, NatureKind.MUSHROOM, null, 0.8f, 200, null, "Unknown fungus")
        val rows = db.nature().all()
        assertEquals(listOf(unknown, id), rows.map { it.id })
        assertNull(rows[0].score); assertNull(rows[0].latitude); assertNull(rows[0].locationSource)
        assertEquals("flower", rows[1].kind); assertEquals(44.0, rows[1].latitude); assertEquals("photo", rows[1].locationSource)
        assertTrue(photos.file(rows[1].photoFileName).isFile)
    }
    @Test fun notesAndCorrectionsSurviveReopenAndClearScoreOnlyWhenLabelChanges() = runBlocking {
        val id = repository.save(photo, NatureKind.FLOWER, "flower", 0.8f, 100, null, "")
        val filename = db.nature().find(id)!!.photoFileName
        repository.update(id, "flower", "Notes only")
        assertEquals(0.8f, db.nature().find(id)!!.score)
        repository.update(id, null, "Uncertain")
        db.close(); db = open()
        val row = db.nature().find(id)!!
        assertNull(row.taxonKey); assertNull(row.score); assertEquals("Uncertain", row.notes)
        assertEquals(filename, row.photoFileName); assertEquals(100L, row.observedAt)
    }
    @Test fun rejectsCrossCategoryLabelsBeforeWritingAndWhenEditing() = runBlocking {
        try { repository.save(photo, NatureKind.MUSHROOM, "flower", 0.8f, 100, null, ""); fail("Expected invalid category") }
        catch (_: IllegalArgumentException) { assertTrue(db.nature().all().isEmpty()); assertFalse(File(root, "photos").exists()) }
        val id = repository.save(photo, NatureKind.MUSHROOM, "fungus", 0.8f, 100, null, "")
        try { repository.update(id, "flower", ""); fail("Expected invalid category") }
        catch (_: IllegalArgumentException) { assertEquals("fungus", db.nature().find(id)!!.taxonKey) }
    }
    @Test fun failedInsertRollsBackPhoto() = runBlocking {
        val failing = object : NatureDao by db.nature() { override suspend fun insert(row: NatureObservation): Long = throw IOException("disk full") }
        try { NatureRepository(failing, photos, catalog, File(root, "photos")).save(photo, NatureKind.FLOWER, "flower", 0.8f, 100, null, ""); fail("Expected failure") }
        catch (_: IOException) { assertTrue(File(root, "photos").listFiles().orEmpty().isEmpty()) }
    }
    @Test fun newKindsAndModelProvenanceSurviveReopen() = runBlocking {
        val fern = repository.save(photo, NatureKind.FERN, "fern", 0.9f, 100, null, "Fern notes", ExpandedNatureClassifier.PLANTS_VERSION)
        val vine = repository.save(photo, NatureKind.VINE, "vine", 0.8f, 200,
            SightingLocation(44.0, -75.0, 4f, "gps"), "Vine notes", ExpandedNatureClassifier.PLANTS_VERSION)
        db.close(); db = open()
        assertEquals("fern", db.nature().find(fern)!!.kind)
        assertEquals("vine", db.nature().find(vine)!!.kind)
        assertEquals(ExpandedNatureClassifier.PLANTS_VERSION, db.nature().find(fern)!!.modelVersion)
        assertEquals(44.0, db.nature().find(vine)!!.latitude)
        repository = NatureRepository(db.nature(), photos, catalog, File(root, "photos"))
        try { repository.update(fern, "vine", "Wrong category"); fail("Expected invalid category") }
        catch (_: IllegalArgumentException) { assertEquals("fern", db.nature().find(fern)!!.taxonKey) }
    }
    @Test fun legacyFlowerRowsAcceptPlantSubgroupsWithoutChangingStoredKindOrModel() = runBlocking {
        val id = repository.save(photo, NatureKind.FLOWER, "flower", 0.8f, 100, null, "Old record")
        repository.update(id, "vine", "Corrected plant")
        val row = db.nature().find(id)!!
        assertEquals("flower", row.kind); assertEquals("vine", row.taxonKey)
        assertEquals(NatureClassifier.VERSION, row.modelVersion); assertNull(row.score)
        repository.update(id, "fern", "Another correction")
        assertEquals("fern", db.nature().find(id)!!.taxonKey)
    }
    @Test fun failedDatabaseDeleteKeepsPhotoAndRow() = runBlocking {
        val id = repository.save(photo, NatureKind.FLOWER, "flower", 0.8f, 100, null, "")
        val file = photos.file(db.nature().find(id)!!.photoFileName)
        val failing = object : NatureDao by db.nature() { override suspend fun delete(id: Long): Unit = throw IOException("disk error") }
        try { NatureRepository(failing, photos, catalog, File(root, "photos")).delete(id); fail("Expected failure") }
        catch (_: IOException) { assertTrue(file.exists()); assertNotNull(db.nature().find(id)) }
    }
    @Test fun deleteAndOrphanRecoveryRemoveOnlyTheirOwnPhotos() = runBlocking {
        val id = repository.save(photo, NatureKind.FLOWER, "flower", 0.8f, 100, null, "")
        val keep = photos.file(db.nature().find(id)!!.photoFileName)
        val orphan = photos.save(photo)
        repository.cleanOrphans()
        assertFalse(photos.file(orphan).exists()); assertTrue(keep.exists())
        repository.delete(id); repository.delete(id)
        assertFalse(keep.exists()); assertTrue(db.nature().all().isEmpty())
    }
}
