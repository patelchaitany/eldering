package com.eldering.safety.data.outbox

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eldering.safety.core.appGraph
import java.util.concurrent.TimeUnit

/** Requests a background outbox run (abstracted so repositories are testable without WorkManager). */
fun interface OutboxScheduler {
    fun schedule()
}

/**
 * Offline retry for the outbox (docs/research/android-background.md §: the first attempt happens inline in
 * SafetyService; WorkManager only retries). Not expedited: on API 29–30 expedited work needs its own foreground
 * notification, and this is a retry path, not the primary one.
 */
class WorkManagerOutboxScheduler(private val context: Context) : OutboxScheduler {
    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // KEEP: a pending run will pick the new rows up; the worker re-checks before finishing. Not APPEND: live
        // updates would chain a job every 15 s. Rows a racing KEEP drops are re-scheduled by the service tick.
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "eldering.outbox"
        const val BACKOFF_SECONDS = 15L
    }
}

/** Drains the outbox; asks WorkManager to retry (with exponential backoff) while rows remain. */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val processor = applicationContext.appGraph.outboxProcessor
        var result = processor.drain(ignoreBackoff = true)
        // Rows enqueued while we were sending: a schedule() made meanwhile is dropped by KEEP, so drain them here
        // (bounded; the SafetyService housekeeping tick re-schedules anything still left behind).
        var extra = 0
        while (result.remaining > 0 && result.lastError == null && !result.signedOut && extra++ < MAX_EXTRA_PASSES) {
            result = processor.drain(ignoreBackoff = true)
        }
        return when {
            result.signedOut -> Result.success() // nothing to do until someone signs in
            result.remaining > 0 -> {
                Log.i(TAG, "Outbox: ${result.remaining} pending, retrying (${result.lastError?.message})")
                Result.retry()
            }
            else -> Result.success()
        }
    }

    private companion object {
        const val TAG = "OutboxWorker"
        const val MAX_EXTRA_PASSES = 5
    }
}
