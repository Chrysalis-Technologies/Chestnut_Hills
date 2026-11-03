package com.chrysalis.treeid.birds

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class BirdAudioTest {
    @Test fun pcmConversionPreservesSignAmplitudeAndPadsFinalWindow() {
        val converted = BirdAudio.window(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE), 0)
        assertEquals(144000, converted.size)
        assertEquals(-1f, converted[0], 0f)
        assertEquals(0f, converted[1], 0f)
        assertEquals(32767f / 32768, converted[2], 0f)
        assertEquals(0f, converted.last(), 0f)
    }
    @Test fun windowOffsetsDoNotRepeatOrSkipSamples() {
        val audio = ShortArray(BirdAudio.WINDOW + 2) { (it % 1000).toShort() }
        val last = BirdAudio.window(audio, BirdAudio.WINDOW)
        assertEquals(audio[BirdAudio.WINDOW] / 32768f, last[0], 0f)
        assertEquals(audio.last() / 32768f, last[1], 0f)
        assertEquals(0f, last[2], 0f)
    }
    @Test fun silenceAndNearSilenceDoNotProduceForcedMatches() {
        assertFalse(BirdAudio.audible(FloatArray(BirdAudio.WINDOW)))
        assertFalse(BirdAudio.audible(FloatArray(BirdAudio.WINDOW) { 1e-5f }))
        assertTrue(BirdAudio.audible(FloatArray(BirdAudio.WINDOW) { 0.02f }))
    }
    @Test fun wavHasCorrectLittleEndianHeaderAndPcmData() {
        val file = Files.createTempFile("bird-audio-test", ".wav").toFile()
        try {
            BirdAudio.writeWav(file, shortArrayOf(-32768, 0, 32767))
            val bytes = file.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(50, bytes.size)
            assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals(42, buffer.getInt(4))
            assertEquals("WAVEfmt ", bytes.copyOfRange(8, 16).toString(Charsets.US_ASCII))
            assertEquals(1, buffer.getShort(20).toInt()); assertEquals(1, buffer.getShort(22).toInt())
            assertEquals(48000, buffer.getInt(24)); assertEquals(96000, buffer.getInt(28))
            assertEquals(16, buffer.getShort(34).toInt()); assertEquals(6, buffer.getInt(40))
            assertEquals(-32768, buffer.getShort(44).toInt()); assertEquals(32767, buffer.getShort(48).toInt())
        } finally { file.delete() }
    }
}
