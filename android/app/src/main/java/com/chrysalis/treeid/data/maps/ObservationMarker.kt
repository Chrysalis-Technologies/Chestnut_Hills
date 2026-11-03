package com.chrysalis.treeid.data.maps

import com.chrysalis.treeid.Screen
import com.chrysalis.treeid.birds.BirdCatalog
import com.chrysalis.treeid.birds.BirdObservation
import com.chrysalis.treeid.data.SpeciesCatalog
import com.chrysalis.treeid.data.sightings.Sighting
import java.util.Locale
import com.chrysalis.treeid.nature.NatureCatalog
import com.chrysalis.treeid.nature.NatureKind
import com.chrysalis.treeid.nature.NatureObservation

enum class MarkerKind(val label: String, val color: Long) {
    TREE("Tree", 0xff245c3a), BIRD("Bird call", 0xff245ca0),
    FLOWER("Plant", 0xff8e3d8e), MUSHROOM("Mushroom", 0xff8b5e3c),
    FERN("Fern or ally", 0xff147d75), VINE("Vine", 0xff6a6b25),
}

data class ObservationMarker(val key: String, val name: String, val time: Long, val latitude: Double?,
    val longitude: Double?, val kind: MarkerKind, val unknown: Boolean, val destination: Screen) {
    val bird: Boolean get() = kind == MarkerKind.BIRD
    val located: Boolean get() = latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
        longitude?.let { it.isFinite() && it in -180.0..180.0 } == true
    val place: String get() = if (located) String.format(Locale.US, "%.5f, %.5f", latitude, longitude) else "No location"
    companion object {
        fun tree(sighting: Sighting, catalog: SpeciesCatalog) = ObservationMarker("tree:${sighting.id}",
            sighting.treeKey?.let { catalog[it]?.commonName } ?: "Unknown tree", sighting.observedAt,
            sighting.latitude, sighting.longitude, MarkerKind.TREE, sighting.treeKey == null, Screen.SightingDetail(sighting.id))
        fun bird(row: BirdObservation, catalog: BirdCatalog) = ObservationMarker("bird:${row.id}",
            catalog[row.speciesKey]?.commonName ?: "Unknown call", row.observedAt,
            row.latitude, row.longitude, MarkerKind.BIRD, row.speciesKey == null, Screen.BirdDetail(row.id))
        fun nature(row: NatureObservation, catalog: NatureCatalog) = ObservationMarker("nature:${row.id}",
            catalog[row.taxonKey]?.commonName ?: "Unknown ${row.kind}", row.observedAt, row.latitude, row.longitude,
            when (NatureKind.fromKey(row.kind)) {
                NatureKind.FLOWER -> MarkerKind.FLOWER
                NatureKind.MUSHROOM -> MarkerKind.MUSHROOM
                NatureKind.FERN -> MarkerKind.FERN
                NatureKind.VINE -> MarkerKind.VINE
            }, row.taxonKey == null, Screen.NatureDetail(row.id))
    }
}
