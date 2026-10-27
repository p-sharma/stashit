package com.ps.stashit.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedItemDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: SavedItemEntity): Long

    @Update
    suspend fun update(item: SavedItemEntity)

    @Delete
    suspend fun delete(item: SavedItemEntity)

    @Query("SELECT * from saved_items ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SavedItemEntity>>

    @Query("SELECT * from saved_items WHERE embedding IS NOT NULL")
    suspend fun getAllEmbedded(): List<SavedItemEntity>

    @Query("SELECT * FROM saved_items WHERE embedding IS NULL AND id > :afterId ORDER BY id LIMIT :batchSize")
    suspend fun getPendingEmbedding(afterId: Long = 0, batchSize: Int = 20): List<SavedItemEntity>

    @Query("SELECT COUNT(*) FROM saved_items where embedding IS NULL")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM saved_items WHERE id = :id")
    suspend fun getById(id: Long): SavedItemEntity?

    @Query("SELECT * FROM saved_items WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): SavedItemEntity?

    @Query(
        """
        UPDATE saved_items
        SET extractedTitle=:title, thumbnailUrl = :thumbnailUrl, metadataFetchedAt = :fetchedAt
        WHERE id = :id
    """
    )
    suspend fun updateMetaData(id: Long, title: String?, thumbnailUrl: String?, fetchedAt: Long)

    @Query(
        """
        SELECT * FROM saved_items
        WHERE transcriptStatus = 'PENDING' AND platform IN ('YOUTUBE_SHORTS', 'TIKTOK') AND id > :afterId
        ORDER BY id LIMIT :batchSize
    """
    )
    suspend fun getPendingTranscript(afterId: Long = 0, batchSize: Int = 20): List<SavedItemEntity>

    // Clearing the embedding queues the item for re-embedding with the transcript included.
    @Query(
        """
        UPDATE saved_items
        SET transcript = :transcript, transcriptStatus = 'DONE', transcriptFetchedAt = :fetchedAt, embedding = NULL
        WHERE id = :id
    """
    )
    suspend fun attachTranscript(id: Long, transcript: String, fetchedAt: Long)

    @Query("UPDATE saved_items SET transcriptStatus = :status, transcriptFetchedAt = :fetchedAt WHERE id = :id")
    suspend fun updateTranscriptStatus(id: Long, status: TranscriptStatus, fetchedAt: Long)

    // The transcript guard drops a write computed before CaptionWorker attached a newer transcript.
    // embedding is the Converters BLOB: Room expands an array query parameter into one placeholder
    // per element instead of applying the FloatArray converter.
    @Query(
        """
        UPDATE saved_items
        SET embedding = :embedding, tags = :tags, classifiedAt = :classifiedAt
        WHERE id = :id AND transcript IS :transcript
    """
    )
    suspend fun attachEmbeddingIfCurrent(
        id: Long,
        embedding: ByteArray,
        tags: String,
        classifiedAt: Long,
        transcript: String?
    ): Int

    @Query("UPDATE saved_items SET embedding = NULL, embeddingModelVersion = NULL")
    suspend fun clearAllEmbeddings()
}