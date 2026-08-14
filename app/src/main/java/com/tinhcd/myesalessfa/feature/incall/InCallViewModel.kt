package com.tinhcd.myesalessfa.feature.incall

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.Customer
import com.tinhcd.myesalessfa.domain.model.VisitWorkflow
import com.tinhcd.myesalessfa.domain.repository.CheckInRepository
import com.tinhcd.myesalessfa.domain.repository.RouteRepository
import com.tinhcd.myesalessfa.domain.repository.WorkflowRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class InCallUiState(
    val loading: Boolean = true,
    val customer: Customer? = null,
    val workflow: VisitWorkflow? = null,
    val submitting: Boolean = false,
    val error: String? = null,
    val checkedOut: Boolean = false,
    /** Work recorded on the device but not yet delivered. */
    val pendingUploads: Int = 0,
)

/**
 * Drives the in-call screen. Notably it has no idea what the steps are — the
 * list comes from `sales_step` on the server, so enabling a new step for a
 * market is a data change rather than a release.
 */
@HiltViewModel
class InCallViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workflowRepository: WorkflowRepository,
    private val routeRepository: RouteRepository,
    private val checkInRepository: CheckInRepository,
) : ViewModel() {

    private val visitId: String = checkNotNull(savedStateHandle["visitId"])
    private val customerId: String = checkNotNull(savedStateHandle["customerId"])

    private val _state = MutableStateFlow(InCallUiState())
    val state: StateFlow<InCallUiState> = _state.asStateFlow()

    init {
        // No initial load here: the screen calls load() on every entry, which
        // covers the first one too. Loading in both places just fetched twice.
        viewModelScope.launch {
            checkInRepository.pendingCount.collect { n ->
                _state.update { it.copy(pendingUploads = n) }
            }
        }
    }

    /** Called again on return from a step so completions show immediately. */
    fun load() {
        _state.update { it.copy(loading = it.workflow == null, error = null) }
        viewModelScope.launch {
            val stop = (routeRepository.getStop(customerId, LocalDate.now()) as? DataResult.Success)
                ?.data

            when (val result = workflowRepository.workflow(visitId)) {
                is DataResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        customer = stop?.customer ?: it.customer,
                        workflow = result.data,
                    )
                }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = "Khong tai duoc danh sach cong viec")
                }
            }
        }
    }

    fun checkOut() {
        val workflow = _state.value.workflow ?: return
        if (!workflow.canCheckOut || _state.value.submitting) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            // A queued check-out still closes the visit for the rep; the route
            // screen's pending banner is what tells them it has yet to be sent.
            when (checkInRepository.checkOut(visitId)) {
                is DataResult.Success -> _state.update {
                    it.copy(submitting = false, checkedOut = true)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(submitting = false, error = "Khong luu duoc check-out")
                }
            }
        }
    }
}
