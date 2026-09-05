package takagi.ru.monica.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.CustomFieldDraft

object CardWalletDataCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun parseBankCardData(
        raw: String,
        decryptIfNeeded: ((String) -> String)? = null
    ): BankCardData? {
        val resolvedRaw = resolveStoredData(raw, decryptIfNeeded)
        return runCatching { json.decodeFromString<BankCardData>(resolvedRaw) }
            .map { data -> data.copy(cardFace = data.cardFace?.validatedOrNull()) }
            .getOrElse {
                parseLegacyBankCardData(resolvedRaw)
            }
    }

    fun parseDocumentData(
        raw: String,
        decryptIfNeeded: ((String) -> String)? = null
    ): DocumentData? {
        val resolvedRaw = resolveStoredData(raw, decryptIfNeeded)
        return runCatching { json.decodeFromString<DocumentData>(resolvedRaw) }
            .map { data -> data.copy(cardFace = data.cardFace?.validatedOrNull()) }
            .getOrElse {
                parseLegacyDocumentData(resolvedRaw)
            }
    }

    fun parseBillingAddressData(
        raw: String,
        decryptIfNeeded: ((String) -> String)? = null
    ): BillingAddressData? {
        val resolvedRaw = resolveStoredData(raw, decryptIfNeeded)
        return runCatching { json.decodeFromString<BillingAddressData>(resolvedRaw) }
            .map { data -> data.copy(cardFace = data.cardFace?.validatedOrNull()) }
            .getOrElse {
                parseLegacyBillingAddressData(resolvedRaw)
            }
    }

    fun parsePaymentAccountData(
        raw: String,
        decryptIfNeeded: ((String) -> String)? = null
    ): PaymentAccountData? {
        val resolvedRaw = resolveStoredData(raw, decryptIfNeeded)
        val decoded = runCatching { json.decodeFromString<PaymentAccountData>(resolvedRaw) }.getOrNull()
        val hasCurrentShape = resolvedRaw.hasAnyJsonKey(
            "paymentType",
            "provider",
            "accountName",
            "accountHolderName",
            "maskedAccountNumber",
            "linkedCardLast4"
        )
        val hasLegacyAliases = resolvedRaw.hasAnyJsonKey(
            "type",
            "accountType",
            "service",
            "brand",
            "network",
            "name",
            "holderName",
            "fullName",
            "nameOnAccount",
            "accountNumber",
            "swift",
            "bic"
        )
        if (decoded != null && (hasCurrentShape || (!hasLegacyAliases && !decoded.isEmpty()))) {
            return decoded
        }
        return parseLegacyPaymentAccountData(resolvedRaw)
    }

    fun encodeBankCardData(data: BankCardData): String = json.encodeToString(
        BankCardData.serializer(),
        data.copy(cardFace = data.cardFace?.validatedOrNull())
    )

    fun parseCardFaceConfig(raw: String): CardFaceConfig? = runCatching {
        json.decodeFromString<CardFaceConfig>(raw).validatedOrNull()
    }.getOrNull()

    fun encodeCardFaceConfig(config: CardFaceConfig): String =
        json.encodeToString(CardFaceConfig.serializer(), requireNotNull(config.validatedOrNull()))

    fun encodeDocumentData(data: DocumentData): String = json.encodeToString(
        DocumentData.serializer(),
        data.copy(cardFace = data.cardFace?.validatedOrNull())
    )

    fun encodeBillingAddressData(data: BillingAddressData): String =
        json.encodeToString(
            BillingAddressData.serializer(),
            data.copy(cardFace = data.cardFace?.validatedOrNull())
        )

    fun encodePaymentAccountData(data: PaymentAccountData): String =
        json.encodeToString(PaymentAccountData.serializer(), data)

    private fun resolveStoredData(
        raw: String,
        decryptIfNeeded: ((String) -> String)?
    ): String {
        return decryptIfNeeded
            ?.let { decrypt -> runCatching { decrypt(raw) }.getOrDefault(raw) }
            ?: raw
    }

    fun parseBillingAddress(raw: String): BillingAddress {
        if (raw.isBlank()) return BillingAddress()
        return runCatching { json.decodeFromString<BillingAddress>(raw) }.getOrDefault(BillingAddress())
    }

    fun encodeBillingAddress(address: BillingAddress): String {
        return if (address.isEmpty()) "" else json.encodeToString(BillingAddress.serializer(), address)
    }

    fun customFieldsToDrafts(fields: List<SecureCustomField>): List<CustomFieldDraft> {
        return fields.map { field ->
            CustomFieldDraft(
                id = CustomFieldDraft.nextTempId(),
                title = field.label,
                value = field.value,
                isProtected = field.isProtected()
            )
        }
    }

    fun draftsToCustomFields(drafts: List<CustomFieldDraft>): List<SecureCustomField> {
        return drafts
            .filter { it.isValid() }
            .map { draft ->
                SecureCustomField(
                    label = draft.title.trim(),
                    value = draft.value,
                    type = if (draft.isProtected) {
                        SecureCustomFieldType.HIDDEN
                    } else {
                        SecureCustomFieldType.TEXT
                    }
                )
            }
    }

    fun customFieldsToDisplay(fields: List<SecureCustomField>): List<CustomField> {
        return fields.mapIndexed { index, field ->
            CustomField(
                id = -(index + 1L),
                entryId = 0L,
                title = field.label,
                value = field.value,
                isProtected = field.isProtected(),
                sortOrder = index
            )
        }
    }

    private fun parseLegacyBankCardData(raw: String): BankCardData? {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        return BankCardData(
            cardNumber = obj.string("cardNumber", "number"),
            cardholderName = obj.string("cardholderName"),
            expiryMonth = obj.string("expiryMonth", "expMonth"),
            expiryYear = obj.string("expiryYear", "expYear"),
            cvv = obj.string("cvv", "code"),
            bankName = obj.string("bankName"),
            billingAddress = obj.string("billingAddress"),
            brand = obj.string("brand"),
            validFromMonth = obj.string("validFromMonth", "fromMonth"),
            validFromYear = obj.string("validFromYear", "fromYear"),
            customFields = parseEmbeddedCustomFields(obj),
            cardFace = parseEmbeddedCardFace(obj)
        )
    }

    private fun parseLegacyDocumentData(raw: String): DocumentData? {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val legacy = runCatching { json.decodeFromString<LegacyDocumentItemData>(raw) }.getOrNull()
        val firstName = legacy?.firstName ?: obj.string("firstName")
        val middleName = legacy?.middleName ?: obj.string("middleName")
        val lastName = legacy?.lastName ?: obj.string("lastName")
        val fullName = listOf(firstName, middleName, lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { obj.string("fullName", "name") }
        val documentType = parseDocumentType(
            legacy?.documentType
                ?: obj.string("documentType", "type")
        )

        return DocumentData(
            documentType = documentType,
            documentNumber = legacy?.documentNumber ?: obj.string("documentNumber", "number"),
            fullName = fullName,
            issuedDate = legacy?.issueDate ?: obj.string("issuedDate", "issueDate"),
            expiryDate = legacy?.expiryDate ?: obj.string("expiryDate"),
            issuedBy = legacy?.issuingAuthority ?: obj.string("issuedBy", "issuingAuthority"),
            nationality = obj.string("nationality"),
            additionalInfo = legacy?.additionalInfo ?: obj.string("additionalInfo"),
            title = legacy?.title ?: obj.string("title"),
            firstName = firstName,
            middleName = middleName,
            lastName = lastName,
            address1 = legacy?.address1 ?: obj.string("address1"),
            address2 = legacy?.address2 ?: obj.string("address2"),
            address3 = obj.string("address3"),
            city = legacy?.city ?: obj.string("city"),
            stateProvince = legacy?.state ?: obj.string("stateProvince", "state"),
            postalCode = legacy?.postalCode ?: obj.string("postalCode"),
            country = legacy?.country ?: obj.string("country"),
            company = legacy?.company ?: obj.string("company"),
            email = legacy?.email ?: obj.string("email"),
            phone = legacy?.phone ?: obj.string("phone"),
            ssn = legacy?.ssn ?: obj.string("ssn"),
            username = legacy?.username ?: obj.string("username"),
            passportNumber = legacy?.passportNumber ?: obj.string("passportNumber"),
            licenseNumber = legacy?.licenseNumber ?: obj.string("licenseNumber", "driverLicense"),
            customFields = parseEmbeddedCustomFields(obj),
            cardFace = parseEmbeddedCardFace(obj)
        )
    }

    private fun parseLegacyBillingAddressData(raw: String): BillingAddressData? {
        val address = runCatching { json.decodeFromString<BillingAddress>(raw) }.getOrNull()
        if (address != null && !address.isEmpty()) {
            return BillingAddressData(
                streetAddress = address.streetAddress,
                apartment = address.apartment,
                city = address.city,
                stateProvince = address.stateProvince,
                postalCode = address.postalCode,
                country = address.country
            )
        }

        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        return BillingAddressData(
            fullName = obj.string("fullName", "name"),
            company = obj.string("company", "organization"),
            streetAddress = obj.string("streetAddress", "address1", "addressLine1"),
            apartment = obj.string("apartment", "address2", "addressLine2"),
            city = obj.string("city"),
            stateProvince = obj.string("stateProvince", "state", "province", "region"),
            postalCode = obj.string("postalCode", "zip", "zipCode"),
            country = obj.string("country"),
            phone = obj.string("phone", "phoneNumber"),
            email = obj.string("email"),
            cardFace = parseEmbeddedCardFace(obj)
        ).takeUnless { it.isEmpty() }
    }

    private fun parseLegacyPaymentAccountData(raw: String): PaymentAccountData? {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val embeddedBillingAddress = obj.string("billingAddress")
        val billingAddress = embeddedBillingAddress.ifBlank {
            val address = BillingAddress(
                streetAddress = obj.string("streetAddress", "address1", "addressLine1"),
                apartment = obj.string("apartment", "address2", "addressLine2"),
                city = obj.string("city"),
                stateProvince = obj.string("stateProvince", "state", "province", "region"),
                postalCode = obj.string("postalCode", "zip", "zipCode"),
                country = obj.string("country")
            )
            encodeBillingAddress(address)
        }

        return PaymentAccountData(
            paymentType = parsePaymentAccountType(obj.string("paymentType", "accountType", "type")),
            provider = obj.string("provider", "service", "brand", "network"),
            accountName = obj.string("accountName", "name", "nickname", "title"),
            accountHolderName = obj.string("accountHolderName", "holderName", "fullName", "nameOnAccount"),
            email = obj.string("email"),
            phone = obj.string("phone", "phoneNumber"),
            username = obj.string("username", "userName", "login"),
            accountId = obj.string("accountId", "accountIdentifier", "id"),
            maskedAccountNumber = obj.string("maskedAccountNumber", "maskedNumber", "accountNumber"),
            linkedCardLast4 = obj.string("linkedCardLast4", "cardLast4", "last4"),
            routingNumber = obj.string("routingNumber"),
            iban = obj.string("iban"),
            swiftBic = obj.string("swiftBic", "swift", "bic"),
            billingAddress = billingAddress,
            website = obj.string("website", "url", "uri"),
            currency = obj.string("currency"),
            notes = obj.string("notes", "memo"),
            customFields = parseEmbeddedCustomFields(obj)
        ).takeUnless { it.isEmpty() }
    }

    private fun parseEmbeddedCustomFields(obj: JsonObject): List<SecureCustomField> {
        return runCatching {
            json.decodeFromString<List<SecureCustomField>>(obj["customFields"].toString())
        }.getOrElse {
            emptyList()
        }
    }

    private fun parseEmbeddedCardFace(obj: JsonObject): CardFaceConfig? {
        val element = obj["cardFace"] ?: obj["card_face"] ?: return null
        return runCatching {
            when (element) {
                is JsonPrimitive -> parseCardFaceConfig(element.content)
                else -> json.decodeFromJsonElement<CardFaceConfig>(element).validatedOrNull()
            }
        }.getOrNull()
    }

    private fun parseDocumentType(raw: String?): DocumentType {
        return when (raw?.trim()?.lowercase()) {
            "passport" -> DocumentType.PASSPORT
            "driver_license", "driverlicense", "license" -> DocumentType.DRIVER_LICENSE
            "social_security", "socialsecurity", "ssn" -> DocumentType.SOCIAL_SECURITY
            "other" -> DocumentType.OTHER
            else -> DocumentType.ID_CARD
        }
    }

    private fun parsePaymentAccountType(raw: String?): PaymentAccountType {
        return when (raw?.trim()?.lowercase()?.replace("-", "_")?.replace(" ", "_")) {
            "bank", "bank_account", "account" -> PaymentAccountType.BANK_ACCOUNT
            "payment_app", "app", "mobile_payment", "mobile_wallet" -> PaymentAccountType.PAYMENT_APP
            "bnpl", "buy_now_pay_later", "pay_later" -> PaymentAccountType.BUY_NOW_PAY_LATER
            "crypto", "crypto_wallet", "wallet_crypto" -> PaymentAccountType.CRYPTO_WALLET
            "other" -> PaymentAccountType.OTHER
            else -> PaymentAccountType.DIGITAL_WALLET
        }
    }

    private fun JsonObject.string(vararg keys: String): String {
        return keys.firstNotNullOfOrNull { key -> this[key].primitiveString() }.orEmpty()
    }

    private fun String.hasAnyJsonKey(vararg keys: String): Boolean {
        val obj = runCatching { json.parseToJsonElement(this) as? JsonObject }.getOrNull() ?: return false
        return keys.any { key -> obj.containsKey(key) }
    }

    private fun kotlinx.serialization.json.JsonElement?.primitiveString(): String? {
        return (this as? JsonPrimitive)?.contentOrNull
    }

    @Serializable
    private data class LegacyDocumentItemData(
        val documentType: String = "",
        val documentNumber: String = "",
        val issueDate: String = "",
        val expiryDate: String = "",
        val issuingAuthority: String = "",
        val title: String = "",
        val firstName: String = "",
        val middleName: String = "",
        val lastName: String = "",
        val address1: String = "",
        val address2: String = "",
        val city: String = "",
        val state: String = "",
        val postalCode: String = "",
        val country: String = "",
        val company: String = "",
        val email: String = "",
        val phone: String = "",
        val additionalInfo: String = "",
        val ssn: String = "",
        val username: String = "",
        val passportNumber: String = "",
        val licenseNumber: String = ""
    )
}

fun SecureCustomField.toDraft(): CustomFieldDraft {
    return CustomFieldDraft(
        id = CustomFieldDraft.nextTempId(),
        title = label,
        value = value,
        isProtected = isProtected()
    )
}
