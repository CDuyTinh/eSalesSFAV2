package com.tinhcd.myesalessfa.domain.usecase

import com.tinhcd.myesalessfa.domain.model.CheckInGate
import com.tinhcd.myesalessfa.domain.model.CheckInPolicy
import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.GeoPoint
import com.tinhcd.myesalessfa.domain.model.ReasonKind
import javax.inject.Inject

/**
 * Decides whether a check-in may proceed.
 *
 * Order matters: a wildly inaccurate fix is checked first, because computing a
 * distance from a 500-metre-accurate position and then complaining about being
 * 300 metres away tells the rep nothing useful.
 */
class EvaluateCheckInUseCase @Inject constructor() {

    operator fun invoke(
        customer: Customer,
        location: GeoPoint?,
        policy: CheckInPolicy,
    ): CheckInGate {
        if (location == null) {
            return CheckInGate.NeedsReason(ReasonKind.GPS_UNAVAILABLE, distanceM = null)
        }

        val accuracy = location.accuracyM
        if (accuracy != null && accuracy > policy.maxAccuracyM) {
            return CheckInGate.NeedsReason(ReasonKind.GPS_LOW_ACCURACY, distanceM = null)
        }

        // An outlet with no recorded position cannot be measured against. Let
        // it through rather than trapping the rep — this is normal for
        // customers created in the field and not yet geocoded.
        if (customer.lat == null || customer.lng == null) {
            return CheckInGate.Allowed(distanceM = 0.0)
        }

        val distance = Haversine.distanceM(
            location.lat, location.lng,
            customer.lat, customer.lng,
        )
        val radius = customer.checkInRadiusM ?: policy.defaultRadiusM

        return when {
            distance <= radius -> CheckInGate.Allowed(distance)
            policy.allowReasonWhenFar ->
                CheckInGate.NeedsReason(ReasonKind.GPS_OUT_OF_RANGE, distance)
            else ->
                CheckInGate.Blocked(ReasonKind.GPS_OUT_OF_RANGE, distance)
        }
    }
}
