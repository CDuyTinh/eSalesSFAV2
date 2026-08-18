package com.tinhcd.myesalessfa.feature.sitestock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.SiteStockView
import com.tinhcd.myesalessfa.domain.repository.SiteStockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SiteStockUiState(
    val loading: Boolean = true,
    val view: SiteStockView = SiteStockView(),
    val error: String? = null,
)

@HiltViewModel
class SiteStockViewModel @Inject constructor(
    private val repository: SiteStockRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SiteStockUiState())
    val state: StateFlow<SiteStockUiState> = _state.asStateFlow()

    init {
        load(null)
    }

    fun load(siteId: String?) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.load(siteId)) {
                is DataResult.Success -> _state.update {
                    // The query survives a warehouse change: a rep comparing the
                    // same product across two sites should not have to type it
                    // again for the second one.
                    it.copy(loading = false, view = result.data.copy(query = it.view.query))
                }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = "Không tải được tồn kho")
                }
            }
        }
    }

    fun onQueryChanged(query: String) =
        _state.update { it.copy(view = it.view.copy(query = query)) }

    fun refresh() = load(_state.value.view.siteId)
}
