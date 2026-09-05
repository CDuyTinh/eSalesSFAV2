package com.tinhcd.myesalessfa.domain.model

/**
 * The display audit: photographic evidence of how the brand is displayed.
 *
 * The photo is the point. A note without a picture is an opinion; with a picture it
 * is something head office can act on. That is why the step's `photo_min` config is
 * enforced here as well as inside `submit_display_audit` — the client refuses to
 * submit what the server would reject, so the rep finds out while still standing in
 * front of the shelf rather than after the upload has failed somewhere else.
 */
data class AuditPhoto(
    /** Absolute path on the device. Uploaded, then replaced by a storage path. */
    val localPath: String,
    val takenAtEpochMs: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    val sizeBytes: Long = 0,
)

data class DraftDisplayAudit(
    val visitId: String,
    val customerId: String,
    val photos: List<AuditPhoto> = emptyList(),
    val note: String = "",
    /**
     * From the step's own `photo_min`. One rather than zero when the server has not
     * configured it: this step exists to produce a picture.
     */
    val photoMin: Int = 1,
    /**
     * From the step's `photo_max`, the legacy's `DISPLAY_IMAGE`, whose default is
     * six. A ceiling matters more than it looks: every photo is uploaded from a
     * shop doorway, and nothing about a display is better understood from the
     * fortieth picture of it.
     */
    val photoMax: Int = 6,
) {
    val photoCount: Int get() = photos.size

    /** Photos still needed before this can be submitted. */
    val photosStillNeeded: Int get() = (photoMin - photoCount).coerceAtLeast(0)

    /** False once the ceiling is reached, which is when the camera stops offering. */
    val canAddPhoto: Boolean get() = photoCount < photoMax

    val canSubmit: Boolean get() = photosStillNeeded == 0

    /** Total bytes queued for upload, which is what the rep is waiting on. */
    val totalSizeBytes: Long get() = photos.sumOf { it.sizeBytes }

    /** Ignored once the ceiling is reached, so no path can slip past it. */
    fun withPhoto(photo: AuditPhoto): DraftDisplayAudit =
        if (canAddPhoto) copy(photos = photos + photo) else this

    /**
     * Removes a photo the rep rejected. Matched on path because that is what
     * identifies a file on the device — two shots of the same shelf are different
     * photos and both may legitimately be kept.
     */
    fun withoutPhoto(localPath: String): DraftDisplayAudit =
        copy(photos = photos.filterNot { it.localPath == localPath })
}
