package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.PricedProduct
import com.tinhcd.myesalessfa.domain.model.PricedUnit
import com.tinhcd.myesalessfa.domain.model.Product
import com.tinhcd.myesalessfa.domain.model.ProductSort
import com.tinhcd.myesalessfa.domain.model.SaleUnit
import com.tinhcd.myesalessfa.domain.model.browse
import com.tinhcd.myesalessfa.domain.model.categoryNames
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderCatalogueTest {

    private fun priced(
        id: String,
        code: String,
        name: String,
        category: String?,
        price: Long,
    ) = PricedProduct(
        product = Product(id, code, name, category, "PCS", 1000),
        units = listOf(
            PricedUnit(SaleUnit("CASE", "Thùng", 24, isDefault = true, sortOrder = 1), price),
        ),
    )

    private val cola = priced("c1", "NGK001", "Nước ngọt Coca-Cola 330ml", "Nước giải khát", 228_000)
    private val beer = priced("c2", "NGK005", "Bia Sài Gòn Lager 330ml", "Bia", 300_000)
    private val water = priced("c3", "NGK009", "Nước suối Aquafina 500ml", "Nước giải khát", 96_000)

    private val catalogue = listOf(cola, beer, water)

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    @Test
    fun `an empty query is every product, in catalogue order`() {
        assertEquals(catalogue, catalogue.browse())
    }

    @Test
    fun `search matches the code as well as the name`() {
        assertEquals(listOf(beer), catalogue.browse(query = "NGK005"))
    }

    @Test
    fun `search ignores diacritics both ways`() {
        // A rep one-handed on a phone types "nuoc"; the catalogue says "Nước".
        assertEquals(listOf(cola, water), catalogue.browse(query = "nuoc"))
        assertEquals(listOf(beer), catalogue.browse(query = "Sài Gòn"))
    }

    @Test
    fun `search ignores case and surrounding spaces`() {
        assertEquals(listOf(beer), catalogue.browse(query = "  BIA "))
    }

    // -------------------------------------------------------------------------
    // Category filter
    // -------------------------------------------------------------------------

    @Test
    fun `no category selected means every category, not none`() {
        assertEquals(catalogue, catalogue.browse(categories = emptySet()))
    }

    @Test
    fun `selecting categories keeps only those`() {
        assertEquals(listOf(beer), catalogue.browse(categories = setOf("Bia")))
        assertEquals(
            catalogue,
            catalogue.browse(categories = setOf("Bia", "Nước giải khát")),
        )
    }

    @Test
    fun `the filter and the search both apply`() {
        assertEquals(
            listOf(cola, water),
            catalogue.browse(query = "nuoc", categories = setOf("Nước giải khát")),
        )

        // A product matching one and not the other is out. Either narrowing being
        // ignored would show a rep products they had just filtered away.
        assertEquals(
            emptyList<PricedProduct>(),
            catalogue.browse(query = "nuoc", categories = setOf("Bia")),
        )
    }

    // -------------------------------------------------------------------------
    // Sorting
    // -------------------------------------------------------------------------

    @Test
    fun `price sorts cheapest first, on the unit the row prints`() {
        assertEquals(
            listOf(water, cola, beer),
            catalogue.browse(sort = ProductSort.PRICE_ASC),
        )
    }

    @Test
    fun `in-basket floats what has been ordered to the top`() {
        val inBasket = mapOf("c2" to 48, "c3" to 12)

        assertEquals(
            listOf(beer, water, cola),
            catalogue.browse(sort = ProductSort.IN_BASKET) { inBasket[it] ?: 0 },
        )
    }

    @Test
    fun `in-basket with an empty basket leaves catalogue order alone`() {
        assertEquals(catalogue, catalogue.browse(sort = ProductSort.IN_BASKET))
    }

    // -------------------------------------------------------------------------
    // The filter sheet's own list
    // -------------------------------------------------------------------------

    @Test
    fun `categories are listed once each and sorted`() {
        assertEquals(listOf("Bia", "Nước giải khát"), catalogue.categoryNames())
    }

    @Test
    fun `a product with no category contributes nothing to the filter`() {
        val odd = priced("c4", "NGK099", "Hàng lẻ", null, 1_000)

        assertEquals(listOf("Bia", "Nước giải khát"), (catalogue + odd).categoryNames())
        // ...and it is still reachable while no filter is on.
        assertEquals(4, (catalogue + odd).browse().size)
    }
}
