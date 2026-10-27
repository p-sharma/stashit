package com.ps.stashit.testutil

import com.ps.stashit.core.database.SavedItemDao
import com.ps.stashit.core.database.Platform
import com.ps.stashit.core.database.SavedItemEntity
import com.ps.stashit.core.database.TranscriptStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory SavedItemDao mirroring the Room queries, including the unique url index
 * with OnConflictStrategy.IGNORE (insert returns -1 on conflict).
 *
 * Rows are not held in a StateFlow directly: SavedItemEntity.equals ignores most columns,
 * so StateFlow's equality conflation would silently drop caption/title/timestamp updates.
 * A version counter drives the observable queries instead.
 */
class FakeSavedItemDao : SavedItemDao {
    private val rows = Rows()
    private val version = MutableStateFlow(0)
    private var nextId = 1L

    val all: List<SavedItemEntity> get() = rows.value.values.toList()

    private inner class Rows {
        var value: Map<Long, SavedItemEntity> = emptyMap()
            set(newValue) {
                field = newValue
                version.value++
            }
    }

    override suspend fun insert(item: SavedItemEntity): Long {
        if (rows.value.values.any { it.url == item.url }) return -1
        val id = if (item.id == 0L) nextId++ else item.id.also { nextId = maxOf(nextId, it + 1) }
        rows.value = rows.value + (id to item.copy(id = id))
        return id
    }

    override suspend fun update(item: SavedItemEntity) {
        if (item.id in rows.value) rows.value = rows.value + (item.id to item)
    }

    override suspend fun delete(item: SavedItemEntity) {
        rows.value = rows.value - item.id
    }

    override fun observeAll(): Flow<List<SavedItemEntity>> =
        version.map { rows.value.values.sortedByDescending { it.createdAt } }

    override suspend fun getAllEmbedded(): List<SavedItemEntity> =
        rows.value.values.filter { it.embedding != null }

    override suspend fun getPendingEmbedding(afterId: Long, batchSize: Int): List<SavedItemEntity> =
        rows.value.values.filter { it.embedding == null && it.id > afterId }.sortedBy { it.id }.take(batchSize)

    override fun observePendingCount(): Flow<Int> =
        version.map { rows.value.values.count { it.embedding == null } }

    override suspend fun getById(id: Long): SavedItemEntity? = rows.value[id]

    override suspend fun getByUrl(url: String): SavedItemEntity? =
        rows.value.values.firstOrNull { it.url == url }

    override suspend fun updateMetaData(id: Long, title: String?, thumbnailUrl: String?, fetchedAt: Long) {
        val row = rows.value[id] ?: return
        rows.value = rows.value + (id to row.copy(
            extractedTitle = title,
            thumbnailUrl = thumbnailUrl,
            metadataFetchedAt = fetchedAt
        ))
    }

    override suspend fun getPendingTranscript(afterId: Long, batchSize: Int): List<SavedItemEntity> =
        rows.value.values
            .filter {
                it.transcriptStatus == TranscriptStatus.PENDING &&
                    it.platform in setOf(Platform.YOUTUBE_SHORTS, Platform.TIKTOK) &&
                    it.id > afterId
            }
            .sortedBy { it.id }
            .take(batchSize)

    override suspend fun attachTranscript(id: Long, transcript: String, fetchedAt: Long) {
        val row = rows.value[id] ?: return
        rows.value = rows.value + (id to row.copy(
            transcript = transcript,
            transcriptStatus = TranscriptStatus.DONE,
            transcriptFetchedAt = fetchedAt,
            embedding = null
        ))
    }

    override suspend fun updateTranscriptStatus(id: Long, status: TranscriptStatus, fetchedAt: Long) {
        val row = rows.value[id] ?: return
        rows.value = rows.value + (id to row.copy(transcriptStatus = status, transcriptFetchedAt = fetchedAt))
    }

    override suspend fun attachEmbeddingIfCurrent(
        id: Long,
        embedding: FloatArray,
        tags: String,
        classifiedAt: Long,
        transcript: String?
    ): Int {
        val row = rows.value[id] ?: return 0
        if (row.transcript != transcript) return 0
        rows.value = rows.value + (id to row.copy(embedding = embedding, tags = tags, classifiedAt = classifiedAt))
        return 1
    }

    override suspend fun clearAllEmbeddings() {
        rows.value = rows.value.mapValues { (_, row) -> row.copy(embedding = null, embeddingModelVersion = null) }
    }
}
