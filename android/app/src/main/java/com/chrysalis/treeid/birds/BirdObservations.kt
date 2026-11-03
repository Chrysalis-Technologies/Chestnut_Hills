package com.chrysalis.treeid.birds

import android.content.Context
import androidx.room.*
import com.chrysalis.treeid.data.sightings.SightingLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

@Entity(tableName = "bird_observations")
data class BirdObservation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val speciesKey: String?,
    val score: Float?,
    val observedAt: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val notes: String = "",
    val audioFileName: String,
    val durationSeconds: Int,
    val modelVersion: String = BirdClassifier.VERSION,
)

@Dao
interface BirdDao {
    @Query("SELECT * FROM bird_observations ORDER BY observedAt DESC, id DESC") fun observeAll(): Flow<List<BirdObservation>>
    @Query("SELECT * FROM bird_observations ORDER BY observedAt DESC, id DESC") suspend fun all(): List<BirdObservation>
    @Query("SELECT * FROM bird_observations WHERE id = :id") suspend fun find(id: Long): BirdObservation?
    @Insert suspend fun insert(value: BirdObservation): Long
    @Query("UPDATE bird_observations SET speciesKey = :key, score = CASE WHEN speciesKey IS :key THEN score ELSE NULL END, notes = :notes WHERE id = :id")
    suspend fun update(id: Long, key: String?, notes: String)
    @Query("DELETE FROM bird_observations WHERE id = :id") suspend fun delete(id: Long)
}

/** Separate database: upgrading never rewrites the tree sightings or tree taxonomy. */
@Database(entities = [BirdObservation::class], version = 1, exportSchema = true)
abstract class BirdDatabase : RoomDatabase() {
    abstract fun birds(): BirdDao
    companion object {
        @Volatile private var instance: BirdDatabase? = null
        fun get(context: Context): BirdDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, BirdDatabase::class.java,
                File(context.noBackupFilesDir, "birds.db").absolutePath).build().also { instance = it }
        }
    }
}

open class BirdRecordings(private val directory: File) {
    fun file(name: String): File {
        require(Regex("[0-9a-f-]{36}\\.wav").matches(name)) { "Invalid recording filename" }
        return File(directory, name)
    }
    open fun save(samples: ShortArray): String {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create recordings directory" }
        val name = "${UUID.randomUUID()}.wav"
        try { BirdAudio.writeWav(file(name), samples) }
        catch (e: Throwable) { file(name).delete(); throw e }
        return name
    }
    open fun delete(name: String) { val file = file(name); check(!file.exists() || file.delete()) { "Cannot delete recording" } }
    fun removeOrphans(keep: Set<String>) {
        directory.listFiles()?.filter { it.name !in keep && Regex("[0-9a-f-]{36}\\.wav").matches(it.name) }
            ?.forEach { delete(it.name) }
    }
}

class BirdRepository(private val dao: BirdDao, val recordings: BirdRecordings, private val catalog: BirdCatalog) {
    private val writes = Mutex()
    fun observeAll() = dao.observeAll()
    suspend fun save(samples: ShortArray, key: String?, score: Float?, time: Long, location: SightingLocation?, notes: String): Long {
        validate(key)
        require(score == null || (score.isFinite() && score in 0f..1f))
        require(samples.size in BirdAudio.WINDOW..BirdAudio.MAX_SAMPLES)
        // Complete the file/DB pair even if the screen is closed while a write is in progress.
        return withContext(Dispatchers.IO + NonCancellable) { writes.withLock {
            val name = recordings.save(samples)
            try { dao.insert(BirdObservation(speciesKey = key, score = score.takeIf { key != null },
                observedAt = time, latitude = location?.latitude, longitude = location?.longitude,
                accuracyMeters = location?.accuracyMeters, notes = notes, audioFileName = name,
                durationSeconds = samples.size / BirdAudio.SAMPLE_RATE)) }
            catch (e: Throwable) { recordings.delete(name); throw e }
        } }
    }
    suspend fun update(id: Long, key: String?, notes: String) { validate(key); dao.update(id, key, notes) }
    suspend fun delete(id: Long) = withContext(Dispatchers.IO + NonCancellable) { writes.withLock {
        val row = dao.find(id) ?: return@withLock
        dao.delete(id)
        recordings.delete(row.audioFileName)
    } }
    suspend fun cleanOrphans() = withContext(Dispatchers.IO) { writes.withLock { recordings.removeOrphans(dao.all().map { it.audioFileName }.toSet()) } }
    private fun validate(key: String?) { require(key == null || (catalog[key] != null && key !in BirdCatalog.NON_EVENTS)) }
}
