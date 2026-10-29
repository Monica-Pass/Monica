// Adapted from Authnkey, Copyright (c) 2025 Michel Le Bihan (MIT).
// See docs/licenses/Authnkey-MIT.txt.
package takagi.ru.monica.passkey.hardware

fun cbor(block: CborEncoder.() -> Unit): ByteArray {
    val encoder = CborEncoder()
    encoder.block()
    return encoder.toByteArray()
}

class CborEncoder {
    private val out = mutableListOf<Byte>()

    fun map(block: CborMapEncoder.() -> Unit) {
        val map = CborMapEncoder()
        map.block()
        writeHeader(5, map.entries.size)
        sortCanonical(map.entries).forEach { (k, v) -> out.addAll(k); out.addAll(v) }
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writeHeader(major: Int, value: Int) {
        out.addAll(encodeHeader(major, value))
    }
}

class CborMapEncoder {
    internal val entries = mutableListOf<RawMapEntry>()

    infix fun Int.to(value: Any?) {
        entries.add(encodeInt(this) to encodeValue(value))
    }

    infix fun String.to(value: Any?) {
        entries.add(encodeText(this) to encodeValue(value))
    }

    fun map(block: CborMapEncoder.() -> Unit): CborRaw {
        val nested = CborMapEncoder()
        nested.block()
        val bytes = mutableListOf<Byte>()
        bytes.addAll(encodeHeader(5, nested.entries.size))
        sortCanonical(nested.entries).forEach { (k, v) -> bytes.addAll(k); bytes.addAll(v) }
        return CborRaw(bytes)
    }

    fun array(block: CborArrayEncoder.() -> Unit): CborRaw {
        val nested = CborArrayEncoder()
        nested.block()
        val bytes = mutableListOf<Byte>()
        bytes.addAll(encodeHeader(4, nested.items.size))
        nested.items.forEach { bytes.addAll(it) }
        return CborRaw(bytes)
    }

    fun bytes(data: ByteArray) = CborRaw(encodeBytes(data))
}

class CborArrayEncoder {
    internal val items = mutableListOf<List<Byte>>()

    fun add(value: Any?) {
        items.add(encodeValue(value))
    }

    fun map(block: CborMapEncoder.() -> Unit) {
        val nested = CborMapEncoder()
        nested.block()
        val bytes = mutableListOf<Byte>()
        bytes.addAll(encodeHeader(5, nested.entries.size))
        sortCanonical(nested.entries).forEach { (k, v) -> bytes.addAll(k); bytes.addAll(v) }
        items.add(bytes)
    }
}

@JvmInline
value class CborRaw(val bytes: List<Byte>)

private typealias RawMapEntry = Pair<List<Byte>, List<Byte>>

private fun sortCanonical(entries: List<RawMapEntry>): List<RawMapEntry> {
    return entries.sortedWith { a, b ->
        val ka = a.first
        val kb = b.first
        if (ka.size != kb.size) ka.size - kb.size
        else ka.indices.map {
            (ka[it].toInt() and 0xFF) - (kb[it].toInt() and 0xFF)
        }.firstOrNull { it != 0 } ?: 0
    }
}

private fun encodeValue(value: Any?): List<Byte> = when (value) {
    null -> listOf(0xF6.toByte())
    is Boolean -> listOf(if (value) 0xF5.toByte() else 0xF4.toByte())
    is Int -> encodeInt(value)
    is Long -> encodeLong(value)
    is String -> encodeText(value)
    is ByteArray -> encodeBytes(value)
    is CborRaw -> value.bytes
    is Map<*, *> -> {
        val entries = value.map { encodeValue(it.key) to encodeValue(it.value) }
        encodeHeader(5, entries.size) + sortCanonical(entries).flatMap { it.first + it.second }
    }
    is List<*> -> {
        val items = value.map { encodeValue(it) }
        encodeHeader(4, items.size) + items.flatten()
    }
    else -> throw IllegalArgumentException("Unsupported: ${value::class}")
}

private fun encodeHeader(major: Int, value: Int): List<Byte> = when {
    value < 24 -> listOf(((major shl 5) or value).toByte())
    value < 0x100 -> listOf(((major shl 5) or 24).toByte(), value.toByte())
    value < 0x10000 -> listOf(
        ((major shl 5) or 25).toByte(),
        (value shr 8).toByte(),
        value.toByte()
    )
    else -> listOf(
        ((major shl 5) or 26).toByte(),
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte()
    )
}

private fun encodeHeaderLong(major: Int, value: Long): List<Byte> = when {
    value < 24 -> listOf(((major shl 5) or value.toInt()).toByte())
    value < 0x100 -> listOf(((major shl 5) or 24).toByte(), value.toByte())
    value < 0x10000 -> listOf(
        ((major shl 5) or 25).toByte(),
        (value shr 8).toByte(),
        value.toByte()
    )
    value < 0x100000000 -> listOf(
        ((major shl 5) or 26).toByte(),
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte()
    )
    else -> listOf(
        ((major shl 5) or 27).toByte(),
        (value shr 56).toByte(),
        (value shr 48).toByte(),
        (value shr 40).toByte(),
        (value shr 32).toByte(),
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte()
    )
}

private fun encodeInt(value: Int): List<Byte> =
    if (value >= 0) encodeHeader(0, value)
    else encodeHeader(1, -1 - value)

private fun encodeLong(value: Long): List<Byte> =
    if (value >= 0) encodeHeaderLong(0, value)
    else encodeHeaderLong(1, -1 - value)

private fun encodeText(s: String): List<Byte> {
    val bytes = s.toByteArray(Charsets.UTF_8)
    return encodeHeader(3, bytes.size) + bytes.toList()
}

private fun encodeBytes(b: ByteArray): List<Byte> =
    encodeHeader(2, b.size) + b.toList()

// ============================================================

class CborDecoder private constructor(private val data: ByteArray) {
    private var pos = 0
    private var depth = 0

    companion object {
        fun decode(data: ByteArray): Any? {
            require(data.size <= 65535) { "CBOR too large" }
            val decoder = CborDecoder(data)
            return decoder.readValue().also { require(decoder.pos == data.size) { "Trailing CBOR" } }
        }

        fun decodeFirst(data: ByteArray): Any? = CborDecoder(data).readValue()

        /**
         * Measure how many bytes the first CBOR value in the data occupies.
         */
        fun measureFirstValue(data: ByteArray): Int {
            val decoder = CborDecoder(data)
            decoder.readValue()
            return decoder.pos
        }
    }

    private fun readValue(): Any? {
        require(pos < data.size && depth < 16) { "Invalid CBOR" }
        depth++

        val initial = data[pos++].toInt() and 0xFF
        val major = initial shr 5
        val info = initial and 0x1F

        val result = when (major) {
            0 -> readUnsigned(info)
            1 -> -1L - readUnsigned(info)
            2 -> readByteString(info)
            3 -> readTextString(info)
            4 -> readArray(info)
            5 -> readMap(info)
            7 -> when (info) {
                20 -> false
                21 -> true
                22 -> null
                else -> throw IllegalArgumentException("Unsupported CBOR simple value")
            }
            else -> throw IllegalArgumentException("Unsupported CBOR type")
        }
        depth--
        return result
    }

    private fun readUnsigned(info: Int): Long {
        val bytes = when (info) { in 0..23 -> 0; 24 -> 1; 25 -> 2; 26 -> 4; 27 -> 8; else -> throw IllegalArgumentException("Indefinite CBOR") }
        require(pos + bytes <= data.size) { "Truncated CBOR" }
        val value = when {
        info < 24 -> info.toLong()
        info == 24 -> (data[pos++].toInt() and 0xFF).toLong()
        info == 25 -> {
            val r = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2
            r.toLong()
        }
        info == 26 -> {
            val r = ((data[pos].toLong() and 0xFF) shl 24) or
                    ((data[pos + 1].toLong() and 0xFF) shl 16) or
                    ((data[pos + 2].toLong() and 0xFF) shl 8) or
                    (data[pos + 3].toLong() and 0xFF)
            pos += 4
            r
        }
        info == 27 -> {
            val r = ((data[pos].toLong() and 0xFF) shl 56) or
                    ((data[pos + 1].toLong() and 0xFF) shl 48) or
                    ((data[pos + 2].toLong() and 0xFF) shl 40) or
                    ((data[pos + 3].toLong() and 0xFF) shl 32) or
                    ((data[pos + 4].toLong() and 0xFF) shl 24) or
                    ((data[pos + 5].toLong() and 0xFF) shl 16) or
                    ((data[pos + 6].toLong() and 0xFF) shl 8) or
                    (data[pos + 7].toLong() and 0xFF)
            pos += 8
            r
        }
        else -> throw IllegalArgumentException("Invalid CBOR integer")
        }
        require(value >= 0) { "CBOR integer overflow" }
        return value
    }

    private fun length(info: Int): Int {
        val length = readUnsigned(info)
        require(length <= data.size - pos) { "Invalid CBOR length" }
        return length.toInt()
    }

    private fun readByteString(info: Int): ByteArray {
        val len = length(info)
        val result = data.sliceArray(pos until pos + len)
        pos += len
        return result
    }

    private fun readTextString(info: Int): String {
        val len = length(info)
        val result = String(data, pos, len, Charsets.UTF_8)
        pos += len
        return result
    }

    private fun readArray(info: Int): List<Any?> {
        val len = length(info)
        return (0 until len).map { readValue() }
    }

    private fun readMap(info: Int): Map<Any?, Any?> {
        val len = length(info)
        val result = linkedMapOf<Any?, Any?>()
        repeat(len) {
            val key = readValue()
            require(key is String || key is Long) { "Invalid CBOR map key" }
            require(!result.containsKey(key)) { "Duplicate CBOR map key" }
            val value = readValue()
            result[key] = value
        }
        return result
    }
}

class CborMap(private val raw: Map<Any?, Any?>) {

    companion object {
        fun decode(data: ByteArray): CborMap? {
            val decoded = CborDecoder.decode(data) as? Map<*, *> ?: return null
            @Suppress("UNCHECKED_CAST")
            return CborMap(decoded as Map<Any?, Any?>)
        }
    }

    operator fun get(key: Int): Any? = raw[key.toLong()] ?: raw[key]
    operator fun get(key: String): Any? = raw[key]

    fun int(key: Int): Int? = (this[key] as? Number)?.toInt()
    fun int(key: String): Int? = (this[key] as? Number)?.toInt()

    fun long(key: Int): Long? = (this[key] as? Number)?.toLong()
    fun long(key: String): Long? = (this[key] as? Number)?.toLong()

    fun bool(key: Int): Boolean? = this[key] as? Boolean
    fun bool(key: String): Boolean? = this[key] as? Boolean

    fun string(key: Int): String? = this[key] as? String
    fun string(key: String): String? = this[key] as? String

    fun bytes(key: Int): ByteArray? = this[key] as? ByteArray
    fun bytes(key: String): ByteArray? = this[key] as? ByteArray

    fun map(key: Int): CborMap? = (this[key] as? Map<*, *>)?.let {
        @Suppress("UNCHECKED_CAST")
        CborMap(it as Map<Any?, Any?>)
    }
    fun map(key: String): CborMap? = (this[key] as? Map<*, *>)?.let {
        @Suppress("UNCHECKED_CAST")
        CborMap(it as Map<Any?, Any?>)
    }

    fun <T> list(key: Int): List<T>? {
        @Suppress("UNCHECKED_CAST")
        return this[key] as? List<T>
    }
    fun <T> list(key: String): List<T>? {
        @Suppress("UNCHECKED_CAST")
        return this[key] as? List<T>
    }

    fun mapList(key: Int): List<CborMap>? = list<Map<Any?, Any?>>(key)?.map { CborMap(it) }
    fun mapList(key: String): List<CborMap>? = list<Map<Any?, Any?>>(key)?.map { CborMap(it) }

    fun containsKey(key: Int): Boolean = raw.containsKey(key.toLong()) || raw.containsKey(key)
    fun containsKey(key: String): Boolean = raw.containsKey(key)
}

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

fun String.hexToByteArray(): ByteArray {
    val len = length
    val out = ByteArray(len / 2)
    for (i in 0 until len step 2) {
        out[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
    }
    return out
}
