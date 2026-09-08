package com.tinhcd.myesalessfa.domain.model

/** One photograph of what the customer is talking about, before upload. */
data class FeedbackPhoto(
    val localPath: String,
    val takenAtEpochMs: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    val sizeBytes: Long = 0,
)

/**
 * One recording. Several make up a feedback, because a customer mid-sentence at the
 * per-clip cap should carry on into the next clip rather than be cut off.
 */
data class FeedbackRecording(
    val localPath: String,
    val seconds: Int,
    val recordedAtEpochMs: Long,
    val sizeBytes: Long = 0,
)

/**
 * What the customer said, on its way to the server.
 *
 * The `feedback` step used to be a plain note, which meant whatever the rep typed
 * stopped being findable the moment it was saved. Four things change that.
 *
 * A coded topic, so a chiller request and a quality complaint can be routed to
 * different people. The legacy has nothing of the kind, and unclassified feedback
 * is unroutable.
 *
 * Photos, which the legacy carries and this did not: half of what a rep is told at
 * the counter is about something they are standing in front of, and a report with
 * no picture of the split case is an assertion.
 *
 * Recordings, plural, as `OM_FeedBackCustomerRecords` holds them — a rep in a loud
 * shop cannot type Vietnamese quickly, and a clip that stops at the cap loses the
 * rest of the sentence.
 *
 * And a note that is still required. The legacy's own validation checks the photo
 * count and nothing else, so it accepts a submission with no words in it at all;
 * nobody can search or route a sound file, and one readable line is what makes the
 * photos and the audio findable later.
 *
 * The topic stays optional by design. A market that has configured no topics still
 * needs the step to work, and a rep must never be unable to report something because
 * head office has not classified it yet.
 */
data class DraftFeedback(
    val visitId: String,
    val topicId: String? = null,
    val note: String = "",
    val photos: List<FeedbackPhoto> = emptyList(),
    val recordings: List<FeedbackRecording> = emptyList(),
    /**
     * From the step's own config. `submit_feedback` reads the same keys, so the
     * client refuses exactly what the server would refuse rather than discovering
     * the limits from a rejection.
     */
    val noteMinLength: Int = 1,
    /** FEEDBACK_CUSTOMER_IMAGE_REQUIRED and FEEDBACK_CUSTOMER_IMAGE. */
    val photoMin: Int = 0,
    val photoMax: Int = 5,
    /** SALES_RECORD_TIME_FILE: how long one clip may run. */
    val audioMaxSeconds: Int = 300,
    /** SALES_RECORD_MAX_REALTIME: how long every clip may run together. */
    val audioTotalSeconds: Int = 900,
    /** From the step's `allow_audio`. False hides recording entirely. */
    val allowAudio: Boolean = false,
) {
    val trimmedNote: String get() = note.trim()

    /**
     * Characters still needed before the note is long enough. Zero once satisfied, so
     * the screen can show a countdown rather than only refusing at the end.
     */
    val charsStillNeeded: Int
        get() = (noteMinLength - trimmedNote.length).coerceAtLeast(0)

    val photosStillNeeded: Int get() = (photoMin - photos.size).coerceAtLeast(0)

    /** False once the ceiling is reached, which is when the camera stops offering. */
    val canAddPhoto: Boolean get() = photos.size < photoMax

    val hasAudio: Boolean get() = recordings.isNotEmpty()

    val totalAudioSeconds: Int get() = recordings.sumOf { it.seconds }

    /** What is left of the budget for the whole feedback, never below zero. */
    val audioSecondsLeft: Int
        get() = (audioTotalSeconds - totalAudioSeconds).coerceAtLeast(0)

    /**
     * How long the next clip may run: the per-clip cap, or whatever is left of the
     * total if that is less. Zero means the budget is spent and the button is off.
     */
    val nextClipSeconds: Int get() = minOf(audioMaxSeconds, audioSecondsLeft)

    val canRecord: Boolean get() = allowAudio && nextClipSeconds > 0

    /** Total bytes queued for upload, which is what the rep waits on. */
    val totalSizeBytes: Long
        get() = photos.sumOf { it.sizeBytes } + recordings.sumOf { it.sizeBytes }

    /**
     * Audio and photos do not substitute for the note.
     *
     * Tempting to let a recording stand alone — it is the richer record — but nobody
     * at head office can search, sort or route a sound file, and a topic plus one
     * readable line is what makes the media findable at all. The recording is
     * evidence attached to a written summary, the same relationship the display audit
     * has between its photo and its note.
     */
    val canSubmit: Boolean get() = charsStillNeeded == 0 && photosStillNeeded == 0

    /** Ignored once the ceiling is reached, so no path can slip past it. */
    fun withPhoto(photo: FeedbackPhoto): DraftFeedback =
        if (canAddPhoto) copy(photos = photos + photo) else this

    fun withoutPhoto(localPath: String): DraftFeedback =
        copy(photos = photos.filterNot { it.localPath == localPath })

    /**
     * Ignored when there is no budget left for it, and clipped to what remains — a
     * recorder that overran the ceiling must not be able to book more than the step
     * allows just because the file on disk is longer.
     */
    fun withRecording(recording: FeedbackRecording): DraftFeedback {
        val room = nextClipSeconds
        if (room <= 0 || recording.seconds < 1) return this
        return copy(
            recordings = recordings + recording.copy(seconds = minOf(recording.seconds, room)),
        )
    }

    fun withoutRecording(localPath: String): DraftFeedback =
        copy(recordings = recordings.filterNot { it.localPath == localPath })
}
