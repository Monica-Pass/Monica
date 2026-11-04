package takagi.ru.monica.bitwarden.export

import kotlinx.serialization.json.*
import takagi.ru.monica.bitwarden.api.SyncResponse
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.service.BitwardenCipherDocument
import takagi.ru.monica.bitwarden.service.BitwardenCipherKeyResolver

data class BitwardenJsonExport(val document: BitwardenJsonDocument, val attachmentCount: Int = 0)

/** Decrypt the complete response, never the lossy Room projections. Personal export only. */
internal object BitwardenJsonSourceExporter {
    private val standard = setOf("id", "folderId", "organizationId", "collectionIds", "name", "notes", "type", "favorite", "reprompt", "fields", "passwordHistory", "revisionDate", "creationDate", "deletedDate") + BitwardenJsonCodec.sections.values
    private val transport = setOf("object", "edit", "viewPassword", "permissions", "organizationUseTotp", "key", "attachments", "encryptedFor", "lastKnownRevisionDate", "encryptedByKeyId", "data", "partialData", "archivedDate", "bankAccount", "driversLicense", "passport")

    fun fromSync(response: SyncResponse, vaultKey: SymmetricCryptoKey, requireCurrent: () -> Unit): BitwardenJsonExport {
        val problems = mutableListOf<BitwardenJsonIssue>()
        var attachmentCount = 0
        val folders = response.folders.map { folder ->
            requireCurrent()
            buildJsonObject { put("id", folder.id); put("name", BitwardenCrypto.decryptToString(requireNotNull(folder.name), vaultKey)) }
        }
        val items = response.ciphers.mapIndexedNotNull { index, cipher ->
            requireCurrent()
            var title: String? = null
            fun issue(path: String, reason: BitwardenJsonIssue.Reason) { problems += BitwardenJsonIssue(index, title?.take(200), "items[$index].$path", reason) }
            if (!cipher.organizationId.isNullOrBlank()) { issue("organizationId", BitwardenJsonIssue.Reason.ORGANIZATION); return@mapIndexedNotNull null }
            if (!BitwardenCipherDocument.canProject(cipher)) { issue("type", BitwardenJsonIssue.Reason.UNSUPPORTED_TYPE); return@mapIndexedNotNull null }
            var ownedKey: SymmetricCryptoKey? = null
            try {
                val key = BitwardenCipherKeyResolver.resolveCipherKey(cipher, vaultKey, "JsonExport").also { ownedKey = it }
                val source = normalize(BitwardenCipherDocument.canonicalForExport(requireNotNull(cipher.sourceDocument)), standard + transport)
                title = source.string("name")?.let { BitwardenCrypto.decryptToString(it, key) }
                source.keys.filter { it !in standard && it !in transport }.forEach { issue(it.take(100), BitwardenJsonIssue.Reason.UNKNOWN_FIELD) }
                if (source["archivedDate"].present()) issue("archivedDate", BitwardenJsonIssue.Reason.ARCHIVED)
                if (source["data"].present() || source["partialData"].present()) issue("data", BitwardenJsonIssue.Reason.UNSUPPORTED_VALUE)
                listOf("bankAccount", "driversLicense", "passport").filter { source[it].present() }.forEach { issue(it, BitwardenJsonIssue.Reason.UNSUPPORTED_TYPE) }
                listOf("fields", "passwordHistory", "attachments").filter { source[it].present() && source[it] !is JsonArray }.forEach { issue(it, BitwardenJsonIssue.Reason.UNSUPPORTED_VALUE) }
                attachmentCount += source.array("attachments").size
                val converted = source.filterKeys { it in standard }.toMutableMap()
                fun decrypt(value: JsonElement?, compatible: Boolean = false): JsonElement {
                    if (!value.present()) return JsonNull
                    require(value is JsonPrimitive && value.isString) { "Invalid encrypted field" }
                    if (compatible && !Regex("^[0-9]\\.").containsMatchIn(value.content)) return value
                    return JsonPrimitive(BitwardenCrypto.decryptToString(value.content, key))
                }
                fun decryptSection(value: JsonElement?, fields: Set<String>, allowed: Set<String> = fields, compatible: Boolean = false): JsonObject {
                    require(value is JsonObject) { "Missing export section" }
                    val normalized = normalize(value, allowed)
                    normalized.keys.filter { it !in allowed }.forEach { issue(it.take(100), BitwardenJsonIssue.Reason.UNKNOWN_FIELD) }
                    return JsonObject(normalized.mapValues { (name, value) -> if (name in fields) decrypt(value, compatible) else value })
                }
                converted["name"] = decrypt(source["name"]); converted["notes"] = decrypt(source["notes"])
                val section = BitwardenJsonCodec.sections.getValue(cipher.type)
                when (cipher.type) {
                    1 -> {
                        val login = decryptSection(source["login"], setOf("username", "password", "totp"),
                            setOf("username", "password", "totp", "uris", "uri", "passwordRevisionDate", "fido2Credentials")).toMutableMap()
                        // Primary URI and password-revision timestamp are transport metadata in the standard exporter.
                        login.remove("uri"); login.remove("passwordRevisionDate")
                        login["uris"] = JsonArray((login["uris"] as? JsonArray).orEmpty().map { uri ->
                            val decrypted = decryptSection(uri, setOf("uri", "uriChecksum"), setOf("uri", "uriChecksum", "match"))
                            JsonObject(decrypted - "uriChecksum")
                        })
                        login["fido2Credentials"] = JsonArray((login["fido2Credentials"] as? JsonArray).orEmpty().map { fido ->
                            decryptSection(fido, BitwardenJsonCodec.fidoNames, compatible = true)
                        })
                        converted[section] = JsonObject(login)
                    }
                    2 -> converted[section] = decryptSection(source[section], emptySet(), setOf("type"))
                    3 -> converted[section] = decryptSection(source[section], BitwardenJsonCodec.cardNames)
                    4 -> converted[section] = decryptSection(source[section], BitwardenJsonCodec.identityNames)
                    5 -> converted[section] = decryptSection(source[section], BitwardenJsonCodec.sshNames)
                }
                converted["fields"] = JsonArray(source.array("fields").map { field ->
                    decryptSection(field, setOf("name", "value"), setOf("name", "value", "type", "linkedId"))
                })
                converted["passwordHistory"] = JsonArray(source.array("passwordHistory").map { history ->
                    decryptSection(history, setOf("password"), setOf("password", "lastUsedDate"))
                })
                converted["favorite"] = source["favorite"]?.takeUnless { it == JsonNull } ?: JsonPrimitive(false)
                converted["reprompt"] = source["reprompt"]?.takeUnless { it == JsonNull } ?: JsonPrimitive(0)
                requireCurrent()
                JsonObject(converted)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { issue("", BitwardenJsonIssue.Reason.UNAVAILABLE); null }
            finally { ownedKey?.clear() }
        }
        val document = BitwardenJsonDocument(JsonArray(folders), JsonArray(items))
        problems += BitwardenJsonCodec.validate(document.json())
        if (problems.isNotEmpty()) throw BitwardenJsonRejected(problems)
        requireCurrent()
        return BitwardenJsonExport(document, attachmentCount)
    }

    private fun normalize(source: JsonObject, names: Set<String>): JsonObject {
        val schema = names.associateBy { it.lowercase(java.util.Locale.ROOT) }
        val result = linkedMapOf<String, JsonElement>()
        source.forEach { (name, value) ->
            val key = schema[name.lowercase(java.util.Locale.ROOT)] ?: name
            require(result.put(key, value) == null) { "Ambiguous field casing" }
        }
        return JsonObject(result)
    }
}
