package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.DraftPosmMovement
import com.tinhcd.myesalessfa.domain.model.DraftPosmRegistration
import com.tinhcd.myesalessfa.domain.model.PosmCatalogueEntry
import com.tinhcd.myesalessfa.domain.model.PosmMovementKind
import com.tinhcd.myesalessfa.domain.model.PosmMovementLine
import com.tinhcd.myesalessfa.domain.model.PosmPhoto
import com.tinhcd.myesalessfa.domain.model.PosmRegistrationLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asking for POSM, handing it over, taking it back.
 *
 * Every ceiling here is one the server applies too — `submit_posm_registration`
 * checks the programme's max_per_customer, `submit_posm_movement` checks the
 * approved remainder and the holding. The point of having them on the device as
 * well is that a rep standing in a shop finds out from the stepper rather than
 * from a rejection after the upload.
 */
class PosmMovementTest {

    private fun entry(
        max: Int = 0,
        placed: Int = 0,
        registered: Int = 0,
        status: String? = null,
        itemId: String = "i1",
    ) = PosmCatalogueEntry(
        programId = "p1",
        programCode = "POSM26",
        programName = "Trang bị điểm bán 2026",
        itemId = itemId,
        itemCode = "TL200",
        itemName = "Tủ lạnh trưng bày 200L",
        unitName = "Cái",
        imageUrl = null,
        maxPerCustomer = max,
        registeredQty = registered,
        approvedQty = 0,
        deliveredQty = 0,
        placedQty = placed,
        registrationStatus = status,
    )

    private fun registration(vararg entries: PosmCatalogueEntry) = DraftPosmRegistration(
        visitId = "v1",
        lines = entries.map { PosmRegistrationLine(it) },
    )

    // -------------------------------------------------------------------------
    // Đăng ký
    // -------------------------------------------------------------------------

    @Test
    fun `nothing asked for is nothing to send`() {
        assertFalse(registration(entry()).canSubmit)
    }

    @Test
    fun `a line with a number on it is`() {
        val draft = registration(entry()).withQty("i1", "p1", 2)

        assertTrue(draft.canSubmit)
        assertEquals(1, draft.askedLines.size)
        assertEquals(2, draft.askedLines.first().qty)
    }

    @Test
    fun `no ceiling means no ceiling`() {
        // max_per_customer zero is the legacy's "as many as head office allows",
        // which is a decision for them and not a number the rep is held to.
        val draft = registration(entry(max = 0)).withQty("i1", "p1", 99)

        assertEquals(99, draft.lines.first().qty)
    }

    @Test
    fun `the ceiling counts what the outlet already holds`() {
        // Two allowed, one on the shelf: the rep may ask for one more, and the
        // stepper stops there rather than sending a request that comes back
        // refused.
        val draft = registration(entry(max = 2, placed = 1)).withQty("i1", "p1", 5)

        assertEquals(1, draft.lines.first().qty)
    }

    @Test
    fun `an outlet already at the ceiling can ask for nothing`() {
        val draft = registration(entry(max = 2, placed = 2)).withQty("i1", "p1", 3)

        assertEquals(0, draft.lines.first().qty)
        assertFalse(draft.canSubmit)
    }

    @Test
    fun `a request is restatable while it is pending and not once it is ruled on`() {
        // The rebuild keeps one row per outlet and asset where the legacy files a
        // new LineRef per request, so this is the line between correcting an ask
        // and overwriting an approval.
        assertTrue(entry(status = null).canRegister)
        assertTrue(entry(status = "pending").canRegister)
        assertFalse(entry(status = "approved").canRegister)
        assertFalse(entry(status = "rejected").canRegister)
    }

    @Test
    fun `only the line the rep touched moves`() {
        val draft = registration(entry(itemId = "i1"), entry(itemId = "i2"))
            .withQty("i2", "p1", 3)

        assertEquals(0, draft.lines.first { it.entry.itemId == "i1" }.qty)
        assertEquals(3, draft.lines.first { it.entry.itemId == "i2" }.qty)
    }

    // -------------------------------------------------------------------------
    // Giao và thu hồi
    // -------------------------------------------------------------------------

    private fun line(available: Int, qty: Int = 0, itemId: String = "i1") = PosmMovementLine(
        programId = "p1",
        itemId = itemId,
        itemName = "Tủ lạnh trưng bày 200L",
        unitName = "Cái",
        available = available,
        qty = qty,
    )

    private fun movement(
        kind: PosmMovementKind = PosmMovementKind.DELIVERY,
        vararg lines: PosmMovementLine,
    ) = DraftPosmMovement(visitId = "v1", kind = kind, lines = lines.toList())

    private fun photo(path: String) = PosmPhoto(path, takenAtEpochMs = 1L, sizeBytes = 100)

    @Test
    fun `a handover needs both a quantity and a photograph`() {
        val bare = movement(lines = arrayOf(line(available = 2)))
        assertFalse(bare.canSubmit)
        assertEquals(1, bare.photosStillNeeded)

        val counted = bare.withQty("i1", "p1", 1)
        // A number without evidence is still not a record.
        assertFalse(counted.canSubmit)

        val evidenced = counted.withPhoto(photo("/a.jpg"))
        assertTrue(evidenced.canSubmit)
        assertEquals(0, evidenced.photosStillNeeded)
    }

    @Test
    fun `a photograph without a quantity moves nothing`() {
        val draft = movement(lines = arrayOf(line(available = 2)))
            .withPhoto(photo("/a.jpg"))

        assertTrue(draft.movingLines.isEmpty())
        assertFalse(draft.canSubmit)
    }

    @Test
    fun `no line may move more than is available`() {
        // Delivery is capped by what head office approved and has not sent; recall
        // by what the shop is holding. Both arrive as `available`.
        val draft = movement(lines = arrayOf(line(available = 3))).withQty("i1", "p1", 10)

        assertEquals(3, draft.lines.first().qty)
        assertEquals(3, draft.totalQty)
    }

    @Test
    fun `a quantity can be taken back down to nothing`() {
        val draft = movement(lines = arrayOf(line(available = 3, qty = 3)))
            .withQty("i1", "p1", 0)

        assertEquals(0, draft.lines.first().qty)
        assertTrue(draft.movingLines.isEmpty())
    }

    @Test
    fun `several lines add up`() {
        val draft = movement(
            lines = arrayOf(line(available = 2, itemId = "i1"), line(available = 5, itemId = "i2")),
        )
            .withQty("i1", "p1", 2)
            .withQty("i2", "p1", 3)
            .withPhoto(photo("/a.jpg"))

        assertEquals(2, draft.movingLines.size)
        assertEquals(5, draft.totalQty)
        assertTrue(draft.canSubmit)
    }

    @Test
    fun `the photo ceiling is a ceiling`() {
        var draft = movement(lines = arrayOf(line(available = 1, qty = 1))).copy(photoMax = 2)
        repeat(5) { draft = draft.withPhoto(photo("/$it.jpg")) }

        assertEquals(2, draft.photoCount)
        assertFalse(draft.canAddPhoto)
    }

    @Test
    fun `a rejected shot goes, and the handover waits again`() {
        val draft = movement(lines = arrayOf(line(available = 1, qty = 1)))
            .withPhoto(photo("/a.jpg"))
            .withoutPhoto("/a.jpg")

        assertEquals(0, draft.photoCount)
        assertFalse(draft.canSubmit)
    }

    @Test
    fun `a recall is shaped the same as a delivery`() {
        // One form, two directions — the legacy uses one screen for its order
        // types IN and IR, and the rules on either side are identical.
        val draft = movement(PosmMovementKind.RECALL, line(available = 2))
            .withQty("i1", "p1", 5)
            .withPhoto(photo("/a.jpg"))

        assertEquals(2, draft.totalQty)
        assertTrue(draft.canSubmit)
    }
}
