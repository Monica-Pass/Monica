package takagi.ru.monica.data.dedup

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import takagi.ru.monica.bitwarden.api.CipherApiResponse
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.service.BitwardenCipherDocument
import takagi.ru.monica.bitwarden.service.BitwardenCipherKeyResolver
import takagi.ru.monica.viewmodel.SingleVaultDedupUiIssue
import takagi.ru.monica.viewmodel.SingleVaultDedupUiOutcome
import takagi.ru.monica.viewmodel.SingleVaultDedupUiOutcomeStatus
import takagi.ru.monica.viewmodel.SingleVaultDedupUiSource

/** Native read-only analysis. The server does not offer a revision-conditional soft delete. */
internal class SingleVaultDedupBitwardenAdapter(
    private val repository: BitwardenRepository,
    private val vaultId: Long,
    private val label: String,
) : SingleVaultDedupSourceAdapter {
    override val source: SingleVaultDedupUiSource get() = SingleVaultDedupUiSource("bitwarden:$vaultId", label, false,
        if (repository.isVaultUnlocked(vaultId)) SingleVaultDedupUiIssue.ANALYSIS_ONLY else SingleVaultDedupUiIssue.LOCKED)
    override suspend fun capture(includeArchived: Boolean, checkActive: () -> Unit): SingleVaultDedupCapture =
        repository.withDedupSource(vaultId, checkActive) { _, response, key ->
            val projects = response.ciphers.mapNotNull { cipher ->
                checkActive()
                val raw = cipher.sourceDocument?.let(BitwardenCipherDocument::canonicalForExport)
                if (raw?.get("deletedDate").present()) return@mapNotNull null
                if (!includeArchived && raw?.get("archivedDate").present()) return@mapNotNull null
                SingleVaultDedupBitwardenDocument.project(source.key, cipher, key, checkActive)
            }
            checkActive()
            object : SingleVaultDedupCapture(projects) {}
        }

    override suspend fun clean(captured: SingleVaultDedupCapture, selected: List<SingleVaultDedupCandidate>,
        checkActive: () -> Unit): SingleVaultDedupUiOutcome {
        checkActive()
        return SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.NOTHING_CHANGED)
    }
    override suspend fun restore(batch: SingleVaultDedupRecoveryEntity, checkActive: () -> Unit): SingleVaultDedupUiOutcome {
        checkActive()
        return SingleVaultDedupUiOutcome(SingleVaultDedupUiOutcomeStatus.NOTHING_CHANGED)
    }
}

/** Unknown nodes remain in the ephemeral native document and block proof rather than disappearing. */
internal object SingleVaultDedupBitwardenDocument {
    private val top = words("id object organizationId collectionIds edit viewPassword permissions organizationUseTotp folderId type name notes login card identity secureNote sshKey fields favorite reprompt key revisionDate creationDate archivedDate deletedDate attachments passwordHistory encryptedFor lastKnownRevisionDate data partialData encryptedByKeyId")
    private val audit = words("id object revisionDate creationDate deletedDate lastKnownRevisionDate key encryptedByKeyId encryptedFor")
    private val card = words("cardholderName brand number expMonth expYear code")
    private val identity = words("title firstName middleName lastName address1 address2 address3 city state postalCode country company email phone ssn username passportNumber licenseNumber")
    private val ssh = words("privateKey publicKey keyFingerprint")

    fun project(sourceKey: String, cipher: CipherApiResponse, vaultKey: SymmetricCryptoKey,
        checkActive: () -> Unit = {}): SingleVaultDedupProject {
        val blockers = linkedSetOf<SingleVaultDedupBlockReason>()
        var title = ""
        var node: SingleVaultDedupNode? = null
        var key: SymmetricCryptoKey? = null
        try {
            checkActive()
            val raw = BitwardenCipherDocument.canonicalForExport(requireNotNull(cipher.sourceDocument))
            node = SingleVaultDedupProjectBuilder.exactJsonNode("bitwarden-unverified-native", raw)
            if (raw.keys.any { it !in top } || !BitwardenCipherDocument.canProject(cipher))
                blockers += SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT
            if (raw["data"].present() || raw["partialData"].present()) blockers += SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT
            if (!cipher.organizationId.isNullOrBlank()) {
                blockers += SingleVaultDedupBlockReason.EXTERNAL_REFERENCE
                return result(sourceKey, cipher.id, title, node, blockers)
            }
            key = BitwardenCipherKeyResolver.resolveCipherKey(cipher, vaultKey, "DedupAnalysis")
            val ownedKey = key
            fun decrypt(value: JsonElement?): JsonElement {
                checkActive()
                if (value == null || value == JsonNull) return JsonNull
                require(value is JsonPrimitive && value.isString)
                return JsonPrimitive(BitwardenCrypto.decryptToString(value.content, ownedKey))
            }
            fun section(value: JsonElement?, encrypted: Set<String>, allowed: Set<String> = encrypted): JsonElement {
                if (value == null || value == JsonNull) return JsonNull
                require(value is JsonObject)
                if (value.keys.any { it !in allowed }) blockers += SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT
                return JsonObject(value.mapValues { (name, content) -> if (name in encrypted) decrypt(content) else content })
            }
            fun array(value: JsonElement?, convert: (JsonElement) -> JsonElement): JsonElement {
                if (value == null || value == JsonNull) return JsonNull
                require(value is JsonArray)
                return JsonArray(value.map(convert))
            }
            val decoded = raw.toMutableMap()
            decoded["name"] = decrypt(raw["name"])
            title = (decoded["name"] as? JsonPrimitive)?.content.orEmpty()
            if (title.isBlank()) blockers += SingleVaultDedupBlockReason.EMPTY_CONTENT
            if ("notes" in raw) decoded["notes"] = decrypt(raw["notes"])
            if ("login" in raw && raw["login"] != JsonNull) {
                val login = (section(raw["login"], words("username password totp uri"),
                    words("username password totp uri uris passwordRevisionDate fido2Credentials")) as JsonObject).toMutableMap()
                if ("uris" in login) login["uris"] = array(login["uris"]) {
                    section(it, words("uri uriChecksum"), words("uri uriChecksum match"))
                }
                if (login["fido2Credentials"].nonEmpty()) {
                    blockers += SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT
                    blockers += SingleVaultDedupBlockReason.PASSKEY_PRESERVED
                }
                decoded["login"] = JsonObject(login)
            }
            listOf("card" to card, "identity" to identity, "sshKey" to ssh).forEach { (name, names) ->
                if (name in raw) decoded[name] = section(raw[name], names)
            }
            if ("secureNote" in raw) decoded["secureNote"] = section(raw["secureNote"], emptySet(), setOf("type"))
            if ("fields" in raw) decoded["fields"] = array(raw["fields"]) { field ->
                val value = section(field, words("name value"), words("name value type linkedId")) as JsonObject
                if (value["type"]?.jsonPrimitive?.intOrNull !in setOf(0, 1, 2)) blockers += SingleVaultDedupBlockReason.EXTERNAL_REFERENCE
                val name = (value["name"] as? JsonPrimitive)?.content.orEmpty()
                if (name.contains("passkey", true) || name.contains("fido2", true))
                    blockers += SingleVaultDedupBlockReason.PASSKEY_REFERENCE
                if (name.startsWith("monica.content.") || name == "passwordGroupId")
                    blockers += SingleVaultDedupBlockReason.INCONSISTENT_PROJECT
                value
            }
            if ("passwordHistory" in raw) decoded["passwordHistory"] = array(raw["passwordHistory"]) {
                section(it, setOf("password"), words("password lastUsedDate"))
            }
            if (raw["attachments"].nonEmpty()) blockers += SingleVaultDedupBlockReason.INCOMPLETE_SNAPSHOT
            val children = buildList {
                add(SingleVaultDedupProjectBuilder.exactJsonNode("bitwarden-context", JsonObject(decoded - audit - setOf("fields"))))
                // Exact typed field nodes retain duplicate multiplicity and relative order.
                (decoded["fields"] as? JsonArray)?.forEach { add(SingleVaultDedupProjectBuilder.exactJsonNode("bitwarden-field", it)) }
            }
            node = SingleVaultDedupNode("bitwarden-project", attributes = mapOf("fieldsShape" to when {
                "fields" !in decoded -> "absent"
                decoded["fields"] == JsonNull -> "null"
                else -> "array"
            }), children = children)
            checkActive()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { blockers += SingleVaultDedupBlockReason.UNREADABLE_CONTENT
        } finally { key?.clear() }
        checkActive()
        return result(sourceKey, cipher.id, title, node, blockers)
    }

    private fun result(source: String, id: String, title: String, node: SingleVaultDedupNode?,
        blockers: Set<SingleVaultDedupBlockReason>) = SingleVaultDedupProject(
        SingleVaultDedupProjectSummary("$source:$id", source, title, emptyList(), node?.contentCount() ?: 0), node, blockers)
    private fun words(value: String) = value.split(' ').toSet()
}

private fun JsonElement?.present() = this != null && this != JsonNull
private fun JsonElement?.nonEmpty() = present() && (this !is JsonArray || isNotEmpty())
