package takagi.ru.monica.bitwarden.service

import takagi.ru.monica.bitwarden.api.CipherFieldApiData
import takagi.ru.monica.bitwarden.api.CipherUriApiData
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.data.model.PasswordUrlRules
import takagi.ru.monica.data.model.PasswordUrlRules.Mode

/** Native URI rows are authoritative; the shared editor keeps their order and exact spelling. */
internal object BitwardenLoginUriRules {
    fun mode(value: Int?): Mode = when (value) {
        null -> Mode.DEFAULT
        0 -> Mode.BASE_DOMAIN
        1 -> Mode.HOST
        2 -> Mode.STARTS_WITH
        3 -> Mode.EXACT
        4 -> Mode.REGEX
        5 -> Mode.NEVER
        else -> error("Unsupported Bitwarden URI match rule")
    }

    fun match(value: Mode): Int? = when (value) {
        Mode.DEFAULT -> null
        Mode.BASE_DOMAIN -> 0
        Mode.HOST -> 1
        Mode.STARTS_WITH -> 2
        Mode.EXACT -> 3
        Mode.REGEX -> 4
        Mode.NEVER -> 5
    }

    fun fromRemote(fields: List<MonicaPlainCustomField>, uris: List<CipherUriApiData>?, key: SymmetricCryptoKey): List<MonicaPlainCustomField> {
        val previous = fields.filter { it.name == PasswordUrlRules.FIELD }
        require(previous.size <= 1 && previous.all { PasswordUrlRules.decode(it.value) != null }) {
            "Unsupported URL matching metadata must be preserved"
        }
        val rows = uris.orEmpty().mapNotNull { uri ->
            val value = uri.uri ?: return@mapNotNull null
            PasswordUrlRules.Rule(BitwardenCrypto.decryptToString(value, key), mode(uri.match))
        }
        return fields.filterNot { it.name == PasswordUrlRules.FIELD } +
            MonicaPlainCustomField(PasswordUrlRules.FIELD, PasswordUrlRules.encode(rows, native = true), false)
    }

    /** Native source replaces only understood local rules; unknown metadata stays untouched by failing closed. */
    fun withoutLocalRules(fields: List<MonicaPlainCustomField>): List<MonicaPlainCustomField> {
        val rules = fields.filter { it.name == PasswordUrlRules.FIELD }
        require(rules.size <= 1 && rules.all { PasswordUrlRules.decode(it.value) != null }) {
            "Unsupported URL matching metadata must be preserved"
        }
        return fields.filterNot { it.name == PasswordUrlRules.FIELD }
    }

    fun encrypted(value: String, key: SymmetricCryptoKey): List<CipherUriApiData> {
        val rules = requireNotNull(PasswordUrlRules.decode(value)) { "Unsupported URL matching metadata" }
        return rules.map { CipherUriApiData(uri = BitwardenCrypto.encryptString(it.url, key), match = match(it.mode)) }
    }

    fun hasExplicitRules(fields: List<CipherFieldApiData>?, key: SymmetricCryptoKey): Boolean {
        val rules = fields.orEmpty().filter { field ->
            field.name?.let { BitwardenCrypto.decryptToString(it, key) } == PasswordUrlRules.FIELD
        }
        require(rules.size <= 1) { "Ambiguous URL matching metadata" }
        return rules.singleOrNull()?.let { field ->
            requireNotNull(PasswordUrlRules.decode(BitwardenCrypto.decryptToString(requireNotNull(field.value), key))) {
                "Unsupported URL matching metadata"
            }
            true
        } ?: false
    }
}
