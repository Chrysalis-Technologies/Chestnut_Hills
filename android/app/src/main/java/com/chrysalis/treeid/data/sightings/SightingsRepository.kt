package com.chrysalis.treeid.data.sightings

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class SightingsRepository(
    private val dao: SightingDao,
    val photos: SightingPhotos,
    private val treeKeys: Set<String>,
) {
    fun observeAll() = dao.observeAll()
    suspend fun all() = dao.all()

    suspend fun save(
        photo: Bitmap,
        treeKey: String?,
        confidence: Float?,
        observedAt: Long,
        location: SightingLocation?,
    ): Long = withContext(Dispatchers.IO + NonCancellable) {
        validateTree(treeKey)
        require(confidence == null || (confidence.isFinite() && confidence in 0f..1f))
        val photoName = photos.save(photo)
        try {
            dao.insert(Sighting(
                treeKey = treeKey,
                confidence = confidence.takeIf { treeKey != null },
                observedAt = observedAt,
                latitude = location?.latitude,
                longitude = location?.longitude,
                accuracyMeters = location?.accuracyMeters,
                locationSource = location?.source,
                photoFileName = photoName,
            ))
        } catch (e: Exception) {
            // Never leave an unreferenced photo when the database rejects a save.
            runCatching { photos.delete(photoName) }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
    }

    suspend fun update(id: Long, treeKey: String?, notes: String) = withContext(Dispatchers.IO) {
        validateTree(treeKey)
        check(dao.updateDetails(id, treeKey, notes) == 1) { "This sighting no longer exists" }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO + NonCancellable) {
        val sighting = dao.find(id) ?: return@withContext
        dao.delete(id)
        photos.delete(sighting.photoFileName)
    }

    private fun validateTree(key: String?) {
        require(key == null || key in treeKeys) { "Choose a tree from the field guide or Unknown tree" }
    }
}
