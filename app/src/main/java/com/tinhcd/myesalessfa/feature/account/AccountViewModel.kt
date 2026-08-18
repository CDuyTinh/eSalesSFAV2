package com.tinhcd.myesalessfa.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.PasswordChange
import com.tinhcd.myesalessfa.domain.model.Salesperson
import com.tinhcd.myesalessfa.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountUiState(
    val me: Salesperson? = null,
    val change: PasswordChange = PasswordChange(),
    val changing: Boolean = false,
    val error: String? = null,
    /**
     * The password was changed. The screen stays put and says so rather than
     * closing: a rep who did not see the confirmation will try again, and the
     * second attempt fails on a current password that is no longer current.
     */
    val changed: Boolean = false,
)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.currentUser.collect { rep -> _state.update { it.copy(me = rep) } }
        }
    }

    fun onCurrent(value: String) = edit { it.copy(current = value) }

    fun onNew(value: String) = edit { it.copy(new = value) }

    fun onConfirm(value: String) = edit { it.copy(confirm = value) }

    fun changePassword() {
        val current = _state.value
        if (current.changing || !current.change.canSubmit) return

        _state.update { it.copy(changing = true, error = null, changed = false) }

        viewModelScope.launch {
            val result = authRepository.changePassword(
                currentPassword = current.change.current,
                newPassword = current.change.new,
            )

            when (result) {
                // Cleared, so the old password is not left sitting in a text field
                // on a phone that gets handed across a counter.
                is DataResult.Success -> _state.update {
                    it.copy(changing = false, changed = true, change = PasswordChange())
                }

                is DataResult.Failure -> _state.update {
                    it.copy(changing = false, error = result.error.passwordMessage())
                }
            }
        }
    }

    private fun edit(change: (PasswordChange) -> PasswordChange) =
        _state.update { it.copy(change = change(it.change), error = null, changed = false) }
}

/**
 * The one failure worth naming precisely is a wrong current password, because it
 * is the one the rep can do something about. It arrives as invalid_credentials
 * from the re-authentication, exactly as it does at sign-in.
 */
private fun AppError.passwordMessage(): String = when (this) {
    is AppError.Auth -> when (message) {
        "invalid_credentials" -> "Mật khẩu hiện tại không đúng"
        else -> "Phiên đăng nhập đã hết hạn, đăng nhập lại"
    }

    is AppError.Network -> "Không có kết nối mạng"
    is AppError.Server -> message.orFallback()
    is AppError.Rule -> message.orFallback()
    is AppError.Unknown -> when {
        message?.contains("invalid", ignoreCase = true) == true ||
            message?.contains("credentials", ignoreCase = true) == true ->
            "Mật khẩu hiện tại không đúng"

        else -> message.orFallback()
    }
}

private fun String?.orFallback(): String =
    this?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
        ?: "Chưa đổi được mật khẩu, thử lại"
