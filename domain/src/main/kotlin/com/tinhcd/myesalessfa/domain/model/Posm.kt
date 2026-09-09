package com.tinhcd.myesalessfa.domain.model

/**
 * POSM: the company's furniture in someone else's shop.
 *
 * A fridge, a three-tier rack, a light box — lent to the outlet and still the
 * company's property. The step is not a questionnaire about whether a poster is
 * hung nicely; it is an asset check. The rep finds each item, counts it, says
 * what condition it is in and photographs it, which is the only way anyone at
 * head office learns that a two-million-dong fridge has a broken door.
 */

/**
 * The three answers the legacy gives a rep for an asset's condition
 * (`API_GetPOSMStatus`). Three values that have not changed in the legacy's
 * lifetime, so they are a type here rather than a lookup table.
 */
enum class PosmCondition(val wireValue: String, val label: String) {
    USABLE("usable", "Sử dụng được"),
    REPAIRABLE("repairable", "Có thể sửa chữa"),
    UNUSABLE("unusable", "Không thể sử dụng"),
    ;

    companion object {
        fun fromWire(value: String?): PosmCondition? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * One asset at this outlet, with whatever this visit has already recorded.
 *
 * [placedQty] is what the company believes it lent; [countedQty] is what the rep
 * found. The two disagreeing is the single most useful thing this step produces,
 * which is why both are kept rather than the count overwriting the record.
 */
data class PosmAtCustomer(
    val programId: String,
    val programCode: String,
    val programName: String,
    val itemId: String,
    val itemCode: String,
    val itemName: String,
    val unitName: String,
    val imageUrl: String?,
    /** What the company's records say is at this shop. */
    val placedQty: Int,
    /** Null until the rep checks it on this visit. */
    val countedQty: Int? = null,
    val condition: PosmCondition? = null,
    val remark: String? = null,
    val suggestion: String? = null,
    val photoCount: Int = 0,
) {
    /** Checked on this visit. A condition without a count cannot be stored. */
    val isChecked: Boolean get() = condition != null

    /**
     * Found fewer than the records claim. Advisory: a shortfall is a question for
     * the rep to answer in the note, not a verdict the app can reach on its own —
     * an asset may have been recalled without the paperwork catching up.
     */
    fun isShort(counted: Int): Boolean = counted < placedQty
}

/** What the outlet has asked for, and how far the request has got. */
data class PosmRegistration(
    val programId: String,
    val programCode: String,
    val programName: String,
    val itemId: String,
    val itemCode: String,
    val itemName: String,
    val unitName: String,
    val registeredQty: Int,
    val approvedQty: Int,
    val deliveredQty: Int,
    /** The legacy's H / C / D: pending, approved, rejected. */
    val status: String,
    val registeredAt: String?,
) {
    val isPending: Boolean get() = status == "pending"
    val isRejected: Boolean get() = status == "rejected"

    /**
     * Approved but not yet in the shop — the number the rep chases. Floored at
     * zero so a back-office over-delivery reads as "nothing outstanding" rather
     * than as a negative debt.
     */
    val awaitingDelivery: Int get() = (approvedQty - deliveredQty).coerceAtLeast(0)
}

/** One photo of an asset, on the device until the check is submitted. */
data class PosmPhoto(
    val localPath: String,
    val takenAtEpochMs: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    val sizeBytes: Long = 0,
)

/**
 * The check the rep is filling in for one asset.
 *
 * Both the count and the condition are required, for the reason the legacy's own
 * validation requires them: a row saying an asset was inspected and nothing about
 * how many there were or what state they were in records the visit, not the asset.
 */
data class DraftPosmCheck(
    val visitId: String,
    val customerId: String,
    val item: PosmAtCustomer,
    val countedQty: Int? = null,
    val condition: PosmCondition? = null,
    val remark: String = "",
    val suggestion: String = "",
    val photos: List<PosmPhoto> = emptyList(),
    /** From the step's `photo_min`, the legacy's POSM_IMAGE_REQUIRED. */
    val photoMin: Int = 1,
    /** From the step's `photo_max`, the legacy's POSM_Image. */
    val photoMax: Int = 4,
) {
    val photoCount: Int get() = photos.size

    val photosStillNeeded: Int get() = (photoMin - photoCount).coerceAtLeast(0)

    /** False once the ceiling is reached, which is when the camera stops offering. */
    val canAddPhoto: Boolean get() = photoCount < photoMax

    val canSubmit: Boolean
        get() = photosStillNeeded == 0 && countedQty != null && condition != null

    /** Total bytes queued for upload, which is what the rep is waiting on. */
    val totalSizeBytes: Long get() = photos.sumOf { it.sizeBytes }

    /** Ignored once the ceiling is reached, so no path can slip past it. */
    fun withPhoto(photo: PosmPhoto): DraftPosmCheck =
        if (canAddPhoto) copy(photos = photos + photo) else this

    fun withoutPhoto(localPath: String): DraftPosmCheck =
        copy(photos = photos.filterNot { it.localPath == localPath })
}

// -----------------------------------------------------------------------------
// Đăng ký, giao và thu hồi
// -----------------------------------------------------------------------------

/** One asset the outlet may be signed up for, with where it already stands. */
data class PosmCatalogueEntry(
    val programId: String,
    val programCode: String,
    val programName: String,
    val itemId: String,
    val itemCode: String,
    val itemName: String,
    val unitName: String,
    val imageUrl: String?,
    /** How many one outlet may hold. Zero means no ceiling. */
    val maxPerCustomer: Int,
    val registeredQty: Int,
    val approvedQty: Int,
    val deliveredQty: Int,
    /** What the outlet holds now, which a ceiling has to count against. */
    val placedQty: Int,
    /** Null when the outlet has never asked for this one. */
    val registrationStatus: String?,
) {
    val isPending: Boolean get() = registrationStatus == "pending"
    val isApproved: Boolean get() = registrationStatus == "approved"
    val isRejected: Boolean get() = registrationStatus == "rejected"

    /**
     * Whether the rep may put in for it at all.
     *
     * A pending request may be restated — the server updates it rather than
     * filing a second — but one head office has already ruled on may not: an
     * approval would be quietly rewritten and a refusal quietly revived.
     */
    val canRegister: Boolean get() = registrationStatus == null || isPending

    /** The most that may be asked for, given the ceiling. Zero means unlimited. */
    val ceiling: Int get() = maxPerCustomer
}

/** One line of a delivery or a recall. */
data class PosmMovementLine(
    val programId: String,
    val itemId: String,
    val itemName: String,
    val unitName: String,
    /** The most this line may move: what is left to deliver, or what is held. */
    val available: Int,
    val qty: Int = 0,
)

/** Which way the assets are going. The legacy's order types IN and IR. */
enum class PosmMovementKind(val wireValue: String, val label: String) {
    DELIVERY("delivery", "Giao POSM"),
    RECALL("recall", "Thu hồi POSM"),
}

/**
 * A handover being filled in.
 *
 * At least one photograph, always: a rep saying they handed over a fridge is an
 * assertion, and the legacy refuses the same submission for the same reason
 * (`posm_image_mess_required`).
 */
data class DraftPosmMovement(
    val visitId: String,
    val kind: PosmMovementKind,
    val lines: List<PosmMovementLine> = emptyList(),
    val note: String = "",
    val photos: List<PosmPhoto> = emptyList(),
    val photoMin: Int = 1,
    val photoMax: Int = 4,
) {
    val photoCount: Int get() = photos.size

    val photosStillNeeded: Int get() = (photoMin - photoCount).coerceAtLeast(0)

    val canAddPhoto: Boolean get() = photoCount < photoMax

    /** Lines the rep actually put a number against. */
    val movingLines: List<PosmMovementLine> get() = lines.filter { it.qty > 0 }

    val totalQty: Int get() = movingLines.sumOf { it.qty }

    val canSubmit: Boolean get() = movingLines.isNotEmpty() && photosStillNeeded == 0

    /** Clamped to what is available, so no line can ask for more than exists. */
    fun withQty(itemId: String, programId: String, qty: Int): DraftPosmMovement =
        copy(
            lines = lines.map { line ->
                if (line.itemId == itemId && line.programId == programId) {
                    line.copy(qty = qty.coerceIn(0, line.available))
                } else {
                    line
                }
            },
        )

    /** Ignored once the ceiling is reached, so no path can slip past it. */
    fun withPhoto(photo: PosmPhoto): DraftPosmMovement =
        if (canAddPhoto) copy(photos = photos + photo) else this

    fun withoutPhoto(localPath: String): DraftPosmMovement =
        copy(photos = photos.filterNot { it.localPath == localPath })
}

/** One line of a registration being put in. */
data class PosmRegistrationLine(
    val entry: PosmCatalogueEntry,
    val qty: Int = 0,
)

/**
 * A request being put in for an outlet.
 *
 * Nothing here carries an approved quantity or a status: those are head office's,
 * and the server writes them itself whatever the payload says.
 */
data class DraftPosmRegistration(
    val visitId: String,
    val lines: List<PosmRegistrationLine> = emptyList(),
    val reason: String = "",
) {
    val askedLines: List<PosmRegistrationLine> get() = lines.filter { it.qty > 0 }

    val canSubmit: Boolean get() = askedLines.isNotEmpty()

    /**
     * Clamped to the programme's ceiling, counting what the outlet already holds:
     * a shop allowed two racks that already has one may ask for one more.
     */
    fun withQty(itemId: String, programId: String, qty: Int): DraftPosmRegistration =
        copy(
            lines = lines.map { line ->
                if (line.entry.itemId == itemId && line.entry.programId == programId) {
                    val ceiling = line.entry.ceiling
                    val room = if (ceiling == 0) {
                        Int.MAX_VALUE
                    } else {
                        (ceiling - line.entry.placedQty).coerceAtLeast(0)
                    }
                    line.copy(qty = qty.coerceIn(0, room))
                } else {
                    line
                }
            },
        )
}
