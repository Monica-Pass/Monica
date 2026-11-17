package takagi.ru.monica.repository

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import takagi.ru.monica.bitwarden.service.BitwardenWireJson
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.DocumentData
import takagi.ru.monica.data.model.BillingAddressData
import takagi.ru.monica.data.model.PasswordUrlRules
import takagi.ru.monica.data.model.ProjectCredentialGroup
import uniffi.mdbx_ffi.*
import java.security.MessageDigest
import java.util.Date

/** Request-owned plaintext only: never persist these display adapters as Room records. */
internal data class MdbxAutofillSnapshot(val credentials: List<MdbxAutofillCredential>,
    val walletItems: List<MdbxAutofillWallet> = emptyList())

internal data class MdbxAutofillWallet(val objectId: String, val revision: String, val item: SecureItem,
    val logicalEntryId: String? = null, val nativeVaultId: String? = null)

internal data class MdbxAutofillCredential(
    val objectId: String,
    val revision: String,
    val entry: PasswordEntry,
    // null = no rule metadata; empty = present but unusable, so matching must fail closed.
    val urlRules: List<PasswordUrlRules.Rule>?,
    val customFields: List<CustomFieldDraft> = emptyList(),
    // A legacy logical identity is useful only after proving its physical UUID in this vault.
    val logicalEntryId: String? = null,
    // Actual opened vault identity, independent of mutable payload metadata.
    val nativeVaultId: String? = null,
)

internal object MdbxAutofillReader {
    const val MAX_PAYLOAD_BYTES = 1024 * 1024
    private const val MAX_OBJECTS = 20_000
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private val supportedTypes = setOf("login", "card", "document-ref", "billing-address")
    private val walletJson = Json { ignoreUnknownKeys = true }

    fun snapshot(vault: MdbxVault, databaseId: Long, namespace: String,
        decryptLegacy: (String) -> String, checkActive: () -> Unit): MdbxAutofillSnapshot {
        val credentials = mutableListOf<MdbxAutofillCredential>()
        val wallets = mutableListOf<MdbxAutofillWallet>()
        var total = 0L
        var inspected = 0
        var cursor: String? = null
        val collectionCursors = mutableSetOf<String>()
        val seenObjects = mutableSetOf<String>()
        do {
            checkActive()
            val collections = vault.listCollectionSummaries(200u, cursor)
            collections.items.filterNot { it.deleted }.forEach { collection ->
                var entryCursor: String? = null
                val entryCursors = mutableSetOf<String>()
                do {
                    checkActive()
                    val page = vault.listObjectSummaries(collection.collectionId, null, 200u, entryCursor)
                    page.items.forEach { summary ->
                        checkActive()
                        check(++inspected <= MAX_OBJECTS) { "MDBX autofill entry limit exceeded" }
                        check(seenObjects.add(summary.objectId)) { "MDBX autofill listing changed" }
                        val disclosed = disclose(vault, namespace, summary, checkActive) { bytes ->
                            total += bytes
                            check(total <= MAX_TOTAL_BYTES) { "MDBX autofill payload limit exceeded" }
                        }
                        if (disclosed != null) {
                            val (record, revision) = disclosed
                            if (record.objectTypeId == "login") parse(record.payloadJson, databaseId,
                                record.objectId, record.collectionId, record.title, revision, decryptLegacy, vault.info().vaultId)?.let(credentials::add)
                            else parseWallet(record.payloadJson, record.objectTypeId, databaseId, record.objectId,
                                record.collectionId, record.title, revision, vault.info().vaultId)?.let(wallets::add)
                        }
                    }
                    entryCursor = page.nextCursor
                    check(entryCursor == null || (page.items.isNotEmpty() && entryCursors.add(entryCursor!!)))
                } while (entryCursor != null)
            }
            cursor = collections.nextCursor
            check(cursor == null || (collections.items.isNotEmpty() && collectionCursors.add(cursor!!)))
        } while (cursor != null)
        checkActive()
        return MdbxAutofillSnapshot(credentials, wallets)
    }

    fun credential(vault: MdbxVault, databaseId: Long, namespace: String, objectId: String,
        expectedRevision: String, decryptLegacy: (String) -> String,
        checkActive: () -> Unit): MdbxAutofillCredential? {
        checkActive()
        val summary = vault.getObjectSummary(objectId) ?: return null
        if (revision(vault.info().vaultId, namespace, summary) != expectedRevision) return null
        if (summary.objectTypeId != "login") return null
        val (record, revision) = disclose(vault, namespace, summary, checkActive) {} ?: return null
        val result = parse(record.payloadJson, databaseId, objectId, record.collectionId, record.title, revision,
            decryptLegacy, vault.info().vaultId)
        checkActive()
        return result?.takeIf { it.revision == expectedRevision }
    }

    fun wallet(vault: MdbxVault, databaseId: Long, namespace: String, objectId: String,
        expectedRevision: String, checkActive: () -> Unit): MdbxAutofillWallet? {
        checkActive()
        val summary = vault.getObjectSummary(objectId) ?: return null
        if (summary.objectTypeId == "login" || revision(vault.info().vaultId, namespace, summary) != expectedRevision) return null
        val (record, revision) = disclose(vault, namespace, summary, checkActive) {} ?: return null
        val result = parseWallet(record.payloadJson, record.objectTypeId, databaseId, objectId,
            record.collectionId, record.title, revision, vault.info().vaultId)
        checkActive()
        return result?.takeIf { it.revision == expectedRevision }
    }

    private fun disclose(vault: MdbxVault, namespace: String, summary: MdbxObjectSummary,
        checkActive: () -> Unit, countBytes: (Int) -> Unit): Pair<MdbxObjectRecord, String>? {
        if (summary.deleted || summary.objectTypeId !in supportedTypes || summary.payloadSchemaVersion != 1u) return null
        checkActive()
        val disclosure = vault.revealObjectWithLimits(summary.objectId, MdbxObjectDisclosureLimits(MAX_PAYLOAD_BYTES.toULong()))
        checkActive()
        if (!accepts(disclosure.authorization)) return null
        val record = disclosure.`object` ?: return null
        if (record.deleted || record.objectId != summary.objectId || record.collectionId != summary.collectionId ||
            record.objectTypeId != summary.objectTypeId || record.payloadSchemaVersion != summary.payloadSchemaVersion ||
            record.title != summary.title || vault.getObjectSummary(summary.objectId) != summary ||
            vault.getCollectionSummary(summary.collectionId)?.deleted != false) return null
        countBytes(record.payloadJson.toByteArray(Charsets.UTF_8).size)
        checkActive()
        return record to revision(vault.info().vaultId, namespace, summary)
    }

    /** Native authorization is authoritative. Unsupported client constraints never get dropped. */
    internal fun accepts(decision: MdbxAuthorizationDecision): Boolean =
        decision.outcome in setOf(MdbxAuthorizationOutcome.ALLOW, MdbxAuthorizationOutcome.ALLOW_WITH_CONSTRAINTS) &&
            decision.constraints.isEmpty()

    private fun revision(vaultId: String, namespace: String, summary: MdbxObjectSummary): String =
        mdbxAutofillDigest(listOf(namespace, vaultId, summary.objectId, summary.collectionId,
            summary.objectTypeId, summary.payloadSchemaVersion.toString(), summary.headCommitId, summary.title))

    /** Only the documented Android secure-item shape; an arbitrary native identity is not a document. */
    internal fun parseWallet(payload: String, type: String, databaseId: Long, objectId: String,
        folderId: String, title: String, revision: String, vaultId: String? = null): MdbxAutofillWallet? = try {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES)
        val root = BitwardenWireJson.parseObject(payload, MAX_PAYLOAD_BYTES)
        require(root["schema"] == null)
        val itemType = when (type) {
            "card" -> ItemType.BANK_CARD
            "document-ref" -> ItemType.DOCUMENT
            "billing-address" -> ItemType.BILLING_ADDRESS
            else -> error("Unsupported MDBX wallet type")
        }
        require(root.string("kind") == itemType.name.lowercase())
        listOf("is_archived", "isArchived", "is_deleted", "isDeleted", "bitwarden_mode", "keepass_mode").forEach {
            require(root[it] == null || root[it] == JsonNull || root[it] == JsonPrimitive(false))
        }
        listOf("bitwardenVaultId", "keepassDatabaseId", "bitwarden_vault_id", "keepass_database_id").forEach {
            require(root[it] == null || root[it] == JsonNull)
        }
        val data = root.getValue("item_data").stringOrEmpty()
        val document = BitwardenWireJson.parseObject(data, MAX_PAYLOAD_BYTES)
        require(document["schema"] == null && document["version"] == null)
        when (itemType) {
            ItemType.BANK_CARD -> walletJson.decodeFromString<BankCardData>(data)
            ItemType.DOCUMENT -> walletJson.decodeFromString<DocumentData>(data)
            ItemType.BILLING_ADDRESS -> {
                require(document.keys.any { it in setOf("fullName", "streetAddress", "city", "phone", "email", "firstName", "lastName") })
                walletJson.decodeFromString<BillingAddressData>(data)
            }
            else -> error("Unsupported MDBX wallet type")
        }
        MdbxAutofillWallet(objectId, revision, SecureItem(id = 0, itemType = itemType, title = title,
            itemData = data, notes = root.string("notes"), mdbxDatabaseId = databaseId, mdbxFolderId = folderId,
            replicaGroupId = objectId, createdAt = Date(0), updatedAt = Date(0)),
            verifiedLogicalEntryId(root, vaultId, objectId), nativeVaultId = vaultId)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { null }

    /** Interpret only the existing v1 login shapes. Unknown JSON stays in the native object. */
    internal fun parse(payload: String, databaseId: Long, objectId: String, folderId: String,
        title: String, revision: String, decryptLegacy: (String) -> String, vaultId: String? = null): MdbxAutofillCredential? = try {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES)
        // Reuse the bounded, duplicate-key rejecting JSON scanner; it performs no Bitwarden I/O.
        val root = BitwardenWireJson.parseObject(payload, MAX_PAYLOAD_BYTES)
        require(root["schema"] == null)
        val envelope = root["data"] as? JsonObject
        val wrapped = envelope?.get("entry") as? JsonObject
            ?: envelope?.takeIf { "id" in it }
        val source = wrapped ?: root
        require(source["schema"] == null)
        require(listOf("username", "password", "password_plain", "passwordPlain", "website").any(source::containsKey))
        if (wrapped != null) require(listOf("username", "password", "password_plain", "passwordPlain", "website").none(root::containsKey))
        require(source["kind"] == null || source.string("kind") == "password")
        // A supported wrapper does not make an unknown inner encoding safe to interpret.
        for (container in listOf(root, source)) {
            val encoding = container.string("monica_password_encoding")
            require(encoding.isEmpty() || encoding == "plaintext-v1")
        }
        val login = source.alias("login_type", "loginType")
        require(login == null || login == JsonPrimitive("PASSWORD") || (wrapped != null && login == JsonPrimitive(0)))
        listOf("is_archived", "isArchived", "is_deleted", "isDeleted", "bitwarden_mode", "keepass_mode").forEach {
            val value = source[it]
            require(value == null || value == JsonNull || value == JsonPrimitive(false))
        }
        listOf("bitwardenVaultId", "keepassDatabaseId", "bitwarden_vault_id", "keepass_database_id").forEach {
            require(source[it] == null || source[it] == JsonNull)
        }
        // CLI/native strings are already plaintext even when they resemble Monica ciphertext.
        // Only the identifiable older Android shape can contain Monica-encrypted local fields.
        val legacyAndroid = wrapped == null && source.string("kind") == "password" &&
            source.string("monica_entry_id").startsWith("password:") &&
            (source["room_id"] as? JsonPrimitive)?.longOrNull != null &&
            verifiedLogicalEntryId(root, vaultId, objectId) != null
        fun plain(key: String, camel: String = key) = source.stringAlias(key, camel).let {
            if (legacyAndroid) decryptLegacy(it) else it
        }
        val explicitPassword = source.alias("password_plain", "passwordPlain")
        val password = if ("password_plain" in source || "passwordPlain" in source)
            explicitPassword.stringOrEmpty() else plain("password")
        val custom = source.alias("custom_fields", "customFields")
        val envelopeCustom = if (wrapped != null) envelope?.get("customFields") else null
        require(custom == null || envelopeCustom == null || custom == envelopeCustom)
        val fields = when (val array = custom ?: envelopeCustom) {
            null, JsonNull -> emptyList()
            is JsonArray -> {
                require(array.size <= 1024)
                array.map { value ->
                    val field = value.jsonObject
                    val protected = field.alias("is_protected", "isProtected")
                    require(protected == null || protected == JsonPrimitive(true) || protected == JsonPrimitive(false))
                    val raw = field.getValue("value").stringOrEmpty()
                    CustomFieldDraft(title = field.getValue("title").stringOrEmpty(),
                        value = if (legacyAndroid && protected == JsonPrimitive(true)) decryptLegacy(raw) else raw,
                        isProtected = protected == JsonPrimitive(true))
                }
            }
            else -> error("Unsupported MDBX custom fields")
        }
        if (fields.any { it.title == ProjectCredentialGroup.FIELD }) {
            fields.filter { it.title == ProjectCredentialGroup.FIELD }.forEach {
                BitwardenWireJson.parseObject(it.value, MAX_PAYLOAD_BYTES)
            }
            require(ProjectCredentialGroup.read(fields) != null)
        }
        val ruleFields = fields.filter { it.title == PasswordUrlRules.FIELD }
        val rules = if (ruleFields.isEmpty()) null else ruleFields.singleOrNull()?.let {
            runCatching { BitwardenWireJson.parseObject(it.value, MAX_PAYLOAD_BYTES); PasswordUrlRules.decode(it.value) }.getOrNull()
        }.orEmpty()
        val entry = PasswordEntry(id = 0, title = title, website = source.string("website"),
            username = plain("username"), password = password, notes = plain("notes"),
            appPackageName = source.stringAlias("app_package_name", "appPackageName"),
            appName = source.stringAlias("app_name", "appName"),
            email = plain("email"), phone = plain("phone"), addressLine = plain("address_line", "addressLine"),
            city = plain("city"), state = plain("state"), zipCode = plain("zip_code", "zipCode"), country = plain("country"),
            creditCardNumber = source.stringAlias("credit_card_number_plain", "creditCardNumberPlain"),
            creditCardHolder = plain("credit_card_holder", "creditCardHolder"),
            creditCardExpiry = plain("credit_card_expiry", "creditCardExpiry"),
            creditCardCVV = source.stringAlias("credit_card_cvv_plain", "creditCardCvvPlain"),
            authenticatorKey = plain("authenticator_key", "authenticatorKey"),
            mdbxDatabaseId = databaseId, mdbxFolderId = folderId, replicaGroupId = objectId,
            createdAt = Date(0), updatedAt = Date(0))
        MdbxAutofillCredential(objectId, revision, entry, rules, fields,
            verifiedLogicalEntryId(root, vaultId, objectId), nativeVaultId = vaultId)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { null }

    private fun verifiedLogicalEntryId(payload: JsonObject, vaultId: String?, objectId: String): String? {
        if (vaultId == null) return null
        val logical = (payload["monica_entry_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() } ?: return null
        return logical.takeIf { mdbx2PhysicalEntryId(vaultId, it) == objectId }
    }

    private fun JsonObject.alias(key: String, camel: String): JsonElement? {
        require(key == camel || this[key] == null || this[camel] == null || this[key] == this[camel])
        return this[key] ?: this[camel]
    }
    private fun JsonObject.string(key: String): String = this[key].stringOrEmpty()
    private fun JsonObject.stringAlias(key: String, camel: String) = alias(key, camel).stringOrEmpty()
    private fun JsonElement?.stringOrEmpty(): String = when (this) {
        null, JsonNull -> ""
        is JsonPrimitive -> { require(isString); content }
        else -> error("Unsupported MDBX string field")
    }
}

/** Length-prefixed inputs prevent concatenation ambiguity; no path or credential is exposed. */
internal fun mdbxAutofillDigest(parts: List<String?>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part?.toByteArray(Charsets.UTF_8)
        digest.update((bytes?.size ?: -1).toString().toByteArray(Charsets.US_ASCII))
        digest.update(0.toByte())
        if (bytes != null) digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
