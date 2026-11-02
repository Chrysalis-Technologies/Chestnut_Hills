package com.chrysalis.treeid.data.sightings

import com.chrysalis.treeid.data.SpeciesCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

enum class SightingExportFormat(val extension: String, val mimeType: String) {
    CSV("csv", "text/csv"),
    GEOJSON("geojson", "application/geo+json"),
}

object SightingExports {
    fun csv(sightings: List<Sighting>, catalog: SpeciesCatalog): String = buildString {
        append("date,tree_common_name,tree_scientific_name,lat,lon,accuracy_m,notes\r\n")
        sightings.forEach { sighting ->
            val tree = sighting.treeKey?.let { catalog[it] }
            append(listOf(
                quoted(Instant.ofEpochMilli(sighting.observedAt).toString()),
                quoted(tree?.commonName ?: "Unknown tree"),
                quoted(tree?.scientificName.orEmpty()),
                sighting.latitude?.toString().orEmpty(),
                sighting.longitude?.toString().orEmpty(),
                sighting.accuracyMeters?.toString().orEmpty(),
                quoted(sighting.notes),
            ).joinToString(","))
            append("\r\n")
        }
    }

    fun geoJson(sightings: List<Sighting>, catalog: SpeciesCatalog): String {
        val features = JSONArray()
        sightings.forEach { sighting ->
            val tree = sighting.treeKey?.let { catalog[it] }
            val geometry = if (sighting.latitude != null && sighting.longitude != null) {
                JSONObject().put("type", "Point")
                    .put("coordinates", JSONArray().put(sighting.longitude).put(sighting.latitude))
            } else JSONObject.NULL
            val properties = JSONObject()
                .put("date", Instant.ofEpochMilli(sighting.observedAt).toString())
                .put("tree_key", sighting.treeKey ?: JSONObject.NULL)
                .put("tree_common_name", tree?.commonName ?: "Unknown tree")
                .put("tree_scientific_name", tree?.scientificName.orEmpty())
                .put("confidence", sighting.confidence ?: JSONObject.NULL)
                .put("accuracy_m", sighting.accuracyMeters ?: JSONObject.NULL)
                .put("location_source", sighting.locationSource ?: JSONObject.NULL)
                .put("notes", sighting.notes)
            features.put(JSONObject().put("type", "Feature").put("id", sighting.id)
                .put("geometry", geometry).put("properties", properties))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString(2)
    }

    private fun quoted(value: String): String {
        // Quoting alone does not stop spreadsheet apps interpreting notes as formulas.
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') ||
            value.startsWith('\t') || value.startsWith('\r')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
}
