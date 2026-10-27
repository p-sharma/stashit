package com.ps.stashit.core

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ps.stashit.core.captions.CaptionFetcher
import com.ps.stashit.core.captions.CaptionResult
import com.ps.stashit.data.repository.SavedItemRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class CaptionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: SavedItemRepository,
    private val captionFetcher: CaptionFetcher,
    private val embeddingScheduler: EmbeddingScheduler
) : CoroutineWorker(context, params) {
    companion object {
        const val BATCH_SIZE = 20
        const val KEY_ATTACHED_COUNT = "attached_count"
        const val UNIQUE_IMMEDIATE_NAME = "caption_immediate"
    }

    override suspend fun doWork(): Result {
        var attached = 0
        var hadTransientFailure = false
        // Transient failures stay PENDING, so page by id to visit each row once per run.
        var cursor = 0L
        while (true) {
            val pending = repository.getPendingTranscript(cursor, BATCH_SIZE)
            if (pending.isEmpty()) break
            cursor = pending.last().id

            // Sequential on purpose: the service runs Whisper on CPU with low concurrency.
            for (item in pending) {
                when (val result = captionFetcher.fetch(item.url)) {
                    is CaptionResult.Success -> {
                        repository.attachTranscript(item.id, result.fullText)
                        attached++
                    }
                    CaptionResult.Unavailable -> repository.markTranscriptUnavailable(item.id)
                    CaptionResult.TransientFailure -> hadTransientFailure = true
                }
            }
        }

        if (attached > 0) embeddingScheduler.triggerImmediate()
        return if (hadTransientFailure) {
            Result.retry()
        } else {
            Result.success(workDataOf(KEY_ATTACHED_COUNT to attached))
        }
    }
}
