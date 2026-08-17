package com.tinhcd.myesalessfa.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.ActivityReport
import com.tinhcd.myesalessfa.domain.model.SalesReport
import com.tinhcd.myesalessfa.domain.repository.ReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

enum class ReportTab(val label: String) {
    ACTIVITIES("Hoạt động"),
    SALES("Doanh số"),
}

/** Which cut of the month's money is on screen. */
enum class SalesCut(val label: String) {
    CUSTOMER("Theo khách hàng"),
    PRODUCT("Theo sản phẩm"),
}

data class ReportsUiState(
    val tab: ReportTab = ReportTab.ACTIVITIES,
    val cut: SalesCut = SalesCut.CUSTOMER,
    val date: LocalDate = LocalDate.now(),
    val month: LocalDate = LocalDate.now().withDayOfMonth(1),
    val activities: ActivityReport? = null,
    val sales: SalesReport? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Both reports, loaded on demand.
 *
 * Each tab keeps whatever it last fetched, so switching back and forth does not
 * re-read: a rep comparing the day against the month is the whole reason the two
 * live on one screen, and making that comparison cost a round trip each way
 * would discourage the only thing this screen is for.
 */
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val repository: ReportRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ReportsUiState())
    val state: StateFlow<ReportsUiState> = _state.asStateFlow()

    init {
        loadActivities()
    }

    fun onTabSelected(tab: ReportTab) {
        _state.update { it.copy(tab = tab, error = null) }
        when (tab) {
            ReportTab.ACTIVITIES -> if (_state.value.activities == null) loadActivities()
            ReportTab.SALES -> if (_state.value.sales == null) loadSales()
        }
    }

    fun onCutSelected(cut: SalesCut) = _state.update { it.copy(cut = cut) }

    fun onDateChanged(date: LocalDate) {
        _state.update { it.copy(date = date, activities = null) }
        loadActivities()
    }

    fun onMonthChanged(month: LocalDate) {
        _state.update { it.copy(month = month.withDayOfMonth(1), sales = null) }
        loadSales()
    }

    /** Re-reads whichever report is showing. */
    fun refresh() {
        when (_state.value.tab) {
            ReportTab.ACTIVITIES -> loadActivities()
            ReportTab.SALES -> loadSales()
        }
    }

    private fun loadActivities() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.activities(_state.value.date)) {
                is DataResult.Success ->
                    _state.update { it.copy(loading = false, activities = result.data) }

                is DataResult.Failure ->
                    _state.update {
                        it.copy(loading = false, error = "Không tải được nhật ký hoạt động")
                    }
            }
        }
    }

    private fun loadSales() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.sales(_state.value.month)) {
                is DataResult.Success ->
                    _state.update { it.copy(loading = false, sales = result.data) }

                is DataResult.Failure ->
                    _state.update {
                        it.copy(loading = false, error = "Không tải được báo cáo doanh số")
                    }
            }
        }
    }
}
