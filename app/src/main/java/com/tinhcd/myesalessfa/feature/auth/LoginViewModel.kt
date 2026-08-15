package com.tinhcd.myesalessfa.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.repository.ReferenceDataSync
import com.tinhcd.myesalessfa.domain.usecase.SignInUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val signedIn: Boolean = false,
) {
    val canSubmit: Boolean get() = username.isNotBlank() && password.isNotBlank() && !loading
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val signIn: SignInUseCase,
    private val referenceDataSync: ReferenceDataSync,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = signIn(current.username, current.password)) {
                is DataResult.Success -> {
                    // Settings, reason codes and the catalogue have to be cached
                    // before the first check-in, which may happen out of signal.
                    //
                    // Through the shared sync rather than by calling each repository:
                    // it records that a refresh has happened, so the foreground event
                    // moments later does not immediately fetch everything again.
                    //
                    // Failure is not fatal and is not surfaced here. The policy falls
                    // back to strict defaults and the catalogue screens say they are
                    // empty; refusing the sign-in would be a worse answer than a rep
                    // who is signed in with yesterday's data.
                    referenceDataSync.syncNow()
                    _state.update { it.copy(loading = false, signedIn = true) }
                }

                is DataResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.error.toMessage()) }
            }
        }
    }
}

private fun AppError.toMessage(): String = when (this) {
    is AppError.Auth -> when (message) {
        "invalid_credentials" -> "Sai ten dang nhap hoac mat khau"
        // Permanent, and only head office can fix it.
        "account_not_provisioned" -> "Tai khoan chua duoc gan nhan vien ban hang"
        // Transient: the password was accepted, the profile was not fetched. Says so,
        // rather than blaming the account.
        "profile_unavailable" -> "Da dang nhap nhung chua tai duoc thong tin nhan vien. Thu lai."
        else -> "Dang nhap that bai"
    }

    is AppError.Network -> "Khong co ket noi mang"
    is AppError.Rule -> when (key) {
        "login_empty_fields" -> "Vui long nhap day du thong tin"
        else -> "Du lieu khong hop le"
    }

    is AppError.Server -> "Loi may chu${code?.let { " ($it)" }.orEmpty()}"
    is AppError.Unknown -> message?.takeIf { it.isNotBlank() } ?: "Da co loi xay ra"
}
