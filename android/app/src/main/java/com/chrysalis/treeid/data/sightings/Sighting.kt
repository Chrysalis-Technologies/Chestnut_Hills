package com.chrysalis.treeid.data.sightings

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A null tree key is an explicitly unknown tree, not the model's _other class. */
@Entity(tableName = "sightings")
data class Sighting(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val treeKey: String?,
    val confidence: Float?,
    val observedAt: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val locationSource: String?,
    val notes: String = "",
    val photoFileName: String,
)

data class SightingLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val source: String,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(accuracyMeters == null || (accuracyMeters.isFinite() && accuracyMeters >= 0))
    }
}
