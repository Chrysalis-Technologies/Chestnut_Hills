package com.chrysalis.treeid.nature

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NaturePixelsTest {
    @Test fun preservesRgbRangeAndUpstreamColumnOrderWithoutRecyclingInput() {
        val photo = Bitmap.createBitmap(299, 299, Bitmap.Config.ARGB_8888)
        photo.setPixel(0, 0, Color.rgb(0, 128, 255))
        photo.setPixel(0, 1, Color.rgb(7, 8, 9))
        photo.setPixel(1, 0, Color.rgb(4, 5, 6))
        val buffer = NaturePixels.buffer(photo).asFloatBuffer()
        assertEquals(299 * 299 * 3, buffer.remaining())
        assertEquals(0f, buffer.get(0), 0f); assertEquals(128f, buffer.get(1), 0f); assertEquals(255f, buffer.get(2), 0f)
        assertEquals(7f, buffer.get(3), 0f); assertEquals(4f, buffer.get(299 * 3), 0f)
        assertFalse(photo.isRecycled); photo.recycle()
    }
    @Test fun centerCropRemovesPortraitAndLandscapeEdges() {
        for ((width, height) in listOf(299 to 599, 599 to 299)) {
            val photo = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            photo.eraseColor(Color.RED)
            val left = (width - 299) / 2; val top = (height - 299) / 2
            for (y in top until top + 299) for (x in left until left + 299) photo.setPixel(x, y, Color.BLUE)
            val buffer = NaturePixels.buffer(photo).asFloatBuffer()
            while (buffer.hasRemaining()) { assertEquals(0f, buffer.get(), 0f); assertEquals(0f, buffer.get(), 0f); assertEquals(255f, buffer.get(), 0f) }
            photo.recycle()
        }
    }
    @Test fun resizesSmallImagesWithoutExtraNormalization() {
        val photo = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(10, 20, 30)) }
        val buffer = NaturePixels.buffer(photo).asFloatBuffer()
        assertEquals(10f, buffer.get(0), 0f); assertEquals(30f, buffer.get(buffer.limit() - 1), 0f)
        photo.recycle()
    }
}
