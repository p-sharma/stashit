package com.ps.stashit.data.repository

import android.util.Log
import com.ps.stashit.core.EmbeddingScheduler
import com.ps.stashit.core.database.Platform
import com.ps.stashit.core.database.SavedItemDao
import com.ps.stashit.core.database.SavedItemEntity
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
    private val embeddingScheduler: EmbeddingScheduler
) : SavedItemRepository {
    companion object {
        private const val MIN_SIMILARITY_THRESHOLD = 0.75f
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
        val entity = SavedItemEntity(url = url, platform = platform, caption = caption)
        val id = dao.insert(entity)
        embeddingScheduler.triggerImmediate()
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

                val text = listOfNotNull(entity.caption, entity.extractedTitle)
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

    override suspend fun getPendingEmbedding(batchSize: Int): List<SavedItem> {
        return dao.getPendingEmbedding(batchSize).map { it.toDomain() }
    }

    override suspend fun attachEmbedding(
        id: Long,
        embedding: FloatArray,
        tags: List<String>
    ) {
        val entity = dao.getById(id) ?: return
        dao.update(
            entity.copy(
                embedding = embedding,
                tags = tags.joinToString(","),
                classifiedAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun attachMetaData(id: Long, metadata: RemoteMetaData?) {
        dao.updateMetaData(
            id = id,
            title = metadata?.title,
            thumbnailUrl = metadata?.thumbnailUrl,
            fetchedAt = System.currentTimeMillis()
        )
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