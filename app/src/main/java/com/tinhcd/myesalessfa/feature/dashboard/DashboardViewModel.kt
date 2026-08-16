package com.tinhcd.myesalessfa.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.ChartRange
import com.tinhcd.myesalessfa.domain.model.DashboardOverview
import com.tinhcd.myesalessfa.domain.usecase.GetDashboardOverviewUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val loading: Boolean = true,
    val overview: DashboardOverview? = null,
    val range: ChartRange = ChartRange.THIS_WEEK,
    val error: String? = null,
)

/**
 * Owns the Overview tab.
 *
 * Switching the chart span does not go back to the server: all three series
 * arrive together, because they are three windows onto the same orders and
 * fetching them separately would let the week disagree with the month it sits in.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val getOverview: GetDashboardOverviewUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = getOverview()) {
                is DataResult.Success ->
                    _state.update { it.copy(loading = false, overview = result.data) }

                // The previous figures are kept rather than blanked. A refresh that
                // failed has not made yesterday's total untrue, and an empty screen
                // would read as "you sold nothing".
                is DataResult.Failure ->
                    _state.update { it.copy(loading = false, error = "Khong tai duoc so lieu") }
            }
        }
    }

    fun onRangeSelected(range: ChartRange) = _state.update { it.copy(range = range) }

    fun dismissError() = _state.update { it.copy(error = null) }
}
