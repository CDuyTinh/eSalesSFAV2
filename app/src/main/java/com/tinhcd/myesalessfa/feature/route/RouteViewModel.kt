package com.tinhcd.myesalessfa.feature.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.RouteStop
import com.tinhcd.myesalessfa.domain.model.Salesperson
import com.tinhcd.myesalessfa.domain.model.SessionState
import com.tinhcd.myesalessfa.domain.repository.AuthRepository
import com.tinhcd.myesalessfa.domain.repository.CheckInRepository
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
    val pendingUploads: Int = 0,
    val signedOut: Boolean = false,
    /**
     * Signed in, but the rep's profile has not arrived. Worth saying out loud rather
     * than just leaving the app bar subtitle blank: a check-in stamps salesperson_id
     * and branch_id from the profile, so it will be refused until this clears.
     */
    val profileMissing: Boolean = false,
    val profileRetrying: Boolean = false,
)

@HiltViewModel
class RouteViewModel @Inject constructor(
    private val getTodayRoute: GetTodayRouteUseCase,
    private val authRepository: AuthRepository,
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
            // Surfaced in the app bar so a rep can see that work is still
            // waiting to reach the server rather than assuming it is lost.
            checkInRepository.pendingCount.collect { n ->
                _state.update { it.copy(pendingUploads = n) }
            }
        }
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = getTodayRoute()) {
                is DataResult.Success ->
                    _state.update { it.copy(loading = false, stops = result.data) }

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

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            _state.update { it.copy(signedOut = true) }
        }
    }
}
