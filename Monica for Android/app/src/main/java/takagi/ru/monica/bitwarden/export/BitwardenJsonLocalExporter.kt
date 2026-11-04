package takagi.ru.monica.bitwarden.export

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import takagi.ru.monica.bitwarden.service.BitwardenLoginUriRules
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.passkey.PasskeyCredentialIdCodec
import takagi.ru.monica.passkey.PasskeyPrivateKeySupport
import takagi.ru.monica.transfer.DatabaseExportSnapshot
import java.time.Instant
import java.util.UUID

internal object BitwardenJsonLocalExporter {
    private val json = Json { encodeDefaults = true }
    fun export(snapshot: DatabaseExportSnapshot, history: List<PasswordHistoryEntry>,
        decrypt: (String) -> String, resolveKey: (String) -> String?): BitwardenJsonExport {
        val problems = mutableListOf<BitwardenJsonIssue>()
        val folders = linkedMapOf<Long, String>().apply { snapshot.categories.keys.forEach { put(it, UUID.randomUUID().toString()) } }
        val items = mutableListOf<JsonElement>()
        fun folder(id: Long?): JsonElement = id?.let {
            require(snapshot.categories.containsKey(it)) { "Missing local folder" }
            JsonPrimitive(folders.getOrPut(it) { UUID.randomUUID().toString() })
        } ?: JsonNull
        fun issue(index: Int, name: String, path: String, reason: BitwardenJsonIssue.Reason = BitwardenJsonIssue.Reason.UNSUPPORTED_VALUE) {
            problems += BitwardenJsonIssue(index, name.take(200), "items[$index].$path", reason)
        }
        fun base(type: Int, title: String, notes: String, favorite: Boolean, category: Long?, created: Long, revised: Long, deleted: Long?) = buildJsonObject {
            put("id", UUID.randomUUID().toString()); put("type", type); put("name", title); put("notes", notes)
            put("favorite", favorite); put("reprompt", 0); put("folderId", folder(category))
            put("organizationId", JsonNull); put("collectionIds", JsonNull)
            put("creationDate", Instant.ofEpochMilli(created).toString()); put("revisionDate", Instant.ofEpochMilli(revised).toString())
            put("deletedDate", deleted?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull)
        }.toMutableMap()
        fun fields(values: List<SecureCustomField>) = JsonArray(values.map { buildJsonObject {
            put("name", it.label); put("value", it.value); put("type", when (it.type) { SecureCustomFieldType.TEXT -> 0; SecureCustomFieldType.HIDDEN -> 1; SecureCustomFieldType.BOOLEAN -> 2 }); put("linkedId", JsonNull)
        } })
        fun fido(key: PasskeyEntry, index: Int, title: String): JsonObject {
            if (key.publicKeyAlgorithm != PasskeyEntry.ALGORITHM_ES256 || key.backupEligible == false) issue(index, title, "fido2Credentials.algorithm")
            val material = resolveKey(key.privateKeyAlias)?.let(PasskeyPrivateKeySupport::exportPkcs8Base64)
            if (material == null) issue(index, title, "fido2Credentials.keyValue", BitwardenJsonIssue.Reason.UNAVAILABLE)
            val credentialId = PasskeyCredentialIdCodec.toBitwardenCredentialId(key.credentialId)
            if (!PasskeyCredentialIdCodec.isValid(key.credentialId) || credentialId == null) issue(index, title, "fido2Credentials.credentialId")
            return buildJsonObject {
                put("credentialId", credentialId.orEmpty()); put("keyType", "public-key"); put("keyAlgorithm", "ECDSA"); put("keyCurve", "P-256")
                put("keyValue", material.orEmpty()); put("rpId", key.rpId); put("rpName", key.rpName); put("userHandle", key.userId)
                put("userName", key.userName); put("userDisplayName", key.userDisplayName); put("counter", key.signCount.toString())
                put("discoverable", key.isDiscoverable.toString()); put("creationDate", Instant.ofEpochMilli(key.createdAt).toString())
            }
        }
        val includedPasskeys = mutableSetOf<Long>()
        snapshot.passwords.sortedWith(compareBy<PasswordEntry> { it.sortOrder }.thenBy { it.id }).forEach { entry ->
            val index = items.size
            try {
                if (entry.isArchived) issue(index, entry.title, "archivedDate", BitwardenJsonIssue.Reason.ARCHIVED)
                val extras = mapOf("appPackageName" to entry.appPackageName, "appName" to entry.appName,
                    "email" to entry.email, "phone" to entry.phone, "addressLine" to entry.addressLine,
                    "city" to entry.city, "state" to entry.state, "zipCode" to entry.zipCode, "country" to entry.country,
                    "creditCardNumber" to entry.creditCardNumber, "creditCardHolder" to entry.creditCardHolder,
                    "creditCardExpiry" to entry.creditCardExpiry, "creditCardCVV" to entry.creditCardCVV,
                    "passkeyBindings" to entry.passkeyBindings, "wifiMetadata" to entry.wifiMetadata,
                    "ssoProvider" to entry.ssoProvider)
                extras.filterValues { it.isNotEmpty() }.forEach { (field, _) -> issue(index, entry.title, field) }
                if (entry.boundNoteId != null || entry.ssoRefEntryId != null || entry.passwordGroupId != null) issue(index, entry.title, "linkedContent")
                if (entry.loginType !in setOf("PASSWORD", LOGIN_TYPE_SSH_KEY)) issue(index, entry.title, "loginType", BitwardenJsonIssue.Reason.UNSUPPORTED_TYPE)
                val type = if (entry.loginType == LOGIN_TYPE_SSH_KEY) 5 else 1
                val item = base(type, entry.title, entry.notes, entry.isFavorite, entry.categoryId, entry.createdAt.time, entry.updatedAt.time, entry.deletedAt?.time)
                val localFields = snapshot.fields[entry.id].orEmpty()
                val rulesField = localFields.filter { it.title == PasswordUrlRules.FIELD }
                if (rulesField.size > 1) issue(index, entry.title, "uris")
                val rules = rulesField.singleOrNull()?.let { PasswordUrlRules.decode(it.value) }
                if (rulesField.isNotEmpty() && rules == null) issue(index, entry.title, "uris")
                item["fields"] = JsonArray(localFields.filterNot { it.title == PasswordUrlRules.FIELD }.map { buildJsonObject {
                    put("name", it.title); put("value", it.value); put("type", if (it.isProtected) 1 else 0); put("linkedId", JsonNull)
                } })
                item["passwordHistory"] = JsonArray(history.filter { it.entryId == entry.id }.map { buildJsonObject {
                    put("password", decrypt(it.password)); put("lastUsedDate", Instant.ofEpochMilli(it.lastUsedAt.time).toString())
                } })
                val keys = snapshot.passkeys.filter { it.boundPasswordId == entry.id }.sortedBy { it.id }
                includedPasskeys += keys.map { it.id }
                if (type == 1) {
                    if (entry.sshKeyData.isNotEmpty()) issue(index, entry.title, "sshKeyData")
                    item["login"] = buildJsonObject {
                        put("username", entry.username); put("password", decrypt(entry.password)); put("totp", if (entry.authenticatorKey.isEmpty()) JsonNull else JsonPrimitive(decrypt(entry.authenticatorKey)))
                        put("uris", JsonArray((rules ?: entry.website.lineSequence().filter { it.isNotEmpty() }.map { PasswordUrlRules.Rule(it) }.toList()).map { rule -> buildJsonObject {
                            put("uri", rule.url); put("match", BitwardenLoginUriRules.match(rule.mode)?.let(::JsonPrimitive) ?: JsonNull)
                        } }))
                        put("fido2Credentials", JsonArray(keys.map { fido(it, index, entry.title) }))
                    }
                } else {
                    val ssh = requireNotNull(SshKeyDataCodec.decode(entry.sshKeyData))
                    if (ssh.additionalFields.isNotEmpty() || ssh.comment.isNotEmpty() || ssh.keySize != 0) issue(index, entry.title, "sshKey.metadata")
                    if (entry.website.isNotEmpty() || entry.username.isNotEmpty() || decrypt(entry.password).isNotEmpty() || entry.authenticatorKey.isNotEmpty() || keys.isNotEmpty()) issue(index, entry.title, "sshKey.loginContent")
                    item["sshKey"] = buildJsonObject { put("privateKey", ssh.privateKeyOpenSsh); put("publicKey", ssh.publicKeyOpenSsh); put("keyFingerprint", ssh.fingerprintSha256) }
                }
                items += JsonObject(item)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { issue(index, entry.title, "", BitwardenJsonIssue.Reason.UNAVAILABLE) }
        }
        snapshot.secureItems.sortedWith(compareBy<SecureItem> { it.sortOrder }.thenBy { it.id }).forEach { entry ->
            val index = items.size
            try {
                if (entry.isArchived) issue(index, entry.title, "archivedDate", BitwardenJsonIssue.Reason.ARCHIVED)
                val type = when (entry.itemType) { ItemType.NOTE -> 2; ItemType.BANK_CARD -> 3; ItemType.DOCUMENT -> 4; else -> 0 }
                if (type == 0) { issue(index, entry.title, "type", BitwardenJsonIssue.Reason.UNSUPPORTED_TYPE); return@forEach }
                val raw = decrypt(entry.itemData)
                val item = base(type, entry.title, entry.notes, entry.isFavorite, entry.categoryId, entry.createdAt.time, entry.updatedAt.time, entry.deletedAt?.time)
                when (type) {
                    2 -> {
                        val note = json.decodeFromString<NoteData>(raw)
                        if (note.tags.isNotEmpty()) issue(index, entry.title, "tags")
                        if (note.isMarkdown) issue(index, entry.title, "isMarkdown")
                        if (entry.notes.isNotEmpty() && entry.notes != note.content) issue(index, entry.title, "notes")
                        item["notes"] = JsonPrimitive(note.content); item["fields"] = fields(note.customFields)
                        item["secureNote"] = buildJsonObject { put("type", 0) }
                    }
                    3 -> {
                        val card = json.decodeFromString<BankCardData>(raw)
                        val clean = card.copy(cardNumber = "", cardholderName = "", expiryMonth = "", expiryYear = "", cvv = "", brand = "", customFields = emptyList(), cardFace = null, editorSectionOrder = emptyList())
                        if (clean != BankCardData("", "", "", "")) issue(index, entry.title, "card.extraFields")
                        item["fields"] = fields(card.customFields)
                        item["card"] = buildJsonObject { put("number", card.cardNumber); put("cardholderName", card.cardholderName); put("expMonth", card.expiryMonth); put("expYear", card.expiryYear); put("code", card.cvv); put("brand", card.brand) }
                    }
                    4 -> {
                        val data = json.decodeFromString<DocumentData>(raw)
                        val all = json.encodeToJsonElement(data).jsonObject.toMutableMap()
                        all["state"] = all.remove("stateProvince") ?: JsonPrimitive("")
                        val standard = all.filterKeys { it in BitwardenJsonCodec.identityNames }
                        val defaults = json.encodeToJsonElement(DocumentData(DocumentType.OTHER, "", "")).jsonObject
                        val extras = all.filterKeys { it !in BitwardenJsonCodec.identityNames && it !in setOf("customFields", "cardFace", "editorSectionOrder", "documentType", "fullName") }
                        extras.filter { (key, value) -> defaults[key] != value }.forEach { (key, _) -> issue(index, entry.title, "identity.$key") }
                        val computedFullName = listOf(data.firstName, data.middleName, data.lastName).filter { it.isNotBlank() }.joinToString(" ")
                        if (data.fullName.isNotEmpty() && data.fullName != computedFullName) issue(index, entry.title, "identity.fullName")
                        if (data.documentType != DocumentType.OTHER) issue(index, entry.title, "identity.documentType")
                        item["identity"] = JsonObject(standard); item["fields"] = fields(data.customFields)
                    }
                }
                item["passwordHistory"] = JsonArray(emptyList()); items += JsonObject(item)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { issue(index, entry.title, "", BitwardenJsonIssue.Reason.UNAVAILABLE) }
        }
        snapshot.passkeys.filter { it.id !in includedPasskeys }.sortedBy { it.id }.forEach { key ->
            val index = items.size
            if (key.boundPasswordId != null) issue(index, key.rpName, "boundPasswordId")
            val item = base(1, key.rpName.ifBlank { key.rpId }, key.notes, false, key.categoryId, key.createdAt, key.createdAt, null)
            item["fields"] = JsonArray(emptyList()); item["passwordHistory"] = JsonArray(emptyList())
            item["login"] = buildJsonObject {
                put("username", key.userName); put("password", JsonNull); put("totp", JsonNull); put("uris", JsonArray(emptyList()))
                put("fido2Credentials", JsonArray(listOf(fido(key, index, key.rpName))))
            }; items += JsonObject(item)
        }
        snapshot.steamAccounts.forEach { problems += BitwardenJsonIssue(null, null, "steamAccounts", BitwardenJsonIssue.Reason.UNSUPPORTED_TYPE) }
        snapshot.nativeTokens.forEach { problems += BitwardenJsonIssue(null, it.title.take(200), "nativeTokens", BitwardenJsonIssue.Reason.UNSUPPORTED_TYPE) }
        val document = BitwardenJsonDocument(JsonArray(folders.map { (id, uuid) -> buildJsonObject { put("id", uuid); put("name", snapshot.categories.getValue(id)) } }), JsonArray(items))
        problems += BitwardenJsonCodec.validate(document.json())
        if (problems.isNotEmpty()) throw BitwardenJsonRejected(problems)
        return BitwardenJsonExport(document, snapshot.attachments.size)
    }
}
