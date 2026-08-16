package com.tinhcd.myesalessfa.domain.usecase

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DashboardOverview
import com.tinhcd.myesalessfa.domain.repository.DashboardRepository
import java.time.LocalDate
import javax.inject.Inject

class GetDashboardOverviewUseCase @Inject constructor(
    private val dashboardRepository: DashboardRepository,
) {
    suspend operator fun invoke(date: LocalDate = LocalDate.now()): DataResult<DashboardOverview> =
        dashboardRepository.overview(date)
}
