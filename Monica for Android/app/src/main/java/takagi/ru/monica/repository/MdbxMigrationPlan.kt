package takagi.ru.monica.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType

data class MdbxMigrationPlan(
    val sourceDatabaseId: Long,
    val sourceName: String,
    val suggestedTargetName: String,
    val folders: List<MdbxMigrationFolderPlan>,
    val entries: List<MdbxMigrationEntryPlan>,
    val attachments: List<MdbxMigrationAttachmentPlan>,
    val warnings: List<MdbxMigrationWarning>,
    val blockers: List<MdbxMigrationBlocker>
) {
    val isEligible: Boolean get() = blockers.isEmpty()
    val activeEntryCount: Int get() = entries.count { !it.entry.deleted }
    val deletedEntryCount: Int get() = entries.size - activeEntryCount
    val attachmentBytes: Long get() = attachments.sumOf { it.attachment.originalSize }
}

data class MdbxMigrationFolderPlan(
    val sourceFolderId: String,
    val sourceParentFolderId: String?,
    val targetDisplayName: String,
    val flattened: Boolean,
    val implicit: Boolean
)

data class MdbxMigrationEntryPlan(
    val entry: MdbxStoredVaultEntry,
    val sourceFolderId: String?
)

data class MdbxMigrationAttachmentPlan(
    val attachment: MdbxStoredAttachment,
    val parentEntryId: String
)

data class MdbxMigrationVerification(
    val folderCount: Int,
    val entryCount: Int,
    val attachmentCount: Int,
    val attachmentBytes: Long
)

data class MdbxMigrationPreview(
    val sourceDatabaseId: Long,
    val sourceName: String,
    val suggestedTargetName: String,
    val folderCount: Int,
    val activeEntryCount: Int,
    val deletedEntryCount: Int,
    val attachmentCount: Int,
    val attachmentBytes: Long,
    val warnings: List<MdbxMigrationWarning>,
    val blockers: List<MdbxMigrationBlocker>
) {
    val isEligible: Boolean get() = blockers.isEmpty()
}

fun MdbxMigrationPlan.toPreview(): MdbxMigrationPreview = MdbxMigrationPreview(
    sourceDatabaseId = sourceDatabaseId,
    sourceName = sourceName,
    suggestedTargetName = suggestedTargetName,
    folderCount = folders.size,
    activeEntryCount = activeEntryCount,
    deletedEntryCount = deletedEntryCount,
    attachmentCount = attachments.size,
    attachmentBytes = attachmentBytes,
    warnings = warnings,
    blockers = blockers
)

enum class MdbxMigrationWarningKind {
    NESTED_FOLDERS_FLATTENED,
    IMPLICIT_FOLDERS_CREATED,
    UNKNOWN_ENTRY_TYPES_COPIED,
    DELETED_ENTRIES_COPIED,
    DELETED_ATTACHMENTS_IGNORED,
    REMOTE_LOCAL_COPY_ONLY
}

data class MdbxMigrationWarning(
    val kind: MdbxMigrationWarningKind,
    val count: Int
)

enum class MdbxMigrationBlockerKind {
    SOURCE_ENGINE_UNSUPPORTED,
    SOURCE_LOCATION_UNSUPPORTED,
    DUPLICATE_FOLDER_ID,
    MISSING_FOLDER_PARENT,
    FOLDER_CYCLE,
    DUPLICATE_ENTRY_ID,
    INVALID_ENTRY_PAYLOAD,
    DUPLICATE_ATTACHMENT_ID,
    ATTACHMENT_TOO_LARGE,
    ATTACHMENT_KEY_MISSING,
    ATTACHMENT_PARENT_MISSING
}

data class MdbxMigrationBlocker(
    val kind: MdbxMigrationBlockerKind,
    val count: Int
)

object MdbxMigrationPlanner {
    private const val MAX_ATTACHMENT_BYTES = 64L * 1024L * 1024L
    private val supportedEntryTypes = setOf(
        "login",
        "note",
        "totp",
        "card",
        "document-ref",
        "billing-address",
        "payment-account",
        "passkey",
        "steam-mafile",
        "steam_mafile"
    )
    private val json = Json { ignoreUnknownKeys = true }

    fun build(
        source: LocalMdbxDatabase,
        folders: List<MdbxStoredFolderEntry>,
        entries: List<MdbxStoredVaultEntry>,
        attachments: List<MdbxStoredAttachment>
    ): MdbxMigrationPlan {
        val blockerCounts = linkedMapOf<MdbxMigrationBlockerKind, Int>()
        val warningCounts = linkedMapOf<MdbxMigrationWarningKind, Int>()

        fun block(kind: MdbxMigrationBlockerKind, count: Int = 1) {
            blockerCounts[kind] = (blockerCounts[kind] ?: 0) + count
        }

        fun warn(kind: MdbxMigrationWarningKind, count: Int = 1) {
            warningCounts[kind] = (warningCounts[kind] ?: 0) + count
        }

        if (source.engineTypeEnum != MdbxEngineType.KOTLIN_MDBX1) {
            block(MdbxMigrationBlockerKind.SOURCE_ENGINE_UNSUPPORTED)
        }
        if (source.sourceTypeEnum in setOf(MdbxSourceType.REMOTE_WEBDAV, MdbxSourceType.REMOTE_ONEDRIVE)) {
            warn(MdbxMigrationWarningKind.REMOTE_LOCAL_COPY_ONLY)
        }

        folders.duplicateCountBy(MdbxStoredFolderEntry::folderId)
            .takeIf { it > 0 }
            ?.let { block(MdbxMigrationBlockerKind.DUPLICATE_FOLDER_ID, it) }
        entries.duplicateCountBy(MdbxStoredVaultEntry::entryId)
            .takeIf { it > 0 }
            ?.let { block(MdbxMigrationBlockerKind.DUPLICATE_ENTRY_ID, it) }
        attachments.duplicateCountBy(MdbxStoredAttachment::attachmentId)
            .takeIf { it > 0 }
            ?.let { block(MdbxMigrationBlockerKind.DUPLICATE_ATTACHMENT_ID, it) }

        val payloadByEntryId = entries.associate { entry ->
            entry.entryId to parsePayload(entry.payloadJson)
        }
        val invalidPayloads = entries.count { entry -> payloadByEntryId[entry.entryId] == null }
        if (invalidPayloads > 0) {
            block(MdbxMigrationBlockerKind.INVALID_ENTRY_PAYLOAD, invalidPayloads)
        }

        val entryPlans = entries.map { entry ->
            MdbxMigrationEntryPlan(
                entry = entry,
                sourceFolderId = payloadByEntryId[entry.entryId]?.sourceFolderId()
            )
        }
        val explicitFolderIds = folders.mapTo(linkedSetOf(), MdbxStoredFolderEntry::folderId)
        val implicitFolderIds = entryPlans.mapNotNullTo(linkedSetOf()) { it.sourceFolderId }
            .filterNotTo(linkedSetOf()) { it in explicitFolderIds || it.equals("root", ignoreCase = true) }
        if (implicitFolderIds.isNotEmpty()) {
            warn(MdbxMigrationWarningKind.IMPLICIT_FOLDERS_CREATED, implicitFolderIds.size)
        }

        val folderById = folders.distinctBy { it.folderId }.associateBy { it.folderId }
        val plannedFolderIds = folderById.keys + implicitFolderIds
        val parentById = buildMap {
            folderById.forEach { (folderId, folder) ->
                put(folderId, folder.parentFolderId.normalizedMigrationParentId())
            }
            implicitFolderIds.forEach { put(it, null) }
        }
        val missingParentCount = parentById.values.count { parentId ->
            parentId != null && parentId !in plannedFolderIds
        }
        if (missingParentCount > 0) {
            block(MdbxMigrationBlockerKind.MISSING_FOLDER_PARENT, missingParentCount)
        }

        val cycleIds = linkedSetOf<String>()
        val visitState = mutableMapOf<String, Int>()
        fun visitFolder(folderId: String) {
            when (visitState[folderId]) {
                1 -> {
                    cycleIds += folderId
                    return
                }
                2 -> return
            }
            visitState[folderId] = 1
            parentById[folderId]
                ?.takeIf { it in plannedFolderIds }
                ?.let(::visitFolder)
            visitState[folderId] = 2
        }
        plannedFolderIds.forEach(::visitFolder)

        val folderPlans = buildList {
            folders.distinctBy { it.folderId }.forEach { folder ->
                add(
                    MdbxMigrationFolderPlan(
                        sourceFolderId = folder.folderId,
                        sourceParentFolderId = folder.parentFolderId,
                        targetDisplayName = folder.name.trim().ifBlank { folder.folderId },
                        flattened = false,
                        implicit = false
                    )
                )
            }
            implicitFolderIds.forEach { folderId ->
                add(
                    MdbxMigrationFolderPlan(
                        sourceFolderId = folderId,
                        sourceParentFolderId = null,
                        targetDisplayName = implicitFolderName(folderId),
                        flattened = false,
                        implicit = true
                    )
                )
            }
        }
        if (cycleIds.isNotEmpty()) {
            block(MdbxMigrationBlockerKind.FOLDER_CYCLE, cycleIds.size)
        }

        val unknownTypes = entries.count { it.entryType.lowercase() !in supportedEntryTypes }
        if (unknownTypes > 0) warn(MdbxMigrationWarningKind.UNKNOWN_ENTRY_TYPES_COPIED, unknownTypes)
        val deletedEntries = entries.count(MdbxStoredVaultEntry::deleted)
        if (deletedEntries > 0) warn(MdbxMigrationWarningKind.DELETED_ENTRIES_COPIED, deletedEntries)
        val deletedAttachments = attachments.count(MdbxStoredAttachment::deleted)
        if (deletedAttachments > 0) {
            warn(MdbxMigrationWarningKind.DELETED_ATTACHMENTS_IGNORED, deletedAttachments)
        }

        val entryIds = entries.mapTo(hashSetOf(), MdbxStoredVaultEntry::entryId)
        val attachmentPlans = attachments.filterNot(MdbxStoredAttachment::deleted).map { attachment ->
            val parentEntryId = attachment.entryId?.takeIf(String::isNotBlank)
                ?: attachment.projectId
            if (attachment.originalSize > MAX_ATTACHMENT_BYTES) {
                block(MdbxMigrationBlockerKind.ATTACHMENT_TOO_LARGE)
            }
            if (attachment.wrappedCek.isNullOrBlank()) {
                block(MdbxMigrationBlockerKind.ATTACHMENT_KEY_MISSING)
            }
            if (parentEntryId !in entryIds) {
                block(MdbxMigrationBlockerKind.ATTACHMENT_PARENT_MISSING)
            }
            MdbxMigrationAttachmentPlan(attachment, parentEntryId)
        }

        return MdbxMigrationPlan(
            sourceDatabaseId = source.id,
            sourceName = source.name,
            suggestedTargetName = "${source.name} (MDBX2)",
            folders = folderPlans,
            entries = entryPlans,
            attachments = attachmentPlans,
            warnings = warningCounts.map { (kind, count) -> MdbxMigrationWarning(kind, count) },
            blockers = blockerCounts.map { (kind, count) -> MdbxMigrationBlocker(kind, count) }
        )
    }

    private fun parsePayload(payloadJson: String): JsonObject? = runCatching {
        json.parseToJsonElement(payloadJson).jsonObject
    }.getOrNull()

    private fun JsonObject.sourceFolderId(): String? {
        val explicit = get("mdbx_folder_id")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("root", ignoreCase = true) }
        if (explicit != null) return explicit
        val categoryId = get("category_id")?.jsonPrimitive?.longOrNull ?: 0L
        return categoryId.takeIf { it > 0L }?.let { "category:$it" }
    }

    private fun implicitFolderName(folderId: String): String = when {
        folderId.startsWith("category:") -> "Category ${folderId.substringAfter(':')}"
        else -> folderId.substringAfterLast(':').ifBlank { folderId }
    }

    private fun String?.normalizedMigrationParentId(): String? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return value.takeUnless { it.equals("root", ignoreCase = true) }
    }

    private fun <T> List<T>.duplicateCountBy(selector: (T) -> String): Int =
        groupBy(selector).values.sumOf { values -> (values.size - 1).coerceAtLeast(0) }
}
