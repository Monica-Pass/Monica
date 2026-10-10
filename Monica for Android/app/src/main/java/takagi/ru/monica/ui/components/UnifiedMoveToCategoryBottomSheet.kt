package takagi.ru.monica.ui.components

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import takagi.ru.monica.R
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.KeePassOperationBlockReason
import takagi.ru.monica.data.KeePassStorageLocation
import takagi.ru.monica.data.writeOperationAvailability
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.repository.MdbxStoredFolderEntry
import takagi.ru.monica.utils.KEEPASS_DISPLAY_PATH_SEPARATOR
import takagi.ru.monica.utils.KeePassGroupInfo
import takagi.ru.monica.utils.decodeKeePassPathSegments

sealed interface UnifiedMoveCategoryTarget {
    data object Uncategorized : UnifiedMoveCategoryTarget
    data class MonicaCategory(val categoryId: Long) : UnifiedMoveCategoryTarget
    data class BitwardenVaultTarget(val vaultId: Long) : UnifiedMoveCategoryTarget
    data class BitwardenFolderTarget(val vaultId: Long, val folderId: String) : UnifiedMoveCategoryTarget
    data class KeePassDatabaseTarget(val databaseId: Long) : UnifiedMoveCategoryTarget
    data class KeePassGroupTarget(
        val databaseId: Long,
        val groupPath: String,
        val groupUuid: String? = null,
    ) : UnifiedMoveCategoryTarget
    data class MdbxDatabaseTarget(val databaseId: Long) : UnifiedMoveCategoryTarget
    data class MdbxFolderTarget(val databaseId: Long, val folderId: String) : UnifiedMoveCategoryTarget
}

private sealed interface MovePickerSource {
    val key: String
    val icon: ImageVector

    data object MonicaLocal : MovePickerSource {
        override val key: String = "monica"
        override val icon: ImageVector = Icons.Default.Smartphone
    }

    data class KeePassDatabase(val database: LocalKeePassDatabase) : MovePickerSource {
        override val key: String = "keepass:${database.id}"
        override val icon: ImageVector = Icons.Default.Key
    }

    data class MdbxDatabase(val database: LocalMdbxDatabase) : MovePickerSource {
        override val key: String = "mdbx:${database.id}"
        override val icon: ImageVector = Icons.Default.Storage
    }

    data class BitwardenVaultSource(val vault: BitwardenVault) : MovePickerSource {
        override val key: String = "bitwarden:${vault.id}"
        override val icon: ImageVector = Icons.Default.CloudSync
    }
}

private data class MovePickerTarget(
    val target: UnifiedMoveCategoryTarget,
    val label: String,
    val title: String,
    val icon: ImageVector,
    val supportingText: String? = null,
    val depth: Int = 0,
)

// Sentinel target id for "Archive" in move sheet without introducing a new persisted category.
const val UNIFIED_MOVE_ARCHIVE_SENTINEL_CATEGORY_ID: Long = -9_223_372_036_854_775_000L

enum class UnifiedMoveAction {
    MOVE,
    COPY
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UnifiedMoveToCategoryBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    initialSource: UnifiedMoveInitialSource = UnifiedMoveInitialSource.MonicaLocal,
    categories: List<Category>,
    keepassDatabases: List<LocalKeePassDatabase>,
    mdbxDatabases: List<LocalMdbxDatabase> = emptyList(),
    bitwardenVaults: List<BitwardenVault>,
    getBitwardenFolders: (Long) -> Flow<List<BitwardenFolder>>,
    getKeePassGroups: (Long) -> Flow<List<KeePassGroupInfo>>,
    getMdbxFolders: (Long) -> Flow<List<MdbxStoredFolderEntry>> = { flowOf(emptyList()) },
    refreshMdbxFolders: (Long) -> Unit = {},
    showBitwardenFolderTargets: Boolean = true,
    allowCopy: Boolean = false,
    allowMove: Boolean = true,
    allowArchiveTarget: Boolean = false,
    allowLocalTarget: Boolean = true,
    onBeforeTargetSelected: ((
        target: UnifiedMoveCategoryTarget,
        action: UnifiedMoveAction,
        proceed: () -> Unit
    ) -> Unit)? = null,
    onTargetSelected: (UnifiedMoveCategoryTarget, UnifiedMoveAction) -> Unit
) {
    if (!visible) return

    val selectedAction = remember { mutableStateOf(if (allowMove) UnifiedMoveAction.MOVE else UnifiedMoveAction.COPY) }
    val blockedKeePassOperation = remember { mutableStateOf<KeePassUnavailableMoveState?>(null) }
    val selectedTarget = remember { mutableStateOf<UnifiedMoveCategoryTarget?>(null) }
    val selectedTargetLabel = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(allowMove) {
        if (!allowMove && selectedAction.value == UnifiedMoveAction.MOVE) {
            selectedAction.value = UnifiedMoveAction.COPY
        }
    }

    val sources = remember(keepassDatabases, mdbxDatabases, bitwardenVaults, allowLocalTarget) {
        buildList {
            if (allowLocalTarget) add(MovePickerSource.MonicaLocal)
            keepassDatabases.forEach { add(MovePickerSource.KeePassDatabase(it)) }
            mdbxDatabases.forEach { add(MovePickerSource.MdbxDatabase(it)) }
            bitwardenVaults.forEach { add(MovePickerSource.BitwardenVaultSource(it)) }
        }
    }
    val sourceKeys = sources.mapTo(linkedSetOf()) { it.key }
    val activeSourceKey = remember(initialSource) {
        mutableStateOf(resolveUnifiedMoveInitialSourceKey(initialSource, sourceKeys))
    }
    val hasManualSourceSelection = remember(initialSource) { mutableStateOf(false) }
    LaunchedEffect(initialSource, sourceKeys) {
        val resolvedInitialSourceKey = resolveUnifiedMoveInitialSourceKey(initialSource, sourceKeys)
        if (!hasManualSourceSelection.value && activeSourceKey.value != resolvedInitialSourceKey) {
            activeSourceKey.value = resolvedInitialSourceKey
            selectedTarget.value = null
            selectedTargetLabel.value = null
        } else if (activeSourceKey.value !in sourceKeys) {
            activeSourceKey.value = sources.firstOrNull()?.key ?: MovePickerSource.MonicaLocal.key
            selectedTarget.value = null
            selectedTargetLabel.value = null
        }
    }

    val localKeePassDatabases = keepassDatabases
    val monicaCategoryNodes = remember(categories) { buildMonicaCategoryNodes(categories) }
    val categoryNoneLabel = stringResource(R.string.category_none)
    val bitwardenRootLabel = stringResource(R.string.folder_no_folder_root)
    val keepassRootLabel = stringResource(R.string.storage_picker_keepass_root)
    val monicaLabel = stringResource(R.string.filter_monica)
    val archiveLabel = stringResource(R.string.archive_page_title)
    val confirmLabel = stringResource(R.string.confirm)
    val externalStorageLabel = stringResource(R.string.external_storage)
    val internalStorageLabel = stringResource(R.string.internal_storage)
    val defaultLabel = stringResource(R.string.default_label)
    val keepassUnavailableFormat = stringResource(R.string.keepass_connection_status_unavailable_format)
    val keepassMissingLabel = stringResource(R.string.keepass_connection_status_missing)
    val keepassNeedsRefreshLabel = stringResource(R.string.keepass_connection_status_needs_refresh)
    val keepassSyncingLabel = stringResource(R.string.keepass_connection_status_syncing)
    val keepassConflictLabel = stringResource(R.string.keepass_connection_status_conflict)
    val keepassFailedLabel = stringResource(R.string.keepass_connection_status_failed)
    val actionLabel = if (selectedAction.value == UnifiedMoveAction.COPY) {
        stringResource(R.string.copy)
    } else {
        stringResource(R.string.move)
    }

    fun labelForSource(source: MovePickerSource): String {
        return when (source) {
            MovePickerSource.MonicaLocal -> monicaLabel
            is MovePickerSource.KeePassDatabase -> source.database.name
            is MovePickerSource.MdbxDatabase -> source.database.name
            is MovePickerSource.BitwardenVaultSource -> source.vault.displayName ?: source.vault.email
        }
    }

    fun keepassReasonLabel(reason: KeePassOperationBlockReason?): String {
        return when (reason) {
            KeePassOperationBlockReason.MISSING_DATABASE -> keepassMissingLabel
            KeePassOperationBlockReason.NEEDS_REFRESH -> keepassNeedsRefreshLabel
            KeePassOperationBlockReason.SYNCING -> keepassSyncingLabel
            KeePassOperationBlockReason.CONFLICT -> keepassConflictLabel
            KeePassOperationBlockReason.FAILED -> keepassFailedLabel
            null -> keepassNeedsRefreshLabel
        }
    }

    fun supportingLabelForSource(source: MovePickerSource): String? {
        return when (source) {
            MovePickerSource.MonicaLocal -> null
            is MovePickerSource.KeePassDatabase -> {
                val availability = source.database.writeOperationAvailability()
                if (availability.canOperate) {
                    if (source.database.storageLocation == KeePassStorageLocation.EXTERNAL) {
                        externalStorageLabel
                    } else {
                        internalStorageLabel
                    }
                } else {
                    keepassUnavailableFormat.format(keepassReasonLabel(availability.reason))
                }
            }
            is MovePickerSource.MdbxDatabase -> when (source.database.sourceTypeEnum) {
                MdbxSourceType.LOCAL_INTERNAL -> internalStorageLabel
                MdbxSourceType.LOCAL_EXTERNAL -> externalStorageLabel
                MdbxSourceType.REMOTE_WEBDAV -> "WebDAV"
                MdbxSourceType.REMOTE_ONEDRIVE -> "OneDrive"
            }
            is MovePickerSource.BitwardenVaultSource -> if (source.vault.isDefault) defaultLabel else source.vault.email
        }
    }

    fun stageTarget(
        target: UnifiedMoveCategoryTarget,
        label: String
    ) {
        selectedTarget.value = target
        selectedTargetLabel.value = label
    }
    fun selectKeePassTarget(
        database: LocalKeePassDatabase,
        target: UnifiedMoveCategoryTarget,
        label: String
    ) {
        val availability = database.writeOperationAvailability()
        if (availability.canOperate) {
            stageTarget(target, label)
        } else {
            blockedKeePassOperation.value = KeePassUnavailableMoveState(
                databaseName = database.name,
                reason = availability.reason ?: KeePassOperationBlockReason.NEEDS_REFRESH
            )
        }
    }
    fun stageRootTargetForSource(source: MovePickerSource) {
        when (source) {
            MovePickerSource.MonicaLocal -> stageTarget(
                target = UnifiedMoveCategoryTarget.Uncategorized,
                label = "$monicaLabel / $categoryNoneLabel"
            )
            is MovePickerSource.KeePassDatabase -> selectKeePassTarget(
                database = source.database,
                target = UnifiedMoveCategoryTarget.KeePassDatabaseTarget(source.database.id),
                label = "${source.database.name} / $keepassRootLabel"
            )
            is MovePickerSource.MdbxDatabase -> stageTarget(
                target = UnifiedMoveCategoryTarget.MdbxDatabaseTarget(source.database.id),
                label = "${source.database.name} / $categoryNoneLabel"
            )
            is MovePickerSource.BitwardenVaultSource -> stageTarget(
                target = UnifiedMoveCategoryTarget.BitwardenVaultTarget(source.vault.id),
                label = "${source.vault.email} / $bitwardenRootLabel"
            )
        }
    }
    fun confirmSelectedTarget() {
        val target = selectedTarget.value ?: return
        val keepassDatabaseId = when (target) {
            is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> target.databaseId
            is UnifiedMoveCategoryTarget.KeePassGroupTarget -> target.databaseId
            else -> null
        }
        if (keepassDatabaseId != null) {
            val database = localKeePassDatabases.firstOrNull { it.id == keepassDatabaseId }
            val availability = database?.writeOperationAvailability()
            if (database == null || availability?.canOperate != true) {
                blockedKeePassOperation.value = KeePassUnavailableMoveState(
                    databaseName = database?.name ?: keepassRootLabel,
                    reason = availability?.reason ?: KeePassOperationBlockReason.NEEDS_REFRESH
                )
                return
            }
        }
        val action = selectedAction.value
        val proceed = { onTargetSelected(target, action) }
        onBeforeTargetSelected?.invoke(target, action, proceed) ?: proceed()
    }

    val activeSource = sources.firstOrNull { it.key == activeSourceKey.value }
        ?: sources.firstOrNull()
        ?: MovePickerSource.MonicaLocal
    val activeMdbxDatabaseId = (activeSource as? MovePickerSource.MdbxDatabase)?.database?.id
    LaunchedEffect(activeMdbxDatabaseId) {
        activeMdbxDatabaseId?.let(refreshMdbxFolders)
    }
    val bitwardenFolders by remember(activeSource.key, showBitwardenFolderTargets) {
        if (activeSource is MovePickerSource.BitwardenVaultSource && showBitwardenFolderTargets) {
            getBitwardenFolders(activeSource.vault.id)
        } else {
            flowOf(emptyList())
        }
    }.collectAsState(initial = emptyList())
    val keepassGroups by remember(activeSource.key, showBitwardenFolderTargets) {
        if (activeSource is MovePickerSource.KeePassDatabase) {
            getKeePassGroups(activeSource.database.id)
        } else {
            flowOf(emptyList())
        }
    }.collectAsState(initial = emptyList())
    val mdbxFolders by remember(activeSource.key, showBitwardenFolderTargets) {
        if (activeSource is MovePickerSource.MdbxDatabase) {
            getMdbxFolders(activeSource.database.id)
        } else {
            flowOf(emptyList())
        }
    }.collectAsState(initial = emptyList())
    val keepassGroupNodes = remember(keepassGroups) { buildKeePassGroupNodes(keepassGroups) }
    val mdbxFolderNodes = remember(mdbxFolders) { buildMdbxFolderNodes(mdbxFolders) }

    fun buildTargetsForSource(source: MovePickerSource): List<MovePickerTarget> {
        return when (source) {
            MovePickerSource.MonicaLocal -> buildList {
                if (allowArchiveTarget) {
                    add(
                        MovePickerTarget(
                            target = UnifiedMoveCategoryTarget.MonicaCategory(UNIFIED_MOVE_ARCHIVE_SENTINEL_CATEGORY_ID),
                            label = "$monicaLabel / $archiveLabel",
                            title = archiveLabel,
                            icon = Icons.Default.Archive
                        )
                    )
                }
                add(
                    MovePickerTarget(
                        target = UnifiedMoveCategoryTarget.Uncategorized,
                        label = "$monicaLabel / $categoryNoneLabel",
                        title = categoryNoneLabel,
                        icon = Icons.Default.FolderOff
                    )
                )
                monicaCategoryNodes.forEach { node ->
                    add(
                        MovePickerTarget(
                            target = UnifiedMoveCategoryTarget.MonicaCategory(node.category.id),
                            label = listOfNotNull(monicaLabel, node.parentPathLabel, node.displayName)
                                .joinToString(" / "),
                            title = node.displayName,
                            icon = Icons.Default.Folder,
                            supportingText = node.parentPathLabel,
                            depth = node.depth
                        )
                    )
                }
            }

            is MovePickerSource.KeePassDatabase -> buildList {
                add(
                    MovePickerTarget(
                        target = UnifiedMoveCategoryTarget.KeePassDatabaseTarget(source.database.id),
                        label = "${source.database.name} / $keepassRootLabel",
                        title = keepassRootLabel,
                        icon = Icons.Default.FolderOff
                    )
                )
                keepassGroupNodes.forEach { groupNode ->
                    add(
                        MovePickerTarget(
                            target = UnifiedMoveCategoryTarget.KeePassGroupTarget(
                                databaseId = source.database.id,
                                groupPath = groupNode.group.path,
                                groupUuid = groupNode.group.uuid,
                            ),
                            label = listOfNotNull(
                                source.database.name,
                                groupNode.parentPathLabel,
                                groupNode.displayName
                            ).joinToString(" / "),
                            title = groupNode.displayName,
                            icon = Icons.Default.Folder,
                            supportingText = groupNode.parentPathLabel,
                            depth = groupNode.depth
                        )
                    )
                }
            }

            is MovePickerSource.MdbxDatabase -> buildList {
                add(
                    MovePickerTarget(
                        target = UnifiedMoveCategoryTarget.MdbxDatabaseTarget(source.database.id),
                        label = "${source.database.name} / $categoryNoneLabel",
                        title = categoryNoneLabel,
                        icon = Icons.Default.FolderOff
                    )
                )
                mdbxFolderNodes.forEach { folderNode ->
                    add(
                        MovePickerTarget(
                            target = UnifiedMoveCategoryTarget.MdbxFolderTarget(
                                databaseId = source.database.id,
                                folderId = folderNode.folder.folderId
                            ),
                            label = listOfNotNull(
                                source.database.name,
                                folderNode.parentPathLabel,
                                folderNode.displayName
                            ).joinToString(" / "),
                            title = folderNode.displayName,
                            icon = Icons.Default.Folder,
                            supportingText = folderNode.parentPathLabel,
                            depth = folderNode.depth
                        )
                    )
                }
            }

            is MovePickerSource.BitwardenVaultSource -> buildList {
                add(
                    MovePickerTarget(
                        target = UnifiedMoveCategoryTarget.BitwardenVaultTarget(source.vault.id),
                        label = "${source.vault.email} / $bitwardenRootLabel",
                        title = bitwardenRootLabel,
                        icon = Icons.Default.FolderOff
                    )
                )
                if (showBitwardenFolderTargets) {
                    bitwardenFolders.forEach { folder ->
                        add(
                            MovePickerTarget(
                                target = UnifiedMoveCategoryTarget.BitwardenFolderTarget(
                                    vaultId = source.vault.id,
                                    folderId = folder.bitwardenFolderId
                                ),
                                label = "${source.vault.email} / ${folder.name}",
                                title = folder.name,
                                icon = Icons.Default.Folder
                            )
                        )
                    }
                }
            }
        }
    }

    val activeTargets = buildTargetsForSource(activeSource)

    blockedKeePassOperation.value?.let { blocked ->
        val reason = keepassBlockReasonLabel(blocked.reason)
        AlertDialog(
            onDismissRequest = { blockedKeePassOperation.value = null },
            title = { Text(stringResource(R.string.keepass_operation_unavailable_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.keepass_operation_unavailable_message,
                        blocked.databaseName,
                        reason
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { blockedKeePassOperation.value = null }) {
                    Text(stringResource(R.string.keepass_operation_refresh_hint))
                }
            }
        )
    }

    MonicaModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (selectedAction.value == UnifiedMoveAction.COPY) {
                        stringResource(R.string.copy)
                    } else {
                        stringResource(R.string.move_to_category)
                    },
                    style = MaterialTheme.typography.titleLarge
                )
            }
            if (allowCopy) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    if (allowMove) {
                        FilterChip(
                            selected = selectedAction.value == UnifiedMoveAction.MOVE,
                            onClick = { selectedAction.value = UnifiedMoveAction.MOVE },
                            label = { Text(text = stringResource(R.string.move)) }
                        )
                    }
                    FilterChip(
                        modifier = Modifier.testTag("transfer_action_copy"),
                        selected = selectedAction.value == UnifiedMoveAction.COPY,
                        onClick = { selectedAction.value = UnifiedMoveAction.COPY },
                        label = { Text(text = stringResource(R.string.copy)) }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    MoveSelectorSectionTitle(text = stringResource(R.string.category_selection_menu_databases))
                }
                item {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        sources.forEach { source ->
                            MonicaExpressiveFilterChip(
                                selected = source.key == activeSourceKey.value,
                                onClick = {
                                    hasManualSourceSelection.value = true
                                    activeSourceKey.value = source.key
                                    selectedTarget.value = null
                                    selectedTargetLabel.value = null
                                    stageRootTargetForSource(source)
                                },
                                label = labelForSource(source),
                                leadingIcon = source.icon
                            )
                        }
                    }
                }
                item {
                    MoveSelectorSectionTitle(text = stringResource(R.string.category_selection_menu_folders))
                }
                if (activeTargets.isEmpty()) {
                    item {
                        Text(
                            text = categoryNoneLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                        )
                    }
                } else {
                    items(activeTargets) { target ->
                        MoveTargetItem(
                            title = target.title,
                            icon = target.icon,
                            depth = target.depth,
                            supportingText = target.supportingText
                                ?: supportingLabelForSource(activeSource),
                            selected = selectedTarget.value == target.target,
                            onClick = {
                                if (activeSource is MovePickerSource.KeePassDatabase) {
                                    selectKeePassTarget(
                                        database = activeSource.database,
                                        target = target.target,
                                        label = target.label
                                    )
                                } else {
                                    stageTarget(target.target, target.label)
                                }
                            }
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.legacy_ui_target_label, selectedTargetLabel.value ?: stringResource(R.string.legacy_ui_choose_folder)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selectedTarget.value == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    FilledTonalButton(
                        onClick = ::confirmSelectedTarget,
                        enabled = selectedTarget.value != null,
                        modifier = Modifier.testTag("transfer_confirm").fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("$confirmLabel$actionLabel")
                    }
                }
            }
        }
    }
}

private data class KeePassUnavailableMoveState(
    val databaseName: String,
    val reason: KeePassOperationBlockReason
)

@Composable
private fun MoveSelectorSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun MoveTargetItem(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    supportingText: String? = null,
    selected: Boolean = false,
    depth: Int = 0,
    menu: (@Composable () -> Unit)? = null
) {
    val safeDepth = depth.coerceIn(0, 8)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (safeDepth * 20).dp)
            .clipToBounds()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (safeDepth > 0) {
            Box(
                modifier = Modifier
                    .padding(start = 6.dp, end = 8.dp)
                    .width(1.dp)
                    .height(42.dp)
                    .alpha(0.45f)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        } else {
            Spacer(modifier = Modifier.width(8.dp))
        }
        ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = ListItemDefaults.colors(
                containerColor = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.34f)
                },
                headlineColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                leadingIconColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
                trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            leadingContent = { Icon(icon, contentDescription = null) },
            headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
            supportingContent = if (supportingText.isNullOrBlank()) null else {
                { Text(supportingText, style = MaterialTheme.typography.labelSmall) }
            },
            trailingContent = menu ?: if (selected) {
                { Icon(Icons.Default.Check, contentDescription = null) }
            } else null
        )
    }
}

private data class MonicaCategoryNode(
    val category: Category,
    val displayName: String,
    val depth: Int,
    val parentPathLabel: String?
)

private data class KeePassGroupNode(
    val group: KeePassGroupInfo,
    val displayName: String,
    val depth: Int,
    val parentPathLabel: String?
)

private data class MdbxFolderNode(
    val folder: MdbxStoredFolderEntry,
    val displayName: String,
    val depth: Int,
    val parentPathLabel: String?
)

private fun buildMonicaCategoryNodes(categories: List<Category>): List<MonicaCategoryNode> {
    return categories
        .sortedBy { it.name.lowercase() }
        .map { category ->
            val segments = splitPathSegments(category.name)
            if (segments.isEmpty()) {
                MonicaCategoryNode(
                    category = category,
                    displayName = category.name,
                    depth = 0,
                    parentPathLabel = null
                )
            } else {
                MonicaCategoryNode(
                    category = category,
                    displayName = segments.last(),
                    depth = (segments.size - 1).coerceAtLeast(0),
                    parentPathLabel = segments.dropLast(1)
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(" / ")
                )
            }
        }
}

private fun buildKeePassGroupNodes(groups: List<KeePassGroupInfo>): List<KeePassGroupNode> {
    return groups
        .sortedBy { it.displayPath.lowercase() }
        .map { group ->
            val pathSegments = decodeKeePassPathSegments(group.path)
            val display = pathSegments.lastOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: group.name.ifBlank { group.displayPath }
            val parentPath = pathSegments
                .dropLast(1)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(KEEPASS_DISPLAY_PATH_SEPARATOR)
            KeePassGroupNode(
                group = group,
                displayName = display.ifBlank { group.path },
                depth = group.depth.coerceAtLeast(0),
                parentPathLabel = parentPath
            )
        }
}

private fun buildMdbxFolderNodes(folders: List<MdbxStoredFolderEntry>): List<MdbxFolderNode> {
    val validFolders = folders
        .filter { it.folderId.isNotBlank() }
        .distinctBy { it.folderId }
    if (validFolders.isEmpty()) return emptyList()

    val folderById = validFolders.associateBy { it.folderId }
    val childrenByParent = validFolders.groupBy { it.parentFolderId.normalizedMdbxParentIdForMoveSheet() }
    val result = mutableListOf<MdbxFolderNode>()

    fun appendChildren(parentId: String?, parentNames: List<String>, depth: Int, seen: Set<String>) {
        childrenByParent[parentId]
            .orEmpty()
            .sortedWith(compareBy<MdbxStoredFolderEntry>({ it.name.lowercase() }, { it.folderId }))
            .forEach { folder ->
                if (folder.folderId in seen) return@forEach
                val name = folder.name.ifBlank { "Folder ${folder.folderId.take(8)}" }
                result += MdbxFolderNode(
                    folder = folder,
                    displayName = name,
                    depth = depth,
                    parentPathLabel = parentNames.takeIf { it.isNotEmpty() }?.joinToString(" / ")
                )
                appendChildren(
                    parentId = folder.folderId,
                    parentNames = parentNames + name,
                    depth = depth + 1,
                    seen = seen + folder.folderId
                )
            }
    }

    appendChildren(parentId = null, parentNames = emptyList(), depth = 0, seen = emptySet())
    validFolders
        .filterNot { folder -> result.any { it.folder.folderId == folder.folderId } }
        .sortedWith(compareBy<MdbxStoredFolderEntry>({ it.name.lowercase() }, { it.folderId }))
        .forEach { folder ->
            val parentNames = generateSequence(folder.parentFolderId.normalizedMdbxParentIdForMoveSheet()) { parentId ->
                folderById[parentId]?.parentFolderId.normalizedMdbxParentIdForMoveSheet()
            }
                .take(16)
                .toList()
                .asReversed()
                .mapNotNull { parentId -> folderById[parentId]?.name?.takeIf { it.isNotBlank() } }
            result += MdbxFolderNode(
                folder = folder,
                displayName = folder.name.ifBlank { "Folder ${folder.folderId.take(8)}" },
                depth = parentNames.size,
                parentPathLabel = parentNames.takeIf { it.isNotEmpty() }?.joinToString(" / ")
            )
        }
    return result
}

private fun String?.normalizedMdbxParentIdForMoveSheet(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return if (value.equals("root", ignoreCase = true)) null else value
}

private fun splitPathSegments(path: String): List<String> {
    return path
        .split('/')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map(::decodePathSegmentForDisplay)
}

private fun decodePathSegmentForDisplay(segment: String): String {
    val decoded = runCatching { Uri.decode(segment).trim() }
        .getOrNull()
        .orEmpty()
    return decoded.ifBlank { segment }
}
