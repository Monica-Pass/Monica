package takagi.ru.monica.data.dedup

import java.util.UUID
import kotlinx.serialization.json.*
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.EntryContentFields
import takagi.ru.monica.data.model.PasswordContentBlocks
import takagi.ru.monica.data.model.ProjectCredentialGroup
import takagi.ru.monica.data.model.EmbeddedWalletContent

/** Structural equality is the proof; a digest is only an index. No text normalization. */
internal object DedupMergeContent {
    fun password(entry: PasswordEntry, fields: List<CustomField>, decrypt: (String) -> String): List<String> =
        listOf("password", entry.title, entry.website, entry.username, decrypt(entry.password), entry.notes,
            entry.isFavorite.toString(), entry.sortOrder.toString(), entry.isGroupCover.toString(),
            entry.appPackageName, entry.appName, entry.email, entry.phone, entry.addressLine, entry.city,
            entry.state, entry.zipCode, entry.country, decrypt(entry.creditCardNumber), entry.creditCardHolder,
            entry.creditCardExpiry, decrypt(entry.creditCardCVV), decrypt(entry.authenticatorKey),
            entry.passkeyBindings, entry.sshKeyData, entry.loginType, entry.ssoProvider, entry.wifiMetadata,
            entry.customIconType, entry.customIconValue ?: "", entry.isArchived.toString(),
            entry.boundNoteId?.toString() ?: "", entry.ssoRefEntryId?.toString() ?: "",
            if (entry.passwordGroupId == null) "" else "project", entry.bitwardenCipherType.toString()) + customParts(fields, decrypt)

    private fun customParts(fields: List<CustomField>, decrypt: (String) -> String): List<String> {
        val plain = drafts(fields, decrypt)
        val metadata = ProjectCredentialGroup.read(plain)
        val blocks = PasswordContentBlocks.read(plain).map { requireNotNull(it.block) }
        val blockNames = blocks.mapIndexed { index, block -> block.id to "block-$index" }.toMap()
        fun canonical(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(value.map(::canonical))
            else -> value
        }
        val ordinary = plain.filterNot { PasswordContentBlocks.owns(it.title) }
        return listOf("fields", ordinary.size.toString()) + ordinary.flatMap { field ->
            val value = when {
                field.title == ProjectCredentialGroup.FIELD -> canonical(JsonObject(requireNotNull(metadata).raw +
                    mapOf("groupId" to JsonPrimitive("credential"), "passwordId" to JsonPrimitive("password")) +
                    if (metadata.projectId != null) mapOf("projectId" to JsonPrimitive("project")) else emptyMap())).toString()
                field.title == EntryContentFields.ORDER -> field.value.split(',').joinToString(",") { token -> when {
                    token.startsWith("CREDENTIAL:") && token.removePrefix("CREDENTIAL:") == metadata?.groupId -> "CREDENTIAL:credential"
                    token.startsWith("BLOCK:") -> "BLOCK:" + blockNames.getValue(token.removePrefix("BLOCK:"))
                    else -> token
                } }
                EmbeddedWalletContent.isMetadata(field.title) -> canonical(JsonObject(Json.parseToJsonElement(field.value).jsonObject - "id")).toString()
                else -> field.value
            }
            listOf(field.title, value, field.isProtected.toString())
        } + listOf("blocks", blocks.size.toString()) + blocks.flatMap { block ->
            val prefix = PasswordContentBlocks.PREFIX + block.id
            listOf("block", canonical(JsonObject(block.raw - "id")).toString()) + plain.filter { it.title == prefix || it.title.startsWith("$prefix.") }
                .map { it.isProtected.toString() }
        }
    }

    fun secure(item: SecureItem, decrypt: (String) -> String, images: String): List<String> =
        listOf("secure", item.itemType.name, item.title, item.notes, item.isFavorite.toString(),
            item.sortOrder.toString(), item.isArchived.toString(), images,
            canonicalPayload(decrypt(item.itemData), decrypt))

    private fun canonicalPayload(value: String, decrypt: (String) -> String): String {
        fun canonical(element: JsonElement, depth: Int): JsonElement {
            require(depth < 64) { "Unsupported nested content" }
            return when (element) {
                is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonical(it.value, depth + 1) })
                is JsonArray -> JsonArray(element.map { canonical(it, depth + 1) })
                is JsonPrimitive -> if (element.isString) JsonPrimitive(decrypt(element.content)) else element
            }
        }
        // Parsing errors are not treated as absence. A non-JSON legacy payload stays exact.
        val parsed = try { Json.parseToJsonElement(value) } catch (_: kotlinx.serialization.SerializationException) { return value }
        return canonical(parsed, 0).toString()
    }

    fun drafts(fields: List<CustomField>, decrypt: (String) -> String) =
        fields.sortedWith(compareBy<CustomField> { it.sortOrder }.thenBy { it.id }).map {
            CustomFieldDraft(id = it.id, title = it.title, value = decrypt(it.value), isProtected = it.isProtected)
        }

    fun projectId(entry: PasswordEntry, fields: List<CustomFieldDraft>): String? {
        val metadata = ProjectCredentialGroup.read(fields)
        if (fields.any { it.title == ProjectCredentialGroup.FIELD }) requireNotNull(metadata)
        require(entry.passwordGroupId == null || metadata?.projectId == null || entry.passwordGroupId == metadata.projectId)
        return metadata?.projectId ?: entry.passwordGroupId
    }

    /** Rebuild one complete explicit project; never flatten credentials or content block fragments. */
    fun cloneProject(
        entries: List<PasswordEntry>, fields: Map<Long, List<CustomField>>, decrypt: (String) -> String,
        encrypt: (String) -> String, newId: () -> String = { UUID.randomUUID().toString() }
    ): Map<Long, Pair<String?, List<CustomField>>> {
        val plain = entries.associate { it.id to drafts(fields[it.id].orEmpty(), decrypt) }
        require(plain.values.flatten().none { it.title.startsWith("monica.") && it.title != ProjectCredentialGroup.FIELD &&
            it.title != EntryContentFields.ORDER && !PasswordContentBlocks.owns(it.title) && !EmbeddedWalletContent.isMetadata(it.title) && it.title !in SUPPLEMENTAL_FIELDS })
        val projectIds = entries.map { projectId(it, plain.getValue(it.id)) }.distinct()
        require(projectIds.size == 1)
        val projectId = projectIds.single()
        // Legacy multi-row groups without metadata cannot safely establish password ownership.
        if (entries.size > 1) require(plain.values.all { ProjectCredentialGroup.read(it) != null })
        ProjectCredentialGroup.restoreForEditing(entries.map {
            it.copy(password = decrypt(it.password), authenticatorKey = decrypt(it.authenticatorKey))
        }, plain)
        val metadata = plain.mapValues { ProjectCredentialGroup.read(it.value) }
        require(metadata.values.filterNotNull().all { it.raw.keys.all(CREDENTIAL_KEYS::contains) })
        val groups = metadata.values.filterNotNull().map { it.groupId }.distinct().associateWith { newId() }
        val passwords = metadata.values.filterNotNull().map { it.passwordId }.distinct().associateWith { newId() }
        val blocks = plain.mapValues { (_, row) -> PasswordContentBlocks.read(row).map { requireNotNull(it.block) } }
        // A block ID may be repeated across sibling rows only with identical full content.
        val blockVersions = blocks.values.flatten().groupBy { it.id }
        require(blockVersions.values.all { versions -> versions.map { it.raw }.distinct().size == 1 })
        require(blockVersions.values.flatten().all { block ->
            block.raw.keys.all { it in setOf("version", "id", "kind", "title", "data") } &&
                block.data.keys.all { it in PasswordContentBlocks.editableKeys(block.kind) + listOf("mode", "template", "templateVersion") }
        })
        val blockIds = blockVersions.keys.associateWith { newId() }
        val replacementProject = if (projectId != null) newId() else null
        return entries.associate { entry ->
            val source = plain.getValue(entry.id)
            require(source.filter { it.title == EntryContentFields.ORDER }.map { it.value }.distinct().size <= 1)
            var replaced = source.filterNot { PasswordContentBlocks.owns(it.title) }.map { field ->
                when (field.title) {
                    ProjectCredentialGroup.FIELD -> {
                        val old = requireNotNull(metadata[entry.id])
                        val raw = old.raw.toMutableMap().apply {
                            put("groupId", JsonPrimitive(groups.getValue(old.groupId)))
                            put("passwordId", JsonPrimitive(passwords.getValue(old.passwordId)))
                            replacementProject?.let { put("projectId", JsonPrimitive(it)) }
                        }
                        field.copy(value = JsonObject(raw).toString())
                    }
                    EntryContentFields.ORDER -> field.copy(value = field.value.split(',').joinToString(",") { token ->
                        when {
                            token.startsWith("CREDENTIAL:") -> "CREDENTIAL:" + groups.getValue(token.removePrefix("CREDENTIAL:"))
                            token.startsWith("BLOCK:") -> "BLOCK:" + blockIds.getValue(token.removePrefix("BLOCK:"))
                            else -> token
                        }
                    })
                    else -> if (EmbeddedWalletContent.isMetadata(field.title)) {
                        require(source.count { it.title == field.title } == 1)
                        val wallet = requireNotNull((EmbeddedWalletContent.read(field.value) as? EmbeddedWalletContent.ReadResult.Available)?.snapshot)
                        require(wallet.raw.keys.all { it in setOf("version", "id", "kind", "title", "notes", "favorite", "data", "assets") })
                        require(wallet.assets.isEmpty() && SingleVaultDedupLocalContent.knownSchema(wallet.kind.itemType, wallet.itemData) &&
                            SingleVaultDedupLocalContent.referencedPasswordIds(wallet.itemData).isEmpty() &&
                            !SingleVaultDedupLocalContent.externalAssets(wallet.itemData, emptySet()))
                        field.copy(value = JsonObject(wallet.raw + ("id" to JsonPrimitive(newId()))).toString())
                    } else field
                }
            }
            for (block in blocks.getValue(entry.id)) {
                val id = blockIds.getValue(block.id)
                // Keep unknown header properties only when none exist; unknown reference semantics fail closed.
                val header = source.single { it.title == PasswordContentBlocks.PREFIX + block.id }
                require(Json.parseToJsonElement(header.value).jsonObject.keys.all { it in setOf("version", "encoding", "parts", "sha256") })
                replaced = PasswordContentBlocks.put(replaced, PasswordContentBlocks.Block(id,
                    JsonObject(block.raw + ("id" to JsonPrimitive(id)))))
            }
            val rewritten = replaced.mapIndexed { index, field ->
                val original = source.firstOrNull { it.id == field.id && it.title == field.title }
                val stored = fields[entry.id].orEmpty().firstOrNull { it.id == field.id && it.title == field.title }
                CustomField(id = 0, entryId = 0, title = field.title,
                    value = if (original?.value == field.value && stored != null) stored.value else encrypt(field.value),
                    isProtected = field.isProtected, sortOrder = index)
            }
            entry.id to (replacementProject to rewritten)
        }
    }

    private val CREDENTIAL_KEYS = setOf("version", "projectId", "groupId", "passwordId", "label", "primary", "groupOrder", "passwordOrder", "note")
    private val SUPPLEMENTAL_FIELDS = mapOf(
        "payment" to "bankName bankBranch cardType brand nickname validFromMonth validFromYear pin iban swiftBic routingNumber accountNumber branchCode currency customerServicePhone billingAddress",
        "contact" to "fullName title firstName middleName lastName company documentType documentNumber issuedDate expiryDate issuedBy nationality ssn passportNumber licenseNumber additionalInfo",
        "address" to "fullName company apartment address3 phone email"
    ).flatMap { (section, names) -> names.split(' ').map { "monica.content.$section.$it" } }.toSet()
}
