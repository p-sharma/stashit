package com.ps.stashit.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StashItDatabaseMigrationTest {
    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashItDatabase::class.java
    )

    @Test
    fun migrate2To3_keepsRowsAndMarksThemSkipped() {
        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO saved_items (id, url, platform, caption, extractedTitle, createdAt)
                VALUES (1, 'https://youtube.com/shorts/a', 'YOUTUBE_SHORTS', 'pasta', 'Carbonara', 1000)
                """
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 3, true, StashItDatabase.MIGRATION_2_3)

        db.query("SELECT url, caption, extractedTitle, transcript, transcriptStatus, transcriptFetchedAt FROM saved_items")
            .use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals("https://youtube.com/shorts/a", cursor.getString(0))
                assertEquals("pasta", cursor.getString(1))
                assertEquals("Carbonara", cursor.getString(2))
                assertNull(cursor.getString(3))
                assertEquals("SKIPPED", cursor.getString(4))
                assertNull(cursor.getString(5))
            }
    }

    @Test
    fun migrate3To4_keepsRowsWithZeroEmbeddingAttempts() {
        helper.createDatabase(dbName, 3).use { db ->
            db.execSQL(
                """
                INSERT INTO saved_items (id, url, platform, caption, createdAt, transcriptStatus)
                VALUES (1, 'https://youtube.com/shorts/a', 'YOUTUBE_SHORTS', 'pasta', 1000, 'DONE')
                """
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 4, true, StashItDatabase.MIGRATION_3_4)

        db.query("SELECT url, caption, transcriptStatus, embeddingAttempts FROM saved_items").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("https://youtube.com/shorts/a", cursor.getString(0))
            assertEquals("pasta", cursor.getString(1))
            assertEquals("DONE", cursor.getString(2))
            assertEquals(0, cursor.getInt(3))
        }
    }

    @Test
    fun migrate2To3_newRowsDefaultToPending() {
        helper.createDatabase(dbName, 2).close()

        val db = helper.runMigrationsAndValidate(dbName, 3, true, StashItDatabase.MIGRATION_2_3)
        db.execSQL(
            "INSERT INTO saved_items (url, platform, createdAt) VALUES ('https://www.tiktok.com/@a/video/1', 'TIKTOK', 0)"
        )

        db.query("SELECT transcriptStatus FROM saved_items").use { cursor ->
            cursor.moveToFirst()
            assertEquals("PENDING", cursor.getString(0))
        }
    }
}
