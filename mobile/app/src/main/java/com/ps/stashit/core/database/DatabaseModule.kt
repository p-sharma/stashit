package com.ps.stashit.core.database

import android.content.Context
import androidx.room.Room
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
    fun provideStashItDatabase(
        @ApplicationContext context: Context
    ): StashItDatabase {
        return Room.databaseBuilder(
            context,
            StashItDatabase::class.java,
            "stashit.db"
        )
            .fallbackToDestructiveMigration(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideSavedItemDao(database: StashItDatabase): SavedItemDao {
        return database.saveItemDao()
    }
}