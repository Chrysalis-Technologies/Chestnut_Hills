package com.chrysalis.treeid.birds

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

data class BirdLabel(val key: String, val commonName: String)
data class BirdPrediction(val label: BirdLabel, val score: Float)

class BirdCatalog(val labels: List<BirdLabel>) {
    private val byKey = labels.associateBy { it.key }
    operator fun get(key: String?): BirdLabel? = byKey[key]
    companion object {
        // Official BirdNET 2.4 non-event classes; never presented as species suggestions.
        val NON_EVENTS = setOf("Human non-vocal", "Human vocal", "Human whistle", "Noise", "Dog",
            "Engine", "Environmental", "Fireworks", "Gun", "Power tools", "Siren")
        fun load(context: Context): BirdCatalog = BirdCatalog(context.assets.open("birds/labels.txt")
            .bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() }.map { line ->
                val parts = line.split('_', limit = 2)
                require(parts.size == 2) { "Invalid BirdNET label" }
                BirdLabel(parts[0], parts[1])
            }.toList() }.also { labels ->
                check(labels.size == 6522 && labels.map { it.key }.distinct().size == labels.size) { "BirdNET labels do not match model" }
            })
    }
}

object BirdScores {
    // Model outputs are logits, not probabilities. This is a display threshold, not calibration.
    const val MIN_SCORE = 0.25f
    fun sigmoid(logit: Float): Float = (1.0 / (1.0 + exp(-logit.toDouble()))).toFloat()
    fun rank(labels: List<BirdLabel>, logits: List<FloatArray>): List<BirdPrediction> {
        require(logits.all { it.size == labels.size && it.all(Float::isFinite) }) { "Invalid BirdNET output" }
        return labels.mapIndexedNotNull { index, label ->
            val score = logits.maxOfOrNull { sigmoid(it[index]) } ?: 0f
            if (label.key in BirdCatalog.NON_EVENTS || score < MIN_SCORE) null else BirdPrediction(label, score)
        }.sortedByDescending { it.score }.take(5)
    }
}

class BirdClassifier private constructor(private val interpreter: Interpreter, private val catalog: BirdCatalog) : AutoCloseable {
    suspend fun classify(samples: ShortArray): List<BirdPrediction> {
        require(samples.size in BirdAudio.WINDOW..BirdAudio.MAX_SAMPLES)
        val outputs = mutableListOf<FloatArray>()
        val input = ByteBuffer.allocateDirect(BirdAudio.WINDOW * 4).order(ByteOrder.nativeOrder())
        for (offset in samples.indices step BirdAudio.WINDOW) {
            currentCoroutineContext().ensureActive()
            val window = BirdAudio.window(samples, offset)
            if (!BirdAudio.audible(window)) continue
            input.clear(); window.forEach { input.putFloat(it) }; input.rewind()
            val output = arrayOf(FloatArray(catalog.labels.size))
            interpreter.run(input, output)
            outputs += output[0]
        }
        return BirdScores.rank(catalog.labels, outputs)
    }

    override fun close() = interpreter.close()

    companion object {
        const val VERSION = "BirdNET 2.4 FP16"
        fun load(context: Context, catalog: BirdCatalog): BirdClassifier {
            val model = context.assets.openFd("birds/birdnet-v2.4-fp16.tflite").use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { file ->
                    file.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
                }
            }
            val engine = Interpreter(model, Interpreter.Options().setNumThreads(2))
            try {
                check(engine.inputTensorCount == 1 && engine.outputTensorCount == 1)
                check(engine.getInputTensor(0).shape().contentEquals(intArrayOf(1, BirdAudio.WINDOW)))
                check(engine.getOutputTensor(0).shape().contentEquals(intArrayOf(1, catalog.labels.size)))
                check(engine.getInputTensor(0).dataType() == DataType.FLOAT32 && engine.getOutputTensor(0).dataType() == DataType.FLOAT32)
                return BirdClassifier(engine, catalog)
            } catch (e: Throwable) { engine.close(); throw e }
        }
    }
}
