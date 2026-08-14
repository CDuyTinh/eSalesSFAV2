package com.tinhcd.myesalessfa.data.outbox

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tinhcd.myesalessfa.data.local.OutboxDao
import com.tinhcd.myesalessfa.data.local.OutboxEntity
import com.tinhcd.myesalessfa.data.remote.NewVisitDto
import com.tinhcd.myesalessfa.data.remote.VisitApi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Drains the outbox. Shared by the repository (which tries immediately, so a
 * check-in on good signal feels instant) and by the worker (which retries on
 * the system's schedule once connectivity returns).
 */
@Singleton
class OutboxFlusher @Inject constructor(
    private val dao: OutboxDao,
    private val visitApi: VisitApi,
) {
    /** @return true if the queue is now empty. */
    suspend fun flush(): Boolean {
        val batch = dao.oldest()
        if (batch.isEmpty()) return true

        var allSent = true
        for (entry in batch) {
            val sent = runCatching { send(entry) }
            if (sent.isSuccess) {
                dao.delete(entry.id)
            } else {
                allSent = false
                dao.recordFailure(entry.id, sent.exceptionOrNull()?.message)
            }
        }
        return allSent
    }

    private suspend fun send(entry: OutboxEntity) {
        when (entry.type) {
            OutboxEntity.TYPE_CHECK_IN ->
                visitApi.insertVisit(json.decodeFromString<NewVisitDto>(entry.payload))

            OutboxEntity.TYPE_CHECK_OUT ->
                visitApi.markCheckedOut(json.decodeFromString<CheckOutPayload>(entry.payload))

            OutboxEntity.TYPE_STEP_RESULT ->
                visitApi.saveStepResult(json.decodeFromString<StepResultPayload>(entry.payload))

            else -> error("Unknown outbox type ${entry.type}")
        }
    }
}

@kotlinx.serialization.Serializable
data class CheckOutPayload(
    val visitId: String,
    val checkOutAt: String,
)

/**
 * A completed workflow step. `fields` is whatever that step chose to record —
 * kept as flat strings so the outbox stays agnostic about step shapes.
 */
@kotlinx.serialization.Serializable
data class StepResultPayload(
    val visitId: String,
    val formId: String,
    val completedAt: String,
    val fields: Map<String, String> = emptyMap(),
)

@HiltWorker
class OutboxWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val flusher: OutboxFlusher,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        if (runCatching { flusher.flush() }.getOrDefault(false)) Result.success() else Result.retry()

    companion object {
        private const val UNIQUE_NAME = "outbox-flush"

        /**
         * Queued rather than scheduled: WorkManager waits for a connection, so
         * a rep who checks in underground gets the visit delivered when they
         * walk back into signal without the app being open.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
