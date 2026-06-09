package com.quakesphere.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VolcanoActivityDao {

    /** Most recent first. */
    @Query("SELECT * FROM volcano_activity ORDER BY publishedAt DESC")
    fun getAll(): Flow<List<VolcanoActivityEntity>>

    /** Entries reported in the last [sinceMillis] window. */
    @Query("SELECT * FROM volcano_activity WHERE publishedAt >= :sinceMillis ORDER BY publishedAt DESC")
    fun getRecent(sinceMillis: Long): Flow<List<VolcanoActivityEntity>>

    /** Suspend snapshot used by the worker for delta-notification logic. */
    @Query("SELECT * FROM volcano_activity")
    suspend fun snapshot(): List<VolcanoActivityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<VolcanoActivityEntity>)

    @Query("DELETE FROM volcano_activity WHERE publishedAt < :cutoffMillis")
    suspend fun pruneOlderThan(cutoffMillis: Long)
}
