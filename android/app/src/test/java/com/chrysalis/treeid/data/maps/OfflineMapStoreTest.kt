package com.chrysalis.treeid.data.maps

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflineMapStoreTest {
    private lateinit var context: Context
    private lateinit var store: OfflineMapStore
    private val sources = mutableListOf<File>()
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("offline-maps", Context.MODE_PRIVATE).edit().clear().commit()
        store = OfflineMapStore(context)
    }
    @After fun cleanup() {
        store.close(); sources.forEach { SQLiteDatabase.deleteDatabase(it) }
        File(context.filesDir, "offline-maps").deleteRecursively()
    }

    private fun assertOnlyActiveDatabaseRemains() {
        val files = File(context.filesDir, "offline-maps").listFiles()!!.toList()
        val databases = files.filter { it.extension == "mbtiles" }
        assertEquals(files.map { it.name }.toString(), 1, databases.size)
        val name = databases.single().name
        // SQLite may keep journal/WAL/shared-memory files beside the open database.
        // Companion files from rejected or replaced imports must not remain.
        val allowed = setOf(name, "$name-journal", "$name-wal", "$name-shm")
        assertTrue(files.map { it.name }.toString(), files.all { it.name in allowed })
    }
    private fun map(name: String = "Local woodland", format: String = "png", empty: Boolean = false): File {
        val file = File(context.cacheDir, "test-${UUID.randomUUID()}.mbtiles").also { sources.add(it) }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
            db.execSQL("INSERT INTO metadata VALUES ('name', ?), ('format', ?), ('attribution', '<b>Local survey</b> CC0')", arrayOf(name, format))
            db.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
            if (!empty) {
                val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
                bitmap.recycle()
                db.execSQL("INSERT INTO tiles VALUES (2, 1, 3, ?)", arrayOf(bytes))
            }
        }
        return file
    }

    @Test fun importsReadsTmsTileAndReopensAfterRestart() = runBlocking {
        val source = map()
        val info = source.inputStream().use { store.importStream(it) }
        assertEquals("Local woodland", info.name)
        assertEquals("Local survey CC0", info.attribution)
        assertEquals(1, store.tiles(2, 1, 0, 1, 0).size)
        assertTrue(store.tiles(2, 1, 3, 1, 3).isEmpty())
        assertTrue(store.tiles(10, 0, 0, 0, 0).isEmpty())
        store.close()
        store = OfflineMapStore(context)
        assertEquals(info, store.openSaved())
        assertEquals(1, store.tiles(2, 1, 0, 1, 0).size)
        store.useOverview()
        assertNull(store.openSaved())
        assertTrue(source.exists()) // The user's original import is never deleted.
        assertTrue(File(context.filesDir, "offline-maps").listFiles().orEmpty().isEmpty())
    }

    @Test fun invalidVectorEmptyAndCorruptImportsPreservePreviousMap() = runBlocking {
        map().inputStream().use { store.importStream(it) }
        val invalid = listOf(map(format = "pbf").readBytes(), map(empty = true).readBytes(), "not sqlite".toByteArray())
        invalid.forEach { bytes ->
            var failed = false
            try { store.importStream(ByteArrayInputStream(bytes)) } catch (e: Exception) { failed = true }
            assertTrue(failed)
            assertEquals("Local woodland", store.openSaved()!!.name)
            assertEquals(1, store.tiles(2, 1, 0, 1, 0).size)
        }
        assertOnlyActiveDatabaseRemains()
    }

    @Test fun replacementKeepsOnlyNewPrivateCopyAndOriginals() = runBlocking {
        val first = map("First"); val second = map("Second")
        first.inputStream().use { store.importStream(it) }
        second.inputStream().use { store.importStream(it) }
        assertEquals("Second", store.openSaved()!!.name)
        assertOnlyActiveDatabaseRemains()
        assertTrue(first.exists() && second.exists())
    }
}
