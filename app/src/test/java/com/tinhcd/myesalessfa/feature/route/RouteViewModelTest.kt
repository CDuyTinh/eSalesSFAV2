package com.tinhcd.myesalessfa.feature.route

import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.SessionState
import com.tinhcd.myesalessfa.domain.model.SyncState
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import com.tinhcd.myesalessfa.domain.repository.AuthRepository
import com.tinhcd.myesalessfa.domain.repository.ReferenceDataSync
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
import com.tinhcd.myesalessfa.domain.usecase.GetTodayRouteUseCase
import com.tinhcd.myesalessfa.feature.nfc.RecentNfcTap
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * When the route's cards arrive one after another: the first time the tab
 * opens and after a fetch from nothing, but not on every return to the tab.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteViewModelTest {

    private val stop = RouteStop(
        customer = Customer(
            id = "c1", code = "KH001", name = "Tạp hóa Minh Anh", address = null,
            phone = null, lat = null, lng = null, avatarUrl = null, checkInRadiusM = 100,
        ),
        visitOrder = 1, status = VisitStatus.PLANNED,
        visitId = null, checkInAtEpochMs = null, checkOutAtEpochMs = null,
    )

    private val routes = mockk<RouteRepository>()
    private val auth = mockk<AuthRepository>()
    private val sync = mockk<ReferenceDataSync>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { auth.sessionState } returns flowOf(SessionState.SignedOut)
        every { sync.state } returns flowOf(SyncState())
        coEvery { routes.getRoute(any()) } returns DataResult.Success(listOf(stop))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = RouteViewModel(
        getTodayRoute = GetTodayRouteUseCase(routes),
        authRepository = auth,
        referenceDataSync = sync,
        checkInRepository = mockk(relaxed = true),
        nfcTags = mockk(relaxed = true),
        recentNfcTap = RecentNfcTap(),
    )

    @Test
    fun `the first list plays the entrance once, and a return to the tab does not`() = runTest {
        val vm = viewModel()
        assertTrue(vm.state.value.entrancePending)

        vm.onEntranceShown()
        vm.load() // what the screen does on every return to the tab

        assertFalse(vm.state.value.entrancePending)
    }

    @Test
    fun `a list fetched from nothing after an error plays it again`() = runTest {
        coEvery { routes.getRoute(any()) } returns DataResult.Failure(AppError.Network())
        val vm = viewModel()
        vm.onEntranceShown()

        coEvery { routes.getRoute(any()) } returns DataResult.Success(listOf(stop))
        vm.load()

        assertTrue(vm.state.value.entrancePending)
    }
}
