package takagi.ru.monica.nfc.card

/** Blocking transport. The owner supplies connection timeout, background execution and cancellation. */
fun interface EmvCardChannel {
    fun transceive(command: ByteArray): ByteArray
}

sealed interface EmvCardReadResult {
    /** Deliberately not a data class: generated toString/copy/component functions can expose a PAN. */
    class BankCard(
        val number: String,
        val expiryMonth: Int? = null,
        val expiryYear: Int? = null,
        val holder: String? = null,
        val scheme: String? = null,
    ) : EmvCardReadResult {
        override fun toString(): String = "BankCard(redacted)"
    }

    data object Unsupported : EmvCardReadResult
    data object Malformed : EmvCardReadResult
    data object ConflictingData : EmvCardReadResult
}

/**
 * Reads public payment application data only. No UID fallback, BIN lookup, PIN, VERIFY,
 * GENERATE AC, GET CHALLENGE, log entry lookup, arbitrary record scan or write command.
 *
 * GPO is opt-in because some cards can update internal counters when processing it. It does
 * not authorize a payment here. PDOL is encoded exactly as declared, with zero terminal data
 * (including zero amounts/capabilities); cards requiring other values remain unsupported.
 * This intentionally favors a bounded, conservative read over complete card compatibility.
 *
 * Transport and checkCurrent exceptions propagate; callers must not log their messages or
 * raw APDUs. checkCurrent is called around every exchange and during all bounded parse loops.
 */
class EmvCardReader(
    private val channel: EmvCardChannel,
    private val checkCurrent: () -> Unit = {},
    private val allowGpo: Boolean = false,
) {
    fun read(): EmvCardReadResult = try {
        Read().run()
    } catch (_: InvalidData) {
        EmvCardReadResult.Malformed
    } catch (_: ConflictingPan) {
        EmvCardReadResult.ConflictingData
    }

    private inner class Read {
        private var exchanges = 0
        private var receivedBytes = 0
        private var records = 0

        fun run(): EmvCardReadResult {
            checkCurrent()
            val directory = exchange(select(PPSE))
            val applications = try {
                if (directory.ok) {
                    val roots = tlv(directory.bytes)
                    requireData(roots.size == 1 && roots.single().tag == 0x6F)
                    requireApplicationName(directory.bytes, roots, PPSE)
                    all(roots).filter { it.tag == 0x4F }.mapNotNull { node ->
                        requireData(node.length in 5..16)
                        APPLICATIONS.firstOrNull { it.aid.matches(directory.bytes, node.start, node.end) }
                    }.distinct().also { requireData(it.size <= MAX_APPLICATIONS) }
                } else FALLBACK
            } finally {
                directory.bytes.fill(0)
            }
            val combined = CardData()
            for (application in applications) {
                checkCurrent()
                readApplication(application)?.let { candidate -> combined.merge(candidate, application.scheme) }
            }
            checkCurrent()
            return combined.result()
        }

        private fun readApplication(application: Application): CardData? {
            val data = CardData()
            val selected = exchange(select(application.aid))
            if (!selected.ok) { selected.bytes.fill(0); return null }
            var afl: ByteArray? = null
            var pdol: ByteArray? = null
            try {
                val roots = tlv(selected.bytes)
                if (roots.isEmpty()) return null
                requireData(roots.size == 1 && roots.single().tag == 0x6F)
                requireApplicationName(selected.bytes, roots, application.aid)
                data.collect(selected.bytes, roots)
                afl = uniqueValue(selected.bytes, roots, 0x94)
                pdol = uniqueValue(selected.bytes, roots, 0x9F38)
            } finally {
                selected.bytes.fill(0)
            }
            try {
                if (afl == null && !data.hasNumber) {
                    if (!allowGpo) return null
                    val command = gpo(pdol ?: byteArrayOf())
                    val response = try { exchange(command) } finally { command.fill(0) }
                    try {
                        if (!response.ok) return null
                        val roots = tlv(response.bytes)
                        requireData(roots.size == 1)
                        val root = roots.single()
                        when (root.tag) {
                            0x80 -> {
                                requireData(root.length >= 2)
                                afl = response.bytes.copyOfRange(root.start + 2, root.end)
                            }
                            0x77 -> {
                                data.collect(response.bytes, roots)
                                afl = uniqueValue(response.bytes, roots, 0x94)
                            }
                            else -> throw InvalidData()
                        }
                    } finally {
                        response.bytes.fill(0)
                    }
                }
                afl?.let { locator ->
                    for ((sfi, record) in recordAddresses(locator)) {
                        checkCurrent()
                        val response = exchange(byteArrayOf(0x00, 0xB2.toByte(), record.toByte(), ((sfi shl 3) or 4).toByte(), 0x00))
                        try {
                            // A missing advertised record leaves the application incompletely read.
                            if (!response.ok) return null
                            val roots = tlv(response.bytes)
                            requireData(roots.size == 1 && roots.single().tag == 0x70)
                            data.collect(response.bytes, roots)
                        } finally {
                            response.bytes.fill(0)
                        }
                    }
                }
                return data.takeIf { it.hasNumber }
            } finally {
                afl?.fill(0)
                pdol?.fill(0)
            }
        }

        private fun gpo(pdol: ByteArray): ByteArray {
            requireData(pdol.size <= MAX_PDOL_BYTES)
            var cursor = 0
            var requested = 0
            var fields = 0
            while (cursor < pdol.size) {
                checkCurrent()
                requireData(++fields <= MAX_PDOL_FIELDS)
                val parsed = readTag(pdol, cursor, pdol.size)
                cursor = parsed.second
                requireData(cursor < pdol.size)
                val length = pdol[cursor++].unsigned()
                requireData(length > 0 && length <= MAX_PDOL_BYTES - requested)
                requested += length
            }
            val templateHeader = if (requested < 128) byteArrayOf(0x83.toByte(), requested.toByte())
                else byteArrayOf(0x83.toByte(), 0x81.toByte(), requested.toByte())
            val body = templateHeader + ByteArray(requested)
            return byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, body.size.toByte()) + body + byteArrayOf(0x00)
        }

        private fun recordAddresses(afl: ByteArray): List<Pair<Int, Int>> {
            requireData(afl.size % 4 == 0 && afl.size <= MAX_RECORDS * 4)
            val result = linkedSetOf<Pair<Int, Int>>()
            for (index in afl.indices step 4) {
                checkCurrent()
                val rawSfi = afl[index].unsigned()
                val sfi = rawSfi shr 3
                val first = afl[index + 1].unsigned()
                val last = afl[index + 2].unsigned()
                val offlineCount = afl[index + 3].unsigned()
                requireData(rawSfi and 7 == 0 && sfi in 1..30 && first in 1..last && offlineCount <= last - first + 1)
                requireData(last - first + 1 <= MAX_RECORDS - records)
                for (record in first..last) {
                    requireData(result.add(sfi to record))
                    records++
                }
            }
            return result.toList()
        }

        /** At most one corrected Le and four GET RESPONSE continuations per logical command. */
        private fun exchange(initial: ByteArray): Response {
            val accumulator = ByteArray(MAX_RESPONSE_BYTES)
            var used = 0
            var command = initial
            var corrected = false
            var continuations = 0
            try {
                while (true) {
                    checkCurrent()
                    requireData(++exchanges <= MAX_EXCHANGES)
                    val raw = channel.transceive(command.copyOf())
                    try {
                        checkCurrent()
                        requireData(raw.size in 2..MAX_FRAME_BYTES)
                        requireData(raw.size <= MAX_TOTAL_BYTES - receivedBytes)
                        receivedBytes += raw.size
                        val status1 = raw[raw.size - 2].unsigned()
                        val status2 = raw.last().unsigned()
                        val length = raw.size - 2
                        if (status1 == 0x6C) {
                            requireData(length == 0)
                            if (corrected) return Response(byteArrayOf(), false)
                            corrected = true
                            command = command.copyOf().also { it[it.lastIndex] = status2.toByte() }
                            continue
                        }
                        requireData(length <= MAX_RESPONSE_BYTES - used)
                        raw.copyInto(accumulator, used, 0, length)
                        used += length
                        if (status1 == 0x61) {
                            if (++continuations > MAX_CONTINUATIONS) return Response(byteArrayOf(), false)
                            command = byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, status2.toByte())
                            continue
                        }
                        return if (status1 == 0x90 && status2 == 0x00) Response(accumulator.copyOf(used), true)
                            else Response(byteArrayOf(), false)
                    } finally {
                        raw.fill(0)
                    }
                }
            } finally {
                accumulator.fill(0)
            }
        }

        private fun tlv(bytes: ByteArray): List<Node> {
            requireData(bytes.size <= MAX_RESPONSE_BYTES)
            var count = 0
            fun parse(start: Int, end: Int, depth: Int): List<Node> {
                requireData(depth <= MAX_TLV_DEPTH)
                var cursor = start
                val nodes = mutableListOf<Node>()
                while (cursor < end) {
                    checkCurrent()
                    if (bytes[cursor].unsigned() in PADDING) {
                        requireData((cursor until end).all { bytes[it].unsigned() in PADDING })
                        break
                    }
                    requireData(++count <= MAX_TLV_NODES)
                    val first = bytes[cursor].unsigned()
                    val (tag, next) = readTag(bytes, cursor, end)
                    cursor = next
                    requireData(cursor < end)
                    val encodedLength = bytes[cursor++].unsigned()
                    val length = if (encodedLength < 128) encodedLength else {
                        val octets = encodedLength and 0x7F
                        requireData(octets in 1..2 && octets <= end - cursor && bytes[cursor].unsigned() != 0)
                        var value = 0
                        repeat(octets) { value = (value shl 8) or bytes[cursor++].unsigned() }
                        requireData(value >= 128 && (octets == 1 || value >= 256))
                        value
                    }
                    requireData(length <= end - cursor)
                    val valueEnd = cursor + length
                    val children = if (first and 0x20 != 0) parse(cursor, valueEnd, depth + 1) else emptyList()
                    nodes += Node(tag, cursor, valueEnd, children)
                    cursor = valueEnd
                }
                return nodes
            }
            return parse(0, bytes.size, 0)
        }

        private fun uniqueValue(bytes: ByteArray, roots: List<Node>, tag: Int): ByteArray? {
            val found = all(roots).filter { it.tag == tag }
            requireData(found.size <= 1)
            return found.singleOrNull()?.let { bytes.copyOfRange(it.start, it.end) }
        }

        private fun requireApplicationName(bytes: ByteArray, roots: List<Node>, expected: ByteArray) {
            val names = all(roots).filter { it.tag == 0x84 }
            requireData(names.size == 1 && expected.matches(bytes, names.single().start, names.single().end))
        }

        private inner class CardData {
            private var number: String? = null
            private val expiries = linkedSetOf<Int>()
            private val holders = linkedSetOf<String>()
            private val schemes = linkedSetOf<String>()
            val hasNumber: Boolean get() = number != null

            fun collect(bytes: ByteArray, roots: List<Node>) {
                for (node in all(roots)) {
                    checkCurrent()
                    when (node.tag) {
                        0x5A -> {
                            requireData(node.length in 4..10)
                            val end = node.length * 2 - if (nibble(bytes, node, node.length * 2 - 1) == 15) 1 else 0
                            acceptPan(digits(bytes, node, 0, end))
                        }
                        0x57, 0x9F6B -> track(bytes, node)
                        0x5F24 -> if (node.length == 3) {
                            val year = decimalByte(bytes[node.start])
                            val month = decimalByte(bytes[node.start + 1])
                            val day = decimalByte(bytes[node.start + 2])
                            if (year != null && month != null && day != null && day in 1..31) expiry(year, month)
                        }
                        0x5F20 -> if (node.length in 2..26 && (node.start until node.end).all { bytes[it].unsigned() in 32..126 }) {
                            String(bytes, node.start, node.length, Charsets.US_ASCII).trim().takeIf { it.isNotEmpty() }?.let(holders::add)
                        }
                    }
                }
            }

            private fun track(bytes: ByteArray, node: Node) {
                requireData(node.length in 8..19)
                val end = node.length * 2 - if (nibble(bytes, node, node.length * 2 - 1) == 15) 1 else 0
                var separator = -1
                for (index in 0 until end) {
                    val value = nibble(bytes, node, index)
                    if (value == 13 && separator == -1) separator = index else requireData(value in 0..9)
                }
                requireData(separator in 8..19 && end - separator - 1 >= 7)
                acceptPan(digits(bytes, node, 0, separator))
                expiry(nibble(bytes, node, separator + 1) * 10 + nibble(bytes, node, separator + 2),
                    nibble(bytes, node, separator + 3) * 10 + nibble(bytes, node, separator + 4))
                // Service/discretionary bytes are checked for structure, never retained or exposed.
            }

            private fun acceptPan(value: String) {
                requireData(value.length in 8..19 && value.any { it != value.first() } && luhn(value))
                if (number != null && number != value) throw ConflictingPan()
                number = value
            }

            private fun expiry(year: Int, month: Int) {
                if (year in 0..99 && month in 1..12) expiries += (2000 + year) * 100 + month
            }

            fun merge(other: CardData, scheme: String) {
                other.number?.let(::acceptPan)
                expiries += other.expiries
                holders += other.holders
                schemes += scheme
            }

            fun result(): EmvCardReadResult {
                val pan = number ?: return EmvCardReadResult.Unsupported
                val expires = expiries.singleOrNull()
                return EmvCardReadResult.BankCard(pan, expires?.rem(100), expires?.div(100), holders.singleOrNull(), schemes.singleOrNull())
            }
        }
    }

    private class Response(val bytes: ByteArray, val ok: Boolean)
    private class Node(val tag: Int, val start: Int, val end: Int, val children: List<Node>) {
        val length: Int get() = end - start
    }
    private class Application(val aid: ByteArray, val scheme: String)
    private class InvalidData : RuntimeException()
    private class ConflictingPan : RuntimeException()

    private companion object {
        const val MAX_APPLICATIONS = 6
        const val MAX_RECORDS = 16
        const val MAX_EXCHANGES = 48
        const val MAX_CONTINUATIONS = 4
        const val MAX_FRAME_BYTES = 2050
        const val MAX_RESPONSE_BYTES = 2048
        const val MAX_TOTAL_BYTES = 16_384
        const val MAX_TLV_DEPTH = 8
        const val MAX_TLV_NODES = 128
        const val MAX_PDOL_BYTES = 128
        const val MAX_PDOL_FIELDS = 32
        val PADDING = setOf(0, 255)
        val PPSE = "2PAY.SYS.DDF01".toByteArray(Charsets.US_ASCII)
        val APPLICATIONS = listOf(
            Application(hex("A0000000031010"), "Visa"),
            Application(hex("A0000000041010"), "Mastercard"),
            Application(hex("A000000333010101"), "UnionPay"),
            Application(hex("A000000333010102"), "UnionPay"),
            Application(hex("A00000002501"), "American Express"),
            Application(hex("A0000000651010"), "JCB"),
            Application(hex("A0000000032010"), "Visa"),
            Application(hex("A0000000043060"), "Maestro"),
            Application(hex("A0000001523010"), "Discover"),
        )
        val FALLBACK = APPLICATIONS.take(MAX_APPLICATIONS)

        fun Byte.unsigned(): Int = toInt() and 0xFF
        fun requireData(condition: Boolean) { if (!condition) throw InvalidData() }
        fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun select(name: ByteArray): ByteArray = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, name.size.toByte()) + name + byteArrayOf(0x00)
        fun ByteArray.matches(other: ByteArray, start: Int, end: Int): Boolean = size == end - start && indices.all { this[it] == other[start + it] }
        fun all(nodes: List<Node>): List<Node> = nodes.flatMap { listOf(it) + all(it.children) }

        fun readTag(bytes: ByteArray, offset: Int, end: Int): Pair<Int, Int> {
            requireData(offset < end)
            val first = bytes[offset].unsigned()
            requireData(first !in PADDING)
            var cursor = offset + 1
            var tag = first
            if (first and 0x1F == 0x1F) {
                var count = 0
                do {
                    requireData(cursor < end && ++count <= 2)
                    val part = bytes[cursor++].unsigned()
                    requireData(count != 1 || part and 0x7F != 0)
                    tag = (tag shl 8) or part
                } while (part and 0x80 != 0)
            }
            return tag to cursor
        }

        fun nibble(bytes: ByteArray, node: Node, index: Int): Int {
            requireData(index >= 0 && index < node.length * 2)
            val value = bytes[node.start + index / 2].unsigned()
            return if (index % 2 == 0) value shr 4 else value and 15
        }

        fun digits(bytes: ByteArray, node: Node, start: Int, end: Int): String = buildString {
            for (index in start until end) {
                val value = nibble(bytes, node, index)
                requireData(value in 0..9)
                append(('0'.code + value).toChar())
            }
        }

        fun decimalByte(value: Byte): Int? {
            val high = value.unsigned() shr 4
            val low = value.unsigned() and 15
            return if (high in 0..9 && low in 0..9) high * 10 + low else null
        }

        fun luhn(value: String): Boolean {
            var sum = 0
            value.reversed().forEachIndexed { index, digit ->
                var number = digit - '0'
                if (index % 2 == 1) { number *= 2; if (number > 9) number -= 9 }
                sum += number
            }
            return sum % 10 == 0
        }
    }
}
