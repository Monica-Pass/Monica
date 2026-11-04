package takagi.ru.monica.ui.vaultv2

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.repository.MdbxStoredFolderEntry
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.SettingsViewModel

/** The summary has its own projection, while all item operations stay in the existing vault. */
@Composable
internal fun VaultOverviewContent(
	itemsReady: Boolean,
    allItems: List<VaultV2Item>,
    archivedPasswords: List<PasswordEntry>,
    archivedSecureItems: List<SecureItem> = emptyList(),
    categories: List<Category>,
    keepassDatabases: List<LocalKeePassDatabase>,
    mdbxDatabases: List<LocalMdbxDatabase>,
    bitwardenVaults: List<BitwardenVault>,
    passwordViewModel: PasswordViewModel,
    localKeePassViewModel: LocalKeePassViewModel,
    settingsViewModel: SettingsViewModel,
    appSettings: AppSettings,
    securityManager: SecurityManager,
    currentScope: String,
    listState: LazyListState,
    selectedCardKey: String?,
    onSelectedCardChange: (String) -> Unit,
    cardStackState: VaultOverviewCardStackState,
    isDetailVisible: Boolean,
    retainedSnapshot: VaultOverviewSnapshot?,
    onSnapshotReady: (VaultOverviewSnapshot) -> Unit,
    onSelectScope: (String) -> Unit,
    onOpenSource: (String) -> Unit,
    onOpenItem: (VaultV2Item) -> Unit,
    onOpenType: (VaultV2ItemType) -> Unit,
    onOpenFolder: (VaultOverviewFolder) -> Unit,
    onFavorites: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
    onAllItems: () -> Unit,
    onSearch: () -> Unit,
    onScanFidoQr: () -> Unit,
    onUnlock: () -> Unit,
    selection: VaultOverviewSelectionState,
    onRequestDeleteItem: (VaultV2Item) -> Unit,
) {
    val context = LocalContext.current
    val database = remember(context) { PasswordDatabase.getDatabase(context) }
    val usageManager = remember(context) { VaultOverviewUsageManager(context) }
    val legacyUsageManager = remember(context) { PasswordQuickAccessManager(context) }
    val usage: Map<String, VaultOverviewUsage>? by usageManager.stats.collectAsState(initial = null)
    val passwordUsage: Map<Long, PasswordQuickAccessRecord>? by legacyUsageManager.statsFlow.collectAsState(initial = null)
    val trashFlow = remember(database) { database.passwordEntryDao().observeVaultOverviewTrashCounts() }
    val trashCounts by trashFlow.collectAsState(initial = emptyList())
    val bitwardenFolderFlow = remember(bitwardenVaults.map { it.id }, passwordViewModel) {
        if (bitwardenVaults.isEmpty()) flowOf(emptyList<BitwardenFolder>())
        else combine(bitwardenVaults.map { passwordViewModel.getBitwardenFolders(it.id) }) { it.toList().flatten() }
    }
    val folders by bitwardenFolderFlow.collectAsState(initial = emptyList())
    val mdbxFolderFlow = remember(mdbxDatabases.map { it.id }, passwordViewModel) {
        if (mdbxDatabases.isEmpty()) flowOf(emptyMap<Long, List<MdbxStoredFolderEntry>>())
        else combine(mdbxDatabases.map { passwordViewModel.getMdbxFolders(it.id) }) { lists ->
            mdbxDatabases.indices.associate { mdbxDatabases[it].id to lists[it] }
        }
    }
    val mdbxFolders by mdbxFolderFlow.collectAsState(initial = emptyMap())
    val cachedKeePassGroups by remember(localKeePassViewModel) { localKeePassViewModel.observeCachedGroups() }.collectAsState()
    val verificationStates by localKeePassViewModel.verificationStates.collectAsState()
    val localLabel = stringResource(R.string.vault_overview_local)
    val sources = remember(keepassDatabases, mdbxDatabases, bitwardenVaults, verificationStates, localLabel) {
        buildList {
            add(VaultOverviewSource("local", localLabel, "Monica"))
            keepassDatabases.forEach {
                add(VaultOverviewSource("keepass:${it.id}", it.name, "KeePass",
                    locked = verificationStates[it.id] is LocalKeePassViewModel.VerificationState.Failed ||
                        (it.encryptedPassword.isNullOrBlank() && it.keyFileUri.isNullOrBlank() &&
                            it.keyFileInternalPath.isNullOrBlank() && verificationStates[it.id] !is LocalKeePassViewModel.VerificationState.Verified)))
            }
            bitwardenVaults.forEach {
                add(VaultOverviewSource("bitwarden:${it.id}", it.displayName?.takeIf(String::isNotBlank) ?: it.email, "Bitwarden", it.isLocked))
            }
            mdbxDatabases.forEach { add(VaultOverviewSource("mdbx:${it.id}", it.name, "MDBX")) }
        }
    }
    val available = remember(sources) { sources.filterNot { it.locked }.mapTo(hashSetOf()) { it.key } }
    LaunchedEffect(currentScope, sources) {
        if (currentScope == "local" || currentScope == "all") return@LaunchedEffect
        val id = currentScope.substringAfter(':', "").toLongOrNull() ?: return@LaunchedEffect
        if (sources.none { it.key == currentScope }) {
            // Parent flows initially emit empty lists. Confirm deletion with Room before changing scope.
            val exists = withContext(Dispatchers.IO) {
                when (currentScope.substringBefore(':')) {
                    "keepass" -> database.localKeePassDatabaseDao().getDatabaseById(id) != null
                    "bitwarden" -> database.bitwardenVaultDao().getVaultById(id) != null
                    "mdbx" -> database.localMdbxDatabaseDao().getDatabaseById(id) != null
                    else -> false
                }
            }
            if (!exists) onSelectScope("local")
        } else if (currentScope in available) {
            when (currentScope.substringBefore(':')) {
                "keepass" -> localKeePassViewModel.refreshGroups(id)
                "mdbx" -> passwordViewModel.refreshMdbxFolders(id)
            }
        }
    }
    val config = appSettings.vaultOverviewConfig
    // Module visibility, expansion, ordering and card focus are intentionally absent from this key.
    val rankingConfig = remember(config.pinnedCards, config.pinnedItems, config.recommendCards, config.recommendItems, config.excludedFrequentItems) {
        VaultOverviewConfig(pinnedCards = config.pinnedCards, pinnedItems = config.pinnedItems,
            recommendCards = config.recommendCards, recommendItems = config.recommendItems,
            excludedFrequentItems = config.excludedFrequentItems)
    }
    var prepared by remember { mutableStateOf<VaultOverviewPreparedData?>(null) }
    LaunchedEffect(itemsReady, allItems, sources, rankingConfig, usage, passwordUsage,
        categories, folders, mdbxFolders, archivedPasswords, archivedSecureItems, cachedKeePassGroups) {
        if (!itemsReady) return@LaunchedEffect
        val usageRecords = usage ?: return@LaunchedEffect
        val passwordRecords = passwordUsage ?: return@LaunchedEffect
        prepared = withContext(Dispatchers.Default) {
            prepareVaultOverview(allItems, sources, rankingConfig, usageRecords, passwordRecords,
                categories, folders, mdbxFolders, archivedPasswords, cachedKeePassGroups, archivedSecureItems)
        }
    }
    // Scope changes reuse the numeric batch. Publishing a large list must not compare every row on the UI thread.
    var snapshot by remember { mutableStateOf(retainedSnapshot, referentialEqualityPolicy()) }
    LaunchedEffect(prepared, currentScope, itemsReady, sources) {
        val input = prepared ?: return@LaunchedEffect
        if (!itemsReady || input.sources != sources) return@LaunchedEffect
        val projected = withContext(Dispatchers.Default) { projectVaultOverview(input, currentScope) }
        onSnapshotReady(projected)
        snapshot = projected
    }
    val trashCount = remember(trashCounts, currentScope, available) {
        // Native MDBX trash can include project objects outside the Room mirror.
        if (currentScope.startsWith("mdbx:") || (currentScope == "all" && available.any { it.startsWith("mdbx:") })) null
        else trashCounts.filter {
            val source = vaultOverviewSourceKey(it.bitwardenVaultId, it.keepassDatabaseId, it.mdbxDatabaseId)
            source in available && (currentScope == "all" || currentScope == source)
        }.sumOf { it.count }
    }
    VaultOverviewScreen(
        snapshot = snapshot?.takeIf { it.scope == currentScope && it.accessibleSources == available }, sources = sources,
        keepassDatabases = keepassDatabases, mdbxDatabases = mdbxDatabases, bitwardenVaults = bitwardenVaults,
        currentScope = currentScope, config = config, listState = listState, securityManager = securityManager,
        selectedCardKey = selectedCardKey, onSelectedCardChange = onSelectedCardChange,
        cardStackState = cardStackState, isDetailVisible = isDetailVisible,
        reduceAnimations = appSettings.reduceAnimations, trashCount = trashCount,
        walletStackLoopEnabled = appSettings.walletStackLoopEnabled,
        sort = appSettings.vaultListSort, onSortChange = settingsViewModel::updateVaultListSort,
        onConfigChange = settingsViewModel::updateVaultOverviewConfig,
        onSelectScope = onSelectScope, onOpenSource = onOpenSource, onOpenItem = onOpenItem,
        onOpenType = onOpenType, onOpenFolder = onOpenFolder, onFavorites = onFavorites,
        onArchive = onArchive, onTrash = onTrash, onAllItems = onAllItems, onSearch = onSearch,
        onScanFidoQr = onScanFidoQr,
        onUnlock = onUnlock, modifier = Modifier.fillMaxSize(),
        selection = selection, onRequestDeleteItem = onRequestDeleteItem,
    )
}
