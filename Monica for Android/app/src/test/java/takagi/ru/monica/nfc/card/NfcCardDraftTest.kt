package takagi.ru.monica.nfc.card

import org.junit.Assert.*
import org.junit.Test

/** Form projection and routing policy only, with published synthetic test-card numbers. */
class NfcCardDraftTest {
    private val card = EmvCardReadResult.BankCard(NUMBER, 12, 2029, "SYNTHETIC HOLDER", "Visa")

    @Test fun emptyFormAcceptsOnlyThePublicFieldsProvidedByTheCard() {
        val before = NfcCardFields("", "", "", "")
        assertFalse(before.conflictsWith(card))
        val after = before.merge(card)
        assertTrue(after.number == NUMBER)
        assertEquals("12", after.month)
        assertEquals("2029", after.year)
        assertEquals("SYNTHETIC HOLDER", after.holder)
        assertEquals("", before.number)
        assertEquals("", before.month)
        assertEquals("", before.year)
        assertEquals("", before.holder)
    }

    @Test fun absentOptionalCardFieldsPreserveEveryExistingUserValue() {
        val before = NfcCardFields("", "03", "2034", "Existing synthetic holder")
        val numberOnly = EmvCardReadResult.BankCard(NUMBER)
        assertFalse(before.conflictsWith(numberOnly))
        val after = before.merge(numberOnly)
        assertTrue(after.number == NUMBER)
        assertEquals(before.month, after.month)
        assertEquals(before.year, after.year)
        assertEquals(before.holder, after.holder)
    }

    @Test fun blankHolderDoesNotEraseAnExistingHolderOrCauseAConflict() {
        val before = NfcCardFields(NUMBER, "12", "2029", "Keep this holder")
        listOf<String?>(null, "", " \t ").forEach { absent ->
            val read = EmvCardReadResult.BankCard(NUMBER, holder = absent)
            assertFalse(before.conflictsWith(read))
            assertEquals(before.holder, before.merge(read).holder)
        }
    }

    @Test fun equivalentGroupingAndTwoDigitYearDoNotRequireReplacement() {
        listOf(NUMBER, "4111 1111 1111 1111", "4111-1111-1111-1111", "  4111 1111 1111 1111  ").forEach { formatted ->
            assertFalse(NfcCardFields(formatted, "12", "29", " SYNTHETIC HOLDER ").conflictsWith(card))
        }
        val zeroPaddedMonth = NfcCardFields(NUMBER, "03", "2029", "SYNTHETIC HOLDER")
        assertFalse(zeroPaddedMonth.conflictsWith(EmvCardReadResult.BankCard(NUMBER, 3, 2029, "SYNTHETIC HOLDER")))
    }

    @Test fun nonFormattingCharactersAndMaskedOrUnicodeNumbersRemainConflicts() {
        listOf("4111x111111111111", "4111/1111/1111/1111", "4111 **** **** 1111", "４１１１１１１１１１１１１１１１").forEach { existing ->
            assertTrue("Ambiguous existing content must require explicit replacement",
                NfcCardFields(existing, "", "", "").conflictsWith(card))
        }
    }

    @Test fun anyDifferentProvidedValueRequiresReplacement() {
        val existing = listOf(
            NfcCardFields("5555555555554444", "12", "2029", "SYNTHETIC HOLDER"),
            NfcCardFields(NUMBER, "11", "2029", "SYNTHETIC HOLDER"),
            NfcCardFields(NUMBER, "12", "2030", "SYNTHETIC HOLDER"),
            NfcCardFields(NUMBER, "12", "2029", "ANOTHER HOLDER"),
            NfcCardFields(NUMBER, "not a month", "2029", "SYNTHETIC HOLDER"),
            NfcCardFields(NUMBER, "12", "not a year", "SYNTHETIC HOLDER"),
        )
        existing.forEach { assertTrue(it.conflictsWith(card)) }
    }

    @Test fun matchingFullHolderStillRequiresConfirmationWhenIndependentNamePartsExist() {
        val fullNameOnly = NfcCardFields(NUMBER, "12", "2029", "SYNTHETIC HOLDER")
        assertFalse(fullNameOnly.conflictsWith(card))
        // Replacing with the same full holder can still erase independent first/middle/last names.
        val withNameParts = NfcCardFields(NUMBER, "12", "2029", "SYNTHETIC HOLDER", hasNameParts = true)
        assertTrue(withNameParts.conflictsWith(card))
        assertEquals(NfcCardRouteDecision.REPLACE,
            nfcCardRouteDecision(NfcCardEditorKind.BANK, false, withNameParts.conflictsWith(card), false))
        listOf<String?>(null, "", " \t ").forEach { absent ->
            val numberOnly = EmvCardReadResult.BankCard(NUMBER, holder = absent)
            assertFalse("Absent holder leaves all existing name fields alone", withNameParts.conflictsWith(numberOnly))
            assertEquals(withNameParts.holder, withNameParts.merge(numberOnly).holder)
        }
    }

    @Test fun partialOptionalValuesUpdateOnlyTheirOwnFields() {
        val before = NfcCardFields("", "06", "2035", "Existing holder")
        val monthOnly = before.merge(EmvCardReadResult.BankCard(NUMBER, expiryMonth = 3))
        assertEquals("03", monthOnly.month)
        assertEquals("2035", monthOnly.year)
        assertEquals("Existing holder", monthOnly.holder)
        val yearOnly = before.merge(EmvCardReadResult.BankCard(NUMBER, expiryYear = 2031))
        assertEquals("06", yearOnly.month)
        assertEquals("2031", yearOnly.year)
        assertEquals("Existing holder", yearOnly.holder)
    }

    @Test fun busyEditorsNeverFillOrNavigateRegardlessOfOtherInputs() {
        listOf<NfcCardEditorKind?>(null, NfcCardEditorKind.BANK, NfcCardEditorKind.DOCUMENT).forEach { kind ->
            listOf(false, true).forEach { conflict ->
                listOf(false, true).forEach { allowNew ->
                    assertEquals(NfcCardRouteDecision.BUSY, nfcCardRouteDecision(kind, true, conflict, allowNew))
                }
            }
        }
    }

    @Test fun bankEditorFillsOnlyWithoutConflictAndOtherwiseRequestsReplacement() {
        listOf(false, true).forEach { allowNew ->
            assertEquals(NfcCardRouteDecision.FILL, nfcCardRouteDecision(NfcCardEditorKind.BANK, false, false, allowNew))
            assertEquals(NfcCardRouteDecision.REPLACE, nfcCardRouteDecision(NfcCardEditorKind.BANK, false, true, allowNew))
        }
    }

    @Test fun documentEditorAlwaysRequiresExplicitNewCardConfirmation() {
        listOf(false, true).forEach { conflict ->
            listOf(false, true).forEach { allowNew ->
                assertEquals(NfcCardRouteDecision.CONFIRM_NEW_CARD,
                    nfcCardRouteDecision(NfcCardEditorKind.DOCUMENT, false, conflict, allowNew))
            }
        }
    }

    @Test fun unregisteredPagesNavigateOnlyWhenTheyExplicitlyAllowANewCard() {
        listOf(false, true).forEach { conflict ->
            assertEquals(NfcCardRouteDecision.NEW_CARD, nfcCardRouteDecision(null, false, conflict, true))
            assertEquals(NfcCardRouteDecision.BUSY, nfcCardRouteDecision(null, false, conflict, false))
        }
    }

    @Test fun diagnosticStringDoesNotExposeFormOrCardValues() {
        val fields = NfcCardFields(NUMBER, "12", "2029", "SYNTHETIC HOLDER")
        assertEquals("NfcCardFields(redacted)", fields.toString())
        assertEquals("BankCard(redacted)", card.toString())
    }

    private companion object {
        const val NUMBER = "4111111111111111"
    }
}
