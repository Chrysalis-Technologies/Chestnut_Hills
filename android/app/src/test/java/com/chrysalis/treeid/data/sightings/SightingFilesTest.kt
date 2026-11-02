package com.chrysalis.treeid.data.sightings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SightingFilesTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var photos: PrivateSightingPhotos

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        directory = File(context.filesDir, "sightings-test-${UUID.randomUUID()}")
        photos = PrivateSightingPhotos(directory)
    }

    @After fun tearDown() { directory.deleteRecursively() }

    @Test fun writesPrivateJpegLimitedTo1024WithoutMutatingIdentificationPhoto() {
        val original = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888)
        try {
            val name = photos.save(original)
            val file = photos.file(name)
            assertEquals(directory, file.parentFile)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            assertEquals("image/jpeg", bounds.outMimeType)
            assertEquals(1024, bounds.outWidth)
            assertEquals(512, bounds.outHeight)
            assertFalse(original.isRecycled)
            assertEquals(1600, original.width)
            photos.delete(name)
            assertFalse(file.exists())
        } finally {
            original.recycle()
        }
    }

    @Test fun readsGpsFromOriginalPhotoIncludingEquatorAndUnknownAccuracy() = runBlocking {
        val original = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        val file = photos.file(photos.save(original))
        original.recycle()
        ExifInterface(file).apply {
            setLatLong(0.0, -75.5)
            saveAttributes()
        }
        val location = PhotoLocation.read(context, Uri.fromFile(file))!!
        assertEquals(0.0, location.latitude, 0.0)
        assertEquals(-75.5, location.longitude, 0.00001)
        assertEquals("photo", location.source)
        assertNull(location.accuracyMeters)
    }

    @Test fun fileProviderExposesOnlyExportCacheNotPrivatePhotos() {
        val exportDirectory = File(context.cacheDir, "sighting-exports").apply { mkdirs() }
        val export = File(exportDirectory, "test.csv").apply { writeText("date,notes\r\n") }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.sighting-exports", export)
            assertEquals("content", uri.scheme)
            assertEquals("date,notes\r\n", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            try {
                FileProvider.getUriForFile(context, "${context.packageName}.sighting-exports", File(directory, "private.jpg"))
                fail("Private photos must not be shareable through the export provider")
            } catch (_: IllegalArgumentException) {
                // Only the dedicated export-cache path is exposed.
            }
        } finally {
            export.delete()
        }
    }
}
