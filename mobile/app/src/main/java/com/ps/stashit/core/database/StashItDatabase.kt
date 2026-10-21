package com.ps.stashit.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [SavedItemEntity::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class StashItDatabase : RoomDatabase() {
    abstract fun saveItemDao(): SavedItemDao
}