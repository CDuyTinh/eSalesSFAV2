package com.tinhcd.myesalessfa.feature.incall.steps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinhcd.myesalessfa.core.location.LocationProvider
import com.tinhcd.myesalessfa.core.photo.PhotoStore
import com.tinhcd.myesalessfa.core.photo.PhotoTarget
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.DraftPosmCheck
import com.tinhcd.myesalessfa.domain.model.DraftPosmMovement
import com.tinhcd.myesalessfa.domain.model.DraftPosmRegistration
import com.tinhcd.myesalessfa.domain.model.PosmAtCustomer
import com.tinhcd.myesalessfa.domain.model.PosmCatalogueEntry
import com.tinhcd.myesalessfa.domain.model.PosmCondition
import com.tinhcd.myesalessfa.domain.model.PosmMovementKind
import com.tinhcd.myesalessfa.domain.model.PosmMovementLine
import com.tinhcd.myesalessfa.domain.model.PosmPhoto
import com.tinhcd.myesalessfa.domain.model.PosmRegistration
import com.tinhcd.myesalessfa.domain.model.PosmRegistrationLine
import com.tinhcd.myesalessfa.domain.model.StepConfig
import com.tinhcd.myesalessfa.domain.repository.ConfigRepository
import com.tinhcd.myesalessfa.domain.repository.PosmRepository
import com.tinhcd.myesalessfa.domain.repository.WorkflowRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The two questions the legacy's two tabs ask.
 *
 * [IN_USE] is "what of ours is in this shop, and what state is it in" — the work
 * of the step. [REGISTERED] is "what has this shop asked for and where has it
 * got to", which the rep reads but does not yet write from here.
 */
enum class PosmTab { IN_USE, REGISTERED }

/** The list, one assets check, a request being put in, or a handover. */
enum class PosmPage { LIST, CHECK, REGISTER, MOVE }

data class PosmUiState(
    val loading: Boolean = true,
    val title: String = "",
    val page: PosmPage = PosmPage.LIST,
    val tab: PosmTab = PosmTab.IN_USE,
    val placed: List<PosmAtCustomer> = emptyList(),
    val registrations: List<PosmRegistration> = emptyList(),
    val check: DraftPosmCheck? = null,
    val catalogue: List<PosmCatalogueEntry> = emptyList(),
    val registration: DraftPosmRegistration? = null,
    val movement: DraftPosmMovement? = null,
    val capturing: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
) {
    val checkedCount: Int get() = placed.count { it.isChecked }

    /** True once nothing is left to check, which is when the step goes green. */
    val allChecked: Boolean get() = placed.isNotEmpty() && checkedCount == placed.size

    /** No POSM at all — the case the step still has to let the rep close. */
    val nothingPlaced: Boolean get() = !loading && placed.isEmpty()
}

/**
 * Backs the `posm_status` step.
 *
 * The step used to be a questionnaire. It is an asset check: the company lends a
 * shop a fridge and a rack, and the rep's job on the call is to find each one,
 * count it, say what condition it is in and photograph it.
 */
@HiltViewModel
class PosmViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workflowRepository: WorkflowRepository,
    private val configRepository: ConfigRepository,
    private val posmRepository: PosmRepository,
    private val photoStore: PhotoStore,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val visitId: String = checkNotNull(savedStateHandle["visitId"])
    private val customerId: String = checkNotNull(savedStateHandle["customerId"])

    private val _state = MutableStateFlow(PosmUiState())
    val state: StateFlow<PosmUiState> = _state.asStateFlow()

    /** The file the camera is currently writing into, if any. */
    private var pending: PhotoTarget? = null

    /** From the step's config, and reused for every asset's draft. */
    private var photoMin = 1
    private var photoMax = 4

    init {
        viewModelScope.launch {
            val step = (workflowRepository.step(FORM_ID) as? DataResult.Success)?.data
            val title = step?.let { configRepository.translate(it.titleKey) }.orEmpty()

            // One photo, not zero: an asset the rep reports as broken and did not
            // photograph is an assertion rather than a report. Four is the
            // legacy's own POSM_Image default.
            photoMin = step?.configInt(StepConfig.PHOTO_MIN, default = 1) ?: 1
            photoMax = step?.configInt(StepConfig.PHOTO_MAX, default = 4) ?: 4

            _state.update { it.copy(title = title) }
            reload()
        }
    }

    private suspend fun reload() {
        when (val r = posmRepository.load(customerId, visitId)) {
            is DataResult.Success -> _state.update {
                it.copy(
                    loading = false,
                    placed = r.data.placed,
                    registrations = r.data.registrations,
                    catalogue = r.data.catalogue,
                )
            }

            is DataResult.Failure -> _state.update {
                it.copy(loading = false, error = "Không tải được danh sách POSM")
            }
        }
    }

    fun onTabChange(tab: PosmTab) = _state.update { it.copy(tab = tab, error = null) }

    /** Opens one asset for checking. Anything half-shot on another is discarded. */
    fun onOpenItem(item: PosmAtCustomer) {
        discardPhotos()
        _state.update {
            it.copy(
                page = PosmPage.CHECK,
                error = null,
                check = DraftPosmCheck(
                    visitId = visitId,
                    customerId = customerId,
                    item = item,
                    // Prefilled when this visit already checked the asset, so
                    // redoing it starts from what was recorded. The photos
                    // deliberately do not come back: they live on the server now,
                    // and redoing replaces them with what the rep is looking at.
                    countedQty = item.countedQty,
                    condition = item.condition,
                    remark = item.remark.orEmpty(),
                    suggestion = item.suggestion.orEmpty(),
                    photoMin = photoMin,
                    photoMax = photoMax,
                ),
            )
        }
    }

    /** Backs out of an asset without recording it, dropping its photos. */
    fun onLeaveItem() {
        discardPhotos()
        _state.update { it.copy(page = PosmPage.LIST, check = null, error = null) }
    }

    /** Nothing has been uploaded yet, so the files go with the draft. */
    private fun discardPhotos() {
        _state.value.check?.photos?.forEach { photoStore.delete(it.localPath) }
        _state.value.movement?.photos?.forEach { photoStore.delete(it.localPath) }
    }

    /**
     * Hands the camera somewhere to write. Called immediately before launching it,
     * so the file exists by the time the camera app resolves the uri.
     */
    fun newPhotoTarget(): PhotoTarget = photoStore.newTarget().also {
        pending = it
        _state.update { state -> state.copy(capturing = true, error = null) }
    }

    /**
     * Records the photo the camera just wrote. Compressed here rather than at
     * upload time so the rep waits for it once, while standing in front of the
     * asset, instead of the upload stalling on it later.
     */
    fun onPhotoTaken(saved: Boolean) {
        val target = pending
        pending = null

        if (!saved || target == null) {
            // Cancelled. The camera may still have created an empty file.
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

            val photo = PosmPhoto(
                localPath = target.path,
                takenAtEpochMs = System.currentTimeMillis(),
                lat = point?.lat,
                lng = point?.lng,
                sizeBytes = size,
            )

            // The camera comes back without context, so the open page decides where
            // the shot lands: a check on one asset, or the evidence for a handover.
            _state.update {
                it.copy(
                    capturing = false,
                    check = if (it.page == PosmPage.MOVE) it.check else it.check?.withPhoto(photo),
                    movement = if (it.page == PosmPage.MOVE) it.movement?.withPhoto(photo) else it.movement,
                )
            }
        }
    }

    /** Removes a rejected shot and the file behind it — nothing is uploaded yet. */
    fun onRemovePhoto(localPath: String) {
        photoStore.delete(localPath)
        _state.update {
            it.copy(
                check = it.check?.withoutPhoto(localPath),
                movement = it.movement?.withoutPhoto(localPath),
                error = null,
            )
        }
    }

    /** Floored at zero: a shop cannot hold a negative number of fridges. */
    fun onCountedQtyChange(value: Int) = _state.update {
        it.copy(check = it.check?.copy(countedQty = value.coerceAtLeast(0)), error = null)
    }

    fun onConditionChange(value: PosmCondition) = _state.update {
        it.copy(check = it.check?.copy(condition = value), error = null)
    }

    fun onRemarkChange(value: String) = _state.update {
        it.copy(check = it.check?.copy(remark = value), error = null)
    }

    fun onSuggestionChange(value: String) = _state.update {
        it.copy(check = it.check?.copy(suggestion = value), error = null)
    }

    fun submit() {
        val current = _state.value
        val check = current.check ?: return
        if (current.submitting || !check.canSubmit) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (posmRepository.submit(check)) {
                is DataResult.Success -> {
                    reload()
                    val refreshed = _state.value
                    _state.update {
                        it.copy(
                            submitting = false,
                            page = PosmPage.LIST,
                            check = null,
                            // The step finishes only when every asset has been
                            // looked at, which is the same rule submit_posm_check
                            // applies before it marks the step done.
                            finished = refreshed.allChecked,
                        )
                    }
                }

                is DataResult.Failure -> _state.update {
                    it.copy(submitting = false, error = "Không lưu được phiếu kiểm POSM")
                }
            }
        }
    }

    /**
     * Closes the step for an outlet holding no POSM. The rep has looked and there
     * is nothing to count; without this the step would sit unfinished forever on
     * every shop that happens to have none of the company's furniture.
     */
    fun completeEmpty() {
        val current = _state.value
        if (current.submitting || !current.nothingPlaced) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (posmRepository.completeEmpty(visitId)) {
                is DataResult.Success -> _state.update {
                    it.copy(submitting = false, finished = true)
                }

                is DataResult.Failure -> _state.update {
                    it.copy(submitting = false, error = "Không ghi nhận được bước POSM")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Đăng ký POSM
    // -------------------------------------------------------------------------

    /**
     * Opens the request form on everything this outlet may still be signed up for.
     *
     * Assets head office has already ruled on are left out rather than shown
     * greyed: an approved request is chased on the other tab, and a refused one is
     * not the rep's to reopen.
     */
    fun onOpenRegister() {
        discardPhotos()
        _state.update { state ->
            state.copy(
                page = PosmPage.REGISTER,
                error = null,
                check = null,
                registration = DraftPosmRegistration(
                    visitId = visitId,
                    lines = state.catalogue
                        .filter { it.canRegister }
                        .map { entry ->
                            // A pending request comes back with what was asked for,
                            // so restating it starts from the last number rather
                            // than from zero.
                            PosmRegistrationLine(
                                entry = entry,
                                qty = if (entry.isPending) entry.registeredQty else 0,
                            )
                        },
                ),
            )
        }
    }

    fun onRegisterQty(programId: String, itemId: String, qty: Int) = _state.update {
        it.copy(registration = it.registration?.withQty(itemId, programId, qty), error = null)
    }

    fun onRegisterReason(value: String) = _state.update {
        it.copy(registration = it.registration?.copy(reason = value), error = null)
    }

    fun submitRegistration() {
        val draft = _state.value.registration ?: return
        if (_state.value.submitting || !draft.canSubmit) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (posmRepository.register(draft)) {
                is DataResult.Success -> {
                    reload()
                    _state.update {
                        it.copy(submitting = false, page = PosmPage.LIST, registration = null)
                    }
                }

                is DataResult.Failure -> _state.update {
                    it.copy(submitting = false, error = "Không gửi được đăng ký POSM")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Giao và thu hồi
    // -------------------------------------------------------------------------

    /** Hands over what head office approved and the shop has not had yet. */
    fun onOpenDelivery(registration: PosmRegistration) {
        openMovement(
            PosmMovementKind.DELIVERY,
            listOf(
                PosmMovementLine(
                    programId = registration.programId,
                    itemId = registration.itemId,
                    itemName = registration.itemName,
                    unitName = registration.unitName,
                    available = registration.awaitingDelivery,
                    qty = registration.awaitingDelivery,
                ),
            ),
        )
    }

    /** Takes back what the outlet is holding. */
    fun onOpenRecall(item: PosmAtCustomer) {
        openMovement(
            PosmMovementKind.RECALL,
            listOf(
                PosmMovementLine(
                    programId = item.programId,
                    itemId = item.itemId,
                    itemName = item.itemName,
                    unitName = item.unitName,
                    available = item.placedQty,
                    // Nothing prefilled: a recall is usually partial, and a form
                    // that starts at "all of them" is one a tired rep just sends.
                    qty = 0,
                ),
            ),
        )
    }

    private fun openMovement(kind: PosmMovementKind, lines: List<PosmMovementLine>) {
        discardPhotos()
        _state.update {
            it.copy(
                page = PosmPage.MOVE,
                error = null,
                check = null,
                movement = DraftPosmMovement(
                    visitId = visitId,
                    kind = kind,
                    lines = lines,
                    // One photograph at least, whatever the step configures for a
                    // check: the legacy refuses a handover without one, and so does
                    // submit_posm_movement.
                    photoMin = maxOf(1, photoMin),
                    photoMax = photoMax,
                ),
            )
        }
    }

    fun onMovementQty(programId: String, itemId: String, qty: Int) = _state.update {
        it.copy(movement = it.movement?.withQty(itemId, programId, qty), error = null)
    }

    fun onMovementNote(value: String) = _state.update {
        it.copy(movement = it.movement?.copy(note = value), error = null)
    }

    fun submitMovement() {
        val draft = _state.value.movement ?: return
        if (_state.value.submitting || !draft.canSubmit) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (posmRepository.move(draft)) {
                is DataResult.Success -> {
                    reload()
                    _state.update {
                        it.copy(submitting = false, page = PosmPage.LIST, movement = null)
                    }
                }

                is DataResult.Failure -> _state.update {
                    it.copy(
                        submitting = false,
                        error = if (draft.kind == PosmMovementKind.DELIVERY) {
                            "Không ghi nhận được lần giao POSM"
                        } else {
                            "Không ghi nhận được lần thu hồi POSM"
                        },
                    )
                }
            }
        }
    }

    /** Backs out of a request or a handover, dropping anything shot for it. */
    fun onLeaveForm() {
        discardPhotos()
        _state.update {
            it.copy(page = PosmPage.LIST, registration = null, movement = null, error = null)
        }
    }

    private companion object {
        const val FORM_ID = "posm_status"
    }
}
