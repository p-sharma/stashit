package com.ps.stashit.testutil

import com.ps.stashit.core.database.MAX_EMBEDDING_ATTEMPTS
import com.ps.stashit.core.database.Platform
import com.ps.stashit.core.database.TranscriptStatus
import com.ps.stashit.core.metadata.RemoteMetaData
import com.ps.stashit.data.domain.SavedItem
import com.ps.stashit.data.domain.SearchResult
import com.ps.stashit.data.repository.SavedItemRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory repository for ViewModel and worker tests.
 *
 * [maxPendingCalls] guards against loops that never drain the pending queue: once exceeded,
 * getPendingEmbedding throws so a runaway loop fails fast instead of hanging the test JVM.
 */
class FakeSavedItemRepository(
    private val maxPendingCalls: Int = Int.MAX_VALUE
) : SavedItemRepository {
    val items = MutableStateFlow<List<SavedItem>>(emptyList())

    val savedCalls = mutableListOf<Triple<String, Platform, String?>>()
    val deleted = mutableListOf<SavedItem>()
    val searchQueries = mutableListOf<String>()
    val embeddings = mutableMapOf<Long, FloatArray>()
    val metadata = mutableMapOf<Long, RemoteMetaData?>()

    /** Transcript status per item id; items absent from the map are not pending a transcript. */
    val transcriptStatuses = mutableMapOf<Long, TranscriptStatus>()
    var pendingCalls = 0
        private set

    var searchResults: suspend (String) -> List<SearchResult> = { emptyList() }

    override fun observeAll(): Flow<List<SavedItem>> = items

    override fun observePendingEmbeddingCount(): Flow<Int> =
        items.map { list -> list.count { !it.isEmbedded } }

    override suspend fun saveItem(url: String, platform: Platform, caption: String?): Long {
        savedCalls += Triple(url, platform, caption)
        return savedCalls.size.toLong()
    }

    override suspend fun delete(item: SavedItem) {
        deleted += item
        items.value = items.value - item
    }

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        searchQueries += query
        return searchResults(query)
    }

    override suspend fun getPendingEmbedding(afterId: Long, batchSize: Int): List<SavedItem> {
        pendingCalls++
        check(pendingCalls <= maxPendingCalls) {
            "getPendingEmbedding called $pendingCalls times; pending queue never drained"
        }
        return items.value
            .filter { !it.isEmbedded && it.embeddingAttempts < MAX_EMBEDDING_ATTEMPTS && it.id > afterId }
            .sortedBy { it.id }
            .take(batchSize)
    }

    override suspend fun attachEmbedding(
        id: Long,
        embedding: FloatArray,
        tags: List<String>,
        embeddedTranscript: String?
    ) {
        val current = items.value.firstOrNull { it.id == id } ?: return
        if (current.transcript != embeddedTranscript) return
        embeddings[id] = embedding
        items.value = items.value.map { if (it.id == id) it.copy(isEmbedded = true) else it }
    }

    override suspend fun recordEmbeddingFailure(id: Long) {
        items.value = items.value.map { if (it.id == id) it.copy(embeddingAttempts = it.embeddingAttempts + 1) else it }
    }

    override suspend fun attachMetaData(id: Long, metadata: RemoteMetaData?) {
        this.metadata[id] = metadata
        items.value = items.value.map {
            if (it.id == id) {
                it.copy(extractedTitle = metadata?.title, thumbnailUrl = metadata?.thumbnailUrl, metadataFetchedAt = 1L)
            } else {
                it
            }
        }
    }

    override suspend fun getPendingTranscript(afterId: Long, batchSize: Int): List<SavedItem> =
        items.value
            .filter { transcriptStatuses[it.id] == TranscriptStatus.PENDING && it.id > afterId }
            .sortedBy { it.id }
            .take(batchSize)

    override suspend fun attachTranscript(id: Long, transcript: String) {
        transcriptStatuses[id] = TranscriptStatus.DONE
        embeddings.remove(id)
        items.value = items.value.map {
            if (it.id == id) it.copy(transcript = transcript, isEmbedded = false, embeddingAttempts = 0) else it
        }
    }

    override suspend fun markTranscriptUnavailable(id: Long) {
        transcriptStatuses[id] = TranscriptStatus.UNAVAILABLE
    }
}
