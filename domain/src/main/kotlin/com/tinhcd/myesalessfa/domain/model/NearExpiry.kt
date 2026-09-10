package com.tinhcd.myesalessfa.domain.model

/**
 * Hàng cận date.
 *
 * Not the stock count wearing a different hat. The count answers "how many of
 * this product are on the shelf"; this answers "which batches of it are about to
 * go off, and how many of each" — a different question whose unit of record is a
 * lot, not a product.
 *
 * The two rules that look arbitrary until you know where they come from are both
 * the legacy form's: a lot number of exactly the configured length
 * (`input_lot_num_then_eght`), and no lot entered twice for one product
 * (`dup_lot_number`).
 */
data class NearExpiryLot(
    val productId: String,
    val productCode: String,
    val productName: String,
    val lotNo: String,
    /** ISO date. Held as text because it is only ever displayed and sent. */
    val expiryDate: String,
    val uomCode: String,
    val uomName: String,
    val qty: Int,
    val baseQty: Int,
) {
    /** Identifies a line without carrying the whole row. */
    val key: String get() = "$productId|$lotNo"
}

/** One photograph of the batch, on the device until the check is submitted. */
data class NearExpiryPhoto(
    val localPath: String,
    val takenAtEpochMs: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    val sizeBytes: Long = 0,
)

/**
 * The document the rep is filling in on one call.
 *
 * It finishes with a photograph *or* a reason for not taking one — evidence, or
 * an explanation of its absence, never neither. That is `alert_chosen_reason`
 * over there, and `submit_near_expiry_check` refuses the same shape here.
 */
data class DraftNearExpiry(
    val visitId: String,
    val lots: List<NearExpiryLot> = emptyList(),
    val note: String = "",
    val photos: List<NearExpiryPhoto> = emptyList(),
    /** Chosen only when there is no photograph. */
    val noPhotoReasonId: String? = null,
    /** From the step's config; the legacy hard-codes eight. */
    val lotNoLength: Int = 8,
    val photoMax: Int = 4,
) {
    val photoCount: Int get() = photos.size

    val canAddPhoto: Boolean get() = photoCount < photoMax

    val hasPhoto: Boolean get() = photos.isNotEmpty()

    /** Base units across every lot — what the document totals to. */
    val totalBaseQty: Int get() = lots.sumOf { it.baseQty }

    /** Lots grouped by product, in the order the rep added them. */
    val byProduct: Map<String, List<NearExpiryLot>>
        get() = lots.groupBy { it.productName }

    /**
     * A photograph makes the reason meaningless, so choosing one clears the
     * other. The server does the same on the way in rather than storing a
     * reason that contradicts the picture beside it.
     */
    val evidenceSettled: Boolean get() = hasPhoto || noPhotoReasonId != null

    val canSubmit: Boolean get() = lots.isNotEmpty() && evidenceSettled

    /** Whether a lot number is the shape the ERP prints on the case. */
    fun isLotNoValid(lotNo: String): Boolean = lotNo.trim().length == lotNoLength

    /** Whether this product already has that lot on the document. */
    fun hasLot(productId: String, lotNo: String): Boolean =
        lots.any { it.productId == productId && it.lotNo.equals(lotNo.trim(), ignoreCase = true) }

    /**
     * Adds a lot, refusing a wrong-length number or one already on the document.
     * Returns the draft unchanged when either applies, so the caller can compare
     * and report rather than being handed a silent no-op it cannot see.
     */
    fun withLot(lot: NearExpiryLot): DraftNearExpiry = when {
        !isLotNoValid(lot.lotNo) -> this
        hasLot(lot.productId, lot.lotNo) -> this
        lot.qty < 1 || lot.baseQty < 1 -> this
        else -> copy(lots = lots + lot.copy(lotNo = lot.lotNo.trim()))
    }

    fun withoutLot(productId: String, lotNo: String): DraftNearExpiry =
        copy(lots = lots.filterNot { it.productId == productId && it.lotNo == lotNo })

    /** Ignored once the ceiling is reached, so no path can slip past it. */
    fun withPhoto(photo: NearExpiryPhoto): DraftNearExpiry =
        if (canAddPhoto) copy(photos = photos + photo, noPhotoReasonId = null) else this

    fun withoutPhoto(localPath: String): DraftNearExpiry =
        copy(photos = photos.filterNot { it.localPath == localPath })

    /** Picking a reason is only meaningful while there is no photograph. */
    fun withNoPhotoReason(reasonId: String?): DraftNearExpiry =
        if (hasPhoto) this else copy(noPhotoReasonId = reasonId)
}
