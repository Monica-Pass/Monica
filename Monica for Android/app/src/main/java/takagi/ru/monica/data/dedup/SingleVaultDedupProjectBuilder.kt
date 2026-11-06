package takagi.ru.monica.data.dedup

import java.util.Collections
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.*
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.*

/** Builds ephemeral semantic projects, never a write plan, from a reader-attested snapshot. */
object SingleVaultDedupProjectBuilder {
    private val credentialKeys = setOf("version", "projectId", "groupId", "passwordId", "label", "primary", "groupOrder", "passwordOrder", "note")
    private val sections = setOf("NOTES", "AUTHENTICATOR", "DOCUMENT", "PAYMENT", "CONTACT", "ADDRESS", "CUSTOM_FIELDS", "ATTACHMENTS")
    private val supplementalKeys = mapOf(
        "payment" to "bankName bankBranch cardType brand nickname validFromMonth validFromYear pin iban swiftBic routingNumber accountNumber branchCode currency customerServicePhone billingAddress",
        "contact" to "fullName title firstName middleName lastName company documentType documentNumber issuedDate expiryDate issuedBy nationality ssn passportNumber licenseNumber additionalInfo",
        "address" to "fullName company apartment address3 phone email"
    ).flatMap { (section, keys) -> keys.split(' ').map { "monica.content.$section.$it" } }.toSet()

    /** Explicit classification guard: a newly added entity property must be reviewed. */
    val classifiedPasswordProperties: Set<String> = setOf(
        "id", "title", "website", "username", "password", "notes", "createdAt", "updatedAt", "isFavorite", "sortOrder", "isGroupCover",
        "appPackageName", "appName", "email", "phone", "addressLine", "city", "state", "zipCode", "country", "creditCardNumber", "creditCardHolder",
        "creditCardExpiry", "creditCardCVV", "categoryId", "boundNoteId", "keepassDatabaseId", "keepassGroupPath", "keepassEntryUuid", "keepassGroupUuid",
        "mdbxDatabaseId", "mdbxFolderId", "authenticatorKey", "passkeyBindings", "sshKeyData", "loginType", "ssoProvider", "ssoRefEntryId", "wifiMetadata",
        "customIconType", "customIconValue", "customIconUpdatedAt", "isDeleted", "deletedAt", "isArchived", "archivedAt", "replicaGroupId", "passwordGroupId",
        "bitwardenVaultId", "bitwardenCipherId", "bitwardenFolderId", "bitwardenRevisionDate", "bitwardenCipherType", "bitwardenLocalModified"
    )

    private class Invalid(val reason: SingleVaultDedupBlockReason) : RuntimeException()
    private fun reject(reason: SingleVaultDedupBlockReason): Nothing = throw Invalid(reason)
    private fun insist(value: Boolean, reason: SingleVaultDedupBlockReason = SingleVaultDedupBlockReason.INCONSISTENT_PROJECT) {
        if (!value) reject(reason)
    }

    private data class Row(
        val entry: PasswordEntry,
        val fields: List<CustomFieldDraft>,
        val metadata: ProjectCredentialGroup.Metadata?,
        val reasons: Set<SingleVaultDedupBlockReason>
    )

    fun build(
        sourceKey: String,
        entries: List<PasswordEntry>,
        fieldsByEntryId: Map<Long, List<CustomField>>,
        decrypt: (String) -> String,
        evidence: SingleVaultDedupEvidence,
        includeArchived: Boolean = false,
        checkActive: () -> Unit = {}
    ): List<SingleVaultDedupProject> {
        require(sourceKey.isNotEmpty())
        val duplicateIds = entries.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        val rows = entries.map { entry ->
            checkActive()
            val reasons = evidence.blockedReasonsByEntry[entry.id].orEmpty().toMutableSet()
            if (entry.passkeyBindings.isNotEmpty()) reasons += setOf(SingleVaultDedupBlockReason.PASSKEY_REFERENCE,
                SingleVaultDedupBlockReason.EXTERNAL_REFERENCE)
            if (entry.loginType.equals("PASSKEY", true) || fieldsByEntryId[entry.id].orEmpty().any {
                    it.title.contains("passkey", true) || it.title.contains("fido", true)
                }) reasons += SingleVaultDedupBlockReason.PASSKEY_PRESERVED
            if (!evidence.complete || entry.id !in evidence.completeMemberIds) reasons += SingleVaultDedupBlockReason.INCOMPLETE_SNAPSHOT
            if (entry.id in duplicateIds) reasons += SingleVaultDedupBlockReason.INCONSISTENT_PROJECT
            var fields = emptyList<CustomFieldDraft>()
            var metadata: ProjectCredentialGroup.Metadata? = null
            try {
                fields = fieldsByEntryId[entry.id].orEmpty().sortedWith(compareBy<CustomField> { it.sortOrder }.thenBy { it.id }).map {
                    checkActive()
                    insist(it.entryId == entry.id)
                    CustomFieldDraft(id = it.id, title = it.title, value = decode(it.value, decrypt), isProtected = it.isProtected)
                }
                if (fields.any { it.title == ProjectCredentialGroup.FIELD }) {
                    fields.filter { it.title == ProjectCredentialGroup.FIELD }.forEach { strictJson(it.value) }
                    metadata = ProjectCredentialGroup.read(fields)
                    insist(metadata != null)
                    insist(metadata!!.raw.keys.all { it in credentialKeys }, SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT)
                    insist(listOf("version", "primary", "groupOrder", "passwordOrder").all { metadata!!.raw.getValue(it).jsonPrimitive.isString.not() })
                }
                entry.passwordGroupId?.let { insist(UUID.fromString(it).toString() == it) }
                if (entry.passwordGroupId != null && metadata?.projectId != null) insist(entry.passwordGroupId == metadata!!.projectId)
            } catch (cancel: CancellationException) { throw cancel
            } catch (invalid: Invalid) { reasons += invalid.reason
            } catch (_: Exception) { reasons += SingleVaultDedupBlockReason.INCONSISTENT_PROJECT }
            Row(entry, fields, metadata, reasons)
        }
        return rows.groupBy { row ->
            row.entry.passwordGroupId?.let { "project:$it" }
                ?: row.metadata?.projectId?.let { "project:$it" }
                ?: "password:${row.entry.id}"
        }.mapNotNull { (key, members) ->
            checkActive()
            if (members.all { it.entry.isDeleted || (!includeArchived && it.entry.isArchived) }) return@mapNotNull null
            val reasons = members.flatMap { it.reasons }.toMutableSet()
            if (members.any { it.entry.isDeleted || (!includeArchived && it.entry.isArchived) }) reasons += SingleVaultDedupBlockReason.INCOMPLETE_SNAPSHOT
            var node: SingleVaultDedupNode? = null
            var count = 0
            try {
                if (reasons.isEmpty()) {
                    val built = projectNode(members, decrypt, evidence, checkActive)
                    node = built.first
                    count = built.second
                    if (count == 0) reasons += SingleVaultDedupBlockReason.EMPTY_CONTENT
                }
            } catch (cancel: CancellationException) { throw cancel
            } catch (invalid: Invalid) { reasons += invalid.reason
            } catch (_: Exception) { reasons += SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT }
            SingleVaultDedupProject(
                SingleVaultDedupProjectSummary(key, sourceKey, members.first().entry.title,
                    Collections.unmodifiableList(members.map { it.entry.id }.sorted()), count), node, reasons)
        }.sortedBy { it.summary.key }
    }

    private fun decode(value: String, decrypt: (String) -> String): String = try {
        if (value.isEmpty()) value else decrypt(value)
    } catch (cancel: CancellationException) { throw cancel
    } catch (_: Exception) { reject(SingleVaultDedupBlockReason.UNREADABLE_CONTENT) }

    private fun projectNode(
        members: List<Row>, decrypt: (String) -> String, evidence: SingleVaultDedupEvidence, checkActive: () -> Unit
    ): Pair<SingleVaultDedupNode, Int> {
        val baselineContext = context(members.first().entry)
        insist(members.all { context(it.entry) == baselineContext })
        val hasMetadata = members.any { it.metadata != null }
        insist(!hasMetadata || members.all { it.metadata != null })
        if (hasMetadata) {
            insist(members.map { it.metadata!!.passwordId }.distinct().size == members.size)
            insist(members.mapNotNull { it.metadata!!.projectId }.distinct().size <= 1)
        }
        val groups = members.groupBy { it.metadata?.groupId ?: "legacy" }.values.sortedWith(
            compareBy<List<Row>> { it.first().metadata?.groupOrder ?: 0 }.thenBy { it.minOf { row -> row.entry.id } })
        if (hasMetadata) {
            insist(groups.map { it.first().metadata!!.groupOrder }.distinct().size == groups.size)
            insist(groups.count { it.first().metadata!!.primary } == 1)
        }
        // Credential reference nodes deliberately contain credentials only, without
        // their own order nodes, so order edges cannot create a recursive graph.
        val references = groups.associate { group ->
            val first = group.first()
            val meta = first.metadata
            val ordered = group.sortedWith(compareBy<Row> { it.metadata?.passwordOrder ?: it.entry.sortOrder }.thenBy { it.entry.id })
            insist(group.all { it.entry.username == first.entry.username && it.entry.loginType == first.entry.loginType &&
                it.metadata?.label == meta?.label && it.metadata?.primary == meta?.primary &&
                it.metadata?.groupOrder == meta?.groupOrder && it.metadata?.note == meta?.note })
            if (hasMetadata) insist(group.map { it.metadata!!.passwordOrder }.distinct().size == group.size)
            (meta?.groupId ?: "legacy") to SingleVaultDedupNode("credential-reference", groupAttributes(first, decrypt), ordered.map {
                SingleVaultDedupNode("credential-password", mapOf("value" to decode(it.entry.password, decrypt),
                    "otp" to decode(it.entry.authenticatorKey, decrypt)))
            })
        }
        var contentCount = 0
        val groupNodes = groups.map { group ->
            checkActive()
            val rows = group.sortedWith(compareBy<Row> { it.metadata?.passwordOrder ?: it.entry.sortOrder }.thenBy { it.entry.id }).map { row ->
                val built = rowNode(row, decrypt, references, evidence, checkActive)
                contentCount += built.second
                built.first
            }
            SingleVaultDedupNode("credential-group", groupAttributes(group.first(), decrypt), rows)
        }
        return SingleVaultDedupNode("password-project", baselineContext, groupNodes) to contentCount
    }

    private fun groupAttributes(row: Row, decrypt: (String) -> String): Map<String, String> = mapOf(
        "username" to decode(row.entry.username, decrypt), "loginType" to row.entry.loginType,
        "label" to row.metadata?.label.orEmpty(), "primary" to (row.metadata?.primary ?: true).toString(),
        "note" to row.metadata?.note.orEmpty(),
        "metadataProtection" to row.fields.filter { it.title == ProjectCredentialGroup.FIELD }.map { it.isProtected }.distinct().sorted().joinToString(",")
    )

    private fun context(e: PasswordEntry): Map<String, String> = buildMap {
        put("title", e.title); put("favorite", e.isFavorite.toString()); put("archived", e.isArchived.toString())
        put("category", e.categoryId?.toString() ?: "null")
        put("keepassDatabase", e.keepassDatabaseId?.toString() ?: "null")
        put("keepassPath", nullableText(e.keepassGroupPath)); put("keepassGroup", nullableText(e.keepassGroupUuid))
        put("mdbxDatabase", e.mdbxDatabaseId?.toString() ?: "null"); put("mdbxFolder", nullableText(e.mdbxFolderId))
        put("bitwardenVault", e.bitwardenVaultId?.toString() ?: "null"); put("bitwardenFolder", nullableText(e.bitwardenFolderId))
        put("cipherType", e.bitwardenCipherType.toString())
        insist(listOf(e.keepassDatabaseId, e.mdbxDatabaseId, e.bitwardenVaultId).count { it != null } <= 1)
    }

    private fun nullableText(value: String?): String = if (value == null) "null" else "text:$value"

    private fun rowNode(row: Row, decrypt: (String) -> String, references: Map<String, SingleVaultDedupNode>,
        evidence: SingleVaultDedupEvidence, checkActive: () -> Unit): Pair<SingleVaultDedupNode, Int> {
        val e = row.entry
        if (e.passkeyBindings.isNotEmpty()) reject(SingleVaultDedupBlockReason.PASSKEY_REFERENCE)
        if (e.boundNoteId != null || e.ssoRefEntryId != null || e.replicaGroupId != null)
            reject(SingleVaultDedupBlockReason.EXTERNAL_REFERENCE)
        if (e.customIconType == "UPLOADED") reject(SingleVaultDedupBlockReason.EXTERNAL_REFERENCE)
        insist(e.customIconType in setOf("NONE", "SIMPLE_ICON"), SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT)
        val attributes = linkedMapOf("password" to decode(e.password, decrypt), "groupCover" to e.isGroupCover.toString(),
            "loginType" to e.loginType, "ssoProvider" to e.ssoProvider, "iconType" to e.customIconType)
        e.customIconValue?.let { attributes["iconValue"] = it }
        val text = linkedMapOf("website" to e.website, "notes" to e.notes, "appPackageName" to e.appPackageName, "appName" to e.appName,
            "email" to e.email, "phone" to e.phone, "addressLine" to e.addressLine, "city" to e.city, "state" to e.state,
            "zipCode" to e.zipCode, "country" to e.country, "creditCardNumber" to e.creditCardNumber, "creditCardHolder" to e.creditCardHolder,
            "creditCardExpiry" to e.creditCardExpiry, "creditCardCVV" to e.creditCardCVV, "authenticatorKey" to e.authenticatorKey)
        var count = if (attributes.getValue("password").isNotEmpty() || e.username.isNotEmpty()) 1 else 0
        text.forEach { (name, raw) -> checkActive(); if (raw.isNotEmpty()) { attributes[name] = decode(raw, decrypt); count++ } }
        val extras = mutableListOf<SingleVaultDedupNode>()
        if (e.sshKeyData.isNotEmpty()) {
            val raw = strictJson(decode(e.sshKeyData, decrypt))
            try { Json.decodeFromJsonElement(SshKeyData.serializer(), raw) } catch (_: Exception) { reject(SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT) }
            extras += exactJsonNode("ssh-key", decodeJson(raw, decrypt)); count++
        }
        if (e.wifiMetadata.isNotEmpty()) {
            val raw = strictJson(decode(e.wifiMetadata, decrypt))
            try { Json.decodeFromJsonElement(WifiData.serializer(), raw) } catch (_: Exception) { reject(SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT) }
            extras += exactJsonNode("wifi", decodeJson(raw, decrypt)); count++
        }
        val fields = row.fields
        val blocks = PasswordContentBlocks.read(fields)
        val blockNodes = blocks.associate { stored ->
            checkActive()
            val block = stored.block ?: reject(SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT)
            val headerField = fields.single { it.title == PasswordContentBlocks.PREFIX + block.id }
            val header = strictJson(headerField.value).jsonObject
            val payload = fields.filter { it.title.startsWith(headerField.title + ".") }.sortedBy { it.title }.joinToString("") { it.value }
            strictJson(String(java.util.Base64.getDecoder().decode(payload), Charsets.UTF_8))
            insist(header.keys == setOf("version", "encoding", "parts", "sha256"), SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT)
            insist(block.raw.keys == setOf("version", "id", "kind", "title", "data"), SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT)
            val allowed = PasswordContentBlocks.editableKeys(block.kind).toSet() + if (block.kind == PasswordContentBlocks.Kind.QR_CODE) setOf("mode", "templateVersion") else emptySet()
            insist(block.data.keys.all { it in allowed }, SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT)
            val protection = fields.filter { it.title == headerField.title || it.title.startsWith(headerField.title + ".") }.map { it.isProtected }.distinct()
            insist(protection.size == 1, SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT)
            val blockAttributes = mutableMapOf("protected" to protection.single().toString())
            if (block.kind == PasswordContentBlocks.Kind.QR_CODE && PasswordQrTemplate.isTemplate(block)) {
                val values = PasswordQrTemplate.Values(mapOf("ACCOUNT" to decode(e.username, decrypt), "PASSWORD" to decode(e.password, decrypt),
                    "TITLE" to e.title, "URL" to decode(e.website, decrypt), "EMAIL" to decode(e.email, decrypt), "PHONE" to decode(e.phone, decrypt), "NOTES" to decode(e.notes, decrypt)),
                    fields.filterNot { it.title.startsWith("monica.") }.map { it.title to it.value })
                blockAttributes["resolvedTemplate"] = try { PasswordQrTemplate.resolve(block, values) }
                    catch (_: Exception) { reject(SingleVaultDedupBlockReason.EXTERNAL_REFERENCE) }
            }
            stored.token to SingleVaultDedupNode("content-block", blockAttributes,
                listOf(exactJsonNode("payload", decodeJson(JsonObject(block.raw - "id"), decrypt))))
        }
        extras += SingleVaultDedupNode("blocks", children = blockNodes.values.toList())
        count += blockNodes.size
        val ordinary = mutableListOf<SingleVaultDedupNode>()
        fields.forEach { field ->
            checkActive()
            when {
                field.title == ProjectCredentialGroup.FIELD || field.title == EntryContentFields.ORDER || PasswordContentBlocks.owns(field.title) -> Unit
                EmbeddedWalletContent.isMetadata(field.title) -> {
                    insist(fields.count { it.title == field.title } == 1)
                    val raw = strictJson(field.value).jsonObject
                    val wallet = (EmbeddedWalletContent.read(field.value) as? EmbeddedWalletContent.ReadResult.Available)?.snapshot
                        ?: reject(SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT)
                    insist(field.title == EmbeddedWalletContent.fieldName(wallet.kind))
                    insist(raw.keys.all { it in setOf("version", "id", "kind", "title", "notes", "favorite", "data", "assets") }, SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT)
                    insist(raw["favorite"] == null || raw["favorite"] is JsonPrimitive && raw["favorite"]!!.jsonPrimitive.booleanOrNull != null && !raw["favorite"]!!.jsonPrimitive.isString)
                    insist(UUID.fromString(wallet.id).toString() == wallet.id)
                    if (wallet.assets.isNotEmpty()) reject(SingleVaultDedupBlockReason.EXTERNAL_REFERENCE)
                    validateWallet(wallet)
                    val decodedWallet = decodeJson(JsonObject(raw - "id"), decrypt)
                    if (DedupStructuredPasskeyPolicy.containsPasskeyContent(decodedWallet))
                        reject(SingleVaultDedupBlockReason.PASSKEY_PRESERVED)
                    ordinary += SingleVaultDedupNode("wallet", mapOf("protected" to field.isProtected.toString()),
                        listOf(exactJsonNode("payload", decodedWallet)))
                    count++
                }
                field.title.startsWith("monica.") && field.title !in supplementalKeys -> reject(SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT)
                else -> {
                    ordinary += SingleVaultDedupNode("custom-field", mapOf("name" to field.title, "value" to field.value, "protected" to field.isProtected.toString()))
                    count++
                }
            }
        }
        extras += SingleVaultDedupNode("fields", children = ordinary)
        val orders = fields.filter { it.title == EntryContentFields.ORDER }
        if (orders.isNotEmpty()) {
            insist(orders.all { it.value == orders.first().value && it.isProtected == orders.first().isProtected })
            val tokens = orders.first().value.takeIf { it.isNotEmpty() }?.split(',').orEmpty()
            insist(tokens.none { it.isEmpty() } && tokens.distinct().size == tokens.size)
            val nodes = tokens.map { token ->
                when {
                    token in sections -> SingleVaultDedupNode("section", mapOf("name" to token))
                    token.startsWith("CREDENTIAL:") -> references[token.removePrefix("CREDENTIAL:")] ?: reject(SingleVaultDedupBlockReason.INCONSISTENT_PROJECT)
                    token.startsWith("BLOCK:") -> blockNodes[token] ?: reject(SingleVaultDedupBlockReason.INCONSISTENT_PROJECT)
                    else -> reject(SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT)
                }
            }
            extras += SingleVaultDedupNode("content-order", mapOf("protected" to orders.first().isProtected.toString()), nodes)
        }
        extras += SingleVaultDedupNode("verified-dependencies", children = evidence.supplementalNodesByEntry[e.id].orEmpty())
        count += evidence.supplementalNodesByEntry[e.id].orEmpty().sumOf { it.contentCount() }
        return SingleVaultDedupNode("password-row", attributes, extras) to count
    }

    private fun validateWallet(wallet: EmbeddedWalletContent.Snapshot) {
        try {
            when (wallet.kind) {
                EmbeddedWalletContent.Kind.BANK_CARD -> Json.decodeFromJsonElement(BankCardData.serializer(), wallet.itemData).also {
                    if (it.cardFace != null) reject(SingleVaultDedupBlockReason.EXTERNAL_REFERENCE)
                }
                EmbeddedWalletContent.Kind.DOCUMENT -> Json.decodeFromJsonElement(DocumentData.serializer(), wallet.itemData).also {
                    if (it.cardFace != null) reject(SingleVaultDedupBlockReason.EXTERNAL_REFERENCE)
                }
                EmbeddedWalletContent.Kind.ADDRESS -> Json.decodeFromJsonElement(BillingAddressData.serializer(), wallet.itemData).also {
                    if (it.cardFace != null) reject(SingleVaultDedupBlockReason.EXTERNAL_REFERENCE)
                }
                EmbeddedWalletContent.Kind.NOTE -> Json.decodeFromJsonElement(NoteData.serializer(), wallet.itemData)
            }
        } catch (invalid: Invalid) { throw invalid
        } catch (_: Exception) { reject(SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT) }
    }

    /** Strict equality for complete JSON payloads; unknown members, null, types and array multiplicity remain significant. */
    fun exactJsonNode(kind: String, value: JsonElement): SingleVaultDedupNode =
        SingleVaultDedupNode(kind, mapOf("json" to canonicalJson(value).toString()))

    private fun decodeJson(value: JsonElement, decrypt: (String) -> String, depth: Int = 0): JsonElement {
        insist(depth <= 64, SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT)
        return when (value) {
            is JsonObject -> JsonObject(value.mapValues { decodeJson(it.value, decrypt, depth + 1) })
            is JsonArray -> JsonArray(value.map { decodeJson(it, decrypt, depth + 1) })
            is JsonPrimitive -> if (value.isString) JsonPrimitive(decode(value.content, decrypt)) else value
        }
    }

    private fun canonicalJson(value: JsonElement, depth: Int = 0): JsonElement {
        require(depth <= 64)
        return when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonicalJson(it.value, depth + 1) })
            is JsonArray -> JsonArray(value.map { canonicalJson(it, depth + 1) })
            else -> value
        }
    }

    /** Reject duplicate object keys before a JSON parser can silently choose a winner. */
    fun strictJson(raw: String): JsonElement {
        require(raw.length <= 1024 * 1024)
        var offset = 0
        fun peek(): Char { require(offset < raw.length); return raw[offset] }
        fun whitespace() { while (offset < raw.length && raw[offset].isWhitespace()) offset++ }
        fun string(): String {
            require(peek() == '"')
            val start = offset++
            while (offset < raw.length) {
                when (raw[offset++]) {
                    '\\' -> offset++
                    '"' -> return Json.parseToJsonElement(raw.substring(start, offset)).jsonPrimitive.content
                }
            }
            error("Invalid JSON string")
        }
        fun scan(depth: Int) {
            require(depth <= 64)
            whitespace()
            when (peek()) {
                '{' -> {
                    offset++; whitespace()
                    val keys = hashSetOf<String>()
                    if (peek() == '}') { offset++; return }
                    while (true) {
                        whitespace(); require(keys.add(string())) { "Duplicate JSON member" }
                        whitespace(); require(peek() == ':'); offset++; scan(depth + 1); whitespace()
                        val separator = peek(); offset++
                        if (separator == '}') break
                        require(separator == ',')
                    }
                }
                '[' -> {
                    offset++; whitespace()
                    if (peek() == ']') { offset++; return }
                    while (true) {
                        scan(depth + 1); whitespace(); val separator = peek(); offset++
                        if (separator == ']') break
                        require(separator == ',')
                    }
                }
                '"' -> string()
                else -> while (offset < raw.length && raw[offset] !in ",]}" && !raw[offset].isWhitespace()) offset++
            }
        }
        scan(0); whitespace(); require(offset == raw.length)
        return Json.parseToJsonElement(raw)
    }
}
