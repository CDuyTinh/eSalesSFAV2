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
    /**
     * The programme being scored, absent for the plain photo record a market with
     * no display programmes still gets.
     */
    val program: DisplayProgram? = null,
    /** FaceRemark: facings the rep counted on the shelf. */
    val countedFaces: Int? = null,
    /** Evaluate: the rep's own yes or no, deliberately not derived from the count. */
    val achieved: Boolean? = null,
) {
    val photoCount: Int get() = photos.size

    /** Photos still needed before this can be submitted. */
    val photosStillNeeded: Int get() = (photoMin - photoCount).coerceAtLeast(0)

    /** False once the ceiling is reached, which is when the camera stops offering. */
    val canAddPhoto: Boolean get() = photoCount < photoMax

    /**
     * A scored programme needs its answer as well as its pictures. Without the
     * verdict the row would say a display was inspected and nothing about whether
     * it passed, which is the one thing the programme exists to record.
     */
    val canSubmit: Boolean
        get() = photosStillNeeded == 0 &&
            (program == null || (countedFaces != null && achieved != null))

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

/**
 * One display programme this outlet is audited on today.
 *
 * The level's [requiredFaces] is the whole point of the step. Without it the rep
 * is photographing a shelf; with it they are checking a commitment — the outlet
 * signed up for so many facings, and either they are there or they are not.
 */
data class DisplayProgram(
    val programId: String,
    val programCode: String,
    val programName: String,
    val specification: String?,
    val levelId: String,
    val levelName: String,
    /** NumSurface: facings the registered level is worth. */
    val requiredFaces: Int,
    /** Bonus in dong, zero for a programme that pays in something else. */
    val bonusAmount: Long,
    /**
     * False for a programme open to every outlet on the route. The rep still
     * audits it; there is simply no signup behind it.
     */
    val registered: Boolean,
    /** 'pending' while head office has not ruled on the signup. Null when open. */
    val registrationStatus: String?,
    /** What this visit already recorded, absent until the rep scores it. */
    val countedFaces: Int? = null,
    val achieved: Boolean? = null,
    val photoCount: Int = 0,
) {
    /** Scored on this visit. The legacy's IsDone, per programme. */
    val isScored: Boolean get() = achieved != null

    val isPending: Boolean get() = registrationStatus == "pending"

    /**
     * Facings short of the level's target, or zero once it is met.
     *
     * Advisory only: [achieved] is the rep's own answer and is not derived from
     * it. A display can miss the count and still be built to specification, or
     * hit it with the wrong products — which is why the legacy dialog asks for
     * both and its authors left the derivation commented out.
     */
    fun shortfall(counted: Int): Int = (requiredFaces - counted).coerceAtLeast(0)
}
