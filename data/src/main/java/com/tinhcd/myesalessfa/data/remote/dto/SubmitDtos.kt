package com.tinhcd.myesalessfa.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The bodies the submit RPCs take. Field names are the functions' own, so the
 * mapping from a domain draft to the wire happens once, in the repository that
 * owns that draft.
 *
 * Each carries a client-minted `id`. That is the idempotency key the functions
 * conflict on, so a request that timed out after the server had in fact committed
 * does not book a second order when the rep taps submit again.
 */

@Serializable
data class OrderPayload(
    val id: String,
    @SerialName("visit_id") val visitId: String,
    @SerialName("order_date") val orderDate: String,
    val note: String? = null,
    @SerialName("client_total_amount") val clientTotalAmount: Long,
    @SerialName("client_created_at") val clientCreatedAt: String,
    val lines: List<OrderLinePayload>,
)

@Serializable
data class OrderLinePayload(
    @SerialName("line_no") val lineNo: Int,
    @SerialName("product_id") val productId: String,
    @SerialName("uom_code") val uomCode: String,
    val qty: Int,
)

@Serializable
data class StockCountPayload(
    val id: String,
    @SerialName("visit_id") val visitId: String,
    @SerialName("count_date") val countDate: String,
    val note: String? = null,
    @SerialName("client_created_at") val clientCreatedAt: String,
    val lines: List<StockCountLinePayload>,
)

@Serializable
data class StockCountLinePayload(
    @SerialName("product_id") val productId: String,
    @SerialName("uom_code") val uomCode: String,
    val qty: Int,
)

/**
 * Photos travel as local file paths. [DisplayAuditApi] uploads each one, swaps in
 * the object name storage returns, and only then writes the row — the function
 * refuses a row whose photo is not in the bucket yet, because a row pointing at a
 * missing object looks exactly like a completed audit in every report that counts
 * them.
 */
@Serializable
data class DisplayAuditPayload(
    val id: String,
    @SerialName("visit_id") val visitId: String,
    @SerialName("audit_date") val auditDate: String,
    val note: String? = null,
    @SerialName("client_created_at") val clientCreatedAt: String,
    val photos: List<AuditPhotoPayload>,
)

@Serializable
data class AuditPhotoPayload(
    /** Where the file is on this device, replaced by [storagePath] once uploaded. */
    @SerialName("local_path") val localPath: String,
    @SerialName("storage_path") val storagePath: String? = null,
    @SerialName("taken_at") val takenAt: String,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("file_size") val fileSize: Long = 0,
)

/**
 * `audio_path` starts as a local file and is swapped for the storage object name by
 * [FeedbackApi], exactly as the display audit does with its photos: bytes first,
 * then the row.
 *
 * No topic name travels, only its id. The server checks the id really is a feedback
 * topic — filing a complaint under a GPS reason code would poison the one index that
 * makes the table worth having.
 */
@Serializable
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

/**
 * `form_id` selects the questionnaire, so one payload type and one endpoint serve
 * every questionnaire step. No score travels: the server computes it from the
 * question definitions, because a client that can name its own score is a client
 * that can pass an audit it failed.
 */
@Serializable
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
 * One stored fact. A multi-choice question contributes one of these per chosen
 * option, matching how `survey_answer` is keyed.
 */
@Serializable
data class SurveyAnswerPayload(
    @SerialName("question_id") val questionId: String,
    @SerialName("option_id") val optionId: String? = null,
    @SerialName("answer_text") val answerText: String? = null,
    @SerialName("answer_value") val answerValue: Double? = null,
    @SerialName("answer_bool") val answerBool: Boolean? = null,
)
