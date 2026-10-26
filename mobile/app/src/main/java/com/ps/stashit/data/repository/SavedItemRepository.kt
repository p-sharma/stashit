package com.ps.stashit.data.repository

import androidx.room.Query
import com.google.gson.internal.NumberLimits
import com.ps.stashit.core.database.Platform
import com.ps.stashit.core.metadata.RemoteMetaData
import com.ps.stashit.data.domain.SavedItem
import com.ps.stashit.data.domain.SearchResult
import kotlinx.coroutines.flow.Flow

interface SavedItemRepository {
    fun observeAll(): Flow<List<SavedItem>>
    fun observePendingEmbeddingCount(): Flow<Int>

    suspend fun saveItem(url: String, platform: Platform, caption: String?): Long
    suspend fun delete(item: SavedItem)

    suspend fun search(query: String, limit: Int = 20):List<SearchResult>

    suspend fun getPendingEmbedding(afterId: Long = 0, batchSize: Int): List<SavedItem>
    suspend fun attachEmbedding(id: Long, embedding: FloatArray, tags: List<String>)

    suspend fun attachMetaData(id: Long, metadata: RemoteMetaData?)
}