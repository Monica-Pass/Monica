package takagi.ru.monica.data.dedup

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Conservative destructive-use gate. Normal KDBX decoding is deliberately unaffected. */
internal object SingleVaultDedupKeePassHeaderAudit {
    fun complete(source: InputStream): Boolean = try {
        val input = DataInputStream(source)
        val prefix = ByteArray(12).also(input::readFully)
        val version = ByteBuffer.wrap(prefix, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (version != 0x00040001) false else {
            val seen = mutableSetOf<Int>()
            var total = 12
            var valid = true
            while (true) {
                val id = input.readUnsignedByte()
                val length = Integer.reverseBytes(input.readInt())
                require(length in 0..1024 * 1024)
                total += 5 + length
                require(total <= 1024 * 1024)
                val data = ByteArray(length).also(input::readFully)
                if (!seen.add(id)) valid = false
                when (id) {
                    0 -> { if (!data.contentEquals(byteArrayOf(13, 10, 13, 10))) valid = false; break }
                    2 -> if (length != 16) valid = false
                    3 -> if (length != 4) valid = false
                    4 -> if (length != 32) valid = false
                    7 -> if (length !in setOf(12, 16)) valid = false
                    11 -> if (!kdfComplete(data)) valid = false
                    // Unclassified public extension semantics (including duplicate keys)
                    // are not safe for destructive use, even if the decoder accepts them.
                    12 -> if (!data.contentEquals(byteArrayOf(0, 1, 0))) valid = false
                    else -> valid = false // Comments, legacy-only, unknown and duplicate fields.
                }
            }
            valid && seen.containsAll(setOf(0, 2, 3, 4, 7, 11))
        }
    } catch (_: Exception) { false }

    internal fun kdfComplete(bytes: ByteArray): Boolean = try {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.short.toInt() == 0x100)
        val types = linkedMapOf<String, Int>()
        while (true) {
            val type = buffer.get().toInt() and 0xff
            if (type == 0) break
            val keySize = buffer.int
            require(keySize in 1..64 && keySize <= buffer.remaining())
            val keyBytes = ByteArray(keySize).also(buffer::get)
            val key = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(keyBytes)).toString()
            require(types.put(key, type) == null)
            val size = buffer.int
            require(size >= 0 && size <= buffer.remaining())
            when (type) { 4 -> require(size == 4); 5 -> require(size == 8); 0x42 -> Unit; else -> error("Unsupported KDF type") }
            buffer.position(buffer.position() + size)
        }
        require(!buffer.hasRemaining())
        val required = if ("R" in types) mapOf("\$UUID" to 0x42, "R" to 5, "S" to 0x42)
            else mapOf("\$UUID" to 0x42, "S" to 0x42, "P" to 4, "M" to 5, "I" to 5, "V" to 4)
        val optional = if ("R" in types) emptyMap() else mapOf("K" to 0x42, "A" to 0x42)
        required.all { (key, type) -> types[key] == type } && types.all { (key, type) -> required[key] == type || optional[key] == type }
    } catch (_: Exception) { false }
}
