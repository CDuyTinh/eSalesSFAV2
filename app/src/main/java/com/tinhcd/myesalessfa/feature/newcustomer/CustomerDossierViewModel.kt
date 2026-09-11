package com.tinhcd.myesalessfa.feature.newcustomer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.core.location.LocationProvider
import com.tinhcd.myesalessfa.core.photo.PhotoStore
import com.tinhcd.myesalessfa.core.photo.PhotoTarget
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.CustomerDraft
import com.tinhcd.myesalessfa.domain.model.CustomerPhotoType
import com.tinhcd.myesalessfa.domain.model.CustomerRegistrationBoard
import com.tinhcd.myesalessfa.domain.model.CustomerRegistrationDetail
import com.tinhcd.myesalessfa.domain.model.CustomerRegistrationRow
import com.tinhcd.myesalessfa.domain.model.GeoPoint
import com.tinhcd.myesalessfa.domain.model.RegistrationStatus
import com.tinhcd.myesalessfa.domain.repository.CustomerRegistrationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DossierPage { LIST, DETAIL, EDIT }

data class CustomerDossierUiState(
    val loading: Boolean = true,
    val page: DossierPage = DossierPage.LIST,
    val board: CustomerRegistrationBoard = CustomerRegistrationBoard(),
    val statusFilter: RegistrationStatus? = null,
    val query: String = "",

    val detail: CustomerRegistrationDetail? = null,
    val draft: CustomerDraft = CustomerDraft(),

    val capturingFor: CustomerPhotoType? = null,
    val working: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    val visible: List<CustomerRegistrationRow>
        get() = board.items.filter { statusFilter == null || it.status == statusFilter }

    val canSave: Boolean get() = !working && draft.canSubmit

    /** Required slots still empty on the open registration. */
    val missingPhotos: List<CustomerPhotoType>
        get() = detail?.missingRequired(board.photoTypes).orEmpty()
}

/**
 * Hồ sơ khách hàng mới — what the rep filed, and what became of it.
 *
 * Filing a registration is the start of a wait, not the end of a task: the rep
 * wants to know whether it was approved, correct the phone number they mistyped,
 * and withdraw the one they filed twice. The rebuild could do none of those
 * until now.
 */
@HiltViewModel
class CustomerDossierViewModel @Inject constructor(
    private val repository: CustomerRegistrationRepository,
    private val photoStore: PhotoStore,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(CustomerDossierUiState())
    val state: StateFlow<CustomerDossierUiState> = _state.asStateFlow()

    /** The file the camera is currently writing into, if any. */
    private var pending: PhotoTarget? = null

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.board(query = _state.value.query)) {
                is DataResult.Success -> _state.update {
                    it.copy(loading = false, board = result.data)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(loading = false, error = "Không tải được hồ sơ khách hàng mới")
                }
            }
        }
    }

    /** Tapping the selected chip clears it. */
    fun onStatusFilter(status: RegistrationStatus?) = _state.update {
        it.copy(statusFilter = if (it.statusFilter == status) null else status)
    }

    /** Filtered server-side: a rep who has registered hundreds should not be sent them all. */
    fun onQuery(value: String) {
        _state.update { it.copy(query = value) }
        load()
    }

    fun dismissMessage() = _state.update { it.copy(message = null, error = null) }

    // -------------------------------------------------------------------------

    fun onOpen(row: CustomerRegistrationRow) {
        _state.update { it.copy(page = DossierPage.DETAIL, working = true, error = null) }

        viewModelScope.launch {
            when (val result = repository.detail(row.id)) {
                is DataResult.Success -> _state.update {
                    it.copy(working = false, detail = result.data)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(working = false, page = DossierPage.LIST, error = "Không mở được hồ sơ")
                }
            }
        }
    }

    fun onBackToList() = _state.update {
        it.copy(page = DossierPage.LIST, detail = null, error = null)
    }

    /** The form, filled from what was filed. */
    fun onEdit() {
        val detail = _state.value.detail ?: return
        if (!detail.row.canEdit) return

        _state.update {
            it.copy(
                page = DossierPage.EDIT,
                error = null,
                draft = CustomerDraft(
                    name = detail.row.name,
                    phone = detail.row.phone.orEmpty(),
                    address = detail.row.address.orEmpty(),
                    provinceId = detail.provinceId,
                    districtId = detail.districtId,
                    wardId = detail.wardId,
                    classId = detail.classId,
                    channelId = detail.channelId,
                    shopTypeId = detail.shopTypeId,
                    note = detail.row.note.orEmpty(),
                    point = detail.point,
                ),
            )
        }
    }

    fun onCancelEdit() = _state.update {
        it.copy(page = DossierPage.DETAIL, draft = CustomerDraft(), error = null)
    }

    fun onName(value: String) = _state.update { it.copy(draft = it.draft.copy(name = value)) }
    fun onPhone(value: String) = _state.update { it.copy(draft = it.draft.copy(phone = value)) }
    fun onAddress(value: String) = _state.update { it.copy(draft = it.draft.copy(address = value)) }
    fun onNote(value: String) = _state.update { it.copy(draft = it.draft.copy(note = value)) }

    fun save() {
        val current = _state.value
        val id = current.detail?.row?.id ?: return
        if (!current.canSave) return

        _state.update { it.copy(working = true, error = null) }

        viewModelScope.launch {
            when (val result = repository.update(id, current.draft)) {
                is DataResult.Success -> {
                    _state.update {
                        it.copy(
                            working = false,
                            page = DossierPage.DETAIL,
                            detail = result.data,
                            draft = CustomerDraft(),
                            message = "Đã cập nhật hồ sơ",
                        )
                    }
                    load()
                }

                is DataResult.Failure -> _state.update {
                    it.copy(working = false, error = "Không cập nhật được hồ sơ")
                }
            }
        }
    }

    fun onDelete() {
        val row = _state.value.detail?.row ?: return
        if (!row.canEdit || _state.value.working) return

        _state.update { it.copy(working = true, error = null) }

        viewModelScope.launch {
            when (repository.delete(row.id)) {
                is DataResult.Success -> {
                    _state.update {
                        it.copy(
                            working = false,
                            page = DossierPage.LIST,
                            detail = null,
                            message = "Đã xóa hồ sơ",
                        )
                    }
                    load()
                }

                is DataResult.Failure -> _state.update {
                    it.copy(working = false, error = "Không xóa được hồ sơ")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Photographs
    // -------------------------------------------------------------------------

    fun newPhotoTarget(type: CustomerPhotoType): PhotoTarget = photoStore.newTarget().also {
        pending = it
        _state.update { state -> state.copy(capturingFor = type, error = null) }
    }

    fun onPhotoTaken(saved: Boolean) {
        val target = pending
        val type = _state.value.capturingFor
        val customerId = _state.value.detail?.row?.id
        pending = null

        if (!saved || target == null || type == null || customerId == null) {
            target?.let { photoStore.delete(it.path) }
            _state.update { it.copy(capturingFor = null) }
            return
        }

        viewModelScope.launch {
            val size = photoStore.compress(target.path)
            if (size <= 0L) {
                photoStore.delete(target.path)
                _state.update {
                    it.copy(capturingFor = null, error = "Không lưu được ảnh, thử lại")
                }
                return@launch
            }

            val point = runCatching { locationProvider.currentLocation() }.getOrNull()

            when (
                val result = repository.setPhoto(
                    customerId = customerId,
                    typeId = type.id,
                    storagePath = target.path,
                    point = point?.let { GeoPoint(it.lat, it.lng, accuracyM = null) },
                    fileSize = size.toInt(),
                )
            ) {
                // The server hands back the whole registration, avatar included,
                // so the screen never has to work out which slot changed what.
                is DataResult.Success -> {
                    _state.update {
                        it.copy(capturingFor = null, detail = result.data)
                    }
                    load()
                }

                is DataResult.Failure -> {
                    photoStore.delete(target.path)
                    _state.update {
                        it.copy(capturingFor = null, error = "Không gửi được ảnh")
                    }
                }
            }
        }
    }
}
