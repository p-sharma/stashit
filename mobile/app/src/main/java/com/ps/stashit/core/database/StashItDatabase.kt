package com.ps.stashit.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SavedItemEntity::class], version = 4, exportSchema = true)
@TypeConverters(Converters::class)
abstract class StashItDatabase : RoomDatabase() {
    abstract fun saveItemDao(): SavedItemDao

    companion object {
        // Rows saved before transcripts existed are marked SKIPPED, so they are not backfilled.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_items ADD COLUMN transcript TEXT")
                db.execSQL("ALTER TABLE saved_items ADD COLUMN transcriptStatus TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE saved_items ADD COLUMN transcriptFetchedAt INTEGER")
                db.execSQL("UPDATE saved_items SET transcriptStatus = 'SKIPPED'")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_items ADD COLUMN embeddingAttempts INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
