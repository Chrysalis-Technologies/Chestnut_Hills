package com.chrysalis.treeid.nature

import android.content.Context
import android.graphics.Bitmap
import androidx.room.*
import com.chrysalis.treeid.data.sightings.SightingLocation
import com.chrysalis.treeid.data.sightings.SightingPhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

@Entity(tableName = "nature_observations")
data class NatureObservation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val taxonKey: String?,
    val score: Float?,
    val observedAt: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val locationSource: String?,
    val notes: String = "",
    val photoFileName: String,
    val modelVersion: String = NatureClassifier.VERSION,
)

@Dao
interface NatureDao {
    @Query("SELECT * FROM nature_observations ORDER BY observedAt DESC, id DESC") fun observeAll(): Flow<List<NatureObservation>>
    @Query("SELECT * FROM nature_observations ORDER BY observedAt DESC, id DESC") suspend fun all(): List<NatureObservation>
    @Query("SELECT * FROM nature_observations WHERE id = :id") suspend fun find(id: Long): NatureObservation?
    @Insert suspend fun insert(row: NatureObservation): Long
    @Query("UPDATE nature_observations SET taxonKey = :key, score = CASE WHEN taxonKey IS :key THEN score ELSE NULL END, notes = :notes WHERE id = :id")
    suspend fun update(id: Long, key: String?, notes: String): Int
    @Query("DELETE FROM nature_observations WHERE id = :id") suspend fun delete(id: Long)
}

/** New database only: no migration of existing tree or bird observations. */
@Database(entities = [NatureObservation::class], version = 1, exportSchema = true)
abstract class NatureDatabase : RoomDatabase() {
    abstract fun nature(): NatureDao
    companion object {
        @Volatile private var instance: NatureDatabase? = null
        fun get(context: Context): NatureDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, NatureDatabase::class.java,
                File(context.noBackupFilesDir, "nature.db").absolutePath).build().also { instance = it }
        }
    }
}

class NatureRepository(private val dao: NatureDao, val photos: SightingPhotos, private val catalog: NatureCatalog,
    private val photoDirectory: File) {
    private val writes = Mutex()
    fun observeAll() = dao.observeAll()
    suspend fun save(photo: Bitmap, kind: NatureKind, key: String?, score: Float?, time: Long,
        location: SightingLocation?, notes: String): Long = withContext(Dispatchers.IO + NonCancellable) { writes.withLock {
        validate(kind, key)
        require(score == null || (score.isFinite() && score in 0f..1f))
        val name = photos.save(photo)
        try { dao.insert(NatureObservation(kind = kind.key, taxonKey = key, score = score.takeIf { key != null },
            observedAt = time, latitude = location?.latitude, longitude = location?.longitude,
            accuracyMeters = location?.accuracyMeters, locationSource = location?.source, notes = notes, photoFileName = name)) }
        catch (e: Throwable) { runCatching { photos.delete(name) }.exceptionOrNull()?.let(e::addSuppressed); throw e }
    } }
    suspend fun update(id: Long, key: String?, notes: String) = withContext(Dispatchers.IO) { writes.withLock {
        val row = dao.find(id) ?: error("Observation no longer exists")
        validate(NatureKind.fromKey(row.kind), key)
        check(dao.update(id, key, notes) == 1) { "Observation no longer exists" }
    } }
    suspend fun delete(id: Long) = withContext(Dispatchers.IO + NonCancellable) { writes.withLock {
        val row = dao.find(id) ?: return@withLock
        dao.delete(id)
        photos.delete(row.photoFileName)
    } }
    suspend fun cleanOrphans() = withContext(Dispatchers.IO) { writes.withLock {
        val keep = dao.all().map { it.photoFileName }.toSet()
        photoDirectory.listFiles()?.filter { it.name !in keep && Regex("[0-9a-f-]{36}\\.jpg").matches(it.name) }
            ?.forEach { photos.delete(it.name) }
    } }
    private fun validate(kind: NatureKind, key: String?) { require(key == null || catalog[key]?.kind == kind) { "Choose a supported ${kind.singular.lowercase()} or Unknown" } }
}
