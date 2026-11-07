package com.imagecompressor.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.imagecompressor.app.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class OutcomeStatus {
    /** Final size is at or below the target. */
    TARGET_MET,

    /** The original JPEG was already under the target, so it was kept untouched. */
    ALREADY_SMALL,

    /** The target could not be reached without excessive quality loss; this is the closest result. */
    CLOSEST
}

data class CompressOutcome(
    val file: File,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    /** JPEG quality used (0 when the original file was kept as is). */
    val quality: Int,
    val status: OutcomeStatus,
    val message: String
)

class UnsupportedImageException(message: String) : IOException(message)

/**
 * Target-size JPEG compression.
 *
 * Strategy (all sizes are MEASURED by really encoding, never estimated):
 *  1. Decode with a power-of-two sample size so very large photos never blow the heap, apply EXIF
 *     orientation and flatten transparency onto white.
 *  2. At the current resolution encode at the minimum acceptable quality. If even that is too big,
 *     shrink the resolution (jump computed from the size ratio, aspect ratio preserved) and retry.
 *  3. Once a resolution fits, binary-search the highest JPEG quality whose measured size is <= target.
 *  4. If that quality is low, shrink a little and retry so we trade resolution for quality instead of
 *     producing blocky images.
 *  5. If nothing fits above the minimum resolution, return the smallest result and flag it as CLOSEST.
 */
object ImageCompressor {
    const val MIN_QUALITY = 40
    const val MAX_QUALITY = 95
    private const val GOOD_QUALITY = 65
    private const val MIN_SIDE = 320
    private const val MAX_DECODE_SIDE = 4096
    private const val CLOSE_ENOUGH = 0.92

    private class Attempt(val bytes: ByteArray, val quality: Int, val width: Int, val height: Int) {
        val size: Int get() = bytes.size
    }

    suspend fun compress(
        context: Context,
        uri: Uri,
        originalSize: Long,
        mime: String?,
        targetBytes: Long,
        outDir: File,
        onProgress: (Float) -> Unit
    ): CompressOutcome = withContext(Dispatchers.Default) {
        outDir.mkdirs()
        val outFile = File(outDir, "${UUID.randomUUID()}.jpg")
        onProgress(0.05f)

        // A JPEG that already fits is kept byte-for-byte (re-encoding could only make it worse).
        if (isJpeg(mime) && originalSize in 1..targetBytes) {
            val (w, h) = readBounds(context, uri)
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open image")
            input.use { ins -> outFile.outputStream().use { ins.copyTo(it) } }
            onProgress(1f)
            return@withContext CompressOutcome(
                outFile, outFile.length(), w, h, 0, OutcomeStatus.ALREADY_SMALL,
                "Already under the target size, so it was kept as is."
            )
        }

        val src = decodeOriented(context, uri, MAX_DECODE_SIDE)
        try {
            onProgress(0.2f)
            var steps = 0
            val bump = {
                steps++
                onProgress(min(0.95f, 0.2f + steps * 0.06f))
            }

            val longest = max(src.width, src.height)
            var scale = 1.0
            var best: Attempt? = null
            var smallest: Attempt? = null
            var guard = 0

            while (guard++ < 14) {
                ensureActive()
                val w = max(1, (src.width * scale).roundToInt())
                val h = max(1, (src.height * scale).roundToInt())
                val bmp = if (w == src.width && h == src.height) src
                else Bitmap.createScaledBitmap(src, w, h, true)
                try {
                    val low = encode(bmp, MIN_QUALITY)
                    bump()
                    if (low.size > targetBytes) {
                        smallest = Attempt(low, MIN_QUALITY, w, h)
                        // JPEG size is roughly proportional to pixel count.
                        val ratio = sqrt(targetBytes.toDouble() / low.size) * 0.96
                        val next = scale * min(ratio, 0.9)
                        if (longest * next < MIN_SIDE) break
                        scale = next
                    } else {
                        val found = searchQuality(bmp, low, w, h, targetBytes) {
                            ensureActive()
                            bump()
                        }
                        best = found
                        if (found.quality >= GOOD_QUALITY) break
                        // Fits, but only at low quality: trade a little resolution for quality.
                        val next = scale * 0.85
                        if (longest * next < MIN_SIDE) break
                        scale = next
                    }
                } finally {
                    if (bmp !== src) bmp.recycle()
                }
            }

            val chosen = best ?: smallest ?: throw IOException("Could not encode image")
            outFile.writeBytes(chosen.bytes)
            onProgress(1f)

            val met = chosen.size <= targetBytes
            val resized = chosen.width != src.width || chosen.height != src.height
            val message = when {
                !met ->
                    "Couldn't reach ${formatSize(targetBytes)} without heavy quality loss. " +
                        "This is the closest result (${formatSize(chosen.size.toLong())})."
                chosen.quality >= MAX_QUALITY && chosen.size < targetBytes * 0.8 ->
                    "Best quality already fits under the target, so the file is smaller than requested."
                resized ->
                    "Resized to ${chosen.width}×${chosen.height} and compressed at quality ${chosen.quality}."
                else ->
                    "Compressed at quality ${chosen.quality}, original resolution kept."
            }
            CompressOutcome(
                outFile, chosen.size.toLong(), chosen.width, chosen.height, chosen.quality,
                if (met) OutcomeStatus.TARGET_MET else OutcomeStatus.CLOSEST, message
            )
        } finally {
            src.recycle()
        }
    }

    /** Highest quality in [MIN_QUALITY, MAX_QUALITY] whose real encoded size is <= target. */
    private fun searchQuality(
        bmp: Bitmap,
        atMin: ByteArray,
        w: Int,
        h: Int,
        target: Long,
        onStep: () -> Unit
    ): Attempt {
        val top = encode(bmp, MAX_QUALITY)
        onStep()
        if (top.size <= target) return Attempt(top, MAX_QUALITY, w, h)

        var best = Attempt(atMin, MIN_QUALITY, w, h)
        var lo = MIN_QUALITY
        var hi = MAX_QUALITY - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            val bytes = encode(bmp, mid)
            onStep()
            if (bytes.size <= target) {
                best = Attempt(bytes, mid, w, h)
                lo = mid
                if (bytes.size.toDouble() >= target * CLOSE_ENOUGH) break
            } else {
                hi = mid - 1
            }
        }
        return best
    }

    private fun encode(bmp: Bitmap, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream(64 * 1024)
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)) {
            throw IOException("JPEG encoding failed")
        }
        return bos.toByteArray()
    }

    private fun isJpeg(mime: String?) = mime == "image/jpeg" || mime == "image/jpg"

    private fun readBounds(context: Context, uri: Uri): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Cannot open image")
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            throw UnsupportedImageException("This file isn't a supported or readable image.")
        }
        return opts.outWidth to opts.outHeight
    }

    /**
     * Decodes [uri] so its longest side is at most [maxSide] (power-of-two sampling), applies the
     * EXIF orientation and flattens any transparency onto white. The original file is only read.
     */
    fun decodeOriented(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val (bw, bh) = readBounds(context, uri)
        var sample = 1
        while (max(bw, bh) / sample > maxSide) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: throw UnsupportedImageException("This file isn't a supported or readable image.")

        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
        }

        if (matrix.isIdentity && !decoded.hasAlpha()) return decoded

        val rect = RectF(0f, 0f, decoded.width.toFloat(), decoded.height.toFloat())
        matrix.mapRect(rect)
        matrix.postTranslate(-rect.left, -rect.top)
        val out = Bitmap.createBitmap(
            ceil(rect.width()).toInt(), ceil(rect.height()).toInt(), Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(decoded, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        decoded.recycle()
        return out
    }

    /** Decodes a (compressed) result file for on-screen preview. */
    fun decodeFileScaled(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }
}
