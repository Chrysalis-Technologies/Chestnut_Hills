package com.chrysalis.treeid.nature

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

data class NatureAnalysis(val predictions: List<NaturePrediction>, val modelVersion: String)

/** Independent contracts for the new models; neither existing input path is changed. */
object ExpandedNaturePixels {
    fun plants(photo: Bitmap): ByteBuffer {
        val side = minOf(photo.width, photo.height)
        val square = Bitmap.createBitmap(photo, (photo.width - side) / 2, (photo.height - side) / 2, side, side)
        var reduced: Bitmap? = null
        var loaded: Bitmap? = null
        var cropped: Bitmap? = null
        try {
            // Iris was trained from area-reduced 448px images, then bilinear 366px
            // and a centered 320px crop. Direct resizing aliases fine leaf details.
            reduced = if (side > 448) areaSquare(square, 448) else square
            loaded = Bitmap.createScaledBitmap(reduced, 366, 366, true)
            cropped = Bitmap.createBitmap(loaded, 23, 23, 320, 320)
            return rgb(cropped)
        } finally {
            listOfNotNull(cropped, loaded, reduced, square).distinct().filter { it !== photo }.forEach { it.recycle() }
        }
    }

    fun mushrooms(photo: Bitmap): ByteBuffer {
        // The publisher's mobile graph expects a full-image 384px resize, RGB 0..255.
        val scaled = Bitmap.createScaledBitmap(photo, 384, 384, true)
        return try { rgb(scaled) } finally { if (scaled !== photo) scaled.recycle() }
    }

    internal fun rgb(photo: Bitmap): ByteBuffer {
        val pixels = IntArray(photo.width * photo.height)
        photo.getPixels(pixels, 0, photo.width, 0, 0, photo.width, photo.height)
        val buffer = ByteBuffer.allocateDirect(pixels.size * 12).order(ByteOrder.nativeOrder())
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 255).toFloat())
            buffer.putFloat(((pixel shr 8) and 255).toFloat())
            buffer.putFloat((pixel and 255).toFloat())
        }
        buffer.rewind()
        return buffer
    }

    internal fun areaSquare(photo: Bitmap, size: Int): Bitmap {
        require(photo.width == photo.height && size in 1..photo.width)
        val side = photo.width
        val source = IntArray(side * side)
        photo.getPixels(source, 0, side, 0, 0, side, side)
        val output = IntArray(size * size)
        val scale = side.toDouble() / size
        for (y in 0 until size) for (x in 0 until size) {
            val left = x * scale; val right = (x + 1) * scale
            val top = y * scale; val bottom = (y + 1) * scale
            var red = 0.0; var green = 0.0; var blue = 0.0
            for (sy in floor(top).toInt() until ceil(bottom).toInt().coerceAtMost(side)) {
                val wy = minOf(bottom, sy + 1.0) - maxOf(top, sy.toDouble())
                for (sx in floor(left).toInt() until ceil(right).toInt().coerceAtMost(side)) {
                    val w = wy * (minOf(right, sx + 1.0) - maxOf(left, sx.toDouble()))
                    val pixel = source[sy * side + sx]
                    red += ((pixel shr 16) and 255) * w
                    green += ((pixel shr 8) and 255) * w
                    blue += (pixel and 255) * w
                }
            }
            val area = scale * scale
            output[y * size + x] = (255 shl 24) or
                ((red / area).roundToInt().coerceIn(0, 255) shl 16) or
                ((green / area).roundToInt().coerceIn(0, 255) shl 8) or
                (blue / area).roundToInt().coerceIn(0, 255)
        }
        return Bitmap.createBitmap(output, size, size, Bitmap.Config.ARGB_8888)
    }
}

object ExpandedNatureScores {
    fun probabilities(logits: FloatArray): FloatArray {
        require(logits.isNotEmpty() && logits.all { it.isFinite() }) { "Invalid mushroom model output" }
        val maximum = logits.max().toDouble()
        val weights = logits.map { exp(it.toDouble() - maximum) }
        val sum = weights.sum()
        return FloatArray(logits.size) { (weights[it] / sum).toFloat() }
    }
    fun clear(scores: FloatArray, minimum: Float, margin: Float): Boolean {
        require(scores.size >= 2 && scores.all { it.isFinite() && it in 0f..1f } && kotlin.math.abs(scores.sum() - 1f) < 0.02f)
        val best = scores.sortedDescending().take(2)
        return best[0] >= minimum && best[0] - best[1] >= margin
    }
    fun rank(taxa: List<NatureTaxon>, scores: FloatArray, kind: NatureKind): List<NaturePrediction> {
        require(scores.size == taxa.size && scores.isNotEmpty())
        val best = scores.indices.maxBy { scores[it] }
        // NY and category filters NEVER renormalize or manufacture a replacement
        // for an out-of-catalog global winner. Such a result stays Unknown.
        if (!taxa[best].supports(kind)) return emptyList()
        return taxa.filter { it.supports(kind) && scores[it.index] >= 0.10f }
            .map { NaturePrediction(it, scores[it.index]) }.sortedByDescending { it.score }.take(5)
    }
}

object ExpandedNatureClassifier {
    const val PLANTS_VERSION = "Iris 9 (0d5bd213)"
    const val FUNGI_VERSION = "FungiTastic DINOv3 (4043bd34)"

    fun identify(context: Context, catalog: NatureCatalog, photo: Bitmap, kind: NatureKind): NatureAnalysis {
        val mushroom = kind == NatureKind.MUSHROOM
        val taxa = if (mushroom) catalog.fungi else catalog.plants
        val filename = if (mushroom) "mushroom_model_fp16.tflite" else "plants.tflite"
        val size = if (mushroom) 384 else 320
        val version = if (mushroom) FUNGI_VERSION else PLANTS_VERSION
        val raw = context.assets.openFd("nature-expanded/$filename").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { file ->
                val model = file.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
                Interpreter(model, Interpreter.Options().setNumThreads(2)).use { engine ->
                    check(engine.inputTensorCount == 1 && engine.outputTensorCount == 1)
                    check(engine.getInputTensor(0).shape().contentEquals(intArrayOf(1, size, size, 3)))
                    check(engine.getOutputTensor(0).shape().contentEquals(intArrayOf(1, taxa.size)))
                    check(engine.getInputTensor(0).dataType() == DataType.FLOAT32 && engine.getOutputTensor(0).dataType() == DataType.FLOAT32)
                    val output = arrayOf(FloatArray(taxa.size))
                    engine.run(if (mushroom) ExpandedNaturePixels.mushrooms(photo) else ExpandedNaturePixels.plants(photo), output)
                    output[0]
                }
            }
        }
        val scores = if (mushroom) ExpandedNatureScores.probabilities(raw) else raw
        // Iris uses its published top/margin rule; fungi uses a conservative,
        // uncalibrated heuristic. Neither score is a measured probability of correctness.
        if (ExpandedNatureScores.clear(scores, if (mushroom) 0.50f else 0.70f, if (mushroom) 0.20f else 0.25f)) {
            return NatureAnalysis(ExpandedNatureScores.rank(taxa, scores, kind), version)
        }
        // Keep v0.5 coverage when the larger model is uncertain. Do not blend
        // scores from two models, and record the model that produced the suggestions.
        return NatureClassifier.load(context, catalog).use {
            NatureAnalysis(it.classify(photo, kind), NatureClassifier.VERSION)
        }
    }
}
