package takagi.ru.monica.bitwarden.discovery

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.Locale

internal fun interface BitwardenDnsTxtResolver {
    suspend fun queryTxt(name: String): BitwardenTxtLookup
}

internal enum class BitwardenTxtFailure {
    INVALID_QUERY, MALFORMED_RESPONSE, RESPONSE_LIMIT, DNS_RESPONSE, TRANSPORT,
}

internal sealed class BitwardenTxtLookup {
    class Records(records: List<String>) : BitwardenTxtLookup() {
        val records: List<String> = Collections.unmodifiableList(records.toList())
        override fun toString() = "BitwardenTxtLookup.Records(count=${records.size})"
    }
    data object NoRecords : BitwardenTxtLookup()
    data object Unsupported : BitwardenTxtLookup()
    data class Failure(val reason: BitwardenTxtFailure) : BitwardenTxtLookup()
}

/** Strict, bounded DNS wire decoder. No packet content or exception text leaves this boundary. */
internal object BitwardenDnsTxtParser {
    const val MAX_PACKET_BYTES = 64 * 1024
    const val MAX_RECORDS = 128
    const val MAX_TXT_RECORDS = 16
    const val MAX_TEXT_BYTES = 8 * 1024
    const val MAX_CNAME_HOPS = 8
    const val TYPE_TXT = 16

    fun parse(expectedQuestionName: String, packet: ByteArray): BitwardenTxtLookup {
        val expected = canonicalQueryName(expectedQuestionName)
            ?: return BitwardenTxtLookup.Failure(BitwardenTxtFailure.INVALID_QUERY)
        return try {
            Decoder(packet).decode(expected)
        } catch (failure: InvalidPacket) {
            BitwardenTxtLookup.Failure(failure.reason)
        } catch (_: CharacterCodingException) {
            BitwardenTxtLookup.Failure(BitwardenTxtFailure.MALFORMED_RESPONSE)
        }
    }

    internal fun canonicalQueryName(raw: String): String? {
        val name = raw.removeSuffix(".").lowercase(Locale.ROOT)
        if (name.length !in 1..253 || name.any { it.code > 127 }) return null
        val labels = name.split('.')
        if (labels.size < 2 || labels.first() !in setOf("_bitwarden", "_vaultwarden")) return null
        if (labels.drop(1).any { !HOST_LABEL.matches(it) }) return null
        return name
    }

    private val HOST_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    private class InvalidPacket(val reason: BitwardenTxtFailure) : Exception()
    private fun invalid(reason: BitwardenTxtFailure = BitwardenTxtFailure.MALFORMED_RESPONSE): Nothing =
        throw InvalidPacket(reason)

    private class Decoder(private val bytes: ByteArray) {
        private val boundaries = mutableSetOf<Int>()
        private var cursor = 12
        private var textBytes = 0
        private var txtAnswers = 0
        private var extendedRcode = 0
        private var optSeen = false

        fun decode(expected: String): BitwardenTxtLookup {
            if (bytes.size > MAX_PACKET_BYTES) invalid(BitwardenTxtFailure.RESPONSE_LIMIT)
            if (bytes.size < 12) invalid()
            val flags = word(2)
            if (flags and 0x8000 == 0 || flags and 0x7800 != 0 || flags and 0x0200 != 0 || flags and 0x0040 != 0) invalid()
            val rcode = flags and 15
            if (word(4) != 1) invalid()
            val counts = listOf(word(6), word(8), word(10))
            if (counts.sum() > MAX_RECORDS) invalid(BitwardenTxtFailure.RESPONSE_LIMIT)
            val question = name(cursor)
            cursor = question.next
            if (question.value != expected || word(cursor) != TYPE_TXT || word(cursor + 2) != 1) invalid()
            cursor += 4
            val aliases = linkedMapOf<String, String>()
            val answers = linkedMapOf<String, MutableList<String>>()
            counts.forEachIndexed { section, count ->
                repeat(count) {
                    val owner = name(cursor)
                    cursor = owner.next
                    val type = word(cursor)
                    val recordClass = word(cursor + 2)
                    val ttl = longWord(cursor + 4)
                    val length = word(cursor + 8)
                    cursor += 10
                    val end = cursor + length
                    if (end > bytes.size) invalid()
                    if (section == 0 && type in setOf(5, TYPE_TXT) && recordClass != 1) invalid()
                    when (type) {
                        TYPE_TXT -> {
                            if (section == 0 && ++txtAnswers > MAX_TXT_RECORDS) invalid(BitwardenTxtFailure.RESPONSE_LIMIT)
                            val value = txt(cursor, end)
                            if (section == 0) answers.getOrPut(owner.value) { mutableListOf() }.add(value)
                        }
                        5 -> {
                            val target = singleName(cursor, end)
                            if (owner.value.isEmpty() || target.isEmpty()) invalid()
                            if (section == 0) {
                                val old = aliases.put(owner.value, target)
                                if (old != null && old != target) invalid()
                            }
                        }
                        2, 12 -> singleName(cursor, end) // NS/PTR names may be compression targets.
                        6 -> { // SOA in negative responses: two names followed by five uint32s.
                            val first = name(cursor)
                            val second = name(first.next)
                            if (second.next + 20 != end) invalid()
                        }
                        15 -> { if (length < 3) invalid(); singleName(cursor + 2, end) }
                        33 -> { if (length < 7) invalid(); singleName(cursor + 6, end) }
                        1 -> if (length != 4) invalid()
                        28 -> if (length != 16) invalid()
                        41 -> {
                            if (section != 2 || owner.value.isNotEmpty() || optSeen) invalid()
                            optSeen = true
                            extendedRcode = (ttl ushr 24).toInt()
                            if ((ttl ushr 16) and 255 != 0L) invalid(BitwardenTxtFailure.DNS_RESPONSE)
                            var option = cursor
                            while (option < end) {
                                if (option + 4 > end) invalid()
                                option += 4 + word(option + 2)
                                if (option > end) invalid()
                            }
                        }
                    }
                    cursor = end
                }
            }
            if (cursor != bytes.size || aliases.keys.any { it in answers }) invalid()
            var owner = expected
            val seen = mutableSetOf<String>()
            var hops = 0
            while (owner in aliases) {
                if (!seen.add(owner) || ++hops > MAX_CNAME_HOPS) invalid()
                owner = aliases.getValue(owner)
            }
            if (owner in seen) invalid()
            val records = answers[owner].orEmpty()
            if (extendedRcode != 0 || rcode !in setOf(0, 3)) invalid(BitwardenTxtFailure.DNS_RESPONSE)
            if (rcode == 3 && records.isNotEmpty()) invalid()
            return if (records.isEmpty()) BitwardenTxtLookup.NoRecords else BitwardenTxtLookup.Records(records)
        }

        private fun txt(start: Int, end: Int): String {
            if (start == end) invalid()
            val assembled = ByteArrayOutputStream()
            var offset = start
            while (offset < end) {
                val length = octet(offset++)
                if (offset + length > end) invalid()
                textBytes += length
                if (textBytes > MAX_TEXT_BYTES) invalid(BitwardenTxtFailure.RESPONSE_LIMIT)
                assembled.write(bytes, offset, length)
                offset += length
            }
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(assembled.toByteArray())).toString()
        }

        private fun singleName(start: Int, end: Int): String = name(start).also {
            if (it.next != end) invalid()
        }.value

        private data class Name(val value: String, val next: Int)

        private fun name(start: Int): Name {
            var offset = start
            var next = -1
            var expanded = 1
            var jumps = 0
            val seen = mutableSetOf<Int>()
            val labels = mutableListOf<String>()
            while (true) {
                if (!seen.add(offset)) invalid()
                val size = octet(offset)
                boundaries.add(offset)
                if (size and 0xC0 == 0xC0) {
                    val target = ((size and 0x3F) shl 8) or octet(offset + 1)
                    if (target < 12 || target >= offset || target !in boundaries || ++jumps > 32) invalid()
                    if (next < 0) next = offset + 2
                    offset = target
                    continue
                }
                if (size and 0xC0 != 0 || size > 63) invalid()
                offset++
                if (size == 0) return Name(labels.joinToString("."), if (next < 0) offset else next)
                if (offset + size > bytes.size || expanded + size + 1 > 255) invalid()
                val label = buildString {
                    repeat(size) {
                        val value = octet(offset + it)
                        if (value !in 48..57 && value !in 65..90 && value !in 97..122 && value != 45 && value != 95) invalid()
                        append(value.toChar())
                    }
                }
                labels.add(label.lowercase(Locale.ROOT))
                expanded += size + 1
                offset += size
            }
        }

        private fun octet(offset: Int): Int {
            if (offset !in bytes.indices) invalid()
            return bytes[offset].toInt() and 255
        }
        private fun word(offset: Int): Int = (octet(offset) shl 8) or octet(offset + 1)
        private fun longWord(offset: Int): Long = (word(offset).toLong() shl 16) or word(offset + 2).toLong()
    }
}
