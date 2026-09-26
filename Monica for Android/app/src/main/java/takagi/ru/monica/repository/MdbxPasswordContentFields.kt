package takagi.ru.monica.repository

import org.json.JSONObject
import takagi.ru.monica.data.PasswordEntry

/** Existing password content stored inside the encrypted MDBX record, without a schema change. */
internal object MdbxPasswordContentFields {
    fun writeTo(payload: JSONObject, entry: PasswordEntry, decryptSensitive: (String) -> String): JSONObject =
        payload.put("email", entry.email)
            .put("phone", entry.phone)
            .put("address_line", entry.addressLine)
            .put("city", entry.city)
            .put("state", entry.state)
            .put("zip_code", entry.zipCode)
            .put("country", entry.country)
            .put("credit_card_number_plain", decryptSensitive(entry.creditCardNumber))
            .put("credit_card_holder", entry.creditCardHolder)
            .put("credit_card_expiry", entry.creditCardExpiry)
            .put("credit_card_cvv_plain", decryptSensitive(entry.creditCardCVV))

    fun readInto(payload: JSONObject, entry: PasswordEntry, previous: PasswordEntry? = null): PasswordEntry {
        val fallback = previous ?: entry
        // Missing keys from an older writer preserve the existing projection. Explicit empty
        // strings/nulls clear a value. Card fields follow the readable editor/KDBX model.
        fun value(key: String, old: String): String =
            if (!payload.has(key)) old else if (payload.isNull(key)) "" else payload.getString(key)
        return entry.copy(
            email = value("email", fallback.email), phone = value("phone", fallback.phone),
            addressLine = value("address_line", fallback.addressLine), city = value("city", fallback.city),
            state = value("state", fallback.state), zipCode = value("zip_code", fallback.zipCode),
            country = value("country", fallback.country),
            creditCardNumber = value("credit_card_number_plain", fallback.creditCardNumber),
            creditCardHolder = value("credit_card_holder", fallback.creditCardHolder),
            creditCardExpiry = value("credit_card_expiry", fallback.creditCardExpiry),
            creditCardCVV = value("credit_card_cvv_plain", fallback.creditCardCVV),
        )
    }
}
