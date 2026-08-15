package com.tinhcd.myesalessfa.feature.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.Salesperson
import com.tinhcd.myesalessfa.domain.model.SessionState
import com.tinhcd.myesalessfa.domain.model.SyncState
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
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RouteUiState(
    val loading: Boolean = true,
    val stops: List<RouteStop> = emptyList(),
    val me: Salesperson? = null,
    val error: String? = null,
    val signedOut: Boolean = false,
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
)

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
                        it.copy(loading = false, error = "Khong tai duoc tuyen hom nay")
                    }
            }
        }
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

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            _state.update { it.copy(signedOut = true) }
        }
    }
}
