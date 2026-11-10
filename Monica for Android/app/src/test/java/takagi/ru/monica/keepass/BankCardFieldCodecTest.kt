package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.CardType
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.SecureCustomField
import takagi.ru.monica.data.model.SecureCustomFieldType
import takagi.ru.monica.utils.KeePassEntryResolutionContext
import takagi.ru.monica.utils.KeePassFieldReferenceResolver

class BankCardFieldCodecTest {
    @Test
    fun aliasesKeepExistingPrecedenceAndExplicitExpiryPartsOverrideIndependently() {
        val canonical = readCard(entry(
            "CardNumber" to "alias-number",
            "Card Number" to "canonical-number",
            "Credit Card Holder" to "Alias Holder",
            "Card Holder" to "Canonical Holder",
            "CVC" to "111",
            "Card CVV" to "222",
            "Card Expiry" to "09/2031",
            "Expiry Month" to "04",
            "Card Type" to " debit card "
        ))
        assertEquals("canonical-number", canonical.cardNumber)
        assertEquals("Canonical Holder", canonical.cardholderName)
        assertEquals("222", canonical.cvv)
        assertEquals("04", canonical.expiryMonth)
        assertEquals("2031", canonical.expiryYear)
        assertEquals(CardType.DEBIT, canonical.cardType)
        assertTrue(canonical.customFields.isEmpty())

        val exactAlias = readCard(entry(
            "cArD nUmBeR" to "mixed-case-number",
            "Card Number" to " ",
            "CardNumber" to "exact-alias-number",
            "CardHolder" to "Alias Holder",
            "CardCVV" to "333",
            "Expiration Date" to " 07 - 2032 ",
            "Expiry Year" to "2040",
            "Card Type" to "prepaid card"
        ))
        assertEquals("exact-alias-number", exactAlias.cardNumber)
        assertEquals("Alias Holder", exactAlias.cardholderName)
        assertEquals("333", exactAlias.cvv)
        assertEquals("07", exactAlias.expiryMonth)
        assertEquals("2040", exactAlias.expiryYear)
        assertEquals(CardType.PREPAID, exactAlias.cardType)
        assertTrue(exactAlias.customFields.isEmpty())

        for ((stored, expected) in listOf(
            " 08.2033 " to ("08" to "2033"),
            "06 2034 ignored" to ("06" to "2034"),
            "2035" to ("" to "2035"),
            " " to ("" to "")
        )) {
            val card = readCard(entry(
                "creditcardnumber" to "case-insensitive-number",
                "creditcardholder" to "Case Holder",
                "cvv" to "444",
                "Expiry Date" to stored,
                "Card Type" to "unknown"
            ))
            assertEquals("case-insensitive-number", card.cardNumber)
            assertEquals("Case Holder", card.cardholderName)
            assertEquals("444", card.cvv)
            assertEquals("month for $stored", expected.first, card.expiryMonth)
            assertEquals("year for $stored", expected.second, card.expiryYear)
            assertEquals(CardType.CREDIT, card.cardType)
            assertTrue(card.customFields.isEmpty())
        }
    }

    @Test
    fun writingProtectsSensitiveFieldsAndKeepsFirstCustomNameWithoutReplacingNativeFields() {
        val card = BankCardData(
            cardNumber = "4111111111111111", cardholderName = "Printed Name",
            expiryMonth = "09", expiryYear = "2031", cvv = "123",
            bankName = "Test Bank", bankBranch = "West Branch", cardType = CardType.DEBIT,
            cardholderFirstName = "Independent", cardholderMiddleName = "Middle",
            cardholderLastName = "Surname", billingAddress = "10 Test Lane",
            brand = "Visa", nickname = "Travel", validFromMonth = "01", validFromYear = "2024",
            pin = "4321", iban = "DE001234", swiftBic = "TESTBIC", routingNumber = "110000",
            accountNumber = "556677", branchCode = "BR-4", currency = "EUR",
            customerServicePhone = "+1 555 0100",
            customFields = listOf(
                SecureCustomField(" title ", "must-not-replace-title"),
                SecureCustomField("PASSWORD", "must-not-replace-password"),
                SecureCustomField(" card number ", "must-not-replace-number"),
                SecureCustomField(" Loyalty ", "first"),
                SecureCustomField("loyalty", "second", SecureCustomFieldType.HIDDEN),
                SecureCustomField("Secret note", "protected-note", SecureCustomFieldType.HIDDEN),
                SecureCustomField("Enabled", "true", SecureCustomFieldType.BOOLEAN),
                SecureCustomField("Empty", ""),
                SecureCustomField("_etm_template", "template-internal"),
                SecureCustomField("_ETM_visible", "case-sensitive-prefix"),
                SecureCustomField("  ", "invalid-label")
            )
        )
        val pairs = mutableListOf<Pair<String, EntryValue>>(
            "Title" to EntryValue.Plain("Existing title"),
            "Password" to encrypted("existing-password")
        )
        KeePassBankCardFieldCodec.appendBankCardFields(pairs, card)
        val fields = EntryFields.of(*pairs.toTypedArray())
        val protectedValues = linkedMapOf(
            "Card Number" to "4111111111111111", "Card CVV" to "123", "PIN" to "4321",
            "IBAN" to "DE001234", "SWIFT/BIC" to "TESTBIC", "Routing Number" to "110000",
            "Account Number" to "556677", "Password" to "existing-password",
            "Secret note" to "protected-note"
        )
        val plainValues = linkedMapOf(
            "Title" to "Existing title", "Card Holder" to "Printed Name",
            "Cardholder First Name" to "Independent", "Cardholder Middle Name" to "Middle",
            "Cardholder Last Name" to "Surname", "Expiry Month" to "09", "Expiry Year" to "2031",
            "Bank Name" to "Test Bank", "Bank Branch" to "West Branch", "Card Type" to "DEBIT",
            "Billing Address" to "10 Test Lane", "Brand" to "Visa", "Nickname" to "Travel",
            "Valid From Month" to "01", "Valid From Year" to "2024", "Branch Code" to "BR-4",
            "Currency" to "EUR", "Customer Service Phone" to "+1 555 0100", "Loyalty" to "first",
            "Enabled" to "true", "Empty" to "", "_ETM_visible" to "case-sensitive-prefix"
        )
        assertEquals("No duplicate keys may be hidden by EntryFields.of", fields.size, pairs.size)
        assertEquals(protectedValues + plainValues, fields.entries.associate { it.key to it.value.content })
        protectedValues.keys.forEach { assertTrue("$it must be protected", fields[it] is EntryValue.Encrypted) }
        plainValues.keys.forEach { assertTrue("$it must be plain", fields[it] is EntryValue.Plain) }
        assertEquals(listOf("Loyalty", "Secret note", "Enabled", "Empty", "_ETM_visible"),
            pairs.takeLast(5).map { it.first })
        assertFalse(fields.containsKey("_etm_template"))
        assertEquals(card.copy(customFields = listOf(
            SecureCustomField("Loyalty", "first"),
            SecureCustomField("Secret note", "protected-note", SecureCustomFieldType.HIDDEN),
            SecureCustomField("Enabled", "true"),
            SecureCustomField("_ETM_visible", "case-sensitive-prefix")
        )), readCard(Entry(UUID.randomUUID(), fields = fields)))
    }

    @Test
    fun referencesResolveThroughContextWithoutRewritingOriginalValuesOrProtection() {
        val source = entry(
            "Title" to "Card Source", "Password" to "4111222233334444",
            "UserName" to "Reference Holder", "Notes" to "reference-note"
        )
        val numberReference = "{REF:P@I:${source.uuid}}"
        val protectedNumber = encrypted(numberReference)
        val protectedNote = encrypted("{REF:N@T:Card Source}")
        val unresolved = "{REF:P@T:missing-source}"
        val consumer = Entry(UUID.randomUUID(), fields = EntryFields.of(
            "Title" to EntryValue.Plain("Card"),
            "UserName" to EntryValue.Plain("not-a-custom-field"),
            "Password" to encrypted("not-a-custom-password"),
            "URL" to EntryValue.Plain("https://example.invalid"),
            "Notes" to EntryValue.Plain("not-a-custom-note"),
            "MonicaSecureItemId" to EntryValue.Plain("42"),
            "MonicaItemData" to EntryValue.Plain("reserved-payload"),
            "_etm_template" to EntryValue.Plain("reserved-template"),
            "CreditCardNumber" to protectedNumber,
            "CreditCardHolder" to EntryValue.Plain("{REF:U@T:Card Source}"),
            "CVC" to EntryValue.Plain("{S:PIN}"),
            "PIN" to encrypted("987"),
            "Reference note" to protectedNote,
            "Unresolved" to EntryValue.Plain(unresolved)
        ))
        val before = consumer.fields.map { (key, value) -> Triple(key, value.content, value is EntryValue.Encrypted) }
        val context = KeePassFieldReferenceResolver.buildContext(listOf(source, consumer))
        val decoded = readCard(consumer, context)
        assertEquals("4111222233334444", decoded.cardNumber)
        assertEquals("Reference Holder", decoded.cardholderName)
        assertEquals("987", decoded.cvv)
        assertEquals("987", decoded.pin)
        assertEquals(listOf(
            SecureCustomField("Reference note", "reference-note", SecureCustomFieldType.HIDDEN),
            SecureCustomField("Unresolved", unresolved)
        ), decoded.customFields)
        assertEquals(before, consumer.fields.map { (key, value) -> Triple(key, value.content, value is EntryValue.Encrypted) })
        assertSame(protectedNumber, consumer.fields["CreditCardNumber"])
        assertSame(protectedNote, consumer.fields["Reference note"])
        assertEquals(numberReference, consumer.fields.getValue("CreditCardNumber").content)
        assertEquals("{REF:N@T:Card Source}", consumer.fields.getValue("Reference note").content)
        assertEquals(numberReference, readCard(consumer).cardNumber)
    }

    @Test
    fun onlyTypeOrValidFromOrBlankCustomValuesDoNotCreateACard() {
        for (fields in listOf(
            arrayOf("Title" to "Ordinary entry", "Notes" to "ordinary notes"),
            arrayOf("Card Type" to "DEBIT"),
            arrayOf("Valid From Month" to "01", "Valid From Year" to "2024"),
            arrayOf("Custom" to "  ", "_etm_template" to "template"),
            arrayOf("Card Number" to " ", "Card Holder" to "", "Card Expiry" to " ")
        )) {
            assertNull(fields.contentToString(),
                KeePassBankCardFieldCodec.buildBankCardItemDataFromEntry(entry(*fields), null))
        }
        val customOnly = readCard(entry(
            "Valid From Month" to "01", "Valid From Year" to "2024", "Custom" to "present"
        ))
        assertEquals(BankCardData(
            cardNumber = "", cardholderName = "", expiryMonth = "", expiryYear = "",
            validFromMonth = "01", validFromYear = "2024",
            customFields = listOf(SecureCustomField("Custom", "present"))
        ), customOnly)
    }

    private fun entry(vararg fields: Pair<String, String>) = Entry(
        UUID.randomUUID(),
        fields = EntryFields.of(*fields.map { it.first to EntryValue.Plain(it.second) }.toTypedArray())
    )

    private fun encrypted(value: String) = EntryValue.Encrypted(EncryptedValue.fromString(value))

    private fun readCard(entry: Entry, context: KeePassEntryResolutionContext? = null): BankCardData {
        val raw = requireNotNull(KeePassBankCardFieldCodec.buildBankCardItemDataFromEntry(entry, context))
        return requireNotNull(CardWalletDataCodec.parseBankCardData(raw))
    }
}
