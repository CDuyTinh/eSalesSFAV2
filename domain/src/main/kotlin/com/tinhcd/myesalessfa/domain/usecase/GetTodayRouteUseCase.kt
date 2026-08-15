package com.tinhcd.myesalessfa.domain.usecase

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
import java.time.LocalDate
import javax.inject.Inject

class GetTodayRouteUseCase @Inject constructor(
    private val routeRepository: RouteRepository,
) {
    suspend operator fun invoke(date: LocalDate = LocalDate.now()): DataResult<List<RouteStop>> =
        routeRepository.getRoute(date)
}
