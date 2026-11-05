package takagi.ru.monica.bitwarden.discovery

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class BitwardenDnsTxtParserTest {
    @Test fun joinsStringsWithinEachRecordButNeverAcrossRecords() {
        val response = packet(listOf(txt("https://", "vault.example.com/"), txt("https://second.example.com/")))
        assertEquals(listOf("https://vault.example.com/", "https://second.example.com/"), records(response))
    }

    @Test fun joinsUtf8BytesBeforeDecodingAndRejectsMalformedEncoding() {
        val pieces = byteArrayOf(1, 0xC3.toByte(), 1, 0xA9.toByte())
        assertEquals(listOf("é"), records(packet(listOf(Rr(data = pieces)))))
        malformed(packet(listOf(Rr(data = byteArrayOf(2, 0xC0.toByte(), 0xAF.toByte())))))
    }

    @Test fun acceptsBackwardCompressionAndCaseInsensitiveQuestion() {
        val response = packet(listOf(txt("https://vault.example.com/")), question = "_BITWARDEN.Example.COM")
        assertEquals(listOf("https://vault.example.com/"), records(response))
        assertTrue(BitwardenDnsTxtParser.parse("_bitwarden.example.com.", response) is BitwardenTxtLookup.Records)
    }

    @Test fun followsOnlyTheRequestedValidatedCnameChain() {
        val aliases = listOf(cname(Q, "alias.example.net"), cname("alias.example.net", "final.example.net"))
        val answers = aliases + txt("https://vault.example.net/", owner = wireName("final.example.net")) +
            txt("https://unrelated.example/", owner = wireName("unrelated.example"))
        assertEquals(listOf("https://vault.example.net/"), records(packet(answers)))
        assertEquals(BitwardenTxtLookup.NoRecords, parse(packet(listOf(txt("https://unrelated.example/", owner = wireName("other.example"))))))
    }

    @Test fun ignoresAuthorityAndAdditionalTxtForCandidateSelection() {
        assertEquals(BitwardenTxtLookup.NoRecords, parse(packet(emptyList(), authorities = listOf(txt("https://authority.example/")),
            additional = listOf(txt("https://additional.example/")))))
    }

    @Test fun rejectsAmbiguousCnameTargetsAndCoexistingTxt() {
        malformed(packet(listOf(cname(Q, "one.example"), cname(Q, "two.example"))))
        malformed(packet(listOf(cname(Q, "one.example"), txt("https://vault.example.com/"))))
        malformed(packet(listOf(cname(Q, "one.example"), cname("one.example", Q))))
    }

    @Test fun acceptsEightAliasHopsButRejectsNine() {
        fun chain(count: Int): List<Rr> = (0 until count).map { index ->
            cname(if (index == 0) Q else "alias$index.example", "alias${index + 1}.example")
        } + txt("https://vault.example/", owner = wireName("alias$count.example"))
        assertEquals(listOf("https://vault.example/"), records(packet(chain(8))))
        malformed(packet(chain(9)))
    }

    @Test fun rejectsSelfForwardHeaderAndUnparsedDataCompressionTargets() {
        val answerStart = 12 + wireName(Q).size + 4
        for (target in listOf(0, answerStart, answerStart + 2, 16_000)) {
            malformed(packet(listOf(txt("https://vault.example/", owner = pointer(target)))))
        }
        val embeddedName = wireName(Q)
        val firstData = byteArrayOf(embeddedName.size.toByte()) + embeddedName
        val firstDataStart = answerStart + 2 + 10
        malformed(packet(listOf(Rr(data = firstData), txt("https://vault.example/", owner = pointer(firstDataStart + 1)))))
    }

    @Test fun rejectsQuestionMismatchTypeClassAndMultipleQuestions() {
        malformed(packet(listOf(txt("https://vault.example/")), question = "_vaultwarden.example.com"))
        malformed(packet(emptyList(), questionType = 1))
        malformed(packet(emptyList(), questionClass = 3))
        malformed(packet(emptyList()).also { it[5] = 2 })
        assertEquals(BitwardenTxtFailure.INVALID_QUERY, (BitwardenDnsTxtParser.parse("person@example.com", packet(emptyList())) as BitwardenTxtLookup.Failure).reason)
    }

    @Test fun rejectsQueriesOpcodesTruncationAndReservedFlag() {
        for (flags in listOf(0x0100, 0x8900, 0x8300, 0x8140)) malformed(packet(emptyList(), flags = flags))
    }

    @Test fun distinguishesNodataNxdomainAndServerFailure() {
        assertEquals(BitwardenTxtLookup.NoRecords, parse(packet(emptyList())))
        assertEquals(BitwardenTxtLookup.NoRecords, parse(packet(emptyList(), flags = 0x8183)))
        assertEquals(BitwardenTxtFailure.DNS_RESPONSE, failure(packet(emptyList(), flags = 0x8182)))
        malformed(packet(listOf(txt("https://vault.example/")), flags = 0x8183))
    }

    @Test fun checksEveryTruncationAndRejectsTrailingBytes() {
        val valid = packet(listOf(txt("https://vault.example.com/")))
        for (length in 0 until valid.size) assertTrue("prefix=$length", parse(valid.copyOf(length)) is BitwardenTxtLookup.Failure)
        malformed(valid + byteArrayOf(0))
        malformed(packet(listOf(Rr(data = byteArrayOf(5, 1)))))
        malformed(packet(listOf(Rr(data = byteArrayOf()))))
        assertEquals(listOf(""), records(packet(listOf(Rr(data = byteArrayOf(0))))))
    }

    @Test fun checksRecordClassNameLengthsAndCnameRdataBoundary() {
        malformed(packet(listOf(Rr(klass = 3, data = strings("https://vault.example/")))))
        malformed(packet(listOf(txt("https://vault.example/", owner = byteArrayOf(64) + ByteArray(64) { 'a'.code.toByte() } + byteArrayOf(0)))))
        malformed(packet(listOf(txt("https://vault.example/", owner = wireName(List(4) { "a".repeat(63) }.joinToString("."))))))
        malformed(packet(listOf(Rr(type = 5, data = wireName("alias.example") + byteArrayOf(0)))))
    }

    @Test fun enforcesPacketRecordTxtAndAggregateTextBudgets() {
        assertEquals(BitwardenTxtFailure.RESPONSE_LIMIT, failure(ByteArray(BitwardenDnsTxtParser.MAX_PACKET_BYTES + 1)))
        assertEquals(BitwardenTxtFailure.RESPONSE_LIMIT, failure(packet(emptyList()).also { it[6] = 1; it[7] = 0 }))
        assertEquals(BitwardenTxtFailure.RESPONSE_LIMIT, failure(packet(List(17) { txt("https://vault.example/") })))
        val atLimit = "a".repeat(BitwardenDnsTxtParser.MAX_TEXT_BYTES)
        val fullRecord = Rr(data = strings(*atLimit.chunked(255).toTypedArray()))
        assertEquals(listOf(atLimit), records(packet(listOf(fullRecord))))
        assertEquals(BitwardenTxtFailure.RESPONSE_LIMIT, failure(packet(listOf(fullRecord, txt("b")))))
    }

    @Test fun validatesNegativeSoaAndEdnsResponseCodesAndOptions() {
        val soa = Rr(owner = wireName("example.com"), type = 6,
            data = wireName("ns.example.com") + wireName("hostmaster.example.com") + ByteArray(20))
        assertEquals(BitwardenTxtLookup.NoRecords, parse(packet(emptyList(), authorities = listOf(soa))))
        val opt = Rr(owner = byteArrayOf(0), type = 41, klass = 1232, data = byteArrayOf(0, 10, 0, 1, 7))
        assertEquals(BitwardenTxtLookup.NoRecords, parse(packet(emptyList(), additional = listOf(opt))))
        malformed(packet(emptyList(), additional = listOf(opt.copy(data = byteArrayOf(0, 10, 0, 2, 7)))))
        assertEquals(BitwardenTxtFailure.DNS_RESPONSE, failure(packet(emptyList(), additional = listOf(opt.copy(ttl = 1L shl 24)))))
    }

    @Test fun recordsAreDefensivelyCopiedAndStringificationDoesNotPrintTxt() {
        val source = mutableListOf("https://sentinel.example/secret-path/")
        val lookup = BitwardenTxtLookup.Records(source)
        source.clear()
        assertEquals(1, lookup.records.size)
        assertFalse(lookup.toString().contains("sentinel"))
        assertThrows(UnsupportedOperationException::class.java) { (lookup.records as MutableList<*>).clear() }
    }

    private fun parse(bytes: ByteArray) = BitwardenDnsTxtParser.parse(Q, bytes)
    private fun records(bytes: ByteArray) = (parse(bytes) as BitwardenTxtLookup.Records).records
    private fun failure(bytes: ByteArray) = (parse(bytes) as BitwardenTxtLookup.Failure).reason
    private fun malformed(bytes: ByteArray) = assertEquals(BitwardenTxtFailure.MALFORMED_RESPONSE, failure(bytes))

    private data class Rr(val owner: ByteArray = pointer(12), val type: Int = 16, val klass: Int = 1,
        val ttl: Long = 0, val data: ByteArray)

    private fun txt(vararg chunks: String, owner: ByteArray = pointer(12)) = Rr(owner = owner, data = strings(*chunks))
    private fun cname(owner: String, target: String) = Rr(owner = wireName(owner), type = 5, data = wireName(target))

    private fun packet(answers: List<Rr>, authorities: List<Rr> = emptyList(), additional: List<Rr> = emptyList(),
        flags: Int = 0x8180, question: String = Q, questionType: Int = 16, questionClass: Int = 1): ByteArray =
        ByteArrayOutputStream().apply {
            word(0x1234); word(flags); word(1); word(answers.size); word(authorities.size); word(additional.size)
            write(wireName(question)); word(questionType); word(questionClass)
            (answers + authorities + additional).forEach { rr ->
                write(rr.owner); word(rr.type); word(rr.klass); word((rr.ttl ushr 16).toInt()); word(rr.ttl.toInt())
                word(rr.data.size); write(rr.data)
            }
        }.toByteArray()

    private companion object {
        const val Q = "_bitwarden.example.com"
        fun pointer(offset: Int) = byteArrayOf((0xC0 or (offset ushr 8)).toByte(), offset.toByte())
        fun ByteArrayOutputStream.word(value: Int) { write(value ushr 8 and 255); write(value and 255) }
        fun wireName(value: String): ByteArray = ByteArrayOutputStream().apply {
            value.split('.').forEach { label -> val raw = label.toByteArray(Charsets.US_ASCII); write(raw.size); write(raw) }
            write(0)
        }.toByteArray()
        fun strings(vararg values: String): ByteArray = ByteArrayOutputStream().apply {
            values.forEach { value -> val raw = value.toByteArray(Charsets.UTF_8); require(raw.size <= 255); write(raw.size); write(raw) }
        }.toByteArray()
    }
}
