package com.tinhcd.myesalessfa.feature.customer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CustomerOrder
import com.tinhcd.myesalessfa.domain.repository.CustomerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CustomerOrdersUiState(
    val loading: Boolean = true,
    val orders: List<CustomerOrder> = emptyList(),
    val error: String? = null,
    /** Which order's lines are open. One at a time: the list is the point. */
    val expandedOrderId: String? = null,
)

@HiltViewModel
class CustomerOrdersViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CustomerRepository,
) : ViewModel() {

    private val customerId: String = checkNotNull(savedStateHandle["customerId"])

    private val _state = MutableStateFlow(CustomerOrdersUiState())
    val state: StateFlow<CustomerOrdersUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.orders(customerId)) {
                is DataResult.Success -> _state.update {
                    it.copy(loading = false, orders = result.data)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = "Không tải được lịch sử đơn hàng")
                }
            }
        }
    }

    fun onToggleOrder(orderId: String) = _state.update {
        it.copy(expandedOrderId = if (it.expandedOrderId == orderId) null else orderId)
    }
}
