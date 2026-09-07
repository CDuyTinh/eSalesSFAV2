package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.CompetitorCriterion
import com.tinhcd.myesalessfa.domain.model.CompetitorEntry
import com.tinhcd.myesalessfa.domain.model.CompetitorPairing
import com.tinhcd.myesalessfa.domain.model.CompetitorPhoto
import com.tinhcd.myesalessfa.domain.model.CompetitorSurveyDefinition
import com.tinhcd.myesalessfa.domain.model.DraftCompetitorSurvey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Khảo sát đối thủ: the rule that decides when the grid is finished.
 *
 * It matters that this matches the server exactly. `market_info_surveys` counts a
 * competitor survey done when every required criterion has an answer for every
 * pairing; if the button here let a rep through on anything less, they would send
 * a survey, watch it succeed, and find the step still red with nothing on screen
 * saying why.
 */
class CompetitorSurveyTest {

    private val price = CompetitorCriterion("c-price", "GIABAN", "Giá bán lẻ", null, isRequired = true)
    private val facings = CompetitorCriterion("c-face", "SOMAT", "Số mặt", null, isRequired = true)
    private val promo = CompetitorCriterion("c-promo", "CTKM", "Khuyến mãi", null, isRequired = false)

    private val coke = CompetitorPairing(
        productId = "p-coke", productCode = "NGK001", productName = "Coca-Cola 330ml",
        competitorProductId = "cp-n1", competitorProduct = "Number 1 330ml",
        competitorName = "Tan Hiep Phat",
    )

    private val water = CompetitorPairing(
        productId = "p-aqua", productCode = "NGK003", productName = "Aquafina 500ml",
        competitorProductId = "cp-lav", competitorProduct = "La Vie 500ml",
        competitorName = "Nestle Waters",
    )

    private fun draft(
        criteria: List<CompetitorCriterion> = listOf(price, facings, promo),
        pairings: List<CompetitorPairing> = listOf(coke, water),
    ) = DraftCompetitorSurvey(
        visitId = "v1",
        definition = CompetitorSurveyDefinition("s1", "KSDT-Q3", "Khảo sát quý 3", criteria, pairings),
    )

    @Test
    fun `a fresh grid owes every pairing`() {
        val d = draft()

        assertEquals(listOf(coke, water), d.outstanding)
        assertFalse(d.canSubmit)
        assertEquals(0, d.answeredIn(coke))
    }

    @Test
    fun `only the required criteria decide whether a pairing is done`() {
        val d = draft()
            .withContent(coke, price, "12000")
            .withContent(coke, facings, "3")

        assertTrue(d.isComplete(coke))
        // The optional one is still unanswered and that is allowed.
        assertFalse(d.entry(coke, promo).isAnswered)
        assertEquals(listOf(water), d.outstanding)
    }

    @Test
    fun `answering only the optional criterion finishes nothing`() {
        val d = draft().withContent(coke, promo, "Mua 2 tặng 1")

        assertFalse(d.isComplete(coke))
        assertEquals(0, d.answeredIn(coke))
    }

    @Test
    fun `blank is not an answer`() {
        // Whitespace in a field the rep tabbed through must not read as a reading
        // taken. The server would store it and it would report later as a price of
        // nothing rather than as a question never asked.
        val d = draft()
            .withContent(coke, price, "   ")
            .withContent(coke, facings, "3")

        assertFalse(d.isComplete(coke))
    }

    @Test
    fun `the grid can only be sent once every pairing is answered`() {
        val half = draft()
            .withContent(coke, price, "12000")
            .withContent(coke, facings, "3")

        assertFalse(half.canSubmit)

        val whole = half
            .withContent(water, price, "5000")
            .withContent(water, facings, "2")

        assertTrue(whole.canSubmit)
        assertTrue(whole.outstanding.isEmpty())
    }

    @Test
    fun `a survey with no pairings is finished rather than stuck`() {
        // Head office can publish an empty survey. A rep who cannot get past it is
        // stranded on a screen with nothing to fill in.
        assertTrue(draft(pairings = emptyList()).canSubmit)
    }

    @Test
    fun `a survey with no required criteria is finished as soon as it opens`() {
        assertTrue(draft(criteria = listOf(promo)).canSubmit)
    }

    @Test
    fun `only answered cells are sent`() {
        val d = draft()
            .withContent(coke, price, "12000")
            .withContent(coke, facings, "3")
            .withContent(coke, promo, "Mua 2 tặng 1")
            .withContent(water, price, "5000")

        val cells = d.filledCells()

        assertEquals(4, cells.size)
        // Untouched cells contribute nothing: an empty row would read later as
        // "asked and found nothing" rather than "not asked".
        assertTrue(cells.none { it.pairing == water && it.criterion == facings })
    }

    @Test
    fun `content is trimmed of nothing here and everything at the edge`() {
        // The draft keeps what the rep typed so the cursor does not jump; the
        // repository trims on the way out. What matters here is that the padded
        // value still counts as an answer.
        val d = draft().withContent(coke, price, " 12000 ")

        assertEquals(" 12000 ", d.entry(coke, price).content)
        assertTrue(d.entry(coke, price).isAnswered)
    }

    // -------------------------------------------------------------------------
    // Photos
    // -------------------------------------------------------------------------

    private fun photo(path: String) = CompetitorPhoto(path, takenAtEpochMs = 1L, sizeBytes = 100)

    @Test
    fun `photos attach to the cell that asked for them`() {
        val d = draft()
            .withPhoto(coke, price, photo("/a.jpg"))
            .withPhoto(water, price, photo("/b.jpg"))

        assertEquals(1, d.entry(coke, price).photoCount)
        assertEquals(0, d.entry(coke, facings).photoCount)
        assertEquals(1, d.entry(water, price).photoCount)
    }

    @Test
    fun `the ceiling is a ceiling`() {
        var d = draft().copy(photoMax = 2)
        repeat(5) { d = d.withPhoto(coke, price, photo("/$it.jpg")) }

        assertEquals(2, d.entry(coke, price).photoCount)
    }

    @Test
    fun `photos the server already holds count towards the ceiling`() {
        // They are as real to the record as a fresh shot, and a cell that already
        // has four should not accept a fifth just because the earlier ones were
        // taken on a previous submission.
        val d = draft()
            .copy(
                photoMax = 2,
                entries = mapOf(
                    DraftCompetitorSurvey.cellKey(coke.productId, coke.competitorProductId, price.id)
                        to CompetitorEntry(content = "12000", alreadyStored = listOf("x/a.jpg", "x/b.jpg")),
                ),
            )
            .withPhoto(coke, price, photo("/c.jpg"))

        assertEquals(2, d.entry(coke, price).photoCount)
        assertTrue(d.entry(coke, price).photos.isEmpty())
    }

    @Test
    fun `a shot can be taken back but a stored one cannot`() {
        val d = draft()
            .copy(
                entries = mapOf(
                    DraftCompetitorSurvey.cellKey(coke.productId, coke.competitorProductId, price.id)
                        to CompetitorEntry(content = "12000", alreadyStored = listOf("x/a.jpg")),
                ),
            )
            .withPhoto(coke, price, photo("/c.jpg"))
            .withoutPhoto(coke, price, "/c.jpg")

        assertEquals(1, d.entry(coke, price).photoCount)
        assertEquals(listOf("x/a.jpg"), d.entry(coke, price).alreadyStored)
    }

    @Test
    fun `what the visit already answered comes back as answered`() {
        // The data layer seeds the draft with the server's rows through this same
        // key, so a reopened survey must read as finished rather than blank.
        val seeded = draft().copy(
            entries = listOf(coke, water).flatMap { p ->
                listOf(price, facings).map { c ->
                    DraftCompetitorSurvey.cellKey(p.productId, p.competitorProductId, c.id) to
                        CompetitorEntry(content = "1")
                }
            }.toMap(),
        )

        assertTrue(seeded.canSubmit)
    }

    @Test
    fun `bytes queued counts only what is still to upload`() {
        val d = draft()
            .withPhoto(coke, price, photo("/a.jpg"))
            .withPhoto(water, price, photo("/b.jpg"))

        assertEquals(200, d.totalSizeBytes)
    }
}
