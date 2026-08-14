package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.PriceRule
import com.tinhcd.myesalessfa.domain.model.Product
import com.tinhcd.myesalessfa.domain.model.SaleUnit
import com.tinhcd.myesalessfa.domain.model.priceCatalogue
import com.tinhcd.myesalessfa.domain.model.priceFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PriceLookupTest {

    private val today = LocalDate.of(2026, 8, 14)
    private val classA = "class-a"
    private val classB = "class-b"

    private fun rule(
        price: Long,
        classId: String? = null,
        productId: String = "coca",
        uomCode: String = "CASE",
        from: LocalDate = LocalDate.of(2026, 1, 1),
        to: LocalDate = LocalDate.of(2099, 12, 31),
    ) = PriceRule(productId, uomCode, classId, price, from, to)

    @Test
    fun `a class price beats the list price`() {
        val rules = listOf(rule(228_000), rule(222_000, classId = classA))
        assertEquals(222_000L, rules.priceFor("coca", "CASE", classA, today))
    }

    @Test
    fun `the class price wins whichever order the rows arrive in`() {
        // Row order comes from a query with no ORDER BY guarantee on the client
        // side, so preferring the specific row must not depend on it.
        val listFirst = listOf(rule(228_000), rule(222_000, classId = classA))
        val classFirst = listOf(rule(222_000, classId = classA), rule(228_000))

        assertEquals(222_000L, listFirst.priceFor("coca", "CASE", classA, today))
        assertEquals(222_000L, classFirst.priceFor("coca", "CASE", classA, today))
    }

    @Test
    fun `a customer in another class falls back to the list price`() {
        val rules = listOf(rule(228_000), rule(222_000, classId = classA))
        assertEquals(228_000L, rules.priceFor("coca", "CASE", classB, today))
    }

    @Test
    fun `a customer with no class never picks up someone else's class price`() {
        // Charging an unclassified customer class A's volume price would be a
        // quiet discount nobody authorised.
        val rules = listOf(rule(228_000), rule(222_000, classId = classA))
        assertEquals(228_000L, rules.priceFor("coca", "CASE", null, today))
    }

    @Test
    fun `an unclassified customer with only a class price cannot be quoted`() {
        val rules = listOf(rule(222_000, classId = classA))
        assertNull(rules.priceFor("coca", "CASE", null, today))
    }

    @Test
    fun `effective dates are inclusive at both ends`() {
        val rules = listOf(
            rule(200_000, from = LocalDate.of(2026, 8, 14), to = LocalDate.of(2026, 8, 14)),
        )
        assertEquals(200_000L, rules.priceFor("coca", "CASE", null, today))
        assertNull(rules.priceFor("coca", "CASE", null, today.minusDays(1)))
        assertNull(rules.priceFor("coca", "CASE", null, today.plusDays(1)))
    }

    @Test
    fun `an order composed yesterday prices at its own date, not at submit time`() {
        // The outbox may hold an order overnight through a price change. It must
        // still cost what the rep quoted.
        val rules = listOf(
            rule(228_000, from = LocalDate.of(2026, 1, 1), to = LocalDate.of(2026, 8, 13)),
            rule(240_000, from = LocalDate.of(2026, 8, 14)),
        )
        assertEquals(228_000L, rules.priceFor("coca", "CASE", null, LocalDate.of(2026, 8, 13)))
        assertEquals(240_000L, rules.priceFor("coca", "CASE", null, today))
    }

    @Test
    fun `price is per unit, so units do not borrow each other's price`() {
        val rules = listOf(rule(228_000, uomCode = "CASE"), rule(10_000, uomCode = "PCS"))
        assertEquals(228_000L, rules.priceFor("coca", "CASE", null, today))
        assertEquals(10_000L, rules.priceFor("coca", "PCS", null, today))
        assertNull(rules.priceFor("coca", "PACK", null, today))
    }

    // -------------------------------------------------------------------------
    // Catalogue assembly
    // -------------------------------------------------------------------------

    private fun product(id: String, code: String) =
        Product(id, code, "Product $code", "Nuoc giai khat", "PCS", 1000)

    private fun unit(code: String, rate: Int, default: Boolean = false, sort: Int = 0) =
        SaleUnit(code, code, rate, default, sort)

    @Test
    fun `a unit with no price for this customer is not offered`() {
        // The server refuses to book an unpriced line, so showing it to the rep
        // only sets up a rejection after they have told the customer it is on
        // its way.
        val catalogue = priceCatalogue(
            products = listOf(product("coca", "NGK001")),
            unitsByProduct = mapOf(
                "coca" to listOf(unit("PCS", 1, sort = 1), unit("CASE", 24, sort = 2)),
            ),
            priceRules = listOf(rule(228_000, uomCode = "CASE")),
            classId = null,
            on = today,
        )

        val units = catalogue.single().units
        assertEquals(listOf("CASE"), units.map { it.unit.uomCode })
    }

    @Test
    fun `a product with no priceable unit disappears entirely`() {
        val catalogue = priceCatalogue(
            products = listOf(product("coca", "NGK001"), product("pepsi", "NGK002")),
            unitsByProduct = mapOf(
                "coca" to listOf(unit("CASE", 24)),
                "pepsi" to listOf(unit("CASE", 24)),
            ),
            priceRules = listOf(rule(228_000, productId = "coca", uomCode = "CASE")),
            classId = null,
            on = today,
        )

        assertEquals(listOf("NGK001"), catalogue.map { it.product.code })
    }

    @Test
    fun `units keep the order head office sorted them in`() {
        val catalogue = priceCatalogue(
            products = listOf(product("candy", "BK003")),
            unitsByProduct = mapOf(
                "candy" to listOf(
                    unit("CASE", 100, default = true, sort = 3),
                    unit("PCS", 1, sort = 1),
                    unit("PACK", 10, sort = 2),
                ),
            ),
            priceRules = listOf(
                rule(12_000, productId = "candy", uomCode = "PCS"),
                rule(115_000, productId = "candy", uomCode = "PACK"),
                rule(1_130_000, productId = "candy", uomCode = "CASE"),
            ),
            classId = null,
            on = today,
        )

        val priced = catalogue.single()
        assertEquals(listOf("PCS", "PACK", "CASE"), priced.units.map { it.unit.uomCode })
        // The default is what the rep gets pre-selected, regardless of position.
        assertEquals("CASE", priced.defaultUnit.unit.uomCode)
        assertEquals(1_130_000L, priced.defaultUnit.price)
    }

    @Test
    fun `with no unit flagged default the first offered unit is used`() {
        val catalogue = priceCatalogue(
            products = listOf(product("coca", "NGK001")),
            unitsByProduct = mapOf(
                "coca" to listOf(unit("PCS", 1, sort = 1), unit("CASE", 24, sort = 2)),
            ),
            priceRules = listOf(
                rule(10_000, uomCode = "PCS"),
                rule(228_000, uomCode = "CASE"),
            ),
            classId = null,
            on = today,
        )

        assertEquals("PCS", catalogue.single().defaultUnit.unit.uomCode)
    }

    @Test
    fun `class pricing applies per unit, leaving other units at list price`() {
        // Only the case is discounted for class A in the seed; the loose price is
        // the same for everyone.
        val catalogue = priceCatalogue(
            products = listOf(product("coca", "NGK001")),
            unitsByProduct = mapOf(
                "coca" to listOf(unit("PCS", 1, sort = 1), unit("CASE", 24, sort = 2)),
            ),
            priceRules = listOf(
                rule(10_000, uomCode = "PCS"),
                rule(228_000, uomCode = "CASE"),
                rule(222_000, uomCode = "CASE", classId = classA),
            ),
            classId = classA,
            on = today,
        )

        val byUnit = catalogue.single().units.associate { it.unit.uomCode to it.price }
        assertEquals(mapOf("PCS" to 10_000L, "CASE" to 222_000L), byUnit)
    }

    @Test
    fun `an empty catalogue is empty, not a crash`() {
        assertTrue(
            priceCatalogue(emptyList(), emptyMap(), emptyList(), null, today).isEmpty(),
        )
    }
}
