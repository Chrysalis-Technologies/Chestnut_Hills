package com.chrysalis.treeid.birds

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** BirdNET 2.4 accepts raw mono 48 kHz audio, in three-second float32 windows. */
object BirdAudio {
    const val SAMPLE_RATE = 48_000
    const val WINDOW = SAMPLE_RATE * 3
    const val SECONDS = 12
    const val MAX_SAMPLES = SAMPLE_RATE * SECONDS

    fun window(samples: ShortArray, offset: Int): FloatArray {
        require(offset >= 0 && offset < samples.size)
        return FloatArray(WINDOW) { index ->
            if (offset + index < samples.size) samples[offset + index] / 32768f else 0f
        }
    }

    fun audible(samples: FloatArray): Boolean =
        samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1) > 1e-8

    fun writeWav(file: File, samples: ShortArray) {
        require(samples.size in 1..MAX_SAMPLES)
        val bytes = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes)
        file.outputStream().buffered().use { stream ->
            stream.write(header.array())
            samples.forEach { sample -> stream.write(sample.toInt() and 255); stream.write((sample.toInt() shr 8) and 255) }
        }
    }
}

/** No service or background capture. Cancellation releases the microphone within one read. */
class BirdRecorder {
    @SuppressLint("MissingPermission") // Requested by the screen; revocation is surfaced as an error.
    suspend fun record(progress: suspend (Int) -> Unit): ShortArray = withContext(Dispatchers.IO) {
        val minimum = AudioRecord.getMinBufferSize(BirdAudio.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "This phone cannot record at 48 kHz." }
        val recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(BirdAudio.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, 19_200)).build()
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable." }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start." }
            val samples = ShortArray(BirdAudio.MAX_SAMPLES)
            val deadline = SystemClock.elapsedRealtime() + 17_000
            var count = 0
            var lastSecond = -1
            while (count < samples.size) {
                currentCoroutineContext().ensureActive()
                check(SystemClock.elapsedRealtime() < deadline) { "Microphone stopped delivering audio. Try again." }
                val read = recorder.read(samples, count, minOf(4_800, samples.size - count), AudioRecord.READ_NON_BLOCKING)
                check(read >= 0) { "Microphone read failed ($read)." }
                count += read
                if (count / BirdAudio.SAMPLE_RATE != lastSecond) {
                    lastSecond = count / BirdAudio.SAMPLE_RATE
                    progress(lastSecond)
                }
                if (read == 0) delay(20)
            }
            samples
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
}
