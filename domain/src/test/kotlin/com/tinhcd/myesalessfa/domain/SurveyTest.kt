package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.AnswerType
import com.tinhcd.myesalessfa.domain.model.DraftSurvey
import com.tinhcd.myesalessfa.domain.model.QuestionGroup
import com.tinhcd.myesalessfa.domain.model.QuestionOption
import com.tinhcd.myesalessfa.domain.model.SurveyDefinition
import com.tinhcd.myesalessfa.domain.model.SurveyQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seeded POSM questionnaire, which `submit_survey` was verified against: two
 * yes/no worth 5 each, one single-choice whose best option is 5, one multi-choice
 * totalling 3, and a pass threshold of 12 out of 18. Answering yes/no/QUAY/SACH+
 * GIA_DUNG scored 13 and passed on the server, so these tests hold the client to the
 * same arithmetic.
 */
class SurveyTest {

    private val posm = SurveyDefinition(
        id = "t1",
        code = "POSM",
        name = "Kiem tra POSM",
        formId = "posm_status",
        passScore = 12,
        groups = listOf(
            QuestionGroup(
                name = "Hien dien POSM",
                questions = listOf(
                    SurveyQuestion("q1", "POSTER", "Co poster?", AnswerType.YES_NO, true, 5),
                    SurveyQuestion("q2", "KE_RIENG", "Co ke rieng?", AnswerType.YES_NO, true, 5),
                ),
            ),
            QuestionGroup(
                name = "Chat luong trung bay",
                questions = listOf(
                    SurveyQuestion(
                        "q3", "VI_TRI", "Vi tri", AnswerType.SINGLE, true, 0,
                        options = listOf(
                            QuestionOption("o1", "QUAY", "Ngay quay", 5),
                            QuestionOption("o2", "LOI_DI", "Loi di", 3),
                            QuestionOption("o3", "GOC", "Goc", 1),
                        ),
                    ),
                    SurveyQuestion(
                        "q4", "VAN_DE", "Van de", AnswerType.MULTI, false, 0,
                        options = listOf(
                            QuestionOption("o4", "SACH", "Sach se", 2),
                            QuestionOption("o5", "GIA_DUNG", "Gia dung", 1),
                            QuestionOption("o6", "HET_HAN", "Gan het han", 0),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun draft(definition: SurveyDefinition = posm) =
        DraftSurvey(visitId = "v1", customerId = "c1", definition = definition)

    @Test
    fun `the achievable total matches what the server stores`() {
        // 5 + 5 for the yes/no pair, 5 for the single's best option, 3 for all of the
        // multi's. The server computed 18 for the same questionnaire.
        assertEquals(18, posm.maxScore)
        assertTrue(posm.isScored)
    }

    @Test
    fun `the seeded answers score 13 and pass, as they did on the server`() {
        val survey = draft()
            .withYesNo("q1", true)
            .withYesNo("q2", false)
            .withOption("q3", "o1")
            .withToggledOption("q4", "o4")
            .withToggledOption("q4", "o5")

        assertEquals(13, survey.totalScore)
        assertTrue(survey.isPassing)
        assertEquals(72, survey.scorePercent) // 13 of 18
    }

    @Test
    fun `a no answer scores nothing but still counts as answered`() {
        // Otherwise a rep who honestly answers no would be blocked from submitting.
        val survey = draft().withYesNo("q1", false)
        assertEquals(0, survey.totalScore)
        assertTrue(survey.isAnswered(posm.questions.first()))
    }

    @Test
    fun `single choice replaces rather than accumulates`() {
        val survey = draft().withOption("q3", "o1").withOption("q3", "o3")
        assertEquals(setOf("o3"), survey.answerFor("q3")?.chosenOptionIds)
        assertEquals(1, survey.totalScore)
    }

    @Test
    fun `multiple choice toggles each option independently`() {
        var survey = draft().withToggledOption("q4", "o4").withToggledOption("q4", "o5")
        assertEquals(3, survey.totalScore)

        survey = survey.withToggledOption("q4", "o4")
        assertEquals(1, survey.totalScore)
        assertEquals(setOf("o5"), survey.answerFor("q4")?.chosenOptionIds)
    }

    @Test
    fun `a zero-score option still counts as an answer`() {
        // "Gan het han" is worth nothing but is worth recording.
        val survey = draft().withToggledOption("q4", "o6")
        assertEquals(0, survey.totalScore)
        assertTrue(survey.isAnswered(posm.questions.last()))
    }

    @Test
    fun `submission is blocked until every required question is answered`() {
        var survey = draft()
        assertFalse(survey.canSubmit)
        assertEquals(listOf("POSTER", "KE_RIENG", "VI_TRI"), survey.unanswered.map { it.code })

        survey = survey.withYesNo("q1", true).withYesNo("q2", true)
        assertEquals(listOf("VI_TRI"), survey.unanswered.map { it.code })
        assertFalse(survey.canSubmit)

        // The multi question is optional, so choosing the single one is enough.
        survey = survey.withOption("q3", "o2")
        assertTrue(survey.canSubmit)
        assertTrue(survey.unanswered.isEmpty())
    }

    @Test
    fun `blank text does not count as an answer`() {
        val definition = posm.copy(
            groups = listOf(
                QuestionGroup(
                    "Ghi nhan",
                    listOf(SurveyQuestion("q9", "NOTE", "Ghi nhan", AnswerType.TEXT, true, 2)),
                ),
            ),
        )

        val blank = draft(definition).withText("q9", "   ")
        assertFalse(blank.canSubmit)
        assertEquals(0, blank.totalScore)

        val filled = draft(definition).withText("q9", "Doi thu giam gia")
        assertTrue(filled.canSubmit)
        assertEquals(2, filled.totalScore)
    }

    @Test
    fun `a number of zero is a real answer`() {
        // Recording a competitor price of zero is nonsense, but recording a facing
        // count of zero is not — the type cannot tell, so zero must answer.
        val definition = posm.copy(
            groups = listOf(
                QuestionGroup(
                    "Doi thu",
                    listOf(SurveyQuestion("q8", "PRICE", "Gia", AnswerType.NUMBER, true, 3)),
                ),
            ),
        )

        val survey = draft(definition).withNumber("q8", 0.0)
        assertTrue(survey.canSubmit)
        assertEquals(3, survey.totalScore)

        // Clearing the field is what leaves it unanswered.
        assertFalse(draft(definition).withNumber("q8", null).canSubmit)
    }

    @Test
    fun `an informational questionnaire always passes and shows no percentage`() {
        val info = SurveyDefinition(
            id = "t2", code = "MKTINFO", name = "Thong tin thi truong",
            formId = "market_info", passScore = 0,
            groups = listOf(
                QuestionGroup(
                    "Ghi nhan",
                    listOf(SurveyQuestion("q7", "NOTE", "Ghi nhan", AnswerType.TEXT, false, 0)),
                ),
            ),
        )

        val survey = draft(info)
        assertEquals(0, info.maxScore)
        assertFalse(info.isScored)
        assertTrue(survey.isPassing)
        // No division by zero when nothing is scoreable.
        assertEquals(0, survey.scorePercent)
    }

    @Test
    fun `a photo question this build cannot render never blocks submission`() {
        // Same rule the workflow applies to steps it has no screen for: holding the rep
        // on a control the app does not have would strand them mid-visit. The server
        // exempts required photo questions for the same reason.
        val withPhoto = posm.copy(
            groups = posm.groups + QuestionGroup(
                "Anh",
                listOf(SurveyQuestion("q5", "ANH", "Chup ke", AnswerType.PHOTO, true, 4)),
            ),
        )

        val survey = draft(withPhoto)
            .withYesNo("q1", true)
            .withYesNo("q2", true)
            .withOption("q3", "o1")

        assertTrue(survey.canSubmit)
        assertTrue(survey.unanswered.none { it.code == "ANH" })
        // Dropped from the screen rather than shown as broken.
        assertTrue(survey.renderableQuestions.none { it.code == "ANH" })
        // And it contributes nothing to the achievable total, so the percentage the rep
        // sees is not quietly capped below 100.
        assertEquals(18, withPhoto.maxScore)
    }

    @Test
    fun `groups keep their questions together for the screen`() {
        assertEquals(listOf("Hien dien POSM", "Chat luong trung bay"), posm.groups.map { it.name })
        assertEquals(listOf("POSTER", "KE_RIENG"), posm.groups.first().questions.map { it.code })
    }
}
