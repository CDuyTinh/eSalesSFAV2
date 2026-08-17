package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.CustomerDraft
import com.tinhcd.myesalessfa.domain.model.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerDraftTest {

    private val filled = CustomerDraft(name = "Tạp hoá Bà Bảy", address = "12 Nguyễn Văn Cừ")

    @Test
    fun `a name and an address are enough`() {
        assertTrue(filled.canSubmit)
        assertNull(filled.nameError)
        assertNull(filled.addressError)
    }

    @Test
    fun `neither may be blank`() {
        assertNotNull(filled.copy(name = "").nameError)
        assertNotNull(filled.copy(address = "").addressError)
        assertFalse(filled.copy(name = "").canSubmit)
        assertFalse(filled.copy(address = "").canSubmit)
    }

    @Test
    fun `whitespace is not a name`() {
        // The field looks filled in and the row would be useless: an outlet called
        // "   " cannot be found again to ask about.
        assertNotNull(filled.copy(name = "   ").nameError)
        assertFalse(filled.copy(address = "  \n ").canSubmit)
    }

    @Test
    fun `a shop with no fix can still be registered`() {
        // Inside a market hall, under a flyover. Refusing here would lose the
        // outlet entirely, which is the worse of the two trades.
        assertTrue(filled.copy(point = null).canSubmit)
    }

    @Test
    fun `nothing else is required`() {
        // Everything a rep might reasonably not know while standing in a doorway
        // with a queue behind them. Head office can chase the segment later.
        assertTrue(
            filled.copy(
                phone = "",
                provinceId = null,
                districtId = null,
                wardId = null,
                classId = null,
                channelId = null,
                shopTypeId = null,
                note = "",
                point = GeoPoint(10.0, 106.0, null),
            ).canSubmit,
        )
    }
}
