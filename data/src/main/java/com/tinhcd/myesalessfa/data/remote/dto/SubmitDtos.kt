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
    /**
     * What the rep decided where a rule asked. `submit_order` recomputes every
     * rule and every amount around these, so they choose between gifts a rule
     * really offers and nothing more.
     */
    val promotions: List<OrderPromotionChoicePayload> = emptyList(),
    /** Discounts the rep applied by hand, named rather than valued. */
    @SerialName("manual_promotions")
    val manualPromotions: List<OrderManualPromotionPayload> = emptyList(),
)

@Serializable
data class OrderPromotionChoicePayload(
    @SerialName("sequence_id") val sequenceId: String,
    @SerialName("take_amount") val takeAmount: Boolean = true,
    @SerialName("free_product_id") val freeProductId: String? = null,
    @SerialName("free_uom_code") val freeUomCode: String? = null,
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
 * Built only once every photo is in storage. `submit_display_audit` refuses a row
 * whose photo is not in the bucket yet, because a row pointing at a missing object
 * looks exactly like a completed audit in every report that counts them.
 */
@Serializable
data class DisplayAuditPayload(
    val id: String,
    @SerialName("visit_id") val visitId: String,
    @SerialName("audit_date") val auditDate: String,
    val note: String? = null,
    @SerialName("client_created_at") val clientCreatedAt: String,
    val photos: List<AuditPhotoPayload>,
    /**
     * The programme scored, and the level it is scored against. Null together for
     * the plain photo record a market with no display programmes still gets;
     * `submit_display_audit` refuses one without the other.
     */
    @SerialName("program_id") val programId: String? = null,
    @SerialName("level_id") val levelId: String? = null,
    @SerialName("counted_faces") val countedFaces: Int? = null,
    val achieved: Boolean? = null,
)

@Serializable
data class AuditPhotoPayload(
    /** The object name storage returned, never a path on this device. */
    @SerialName("storage_path") val storagePath: String,
    @SerialName("taken_at") val takenAt: String,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("file_size") val fileSize: Long = 0,
)

/**
 * `audio_path` is the storage object name, filled in only once the recording is
 * uploaded — bytes first, then the row, exactly as the display audit does. Null when
 * the rep left a written note with no recording.
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
    /** What the rep photographed. Sized by the step's photo_min and photo_max. */
    val photos: List<AuditPhotoPayload> = emptyList(),
    /**
     * The recordings, in the order they were made. Several because a customer still
     * talking at the per-clip cap carries on into the next one.
     */
    val audios: List<FeedbackAudioPayload> = emptyList(),
    @SerialName("client_created_at") val clientCreatedAt: String,
)

@Serializable
data class FeedbackAudioPayload(
    /** The object name storage returned, never a path on this device. */
    @SerialName("storage_path") val storagePath: String,
    val seconds: Int,
    @SerialName("recorded_at") val recordedAt: String,
    @SerialName("file_size") val fileSize: Long = 0,
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
    /**
     * Which questionnaire, where the step holds more than one. Omitted by the
     * older shape, which the server still answers with the single active one.
     */
    @SerialName("survey_type_id") val surveyTypeId: String? = null,
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

/**
 * One asset's check. `posm_item_id` absent is the deliberate second shape: an
 * outlet holding no POSM, where the rep is recording that they looked and there
 * was nothing to count.
 */
@Serializable
data class PosmCheckPayload(
    val id: String,
    @SerialName("visit_id") val visitId: String,
    @SerialName("program_id") val programId: String? = null,
    @SerialName("posm_item_id") val itemId: String? = null,
    @SerialName("counted_qty") val countedQty: Int? = null,
    /** usable | repairable | unusable, the legacy's three Result codes. */
    val condition: String? = null,
    val remark: String? = null,
    val suggestion: String? = null,
    @SerialName("check_date") val checkDate: String? = null,
    @SerialName("client_created_at") val clientCreatedAt: String,
    val photos: List<AuditPhotoPayload> = emptyList(),
)

/**
 * A manual discount the rep applied. The client names the catalogue entry; the
 * server reads its value. `amount` is only honoured on an entry whose
 * `allow_edit` says so, and only downwards.
 */
@Serializable
data class OrderManualPromotionPayload(
    @SerialName("promotion_id") val promotionId: String,
    val amount: Long? = null,
)

/**
 * A competitor survey, whole. `InsertAnswersCompetitorSurvey` took one answer at
 * a time; a half-filed grid is not a state the rep ever meant to leave behind, so
 * the whole thing travels at once.
 */
@Serializable
data class CompetitorSurveyPayload(
    @SerialName("visit_id") val visitId: String,
    @SerialName("survey_id") val surveyId: String,
    @SerialName("client_created_at") val clientCreatedAt: String,
    val answers: List<CompetitorAnswerPayload> = emptyList(),
)

@Serializable
data class CompetitorAnswerPayload(
    @SerialName("product_id") val productId: String,
    @SerialName("competitor_product_id") val competitorProductId: String,
    @SerialName("criteria_id") val criteriaId: String,
    val content: String,
    val photos: List<AuditPhotoPayload> = emptyList(),
)
