package takagi.ru.monica.keepass

import android.util.Base64
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.models.AutoTypeData
import app.keemobile.kotpass.models.AutoTypeItem
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.TimeData
import java.util.UUID
import okio.ByteString
import okio.ByteString.Companion.toByteString
import takagi.ru.monica.keepass.KeePassTreeNavigation.findGroupUuidByPath

/** Captures native trees for the existing change-set format, including history resource pools.
 * No snapshots are retained here. Missing referenced resources continue to fail the operation.
 */
internal object KeePassNativeSnapshotBuilder {
    fun buildCreateEntryChangeSet(
        database: KeePassDatabase,
        databaseId: Long,
        target: KeePassChangeTarget,
        entry: Entry,
        targetGroupPath: String?,
        targetGroupUuidOverride: UUID? = null,
    ): KeePassChangeSet? {
        val resolvedTargetPath = targetGroupPath?.takeIf { it.isNotBlank() }
        val targetGroupUuid = targetGroupUuidOverride ?: if (resolvedTargetPath == null) {
            database.content.group.uuid
        } else {
            findGroupUuidByPath(
                group = database.content.group,
                currentPathKey = null,
                targetPathKey = resolvedTargetPath
            )
        } ?: return null
        val referencedBinaryHashes = linkedSetOf<String>()
        val referencedCustomIconUuids = linkedSetOf<UUID>()
        val nativeEntry = entry.toEntryTreeSnapshot(
            referencedBinaryHashes = referencedBinaryHashes,
            referencedCustomIconUuids = referencedCustomIconUuids,
        )
        val binaryPool = referencedBinaryHashes.map { hashHex ->
            val hash = hashHex.toByteStringHash()
            val binary = database.binaries[hash]
                ?: throw IllegalStateException("KeePass binary pool is missing create-entry attachment: $hashHex")
            KeePassBinaryPoolItemPatch(
                hash = hashHex,
                protected = binary.memoryProtection,
                compressed = binary is BinaryData.Compressed,
                contentBase64 = Base64.encodeToString(
                    binary.rawContent,
                    Base64.NO_WRAP,
                ),
                rawStorageContent = true,
            )
        }
        val customIconPool = referencedCustomIconUuids.map { uuid ->
            val icon = database.content.meta.customIcons[uuid]
                ?: throw IllegalStateException("KeePass custom icon pool is missing create-entry icon: $uuid")
            KeePassCustomIconPoolItemPatch(
                uuid = uuid.toString(),
                dataBase64 = Base64.encodeToString(icon.data, Base64.NO_WRAP),
                name = icon.name,
                lastModifiedEpochMillis = icon.lastModified?.toEpochMilli(),
            )
        }
        return KeePassChangeSet(
            databaseId = databaseId,
            target = target,
            operation = KeePassChangeOperation.CREATE_ENTRY,
            entryUuid = entry.uuid.toString(),
            baseFingerprint = null,
            entryPatch = KeePassEntryCreatePatch(
                targetGroupPath = resolvedTargetPath,
                targetGroupUuid = targetGroupUuid.toString(),
                fields = entry.fields.map { (name, value) ->
                    takagi.ru.monica.keepass.KeePassFieldChange(
                        name = name,
                        value = runCatching { value.content }.getOrDefault(""),
                        protected = value is EntryValue.Encrypted
                    )
                },
                iconName = entry.icon?.name,
                nativeEntry = nativeEntry,
                binaryPool = binaryPool,
                customIconPool = customIconPool,
            ),
            databaseMetaPatch = database.content.meta.entryTemplatesGroup?.let { uuid ->
                KeePassDatabaseMetaPatch(
                    entryTemplatesGroupUuid = uuid.toString(),
                    entryTemplatesGroupChangedEpochMillis = database.content.meta.entryTemplatesGroupChanged?.toEpochMilli(),
                )
            },
        )
    }

    fun buildGroupTreeChangePatch(
        database: KeePassDatabase,
        root: Group,
        sourceRootGroupUuid: UUID?,
        targetParentGroupUuid: UUID?
    ): KeePassGroupTreeChangePatch {
        val referencedBinaryHashes = linkedSetOf<String>()
        val referencedCustomIconUuids = linkedSetOf<UUID>()
        val snapshot = root.toGroupTreeSnapshot(
            referencedBinaryHashes = referencedBinaryHashes,
            referencedCustomIconUuids = referencedCustomIconUuids
        )
        val binaryPool = referencedBinaryHashes.map { hashHex ->
            val hash = hashHex.toByteStringHash()
            val binaryData = database.binaries[hash]
                ?: throw IllegalStateException("KeePass binary pool is missing group tree attachment: $hashHex")
            KeePassBinaryPoolItemPatch(
                hash = hashHex,
                protected = binaryData.memoryProtection,
                compressed = binaryData is BinaryData.Compressed,
                contentBase64 = Base64.encodeToString(
                    binaryData.rawContent,
                    Base64.NO_WRAP
                ),
                rawStorageContent = true,
            )
        }
        val customIconPool = referencedCustomIconUuids.map { uuid ->
            val customIcon = database.content.meta.customIcons[uuid]
                ?: throw IllegalStateException("KeePass custom icon pool is missing group tree icon: $uuid")
            KeePassCustomIconPoolItemPatch(
                uuid = uuid.toString(),
                dataBase64 = Base64.encodeToString(customIcon.data, Base64.NO_WRAP),
                name = customIcon.name,
                lastModifiedEpochMillis = customIcon.lastModified?.toEpochMilli()
            )
        }
        return KeePassGroupTreeChangePatch(
            root = snapshot,
            binaryPool = binaryPool,
            customIconPool = customIconPool,
            sourceRootGroupUuid = sourceRootGroupUuid?.toString(),
            targetParentGroupUuid = targetParentGroupUuid?.toString()
        )
    }

    private fun Group.toGroupTreeSnapshot(
        referencedBinaryHashes: MutableSet<String>,
        referencedCustomIconUuids: MutableSet<UUID>
    ): KeePassGroupTreeSnapshot {
        customIconUuid?.let(referencedCustomIconUuids::add)
        return KeePassGroupTreeSnapshot(
            uuid = uuid.toString(),
            name = name,
            notes = notes,
            iconName = icon.name,
            customIconUuid = customIconUuid?.toString(),
            expanded = expanded,
            defaultAutoTypeSequence = defaultAutoTypeSequence.orEmpty(),
            enableAutoType = enableAutoType.name,
            enableSearching = enableSearching.name,
            lastTopVisibleEntryUuid = lastTopVisibleEntry?.toString(),
            previousParentGroupUuid = previousParentGroup?.toString(),
            tags = tags,
            times = times?.toKeePassTimesPatch(),
            customData = customData.toKeePassCustomDataPatchList(),
            entries = entries.map {
                it.toEntryTreeSnapshot(referencedBinaryHashes, referencedCustomIconUuids)
            },
            groups = groups.map {
                it.toGroupTreeSnapshot(referencedBinaryHashes, referencedCustomIconUuids)
            }
        )
    }

    private fun Entry.toEntryTreeSnapshot(
        referencedBinaryHashes: MutableSet<String>,
        referencedCustomIconUuids: MutableSet<UUID>
    ): KeePassEntryTreeSnapshot {
        customIconUuid?.let(referencedCustomIconUuids::add)
        return KeePassEntryTreeSnapshot(
            uuid = uuid.toString(),
            fields = fields.map { (name, value) ->
                KeePassFieldChange(
                    name = name,
                    value = value.content,
                    protected = value is EntryValue.Encrypted
                )
            },
            binaries = binaries.map { reference ->
                val hashHex = reference.hash.hex()
                referencedBinaryHashes += hashHex
                KeePassBinaryReferencePatch(
                    name = reference.name,
                    hash = hashHex
                )
            },
            history = history.map {
                it.toEntryTreeSnapshot(referencedBinaryHashes, referencedCustomIconUuids)
            },
            iconName = icon.name,
            customIconUuid = customIconUuid?.toString(),
            foregroundColor = foregroundColor,
            backgroundColor = backgroundColor,
            overrideUrl = overrideUrl,
            autoType = autoType?.toKeePassAutoTypePatch(),
            tags = tags,
            times = times?.toKeePassTimesPatch(),
            customData = customData.toKeePassCustomDataPatchList(),
            previousParentGroupUuid = previousParentGroup?.toString(),
            qualityCheck = qualityCheck
        )
    }

    private fun TimeData.toKeePassTimesPatch(): KeePassTimesPatch {
        return KeePassTimesPatch(
            creationTimeEpochMillis = creationTime?.toEpochMilli(),
            lastModificationTimeEpochMillis = lastModificationTime?.toEpochMilli(),
            lastAccessTimeEpochMillis = lastAccessTime?.toEpochMilli(),
            expiryTimeEpochMillis = expiryTime?.toEpochMilli(),
            expires = expires,
            usageCount = usageCount,
            locationChangedEpochMillis = locationChanged?.toEpochMilli()
        )
    }

    private fun Map<String, CustomDataValue>.toKeePassCustomDataPatchList(): List<KeePassCustomDataPatch> {
        return map { (key, value) ->
            KeePassCustomDataPatch(
                key = key,
                value = value.value,
                lastModifiedEpochMillis = value.lastModified?.toEpochMilli()
            )
        }
    }

    private fun AutoTypeData.toKeePassAutoTypePatch(): KeePassAutoTypePatch {
        return KeePassAutoTypePatch(
            enabled = enabled,
            obfuscation = obfuscation.name,
            defaultSequence = defaultSequence.orEmpty(),
            items = items.map { it.toKeePassAutoTypeItemPatch() }
        )
    }

    private fun AutoTypeItem.toKeePassAutoTypeItemPatch(): KeePassAutoTypeItemPatch {
        return KeePassAutoTypeItemPatch(
            window = window,
            keystrokeSequence = keystrokeSequence
        )
    }

    fun String.toByteStringHash(): ByteString {
        val normalized = trim()
        require(normalized.length % 2 == 0) { "Invalid hex length" }
        val bytes = ByteArray(normalized.length / 2) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return bytes.toByteString()
    }
}
