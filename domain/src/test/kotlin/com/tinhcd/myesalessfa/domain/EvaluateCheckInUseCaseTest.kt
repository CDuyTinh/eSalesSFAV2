package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.CheckInGate
import com.tinhcd.myesalessfa.domain.model.CheckInPolicy
import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.GeoPoint
import com.tinhcd.myesalessfa.domain.model.ReasonKind
import com.tinhcd.myesalessfa.domain.usecase.EvaluateCheckInUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluateCheckInUseCaseTest {

    private val evaluate = EvaluateCheckInUseCase()

    private val policy = CheckInPolicy(
        defaultRadiusM = 100,
        maxAccuracyM = 50,
        allowReasonWhenFar = true,
    )

    /** KH001 from the seed data. */
    private fun outlet(radius: Int? = null) = Customer(
        id = "c1",
        code = "KH001",
        name = "Tap hoa Minh Anh",
        address = null,
        phone = null,
        lat = 10.9812,
        lng = 106.6524,
        avatarUrl = null,
        checkInRadiusM = radius,
    )

    @Test
    fun `standing at the shop is allowed`() {
        val gate = evaluate(outlet(), GeoPoint(10.9812, 106.6524, 8f), policy)
        assertTrue(gate is CheckInGate.Allowed)
    }

    @Test
    fun `a few streets away asks for a reason`() {
        // ~800 m from the outlet.
        val gate = evaluate(outlet(), GeoPoint(10.9884, 106.6524, 8f), policy)
        assertTrue(gate is CheckInGate.NeedsReason)
        assertEquals(ReasonKind.GPS_OUT_OF_RANGE, (gate as CheckInGate.NeedsReason).kind)
    }

    @Test
    fun `too far is blocked outright when policy forbids a reason`() {
        val strict = policy.copy(allowReasonWhenFar = false)
        val gate = evaluate(outlet(), GeoPoint(10.9884, 106.6524, 8f), strict)
        assertTrue(gate is CheckInGate.Blocked)
    }

    @Test
    fun `a vague fix is rejected before distance is even considered`() {
        // Right on top of the shop, but the fix is worthless.
        val gate = evaluate(outlet(), GeoPoint(10.9812, 106.6524, 500f), policy)
        assertEquals(
            ReasonKind.GPS_LOW_ACCURACY,
            (gate as CheckInGate.NeedsReason).kind,
        )
    }

    @Test
    fun `no location at all asks for a reason`() {
        val gate = evaluate(outlet(), null, policy)
        assertEquals(
            ReasonKind.GPS_UNAVAILABLE,
            (gate as CheckInGate.NeedsReason).kind,
        )
    }

    @Test
    fun `per-customer radius overrides the global default`() {
        // 800 m away, but this outlet is allowed a 1 km radius.
        val gate = evaluate(outlet(radius = 1_000), GeoPoint(10.9884, 106.6524, 8f), policy)
        assertTrue(gate is CheckInGate.Allowed)
    }

    @Test
    fun `an outlet with no coordinates is not trapped`() {
        val ungeocoded = outlet().copy(lat = null, lng = null)
        val gate = evaluate(ungeocoded, GeoPoint(10.9812, 106.6524, 8f), policy)
        assertTrue(gate is CheckInGate.Allowed)
    }
}
