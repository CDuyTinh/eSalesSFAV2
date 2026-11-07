package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.lifecycle.SavedStateHandle
import com.tinhcd.myesalessfa.R
import com.tinhcd.myesalessfa.core.ui.UiText
import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.PricedProduct
import com.tinhcd.myesalessfa.domain.model.PricedUnit
import com.tinhcd.myesalessfa.domain.model.Product
import com.tinhcd.myesalessfa.domain.model.SaleUnit
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import com.tinhcd.myesalessfa.domain.repository.CustomerRepository
import com.tinhcd.myesalessfa.domain.repository.OrderRepository
import com.tinhcd.myesalessfa.domain.repository.SiteStockRepository
import com.tinhcd.myesalessfa.domain.usecase.GetOrderSuggestionsUseCase
import com.tinhcd.myesalessfa.domain.usecase.GetVisitCatalogueUseCase
import com.tinhcd.myesalessfa.domain.usecase.VisitCatalogue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Đặt hàng's rules between the product list and the server: a quantity is
 * needed, a zero takes a line out, the order must reach the minimum in cases
 * (OM10100 BUG-2026-74458) before the confirmation page, and a sent order
 * moves on to Thông tin đơn hàng.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TakeOrderViewModelTest {

    private val can = SaleUnit(uomCode = "PCS", uomName = "Lon", conversionRate = 1, isDefault = true)
    private val case = SaleUnit(uomCode = "CASE", uomName = "Thùng", conversionRate = 24, isDefault = false)

    /** Sold by the can by default; 24 cans to the case. */
    private val cola = PricedProduct(
        product = Product("p1", "NGK001", "Nước ngọt Cola 330ml", "Nước giải khát", "PCS", vatBasisPoints = 1000),
        units = listOf(PricedUnit(can, 10_000), PricedUnit(case, 230_000)),
    )

    /** Sold by the case by default. */
    private val water = PricedProduct(
        product = Product("p2", "NGK002", "Nước suối 500ml", "Nước giải khát", "PCS", vatBasisPoints = 1000),
        units = listOf(PricedUnit(can.copy(isDefault = false), 5_000), PricedUnit(case.copy(isDefault = true), 110_000)),
    )

    private val catalogue = mockk<GetVisitCatalogueUseCase>()
    private val suggestions = mockk<GetOrderSuggestionsUseCase>()
    private val siteStock = mockk<SiteStockRepository>()
    private val orders = mockk<OrderRepository>()
    private val config = mockk<ConfigRepository>()
    private val customers = mockk<CustomerRepository>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { catalogue(any()) } returns DataResult.Success(VisitCatalogue(null, listOf(cola, water), emptyMap()))
        coEvery { suggestions(any(), any()) } returns emptyList()
        coEvery { siteStock.load(null) } returns DataResult.Failure(AppError.Network())
        coEvery { config.minOrderCartons() } returns 0.5
        coEvery { customers.info(any()) } returns DataResult.Failure(AppError.Network())
        coEvery { orders.cart(any()) } returns DataResult.Success(emptyList())
        coEvery { orders.saveCart(any(), any()) } returns DataResult.Success(Unit)
        coEvery { orders.promotions(any(), any()) } returns DataResult.Failure(AppError.Network())
        coEvery { orders.manualPromotions(any()) } returns DataResult.Failure(AppError.Network())
        coEvery { orders.shipAddresses(any()) } returns DataResult.Failure(AppError.Network())
        coEvery { orders.submit(any()) } returns DataResult.Success(Unit)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = TakeOrderViewModel(
        savedStateHandle = SavedStateHandle(mapOf("visitId" to "v1", "customerId" to "c1")),
        getVisitCatalogue = catalogue,
        getOrderSuggestions = suggestions,
        siteStockRepository = siteStock,
        orderRepository = orders,
        configRepository = config,
        customerRepository = customers,
    )

    private fun TakeOrderViewModel.add(product: PricedProduct, qty: Int) {
        onDraftQtyChange(product, qty)
        commitDraft()
    }

    private fun UiText?.resId(): Int? = (this as? UiText.Res)?.id

    @Test
    fun `opens on the catalogue with an empty basket`() = runTest {
        val vm = viewModel()

        assertEquals(listOf(cola, water), vm.state.value.catalogue)
        assertTrue(vm.state.value.order.lines.isEmpty())
        assertNull(vm.state.value.error)
    }

    @Test
    fun `committing with nothing typed asks for a quantity`() = runTest {
        val vm = viewModel()

        vm.commitDraft()

        assertEquals(R.string.order_need_qty, vm.state.value.error.resId())
    }

    @Test
    fun `lines go in at the product's default unit and are saved as a basket`() = runTest {
        val vm = viewModel()

        vm.add(cola, 3)

        val line = vm.state.value.order.lines.single()
        assertEquals("p1", line.productId)
        assertEquals("PCS", line.uomCode)
        assertEquals(3, line.qty)
        coVerify { orders.saveCart("c1", any()) }
    }

    @Test
    fun `a zero takes the line back out`() = runTest {
        val vm = viewModel()
        vm.add(cola, 3)

        vm.add(cola, 0)

        assertTrue(vm.state.value.order.lines.isEmpty())
    }

    @Test
    fun `an order under half a case stops before the confirmation page`() = runTest {
        val vm = viewModel()
        vm.add(cola, 6) // a quarter of a case

        vm.openConfirm()

        assertEquals(R.string.order_min_cartons, vm.state.value.error.resId())
        assertTrue(vm.state.value.page != TakeOrderPage.CONFIRM)
    }

    @Test
    fun `an order of a whole case goes on to confirm, then sends`() = runTest {
        val vm = viewModel()
        vm.add(water, 1)

        vm.openConfirm()
        assertEquals(TakeOrderPage.CONFIRM, vm.state.value.page)

        vm.submit()
        coVerify { orders.submit(match { it.customerId == "c1" && it.visitId == "v1" && it.lines.size == 1 }) }
        assertEquals(TakeOrderPage.DONE, vm.state.value.page)
    }

    @Test
    fun `a refused order stays on the page with a message`() = runTest {
        coEvery { orders.submit(any()) } returns DataResult.Failure(AppError.Server(500))
        val vm = viewModel()
        vm.add(water, 1)
        vm.openConfirm()

        vm.submit()

        assertEquals(R.string.order_save_failed, vm.state.value.error.resId())
        assertEquals(TakeOrderPage.CONFIRM, vm.state.value.page)
    }
}
