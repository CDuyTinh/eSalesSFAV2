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
import com.tinhcd.myesalessfa.data.remote.DisplayAuditApi
import com.tinhcd.myesalessfa.data.remote.FeedbackApi
import com.tinhcd.myesalessfa.data.remote.NewVisitDto
import com.tinhcd.myesalessfa.data.remote.OrderApi
import com.tinhcd.myesalessfa.data.remote.StockApi
import com.tinhcd.myesalessfa.data.remote.SurveyApi
import com.tinhcd.myesalessfa.data.remote.VisitApi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.SerialName
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
    private val orderApi: OrderApi,
    private val stockApi: StockApi,
    private val displayAuditApi: DisplayAuditApi,
    private val surveyApi: SurveyApi,
    private val feedbackApi: FeedbackApi,
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

            OutboxEntity.TYPE_ORDER ->
                orderApi.submit(json.decodeFromString<OrderPayload>(entry.payload))

            OutboxEntity.TYPE_STOCK_COUNT ->
                stockApi.submit(json.decodeFromString<StockCountPayload>(entry.payload))

            OutboxEntity.TYPE_DISPLAY_AUDIT ->
                displayAuditApi.submit(json.decodeFromString<DisplayAuditPayload>(entry.payload))

            OutboxEntity.TYPE_SURVEY ->
                surveyApi.submit(json.decodeFromString<SurveyPayload>(entry.payload))

            OutboxEntity.TYPE_FEEDBACK ->
                feedbackApi.submit(json.decodeFromString<FeedbackPayload>(entry.payload))

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

/**
 * An order on its way to `submit_order`.
 *
 * Field names are the RPC's own, so the payload goes over the wire unchanged
 * rather than through a second mapping that could disagree with the function
 * signature.
 *
 * It carries no prices. The server looks up price, VAT and unit conversion for
 * itself from the effective-dated catalogue, which is why `order_date` matters:
 * an order that waited overnight in the outbox still prices at the day the rep
 * agreed it, not the day it happened to arrive. `client_total_amount` is the
 * device's own figure, sent to be recorded rather than trusted.
 */
@kotlinx.serialization.Serializable
data class OrderPayload(
    val id: String,
    @SerialName("visit_id") val visitId: String,
    @SerialName("order_date") val orderDate: String,
    val note: String? = null,
    @SerialName("client_total_amount") val clientTotalAmount: Long,
    @SerialName("client_created_at") val clientCreatedAt: String,
    val lines: List<OrderLinePayload>,
)

@kotlinx.serialization.Serializable
data class OrderLinePayload(
    @SerialName("line_no") val lineNo: Int,
    @SerialName("product_id") val productId: String,
    @SerialName("uom_code") val uomCode: String,
    val qty: Int,
)

/**
 * A stock count on its way to `submit_stock_count`. Field names are the RPC's
 * own, as for [OrderPayload].
 *
 * A count belongs in the outbox for the same reason a check-in does: the rep
 * physically walked the shelves, and nothing on the device or the server can
 * reconstruct what they saw once they have left the shop.
 */
@kotlinx.serialization.Serializable
data class StockCountPayload(
    val id: String,
    @SerialName("visit_id") val visitId: String,
    @SerialName("count_date") val countDate: String,
    val note: String? = null,
    @SerialName("client_created_at") val clientCreatedAt: String,
    val lines: List<StockCountLinePayload>,
)

@kotlinx.serialization.Serializable
data class StockCountLinePayload(
    @SerialName("product_id") val productId: String,
    @SerialName("uom_code") val uomCode: String,
    val qty: Int,
)

/**
 * A display audit on its way to `submit_display_audit`.
 *
 * Carries local file paths, not storage paths — the photos have not been uploaded
 * when this is queued. The flusher uploads each one, swaps in the object name it gets
 * back, and only then writes the row. One entry, all or nothing: an audit whose
 * photo never arrived is not evidence of anything, and a row pointing at a missing
 * object looks exactly like a completed audit in every report that counts them.
 */
@kotlinx.serialization.Serializable
data class DisplayAuditPayload(
    val id: String,
    @SerialName("visit_id") val visitId: String,
    @SerialName("audit_date") val auditDate: String,
    val note: String? = null,
    @SerialName("client_created_at") val clientCreatedAt: String,
    val photos: List<AuditPhotoPayload>,
)

@kotlinx.serialization.Serializable
data class AuditPhotoPayload(
    /**
     * Where the file is on this device. Replaced with the storage object name once
     * uploaded, which is what the row records.
     */
    @SerialName("local_path") val localPath: String,
    @SerialName("storage_path") val storagePath: String? = null,
    @SerialName("taken_at") val takenAt: String,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("file_size") val fileSize: Long = 0,
)

/**
 * A completed questionnaire on its way to `submit_survey`.
 *
 * `form_id` is what selects the questionnaire, so one payload type and one endpoint
 * serve every questionnaire step. No score travels: the server computes it from the
 * question definitions, because a client that can name its own score is a client that
 * can pass an audit it failed.
 */
/**
 * Customer feedback on its way to `submit_feedback`.
 *
 * `audio_path` starts as a local file and is swapped for the storage object name by
 * the flusher, exactly as the display audit does with its photos: bytes first, then
 * the row, because the function refuses a path that is not in storage yet.
 *
 * No topic name travels, only its id. The server checks the id really is a feedback
 * topic — filing a complaint under a GPS reason code would poison the one index that
 * makes the table worth having.
 */
@kotlinx.serialization.Serializable
data class FeedbackPayload(
    val id: String,
    @SerialName("visit_id") val visitId: String,
    @SerialName("feedback_date") val feedbackDate: String,
    @SerialName("topic_id") val topicId: String? = null,
    val note: String,
    @SerialName("local_audio_path") val localAudioPath: String? = null,
    @SerialName("audio_path") val audioPath: String? = null,
    @SerialName("audio_seconds") val audioSeconds: Int? = null,
    @SerialName("client_created_at") val clientCreatedAt: String,
)

@kotlinx.serialization.Serializable
data class SurveyPayload(
    val id: String,
    @SerialName("visit_id") val visitId: String,
    @SerialName("form_id") val formId: String,
    @SerialName("survey_date") val surveyDate: String,
    val note: String? = null,
    @SerialName("client_created_at") val clientCreatedAt: String,
    val answers: List<SurveyAnswerPayload>,
)

/**
 * One stored fact. A multi-choice question contributes one of these per chosen option,
 * matching how `survey_answer` is keyed.
 */
@kotlinx.serialization.Serializable
data class SurveyAnswerPayload(
    @SerialName("question_id") val questionId: String,
    @SerialName("option_id") val optionId: String? = null,
    @SerialName("answer_text") val answerText: String? = null,
    @SerialName("answer_value") val answerValue: Double? = null,
    @SerialName("answer_bool") val answerBool: Boolean? = null,
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
