package com.tinhcd.myesalessfa.feature.route

import androidx.lifecycle.ViewModel
import com.tinhcd.myesalessfa.core.ui.uiText
import com.tinhcd.myesalessfa.core.ui.UiText
import com.tinhcd.myesalessfa.R
import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.Salesperson
import com.tinhcd.myesalessfa.domain.model.SessionState
import com.tinhcd.myesalessfa.domain.model.SyncState
import com.tinhcd.myesalessfa.domain.model.VisitStatus
import com.tinhcd.myesalessfa.domain.repository.AuthRepository
import com.tinhcd.myesalessfa.domain.repository.CheckInRepository
import com.tinhcd.myesalessfa.domain.repository.NfcTagRepository
import com.tinhcd.myesalessfa.feature.nfc.RecentNfcTap
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
enum class RouteFilter(@StringRes val label: Int) {
    ALL(R.string.filter_all),
    PLANNED(R.string.visit_planned),
    IN_PROGRESS(R.string.filter_visiting),
    DONE(R.string.visit_completed),
    CLOSED(R.string.visit_closed),
}

data class RouteUiState(
    val loading: Boolean = true,
    val stops: List<RouteStop> = emptyList(),
    val me: Salesperson? = null,
    val error: UiText? = null,
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
    /** A tapped tag led to this stop; the screen opens it once and clears it. */
    val nfcStop: RouteStop? = null,
    /** What a tapped tag could not lead to, said once. */
    val nfcMessage: UiText? = null,
    /**
     * The cards should arrive one after another the next time they are drawn:
     * the first time the tab opens, and whenever the list is fetched from
     * nothing (after the skeleton or an error). Not on every return to the tab --
     * the shell drops the screen when another tab is chosen, but this survives,
     * and replaying the entrance forty times a day would be noise.
     */
    val entrancePending: Boolean = true,
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
    private val nfcTags: NfcTagRepository,
    private val recentNfcTap: RecentNfcTap,
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
        _state.update { it.copy(loading = true, error = null, entrancePending = it.entrancePending || it.stops.isEmpty()) }
        viewModelScope.launch {
            when (val result = getTodayRoute()) {
                is DataResult.Success -> _state.update {
                    it.copy(loading = false, stops = result.data)
                }

                is DataResult.Failure ->
                    _state.update {
                        it.copy(loading = false, error = uiText(R.string.route_load_failed))
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


    /**
     * A shop's tag tapped on the route list: straight into that stop, the way
     * tapping its card would. A tag for a shop that is not on today's route is
     * named rather than opened -- the route is what the rep is paid to cover,
     * and an off-route visit goes through the customer list on purpose.
     */
    fun onNfcTag(uid: String) {
        viewModelScope.launch {
            val message = when (val result = nfcTags.ownerOf(uid)) {
                is DataResult.Success -> {
                    val owner = result.data
                    val stop = owner?.let { o -> _state.value.stops.firstOrNull { it.customer.id == o.customerId } }
                    when {
                        stop != null -> {
                            recentNfcTap.record(stop.customer.id, uid)
                            _state.update { it.copy(nfcStop = stop) }
                            return@launch
                        }
                        owner != null -> uiText(R.string.route_nfc_off_route, owner.name)
                        else -> uiText(R.string.checkin_nfc_unknown)
                    }
                }

                is DataResult.Failure -> uiText(R.string.route_nfc_failed)
            }
            _state.update { it.copy(nfcMessage = message) }
        }
    }

    /** The entrance has started; the cards on screen keep playing it. */
    fun onEntranceShown() = _state.update { it.copy(entrancePending = false) }

    fun onNfcHandled() = _state.update { it.copy(nfcStop = null, nfcMessage = null) }
}
