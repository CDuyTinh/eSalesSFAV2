package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.local.ConfigDao
import com.tinhcd.myesalessfa.data.remote.dto.SurveyAnswerPayload
import com.tinhcd.myesalessfa.data.remote.api.SurveyApi
import com.tinhcd.myesalessfa.data.remote.dto.SurveyPayload
import com.tinhcd.myesalessfa.data.remote.dto.SurveyTypeDto
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.AnswerType
import com.tinhcd.myesalessfa.domain.model.DraftSurvey
import com.tinhcd.myesalessfa.domain.model.QuestionGroup
import com.tinhcd.myesalessfa.domain.model.QuestionOption
import com.tinhcd.myesalessfa.domain.model.SurveyDefinition
import com.tinhcd.myesalessfa.domain.model.SurveyQuestion
import com.tinhcd.myesalessfa.domain.repository.SurveyRepository
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

@Singleton
class SurveyRepositoryImpl @Inject constructor(
    private val configDao: ConfigDao,
    private val surveyApi: SurveyApi,
) : SurveyRepository {

    /**
     * The questionnaire for a step, from the cache /bootstrap filled. Null when the
     * server has no active questionnaire for it — which the screen reports rather than
     * showing an empty form the rep cannot submit.
     */
    override suspend fun definition(formId: String): DataResult<SurveyDefinition?> = try {
        val raw = configDao.surveyDefinition(formId)
        val dto = raw?.let { runCatching { json.decodeFromString<SurveyTypeDto>(it) }.getOrNull() }
        DataResult.Success(dto?.toDomain())
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    override suspend fun submit(survey: DraftSurvey): DataResult<Unit> = try {
        surveyApi.submit(
            SurveyPayload(
                // The idempotency key `submit_survey` conflicts on, so a retry after
                // a timeout that in fact succeeded does not delete and rewrite the
                // result.
                id = UUID.randomUUID().toString(),
                visitId = survey.visitId,
                formId = survey.definition.formId,
                surveyDate = LocalDate.now().toString(),
                note = survey.note.trim().ifBlank { null },
                clientCreatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
                answers = survey.toAnswerPayloads(),
            ),
        )
        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}

/**
 * Flattens the answers into one row per stored fact.
 *
 * A multi-choice question becomes one payload per chosen option, matching how
 * `survey_answer` is keyed — and no score travels, because the server computes it from
 * the question definitions.
 */
private fun DraftSurvey.toAnswerPayloads(): List<SurveyAnswerPayload> =
    definition.questions
        .filter { it.answerType.isSupported }
        .flatMap { question ->
            val answer = answerFor(question.id) ?: return@flatMap emptyList()

            when (question.answerType) {
                AnswerType.YES_NO -> answer.bool?.let {
                    listOf(SurveyAnswerPayload(questionId = question.id, answerBool = it))
                }.orEmpty()

                AnswerType.SINGLE, AnswerType.MULTI ->
                    answer.chosenOptionIds.map {
                        SurveyAnswerPayload(questionId = question.id, optionId = it)
                    }

                AnswerType.NUMBER -> answer.number?.let {
                    listOf(SurveyAnswerPayload(questionId = question.id, answerValue = it))
                }.orEmpty()

                AnswerType.TEXT -> answer.text.trim().ifBlank { null }?.let {
                    listOf(SurveyAnswerPayload(questionId = question.id, answerText = it))
                }.orEmpty()

                AnswerType.PHOTO -> emptyList()
            }
        }

private fun SurveyTypeDto.toDomain() = SurveyDefinition(
    id = id,
    code = code,
    name = name,
    formId = formId,
    passScore = passScore,
    groups = groups.map { group ->
        QuestionGroup(
            name = group.name,
            questions = group.questions.map { question ->
                SurveyQuestion(
                    id = question.id,
                    code = question.code,
                    content = question.content,
                    answerType = question.answerType.toAnswerType(),
                    isRequired = question.isRequired,
                    score = question.score,
                    options = question.options.map {
                        QuestionOption(
                            id = it.id,
                            code = it.code,
                            content = it.content,
                            score = it.score,
                        )
                    },
                )
            },
        )
    },
)

/**
 * An answer type this build has never heard of is treated as a photo question: kept in
 * the definition, dropped from the screen, and never blocking submission. The server
 * may add a type before an app that can render it ships, and that must not strand a rep
 * mid-visit.
 */
private fun String.toAnswerType(): AnswerType = when (this) {
    "yes_no" -> AnswerType.YES_NO
    "single" -> AnswerType.SINGLE
    "multi" -> AnswerType.MULTI
    "number" -> AnswerType.NUMBER
    "text" -> AnswerType.TEXT
    else -> AnswerType.PHOTO
}
