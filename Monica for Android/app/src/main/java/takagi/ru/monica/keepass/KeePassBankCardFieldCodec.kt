package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.CardType
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.SecureCustomField
import takagi.ru.monica.data.model.SecureCustomFieldType
import takagi.ru.monica.data.model.formatForDisplay
import java.util.Locale
import takagi.ru.monica.utils.KeePassEntryResolutionContext
import takagi.ru.monica.utils.KeePassFieldReferenceResolver.getFieldValue
import takagi.ru.monica.utils.KeePassFieldReferenceResolver.getFieldValueIgnoreCase
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_ITEM_ID
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_ITEM_TYPE
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_ITEM_DATA
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_ITEM_NOTES
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_IMAGE_PATHS
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_MONICA_IS_FAVORITE
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_BANK_NAME
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_BANK_BRANCH
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_CARD_TYPE
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_BILLING_ADDRESS
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_BRAND
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_NICKNAME
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_VALID_FROM_MONTH
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_VALID_FROM_YEAR
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_PIN
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_IBAN
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_SWIFT_BIC
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_ROUTING_NUMBER
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_ACCOUNT_NUMBER
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_BRANCH_CODE
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_CURRENCY
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_CUSTOMER_SERVICE_PHONE
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_CARD_NUMBER
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_CARD_HOLDER
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_CARD_EXPIRY
import takagi.ru.monica.keepass.KeePassCompatibilityFieldNames.FIELD_CARD_CVV

/** Native card field names, aliases and custom-field rules. No image, key or storage access. */
internal object KeePassBankCardFieldCodec {
    fun appendBankCardFields(
        pairs: MutableList<Pair<String, EntryValue>>,
        cardData: BankCardData
    ) {
        fun addPlain(name: String, value: String) {
            if (value.isNotBlank()) {
                pairs += name to EntryValue.Plain(value)
            }
        }

        fun addProtected(name: String, value: String) {
            if (value.isNotBlank()) {
                pairs += name to EntryValue.Encrypted(EncryptedValue.fromString(value))
            }
        }

        val billingAddressDisplay = CardWalletDataCodec.parseBillingAddress(cardData.billingAddress)
            .formatForDisplay()
            .ifBlank { cardData.billingAddress }

        addProtected(FIELD_CARD_NUMBER, cardData.cardNumber)
        addPlain(FIELD_CARD_HOLDER, cardData.cardholderName)
        addPlain("Cardholder First Name", cardData.cardholderFirstName)
        addPlain("Cardholder Middle Name", cardData.cardholderMiddleName)
        addPlain("Cardholder Last Name", cardData.cardholderLastName)

        addPlain("Expiry Month", cardData.expiryMonth)
        addPlain("Expiry Year", cardData.expiryYear)
        addProtected(FIELD_CARD_CVV, cardData.cvv)
        addPlain(FIELD_BANK_NAME, cardData.bankName)
        addPlain(FIELD_BANK_BRANCH, cardData.bankBranch)
        addPlain(FIELD_CARD_TYPE, cardData.cardType.name)
        addPlain(FIELD_BILLING_ADDRESS, billingAddressDisplay)
        addPlain(FIELD_BRAND, cardData.brand)
        addPlain(FIELD_NICKNAME, cardData.nickname)
        addPlain(FIELD_VALID_FROM_MONTH, cardData.validFromMonth)
        addPlain(FIELD_VALID_FROM_YEAR, cardData.validFromYear)
        addProtected(FIELD_PIN, cardData.pin)
        addProtected(FIELD_IBAN, cardData.iban)
        addProtected(FIELD_SWIFT_BIC, cardData.swiftBic)
        addProtected(FIELD_ROUTING_NUMBER, cardData.routingNumber)
        addProtected(FIELD_ACCOUNT_NUMBER, cardData.accountNumber)
        addPlain(FIELD_BRANCH_CODE, cardData.branchCode)
        addPlain(FIELD_CURRENCY, cardData.currency)
        addPlain(FIELD_CUSTOMER_SERVICE_PHONE, cardData.customerServicePhone)
        appendSecureItemCustomFields(pairs, cardData.customFields)
    }

    private fun appendSecureItemCustomFields(
        pairs: MutableList<Pair<String, EntryValue>>,
        customFields: List<SecureCustomField>
    ) {
        val usedKeys = pairs.mapTo(mutableSetOf()) { it.first.trim().lowercase(Locale.ROOT) }
        customFields
            .asSequence()
            .filter { it.isValid() }
            .forEach { field ->
                val key = field.label.trim()
                if (key.isBlank() || key.startsWith("_etm_")) return@forEach
                if (!usedKeys.add(key.lowercase(Locale.ROOT))) return@forEach
                val value = if (field.type == SecureCustomFieldType.HIDDEN) {
                    EntryValue.Encrypted(EncryptedValue.fromString(field.value))
                } else {
                    EntryValue.Plain(field.value)
                }
                pairs += key to value
            }
    }

    fun buildBankCardItemDataFromEntry(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext?
    ): String? {
        val expiryMonth = getFieldValueIgnoreCase(entry, resolutionContext, "Expiry Month")
        val expiryYear = getFieldValueIgnoreCase(entry, resolutionContext, "Expiry Year")
        val fallbackExpiry = splitCardExpiry(
            getFieldValueIgnoreCase(
                entry,
                resolutionContext,
                FIELD_CARD_EXPIRY,
                "CardExpiry",
                "Expiration Date",
                "Expiry Date"
            )
        )
        val data = BankCardData(
            cardNumber = getFieldValueIgnoreCase(
                entry,
                resolutionContext,
                FIELD_CARD_NUMBER,
                "CardNumber",
                "Credit Card Number",
                "CreditCardNumber"
            ),
            cardholderName = getFieldValueIgnoreCase(
                entry,
                resolutionContext,
                FIELD_CARD_HOLDER,
                "CardHolder",
                "Credit Card Holder",
                "CreditCardHolder"
            ),
            expiryMonth = expiryMonth.ifBlank { fallbackExpiry.first },
            expiryYear = expiryYear.ifBlank { fallbackExpiry.second },
            cvv = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_CARD_CVV, "CardCVV", "CVV", "CVC"),
            bankName = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_BANK_NAME),
            bankBranch = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_BANK_BRANCH),
            cardholderFirstName = getFieldValueIgnoreCase(entry, resolutionContext, "Cardholder First Name"),
            cardholderMiddleName = getFieldValueIgnoreCase(entry, resolutionContext, "Cardholder Middle Name"),
            cardholderLastName = getFieldValueIgnoreCase(entry, resolutionContext, "Cardholder Last Name"),

            cardType = parseKeePassCardType(getFieldValueIgnoreCase(entry, resolutionContext, FIELD_CARD_TYPE)),
            billingAddress = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_BILLING_ADDRESS),
            brand = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_BRAND),
            nickname = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_NICKNAME),
            validFromMonth = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_VALID_FROM_MONTH),
            validFromYear = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_VALID_FROM_YEAR),
            pin = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_PIN),
            iban = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_IBAN),
            swiftBic = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_SWIFT_BIC),
            routingNumber = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_ROUTING_NUMBER),
            accountNumber = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_ACCOUNT_NUMBER),
            branchCode = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_BRANCH_CODE),
            currency = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_CURRENCY),
            customerServicePhone = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_CUSTOMER_SERVICE_PHONE),
            customFields = extractStructuredSecureItemCustomFields(entry, resolutionContext)
        )
        val hasAnyCardField = listOf(
            data.cardNumber,
            data.cardholderName,
            data.cardholderFirstName,
            data.cardholderMiddleName,
            data.cardholderLastName,

            data.expiryMonth,
            data.expiryYear,
            data.cvv,
            data.bankName,
            data.bankBranch,
            data.billingAddress,
            data.brand,
            data.nickname,
            data.pin,
            data.iban,
            data.swiftBic,
            data.routingNumber,
            data.accountNumber,
            data.branchCode,
            data.currency,
            data.customerServicePhone
        ).any { it.isNotBlank() } || data.customFields.isNotEmpty()

        return if (hasAnyCardField) {
            CardWalletDataCodec.encodeBankCardData(data)
        } else {
            null
        }
    }

    private fun splitCardExpiry(raw: String): Pair<String, String> {
        val normalized = raw.trim()
        if (normalized.isBlank()) return "" to ""
        val parts = normalized.split("/", "-", ".", " ")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (parts.size < 2) return "" to normalized
        return parts[0] to parts[1]
    }

    private fun parseKeePassCardType(raw: String): CardType {
        return when (raw.trim().uppercase(Locale.ROOT).replace(" ", "_")) {
            "DEBIT", "DEBIT_CARD" -> CardType.DEBIT
            "PREPAID", "PREPAID_CARD" -> CardType.PREPAID
            else -> CardType.CREDIT
        }
    }

    private fun extractStructuredSecureItemCustomFields(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext?
    ): List<SecureCustomField> {
        val reserved = (baseSecureItemFieldNames() + bankCardSecureItemFieldNames())
            .mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        return entry.fields.mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            if (normalizedKey.isBlank() || normalizedKey.startsWith("_etm_")) return@mapNotNull null
            if (normalizedKey.lowercase(Locale.ROOT) in reserved) return@mapNotNull null
            val content = getFieldValue(entry, key, resolutionContext)
            if (content.isBlank()) return@mapNotNull null
            SecureCustomField(
                label = normalizedKey,
                value = content,
                type = if (value is EntryValue.Encrypted) {
                    SecureCustomFieldType.HIDDEN
                } else {
                    SecureCustomFieldType.TEXT
                }
            )
        }
    }

    private fun baseSecureItemFieldNames(): Set<String> {
        return setOf(
            "Title",
            "UserName",
            "Password",
            "URL",
            "Notes",
            FIELD_MONICA_ITEM_TYPE,
            FIELD_MONICA_ITEM_DATA,
            FIELD_MONICA_ITEM_NOTES,
            FIELD_MONICA_IMAGE_PATHS,
            FIELD_MONICA_IS_FAVORITE,
            FIELD_MONICA_ITEM_ID
        )
    }

    private fun bankCardSecureItemFieldNames(): Set<String> {
        return setOf(
            FIELD_CARD_NUMBER,
            "CardNumber",
            "Credit Card Number",
            "CreditCardNumber",
            FIELD_CARD_HOLDER,
            "CardHolder",
            "Credit Card Holder",
            "CreditCardHolder",
            FIELD_CARD_EXPIRY,
            "CardExpiry",
            "Expiration Date",
            "Expiry Date",
            "Expiry Month",
            "Expiry Year",
            FIELD_CARD_CVV,
            "CardCVV",
            "CVV",
            "CVC",
            FIELD_BANK_NAME,
            FIELD_BANK_BRANCH,
            "Cardholder First Name", "Cardholder Middle Name", "Cardholder Last Name",
            FIELD_CARD_TYPE,
            FIELD_BILLING_ADDRESS,
            FIELD_BRAND,
            FIELD_NICKNAME,
            FIELD_VALID_FROM_MONTH,
            FIELD_VALID_FROM_YEAR,
            FIELD_PIN,
            FIELD_IBAN,
            FIELD_SWIFT_BIC,
            FIELD_ROUTING_NUMBER,
            FIELD_ACCOUNT_NUMBER,
            FIELD_BRANCH_CODE,
            FIELD_CURRENCY,
            FIELD_CUSTOMER_SERVICE_PHONE
        )
    }
}
