package com.tinhcd.myesalessfa.domain.usecase

import com.tinhcd.myesalessfa.domain.model.Branch
import com.tinhcd.myesalessfa.domain.model.CheckInGate
import com.tinhcd.myesalessfa.domain.model.CheckInPolicy
import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.GeoPoint
import com.tinhcd.myesalessfa.domain.model.ReasonKind
import com.tinhcd.myesalessfa.domain.model.WorkDayPolicy
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
    ): CheckInGate = evaluate(
        location = location,
        // An outlet with no recorded position cannot be measured against. Let
        // it through rather than trapping the rep — this is normal for
        // customers created in the field and not yet geocoded.
        targetLat = customer.lat,
        targetLng = customer.lng,
        radiusM = customer.checkInRadiusM ?: policy.defaultRadiusM,
        maxAccuracyM = policy.maxAccuracyM,
        allowReasonWhenFar = policy.allowReasonWhenFar,
    )

    /**
     * The same judgement applied at the depot, where the day is opened and closed.
     *
     * Shares this class rather than getting one of its own because the question is
     * identical — am I where I claim to be, and is the fix good enough to say so —
     * and a second copy would be a second place for the accuracy-before-distance
     * ordering to be got wrong.
     */
    fun atBranch(
        branch: Branch,
        location: GeoPoint?,
        policy: WorkDayPolicy,
    ): CheckInGate = evaluate(
        location = location,
        targetLat = branch.lat,
        targetLng = branch.lng,
        radiusM = policy.branchRadiusM,
        maxAccuracyM = policy.maxAccuracyM,
        allowReasonWhenFar = policy.allowReasonWhenFar,
    )

    private fun evaluate(
        location: GeoPoint?,
        targetLat: Double?,
        targetLng: Double?,
        radiusM: Int,
        maxAccuracyM: Int,
        allowReasonWhenFar: Boolean,
    ): CheckInGate {
        if (location == null) {
            return CheckInGate.NeedsReason(ReasonKind.GPS_UNAVAILABLE, distanceM = null)
        }

        val accuracy = location.accuracyM
        if (accuracy != null && accuracy > maxAccuracyM) {
            return CheckInGate.NeedsReason(ReasonKind.GPS_LOW_ACCURACY, distanceM = null)
        }

        if (targetLat == null || targetLng == null) {
            return CheckInGate.Allowed(distanceM = 0.0)
        }

        val distance = Haversine.distanceM(location.lat, location.lng, targetLat, targetLng)

        return when {
            distance <= radiusM -> CheckInGate.Allowed(distance)
            allowReasonWhenFar -> CheckInGate.NeedsReason(ReasonKind.GPS_OUT_OF_RANGE, distance)
            else -> CheckInGate.Blocked(ReasonKind.GPS_OUT_OF_RANGE, distance)
        }
    }
}
