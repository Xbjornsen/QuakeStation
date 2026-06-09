package com.quakesphere.di

import android.content.Context
import androidx.room.Room
import com.quakesphere.data.db.EarthquakeDao
import com.quakesphere.data.db.EarthquakeDatabase
import com.quakesphere.data.db.VolcanoActivityDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideEarthquakeDatabase(
        @ApplicationContext context: Context
    ): EarthquakeDatabase {
        return Room.databaseBuilder(
            context,
            EarthquakeDatabase::class.java,
            "quakesphere.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideEarthquakeDao(database: EarthquakeDatabase): EarthquakeDao {
        return database.earthquakeDao()
    }

    @Provides
    @Singleton
    fun provideVolcanoActivityDao(database: EarthquakeDatabase): VolcanoActivityDao =
        database.volcanoActivityDao()
}
