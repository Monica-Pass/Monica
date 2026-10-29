package takagi.ru.monica.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Date
import takagi.ru.monica.R
import takagi.ru.monica.attachments.ui.attachmentErrorMessage
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.OperationLogItemType
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.TIMELINE_FIELD_BATCH_COPY_PAYLOAD
import takagi.ru.monica.data.model.TIMELINE_FIELD_BATCH_MOVE_PAYLOAD
import takagi.ru.monica.data.model.TimelineBatchCopyPayload
import takagi.ru.monica.data.model.TimelineBatchMovePayload
import takagi.ru.monica.data.model.TimelinePasswordLocationState
import takagi.ru.monica.data.model.TimelinePasswordRecreatedEntry
import takagi.ru.monica.ui.password.PasswordAggregateListItemUi
import takagi.ru.monica.ui.password.PasswordBatchTransferGlobalProgressState
import takagi.ru.monica.ui.password.PasswordBatchTransferProgressTracker
import takagi.ru.monica.ui.components.UNIFIED_MOVE_ARCHIVE_SENTINEL_CATEGORY_ID
import takagi.ru.monica.ui.components.UnifiedMoveToCategoryBottomSheet
import takagi.ru.monica.ui.components.UnifiedMoveAction
import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget
import takagi.ru.monica.ui.components.UnifiedMoveInitialSource
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.decodeKeePassPathForDisplay
import takagi.ru.monica.utils.FieldChange
import takagi.ru.monica.utils.OperationLogger
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.sync.SyncErrorKind
import takagi.ru.monica.sync.classifySyncFailure

internal fun CategoryFilter.toUnifiedMoveInitialSource(): UnifiedMoveInitialSource {
    return when (this) {
        is CategoryFilter.KeePassDatabase -> UnifiedMoveInitialSource.KeePassDatabase(databaseId)
        is CategoryFilter.KeePassGroupFilter -> UnifiedMoveInitialSource.KeePassDatabase(databaseId)
        is CategoryFilter.KeePassDatabaseStarred -> UnifiedMoveInitialSource.KeePassDatabase(databaseId)
        is CategoryFilter.KeePassDatabaseUncategorized -> UnifiedMoveInitialSource.KeePassDatabase(databaseId)
        is CategoryFilter.MdbxDatabase -> UnifiedMoveInitialSource.MdbxDatabase(databaseId)
        is CategoryFilter.MdbxFolderFilter -> UnifiedMoveInitialSource.MdbxDatabase(databaseId)
        is CategoryFilter.BitwardenVault -> UnifiedMoveInitialSource.BitwardenVault(vaultId)
        is CategoryFilter.BitwardenFolderFilter -> UnifiedMoveInitialSource.BitwardenVault(vaultId)
        is CategoryFilter.BitwardenVaultStarred -> UnifiedMoveInitialSource.BitwardenVault(vaultId)
        is CategoryFilter.BitwardenVaultUncategorized -> UnifiedMoveInitialSource.BitwardenVault(vaultId)
        is CategoryFilter.All,
        is CategoryFilter.Archived,
        is CategoryFilter.Local,
        is CategoryFilter.LocalOnly,
        is CategoryFilter.Starred,
        is CategoryFilter.Uncategorized,
        is CategoryFilter.LocalStarred,
        is CategoryFilter.LocalUncategorized,
        is CategoryFilter.Custom -> UnifiedMoveInitialSource.MonicaLocal
    }
}

internal data class PasswordBatchMoveActionResolution(
    val effectiveAction: UnifiedMoveAction,
    val showKeepassCopyOnlyHint: Boolean
)

internal data class PasswordBatchMoveTargetRouting(
    val isArchiveTarget: Boolean,
    val monicaCategoryId: Long?,
    val isMonicaCopyTarget: Boolean
)

internal data class PasswordBatchTransferProgressUiState(
    val action: UnifiedMoveAction,
    val targetLabel: String,
    val processed: Int,
    val total: Int
) {
    val progressFraction: Float
        get() = if (total <= 0) 0f else processed.toFloat() / total.toFloat()

    val progressText: String
        get() = "$processed / $total"
}

internal fun PasswordBatchTransferGlobalProgressState.toDialogUiState(): PasswordBatchTransferProgressUiState =
    PasswordBatchTransferProgressUiState(
        action = action,
        targetLabel = targetLabel,
        processed = processed,
        total = total
    )

internal data class KeePassBatchTransferFailure(
    val kind: SyncErrorKind,
    val failedCount: Int,
    val reasons: List<String>,
    val detail: String,
)

private data class KeePassBatchTransferFailurePrompt(
    val targetLabel: String,
    val failure: KeePassBatchTransferFailure,
)

private class KeePassBatchTransferException(
    val failure: KeePassBatchTransferFailure,
) : Exception(failure.detail)

internal fun resolveKeePassBatchTransferFailure(
    failures: Map<Long, String>,
): KeePassBatchTransferFailure {
    return resolveKeePassBatchTransferFailure(
        failureCount = failures.size,
        failureMessages = failures.values,
    )
}

internal fun resolveKeePassBatchTransferFailure(
    failureCount: Int,
    failureMessages: Collection<String>,
): KeePassBatchTransferFailure {
    val reasons = failureMessages
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    val detail = reasons.firstOrNull() ?: "KeePass operation failed"
    val classified = classifySyncFailure(IllegalStateException(reasons.joinToString(" | ").ifBlank { detail }))
    return KeePassBatchTransferFailure(
        kind = classified.kind,
        failedCount = failureCount.coerceAtLeast(1),
        reasons = reasons,
        detail = detail,
    )
}

private fun formatBatchResultToast(
    context: Context,
    successCount: Int,
    failedCount: Int
): String {
    return if (failedCount > 0) {
        context.getString(
            R.string.password_batch_transfer_partial_result,
            successCount,
            failedCount
        )
    } else {
        context.getString(R.string.selected_items, successCount)
    }
}

internal fun resolvePasswordBatchMoveAction(
    requestedAction: UnifiedMoveAction,
    selectedEntries: List<PasswordEntry>,
    target: UnifiedMoveCategoryTarget
): PasswordBatchMoveActionResolution {
    val hasKeePassEntries = selectedEntries.any { it.isKeePassEntry() }
    val forceCopy = requestedAction == UnifiedMoveAction.MOVE &&
        hasKeePassEntries &&
        isKeePassMoveCopyOnlyTarget(target)
    return PasswordBatchMoveActionResolution(
        effectiveAction = if (forceCopy) UnifiedMoveAction.COPY else requestedAction,
        showKeepassCopyOnlyHint = forceCopy
    )
}

internal fun isKeePassMoveCopyOnlyTarget(target: UnifiedMoveCategoryTarget): Boolean {
    return when (target) {
        UnifiedMoveCategoryTarget.Uncategorized -> false
        is UnifiedMoveCategoryTarget.MonicaCategory ->
            target.categoryId == UNIFIED_MOVE_ARCHIVE_SENTINEL_CATEGORY_ID
        is UnifiedMoveCategoryTarget.KeePassDatabaseTarget,
        is UnifiedMoveCategoryTarget.KeePassGroupTarget,
        is UnifiedMoveCategoryTarget.MdbxDatabaseTarget,
        is UnifiedMoveCategoryTarget.MdbxFolderTarget -> false
        else -> true
    }
}

internal fun shouldForceSupplementaryKeePassCopy(
    requestedAction: UnifiedMoveAction,
    hasKeePassOwnedItems: Boolean,
    target: UnifiedMoveCategoryTarget
): Boolean {
    return requestedAction == UnifiedMoveAction.MOVE &&
        hasKeePassOwnedItems &&
        isKeePassMoveCopyOnlyTarget(target)
}

internal fun resolvePasswordBatchMoveTargetRouting(
    target: UnifiedMoveCategoryTarget
): PasswordBatchMoveTargetRouting {
    val isArchiveTarget = target is UnifiedMoveCategoryTarget.MonicaCategory &&
        target.categoryId == UNIFIED_MOVE_ARCHIVE_SENTINEL_CATEGORY_ID
    val monicaCategoryId = when (target) {
        UnifiedMoveCategoryTarget.Uncategorized -> null
        is UnifiedMoveCategoryTarget.MonicaCategory ->
            target.categoryId.takeUnless { it == UNIFIED_MOVE_ARCHIVE_SENTINEL_CATEGORY_ID }
        else -> null
    }
    val isMonicaCopyTarget = target == UnifiedMoveCategoryTarget.Uncategorized ||
        (target is UnifiedMoveCategoryTarget.MonicaCategory && !isArchiveTarget)
    return PasswordBatchMoveTargetRouting(
        isArchiveTarget = isArchiveTarget,
        monicaCategoryId = monicaCategoryId,
        isMonicaCopyTarget = isMonicaCopyTarget
    )
}

internal fun toLocationState(entry: PasswordEntry): TimelinePasswordLocationState {
    return TimelinePasswordLocationState(
        id = entry.id,
        categoryId = entry.categoryId,
        keepassDatabaseId = entry.keepassDatabaseId,
        keepassGroupPath = entry.keepassGroupPath,
        mdbxDatabaseId = entry.mdbxDatabaseId,
        mdbxFolderId = entry.mdbxFolderId,
        bitwardenVaultId = entry.bitwardenVaultId,
        bitwardenCipherId = entry.bitwardenCipherId,
        bitwardenFolderId = entry.bitwardenFolderId,
        bitwardenRevisionDate = entry.bitwardenRevisionDate,
        bitwardenLocalModified = entry.bitwardenLocalModified,
        isArchived = entry.isArchived,
        archivedAtMillis = entry.archivedAt?.time
    )
}

internal fun toMovedLocationState(
    entry: PasswordEntry,
    target: UnifiedMoveCategoryTarget
): TimelinePasswordLocationState {
    val archivedAt = if (target is UnifiedMoveCategoryTarget.MonicaCategory &&
        target.categoryId == UNIFIED_MOVE_ARCHIVE_SENTINEL_CATEGORY_ID
    ) {
        entry.archivedAt?.time ?: System.currentTimeMillis()
    } else {
        null
    }

    return when (target) {
        UnifiedMoveCategoryTarget.Uncategorized -> TimelinePasswordLocationState(
            id = entry.id,
            categoryId = null,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            isArchived = false,
            archivedAtMillis = null
        )

        is UnifiedMoveCategoryTarget.MonicaCategory -> {
            if (target.categoryId == UNIFIED_MOVE_ARCHIVE_SENTINEL_CATEGORY_ID) {
                TimelinePasswordLocationState(
                    id = entry.id,
                    categoryId = null,
                    keepassDatabaseId = null,
                    keepassGroupPath = null,
                    bitwardenVaultId = null,
                    bitwardenCipherId = null,
                    bitwardenFolderId = null,
                    bitwardenRevisionDate = null,
                    bitwardenLocalModified = false,
                    isArchived = true,
                    archivedAtMillis = archivedAt
                )
            } else {
                TimelinePasswordLocationState(
                    id = entry.id,
                    categoryId = target.categoryId,
                    keepassDatabaseId = null,
                    keepassGroupPath = null,
                    bitwardenVaultId = null,
                    bitwardenCipherId = null,
                    bitwardenFolderId = null,
                    bitwardenRevisionDate = null,
                    bitwardenLocalModified = false,
                    isArchived = false,
                    archivedAtMillis = null
                )
            }
        }

        is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> TimelinePasswordLocationState(
            id = entry.id,
            categoryId = null,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            bitwardenVaultId = target.vaultId,
            bitwardenCipherId = null,
            bitwardenFolderId = "",
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            isArchived = false,
            archivedAtMillis = null
        )

        is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> TimelinePasswordLocationState(
            id = entry.id,
            categoryId = null,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            bitwardenVaultId = target.vaultId,
            bitwardenCipherId = null,
            bitwardenFolderId = target.folderId,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            isArchived = false,
            archivedAtMillis = null
        )

        is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> TimelinePasswordLocationState(
            id = entry.id,
            categoryId = null,
            keepassDatabaseId = target.databaseId,
            keepassGroupPath = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            isArchived = false,
            archivedAtMillis = null
        )

        is UnifiedMoveCategoryTarget.KeePassGroupTarget -> TimelinePasswordLocationState(
            id = entry.id,
            categoryId = null,
            keepassDatabaseId = target.databaseId,
            keepassGroupPath = target.groupPath,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            isArchived = false,
            archivedAtMillis = null
        )

        is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> TimelinePasswordLocationState(
            id = entry.id,
            categoryId = null,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            mdbxDatabaseId = target.databaseId,
            mdbxFolderId = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            isArchived = false,
            archivedAtMillis = null
        )

        is UnifiedMoveCategoryTarget.MdbxFolderTarget -> TimelinePasswordLocationState(
            id = entry.id,
            categoryId = null,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            mdbxDatabaseId = target.databaseId,
            mdbxFolderId = target.folderId,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            isArchived = false,
            archivedAtMillis = null
        )
    }
}

internal fun buildCopiedEntryForTarget(
    entry: PasswordEntry,
    target: UnifiedMoveCategoryTarget
): PasswordEntry {
    val now = Date()
    return when (target) {
        UnifiedMoveCategoryTarget.Uncategorized -> entry.copy(
            id = 0,
            createdAt = now,
            updatedAt = now,
            categoryId = null,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            mdbxDatabaseId = null,
            mdbxFolderId = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            replicaGroupId = null,
            isArchived = false,
            archivedAt = null,
            isDeleted = false,
            deletedAt = null
        )

        is UnifiedMoveCategoryTarget.MonicaCategory -> {
            if (target.categoryId == UNIFIED_MOVE_ARCHIVE_SENTINEL_CATEGORY_ID) {
                entry.copy(
                    id = 0,
                    createdAt = now,
                    updatedAt = now,
                    categoryId = null,
                    keepassDatabaseId = null,
                    keepassGroupPath = null,
                    keepassEntryUuid = null,
                    keepassGroupUuid = null,
                    mdbxDatabaseId = null,
                    mdbxFolderId = null,
                    bitwardenVaultId = null,
                    bitwardenCipherId = null,
                    bitwardenFolderId = null,
                    bitwardenRevisionDate = null,
                    bitwardenLocalModified = false,
                    replicaGroupId = null,
                    isArchived = true,
                    archivedAt = now,
                    isDeleted = false,
                    deletedAt = null
                )
            } else {
                entry.copy(
                    id = 0,
                    createdAt = now,
                    updatedAt = now,
                    categoryId = target.categoryId,
                    keepassDatabaseId = null,
                    keepassGroupPath = null,
                    keepassEntryUuid = null,
                    keepassGroupUuid = null,
                    mdbxDatabaseId = null,
                    mdbxFolderId = null,
                    bitwardenVaultId = null,
                    bitwardenCipherId = null,
                    bitwardenFolderId = null,
                    bitwardenRevisionDate = null,
                    bitwardenLocalModified = false,
                    replicaGroupId = null,
                    isArchived = false,
                    archivedAt = null,
                    isDeleted = false,
                    deletedAt = null
                )
            }
        }

        is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> entry.copy(
            id = 0,
            createdAt = now,
            updatedAt = now,
            categoryId = null,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            mdbxDatabaseId = null,
            mdbxFolderId = null,
            bitwardenVaultId = target.vaultId,
            bitwardenCipherId = null,
            bitwardenFolderId = "",
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            replicaGroupId = null,
            isArchived = false,
            archivedAt = null,
            isDeleted = false,
            deletedAt = null
        )

        is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> entry.copy(
            id = 0,
            createdAt = now,
            updatedAt = now,
            categoryId = null,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            mdbxDatabaseId = null,
            mdbxFolderId = null,
            bitwardenVaultId = target.vaultId,
            bitwardenCipherId = null,
            bitwardenFolderId = target.folderId,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            replicaGroupId = null,
            isArchived = false,
            archivedAt = null,
            isDeleted = false,
            deletedAt = null
        )

        is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> entry.copy(
            id = 0,
            createdAt = now,
            updatedAt = now,
            categoryId = null,
            keepassDatabaseId = target.databaseId,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            mdbxDatabaseId = null,
            mdbxFolderId = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            replicaGroupId = null,
            isArchived = false,
            archivedAt = null,
            isDeleted = false,
            deletedAt = null
        )

        is UnifiedMoveCategoryTarget.KeePassGroupTarget -> entry.copy(
            id = 0,
            createdAt = now,
            updatedAt = now,
            categoryId = null,
            keepassDatabaseId = target.databaseId,
            keepassGroupPath = target.groupPath,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            mdbxDatabaseId = null,
            mdbxFolderId = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            replicaGroupId = null,
            isArchived = false,
            archivedAt = null,
            isDeleted = false,
            deletedAt = null
        )

        is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> entry.copy(
            id = 0,
            createdAt = now,
            updatedAt = now,
            categoryId = null,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            mdbxDatabaseId = target.databaseId,
            mdbxFolderId = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            replicaGroupId = null,
            isArchived = false,
            archivedAt = null,
            isDeleted = false,
            deletedAt = null
        )

        is UnifiedMoveCategoryTarget.MdbxFolderTarget -> entry.copy(
            id = 0,
            createdAt = now,
            updatedAt = now,
            categoryId = null,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            mdbxDatabaseId = target.databaseId,
            mdbxFolderId = target.folderId,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            replicaGroupId = null,
            isArchived = false,
            archivedAt = null,
            isDeleted = false,
            deletedAt = null
        )
    }
}

internal fun buildMoveTargetLabel(
    context: Context,
    target: UnifiedMoveCategoryTarget,
    categories: List<Category>,
    keepassDatabases: List<LocalKeePassDatabase>,
    mdbxDatabases: List<LocalMdbxDatabase> = emptyList()
): String {
    return when (target) {
        UnifiedMoveCategoryTarget.Uncategorized -> context.getString(R.string.category_none)
        is UnifiedMoveCategoryTarget.MonicaCategory -> {
            if (target.categoryId == UNIFIED_MOVE_ARCHIVE_SENTINEL_CATEGORY_ID) {
                context.getString(R.string.archive_page_title)
            } else {
                categories.find { it.id == target.categoryId }?.name
                    ?: context.getString(R.string.filter_monica)
            }
        }

        is UnifiedMoveCategoryTarget.BitwardenVaultTarget,
        is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> context.getString(R.string.filter_bitwarden)

        is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> {
            keepassDatabases.find { it.id == target.databaseId }?.name ?: "KeePass"
        }

        is UnifiedMoveCategoryTarget.KeePassGroupTarget -> decodeKeePassPathForDisplay(target.groupPath)

        is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> {
            mdbxDatabases.find { it.id == target.databaseId }?.name ?: "MDBX"
        }

        is UnifiedMoveCategoryTarget.MdbxFolderTarget -> {
            mdbxDatabases.find { it.id == target.databaseId }?.name ?: "MDBX"
        }
    }
}

internal data class PasswordBatchCopyResult(
    val successCount: Int,
    val failedCount: Int,
    val copiedEntryIds: List<Long>,
    /** 源 password id → 新 password id 的映射，便于调用方做级联复制（附件、TOTP 等）。 */
    val idPairs: List<Pair<Long, Long>> = emptyList()
)

internal suspend fun executePasswordBatchCopy(
    context: Context,
    selectedEntries: List<PasswordEntry>,
    target: UnifiedMoveCategoryTarget,
    targetRouting: PasswordBatchMoveTargetRouting,
    copyPasswordToMonicaLocal: suspend (PasswordEntry, Long?) -> Long?,
    addCopiedEntry: suspend (PasswordEntry) -> Long?,
    addMdbxCopiedEntriesBatch: suspend (List<PasswordEntry>) -> List<Long>,
    buildCopiedEntryForTarget: (PasswordEntry, UnifiedMoveCategoryTarget) -> PasswordEntry,
    rollbackCopiedEntry: suspend (Long) -> Unit = {},
    logTimeline: Boolean = true,
    onProgress: ((Int, Int) -> Unit)? = null
): PasswordBatchCopyResult {
    val copiedIds = mutableListOf<Long>()
    val idPairs = mutableListOf<Pair<Long, Long>>()
    var failedCount = 0
    val total = selectedEntries.size
    var processed = 0
    if (total > 0) {
        onProgress?.invoke(0, total)
    }

    if (targetRouting.isMonicaCopyTarget) {
        selectedEntries.forEach { entry ->
            val createdId = copyPasswordToMonicaLocal(entry, targetRouting.monicaCategoryId)
            if (createdId != null && createdId > 0) {
                copiedIds += createdId
                idPairs += entry.id to createdId
            } else {
                failedCount += 1
            }
            processed += 1
            onProgress?.invoke(processed, total)
        }
    } else if (target is UnifiedMoveCategoryTarget.MdbxDatabaseTarget || target is UnifiedMoveCategoryTarget.MdbxFolderTarget) {
        val copiedEntries = selectedEntries.map { entry -> buildCopiedEntryForTarget(entry, target) }
        val createdIds = addMdbxCopiedEntriesBatch(copiedEntries)
        createdIds.forEachIndexed { index, createdId ->
            if (createdId > 0) {
                copiedIds += createdId
                selectedEntries.getOrNull(index)?.let { source -> idPairs += source.id to createdId }
            }
        }
        failedCount += (selectedEntries.size - copiedIds.size).coerceAtLeast(0)
        processed = total
        onProgress?.invoke(processed, total)
    } else {
        selectedEntries.forEach { entry ->
            val copiedEntry = buildCopiedEntryForTarget(entry, target)
            val createdId = addCopiedEntry(copiedEntry)
            if (createdId != null && createdId > 0) {
                copiedIds += createdId
                idPairs += entry.id to createdId
            } else {
                failedCount += 1
            }
            processed += 1
            onProgress?.invoke(processed, total)
        }
    }

    // Monica 与 MDBX 目标都使用独立 LOCAL 附件记录；MDBX 克隆时同步写入目标数据库。
    // KeePass 由 KDBX 执行器处理，Bitwarden 需要等待目标 cipher 建立后再上传。
    val cloneToLocalAttachmentTarget = targetRouting.isMonicaCopyTarget ||
        target is UnifiedMoveCategoryTarget.MdbxDatabaseTarget ||
        target is UnifiedMoveCategoryTarget.MdbxFolderTarget
    if (cloneToLocalAttachmentTarget && idPairs.isNotEmpty()) {
        val facade = takagi.ru.monica.attachments.AttachmentContainer.facade(context)
        idPairs.toList().forEach { pair ->
            val (sourceId, newId) = pair
            val attachmentCopied = runCatching {
                val expectedCount = facade.listByPassword(sourceId).size
                val copiedCount = facade.cloneAttachmentsToNewParent(sourceId, newId)
                check(copiedCount == expectedCount) {
                    "Attachment copy incomplete for password $sourceId"
                }
            }.isSuccess
            if (!attachmentCopied) {
                runCatching { rollbackCopiedEntry(newId) }
                copiedIds.remove(newId)
                idPairs.remove(pair)
                failedCount += 1
            }
        }
    }

    if (logTimeline) {
        logPasswordBatchCopyTimeline(
            context = context,
            copiedEntryIds = copiedIds.toList()
        )
    }

    return PasswordBatchCopyResult(
        successCount = copiedIds.size,
        failedCount = failedCount,
        copiedEntryIds = copiedIds.toList(),
        idPairs = idPairs.toList()
    )
}

private val timelineBatchJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
}

internal fun logPasswordBatchCopyTimeline(
    context: Context,
    copiedEntryIds: List<Long>,
    copiedCountOverride: Int? = null
) {
    val copiedCount = copiedCountOverride ?: copiedEntryIds.size
    if (copiedCount <= 0) return
    val payload = TimelineBatchCopyPayload(copiedEntryIds = copiedEntryIds)
    OperationLogger.logUpdate(
        itemType = OperationLogItemType.PASSWORD,
        itemId = System.currentTimeMillis(),
        itemTitle = context.getString(
            R.string.timeline_batch_copy_title,
            copiedCount
        ),
        changes = listOf(
            FieldChange(
                fieldName = context.getString(R.string.timeline_field_batch_copy),
                oldValue = "0",
                newValue = copiedCount.toString()
            ),
            FieldChange(
                fieldName = TIMELINE_FIELD_BATCH_COPY_PAYLOAD,
                oldValue = "{}",
                newValue = timelineBatchJson.encodeToString(payload)
            )
        )
    )
}

internal fun logPasswordBatchMoveTimeline(
    context: Context,
    selectedEntries: List<PasswordEntry>,
    oldStates: List<TimelinePasswordLocationState>,
    newStates: List<TimelinePasswordLocationState>,
    recreatedEntries: List<TimelinePasswordRecreatedEntry> = emptyList(),
    targetLabel: String
) {
    if (selectedEntries.isEmpty()) return
    val payload = TimelineBatchMovePayload(
        oldStates = oldStates,
        newStates = newStates,
        recreatedEntries = recreatedEntries
    )
    val payloadJson = timelineBatchJson.encodeToString(payload)
    OperationLogger.logUpdate(
        itemType = OperationLogItemType.PASSWORD,
        itemId = System.currentTimeMillis(),
        itemTitle = context.getString(
            R.string.timeline_batch_move_title,
            selectedEntries.size
        ),
        changes = listOf(
            FieldChange(
                fieldName = context.getString(R.string.timeline_field_batch_move),
                oldValue = context.getString(R.string.timeline_batch_source_multiple),
                newValue = targetLabel
            ),
            FieldChange(
                fieldName = TIMELINE_FIELD_BATCH_MOVE_PAYLOAD,
                oldValue = payloadJson,
                newValue = payloadJson
            )
        )
    )
}

private fun buildPasswordDecryptSnapshot(
    entries: List<PasswordEntry>,
    securityManager: SecurityManager
): Map<String, String> {
    return entries.mapNotNull { entry ->
        runCatching { if (takagi.ru.monica.keepass.KeePassMonicaPasswordStore.isUiId(entry.id)) entry.password else securityManager.decryptData(entry.password) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { plain -> entry.password to plain }
    }.toMap()
}

private fun resolvePasswordForBatchMove(
    encrypted: String,
    decryptSnapshot: Map<String, String>,
    securityManager: SecurityManager
): String {
    return decryptSnapshot[encrypted]
        ?: securityManager.decryptData(encrypted)
        ?: ""
}

internal fun PasswordBatchAggregateSelection.totalItemCount(
    selectedPasswordCount: Int
): Int {
    return selectedPasswordCount +
        bankCards.size +
        documents.size +
        billingAddresses.size +
        notes.size +
        totpItems.size +
        passkeys.size
}

@Composable
internal fun PasswordBatchTransferProgressDialog(
    state: PasswordBatchTransferProgressUiState,
    onMoveToBackground: () -> Unit
) {
    val title = when (state.action) {
        UnifiedMoveAction.COPY -> R.string.password_batch_transfer_progress_title_copy
        UnifiedMoveAction.MOVE -> R.string.password_batch_transfer_progress_title_move
    }
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(text = state.targetLabel)
        },
        text = {
            Column {
                Text(text = stringResource(id = title))
                Spacer(modifier = Modifier.height(12.dp))
                if (state.total > 0 && state.processed <= 0) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { state.progressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (state.total > 0 && state.processed <= 0) {
                        stringResource(R.string.password_batch_transfer_progress_preparing)
                    } else {
                        state.progressText
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onMoveToBackground) {
                Text(text = stringResource(R.string.password_batch_transfer_continue_in_background))
            }
        }
    )
}

@Composable
internal fun PasswordBatchMoveSheet(
    visible: Boolean,
    initialSource: UnifiedMoveInitialSource,
    categories: List<Category>,
    keepassDatabases: List<LocalKeePassDatabase>,
    mdbxDatabases: List<takagi.ru.monica.data.LocalMdbxDatabase> = emptyList(),
    bitwardenVaults: List<takagi.ru.monica.data.bitwarden.BitwardenVault>,
    database: takagi.ru.monica.data.PasswordDatabase,
    localKeePassViewModel: takagi.ru.monica.viewmodel.LocalKeePassViewModel,
    securityManager: SecurityManager,
    selectedPasswords: Set<Long>,
    selectedSupplementaryItems: List<PasswordAggregateListItemUi>,
    passwordEntries: List<PasswordEntry>,
    aggregateUiState: PasswordListAggregateUiState,
    viewModel: PasswordViewModel,
    bitwardenRepository: BitwardenRepository,
    context: Context,
    coroutineScope: CoroutineScope,
    onRenameCategory: (Category) -> Unit,
    onDeleteCategory: (Category) -> Unit,
    onDismiss: () -> Unit,
    onSelectionCleared: () -> Unit
) {
    val selectedEntries = remember(selectedPasswords, passwordEntries) {
        passwordEntries.filter { it.id in selectedPasswords }
    }
    val aggregateSelection = remember(
        selectedSupplementaryItems,
        aggregateUiState.bankCards,
        aggregateUiState.documents,
        aggregateUiState.billingAddresses,
        aggregateUiState.notes,
        aggregateUiState.totpItems,
        aggregateUiState.passkeys
    ) {
        aggregateUiState.resolveBatchAggregateSelection(selectedSupplementaryItems)
    }
    val hasMixedSelection = aggregateSelection.hasItems
    var transferProgress by remember {
        mutableStateOf<PasswordBatchTransferProgressUiState?>(null)
    }
    var showProgressDialog by remember {
        mutableStateOf(false)
    }

    // 附件感知移动确认弹窗状态
    var attachmentAwarePrompt by remember {
        mutableStateOf<AttachmentAwareMovePrompt?>(null)
    }
    var preserveCategoriesPrompt by remember {
        mutableStateOf<PasswordBatchPreserveCategoriesPrompt?>(null)
    }
    var preserveCategoriesForNextTransfer by remember { mutableStateOf(false) }
    var keepassFailurePrompt by remember {
        mutableStateOf<KeePassBatchTransferFailurePrompt?>(null)
    }

    UnifiedMoveToCategoryBottomSheet(
        visible = visible,
        onDismiss = onDismiss,
        initialSource = initialSource,
        categories = categories,
        keepassDatabases = keepassDatabases,
        mdbxDatabases = mdbxDatabases,
        bitwardenVaults = bitwardenVaults,
        getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
        getKeePassGroups = localKeePassViewModel::getGroups,
        getMdbxFolders = viewModel::getMdbxFolders,
        refreshMdbxFolders = viewModel::refreshMdbxFolders,
        showBitwardenFolderTargets = false,
        allowCopy = true,
        allowMove = true,
        allowArchiveTarget = !hasMixedSelection,
        onBeforeTargetSelected = { target, _, proceed ->
            if (shouldOfferPasswordBatchCategoryPreservation(selectedEntries, target)) {
                val targetStorageKey = target.passwordBatchStorageKey()
                val classifiedItemCount = selectedEntries.count { entry ->
                    entry.hasPasswordBatchSourceCategory() &&
                        entry.passwordBatchStorageKey() != targetStorageKey
                }
                preserveCategoriesPrompt = PasswordBatchPreserveCategoriesPrompt(
                    classifiedItemCount = classifiedItemCount,
                    proceed = { preserveCategories ->
                        preserveCategoriesForNextTransfer = preserveCategories
                        preserveCategoriesPrompt = null
                        proceed()
                    }
                )
            } else {
                preserveCategoriesForNextTransfer = false
                proceed()
            }
        },
        onTargetSelected = { target, action ->
            val preserveSourceCategories = preserveCategoriesForNextTransfer
            preserveCategoriesForNextTransfer = false
            val selectedIds = selectedEntries.map(PasswordEntry::id)
            val actionResolutionForProgress = resolvePasswordBatchMoveAction(
                requestedAction = action,
                selectedEntries = selectedEntries,
                target = target
            )
            val effectiveAction = if (
                actionResolutionForProgress.effectiveAction == UnifiedMoveAction.COPY ||
                shouldForceSupplementaryKeePassCopy(
                    requestedAction = action,
                    hasKeePassOwnedItems = aggregateSelection.hasKeePassOwned,
                    target = target
                )
            ) {
                UnifiedMoveAction.COPY
            } else {
                action
            }
            val totalCount = if (hasMixedSelection) {
                aggregateSelection.totalItemCount(selectedEntries.size)
            } else {
                selectedEntries.size
            }
            if (totalCount <= 0) {
                onDismiss()
                onSelectionCleared()
                return@UnifiedMoveToCategoryBottomSheet
            }

            val targetLabel = buildMoveTargetLabel(
                context = context,
                target = target,
                categories = categories,
                keepassDatabases = keepassDatabases
            )
            val notificationId = PasswordBatchTransferNotificationHelper.createNotificationId()
            var lastKnownProcessed = 0
            var lastKnownTotal = totalCount
            val onProgressUpdate: (Int, Int) -> Unit = { processed, total ->
                val normalizedTotal = total.coerceAtLeast(totalCount)
                val normalizedProcessed = processed.coerceIn(0, normalizedTotal)
                lastKnownProcessed = maxOf(lastKnownProcessed, normalizedProcessed)
                lastKnownTotal = normalizedTotal
                coroutineScope.launch {
                    transferProgress = PasswordBatchTransferProgressUiState(
                        action = effectiveAction,
                        targetLabel = targetLabel,
                        processed = normalizedProcessed,
                        total = normalizedTotal
                    )
                }
                PasswordBatchTransferProgressTracker.update(
                    action = effectiveAction,
                    targetLabel = targetLabel,
                    processed = normalizedProcessed,
                    total = normalizedTotal
                )
                PasswordBatchTransferNotificationHelper.showProgress(
                    context = context,
                    notificationId = notificationId,
                    action = effectiveAction,
                    processed = normalizedProcessed,
                    total = normalizedTotal,
                    targetLabel = targetLabel
                )
            }

            showProgressDialog = false
            onProgressUpdate(if (totalCount > 1) 1 else 0, totalCount)
            onDismiss()
            onSelectionCleared()

            viewModel.viewModelScope.launch {
                var successCount = 0
                var failedCount = 0
                var completedCleanly = false
                val passwordTargetOverrides = if (preserveSourceCategories) {
                    try {
                        resolvePasswordBatchPreservedCategoryTargets(
                            entries = selectedEntries,
                            selectedTarget = target,
                            categories = categories,
                            bitwardenRepository = bitwardenRepository,
                            localKeePassViewModel = localKeePassViewModel,
                            passwordViewModel = viewModel
                        )
                    } catch (error: Exception) {
                        PasswordBatchTransferNotificationHelper.showCompleted(
                            context = context,
                            notificationId = notificationId,
                            action = effectiveAction,
                            successCount = 0,
                            failedCount = totalCount
                        )
                        Toast.makeText(
                            context,
                            context.getString(R.string.webdav_operation_failed, error.message ?: ""),
                            Toast.LENGTH_SHORT
                        ).show()
                        transferProgress = null
                        PasswordBatchTransferProgressTracker.clear()
                        return@launch
                    }
                } else {
                    emptyMap()
                }
                // Attachment_Aware_Move_Dialog preflight（Requirement 8）：
                // 目标是 Bitwarden Vault/Folder + 该 vault 是免费账户 + 选中集合里有带附件条目
                // → 弹 dialog 让用户知情；用户确认后按原逻辑执行（附件本身不会被搬到 Bitwarden）
                val bitwardenMoveTargetVaultId: Long? = when (target) {
                    is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> target.vaultId
                    is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.vaultId
                    else -> null
                }
                if (bitwardenMoveTargetVaultId != null) {
                    val vaultIsPremium = takagi.ru.monica.bitwarden.BitwardenVaultPremiumStore
                        .isPremium(context, bitwardenMoveTargetVaultId)
                    val advisor = takagi.ru.monica.attachments.AttachmentContainer
                        .batchMoveAdvisor(context)
                    val classification = advisor.classify(selectedIds, vaultIsPremium)
                    if (!vaultIsPremium && classification.copyInsteadOfMove.isNotEmpty()) {
                        val attachedTitles = selectedEntries
                            .filter { it.id in classification.copyInsteadOfMove }
                            .map { it.title }
                        val userConfirmed = kotlinx.coroutines.CompletableDeferred<Boolean>()
                        attachmentAwarePrompt = AttachmentAwareMovePrompt(
                            classification = classification,
                            titles = attachedTitles,
                            response = userConfirmed
                        )
                        val confirmed = userConfirmed.await()
                        attachmentAwarePrompt = null
                        if (!confirmed) {
                            // 用户取消：中止整个批量操作
                            showProgressDialog = false
                            transferProgress = null
                            PasswordBatchTransferProgressTracker.clear()
                            PasswordBatchTransferNotificationHelper.cancel(context, notificationId)
                            onSelectionCleared()
                            return@launch
                        }
                        // 用户确认 → action == MOVE 时走"分两路"的手动实现；
                        // action == COPY（例如 KeePass 选中集被强转）时走原 COPY 主流程，
                        // 附件本身不会被 buildCopiedEntryForTarget 带进 Bitwarden
                        if (action == UnifiedMoveAction.MOVE) {
                            try {
                                val targetFolderId = when (target) {
                                    is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.folderId
                                    else -> ""
                                }
                                if (classification.plainMove.isNotEmpty()) {
                                    selectedEntries
                                        .filter { it.id in classification.plainMove }
                                        .groupBy { entry ->
                                            passwordBatchTargetForEntry(
                                                entry = entry,
                                                selectedTarget = target,
                                                targetOverrides = passwordTargetOverrides
                                            )
                                        }
                                        .forEach { (resolvedTarget, entries) ->
                                            val folderId = (resolvedTarget as? UnifiedMoveCategoryTarget.BitwardenFolderTarget)
                                                ?.folderId
                                                ?: targetFolderId
                                            val ids = entries.map(PasswordEntry::id)
                                            viewModel.unarchivePasswordsAwait(ids)
                                            viewModel.movePasswordsToBitwardenFolderAwait(
                                                ids,
                                                bitwardenMoveTargetVaultId,
                                                folderId
                                            )
                                        }
                                }
                                if (classification.copyInsteadOfMove.isNotEmpty()) {
                                    val entriesToCopy = selectedEntries
                                        .filter { it.id in classification.copyInsteadOfMove }
                                    entriesToCopy.forEach { entry ->
                                        val copied = viewModel.buildEncryptedPasswordCopy(
                                            entry,
                                            passwordBatchTargetForEntry(
                                                entry = entry,
                                                selectedTarget = target,
                                                targetOverrides = passwordTargetOverrides
                                            )
                                        )
                                        viewModel.addPasswordEntryWithResultAwait(copied)
                                    }
                                }
                                successCount = selectedEntries.size
                                onProgressUpdate(selectedEntries.size, selectedEntries.size)
                                completedCleanly = true
                            } catch (e: Exception) {
                                android.util.Log.e(
                                    "PasswordBatchMove",
                                    "Attachment-aware split move failed",
                                    e
                                )
                                failedCount = selectedEntries.size
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.webdav_operation_failed, e.message ?: ""),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } finally {
                                transferProgress = null
                                showProgressDialog = false
                                if (completedCleanly && successCount > 0 && failedCount == 0) {
                                    PasswordBatchTransferProgressTracker.complete(
                                        action = effectiveAction,
                                        targetLabel = targetLabel,
                                        successCount = successCount
                                    )
                                } else {
                                    PasswordBatchTransferProgressTracker.clear()
                                }
                                PasswordBatchTransferNotificationHelper.showCompleted(
                                    context = context,
                                    notificationId = notificationId,
                                    action = effectiveAction,
                                    successCount = successCount,
                                    failedCount = failedCount
                                )
                                onSelectionCleared()
                            }
                            return@launch
                        }
                        // action == COPY：跌落到后续标准流程（KeePass → Bitwarden 会走 COPY 分支）
                    }
                }
                try {
                    val targetBitwardenVault = bitwardenMoveTargetVaultId?.let { vaultId ->
                        bitwardenVaults.firstOrNull { it.id == vaultId }
                            ?: error("Bitwarden target vault is missing")
                    }
                    val targetBitwardenIsPremium = bitwardenMoveTargetVaultId?.let { vaultId ->
                        takagi.ru.monica.bitwarden.BitwardenVaultPremiumStore.isPremium(context, vaultId)
                    } ?: false
                    val preparedAttachments = preparePasswordBatchAttachments(
                        context = context,
                        entries = selectedEntries,
                        bitwardenVaults = bitwardenVaults,
                        viewModel = viewModel,
                        skipPasswordIds = if (
                            bitwardenMoveTargetVaultId != null && !targetBitwardenIsPremium
                        ) {
                            selectedIds.toSet()
                        } else {
                            emptySet()
                        }
                    )
                    if (hasMixedSelection) {
                        val result = executeMixedPasswordBatchMove(
                            context = context,
                            action = action,
                            target = target,
                            selectedEntries = selectedEntries,
                            aggregateSelection = aggregateSelection,
                            categories = categories,
                            keepassDatabases = keepassDatabases,
                            localKeePassViewModel = localKeePassViewModel,
                            securityManager = securityManager,
                            viewModel = viewModel,
                            aggregateViewModels = aggregateUiState.toPasswordBatchMoveViewModels(),
                            bitwardenRepository = bitwardenRepository,
                            passwordTargetOverrides = passwordTargetOverrides,
                            onProgress = onProgressUpdate
                        )
                        successCount = result.successCount
                        failedCount = result.failedCount
                        if (result.keepassFailureMessages.isNotEmpty()) {
                            throw KeePassBatchTransferException(
                                resolveKeePassBatchTransferFailure(
                                    failureCount = result.failedCount,
                                    failureMessages = result.keepassFailureMessages,
                                )
                            )
                        }
                        if (effectiveAction == UnifiedMoveAction.COPY) {
                            if (targetBitwardenVault != null && targetBitwardenIsPremium) {
                                completePasswordBatchBitwardenAttachments(
                                    context = context,
                                    idPairs = result.copiedPasswordIdPairs,
                                    sourceEntries = selectedEntries,
                                    targetVault = targetBitwardenVault,
                                    preparedAttachments = preparedAttachments,
                                    isMove = false,
                                    viewModel = viewModel,
                                    bitwardenRepository = bitwardenRepository
                                )
                            } else {
                                completePasswordBatchLocalOrKeePassAttachmentCopies(
                                    context = context,
                                    idPairs = result.copiedPasswordIdPairs,
                                    target = target,
                                    preparedAttachments = preparedAttachments,
                                    viewModel = viewModel
                                )
                            }
                        } else if (targetBitwardenVault != null && targetBitwardenIsPremium) {
                            completePasswordBatchBitwardenAttachments(
                                context = context,
                                idPairs = selectedEntries.map { it.id to it.id },
                                sourceEntries = selectedEntries,
                                targetVault = targetBitwardenVault,
                                preparedAttachments = preparedAttachments,
                                isMove = true,
                                viewModel = viewModel,
                                bitwardenRepository = bitwardenRepository
                            )
                        }
                        if (result.blockedPasskeyCount > 0) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.passkey_bitwarden_move_blocked),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        val actionResolution = resolvePasswordBatchMoveAction(
                            requestedAction = action,
                            selectedEntries = selectedEntries,
                            target = target
                        )
                        if (actionResolution.showKeepassCopyOnlyHint) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.keepass_copy_only_hint),
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        val resolvedAction = actionResolution.effectiveAction
                        val targetRouting = resolvePasswordBatchMoveTargetRouting(target)
                        val passwordGroups = groupPasswordBatchEntriesByTarget(
                            entries = selectedEntries,
                            selectedTarget = target,
                            targetOverrides = passwordTargetOverrides
                        )
                        if (resolvedAction == UnifiedMoveAction.COPY) {
                            when (target) {
                                is UnifiedMoveCategoryTarget.KeePassDatabaseTarget,
                                is UnifiedMoveCategoryTarget.KeePassGroupTarget -> {
                                    val decryptSnapshot = buildPasswordDecryptSnapshot(
                                        entries = selectedEntries,
                                        securityManager = securityManager
                                    )
                                    val copiedEntries = selectedEntries.map { entry ->
                                        buildCopiedEntryForTarget(
                                            entry,
                                            passwordBatchTargetForEntry(
                                                entry = entry,
                                                selectedTarget = target,
                                                targetOverrides = passwordTargetOverrides
                                            )
                                        )
                                    }
                                    val targetDatabaseId = when (target) {
                                        is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> target.databaseId
                                        is UnifiedMoveCategoryTarget.KeePassGroupTarget -> target.databaseId
                                        else -> error("Unexpected KeePass target")
                                    }
                                    val addResult = localKeePassViewModel.addPasswordEntriesToKdbx(
                                        databaseId = targetDatabaseId,
                                        entries = copiedEntries,
                                        decryptPassword = { encrypted ->
                                            resolvePasswordForBatchMove(
                                                encrypted = encrypted,
                                                decryptSnapshot = decryptSnapshot,
                                                securityManager = securityManager
                                            )
                                        },
                                        sourceEntries = selectedEntries,
                                        onItemProcessed = onProgressUpdate
                                    )
                                    if (addResult.isFailure) {
                                        throw addResult.exceptionOrNull()
                                            ?: IllegalStateException("Copy to KeePass failed")
                                    }
                                    val addedCount = addResult.getOrThrow().coerceIn(0, selectedEntries.size)
                                    successCount = addedCount
                                    failedCount = (selectedEntries.size - addedCount).coerceAtLeast(0)
                                    logPasswordBatchCopyTimeline(
                                        context = context,
                                        copiedEntryIds = emptyList(),
                                        copiedCountOverride = successCount
                                    )
                                }

                                else -> {
                                    val copiedEntryIds = mutableListOf<Long>()
                                    val copiedIdPairs = mutableListOf<Pair<Long, Long>>()
                                    var processedBeforeGroup = 0
                                    val copyGroups = if (
                                        target is UnifiedMoveCategoryTarget.MdbxDatabaseTarget ||
                                        target is UnifiedMoveCategoryTarget.MdbxFolderTarget
                                    ) {
                                        listOf(target to selectedEntries)
                                    } else {
                                        passwordGroups
                                    }
                                    copyGroups.forEach { (groupTarget, groupEntries) ->
                                        val copyResult = executePasswordBatchCopy(
                                            context = context,
                                            selectedEntries = groupEntries,
                                            target = groupTarget,
                                            targetRouting = resolvePasswordBatchMoveTargetRouting(groupTarget),
                                            copyPasswordToMonicaLocal = { entry, categoryId ->
                                                viewModel.copyPasswordToMonicaLocal(
                                                    entry = entry,
                                                    categoryId = categoryId
                                                )
                                            },
                                            addCopiedEntry = { entry ->
                                                viewModel.addPasswordEntryWithResultAwait(entry)
                                            },
                                            addMdbxCopiedEntriesBatch = { entries ->
                                                viewModel.createMdbxPasswordEntriesBatchAlreadyEncrypted(entries)
                                            },
                                            buildCopiedEntryForTarget = { entry, fallbackTarget ->
                                                val resolvedTarget = if (
                                                    target is UnifiedMoveCategoryTarget.MdbxDatabaseTarget ||
                                                    target is UnifiedMoveCategoryTarget.MdbxFolderTarget
                                                ) {
                                                    passwordBatchTargetForEntry(
                                                        entry = entry,
                                                        selectedTarget = target,
                                                        targetOverrides = passwordTargetOverrides
                                                    )
                                                } else {
                                                    fallbackTarget
                                                }
                                                viewModel.buildEncryptedPasswordCopy(entry, resolvedTarget)
                                            },
                                            rollbackCopiedEntry = viewModel::rollbackPasswordTransferTargetAwait,
                                            logTimeline = false,
                                            onProgress = { processed, _ ->
                                                onProgressUpdate(
                                                    (processedBeforeGroup + processed)
                                                        .coerceAtMost(selectedEntries.size),
                                                    selectedEntries.size
                                                )
                                            }
                                        )
                                        processedBeforeGroup += groupEntries.size
                                        successCount += copyResult.successCount
                                        failedCount += copyResult.failedCount
                                        copiedEntryIds += copyResult.copiedEntryIds
                                        copiedIdPairs += copyResult.idPairs
                                    }
                                    logPasswordBatchCopyTimeline(
                                        context = context,
                                        copiedEntryIds = copiedEntryIds
                                    )
                                    if (copiedIdPairs.isNotEmpty() && target.passwordBatchStorageKey()?.startsWith("mdbx:") == true) {
                                        viewModel.copyBoundTotpsForPasswordCopies(copiedIdPairs)
                                    }
                                    if (targetBitwardenVault != null && targetBitwardenIsPremium) {
                                        completePasswordBatchBitwardenAttachments(
                                            context = context,
                                            idPairs = copiedIdPairs,
                                            sourceEntries = selectedEntries,
                                            targetVault = targetBitwardenVault,
                                            preparedAttachments = preparedAttachments,
                                            isMove = false,
                                            viewModel = viewModel,
                                            bitwardenRepository = bitwardenRepository
                                        )
                                    }
                                }
                            }
                        } else {
                            val oldStates = selectedEntries.map(::toLocationState)
                            val newStates = selectedEntries.map { entry ->
                                toMovedLocationState(
                                    entry,
                                    passwordBatchTargetForEntry(
                                        entry = entry,
                                        selectedTarget = target,
                                        targetOverrides = passwordTargetOverrides
                                    )
                                )
                            }
                            val recreatedEntries = mutableListOf<TimelinePasswordRecreatedEntry>()
                            val decryptSnapshot = buildPasswordDecryptSnapshot(
                                entries = selectedEntries,
                                securityManager = securityManager
                            )

                            when {
                                targetRouting.isArchiveTarget -> {
                                    viewModel.archivePasswords(selectedIds)
                                    onProgressUpdate(selectedEntries.size, selectedEntries.size)
                                }

                                else -> {
                                    if (
                                        target is UnifiedMoveCategoryTarget.MdbxDatabaseTarget ||
                                        target is UnifiedMoveCategoryTarget.MdbxFolderTarget
                                    ) {
                                        val databaseId = when (target) {
                                            is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> target.databaseId
                                            is UnifiedMoveCategoryTarget.MdbxFolderTarget -> target.databaseId
                                            else -> error("Unexpected MDBX target")
                                        }
                                        val folderIdsByPasswordId = selectedEntries.associate { entry ->
                                            val resolvedTarget = passwordBatchTargetForEntry(
                                                entry = entry,
                                                selectedTarget = target,
                                                targetOverrides = passwordTargetOverrides
                                            )
                                            val folderId = (resolvedTarget as? UnifiedMoveCategoryTarget.MdbxFolderTarget)
                                                ?.folderId
                                            entry.id to folderId
                                        }
                                        viewModel.unarchivePasswordsAwait(selectedIds)
                                        viewModel.movePasswordsToMdbxFoldersAwait(
                                            databaseId = databaseId,
                                            folderIdsByPasswordId = folderIdsByPasswordId
                                        )
                                        onProgressUpdate(selectedEntries.size, selectedEntries.size)
                                    } else {
                                    var processedBeforeGroup = 0
                                    passwordGroups.forEach { (groupTarget, groupEntries) ->
                                        val groupIds = groupEntries.map(PasswordEntry::id)
                                        when (groupTarget) {
                                            UnifiedMoveCategoryTarget.Uncategorized,
                                            is UnifiedMoveCategoryTarget.MonicaCategory -> {
                                                val categoryId = (groupTarget as? UnifiedMoveCategoryTarget.MonicaCategory)
                                                    ?.categoryId
                                                val keepassEntries = groupEntries.filter { it.isKeePassEntry() }
                                                val bitwardenEntries = groupEntries.filter { it.isBitwardenEntry() }
                                                val mdbxEntries = groupEntries.filter { it.isMdbxEntry() }
                                                val localIds = groupEntries
                                                    .filter { it.isLocalOnlyEntry() }
                                                    .map(PasswordEntry::id)

                                                if (keepassEntries.isNotEmpty()) {
                                                    val keepassIds = keepassEntries.map(PasswordEntry::id)
                                                    val result = viewModel.moveKeePassPasswordsToMonicaCategoryAwait(
                                                        ids = keepassIds,
                                                        categoryId = categoryId
                                                    )
                                                    if (result.isFailure) {
                                                        throw result.exceptionOrNull()
                                                            ?: IllegalStateException("KeePass move failed")
                                                    }
                                                    viewModel.unarchivePasswordsAwait(keepassIds)
                                                }
                                                bitwardenEntries.forEach { entry ->
                                                    val result = viewModel.moveBitwardenPasswordToMonicaLocal(
                                                        entry = entry,
                                                        categoryId = categoryId
                                                    )
                                                    if (result.isFailure) {
                                                        throw result.exceptionOrNull()
                                                            ?: IllegalStateException("Bitwarden move failed")
                                                    }
                                                    recreatedEntries += TimelinePasswordRecreatedEntry(
                                                        sourceEntryId = entry.id,
                                                        recreatedEntryId = result.getOrThrow()
                                                    )
                                                }
                                                if (mdbxEntries.isNotEmpty()) {
                                                    viewModel.moveMdbxPasswordsToMonicaCategoryAwait(
                                                        entries = mdbxEntries,
                                                        categoryId = categoryId
                                                    )
                                                }
                                                if (localIds.isNotEmpty()) {
                                                    viewModel.unarchivePasswordsAwait(localIds)
                                                    viewModel.movePasswordsToCategoryAwait(localIds, categoryId)
                                                }
                                            }

                                            is UnifiedMoveCategoryTarget.BitwardenVaultTarget,
                                            is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> {
                                                val vaultId = when (groupTarget) {
                                                    is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> groupTarget.vaultId
                                                    is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> groupTarget.vaultId
                                                    else -> error("Unexpected Bitwarden target")
                                                }
                                                val folderId = (groupTarget as? UnifiedMoveCategoryTarget.BitwardenFolderTarget)
                                                    ?.folderId
                                                    .orEmpty()
                                                viewModel.unarchivePasswordsAwait(groupIds)
                                                viewModel.movePasswordsToBitwardenFolderAwait(groupIds, vaultId, folderId)
                                            }

                                            is UnifiedMoveCategoryTarget.KeePassDatabaseTarget,
                                            is UnifiedMoveCategoryTarget.KeePassGroupTarget -> {
                                                val databaseId = when (groupTarget) {
                                                    is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> groupTarget.databaseId
                                                    is UnifiedMoveCategoryTarget.KeePassGroupTarget -> groupTarget.databaseId
                                                    else -> error("Unexpected KeePass target")
                                                }
                                                val groupPath = (groupTarget as? UnifiedMoveCategoryTarget.KeePassGroupTarget)
                                                    ?.groupPath
                                                val groupUuid = (groupTarget as? UnifiedMoveCategoryTarget.KeePassGroupTarget)
                                                    ?.groupUuid
                                                val result = localKeePassViewModel.movePasswordEntriesToKdbx(
                                                    databaseId = databaseId,
                                                    groupPath = groupPath,
                                                    groupUuid = groupUuid,
                                                    entries = groupEntries,
                                                    decryptPassword = { encrypted ->
                                                        resolvePasswordForBatchMove(
                                                            encrypted = encrypted,
                                                            decryptSnapshot = decryptSnapshot,
                                                            securityManager = securityManager
                                                        )
                                                    },
                                                    onItemProcessed = { processed, _ ->
                                                        onProgressUpdate(
                                                            (processedBeforeGroup + processed)
                                                                .coerceAtMost(selectedEntries.size),
                                                            selectedEntries.size
                                                        )
                                                    }
                                                )
                                                if (result.isFailure) {
                                                    throw result.exceptionOrNull()
                                                        ?: IllegalStateException("Move to KeePass failed")
                                                }
                                                val summary = result.getOrThrow()
                                                val succeededIds = summary.targetEntryUuidsByPasswordId.keys.toList()
                                                if (succeededIds.isNotEmpty()) {
                                                    viewModel.unarchivePasswordsAwait(succeededIds.filterNot(takagi.ru.monica.keepass.KeePassMonicaPasswordStore::isUiId))
                                                    viewModel.finalizePasswordsWrittenToKeePassAwait(
                                                        targetEntryUuidsByPasswordId = summary.targetEntryUuidsByPasswordId,
                                                        databaseId = databaseId,
                                                        groupPath = groupPath
                                                    )
                                                }
                                                if (summary.failedCount > 0) {
                                                    throw KeePassBatchTransferException(
                                                        resolveKeePassBatchTransferFailure(
                                                            failures = summary.failuresByPasswordId,
                                                        )
                                                    )
                                                }
                                            }

                                            is UnifiedMoveCategoryTarget.MdbxDatabaseTarget,
                                            is UnifiedMoveCategoryTarget.MdbxFolderTarget -> {
                                                val databaseId = when (groupTarget) {
                                                    is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> groupTarget.databaseId
                                                    is UnifiedMoveCategoryTarget.MdbxFolderTarget -> groupTarget.databaseId
                                                    else -> error("Unexpected MDBX target")
                                                }
                                                val folderId = (groupTarget as? UnifiedMoveCategoryTarget.MdbxFolderTarget)
                                                    ?.folderId
                                                viewModel.unarchivePasswordsAwait(groupIds)
                                                viewModel.movePasswordsToMdbxDatabaseAwait(
                                                    groupIds,
                                                    databaseId,
                                                    folderId
                                                )
                                            }
                                        }
                                        processedBeforeGroup += groupEntries.size
                                        onProgressUpdate(processedBeforeGroup, selectedEntries.size)
                                    }
                                    }
                                }
                            }

                            if (targetBitwardenVault != null && targetBitwardenIsPremium) {
                                completePasswordBatchBitwardenAttachments(
                                    context = context,
                                    idPairs = selectedEntries.map { it.id to it.id },
                                    sourceEntries = selectedEntries,
                                    targetVault = targetBitwardenVault,
                                    preparedAttachments = preparedAttachments,
                                    isMove = true,
                                    viewModel = viewModel,
                                    bitwardenRepository = bitwardenRepository
                                )
                            }

                            logPasswordBatchMoveTimeline(
                                context = context,
                                selectedEntries = selectedEntries,
                                oldStates = oldStates,
                                newStates = newStates,
                                recreatedEntries = recreatedEntries,
                                targetLabel = targetLabel
                            )
                            successCount = selectedEntries.size
                            failedCount = 0
                        }
                    }

                    PasswordBatchTransferNotificationHelper.showCompleted(
                        context = context,
                        notificationId = notificationId,
                        action = effectiveAction,
                        successCount = successCount,
                        failedCount = failedCount
                    )
                    Toast.makeText(
                        context,
                        formatBatchResultToast(
                            context = context,
                            successCount = successCount,
                            failedCount = failedCount
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                    completedCleanly = true
                    onSelectionCleared()
                } catch (e: Exception) {
                    val normalizedTotal = lastKnownTotal.coerceAtLeast(totalCount)
                    val attachmentFailureCount = (e as? PasswordBatchAttachmentTransferException)
                        ?.failedPasswordCount
                        ?: 1
                    val preliminarySuccessCount = maxOf(
                        successCount,
                        (lastKnownProcessed - failedCount).coerceAtLeast(0)
                    ).coerceIn(0, normalizedTotal)
                    val normalizedFailedCount = if (failedCount > 0) {
                        maxOf(
                            failedCount,
                            attachmentFailureCount,
                            normalizedTotal - preliminarySuccessCount
                        )
                            .coerceIn(0, normalizedTotal)
                    } else {
                        maxOf(attachmentFailureCount, normalizedTotal - preliminarySuccessCount)
                            .coerceIn(0, normalizedTotal)
                    }
                    val inferredSuccessCount = preliminarySuccessCount
                        .coerceAtMost((normalizedTotal - normalizedFailedCount).coerceAtLeast(0))
                    PasswordBatchTransferNotificationHelper.showCompleted(
                        context = context,
                        notificationId = notificationId,
                        action = effectiveAction,
                        successCount = inferredSuccessCount,
                        failedCount = normalizedFailedCount
                    )
                    val keepassFailure = when {
                        e is KeePassBatchTransferException -> e.failure
                        target is UnifiedMoveCategoryTarget.KeePassDatabaseTarget ||
                            target is UnifiedMoveCategoryTarget.KeePassGroupTarget -> {
                            resolveKeePassBatchTransferFailure(
                                failureCount = normalizedFailedCount,
                                failureMessages = listOfNotNull(e.message),
                            )
                        }
                        else -> null
                    }
                    if (keepassFailure != null) {
                        keepassFailurePrompt = KeePassBatchTransferFailurePrompt(
                            targetLabel = targetLabel,
                            failure = keepassFailure,
                        )
                    } else {
                        Toast.makeText(
                            context,
                            if (e is PasswordBatchAttachmentTransferException) {
                                context.getString(
                                    R.string.password_batch_attachment_failure,
                                    e.failedPasswordCount,
                                    attachmentErrorMessage(context, e.cause ?: e)
                                )
                            } else context.getString(R.string.webdav_operation_failed, e.message ?: ""),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } finally {
                    transferProgress = null
                    showProgressDialog = false
                    if (completedCleanly && successCount > 0 && failedCount == 0) {
                        PasswordBatchTransferProgressTracker.complete(
                            action = effectiveAction,
                            targetLabel = targetLabel,
                            successCount = successCount
                        )
                    } else {
                        PasswordBatchTransferProgressTracker.clear()
                    }
                }
            }
            return@UnifiedMoveToCategoryBottomSheet
        }
    )

    if (showProgressDialog) {
        transferProgress?.let { state ->
            PasswordBatchTransferProgressDialog(
                state = state,
                onMoveToBackground = { showProgressDialog = false }
            )
        }
    }

    // Attachment_Aware_Move_Dialog：在免费 Bitwarden 账户 + 带附件条目批量移动时渲染
    attachmentAwarePrompt?.let { prompt ->
        takagi.ru.monica.attachments.ui.AttachmentAwareMoveDialog(
            classification = prompt.classification,
            attachmentItemTitles = prompt.titles,
            onConfirm = {
                prompt.response.complete(true)
            },
            onDismiss = {
                prompt.response.complete(false)
            }
        )
    }

    preserveCategoriesPrompt?.let { prompt ->
        PasswordBatchPreserveCategoriesDialog(
            prompt = prompt,
            onDismiss = { preserveCategoriesPrompt = null }
        )
    }

	keepassFailurePrompt?.let { prompt ->
		KeePassBatchTransferFailureDialog(
			prompt = prompt,
			onDismiss = { keepassFailurePrompt = null },
		)
	}
}

@Composable
private fun KeePassBatchTransferFailureDialog(
    prompt: KeePassBatchTransferFailurePrompt,
    onDismiss: () -> Unit,
) {
    val failure = prompt.failure
    val message = when (failure.kind) {
        SyncErrorKind.CONFLICT -> stringResource(
            R.string.password_batch_keepass_failure_conflict,
            prompt.targetLabel,
            failure.failedCount,
        )
        SyncErrorKind.NETWORK_UNAVAILABLE,
        SyncErrorKind.REMOTE_UNAVAILABLE,
        SyncErrorKind.RATE_LIMITED -> stringResource(
            R.string.password_batch_keepass_failure_network,
            prompt.targetLabel,
            failure.failedCount,
        )
        SyncErrorKind.AUTH_REQUIRED -> stringResource(
            R.string.password_batch_keepass_failure_auth,
            prompt.targetLabel,
            failure.failedCount,
        )
        SyncErrorKind.PERMISSION_DENIED -> stringResource(
            R.string.password_batch_keepass_failure_permission,
            prompt.targetLabel,
            failure.failedCount,
        )
        SyncErrorKind.TARGET_LOCKED -> stringResource(
            R.string.password_batch_keepass_failure_locked,
            prompt.targetLabel,
            failure.failedCount,
        )
        else -> stringResource(
            R.string.password_batch_keepass_failure_unknown,
            prompt.targetLabel,
            failure.failedCount,
            failure.detail,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.password_batch_keepass_failure_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        },
    )
}

/** 附件感知批量移动弹窗挂起状态。 */
private data class AttachmentAwareMovePrompt(
    val classification: takagi.ru.monica.attachments.facade.AttachmentBatchMoveAdvisor.Classification,
    val titles: List<String>,
    val response: kotlinx.coroutines.CompletableDeferred<Boolean>
)

private suspend fun PasswordViewModel.addPasswordEntryWithResultAwait(
    entry: PasswordEntry
): Long? {
    val deferred = CompletableDeferred<Long?>()
    addPasswordEntryWithResult(
        entry = entry,
        includeDetailedLog = false,
        // batch copy / cross-container copy 的 entry.password 是源条目的已加密密文，
        // 不能再经一次 encryptData，否则存进去解不出来（KeePass → Bitwarden 常态）
        passwordAlreadyEncrypted = true,
        // batch copy 的 target 已经在 buildCopiedEntryForTarget 里明确指定；不能再被当前 UI
        // categoryFilter 二次绑定（否则 KeePass 视图下复制到 Bitwarden 会被强塞
        // keepassDatabaseId，触发 ownership conflict 直接 block）
        skipCategoryBinding = true
    ) { createdId ->
        deferred.complete(createdId)
    }
    return deferred.await()
}
