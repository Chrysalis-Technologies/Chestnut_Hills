package com.chrysalis.treeid.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One entry of the field guide (and one class the model can predict). */
data class Species(
    val key: String,
    val commonName: String,
    val scientificName: String,
    val family: String,
    val group: String,
    val status: String,
    val features: List<String>,
    val lookalikes: List<String>,
) {
    /** The catch-all "not a tree in this guide" class. */
    val isOther: Boolean get() = group == "other"

    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return commonName.contains(q, ignoreCase = true) ||
            scientificName.contains(q, ignoreCase = true) ||
            family.contains(q, ignoreCase = true)
    }
}

/** The field guide bundled from species/nys_trees.json (shared with the training pipeline). */
class SpeciesCatalog(val region: String, val all: List<Species>) {
    private val byKey = all.associateBy { it.key }

    operator fun get(key: String): Species? = byKey[key]

    val trees: List<Species> = all.filter { !it.isOther }.sortedBy { it.commonName.lowercase() }

    companion object {
        private const val ASSET = "nys_trees.json"

        fun load(context: Context): SpeciesCatalog {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val classes = root.getJSONArray("classes")
            val list = (0 until classes.length()).map { i ->
                val o = classes.getJSONObject(i)
                Species(
                    key = o.getString("key"),
                    commonName = o.getString("common_name"),
                    scientificName = o.optString("scientific_name"),
                    family = o.optString("family"),
                    group = o.optString("group"),
                    status = o.optString("status"),
                    features = o.optJSONArray("features").toStringList(),
                    lookalikes = o.optJSONArray("lookalikes").toStringList(),
                )
            }
            return SpeciesCatalog(root.optString("region", "New York State"), list)
        }

        private fun JSONArray?.toStringList(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }
    }
}
