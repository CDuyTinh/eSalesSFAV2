package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.DraftStockCount
import com.tinhcd.myesalessfa.domain.model.PricedProduct
import com.tinhcd.myesalessfa.domain.model.PricedUnit
import com.tinhcd.myesalessfa.domain.model.Product
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.SaleUnit
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import com.tinhcd.myesalessfa.domain.repository.CatalogRepository
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
import com.tinhcd.myesalessfa.domain.repository.StockRepository
import com.tinhcd.myesalessfa.domain.usecase.GetOrderSuggestionsUseCase
import com.tinhcd.myesalessfa.domain.usecase.GetVisitCatalogueUseCase
import com.tinhcd.myesalessfa.domain.usecase.VisitCatalogue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The two in-call steps assemble their catalogue through these, so both are here. */
class GetVisitCatalogueUseCaseTest {

    private val today = LocalDate.of(2026, 9, 1)

    private val customer = Customer(
        id = "cus-1",
        code = "KH001",
        name = "Tap hoa Ba Tam",
        address = null,
        phone = null,
        lat = null,
        lng = null,
        avatarUrl = null,
        checkInRadiusM = null,
        classId = "class-a",
        channelId = "gt",
        shopTypeId = "shop-1",
    )

    private val cola = PricedProduct(
        product = Product("p-1", "SP001", "Cola", "Nuoc ngot", "PCS", 1000),
        units = listOf(
            PricedUnit(SaleUnit("CASE", "Thung", 24, isDefault = true, sortOrder = 1), 228_000),
            PricedUnit(SaleUnit("PCS", "Lon", 1, isDefault = false, sortOrder = 2), 10_000),
        ),
    )

    private class FakeRoute(
        private val result: DataResult<RouteStop?>,
    ) : RouteRepository {
        var askedFor: Pair<String, LocalDate>? = null

        override suspend fun getRoute(date: LocalDate): DataResult<List<RouteStop>> =
            DataResult.Success(emptyList())

        override suspend fun getStop(customerId: String, date: LocalDate): DataResult<RouteStop?> {
            askedFor = customerId to date
            return result
        }
    }

    private class FakeCatalog(
        private val catalogue: DataResult<List<PricedProduct>>,
        private val mustStock: DataResult<Map<String, Int>> = DataResult.Success(emptyMap()),
    ) : CatalogRepository {
        var catalogueClassId: String? = null
        var mustStockScope: Pair<String?, String?>? = null

        override suspend fun refresh(): DataResult<Unit> = DataResult.Success(Unit)

        override suspend fun catalogue(
            classId: String?,
            on: LocalDate,
        ): DataResult<List<PricedProduct>> {
            catalogueClassId = classId
            return catalogue
        }

        override suspend fun mustStock(
            channelId: String?,
            shopTypeId: String?,
            on: LocalDate,
        ): DataResult<Map<String, Int>> {
            mustStockScope = channelId to shopTypeId
            return mustStock
        }
    }

    private class FakeStock(
        private val counted: DataResult<Map<String, Int>>,
    ) : StockRepository {
        var countReads = 0

        override suspend fun previousCount(
            customerId: String,
            visitId: String,
        ): DataResult<Map<String, Int>> = DataResult.Success(emptyMap())

        override suspend fun countedBaseQty(visitId: String): DataResult<Map<String, Int>> {
            countReads++
            return counted
        }

        override suspend fun purchasedProducts(
            customerId: String,
            months: Int,
        ): DataResult<Set<String>> = DataResult.Success(emptySet())

        override suspend fun submit(count: DraftStockCount): DataResult<Unit> =
            DataResult.Success(Unit)
    }

    private fun stopOf(customer: Customer) = RouteStop(
        customer = customer,
        visitOrder = 1,
        status = VisitStatus.IN_PROGRESS,
        visitId = "v-1",
        checkInAtEpochMs = null,
        checkOutAtEpochMs = null,
    )

    @Test
    fun scopesTheCatalogueByClassAndTheParLevelsBySegment() = runTest {
        val route = FakeRoute(DataResult.Success(stopOf(customer)))
        val catalog = FakeCatalog(
            catalogue = DataResult.Success(listOf(cola)),
            mustStock = DataResult.Success(mapOf("p-1" to 48)),
        )

        val result = GetVisitCatalogueUseCase(route, catalog)("cus-1", today)

        val visit = (result as DataResult.Success).data
        assertEquals("class-a", catalog.catalogueClassId)
        assertEquals("gt" to "shop-1", catalog.mustStockScope)
        assertEquals("cus-1" to today, route.askedFor)
        assertEquals(customer, visit.customer)
        assertEquals(mapOf("p-1" to 48), visit.mustStock)
    }

    /** No signal for the route call must not cost the rep their catalogue. */
    @Test
    fun fallsBackToListPricesWhenTheStopCannotBeRead() = runTest {
        val catalog = FakeCatalog(DataResult.Success(listOf(cola)))
        val useCase = GetVisitCatalogueUseCase(
            FakeRoute(DataResult.Failure(AppError.Network())),
            catalog,
        )

        val visit = (useCase("cus-1", today) as DataResult.Success).data

        assertNull(visit.customer)
        assertNull(catalog.catalogueClassId)
        assertEquals(listOf(cola), visit.catalogue)
    }

    /** Losing the par levels costs the markers on the row, not the screen. */
    @Test
    fun survivesAMustStockFailureWithNoParLevels() = runTest {
        val catalog = FakeCatalog(
            catalogue = DataResult.Success(listOf(cola)),
            mustStock = DataResult.Failure(AppError.Network()),
        )
        val useCase = GetVisitCatalogueUseCase(
            FakeRoute(DataResult.Success(stopOf(customer))),
            catalog,
        )

        val visit = (useCase("cus-1", today) as DataResult.Success).data

        assertTrue(visit.mustStock.isEmpty())
        assertEquals(listOf(cola), visit.catalogue)
    }

    /** The catalogue is the one input neither step works without. */
    @Test
    fun failsWhenTheCatalogueFails() = runTest {
        val useCase = GetVisitCatalogueUseCase(
            FakeRoute(DataResult.Success(stopOf(customer))),
            FakeCatalog(DataResult.Failure(AppError.Server(code = 500))),
        )

        assertTrue(useCase("cus-1", today) is DataResult.Failure)
    }

    @Test
    fun suggestsWholeSaleUnitsForWhatTheCountFoundBelowPar() = runTest {
        val stock = FakeStock(DataResult.Success(mapOf("p-1" to 22)))
        val visit = VisitCatalogue(customer, listOf(cola), mapOf("p-1" to 48))

        val suggestions = GetOrderSuggestionsUseCase(stock)("v-1", visit)

        assertEquals(1, suggestions.size)
        // 26 short on a product sold by the 24-case and singly: one case, two pieces.
        assertEquals(
            listOf(1 to "CASE", 2 to "PCS"),
            suggestions[0].parts.map { it.qty to it.uomCode },
        )
    }

    /** Nothing is owed, so the count is not worth a round trip. */
    @Test
    fun doesNotReadTheCountWhenTheOutletOwesNoParLevels() = runTest {
        val stock = FakeStock(DataResult.Success(mapOf("p-1" to 22)))
        val visit = VisitCatalogue(customer, listOf(cola), emptyMap())

        assertTrue(GetOrderSuggestionsUseCase(stock)("v-1", visit).isEmpty())
        assertEquals(0, stock.countReads)
    }

    /** An unreadable count is not evidence the shelf is empty. */
    @Test
    fun suggestsNothingWhenTheCountCannotBeRead() = runTest {
        val stock = FakeStock(DataResult.Failure(AppError.Network()))
        val visit = VisitCatalogue(customer, listOf(cola), mapOf("p-1" to 48))

        assertTrue(GetOrderSuggestionsUseCase(stock)("v-1", visit).isEmpty())
    }
}
