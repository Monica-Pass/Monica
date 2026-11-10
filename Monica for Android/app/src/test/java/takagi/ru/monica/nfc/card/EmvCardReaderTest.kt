package takagi.ru.monica.nfc.card

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Entirely synthetic APDU conversations. No NFC hardware, accounts or real card data. */
class EmvCardReaderTest {
    @Test fun ppseGpoAndAdvertisedRecordReturnOnlyPublicCardFields() {
        val record = tlv(0x70, pan(PAN_A) + tlv(0x57, track(PAN_A)) + tlv(0x9F6B, track(PAN_A)) +
            tlv(0x5F24, hex("291231")) + tlv(0x5F20, "SYNTHETIC/CARDHOLDER".toByteArray()))
        val conversation = standard(record)
        val result = bank(conversation.reader().read())
        assertTrue(result.number == PAN_A)
        assertEquals(12, result.expiryMonth)
        assertEquals(2029, result.expiryYear)
        assertEquals("SYNTHETIC/CARDHOLDER", result.holder)
        assertEquals("Visa", result.scheme)
        assertEquals("BankCard(redacted)", result.toString())
        assertFalse(result.toString().contains(PAN_A))
        conversation.complete()
        conversation.assertReadCommandsOnly()
        assertTrue("Transport response buffers are erased after parsing", conversation.responses.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test fun gpoIsNeverSentWithoutExplicitOptIn() {
        val conversation = Dialogue(step(SELECT_PPSE, directory(VISA)), step(select(VISA), fci(VISA)))
        assertSame(EmvCardReadResult.Unsupported, conversation.reader(allowGpo = false).read())
        conversation.complete()
        assertFalse(conversation.commands.any { it[1] == 0xA8.toByte() })
    }

    @Test fun alreadyAvailablePublicFieldsDoNotNeedGpo() {
        val conversation = Dialogue(step(SELECT_PPSE, directory(VISA)),
            step(select(VISA), fci(VISA, pan(PAN_A))))
        assertTrue(bank(conversation.reader(allowGpo = false).read()).number == PAN_A)
        conversation.complete()
        assertEquals(2, conversation.commands.size)
    }

    @Test fun trackOnlyAndOddLengthPanFieldsAreAcceptedWithoutRetainingTrackData() {
        listOf(0x57, 0x9F6B).forEach { tag ->
            val conversation = standard(tlv(0x70, tlv(tag, track(PAN_A))))
            val result = bank(conversation.reader().read())
            assertTrue(result.number == PAN_A)
            assertEquals(12, result.expiryMonth)
            assertEquals(2029, result.expiryYear)
            conversation.complete()
        }
        val odd = "4000000000000000006"
        val conversation = standard(tlv(0x70, pan(odd)))
        assertTrue(bank(conversation.reader().read()).number == odd)
        conversation.complete()
    }

    @Test fun pdolDeclaresOnlyContactlessEmvAndKeepsAmountsAndUnknownDataZero() {
        val pdol = tlv(0x9F38, hex("9F66049F0206DF01049F33039F7A01"))
        val expected = hex("80A8000014831220000000") + ByteArray(14) + hex("00")
        val conversation = Dialogue(step(SELECT_PPSE, directory(VISA)), step(select(VISA), fci(VISA, pdol)),
            step(expected, gpoResponse()), step(READ_RECORD, tlv(0x70, pan(PAN_A))))
        assertTrue(bank(conversation.reader().read()).number == PAN_A)
        conversation.complete()
        conversation.assertReadCommandsOnly()
    }

    @Test fun pdolLongTemplateLengthAndFormatOneGpoAreEncodedAndReadCorrectly() {
        val pdol = tlv(0x9F38, hex("DF0180"))
        val expected = hex("80A8000083838180") + ByteArray(128) + hex("00")
        val conversation = Dialogue(step(SELECT_PPSE, directory(VISA)), step(select(VISA), fci(VISA, pdol)),
            step(expected, tlv(0x80, hex("000008010100"))), step(READ_RECORD, tlv(0x70, pan(PAN_A))))
        assertTrue(bank(conversation.reader().read()).number == PAN_A)
        conversation.complete()
    }

    @Test fun absentPpseUsesOnlyTheBoundedSelectedPaymentApplications() {
        val steps = mutableListOf(rawStep(SELECT_PPSE, "6A82"))
        FALLBACK.forEach { aid ->
            steps += if (aid.contentEquals(MASTERCARD)) step(select(aid), fci(aid, pan(PAN_B)))
                else rawStep(select(aid), "6A82")
        }
        val conversation = Dialogue(*steps.toTypedArray())
        val result = bank(conversation.reader().read())
        assertTrue(result.number == PAN_B)
        assertEquals("Mastercard", result.scheme)
        conversation.complete()
        assertEquals(1 + FALLBACK.size, conversation.commands.size)
        conversation.assertReadCommandsOnly()
    }

    @Test fun unknownIdentityApplicationAndMissingCardNumberDoNotBecomeCardNumbers() {
        val unknown = Dialogue(step(SELECT_PPSE, directory(hex("A0000002471001"))))
        assertSame(EmvCardReadResult.Unsupported, unknown.reader().read())
        unknown.complete()
        assertEquals(1, unknown.commands.size)
        val noPan = standard(tlv(0x70, tlv(0x5F34, hex("01")) + tlv(0x9F4D, hex("1E0A"))))
        assertSame(EmvCardReadResult.Unsupported, noPan.reader().read())
        noPan.complete()
        assertEquals("The log pointer is not followed", 4, noPan.commands.size)
    }

    @Test fun aSingle6cRetriesOnlyLeAnd61UsesBoundedGetResponse() {
        val bytes = directory(VISA)
        val first = bytes.copyOfRange(0, 4)
        val tail = bytes.copyOfRange(4, bytes.size)
        val corrected = SELECT_PPSE.copyOf().also { it[it.lastIndex] = 0x20 }
        val conversation = Dialogue(rawStep(SELECT_PPSE, "6C20"),
            Step(corrected, first + byteArrayOf(0x61, tail.size.toByte())),
            step(byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, tail.size.toByte()), tail),
            step(select(VISA), fci(VISA, pan(PAN_A))))
        assertTrue(bank(conversation.reader().read()).number == PAN_A)
        conversation.complete()
        conversation.assertReadCommandsOnly()
    }

    @Test fun repeated6cAndEndless61TerminateWithoutUnboundedCardCommands() {
        val corrected = SELECT_PPSE.copyOf().also { it[it.lastIndex] = 0x10 }
        val twice = Dialogue(*(listOf(rawStep(SELECT_PPSE, "6C10"), rawStep(corrected, "6C10")) + rejectedFallback()).toTypedArray())
        assertSame(EmvCardReadResult.Unsupported, twice.reader().read())
        twice.complete()
        assertEquals(2 + FALLBACK.size, twice.commands.size)
        val chained = mutableListOf(rawStep(SELECT_PPSE, "6101"))
        repeat(4) { chained += rawStep(hex("00C0000001"), "6101") }
        val endless = Dialogue(*(chained + rejectedFallback()).toTypedArray())
        assertSame(EmvCardReadResult.Unsupported, endless.reader().read())
        endless.complete()
        assertEquals(5 + FALLBACK.size, endless.commands.size)
    }

    @Test fun refusedProcessingAndUnsuccessfulAdvertisedRecordReturnNoPartialCard() {
        val refused = Dialogue(step(SELECT_PPSE, directory(VISA)), step(select(VISA), fci(VISA)),
            rawStep(EMPTY_GPO, "6985"))
        assertSame(EmvCardReadResult.Unsupported, refused.reader().read())
        refused.complete()
        val missing = Dialogue(step(SELECT_PPSE, directory(VISA)),
            step(select(VISA), fci(VISA, pan(PAN_A) + tlv(0x94, AFL))), rawStep(READ_RECORD, "6A83"))
        assertSame(EmvCardReadResult.Unsupported, missing.reader().read())
        missing.complete()
    }

    @Test fun malformedBerLengthsDepthAndNodeCountsAreRejected() {
        val tooDeep = (1..10).fold(byteArrayOf()) { bytes, _ -> tlv(0xE1, bytes) }
        val excessiveNodes = ByteArray(0).let { base -> (1..130).fold(base) { bytes, _ -> bytes + tlv(0x50, byteArrayOf()) } }
        val badValues = listOf(hex("6F800000"), hex("6F82010000"), hex("6F810100"), hex("6F029F80"),
            tlv(0x6F, tooDeep), tlv(0x6F, excessiveNodes))
        badValues.forEach { payload ->
            val conversation = Dialogue(step(SELECT_PPSE, payload))
            assertSame(EmvCardReadResult.Malformed, conversation.reader().read())
            conversation.complete()
        }
    }

    @Test fun oversizedResponseAndOverBudgetPdolAreRejectedBeforeFurtherCommands() {
        val oversize = Dialogue(Step(SELECT_PPSE, ByteArray(2051)))
        assertSame(EmvCardReadResult.Malformed, oversize.reader().read())
        oversize.complete()
        listOf(hex("9F0281"), hex("9F66049F02")).forEach { pdol ->
            val conversation = Dialogue(step(SELECT_PPSE, directory(VISA)), step(select(VISA), fci(VISA, tlv(0x9F38, pdol))))
            assertSame(EmvCardReadResult.Malformed, conversation.reader().read())
            conversation.complete()
            assertFalse(conversation.commands.any { it[1] == 0xA8.toByte() })
        }
    }

    @Test fun invalidOrExcessiveAflNeverTriggersARecordScan() {
        listOf("00010100", "09010100", "08000100", "08020100", "08010102", "08011100", "080101").forEach { afl ->
            val conversation = Dialogue(step(SELECT_PPSE, directory(VISA)), step(select(VISA), fci(VISA)),
                step(EMPTY_GPO, gpoResponse(hex(afl))))
            assertSame(EmvCardReadResult.Malformed, conversation.reader().read())
            conversation.complete()
            assertFalse(conversation.commands.any { it[1] == 0xB2.toByte() })
        }
    }

    @Test fun mismatchedSelectedApplicationIdentityIsNotAcceptedAsPaymentData() {
        val conversation = Dialogue(step(SELECT_PPSE, directory(VISA)),
            step(select(VISA), fci(hex("A0000002471001"), pan(PAN_A))))
        assertSame(EmvCardReadResult.Malformed, conversation.reader().read())
        conversation.complete()
        assertEquals(2, conversation.commands.size)
    }

    @Test fun cumulativeResponseBytesStopBeforeAllAdvertisedRecordsAreRead() {
        val record = tlv(0x70, pan(PAN_A) + tlv(0x53, ByteArray(1800)))
        val steps = mutableListOf(step(SELECT_PPSE, directory(VISA)), step(select(VISA), fci(VISA)),
            step(EMPTY_GPO, gpoResponse(hex("08011000"))))
        for (number in 1..16) steps += step(byteArrayOf(0x00, 0xB2.toByte(), number.toByte(), 0x0C, 0x00), record)
        val conversation = Dialogue(*steps.toTypedArray())
        assertSame(EmvCardReadResult.Malformed, conversation.reader().read())
        assertTrue(conversation.commands.count { it[1] == 0xB2.toByte() } in 1..15)
        assertTrue(conversation.responses.sumOf { it.size } > 16_384)
        assertTrue(conversation.responses.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test fun totalExchangeBudgetAppliesAcrossApplicationsAndContinuationChains() {
        fun chain(command: ByteArray, payload: ByteArray): List<Step> = buildList {
            add(Step(command, payload.copyOfRange(0, 1) + hex("6101")))
            for (index in 1..3) add(Step(hex("00C0000001"), payload.copyOfRange(index, index + 1) + hex("6101")))
            add(step(hex("00C0000001"), payload.copyOfRange(4, payload.size)))
        }
        val steps = chain(SELECT_PPSE, directory(*FALLBACK.toTypedArray())).toMutableList()
        FALLBACK.forEach { aid ->
            steps += chain(select(aid), fci(aid))
            steps += rawStep(EMPTY_GPO, "6101")
            repeat(3) { steps += rawStep(hex("00C0000001"), "6101") }
            steps += rawStep(hex("00C0000001"), "6985")
        }
        val conversation = Dialogue(*steps.toTypedArray())
        assertSame(EmvCardReadResult.Malformed, conversation.reader().read())
        assertEquals(48, conversation.commands.size)
        conversation.assertReadCommandsOnly()
    }

    @Test fun conflictingPanAcrossPublicFieldsOrApplicationsIsNeverChosenArbitrarily() {
        val fields = standard(tlv(0x70, pan(PAN_A) + tlv(0x57, track(PAN_B))))
        assertSame(EmvCardReadResult.ConflictingData, fields.reader().read())
        fields.complete()
        val applications = Dialogue(step(SELECT_PPSE, directory(VISA, MASTERCARD)),
            step(select(VISA), fci(VISA, pan(PAN_A))), step(select(MASTERCARD), fci(MASTERCARD, pan(PAN_B))))
        assertSame(EmvCardReadResult.ConflictingData, applications.reader().read())
        applications.complete()
    }

    @Test fun malformedPanAndTrackDataCannotProduceAPlausibleNumber() {
        listOf(pan("4111111111111112"), tlv(0x5A, hex("411F111111111111")), tlv(0x5A, hex("0000000000000000")),
            tlv(0x57, hex("4111111111111111D29D2201")), tlv(0x9F6B, hex("4111111111111111D2912F")))
            .forEach { fields ->
                val conversation = standard(tlv(0x70, fields))
                assertSame(EmvCardReadResult.Malformed, conversation.reader().read())
                conversation.complete()
            }
    }

    @Test fun invalidOrConflictingOptionalExpiryAndHolderStayAbsent() {
        val invalid = standard(tlv(0x70, pan(PAN_A) + tlv(0x5F24, hex("291331")) + tlv(0x5F20, hex("0041"))))
        val invalidCard = bank(invalid.reader().read())
        assertNull(invalidCard.expiryMonth); assertNull(invalidCard.expiryYear); assertNull(invalidCard.holder)
        invalid.complete()
        val conflicts = standard(tlv(0x70, pan(PAN_A) + tlv(0x57, track(PAN_A)) + tlv(0x5F24, hex("300131")) +
            tlv(0x5F20, "SYNTHETIC ONE".toByteArray()) + tlv(0x5F20, "SYNTHETIC TWO".toByteArray())))
        val card = bank(conflicts.reader().read())
        assertTrue(card.number == PAN_A)
        assertNull(card.expiryMonth); assertNull(card.expiryYear); assertNull(card.holder)
        conflicts.complete()
    }

    @Test fun cancellationBeforeAndAfterTransceivePropagatesWithoutAnotherCommand() {
        val before = Dialogue()
        try {
            before.reader(check = { throw CancellationException() }).read()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        before.complete()
        val after = Dialogue(step(SELECT_PPSE, directory(VISA)))
        try {
            after.reader(check = { if (after.commands.isNotEmpty()) throw CancellationException() }).read()
            fail("Cancellation after a response must propagate")
        } catch (_: CancellationException) { }
        after.complete()
        assertTrue(after.responses.single().all { it == 0.toByte() })
    }

    @Test fun cancellationDuringTlvTraversalStopsBeforeSelectingAnApplication() {
        val conversation = Dialogue(step(SELECT_PPSE, directory(VISA)))
        var postResponseChecks = 0
        try {
            conversation.reader(check = {
                if (conversation.commands.isNotEmpty() && ++postResponseChecks == 4) throw CancellationException()
            }).read()
            fail("Cancellation during parsing must propagate")
        } catch (_: CancellationException) { }
        conversation.complete()
    }

    @Test fun unionPayProductsAndIssuerSuffixesUseFullAdvertisedAidAndDomesticPdol() {
        for (aid in listOf("A000000333010101", "A000000333010102", "A000000333010103", "A0000003330101010101")) {
            val selected = hex(aid)
            val pdol = tlv(0x9F38, hex("9F66049F1A025F2A029F02069F03069F7A01"))
            val expected = hex("80A800001783152000000001560156") + ByteArray(13) + hex("00")
            val conversation = Dialogue(step(SELECT_PPSE, directory(selected)), step(select(selected), fci(selected, pdol)),
                step(expected, gpoResponse()), step(READ_RECORD, tlv(0x70, pan(PAN_UNIONPAY) + tlv(0x5F24, hex("300131")))))
            val card = bank(conversation.reader().read())
            assertEquals(PAN_UNIONPAY, card.number)
            assertEquals("UnionPay", card.scheme)
            assertEquals(2030, card.expiryYear)
            conversation.complete()
            conversation.assertReadCommandsOnly()
        }
    }

    @Test fun noDirectoryStillTriesDiscoverAndAdditionalUnionPayProduct() {
        for (target in listOf(hex("A000000333010103"), hex("A0000001523010"))) {
            val conversation = Dialogue(*(listOf(rawStep(SELECT_PPSE, "6A82")) + FALLBACK.map { aid ->
                if (aid.contentEquals(target)) step(select(aid), fci(aid, pan(PAN_A))) else rawStep(select(aid), "6A82")
            }).toTypedArray())
            assertEquals(PAN_A, bank(conversation.reader().read()).number)
            conversation.complete()
            conversation.assertReadCommandsOnly()
        }
    }

    @Test fun unionPayLuhnExceptionRequiresVerifiedUnionPayApplicationAndConsistentPan() {
        val nonLuhn = "6200000000000000"
        val union = hex("A000000333010101")
        val accepted = Dialogue(step(SELECT_PPSE, directory(union)), step(select(union), fci(union, pan(nonLuhn))))
        assertEquals(nonLuhn, bank(accepted.reader().read()).number)
        accepted.complete()
        val visa = Dialogue(step(SELECT_PPSE, directory(VISA)), step(select(VISA), fci(VISA, pan(nonLuhn))))
        assertSame(EmvCardReadResult.Malformed, visa.reader().read())
        visa.complete()
        val tooShort = Dialogue(step(SELECT_PPSE, directory(union)), step(select(union), fci(union, pan("62000000000000"))))
        assertSame(EmvCardReadResult.Malformed, tooShort.reader().read())
        tooShort.complete()
        val conflicts = Dialogue(step(SELECT_PPSE, directory(union)),
            step(select(union), fci(union, pan(nonLuhn) + tlv(0x57, track(PAN_UNIONPAY)))))
        assertSame(EmvCardReadResult.ConflictingData, conflicts.reader().read())
        conflicts.complete()
    }

    @Test fun pdolNumericPaddingAndZeroLengthEntriesFollowDeclaredOffsets() {
        val aid = hex("A000000333010101")
        val pdol = tlv(0x9F38, hex("9F66009F1A015F2A039F6606DF0101"))
        val expected = hex("80A800000D830B560001562000000000000000")
        val conversation = Dialogue(step(SELECT_PPSE, directory(aid)), step(select(aid), fci(aid, pdol)),
            step(expected, gpoResponse()), step(READ_RECORD, tlv(0x70, pan(PAN_UNIONPAY))))
        assertEquals(PAN_UNIONPAY, bank(conversation.reader().read()).number)
        conversation.complete()
    }

    @Test fun requestedDateTimeAndUnpredictableNumberAreBoundedAndNotEmptyPlaceholders() {
        val pdol = tlv(0x9F38, hex("9A039F21039F3704"))
        val conversation = Dialogue(step(SELECT_PPSE, directory(VISA)), step(select(VISA), fci(VISA, pdol)),
            Step(byteArrayOf(), gpoResponse() + hex("9000")) { command ->
                assertEquals(18, command.size)
                assertArrayEquals(hex("80A800000C830A"), command.copyOfRange(0, 7))
                val digits = command.copyOfRange(7, 13).map { (it.toInt() and 255).let { value ->
                    assertTrue(value shr 4 <= 9 && value and 15 <= 9)
                    (value shr 4) * 10 + (value and 15)
                } }
                assertTrue(digits[1] in 1..12 && digits[2] in 1..31)
                assertTrue(digits[3] in 0..23 && digits[4] in 0..59 && digits[5] in 0..59)
                assertEquals(0, command.last().toInt())
            }, step(READ_RECORD, tlv(0x70, pan(PAN_A))))
        assertEquals(PAN_A, bank(conversation.reader().read()).number)
        conversation.complete()
    }

    @Test fun trackOneReturnsOnlyPanNameAndExpiryIncludingWithoutStartSentinel() {
        for (prefix in listOf("%B", "B")) {
            val record = tlv(0x70, tlv(0x56, (prefix + PAN_A + "^SYNTHETIC/HOLDER^29122010000000?").toByteArray()))
            val conversation = standard(record)
            val card = bank(conversation.reader().read())
            assertEquals(PAN_A, card.number)
            assertEquals("SYNTHETIC/HOLDER", card.holder)
            assertEquals(12, card.expiryMonth)
            assertEquals(2029, card.expiryYear)
            conversation.complete()
            assertTrue(conversation.responses.all { bytes -> bytes.all { it == 0.toByte() } })
        }
    }

    @Test fun malformedOrConflictingTrackOneNeverProvidesAPartialCard() {
        for (value in listOf("B${PAN_A}^NAME^2912", "B${PAN_A}^NAME^2912X01", "B${PAN_A}^NAME^2912201?EXTRA")) {
            val conversation = standard(tlv(0x70, tlv(0x56, value.toByteArray())))
            assertSame(EmvCardReadResult.Malformed, conversation.reader().read())
            conversation.complete()
        }
        val conflict = standard(tlv(0x70, pan(PAN_A) + tlv(0x56, "B${PAN_B}^NAME^2912201?".toByteArray())))
        assertSame(EmvCardReadResult.ConflictingData, conflict.reader().read())
        conflict.complete()
    }

    @Test fun excessApplicationDirectoryIsRejectedAndDuplicateAidIsReadOnce() {
        val tooMany = Dialogue(step(SELECT_PPSE, directory(*(1..13).map { hex("A0000003330101") + byteArrayOf(it.toByte()) }.toTypedArray())))
        assertSame(EmvCardReadResult.Malformed, tooMany.reader().read())
        tooMany.complete()
        val duplicate = Dialogue(step(SELECT_PPSE, directory(VISA, VISA)), step(select(VISA), fci(VISA, pan(PAN_A))))
        assertEquals(PAN_A, bank(duplicate.reader().read()).number)
        duplicate.complete()
    }

    private class Step(val command: ByteArray, val response: ByteArray, val check: ((ByteArray) -> Unit)? = null)
    private class Dialogue(vararg steps: Step) {
        private val expected = steps.toList()
        val commands = mutableListOf<ByteArray>()
        val responses = mutableListOf<ByteArray>()
        fun reader(allowGpo: Boolean = true, check: () -> Unit = {}) = EmvCardReader(EmvCardChannel { command ->
            assertTrue("Reader exceeded the synthetic APDU plan", commands.size < expected.size)
            val step = expected[commands.size]
            if (step.check != null) step.check.invoke(command)
            else assertTrue("Unexpected read command", step.command.contentEquals(command))
            commands += command.copyOf()
            step.response.copyOf().also(responses::add)
        }, checkCurrent = check, allowGpo = allowGpo)
        fun complete() { assertEquals("The planned public reads must complete", expected.size, commands.size) }
        fun assertReadCommandsOnly() {
            commands.forEach { command ->
                assertTrue(command[1].toInt() and 255 in setOf(0xA4, 0xA8, 0xB2, 0xC0))
                assertFalse(command[1].toInt() and 255 in setOf(0x20, 0x84, 0x88, 0xAE, 0xCA, 0xD6, 0xDC))
            }
        }
    }

    private fun bank(result: EmvCardReadResult): EmvCardReadResult.BankCard {
        assertTrue("Expected public bank card fields", result is EmvCardReadResult.BankCard)
        return result as EmvCardReadResult.BankCard
    }
    private fun standard(record: ByteArray) = Dialogue(step(SELECT_PPSE, directory(VISA)), step(select(VISA), fci(VISA)),
        step(EMPTY_GPO, gpoResponse()), step(READ_RECORD, record))
    private fun rejectedFallback() = FALLBACK.map { rawStep(select(it), "6A82") }
    private fun rawStep(command: ByteArray, response: String) = Step(command, hex(response))
    private fun step(command: ByteArray, payload: ByteArray) = Step(command, payload + hex("9000"))
    private fun directory(vararg aids: ByteArray) = tlv(0x6F, tlv(0x84, "2PAY.SYS.DDF01".toByteArray()) +
        tlv(0xA5, tlv(0xBF0C, aids.fold(byteArrayOf()) { bytes, aid -> bytes + tlv(0x61, tlv(0x4F, aid)) })))
    private fun fci(aid: ByteArray, values: ByteArray = byteArrayOf()) = tlv(0x6F, tlv(0x84, aid) + tlv(0xA5, values))
    private fun gpoResponse(afl: ByteArray = AFL) = tlv(0x77, tlv(0x82, hex("0000")) + tlv(0x94, afl))
    private fun pan(number: String) = tlv(0x5A, hex(number + if (number.length % 2 == 1) "F" else ""))
    private fun track(number: String): ByteArray {
        val value = number + "D2912" + "201" + "0000000"
        return hex(value + if (value.length % 2 == 1) "F" else "")
    }
    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val bytes = hex(tag.toString(16).let { if (it.length % 2 == 1) "0$it" else it })
        val length = when {
            value.size < 128 -> byteArrayOf(value.size.toByte())
            value.size < 256 -> byteArrayOf(0x81.toByte(), value.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (value.size shr 8).toByte(), value.size.toByte())
        }
        return bytes + length + value
    }

    private companion object {
        const val PAN_A = "4111111111111111"
        const val PAN_B = "5555555555554444"
        const val PAN_UNIONPAY = "6200000000000005"
        val VISA = hex("A0000000031010")
        val MASTERCARD = hex("A0000000041010")
        val FALLBACK = listOf(VISA, MASTERCARD, hex("A000000333010101"), hex("A000000333010102"),
            hex("A000000333010103"), hex("A00000002501"), hex("A0000000651010"),
            hex("A0000000032010"), hex("A0000000043060"), hex("A0000001523010"))
        val AFL = hex("08010100")
        val SELECT_PPSE = select("2PAY.SYS.DDF01".toByteArray())
        val EMPTY_GPO = hex("80A8000002830000")
        val READ_RECORD = hex("00B2010C00")
        fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun select(aid: ByteArray) = hex("00A40400") + byteArrayOf(aid.size.toByte()) + aid + hex("00")
    }
}
