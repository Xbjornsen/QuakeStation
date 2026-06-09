package com.quakesphere.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached Smithsonian GVP Weekly Volcanic Activity Report entry.
 * One row per RSS `<item>`; we keep them indefinitely (the cache is
 * pruned by the repository when entries fall off the current week).
 */
@Entity(tableName = "volcano_activity")
data class VolcanoActivityEntity(
    @PrimaryKey val id: String,
    val volcanoName: String,
    val country: String,
    val publishedAt: Long,
    val summary: String,
    val link: String?,
    /** When this row was inserted/refreshed (epoch millis). */
    val fetchedAt: Long
)
