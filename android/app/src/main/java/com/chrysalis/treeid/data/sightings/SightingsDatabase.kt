package com.chrysalis.treeid.data.sightings

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface SightingDao {
    @Query("SELECT * FROM sightings ORDER BY observedAt DESC, id DESC")
    fun observeAll(): Flow<List<Sighting>>

    @Query("SELECT * FROM sightings ORDER BY observedAt DESC, id DESC")
    suspend fun all(): List<Sighting>

    @Query("SELECT * FROM sightings WHERE id = :id")
    suspend fun find(id: Long): Sighting?

    @Insert
    suspend fun insert(sighting: Sighting): Long

    // A model probability must not be attributed to a manually changed identification.
    @Query("""
        UPDATE sightings SET notes = :notes,
            confidence = CASE WHEN treeKey IS :treeKey THEN confidence ELSE NULL END,
            treeKey = :treeKey
        WHERE id = :id
    """)
    suspend fun updateDetails(id: Long, treeKey: String?, notes: String): Int

    @Query("DELETE FROM sightings WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [Sighting::class], version = 1, exportSchema = true)
abstract class SightingsDatabase : RoomDatabase() {
    abstract fun sightings(): SightingDao

    companion object {
        @Volatile private var instance: SightingsDatabase? = null

        fun get(context: Context): SightingsDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, SightingsDatabase::class.java, "sightings.db",
            ).build().also { instance = it }
        }
    }
}
