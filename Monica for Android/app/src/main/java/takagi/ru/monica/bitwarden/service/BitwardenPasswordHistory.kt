package takagi.ru.monica.bitwarden.service

import java.time.Instant
import kotlinx.serialization.json.*
import takagi.ru.monica.bitwarden.api.CipherApiResponse
import takagi.ru.monica.bitwarden.api.CipherFieldApiData
import takagi.ru.monica.bitwarden.api.CipherUpdateRequest
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey

/** Protocol-only decisions. Callers keep local IDs and all cleartext strictly in the unlocked scope. */
internal object BitwardenPasswordHistory {
    const val MAX_ENTRIES = 5
    const val MAX_WIRE_ENTRIES = 512

    data class Record(
        val wire: JsonElement,
        val password: String?,
        val lastUsedDate: String?
    )

    data class DesiredRecord(
        val localId: Long?,
        val wire: JsonElement?,
        val password: String?,
        val lastUsedDate: String?
    )

    data class PreparedRecord(val localId: Long?, val wire: JsonElement)
    data class Prepared(val request: CipherUpdateRequest, val records: List<PreparedRecord>)

    /** Unknown record shapes survive in the wire state. Recognized secrets must decrypt strictly. */
    fun decode(raw: JsonElement?, key: SymmetricCryptoKey): List<Record> {
        if (raw == null || raw is JsonNull) return emptyList()
        val items = raw as? JsonArray ?: error("Bitwarden password history is not an array")
        require(items.size <= MAX_WIRE_ENTRIES) { "Bitwarden password history is too large" }
        return items.map { wire ->
            val obj = wire as? JsonObject
            if (obj == null) Record(wire, null, null) else {
                val password = uniqueString(obj, "password")
                val date = uniqueString(obj, "lastUsedDate")
                // Authenticate every recognized secret even if its display date is absent.
                val plain = password?.let { BitwardenCrypto.decryptToString(it, key) }
                if (date != null) Instant.parse(date)
                Record(wire, plain.takeIf { date != null }, date)
            }
        }
    }

    /** desired=null means no explicit local history operation; preserve the original complete list. */
    fun prepare(
        request: CipherUpdateRequest,
        source: CipherApiResponse,
        key: SymmetricCryptoKey,
        desired: List<DesiredRecord>? = null,
        initialized: Boolean = false,
        clearRemote: Boolean = false,
        suppressOriginalPassword: Boolean = false,
        suppressHiddenChange: (String) -> Boolean = { false },
        now: Instant = Instant.now()
    ): Prepared {
        require(request.type == source.type) { "Password history cipher type changed" }
        if (source.type == 1 && (source.login == null || request.login == null)) {
            error("Password history login data unavailable")
        }
        if (source.type != 1) {
            require(desired == null) { "Password history mutation requires a login" }
        }
        // Always authenticate the source history, even when only its unchanged bytes will be sent.
        val remote = decode(source.passwordHistory, key)
        val oldPassword = decryptOptional(source.login?.password, key)
        val newPassword = decryptOptional(request.login?.password, key)
        val passwordChanged = oldPassword != newPassword
        val newDate = now.toString()
        val hiddenChanges = hiddenChanges(source.fields, request.fields, key).filterNot(suppressHiddenChange)

        val desiredRecords = desired?.map { local ->
            val wire = local.wire ?: newWire(requireNotNull(local.password), requireNotNull(local.lastUsedDate), key)
            PreparedRecord(local.localId, wire)
        }
        var records = when {
            desiredRecords == null -> remote.map { PreparedRecord(null, it.wire) }
            initialized || clearRemote -> desiredRecords
            else -> {
                // Upgrade an unmapped local history once, without collapsing duplicate occurrences.
                val original = remote.map { PreparedRecord(null, it.wire) }.toMutableList()
                val used = BooleanArray(remote.size)
                val newLocal = mutableListOf<PreparedRecord>()
                desired!!.zip(desiredRecords).forEach { (local, prepared) ->
                    val index = remote.indices.firstOrNull { index -> !used[index] && remote[index].let { original ->
                        local.wire == original.wire || (local.wire == null && local.password == original.password &&
                            local.lastUsedDate != null && original.lastUsedDate != null &&
                            Instant.parse(local.lastUsedDate) == Instant.parse(original.lastUsedDate))
                    } }
                    if (index != null) {
                        used[index] = true
                        original[index] = PreparedRecord(local.localId, remote[index].wire)
                    } else newLocal += prepared
                }
                original + newLocal
            }
        }

        // Offline local transitions already captured the original password. Do not add it twice.
        val capturedOriginal = desired.orEmpty().any { it.wire == null && it.password == oldPassword }
        val changes = buildList {
            if (passwordChanged && oldPassword.isNotEmpty() && !capturedOriginal && !suppressOriginalPassword && !clearRemote) {
                add(PreparedRecord(null, newWire(oldPassword, newDate, key)))
            }
            hiddenChanges.forEach { add(PreparedRecord(null, newWire(it, newDate, key))) }
        }
        // Official clients append changes and retain the last five understood records.
        // An unfamiliar record or extra metadata must never be silently discarded by that limit.
        if (changes.isNotEmpty()) records = retainRecent(records + changes) { it.wire }
        else if (desired.orEmpty().any { it.wire == null }) records = retainRecent(records) { it.wire }
        require(records.size <= MAX_WIRE_ENTRIES) { "Bitwarden password history is too large" }

        val unchanged = desired == null && !passwordChanged && hiddenChanges.isEmpty()
        val revision = when {
            !passwordChanged -> source.login?.passwordRevisionDate
            oldPassword.isEmpty() && newPassword.isEmpty() -> source.login?.passwordRevisionDate
            else -> newDate
        }
        val history = if (unchanged) source.passwordHistory else JsonArray(records.map { it.wire })
        return Prepared(request.copy(passwordHistory = history,
            login = request.login?.copy(passwordRevisionDate = revision)), records)
    }

    private fun newWire(password: String, date: String, key: SymmetricCryptoKey): JsonObject {
        Instant.parse(date)
        return buildJsonObject {
            put("password", BitwardenCrypto.encryptString(password, key))
            put("lastUsedDate", date)
        }
    }

    private fun uniqueString(obj: JsonObject, key: String): String? {
        val names = obj.keys.filter { it.equals(key, ignoreCase = true) }
        require(names.size <= 1) { "Ambiguous password history field" }
        val value = names.singleOrNull()?.let(obj::get) ?: return null
        if (value is JsonNull) return null
        require(value is JsonPrimitive && value.isString) { "Invalid password history field" }
        return value.content
    }

    private fun decryptOptional(value: String?, key: SymmetricCryptoKey): String =
        value?.let { BitwardenCrypto.decryptToString(it, key) }.orEmpty()

    /** Bitwarden records changed or deleted hidden fields as `name: old value`. */
    private fun hiddenChanges(old: List<CipherFieldApiData>?, updated: List<CipherFieldApiData>?, key: SymmetricCryptoKey): List<String> {
        fun values(fields: List<CipherFieldApiData>?) = fields.orEmpty().filter { it.type == 1 }.map {
            decryptOptional(it.name, key) to decryptOptional(it.value, key)
        }
        val remaining = values(updated).toMutableList()
        return values(old).mapNotNull { (name, value) ->
            val occurrence = remaining.indexOf(name to value)
            if (occurrence >= 0) { remaining.removeAt(occurrence); null }
            else if (name.isNotEmpty() && value.isNotEmpty()) "$name: $value" else null
        }
    }

    /** Null wire denotes a new local record. Unknown records/metadata have independent retention. */
    internal fun <T> retainRecent(records: List<T>, wire: (T) -> JsonElement?): List<T> {
        var known = 0
        return records.asReversed().filter { record ->
            val raw = wire(record)
            val understood = raw == null || raw is JsonObject &&
                raw.keys.map { it.lowercase(java.util.Locale.ROOT) }.toSet() == setOf("password", "lastuseddate") &&
                raw.values.all { it is JsonPrimitive && it.isString }
            if (understood) ++known <= MAX_ENTRIES else true
        }.asReversed()
    }
}
