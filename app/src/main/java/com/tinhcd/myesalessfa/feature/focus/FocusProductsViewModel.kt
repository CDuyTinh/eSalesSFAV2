package com.tinhcd.myesalessfa.feature.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.FocusProduct
import com.tinhcd.myesalessfa.domain.repository.FocusProductRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class FocusProductsUiState(
    val loading: Boolean = true,
    val date: LocalDate = LocalDate.now(),
    val products: List<FocusProduct> = emptyList(),
    val error: String? = null,
) {
    /** Pushes with a quantity attached, which are the ones a total means anything for. */
    private val measured: List<FocusProduct> get() = products.filter { it.targetBaseQty != null }

    val onTrack: Int get() = measured.count { (it.percent ?: 0) >= 100 }

    val measuredCount: Int get() = measured.size
}

@HiltViewModel
class FocusProductsViewModel @Inject constructor(
    private val repository: FocusProductRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FocusProductsUiState())
    val state: StateFlow<FocusProductsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.onDate(_state.value.date)) {
                is DataResult.Success ->
                    _state.update { it.copy(loading = false, products = result.data) }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = "Không tải được sản phẩm trọng tâm")
                }
            }
        }
    }
}
