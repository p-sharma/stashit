package com.ps.stashit.core

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class EmbeddingScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager = WorkManager.getInstance(context)

    fun triggerImmediate() {
        val request = OneTimeWorkRequestBuilder<EmbeddingWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        // APPEND_OR_REPLACE, not KEEP: CaptionWorker re-queues rows whose ids are behind a running
        // worker's cursor, so a trigger during a run must schedule a follow-up run.
        workManager.enqueueUniqueWork(
            EmbeddingWorker.UNIQUE_IMMEDIATE_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    fun schedulePeriodicSweep() {
        val request = PeriodicWorkRequestBuilder<EmbeddingWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            ).build()
        workManager.enqueueUniquePeriodicWork(
            EmbeddingWorker.UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun observeImmediateWork(): Flow<WorkInfo?> =
        workManager.getWorkInfosForUniqueWorkFlow(EmbeddingWorker.UNIQUE_IMMEDIATE_NAME)
            .map {it.firstOrNull()}
}