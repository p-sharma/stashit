package com.ps.stashit.core

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ps.stashit.core.metadata.MetaDataFetcherRouter
import com.ps.stashit.core.metadata.MetadataFetcher
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
                // Skipped items stay pending, so page by id to visit each row once per run.
                var cursor = 0L
                while (true) {
                    val pending = repository.getPendingEmbedding(cursor, BATCH_SIZE)
                    if (pending.isEmpty()) break
                    cursor = pending.last().id

                    for (item in pending) {
                        val metaData = metadataRouter.fetch(item)
                        repository.attachMetaData(item.id,metaData)
                        val enrichedItem = item.copy(
                            extractedTitle = metaData?.title?: item.extractedTitle,
                            caption = item.caption
                        )

                        val text = buildEmbeddingText(enrichedItem)
                        if (text.isBlank()) continue
                        val embedding = textEmbedder.embedDocument(text)
                        if(embedding.any { it.isNaN() }) {
                            Log.w(TAG, "NaN embedding for item ${item.id}")
                            continue
                        }
                        repository.attachEmbedding(item.id, embedding, tags = emptyList(), embeddedTranscript = item.transcript)
                        processed++
                    }
                }
                Result.Success(workDataOf(KEY_PROCESSED_COUNT to processed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Embedding run failed", e)
                Result.retry()
            }
        }
    }

    private fun buildEmbeddingText(item: SavedItem): String {
        return listOfNotNull(item.extractedTitle, item.caption, item.transcript).joinToString(" ").trim()
    }

}