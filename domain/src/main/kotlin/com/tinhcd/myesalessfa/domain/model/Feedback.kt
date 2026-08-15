package com.tinhcd.myesalessfa.domain.model

/**
 * What the customer said, on its way to the server.
 *
 * The `feedback` step used to be a plain note, which meant whatever the rep typed
 * stopped being findable the moment it was saved. Two things change that: a coded
 * topic, so a chiller request and a quality complaint can be routed to different
 * people, and optional audio, because a rep in a loud shop cannot type Vietnamese
 * quickly and thirty seconds of voice beats a rushed sentence.
 *
 * The topic is optional by design. A market that has configured no topics still
 * needs the step to work, and a rep must never be unable to report something because
 * head office has not classified it yet.
 */
data class DraftFeedback(
    val visitId: String,
    val topicId: String? = null,
    val note: String = "",
    /** Local file, before upload. Null when the rep recorded nothing. */
    val audioPath: String? = null,
    val audioSeconds: Int = 0,
    /**
     * From the step's own config. `submit_feedback` reads the same key, so the client
     * refuses exactly what the server would refuse rather than discovering the limit
     * from a rejection.
     */
    val noteMinLength: Int = 1,
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

    val hasAudio: Boolean get() = audioPath != null

    /**
     * Audio does not substitute for the note.
     *
     * Tempting to let a recording stand alone — it is the richer record — but nobody
     * at head office can search, sort or route a sound file, and a topic plus one
     * readable line is what makes the audio findable at all. The recording is
     * evidence attached to a written summary, the same relationship the display audit
     * has between its photo and its note.
     */
    val canSubmit: Boolean get() = charsStillNeeded == 0
}
