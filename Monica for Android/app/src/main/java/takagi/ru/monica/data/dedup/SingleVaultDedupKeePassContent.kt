package takagi.ru.monica.data.dedup

import app.keemobile.kotpass.constants.AutoTypeObfuscation
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.models.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.*
import okio.ByteString.Companion.decodeHex
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.keepass.*

internal class SingleVaultDedupKeePassRead(val session: KeePassNativeSession, val xmlComplete: Boolean, val canClean: Boolean, val lockVersion: Long) {
    override fun toString() = "SingleVaultDedupKeePassRead(<redacted>)"
}

/** Complete native entry payloads; these JSON objects must only enter the encrypted recovery column. */
internal object SingleVaultDedupKeePassContent {
    fun entry(e: Entry, depth: Int = 0): JsonObject {
        require(depth <= 32)
        return buildJsonObject {
            put("uuid", e.uuid.toString()); put("icon", e.icon.name); put("customIconUuid", nullable(e.customIconUuid?.toString()))
            put("foregroundColor", nullable(e.foregroundColor)); put("backgroundColor", nullable(e.backgroundColor)); put("overrideUrl", e.overrideUrl)
            put("times", e.times?.let(::times) ?: JsonNull)
            put("autoType", e.autoType?.let { auto -> buildJsonObject {
                put("enabled", auto.enabled); put("obfuscation", auto.obfuscation.name); put("defaultSequence", nullable(auto.defaultSequence))
                put("items", JsonArray(auto.items.map { buildJsonObject { put("window", it.window); put("sequence", it.keystrokeSequence) } }))
            } } ?: JsonNull)
            put("fields", JsonArray(e.fields.map { (name, value) -> buildJsonObject {
                put("name", name); put("protected", value is EntryValue.Encrypted); put("value", value.content)
            } }))
            put("tags", JsonArray(e.tags.map(::JsonPrimitive)))
            put("binaries", JsonArray(e.binaries.map { buildJsonObject { put("hash", it.hash.hex()); put("name", it.name) } }))
            put("history", JsonArray(e.history.map { entry(it, depth + 1) }))
            put("customData", JsonObject(e.customData.mapValues { (_, value) -> buildJsonObject {
                put("value", value.value); put("lastModified", nullable(value.lastModified?.toString()))
            } }))
            put("previousParentGroup", nullable(e.previousParentGroup?.toString())); put("qualityCheck", e.qualityCheck)
        }
    }

    fun decodeEntry(raw: JsonObject, depth: Int = 0): Entry {
        require(depth <= 32)
        val fields = raw.getValue("fields").jsonArray.map { value -> value.jsonObject.let {
            val text = it.getValue("value").jsonPrimitive.content
            it.getValue("name").jsonPrimitive.content to if (it.getValue("protected").jsonPrimitive.boolean)
                EntryValue.Encrypted(EncryptedValue.fromString(text)) else EntryValue.Plain(text)
        } }
        require(fields.map { it.first }.distinct().size == fields.size)
        return Entry(uuid = UUID.fromString(raw.text("uuid")), icon = PredefinedIcon.valueOf(raw.text("icon")),
            customIconUuid = raw.optional("customIconUuid")?.let(UUID::fromString), foregroundColor = raw.optional("foregroundColor"),
            backgroundColor = raw.optional("backgroundColor"), overrideUrl = raw.text("overrideUrl"),
            times = raw["times"].takeUnless { it == JsonNull }?.jsonObject?.let(::decodeTimes),
            autoType = raw["autoType"].takeUnless { it == JsonNull }?.jsonObject?.let { auto -> AutoTypeData(
                auto.getValue("enabled").jsonPrimitive.boolean, AutoTypeObfuscation.valueOf(auto.text("obfuscation")), auto.optional("defaultSequence"),
                auto.getValue("items").jsonArray.map { it.jsonObject.let { item -> AutoTypeItem(item.text("window"), item.text("sequence")) } }) },
            fields = EntryFields(fields.toMap(LinkedHashMap())), tags = raw.getValue("tags").jsonArray.map { it.jsonPrimitive.content },
            binaries = raw.getValue("binaries").jsonArray.map { it.jsonObject.let { ref -> BinaryReference(ref.text("hash").decodeHex(), ref.text("name")) } },
            history = raw.getValue("history").jsonArray.map { decodeEntry(it.jsonObject, depth + 1) },
            customData = raw.getValue("customData").jsonObject.mapValues { (_, value) -> value.jsonObject.let {
                CustomDataValue(it.text("value"), it.optional("lastModified")?.let(Instant::parse))
            } }, previousParentGroup = raw.optional("previousParentGroup")?.let(UUID::fromString), qualityCheck = raw.getValue("qualityCheck").jsonPrimitive.boolean)
    }

    fun payload(database: KeePassDatabase, uuid: UUID): JsonObject {
        val payload = KeePassLosslessTransfer.captureEntry(database, uuid)
        require(payload.binaryPool.values.sumOf { it.rawContent.size.toLong() } + payload.customIcons.values.sumOf { it.data.size.toLong() } <= 16 * 1024 * 1024)
        return buildJsonObject {
            put("entry", entry(payload.entry)); put("parent", payload.sourceParentGroupUuid.toString())
            put("binaries", JsonObject(payload.binaryPool.mapKeys { it.key.hex() }.mapValues { (_, binary) -> buildJsonObject {
                put("compressed", binary is BinaryData.Compressed); put("protected", binary.memoryProtection)
                put("bytes", Base64.getEncoder().encodeToString(binary.rawContent))
            } }))
            put("icons", JsonObject(payload.customIcons.mapKeys { it.key.toString() }.mapValues { (_, icon) -> buildJsonObject {
                put("bytes", Base64.getEncoder().encodeToString(icon.data)); put("name", nullable(icon.name)); put("lastModified", nullable(icon.lastModified?.toString()))
            } }))
        }
    }

    fun decodePayload(raw: JsonObject): KeePassNativeEntryTransferPayload {
        val binaries = raw.getValue("binaries").jsonObject.map { (hash, value) ->
            val data = value.jsonObject; val bytes = Base64.getDecoder().decode(data.text("bytes"))
            val binary = if (data.getValue("compressed").jsonPrimitive.boolean) BinaryData.Compressed(data.getValue("protected").jsonPrimitive.boolean, bytes)
                else BinaryData.Uncompressed(data.getValue("protected").jsonPrimitive.boolean, bytes)
            require(binary.hash.hex() == hash)
            hash.decodeHex() to binary
        }.toMap()
        val icons = raw.getValue("icons").jsonObject.map { (uuid, value) -> value.jsonObject.let {
            UUID.fromString(uuid) to CustomIcon(Base64.getDecoder().decode(it.text("bytes")), it.optional("name"), it.optional("lastModified")?.let(Instant::parse))
        } }.toMap()
        return KeePassNativeEntryTransferPayload(decodeEntry(raw.getValue("entry").jsonObject), UUID.fromString(raw.text("parent")), binaries, icons)
    }

    fun equivalentPayload(a: KeePassNativeEntryTransferPayload, b: KeePassNativeEntryTransferPayload): Boolean =
        a.sourceParentGroupUuid == b.sourceParentGroupUuid && entry(a.entry) == entry(b.entry) &&
            a.binaryPool.keys == b.binaryPool.keys && a.binaryPool.all { (hash, value) -> b.binaryPool[hash]?.let {
                value::class == it::class && value.memoryProtection == it.memoryProtection && value.rawContent.contentEquals(it.rawContent)
            } == true } && a.customIcons == b.customIcons

    /** Check every persisted object, including unselected groups and resources. Source audit rejects subsecond timestamps. */
    fun roundTripEquivalent(expected: KeePassDatabase, actual: KeePassDatabase): Boolean {
        if (expected.header.version != actual.header.version || expected.header.cipherId != actual.header.cipherId || expected.header.compression != actual.header.compression) return false
        fun time(value: TimeData?) = value?.copy(creationTime = value.creationTime?.truncatedTo(ChronoUnit.SECONDS),
            lastAccessTime = value.lastAccessTime?.truncatedTo(ChronoUnit.SECONDS), lastModificationTime = value.lastModificationTime?.truncatedTo(ChronoUnit.SECONDS),
            locationChanged = value.locationChanged?.truncatedTo(ChronoUnit.SECONDS), expiryTime = value.expiryTime?.truncatedTo(ChronoUnit.SECONDS))
        fun normalize(e: Entry): Entry = e.copy(times = time(e.times), history = e.history.map(::normalize))
        fun group(a: Group, b: Group): Boolean = a.copy(times = time(a.times), groups = emptyList(), entries = emptyList()) ==
            b.copy(times = time(b.times), groups = emptyList(), entries = emptyList()) && a.groups.size == b.groups.size &&
            a.groups.zip(b.groups).all { group(it.first, it.second) } && a.entries.size == b.entries.size &&
            a.entries.zip(b.entries).all { entry(normalize(it.first)) == entry(normalize(it.second)) }
        val expectedMeta = expected.content.meta.copy(recycleBinChanged = expected.content.meta.recycleBinChanged?.truncatedTo(ChronoUnit.SECONDS))
        val actualMeta = actual.content.meta.copy(recycleBinChanged = actual.content.meta.recycleBinChanged?.truncatedTo(ChronoUnit.SECONDS))
        return expectedMeta == actualMeta && expected.content.deletedObjects == actual.content.deletedObjects &&
            group(expected.content.group, actual.content.group) && expected.binaries.keys == actual.binaries.keys &&
            expected.binaries.all { (hash, binary) -> actual.binaries[hash]?.let {
                it.memoryProtection == binary.memoryProtection && it.getContent().contentEquals(binary.getContent())
            } == true }
    }

    fun projects(session: KeePassNativeSession, includeArchived: Boolean, xmlComplete: Boolean, checkActive: () -> Unit): List<SingleVaultDedupProject> {
        val rows = mutableListOf<PasswordEntry>()
        val fields = mutableMapOf<Long, List<CustomField>>()
        val reasons = mutableMapOf<Long, Set<SingleVaultDedupBlockReason>>()
        val nodes = mutableMapOf<Long, List<SingleVaultDedupNode>>()
        val sourceKey = "keepass:${session.databaseId}"
        // Any unresolved native REF placeholder could point at an otherwise unrelated
        // entry (including title/username selectors), so conservatively keep all.
        val referencePresent = session.entryNodes.any { node -> hasReference(node.entry) } ||
            session.groupNodes.any { it.group.defaultAutoTypeSequence.orEmpty().contains("{REF:", true) }
        val inheritedBehavior = session.groupNodes.any { !it.group.defaultAutoTypeSequence.isNullOrEmpty() }
        val inheritedIdentity = session.groupNodes.any { it.group.defaultAutoTypeSequence.orEmpty().contains("{UUID}", true) }
        session.entryNodes.forEachIndexed { index, record ->
            checkActive()
            val id = index + 1L
            val e = record.entry
            fun value(name: String) = e.fields[name]?.content.orEmpty()
            val blocked = linkedSetOf<SingleVaultDedupBlockReason>()
            if (hasPasskey(e)) blocked += SingleVaultDedupBlockReason.PASSKEY_PRESERVED
            if (hasPasskeyBinding(e)) blocked += SingleVaultDedupBlockReason.PASSKEY_REFERENCE
            if (!xmlComplete) blocked += SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT
            if (session.duplicateEntryIdentities.isNotEmpty() || session.duplicateGroupIdentities.isNotEmpty()) blocked += SingleVaultDedupBlockReason.INCONSISTENT_PROJECT
            if (referencePresent || e.fields.keys.any { name -> listOf("passkey", "ssoref", "boundnote", "boundpassword", "replicagroup").any { name.contains(it, true) } })
                blocked += SingleVaultDedupBlockReason.EXTERNAL_REFERENCE
            if (inheritedIdentity || hasIdentityPlaceholder(e)) blocked += SingleVaultDedupBlockReason.EXTERNAL_REFERENCE
            if (hasPluginData(e) || session.database.content.meta.customData.isNotEmpty()) blocked += SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT
            if (listOf("MonicaIsFavorite", "MonicaIsArchived").any { value(it).lowercase() !in setOf("", "true", "false") })
                blocked += SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT
            if (session.groupNodes.any { it.group.customData.isNotEmpty() }) blocked += SingleVaultDedupBlockReason.UNSUPPORTED_CONTENT
            if (e.fields.filterKeys { it !in setOf("Title", "MonicaPasswordGroupId") }.values.none { it.content.isNotEmpty() } && e.binaries.isEmpty() && e.history.isEmpty())
                blocked += SingleVaultDedupBlockReason.EMPTY_CONTENT
            rows += PasswordEntry(id, value("Title"), value("URL"), value("UserName"), value("Password"), notes = value("Notes"),
                keepassDatabaseId = session.databaseId, keepassGroupUuid = record.parentGroup.groupUuid.toString(), keepassGroupPath = record.legacyGroupPath,
                keepassEntryUuid = e.uuid.toString(), passwordGroupId = value("MonicaPasswordGroupId").takeIf { it.isNotEmpty() },
                isFavorite = value("MonicaIsFavorite").equals("true", true), isArchived = value("MonicaIsArchived").equals("true", true), isDeleted = record.isInRecycleBin)
            fields[id] = e.fields.filterKeys { it !in setOf("Title", "URL", "UserName", "Password", "Notes", "MonicaPasswordGroupId") }.map { (name, value) ->
                CustomField(entryId = id, title = name, value = value.content, isProtected = value is EntryValue.Encrypted)
            }
            try {
                val encoded = payload(session.database, e.uuid)
                val native = entry(e)
                val properties = JsonObject(native - setOf("uuid", "fields", "times", "previousParentGroup", "history", "binaries", "customIconUuid"))
                val props = mutableListOf(SingleVaultDedupProjectBuilder.exactJsonNode("native-properties", properties))
                props += SingleVaultDedupProjectBuilder.exactJsonNode("native-expiry", buildJsonObject {
                    put("present", e.times != null); put("expires", e.times?.expires ?: false); put("expiry", nullable(e.times?.expiryTime?.toString()))
                })
                props += SingleVaultDedupNode("native-field-order", children = e.fields.filterKeys { it != "MonicaPasswordGroupId" }.map { (name, value) ->
                    SingleVaultDedupNode("native-field", mapOf("name" to name, "protected" to (value is EntryValue.Encrypted).toString()))
                })
                props += SingleVaultDedupProjectBuilder.exactJsonNode("native-group-metadata-protection", buildJsonObject {
                    put("present", "MonicaPasswordGroupId" in e.fields); put("protected", e.fields["MonicaPasswordGroupId"] is EntryValue.Encrypted)
                })
                props += SingleVaultDedupNode("native-history", children = native.getValue("history").jsonArray.map {
                    SingleVaultDedupProjectBuilder.exactJsonNode("historic-entry", it)
                })
                props += SingleVaultDedupNode("native-attachments", children = e.binaries.map { reference ->
                    SingleVaultDedupNode("native-attachment", mapOf("name" to reference.name), listOf(
                        SingleVaultDedupProjectBuilder.exactJsonNode("binary", encoded.getValue("binaries").jsonObject.getValue(reference.hash.hex()))))
                })
                props += SingleVaultDedupProjectBuilder.exactJsonNode("native-custom-icons", encoded.getValue("icons"))
                // Native placeholder behavior may change when extra fields appear.
                if (e.autoType != null || e.overrideUrl.isNotEmpty() || inheritedBehavior ||
                    e.fields.any { (name, value) -> ('{' in value.content && !name.startsWith("monica.content.")) || name.contains("otp", true) })
                    props += SingleVaultDedupProjectBuilder.exactJsonNode("native-behavior-fields", native.getValue("fields"))
                nodes[id] = props
            } catch (cancel: java.util.concurrent.CancellationException) { throw cancel
            } catch (_: Exception) { blocked += SingleVaultDedupBlockReason.UNREADABLE_CONTENT }
            reasons[id] = blocked
        }
        return SingleVaultDedupProjectBuilder.build(sourceKey, rows, fields, { it },
            SingleVaultDedupEvidence(true, rows.map { it.id }.toSet(), reasons, nodes), includeArchived, checkActive)
    }

    private fun hasReference(e: Entry): Boolean = e.fields.values.any { it.content.contains("{REF:", true) } ||
        e.autoType?.let { auto -> auto.defaultSequence.orEmpty().contains("{REF:", true) || auto.items.any { it.keystrokeSequence.contains("{REF:", true) } } == true ||
        e.overrideUrl.contains("{REF:", true) || e.history.any(::hasReference)

    // A historic credential is still part of the complete native project. Keep it even
    // when the current entry looks like a plain login, or an unfamiliar FIDO plugin wrote it.
    private fun hasPasskey(e: Entry): Boolean = e.fields.any { (name, value) ->
        name.contains("passkey", true) || name.contains("fido", true) ||
            name.equals("MonicaItemType", true) && value.content.trim().equals("PASSKEY", true)
    } || e.history.any(::hasPasskey)

    private fun hasPasskeyBinding(e: Entry): Boolean = e.fields.keys.any {
        it.contains("passkeybindings", true) || it.contains("boundpassword", true)
    } || e.history.any(::hasPasskeyBinding)

    private fun hasPluginData(e: Entry): Boolean = e.customData.isNotEmpty() || e.history.any(::hasPluginData)

    private fun hasIdentityPlaceholder(e: Entry): Boolean = e.fields.values.any { it.content.contains("{UUID}", true) } ||
        e.autoType?.let { auto -> auto.defaultSequence.orEmpty().contains("{UUID}", true) || auto.items.any { it.keystrokeSequence.contains("{UUID}", true) } } == true ||
        e.overrideUrl.contains("{UUID}", true) || e.history.any(::hasIdentityPlaceholder)

    private fun times(t: TimeData) = buildJsonObject {
        put("creation", nullable(t.creationTime?.toString())); put("access", nullable(t.lastAccessTime?.toString())); put("modified", nullable(t.lastModificationTime?.toString()))
        put("location", nullable(t.locationChanged?.toString())); put("expiry", nullable(t.expiryTime?.toString())); put("expires", t.expires); put("usage", t.usageCount)
    }
    private fun decodeTimes(o: JsonObject) = TimeData(o.optional("creation")?.let(Instant::parse), o.optional("access")?.let(Instant::parse),
        o.optional("modified")?.let(Instant::parse), o.optional("location")?.let(Instant::parse), o.optional("expiry")?.let(Instant::parse),
        o.getValue("expires").jsonPrimitive.boolean, o.getValue("usage").jsonPrimitive.int)
    private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.optional(key: String) = getValue(key).jsonPrimitive.contentOrNull
}
