package takagi.ru.monica.rustcore

import java.io.ByteArrayOutputStream
import takagi.ru.monica.data.PasswordEntry

/**
 * Single-batch JNI facade for secret-free password-list metadata.
 *
 * The native boundary deliberately excludes PasswordEntry.password and every other
 * sensitive credential value. If the native library is unavailable or rejects a
 * batch, callers get null and must keep the Kotlin/Room fallback path.
 */
object RustPasswordListCore {
    private const val METADATA_BATCH_MAGIC = 0x3146504D // "MPF1" as little-endian u32.

    @Volatile
    private var loadAttempted = false

    @Volatile
    private var nativeAvailable = false

    private fun ensureLoaded(): Boolean {
        if (!loadAttempted) {
            synchronized(this) {
                if (!loadAttempted) {
                    nativeAvailable = runCatching {
                        System.loadLibrary("monica_rust_jni")
                        nativeSelfTest()
                    }.getOrDefault(false)
                    loadAttempted = true
                }
            }
        }
        return nativeAvailable
    }

    fun filterEntries(entries: List<PasswordEntry>, query: String): List<PasswordEntry>? {
        if (entries.isEmpty()) return emptyList()

        // The initial password screen normally has no search term. Returning the
        // existing list here deliberately avoids loading the JNI library during
        // cold start; native code is loaded only once the user actually searches.
        if (query.isBlank()) return entries
        if (!ensureLoaded()) return null

        // Pack all searchable, non-secret metadata into one versioned UTF-8 frame.
        // JNI now crosses the Java/native boundary once for the whole list instead
        // of retrieving five Java String objects for every password row.
        val metadata = encodeMetadata(entries)
        val selectedIndices = runCatching {
            nativeFilterIndices(metadata = metadata, query = query)
        }.getOrNull() ?: return null

        return buildList(selectedIndices.size) {
            for (index in selectedIndices) {
                if (index in entries.indices) {
                    add(entries[index])
                }
            }
        }
    }

    private fun encodeMetadata(entries: List<PasswordEntry>): ByteArray {
        // Cap only the pre-allocation hint; ByteArrayOutputStream still grows for
        // larger lists and no entry/functionality is truncated.
        val initialCapacity = 8 + entries.size.coerceAtMost(2048) * 96
        val output = ByteArrayOutputStream(initialCapacity)
        writeIntLe(output, METADATA_BATCH_MAGIC)
        writeIntLe(output, entries.size)

        for (entry in entries) {
            writeUtf8(output, entry.title)
            writeUtf8(output, entry.username)
            writeUtf8(output, entry.website)
            writeUtf8(output, entry.appName)
            writeUtf8(output, entry.appPackageName)
        }
        return output.toByteArray()
    }

    private fun writeUtf8(output: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeIntLe(output, bytes.size)
        output.write(bytes)
    }

    private fun writeIntLe(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 24) and 0xff)
    }

    fun diagnosticLabel(): String = if (!ensureLoaded()) {
        "Rust core unavailable"
    } else {
        runCatching { nativeVersion() }.getOrDefault("Rust core loaded")
    }

    @JvmStatic
    private external fun nativeVersion(): String

    @JvmStatic
    private external fun nativeSelfTest(): Boolean

    @JvmStatic
    private external fun nativeFilterIndices(
        metadata: ByteArray,
        query: String,
    ): IntArray?
}
