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

    @Query("UPDATE saved_items SET embedding = NULL, embeddingModelVersion = NULL")
    suspend fun clearAllEmbeddings()
}