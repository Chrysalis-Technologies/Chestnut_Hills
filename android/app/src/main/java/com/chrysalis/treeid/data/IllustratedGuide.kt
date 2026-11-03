package com.chrysalis.treeid.data

import android.content.Context
import org.json.JSONObject

data class GuidePhoto(
    val asset: String, val part: String, val caption: String, val taxon: String,
    val credit: String, val license: String, val licenseUrl: String, val sourceUrl: String, val sourceTitle: String,
)
data class SeasonalGuide(val seasons: Map<String, String>, val photos: List<GuidePhoto>, val sources: List<String>)

/** Supplemental guide data keyed by the unchanged model/species IDs. No network loader. */
class IllustratedGuide(val entries: Map<String, SeasonalGuide>) {
    companion object {
        fun load(context: Context): IllustratedGuide {
            val root = JSONObject(context.assets.open("guide/guide.json").bufferedReader().use { it.readText() })
                .getJSONObject("entries")
            return IllustratedGuide(root.keys().asSequence().associateWith { key ->
                val entry = root.getJSONObject(key)
                val seasons = entry.getJSONObject("seasons")
                val photos = entry.getJSONArray("photos")
                val sources = entry.getJSONArray("sources")
                SeasonalGuide(
                    listOf("Spring", "Summer", "Autumn", "Winter").associateWith { seasons.getString(it) },
                    (0 until photos.length()).map { n ->
                        val p = photos.getJSONObject(n)
                        GuidePhoto(p.getString("asset"), p.getString("part"), p.getString("caption"), p.getString("taxon"),
                            p.getString("credit"), p.getString("license"), p.getString("license_url"), p.getString("source_url"),
                            p.getString("source_title"))
                    }, (0 until sources.length()).map { sources.getString(it) },
                )
            })
        }
    }
}
