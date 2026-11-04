package takagi.ru.monica.bitwarden.service

import kotlinx.serialization.json.*
import takagi.ru.monica.bitwarden.api.CipherApiResponse
import takagi.ru.monica.bitwarden.api.CipherUpdateRequest
import takagi.ru.monica.bitwarden.api.SyncResponse
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import okhttp3.ResponseBody
import okio.Buffer

/** Complete wire document used by the existing cipher upload paths. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal object BitwardenCipherDocument {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private val topNames = names("id object organizationId collectionIds edit viewPassword permissions organizationUseTotp folderId type name notes login card identity secureNote sshKey fields favorite reprompt key revisionDate creationDate archivedDate deletedDate attachments passwordHistory encryptedFor lastKnownRevisionDate data partialData encryptedByKeyId")
    private val loginNames = names("username password passwordRevisionDate totp uri uris fido2Credentials")
    private val uriNames = names("uri match uriChecksum")
    private val fieldNames = names("name value type linkedId")
    private val cardNames = names("cardholderName brand number expMonth expYear code")
    private val identityNames = names("title firstName middleName lastName address1 address2 address3 city state postalCode country company email phone ssn username passportNumber licenseNumber")
    private val sshNames = names("privateKey publicKey keyFingerprint")
    private val historyNames = names("password lastUsedDate")
    private val attachmentNames = names("id url fileName key size sizeName")
    private val fidoNames = names("credentialId keyType keyAlgorithm keyCurve keyValue rpId rpName counter userHandle userName userDisplayName discoverable creationDate")
    private val responseOnly = setOf("id", "object", "revisionDate", "creationDate", "deletedDate", "edit", "viewPassword", "permissions", "organizationUseTotp")
    private val preserved = setOf("organizationId", "collectionIds", "key", "reprompt", "archivedDate", "passwordHistory", "attachments")

    /** An HTTP success alone cannot retire edits: the returned document must confirm the sent data. */
    fun confirms(request: JsonObject, response: JsonObject): Boolean = runCatching {
        equalSection(request, response, "cipher")
    }.getOrDefault(false)

    private fun equalSection(expected: JsonElement?, actual: JsonElement?, section: String): Boolean {
        val schema = when (section) {
            "cipher" -> topNames
            "login" -> loginNames
            "uris" -> uriNames
            "fields" -> fieldNames
            "card" -> cardNames
            "identity" -> identityNames
            "secureNote" -> names("type")
            "sshKey" -> sshNames
            "passwordHistory" -> historyNames
            "attachments" -> attachmentNames
            "fido2Credentials" -> fidoNames
            "legacy1", "legacy2", "legacy3", "legacy4", "legacy5" -> legacyNames(section.last().digitToInt())
            else -> return expected == actual
        }
        if (expected is JsonArray && actual is JsonArray) {
            return expected.size == actual.size && expected.indices.all { equalSection(expected[it], actual[it], section) }
        }
        if (expected !is JsonObject || actual !is JsonObject) return expected == actual
        val left = normalize(expected, schema)
        val right = normalize(actual, schema)
        val ignored = when (section) {
            "cipher" -> responseOnly + setOf("encryptedFor", "lastKnownRevisionDate")
            // Signed download URLs and formatted sizes are server presentation, not stored attachment content.
            "attachments" -> setOf("url", "sizeName")
            else -> emptySet()
        }
        val required = when (section) {
            "cipher" -> setOf("type", "name", "favorite", "reprompt")
            "fields", "secureNote" -> setOf("type")
            "attachments" -> setOf("id")
            else -> emptySet()
        }
        return (left.keys + right.keys - ignored).all { name ->
            val a = left[name]
            val b = right[name]
            when {
                // The server normalizes the absence of custom fields to an empty collection.
                section == "cipher" && name == "fields" && emptyFields(a) && emptyFields(b) -> true
                section.startsWith("legacy") && name == "fields" && emptyFields(a) && emptyFields(b) -> true
                section == "cipher" && name == "data" -> {
                    val type = left["type"]?.jsonPrimitive?.intOrNull ?: return@all false
                    val before = parseLegacyData(a, type)
                    val after = parseLegacyData(b, type)
                    when {
                        before != null && after != null -> equalSection(before, after, "legacy$type")
                        !present(a) && after != null -> matchesLegacyFields(after, right, type)
                        else -> a == b
                    }
                }
                // Only recognized nullable fields may equate absence with explicit null.
                name in schema.values && name !in required && !present(a) && !present(b) -> true
                name in setOf("archivedDate", "passwordRevisionDate", "lastUsedDate", "creationDate") &&
                    name in schema.values && a is JsonPrimitive && b is JsonPrimitive && a.isString && b.isString ->
                    a == b || runCatching { java.time.Instant.parse(a.content) == java.time.Instant.parse(b.content) }.getOrDefault(false)
                name in schema.values -> equalSection(a, b, name)
                else -> a == b
            }
        }
    }

    /** Bound the body before parsing; duplicate keys must be checked before JsonObject drops them. */
    fun read(body: ResponseBody, sync: Boolean = false): JsonObject = body.use {
        val limit = if (sync) 64 * 1024 * 1024 else 8 * 1024 * 1024
        val buffer = Buffer()
        val stream = it.source()
        while (stream.read(buffer, 8192) != -1L) require(buffer.size <= limit) { "Wire document exceeds limit" }
        BitwardenWireJson.parseObject(buffer.readUtf8(), limit)
    }

    fun decode(source: JsonObject): CipherApiResponse {
        val document = canonical(source)
        require((document["id"] as? JsonPrimitive)?.isString == true && document["id"]!!.jsonPrimitive.content.isNotBlank()) { "Missing cipher identity" }
        require((document["type"] as? JsonPrimitive)?.intOrNull != null) { "Missing cipher type" }
        return json.decodeFromJsonElement<CipherApiResponse>(document).copy(sourceDocument = source)
    }

    fun decodeSync(source: JsonObject): SyncResponse {
        val document = normalize(source, names("profile folders ciphers collections policies sends"))
        val ciphers = document["ciphers"] as? JsonArray ?: error("Missing cipher list")
        val decoded = ciphers.map { decode(it.jsonObject) }
        require(decoded.map { it.id }.toSet().size == decoded.size) { "Duplicate cipher identity" }
        return json.decodeFromJsonElement<SyncResponse>(JsonObject(document - "ciphers")).copy(ciphers = decoded)
    }

    /** New/partial formats never enter the legacy projection or ordinary editor. */
    fun canProject(cipher: CipherApiResponse): Boolean = cipher.type in 1..5 && !cipher.name.isNullOrBlank() &&
        (!present(cipher.data) || legacyData(cipher) != null) && !present(cipher.partialData) &&
        (!present(cipher.encryptedByKeyId) || (cipher.encryptedByKeyId as? JsonPrimitive)?.let {
            it.isString && Regex("[0-9a-f]{32}").matches(it.content)
        } == true) &&
        when (cipher.type) {
            1 -> cipher.login != null
            2 -> cipher.secureNote != null
            3 -> cipher.card != null
            4 -> cipher.identity != null
            5 -> cipher.sshKey != null
            else -> false
        }

    fun update(source: JsonObject, request: CipherUpdateRequest, key: SymmetricCryptoKey? = null, archiveIntent: JsonElement? = null): JsonObject {
        val original = canonical(source)
        val cipher = decode(source)
        require(canProject(cipher)) { "Cipher format is not editable by this client" }
        require(BitwardenCipherRevisionGuard.matches(request.lastKnownRevisionDate, cipher.revisionDate)) { "Cipher baseline changed" }
        val patch = json.encodeToJsonElement(BitwardenCipherOwnership.preserve(request, cipher)).jsonObject
        val result = (original - responseOnly).toMutableMap()
        patch.forEach { (name, value) ->
            if (name !in preserved) {
                result[name] = when (name) {
                    "login" -> mergeLogin(original[name], value, key)
                    "card" -> mergeObject(original[name], value)
                    "identity" -> mergeObject(original[name], value)
                    "secureNote" -> mergeObject(original[name], value)
                    "sshKey" -> mergeObject(original[name], value)
                    "fields" -> mergeArray(original[name], value, fieldNames, "name", key)
                    else -> value
                }
            }
        }
        // Include nulls only when supplied by the source. An absent preserved field is not an intent to clear it.
        preserved.forEach { name -> original[name]?.let { result[name] = it } }
        if (archiveIntent != null) {
            require(archiveIntent == JsonNull || archiveIntent is JsonPrimitive && archiveIntent.isString) { "Invalid archive intent" }
            if (archiveIntent != JsonNull) java.time.Instant.parse(archiveIntent.jsonPrimitive.content)
            result["archivedDate"] = archiveIntent
        }
        legacyData(cipher)?.let { oldData ->
            val data = oldData.toMutableMap()
            legacyFields(JsonObject(result), cipher.type).forEach { (name, value) ->
                // Ordinary editing never rewrites the server's history or its opaque metadata.
                if (name == "passwordHistory" && oldData.containsKey(name)) return@forEach
                data[name] = when (name) {
                    "fields" -> mergeArray(oldData[name], value, fieldNames, "name", key)
                    "uris" -> mergeArray(oldData[name], value, uriNames, "uri", key)
                    "fido2Credentials" -> mergeArray(oldData[name], value, fidoNames, "credentialId", key)
                    else -> value
                }
            }
            result["data"] = JsonPrimitive(legacyServerKeys(JsonObject(data), legacyNames(cipher.type)).toString())
        }
        result["lastKnownRevisionDate"] = JsonPrimitive(cipher.revisionDate)
        return JsonObject(result)
    }

    /** Official Data may be serialized legacy JSON; encrypted blobs are a different format. */
    private fun legacyNames(type: Int): Map<String, String> = names("name notes fields passwordHistory") + when (type) {
        1 -> loginNames
        2 -> names("type")
        3 -> cardNames
        4 -> identityNames
        5 -> sshNames
        else -> emptyMap()
    }

    private fun parseLegacyData(value: JsonElement?, type: Int): JsonObject? = runCatching {
        require(type in 1..5 && value is JsonPrimitive && value.isString)
        normalize(BitwardenWireJson.parseObject(value.content), legacyNames(type))
    }.getOrNull()

    private fun legacyFields(document: JsonObject, type: Int): JsonObject {
        val section = when (type) { 1 -> "login"; 2 -> "secureNote"; 3 -> "card"; 4 -> "identity"; 5 -> "sshKey"; else -> error("Unknown legacy type") }
        val fields = document.filterKeys { it in setOf("name", "notes", "fields", "passwordHistory") } +
            (document[section] as? JsonObject).orEmpty()
        return JsonObject(fields.filterKeys { it in legacyNames(type).values })
    }

    private fun knownLegacyValue(value: JsonElement?, name: String): JsonElement? {
        val schema = when (name) { "fields" -> fieldNames; "uris" -> uriNames; "passwordHistory" -> historyNames; "fido2Credentials" -> fidoNames; else -> return value }
        return (value as? JsonArray)?.let { array -> JsonArray(array.map { element ->
            if (element is JsonObject) JsonObject(normalize(element, schema).filterKeys { it in schema.values }) else element
        }) } ?: value
    }

    private fun matchesLegacyFields(data: JsonObject, document: JsonObject, type: Int): Boolean {
        val expected = legacyFields(document, type)
        if (data["name"] !is JsonPrimitive || data["name"] != expected["name"]) return false
        // Unknown legacy fields remain sealed and are retained on upload; only known mirrors are compared.
        val known = data.filterKeys { it in legacyNames(type).values }
        val left = JsonObject(expected.filterKeys { it in known }.mapValues { knownLegacyValue(it.value, it.key) ?: JsonNull })
        val right = JsonObject(known.mapValues { knownLegacyValue(it.value, it.key) ?: JsonNull })
        return equalSection(left, right, "legacy$type")
    }

    private fun legacyData(cipher: CipherApiResponse): JsonObject? {
        val data = parseLegacyData(cipher.data, cipher.type) ?: return null
        val document = canonical(cipher.sourceDocument ?: json.encodeToJsonElement(cipher).jsonObject)
        return data.takeIf { matchesLegacyFields(it, document, cipher.type) }
    }

    /** C# Data models use PascalCase, including the nested URI/field/history models. */
    private fun legacyServerKeys(value: JsonObject, schema: Map<String, String>): JsonObject = JsonObject(value.map { (name, content) ->
        val canonical = schema[name.lowercase(java.util.Locale.ROOT)]
        val wireName = when (canonical) { null -> name; "ssn" -> "SSN"; else -> canonical.replaceFirstChar(Char::uppercaseChar) }
        val child = when (canonical) { "fields" -> fieldNames; "uris" -> uriNames; "passwordHistory" -> historyNames; "fido2Credentials" -> fidoNames; else -> null }
        wireName to if (child != null && content is JsonArray) JsonArray(content.map {
            if (it is JsonObject) legacyServerKeys(normalize(it, child), child) else it
        }) else content
    }.toMap())

    private fun mergeLogin(old: JsonElement?, patch: JsonElement, key: SymmetricCryptoKey?): JsonElement {
        if (patch == JsonNull) {
            require(!present(old)) { "Cannot discard login data" }
            return patch
        }
        val result = mergeObject(old, patch).jsonObject.toMutableMap()
        val original = old as? JsonObject
        for (name in listOf("passwordRevisionDate", "fido2Credentials")) {
            if (original?.containsKey(name) == true) result[name] = original.getValue(name) else result.remove(name)
        }
        result["uris"] = mergeArray(original?.get("uris"), patch.jsonObject["uris"] ?: JsonNull, uriNames, "uri", key)
        // Legacy primary URI is derived from the first modern URI, not independent opaque data.
        if (original?.containsKey("uri") == true) {
            result["uri"] = (result["uris"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("uri") ?: JsonNull
        }
        return JsonObject(result)
    }

    private fun mergeObject(old: JsonElement?, patch: JsonElement): JsonElement {
        if (patch == JsonNull) {
            require(!present(old)) { "Cannot discard cipher section" }
            return patch
        }
        return JsonObject((old as? JsonObject).orEmpty() + patch.jsonObject)
    }

    /** Match re-encrypted elements by their authenticated identity, retaining duplicate occurrences. */
    private fun mergeArray(old: JsonElement?, patch: JsonElement, schema: Map<String, String>, identity: String, key: SymmetricCryptoKey?): JsonElement {
        val original = (old as? JsonArray).orEmpty().map { normalize(it.jsonObject, schema) }
        val replacements = (patch as? JsonArray).orEmpty().map { normalize(it.jsonObject, schema) }
        fun opaque(item: JsonObject) = item.keys.any { it !in schema.values }
        if (original.none(::opaque)) return patch
        fun identityOf(item: JsonObject): List<JsonElement?> {
            val value = item[identity]
            val decoded = if (key != null && value is JsonPrimitive && value.isString) {
                JsonPrimitive(BitwardenCrypto.decryptToString(value.content, key))
            } else value
            fun optional(value: JsonElement?) = value?.takeUnless { it == JsonNull }
            val kind = if (identity == "uri") optional(item["match"]) else optional(item["type"]) ?: JsonPrimitive(0)
            return listOf(optional(decoded), kind, optional(item["linkedId"]))
        }
        val used = BooleanArray(original.size)
        val result = replacements.map { replacement ->
            val index = original.indices.firstOrNull { !used[it] && identityOf(original[it]) == identityOf(replacement) }
            if (index == null) replacement else {
                used[index] = true
                JsonObject(original[index] + replacement)
            }
        }
        require(original.indices.none { !used[it] && opaque(original[it]) }) { "Cannot discard unknown array metadata" }
        return if (patch == JsonNull) JsonNull else JsonArray(result)
    }

    private fun canonical(source: JsonObject): JsonObject {
        val result = normalize(source, topNames).toMutableMap()
        fun section(name: String, schema: Map<String, String>) {
            val value = result[name]
            if (present(value)) result[name] = normalize(value!!.jsonObject, schema)
        }
        section("login", loginNames)
        section("card", cardNames)
        section("identity", identityNames)
        section("secureNote", names("type"))
        section("sshKey", sshNames)
        var login = result["login"] as? JsonObject
        val primaryUri = login?.get("uri")
        require(!present(primaryUri) || primaryUri is JsonPrimitive && primaryUri.isString) { "Invalid primary URI" }
        if (login != null && !present(login["uris"]) && present(primaryUri)) {
            login = JsonObject(login + ("uris" to JsonArray(listOf(JsonObject(mapOf("uri" to primaryUri!!))))))
            result["login"] = login
        }
        if (login != null && present(login["uris"])) {
            result["login"] = JsonObject(login + ("uris" to JsonArray(login["uris"]!!.jsonArray.map { normalize(it.jsonObject, uriNames) })))
        }
        fun validateArray(value: JsonElement?, schema: Map<String, String>) {
            if (present(value)) value!!.jsonArray.forEach { normalize(it.jsonObject, schema) }
        }
        validateArray(login?.get("fido2Credentials"), names("credentialId keyType keyAlgorithm keyCurve keyValue rpId rpName counter userHandle userName userDisplayName discoverable creationDate"))
        // Opaque history records need not have a shape understood by this client.
        (result["passwordHistory"] as? JsonArray)?.forEach {
            if (it is JsonObject) normalize(it, names("password lastUsedDate"))
        }
        validateArray(result["attachments"], names("id url fileName key size sizeName"))
        (result["permissions"] as? JsonObject)?.let { normalize(it, names("delete restore")) }
        if (present(result["fields"])) result["fields"] = JsonArray(result["fields"]!!.jsonArray.map { normalize(it.jsonObject, fieldNames) })
        return JsonObject(result)
    }

    private fun normalize(source: JsonObject, schema: Map<String, String>): JsonObject {
        val result = linkedMapOf<String, JsonElement>()
        source.forEach { (name, value) ->
            val canonical = schema[name.lowercase(java.util.Locale.ROOT)] ?: name
            require(!result.containsKey(canonical)) { "Ambiguous cipher field casing" }
            result[canonical] = value
        }
        return JsonObject(result)
    }

    private fun present(value: JsonElement?) = value != null && value != JsonNull
    private fun emptyFields(value: JsonElement?) = !present(value) || value is JsonArray && value.isEmpty()
    private fun names(value: String) = value.split(' ').associateBy { it.lowercase(java.util.Locale.ROOT) } +
        (if ("sshKey" in value.split(' ')) mapOf("ssh_key" to "sshKey") else emptyMap())
}
