package takagi.ru.monica.keepass

import java.util.Locale

enum class KeePassFieldRole {
    STANDARD,
    MONICA_PASSWORD,
    MONICA_SECURE_ITEM,
    MONICA_PASSKEY,
    KEEPASS_TOTP,
    KEEPASS_PASSKEY,
    KEEPASS_PLUGIN,
    UNKNOWN
}

object KeePassFieldRegistry {
    private val passwordEntryOverlayFields = setOf(
        "Title", "UserName", "Password", "URL", "Notes",
        "MonicaLocalId",
        "MonicaConflictCopy",
        "App Package Name", "App Name",
        "Email", "Phone",
        "Address", "City", "State", "Postal Code", "Country",
        "Card Number", "Card Holder", "Card Expiry", "Card CVV",
        "SSO Provider", "MonicaSsoRefEntryId",
        "MonicaLoginType", "SSID", "MonicaWifiData",
        "MonicaSshAlgorithm", "MonicaSshKeySize", "MonicaSshPublicKey",
        "MonicaSshPrivateKey", "MonicaSshFingerprint", "MonicaSshComment", "MonicaSshFormat"
    )

    private val secureItemOverlayFields = setOf(
        "Title", "UserName", "Password", "URL", "Notes",
        "MonicaSecureItemId",
        "MonicaConflictCopy",
        "MonicaItemType",
        "MonicaItemData",
        "MonicaImagePaths",
        "MonicaIsFavorite",
        "Card Number", "CardNumber", "Credit Card Number", "CreditCardNumber",
        "Card Holder", "CardHolder", "Credit Card Holder", "CreditCardHolder",
        "Card Expiry", "CardExpiry", "Expiration Date", "Expiry Date",
        "Card CVV", "CardCVV", "CVV", "CVC",
        "Expiry Month", "Expiry Year",
        "Bank Name",
        "Card Type",
        "Billing Address",
        "Brand",
        "Nickname",
        "Valid From Month",
        "Valid From Year",
        "PIN",
        "IBAN",
        "SWIFT/BIC",
        "Routing Number",
        "Account Number",
        "Branch Code",
        "Currency",
        "Customer Service Phone"
    )

    private val passkeyEntryOverlayFields = setOf(
        "Title", "UserName", "Password", "URL", "Notes",
        "MonicaConflictCopy",
        "MonicaPasskeyCredentialId",
        "MonicaPasskeyData",
        "MonicaPasskeyMode",
        KeePassDxPasskeyCodec.FIELD_PASSKEY,
        KeePassDxPasskeyCodec.FIELD_USERNAME,
        KeePassDxPasskeyCodec.FIELD_PRIVATE_KEY,
        KeePassDxPasskeyCodec.FIELD_CREDENTIAL_ID,
        KeePassDxPasskeyCodec.FIELD_USER_HANDLE,
        KeePassDxPasskeyCodec.FIELD_RELYING_PARTY,
        KeePassDxPasskeyCodec.FIELD_FLAG_BE,
        KeePassDxPasskeyCodec.FIELD_FLAG_BS
    )

    private val standardFields = setOf(
        "Title", "Name",
        "UserName", "Username", "User", "Login",
        "Password", "Pass", "pass", "pwd", "PWD", "密码", "口令",
        "URL", "Url", "Website", "URI",
        "Notes", "Note", "Comment"
    )

    private val monicaPasswordFields = setOf(
        "MonicaLocalId",
        "MonicaConflictCopy",
        "App Package Name", "AppPackageName", "MonicaAppPackageName",
        "App Name", "AppName", "MonicaAppName",
        "Email", "E-mail", "Mail",
        "Phone", "Phone Number", "Telephone",
        "Address", "Address Line",
        "City", "State", "Province", "Postal Code", "PostalCode", "Zip Code", "ZipCode", "Country",
        "Card Number", "CardNumber", "Credit Card Number", "CreditCardNumber",
        "Card Holder", "CardHolder", "Credit Card Holder", "CreditCardHolder",
        "Card Expiry", "CardExpiry", "Expiration Date", "Expiry Date",
        "Card CVV", "CardCVV", "CVV", "CVC",
        "Expiry Month", "Expiry Year",
        "SSO Provider", "SsoProvider", "MonicaSsoProvider", "MonicaSsoRefEntryId",
        "SSID", "MonicaWifiData", "MonicaLoginType",
        "MonicaSshAlgorithm", "MonicaSshKeySize", "MonicaSshPublicKey",
        "MonicaSshPrivateKey", "MonicaSshFingerprint", "MonicaSshComment", "MonicaSshFormat"
    )

    private val monicaSecureItemFields = setOf(
        "MonicaTotpData",
        "MonicaSecureItemId",
        "MonicaConflictCopy",
        "MonicaItemType",
        "MonicaItemData",
        "MonicaImagePaths",
        "MonicaIsFavorite",
        "Bank Name",
        "Card Type",
        "Billing Address",
        "Brand",
        "Nickname",
        "Valid From Month",
        "Valid From Year",
        "PIN",
        "IBAN",
        "SWIFT/BIC",
        "Routing Number",
        "Account Number",
        "Branch Code",
        "Currency",
        "Customer Service Phone"
    )

    private val monicaPasskeyFields = setOf(
        "MonicaPasskeyCredentialId",
        "MonicaPasskeyData",
        "MonicaPasskeyMode",
        "MonicaConflictCopy"
    )

    private val keepPassTotpFields = KeePassTotpCodec.fieldNames

    private val keepPassPasskeyFields = setOf(
        KeePassDxPasskeyCodec.FIELD_PASSKEY,
        KeePassDxPasskeyCodec.FIELD_USERNAME,
        KeePassDxPasskeyCodec.FIELD_PRIVATE_KEY,
        KeePassDxPasskeyCodec.FIELD_CREDENTIAL_ID,
        KeePassDxPasskeyCodec.FIELD_USER_HANDLE,
        KeePassDxPasskeyCodec.FIELD_RELYING_PARTY,
        KeePassDxPasskeyCodec.FIELD_FLAG_BE,
        KeePassDxPasskeyCodec.FIELD_FLAG_BS
    )

    private val standardFieldKeys = normalizedSet(standardFields)
    private val monicaPasswordFieldKeys = normalizedSet(monicaPasswordFields)
    private val monicaSecureItemFieldKeys = normalizedSet(monicaSecureItemFields)
    private val monicaPasskeyFieldKeys = normalizedSet(monicaPasskeyFields)
    private val keepPassTotpFieldKeys = normalizedSet(keepPassTotpFields)
    private val keepPassPasskeyFieldKeys = normalizedSet(keepPassPasskeyFields)
    private val passwordEntryOverlayFieldKeys = normalizedSet(passwordEntryOverlayFields)
    private val secureItemOverlayFieldKeys = normalizedSet(secureItemOverlayFields)
    private val passkeyEntryOverlayFieldKeys = normalizedSet(passkeyEntryOverlayFields)

    fun normalize(name: String): String = name.trim().lowercase(Locale.ROOT)

    fun roleOf(name: String): KeePassFieldRole {
        val key = normalize(name)
        if (key.isBlank()) return KeePassFieldRole.UNKNOWN
        return when {
            key.startsWith("_etm_") -> KeePassFieldRole.KEEPASS_PLUGIN
            key in standardFieldKeys -> KeePassFieldRole.STANDARD
            key in monicaPasswordFieldKeys -> KeePassFieldRole.MONICA_PASSWORD
            key in monicaSecureItemFieldKeys -> KeePassFieldRole.MONICA_SECURE_ITEM
            key in monicaPasskeyFieldKeys -> KeePassFieldRole.MONICA_PASSKEY
            key in keepPassTotpFieldKeys -> KeePassFieldRole.KEEPASS_TOTP
            key in keepPassPasskeyFieldKeys -> KeePassFieldRole.KEEPASS_PASSKEY
            else -> KeePassFieldRole.UNKNOWN
        }
    }

    fun isMonicaOwned(name: String): Boolean {
        return when (roleOf(name)) {
            KeePassFieldRole.MONICA_PASSWORD,
            KeePassFieldRole.MONICA_SECURE_ITEM,
            KeePassFieldRole.MONICA_PASSKEY -> true
            else -> false
        }
    }

    fun isPreservedByDefault(name: String): Boolean {
        return !isMonicaOwned(name)
    }

    fun isReservedPasswordProjectionField(name: String): Boolean {
        return roleOf(name) != KeePassFieldRole.UNKNOWN
    }

    fun isPasswordEntryOverlayField(name: String): Boolean {
        val key = normalize(name)
        return key.isNotBlank() && key in passwordEntryOverlayFieldKeys
    }

    fun isSecureItemOverlayField(name: String): Boolean {
        val key = normalize(name)
        return key.isNotBlank() && key in secureItemOverlayFieldKeys
    }

    fun isPasskeyEntryOverlayField(name: String): Boolean {
        val key = normalize(name)
        return key.isNotBlank() && key in passkeyEntryOverlayFieldKeys
    }

    fun isKeePassTotpField(name: String): Boolean {
        return roleOf(name) == KeePassFieldRole.KEEPASS_TOTP
    }

    fun isPasswordSecretFallbackCandidateField(name: String): Boolean {
        return roleOf(name) == KeePassFieldRole.UNKNOWN
    }

    private fun normalizedSet(values: Set<String>): Set<String> {
        return values.mapTo(mutableSetOf(), ::normalize)
    }
}
