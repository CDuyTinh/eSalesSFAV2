package com.tinhcd.myesalessfa

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StartupUiState(
    val message: String = "Dang khoi tao...",
)

/**
 * Depends on the :domain interface only. Hilt supplies the Supabase-backed
 * implementation from :data — this class has no idea Supabase exists.
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(StartupUiState())
    val state: StateFlow<StartupUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.currentUser.collect { user ->
                _state.value = StartupUiState(
                    message = if (user == null) {
                        "Chua dang nhap"
                    } else {
                        "${user.fullName} - ${user.branchName}"
                    },
                )
            }
        }
    }
}
