package takagi.ru.monica.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.repository.PasswordPageAggregateStackRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.ManualStackDialogMode
import takagi.ru.monica.ui.PasswordStackModeDialog
import takagi.ru.monica.ui.applySharedPasswordStackMode
import takagi.ru.monica.ui.common.selection.SelectionActionBar
import takagi.ru.monica.ui.gestures.SwipeActions
import takagi.ru.monica.ui.vaultv2.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import takagi.ru.monica.R
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.ui.components.GroupedItemDefaults
import takagi.ru.monica.ui.icons.MonicaIcons
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.MdbxViewModel

/** Browser only. Details and edits use independent navigation destinations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeApiTokensScreen(
    viewModel: MdbxViewModel,
    initialDatabaseId: Long? = null,
    onNavigateBack: () -> Unit,
    onOpen: (Long, String) -> Unit,
    onCreate: (Long?) -> Unit,
    onManageDatabases: () -> Unit,
    appSettings: AppSettings = AppSettings(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val securityManager = takagi.ru.monica.ui.rememberUiSecurityManager()
    val stacks = remember(context) { PasswordPageAggregateStackRepository(PasswordDatabase.getDatabase(context).passwordPageAggregateStackDao()) }
    val memberships by stacks.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val selected = remember { mutableStateListOf<String>() }
    var delete by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf(false) }
    var stack by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(ManualStackDialogMode.STACK) }
    BackHandler(selected.isNotEmpty()) { selected.clear() }
    val allDatabases by viewModel.allDatabases.collectAsStateWithLifecycle()
    val databasesLoaded by viewModel.allDatabasesLoaded.collectAsStateWithLifecycle()
    val databases = remember(allDatabases) { allDatabases.filter { it.engineTypeEnum == MdbxEngineType.RUST_MDBX2 } }
    var databaseId by rememberSaveable { mutableStateOf(initialDatabaseId) }
    var folderId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(databasesLoaded, databases.map { it.id }, databaseId) {
        if (databasesLoaded && databases.none { it.id == databaseId }) {
            databaseId = (databases.firstOrNull { it.isDefault } ?: databases.firstOrNull())?.id
            folderId = null
        }
    }
    val filter = databaseId?.let { id -> folderId?.let { CategoryFilter.MdbxFolderFilter(id, it) } ?: CategoryFilter.MdbxDatabase(id) }
        ?: CategoryFilter.All
    val tokens = rememberNativeTokenList(viewModel, filter, query, onlyTokens = true, onToggle = {},
        onOpen = { db, entry -> if (db != null && entry != null) onOpen(db, entry) })
    val typeLabel = stringResource(R.string.entry_type_api_token)
    val items = remember(tokens.entries, typeLabel) { buildVaultV2NativeTokenItems(tokens.entries, typeLabel) }
    val rows = remember(items, memberships, appSettings.passwordGroupMode, appSettings.stackCardMode, appSettings.passwordWebsiteStackMatchMode) {
        buildVaultV2StackedSections(items, memberships, emptyMap(), memberships.filter { it.stackOrder < 0 }
            .mapNotNull { takagi.ru.monica.ui.password.passwordIdFromSelectionKey(it.itemKey) }.toSet(),
            appSettings.passwordGroupMode, appSettings.passwordWebsiteStackMatchMode, appSettings.stackCardMode == "ALWAYS_EXPANDED")
            .flatMap { it.second }
    }
    fun toggle(key: String) { if (key in selected) selected.remove(key) else selected.add(key) }
    val selectedItems = items.filter { it.key in selected }
    val open: (VaultV2Item) -> Unit = { it.nativeToken?.let { token -> onOpen(token.databaseId, token.entryId) } }
    val requestDelete: (List<VaultV2Item>) -> Unit = { group -> selected.clear(); selected.addAll(group.map { it.key }); delete = true }
    fun deleteSelected(targets: List<VaultV2Item>) { delete = false; scope.launch {
        try {
            targets.forEach { viewModel.deleteNativeApiToken(checkNotNull(it.nativeToken)); stacks.clearManualStack(listOf(it.key)) }
            selected.clear()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Toast.makeText(context, R.string.api_token_load_error, Toast.LENGTH_LONG).show() }
        delete = false
    } }
    Scaffold(modifier = Modifier.imePadding(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.entry_type_api_token)) },
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(MonicaIcons.Navigation.back, stringResource(R.string.back)) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)) },
        floatingActionButton = {
            if (selected.isNotEmpty()) SelectionActionBar(selectedCount = selected.size, onExit = { selected.clear() },
                onSelectAll = { selected.clear(); selected.addAll(items.map { it.key }) },
                onFavorite = { scope.launch {
                    try {
                        val favorite = !selectedItems.all { it.isFavorite }
                        selectedItems.forEach { viewModel.setNativeApiTokenFavorite(checkNotNull(it.nativeToken), favorite) }
                        selected.clear()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { Toast.makeText(context, R.string.api_token_load_error, Toast.LENGTH_LONG).show() }
                } },
                onDelete = { delete = true }, onMoveToCategory = { move = true },
                onStack = if (selected.size >= 2) ({ stack = true }) else null)
            else if (databases.isNotEmpty()) FloatingActionButton(onClick = { onCreate(databaseId) }) {
                Icon(Icons.Default.Add, stringResource(R.string.add))
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), state = rememberLazyListState(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(GroupedItemDefaults.Spacing)) {
            item("source") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (databasesLoaded) ApiTokenStorageSelector(viewModel, databases, databaseId,
                        folderId, editing = false, enabled = true,
                        onSelect = { id, folder -> databaseId = id; folderId = folder; selected.clear() }, onManageDatabases = onManageDatabases)
                    takagi.ru.monica.ui.components.OutlinedTextField(query, { query = it; selected.clear() },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), singleLine = true,
                        label = { Text(stringResource(R.string.search)) })
                }
            }
            if (tokens.loading && rows.isEmpty()) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (tokens.failed) item("error") { TextButton(onClick = tokens.onRetry) { Text(stringResource(R.string.api_token_reload)) } }
            if (rows.isEmpty() && !tokens.loading && !tokens.failed) item("empty") { Text(stringResource(R.string.api_token_empty)) }
            itemsIndexed(rows, key = { _, item -> item.key }) { index, item ->
                if (item.stackedItems.isNotEmpty()) VaultV2PasswordStackCard(item, appSettings, securityManager, selected, open, requestDelete,
                    onFavoriteItem = { row -> tokens.onFavorite(checkNotNull(row.nativeToken), !row.isFavorite) },
                    onReorderStack = { group -> scope.launch { stacks.applyManualStack(group.map { it.key }) } })
                else {
                    val shape = GroupedItemDefaults.shape(index, rows.size)
                    SwipeActions(onSwipeLeft = { requestDelete(listOf(item)) }, onSwipeRight = { toggle(item.key) }, isSwiped = false, cardShape = shape) {
                        VaultV2ItemCard(item, null, appSettings, securityManager, item.key in selected, shape,
                            onClick = { if (selected.isEmpty()) open(item) else toggle(item.key) }, onLongClick = { toggle(item.key) })
                    }
                }
            }
        }
    }
    if (stack) PasswordStackModeDialog(selected.size, mode, { mode = it }, { stack = false }, { scope.launch {
        try {
            applySharedPasswordStackMode(mode, selected.toList(), stacks)
            selected.clear(); stack = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Toast.makeText(context, R.string.api_token_load_error, Toast.LENGTH_LONG).show() }
    } })
    if (delete) {
        val targets = remember { selectedItems.toList() }
        val deleteTargets = { deleteSelected(targets) }
        takagi.ru.monica.ui.common.dialog.DeleteConfirmDialog(
            itemTitle = stringResource(R.string.selected_items, targets.size), itemType = typeLabel,
            biometricEnabled = appSettings.biometricEnabled, skipIdentityVerification = appSettings.disablePasswordVerification,
            onDismiss = { delete = false }, onConfirmWithoutVerification = deleteTargets,
            onPasswordVerified = deleteTargets, onConfirmWithBiometric = deleteTargets,
            isRequestCurrent = { delete && selectedItems == targets },
        )
    }
    NativeTokenBatchMoveSheet(move, viewModel, selectedItems.mapNotNull { it.nativeToken }, { move = false }, { selected.clear() })
}
