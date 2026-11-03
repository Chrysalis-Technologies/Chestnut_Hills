package com.chrysalis.treeid.nature

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

enum class NatureKind(val key: String, val title: String, val singular: String) {
    FLOWER("flower", "Plants", "Plant"), MUSHROOM("mushroom", "Mushrooms", "Mushroom"),
    FERN("fern", "Ferns & allies", "Fern or ally"), VINE("vine", "Vines", "Vine");
    companion object { fun fromKey(key: String) = entries.first { it.key == key } }
}

data class NatureTaxon(val index: Int, val key: String, val scientificName: String,
    val commonName: String, val kind: NatureKind?, val fern: Boolean = false,
    val vine: Boolean = false, val nyObservations: Int? = null) {
    fun supports(group: NatureKind) = when (group) {
        NatureKind.FLOWER -> kind == NatureKind.FLOWER || fern
        NatureKind.MUSHROOM -> kind == NatureKind.MUSHROOM
        NatureKind.FERN -> fern
        NatureKind.VINE -> vine
    }
}
data class NaturePrediction(val taxon: NatureTaxon, val score: Float)

class NatureCatalog(val taxa: List<NatureTaxon>, val plants: List<NatureTaxon> = emptyList(),
    val fungi: List<NatureTaxon> = emptyList(), val snapshotDate: String = "2026-11-03") {
    // Existing numeric keys remain valid for saved v0.5 records. Per-model arrays
    // retain their own output order; canonical lookup is only for labels/editing.
    private val byKey = (taxa + plants + fungi).associateBy { it.key }
    private val groups = NatureKind.entries.associateWith { group -> byKey.values.filter { it.supports(group) } }
    operator fun get(key: String?): NatureTaxon? = byKey[key]
    fun supported(kind: NatureKind, newYorkOnly: Boolean = false) = groups.getValue(kind)
        .let { rows -> if (newYorkOnly) rows.filter { it.nyObservations != null } else rows }
    companion object {
        fun load(context: Context): NatureCatalog {
            val original = JSONArray(context.assets.open("nature/catalog.json").bufferedReader().use { it.readText() })
            val bundle = JSONObject(context.assets.open("nature-expanded/catalog.json").bufferedReader().use { it.readText() })
            fun read(json: JSONArray, count: Int): List<NatureTaxon> {
                val rows = (0 until json.length()).map { index -> json.getJSONObject(index).let { row ->
                NatureTaxon(row.getInt("index"), row.getString("key"), row.getString("scientificName"),
                    row.getString("commonName"), if (row.isNull("kind")) null else NatureKind.fromKey(row.getString("kind")),
                    row.optBoolean("fern"), row.optBoolean("vine"), if (row.isNull("nyObservations")) null else row.getInt("nyObservations"))
                } }
                check(rows.size == count && rows.map { it.index } == (0 until count).toList()) { "Nature taxonomy/model mismatch" }
                check(rows.map { it.key }.distinct().size == rows.size) { "Duplicate nature labels" }
                return rows
            }
            val legacy = read(bundle.getJSONArray("legacy"), 507)
            check(original.length() == legacy.size && legacy.all { original.getJSONObject(it.index).getString("key") == it.key })
            return NatureCatalog(legacy, read(bundle.getJSONArray("iris"), 1569),
                read(bundle.getJSONArray("fungi"), 2827), bundle.getString("snapshotDate"))
        }
    }
}

/** A separate input contract; the tree classifier's preprocessing is never used or changed. */
object NaturePixels {
    const val SIZE = 299
    fun buffer(photo: Bitmap): ByteBuffer {
        val side = minOf(photo.width, photo.height)
        val cropped = Bitmap.createBitmap(photo, (photo.width - side) / 2, (photo.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, SIZE, SIZE, true)
        try {
            val pixels = IntArray(SIZE * SIZE)
            scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            val result = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
            // Match iNaturalist's Android Small 2 path: x-major RGB, float 0..255,
            // center-square crop and Android bilinear resize. No extra normalization.
            for (x in 0 until SIZE) for (y in 0 until SIZE) {
                val color = pixels[y * SIZE + x]
                result.putFloat(((color shr 16) and 255).toFloat())
                result.putFloat(((color shr 8) and 255).toFloat())
                result.putFloat((color and 255).toFloat())
            }
            result.rewind()
            return result
        } finally {
            if (scaled !== photo && scaled !== cropped) scaled.recycle()
            if (cropped !== photo) cropped.recycle()
        }
    }
}

object NatureScores {
    const val MIN_TOP_SCORE = 0.25f
    const val MIN_CANDIDATE_SCORE = 0.10f
    fun rank(catalog: NatureCatalog, scores: FloatArray, kind: NatureKind): List<NaturePrediction> {
        require(scores.size == catalog.taxa.size && scores.all { it.isFinite() && it in 0f..1f }) { "Invalid nature model output" }
        require(kotlin.math.abs(scores.sum() - 1f) < 0.02f) { "Nature output is not normalized" }
        val best = scores.indices.maxByOrNull { scores[it] } ?: return emptyList()
        // Compare with ALL 507 outputs first. Selecting Mushrooms must not turn a
        // bird/plant result into an artificially confident mushroom identification.
        if (!catalog.taxa[best].supports(kind) || scores[best] < MIN_TOP_SCORE) return emptyList()
        return catalog.taxa.filter { it.supports(kind) && scores[it.index] >= MIN_CANDIDATE_SCORE }
            .map { NaturePrediction(it, scores[it.index]) }.sortedByDescending { it.score }.take(5)
    }
}

class NatureClassifier private constructor(private val engine: Interpreter, private val catalog: NatureCatalog) : AutoCloseable {
    fun classify(photo: Bitmap, kind: NatureKind): List<NaturePrediction> = NatureScores.rank(catalog, scores(photo), kind)
    fun scores(photo: Bitmap): FloatArray {
        val output = arrayOf(FloatArray(catalog.taxa.size))
        engine.run(NaturePixels.buffer(photo), output)
        return output[0]
    }
    override fun close() = engine.close()
    companion object {
        const val VERSION = "iNaturalist Small 2 (v25.01.15)"
        fun load(context: Context, catalog: NatureCatalog): NatureClassifier {
            val model = context.assets.openFd("nature/inat-small-2.tflite").use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { file ->
                    file.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
                }
            }
            val engine = Interpreter(model, Interpreter.Options().setNumThreads(2))
            try {
                check(engine.inputTensorCount == 1 && engine.outputTensorCount == 1)
                check(engine.getInputTensor(0).shape().contentEquals(intArrayOf(1, 299, 299, 3)))
                check(engine.getOutputTensor(0).shape().contentEquals(intArrayOf(1, catalog.taxa.size)))
                check(engine.getInputTensor(0).dataType() == DataType.FLOAT32 && engine.getOutputTensor(0).dataType() == DataType.FLOAT32)
                return NatureClassifier(engine, catalog)
            } catch (e: Throwable) { engine.close(); throw e }
        }
    }
}
