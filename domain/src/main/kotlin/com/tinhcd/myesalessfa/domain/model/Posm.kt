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
