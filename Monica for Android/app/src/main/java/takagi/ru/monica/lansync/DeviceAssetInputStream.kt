package takagi.ru.monica.lansync

import java.io.InputStream
import java.io.FilterInputStream
import java.security.MessageDigest

/** A verified final chunk is required before its bytes can reach a storage commit. */
internal class DeviceAssetInputStream(
    private val id: String,
    private val size: Long,
    private val checkActive: () -> Unit,
    private val nextChunk: () -> Chunk
) : InputStream() {
    internal data class Chunk(val id: String, val index: Int, val final: Boolean, val bytes: ByteArray)

    private val digest = MessageDigest.getInstance("SHA-256")
    private var chunk = ByteArray(0)
    private var position = 0
    private var expectedIndex = 0
    private var received = 0L
    private var complete = false
    private var closed = false

    init {
        if (size !in 0..MAX_BYTES) throw LanSyncException("asset_limit")
        if (!id.matches(Regex("[0-9a-fA-F]{64}"))) throw LanSyncException("invalid_asset")
    }

    override fun read(): Int {
        checkActive()
        if (!load()) return -1
        return chunk[position++].toInt() and 255
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
        checkActive()
        check(!closed) { "closed" }
        if (length == 0) return 0
        if (!load()) return -1
        val count = minOf(length, chunk.size - position)
        chunk.copyInto(bytes, offset, position, position + count)
        position += count
        return count
    }

    private fun load(): Boolean {
        check(!closed) { "closed" }
        if (position < chunk.size) return true
        chunk.fill(0)
        chunk = ByteArray(0)
        position = 0
        if (complete) return false
        checkActive()
        val next = nextChunk()
        try {
            if (next.id != id || next.index != expectedIndex || next.bytes.size > CHUNK_BYTES ||
                received + next.bytes.size > size || (!next.final && next.bytes.isEmpty())) {
                throw LanSyncException("invalid_asset")
            }
            expectedIndex++
            received += next.bytes.size
            digest.update(next.bytes)
            if (next.final) {
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (received != size || hash != id.lowercase()) throw LanSyncException("invalid_asset")
                complete = true
            } else if (received >= size) {
                throw LanSyncException("invalid_asset")
            }
            chunk = next.bytes
            return chunk.isNotEmpty()
        } catch (failure: Throwable) {
            next.bytes.fill(0)
            close()
            throw failure
        }
    }

    override fun close() {
        chunk.fill(0)
        chunk = ByteArray(0)
        position = 0
        closed = true
    }

    companion object {
        const val CHUNK_BYTES = 512 * 1024
        const val MAX_BYTES = 64L * 1024 * 1024

        /** Checks local decrypted sources with the same EOF/digest contract as received chunks. */
        fun verified(source: InputStream, id: String, size: Long, checkActive: () -> Unit): InputStream {
            var remaining = size
            var index = 0
            val verified = try {
                DeviceAssetInputStream(id, size, checkActive) {
                    val bytes = ByteArray(minOf(CHUNK_BYTES.toLong(), remaining).toInt())
                    try {
                        var position = 0
                        while (position < bytes.size) {
                            checkActive()
                            val count = source.read(bytes, position, bytes.size - position)
                            if (count < 0) throw LanSyncException("invalid_asset")
                            if (count == 0) {
                                val one = source.read()
                                if (one < 0) throw LanSyncException("invalid_asset")
                                bytes[position++] = one.toByte()
                            } else position += count
                        }
                        remaining -= bytes.size
                        if (remaining == 0L && source.read() != -1) throw LanSyncException("invalid_asset")
                        Chunk(id, index++, remaining == 0L, bytes)
                    } catch (failure: Throwable) { bytes.fill(0); throw failure }
                }
            } catch (failure: Throwable) { source.close(); throw failure }
            return object : FilterInputStream(verified) {
                override fun close() {
                    try { super.close() } finally { source.close() }
                }
            }
        }

        fun ownedBytes(bytes: ByteArray): InputStream = object : FilterInputStream(bytes.inputStream()) {
            override fun close() {
                try { super.close() } finally { bytes.fill(0) }
            }
        }
    }
}
