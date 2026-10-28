package com.ps.stashit.data.repository

import android.util.Log
import com.ps.stashit.core.CaptionScheduler
import com.ps.stashit.core.EmbeddingScheduler
import com.ps.stashit.core.database.Converters
import com.ps.stashit.core.database.Platform
import com.ps.stashit.core.database.SavedItemDao
import com.ps.stashit.core.database.SavedItemEntity
import com.ps.stashit.core.database.TranscriptStatus
import com.ps.stashit.core.metadata.RemoteMetaData
import com.ps.stashit.core.ml.TextEmbedder
import com.ps.stashit.data.domain.SavedItem
import com.ps.stashit.data.domain.SearchResult
import com.ps.stashit.data.domain.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import kotlin.math.sqrt

class SavedItemRepositoryImpl @Inject constructor(
    private val dao: SavedItemDao,
    private val textEmbedder: TextEmbedder,
    private val embeddingScheduler: EmbeddingScheduler,
    private val captionScheduler: CaptionScheduler
) : SavedItemRepository {
    companion object {
        private const val MIN_SIMILARITY_THRESHOLD = 0.75f
        private val TRANSCRIPT_PLATFORMS = setOf(Platform.YOUTUBE_SHORTS, Platform.TIKTOK)
        private val converters = Converters()
    }

    override fun observeAll(): Flow<List<SavedItem>> {
        return dao.observeAll().map { list -> list.map { it.toDomain() } }
    }

    override fun observePendingEmbeddingCount(): Flow<Int> {
        return dao.observePendingCount()
    }

    override suspend fun saveItem(
        url: String,
        platform: Platform,
        caption: String?
    ): Long {
        val existing = dao.getByUrl(url)
        if (existing != null) {
            val updated = existing.copy(
                caption = existing.caption ?: caption,
                createdAt = System.currentTimeMillis()
            )
            dao.update(updated)
            return existing.id
        }
        val transcriptStatus =
            if (platform in TRANSCRIPT_PLATFORMS) TranscriptStatus.PENDING else TranscriptStatus.SKIPPED
        val entity = SavedItemEntity(
            url = url,
            platform = platform,
            caption = caption,
            transcriptStatus = transcriptStatus
        )
        val id = dao.insert(entity)
        embeddingScheduler.triggerImmediate()
        if (transcriptStatus == TranscriptStatus.PENDING) captionScheduler.triggerImmediate()
        return id
    }

    override suspend fun delete(item: SavedItem) {
        dao.getById(item.id)?.let { dao.delete(it) }
    }

    override suspend fun search(
        query: String,
        limit: Int
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val queryEmbedding = textEmbedder.embedQuery(query)
        //val norm = sqrt(queryEmbedding.sumOf { (it * it).toDouble() })
        val candidates = dao.getAllEmbedded()
        val queryLower = query.trim().lowercase()

        return candidates
            .asSequence()
            .mapNotNull { entity ->
                val embedding = entity.embedding ?: return@mapNotNull null
                val semanticScore = cosineSimilarity(queryEmbedding, embedding)

                val text = listOfNotNull(entity.caption, entity.extractedTitle, entity.transcript)
                    .joinToString(" ")
                    .lowercase()
                val isExactMatch = text.contains(queryLower)
                val keywordBoost = if (text.contains(queryLower)) 0.3f else 0f

                val combinedScore = (semanticScore + keywordBoost).coerceAtMost(1f)

                SearchResult(item = entity.toDomain(), score = combinedScore) to isExactMatch
            }
            .filter { (result, isExactMatch) -> isExactMatch || result.score >= MIN_SIMILARITY_THRESHOLD }
            .map { (result, _) -> result }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    override suspend fun getPendingEmbedding(afterId: Long, batchSize: Int): List<SavedItem> {
        return dao.getPendingEmbedding(afterId, batchSize).map { it.toDomain() }
    }

    override suspend fun attachEmbedding(
        id: Long,
        embedding: FloatArray,
        tags: List<String>,
        embeddedTranscript: String?
    ) {
        dao.attachEmbeddingIfCurrent(
            id = id,
            embedding = converters.fromEmbedding(embedding)!!,
            tags = tags.joinToString(","),
            classifiedAt = System.currentTimeMillis(),
            transcript = embeddedTranscript
        )
    }

    override suspend fun recordEmbeddingFailure(id: Long) {
        dao.incrementEmbeddingAttempts(id)
    }

    override suspend fun attachMetaData(id: Long, metadata: RemoteMetaData?) {
        dao.updateMetaData(
            id = id,
            title = metadata?.title,
            thumbnailUrl = metadata?.thumbnailUrl,
            fetchedAt = System.currentTimeMillis()
        )
    }

    override suspend fun getPendingTranscript(afterId: Long, batchSize: Int): List<SavedItem> {
        return dao.getPendingTranscript(afterId, batchSize).map { it.toDomain() }
    }

    override suspend fun attachTranscript(id: Long, transcript: String) {
        dao.attachTranscript(id, transcript, fetchedAt = System.currentTimeMillis())
    }

    override suspend fun markTranscriptUnavailable(id: Long) {
        dao.updateTranscriptStatus(id, TranscriptStatus.UNAVAILABLE, fetchedAt = System.currentTimeMillis())
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f;
        var normA = 0f;
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }
}