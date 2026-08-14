package com.tinhcd.myesalessfa.domain.model

/**
 * Questionnaires: one engine, however many steps use it.
 *
 * `posm_status` and `market_info` are the same code with different data. Each
 * `SurveyDefinition` names the workflow step it belongs to, so a third questionnaire
 * step is a row on the server and a form id in `sales_step` — no new screen. That is
 * the workflow's own "steps are data" property, one level further down.
 *
 * Scores here are for the rep's eyes. `submit_survey` recomputes them from the same
 * definitions and its answer is the one that is stored, for the reason order prices
 * are server-side: a client that can name its own score is a client that can pass an
 * audit it failed.
 */
enum class AnswerType {
    YES_NO,
    SINGLE,
    MULTI,
    NUMBER,
    TEXT,

    /**
     * Defined on the server, not rendered by this build. Photo capture exists — the
     * display audit uses it — but wiring it into a questionnaire before it has run on
     * a real device would compound one unproven thing with another.
     */
    PHOTO,
    ;

    /** Whether this build can put a control on screen for it. */
    val isSupported: Boolean get() = this != PHOTO
}

data class QuestionOption(
    val id: String,
    val code: String,
    val content: String,
    val score: Int,
)

data class SurveyQuestion(
    val id: String,
    val code: String,
    val content: String,
    val answerType: AnswerType,
    val isRequired: Boolean,
    /** Worth of a full answer for yes/no, number and text. Options govern the rest. */
    val score: Int,
    val options: List<QuestionOption> = emptyList(),
) {
    /**
     * The most this question can contribute. Single takes its best option, multi can
     * take them all — which is how the server computes `max_score` too, so the
     * percentage the rep sees matches the one that is stored.
     */
    val maxScore: Int
        get() = when (answerType) {
            AnswerType.SINGLE -> options.maxOfOrNull { it.score } ?: 0
            AnswerType.MULTI -> options.sumOf { it.score }
            AnswerType.PHOTO -> 0
            else -> score
        }
}

data class QuestionGroup(
    val name: String,
    val questions: List<SurveyQuestion>,
)

data class SurveyDefinition(
    val id: String,
    val code: String,
    val name: String,
    /** The workflow step this questionnaire belongs to. */
    val formId: String,
    /** Total needed to pass. Zero means informational — it always passes. */
    val passScore: Int,
    val groups: List<QuestionGroup>,
) {
    val questions: List<SurveyQuestion> get() = groups.flatMap { it.questions }

    val maxScore: Int get() = questions.sumOf { it.maxScore }

    /** Whether a score is worth showing at all. */
    val isScored: Boolean get() = maxScore > 0
}

/**
 * One question's answer. Which field carries it depends on the question's type, which
 * mirrors how the server stores it — a number stays a number for whoever reports on
 * it later rather than becoming text.
 */
data class SurveyAnswer(
    val questionId: String,
    val chosenOptionIds: Set<String> = emptySet(),
    val text: String = "",
    val number: Double? = null,
    val bool: Boolean? = null,
)

data class DraftSurvey(
    val visitId: String,
    val customerId: String,
    val definition: SurveyDefinition,
    val answers: Map<String, SurveyAnswer> = emptyMap(),
    val note: String = "",
) {
    /**
     * Questions this build can put on screen. A photo question is dropped rather than
     * shown as broken, and — critically — it cannot block submission even when the
     * server marks it required. Holding a rep on a control the app does not have would
     * strand them mid-visit, which is the same rule the workflow applies to steps it
     * cannot render.
     */
    val renderableQuestions: List<SurveyQuestion>
        get() = definition.questions.filter { it.answerType.isSupported }

    fun answerFor(questionId: String): SurveyAnswer? = answers[questionId]

    /** Required, renderable, and still blank. */
    val unanswered: List<SurveyQuestion>
        get() = renderableQuestions.filter { it.isRequired && !isAnswered(it) }

    val canSubmit: Boolean get() = unanswered.isEmpty()

    fun isAnswered(question: SurveyQuestion): Boolean {
        val answer = answers[question.id] ?: return false
        return when (question.answerType) {
            AnswerType.YES_NO -> answer.bool != null
            AnswerType.SINGLE, AnswerType.MULTI -> answer.chosenOptionIds.isNotEmpty()
            AnswerType.NUMBER -> answer.number != null
            AnswerType.TEXT -> answer.text.isNotBlank()
            AnswerType.PHOTO -> false
        }
    }

    /**
     * What this survey scores as it stands. Recomputed by the server on submit; shown
     * here so a rep filling in a Perfect Store audit can see where they are.
     */
    val totalScore: Int
        get() = definition.questions.sumOf { question ->
            val answer = answers[question.id] ?: return@sumOf 0
            when (question.answerType) {
                AnswerType.YES_NO -> if (answer.bool == true) question.score else 0
                AnswerType.SINGLE, AnswerType.MULTI ->
                    question.options
                        .filter { it.id in answer.chosenOptionIds }
                        .sumOf { it.score }

                AnswerType.NUMBER -> if (answer.number != null) question.score else 0
                AnswerType.TEXT -> if (answer.text.isNotBlank()) question.score else 0
                AnswerType.PHOTO -> 0
            }
        }

    val isPassing: Boolean get() = totalScore >= definition.passScore

    val scorePercent: Int
        get() = definition.maxScore.let { max ->
            if (max <= 0) 0 else (totalScore * 100) / max
        }

    // -------------------------------------------------------------------------
    // Answering
    // -------------------------------------------------------------------------

    fun withYesNo(questionId: String, value: Boolean): DraftSurvey =
        withAnswer(questionId) { it.copy(bool = value) }

    fun withNumber(questionId: String, value: Double?): DraftSurvey =
        withAnswer(questionId) { it.copy(number = value) }

    fun withText(questionId: String, value: String): DraftSurvey =
        withAnswer(questionId) { it.copy(text = value) }

    /** Single choice: replaces whatever was chosen before. */
    fun withOption(questionId: String, optionId: String): DraftSurvey =
        withAnswer(questionId) { it.copy(chosenOptionIds = setOf(optionId)) }

    /** Multiple choice: toggles one option, leaving the others alone. */
    fun withToggledOption(questionId: String, optionId: String): DraftSurvey =
        withAnswer(questionId) { existing ->
            existing.copy(
                chosenOptionIds = if (optionId in existing.chosenOptionIds) {
                    existing.chosenOptionIds - optionId
                } else {
                    existing.chosenOptionIds + optionId
                },
            )
        }

    private fun withAnswer(
        questionId: String,
        update: (SurveyAnswer) -> SurveyAnswer,
    ): DraftSurvey {
        val existing = answers[questionId] ?: SurveyAnswer(questionId)
        return copy(answers = answers + (questionId to update(existing)))
    }
}
