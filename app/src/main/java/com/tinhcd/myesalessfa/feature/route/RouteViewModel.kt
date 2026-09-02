package com.tinhcd.myesalessfa.feature.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.Salesperson
import com.tinhcd.myesalessfa.domain.model.SessionState
import com.tinhcd.myesalessfa.domain.model.SyncState
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import com.tinhcd.myesalessfa.domain.repository.AuthRepository
import com.tinhcd.myesalessfa.domain.repository.CheckInRepository
import com.tinhcd.myesalessfa.domain.repository.ReferenceDataSync
import com.tinhcd.myesalessfa.domain.usecase.GetTodayRouteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import com.tinhcd.myesalessfa.domain.foldForSearch
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The narrowing the filter sheet offers, in the order it lists them.
 *
 * [DONE] deliberately covers both endings a visit can have. A rep sorting the
 * day's work asks "which ones are behind me", not "which ones ended in an order".
 */
enum class RouteFilter(val label: String) {
    ALL("Tất cả"),
    PLANNED("Chưa ghé"),
    IN_PROGRESS("Đang viếng thăm"),
    DONE("Đã hoàn thành"),
    CLOSED("Đóng cửa"),
}

data class RouteUiState(
    val loading: Boolean = true,
    val stops: List<RouteStop> = emptyList(),
    val me: Salesperson? = null,
    val error: String? = null,
    /** What the rep typed in the search box. Matched against the loaded stops. */
    val query: String = "",
    val filter: RouteFilter = RouteFilter.ALL,
    /**
     * Signed in, but the rep's profile has not arrived. Worth saying out loud rather
     * than just leaving the app bar subtitle blank: a check-in stamps salesperson_id
     * and branch_id from the profile, so it will be refused until this clears.
     */
    val profileMissing: Boolean = false,
    val profileRetrying: Boolean = false,
    /**
     * How the cached reference data is doing. Shown rather than hidden because the
     * rep is the one who gets asked why a new price or a retired step has not
     * appeared, and "it refreshes when you sign in" was never an answer they could
     * act on.
     */
    val sync: SyncState = SyncState(),
) {
    /**
     * The stop the rep is inside, when they are inside one.
     *
     * One visit at a time is the rule, as it was in the app this replaces: a rep
     * cannot be standing in two shops, and a second open visit makes the working
     * time of both unreadable. Every other stop's check-in is refused while this
     * is set — on the card, in the Edge Function and by a unique index, in that
     * order of politeness.
     */
    val openStop: RouteStop?
        get() = stops.firstOrNull { it.status == VisitStatus.IN_PROGRESS }

    /**
     * The stops the list actually draws.
     *
     * Filtered here rather than on the server: the whole day's route is already in
     * memory, it is tens of rows, and a rep typing a shop name in a market with one
     * bar of signal should not be waiting on a round trip per keystroke.
     */
    val visibleStops: List<RouteStop>
        get() = stops.filter { it.matches(query) && filter.accepts(it.status) }

    /** True when something is being hidden, which the filter button has to admit to. */
    val filtering: Boolean
        get() = query.isNotBlank() || filter != RouteFilter.ALL
}

private fun RouteStop.matches(query: String): Boolean {
    val needle = query.trim().foldForSearch()
    if (needle.isEmpty()) return true
    return listOfNotNull(customer.name, customer.code, customer.address, customer.phone)
        .any { it.foldForSearch().contains(needle) }
}

private fun RouteFilter.accepts(status: VisitStatus): Boolean = when (this) {
    RouteFilter.ALL -> true
    RouteFilter.PLANNED -> status == VisitStatus.PLANNED
    RouteFilter.IN_PROGRESS -> status == VisitStatus.IN_PROGRESS
    RouteFilter.DONE -> status == VisitStatus.COMPLETED || status == VisitStatus.NO_ORDER
    RouteFilter.CLOSED -> status == VisitStatus.CLOSED
}

@HiltViewModel
class RouteViewModel @Inject constructor(
    private val getTodayRoute: GetTodayRouteUseCase,
    private val authRepository: AuthRepository,
    private val referenceDataSync: ReferenceDataSync,
    checkInRepository: CheckInRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RouteUiState())
    val state: StateFlow<RouteUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            authRepository.sessionState.collect { session ->
                _state.update {
                    it.copy(
                        me = (session as? SessionState.SignedIn)?.rep,
                        profileMissing = session is SessionState.SignedIn && session.profileMissing,
                    )
                }
            }
        }
        viewModelScope.launch {
            referenceDataSync.state.collect { sync -> _state.update { it.copy(sync = sync) } }
        }
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = getTodayRoute()) {
                is DataResult.Success -> _state.update {
                    it.copy(loading = false, stops = result.data)
                }

                is DataResult.Failure ->
                    _state.update {
                        it.copy(loading = false, error = "Không tải được tuyến hôm nay")
                    }
            }
        }
    }

    fun onQueryChanged(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun onFilterChanged(filter: RouteFilter) {
        _state.update { it.copy(filter = filter) }
    }

    /**
     * Re-attempts the profile fetch. `profileMissing` clears through the session
     * flow rather than being set here, so the banner disappears only when the state
     * actually changed and not merely because a retry was attempted.
     */
    fun retryProfile() {
        if (_state.value.profileRetrying) return
        viewModelScope.launch {
            _state.update { it.copy(profileRetrying = true) }
            authRepository.refreshProfile()
            _state.update { it.copy(profileRetrying = false) }
        }
    }

    /**
     * Pulls the reference data down again on the rep's say-so, then reloads the route
     * so a change to the workflow or the customer list is visible immediately rather
     * than at the next navigation.
     */
    fun refreshReferenceData() {
        if (_state.value.sync.syncing) return
        viewModelScope.launch {
            referenceDataSync.syncNow()
            load()
        }
    }

}
