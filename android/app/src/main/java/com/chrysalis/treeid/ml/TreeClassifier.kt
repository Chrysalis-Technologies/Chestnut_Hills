package com.chrysalis.treeid.ml

import android.content.Context
import android.graphics.Bitmap
import com.chrysalis.treeid.util.Images
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Runs the bundled TensorFlow Lite tree classifier entirely on the device.
 *
 * Input contract (must match training/train.py): RGB, centre square crop, resized to the model's
 * input size, float32 values in 0..255 (normalisation is inside the model). Output: softmax
 * probabilities in the order of labels.txt.
 */
class TreeClassifier private constructor(
    private val interpreter: Interpreter,
    val labels: List<String>,
    val inputSize: Int,
    val info: ModelInfo,
) : Closeable {

    data class ModelInfo(
        val version: String,
        val numClasses: Int,
        val testTop1: Double?,
        val testTop3: Double?,
        val trainPhotos: Int?,
    )

    data class Prediction(val key: String, val confidence: Float)

    private val input: ByteBuffer =
        ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(labels.size) }
    private val pixels = IntArray(inputSize * inputSize)

    /** Returns the [topK] most likely classes, averaging the photo and its mirror image. */
    @Synchronized
    fun classify(photo: Bitmap, topK: Int = 5): List<Prediction> {
        val square = Images.centerSquare(photo, inputSize)
        square.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val a = run(mirror = false)
        val b = run(mirror = true)
        val probs = FloatArray(labels.size) { (a[it] + b[it]) / 2f }
        return probs.indices
            .sortedByDescending { probs[it] }
            .take(topK)
            .map { Prediction(labels[it], probs[it]) }
    }

    private fun run(mirror: Boolean): FloatArray {
        input.rewind()
        for (y in 0 until inputSize) {
            val row = y * inputSize
            for (x in 0 until inputSize) {
                val px = pixels[row + if (mirror) inputSize - 1 - x else x]
                input.putFloat(((px shr 16) and 0xFF).toFloat())
                input.putFloat(((px shr 8) and 0xFF).toFloat())
                input.putFloat((px and 0xFF).toFloat())
            }
        }
        input.rewind()
        interpreter.run(input, output)
        return output[0].copyOf()
    }

    override fun close() = interpreter.close()

    companion object {
        const val MODEL = "tree_model.tflite"
        const val LABELS = "labels.txt"
        const val INFO = "model_info.json"

        /** Loads the model from assets, or returns null when this build has no model in it. */
        fun load(context: Context): TreeClassifier? {
            val assets = context.assets
            val names = assets.list("")?.toSet().orEmpty()
            if (MODEL !in names || LABELS !in names) return null

            val labels = assets.open(LABELS).bufferedReader().use { r ->
                r.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            }
            val json = if (INFO in names) {
                JSONObject(assets.open(INFO).bufferedReader().use { it.readText() })
            } else {
                JSONObject()
            }

            val options = Interpreter.Options().setNumThreads(
                Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            )
            val interpreter = Interpreter(mapAsset(context, MODEL), options)
            val inShape = interpreter.getInputTensor(0).shape() // [1, size, size, 3]
            val outShape = interpreter.getOutputTensor(0).shape() // [1, classes]
            check(inShape.size == 4 && inShape[1] == inShape[2] && inShape[3] == 3) {
                "Unexpected model input shape ${inShape.contentToString()}"
            }
            check(outShape.last() == labels.size) {
                "labels.txt has ${labels.size} entries but the model has ${outShape.last()} outputs"
            }

            val info = ModelInfo(
                version = json.optString("model_version", "unknown"),
                numClasses = labels.size,
                testTop1 = json.optDouble("test_top1").takeUnless { it.isNaN() },
                testTop3 = json.optDouble("test_top3").takeUnless { it.isNaN() },
                trainPhotos = json.optInt("train_photos", -1).takeIf { it > 0 },
            )
            return TreeClassifier(interpreter, labels, inShape[1], info)
        }

        private fun mapAsset(context: Context, name: String): MappedByteBuffer =
            context.assets.openFd(name).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
    }
}
