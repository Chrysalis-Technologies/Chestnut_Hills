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
class ExpandedNaturePixelsTest {
    @Test fun newModelsUseRowMajorRgbWithoutDividingBy255() {
        val photo = Bitmap.createBitmap(intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE), 2, 2, Bitmap.Config.ARGB_8888)
        try {
            val actual = FloatArray(12); ExpandedNaturePixels.rgb(photo).asFloatBuffer().get(actual)
            assertArrayEquals(floatArrayOf(255f,0f,0f, 0f,255f,0f, 0f,0f,255f, 255f,255f,255f), actual, 0f)
            assertFalse(photo.isRecycled)
        } finally { photo.recycle() }
    }
    @Test fun areaReductionAveragesFinePatternsInsteadOfAliasingThem() {
        val photo = Bitmap.createBitmap(intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE), 2, 2, Bitmap.Config.ARGB_8888)
        val reduced = ExpandedNaturePixels.areaSquare(photo, 1)
        try { assertEquals(Color.rgb(128,128,128), reduced.getPixel(0,0)); assertFalse(photo.isRecycled) }
        finally { reduced.recycle(); photo.recycle() }
    }
    @Test fun irisCropsTheCenterAndRetainsCallerBitmap() {
        val photo = Bitmap.createBitmap(600, 1000, Bitmap.Config.ARGB_8888)
        photo.eraseColor(Color.RED)
        photo.setPixels(IntArray(600 * 600) { Color.BLUE }, 0, 600, 0, 200, 600, 600)
        try {
            val buffer = ExpandedNaturePixels.plants(photo).asFloatBuffer()
            assertEquals(320 * 320 * 3, buffer.remaining())
            for (i in intArrayOf(0, 160 * 320 * 3, buffer.limit() - 3)) {
                assertEquals(0f, buffer.get(i), 0f); assertEquals(0f, buffer.get(i+1), 0f); assertEquals(255f, buffer.get(i+2), 0f)
            }
            assertFalse(photo.isRecycled)
        } finally { photo.recycle() }
    }
    @Test fun fungiKeepsTheWholeImageRatherThanCroppingTheEnds() {
        val photo = Bitmap.createBitmap(2, 8, Bitmap.Config.ARGB_8888)
        photo.eraseColor(Color.GREEN); photo.setPixel(0,0,Color.RED); photo.setPixel(1,7,Color.BLUE)
        try {
            val buffer = ExpandedNaturePixels.mushrooms(photo).asFloatBuffer()
            assertEquals(384 * 384 * 3, buffer.remaining())
            assertEquals(255f, buffer.get(0), 0f)
            assertEquals(255f, buffer.get(buffer.limit()-1), 0f)
            assertFalse(photo.isRecycled)
        } finally { photo.recycle() }
    }
}
