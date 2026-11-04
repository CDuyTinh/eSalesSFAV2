package com.tinhcd.myesalessfa.core.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Photos a rep has taken, held on the device until they reach storage.
 *
 * Files live in app-private storage, so nothing appears in the gallery and nothing
 * needs the media permissions. They survive process death, which they have to: the
 * rep may photograph a shelf, leave the screen, and come back to submit, and the
 * bytes have to still be there when the audit is sent.
 */
@Singleton
class PhotoStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val dir: File
        get() = File(context.filesDir, "visit-photos").apply { mkdirs() }

    /**
     * An empty file plus the uri a camera app may write into.
     *
     * The camera is a separate app, so it cannot be handed a raw path — the uri
     * comes from a FileProvider, and taking the picture grants write permission to
     * that one file and nothing else.
     */
    fun newTarget(): PhotoTarget {
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.photos",
            file,
        )
        return PhotoTarget(path = file.absolutePath, uri = uri)
    }

    /** A fresh file path for a camera running inside the app, which needs no uri. */
    fun newFile(): String = File(dir, "${UUID.randomUUID()}.jpg").absolutePath

    /**
     * The check-in photo as the legacy builds it: the shop front, with the rep's
     * selfie set into its bottom-left corner at a quarter of the width, and the
     * [stamp] lines written across the top. One file, so the record carries both
     * faces of the visit and the facts it was taken under, and no viewer can show
     * one without the other.
     *
     * Both inputs are expected already [compress]ed, so their pixels are upright.
     * Returns the new file's path, or null when the shop photo cannot be read.
     */
    suspend fun composeCheckIn(shopPath: String, selfiePath: String?, stamp: List<String>): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val shop = BitmapFactory.decodeFile(shopPath) ?: return@runCatching null
                val out = shop.copy(Bitmap.Config.ARGB_8888, true)
                shop.recycle()
                val canvas = Canvas(out)
                val unit = out.width / 100f

                selfiePath?.let(BitmapFactory::decodeFile)?.let { selfie ->
                    val w = out.width / 4f
                    val h = w * selfie.height / selfie.width
                    val margin = unit * 2
                    val frame = RectF(margin, out.height - margin - h, margin + w, out.height - margin)
                    val border = unit * 0.6f
                    canvas.drawRect(
                        RectF(frame.left - border, frame.top - border, frame.right + border, frame.bottom + border),
                        Paint().apply { color = Color.WHITE },
                    )
                    canvas.drawBitmap(selfie, null, frame, Paint(Paint.FILTER_BITMAP_FLAG))
                    selfie.recycle()
                }

                if (stamp.isNotEmpty()) {
                    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        textSize = unit * 2.8f
                    }
                    val line = text.fontSpacing
                    val pad = unit * 1.5f
                    val width = stamp.maxOf(text::measureText) + pad * 2
                    canvas.drawRect(
                        0f, 0f, minOf(width, out.width.toFloat()), line * stamp.size + pad * 2,
                        Paint().apply { color = Color.argb(120, 0, 0, 0) },
                    )
                    stamp.forEachIndexed { i, s -> canvas.drawText(s, pad, pad + line * (i + 1) - text.descent(), text) }
                }

                val file = File(newFile())
                FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                out.recycle()
                file.absolutePath
            }.getOrNull()
        }

    fun exists(path: String): Boolean = File(path).exists()

    fun sizeOf(path: String): Long = File(path).length()

    fun bytes(path: String): ByteArray = File(path).readBytes()

    /** Only ever called for a photo the rep removed before submitting. */
    fun delete(path: String) {
        runCatching { File(path).delete() }
    }

    /**
     * Downscales and re-encodes in place, returning the resulting size in bytes.
     *
     * A phone camera produces several megabytes. The rep is standing in a shop on a
     * connection measured in tens of kilobytes per second, so this is the difference
     * between an audit that arrives and one that is still uploading when they drive
     * away. [MAX_EDGE] keeps product facings legible — the whole point of a display
     * photo — while cutting the file to a few hundred kilobytes.
     *
     * Rotation is applied to the pixels rather than left in EXIF, because the
     * re-encode drops the metadata and a sideways shelf photo is no evidence at all.
     *
     * Failure is not fatal: the original file is left as it is and uploaded at full
     * size. Slow beats lost.
     */
    suspend fun compress(path: String): Long = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext 0L

        runCatching {
            val rotation = rotationOf(file)

            // Two passes: measure first, then decode subsampled, so a large photo
            // never has to exist in memory at full resolution.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }
            val decoded = BitmapFactory.decodeFile(path, options)
                ?: return@runCatching file.length()

            val scaled = scaleToMaxEdge(decoded)
            val oriented = if (rotation == 0f) scaled else rotate(scaled, rotation)

            FileOutputStream(file).use { out ->
                oriented.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            oriented.recycle()

            file.length()
        }.getOrElse { file.length() }
    }

    private fun rotationOf(file: File): Float =
        runCatching {
            when (
                ExifInterface(file).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)

    /** Largest power-of-two subsample that still leaves the image above [MAX_EDGE]. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= MAX_EDGE) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleToMaxEdge(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_EDGE) return source

        val ratio = MAX_EDGE.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun rotate(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(
            source, 0, 0, source.width, source.height, matrix, true,
        )
        if (rotated !== source) source.recycle()
        return rotated
    }

    private companion object {
        /**
         * Long edge in pixels. 1600 keeps a shelf's product facings readable; 1024
         * would halve the upload again but starts losing the labels that make the
         * photo worth taking.
         */
        const val MAX_EDGE = 1600

        const val JPEG_QUALITY = 80
    }
}

/** A file to be written and the uri a camera app is allowed to write it through. */
data class PhotoTarget(
    val path: String,
    val uri: Uri,
)
