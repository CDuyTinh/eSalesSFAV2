package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.core.location.LocationProvider
import com.tinhcd.myesalessfa.core.photo.PhotoStore
import com.tinhcd.myesalessfa.core.photo.PhotoTarget
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftNearExpiry
import com.tinhcd.myesalessfa.domain.model.NearExpiryLot
import com.tinhcd.myesalessfa.domain.model.NearExpiryPhoto
import com.tinhcd.myesalessfa.domain.model.PricedProduct
import com.tinhcd.myesalessfa.domain.model.ReasonCode
import com.tinhcd.myesalessfa.domain.model.ReasonKind
import com.tinhcd.myesalessfa.domain.model.StepConfig
import com.tinhcd.myesalessfa.domain.model.SupportedSteps
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import com.tinhcd.myesalessfa.domain.repository.NearExpiryRepository
import com.tinhcd.myesalessfa.domain.repository.WorkflowRepository
import com.tinhcd.myesalessfa.domain.usecase.GetVisitCatalogueUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The document, or the catalogue the rep is picking a product out of. */
enum class NearExpiryPage { DOCUMENT, PICK_PRODUCT }

data class NearExpiryUiState(
    val loading: Boolean = true,
    val title: String = "",
    val page: NearExpiryPage = NearExpiryPage.DOCUMENT,
    val draft: DraftNearExpiry = DraftNearExpiry(visitId = ""),
    val catalogue: List<PricedProduct> = emptyList(),
    val query: String = "",
    /** Reasons for arriving without a photograph. */
    val noPhotoReasons: List<ReasonCode> = emptyList(),
    /** The product whose lot form is open, if any. */
    val addingFor: PricedProduct? = null,
    val capturing: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
) {
    /** What the picker shows: the priced catalogue, narrowed by what was typed. */
    val visibleProducts: List<PricedProduct>
        get() = if (query.isBlank()) {
            catalogue
        } else {
            val needle = query.trim().lowercase()
            catalogue.filter {
                it.product.name.lowercase().contains(needle) ||
                    it.product.code.lowercase().contains(needle)
            }
        }

    val canSubmit: Boolean get() = !submitting && !capturing && draft.canSubmit
}

/**
 * Backs the `stock_out_date` step.
 *
 * The rep walks the shelf for stock about to expire and writes down each batch:
 * which product, which lot number, when it goes off, how many. It ends with a
 * photograph or a reason for not taking one, which is the legacy's own rule and
 * the one thing that makes the document evidence rather than an assertion.
 */
@HiltViewModel
class NearExpiryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workflowRepository: WorkflowRepository,
    private val configRepository: ConfigRepository,
    private val nearExpiryRepository: NearExpiryRepository,
    private val getVisitCatalogue: GetVisitCatalogueUseCase,
    private val photoStore: PhotoStore,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val visitId: String = checkNotNull(savedStateHandle["visitId"])
    private val customerId: String = checkNotNull(savedStateHandle["customerId"])

    private val _state = MutableStateFlow(NearExpiryUiState())
    val state: StateFlow<NearExpiryUiState> = _state.asStateFlow()

    /** The file the camera is currently writing into, if any. */
    private var pending: PhotoTarget? = null

    init {
        viewModelScope.launch {
            val step = (workflowRepository.step(SupportedSteps.STOCK_OUT_DATE)
                as? DataResult.Success)?.data
            val title = step?.let { configRepository.translate(it.titleKey) }.orEmpty()

            // `input_lot_num_then_eght` — eight over there, configurable here so a
            // market whose lots are ten characters is a settings change.
            val lotLength = step?.configInt(StepConfig.LOT_NO_LENGTH, default = 8) ?: 8
            val photoMax = step?.configInt(StepConfig.PHOTO_MAX, default = 4) ?: 4

            val reasons = configRepository.reasons(ReasonKind.PHOTO_SKIPPED)

            // Whatever this visit already wrote down, so reopening the step shows
            // the lots rather than a blank list the rep would key in twice.
            val existing = (nearExpiryRepository.lotsFor(visitId) as? DataResult.Success)
                ?.data.orEmpty()

            when (val visit = getVisitCatalogue(customerId)) {
                is DataResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        title = title.ifBlank { "Hàng cận date" },
                        catalogue = visit.data.catalogue,
                        noPhotoReasons = reasons,
                        draft = DraftNearExpiry(
                            visitId = visitId,
                            lots = existing,
                            lotNoLength = lotLength,
                            photoMax = photoMax,
                        ),
                    )
                }

                is DataResult.Failure -> _state.update {
                    it.copy(
                        loading = false,
                        title = title.ifBlank { "Hàng cận date" },
                        noPhotoReasons = reasons,
                        draft = DraftNearExpiry(
                            visitId = visitId,
                            lots = existing,
                            lotNoLength = lotLength,
                            photoMax = photoMax,
                        ),
                        error = "Không tải được danh mục sản phẩm",
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Picking a product, then a lot of it
    // -------------------------------------------------------------------------

    fun onOpenPicker() = _state.update {
        it.copy(page = NearExpiryPage.PICK_PRODUCT, query = "", error = null)
    }

    fun onClosePicker() = _state.update {
        it.copy(page = NearExpiryPage.DOCUMENT, addingFor = null, error = null)
    }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

    fun onPickProduct(product: PricedProduct) = _state.update {
        it.copy(addingFor = product, error = null)
    }

    fun onDismissLotForm() = _state.update { it.copy(addingFor = null) }

    /**
     * Adds one batch.
     *
     * The two refusals are the legacy form's, and each is reported rather than
     * swallowed: a wrong-length number means the rep misread the case, and a
     * repeated one means they are entering the same batch twice.
     */
    fun onAddLot(
        product: PricedProduct,
        lotNo: String,
        expiryDate: String,
        uomCode: String,
        qty: Int,
    ) {
        val current = _state.value
        val draft = current.draft
        val trimmed = lotNo.trim()

        if (!draft.isLotNoValid(trimmed)) {
            _state.update {
                it.copy(error = "Số lô phải đúng ${draft.lotNoLength} ký tự")
            }
            return
        }

        if (draft.hasLot(product.product.id, trimmed)) {
            _state.update { it.copy(error = "Lô $trimmed đã có trong danh sách") }
            return
        }

        if (qty < 1) {
            _state.update { it.copy(error = "Số lượng phải lớn hơn 0") }
            return
        }

        val unit = product.units.firstOrNull { it.unit.uomCode == uomCode }
            ?: product.units.firstOrNull()
            ?: run {
                _state.update { it.copy(error = "Sản phẩm chưa có đơn vị bán") }
                return
            }

        _state.update {
            it.copy(
                addingFor = null,
                page = NearExpiryPage.DOCUMENT,
                error = null,
                draft = it.draft.withLot(
                    NearExpiryLot(
                        productId = product.product.id,
                        productCode = product.product.code,
                        productName = product.product.name,
                        lotNo = trimmed,
                        expiryDate = expiryDate,
                        uomCode = unit.unit.uomCode,
                        uomName = unit.unit.uomName,
                        qty = qty,
                        // Stored beside the counted figure, as the stock count
                        // does: a case of twenty-four and twenty-four singles are
                        // the same quantity and a different sentence.
                        baseQty = qty * unit.unit.conversionRate,
                    ),
                ),
            )
        }
    }

    fun onRemoveLot(productId: String, lotNo: String) = _state.update {
        it.copy(draft = it.draft.withoutLot(productId, lotNo), error = null)
    }

    fun onNoteChange(value: String) = _state.update {
        it.copy(draft = it.draft.copy(note = value), error = null)
    }

    // -------------------------------------------------------------------------
    // Evidence, or a reason for its absence
    // -------------------------------------------------------------------------

    fun newPhotoTarget(): PhotoTarget = photoStore.newTarget().also {
        pending = it
        _state.update { state -> state.copy(capturing = true, error = null) }
    }

    fun onPhotoTaken(saved: Boolean) {
        val target = pending
        pending = null

        if (!saved || target == null) {
            target?.let { photoStore.delete(it.path) }
            _state.update { it.copy(capturing = false) }
            return
        }

        viewModelScope.launch {
            val size = photoStore.compress(target.path)
            if (size <= 0L) {
                photoStore.delete(target.path)
                _state.update {
                    it.copy(capturing = false, error = "Không lưu được ảnh, thử lại")
                }
                return@launch
            }

            val point = runCatching { locationProvider.currentLocation() }.getOrNull()

            _state.update {
                it.copy(
                    capturing = false,
                    // withPhoto clears any reason: a photograph makes it
                    // meaningless, and the server clears it too.
                    draft = it.draft.withPhoto(
                        NearExpiryPhoto(
                            localPath = target.path,
                            takenAtEpochMs = System.currentTimeMillis(),
                            lat = point?.lat,
                            lng = point?.lng,
                            sizeBytes = size,
                        ),
                    ),
                )
            }
        }
    }

    fun onRemovePhoto(localPath: String) {
        photoStore.delete(localPath)
        _state.update { it.copy(draft = it.draft.withoutPhoto(localPath), error = null) }
    }

    /** Tapping the chosen reason again clears it. */
    fun onNoPhotoReason(reasonId: String) = _state.update {
        val next = if (it.draft.noPhotoReasonId == reasonId) null else reasonId
        it.copy(draft = it.draft.withNoPhotoReason(next), error = null)
    }

    // -------------------------------------------------------------------------

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        _state.update { it.copy(submitting = true, error = null) }

        viewModelScope.launch {
            when (nearExpiryRepository.submit(current.draft)) {
                is DataResult.Success -> _state.update {
                    it.copy(submitting = false, finished = true)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(submitting = false, error = "Không lưu được phiếu hàng cận date")
                }
            }
        }
    }

    override fun onCleared() {
        // Nothing has been uploaded yet, so the files go with the draft.
        if (!_state.value.finished) {
            _state.value.draft.photos.forEach { photoStore.delete(it.localPath) }
        }
    }
}
