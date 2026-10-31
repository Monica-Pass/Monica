package takagi.ru.monica.ui

import takagi.ru.monica.ui.password.KeePassFileActions

import takagi.ru.monica.utils.AppLocaleStringResolver

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.ui.UnlockVaultDialog
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.CategorySelectionUiMode
import takagi.ru.monica.data.MdbxCapability
import takagi.ru.monica.data.PasswordCardDisplayMode
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.data.PasswordListQuickFilterItem
import takagi.ru.monica.data.supports
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.repository.MdbxStoredFolderEntry
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.components.CreateCategoryDialog
import takagi.ru.monica.ui.components.CreateDialogTarget
import takagi.ru.monica.ui.components.ExpressiveTopBar
import takagi.ru.monica.ui.components.M3IdentityVerifyDialog
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuDropdown
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuOffset
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget
import takagi.ru.monica.utils.BiometricHelper
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.decodeKeePassPathForDisplay
import takagi.ru.monica.utils.planLocalCategoryMove
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.SettingsViewModel
import takagi.ru.monica.ui.password.StackCardMode
import takagi.ru.monica.ui.password.BitwardenClearCacheTopActionsMenuItem
import takagi.ru.monica.ui.password.BitwardenLockTopActionsMenuItem
import takagi.ru.monica.ui.password.BitwardenReunlockTopActionsMenuItem
import takagi.ru.monica.ui.password.BitwardenSyncTopActionsMenuItem
import takagi.ru.monica.ui.password.CommonPasswordTopActionsMenuItems
import takagi.ru.monica.ui.password.KeepassRefreshTopActionsMenuItem
import takagi.ru.monica.ui.password.rememberKeePassFileActions
import takagi.ru.monica.ui.password.KeePassFileMenuItems
import takagi.ru.monica.ui.password.KeePassFileStatus
import takagi.ru.monica.ui.password.KeepassNativeManagerTopActionsMenuItem
import takagi.ru.monica.ui.password.MdbxCommitHistoryTopActionsMenuItem
import takagi.ru.monica.ui.password.MdbxCreateSnapshotTopActionsMenuItem
import takagi.ru.monica.ui.password.MdbxSyncTopActionsMenuItem
import takagi.ru.monica.ui.password.PasswordTopActionsDropdownMenu
import takagi.ru.monica.viewmodel.MdbxViewModel

@Composable
internal fun PasswordListTopSection(
    currentFilter: CategoryFilter,
    categories: List<Category>,
    keepassDatabases: List<takagi.ru.monica.data.LocalKeePassDatabase>,
    mdbxDatabases: List<takagi.ru.monica.data.LocalMdbxDatabase>,
    bitwardenVaults: List<takagi.ru.monica.data.bitwarden.BitwardenVault>,
    viewModel: PasswordViewModel,
    localKeePassViewModel: takagi.ru.monica.viewmodel.LocalKeePassViewModel,
    bitwardenViewModel: BitwardenViewModel,
    mdbxViewModel: MdbxViewModel? = null,
    selectedBitwardenVaultId: Long?,
    selectedKeePassDatabaseId: Long?,
    keePassFileActions: KeePassFileActions?,
    selectedMdbxDatabaseId: Long?,
    selectedMdbxFolders: List<MdbxStoredFolderEntry>,
    isTopBarSyncing: Boolean,
    isArchiveView: Boolean,
    isKeePassDatabaseView: Boolean,
    searchQuery: String,
    isSearchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    topActionsMenuExpanded: Boolean,
    onTopActionsMenuExpandedChange: (Boolean) -> Unit,
    showStandaloneSettingsEntry: Boolean,
    onOpenStandaloneSettings: () -> Unit,
    isCategorySheetVisible: Boolean,
    onCategorySheetVisibleChange: (Boolean) -> Unit,
    categoryPillBoundsInWindow: androidx.compose.ui.geometry.Rect?,
    onCategoryPillBoundsChange: (androidx.compose.ui.geometry.Rect?) -> Unit,
    showDisplayOptionsSheet: Boolean,
    onShowDisplayOptionsSheetChange: (Boolean) -> Unit,
    configuredQuickFilterItems: List<PasswordListQuickFilterItem>,
    quickFilterFavorite: Boolean,
    onQuickFilterFavoriteChange: (Boolean) -> Unit,
    quickFilter2fa: Boolean,
    onQuickFilter2faChange: (Boolean) -> Unit,
    quickFilterNotes: Boolean,
    onQuickFilterNotesChange: (Boolean) -> Unit,
    quickFilterPasskey: Boolean,
    onQuickFilterPasskeyChange: (Boolean) -> Unit,
    quickFilterBoundNote: Boolean,
    onQuickFilterBoundNoteChange: (Boolean) -> Unit,
    quickFilterAttachments: Boolean,
    onQuickFilterAttachmentsChange: (Boolean) -> Unit,
    quickFilterUncategorized: Boolean,
    onQuickFilterUncategorizedChange: (Boolean) -> Unit,
    quickFilterLocalOnly: Boolean,
    onQuickFilterLocalOnlyChange: (Boolean) -> Unit,
    quickFilterManualStackOnly: Boolean,
    onQuickFilterManualStackOnlyChange: (Boolean) -> Unit,
    quickFilterNeverStack: Boolean,
    onQuickFilterNeverStackChange: (Boolean) -> Unit,
    quickFilterUnstacked: Boolean,
    onQuickFilterUnstackedChange: (Boolean) -> Unit,
    aggregateSelectedTypes: Set<PasswordPageContentType>,
    aggregateVisibleTypes: List<PasswordPageContentType>,
    onToggleAggregateType: (PasswordPageContentType) -> Unit,
    categoryMenuQuickFolderShortcuts: List<PasswordQuickFolderShortcut>,
    stackCardMode: StackCardMode,
    groupMode: String,
    passwordCardDisplayMode: PasswordCardDisplayMode,
    settingsViewModel: SettingsViewModel,
    context: Context,
    activity: FragmentActivity?,
    biometricHelper: BiometricHelper,
    canUseBiometric: Boolean,
    coroutineScope: CoroutineScope,
    bitwardenRepository: BitwardenRepository,
    securityManager: SecurityManager,
    onRenameCategory: (Category, String) -> Unit,
    onDeleteCategory: (Category) -> Unit,
    onTransferCategory: (Category, UnifiedMoveCategoryTarget, takagi.ru.monica.ui.components.UnifiedMoveAction) -> Unit,
    onOpenCommonAccountTemplates: () -> Unit,
    onOpenMdbxCommitHistory: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenTrash: () -> Unit,
    onScanFidoQr: () -> Unit
) {
    val appSettings by settingsViewModel.settings.collectAsState()
    val isAuthenticated by viewModel.isAuthenticated.collectAsState()
    var showCreateCategoryDialog by remember { mutableStateOf(false) }
    var showReunlockDialog by remember { mutableStateOf(false) }
    var reunlockPassword by remember { mutableStateOf("") }
    var reunlockPasswordError by remember { mutableStateOf(false) }
    var showBitwardenUnlockDialog by remember { mutableStateOf(false) }
    var showClearBitwardenCacheDialog by remember { mutableStateOf(false) }
    var clearCacheRiskSummary by remember { mutableStateOf<BitwardenRepository.VaultCacheRiskSummary?>(null) }
    var isBitwardenMaintenanceActionRunning by remember { mutableStateOf(false) }
    val selectedBitwardenVault = selectedBitwardenVaultId?.let { vaultId ->
        bitwardenVaults.find { it.id == vaultId }
    }
    val selectedMdbxDatabase = remember(selectedMdbxDatabaseId, mdbxDatabases) {
        selectedMdbxDatabaseId?.let { databaseId ->
            mdbxDatabases.find { it.id == databaseId }
        }
    }
    val onOpenKeePassNativeManager: () -> Unit = {
        selectedKeePassDatabaseId?.let { databaseId ->
            takagi.ru.monica.ui.screens.DatabaseManagerNavigation.open(takagi.ru.monica.credentialexchange.ImportDestination(takagi.ru.monica.credentialexchange.ImportDestinationKind.KEEPASS, databaseId))
        }
    }
    Column {
        val title = when (val filter = currentFilter) {
            is CategoryFilter.All -> stringResource(R.string.legacy_ui_all_title)
            is CategoryFilter.Archived -> stringResource(R.string.archive_page_title)
            is CategoryFilter.Local -> stringResource(R.string.filter_monica)
            is CategoryFilter.LocalOnly -> stringResource(R.string.filter_local_only)
            is CategoryFilter.Starred -> stringResource(R.string.filter_starred)
            is CategoryFilter.Uncategorized -> stringResource(R.string.filter_uncategorized)
            is CategoryFilter.LocalStarred -> "${stringResource(R.string.filter_monica)} · ${stringResource(R.string.filter_starred)}"
            is CategoryFilter.LocalUncategorized -> "${stringResource(R.string.filter_monica)} · ${stringResource(R.string.filter_uncategorized)}"
            is CategoryFilter.Custom -> categories.find { it.id == filter.categoryId }?.name ?: stringResource(R.string.filter_all)
            is CategoryFilter.KeePassDatabase -> keepassDatabases.find { it.id == filter.databaseId }?.name ?: "KeePass"
            is CategoryFilter.KeePassGroupFilter -> decodeKeePassPathForDisplay(filter.groupPath)
            is CategoryFilter.KeePassDatabaseStarred -> "${keepassDatabases.find { it.id == filter.databaseId }?.name ?: "KeePass"} · ${stringResource(R.string.filter_starred)}"
            is CategoryFilter.KeePassDatabaseUncategorized -> "${keepassDatabases.find { it.id == filter.databaseId }?.name ?: "KeePass"} · ${stringResource(R.string.filter_uncategorized)}"
            is CategoryFilter.BitwardenVault -> "Bitwarden"
            is CategoryFilter.BitwardenFolderFilter -> "Bitwarden"
            is CategoryFilter.BitwardenVaultStarred -> "${stringResource(R.string.filter_bitwarden)} · ${stringResource(R.string.filter_starred)}"
            is CategoryFilter.BitwardenVaultUncategorized -> "${stringResource(R.string.filter_bitwarden)} · ${stringResource(R.string.filter_uncategorized)}"
            is CategoryFilter.MdbxDatabase -> mdbxDatabases.find { it.id == filter.databaseId }?.name ?: "MDBX"
            is CategoryFilter.MdbxFolderFilter -> buildMdbxFolderPathLabel(context, filter.folderId, selectedMdbxFolders)
        }

        ExpressiveTopBar(
            title = title,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            isSearchExpanded = isSearchExpanded,
            onSearchExpandedChange = onSearchExpandedChange,
            searchHint = stringResource(R.string.search_passwords_hint),
            onActionPillBoundsChanged = if (isArchiveView) null else onCategoryPillBoundsChange,
            actions = {
                if (isArchiveView) {
                    IconButton(onClick = { viewModel.closeArchiveView() }) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = stringResource(R.string.nav_passwords_short),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (!isArchiveView) {
                    IconButton(onClick = { onCategorySheetVisibleChange(true) }) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = stringResource(R.string.category),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = { onSearchExpandedChange(true) }) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box {
                    IconButton(
                        onClick = { onTopActionsMenuExpandedChange(true) },
                        enabled = isAuthenticated
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_options),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!isArchiveView && appSettings.categorySelectionUiMode == CategorySelectionUiMode.CHIP_MENU) {
                        PasswordFilterPanel(
                            expanded = isCategorySheetVisible,
                            onDismissRequest = { onCategorySheetVisibleChange(false) }
                        ) {
                            PasswordListCategoryChipMenu(
                                currentFilter = currentFilter,
                                keepassDatabases = keepassDatabases,
                                mdbxDatabases = mdbxDatabases,
                                bitwardenVaults = bitwardenVaults,
                                configuredQuickFilterItems = configuredQuickFilterItems,
                                quickFilterFavorite = quickFilterFavorite,
                                onQuickFilterFavoriteChange = onQuickFilterFavoriteChange,
                                quickFilter2fa = quickFilter2fa,
                                onQuickFilter2faChange = onQuickFilter2faChange,
                                quickFilterNotes = quickFilterNotes,
                                onQuickFilterNotesChange = onQuickFilterNotesChange,
                                quickFilterPasskey = quickFilterPasskey,
                                onQuickFilterPasskeyChange = onQuickFilterPasskeyChange,
                                quickFilterBoundNote = quickFilterBoundNote,
                                onQuickFilterBoundNoteChange = onQuickFilterBoundNoteChange,
                                quickFilterAttachments = quickFilterAttachments,
                                onQuickFilterAttachmentsChange = onQuickFilterAttachmentsChange,
                                quickFilterUncategorized = quickFilterUncategorized,
                                onQuickFilterUncategorizedChange = onQuickFilterUncategorizedChange,
                                quickFilterLocalOnly = quickFilterLocalOnly,
                                onQuickFilterLocalOnlyChange = onQuickFilterLocalOnlyChange,
                                quickFilterManualStackOnly = quickFilterManualStackOnly,
                                onQuickFilterManualStackOnlyChange = onQuickFilterManualStackOnlyChange,
                                quickFilterNeverStack = quickFilterNeverStack,
                                onQuickFilterNeverStackChange = onQuickFilterNeverStackChange,
                                quickFilterUnstacked = quickFilterUnstacked,
                                onQuickFilterUnstackedChange = onQuickFilterUnstackedChange,
                                aggregateSelectedTypes = aggregateSelectedTypes,
                                aggregateVisibleTypes = aggregateVisibleTypes,
                                onToggleAggregateType = onToggleAggregateType,
                                quickFolderShortcuts = categoryMenuQuickFolderShortcuts,
                                topModulesOrder = appSettings.passwordListTopModulesOrder,
                                onTopModulesOrderChange = settingsViewModel::updatePasswordListTopModulesOrder,
                                onQuickFilterItemsOrderChange = settingsViewModel::updatePasswordListQuickFilterItems,
                                launchAnchorBounds = null,
                                onDismiss = { onCategorySheetVisibleChange(false) },
                                onSelectFilter = viewModel::setCategoryFilter,
                                categories = categories,
                                onCreateCategory = {
                                    onCategorySheetVisibleChange(false)
                                    showCreateCategoryDialog = true
                                },
                                onMoveCategory = { category, targetParentCategoryId ->
                                    runCatching {
                                        planLocalCategoryMove(
                                            categories = categories,
                                            sourceCategory = category,
                                            targetParentCategory = categories.find { it.id == targetParentCategoryId },
                                            strings = AppLocaleStringResolver(context),
                                        )
                                    }.onSuccess { plan ->
                                        plan.updatedCategories.forEach(viewModel::updateCategory)
                                    }.onFailure { error ->
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.save_failed_with_error, error.message ?: ""),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onMoveCategoryToStorageTarget = { category, target ->
                                    when (target) {
                                        is StorageTarget.MonicaLocal -> {
                                            runCatching {
                                                planLocalCategoryMove(
                                                    categories = categories,
                                                    sourceCategory = category,
                                                    targetParentCategory = categories.find { it.id == target.categoryId },
                                                    strings = AppLocaleStringResolver(context),
                                                )
                                            }.onSuccess { plan ->
                                                plan.updatedCategories.forEach(viewModel::updateCategory)
                                            }.onFailure { error ->
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.save_failed_with_error, error.message ?: ""),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }

                                        is StorageTarget.Bitwarden -> {
                                            viewModel.updateCategory(
                                                category.copy(
                                                    bitwardenVaultId = target.vaultId,
                                                    bitwardenFolderId = target.folderId.orEmpty()
                                                )
                                            )
                                        }

                                        is StorageTarget.KeePass -> {
                                            Toast.makeText(
                                                context,
                                                context.getString(
                                                    R.string.save_failed_with_error,
                                                    context.getString(R.string.legacy_ui_move_category_keepass_unsupported)
                                                ),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }

                                        is StorageTarget.Mdbx -> {
                                            Toast.makeText(
                                                context,
                                                context.getString(
                                                    R.string.save_failed_with_error,
                                                    context.getString(R.string.legacy_ui_move_category_mdbx_unsupported)
                                                ),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                },
                                onTransferCategory = onTransferCategory,
                                getBitwardenFolders = viewModel::getBitwardenFolders,
                                getMdbxFolders = viewModel::getMdbxFolders,
                                getKeePassGroups = localKeePassViewModel::getGroups,
                                onExternalFolderChanged = { target ->
                                    when (target) {
                                        is takagi.ru.monica.repository.ExternalFolderTarget.KeePass -> {
                                            localKeePassViewModel.refreshGroups(target.databaseId)
                                            viewModel.refreshKeePassFromSourceForCurrentContext()
                                        }
                                        is takagi.ru.monica.repository.ExternalFolderTarget.Mdbx -> viewModel.refreshMdbxFolders(target.databaseId)
                                        is takagi.ru.monica.repository.ExternalFolderTarget.Bitwarden -> Unit
                                    }
                                },
                                onRenameCategory = onRenameCategory,
                                onDeleteCategory = onDeleteCategory
                            )
                        }
                    }
                    PasswordTopActionsDropdownMenu(
                        expanded = topActionsMenuExpanded,
                        showNearbySync = !isKeePassDatabaseView,
                        onDismissRequest = { onTopActionsMenuExpandedChange(false) }
                    ) {
                            if (currentFilter.isMonicaDatabaseFilter() || currentFilter is CategoryFilter.LocalOnly) {
                                KeepassNativeManagerTopActionsMenuItem(onClick = {
                                    onTopActionsMenuExpandedChange(false)
                                    takagi.ru.monica.ui.screens.DatabaseManagerNavigation.open(takagi.ru.monica.credentialexchange.ImportDestination.Local)
                                })
                            }
                            if (isKeePassDatabaseView) {
                                if (keePassFileActions != null) KeePassFileMenuItems(keePassFileActions) { onTopActionsMenuExpandedChange(false) }
                                else KeepassRefreshTopActionsMenuItem(
                                    onClick = {
                                        onTopActionsMenuExpandedChange(false)
                                        viewModel.refreshKeePassFromSourceForCurrentContext()
                                    }
                                )
                                if (selectedKeePassDatabaseId != null) {
                                    KeepassNativeManagerTopActionsMenuItem(
                                        onClick = {
                                            onTopActionsMenuExpandedChange(false)
                                            onOpenKeePassNativeManager()
                                        }
                                    )
                                }
                            }
                            if (selectedMdbxDatabaseId != null) {
                                KeepassNativeManagerTopActionsMenuItem(onClick = {
                                    onTopActionsMenuExpandedChange(false)
                                    takagi.ru.monica.ui.screens.DatabaseManagerNavigation.open(takagi.ru.monica.credentialexchange.ImportDestination(takagi.ru.monica.credentialexchange.ImportDestinationKind.MDBX, selectedMdbxDatabaseId))
                                })
                            }
                            if (
                                selectedMdbxDatabaseId != null &&
                                mdbxViewModel != null &&
                                selectedMdbxDatabase?.supports(MdbxCapability.REMOTE_SYNC) == true
                            ) {
                                MdbxSyncTopActionsMenuItem(
                                    onClick = {
                                        onTopActionsMenuExpandedChange(false)
                                        if (selectedMdbxDatabase?.mdbxPathShouldFlushPendingUpload() == true) {
                                            mdbxViewModel.flushPendingVaultUpload(selectedMdbxDatabaseId)
                                        } else {
                                            mdbxViewModel.syncVault(selectedMdbxDatabaseId)
                                        }
                                    }
                                )
                            }
                            if (
                                selectedMdbxDatabaseId != null &&
                                mdbxViewModel != null &&
                                selectedMdbxDatabase?.supports(MdbxCapability.SNAPSHOTS) == true
                            ) {
                                MdbxCreateSnapshotTopActionsMenuItem(
                                    onClick = {
                                        onTopActionsMenuExpandedChange(false)
                                        mdbxViewModel.createQuickSnapshot(selectedMdbxDatabaseId) { result ->
                                            val message = result.fold(
                                                onSuccess = { snapshot ->
                                                    context.getString(
                                                        R.string.mdbx_quick_snapshot_created,
                                                        snapshot.name
                                                    )
                                                },
                                                onFailure = { error ->
                                                    context.getString(
                                                        R.string.mdbx_quick_snapshot_failed,
                                                        error.message ?: context.getString(R.string.mdbx_snapshot_unavailable)
                                                    )
                                                }
                                            )
                                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                            mdbxViewModel.clearOperationState()
                                        }
                                    }
                                )
                            }
                            if (
                                selectedMdbxDatabaseId != null &&
                                selectedMdbxDatabase?.supports(MdbxCapability.DELTA_HISTORY) == true
                            ) {
                                MdbxCommitHistoryTopActionsMenuItem(
                                    onClick = {
                                        onTopActionsMenuExpandedChange(false)
                                        onOpenMdbxCommitHistory(selectedMdbxDatabaseId)
                                    }
                                )
                            }
                            if (selectedBitwardenVaultId != null) {
                                BitwardenSyncTopActionsMenuItem(
                                    isSyncing = isTopBarSyncing,
                                    enabled = !isTopBarSyncing && !isBitwardenMaintenanceActionRunning,
                                    onClick = {
                                        val vaultId = selectedBitwardenVaultId
                                        if (!isTopBarSyncing && !isBitwardenMaintenanceActionRunning && vaultId != null) {
                                            onTopActionsMenuExpandedChange(false)
                                            bitwardenViewModel.requestManualSync(vaultId)
                                        }
                                    },
                                )
                            }
                            if (selectedBitwardenVaultId != null) {
                                BitwardenReunlockTopActionsMenuItem(
                                    onClick = {
                                        onTopActionsMenuExpandedChange(false)
                                        showBitwardenUnlockDialog = true
                                    },
                                )
                                BitwardenLockTopActionsMenuItem(
                                    onClick = {
                                        onTopActionsMenuExpandedChange(false)
                                        coroutineScope.launch {
                                            runCatching {
                                                bitwardenRepository.forceLock(selectedBitwardenVaultId)
                                            }.onSuccess {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.current_database_locked),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }.onFailure { error ->
                                                Toast.makeText(
                                                    context,
                                                    context.getString(
                                                        R.string.save_failed_with_error,
                                                        error.message ?: ""
                                                    ),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                )
                                BitwardenClearCacheTopActionsMenuItem(
                                    enabled = !isBitwardenMaintenanceActionRunning,
                                    onClick = {
                                        val vaultId = selectedBitwardenVaultId
                                        if (vaultId != null) {
                                            onTopActionsMenuExpandedChange(false)
                                            coroutineScope.launch {
                                                runCatching {
                                                    viewModel.getBitwardenVaultCacheRiskSummary(vaultId)
                                                }.onSuccess { summary ->
                                                    clearCacheRiskSummary = summary
                                                    showClearBitwardenCacheDialog = true
                                                }.onFailure { error ->
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(
                                                            R.string.bitwarden_clear_cache_failed,
                                                            error.message ?: ""
                                                        ),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                            CommonPasswordTopActionsMenuItems(
                                onDismissMenu = { onTopActionsMenuExpandedChange(false) },
                                onShowDisplayOptions = { onShowDisplayOptionsSheetChange(true) },
                                onOpenCommonAccountTemplates = onOpenCommonAccountTemplates,
                                onOpenHistory = onOpenHistory,
                                onOpenTrash = onOpenTrash,
                                onOpenArchive = viewModel::openArchiveView,
                                showSettingsEntry = showStandaloneSettingsEntry,
                                onOpenSettings = onOpenStandaloneSettings,
                                onScanFidoQr = onScanFidoQr
                            )
                        }
                }
            }
        )

        if (showBitwardenUnlockDialog && selectedBitwardenVault != null) {
            UnlockVaultDialog(
                email = selectedBitwardenVault.email,
                onUnlock = { masterPassword ->
                    showBitwardenUnlockDialog = false
                    coroutineScope.launch {
                        when (val result = bitwardenRepository.unlock(
                            selectedBitwardenVault.id,
                            masterPassword
                        )) {
                            is BitwardenRepository.UnlockResult.Success -> {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.current_database_unlocked),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                            is BitwardenRepository.UnlockResult.Error -> {
                                Toast.makeText(
                                    context,
                                    result.message,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                },
                onDismiss = { showBitwardenUnlockDialog = false }
            )
        }
        if (showClearBitwardenCacheDialog && selectedBitwardenVaultId != null && clearCacheRiskSummary != null) {
            val vaultId = selectedBitwardenVaultId
            val riskSummary = clearCacheRiskSummary!!
            val hasRisk = riskSummary.hasRisk
            val resetDialogState: () -> Unit = {
                showClearBitwardenCacheDialog = false
                clearCacheRiskSummary = null
            }

            AlertDialog(
                onDismissRequest = {
                    if (!isBitwardenMaintenanceActionRunning) {
                        resetDialogState()
                    }
                },
                title = { Text(stringResource(R.string.bitwarden_clear_cache_confirm_title)) },
                text = {
                    Text(
                        if (hasRisk) {
                            context.getString(
                                R.string.bitwarden_clear_cache_confirm_message_with_risk,
                                riskSummary.pendingOperationCount,
                                riskSummary.passwordLocalModifiedCount,
                                riskSummary.secureItemLocalModifiedCount,
                                riskSummary.unresolvedConflictCount
                            )
                        } else {
                            context.getString(R.string.bitwarden_clear_cache_confirm_message)
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !isBitwardenMaintenanceActionRunning,
                        onClick = {
                            coroutineScope.launch {
                                isBitwardenMaintenanceActionRunning = true
                                runCatching {
                                    viewModel.clearBitwardenVaultLocalCache(
                                        vaultId = vaultId,
                                        mode = if (hasRisk) {
                                            BitwardenRepository.CacheClearMode.SAFE_ONLY_SYNCED
                                        } else {
                                            BitwardenRepository.CacheClearMode.FULL_FORCE
                                        }
                                    )
                                }.onSuccess { result ->
                                    val message = if (hasRisk) {
                                        context.getString(
                                            R.string.bitwarden_clear_cache_success_safe,
                                            result.totalClearedCount,
                                            result.protectedCipherCount
                                        )
                                    } else {
                                        context.getString(
                                            R.string.bitwarden_clear_cache_success,
                                            result.totalClearedCount
                                        )
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    resetDialogState()
                                }.onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.bitwarden_clear_cache_failed,
                                            error.message ?: ""
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                isBitwardenMaintenanceActionRunning = false
                            }
                        }
                    ) {
                        Text(
                            stringResource(
                                if (hasRisk) R.string.bitwarden_clear_cache_action_safe
                                else R.string.bitwarden_clear_cache_action
                            )
                        )
                    }
                },
                dismissButton = {
                    Row {
                                if (hasRisk) {
                                    TextButton(
                                        enabled = !isBitwardenMaintenanceActionRunning,
                                        onClick = {
                                            coroutineScope.launch {
                                                isBitwardenMaintenanceActionRunning = true
                                                runCatching {
                                                    viewModel.clearBitwardenVaultLocalCache(
                                                        vaultId = vaultId,
                                                        mode = BitwardenRepository.CacheClearMode.FULL_FORCE
                                                    )
                                                }.onSuccess { result ->
                                                    Toast.makeText(
                                                context,
                                                context.getString(
                                                    R.string.bitwarden_clear_cache_force_success,
                                                    result.totalClearedCount
                                                ),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            resetDialogState()
                                        }.onFailure { error ->
                                            Toast.makeText(
                                                context,
                                                context.getString(
                                                    R.string.bitwarden_clear_cache_failed,
                                                    error.message ?: ""
                                                ),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        isBitwardenMaintenanceActionRunning = false
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.bitwarden_clear_cache_action_force))
                            }
                        }
                        TextButton(
                            enabled = !isBitwardenMaintenanceActionRunning,
                            onClick = { resetDialogState() }
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            )
        }

        if (showReunlockDialog) {
            val dismissReunlockDialog: () -> Unit = {
                showReunlockDialog = false
                reunlockPassword = ""
                reunlockPasswordError = false
            }
            val skipIdentityVerification = appSettings.disablePasswordVerification
            val biometricAction = if (!skipIdentityVerification && activity != null && canUseBiometric) {
                {
                    biometricHelper.authenticate(
                        activity = activity,
                        title = context.getString(R.string.verify_identity),
                        subtitle = context.getString(R.string.reunlock_current_database_menu),
                        onSuccess = {
                            val unlocked = runCatching {
                                securityManager.unlockVaultWithBiometric()
                            }.getOrDefault(false)
                            if (unlocked) {
                                securityManager.markVaultAuthenticated()
                                viewModel.restoreAuthenticatedUiState()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.current_database_unlocked),
                                    Toast.LENGTH_SHORT
                                ).show()
                                dismissReunlockDialog()
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.reunlock_current_database_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onError = { error ->
                            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                        },
                        onFailed = {}
                    )
                }
            } else {
                null
            }

            M3IdentityVerifyDialog(
                title = stringResource(R.string.verify_identity),
                message = stringResource(R.string.reunlock_current_database_message),
                passwordValue = reunlockPassword,
                onPasswordChange = {
                    reunlockPassword = it
                    reunlockPasswordError = false
                },
                onDismiss = dismissReunlockDialog,
                onConfirm = {
                    val unlocked = if (skipIdentityVerification) {
                        securityManager.unlockVaultForDeveloperBypass(appSettings.autoLockMinutes)
                    } else {
                        securityManager.unlockVaultWithPassword(reunlockPassword)
                    }
                    if (unlocked) {
                        securityManager.markVaultAuthenticated()
                        viewModel.restoreAuthenticatedUiState()
                        Toast.makeText(
                            context,
                            context.getString(R.string.current_database_unlocked),
                            Toast.LENGTH_SHORT
                        ).show()
                        dismissReunlockDialog()
                    } else {
                        reunlockPasswordError = true
                    }
                },
                confirmText = stringResource(R.string.unlock),
                destructiveConfirm = false,
                requireIdentityVerification = !skipIdentityVerification,
                isPasswordError = reunlockPasswordError,
                passwordErrorText = stringResource(R.string.current_password_incorrect),
                onBiometricClick = biometricAction,
                biometricHintText = if (biometricAction == null) {
                    context.getString(R.string.biometric_not_available)
                } else {
                    null
                }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        val unifiedSelectedFilter = when (val filter = currentFilter) {
            is CategoryFilter.All -> UnifiedCategoryFilterSelection.All
            is CategoryFilter.Archived -> UnifiedCategoryFilterSelection.All
            is CategoryFilter.Local -> UnifiedCategoryFilterSelection.Local
            is CategoryFilter.LocalOnly -> UnifiedCategoryFilterSelection.Local
            is CategoryFilter.Starred -> UnifiedCategoryFilterSelection.Starred
            is CategoryFilter.Uncategorized -> UnifiedCategoryFilterSelection.Uncategorized
            is CategoryFilter.LocalStarred -> UnifiedCategoryFilterSelection.LocalStarred
            is CategoryFilter.LocalUncategorized -> UnifiedCategoryFilterSelection.LocalUncategorized
            is CategoryFilter.Custom -> UnifiedCategoryFilterSelection.Custom(filter.categoryId)
            is CategoryFilter.BitwardenVault -> UnifiedCategoryFilterSelection.BitwardenVaultFilter(filter.vaultId)
            is CategoryFilter.BitwardenFolderFilter -> UnifiedCategoryFilterSelection.BitwardenFolderFilter(filter.vaultId, filter.folderId)
            is CategoryFilter.BitwardenVaultStarred -> UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter(filter.vaultId)
            is CategoryFilter.BitwardenVaultUncategorized -> UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter(filter.vaultId)
            is CategoryFilter.KeePassDatabase -> UnifiedCategoryFilterSelection.KeePassDatabaseFilter(filter.databaseId)
        is CategoryFilter.KeePassGroupFilter -> UnifiedCategoryFilterSelection.KeePassGroupFilter(
            filter.databaseId,
            filter.groupPath,
            filter.groupUuid
        )
            is CategoryFilter.KeePassDatabaseStarred -> UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter(filter.databaseId)
            is CategoryFilter.KeePassDatabaseUncategorized -> UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter(filter.databaseId)
            is CategoryFilter.MdbxDatabase -> UnifiedCategoryFilterSelection.MdbxDatabaseFilter(filter.databaseId)
            is CategoryFilter.MdbxFolderFilter -> UnifiedCategoryFilterSelection.MdbxFolderFilter(filter.databaseId, filter.folderId)
        }
        if (showDisplayOptionsSheet) {
            PasswordDisplayOptionsSheet(
                stackCardMode = stackCardMode,
                groupMode = groupMode,
                passwordCardDisplayMode = passwordCardDisplayMode,
                onDismiss = { onShowDisplayOptionsSheetChange(false) },
                onStackCardModeSelected = { mode ->
                    settingsViewModel.updateStackCardMode(mode.name)
                },
                onGroupModeSelected = { modeKey ->
                    settingsViewModel.updatePasswordGroupMode(modeKey)
                },
                onPasswordCardDisplayModeSelected = { mode ->
                    settingsViewModel.updatePasswordCardDisplayMode(mode)
                }
            )
        }

        if (showCreateCategoryDialog) {
            val initialLocalParentPath = (currentFilter as? CategoryFilter.Custom)?.let { filter ->
                categories.firstOrNull { it.id == filter.categoryId }?.name
            }
            val initialDialogMdbxDbId = when (currentFilter) {
                is CategoryFilter.MdbxDatabase -> currentFilter.databaseId
                is CategoryFilter.MdbxFolderFilter -> currentFilter.databaseId
                else -> null
            }
            val (initialDialogTarget, initialDialogKeePassDbId, initialDialogBitwardenVaultId) = remember(currentFilter) {
                when (currentFilter) {
                    is CategoryFilter.KeePassDatabase,
                    is CategoryFilter.KeePassGroupFilter,
                    is CategoryFilter.KeePassDatabaseStarred,
                    is CategoryFilter.KeePassDatabaseUncategorized -> {
                        val dbId = when (currentFilter) {
                            is CategoryFilter.KeePassDatabase -> currentFilter.databaseId
                            is CategoryFilter.KeePassGroupFilter -> currentFilter.databaseId
                            is CategoryFilter.KeePassDatabaseStarred -> currentFilter.databaseId
                            is CategoryFilter.KeePassDatabaseUncategorized -> currentFilter.databaseId
                            else -> null
                        }
                        Triple(CreateDialogTarget.KeePass, dbId, null)
                    }
                    is CategoryFilter.BitwardenVault,
                    is CategoryFilter.BitwardenFolderFilter,
                    is CategoryFilter.BitwardenVaultStarred,
                    is CategoryFilter.BitwardenVaultUncategorized -> {
                        val vaultId = when (currentFilter) {
                            is CategoryFilter.BitwardenVault -> currentFilter.vaultId
                            is CategoryFilter.BitwardenFolderFilter -> currentFilter.vaultId
                            is CategoryFilter.BitwardenVaultStarred -> currentFilter.vaultId
                            is CategoryFilter.BitwardenVaultUncategorized -> currentFilter.vaultId
                            else -> null
                        }
                        Triple(CreateDialogTarget.Bitwarden, null, vaultId)
                    }
                    is CategoryFilter.MdbxDatabase,
                    is CategoryFilter.MdbxFolderFilter -> {
                        Triple(CreateDialogTarget.Mdbx, null, null)
                    }
                    else -> Triple(null, null, null)
                }
            }
            CreateCategoryDialog(
                visible = true,
                onDismiss = { showCreateCategoryDialog = false },
                categories = categories,
                keepassDatabases = keepassDatabases,
                mdbxDatabases = mdbxDatabases,
                bitwardenVaults = bitwardenVaults,
                getKeePassGroups = localKeePassViewModel::getGroups,
                getMdbxFolders = viewModel::getMdbxFolders,
                onCreateCategoryWithName = { name -> viewModel.addCategory(name) },
                initialLocalParentPath = initialLocalParentPath,
                initialTarget = initialDialogTarget,
                initialKeePassDbId = initialDialogKeePassDbId,
                initialMdbxDbId = initialDialogMdbxDbId,
                initialBitwardenVaultId = initialDialogBitwardenVaultId,
                onCreateBitwardenFolder = { vaultId, name ->
                    coroutineScope.launch {
                        val result = bitwardenRepository.createFolder(vaultId, name)
                        result.exceptionOrNull()?.let { error ->
                            Toast.makeText(
                                context,
                                context.getString(R.string.webdav_operation_failed, error.message ?: ""),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                onCreateKeePassGroup = { databaseId, parentPath, name ->
                    localKeePassViewModel.createGroup(
                        databaseId = databaseId,
                        groupName = name,
                        parentPath = parentPath
                    ) { result ->
                        result.exceptionOrNull()?.let { error ->
                            Toast.makeText(
                                context,
                                context.getString(R.string.webdav_operation_failed, error.message ?: ""),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                initialMdbxParentFolderId = (currentFilter as? CategoryFilter.MdbxFolderFilter)?.folderId,
                onCreateMdbxProject = { databaseId, parentFolderId, name ->
                    coroutineScope.launch {
                        viewModel.createMdbxFolder(databaseId, name, parentFolderId ?: "root") { result ->
                            result.exceptionOrNull()?.let { error ->
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.save_failed_with_error, error.message ?: ""),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            )
        }
    }
}
