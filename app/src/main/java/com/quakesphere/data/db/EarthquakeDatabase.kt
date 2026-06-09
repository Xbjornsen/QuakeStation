package com.quakesphere.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [EarthquakeEntity::class, VolcanoActivityEntity::class],
    // v1 → v2: added VolcanoActivityEntity. DatabaseModule uses
    // fallbackToDestructiveMigration so existing users' earthquake cache
    // is wiped and re-fetched from USGS on next sync.
    version = 2,
    exportSchema = false
)
abstract class EarthquakeDatabase : RoomDatabase() {
    abstract fun earthquakeDao(): EarthquakeDao
    abstract fun volcanoActivityDao(): VolcanoActivityDao
}
