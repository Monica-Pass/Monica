package takagi.ru.monica.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import takagi.ru.monica.R
import takagi.ru.monica.utils.StringResolver
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxCapability
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.supports
import takagi.ru.monica.repository.MdbxConflictResolution
import takagi.ru.monica.repository.MdbxConflictSummary
import takagi.ru.monica.repository.MdbxCommitDiff
import takagi.ru.monica.repository.MdbxDeltaSummary
import takagi.ru.monica.repository.MdbxHealthRepairChoice
import takagi.ru.monica.repository.MdbxMigrationBlockerKind
import takagi.ru.monica.repository.MdbxMigrationWarningKind
import takagi.ru.monica.repository.MdbxSnapshotSummary
import takagi.ru.monica.repository.MdbxStructureNode
import takagi.ru.monica.repository.MdbxStructureNodeStatus
import takagi.ru.monica.repository.MdbxStructureNodeType
import takagi.ru.monica.repository.MdbxStructurePreview
import takagi.ru.monica.repository.MdbxVaultDiagnostics
import takagi.ru.monica.ui.components.M3IdentityVerifyDialog
import takagi.ru.monica.utils.ClipboardUtils
import takagi.ru.monica.utils.BiometricHelper
import takagi.ru.monica.viewmodel.MdbxViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class MdbxManagerInitialPage {
    HOME,
    DETAIL,
    COMMIT_HISTORY
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MdbxManagerScreen(
    viewModel: MdbxViewModel,
    initialDatabaseId: Long? = null,
    initialPage: MdbxManagerInitialPage = MdbxManagerInitialPage.HOME,
    onNavigateBack: () -> Unit,
    onNavigateToLocalCreate: () -> Unit,
    onNavigateToLocalOpen: () -> Unit,
    onNavigateToWebDavCreate: () -> Unit,
    onNavigateToWebDavOpen: () -> Unit,
    onNavigateToOneDriveCreate: () -> Unit,
    onNavigateToOneDriveOpen: () -> Unit
) {
    val strings = rememberScreenStrings()
    val context = LocalContext.current
    val databases by viewModel.allDatabases.collectAsState()
    val databasesLoaded by viewModel.allDatabasesLoaded.collectAsState()
    val operationState by viewModel.operationState.collectAsState()
    val migrationState by viewModel.migrationState.collectAsState()
    val conflictCounts by viewModel.conflictCounts.collectAsState()
    val vaultDiagnostics by viewModel.vaultDiagnostics.collectAsState()
    val conflictDialogState by viewModel.conflictDialogState.collectAsState()
    val deltaDialogState by viewModel.deltaDialogState.collectAsState()
    val healthRepairState by viewModel.healthRepairState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf<LocalMdbxDatabase?>(null) }
    var showHealthRepairDeleteVerification by rememberSaveable { mutableStateOf(false) }
    var healthRepairMasterPassword by rememberSaveable { mutableStateOf("") }
    var healthRepairPasswordError by rememberSaveable { mutableStateOf(false) }
    val healthRepairBiometricHelper = remember(context) { BiometricHelper(context) }
    var page by rememberSaveable(
        initialDatabaseId,
        initialPage,
        stateSaver = MdbxManagerPageSaver
    ) {
        mutableStateOf(initialMdbxManagerPage(initialDatabaseId, initialPage))
    }
    val openedFromCommitHistoryShortcut =
        initialDatabaseId != null && initialPage == MdbxManagerInitialPage.COMMIT_HISTORY
    val snapshotPage = page as? MdbxManagerPage.SnapshotStructure
    var snapshotCompareMode by rememberSaveable(snapshotPage?.databaseId, snapshotPage?.snapshotId) {
        mutableStateOf(false)
    }
    val localDatabases = remember(databases) {
        databases.filter {
            it.sourceTypeEnum == MdbxSourceType.LOCAL_INTERNAL ||
                it.sourceTypeEnum == MdbxSourceType.LOCAL_EXTERNAL
        }
    }
    val webDavDatabases = remember(databases) {
        databases.filter { it.sourceTypeEnum == MdbxSourceType.REMOTE_WEBDAV }
    }
    val oneDriveDatabases = remember(databases) {
        databases.filter { it.sourceTypeEnum == MdbxSourceType.REMOTE_ONEDRIVE }
    }
    val selectedDatabase = (page as? MdbxManagerPage.DatabasePage)?.databaseId?.let { databaseId ->
        databases.firstOrNull { it.id == databaseId }
    }

    LaunchedEffect(Unit) {
        viewModel.pruneMissingLocalVaults()
    }
    LaunchedEffect(databasesLoaded, databases) {
        val databasePage = page as? MdbxManagerPage.DatabasePage
        if (
            databasesLoaded &&
            databasePage != null &&
            databases.none { it.id == databasePage.databaseId }
        ) {
            page = MdbxManagerPage.Hub
        }
    }
    LaunchedEffect(selectedDatabase?.id) {
        selectedDatabase?.let { database ->
            viewModel.activateMdbxDatabase(database.id)
        }
    }
    LaunchedEffect(healthRepairState) {
        if (healthRepairState !is MdbxViewModel.MdbxHealthRepairState.Reviewing) {
            showHealthRepairDeleteVerification = false
            healthRepairMasterPassword = ""
            healthRepairPasswordError = false
        }
    }
    LaunchedEffect(page, selectedDatabase?.id, deltaDialogState) {
        when (val currentPage = page) {
            is MdbxManagerPage.Conflict -> viewModel.dismissDeltaDialog()
            is MdbxManagerPage.Snapshots -> {
                viewModel.dismissConflictDialog()
                val currentDeltaState = deltaDialogState as? MdbxViewModel.MdbxDeltaDialogState.Visible
                val database = selectedDatabase
                if (
                    database != null &&
                    database.id == currentPage.databaseId &&
                    currentDeltaState?.databaseId != currentPage.databaseId
                ) {
                    viewModel.showDeltaHistory(database)
                }
            }
            is MdbxManagerPage.SnapshotStructure -> viewModel.dismissConflictDialog()
            is MdbxManagerPage.CommitHistory -> {
                viewModel.dismissConflictDialog()
                val currentDeltaState = deltaDialogState as? MdbxViewModel.MdbxDeltaDialogState.Visible
                val database = selectedDatabase
                if (
                    database != null &&
                    database.id == currentPage.databaseId &&
                    currentDeltaState?.databaseId != currentPage.databaseId
                ) {
                    viewModel.showDeltaHistory(database)
                }
            }
            is MdbxManagerPage.Health,
            is MdbxManagerPage.Attachments,
            is MdbxManagerPage.Maintenance -> {
                viewModel.dismissConflictDialog()
                viewModel.dismissDeltaDialog()
                viewModel.dismissAdvancedTools()
            }
            else -> Unit
        }
    }
    val deltaState = deltaDialogState as? MdbxViewModel.MdbxDeltaDialogState.Visible
    val snapshotTopBarState = deltaState?.takeIf { it.databaseId == snapshotPage?.databaseId }
    val snapshotTopBarPreview = snapshotTopBarState?.let { topBarState ->
        topBarState.structurePreview
            ?.takeIf { topBarState.selectedStructureSnapshotId == snapshotPage?.snapshotId }
    }
    val snapshotTopBarName = snapshotPage?.let { current ->
        snapshotTopBarPreview?.snapshotName
            ?: snapshotTopBarState?.snapshots?.firstOrNull { it.snapshotId == current.snapshotId }?.name
            ?: shortId(current.snapshotId)
    }
    val snapshotTopBarMeta = snapshotPage?.let {
        snapshotTopBarPreview?.let { preview ->
            strings.get(R.string.mdbx_ui_structure_comparison_counts, preview.currentItemCount, preview.snapshotItemCount)
        } ?: strings.get(R.string.mdbx_ui_structure_loading)
    }

    val goBack: () -> Unit = {
        page = when (val current = page) {
            MdbxManagerPage.Hub -> {
                onNavigateBack()
                MdbxManagerPage.Hub
            }
            is MdbxManagerPage.Source -> MdbxManagerPage.Hub
            is MdbxManagerPage.Detail -> current.source?.let { MdbxManagerPage.Source(it) } ?: MdbxManagerPage.Hub
            is MdbxManagerPage.Conflict -> {
                viewModel.dismissConflictDialog()
                MdbxManagerPage.Detail(current.databaseId, current.source)
            }
            is MdbxManagerPage.Snapshots -> {
                val deltaState = deltaDialogState as? MdbxViewModel.MdbxDeltaDialogState.Visible
                if (deltaState?.selectedDiffCommitId != null) {
                    viewModel.closeCommitDiff()
                    current
                } else {
                    viewModel.dismissDeltaDialog()
                    MdbxManagerPage.Detail(current.databaseId, current.source)
                }
            }
            is MdbxManagerPage.SnapshotStructure -> {
                viewModel.closeSnapshotStructure()
                MdbxManagerPage.Snapshots(current.databaseId, current.source)
            }
            is MdbxManagerPage.CommitHistory -> {
                val deltaState = deltaDialogState as? MdbxViewModel.MdbxDeltaDialogState.Visible
                if (deltaState?.selectedDiffCommitId != null) {
                    viewModel.closeCommitDiff()
                    current
                } else if (openedFromCommitHistoryShortcut) {
                    viewModel.dismissDeltaDialog()
                    onNavigateBack()
                    MdbxManagerPage.Hub
                } else {
                    viewModel.dismissDeltaDialog()
                    MdbxManagerPage.Detail(current.databaseId, current.source)
                }
            }
            is MdbxManagerPage.Health -> MdbxManagerPage.Detail(current.databaseId, current.source)
            is MdbxManagerPage.Attachments -> MdbxManagerPage.Detail(current.databaseId, current.source)
            is MdbxManagerPage.Maintenance -> MdbxManagerPage.Detail(current.databaseId, current.source)
        }
    }
    BackHandler(onBack = goBack)

    LaunchedEffect(operationState) {
        when (val state = operationState) {
            is MdbxViewModel.OperationState.Success -> {
                snackbarHostState.showSnackbar(
                    message = state.message,
                    duration = SnackbarDuration.Short
                )
                viewModel.clearOperationState()
            }
            is MdbxViewModel.OperationState.Error -> {
                snackbarHostState.showSnackbar(
                    message = state.message,
                    duration = SnackbarDuration.Short
                )
                viewModel.clearOperationState()
            }
            else -> Unit
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        topBar = {
            TopAppBar(
                title = {
                    if (snapshotPage != null && snapshotTopBarName != null) {
                        Column {
                            Text(
                                snapshotTopBarName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                snapshotTopBarMeta.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Text(page.title(strings, selectedDatabase))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = goBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (snapshotPage != null) {
                        IconButton(onClick = { snapshotCompareMode = !snapshotCompareMode }) {
                            Icon(
                                if (snapshotCompareMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = null
                            )
                        }
                    } else if (page is MdbxManagerPage.Source) {
                        val current = page as MdbxManagerPage.Source
                        IconButton(onClick = {
                            when (current.source) {
                                MdbxManagerSource.LOCAL -> onNavigateToLocalOpen()
                                MdbxManagerSource.WEBDAV -> onNavigateToWebDavOpen()
                                MdbxManagerSource.ONEDRIVE -> onNavigateToOneDriveOpen()
                            }
                        }) {
                            Icon(Icons.Default.Folder, contentDescription = strings.get(R.string.mdbx_ui_open_existing_database))
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (page is MdbxManagerPage.Source) {
                val current = page as MdbxManagerPage.Source
                ExtendedFloatingActionButton(
                    onClick = {
                        when (current.source) {
                            MdbxManagerSource.LOCAL -> onNavigateToLocalCreate()
                            MdbxManagerSource.WEBDAV -> onNavigateToWebDavCreate()
                            MdbxManagerSource.ONEDRIVE -> onNavigateToOneDriveCreate()
                        }
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.mdbx_create_new_vault_button)) }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val forward = targetState.depth() > initialState.depth()
                    val slideDistance = 60
                    if (forward) {
                        (slideInHorizontally(tween(300)) { slideDistance } + fadeIn(tween(300)))
                            .togetherWith(slideOutHorizontally(tween(300)) { -slideDistance } + fadeOut(tween(200)))
                    } else {
                        (slideInHorizontally(tween(300)) { -slideDistance } + fadeIn(tween(300)))
                            .togetherWith(slideOutHorizontally(tween(300)) { slideDistance } + fadeOut(tween(200)))
                    }
                },
                contentKey = { it::class }
            ) { current ->
            when (current) {
                MdbxManagerPage.Hub -> {
                    MdbxManagerHubPage(
                        localCount = localDatabases.size,
                        webDavCount = webDavDatabases.size,
                        oneDriveCount = oneDriveDatabases.size,
                        onOpenLocal = { page = MdbxManagerPage.Source(MdbxManagerSource.LOCAL) },
                        onOpenWebDav = { page = MdbxManagerPage.Source(MdbxManagerSource.WEBDAV) },
                        onOpenOneDrive = { page = MdbxManagerPage.Source(MdbxManagerSource.ONEDRIVE) }
                    )
                }
                is MdbxManagerPage.Source -> {
                    val sourceDatabases = when (current.source) {
                        MdbxManagerSource.LOCAL -> localDatabases
                        MdbxManagerSource.WEBDAV -> webDavDatabases
                        MdbxManagerSource.ONEDRIVE -> oneDriveDatabases
                    }
                    MdbxSourceManagementPage(
                        source = current.source,
                        databases = sourceDatabases,
                        conflictCounts = conflictCounts,
                        diagnostics = vaultDiagnostics,
                        onCreateClick = {
                            when (current.source) {
                                MdbxManagerSource.LOCAL -> onNavigateToLocalCreate()
                                MdbxManagerSource.WEBDAV -> onNavigateToWebDavCreate()
                                MdbxManagerSource.ONEDRIVE -> onNavigateToOneDriveCreate()
                            }
                        },
                        onOpenClick = {
                            when (current.source) {
                                MdbxManagerSource.LOCAL -> onNavigateToLocalOpen()
                                MdbxManagerSource.WEBDAV -> onNavigateToWebDavOpen()
                                MdbxManagerSource.ONEDRIVE -> onNavigateToOneDriveOpen()
                            }
                        },
                        onOpenDatabase = { db ->
                            viewModel.activateMdbxDatabase(db.id)
                            page = MdbxManagerPage.Detail(db.id, current.source)
                        }
                    )
                }
                is MdbxManagerPage.Detail -> {
                    selectedDatabase?.let { db ->
                        MdbxVaultDetailPage(
                            database = db,
                            isDefault = db.isDefault,
                            conflictCount = conflictCounts[db.id] ?: 0,
                            diagnostics = vaultDiagnostics[db.id],
                            onSync = { viewModel.syncVault(db.id) },
                            onShowConflicts = {
                                viewModel.showConflicts(db)
                                page = MdbxManagerPage.Conflict(db.id, current.source)
                            },
                            onShowHealth = {
                                viewModel.refreshVaultDiagnostics(listOf(db))
                                page = MdbxManagerPage.Health(db.id, current.source)
                            },
                            onShowSnapshots = {
                                viewModel.showDeltaHistory(db)
                                page = MdbxManagerPage.Snapshots(db.id, current.source)
                            },
                            onShowCommitHistory = {
                                viewModel.showDeltaHistory(db)
                                page = MdbxManagerPage.CommitHistory(db.id, current.source)
                            },
                            onShowAttachments = {
                                viewModel.refreshVaultDiagnostics(listOf(db))
                                page = MdbxManagerPage.Attachments(db.id, current.source)
                            },
                            onShowMaintenance = {
                                viewModel.refreshVaultDiagnostics(listOf(db))
                                page = MdbxManagerPage.Maintenance(db.id, current.source)
                            },
                            onMigrate = if (
                                db.engineTypeEnum == MdbxEngineType.KOTLIN_MDBX1 &&
                                db.sourceTypeEnum in setOf(
                                    MdbxSourceType.LOCAL_INTERNAL,
                                    MdbxSourceType.LOCAL_EXTERNAL
                                )
                            ) {
                                { viewModel.prepareMdbx2Migration(db.id) }
                            } else {
                                null
                            },
                            onSetDefault = { viewModel.setAsDefault(db.id) },
                            onDelete = { showDeleteDialog = db }
                        )
                    } ?: EmptyMdbxState(
                        onCreateClick = {
                            when (current.source) {
                                MdbxManagerSource.LOCAL -> onNavigateToLocalCreate()
                                MdbxManagerSource.WEBDAV -> onNavigateToWebDavCreate()
                                else -> onNavigateToLocalCreate()
                            }
                        },
                        onOpenClick = {
                            when (current.source) {
                                MdbxManagerSource.LOCAL -> onNavigateToLocalOpen()
                                MdbxManagerSource.WEBDAV -> onNavigateToWebDavOpen()
                                else -> onNavigateToLocalOpen()
                            }
                        }
                    )
                }
                is MdbxManagerPage.Conflict -> {
                    val state = conflictDialogState as? MdbxViewModel.MdbxConflictDialogState.Visible
                    MdbxConflictPage(
                        state = state,
                        databaseName = selectedDatabase?.name ?: "MDBX",
                        onResolve = { conflictId, resolution ->
                            viewModel.resolveConflict(current.databaseId, conflictId, resolution)
                        }
                    )
                }
                is MdbxManagerPage.Snapshots -> {
                    val state = deltaDialogState as? MdbxViewModel.MdbxDeltaDialogState.Visible
                    MdbxSnapshotPage(
                        state = state,
                        engineAlwaysCreatesFullSnapshots =
                            selectedDatabase?.engineTypeEnum == MdbxEngineType.RUST_MDBX2,
                        onShowDiff = { commitId -> viewModel.showCommitDiff(current.databaseId, commitId) },
                        onShowSnapshotStructure = { snapshotId ->
                            viewModel.showSnapshotStructure(current.databaseId, snapshotId)
                            page = MdbxManagerPage.SnapshotStructure(current.databaseId, current.source, snapshotId)
                        },
                        onCreateSnapshot = { name, fullSnapshot, onOutcome ->
                            viewModel.requestSnapshotCreation(
                                databaseId = current.databaseId,
                                name = name,
                                requestedFullSnapshot = fullSnapshot,
                                onOutcome = onOutcome
                            )
                        },
                        onDeleteSnapshot = { snapshotId ->
                            viewModel.deleteSnapshot(current.databaseId, snapshotId)
                        },
                        onRevertSnapshot = { snapshotId ->
                            viewModel.revertToSnapshot(current.databaseId, snapshotId)
                        },
                        onPruneAutomaticSnapshots = {
                            viewModel.pruneAutomaticSnapshots(current.databaseId)
                        }
                    )
                }
                is MdbxManagerPage.SnapshotStructure -> {
                    val state = deltaDialogState as? MdbxViewModel.MdbxDeltaDialogState.Visible
                    LaunchedEffect(current.databaseId, current.snapshotId) {
                        if (state == null || state.databaseId != current.databaseId) {
                            selectedDatabase?.let(viewModel::showDeltaHistory)
                        } else if (state.selectedStructureSnapshotId != current.snapshotId) {
                            viewModel.showSnapshotStructure(current.databaseId, current.snapshotId)
                        }
                    }
                    LaunchedEffect(state?.databaseId, state?.isLoading, current.snapshotId) {
                        if (
                            state?.databaseId == current.databaseId &&
                            !state.isLoading &&
                            state.selectedStructureSnapshotId != current.snapshotId
                        ) {
                            viewModel.showSnapshotStructure(current.databaseId, current.snapshotId)
                        }
                    }
                    MdbxSnapshotStructurePage(
                        preview = state?.structurePreview,
                        isLoading = state == null || state.isLoading || state.isStructureLoading,
                        compareMode = snapshotCompareMode
                    )
                }
                is MdbxManagerPage.CommitHistory -> {
                    val state = deltaDialogState as? MdbxViewModel.MdbxDeltaDialogState.Visible
                    MdbxCommitHistoryPage(
                        state = state,
                        onShowDiff = { commitId -> viewModel.showCommitDiff(current.databaseId, commitId) },
                        onRevert = { commitId -> viewModel.revertCommit(current.databaseId, commitId) }
                    )
                }
                is MdbxManagerPage.Health -> {
                    selectedDatabase?.let { db ->
                        MdbxHealthDetailPage(
                            database = db,
                            diagnostics = vaultDiagnostics[db.id],
                            onRefreshDiagnostics = { viewModel.refreshVaultDiagnostics(listOf(db)) },
                            onOpenMaintenance = {
                                page = MdbxManagerPage.Maintenance(db.id, current.source)
                            },
                            onOpenSnapshots = {
                                page = MdbxManagerPage.Snapshots(db.id, current.source)
                            },
                            onOpenCommitHistory = {
                                page = MdbxManagerPage.CommitHistory(db.id, current.source)
                            },
                            onOpenAttachments = {
                                page = MdbxManagerPage.Attachments(db.id, current.source)
                            },
                            onStartAutomaticRepair = if (db.engineTypeEnum == MdbxEngineType.RUST_MDBX2) {
                                { viewModel.requestHealthRepair(db) }
                            } else {
                                null
                            },
                            repairInProgress = when (val repairState = healthRepairState) {
                                MdbxViewModel.MdbxHealthRepairState.Hidden -> false
                                is MdbxViewModel.MdbxHealthRepairState.Planning ->
                                    repairState.databaseId == db.id
                                is MdbxViewModel.MdbxHealthRepairState.Reviewing ->
                                    repairState.databaseId == db.id
                                is MdbxViewModel.MdbxHealthRepairState.Applying ->
                                    repairState.databaseId == db.id
                                is MdbxViewModel.MdbxHealthRepairState.Blocked -> false
                                is MdbxViewModel.MdbxHealthRepairState.Failed -> false
                            }
                        )
                    }
                }
                is MdbxManagerPage.Attachments -> {
                    selectedDatabase?.let { db ->
                        MdbxAttachmentDetailPage(
                            database = db,
                            diagnostics = vaultDiagnostics[db.id],
                            onRefreshDiagnostics = { viewModel.refreshVaultDiagnostics(listOf(db)) }
                        )
                    }
                }
                is MdbxManagerPage.Maintenance -> {
                    selectedDatabase?.let { db ->
                        MdbxMaintenancePage(
                            database = db,
                            diagnostics = vaultDiagnostics[db.id],
                            allowSync = db.supports(MdbxCapability.REMOTE_SYNC),
                            allowPendingUpload = db.supports(MdbxCapability.REMOTE_SYNC),
                            onRefreshDiagnostics = { viewModel.refreshVaultDiagnostics(listOf(db)) },
                            onSync = { viewModel.syncVault(db.id) },
                            onFlushPendingUpload = { viewModel.flushPendingVaultUpload(db.id) }
                        )
                    } ?: EmptyMdbxState(
                        onCreateClick = {
                            when (current.source) {
                                MdbxManagerSource.LOCAL -> onNavigateToLocalCreate()
                                MdbxManagerSource.WEBDAV -> onNavigateToWebDavCreate()
                                else -> onNavigateToLocalCreate()
                            }
                        },
                        onOpenClick = {
                            when (current.source) {
                                MdbxManagerSource.LOCAL -> onNavigateToLocalOpen()
                                MdbxManagerSource.WEBDAV -> onNavigateToWebDavOpen()
                                else -> onNavigateToLocalOpen()
                            }
                        }
                    )
                }
            }
            }

        }
    }

    if (!showHealthRepairDeleteVerification) {
        MdbxHealthRepairDialog(
            state = healthRepairState,
            onCancel = {
                healthRepairMasterPassword = ""
                healthRepairPasswordError = false
                viewModel.dismissHealthRepair()
            },
            onRetry = {
                val databaseId = when (val state = healthRepairState) {
                    is MdbxViewModel.MdbxHealthRepairState.Failed -> state.databaseId
                    is MdbxViewModel.MdbxHealthRepairState.Blocked -> state.databaseId
                    else -> null
                }
                databaseId?.let { id -> databases.firstOrNull { it.id == id } }
                    ?.let(viewModel::requestHealthRepair)
            },
            onKeepContent = {
                viewModel.chooseHealthRepairConflict(MdbxHealthRepairChoice.KEEP_CONTENT)
            },
            onDeleteObject = {
                showHealthRepairDeleteVerification = true
                healthRepairMasterPassword = ""
                healthRepairPasswordError = false
            }
        )
    }

    if (showHealthRepairDeleteVerification) {
        val activity = context.findActivity() as? FragmentActivity
        val completeDeleteChoice = {
            showHealthRepairDeleteVerification = false
            healthRepairMasterPassword = ""
            healthRepairPasswordError = false
            viewModel.chooseHealthRepairConflict(MdbxHealthRepairChoice.DELETE_OBJECT)
        }
        val biometricAction = if (
            activity != null && healthRepairBiometricHelper.isBiometricAvailable()
        ) {
            {
                healthRepairBiometricHelper.authenticate(
                    activity = activity,
                    title = strings.get(R.string.mdbx_ui_repair_verify_title),
                    subtitle = strings.get(R.string.mdbx_ui_repair_verify_subtitle),
                    onSuccess = completeDeleteChoice,
                    onError = { message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    },
                    onFailed = {}
                )
            }
        } else {
            null
        }
        M3IdentityVerifyDialog(
            title = strings.get(R.string.mdbx_ui_repair_verify_delete_title),
            message = strings.get(R.string.mdbx_ui_repair_verify_delete_description),
            passwordValue = healthRepairMasterPassword,
            onPasswordChange = {
                healthRepairMasterPassword = it
                healthRepairPasswordError = false
            },
            onDismiss = {
                showHealthRepairDeleteVerification = false
                healthRepairMasterPassword = ""
                healthRepairPasswordError = false
            },
            onConfirm = {
                if (viewModel.verifyMasterPassword(healthRepairMasterPassword)) {
                    completeDeleteChoice()
                } else {
                    healthRepairPasswordError = true
                }
            },
            confirmText = strings.get(R.string.mdbx_ui_verify_and_delete),
            icon = Icons.Default.Delete,
            destructiveConfirm = true,
            isPasswordError = healthRepairPasswordError,
            passwordErrorText = strings.get(R.string.mdbx_ui_master_password_incorrect),
            onBiometricClick = biometricAction,
            biometricHintText = if (biometricAction == null) strings.get(R.string.mdbx_ui_biometrics_unavailable) else null
        )
    }

    MdbxMigrationDialog(
        state = migrationState,
        onDismiss = viewModel::dismissMdbxMigration,
        onStart = viewModel::startMdbx2Migration,
        onRetryPreflight = viewModel::prepareMdbx2Migration,
        onOpenTarget = { targetDatabaseId ->
            viewModel.dismissMdbxMigration()
            page = MdbxManagerPage.Detail(targetDatabaseId, MdbxManagerSource.LOCAL)
        }
    )

    showDeleteDialog?.let { db ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(stringResource(R.string.mdbx_delete_vault_title)) },
            text = {
                Text(stringResource(R.string.mdbx_delete_vault_message, db.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteVault(db.id)
                        if ((page as? MdbxManagerPage.DatabasePage)?.databaseId == db.id) {
                            page = MdbxManagerPage.Hub
                        }
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.mdbx_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text(stringResource(R.string.mdbx_cancel))
                }
            }
        )
    }
}

@Composable
private fun MdbxMigrationDialog(
    state: MdbxViewModel.MdbxMigrationState,
    onDismiss: () -> Unit,
    onStart: (Long, String, String) -> Unit,
    onRetryPreflight: (Long) -> Unit,
    onOpenTarget: (Long) -> Unit
) {
    val strings = rememberScreenStrings()
    when (state) {
        MdbxViewModel.MdbxMigrationState.Hidden -> Unit
        is MdbxViewModel.MdbxMigrationState.Preparing -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(strings.get(R.string.mdbx_ui_migration_preflight_title)) },
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(strings.get(R.string.mdbx_ui_migration_preflight_description))
                    }
                },
                confirmButton = {}
            )
        }
        is MdbxViewModel.MdbxMigrationState.Ready -> {
            val preview = state.preview
            var targetName by remember(preview.sourceDatabaseId) {
                mutableStateOf(preview.suggestedTargetName)
            }
            var password by remember(preview.sourceDatabaseId) { mutableStateOf("") }
            var confirmPassword by remember(preview.sourceDatabaseId) { mutableStateOf("") }
            var passwordVisible by remember(preview.sourceDatabaseId) { mutableStateOf(false) }
            val passwordsMatch = password.isNotEmpty() && password == confirmPassword
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(strings.get(R.string.mdbx_ui_migrate_to_mdbx2)) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            strings.get(R.string.mdbx_ui_migration_source_preserved),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        HorizontalDivider()
                        MigrationSummaryLine(strings.get(R.string.folder_generic), preview.folderCount.toString())
                        MigrationSummaryLine(strings.get(R.string.mdbx_ui_active_entries), preview.activeEntryCount.toString())
                        MigrationSummaryLine(strings.get(R.string.mdbx_ui_deletion_records), preview.deletedEntryCount.toString())
                        MigrationSummaryLine(
                            strings.get(R.string.attachments),
                            strings.get(R.string.mdbx_ui_attachment_count_and_size, preview.attachmentCount, formatBytes(preview.attachmentBytes))
                        )
                        preview.warnings.forEach { warning ->
                            MigrationNoticeLine(
                                icon = Icons.Default.Info,
                                text = migrationWarningText(strings, warning.kind, warning.count),
                                isError = false
                            )
                        }
                        preview.blockers.forEach { blocker ->
                            MigrationNoticeLine(
                                icon = Icons.Default.Warning,
                                text = migrationBlockerText(strings, blocker.kind, blocker.count),
                                isError = true
                            )
                        }
                        OutlinedTextField(
                            value = targetName,
                            onValueChange = { targetName = it },
                            label = { Text(strings.get(R.string.mdbx_ui_new_database_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(strings.get(R.string.mdbx_ui_new_database_password)) },
                            singleLine = true,
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(Icons.Default.Visibility, contentDescription = strings.get(R.string.show_password))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            label = { Text(strings.get(R.string.confirm_password)) },
                            singleLine = true,
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            isError = confirmPassword.isNotEmpty() && !passwordsMatch,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = preview.isEligible && targetName.isNotBlank() && passwordsMatch,
                        onClick = { onStart(preview.sourceDatabaseId, targetName, password) }
                    ) {
                        Text(strings.get(R.string.mdbx_ui_migration_start))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(strings.get(R.string.cancel)) }
                }
            )
        }
        is MdbxViewModel.MdbxMigrationState.Running -> {
            val progress = if (state.total > 0) {
                (state.completed.toFloat() / state.total.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            AlertDialog(
                onDismissRequest = {},
                title = { Text(strings.get(R.string.mdbx_ui_migration_running)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(migrationStageText(strings, state.stage))
                        if (state.total > 0) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "${state.completed} / ${state.total}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                },
                confirmButton = {}
            )
        }
        is MdbxViewModel.MdbxMigrationState.Success -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(strings.get(R.string.mdbx_ui_migration_complete)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(strings.get(R.string.mdbx_ui_migration_created, state.targetName))
                        MigrationSummaryLine(strings.get(R.string.folder_generic), state.verification.folderCount.toString())
                        MigrationSummaryLine(strings.get(R.string.mdbx_ui_object_entry), state.verification.entryCount.toString())
                        MigrationSummaryLine(
                            strings.get(R.string.attachments),
                            strings.get(R.string.mdbx_ui_attachment_count_and_size, state.verification.attachmentCount, formatBytes(state.verification.attachmentBytes))
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onOpenTarget(state.targetDatabaseId) }) {
                        Text(strings.get(R.string.mdbx_ui_open_new_database))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(strings.get(R.string.mdbx_ui_done)) }
                }
            )
        }
        is MdbxViewModel.MdbxMigrationState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(strings.get(R.string.mdbx_ui_migration_failed)) },
                text = { Text(state.message) },
                confirmButton = {
                    TextButton(onClick = { onRetryPreflight(state.sourceDatabaseId) }) {
                        Text(strings.get(R.string.mdbx_ui_recheck))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(strings.get(R.string.close)) }
                }
            )
        }
    }
}

@Composable
private fun MigrationSummaryLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MigrationNoticeLine(icon: ImageVector, text: String, isError: Boolean) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

private fun migrationWarningText(strings: StringResolver, kind: MdbxMigrationWarningKind, count: Int): String = when (kind) {
    MdbxMigrationWarningKind.NESTED_FOLDERS_FLATTENED -> strings.get(R.string.mdbx_ui_migration_flattened_folders, count)
    MdbxMigrationWarningKind.IMPLICIT_FOLDERS_CREATED -> strings.get(R.string.mdbx_ui_migration_implicit_folders, count)
    MdbxMigrationWarningKind.UNKNOWN_ENTRY_TYPES_COPIED -> strings.get(R.string.mdbx_ui_migration_unknown_types, count)
    MdbxMigrationWarningKind.DELETED_ENTRIES_COPIED -> strings.get(R.string.mdbx_ui_migration_deleted_entries, count)
    MdbxMigrationWarningKind.DELETED_ATTACHMENTS_IGNORED -> strings.get(R.string.mdbx_ui_migration_deleted_attachments, count)
}

private fun migrationBlockerText(strings: StringResolver, kind: MdbxMigrationBlockerKind, count: Int): String = when (kind) {
    MdbxMigrationBlockerKind.SOURCE_ENGINE_UNSUPPORTED -> strings.get(R.string.mdbx_ui_migration_engine_unsupported)
    MdbxMigrationBlockerKind.SOURCE_LOCATION_UNSUPPORTED -> strings.get(R.string.mdbx_ui_migration_location_unsupported)
    MdbxMigrationBlockerKind.DUPLICATE_FOLDER_ID -> strings.get(R.string.mdbx_ui_migration_duplicate_folders, count)
    MdbxMigrationBlockerKind.MISSING_FOLDER_PARENT -> strings.get(R.string.mdbx_ui_migration_missing_parents, count)
    MdbxMigrationBlockerKind.FOLDER_CYCLE -> strings.get(R.string.mdbx_ui_migration_folder_cycles, count)
    MdbxMigrationBlockerKind.DUPLICATE_ENTRY_ID -> strings.get(R.string.mdbx_ui_migration_duplicate_entries, count)
    MdbxMigrationBlockerKind.INVALID_ENTRY_PAYLOAD -> strings.get(R.string.mdbx_ui_migration_invalid_payloads, count)
    MdbxMigrationBlockerKind.DUPLICATE_ATTACHMENT_ID -> strings.get(R.string.mdbx_ui_migration_duplicate_attachments, count)
    MdbxMigrationBlockerKind.ATTACHMENT_TOO_LARGE -> strings.get(R.string.mdbx_ui_migration_large_attachments, count)
    MdbxMigrationBlockerKind.ATTACHMENT_KEY_MISSING -> strings.get(R.string.mdbx_ui_migration_missing_keys, count)
    MdbxMigrationBlockerKind.ATTACHMENT_PARENT_MISSING -> strings.get(R.string.mdbx_ui_migration_missing_entries, count)
}

private fun migrationStageText(strings: StringResolver, stage: MdbxViewModel.MdbxMigrationStage): String = when (stage) {
    MdbxViewModel.MdbxMigrationStage.PREFLIGHT -> strings.get(R.string.mdbx_ui_migration_stage_preflight)
    MdbxViewModel.MdbxMigrationStage.FOLDERS -> strings.get(R.string.folder_create)
    MdbxViewModel.MdbxMigrationStage.ENTRIES -> strings.get(R.string.mdbx_ui_migration_stage_entries)
    MdbxViewModel.MdbxMigrationStage.ATTACHMENTS -> strings.get(R.string.mdbx_ui_migration_stage_attachments)
    MdbxViewModel.MdbxMigrationStage.VERIFYING -> strings.get(R.string.mdbx_ui_migration_stage_verifying)
    MdbxViewModel.MdbxMigrationStage.IMPORTING -> strings.get(R.string.mdbx_ui_migration_stage_index)
}

private enum class MdbxManagerSource {
    LOCAL,
    WEBDAV,
    ONEDRIVE
}

private fun initialMdbxManagerPage(
    databaseId: Long?,
    initialPage: MdbxManagerInitialPage
): MdbxManagerPage = when {
    databaseId == null || initialPage == MdbxManagerInitialPage.HOME -> MdbxManagerPage.Hub
    initialPage == MdbxManagerInitialPage.COMMIT_HISTORY -> {
        MdbxManagerPage.CommitHistory(databaseId, source = null)
    }
    else -> MdbxManagerPage.Detail(databaseId, source = null)
}

private sealed class MdbxManagerPage {
    data object Hub : MdbxManagerPage()
    data class Source(val source: MdbxManagerSource) : MdbxManagerPage()
    sealed class DatabasePage(open val databaseId: Long, open val source: MdbxManagerSource?) : MdbxManagerPage()
    data class Detail(override val databaseId: Long, override val source: MdbxManagerSource?) : DatabasePage(databaseId, source)
    data class Conflict(override val databaseId: Long, override val source: MdbxManagerSource?) : DatabasePage(databaseId, source)
    data class Snapshots(override val databaseId: Long, override val source: MdbxManagerSource?) : DatabasePage(databaseId, source)
    data class SnapshotStructure(
        override val databaseId: Long,
        override val source: MdbxManagerSource?,
        val snapshotId: String
    ) : DatabasePage(databaseId, source)
    data class CommitHistory(override val databaseId: Long, override val source: MdbxManagerSource?) : DatabasePage(databaseId, source)
    data class Health(override val databaseId: Long, override val source: MdbxManagerSource?) : DatabasePage(databaseId, source)
    data class Attachments(override val databaseId: Long, override val source: MdbxManagerSource?) : DatabasePage(databaseId, source)
    data class Maintenance(override val databaseId: Long, override val source: MdbxManagerSource?) : DatabasePage(databaseId, source)
}

private fun MdbxManagerPage.depth(): Int = when (this) {
    MdbxManagerPage.Hub -> 0
    is MdbxManagerPage.Source -> 1
    is MdbxManagerPage.Detail -> 2
    is MdbxManagerPage.Conflict -> 3
    is MdbxManagerPage.Snapshots -> 3
    is MdbxManagerPage.SnapshotStructure -> 4
    is MdbxManagerPage.CommitHistory -> 3
    is MdbxManagerPage.Health -> 3
    is MdbxManagerPage.Attachments -> 3
    is MdbxManagerPage.Maintenance -> 3
}

private val MdbxManagerPageSaver: Saver<MdbxManagerPage, Any> = Saver(
    save = { page ->
        when (page) {
            MdbxManagerPage.Hub -> listOf("Hub")
            is MdbxManagerPage.Source -> listOf("Source", page.source.name)
            is MdbxManagerPage.Detail -> listOf("Detail", page.databaseId, page.source?.name ?: "")
            is MdbxManagerPage.Conflict -> listOf("Conflict", page.databaseId, page.source?.name ?: "")
            is MdbxManagerPage.Snapshots -> listOf("Snapshots", page.databaseId, page.source?.name ?: "")
            is MdbxManagerPage.SnapshotStructure -> listOf(
                "SnapshotStructure",
                page.databaseId,
                page.source?.name ?: "",
                page.snapshotId
            )
            is MdbxManagerPage.CommitHistory -> listOf("CommitHistory", page.databaseId, page.source?.name ?: "")
            is MdbxManagerPage.Health -> listOf("Health", page.databaseId, page.source?.name ?: "")
            is MdbxManagerPage.Attachments -> listOf("Attachments", page.databaseId, page.source?.name ?: "")
            is MdbxManagerPage.Maintenance -> listOf("Maintenance", page.databaseId, page.source?.name ?: "")
        }
    },
    restore = { value ->
        val list = value as? List<*> ?: return@Saver null
        when (list.firstOrNull()) {
            "Hub" -> MdbxManagerPage.Hub
            "Source" -> {
                val source = runCatching { MdbxManagerSource.valueOf(list[1] as String) }.getOrNull() ?: return@Saver null
                MdbxManagerPage.Source(source)
            }
            "Detail" -> MdbxManagerPage.Detail(list[1] as Long, parseMdbxManagerSourceOrNull(list[2] as String))
            "Conflict" -> MdbxManagerPage.Conflict(list[1] as Long, parseMdbxManagerSourceOrNull(list[2] as String))
            "Snapshots" -> MdbxManagerPage.Snapshots(list[1] as Long, parseMdbxManagerSourceOrNull(list[2] as String))
            "SnapshotStructure" -> MdbxManagerPage.SnapshotStructure(
                list[1] as Long,
                parseMdbxManagerSourceOrNull(list[2] as String),
                list[3] as String
            )
            "CommitHistory",
            "History" -> MdbxManagerPage.CommitHistory(list[1] as Long, parseMdbxManagerSourceOrNull(list[2] as String))
            "Health" -> MdbxManagerPage.Health(list[1] as Long, parseMdbxManagerSourceOrNull(list[2] as String))
            "Attachments" -> MdbxManagerPage.Attachments(list[1] as Long, parseMdbxManagerSourceOrNull(list[2] as String))
            "Advanced" -> MdbxManagerPage.Detail(list[1] as Long, parseMdbxManagerSourceOrNull(list[2] as String))
            "Maintenance" -> MdbxManagerPage.Maintenance(list[1] as Long, parseMdbxManagerSourceOrNull(list[2] as String))
            else -> null
        }
    }
)

private fun parseMdbxManagerSourceOrNull(raw: String): MdbxManagerSource? =
    raw.takeIf { it.isNotBlank() }?.let { runCatching { MdbxManagerSource.valueOf(it) }.getOrNull() }

private fun MdbxManagerPage.title(strings: StringResolver, database: LocalMdbxDatabase?): String = when (this) {
    MdbxManagerPage.Hub -> "MDBX"
    is MdbxManagerPage.Source -> when (source) {
        MdbxManagerSource.LOCAL -> strings.get(R.string.mdbx_ui_manager_local_title)
        MdbxManagerSource.WEBDAV -> strings.get(R.string.mdbx_ui_manager_webdav_title)
        MdbxManagerSource.ONEDRIVE -> strings.get(R.string.mdbx_ui_manager_onedrive_title)
    }
    is MdbxManagerPage.Detail -> database?.name ?: strings.get(R.string.mdbx_ui_manager_details_title)
    is MdbxManagerPage.Conflict -> strings.get(R.string.mdbx_ui_manager_conflicts_title)
    is MdbxManagerPage.Snapshots -> strings.get(R.string.mdbx_ui_object_snapshot)
    is MdbxManagerPage.SnapshotStructure -> strings.get(R.string.mdbx_ui_manager_snapshot_details)
    is MdbxManagerPage.CommitHistory -> strings.get(R.string.mdbx_ui_manager_history_title)
    is MdbxManagerPage.Health -> strings.get(R.string.mdbx_ui_manager_health_title)
    is MdbxManagerPage.Attachments -> strings.get(R.string.mdbx_ui_manager_attachments_title)
    is MdbxManagerPage.Maintenance -> strings.get(R.string.mdbx_ui_manager_maintenance_title)
}

@Composable
private fun MdbxManagerHubPage(
    localCount: Int,
    webDavCount: Int,
    oneDriveCount: Int,
    onOpenLocal: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenOneDrive: () -> Unit
) {
    val strings = rememberScreenStrings()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "MDBX",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                strings.get(R.string.mdbx_ui_manager_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            MdbxManagerEntryCard(
                icon = Icons.Default.Storage,
                title = strings.get(R.string.mdbx_ui_manager_local_title),
                subtitle = strings.get(R.string.mdbx_ui_manager_local_description),
                count = localCount,
                color = MaterialTheme.colorScheme.primary,
                onClick = onOpenLocal
            )
        }
        item {
            MdbxManagerEntryCard(
                icon = Icons.Default.CloudSync,
                title = strings.get(R.string.mdbx_ui_manager_webdav_title),
                subtitle = strings.get(R.string.mdbx_ui_manager_webdav_description),
                count = webDavCount,
                color = MaterialTheme.colorScheme.tertiary,
                onClick = onOpenWebDav
            )
        }
        item {
            MdbxManagerEntryCard(
                icon = Icons.Default.Cloud,
                title = strings.get(R.string.mdbx_ui_manager_onedrive_title),
                subtitle = strings.get(R.string.mdbx_ui_manager_onedrive_description),
                count = oneDriveCount,
                color = MaterialTheme.colorScheme.secondary,
                onClick = onOpenOneDrive
            )
        }
    }
}

@Composable
private fun MdbxManagerEntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    count: Int,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = color.copy(alpha = 0.14f),
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            "$count",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MdbxSourceManagementPage(
    source: MdbxManagerSource,
    databases: List<LocalMdbxDatabase>,
    conflictCounts: Map<Long, Int>,
    diagnostics: Map<Long, MdbxVaultDiagnostics>,
    onCreateClick: () -> Unit,
    onOpenClick: () -> Unit,
    onOpenDatabase: (LocalMdbxDatabase) -> Unit
) {
    val strings = rememberScreenStrings()
    val header = when (source) {
        MdbxManagerSource.LOCAL -> Triple(Icons.Default.Storage, strings.get(R.string.mdbx_ui_local_databases), strings.get(R.string.mdbx_ui_local_connected_description))
        MdbxManagerSource.WEBDAV -> Triple(Icons.Default.CloudSync, strings.get(R.string.mdbx_ui_webdav_working_copies), strings.get(R.string.mdbx_ui_webdav_working_description))
        MdbxManagerSource.ONEDRIVE -> Triple(Icons.Default.Cloud, strings.get(R.string.mdbx_ui_onedrive_working_copies), strings.get(R.string.mdbx_ui_onedrive_working_description))
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            MdbxSectionHeader(
                icon = header.first,
                title = header.second,
                subtitle = header.third,
                color = when (source) {
                    MdbxManagerSource.LOCAL -> MaterialTheme.colorScheme.primary
                    MdbxManagerSource.WEBDAV -> MaterialTheme.colorScheme.tertiary
                    MdbxManagerSource.ONEDRIVE -> MaterialTheme.colorScheme.secondary
                }
            )
        }
        if (databases.isEmpty()) {
            item {
                MdbxSourceEmptyCard(source = source, onCreateClick = onCreateClick, onOpenClick = onOpenClick)
            }
        } else {
            items(items = databases, key = { it.id }) { db ->
                MdbxVaultSmallCard(
                    database = db,
                    isDefault = db.isDefault,
                    conflictCount = conflictCounts[db.id] ?: 0,
                    diagnostics = diagnostics[db.id],
                    onOpen = { onOpenDatabase(db) }
                )
            }
            item {
                MdbxQuickActionsCard(onCreateClick = onCreateClick, onOpenClick = onOpenClick)
            }
        }
    }
}

@Composable
private fun MdbxSourceEmptyCard(
    source: MdbxManagerSource,
    onCreateClick: () -> Unit,
    onOpenClick: () -> Unit
) {
    val strings = rememberScreenStrings()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                when (source) {
                    MdbxManagerSource.LOCAL -> strings.get(R.string.mdbx_ui_local_databases_empty)
                    MdbxManagerSource.WEBDAV -> strings.get(R.string.mdbx_ui_webdav_databases_empty)
                    MdbxManagerSource.ONEDRIVE -> strings.get(R.string.mdbx_ui_onedrive_databases_empty)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                when (source) {
                    MdbxManagerSource.LOCAL -> strings.get(R.string.mdbx_ui_local_empty_description)
                    MdbxManagerSource.WEBDAV -> strings.get(R.string.mdbx_ui_webdav_empty_description)
                    MdbxManagerSource.ONEDRIVE -> strings.get(R.string.mdbx_ui_onedrive_empty_description)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                    OutlinedButton(
                        onClick = onOpenClick,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.get(R.string.attachment_open))
                    }
                    Button(
                        onClick = onCreateClick,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.mdbx_create_new_vault_button))
                    }
                }
            }
        }
    }

@Composable
private fun MdbxVaultDetailPage(
    database: LocalMdbxDatabase,
    isDefault: Boolean,
    conflictCount: Int,
    diagnostics: MdbxVaultDiagnostics?,
    onSync: () -> Unit,
    onShowConflicts: () -> Unit,
    onShowHealth: () -> Unit,
    onShowSnapshots: () -> Unit,
    onShowCommitHistory: () -> Unit,
    onShowAttachments: () -> Unit,
    onShowMaintenance: () -> Unit,
    onMigrate: (() -> Unit)?,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit
) {
    val strings = rememberScreenStrings()
    val context = LocalContext.current
    val tigaLabel = runCatching { MdbxTigaMode.valueOf(database.tigaMode).label }.getOrDefault(database.tigaMode)
    val supportsSync = database.supports(MdbxCapability.REMOTE_SYNC)
    val supportsConflicts = database.supports(MdbxCapability.CONFLICTS)
    val supportsSnapshots = database.supports(MdbxCapability.SNAPSHOTS)
    val supportsHistory = database.supports(MdbxCapability.DELTA_HISTORY)
    val healthIssueCount = diagnostics?.healthIssueCount ?: 0
    val hasUnavailableCopy = diagnostics?.isReadable == false

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = sourceColor(database).copy(alpha = 0.12f),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                sourceIcon(database),
                                contentDescription = null,
                                tint = sourceColor(database),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                database.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isDefault) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = stringResource(R.string.mdbx_default_badge),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Text(
                            "${database.engineTypeEnum.displayName()} · Tiga: $tigaLabel · ${mdbxSourceLabel(strings, database)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            database.displayPath(context, strings),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusTile(
                    modifier = Modifier.weight(1f),
                    icon = if (!supportsConflicts) {
                        Icons.Default.Info
                    } else if (conflictCount > 0) {
                        Icons.AutoMirrored.Filled.CallMerge
                    } else {
                        Icons.Default.CheckCircle
                    },
                    label = stringResource(R.string.mdbx_status_conflicts),
                    value = if (!supportsConflicts) {
                        strings.get(R.string.passkey_settings_unsupported_title)
                    } else if (conflictCount > 0) {
                        stringResource(R.string.mdbx_conflict_count_short, conflictCount)
                    } else {
                        stringResource(R.string.mdbx_no_conflicts_short)
                    },
                    isWarning = conflictCount > 0,
                    onClick = if (supportsConflicts) onShowConflicts else null
                )
                StatusTile(
                    modifier = Modifier.weight(1f),
                    icon = if (healthIssueCount > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
                    label = stringResource(R.string.mdbx_status_health),
                    value = if (healthIssueCount > 0) {
                        stringResource(R.string.mdbx_health_issues_short, healthIssueCount)
                    } else {
                        stringResource(R.string.mdbx_health_ok_short)
                    },
                    isWarning = healthIssueCount > 0,
                    onClick = onShowHealth
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusTile(
                    modifier = Modifier.weight(1f),
                    icon = if (supportsHistory) Icons.Default.History else Icons.Default.Info,
                    label = stringResource(R.string.mdbx_status_delta),
                    value = if (!supportsHistory) {
                        strings.get(R.string.passkey_settings_unsupported_title)
                    } else diagnostics?.let {
                        stringResource(R.string.mdbx_commit_tombstone_short, it.commitCount, it.tombstoneCount)
                    } ?: stringResource(R.string.mdbx_status_loading),
                    isWarning = false,
                    onClick = if (supportsHistory) onShowCommitHistory else null
                )
                StatusTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Storage,
                    label = stringResource(R.string.mdbx_status_attachments),
                    value = diagnostics?.let {
                        stringResource(
                            R.string.mdbx_attachment_short,
                            it.attachmentCount,
                            it.externalAttachmentCount,
                            formatBytes(it.storedAttachmentBytes)
                        )
                    } ?: stringResource(R.string.mdbx_status_loading),
                    isWarning = false,
                    onClick = onShowAttachments
                )
            }
        }

        diagnostics?.let { diagnostic ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DiagnosticLine(
                            icon = if (diagnostic.isReadable) Icons.Default.CloudSync else Icons.Default.CloudOff,
                            label = stringResource(R.string.mdbx_sync_status_label),
                            value = diagnostic.lastSyncStatus
                        )
                        DiagnosticLine(
                            icon = Icons.Default.Security,
                            label = stringResource(R.string.mdbx_compatibility_label),
                            value = mdbxCompatibilityValue(diagnostic, database)
                        )
                        DiagnosticLine(
                            icon = Icons.Default.Sync,
                            label = stringResource(R.string.mdbx_recovery_label),
                            value = if (diagnostic.structuralIssueCount == 0 && diagnostic.integrityOk) {
                                stringResource(R.string.mdbx_recovery_clean)
                            } else {
                                stringResource(
                                    R.string.mdbx_recovery_issue_value,
                                    diagnostic.structuralIssueCount,
                                    diagnostic.integrityMessage ?: "-"
                                )
                            }
                        )
                        DiagnosticLine(
                            icon = Icons.Default.Info,
                            label = stringResource(R.string.mdbx_file_size_label),
                            value = formatBytes(diagnostic.fileSizeBytes)
                        )
                        DiagnosticLine(
                            icon = Icons.Default.Storage,
                            label = strings.get(R.string.mdbx_ui_client),
                            value = diagnostic.currentDeviceId ?: "-"
                        )
                        DiagnosticLine(
                            icon = Icons.Default.Folder,
                            label = strings.get(R.string.mdbx_ui_catalog_index),
                            value = "${diagnostic.folderCount} folders · ${diagnostic.indexedObjectCount} indexed"
                        )
                    }
                }
            }
            if (hasUnavailableCopy) {
                item {
                    Text(
                        diagnostic.unavailableReason ?: stringResource(R.string.mdbx_unavailable_local_copy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        item {
            MdbxDetailActionList(
                isDefault = isDefault,
                conflictCount = conflictCount,
                allowSync = supportsSync,
                allowConflicts = supportsConflicts,
                allowSnapshots = supportsSnapshots,
                allowCommitHistory = supportsHistory,
                onSync = onSync,
                onShowConflicts = onShowConflicts,
                onShowSnapshots = onShowSnapshots,
                onShowCommitHistory = onShowCommitHistory,
                onShowMaintenance = onShowMaintenance,
                onMigrate = onMigrate,
                onSetDefault = onSetDefault,
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun MdbxDetailActionList(
    isDefault: Boolean,
    conflictCount: Int,
    allowSync: Boolean,
    allowConflicts: Boolean,
    allowSnapshots: Boolean,
    allowCommitHistory: Boolean,
    onSync: () -> Unit,
    onShowConflicts: () -> Unit,
    onShowSnapshots: () -> Unit,
    onShowCommitHistory: () -> Unit,
    onShowMaintenance: () -> Unit,
    onMigrate: (() -> Unit)?,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit
) {
    val strings = rememberScreenStrings()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            if (!isDefault) {
                MdbxNavigationActionRow(Icons.Default.Star, stringResource(R.string.mdbx_set_default), onSetDefault)
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            }
            if (allowSync) {
                MdbxNavigationActionRow(Icons.Default.Sync, strings.get(R.string.mdbx_sync_status_label), onSync)
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            }
            if (allowConflicts) {
                MdbxNavigationActionRow(
                    Icons.AutoMirrored.Filled.CallMerge,
                    if (conflictCount > 0) strings.get(R.string.mdbx_ui_conflict_management_count, conflictCount) else strings.get(R.string.mdbx_ui_manager_conflicts_title),
                    onShowConflicts
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            }
            if (allowSnapshots) {
                MdbxNavigationActionRow(Icons.Default.Restore, strings.get(R.string.mdbx_ui_object_snapshot), onShowSnapshots)
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            }
            if (allowCommitHistory) {
                MdbxNavigationActionRow(Icons.Default.History, strings.get(R.string.mdbx_ui_manager_history_title), onShowCommitHistory)
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            }
            onMigrate?.let { migrate ->
                MdbxNavigationActionRow(Icons.Default.SwapHoriz, strings.get(R.string.mdbx_ui_migrate_to_mdbx2), migrate)
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            }
            MdbxNavigationActionRow(Icons.Default.ReportProblem, strings.get(R.string.mdbx_ui_manager_maintenance_title), onShowMaintenance)
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            MdbxNavigationActionRow(
                icon = Icons.Default.Delete,
                title = stringResource(R.string.mdbx_delete),
                onClick = onDelete,
                isDestructive = true,
                showChevron = false
            )
        }
    }
}

@Composable
private fun MdbxNavigationActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false,
    showChevron: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(18.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (showChevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MdbxConflictPage(
    state: MdbxViewModel.MdbxConflictDialogState.Visible?,
    databaseName: String,
    onResolve: (String, MdbxConflictResolution) -> Unit
) {
    val strings = rememberScreenStrings()
    var selectedConflictId by rememberSaveable(state?.databaseId ?: -1L) { mutableStateOf<String?>(null) }
    val selectedConflict = state?.conflicts?.firstOrNull { it.conflictId == selectedConflictId }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            if (selectedConflict != null) {
                TextButton(onClick = { selectedConflictId = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.get(R.string.mdbx_ui_back_to_conflicts))
                }
            } else {
                val visibleConflictCount = state?.conflicts?.size ?: 0
                MdbxDetailHeroCard(
                    icon = if (visibleConflictCount > 0) {
                        Icons.AutoMirrored.Filled.CallMerge
                    } else {
                        Icons.Default.CheckCircle
                    },
                    title = when {
                        state == null || state.isLoading -> strings.get(R.string.mdbx_ui_conflicts_loading)
                        visibleConflictCount > 0 -> strings.get(R.string.mdbx_ui_conflicts_pending_count, visibleConflictCount)
                        else -> strings.get(R.string.mdbx_ui_conflicts_none)
                    },
                    subtitle = when {
                        state == null || state.isLoading -> strings.get(R.string.mdbx_ui_conflicts_checking_branches, state?.databaseName ?: databaseName)
                        visibleConflictCount > 0 -> strings.get(R.string.mdbx_ui_conflicts_review_description)
                        else -> strings.get(R.string.mdbx_ui_conflicts_branches_consistent, state.databaseName)
                    },
                    warning = visibleConflictCount > 0
                )
            }
        }
        if (state == null || state.isLoading) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        if (selectedConflict != null) {
            item {
                ConflictDiffDetail(
                    conflict = selectedConflict,
                    enabled = !state.isLoading,
                    onResolve = onResolve
                )
            }
        } else if (state != null && state.conflicts.isNotEmpty()) {
            items(items = state.conflicts, key = { it.conflictId }) { conflict ->
                ConflictSummaryRow(
                    conflict = conflict,
                    onOpen = { selectedConflictId = conflict.conflictId }
                )
            }
        }
    }
}

@Composable
private fun ConflictSummaryRow(
    conflict: MdbxConflictSummary,
    onOpen: () -> Unit
) {
    val strings = rememberScreenStrings()
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.CallMerge,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "${objectTypeLabel(strings, conflict.objectType)} · ${shortId(conflict.objectId)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(R.string.mdbx_conflict_fields_value, conflict.conflictingFields),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    strings.get(R.string.mdbx_ui_conflict_commit_ids, shortId(conflict.localCommitId), shortId(conflict.incomingCommitId), conflict.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConflictDiffDetail(
    conflict: MdbxConflictSummary,
    enabled: Boolean,
    onResolve: (String, MdbxConflictResolution) -> Unit
) {
    val strings = rememberScreenStrings()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldDiffPanel(
            title = strings.get(R.string.mdbx_ui_conflict_details),
            subtitle = strings.get(R.string.mdbx_ui_conflict_object_baseline, objectTypeLabel(strings, conflict.objectType), shortId(conflict.objectId), shortId(conflict.baseCommitId)),
            changes = conflict.toFieldChanges(strings)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onResolve(conflict.conflictId, MdbxConflictResolution.LOCAL_WINS) },
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.mdbx_conflict_local_wins))
            }
            Button(
                onClick = { onResolve(conflict.conflictId, MdbxConflictResolution.INCOMING_WINS) },
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.mdbx_conflict_incoming_wins))
            }
        }
        TextButton(
            onClick = { onResolve(conflict.conflictId, MdbxConflictResolution.MARK_RESOLVED) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            Text(stringResource(R.string.mdbx_conflict_mark_resolved))
        }
    }
}

@Composable
private fun MdbxSnapshotPage(
    state: MdbxViewModel.MdbxDeltaDialogState.Visible?,
    engineAlwaysCreatesFullSnapshots: Boolean,
    onShowDiff: (String) -> Unit,
    onShowSnapshotStructure: (String) -> Unit,
    onCreateSnapshot: (
        String,
        Boolean,
        (MdbxViewModel.MdbxSnapshotCreateOutcome) -> Unit
    ) -> Unit,
    onDeleteSnapshot: (String) -> Unit,
    onRevertSnapshot: (String) -> Unit,
    onPruneAutomaticSnapshots: () -> Unit
) {
    val strings = rememberScreenStrings()
    var snapshotName by rememberSaveable(state?.databaseId ?: -1L) { mutableStateOf("") }
    var fullSnapshot by rememberSaveable(state?.databaseId ?: -1L) { mutableStateOf(false) }
    var pendingRevertSnapshot by remember { mutableStateOf<MdbxSnapshotSummary?>(null) }
    var pendingDeleteSnapshot by remember { mutableStateOf<MdbxSnapshotSummary?>(null) }
    var pendingNoChangesSnapshotRequest by remember(state?.databaseId) {
        mutableStateOf<String?>(null)
    }
    var showPruneAutomaticConfirmation by remember { mutableStateOf(false) }
    val manualSnapshots = state?.snapshots?.filterNot { it.autoPrune }.orEmpty()
    val automaticSnapshots = state?.snapshots?.filter { it.autoPrune }.orEmpty()

    pendingRevertSnapshot?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { pendingRevertSnapshot = null },
            icon = { Icon(Icons.Default.Restore, contentDescription = null) },
            title = { Text(strings.get(R.string.mdbx_ui_snapshot_restore_title)) },
            text = {
                Text(
                    strings.get(R.string.mdbx_ui_snapshot_restore_state, snapshot.displayName(strings))
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRevertSnapshot = null
                        onRevertSnapshot(snapshot.snapshotId)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(strings.get(R.string.mdbx_ui_snapshot_restore_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevertSnapshot = null }) {
                    Text(strings.get(R.string.cancel))
                }
            }
        )
    }

    pendingDeleteSnapshot?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSnapshot = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text(strings.get(R.string.mdbx_ui_snapshot_delete_title)) },
            text = {
                Text(strings.get(R.string.mdbx_ui_snapshot_delete_description, snapshot.displayName(strings)))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteSnapshot = null
                        onDeleteSnapshot(snapshot.snapshotId)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(strings.get(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSnapshot = null }) {
                    Text(strings.get(R.string.cancel))
                }
            }
        )
    }

    if (showPruneAutomaticConfirmation) {
        AlertDialog(
            onDismissRequest = { showPruneAutomaticConfirmation = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text(strings.get(R.string.mdbx_ui_snapshot_prune_title)) },
            text = {
                Text(strings.get(R.string.mdbx_ui_snapshot_prune_description, automaticSnapshots.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPruneAutomaticConfirmation = false
                        onPruneAutomaticSnapshots()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(strings.get(R.string.mdbx_ui_snapshot_prune_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPruneAutomaticConfirmation = false }) {
                    Text(strings.get(R.string.cancel))
                }
            }
        )
    }

    pendingNoChangesSnapshotRequest?.let { pendingName ->
        AlertDialog(
            onDismissRequest = { pendingNoChangesSnapshotRequest = null },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text(stringResource(R.string.mdbx_snapshot_no_changes_title)) },
            text = { Text(stringResource(R.string.mdbx_snapshot_no_changes_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingNoChangesSnapshotRequest = null
                        onCreateSnapshot(pendingName, true) { outcome ->
                            if (outcome is MdbxViewModel.MdbxSnapshotCreateOutcome.Created) {
                                snapshotName = ""
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.mdbx_snapshot_create_full_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingNoChangesSnapshotRequest = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (
            state == null ||
            state.isLoading ||
            state.isDiffLoading ||
            state.isSnapshotLoading
        ) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        state?.let { visibleState ->
            val selectedCommitId = visibleState.selectedDiffCommitId
            if (selectedCommitId != null) {
                item {
                    CommitDetailHeader(
                        commitId = selectedCommitId,
                        delta = visibleState.deltas.firstOrNull { it.commitId == selectedCommitId },
                        diffItems = visibleState.diffItems
                    )
                }
                if (visibleState.diffError != null && !visibleState.isDiffLoading) {
                    item { CommitDiffErrorCard(visibleState.diffError) }
                } else if (visibleState.diffItems.isEmpty() && !visibleState.isDiffLoading) {
                    item {
                        CommitEventExplanationCard(
                            presentation = visibleState.deltas
                                .firstOrNull { it.commitId == selectedCommitId }
                                ?.toHistoryPresentation(strings),
                            commitId = selectedCommitId
                        )
                    }
                } else {
                    items(
                        items = visibleState.diffItems,
                        key = { diff -> "snapshot-diff:${diff.objectType}:${diff.objectId}" }
                    ) { diff ->
                        CommitObjectChangeCard(diff)
                    }
                }
                item {
                    CommitTechnicalInfoCard(
                        commitId = selectedCommitId,
                        delta = visibleState.deltas.firstOrNull { it.commitId == selectedCommitId }
                    )
                }
            } else {
                item {
                    SnapshotCreationCard(
                        snapshotName = snapshotName,
                        onSnapshotNameChange = { snapshotName = it },
                        fullSnapshot = fullSnapshot,
                        onFullSnapshotChange = { fullSnapshot = it },
                        engineAlwaysCreatesFullSnapshots = engineAlwaysCreatesFullSnapshots,
                        enabled = !visibleState.isLoading && !visibleState.isSnapshotLoading,
                        onCreateSnapshot = {
                            val requestedName = snapshotName
                            onCreateSnapshot(requestedName, fullSnapshot) { outcome ->
                                when (outcome) {
                                    is MdbxViewModel.MdbxSnapshotCreateOutcome.Created -> {
                                        snapshotName = ""
                                    }
                                    MdbxViewModel.MdbxSnapshotCreateOutcome.NoChanges -> {
                                        pendingNoChangesSnapshotRequest = requestedName
                                    }
                                    is MdbxViewModel.MdbxSnapshotCreateOutcome.Failed -> Unit
                                }
                            }
                        }
                    )
                }
                item {
                    SnapshotListHeader(
                        manualSnapshotCount = manualSnapshots.size,
                        automaticSnapshotCount = automaticSnapshots.size,
                        enabled = !visibleState.isLoading && !visibleState.isSnapshotLoading,
                        onPruneAutomaticSnapshots = { showPruneAutomaticConfirmation = true }
                    )
                }
                if (visibleState.snapshots.isEmpty() && !visibleState.isSnapshotLoading) {
                    item { SnapshotEmptyState() }
                } else {
                    items(
                        items = visibleState.snapshots.take(30),
                        key = MdbxSnapshotSummary::snapshotId
                    ) { snapshot ->
                        SnapshotRow(
                            snapshot = snapshot,
                            enabled = !visibleState.isLoading && !visibleState.isSnapshotLoading,
                            onShowDiff = { onShowDiff(snapshot.baseCommitId) },
                            onOpenStructure = { onShowSnapshotStructure(snapshot.snapshotId) },
                            onDelete = { pendingDeleteSnapshot = snapshot },
                            onRevert = { pendingRevertSnapshot = snapshot }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MdbxCommitHistoryPage(
    state: MdbxViewModel.MdbxDeltaDialogState.Visible?,
    onShowDiff: (String) -> Unit,
    onRevert: (String) -> Unit
) {
    val strings = rememberScreenStrings()
    var pendingRevert by remember { mutableStateOf<MdbxDeltaSummary?>(null) }
    var expandedGroups by remember { mutableStateOf<Set<ObjectChangeKind>>(emptySet()) }
    val selectedCommitId = state?.selectedDiffCommitId
    val selectedDelta = remember(state?.deltas, selectedCommitId) {
        state?.deltas?.firstOrNull { it.commitId == selectedCommitId }
    }
    val groupedDiffs = remember(state?.diffItems) {
        state?.diffItems.orEmpty()
            .groupBy(MdbxCommitDiff::objectChangeKind)
            .toSortedMap(compareBy { it.sortOrder })
    }

    LaunchedEffect(selectedCommitId, state?.isDiffLoading, groupedDiffs.keys) {
        if (selectedCommitId == null || state?.isDiffLoading == true) {
            expandedGroups = emptySet()
        } else if (state?.diffItems.orEmpty().size <= AUTO_EXPAND_COMMIT_OBJECT_LIMIT) {
            expandedGroups = groupedDiffs.keys
        }
    }

    pendingRevert?.let { delta ->
        val presentation = delta.toHistoryPresentation(strings)
        AlertDialog(
            onDismissRequest = { pendingRevert = null },
            icon = { Icon(Icons.Default.Restore, contentDescription = null) },
            title = { Text(strings.get(R.string.mdbx_ui_history_revert_title)) },
            text = {
                Text(
                    strings.get(R.string.mdbx_ui_history_revert_objects, presentation.objectCount)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRevert = null
                        onRevert(delta.commitId)
                    }
                ) {
                    Text(strings.get(R.string.mdbx_ui_history_revert_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevert = null }) {
                    Text(strings.get(R.string.cancel))
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state == null || state.isLoading) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        state?.let { visibleState ->
            if (selectedCommitId != null) {
                item {
                    CommitDetailHeader(
                        commitId = selectedCommitId,
                        delta = selectedDelta,
                        diffItems = visibleState.diffItems
                    )
                }
                if (visibleState.isDiffLoading) {
                    item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                } else if (visibleState.diffError != null) {
                    item { CommitDiffErrorCard(visibleState.diffError) }
                } else if (visibleState.diffItems.isEmpty()) {
                    item {
                        CommitEventExplanationCard(
                            presentation = selectedDelta?.toHistoryPresentation(strings),
                            commitId = selectedCommitId
                        )
                    }
                } else {
                    groupedDiffs.forEach { (kind, diffs) ->
                        val expanded = kind in expandedGroups
                        item(key = "group-${kind.name}") {
                            CommitChangeGroupHeader(
                                kind = kind,
                                count = diffs.size,
                                expanded = expanded,
                                onToggle = {
                                    expandedGroups = if (expanded) {
                                        expandedGroups - kind
                                    } else {
                                        expandedGroups + kind
                                    }
                                }
                            )
                        }
                        if (expanded) {
                            items(
                                items = diffs,
                                key = { diff -> "${kind.name}:${diff.objectType}:${diff.objectId}" }
                            ) { diff ->
                                CommitObjectChangeCard(diff)
                            }
                        }
                    }
                }
                item {
                    CommitTechnicalInfoCard(
                        commitId = selectedCommitId,
                        delta = selectedDelta
                    )
                }
                selectedDelta
                    ?.takeIf { it.toHistoryPresentation(strings).canRevert }
                    ?.let { revertableDelta ->
                        item {
                            OutlinedButton(
                                onClick = { pendingRevert = revertableDelta },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Default.Restore, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(strings.get(R.string.mdbx_ui_history_revert_action))
                            }
                        }
                    }
            } else {
                item {
                    CommitHistoryListHeader(commitCount = visibleState.deltas.size)
                }
                if (visibleState.deltas.isEmpty() && !visibleState.isLoading) {
                    item { CommitHistoryEmptyState() }
                }
                items(items = visibleState.deltas, key = { it.commitId }) { delta ->
                    DeltaRow(
                        delta = delta,
                        onShowDiff = { onShowDiff(delta.commitId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CommitHistoryListHeader(commitCount: Int) {
    val strings = rememberScreenStrings()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            strings.get(R.string.mdbx_ui_history_record_count, commitCount),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            strings.get(R.string.mdbx_ui_history_order_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CommitHistoryEmptyState() {
    val strings = rememberScreenStrings()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    strings.get(R.string.mdbx_ui_history_empty),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    strings.get(R.string.mdbx_ui_history_empty_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MdbxAdvancedToolsPage(
    state: MdbxViewModel.MdbxAdvancedDialogState.Visible?,
    databaseName: String,
    onExportBundle: (String?) -> Unit,
    onImportBundle: (String) -> Unit,
    onFlushPendingUpload: () -> Unit,
    onRunBenchmark: (Int) -> Unit
) {
    val strings = rememberScreenStrings()
    val context = LocalContext.current
    var baseCommitId by rememberSaveable(state?.databaseId ?: -1L) { mutableStateOf("") }
    var importJson by rememberSaveable(state?.databaseId ?: -1L) { mutableStateOf("") }
    var benchmarkCountText by rememberSaveable(state?.databaseId ?: -1L) { mutableStateOf("10") }
    val benchmarkCount = benchmarkCountText.toIntOrNull()?.coerceIn(1, 500) ?: 10
    val diagnostics = state?.diagnostics
    val isLoading = state?.isLoading == true

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                strings.get(R.string.mdbx_ui_advanced_tools_title, state?.databaseName ?: databaseName),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (state == null || isLoading) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        state?.message?.takeIf { it.isNotBlank() }?.let { message ->
            item {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            AdvancedToolSection(title = "Oplog / Sync bundle") {
                OutlinedTextField(
                    value = baseCommitId,
                    onValueChange = { baseCommitId = it },
                    label = { Text(strings.get(R.string.mdbx_ui_base_commit_optional)) },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onExportBundle(baseCommitId.trim().takeIf { it.isNotBlank() }) },
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.get(R.string.export))
                    }
                    OutlinedButton(
                        onClick = {
                            state?.exportedBundleJson?.let {
                                ClipboardUtils.copyToClipboard(context, it, "MDBX sync bundle")
                            }
                        },
                        enabled = !state?.exportedBundleJson.isNullOrBlank(),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.get(R.string.copy))
                    }
                }
                state?.lastExportedBundle?.let { bundle ->
                    Text(
                        "head ${shortId(bundle.headCommitId)} · ${bundle.commitCount} commits · ${bundle.payloadHash.take(12)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = importJson,
                    onValueChange = { importJson = it },
                    label = { Text(strings.get(R.string.mdbx_ui_bundle_paste)) },
                    minLines = 3,
                    maxLines = 6,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { onImportBundle(importJson) },
                    enabled = !isLoading && importJson.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.get(R.string.mdbx_ui_bundle_import))
                }
            }
        }
        item {
            AdvancedToolSection(title = strings.get(R.string.mdbx_ui_background_upload)) {
                DiagnosticLine(Icons.Default.Sync, strings.get(R.string.keepass_remote_sync_status), diagnostics?.lastSyncStatus ?: "-")
                Button(
                    onClick = onFlushPendingUpload,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.CloudSync, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.get(R.string.mdbx_ui_upload_pending_now))
                }
            }
        }
        item {
            AdvancedToolSection(title = strings.get(R.string.mdbx_ui_attachment_storage_format)) {
                DiagnosticLine(
                    Icons.Default.Storage,
                    strings.get(R.string.attachments),
                    diagnostics?.let { "${it.attachmentCount} total · ${it.externalAttachmentCount} external" } ?: "-"
                )
                DiagnosticLine(
                    Icons.Default.Folder,
                    strings.get(R.string.mdbx_ui_storage),
                    diagnostics?.let {
                        "${formatBytes(it.originalAttachmentBytes)} original · ${formatBytes(it.storedAttachmentBytes)} stored"
                    } ?: "-"
                )
                DiagnosticLine(
                    if ((diagnostics?.attachmentChunkMismatchCount ?: 0) > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
                    strings.get(R.string.mdbx_ui_chunk_verification),
                    diagnostics?.let { "${it.attachmentChunkMismatchCount} mismatch" } ?: "-"
                )
            }
        }
        item {
            AdvancedToolSection(title = strings.get(R.string.mdbx_ui_performance_benchmark)) {
                OutlinedTextField(
                    value = benchmarkCountText,
                    onValueChange = { value -> benchmarkCountText = value.filter { it.isDigit() }.take(3) },
                    label = { Text(strings.get(R.string.mdbx_ui_benchmark_commit_count)) },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { onRunBenchmark(benchmarkCount) },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.get(R.string.mdbx_ui_benchmark_run))
                }
                state?.lastBenchmarkResult?.let { result ->
                    Text(
                        "${result.operationCount} commits · ${result.elapsedMs} ms · ${formatBytes(result.fileDeltaBytes)} file delta",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MdbxMaintenancePage(
    database: LocalMdbxDatabase,
    diagnostics: MdbxVaultDiagnostics?,
    allowSync: Boolean,
    allowPendingUpload: Boolean,
    onRefreshDiagnostics: () -> Unit,
    onSync: () -> Unit,
    onFlushPendingUpload: () -> Unit
) {
    val strings = rememberScreenStrings()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                strings.get(R.string.mdbx_ui_maintenance_database_title, database.name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                strings.get(R.string.mdbx_ui_maintenance_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            MaintenanceActionPanel(
                allowSync = allowSync,
                allowPendingUpload = allowPendingUpload,
                onRefreshDiagnostics = onRefreshDiagnostics,
                onSync = onSync,
                onFlushPendingUpload = onFlushPendingUpload
            )
        }

        item {
            MdbxDiagnosticOverviewCard(database = database, diagnostics = diagnostics)
        }

        diagnostics?.let { diagnostic ->
            item {
                MdbxDiagnosticSection(title = strings.get(R.string.mdbx_ui_key_metrics)) {
                    DiagnosticLine(Icons.Default.Sync, strings.get(R.string.sync_status_pending_badge), diagnostic.pendingSyncCount.toString())
                    DiagnosticLine(Icons.AutoMirrored.Filled.CallMerge, strings.get(R.string.mdbx_ui_unresolved_conflicts), diagnostic.unresolvedConflictCount.toString())
                    DiagnosticLine(Icons.Default.History, strings.get(R.string.mdbx_ui_commits_and_snapshots), "${diagnostic.commitCount} / ${diagnostic.snapshotCount}")
                    DiagnosticLine(Icons.Default.Folder, strings.get(R.string.mdbx_ui_entries_and_folders), "${diagnostic.entryCount} / ${diagnostic.folderCount}")
                    DiagnosticLine(Icons.Default.Storage, strings.get(R.string.attachments), strings.get(R.string.mdbx_ui_attachment_count_and_size, diagnostic.attachmentCount, formatBytes(diagnostic.storedAttachmentBytes)))
                }
            }
            item {
                MdbxDiagnosticSection(title = strings.get(R.string.mdbx_ui_advanced_details)) {
                    DiagnosticLine(Icons.Default.Security, strings.get(R.string.mdbx_ui_format_and_tiga), mdbxCompatibilityValue(diagnostic, database))
                    DiagnosticLine(Icons.Default.Storage, strings.get(R.string.mdbx_ui_branches_and_devices), "${diagnostic.branchCount} / ${diagnostic.deviceCount}")
                    DiagnosticLine(Icons.Default.Delete, strings.get(R.string.mdbx_ui_deletion_markers), diagnostic.tombstoneCount.toString())
                    DiagnosticLine(Icons.Default.Storage, strings.get(R.string.mdbx_ui_indexed_objects), diagnostic.indexedObjectCount.toString())
                    DiagnosticLine(Icons.Default.Storage, strings.get(R.string.mdbx_ui_external_attachments), strings.get(R.string.mdbx_ui_external_attachment_size, diagnostic.externalAttachmentCount, formatBytes(diagnostic.originalAttachmentBytes)))
                    DiagnosticLine(
                        if (diagnostic.attachmentChunkMismatchCount > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
                        strings.get(R.string.mdbx_ui_attachment_chunk_issues),
                        diagnostic.attachmentChunkMismatchCount.toString()
                    )
                    DiagnosticLine(Icons.Default.Warning, strings.get(R.string.mdbx_ui_dangling_parents), diagnostic.danglingParentCount.toString())
                    DiagnosticLine(Icons.Default.Warning, strings.get(R.string.mdbx_ui_dangling_heads), "${diagnostic.danglingBranchHeadCount} branch · ${diagnostic.danglingDeviceHeadCount} device")
                    DiagnosticLine(
                        if (diagnostic.isReadable) Icons.Default.CheckCircle else Icons.Default.CloudOff,
                        strings.get(R.string.mdbx_ui_readable),
                        if (diagnostic.isReadable) strings.get(R.string.yes) else (diagnostic.unavailableReason ?: strings.get(R.string.no))
                    )
                    DiagnosticLine(Icons.Default.Folder, strings.get(R.string.mdbx_file_size_label), diagnostic.filePath ?: "-")
                }
            }
        } ?: item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(strings.get(R.string.mdbx_ui_diagnostics_waiting), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun MaintenanceActionPanel(
    allowSync: Boolean,
    allowPendingUpload: Boolean,
    onRefreshDiagnostics: () -> Unit,
    onSync: () -> Unit,
    onFlushPendingUpload: () -> Unit
) {
    val strings = rememberScreenStrings()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(strings.get(R.string.mdbx_ui_maintenance_actions), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRefreshDiagnostics,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.get(R.string.refresh))
                }
                if (allowSync) {
                    OutlinedButton(
                        onClick = onSync,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.get(R.string.mdbx_sync_status_label))
                    }
                }
            }
            if (allowPendingUpload) {
                FilledTonalButton(
                    onClick = onFlushPendingUpload,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                ) {
                    Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.get(R.string.mdbx_ui_upload_pending))
                }
            }
        }
    }
}

private fun MdbxEngineType.displayName(): String = when (this) {
    MdbxEngineType.KOTLIN_MDBX1 -> "MDBX1"
    MdbxEngineType.RUST_MDBX2 -> "MDBX2"
}

@Composable
private fun MdbxDiagnosticOverviewCard(
    database: LocalMdbxDatabase,
    diagnostics: MdbxVaultDiagnostics?
) {
    val strings = rememberScreenStrings()
    val healthIssueCount = diagnostics?.healthIssueCount ?: 0
    val healthText = when {
        diagnostics == null -> strings.get(R.string.mdbx_status_loading)
        healthIssueCount > 0 -> strings.get(R.string.mdbx_ui_health_needs_action_count, healthIssueCount)
        else -> strings.get(R.string.mdbx_health_ok_short)
    }
    val syncText = diagnostics?.let { diagnostic ->
        if (diagnostic.pendingSyncCount > 0) {
            strings.get(R.string.mdbx_ui_sync_pending_summary, diagnostic.lastSyncStatus, diagnostic.pendingSyncCount)
        } else {
            diagnostic.lastSyncStatus
        }
    } ?: database.lastSyncStatus
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = sourceColor(database).copy(alpha = 0.12f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(sourceIcon(database), contentDescription = null, tint = sourceColor(database))
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(database.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${mdbxSourceLabel(strings, database)} · ${diagnostics?.lastSyncStatus ?: database.lastSyncStatus}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider()
            DiagnosticLine(
                icon = if (healthIssueCount > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
                label = strings.get(R.string.mdbx_status_health),
                value = healthText
            )
            DiagnosticLine(Icons.Default.Sync, strings.get(R.string.mdbx_sync_status_label), syncText)
            DiagnosticLine(
                icon = if (diagnostics?.isReadable == false) Icons.Default.CloudOff else Icons.Default.Storage,
                label = strings.get(R.string.mdbx_file_size_label),
                value = diagnostics?.let { "${formatBytes(it.fileSizeBytes)} · ${it.filePath ?: "-"}" } ?: "-"
            )
            diagnostics?.lastSyncError?.takeIf { it.isNotBlank() }?.let { error ->
                DiagnosticLine(Icons.Default.Warning, strings.get(R.string.mdbx_ui_latest_error), error)
            }
        }
    }
}

@Composable
private fun MdbxDiagnosticSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun EmptyMdbxState(
    onCreateClick: () -> Unit,
    onOpenClick: () -> Unit
) {
    val strings = rememberScreenStrings()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(80.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Storage,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            stringResource(R.string.mdbx_no_vaults),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.mdbx_create_first_vault),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(28.dp))
        Row(
            modifier = Modifier.fillMaxWidth(0.88f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onOpenClick,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(strings.get(R.string.attachment_open))
            }
            Button(
                onClick = onCreateClick,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.mdbx_create_new_vault_button))
            }
        }
    }
}

@Composable
private fun MdbxSectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = color.copy(alpha = 0.12f),
            modifier = Modifier.size(32.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = color
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MdbxQuickActionsCard(
    onCreateClick: () -> Unit,
    onOpenClick: () -> Unit
) {
    val strings = rememberScreenStrings()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCreateClick)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.mdbx_create_new_vault_button),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        strings.get(R.string.mdbx_ui_create_database_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenClick)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        strings.get(R.string.mdbx_ui_open_existing_database),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        strings.get(R.string.mdbx_ui_open_database_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun sourceColor(database: LocalMdbxDatabase): Color =
    when (database.sourceTypeEnum) {
        MdbxSourceType.LOCAL_INTERNAL -> MaterialTheme.colorScheme.primary
        MdbxSourceType.LOCAL_EXTERNAL -> MaterialTheme.colorScheme.secondary
        MdbxSourceType.REMOTE_WEBDAV -> MaterialTheme.colorScheme.tertiary
        MdbxSourceType.REMOTE_ONEDRIVE -> MaterialTheme.colorScheme.secondary
    }

private fun sourceIcon(database: LocalMdbxDatabase): ImageVector =
    when (database.sourceTypeEnum) {
        MdbxSourceType.LOCAL_INTERNAL -> Icons.Default.Security
        MdbxSourceType.LOCAL_EXTERNAL -> Icons.Default.Folder
        MdbxSourceType.REMOTE_WEBDAV -> Icons.Default.CloudSync
        MdbxSourceType.REMOTE_ONEDRIVE -> Icons.Default.Cloud
    }

private fun mdbxSourceLabel(strings: StringResolver, database: LocalMdbxDatabase): String =
    when (database.sourceTypeEnum) {
        MdbxSourceType.LOCAL_INTERNAL -> strings.get(R.string.mdbx_ui_private_directory)
        MdbxSourceType.LOCAL_EXTERNAL -> strings.get(R.string.mdbx_ui_local_file)
        MdbxSourceType.REMOTE_WEBDAV -> "WebDAV"
        MdbxSourceType.REMOTE_ONEDRIVE -> "OneDrive"
    }

private fun LocalMdbxDatabase.managerSource(): MdbxManagerSource =
    when (sourceTypeEnum) {
        MdbxSourceType.LOCAL_INTERNAL,
        MdbxSourceType.LOCAL_EXTERNAL -> MdbxManagerSource.LOCAL
        MdbxSourceType.REMOTE_WEBDAV -> MdbxManagerSource.WEBDAV
        MdbxSourceType.REMOTE_ONEDRIVE -> MdbxManagerSource.ONEDRIVE
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MdbxVaultSmallCard(
    database: LocalMdbxDatabase,
    isDefault: Boolean,
    conflictCount: Int,
    diagnostics: MdbxVaultDiagnostics?,
    onOpen: () -> Unit
) {
    val strings = rememberScreenStrings()
    val context = LocalContext.current
    val healthIssueCount = diagnostics?.healthIssueCount ?: 0
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = sourceColor(database).copy(alpha = 0.12f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            sourceIcon(database),
                            contentDescription = null,
                            tint = sourceColor(database),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            database.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isDefault) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Star,
                                contentDescription = stringResource(R.string.mdbx_default_badge),
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        "${mdbxSourceLabel(strings, database)} · ${diagnostics?.lastSyncStatus ?: database.lastSyncStatus}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        database.displayPath(context, strings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = onOpen,
                    label = {
                        Text(if (conflictCount > 0) strings.get(R.string.mdbx_ui_conflict_count_badge, conflictCount) else strings.get(R.string.mdbx_ui_conflicts_clean))
                    },
                    leadingIcon = {
                        Icon(
                            if (conflictCount > 0) Icons.AutoMirrored.Filled.CallMerge else Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                AssistChip(
                    onClick = onOpen,
                    label = {
                        Text(if (healthIssueCount > 0) strings.get(R.string.mdbx_ui_health_count_badge, healthIssueCount) else strings.get(R.string.mdbx_ui_health_clean))
                    },
                    leadingIcon = {
                        Icon(
                            if (healthIssueCount > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MdbxVaultDetailBottomSheet(
    database: LocalMdbxDatabase,
    isDefault: Boolean,
    conflictCount: Int,
    diagnostics: MdbxVaultDiagnostics?,
    onDismiss: () -> Unit,
    onSync: () -> Unit,
    onShowConflicts: () -> Unit,
    onShowSnapshots: () -> Unit,
    onShowCommitHistory: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit
) {
    val strings = rememberScreenStrings()
    val context = LocalContext.current
    val tigaLabel = try {
        MdbxTigaMode.valueOf(database.tigaMode).label
    } catch (_: IllegalArgumentException) {
        database.tigaMode
    }

    val healthIssueCount = diagnostics?.healthIssueCount ?: 0
    val hasUnavailableCopy = diagnostics?.isReadable == false

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = sourceColor(database).copy(alpha = 0.12f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            sourceIcon(database),
                            contentDescription = null,
                            tint = sourceColor(database),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            database.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isDefault) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Star,
                                contentDescription = stringResource(R.string.mdbx_default_badge),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Tiga: $tigaLabel · ${mdbxSourceLabel(strings, database)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (database.filePath.isNotBlank()) {
                        Text(
                            database.displayPath(context, strings),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusTile(
                    modifier = Modifier.weight(1f),
                    icon = if (conflictCount > 0) Icons.AutoMirrored.Filled.CallMerge else Icons.Default.CheckCircle,
                    label = stringResource(R.string.mdbx_status_conflicts),
                    value = if (conflictCount > 0) {
                        stringResource(R.string.mdbx_conflict_count_short, conflictCount)
                    } else {
                        stringResource(R.string.mdbx_no_conflicts_short)
                    },
                    isWarning = conflictCount > 0
                )
                StatusTile(
                    modifier = Modifier.weight(1f),
                    icon = if (healthIssueCount > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
                    label = stringResource(R.string.mdbx_status_health),
                    value = if (healthIssueCount > 0) {
                        stringResource(R.string.mdbx_health_issues_short, healthIssueCount)
                    } else {
                        stringResource(R.string.mdbx_health_ok_short)
                    },
                    isWarning = healthIssueCount > 0
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.History,
                    label = stringResource(R.string.mdbx_status_delta),
                    value = diagnostics?.let {
                        stringResource(R.string.mdbx_commit_tombstone_short, it.commitCount, it.tombstoneCount)
                    } ?: stringResource(R.string.mdbx_status_loading),
                    isWarning = false
                )
                StatusTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Storage,
                    label = stringResource(R.string.mdbx_status_attachments),
                    value = diagnostics?.let {
                        stringResource(
                            R.string.mdbx_attachment_short,
                            it.attachmentCount,
                            it.externalAttachmentCount,
                            formatBytes(it.storedAttachmentBytes)
                        )
                    } ?: stringResource(R.string.mdbx_status_loading),
                    isWarning = false
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            diagnostics?.let { diagnostic ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                    DiagnosticLine(
                        icon = if (diagnostic.isReadable) Icons.Default.CloudSync else Icons.Default.CloudOff,
                        label = stringResource(R.string.mdbx_sync_status_label),
                        value = diagnostic.lastSyncStatus
                    )
                    DiagnosticLine(
                        icon = Icons.Default.Security,
                        label = stringResource(R.string.mdbx_compatibility_label),
                        value = mdbxCompatibilityValue(diagnostic, database)
                    )
                    DiagnosticLine(
                        icon = Icons.Default.Sync,
                        label = stringResource(R.string.mdbx_recovery_label),
                        value = if (diagnostic.structuralIssueCount == 0 && diagnostic.integrityOk) {
                            stringResource(R.string.mdbx_recovery_clean)
                        } else {
                            stringResource(
                                R.string.mdbx_recovery_issue_value,
                                diagnostic.structuralIssueCount,
                                diagnostic.integrityMessage ?: "-"
                            )
                        }
                    )
                    DiagnosticLine(
                        icon = Icons.Default.Info,
                        label = stringResource(R.string.mdbx_file_size_label),
                        value = formatBytes(diagnostic.fileSizeBytes)
                    )
                    DiagnosticLine(
                        icon = Icons.Default.Storage,
                        label = strings.get(R.string.mdbx_ui_client),
                        value = diagnostic.currentDeviceId ?: "-"
                    )
                    DiagnosticLine(
                        icon = Icons.Default.Folder,
                        label = strings.get(R.string.mdbx_ui_catalog_index),
                        value = "${diagnostic.folderCount} folders · ${diagnostic.indexedObjectCount} indexed"
                    )
                    }
                }
                if (hasUnavailableCopy) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        diagnostic.unavailableReason
                            ?: stringResource(R.string.mdbx_unavailable_local_copy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                stringResource(R.string.actions),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isDefault) {
                    OutlinedButton(
                        onClick = onSetDefault,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Star, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.mdbx_set_default))
                    }
                }
                OutlinedButton(
                    onClick = onSync,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.get(R.string.mdbx_sync_status_label))
                }
                OutlinedButton(
                    onClick = onShowConflicts,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.CallMerge, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (conflictCount > 0) strings.get(R.string.mdbx_ui_conflict_management_count, conflictCount) else strings.get(R.string.mdbx_ui_manager_conflicts_title))
                }
                OutlinedButton(
                    onClick = onShowSnapshots,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.get(R.string.mdbx_ui_object_snapshot))
                }
                OutlinedButton(
                    onClick = onShowCommitHistory,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.History, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.get(R.string.mdbx_ui_manager_history_title))
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.mdbx_delete))
                }
            }
        }
    }
}

@Composable
private fun MdbxOperationsDashboard(
    databases: List<LocalMdbxDatabase>,
    diagnostics: Map<Long, MdbxVaultDiagnostics>
) {
    val totalConflicts = diagnostics.values.sumOf { it.unresolvedConflictCount }
    val totalHealthIssues = diagnostics.values.sumOf { it.healthIssueCount }
    val totalCommits = diagnostics.values.sumOf { it.commitCount }
    val externalAttachments = diagnostics.values.sumOf { it.externalAttachmentCount }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Science,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.mdbx_operations_dashboard_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.AutoMirrored.Filled.CallMerge,
                    label = stringResource(R.string.mdbx_status_conflicts),
                    value = totalConflicts.toString(),
                    isWarning = totalConflicts > 0
                )
                StatusTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Warning,
                    label = stringResource(R.string.mdbx_status_health),
                    value = totalHealthIssues.toString(),
                    isWarning = totalHealthIssues > 0
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.History,
                    label = stringResource(R.string.mdbx_status_delta),
                    value = totalCommits.toString(),
                    isWarning = false
                )
                StatusTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Storage,
                    label = stringResource(R.string.mdbx_status_attachments),
                    value = stringResource(
                        R.string.mdbx_dashboard_attachment_value,
                        externalAttachments
                    ),
                    isWarning = false
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.mdbx_dashboard_vault_count, databases.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusTile(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    isWarning: Boolean,
    onClick: (() -> Unit)? = null
) {
    val strings = rememberScreenStrings()
    val accentColor = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val containerColor = if (isWarning) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.58f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val interactionModifier = if (onClick != null) {
        Modifier.clickable(
            onClickLabel = strings.get(R.string.mdbx_ui_view_named_details, label),
            onClick = onClick
        )
    } else {
        Modifier
    }
    Surface(
        modifier = modifier
            .then(interactionModifier)
            .heightIn(min = 88.dp),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = if (onClick != null) 2.dp else 1.dp,
        color = containerColor
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.weight(1f))
                if (onClick != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DiagnosticLine(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MdbxAdvancedToolsDialog(
    state: MdbxViewModel.MdbxAdvancedDialogState.Visible,
    onDismiss: () -> Unit,
    onExportBundle: (String?) -> Unit,
    onImportBundle: (String) -> Unit,
    onFlushPendingUpload: () -> Unit,
    onRunBenchmark: (Int) -> Unit
) {
    val strings = rememberScreenStrings()
    val context = LocalContext.current
    var baseCommitId by rememberSaveable(state.databaseId) { mutableStateOf("") }
    var importJson by rememberSaveable(state.databaseId) { mutableStateOf("") }
    var benchmarkCountText by rememberSaveable(state.databaseId) { mutableStateOf("10") }
    val benchmarkCount = benchmarkCountText.toIntOrNull()?.coerceIn(1, 500) ?: 10
    val diagnostics = state.diagnostics

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.get(R.string.mdbx_ui_advanced_tools_title, state.databaseName)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                state.message?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                AdvancedToolSection(title = "Oplog / Sync bundle") {
                    OutlinedTextField(
                        value = baseCommitId,
                        onValueChange = { baseCommitId = it },
                        label = { Text(strings.get(R.string.mdbx_ui_base_commit_optional)) },
                        singleLine = true,
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onExportBundle(baseCommitId.trim().takeIf { it.isNotBlank() }) },
                            enabled = !state.isLoading,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.get(R.string.export))
                        }
                        OutlinedButton(
                            onClick = {
                                state.exportedBundleJson?.let {
                                    ClipboardUtils.copyToClipboard(context, it, "MDBX sync bundle")
                                }
                            },
                            enabled = !state.exportedBundleJson.isNullOrBlank(),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.get(R.string.copy))
                        }
                    }
                    state.lastExportedBundle?.let { bundle ->
                        Text(
                            "head ${shortId(bundle.headCommitId)} · ${bundle.commitCount} commits · ${bundle.payloadHash.take(12)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = importJson,
                        onValueChange = { importJson = it },
                        label = { Text(strings.get(R.string.mdbx_ui_bundle_paste)) },
                        minLines = 3,
                        maxLines = 6,
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onImportBundle(importJson) },
                        enabled = !state.isLoading && importJson.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.get(R.string.mdbx_ui_bundle_import))
                    }
                    state.lastImportResult?.let { result ->
                        Text(
                            strings.get(R.string.mdbx_ui_bundle_import_result, result.appliedObjectCount, result.keptLocalObjectCount, result.conflictCount, result.tombstoneCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                AdvancedToolSection(title = strings.get(R.string.mdbx_ui_background_upload)) {
                    DiagnosticLine(
                        icon = Icons.Default.Sync,
                        label = strings.get(R.string.keepass_remote_sync_status),
                        value = diagnostics?.lastSyncStatus ?: "-"
                    )
                    Button(
                        onClick = onFlushPendingUpload,
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.CloudSync, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.get(R.string.mdbx_ui_upload_pending_now))
                    }
                }

                AdvancedToolSection(title = strings.get(R.string.mdbx_ui_attachment_storage_format)) {
                    DiagnosticLine(
                        icon = Icons.Default.Storage,
                        label = strings.get(R.string.attachments),
                        value = diagnostics?.let {
                            "${it.attachmentCount} total · ${it.externalAttachmentCount} external"
                        } ?: "-"
                    )
                    DiagnosticLine(
                        icon = Icons.Default.Folder,
                        label = strings.get(R.string.mdbx_ui_storage),
                        value = diagnostics?.let {
                            "${formatBytes(it.originalAttachmentBytes)} original · ${formatBytes(it.storedAttachmentBytes)} stored"
                        } ?: "-"
                    )
                    DiagnosticLine(
                        icon = if ((diagnostics?.attachmentChunkMismatchCount ?: 0) > 0) {
                            Icons.Default.Warning
                        } else {
                            Icons.Default.CheckCircle
                        },
                        label = strings.get(R.string.mdbx_ui_chunk_verification),
                        value = diagnostics?.let { "${it.attachmentChunkMismatchCount} mismatch" } ?: "-"
                    )
                }

                AdvancedToolSection(title = strings.get(R.string.mdbx_ui_performance_benchmark)) {
                    OutlinedTextField(
                        value = benchmarkCountText,
                        onValueChange = { value ->
                            benchmarkCountText = value.filter { it.isDigit() }.take(3)
                        },
                        label = { Text(strings.get(R.string.mdbx_ui_benchmark_commit_count)) },
                        singleLine = true,
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onRunBenchmark(benchmarkCount) },
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.get(R.string.mdbx_ui_benchmark_run))
                    }
                    state.lastBenchmarkResult?.let { result ->
                        Text(
                            "${result.operationCount} commits · ${result.elapsedMs} ms · ${formatBytes(result.fileDeltaBytes)} file delta",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.mdbx_close))
            }
        }
    )
}

@Composable
private fun AdvancedToolSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun SnapshotCreationCard(
    snapshotName: String,
    onSnapshotNameChange: (String) -> Unit,
    fullSnapshot: Boolean,
    onFullSnapshotChange: (Boolean) -> Unit,
    engineAlwaysCreatesFullSnapshots: Boolean,
    enabled: Boolean,
    onCreateSnapshot: () -> Unit
) {
    val strings = rememberScreenStrings()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp).size(22.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        strings.get(R.string.mdbx_ui_snapshot_create),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        strings.get(R.string.mdbx_ui_snapshot_create_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            OutlinedTextField(
                value = snapshotName,
                onValueChange = onSnapshotNameChange,
                enabled = enabled,
                singleLine = true,
                label = { Text(strings.get(R.string.name)) },
                placeholder = { Text(strings.get(R.string.mdbx_ui_snapshot_name_placeholder)) },
                modifier = Modifier.fillMaxWidth()
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            if (engineAlwaysCreatesFullSnapshots) {
                                if (fullSnapshot) {
                                    stringResource(R.string.mdbx_snapshot_always_create_full)
                                } else {
                                    stringResource(R.string.mdbx_snapshot_create_when_changed)
                                }
                            } else if (fullSnapshot) {
                                strings.get(R.string.mdbx_ui_snapshot_full)
                            } else {
                                strings.get(R.string.mdbx_ui_snapshot_incremental)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (engineAlwaysCreatesFullSnapshots) {
                                if (fullSnapshot) {
                                    stringResource(R.string.mdbx_snapshot_always_create_full_description)
                                } else {
                                    stringResource(R.string.mdbx_snapshot_create_when_changed_description)
                                }
                            } else if (fullSnapshot) {
                                strings.get(R.string.mdbx_ui_snapshot_full_description)
                            } else {
                                strings.get(R.string.mdbx_ui_snapshot_incremental_description)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = fullSnapshot,
                        onCheckedChange = onFullSnapshotChange,
                        enabled = enabled
                    )
                }
            }
            Button(
                onClick = onCreateSnapshot,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.get(R.string.mdbx_ui_snapshot_create))
            }
        }
    }
}

@Composable
private fun SnapshotListHeader(
    manualSnapshotCount: Int,
    automaticSnapshotCount: Int,
    enabled: Boolean,
    onPruneAutomaticSnapshots: () -> Unit
) {
    val strings = rememberScreenStrings()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                strings.get(R.string.mdbx_ui_snapshots_saved_count, manualSnapshotCount + automaticSnapshotCount),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                strings.get(R.string.mdbx_ui_snapshot_mode_counts, manualSnapshotCount, automaticSnapshotCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (automaticSnapshotCount > 0) {
            TextButton(
                onClick = onPruneAutomaticSnapshots,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(strings.get(R.string.mdbx_ui_snapshot_clear_automatic))
            }
        }
    }
}

@Composable
private fun SnapshotEmptyState() {
    val strings = rememberScreenStrings()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Restore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    strings.get(R.string.mdbx_ui_snapshot_empty),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    strings.get(R.string.mdbx_ui_snapshot_empty_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MdbxSnapshotStructurePage(
    preview: MdbxStructurePreview?,
    isLoading: Boolean,
    compareMode: Boolean
) {
    val activity = LocalContext.current.findActivity()
    val originalOrientation = remember(activity) { activity?.requestedOrientation }

    LaunchedEffect(compareMode, activity) {
        activity?.requestedOrientation = if (compareMode) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    DisposableEffect(activity, originalOrientation) {
        onDispose {
            if (originalOrientation != null) {
                activity?.requestedOrientation = originalOrientation
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = if (compareMode) 0.dp else 8.dp,
                vertical = if (compareMode) 0.dp else 8.dp
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        SnapshotStructurePreviewPage(
            preview = preview,
            compareMode = compareMode,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SnapshotStructurePreviewPage(
    preview: MdbxStructurePreview?,
    compareMode: Boolean,
    modifier: Modifier = Modifier
) {
    val strings = rememberScreenStrings()
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        if (preview == null) {
            Text(
                strings.get(R.string.mdbx_ui_snapshot_structure_loading),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (compareMode) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                StructureTreePanel(
                    title = strings.get(R.string.mdbx_ui_current_version),
                    nodes = preview.currentNodes,
                    modifier = Modifier.weight(1f),
                    framed = false
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StructureTreePanel(
                    title = strings.get(R.string.mdbx_ui_snapshot_version),
                    nodes = preview.snapshotNodes,
                    modifier = Modifier.weight(1f),
                    framed = false
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                StructureTreePanel(
                    title = "",
                    nodes = preview.snapshotNodes,
                    modifier = Modifier.fillMaxWidth(),
                    framed = false
                )
            }
        }
    }
}

@Composable
private fun StructureTreePanel(
    title: String,
    nodes: List<MdbxStructureNode>,
    modifier: Modifier = Modifier,
    framed: Boolean = true
) {
    val strings = rememberScreenStrings()
    var expandedIds by remember(nodes) {
        mutableStateOf(nodes.filter { it.type == MdbxStructureNodeType.FOLDER }.map { it.id }.toSet())
    }
    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            if (title.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        strings.get(R.string.mdbx_ui_item_count, nodes.count { it.type == MdbxStructureNodeType.ENTRY }),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (nodes.isEmpty()) {
                Text(
                    strings.get(R.string.mdbx_ui_structure_empty),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val visibleNodes = visibleStructureNodes(nodes, expandedIds)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                ) {
                    visibleNodes.forEach { item ->
                        StructureTreeRow(
                            node = item.node,
                            depth = item.depth,
                            isExpanded = item.node.id in expandedIds,
                            hasChildren = item.hasChildren,
                            onToggle = {
                                expandedIds = if (item.node.id in expandedIds) {
                                    expandedIds - item.node.id
                                } else {
                                    expandedIds + item.node.id
                                }
                            }
                        )
                    }
                }
            }
        }
    }
    if (framed) {
        OutlinedCard(modifier = modifier) { content() }
    } else {
        Surface(modifier = modifier, color = Color.Transparent) { content() }
    }
}

private data class VisibleStructureNode(
    val node: MdbxStructureNode,
    val depth: Int,
    val hasChildren: Boolean
)

private fun visibleStructureNodes(
    nodes: List<MdbxStructureNode>,
    expandedIds: Set<String>
): List<VisibleStructureNode> {
    val childrenByParent = nodes.groupBy { it.parentId }
    fun walk(parentId: String?, depth: Int): List<VisibleStructureNode> =
        childrenByParent[parentId].orEmpty().sortedWith(structureTreeNodeComparator).flatMap { node ->
            val hasChildren = childrenByParent.containsKey(node.id)
            listOf(VisibleStructureNode(node, depth, hasChildren)) +
                if (hasChildren && node.id in expandedIds) walk(node.id, depth + 1) else emptyList()
        }
    return walk(null, 0)
}

private val structureTreeNodeComparator = compareBy<MdbxStructureNode>(
    { if (it.type == MdbxStructureNodeType.FOLDER) 0 else 1 },
    { it.name.lowercase(Locale.ROOT) },
    { it.path.lowercase(Locale.ROOT) },
    { it.id }
)

@Composable
private fun StructureTreeRow(
    node: MdbxStructureNode,
    depth: Int,
    isExpanded: Boolean,
    hasChildren: Boolean,
    onToggle: () -> Unit
) {
    val statusColor = structureStatusColor(node.status)
    Row(
        modifier = Modifier
            .widthIn(min = 260.dp)
            .height(34.dp)
            .clickable(enabled = hasChildren, onClick = onToggle)
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StructureIndentLines(depth)
        if (hasChildren) {
            Icon(
                if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(modifier = Modifier.width(16.dp))
        }
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            if (node.type == MdbxStructureNodeType.FOLDER) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = if (node.type == MdbxStructureNodeType.FOLDER) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            node.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (node.type == MdbxStructureNodeType.FOLDER) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 190.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (node.status != MdbxStructureNodeStatus.UNCHANGED) {
            Text(
                structureStatusLabel(node.status),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            node.metadata,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StructureIndentLines(depth: Int) {
    if (depth <= 0) return
    Row {
        repeat(depth) {
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
    }
}

@Composable
private fun structureStatusColor(status: MdbxStructureNodeStatus): Color =
    when (status) {
        MdbxStructureNodeStatus.ADDED -> MaterialTheme.colorScheme.primary
        MdbxStructureNodeStatus.REMOVED -> MaterialTheme.colorScheme.error
        MdbxStructureNodeStatus.MODIFIED -> MaterialTheme.colorScheme.tertiary
        MdbxStructureNodeStatus.UNCHANGED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun structureStatusLabel(status: MdbxStructureNodeStatus): String =
    when (status) {
        MdbxStructureNodeStatus.ADDED -> "A"
        MdbxStructureNodeStatus.REMOVED -> "D"
        MdbxStructureNodeStatus.MODIFIED -> "M"
        MdbxStructureNodeStatus.UNCHANGED -> ""
    }

@Composable
private fun SnapshotRow(
    snapshot: MdbxSnapshotSummary,
    enabled: Boolean,
    onShowDiff: () -> Unit,
    onOpenStructure: () -> Unit,
    onDelete: () -> Unit,
    onRevert: () -> Unit
) {
    val strings = rememberScreenStrings()
    var actionMenuExpanded by remember { mutableStateOf(false) }
    Card(
        onClick = onOpenStructure,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (snapshot.integrityOk) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                ) {
                    Icon(
                        if (snapshot.autoPrune) Icons.Default.History else Icons.Default.Restore,
                        contentDescription = null,
                        tint = if (snapshot.integrityOk) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.padding(10.dp).size(21.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        snapshot.displayName(strings),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        formatMdbxHistoryTime(snapshot.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = strings.get(R.string.mdbx_ui_snapshot_view_structure),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SnapshotInfoPill(if (snapshot.autoPrune) strings.get(R.string.mdbx_ui_automatic) else strings.get(R.string.mdbx_ui_manual))
                SnapshotInfoPill(if (snapshot.isFull) strings.get(R.string.mdbx_ui_full) else strings.get(R.string.mdbx_status_delta))
                SnapshotInfoPill(formatBytes(snapshot.payloadBytes))
                SnapshotInfoPill(
                    label = if (snapshot.integrityOk) strings.get(R.string.mdbx_ui_verification_ok) else strings.get(R.string.mdbx_ui_verification_failed),
                    emphasized = true,
                    error = !snapshot.integrityOk
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onShowDiff,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.get(R.string.mdbx_ui_changes))
                }
                TextButton(
                    onClick = onOpenStructure,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.get(R.string.mdbx_ui_structure))
                }
                Spacer(modifier = Modifier.weight(1f))
                Box {
                    IconButton(
                        onClick = { actionMenuExpanded = true },
                        enabled = enabled
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = strings.get(R.string.mdbx_ui_snapshot_more_actions))
                    }
                    DropdownMenu(
                        expanded = actionMenuExpanded,
                        onDismissRequest = { actionMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(strings.get(R.string.mdbx_ui_snapshot_restore)) },
                            leadingIcon = {
                                Icon(Icons.Default.Restore, contentDescription = null)
                            },
                            enabled = enabled && snapshot.integrityOk,
                            onClick = {
                                actionMenuExpanded = false
                                onRevert()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(strings.get(R.string.mdbx_ui_snapshot_delete), color = MaterialTheme.colorScheme.error)
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            enabled = enabled,
                            onClick = {
                                actionMenuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SnapshotInfoPill(
    label: String,
    emphasized: Boolean = false,
    error: Boolean = false
) {
    val containerColor = when {
        error -> MaterialTheme.colorScheme.errorContainer
        emphasized -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val contentColor = when {
        error -> MaterialTheme.colorScheme.onErrorContainer
        emphasized -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor
        )
    }
}

@Composable
private fun CommitDetailHeader(
    commitId: String,
    delta: MdbxDeltaSummary?,
    diffItems: List<MdbxCommitDiff>
) {
    val strings = rememberScreenStrings()
    val presentation = delta?.toHistoryPresentation(strings)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = presentation?.primaryAction.historyContainerColor()
                    ?: MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(
                    imageVector = presentation?.primaryAction?.historyIcon() ?: Icons.Default.History,
                    contentDescription = null,
                    tint = presentation?.primaryAction.historyContentColor()
                        ?: MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(10.dp).size(22.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = presentation?.title ?: strings.get(R.string.mdbx_ui_commit_identifier, shortId(commitId)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (presentation?.isSystemCommit == true) {
                        HistoryStatusPill(strings.get(R.string.mdbx_ui_system))
                    }
                }
                Text(
                    text = presentation?.supportingText
                        ?: strings.get(R.string.mdbx_ui_changed_object_count, diffItems.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                delta?.let {
                    Text(
                        text = strings.get(R.string.mdbx_ui_history_time_and_device, formatMdbxHistoryTime(it.createdAt), shortId(it.deviceId)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryStatusPill(label: String) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun CommitEventExplanationCard(
    presentation: MdbxCommitPresentation?,
    commitId: String
) {
    val strings = rememberScreenStrings()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (presentation?.isSystemCommit == true) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.48f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (presentation?.isSystemCommit == true) {
                    Icons.Default.Security
                } else {
                    Icons.Default.Info
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (presentation?.isSystemCommit == true) {
                        strings.get(R.string.mdbx_ui_database_level_record)
                    } else {
                        strings.get(R.string.mdbx_ui_field_versions_unavailable)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = presentation?.systemDescription
                        ?: strings.get(R.string.mdbx_ui_metadata_commit_description, shortId(commitId)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CommitDiffErrorCard(message: String) {
    val strings = rememberScreenStrings()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.48f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.ReportProblem,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(22.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    strings.get(R.string.mdbx_ui_details_unavailable),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun CommitChangeGroupHeader(
    kind: ObjectChangeKind,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val strings = rememberScreenStrings()
    val tone = kind.groupTone()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onToggle),
        shape = MaterialTheme.shapes.medium,
        color = tone.containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = kind.icon(),
                contentDescription = null,
                tint = tone.contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "${kind.label(strings)} $count",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tone.contentColor,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) strings.get(R.string.collapse) else strings.get(R.string.expand),
                tint = tone.contentColor
            )
        }
    }
}

@Composable
private fun CommitTechnicalInfoCard(
    commitId: String,
    delta: MdbxDeltaSummary?
) {
    val strings = rememberScreenStrings()
    var expanded by rememberSaveable(commitId) { mutableStateOf(false) }
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    strings.get(R.string.passkey_detail_technical),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) strings.get(R.string.mdbx_ui_collapse_technical_info) else strings.get(R.string.mdbx_ui_expand_technical_info)
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    TechnicalInfoLine("Commit ID", commitId)
                    delta?.operationId?.takeIf { it.isNotBlank() }?.let {
                        TechnicalInfoLine(strings.get(R.string.mdbx_ui_operation_id), it)
                    }
                    delta?.operationKind?.takeIf { it.isNotBlank() }?.let {
                        TechnicalInfoLine(strings.get(R.string.mdbx_ui_operation_type), it)
                    }
                    delta?.let {
                        TechnicalInfoLine(strings.get(R.string.mdbx_ui_commit_type), "${it.commitKind} / ${it.changeScope}")
                        TechnicalInfoLine(strings.get(R.string.steam_device_label), it.deviceId)
                        TechnicalInfoLine(strings.get(R.string.mdbx_ui_sequence), it.localSeq.toString())
                        TechnicalInfoLine(strings.get(R.string.mdbx_ui_parent_commits), it.parentCount.toString())
                        it.branchName?.takeIf(String::isNotBlank)?.let { branch ->
                            TechnicalInfoLine(strings.get(R.string.mdbx_ui_branch), branch)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TechnicalInfoLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CommitObjectChangeCard(
    diff: MdbxCommitDiff
) {
    val strings = rememberScreenStrings()
    val fieldChanges = diff.toFieldChanges(strings)
    val actionTone = diff.objectChangeTone()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = actionTone.containerColor
                ) {
                    Icon(
                        diff.objectChangeIcon(),
                        contentDescription = null,
                        tint = actionTone.contentColor,
                        modifier = Modifier.padding(9.dp).size(18.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        diff.displayObjectTitle(strings),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    diff.storagePath?.takeIf { it.isNotBlank() }?.let { path ->
                        Text(
                            path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        diff.objectChangeMeta(strings),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (fieldChanges.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Text(
                        strings.get(R.string.mdbx_ui_field_changes),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                fieldChanges.forEachIndexed { index, change ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    FieldChangeRow(change)
                }
            }
        }
    }
}

private data class ObjectChangeTone(
    val containerColor: Color,
    val contentColor: Color
)

private enum class ObjectChangeKind {
    CREATED,
    MODIFIED,
    MOVED,
    DELETED,
    RESTORED;

    val sortOrder: Int
        get() = when (this) {
            CREATED -> 0
            MODIFIED -> 1
            MOVED -> 2
            DELETED -> 3
            RESTORED -> 4
        }
}

private data class FieldChange(
    val objectTitle: String,
    val objectPath: String?,
    val fieldLabel: String,
    val before: String,
    val after: String,
    val sensitive: Boolean = false
)

private data class FieldChangeGroup(
    val objectTitle: String,
    val objectPath: String?,
    val changes: List<FieldChange>
)

@Composable
private fun FieldDiffPanel(
    title: String,
    subtitle: String,
    changes: List<FieldChange>
) {
    val strings = rememberScreenStrings()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title.isNotBlank() || subtitle.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (title.isNotBlank()) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (changes.isEmpty()) {
            Text(
                strings.get(R.string.mdbx_ui_field_changes_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        changes
            .groupBy { it.objectPath to it.objectTitle }
            .map { (objectKey, objectChanges) ->
                FieldChangeGroup(
                    objectTitle = objectKey.second,
                    objectPath = objectKey.first,
                    changes = objectChanges
                )
            }
            .forEach { group ->
                FieldChangeGroupBlock(group)
            }
    }
}

@Composable
private fun FieldChangeGroupBlock(
    group: FieldChangeGroup
) {
    val strings = rememberScreenStrings()
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        group.displayPath(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)) {
                Text(
                    strings.get(R.string.mdbx_ui_field_changes),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            group.changes.forEachIndexed { index, change ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                FieldChangeRow(change)
            }
        }
    }
}

@Composable
private fun FieldChangeRow(change: FieldChange) {
    val strings = rememberScreenStrings()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(
            "${change.fieldLabel}:",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (change.sensitive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    strings.get(R.string.mdbx_ui_sensitive_changes_hidden),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        } else {
            VersionValueRow(
                marker = "-",
                value = change.before,
                color = MaterialTheme.colorScheme.error,
                backgroundColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f)
            )
            VersionValueRow(
                marker = "+",
                value = change.after,
                color = MaterialTheme.colorScheme.primary,
                backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
            )
        }
    }
}

@Composable
private fun VersionValueRow(
    marker: String,
    value: String,
    color: Color,
    backgroundColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            marker,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.width(18.dp)
        )
        Text(
            value.ifBlank { "null" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun MdbxCommitDiff.toFieldChanges(strings: StringResolver): List<FieldChange> {
    if (objectChangeKind() != ObjectChangeKind.MODIFIED) return emptyList()
    val objectTitle = displayObjectTitle(strings)
    val objectPath = storagePath?.takeIf { it.isNotBlank() }
    return buildList {
        if (previousTitle != currentTitle) {
            add(FieldChange(objectTitle, objectPath, strings.get(R.string.title), previousTitle.orEmpty(), currentTitle.orEmpty()))
        }
        if (
            previousPayloadPreview != currentPayloadPreview ||
            changedFields.any { it.equals("payload", ignoreCase = true) }
        ) {
            add(
                FieldChange(
                    objectTitle = objectTitle,
                    objectPath = objectPath,
                    fieldLabel = strings.get(R.string.content),
                    before = "",
                    after = "",
                    sensitive = true
                )
            )
        }
    }
}

private fun MdbxCommitDiff.objectChangeKind(): ObjectChangeKind =
    when {
        previousDeleted == null && !currentDeleted -> ObjectChangeKind.CREATED
        previousDeleted == true && !currentDeleted -> ObjectChangeKind.RESTORED
        currentDeleted -> ObjectChangeKind.DELETED
        changedFields.any {
            it.equals("collection", ignoreCase = true) ||
                it.equals("project_id", ignoreCase = true)
        } -> ObjectChangeKind.MOVED
        else -> ObjectChangeKind.MODIFIED
    }

private fun MdbxCommitDiff.objectChangeTitle(strings: StringResolver): String {
    val objectLabel = mdbxHistoryObjectTypeLabel(strings, objectType, contentType)
    return when (objectChangeKind()) {
        ObjectChangeKind.CREATED -> strings.get(R.string.mdbx_ui_object_created, objectLabel)
        ObjectChangeKind.MODIFIED -> strings.get(R.string.mdbx_ui_object_modified, objectLabel)
        ObjectChangeKind.MOVED -> strings.get(R.string.mdbx_ui_history_action_moved, objectLabel)
        ObjectChangeKind.DELETED -> strings.get(R.string.mdbx_ui_history_action_deleted, objectLabel)
        ObjectChangeKind.RESTORED -> strings.get(R.string.mdbx_ui_history_action_restored, objectLabel)
    }
}

private fun MdbxCommitDiff.objectChangeIcon(): ImageVector =
    when (objectChangeKind()) {
        ObjectChangeKind.CREATED -> Icons.Default.Add
        ObjectChangeKind.MODIFIED -> Icons.Default.History
        ObjectChangeKind.MOVED -> Icons.Default.SwapHoriz
        ObjectChangeKind.DELETED -> Icons.Default.Delete
        ObjectChangeKind.RESTORED -> Icons.Default.Restore
    }

private fun ObjectChangeKind.label(strings: StringResolver): String = when (this) {
    ObjectChangeKind.CREATED -> strings.get(R.string.mdbx_ui_action_created)
    ObjectChangeKind.MODIFIED -> strings.get(R.string.mdbx_ui_action_modified)
    ObjectChangeKind.MOVED -> strings.get(R.string.move)
    ObjectChangeKind.DELETED -> strings.get(R.string.delete)
    ObjectChangeKind.RESTORED -> strings.get(R.string.restore)
}

private fun ObjectChangeKind.icon(): ImageVector = when (this) {
    ObjectChangeKind.CREATED -> Icons.Default.Add
    ObjectChangeKind.MODIFIED -> Icons.Default.History
    ObjectChangeKind.MOVED -> Icons.Default.SwapHoriz
    ObjectChangeKind.DELETED -> Icons.Default.Delete
    ObjectChangeKind.RESTORED -> Icons.Default.Restore
}

@Composable
private fun ObjectChangeKind.groupTone(): ObjectChangeTone = when (this) {
    ObjectChangeKind.CREATED -> ObjectChangeTone(
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
        MaterialTheme.colorScheme.onPrimaryContainer
    )
    ObjectChangeKind.MODIFIED -> ObjectChangeTone(
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f),
        MaterialTheme.colorScheme.onSecondaryContainer
    )
    ObjectChangeKind.MOVED,
    ObjectChangeKind.RESTORED -> ObjectChangeTone(
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.58f),
        MaterialTheme.colorScheme.onTertiaryContainer
    )
    ObjectChangeKind.DELETED -> ObjectChangeTone(
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.58f),
        MaterialTheme.colorScheme.onErrorContainer
    )
}

private fun MdbxHistoryAction.historyIcon(): ImageVector = when (this) {
    MdbxHistoryAction.CREATED -> Icons.Default.Add
    MdbxHistoryAction.UPDATED -> Icons.Default.History
    MdbxHistoryAction.MOVED -> Icons.Default.SwapHoriz
    MdbxHistoryAction.COPIED -> Icons.Default.ContentCopy
    MdbxHistoryAction.DELETED -> Icons.Default.Delete
    MdbxHistoryAction.RESTORED -> Icons.Default.Restore
    MdbxHistoryAction.MERGED -> Icons.AutoMirrored.Filled.CallMerge
    MdbxHistoryAction.SYSTEM -> Icons.Default.Security
}

@Composable
private fun MdbxHistoryAction?.historyContainerColor(): Color = when (this) {
    MdbxHistoryAction.CREATED -> MaterialTheme.colorScheme.primaryContainer
    MdbxHistoryAction.DELETED -> MaterialTheme.colorScheme.errorContainer
    MdbxHistoryAction.MOVED,
    MdbxHistoryAction.RESTORED -> MaterialTheme.colorScheme.tertiaryContainer
    MdbxHistoryAction.SYSTEM -> MaterialTheme.colorScheme.secondaryContainer
    else -> MaterialTheme.colorScheme.secondaryContainer
}

@Composable
private fun MdbxHistoryAction?.historyContentColor(): Color = when (this) {
    MdbxHistoryAction.CREATED -> MaterialTheme.colorScheme.onPrimaryContainer
    MdbxHistoryAction.DELETED -> MaterialTheme.colorScheme.onErrorContainer
    MdbxHistoryAction.MOVED,
    MdbxHistoryAction.RESTORED -> MaterialTheme.colorScheme.onTertiaryContainer
    else -> MaterialTheme.colorScheme.onSecondaryContainer
}

@Composable
private fun formatMdbxHistoryTime(value: String): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val zone = ZoneId.systemDefault()
    val formatter = remember(locale, zone) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zone)
    }
    return runCatching {
        formatter.format(Instant.parse(value))
    }.getOrElse {
        value.replace('T', ' ').removeSuffix("Z").take(16)
    }
}

@Composable
private fun MdbxCommitDiff.objectChangeTone(): ObjectChangeTone =
    when (objectChangeKind()) {
        ObjectChangeKind.CREATED -> ObjectChangeTone(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
        ObjectChangeKind.MODIFIED -> ObjectChangeTone(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.46f),
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
        ObjectChangeKind.MOVED -> ObjectChangeTone(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.46f),
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
        ObjectChangeKind.DELETED -> ObjectChangeTone(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
        ObjectChangeKind.RESTORED -> ObjectChangeTone(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.46f),
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }

private fun MdbxCommitDiff.displayObjectTitle(strings: StringResolver): String =
    displayTitle?.takeIf { it.isNotBlank() }
        ?: mdbxHistoryObjectTypeLabel(strings, objectType, contentType)

private fun MdbxCommitDiff.objectChangeMeta(strings: StringResolver): String =
    objectChangeTitle(strings)

private fun MdbxConflictSummary.toFieldChanges(strings: StringResolver): List<FieldChange> {
    val objectTitle = localTitle
        ?: incomingTitle
        ?: "${objectTypeLabel(strings, objectType)} · ${shortId(objectId)}"
    return buildList {
        if (localTitle != incomingTitle) {
            add(FieldChange(objectTitle, null, strings.get(R.string.title), localTitle.orEmpty(), incomingTitle.orEmpty()))
        }
        if (localPayloadPreview != incomingPayloadPreview) {
            add(FieldChange(objectTitle, null, strings.get(R.string.mdbx_ui_content_summary), localPayloadPreview.orEmpty(), incomingPayloadPreview.orEmpty()))
        }
        if (conflictingFields.isNotBlank()) {
            add(FieldChange(objectTitle, null, strings.get(R.string.mdbx_ui_conflicting_fields), conflictingFields, conflictingFields))
        }
    }
}

private fun FieldChangeGroup.displayPath(): String =
    listOfNotNull(
        objectPath?.takeIf { it.isNotBlank() },
        objectTitle.takeIf { it.isNotBlank() }
    ).joinToString("/").ifBlank { "-" }

private fun objectTypeLabel(strings: StringResolver, type: String): String =
    mdbxHistoryObjectTypeLabel(strings, type)

@Composable
private fun DeltaRow(
    delta: MdbxDeltaSummary,
    onShowDiff: () -> Unit
) {
    val strings = rememberScreenStrings()
    val presentation = remember(delta, strings) { delta.toHistoryPresentation(strings) }
    Card(
        onClick = onShowDiff,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = presentation.primaryAction.historyContainerColor()
            ) {
                Icon(
                    presentation.primaryAction.historyIcon(),
                    contentDescription = null,
                    tint = presentation.primaryAction.historyContentColor(),
                    modifier = Modifier.padding(10.dp).size(21.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    presentation.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (presentation.supportingText.isNotBlank()) {
                    Text(
                        presentation.supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        formatMdbxHistoryTime(delta.createdAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (presentation.objectCount > 0) {
                        HistoryStatusPill(strings.get(R.string.mdbx_ui_item_count, presentation.objectCount))
                    }
                    if (presentation.isSystemCommit) {
                        HistoryStatusPill(strings.get(R.string.mdbx_ui_system))
                    }
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = strings.get(R.string.mdbx_ui_view_commit_details),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

private fun MdbxSnapshotSummary.displayName(strings: StringResolver): String {
    val rawName = name.trim()
    return when {
        rawName.isBlank() -> if (autoPrune) strings.get(R.string.mdbx_ui_automatic_snapshot_name) else strings.get(R.string.mdbx_ui_manual_snapshot_name)
        rawName.startsWith("Snapshot ", ignoreCase = true) -> strings.get(R.string.mdbx_ui_manual_snapshot_name)
        rawName.startsWith("Auto ", ignoreCase = true) -> strings.get(R.string.mdbx_ui_automatic_snapshot_name)
        else -> rawName
    }
}

private fun shortId(value: String): String =
    value.take(8).ifBlank { "-" }

private const val AUTO_EXPAND_COMMIT_OBJECT_LIMIT = 12

private fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun LocalMdbxDatabase.displayPath(context: Context, strings: StringResolver): String {
    val raw = filePath.takeIf { it.isNotBlank() } ?: workingCopyPath.orEmpty()
    return when (sourceTypeEnum) {
        MdbxSourceType.REMOTE_WEBDAV -> "WebDAV · $raw"
        MdbxSourceType.LOCAL_INTERNAL -> {
            val copiedName = workingCopyPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            listOfNotNull(strings.get(R.string.mdbx_ui_private_directory), copiedName).joinToString(" · ").ifBlank { raw }
        }
        MdbxSourceType.LOCAL_EXTERNAL -> {
            val uri = runCatching { Uri.parse(raw) }.getOrNull()
            val displayName = uri?.let { context.displayNameForUri(it) }
            val location = uri?.lastPathSegment
                ?.substringAfterLast(':')
                ?.takeIf { it.isNotBlank() && it != displayName }
            listOfNotNull(strings.get(R.string.mdbx_ui_local_file), location, displayName)
                .joinToString(" · ")
                .ifBlank { raw }
        }
        MdbxSourceType.REMOTE_ONEDRIVE -> "OneDrive · $raw"
    }
}

private fun Context.displayNameForUri(uri: Uri): String? =
    runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

private fun mdbxCompatibilityValue(
    diagnostic: MdbxVaultDiagnostics,
    database: LocalMdbxDatabase
): String =
    listOf(
        diagnostic.releaseLabel?.takeIf { it.isNotBlank() }
            ?: diagnostic.formatVersion
            ?: "MDBX-?",
        diagnostic.formatVersion?.takeIf { format ->
            format.isNotBlank() && format != diagnostic.releaseLabel
        },
        diagnostic.defaultTigaMode?.takeIf { it.isNotBlank() } ?: database.tigaMode
    ).filterNotNull().joinToString(" · ")

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}
