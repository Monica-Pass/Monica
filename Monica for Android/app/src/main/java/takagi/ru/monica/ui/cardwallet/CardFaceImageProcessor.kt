package takagi.ru.monica.ui.cardwallet

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

/** Reads a document once, then normalizes it before encrypted attachment persistence. */
object CardFaceImageProcessor {
    const val CARD_ASPECT_RATIO = 85.60f / 53.98f
    private const val MAX_SOURCE_BYTES = 25L * 1024L * 1024L
    private const val MAX_SOURCE_DIMENSION = 32_768
    private const val OUTPUT_WIDTH = 1280
    private const val JPEG_QUALITY = 88

    enum class Failure { UNREADABLE, TOO_LARGE, UNSUPPORTED, DECODE_FAILED }
    class ImportException(val reason: Failure) : Exception(reason.name)

    data class Prepared(val bytes: ByteArray, val preview: Bitmap)

    suspend fun prepare(context: Context, uri: Uri): Result<Prepared> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.applicationContext.contentResolver
            val sourceBytes = openSourceStream(resolver, uri)?.use {
                readBoundedBytes(it, MAX_SOURCE_BYTES) ?: throw ImportException(Failure.TOO_LARGE)
            } ?: throw ImportException(Failure.UNREADABLE)
            try {
                Result.success(normalize(sourceBytes))
            } finally {
                sourceBytes.fill(0)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ImportException) {
            Result.failure(error)
        } catch (_: SecurityException) {
            Result.failure(ImportException(Failure.UNREADABLE))
        } catch (_: OutOfMemoryError) {
            Result.failure(ImportException(Failure.DECODE_FAILED))
        } catch (_: Exception) {
            Result.failure(ImportException(Failure.DECODE_FAILED))
        }
    }

    private fun normalize(sourceBytes: ByteArray): Prepared {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_SOURCE_DIMENSION || bounds.outHeight !in 1..MAX_SOURCE_DIMENSION) {
            throw ImportException(Failure.DECODE_FAILED)
        }
        if (bounds.outMimeType !in setOf("image/jpeg", "image/png", "image/webp")) {
            throw ImportException(Failure.UNSUPPORTED)
        }
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder also applies EXIF orientation, including mirrored camera images.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(sourceBytes))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(calculateSampleSize(info.size.width, info.size.height, OUTPUT_WIDTH * 2))
            }
        } else {
            decodeLegacy(sourceBytes, bounds)
        }
        try {
            val crop = centerCropSize(decoded.width, decoded.height, CARD_ASPECT_RATIO)
            val left = ((decoded.width - crop.first) / 2).coerceAtLeast(0)
            val top = ((decoded.height - crop.second) / 2).coerceAtLeast(0)
            val cropped = Bitmap.createBitmap(decoded, left, top, crop.first, crop.second)
            try {
                val width = minOf(OUTPUT_WIDTH, cropped.width).coerceAtLeast(1)
                val height = (width / CARD_ASPECT_RATIO).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(cropped, width, height, true)
                try {
                    // A JPEG cannot retain transparency; use a predictable white background.
                    val normalized = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(normalized).apply {
                            drawColor(Color.WHITE)
                            drawBitmap(scaled, 0f, 0f, null)
                        }
                        val output = ByteArrayOutputStream()
                        if (!normalized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                            throw ImportException(Failure.DECODE_FAILED)
                        }
                        val bytes = output.toByteArray()
                        if (bytes.isEmpty()) throw ImportException(Failure.DECODE_FAILED)
                        val preview = normalized.copy(Bitmap.Config.ARGB_8888, false)
                            ?: throw ImportException(Failure.DECODE_FAILED)
                        return Prepared(bytes, preview)
                    } finally {
                        normalized.recycle()
                    }
                } finally {
                    if (scaled !== cropped) scaled.recycle()
                }
            } finally {
                if (cropped !== decoded) cropped.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    private fun decodeLegacy(sourceBytes: ByteArray, bounds: BitmapFactory.Options): Bitmap {
        val decoded = BitmapFactory.decodeByteArray(
            sourceBytes, 0, sourceBytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, OUTPUT_WIDTH * 2)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: throw ImportException(Failure.DECODE_FAILED)
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(sourceBytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        if (matrix.isIdentity) return decoded
        return try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } finally {
            decoded.recycle()
        }
    }

    /** Provider sizes can be unknown; enforce the limit using the bytes actually read. */
    internal fun readBoundedBytes(input: InputStream, maxBytes: Long): ByteArray? {
        if (maxBytes <= 0L) return null
        val output = ByteArrayOutputStream(minOf(maxBytes, 64L * 1024L).toInt())
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun openSourceStream(resolver: ContentResolver, uri: Uri): InputStream? {
        // Some document providers only expose typed assets, or cannot expose a seekable descriptor.
        return runCatching { resolver.openInputStream(uri) }.getOrNull()
            ?: runCatching { resolver.openTypedAssetFileDescriptor(uri, "image/*", null)?.createInputStream() }.getOrNull()
            ?: runCatching {
                resolver.openFileDescriptor(uri, "r")?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
            }.getOrNull()
    }

    internal fun centerCropSize(width: Int, height: Int, aspectRatio: Float): Pair<Int, Int> {
        require(width > 0 && height > 0 && aspectRatio > 0f)
        return if (width.toFloat() / height > aspectRatio) {
            (height * aspectRatio).toInt().coerceIn(1, width) to height
        } else {
            width to (width / aspectRatio).toInt().coerceIn(1, height)
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width / sample > maxDimension || height / sample > maxDimension) sample *= 2
        return sample
    }
}
