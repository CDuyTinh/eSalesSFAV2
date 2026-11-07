package com.tinhcd.myesalessfa.feature.checkin

import androidx.lifecycle.SavedStateHandle
import com.tinhcd.myesalessfa.core.location.LocationProvider
import com.tinhcd.myesalessfa.core.photo.PhotoStore
import com.tinhcd.myesalessfa.core.ui.AppStrings
import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CheckInGate
import com.tinhcd.myesalessfa.domain.model.CheckInPolicy
import com.tinhcd.myesalessfa.domain.model.CheckInRequest
import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.GeoPoint
import com.tinhcd.myesalessfa.domain.model.ReasonCode
import com.tinhcd.myesalessfa.domain.model.ReasonKind
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import com.tinhcd.myesalessfa.domain.repository.CheckInRepository
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
import com.tinhcd.myesalessfa.domain.usecase.EvaluateCheckInUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Check-in's rules as the rep meets them: in range it goes straight through,
 * out of range it waits for a reason, a required photo holds it back, and a
 * failed save keeps the screen open with a message.
 *
 * The real EvaluateCheckInUseCase does the distance arithmetic; only the
 * edges (route, GPS, storage, network) are faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckInViewModelTest {

    private val shop = Customer(
        id = "c1", code = "KH001", name = "Tạp hóa Minh Anh", address = "45 Nguyễn Trãi",
        phone = null, lat = 10.9812, lng = 106.6524, avatarUrl = null, checkInRadiusM = 100,
    )
    private val stop = RouteStop(
        customer = shop, visitOrder = 1, status = VisitStatus.PLANNED,
        visitId = null, checkInAtEpochMs = null, checkOutAtEpochMs = null,
    )

    /** About ten metres from the shop, and about two kilometres away. */
    private val atTheDoor = GeoPoint(10.98129, 106.65245, accuracyM = 10f)
    private val acrossTown = GeoPoint(11.0000, 106.6600, accuracyM = 10f)

    private val farReason = ReasonCode(id = "r1", code = "FAR", name = "Ngoài bán kính", kind = ReasonKind.GPS_OUT_OF_RANGE)

    private val routes = mockk<RouteRepository>()
    private val checkIns = mockk<CheckInRepository>()
    private val config = mockk<ConfigRepository>()
    private val location = mockk<LocationProvider>()
    private val photos = mockk<PhotoStore>(relaxed = true)
    private val strings = mockk<AppStrings>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { routes.getStop("c1", any()) } returns DataResult.Success(stop)
        coEvery { config.checkInPolicy() } returns CheckInPolicy.Fallback
        coEvery { config.reasons(ReasonKind.GPS_OUT_OF_RANGE) } returns listOf(farReason)
        coEvery { checkIns.checkIn(any()) } returns DataResult.Success(Unit)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(at: GeoPoint?): CheckInViewModel {
        coEvery { location.currentLocation() } returns at
        return CheckInViewModel(
            strings = strings,
            savedStateHandle = SavedStateHandle(mapOf("customerId" to "c1")),
            routeRepository = routes,
            checkInRepository = checkIns,
            configRepository = config,
            evaluate = EvaluateCheckInUseCase(),
            locationProvider = location,
            photoStore = photos,
        )
    }

    @Test
    fun `at the door the check-in goes straight through`() = runTest {
        val vm = viewModel(atTheDoor)

        val state = vm.state.value
        assertEquals(shop, state.customer)
        assertTrue(state.gate is CheckInGate.Allowed)
        assertFalse(state.needsReason)
        assertTrue(state.canSubmit)

        vm.submit()

        val sent = slot<CheckInRequest>()
        coVerify { checkIns.checkIn(capture(sent)) }
        assertEquals("c1", sent.captured.customerId)
        assertNull(sent.captured.reasonId)
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun `out of range it waits for a reason and sends it`() = runTest {
        val vm = viewModel(acrossTown)

        assertTrue(vm.state.value.needsReason)
        assertEquals(listOf(farReason), vm.state.value.reasons)
        assertFalse(vm.state.value.canSubmit)

        vm.selectReason(farReason)
        assertTrue(vm.state.value.canSubmit)

        vm.submit()
        coVerify { checkIns.checkIn(match { it.reasonId == "r1" }) }
    }

    @Test
    fun `a required photo holds the check-in back`() = runTest {
        coEvery { config.checkInPolicy() } returns CheckInPolicy.Fallback.copy(photoRequired = true)
        val vm = viewModel(atTheDoor)

        assertTrue(vm.state.value.photoRequired)
        assertFalse(vm.state.value.canSubmit)

        vm.submit()
        coVerify(exactly = 0) { checkIns.checkIn(any()) }
    }

    @Test
    fun `a blank note is not sent as a note`() = runTest {
        val vm = viewModel(atTheDoor)
        vm.onNoteChange("   ")

        vm.submit()

        coVerify { checkIns.checkIn(match { it.note == null }) }
    }

    @Test
    fun `a failed save keeps the screen open with a message`() = runTest {
        coEvery { checkIns.checkIn(any()) } returns DataResult.Failure(AppError.Network())
        val vm = viewModel(atTheDoor)

        vm.submit()

        assertFalse(vm.state.value.finished)
        assertFalse(vm.state.value.submitting)
        assertNotNull(vm.state.value.error)
    }

    @Test
    fun `an outlet not on today's route has nothing to check in to`() = runTest {
        coEvery { routes.getStop("c1", any()) } returns DataResult.Success(null)
        val vm = viewModel(atTheDoor)

        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.customer)
        assertFalse(vm.state.value.canSubmit)
    }
}
