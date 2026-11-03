package com.chrysalis.treeid.data.maps

import com.chrysalis.treeid.Screen
import com.chrysalis.treeid.birds.BirdCatalog
import com.chrysalis.treeid.birds.BirdObservation
import com.chrysalis.treeid.data.SpeciesCatalog
import com.chrysalis.treeid.data.sightings.Sighting
import java.util.Locale

data class ObservationMarker(val key: String, val name: String, val time: Long, val latitude: Double?,
    val longitude: Double?, val bird: Boolean, val unknown: Boolean, val destination: Screen) {
    val located: Boolean get() = latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
        longitude?.let { it.isFinite() && it in -180.0..180.0 } == true
    val place: String get() = if (located) String.format(Locale.US, "%.5f, %.5f", latitude, longitude) else "No location"
    companion object {
        fun tree(sighting: Sighting, catalog: SpeciesCatalog) = ObservationMarker("tree:${sighting.id}",
            sighting.treeKey?.let { catalog[it]?.commonName } ?: "Unknown tree", sighting.observedAt,
            sighting.latitude, sighting.longitude, false, sighting.treeKey == null, Screen.SightingDetail(sighting.id))
        fun bird(row: BirdObservation, catalog: BirdCatalog) = ObservationMarker("bird:${row.id}",
            catalog[row.speciesKey]?.commonName ?: "Unknown call", row.observedAt,
            row.latitude, row.longitude, true, row.speciesKey == null, Screen.BirdDetail(row.id))
    }
}
