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
    /**
     * Stores the embedding only if the row's transcript still equals [embeddedTranscript],
     * so a vector computed before a transcript arrived never overwrites the re-embed request.
     */
    suspend fun attachEmbedding(id: Long, embedding: FloatArray, tags: List<String>, embeddedTranscript: String?)

    /** Counts a failed embedding run; the item stops being pending after MAX_EMBEDDING_ATTEMPTS. */
    suspend fun recordEmbeddingFailure(id: Long)

    suspend fun attachMetaData(id: Long, metadata: RemoteMetaData?)

    suspend fun getPendingTranscript(afterId: Long = 0, batchSize: Int): List<SavedItem>
    suspend fun attachTranscript(id: Long, transcript: String)
    suspend fun markTranscriptUnavailable(id: Long)
}