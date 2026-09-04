package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.PricedProduct
import com.tinhcd.myesalessfa.domain.model.PricedUnit
import com.tinhcd.myesalessfa.domain.model.Product
import com.tinhcd.myesalessfa.domain.model.SaleUnit
import com.tinhcd.myesalessfa.domain.model.StockScope
import com.tinhcd.myesalessfa.domain.model.browse
import com.tinhcd.myesalessfa.domain.model.inScope
import org.junit.Assert.assertEquals
import org.junit.Test

class StockScopeTest {

    private fun priced(id: String, code: String, name: String, category: String) = PricedProduct(
        product = Product(id, code, name, category, "PCS", 1000),
        units = listOf(
            PricedUnit(SaleUnit("CASE", "Thùng", 24, isDefault = true, sortOrder = 1), 228_000),
        ),
    )

    private val cola = priced("c1", "NGK001", "Nước ngọt Coca-Cola 330ml", "Nước giải khát")
    private val beer = priced("c2", "NGK005", "Bia Sài Gòn Lager 330ml", "Bia")
    private val water = priced("c3", "NGK009", "Nước suối Aquafina 500ml", "Nước giải khát")

    private val catalogue = listOf(cola, beer, water)

    private val purchased = setOf("c1", "c2")
    private val mustStock = setOf("c2")

    @Test
    fun `purchased shows only what the outlet has bought`() {
        assertEquals(
            listOf(cola, beer),
            catalogue.inScope(StockScope.PURCHASED, purchased, mustStock),
        )
    }

    @Test
    fun `must-stock shows only what the outlet is obliged to hold`() {
        assertEquals(
            listOf(beer),
            catalogue.inScope(StockScope.MUST_STOCK, purchased, mustStock),
        )
    }

    @Test
    fun `all is the whole catalogue`() {
        assertEquals(catalogue, catalogue.inScope(StockScope.ALL, purchased, mustStock))
    }

    // -------------------------------------------------------------------------
    // Falling through rather than showing nothing
    // -------------------------------------------------------------------------

    @Test
    fun `no purchase history falls through to the whole catalogue`() {
        // A rep who has never sold to this outlet gets an empty history. An empty
        // sheet would read as the catalogue having failed to load, and the rep
        // cannot count their way out of it.
        assertEquals(
            catalogue,
            catalogue.inScope(StockScope.PURCHASED, emptySet(), mustStock),
        )
    }

    @Test
    fun `no must-stock list falls through the same way`() {
        assertEquals(
            catalogue,
            catalogue.inScope(StockScope.MUST_STOCK, purchased, emptySet()),
        )
    }

    @Test
    fun `a purchased product missing from the catalogue simply does not appear`() {
        // It was bought once and has since been delisted, or priced out of this
        // customer's class. Either way there is nothing to count against.
        assertEquals(
            listOf(cola),
            catalogue.inScope(StockScope.PURCHASED, setOf("c1", "gone"), mustStock),
        )
    }

    // -------------------------------------------------------------------------
    // Composing with the search and the category filter
    // -------------------------------------------------------------------------

    @Test
    fun `scope narrows before the search and the filter do`() {
        val visible = catalogue
            .inScope(StockScope.PURCHASED, purchased, mustStock)
            .browse(query = "nuoc")

        // Water matches the search but is not in the purchase history, so it is
        // out; the scope is not a suggestion the search can widen back.
        assertEquals(listOf(cola), visible)
    }

    @Test
    fun `scope and category filter both apply`() {
        val visible = catalogue
            .inScope(StockScope.PURCHASED, purchased, mustStock)
            .browse(categories = setOf("Bia"))

        assertEquals(listOf(beer), visible)
    }
}
