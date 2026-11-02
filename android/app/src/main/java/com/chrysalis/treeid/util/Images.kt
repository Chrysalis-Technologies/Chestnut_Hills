package com.chrysalis.treeid.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
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
     * The centre square of [src] resized to [size] x [size], as interleaved RGB floats (0..255).
     *
     * This reproduces training/train.py's preprocessing exactly: a square centre crop followed by
     * tf.image.resize(method="bilinear", antialias=true), i.e. a separable triangle filter whose
     * width grows with the downscale factor. Matching it keeps the app's answers identical to the
     * accuracy measured during training.
     */
    fun centerSquarePixels(src: Bitmap, size: Int): FloatArray {
        val bitmap = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val side = min(bitmap.width, bitmap.height)
        val pixels = IntArray(side * side)
        bitmap.getPixels(pixels, 0, side, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val kernel = ResizeKernel(side, size)

        // Horizontal pass: side rows x size columns.
        val tmp = FloatArray(side * size * 3)
        for (y in 0 until side) {
            val rowIn = y * side
            for (x in 0 until size) {
                val start = rowIn + kernel.start[x]
                val weights = kernel.weights[x]
                var r = 0f
                var g = 0f
                var b = 0f
                for (t in weights.indices) {
                    val p = pixels[start + t]
                    val w = weights[t]
                    r += w * ((p shr 16) and 0xFF)
                    g += w * ((p shr 8) and 0xFF)
                    b += w * (p and 0xFF)
                }
                val o = (y * size + x) * 3
                tmp[o] = r
                tmp[o + 1] = g
                tmp[o + 2] = b
            }
        }

        // Vertical pass: size rows x size columns.
        val out = FloatArray(size * size * 3)
        for (y in 0 until size) {
            val start = kernel.start[y]
            val weights = kernel.weights[y]
            for (x in 0 until size) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (t in weights.indices) {
                    val i = ((start + t) * size + x) * 3
                    val w = weights[t]
                    r += w * tmp[i]
                    g += w * tmp[i + 1]
                    b += w * tmp[i + 2]
                }
                val o = (y * size + x) * 3
                out[o] = r
                out[o + 1] = g
                out[o + 2] = b
            }
        }
        return out
    }

    /** Per-output-pixel source ranges and normalised triangle-filter weights. */
    private class ResizeKernel(src: Int, dst: Int) {
        val start = IntArray(dst)
        val weights: Array<FloatArray>

        init {
            val scale = src.toDouble() / dst
            val support = max(scale, 1.0)
            weights = Array(dst) { i ->
                val center = (i + 0.5) * scale
                val lo = max(0, floor(center - support).toInt())
                val hi = min(src, ceil(center + support).toInt())
                val raw = DoubleArray(hi - lo) { k -> max(0.0, 1.0 - abs((lo + k + 0.5 - center) / support)) }
                val sum = raw.sum()
                start[i] = lo
                FloatArray(raw.size) { (raw[it] / sum).toFloat() }
            }
        }
    }
}
