package com.chrysalis.treeid.data.sightings

import android.graphics.Bitmap
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

interface SightingPhotos {
    fun save(photo: Bitmap): String
    fun delete(name: String)
    fun file(name: String): File
}

/** Called on the repository's IO dispatcher; never changes the identification bitmap. */
class PrivateSightingPhotos(private val directory: File) : SightingPhotos {
    override fun save(photo: Bitmap): String {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create photo directory")
        val name = "${UUID.randomUUID()}.jpg"
        val target = file(name)
        val scale = (1024f / max(photo.width, photo.height)).coerceAtMost(1f)
        val copy = if (scale < 1f) Bitmap.createScaledBitmap(
            photo, (photo.width * scale).roundToInt().coerceAtLeast(1),
            (photo.height * scale).roundToInt().coerceAtLeast(1), true,
        ) else photo
        try {
            target.outputStream().use {
                if (!copy.compress(Bitmap.CompressFormat.JPEG, 85, it)) throw IOException("Cannot encode photo")
            }
            return name
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            if (copy !== photo) copy.recycle()
        }
    }

    override fun delete(name: String) {
        val target = file(name)
        if (target.exists() && !target.delete()) throw IOException("Cannot delete sighting photo")
    }

    override fun file(name: String): File {
        require(name == File(name).name && name.endsWith(".jpg"))
        return File(directory, name)
    }
}
