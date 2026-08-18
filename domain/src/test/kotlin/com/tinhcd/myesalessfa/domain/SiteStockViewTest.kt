package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.Site
import com.tinhcd.myesalessfa.domain.model.SiteStockItem
import com.tinhcd.myesalessfa.domain.model.SiteStockView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteStockViewTest {

    private val coke = SiteStockItem(
        productId = "p1",
        productCode = "NGK001",
        productName = "Nước ngọt Coca-Cola 330ml",
        baseUom = "PCS",
        qtyBase = 480,
        updatedAtEpochMs = 3_000,
    )

    private val water = SiteStockItem(
        productId = "p2",
        productCode = "NGK003",
        productName = "Nước suối Aquafina 500ml",
        baseUom = "PCS",
        qtyBase = 0,
        updatedAtEpochMs = 1_000,
    )

    private fun view(query: String = "") = SiteStockView(
        sites = listOf(
            Site("s1", "KHO01", "Kho chính", null),
            Site("s2", "KHO02", "Kho Lái Thiêu", null),
        ),
        siteId = "s1",
        items = listOf(coke, water),
        query = query,
    )

    @Test
    fun `the selected site is resolved from the id`() {
        assertEquals("KHO01", view().site?.code)
        assertNull(view().copy(siteId = "gone").site)
    }

    @Test
    fun `zero is out of stock and a positive quantity is not`() {
        assertTrue(water.isOutOfStock)
        assertFalse(coke.isOutOfStock)
        assertEquals(1, view().outOfStockCount)
    }

    @Test
    fun `search matches without diacritics`() {
        // A rep types "nuoc suoi" one-handed; the catalogue spells it properly.
        assertEquals(listOf(water), view(query = "nuoc suoi").visible)
    }

    @Test
    fun `search matches the product code too`() {
        assertEquals(listOf(coke), view(query = "ngk001").visible)
    }

    @Test
    fun `an empty query shows everything`() {
        assertEquals(2, view().visible.size)
        assertEquals(2, view(query = "   ").visible.size)
    }

    @Test
    fun `a query matching nothing shows nothing rather than everything`() {
        assertTrue(view(query = "bia").visible.isEmpty())
    }

    @Test
    fun `freshness quotes the oldest line, not the newest`() {
        // One product updated a minute ago must not vouch for a list where
        // everything else is a week stale.
        assertEquals(1_000L, view().oldestUpdateEpochMs)
    }

    @Test
    fun `stock with no timestamps has no freshness to quote`() {
        val undated = view().copy(
            items = listOf(coke.copy(updatedAtEpochMs = null)),
        )

        assertNull(undated.oldestUpdateEpochMs)
    }
}
