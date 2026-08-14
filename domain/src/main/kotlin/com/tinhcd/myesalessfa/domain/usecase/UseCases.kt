package com.tinhcd.myesalessfa.domain.usecase

import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.Salesperson
import com.tinhcd.myesalessfa.domain.repository.AuthRepository
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SignInUseCase @Inject constructor(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(username: String, password: String): DataResult<Salesperson> {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            return DataResult.Failure(AppError.Rule("login_empty_fields"))
        }
        return authRepository.signIn(user, password)
    }
}

class GetTodayRouteUseCase @Inject constructor(
    private val routeRepository: RouteRepository,
) {
    suspend operator fun invoke(date: LocalDate = LocalDate.now()): DataResult<List<RouteStop>> =
        routeRepository.getRoute(date)
}

/**
 * Straight-line distance in metres. The legacy app compared this against
 * GPS_DISTANCE to decide whether a check-in was allowed, or whether the rep had
 * to pick a reason code first.
 */
object Haversine {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
