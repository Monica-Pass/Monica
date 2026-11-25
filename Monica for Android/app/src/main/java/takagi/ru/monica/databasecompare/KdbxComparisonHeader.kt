package takagi.ru.monica.databasecompare

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reject unmodeled outer-header data and hostile KDF allocations before decoding. */
internal object KdbxComparisonHeader {
    fun validate(file: File) = file.inputStream().buffered().use { input ->
        fun bytes(n: Int): ByteArray {
            if (n !in 0..(1024 * 1024)) throw KdbxUnsupportedContent()
            val value = ByteArray(n); var offset = 0
            while (offset < n) { val read = input.read(value, offset, n - offset); require(read > 0) { "Truncated KDBX header" }; offset += read }
            return value
        }
        val prefix = ByteBuffer.wrap(bytes(12)).order(ByteOrder.LITTLE_ENDIAN)
        require(prefix.int == 0x9AA2D903.toInt() && prefix.int == 0xB54BFB67.toInt()) { "Not a KDBX database" }
        val version = prefix.int; val major = version ushr 16; val minor = version and 65535
        if (major !in 3..4 || (major == 3 && minor != 1) || (major == 4 && minor !in 0..1)) throw KdbxUnsupportedContent()
        val seen = mutableSetOf<Int>(); var total = 12
        while (true) {
            val id = input.read(); require(id >= 0)
            val length = ByteBuffer.wrap(bytes(if (major == 3) 2 else 4)).order(ByteOrder.LITTLE_ENDIAN).let { if (major == 3) it.short.toInt() and 65535 else it.int }
            total += length; if (total > 1024 * 1024 || length < 0) throw KdbxUnsupportedContent()
            val value = bytes(length)
            if (!seen.add(id) || id !in (if (major == 3) setOf(0,2,3,4,5,6,7,8,9,10) else setOf(0,2,3,4,7,11,12))) throw KdbxUnsupportedContent()
            if (id == 6 && (value.size != 8 || littleLong(value) !in 1..MAX_AES_ROUNDS)) throw KdbxUnsupportedContent()
            if (id == 11) validateKdf(value)
            if (id == 0) break
        }
    }

    private fun validateKdf(data: ByteArray) {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.remaining() >= 3)
        val version = buf.short.toInt() and 65535
        if (version != 0x100) throw KdbxUnsupportedContent()
        val seen = mutableSetOf<String>()
        while (buf.hasRemaining()) {
            val type = buf.get().toInt() and 255
            if (type == 0) { require(!buf.hasRemaining()); break }
            require(buf.remaining() >= 4)
            val nameSize = buf.int; require(nameSize in 0..buf.remaining())
            val nameBytes = ByteArray(nameSize);buf.get(nameBytes);val key = nameBytes.toString(Charsets.UTF_8)
            require(buf.remaining() >= 4)
            val valueSize = buf.int; require(valueSize in 0..buf.remaining())
            val value=ByteArray(valueSize);buf.get(value)
            if (!seen.add(key) || key !in setOf("\$UUID","S","R","P","M","I","V","K","A")) throw KdbxUnsupportedContent()
            when(key) {
                "R" -> if(value.size != 8 || littleLong(value) !in 1..MAX_AES_ROUNDS) throw KdbxUnsupportedContent()
                "M" -> if(value.size != 8 || littleLong(value) !in 1..minOf(256L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4)) throw KdbxUnsupportedContent()
                "I" -> if(value.size != 8 || littleLong(value) !in 1..100) throw KdbxUnsupportedContent()
                "P" -> if(value.size != 4 || ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).int !in 1..32) throw KdbxUnsupportedContent()
            }
        }
    }
    private fun littleLong(value: ByteArray) = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).long
    private const val MAX_AES_ROUNDS = 10_000_000L
}
