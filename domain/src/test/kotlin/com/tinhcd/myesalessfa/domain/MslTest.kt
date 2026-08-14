package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.MslDefinition
import com.tinhcd.myesalessfa.domain.model.MslItem
import com.tinhcd.myesalessfa.domain.model.mslFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The expected figures mirror the seeded lists, which were checked against the
 * live database: KH001 (General Trade + Tap hoa) resolves Coca to par 48 from
 * CORE+GT, while KH003 (Modern Trade + Sieu thi mini) resolves it to 24 from CORE
 * alone and never sees GT's additions.
 */
class MslTest {

    private val today = LocalDate.of(2026, 8, 14)
    private val gt = "channel-gt"
    private val mt = "channel-mt"
    private val tapHoa = "shop-th"
    private val sieuThi = "shop-sm"

    private fun list(
        code: String,
        channelId: String? = null,
        shopTypeId: String? = null,
        items: List<Pair<String, Int>>,
        from: LocalDate = LocalDate.of(2026, 1, 1),
        to: LocalDate = LocalDate.of(2099, 12, 31),
    ) = MslDefinition(
        id = code,
        code = code,
        channelId = channelId,
        shopTypeId = shopTypeId,
        fromDate = from,
        toDate = to,
        items = items.map { (id, min) -> MslItem(id, min) },
    )

    private val core = list("CORE", items = listOf("coca" to 24, "pepsi" to 24, "oreo" to 36))
    private val gtExtras = list("GT", channelId = gt, items = listOf("coca" to 48, "oishi" to 60))
    private val smExtras = list("SM", shopTypeId = sieuThi, items = listOf("ps" to 72))

    @Test
    fun `lists are unioned rather than chosen between`() {
        // Adding a channel list must not discard the national one. A rep in GT owes
        // everything CORE demands plus everything GT adds.
        val par = listOf(core, gtExtras).mslFor(gt, tapHoa, today)

        assertEquals(setOf("coca", "pepsi", "oreo", "oishi"), par.keys)
        assertEquals(24, par["pepsi"])
        assertEquals(60, par["oishi"])
    }

    @Test
    fun `where two lists demand the same product the stricter par wins`() {
        // CORE wants 24 of Coca, GT wants 48. Both are obligations, so 48 governs.
        // This is where MSL deliberately differs from pricing, which must pick one.
        assertEquals(48, listOf(core, gtExtras).mslFor(gt, tapHoa, today)["coca"])
        assertEquals(48, listOf(gtExtras, core).mslFor(gt, tapHoa, today)["coca"])
    }

    @Test
    fun `a customer outside the channel never picks up its additions`() {
        val par = listOf(core, gtExtras, smExtras).mslFor(mt, sieuThi, today)

        assertEquals(setOf("coca", "pepsi", "oreo", "ps"), par.keys)
        // CORE's figure, not GT's stricter one.
        assertEquals(24, par["coca"])
    }

    @Test
    fun `a list scoped by shop type alone applies across channels`() {
        val inMt = listOf(smExtras).mslFor(mt, sieuThi, today)
        val inGt = listOf(smExtras).mslFor(gt, sieuThi, today)

        assertEquals(72, inMt["ps"])
        assertEquals(72, inGt["ps"])
        // But not to a different shop type.
        assertTrue(listOf(smExtras).mslFor(gt, tapHoa, today).isEmpty())
    }

    @Test
    fun `an outlet with no channel picks up only the lists naming no channel`() {
        // The same asymmetry as the price lookup: an unclassified outlet must not
        // inherit obligations written for a segment it is not in.
        val par = listOf(core, gtExtras).mslFor(channelId = null, shopTypeId = null, on = today)

        assertEquals(setOf("coca", "pepsi", "oreo"), par.keys)
        assertEquals(24, par["coca"])
    }

    @Test
    fun `effective dates are inclusive and exclude a list that has ended`() {
        val ended = list(
            "OLD",
            items = listOf("coca" to 99),
            from = LocalDate.of(2026, 1, 1),
            to = LocalDate.of(2026, 8, 13),
        )
        val starting = list(
            "NEW",
            items = listOf("pepsi" to 12),
            from = LocalDate.of(2026, 8, 14),
        )

        val par = listOf(ended, starting).mslFor(gt, tapHoa, today)
        assertEquals(setOf("pepsi"), par.keys)

        // On its last day the ended list still counts.
        assertEquals(
            99,
            listOf(ended).mslFor(gt, tapHoa, LocalDate.of(2026, 8, 13))["coca"],
        )
    }

    @Test
    fun `no applicable list resolves to no obligations, not to a crash`() {
        assertTrue(emptyList<MslDefinition>().mslFor(gt, tapHoa, today).isEmpty())
        assertTrue(listOf(gtExtras).mslFor(mt, tapHoa, today).isEmpty())
    }
}
