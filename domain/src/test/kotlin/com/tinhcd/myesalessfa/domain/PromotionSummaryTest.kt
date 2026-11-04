package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.AppliedManualPromotion
import com.tinhcd.myesalessfa.domain.model.EarnedPromotion
import com.tinhcd.myesalessfa.domain.model.DisplayRewardDue
import com.tinhcd.myesalessfa.domain.model.DisplayRewardType
import com.tinhcd.myesalessfa.domain.model.LevelCapCut
import com.tinhcd.myesalessfa.domain.model.ManualPromotion
import com.tinhcd.myesalessfa.domain.model.ManualPromotionItem
import com.tinhcd.myesalessfa.domain.model.ManualPromotionType
import com.tinhcd.myesalessfa.domain.model.PromotionChoice
import com.tinhcd.myesalessfa.domain.model.PromotionGift
import com.tinhcd.myesalessfa.domain.model.PromotionReward
import com.tinhcd.myesalessfa.domain.model.PromotionScope
import com.tinhcd.myesalessfa.domain.model.PromotionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of promotions the device really owns: what the rep decided.
 *
 * The amounts come from the server and the server recomputes them when it books
 * the order, so nothing here is arithmetic anyone relies on. What is relied on is
 * that the total the rep reads out matches the choice they made — a rule whose
 * money was swapped for goods must stop counting the moment they swap it, not
 * when the confirmation comes back.
 */
class PromotionSummaryTest {

    private val oreo = PromotionGift("p-oreo", "BK001", "Bánh Oreo", "PCS", "Le", 2, availableQty = 20, chosen = true)
    private val cosy = PromotionGift("p-cosy", "BK002", "Bánh Cosy", "PCS", "Le", 1, availableQty = 0, chosen = false)

    private fun promo(
        id: String = "s1",
        reward: PromotionReward = PromotionReward.AMOUNT,
        amount: Long = 30_000,
        needsChoice: Boolean = false,
        gifts: List<PromotionGift> = emptyList(),
    ) = EarnedPromotion(
        sequenceId = id,
        programCode = "KM-BK",
        programName = "Bánh kẹo",
        sequenceName = "Mua 10 gói",
        scope = PromotionScope.LINE,
        reward = reward,
        breakId = "b1",
        breakName = "Mức 1",
        portion = 1,
        discountAmount = amount,
        percent = null,
        needsChoice = needsChoice,
        gifts = gifts,
    )

    @Test
    fun `a plain money rule counts towards the total`() {
        val summary = PromotionSummary(earned = listOf(promo()))

        assertEquals(30_000, summary.totalDiscount)
        assertFalse(summary.isEmpty)
        assertTrue(summary.undecided.isEmpty())
    }

    @Test
    fun `taking goods on an either-or rule gives up its money`() {
        val either = promo(
            reward = PromotionReward.AMOUNT_OR_ITEM,
            needsChoice = true,
            gifts = listOf(oreo, cosy),
        )
        val summary = PromotionSummary(earned = listOf(either))

        // The server's default is the money, so the untouched summary still counts it.
        assertEquals(30_000, summary.totalDiscount)
        assertEquals(emptyList<PromotionGift>(), summary.giftsOf(either))

        val takingGoods = summary.withChoice(
            PromotionChoice("s1", takeAmount = false, freeProductId = "p-cosy", freeUomCode = "PCS"),
        )

        assertEquals(0, takingGoods.totalDiscount)
        assertEquals(listOf(cosy), takingGoods.giftsOf(either))
    }

    @Test
    fun `a bundle gives everything the server pre-selected`() {
        val bundle = promo(
            reward = PromotionReward.FREE_ITEM,
            amount = 0,
            gifts = listOf(oreo, cosy.copy(chosen = true)),
        )
        val summary = PromotionSummary(earned = listOf(bundle))

        assertEquals(2, summary.giftsOf(bundle).size)
        assertEquals(0, summary.totalDiscount)
    }

    @Test
    fun `alternatives give only the one the rep named`() {
        val either = promo(
            reward = PromotionReward.FREE_ITEM,
            amount = 0,
            needsChoice = true,
            gifts = listOf(oreo, cosy),
        )
        val summary = PromotionSummary(earned = listOf(either))

        // Untouched, the server's pre-selection stands.
        assertEquals(listOf(oreo), summary.giftsOf(either))

        val picked = summary.withChoice(
            PromotionChoice("s1", takeAmount = false, freeProductId = "p-cosy"),
        )

        assertEquals(listOf(cosy), picked.giftsOf(either))
    }

    @Test
    fun `rules still awaiting an answer are listed, and stop being once answered`() {
        val asking = promo(needsChoice = true, gifts = listOf(oreo, cosy))
        val summary = PromotionSummary(earned = listOf(asking, promo(id = "s2")))

        assertEquals(listOf("s1"), summary.undecided.map { it.sequenceId })
        assertTrue(
            summary.withChoice(PromotionChoice("s1", takeAmount = false))
                .undecided.isEmpty(),
        )
    }

    @Test
    fun `an answer to a rule the basket no longer earns is dropped`() {
        // The rep swapped goods for money on a rule, then removed the products
        // that earned it. Carrying the answer forward would send a choice about a
        // rule the order does not qualify for.
        val summary = PromotionSummary(earned = listOf(promo(), promo(id = "s2")))
            .withChoice(PromotionChoice("s1", takeAmount = false))
            .withChoice(PromotionChoice("s2", takeAmount = false))

        val pruned = summary.prunedTo(listOf(promo(id = "s2")))

        assertEquals(setOf("s2"), pruned.choices.keys)
        assertEquals(listOf("s2"), pruned.earned.map { it.sequenceId })
    }

    @Test
    fun `an empty basket earns nothing and owes no answers`() {
        val summary = PromotionSummary()

        assertTrue(summary.isEmpty)
        assertEquals(0, summary.totalDiscount)
        assertTrue(summary.undecided.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Manual discounts: the ones a person decided rather than a rule
    // -------------------------------------------------------------------------

    private val percent = ManualPromotion(
        id = "m-pct", code = "KMT-CK3", name = "Chiết khấu tay 3%",
        type = ManualPromotionType.PERCENT, value = 3, allowEdit = false,
        items = emptyList(),
    )

    private val support = ManualPromotion(
        id = "m-amt", code = "KMT-TIEN", name = "Hỗ trợ khách hàng",
        type = ManualPromotionType.AMOUNT, value = 100_000, allowEdit = true,
        items = emptyList(),
    )

    private val gift = ManualPromotion(
        id = "m-gift", code = "KMT-QUA", name = "Tặng quà",
        type = ManualPromotionType.FREE_ITEM, value = 0, allowEdit = false,
        items = listOf(ManualPromotionItem("Bánh Oreo", "Le", 2)),
    )

    private fun catalogue() = PromotionSummary(
        manualCatalogue = listOf(percent, support, gift),
    )

    @Test
    fun `a percentage entry is taken on the gross the caller supplies`() {
        val applied = catalogue().withManual(AppliedManualPromotion("m-pct"))

        assertEquals(30_000, applied.manualDiscountOn(1_000_000))
        // Nothing bought, nothing given: the percentage has nothing to bite on.
        assertEquals(0, applied.manualDiscountOn(0))
    }

    @Test
    fun `an editable entry is a ceiling the rep may come under`() {
        val full = catalogue().withManual(AppliedManualPromotion("m-amt"))
        assertEquals(100_000, full.manualDiscountOn(1_000_000))

        val partial = catalogue().withManual(AppliedManualPromotion("m-amt", 60_000))
        assertEquals(60_000, partial.manualDiscountOn(1_000_000))

        // Above the ceiling never counts. The server refuses such an order
        // outright; the screen must not have shown a larger figure meanwhile.
        val over = catalogue().withManual(AppliedManualPromotion("m-amt", 500_000))
        assertEquals(100_000, over.manualDiscountOn(1_000_000))
    }

    @Test
    fun `a goods entry moves no money`() {
        val applied = catalogue().withManual(AppliedManualPromotion("m-gift"))

        assertEquals(0, applied.manualDiscountOn(1_000_000))
    }

    @Test
    fun `manual and automatic discounts add up, and each can be taken back`() {
        val summary = catalogue()
            .copy(earned = listOf(promo()))
            .withManual(AppliedManualPromotion("m-pct"))
            .withManual(AppliedManualPromotion("m-amt", 40_000))

        assertEquals(30_000, summary.totalDiscount)
        assertEquals(70_000, summary.manualDiscountOn(1_000_000))

        val fewer = summary.withoutManual("m-amt")
        assertEquals(30_000, fewer.manualDiscountOn(1_000_000))
    }

    @Test
    fun `an entry that is not in the catalogue is worth nothing`() {
        // A stale application after the catalogue moved on. The server refuses
        // it; the screen must not add a figure it cannot explain in the meantime.
        val summary = catalogue().withManual(AppliedManualPromotion("m-gone", 50_000))

        assertEquals(0, summary.manualDiscountOn(1_000_000))
    }

    @Test
    fun `a gift the depot cannot cover is short, and one it can is not`() {
        // Earned is not the same as deliverable. The screen says so on the row
        // rather than quietly handing over fewer, because the rep is the one
        // making the promise to the customer.
        assertFalse(oreo.isShort)
        assertTrue(cosy.isShort)
        assertTrue(oreo.copy(qty = 50).isShort)
        // Exactly enough is enough.
        assertFalse(oreo.copy(qty = 20).isShort)
    }

    private val cut = LevelCapCut(
        sequenceId = "s1", breakId = "b10", programName = "Mua NGK", breakName = "Mua 10 thùng",
        requestedLots = 2, grantedLots = 1, outOfQuota = true, outOfStock = true,
    )

    @Test
    fun `a cut level names every limit that cut it`() {
        assertEquals("hết suất của khách hàng, không đủ hàng tặng", cut.reasons)
        assertEquals("hết ngân sách", cut.copy(outOfQuota = false, outOfStock = false, outOfBudget = true).reasons)
    }

    @Test
    fun `a level is fully cut only when nothing was paid`() {
        assertFalse(cut.fullyCut)
        assertTrue(cut.copy(grantedLots = 0).fullyCut)
        // A percentage paid down to part of its amount still pays.
        val pct = LevelCapCut("s2", "b1", "CK", "Từ 2tr", requestedAmount = 60_000, grantedAmount = 25_000, outOfBudget = true)
        assertFalse(pct.fullyCut)
        assertTrue(pct.copy(grantedAmount = 0).fullyCut)
    }

    @Test
    fun `a fresh answer replaces the cut levels`() {
        val before = PromotionSummary(levelCaps = listOf(cut))
        assertEquals(emptyList<LevelCapCut>(), before.prunedTo(fresh = emptyList()).levelCaps)
        assertEquals(listOf(cut), PromotionSummary().prunedTo(fresh = emptyList(), freshLevelCaps = listOf(cut)).levelCaps)
    }

    // CTKM lựa chọn: a percentage taken by default over a gift in group g1.
    private val groupPct = promo(id = "pct", amount = 60_000).copy(proposalId = "g1", isDefault = true)
    private val groupGift = promo(id = "gift", reward = PromotionReward.FREE_ITEM, amount = 0, gifts = listOf(oreo))
        .copy(proposalId = "g1", isDefault = false)
    private val alone = promo(id = "alone", amount = 10_000)

    @Test
    fun `a group counts only the rule taken, the server's until the rep picks`() {
        val summary = PromotionSummary(earned = listOf(groupPct, groupGift, alone))
        assertEquals(70_000, summary.totalDiscount)
        assertTrue(summary.giftsOf(groupGift).isEmpty())
        assertEquals(listOf("pct"), summary.takenInGroups)

        val picked = summary.withProposalPick("g1", "gift")
        assertEquals(10_000, picked.totalDiscount)
        assertEquals(listOf(oreo), picked.giftsOf(groupGift))
        assertFalse(picked.isTaken(groupPct))
        assertEquals(listOf("gift"), picked.takenInGroups)
    }

    @Test
    fun `a pick is dropped once its rule is no longer on offer`() {
        val picked = PromotionSummary(earned = listOf(groupPct, groupGift)).withProposalPick("g1", "gift")
        assertEquals(mapOf("g1" to "gift"), picked.prunedTo(listOf(groupPct, groupGift)).proposalPicks)
        assertTrue(picked.prunedTo(listOf(groupPct)).proposalPicks.isEmpty())
    }

    private val money = DisplayRewardDue("r1", "TB2609", "M2", DisplayRewardType.AMOUNT, amountLeft = 700_000)
    private val pct = DisplayRewardDue("r2", "TB2610", "M1", DisplayRewardType.PERCENT, percent = 2.0)
    private val goods = DisplayRewardDue("r3", "TBQ", "CB", DisplayRewardType.GOODS, qtyLeft = 2)

    @Test
    fun `display rewards take off only what is paid on this order`() {
        val summary = PromotionSummary(displayRewards = listOf(money, pct, goods))
        assertEquals(0L, summary.displayRewardDiscountOn(5_000_000))
        val paid = summary.withDisplayReward("r1", true).withDisplayReward("r2", true).withDisplayReward("r3", true)
        // The money still due, 2 % of the goods sold, and nothing for goods.
        assertEquals(800_000L, paid.displayRewardDiscountOn(5_000_000))
        assertEquals(100_000L, paid.withDisplayReward("r1", false).displayRewardDiscountOn(5_000_000))
    }

    @Test
    fun `a paid display reward is dropped once it is no longer due`() {
        val paid = PromotionSummary(displayRewards = listOf(money, pct)).withDisplayReward("r1", true)
        assertEquals(setOf("r1"), paid.prunedTo(emptyList()).paidDisplayRewards)
        assertTrue(paid.prunedTo(emptyList(), freshDisplayRewards = listOf(pct)).paidDisplayRewards.isEmpty())
    }
}
