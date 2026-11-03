package com.chrysalis.treeid.birds

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.chrysalis.treeid.data.sightings.SightingLocation
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against the installed app on an actual phone. Does not open the microphone. */
@RunWith(AndroidJUnit4::class)
class BirdDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun bundledModelRunsOnThePhone() = runBlocking {
        val context = instrumentation.targetContext
        val catalog = BirdCatalog.load(context)
        BirdClassifier.load(context, catalog).use { classifier ->
            assertTrue(classifier.classify(ShortArray(BirdAudio.WINDOW)).isEmpty())
            val tone = ShortArray(BirdAudio.WINDOW) { index -> (3276.8 * sin(2 * PI * 2000 * index / 48000)).toInt().toShort() }
            // Deterministic non-bird fixture exercises native inference and the raw-audio path.
            assertTrue(classifier.classify(tone).isEmpty())
        }
    }

    @Test fun privateDatabaseAudioAndGpsRoundTrip() = runBlocking {
        val context = instrumentation.targetContext
        val directory = File(context.noBackupFilesDir, "device-test-${UUID.randomUUID()}").apply { mkdirs() }
        val path = File(directory, "test.db").absolutePath
        var db = Room.databaseBuilder(context, BirdDatabase::class.java, path).build()
        val recordings = BirdRecordings(File(directory, "audio"))
        val catalog = BirdCatalog.load(context)
        try {
            val repository = BirdRepository(db.birds(), recordings, catalog)
            val id = repository.save(ShortArray(BirdAudio.WINDOW) { 123 }, "Turdus migratorius", 0.8f, 100,
                SightingLocation(44.0, -75.0, 5f, "gps"), "Synthetic test, not a field observation")
            db.close(); db = Room.databaseBuilder(context, BirdDatabase::class.java, path).build()
            val row = db.birds().find(id)!!
            assertEquals(44.0, row.latitude); assertEquals("Turdus migratorius", row.speciesKey)
            val audio = recordings.file(row.audioFileName)
            assertEquals(44L + BirdAudio.WINDOW * 2, audio.length())
            val reopened = BirdRepository(db.birds(), recordings, catalog)
            reopened.update(id, "Poecile atricapillus", "Correction")
            assertNull(db.birds().find(id)!!.score)
            reopened.delete(id)
            assertFalse(audio.exists()); assertTrue(db.birds().all().isEmpty())
        } finally { db.close(); directory.deleteRecursively() }
    }

    @Test fun installedAppStaysOfflineAndPrivate() {
        val context = instrumentation.targetContext
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains(Manifest.permission.INTERNET))
        assertTrue(permissions.contains(Manifest.permission.RECORD_AUDIO))
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
