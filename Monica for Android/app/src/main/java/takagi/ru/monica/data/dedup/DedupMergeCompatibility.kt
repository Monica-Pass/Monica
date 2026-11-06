package takagi.ru.monica.data.dedup

import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.constants.GroupOverride
import kotlinx.serialization.json.*
import takagi.ru.monica.bitwarden.BitwardenVaultIdentity
import takagi.ru.monica.bitwarden.service.BitwardenCipherDocument
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.keepass.KeePassNativeEntryRecord
import takagi.ru.monica.keepass.KeePassNativeBrowserSnapshot
import takagi.ru.monica.keepass.KeePassNativeGroupIdentity
import takagi.ru.monica.security.SecurityManager

/** Capability checks concern each complete source record, not its provider name. */
internal class DedupMergeCompatibility(private val db: PasswordDatabase, private val security: SecurityManager) {
    fun password(entry: PasswordEntry): Boolean = entry.boundNoteId == null && entry.ssoRefEntryId == null &&
        entry.passkeyBindings.isEmpty() && (entry.replicaGroupId == null || entry.mdbxDatabaseId != null) &&
        entry.customIconType != "UPLOADED" && entry.bitwardenCipherType == 1

    /** Copy only self-contained known payloads; retaining an old numeric ID would bind the new owner to the source. */
    fun secure(item: SecureItem, attachmentNames: Set<String>, onPasskeyFound: () -> Unit = {}): Boolean {
        fun decode(value: String): String {
            if (value.isEmpty()) return value
            if (security.looksLikeMonicaCiphertext(value)) {
                require(value == value.trim())
                return security.decryptData(value)
            }
            require(!SingleVaultDedupLocalContent.couldBeLegacyCiphertext(value))
            return value
        }
        val payload = SingleVaultDedupLocalContent.decodeJson(
            SingleVaultDedupProjectBuilder.strictJson(decode(item.itemData)), ::decode)
        if (DedupStructuredPasskeyPolicy.containsPasskeyContent(payload)) {
            onPasskeyFound()
            return false
        }
        fun hasSourceOwnership(value: JsonElement): Boolean = when (value) {
            is JsonObject -> value.any { (key, child) ->
                key in setOf("categoryId", "keepassDatabaseId", "mdbxDatabaseId", "bitwardenVaultId") && child != JsonNull ||
                    hasSourceOwnership(child)
            }
            is JsonArray -> value.any(::hasSourceOwnership)
            else -> false
        }
        return SingleVaultDedupLocalContent.knownSchema(item.itemType, payload) &&
            SingleVaultDedupLocalContent.referencedPasswordIds(payload).isEmpty() && !hasSourceOwnership(payload) &&
            !SingleVaultDedupLocalContent.externalAssets(payload, attachmentNames) &&
            !SingleVaultDedupLocalContent.externalAssets(JsonPrimitive(decode(item.notes)), attachmentNames)
    }

    fun nativePassword(record: KeePassNativeEntryRecord, entry: PasswordEntry, fields: List<CustomField>): Boolean {
        if (record.kind == takagi.ru.monica.keepass.KeePassNativeEntryKind.PASSKEY || !nativeBehavior(record)) return false
        val standard = mapOf("Title" to entry.title, "UserName" to entry.username, "Password" to entry.password,
            "URL" to entry.website, "Notes" to entry.notes, "App Package Name" to entry.appPackageName,
            "App Name" to entry.appName, "Email" to entry.email, "Phone" to entry.phone,
            "Address" to entry.addressLine, "City" to entry.city, "State" to entry.state,
            "Postal Code" to entry.zipCode, "Country" to entry.country, "Card Number" to entry.creditCardNumber,
            "Card Holder" to entry.creditCardHolder, "Card Expiry" to entry.creditCardExpiry, "Card CVV" to entry.creditCardCVV,
            "MonicaPasswordGroupId" to entry.passwordGroupId.orEmpty(), "MonicaIsFavorite" to entry.isFavorite.toString(),
            "MonicaIsArchived" to entry.isArchived.toString(), "MonicaSortOrder" to entry.sortOrder.toString(),
            "MonicaIsGroupCover" to entry.isGroupCover.toString(), "MonicaLoginType" to entry.loginType,
            "SSO Provider" to entry.ssoProvider, "MonicaWifiData" to entry.wifiMetadata,
            "MonicaCustomIconType" to entry.customIconType, "MonicaCustomIconValue" to entry.customIconValue.orEmpty())
        val remaining = fields.map { Triple(it.title, security.decryptDataIfMonicaCiphertext(it.value), it.isProtected) }.toMutableList()
        return record.fields.all { field ->
            if (field.rawValue != field.displayValue || field.rawValue.contains("{REF:", ignoreCase = true)) return@all false
            val mapped = standard[field.name]
            when {
                mapped != null -> security.decryptDataIfMonicaCiphertext(mapped) == field.rawValue &&
                    (!field.isProtected || field.name in setOf("Password", "Card Number", "Card CVV", "MonicaWifiData"))
                field.name == "MonicaLocalId" || field.name == "MonicaCategoryId" -> true // Source-only ownership, destination is reviewed root.
                else -> remaining.remove(Triple(field.name, field.rawValue, field.isProtected))
            }
        }
    }

    fun nativeSecure(record: KeePassNativeEntryRecord, item: SecureItem): Boolean {
        if (record.kind == takagi.ru.monica.keepass.KeePassNativeEntryKind.PASSKEY || !nativeBehavior(record) || item.replicaGroupId != null) return false
        // SecureItem has no generic field collection. Extra native fields must never disappear.
        val supported = mapOf("Title" to item.title, "Notes" to item.notes, "MonicaItemType" to item.itemType.name,
            "MonicaItemData" to security.decryptDataIfMonicaCiphertext(item.itemData),
            "MonicaImagePaths" to item.imagePaths, "MonicaIsFavorite" to item.isFavorite.toString())
        return record.fields.all { field ->
            field.rawValue == field.displayValue && !field.rawValue.contains("{REF:", true) &&
                (field.name == "MonicaSecureItemId" || supported[field.name] == field.rawValue &&
                    (!field.isProtected || field.name == "MonicaItemData"))
        }
    }

    private fun nativeBehavior(record: KeePassNativeEntryRecord): Boolean =
        record.occurrenceIndex == 0 && !record.isInRecycleBin && record.history.isEmpty() &&
            record.tags.isEmpty() && record.customData.isEmpty() && record.autoType == null &&
            record.icon == PredefinedIcon.Key && record.customIconUuid == null && record.customIcon == null &&
            record.foregroundColor.isNullOrEmpty() && record.backgroundColor.isNullOrEmpty() && record.overrideUrl.isEmpty() &&
            record.times?.expires != true && record.previousParentGroup == null && record.qualityCheck &&
            record.attachments.none { it.isMissing }

    fun nativeAncestors(record: KeePassNativeEntryRecord, snapshot: KeePassNativeBrowserSnapshot): Boolean {
        var identity: KeePassNativeGroupIdentity? = record.parentGroup
        val seen = mutableSetOf<KeePassNativeGroupIdentity>()
        while (identity != null) {
            if (!seen.add(identity)) return false
            val group = snapshot.groupsByIdentity[identity]?.singleOrNull() ?: return false
            // Kotpass-created roots explicitly enable the same defaults that an inherited root uses.
            // Descendant overrides still carry native behavior that this destination cannot preserve.
            val isRoot = group.parentGroup == null
            fun isDefault(value: GroupOverride) = value == GroupOverride.Inherit ||
                isRoot && value == GroupOverride.Enabled
            if (!group.defaultAutoTypeSequence.isNullOrEmpty() || !isDefault(group.enableAutoType) ||
                !isDefault(group.enableSearching) || group.customData.isNotEmpty() ||
                group.times?.expires == true) return false
            identity = group.parentGroup
        }
        return true
    }

    /** Returns the full bound raw document for review revalidation; null means unsupported/unreadable. */
    suspend fun bitwarden(vaultId: Long, cipherId: String?, revision: String?, modified: Boolean,
        onPasskeyFound: () -> Unit = {}): JsonObject? {
        // Unsynced local-only drafts have no server-native properties yet.
        if (cipherId.isNullOrEmpty()) return if (modified) JsonObject(emptyMap()) else null
        if (modified || revision.isNullOrEmpty()) return null
        val vault = db.bitwardenVaultDao().getVaultById(vaultId) ?: return null
        if (vault.userId.isNullOrBlank()) return null
        val account = BitwardenVaultIdentity.buildAccountKey(vault.serverUrl, vault.userId,
            BitwardenVaultIdentity.resolveCanonicalEmail(vault))
        val source = db.bitwardenCipherSourceDao().get(vaultId, cipherId, revision) ?: return null
        if (source.accountKey != account) return null
        val envelope = Json.parseToJsonElement(security.decryptData(source.sealedDocument)).jsonObject
        require(envelope["format"] == JsonPrimitive(1) && envelope["vaultId"] == JsonPrimitive(vaultId) &&
            envelope["accountKey"] == JsonPrimitive(account) && envelope["cipherId"] == JsonPrimitive(cipherId) &&
            envelope["revisionDate"] == JsonPrimitive(revision))
        val raw = envelope.getValue("document").jsonObject
        val cipher = BitwardenCipherDocument.decode(raw)
        if (cipher.id != cipherId || cipher.revisionDate != revision) return null
        // A sealed native credential can exist without a projected Passkey row.
        // Only report its owning project, never disclose FIDO material to the UI.
        if (!cipher.login?.fido2Credentials.isNullOrEmpty()) onPasskeyFound()
        val normalized = BitwardenCipherDocument.canonicalForExport(raw)
        if (normalized.keys.any { it !in TOP_FIELDS }) return null
        if (cipher.organizationId != null || !cipher.collectionIds.isNullOrEmpty() || cipher.reprompt != 0 ||
            cipher.edit == false || cipher.viewPassword == false || cipher.deletedDate != null ||
            !empty(cipher.passwordHistory) || !empty(cipher.data) || !empty(cipher.partialData) || !empty(cipher.encryptedByKeyId)) return null
        // The current destination does not implement linked/boolean field or per-URI matching behavior.
        if (cipher.fields.orEmpty().any { it.type !in 0..1 || it.linkedId != null }) return null
        if (cipher.login?.uris.orEmpty().size > 1 || cipher.login?.uris.orEmpty().any { it.match != null } ||
            !cipher.login?.fido2Credentials.isNullOrEmpty()) return null
        val sections = mapOf("login" to setOf("username", "password", "passwordRevisionDate", "totp", "uris", "fido2Credentials"),
            "fields" to setOf("name", "value", "type", "linkedId"), "uris" to setOf("uri", "match", "uriChecksum"),
            "secureNote" to setOf("type"), "attachments" to setOf("id", "url", "fileName", "key", "size", "sizeName"))
        fun known(value: JsonElement?, section: String): Boolean {
            if (empty(value)) return true
            if (value is JsonArray) return value.all { known(it, section) }
            if (value !is JsonObject) return false
            val keys = sections[section] ?: return false
            return value.keys.all { it in keys } && (section != "login" || known(value["uris"], "uris"))
        }
        // Card/identity/SSH cipher projection requires its own complete adapter. Do not copy as a login.
        if (cipher.type !in setOf(1, 2)) return null
        if (cipher.type == 2 && !cipher.fields.isNullOrEmpty()) return null
        if (!known(normalized[if (cipher.type == 1) "login" else "secureNote"], if (cipher.type == 1) "login" else "secureNote") ||
            !known(normalized["fields"], "fields") || !known(normalized["attachments"], "attachments")) return null
        return normalized
    }

    private fun empty(value: JsonElement?) = value == null || value == JsonNull || value is JsonArray && value.isEmpty()
    private companion object {
        val TOP_FIELDS = setOf("id", "object", "organizationId", "collectionIds", "edit", "viewPassword", "folderId", "type",
            "name", "notes", "login", "card", "identity", "secureNote", "sshKey", "fields", "favorite", "reprompt", "key",
            "revisionDate", "creationDate", "archivedDate", "deletedDate", "attachments", "passwordHistory", "data", "partialData", "encryptedByKeyId")
    }
}
