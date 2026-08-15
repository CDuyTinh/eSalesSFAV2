package com.tinhcd.myesalessfa.core.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Voice notes a rep has recorded, held on the device until they reach storage.
 *
 * Same arrangement as [com.tinhcd.myesalessfa.core.photo.PhotoStore] and for the same
 * reasons: app-private files, so nothing lands in the phone's media library and no
 * media permission is needed, and they survive process death because the rep may
 * record, navigate away, and come back to submit.
 *
 * AAC in an MP4 container. It is the one encoder every Android version here has, it
 * plays anywhere without a codec argument, and speech at [BIT_RATE] costs about
 * 8 KB/s — thirty seconds is a quarter of a compressed photo.
 */
@Singleton
class VoiceRecorder @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val dir: File
        get() = File(context.filesDir, "visit-audio").apply { mkdirs() }

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var startedAt = 0L
    private var currentPath: String? = null

    val isRecording: Boolean get() = recorder != null

    /**
     * Starts recording into a new file and returns its path.
     *
     * Throws if the microphone cannot be opened — another app holding it, or a
     * permission that was refused. The caller reports that rather than leaving a
     * button that silently does nothing.
     */
    fun start(): String {
        stop()

        val file = File(dir, "${UUID.randomUUID()}.m4a")
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        created.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(BIT_RATE)
            setAudioSamplingRate(SAMPLE_RATE)
            // A hard ceiling rather than trusting the rep to press stop. A recorder
            // left running while the phone goes in a pocket would otherwise fill the
            // disk and produce a file nobody will ever listen to.
            setMaxDuration(MAX_MILLIS)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        recorder = created
        startedAt = SystemClock.elapsedRealtime()
        currentPath = file.absolutePath
        return file.absolutePath
    }

    /**
     * Stops recording and returns how many whole seconds were captured, or 0 if
     * nothing was running.
     *
     * A recorder that fails on stop is still released: leaving the microphone held
     * would break the next attempt as well as every other app that wants it.
     */
    fun stop(): Int {
        val active = recorder ?: return 0
        recorder = null

        val elapsed = SystemClock.elapsedRealtime() - startedAt
        runCatching { active.stop() }
        runCatching { active.release() }

        // Below a second there is nothing to hear, and the encoder may not even have
        // written a playable header. Treated as no recording at all.
        val seconds = (elapsed / 1000L).toInt()
        if (seconds < 1) {
            currentPath?.let { delete(it) }
            currentPath = null
            return 0
        }
        return seconds
    }

    /** Whole seconds recorded so far, for a live counter. */
    fun elapsedSeconds(): Int =
        if (recorder == null) 0 else ((SystemClock.elapsedRealtime() - startedAt) / 1000L).toInt()

    /** Plays a recording back so the rep can hear what they are about to send. */
    fun play(path: String, onFinished: () -> Unit = {}) {
        stopPlayback()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener {
                    stopPlayback()
                    onFinished()
                }
                prepare()
                start()
            }
        }.onFailure { onFinished() }
    }

    fun stopPlayback() {
        player?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        player = null
    }

    fun delete(path: String) {
        runCatching { File(path).delete() }
    }

    fun sizeOf(path: String): Long = File(path).length()

    private companion object {
        /** Speech, not music. 32 kbps AAC is clear enough to transcribe from. */
        const val BIT_RATE = 32_000
        const val SAMPLE_RATE = 22_050

        /**
         * Two minutes. Long enough for anything a shopkeeper says in passing, short
         * enough that the upload still finishes on a bad connection.
         */
        const val MAX_MILLIS = 120_000
    }
}
