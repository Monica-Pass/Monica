package takagi.ru.monica.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.KeePassOperationBlockReason
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.isUsable
import takagi.ru.monica.data.isMonicaLocalCategory
import takagi.ru.monica.data.writeOperationAvailability
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.normalizedStorageTargets
import takagi.ru.monica.data.model.withStorageTargetSelected
import takagi.ru.monica.data.model.withoutStorageTarget
import takagi.ru.monica.repository.MdbxStoredFolderEntry
import takagi.ru.monica.utils.KeePassGroupInfo
import takagi.ru.monica.utils.buildLocalCategoryPathOptions
import takagi.ru.monica.utils.decodeKeePassPathForDisplay
import takagi.ru.monica.utils.localCategoryHierarchyLabel
import takagi.ru.monica.R

private enum class StoragePickerSelectionMode {
    SINGLE,
    MULTI
}

private sealed interface StoragePickerSource {
    val key: String
    val icon: ImageVector

    data object MonicaLocal : StoragePickerSource {
        override val key: String = "monica"
        override val icon: ImageVector = Icons.Default.Shield
    }

    data class KeePassDatabase(val database: LocalKeePassDatabase) : StoragePickerSource {
        override val key: String = "keepass:${database.id}"
        override val icon: ImageVector = Icons.Default.Key
    }

    data class MdbxDatabase(val database: LocalMdbxDatabase) : StoragePickerSource {
        override val key: String = "mdbx:${database.id}"
        override val icon: ImageVector = Icons.Default.Storage
    }

    data class BitwardenVaultSource(val vault: BitwardenVault) : StoragePickerSource {
        override val key: String = "bitwarden:${vault.id}"
        override val icon: ImageVector = Icons.Default.Cloud
    }
}

private data class StorageTargetChip(
    val target: StorageTarget,
    val label: String,
    val icon: ImageVector,
    val sourceKey: String
)

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun MultiStorageTargetPickerBottomSheet(
    visible: Boolean,
    selectedTargets: List<StorageTarget>,
    lockedTargetKeys: Set<String>,
    categories: List<Category>,
    keepassDatabases: List<LocalKeePassDatabase>,
    mdbxDatabases: List<LocalMdbxDatabase> = emptyList(),
    bitwardenVaults: List<BitwardenVault>,
    getBitwardenFolders: (Long) -> Flow<List<BitwardenFolder>>,
    getKeePassGroups: (Long) -> Flow<List<KeePassGroupInfo>>,
    getMdbxFolders: (Long) -> Flow<List<MdbxStoredFolderEntry>> = { flowOf(emptyList()) },
    onDismiss: () -> Unit,
    onSelectedTargetsChange: (List<StorageTarget>) -> Unit,
    onTargetClicked: ((StorageTarget) -> Unit)? = null,
    forceMultiSelectionMode: Boolean = false,
    showSelectionModeToggle: Boolean = true,
    showBitwardenFolderTargets: Boolean = true,
    confirmButtonText: String? = null,
    onConfirmSelection: ((List<StorageTarget>) -> Unit)? = null,
    showMonicaLocal: Boolean = true,
) {
    if (!visible) return

    val configuration = LocalConfiguration.current
    val maxSheetHeight = configuration.screenHeightDp.dp * 0.82f
    val minSheetHeight = (configuration.screenHeightDp.dp * 0.45f)
        .coerceIn(300.dp, 430.dp)
    val effectiveMinSheetHeight = if (minSheetHeight > maxSheetHeight) {
        maxSheetHeight
    } else {
        minSheetHeight
    }

    val sources = remember(keepassDatabases, mdbxDatabases, bitwardenVaults, showMonicaLocal) {
        buildList {
            if (showMonicaLocal) add(StoragePickerSource.MonicaLocal)
            keepassDatabases.forEach { add(StoragePickerSource.KeePassDatabase(it)) }
            mdbxDatabases.filter { it.isUsable }.forEach { add(StoragePickerSource.MdbxDatabase(it)) }
            bitwardenVaults.forEach { add(StoragePickerSource.BitwardenVaultSource(it)) }
        }
    }
    if (sources.isEmpty()) return
    val monicaOnlyLabel = stringResource(R.string.vault_monica_only)
    val categoryNoneLabel = stringResource(R.string.category_none)
    val bitwardenRootLabel = stringResource(R.string.folder_no_folder_root)
    val keepassRootLabel = stringResource(R.string.storage_picker_keepass_root)
    val keepassUnavailableFormat = stringResource(R.string.keepass_connection_status_unavailable_format)
    val keepassMissingLabel = stringResource(R.string.keepass_connection_status_missing)
    val keepassNeedsRefreshLabel = stringResource(R.string.keepass_connection_status_needs_refresh)
    val keepassSyncingLabel = stringResource(R.string.keepass_connection_status_syncing)
    val keepassConflictLabel = stringResource(R.string.keepass_connection_status_conflict)
    val keepassFailedLabel = stringResource(R.string.keepass_connection_status_failed)
    val selectedKeys = selectedTargets.map(StorageTarget::stableKey).toSet()
    val selectedSourceKeys = remember(selectedTargets) {
        selectedTargets.map { it.toSourceKey() }.toSet()
    }
    val bitwardenFoldersByVault = mutableMapOf<Long, List<BitwardenFolder>>()
    bitwardenVaults.forEach { vault ->
        val folders by getBitwardenFolders(vault.id).collectAsState(initial = emptyList())
        bitwardenFoldersByVault[vault.id] = folders
    }
    val keepassGroupsByDatabase = mutableMapOf<Long, List<KeePassGroupInfo>>()
    keepassDatabases.forEach { database ->
        val groups by getKeePassGroups(database.id).collectAsState(initial = emptyList())
        keepassGroupsByDatabase[database.id] = groups
    }
    val mdbxFoldersByDatabase = mutableMapOf<Long, List<MdbxStoredFolderEntry>>()
    mdbxDatabases.filter { it.isUsable }.forEach { database ->
        val folders by getMdbxFolders(database.id).collectAsState(initial = emptyList())
        mdbxFoldersByDatabase[database.id] = folders
    }
    val primaryTarget = selectedTargets.firstOrNull() ?: StorageTarget.MonicaLocal(null)
    val primarySourceKey = primaryTarget.toSourceKey().takeIf { key -> sources.any { it.key == key } }
        ?: sources.first().key
    // Existing targets are protected only while editing in multi-select mode.
    // Single-select is the explicit "move" workflow and replaces the target.
    val singleModeAllowed = true
    var selectionMode by remember(visible, selectedTargets, singleModeAllowed) {
        mutableStateOf(
            if (forceMultiSelectionMode) {
                StoragePickerSelectionMode.MULTI
            } else if (!singleModeAllowed) {
                StoragePickerSelectionMode.MULTI
            } else if (selectedTargets.size > 1) {
                StoragePickerSelectionMode.MULTI
            } else {
                StoragePickerSelectionMode.SINGLE
            }
        )
    }
    var singleSourceKey by remember(visible, primarySourceKey) { mutableStateOf(primarySourceKey) }
    val activeSourceKeys = remember(visible, selectedTargets) {
        mutableStateListOf<String>().apply {
            addAll(selectedSourceKeys)
        }
    }

    fun rootTargetForSource(source: StoragePickerSource): StorageTarget {
        return when (source) {
            StoragePickerSource.MonicaLocal -> StorageTarget.MonicaLocal(null)
            is StoragePickerSource.KeePassDatabase -> StorageTarget.KeePass(source.database.id, null)
            is StoragePickerSource.MdbxDatabase -> StorageTarget.Mdbx(source.database.id)
            is StoragePickerSource.BitwardenVaultSource -> StorageTarget.Bitwarden(source.vault.id, null)
        }
    }

    fun labelForSource(source: StoragePickerSource): String {
        return when (source) {
            StoragePickerSource.MonicaLocal -> monicaOnlyLabel
            is StoragePickerSource.KeePassDatabase -> {
                val availability = source.database.writeOperationAvailability()
                if (availability.canOperate) {
                    source.database.name
                } else {
                    val reason = when (availability.reason) {
                        KeePassOperationBlockReason.MISSING_DATABASE -> keepassMissingLabel
                        KeePassOperationBlockReason.NEEDS_REFRESH -> keepassNeedsRefreshLabel
                        KeePassOperationBlockReason.SYNCING -> keepassSyncingLabel
                        KeePassOperationBlockReason.CONFLICT -> keepassConflictLabel
                        KeePassOperationBlockReason.FAILED -> keepassFailedLabel
                        null -> keepassNeedsRefreshLabel
                    }
                    "${source.database.name} · ${keepassUnavailableFormat.format(reason)}"
                }
            }
            is StoragePickerSource.MdbxDatabase -> source.database.name
            is StoragePickerSource.BitwardenVaultSource -> source.vault.displayName ?: source.vault.email
        }
    }

    fun statusDotColorForSource(source: StoragePickerSource): Color? {
        return when (source) {
            StoragePickerSource.MonicaLocal -> null
            is StoragePickerSource.KeePassDatabase -> {
                if (source.database.writeOperationAvailability().canOperate) StorageHealthyGreen else null
            }
            is StoragePickerSource.MdbxDatabase -> StorageHealthyGreen
            is StoragePickerSource.BitwardenVaultSource -> {
                if (source.vault.hasHealthyConnection()) StorageHealthyGreen else null
            }
        }
    }

    fun sourceByKey(key: String): StoragePickerSource {
        return sources.firstOrNull { it.key == key } ?: sources.first()
    }

    fun buildTargetsForSource(source: StoragePickerSource): List<StorageTargetChip> {
        return when (source) {
            StoragePickerSource.MonicaLocal -> buildList {
                add(
                    StorageTargetChip(
                        target = StorageTarget.MonicaLocal(null),
                        label = categoryNoneLabel,
                        icon = Icons.Default.FolderOff,
                        sourceKey = source.key
                    )
                )
                buildLocalCategoryPathOptions(
                    categories.filter(Category::isMonicaLocalCategory),
                    includeVirtualParents = false
                ).forEach { option ->
                    val category = option.category ?: return@forEach
                    add(
                        StorageTargetChip(
                            target = StorageTarget.MonicaLocal(category.id),
                            label = localCategoryHierarchyLabel(option.path),
                            icon = Icons.Default.Folder,
                            sourceKey = source.key
                        )
                    )
                }
            }

            is StoragePickerSource.KeePassDatabase -> buildList {
                add(
                    StorageTargetChip(
                        target = StorageTarget.KeePass(source.database.id, null),
                        label = keepassRootLabel,
                        icon = Icons.Default.FolderOff,
                        sourceKey = source.key
                    )
                )
                keepassGroupsByDatabase[source.database.id].orEmpty().forEach { group ->
                    add(
                        StorageTargetChip(
                            target = StorageTarget.KeePass(source.database.id, group.path),
                            label = decodeKeePassPathForDisplay(group.path),
                            icon = Icons.Default.Folder,
                            sourceKey = source.key
                        )
                    )
                }
            }

            is StoragePickerSource.MdbxDatabase -> buildList {
                add(
                    StorageTargetChip(
                        target = StorageTarget.Mdbx(source.database.id),
                        label = categoryNoneLabel,
                        icon = Icons.Default.FolderOff,
                        sourceKey = source.key
                    )
                )
                val folders = mdbxFoldersByDatabase[source.database.id].orEmpty()
                folders
                    .filter { it.folderId.isNotBlank() }
                    .distinctBy { it.folderId }
                    .sortedWith(compareBy<MdbxStoredFolderEntry>({ mdbxFolderDisplayLabel(it, folders).lowercase() }, { it.folderId }))
                    .forEach { folder ->
                        add(
                            StorageTargetChip(
                                target = StorageTarget.Mdbx(source.database.id, folder.folderId),
                                label = mdbxFolderDisplayLabel(folder, folders),
                                icon = Icons.Default.Folder,
                                sourceKey = source.key
                            )
                        )
                    }
            }

            is StoragePickerSource.BitwardenVaultSource -> buildList {
                add(
                    StorageTargetChip(
                        target = StorageTarget.Bitwarden(source.vault.id, null),
                        label = bitwardenRootLabel,
                        icon = Icons.Default.FolderOff,
                        sourceKey = source.key
                    )
                )
                if (showBitwardenFolderTargets) {
                    bitwardenFoldersByVault[source.vault.id].orEmpty().forEach { folder ->
                        add(
                            StorageTargetChip(
                                target = StorageTarget.Bitwarden(source.vault.id, folder.bitwardenFolderId),
                                label = folder.name,
                                icon = Icons.Default.Folder,
                                sourceKey = source.key
                            )
                        )
                    }
                }
            }
        }
    }

    fun updateSingleSource(sourceKey: String) {
        if (!singleModeAllowed) return
        singleSourceKey = sourceKey
        val source = sourceByKey(sourceKey)
        onSelectedTargetsChange(listOf(rootTargetForSource(source)))
    }

    fun toggleMultiSource(sourceKey: String) {
        val source = sourceByKey(sourceKey)
        val rootTarget = rootTargetForSource(source)
        if (sourceKey in activeSourceKeys) {
            if (selectedTargets.any { it.toSourceKey() == sourceKey && it.stableKey in lockedTargetKeys }) {
                return
            }
            activeSourceKeys.remove(sourceKey)
            onSelectedTargetsChange(
                selectedTargets
                    .filterNot { it.toSourceKey() == sourceKey }
                    .normalizedStorageTargets()
            )
        } else {
            activeSourceKeys.add(sourceKey)
            onSelectedTargetsChange(
                selectedTargets.withStorageTargetSelected(rootTarget)
            )
        }
    }

    fun updateSelectionMode(newMode: StoragePickerSelectionMode) {
        if (forceMultiSelectionMode) return
        if (selectionMode == newMode) return
        if (newMode == StoragePickerSelectionMode.SINGLE && !singleModeAllowed) return
        selectionMode = newMode
        if (newMode == StoragePickerSelectionMode.SINGLE) {
            val retained = selectedTargets.firstOrNull { target -> sources.any { it.key == target.toSourceKey() } }
                ?: rootTargetForSource(sourceByKey(primarySourceKey))
            singleSourceKey = retained.toSourceKey()
            onSelectedTargetsChange(listOf(retained))
        } else {
            activeSourceKeys.clear()
            activeSourceKeys.addAll(selectedSourceKeys)
        }
    }

    val folderTargets = remember(
        selectionMode,
        singleSourceKey,
        activeSourceKeys.toList(),
        categories,
        keepassDatabases,
        mdbxDatabases,
        bitwardenVaults,
        bitwardenFoldersByVault,
        keepassGroupsByDatabase,
        mdbxFoldersByDatabase
    ) {
        if (selectionMode == StoragePickerSelectionMode.SINGLE) {
            buildTargetsForSource(sourceByKey(singleSourceKey))
        } else {
            activeSourceKeys
                .map(::sourceByKey)
                .flatMap(::buildTargetsForSource)
                .distinctBy { it.target.stableKey }
        }
    }
    val groupedFolderTargets = remember(
        sources,
        activeSourceKeys.toList(),
        categories,
        keepassDatabases,
        mdbxDatabases,
        bitwardenVaults,
        bitwardenFoldersByVault,
        keepassGroupsByDatabase,
        mdbxFoldersByDatabase
    ) {
        val activeSourceKeySet = activeSourceKeys.toSet()
        sources
            .filter { it.key in activeSourceKeySet }
            .associateWith(::buildTargetsForSource)
    }

    MonicaModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = effectiveMinSheetHeight, max = maxSheetHeight)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.vault_select_storage),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp)
            )

            if (showSelectionModeToggle && !forceMultiSelectionMode) {
                StoragePickerModeToggleGroup(
                    selectionMode = selectionMode,
                    singleEnabled = singleModeAllowed,
                    onModeSelected = ::updateSelectionMode
                )
            }

            if (lockedTargetKeys.isNotEmpty()) {
                Text(
                    text = stringResource(
                        if (selectionMode == StoragePickerSelectionMode.SINGLE) {
                            R.string.storage_picker_edit_single_move_hint
                        } else {
                            R.string.storage_picker_edit_multi_copy_hint
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            StorageSelectorSectionTitle(text = stringResource(R.string.category_selection_menu_databases))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sources.forEach { source ->
                    val selected = if (selectionMode == StoragePickerSelectionMode.SINGLE) {
                        source.key == singleSourceKey
                    } else {
                        source.key in activeSourceKeys
                    }
                    MonicaExpressiveFilterChip(
                        selected = selected,
                        onClick = {
                            if (selectionMode == StoragePickerSelectionMode.SINGLE) {
                                updateSingleSource(source.key)
                            } else {
                                toggleMultiSource(source.key)
                            }
                        },
                        label = labelForSource(source),
                        leadingIcon = source.icon,
                        statusDotColor = statusDotColorForSource(source)
                    )
                }
            }

            StorageSelectorSectionTitle(text = stringResource(R.string.category_selection_menu_folders))
            if (selectionMode == StoragePickerSelectionMode.SINGLE) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    folderTargets.forEach { chip ->
                        FolderTargetChip(
                            chip = chip,
                            selectedTargets = selectedTargets,
                            selectedKeys = selectedKeys,
                            targetLocked = chip.target.stableKey in lockedTargetKeys,
                            sourceHasLockedTarget = selectedTargets.any {
                                it.toSourceKey() == chip.sourceKey &&
                                    it.stableKey in lockedTargetKeys
                            },
                                    singleMode = true,
                                    singleModeAllowed = singleModeAllowed,
                                    onSelectedTargetsChange = onSelectedTargetsChange,
                                    onTargetClicked = onTargetClicked
                                )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    groupedFolderTargets.forEach { (source, chips) ->
                        if (chips.isEmpty()) return@forEach
                        SourceFolderGroup(
                            title = labelForSource(source),
                            icon = source.icon
                        ) {
                            chips.forEach { chip ->
                                FolderTargetChip(
                                    chip = chip,
                                    selectedTargets = selectedTargets,
                                    selectedKeys = selectedKeys,
                                    targetLocked = chip.target.stableKey in lockedTargetKeys,
                                    sourceHasLockedTarget = selectedTargets.any {
                                        it.toSourceKey() == chip.sourceKey &&
                                            it.stableKey in lockedTargetKeys
                                    },
                                    singleMode = false,
                                    singleModeAllowed = singleModeAllowed,
                                    onSelectedTargetsChange = onSelectedTargetsChange,
                                    onTargetClicked = onTargetClicked
                                )
                            }
                        }
                    }
                }
            }

            if (onConfirmSelection != null) {
                FilledTonalButton(
                    onClick = { onConfirmSelection.invoke(selectedTargets) },
                    enabled = selectedTargets.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(confirmButtonText ?: stringResource(R.string.confirm))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StoragePickerModeToggleGroup(
    selectionMode: StoragePickerSelectionMode,
    singleEnabled: Boolean,
    onModeSelected: (StoragePickerSelectionMode) -> Unit
) {
    Row(
        modifier = Modifier.wrapContentWidth(Alignment.Start),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        StoragePickerModeButton(
            label = stringResource(R.string.single_select),
            selected = selectionMode == StoragePickerSelectionMode.SINGLE,
            enabled = singleEnabled,
            position = 0,
            lastIndex = 1,
            onClick = { onModeSelected(StoragePickerSelectionMode.SINGLE) }
        )
        StoragePickerModeButton(
            label = stringResource(R.string.multi_select),
            selected = selectionMode == StoragePickerSelectionMode.MULTI,
            enabled = true,
            position = 1,
            lastIndex = 1,
            onClick = { onModeSelected(StoragePickerSelectionMode.MULTI) }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StoragePickerModeButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    position: Int,
    lastIndex: Int,
    onClick: () -> Unit
) {
    ToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        enabled = enabled,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .sizeIn(minWidth = 56.dp)
            .semantics { role = Role.RadioButton },
        shapes = when (position) {
            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
            lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
        }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

@Composable
private fun SourceFolderGroup(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun FolderTargetChip(
    chip: StorageTargetChip,
    selectedTargets: List<StorageTarget>,
    selectedKeys: Set<String>,
    targetLocked: Boolean,
    sourceHasLockedTarget: Boolean,
    singleMode: Boolean,
    singleModeAllowed: Boolean,
    onSelectedTargetsChange: (List<StorageTarget>) -> Unit,
    onTargetClicked: ((StorageTarget) -> Unit)?
) {
    val targetKey = chip.target.stableKey
    val selected = if (singleMode) {
        selectedTargets.firstOrNull()?.stableKey == targetKey
    } else {
        targetKey in selectedKeys
    }

    MonicaExpressiveFilterChip(
        selected = selected,
        onClick = {
            if (singleMode) {
                onSelectedTargetsChange(listOf(chip.target))
                onTargetClicked?.invoke(chip.target)
            } else {
                val updatedTargets = if (selected) {
                    if (targetLocked) {
                        selectedTargets
                    } else {
                        selectedTargets.withoutStorageTarget(chip.target)
                    }
                } else if (sourceHasLockedTarget) {
                    selectedTargets
                } else {
                    selectedTargets.withStorageTargetSelected(chip.target)
                }
                onSelectedTargetsChange(updatedTargets)
                if (!selected) {
                    onTargetClicked?.invoke(chip.target)
                }
            }
        },
        label = chip.label,
        leadingIcon = chip.icon
    )
}

@Composable
private fun StorageSelectorSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
}

private fun StorageTarget.toSourceKey(): String {
    return when (this) {
        is StorageTarget.MonicaLocal -> "monica"
        is StorageTarget.KeePass -> "keepass:$databaseId"
        is StorageTarget.Mdbx -> "mdbx:$databaseId"
        is StorageTarget.Bitwarden -> "bitwarden:$vaultId"
    }
}

private fun mdbxFolderDisplayLabel(
    folder: MdbxStoredFolderEntry,
    folders: List<MdbxStoredFolderEntry>
): String {
    val folderById = folders
        .filter { it.folderId.isNotBlank() }
        .associateBy { it.folderId }
    val parentNames = generateSequence(folder.parentFolderId.normalizedMdbxParentIdForStoragePicker()) { parentId ->
        folderById[parentId]?.parentFolderId.normalizedMdbxParentIdForStoragePicker()
    }
        .take(16)
        .toList()
        .asReversed()
        .mapNotNull { parentId -> folderById[parentId]?.name?.takeIf { it.isNotBlank() } }
    val name = folder.name.ifBlank { "Folder ${folder.folderId.take(8)}" }
    return (parentNames + name).joinToString("/")
}

private fun String?.normalizedMdbxParentIdForStoragePicker(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return if (value.equals("root", ignoreCase = true)) null else value
}

private val StorageHealthyGreen = Color(0xFF22C55E)

private fun BitwardenVault.hasHealthyConnection(): Boolean {
    return isConnected && !encryptedRefreshToken.isNullOrBlank()
}
