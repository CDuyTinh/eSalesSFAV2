package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.DraftPosmCheck
import com.tinhcd.myesalessfa.domain.model.PosmAtCustomer
import com.tinhcd.myesalessfa.domain.model.PosmCondition
import com.tinhcd.myesalessfa.domain.model.PosmPhoto
import com.tinhcd.myesalessfa.domain.model.PosmRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checking the company's furniture in someone else's shop.
 *
 * The rule under test is the one the server also enforces: a check needs the
 * count, the condition and the photos together. Any one of them missing leaves a
 * row saying an asset was inspected and nothing useful about it.
 */
class PosmCheckTest {

    private val fridge = PosmAtCustomer(
        programId = "p1",
        programCode = "POSM2026",
        programName = "Trang bị điểm bán 2026",
        itemId = "i1",
        itemCode = "TL200",
        itemName = "Tủ lạnh trưng bày 200L",
        unitName = "Cái",
        imageUrl = null,
        placedQty = 2,
    )

    private fun draft(photos: Int = 1) = DraftPosmCheck(
        visitId = "v1",
        customerId = "c1",
        item = fridge,
        photos = (1..photos).map {
            PosmPhoto(localPath = "/p/$it.jpg", takenAtEpochMs = 0, sizeBytes = 30_000)
        },
    )

    @Test
    fun `a check needs the count, the condition and a photo`() {
        val bare = draft()

        assertFalse("nothing answered", bare.canSubmit)
        assertFalse("counted only", bare.copy(countedQty = 2).canSubmit)
        assertFalse(
            "condition only",
            bare.copy(condition = PosmCondition.USABLE).canSubmit,
        )
        assertFalse(
            "no photo",
            bare.copy(photos = emptyList(), countedQty = 2, condition = PosmCondition.USABLE)
                .canSubmit,
        )
        assertTrue(
            bare.copy(countedQty = 2, condition = PosmCondition.USABLE).canSubmit,
        )
    }

    @Test
    fun `finding none of an asset is an answer, not a blank`() {
        // A fridge that has vanished is exactly what this step exists to catch, so
        // counting zero must be submittable.
        val gone = draft().copy(countedQty = 0, condition = PosmCondition.UNUSABLE)

        assertTrue(gone.canSubmit)
        assertTrue(fridge.isShort(0))
    }

    @Test
    fun `a shortfall is measured against what the company lent`() {
        assertTrue(fridge.isShort(1))
        assertFalse(fridge.isShort(2))
        // More than the records claim is not a shortfall. It is a different
        // problem, and not one this flag is asked about.
        assertFalse(fridge.isShort(3))
    }

    @Test
    fun `photos stop at the ceiling`() {
        val full = draft(photos = 4)

        assertFalse(full.canAddPhoto)
        assertEquals(
            "the fifth is ignored, not appended",
            4,
            full.withPhoto(PosmPhoto("/p/5.jpg", 0)).photoCount,
        )
        assertTrue(full.withoutPhoto("/p/1.jpg").canAddPhoto)
    }

    @Test
    fun `an item is only checked once it carries a condition`() {
        assertFalse(fridge.isChecked)
        assertFalse(fridge.copy(countedQty = 2).isChecked)
        assertTrue(fridge.copy(countedQty = 2, condition = PosmCondition.REPAIRABLE).isChecked)
    }

    @Test
    fun `condition codes survive the round trip and an unknown one does not crash`() {
        PosmCondition.entries.forEach {
            assertEquals(it, PosmCondition.fromWire(it.wireValue))
        }
        // A server that gains a fourth condition should not take the step down.
        assertNull(PosmCondition.fromWire("melted"))
        assertNull(PosmCondition.fromWire(null))
    }

    @Test
    fun `outstanding delivery is what is approved but not yet in the shop`() {
        val registration = PosmRegistration(
            programId = "p1",
            programCode = "POSM2026",
            programName = "Trang bị điểm bán 2026",
            itemId = "i2",
            itemCode = "KE3T",
            itemName = "Kệ trưng bày 3 tầng",
            unitName = "Cái",
            registeredQty = 4,
            approvedQty = 2,
            deliveredQty = 1,
            status = "approved",
            registeredAt = null,
        )

        assertEquals(1, registration.awaitingDelivery)
        assertEquals(0, registration.copy(deliveredQty = 2).awaitingDelivery)
        // A back-office over-delivery reads as nothing outstanding, not as a debt
        // owed the other way.
        assertEquals(0, registration.copy(deliveredQty = 5).awaitingDelivery)
        assertFalse(registration.isPending)
        assertTrue(registration.copy(status = "pending").isPending)
        assertTrue(registration.copy(status = "rejected").isRejected)
    }
}
