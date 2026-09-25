package takagi.ru.monica.keepass

import kotlinx.serialization.json.Json
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.BillingAddressData
import takagi.ru.monica.data.model.DocumentData
import takagi.ru.monica.data.model.NoteData
import takagi.ru.monica.data.model.PaymentAccountData
import takagi.ru.monica.data.model.TotpData

/** A marker alone never makes an entry a usable specialized projection. */
internal data class KeePassSecureItemPayload(val type: ItemType, val data: String) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun resolve(
            typeName: String,
            raw: String,
            standardTotp: TotpData?,
            rebuild: (ItemType) -> String?
        ): KeePassSecureItemPayload? {
            val type = ItemType.entries.firstOrNull { it.name.equals(typeName.trim(), ignoreCase = true) }
                ?.takeUnless { it == ItemType.PASSWORD } ?: return null
            if (type == ItemType.TOTP) {
                val data = resolveTotp(raw, standardTotp) ?: return null
                return KeePassSecureItemPayload(type, json.encodeToString(TotpData.serializer(), data))
            }
            val valid = runCatching {
                when (type) {
                    ItemType.NOTE -> json.decodeFromString<NoteData>(raw)
                    ItemType.BANK_CARD -> json.decodeFromString<BankCardData>(raw)
                    ItemType.DOCUMENT -> json.decodeFromString<DocumentData>(raw)
                    ItemType.BILLING_ADDRESS -> json.decodeFromString<BillingAddressData>(raw)
                    ItemType.PAYMENT_ACCOUNT -> json.decodeFromString<PaymentAccountData>(raw)
                    else -> return null
                }
            }.isSuccess
            val data = if (valid) raw else rebuild(type)?.takeIf(String::isNotBlank) ?: return null
            return KeePassSecureItemPayload(type, data)
        }

        fun resolveTotp(raw: String, standard: TotpData?): TotpData? {
            val stored = runCatching { json.decodeFromString<TotpData>(raw) }.getOrNull()
                ?.takeIf { it.secret.isNotBlank() }
            // Other KeePass clients edit standard OTP fields without updating Monica's JSON.
            return if (standard != null && stored != null) stored.copy(
                secret = standard.secret, issuer = standard.issuer, accountName = standard.accountName,
                period = standard.period, digits = standard.digits, algorithm = standard.algorithm,
                otpType = standard.otpType, counter = standard.counter, link = standard.link
            ) else standard ?: stored
        }
    }
}
