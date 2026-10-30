@file:Suppress("unused")

package app.keemobile.kotpass.models

import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.sha256
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

sealed class BinaryData(val hash: ByteString) {
    abstract val memoryProtection: Boolean
    abstract val rawContent: ByteArray

    abstract fun getContent(): ByteArray

    abstract fun inputStream(): InputStream

    class Uncompressed(
        override val memoryProtection: Boolean,
        override val rawContent: ByteArray
    ) : BinaryData(rawContent.sha256().toByteString()) {
        override fun getContent(): ByteArray = rawContent

        override fun inputStream(): InputStream = rawContent.inputStream()

        fun toCompressed(): Compressed = try {
            val outputStream = ByteArrayOutputStream()
            GZIPOutputStream(outputStream).use { it.write(getContent()) }
            Compressed(memoryProtection, outputStream.toByteArray())
        } catch (_: IOException) {
            throw FormatError.FailedCompression("Failed to gzip binary data.")
        }
    }

    class Compressed(
        override val memoryProtection: Boolean,
        override val rawContent: ByteArray
    ) : BinaryData(rawContent.sha256().toByteString()) {
        override fun getContent(): ByteArray = try {
            inputStream().use(InputStream::readBytes)
        } catch (_: IOException) {
            throw FormatError.FailedCompression(
                "Failed to read from compressed binary data stream."
            )
        }

        override fun inputStream(): InputStream = try {
            GZIPInputStream(rawContent.inputStream())
        } catch (_: IOException) {
            throw FormatError.FailedCompression(
                "Failed to create compressed binary data stream."
            )
        }
    }
}
