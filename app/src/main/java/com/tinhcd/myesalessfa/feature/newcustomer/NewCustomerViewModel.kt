package com.tinhcd.myesalessfa.feature.newcustomer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.core.location.LocationProvider
import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CustomerDraft
import com.tinhcd.myesalessfa.domain.model.CustomerOptions
import com.tinhcd.myesalessfa.domain.model.NamedRef
import com.tinhcd.myesalessfa.domain.model.RegisteredCustomer
import com.tinhcd.myesalessfa.domain.repository.CustomerRegistrationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NewCustomerUiState(
    val loading: Boolean = true,
    val options: CustomerOptions = CustomerOptions(),
    val districts: List<NamedRef> = emptyList(),
    val wards: List<NamedRef> = emptyList(),
    val draft: CustomerDraft = CustomerDraft(),
    val locating: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    /** Set once the outlet exists, so the screen can name it before leaving. */
    val registered: RegisteredCustomer? = null,
    /** Shown only after a failed submit, so a half-filled form is not scolded. */
    val showErrors: Boolean = false,
)

/**
 * Registering an outlet the rep is standing in.
 *
 * The position is taken when the screen opens rather than at submit. The rep is
 * at the shop while they type, and by the time they have picked a ward they may
 * have walked back to the bike — capturing late would record where the form was
 * finished rather than where the outlet is.
 */
@HiltViewModel
class NewCustomerViewModel @Inject constructor(
    private val repository: CustomerRegistrationRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(NewCustomerUiState())
    val state: StateFlow<NewCustomerUiState> = _state.asStateFlow()

    init {
        load()
        captureLocation()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.options()) {
                is DataResult.Success ->
                    _state.update { it.copy(loading = false, options = result.data) }

                is DataResult.Failure ->
                    _state.update {
                        it.copy(loading = false, error = "Không tải được danh mục")
                    }
            }
        }
    }

    fun captureLocation() {
        _state.update { it.copy(locating = true) }
        viewModelScope.launch {
            val point = runCatching { locationProvider.currentLocation() }.getOrNull()
            _state.update {
                it.copy(locating = false, draft = it.draft.copy(point = point))
            }
        }
    }

    fun onName(value: String) = edit { it.copy(name = value) }

    fun onPhone(value: String) = edit { it.copy(phone = value) }

    fun onAddress(value: String) = edit { it.copy(address = value) }

    fun onNote(value: String) = edit { it.copy(note = value) }

    fun onClass(value: NamedRef?) = edit { it.copy(classId = value?.id) }

    fun onChannel(value: NamedRef?) = edit { it.copy(channelId = value?.id) }

    fun onShopType(value: NamedRef?) = edit { it.copy(shopTypeId = value?.id) }

    /**
     * Choosing a province clears the district and ward under it, and choosing a
     * district clears the ward. Leaving them would let a form be submitted with a
     * ward in one district and a district in another — the kind of row that looks
     * fine until somebody plots it on a map.
     */
    fun onProvince(value: NamedRef?) {
        _state.update {
            it.copy(
                districts = emptyList(),
                wards = emptyList(),
                draft = it.draft.copy(
                    provinceId = value?.id,
                    districtId = null,
                    wardId = null,
                ),
            )
        }
        val id = value?.id ?: return
        viewModelScope.launch {
            repository.districts(id).onSuccess { rows ->
                _state.update { it.copy(districts = rows) }
            }
        }
    }

    fun onDistrict(value: NamedRef?) {
        _state.update {
            it.copy(
                wards = emptyList(),
                draft = it.draft.copy(districtId = value?.id, wardId = null),
            )
        }
        val id = value?.id ?: return
        viewModelScope.launch {
            repository.wards(id).onSuccess { rows -> _state.update { it.copy(wards = rows) } }
        }
    }

    fun onWard(value: NamedRef?) = edit { it.copy(wardId = value?.id) }

    fun submit() {
        val current = _state.value
        if (current.submitting) return

        if (!current.draft.canSubmit) {
            _state.update { it.copy(showErrors = true) }
            return
        }

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.register(current.draft)) {
                is DataResult.Success ->
                    _state.update { it.copy(submitting = false, registered = result.data) }

                is DataResult.Failure ->
                    _state.update {
                        it.copy(submitting = false, error = result.error.registrationMessage())
                    }
            }
        }
    }

    private fun edit(change: (CustomerDraft) -> CustomerDraft) =
        _state.update { it.copy(draft = change(it.draft)) }
}

private inline fun <T> DataResult<T>.onSuccess(block: (T) -> Unit) {
    if (this is DataResult.Success) block(data)
}

/**
 * The server's refusals are specific — which field it would not accept — so they
 * are preferred over anything this screen could say in their place.
 */
private fun AppError.registrationMessage(): String = when (this) {
    is AppError.Network -> "Không có kết nối mạng"
    is AppError.Auth -> "Phiên đăng nhập đã hết hạn, đăng nhập lại"
    is AppError.Server -> message.orFallback()
    is AppError.Rule -> message.orFallback()
    is AppError.Unknown -> message.orFallback()
}

private fun String?.orFallback(): String =
    this?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
        ?: "Chưa đăng ký được khách hàng, thử lại"
