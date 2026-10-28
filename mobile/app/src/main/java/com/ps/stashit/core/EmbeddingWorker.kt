package com.ps.stashit.core

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ps.stashit.core.database.MAX_EMBEDDING_ATTEMPTS
import com.ps.stashit.core.metadata.MetaDataFetcherRouter
import com.ps.stashit.core.metadata.MetaDataResult
import com.ps.stashit.core.ml.TextEmbedder
import com.ps.stashit.data.domain.SavedItem
import com.ps.stashit.data.repository.SavedItemRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class EmbeddingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: SavedItemRepository,
    private val textEmbedder: TextEmbedder,
    private val metadataRouter: MetaDataFetcherRouter
) : CoroutineWorker(context, params) {
    companion object {
        private const val TAG = "EmbeddingWorker"
        const val BATCH_SIZE = 20
        const val KEY_PROCESSED_COUNT = "processed_count"
        const val UNIQUE_PERIODIC_NAME = "embedding_sweep"
        const val UNIQUE_IMMEDIATE_NAME = "embedding_immediate"
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.Default) {
            try {
                var processed = 0
                var retryNeeded = false
                // Failed items stay pending, so page by id to visit each row once per run.
                var cursor = 0L
                while (true) {
                    val pending = repository.getPendingEmbedding(cursor, BATCH_SIZE)
                    if (pending.isEmpty()) break
                    cursor = pending.last().id

                    for (item in pending) {
                        if (embedItem(item)) {
                            processed++
                        } else {
                            repository.recordEmbeddingFailure(item.id)
                            if (item.embeddingAttempts + 1 < MAX_EMBEDDING_ATTEMPTS) retryNeeded = true
                        }
                    }
                }
                if (retryNeeded) Result.retry() else Result.success(workDataOf(KEY_PROCESSED_COUNT to processed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Embedding run failed", e)
                Result.retry()
            }
        }
    }

    /** Returns false when this run could not embed the item, which counts as one of its attempts. */
    private suspend fun embedItem(item: SavedItem): Boolean = try {
        // A later attempt reuses the stored title instead of calling oEmbed again.
        val title = if (item.metadataFetchedAt == null) {
            when (val result = metadataRouter.fetch(item)) {
                is MetaDataResult.Found -> {
                    repository.attachMetaData(item.id, result.metaData)
                    result.metaData.title ?: item.extractedTitle
                }
                MetaDataResult.Unavailable -> {
                    repository.attachMetaData(item.id, null)
                    item.extractedTitle
                }
                // Embedding now would drop the item from the pending set and lose the title for good,
                // so wait for a later run unless this is the item's last attempt.
                MetaDataResult.Failed -> {
                    if (item.embeddingAttempts + 1 < MAX_EMBEDDING_ATTEMPTS) {
                        Log.w(TAG, "Metadata fetch for item ${item.id} failed, deferring embedding")
                        return false
                    }
                    item.extractedTitle
                }
            }
        } else {
            item.extractedTitle
        }

        val text = buildEmbeddingText(item.copy(extractedTitle = title))
        if (text.isBlank()) {
            Log.w(TAG, "No text to embed for item ${item.id}")
            false
        } else {
            val embedding = textEmbedder.embedDocument(text)
            if (embedding.any { it.isNaN() }) {
                Log.w(TAG, "NaN embedding for item ${item.id}")
                false
            } else {
                repository.attachEmbedding(item.id, embedding, tags = emptyList(), embeddedTranscript = item.transcript)
                true
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Embedding item ${item.id} failed", e)
        false
    }

    private fun buildEmbeddingText(item: SavedItem): String {
        return listOfNotNull(item.extractedTitle, item.caption, item.transcript).joinToString(" ").trim()
    }

}