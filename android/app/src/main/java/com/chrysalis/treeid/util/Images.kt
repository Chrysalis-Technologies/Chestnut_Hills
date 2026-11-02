package com.chrysalis.treeid.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object Images {
    /** Long side of photos kept in memory; far more detail than the model needs. */
    const val MAX_SIDE = 1600

    /** Decodes a picked image, downsampled to about [maxSide] and turned upright using its EXIF data. */
    fun decodeUri(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        return limitSize(rotate(bitmap, rotation), maxSide)
    }

    /**
     * Converts a CameraX capture to an upright bitmap, cropped to the part of the frame that was
     * visible in the viewfinder (CameraX reports that area as the crop rect when a ViewPort is set).
     */
    fun fromImageProxy(image: ImageProxy): Bitmap {
        val full = image.toBitmap()
        val crop = Rect(image.cropRect)
        val cropped = if (
            crop.width() > 0 && crop.height() > 0 &&
            crop.intersect(0, 0, full.width, full.height) &&
            (crop.width() < full.width || crop.height() < full.height)
        ) {
            Bitmap.createBitmap(full, crop.left, crop.top, crop.width(), crop.height())
        } else {
            full
        }
        return limitSize(rotate(cropped, image.imageInfo.rotationDegrees), MAX_SIDE)
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    fun limitSize(src: Bitmap, maxSide: Int): Bitmap {
        val longSide = max(src.width, src.height)
        if (longSide <= maxSide) return src
        val scale = maxSide.toFloat() / longSide
        return Bitmap.createScaledBitmap(
            src, (src.width * scale).roundToInt(), (src.height * scale).roundToInt(), true,
        )
    }

    /**
     * Centre square scaled to [size] x [size]. Downscaling in halving steps approximates the
     * anti-aliased resize used during training (training/train.py: center_crop).
     */
    fun centerSquare(src: Bitmap, size: Int): Bitmap {
        val side = min(src.width, src.height)
        var current = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        while (current.width >= size * 2) {
            current = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
        }
        if (current.width != size) {
            current = Bitmap.createScaledBitmap(current, size, size, true)
        }
        return if (current.config == Bitmap.Config.ARGB_8888) current
        else current.copy(Bitmap.Config.ARGB_8888, false)
    }
}
