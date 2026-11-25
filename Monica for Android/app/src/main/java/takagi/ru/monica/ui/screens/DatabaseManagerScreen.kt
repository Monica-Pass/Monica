package takagi.ru.monica.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.ui.gestures.SwipeActions
import takagi.ru.monica.credentialexchange.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.MdbxViewModel
import java.util.Locale

private data class ManagerActionRequest(val pane: DatabaseManagerPane, val paneIndex: Int,
    val location: DatabaseManagerLocation, val ids: Set<String>, val rows: List<MdbxStructureNode>,
    val title: String, val revision: String)
private data class ManagerTransferRequest(val source: ManagerActionRequest, val target: DatabaseManagerLocation,
    val targetTitle: String, val targetPath: String, val copy: Boolean)

private data class ManagerNameRequest(val location: DatabaseManagerLocation, val row: MdbxStructureNode? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatabaseManagerScreen(initialDatabaseKey: String?, mdbxViewModel: MdbxViewModel,
    keepassViewModel: LocalKeePassViewModel, onBack: () -> Unit,
    onManageDatabase: (ImportDestination) -> Unit = {},
    onNativeEntry: (takagi.ru.monica.keepass.KeePassNativeResolvedRoute) -> Unit = {}) {
    val context = LocalContext.current
    val mdbxDatabases by mdbxViewModel.allDatabases.collectAsState()
    val mdbxDatabasesLoaded by mdbxViewModel.allDatabasesLoaded.collectAsState()
    val repo = remember { DatabaseManagerRepository(context) }
    val left = rememberSaveable(saver = DatabaseManagerPane.Saver) { DatabaseManagerPane(initialDatabaseKey) }
    val right = rememberSaveable(saver = DatabaseManagerPane.Saver) { DatabaseManagerPane() }
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var twoPanes by rememberSaveable { mutableStateOf(landscape) }
    var active by rememberSaveable { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var stores by remember { mutableStateOf(emptyList<DatabaseManagerStore>()) }
    var nameRequest by remember { mutableStateOf<ManagerNameRequest?>(null) }
    var report by remember { mutableStateOf<DatabaseManagerReport?>(null) }
    var detail by remember { mutableStateOf<Pair<DatabaseManagerLocation, MdbxStructureNode>?>(null) }
    var comparisonDatabaseId by remember { mutableStateOf<Long?>(null) }
    var transferRequest by remember { mutableStateOf<ManagerTransferRequest?>(null) }
    var actionRequest by remember { mutableStateOf<ManagerActionRequest?>(null) }
    var deleteRequest by remember { mutableStateOf<ManagerActionRequest?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val unlocked by SessionManager.isUnlocked.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!foreground) { left.snapshot = null; right.snapshot = null; detail = null; nameRequest = null; transferRequest = null; actionRequest = null; deleteRequest = null; deleteError = null; report = null }
        }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revision, foreground, unlocked) {
        if (foreground && unlocked) stores = repo.stores() else {
            stores = emptyList(); detail = null; nameRequest = null; transferRequest = null; actionRequest = null; deleteRequest = null; deleteError = null; report = null
        }
    }
    listOf(left, right).forEach { pane ->
        LaunchedEffect(pane.databaseKey, revision, foreground, unlocked, mdbxDatabases, mdbxDatabasesLoaded) {
            pane.snapshot = null
            if (!foreground || !unlocked) return@LaunchedEffect
            val location = pane.location ?: return@LaunchedEffect
            if (location.database.mdbxId != null && (!mdbxDatabasesLoaded || mdbxDatabases.any {
                it.id == location.database.mdbxId && (it.tigaModeEnum == takagi.ru.monica.data.MdbxTigaMode.GLITTER ||
                    takagi.ru.monica.repository.MdbxClientModePolicy.isEnvelope(it.encryptedPassword))
            })) return@LaunchedEffect
            pane.loading = true; pane.error = null
            try { pane.installSnapshot(repo.browse(location)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { pane.error = error.message.orEmpty() }
            finally { pane.loading = false }
        }
    }
    suspend fun refreshDatabases(vararg keys: ImportDestination) {
        keys.distinct().forEach { key ->
            if (key.mdbxId != null) mdbxViewModel.refreshManagerProjection(key.databaseId)
        }
    }
    fun mutate(location: DatabaseManagerLocation, operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { operation(); refreshDatabases(location.database) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report = DatabaseManagerReport(0, listOf(DatabaseManagerFailure("", error.message.orEmpty()))) }
            finally { busy = false; revision++ }
        }
    }
    val current = if (active == 0) left else right
    val glitterDatabase = mdbxDatabases.firstOrNull {
        it.id == current.location?.database?.mdbxId && (it.tigaModeEnum == takagi.ru.monica.data.MdbxTigaMode.GLITTER ||
            takagi.ru.monica.repository.MdbxClientModePolicy.isEnvelope(it.encryptedPassword))
    }
    val other = if (active == 0) right else left
    fun requestFor(pane: DatabaseManagerPane, paneIndex: Int, ids: Set<String>): ManagerActionRequest? {
        val location = pane.location ?: return null
        val snapshot = pane.snapshot ?: return null
        if (!pane.isReady) return null
        val rows = snapshot.nodes.filter { it.id in ids }
        if (rows.size != ids.size) return null
        return ManagerActionRequest(pane, paneIndex, location, ids.toSet(), rows, snapshot.title, snapshot.revision)
    }
    fun chooseDestination(request: ManagerActionRequest) {
        actionRequest = null
        request.pane.selected = request.ids; request.pane.selectionMode = true
        twoPanes = true; active = 1 - request.paneIndex
    }
    fun beginTransfer(request: ManagerActionRequest, copy: Boolean) {
        val target = if (request.paneIndex == 0) right else left
        val targetLocation = target.location ?: return
        val targetSnapshot = target.snapshot ?: return
        if (!twoPanes || !target.isReady || busy) return
        actionRequest = null
        transferRequest = ManagerTransferRequest(request, targetLocation, targetSnapshot.title,
            targetSnapshot.index.ancestors(target.folderId).joinToString(" / ") { it.name }, copy)
    }
    fun back() {
        when {
            busy -> Unit
            current.isSelecting -> current.clearSelection()
            current.query.isNotBlank() -> current.query = ""
            current.search -> current.search = false
            current.folderId != null -> current.navigate(current.databaseKey, current.snapshot?.index?.folders?.get(current.folderId)?.parentId)
            current.databaseKey != null -> current.navigate(null)
            else -> onBack()
        }
    }
    if (glitterDatabase != null) {
        MdbxUnsupportedModePage(glitterDatabase.name) {
            current.navigate(null)
        }
        return
    }
    BackHandler(enabled = comparisonDatabaseId == null) { if (detail != null) detail = null else back() }
    comparisonDatabaseId?.let { databaseId ->
        // Keep this route while the system document picker is open. The comparison screen
        // independently clears plaintext and validates the session before accepting a result.
        DatabaseCompareScreen(databaseId) { comparisonDatabaseId = null }
        return
    }
    detail?.let { (location, row) ->
        when (location.database.kind) {
            ImportDestinationKind.KEEPASS -> {
                val databases by keepassViewModel.allDatabases.collectAsState()
                databases.firstOrNull { it.id == location.database.databaseId }?.let { database ->
                    KeePassNativeManagerScreen(database, keepassViewModel, { detail = null; revision++ }, onNativeEntry,
                        initialEntryUuid = row.id)
                }
            }
            else -> {
                var payload by remember(location, row.id) { mutableStateOf<String?>(null) }
                var failure by remember { mutableStateOf(false) }
                LaunchedEffect(location, row.id, unlocked, foreground) {
                    payload = null
                    if (!unlocked || !foreground) return@LaunchedEffect
                    try { payload = withContext(Dispatchers.IO) { managerDetailPayload(repo, location, row) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failure = true }
                }
                MdbxUnknownEntryContent(row.name, mdbxBrowserTypeName(row.metadata), payload, failure, { detail = null },
                    notice = stringResource(R.string.mdbx_native_readonly))
            }
        }
        return
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.mdbx_native_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = ::back, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                IconButton(onClick = { twoPanes = !twoPanes; active = 0 }, enabled = !busy) {
                    Icon(if (twoPanes) Icons.Default.ViewAgenda else Icons.Default.VerticalSplit, stringResource(R.string.manager_dual))
                }
                IconButton(onClick = { revision++ }, enabled = !busy) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
            })
    }, bottomBar = {
        DatabaseManagerActionBar(current, other, active, twoPanes, busy, progress,
            onBack = { current.goBack() }, onForward = { current.goForward() },
            onCreate = { current.location?.let { nameRequest = ManagerNameRequest(it) } },
            onSwitch = { active = 1 - active },
            onParent = { if (current.folderId != null) current.navigate(current.databaseKey,
                current.snapshot?.index?.folders?.get(current.folderId)?.parentId) else current.navigate(null) },
            onCopy = { requestFor(current, active, current.selected)?.let { beginTransfer(it, true) } },
            onMove = { requestFor(current, active, current.selected)?.let { beginTransfer(it, false) } },
            onChooseDestination = { requestFor(current, active, current.selected)?.let(::chooseDestination) },
            onMore = { actionRequest = requestFor(current, active, current.selected) },
            onClear = { current.clearSelection() })
    }) { padding ->
        if (foreground && unlocked) Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(left, right).take(if (twoPanes) 2 else 1).forEachIndexed { index, pane ->
                DatabaseManagerPaneContent(pane, stores, index.toString(), twoPanes, active == index, !busy,
                    onActivate = { active = index }, onOpen = { row -> pane.location?.let { detail = it to row } },
                    modifier = Modifier.weight(1f),
                    onCreateFolder = { pane.location?.let { nameRequest = ManagerNameRequest(it) } },
                    onManageDatabase = { pane.location?.database?.let(onManageDatabase) },
                    onCompareDatabase = {
                        if (!busy && foreground && SessionManager.isUnlocked.value) {
                            pane.location?.database?.takeIf { it.kind == ImportDestinationKind.KEEPASS }
                                ?.let { comparisonDatabaseId = it.databaseId }
                        }
                    },
                    onActions = { ids -> active = index; actionRequest = requestFor(pane, index, ids) })
            }
        }
    }
    nameRequest?.let { request ->
        var name by remember(request) { mutableStateOf(request.row?.name.orEmpty()) }
        AlertDialog(onDismissRequest = { nameRequest = null }, title = { Text(stringResource(if (request.row == null) R.string.keepass_native_create_group else R.string.rename_category)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.title)) }) },
            confirmButton = { TextButton(enabled = name.isNotBlank() && name.length <= 512, onClick = {
                nameRequest = null; mutate(request.location) {
                    if (request.row == null) repo.createFolder(request.location, name.trim()) else repo.rename(request.location, request.row, name.trim())
                }
            }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { nameRequest = null }) { Text(stringResource(R.string.cancel)) } })
    }
    actionRequest?.takeIf { foreground && unlocked }?.let { request ->
        val target = if (request.paneIndex == 0) right else left
        DatabaseManagerActionsSheet(request.title, request.paneIndex, request.rows, twoPanes,
            canTransfer = twoPanes && target.isReady && !busy,
            onDismiss = { actionRequest = null },
            onCopy = { beginTransfer(request, true) }, onMove = { beginTransfer(request, false) },
            onChooseDestination = { chooseDestination(request) },
            onOpen = {
                actionRequest = null
                request.rows.singleOrNull()?.let { row ->
                    if (row.type == MdbxStructureNodeType.FOLDER) request.pane.navigate(request.location.database.key, row.id)
                    else detail = request.location to row
                }
            },
            onRename = { actionRequest = null; request.rows.singleOrNull()?.let { nameRequest = ManagerNameRequest(request.location, it) } },
            onSelect = { actionRequest = null; request.rows.singleOrNull()?.let { request.pane.toggleSelection(it.id) } },
            onSelectAll = { actionRequest = null; request.pane.selectAll(request.pane.visibleIds) },
            onClear = { actionRequest = null; request.pane.clearSelection() },
            onDelete = { actionRequest = null; deleteError = null; deleteRequest = request })
    }
    deleteRequest?.takeIf { foreground && unlocked }?.let { request ->
        DatabaseManagerDeleteDialog(request.title, request.paneIndex, request.ids.size,
            request.location.database.kind == ImportDestinationKind.LOCAL, busy, deleteError,
            onDismiss = { deleteRequest = null; deleteError = null }, onConfirm = {
                if (!busy) {
                    busy = true; deleteError = null
                    scope.launch {
                        try {
                            repo.delete(request.location, request.ids, request.revision)
                            request.pane.clearSelection()
                            deleteRequest = null
                            refreshDatabases(request.location.database)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { deleteError = context.getString(R.string.save_failed_with_error, error.message.orEmpty()) }
                        finally { busy = false; revision++ }
                    }
                }
            })
    }
    transferRequest?.let { request ->
        val source = request.source
        AlertDialog(onDismissRequest = { transferRequest = null }, title = { Text(stringResource(if (request.copy) R.string.copy else R.string.move)) },
            text = { Text(stringResource(R.string.manager_confirm_transfer, source.ids.size,
                source.title, request.targetTitle, request.targetPath)) },
            confirmButton = { TextButton(modifier = Modifier.testTag("manager-confirm-transfer"), onClick = {
                transferRequest = null
                busy = true; progress = 0f
                scope.launch {
                    try {
                        report = repo.transfer(source.location, request.target, source.ids, request.copy, source.revision) { done, total ->
                            progress = done.toFloat() / total.coerceAtLeast(1)
                        }
                        source.pane.clearSelection()
                        refreshDatabases(source.location.database, request.target.database)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { report = DatabaseManagerReport(0, listOf(DatabaseManagerFailure("", error.message.orEmpty()))) }
                    finally { busy = false; revision++ }
                }
            }) { Text(stringResource(if (request.copy) R.string.copy else R.string.move)) } },
            dismissButton = { TextButton(onClick = { transferRequest = null }) { Text(stringResource(R.string.cancel)) } })
    }
    report?.takeIf { foreground && unlocked }?.let { result ->
        AlertDialog(onDismissRequest = { report = null }, title = { Text(stringResource(R.string.manager_result)) },
            text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(stringResource(R.string.manager_result_count, result.completed, result.failures.size)) }
                items(result.failures) { failure ->
                    Column { Text(failure.title, style = MaterialTheme.typography.titleSmall)
                        Text(managerFailureText(failure.reason), style = MaterialTheme.typography.bodyMedium)
                        var technical by remember { mutableStateOf(false) }
                        TextButton(onClick = { technical = !technical }) { Text(stringResource(R.string.manager_technical_details)) }
                        if (technical) Text(failure.reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } }, confirmButton = { TextButton(onClick = { report = null }) { Text(stringResource(R.string.close)) } })
    }
}

@Composable
internal fun DatabaseManagerPaneContent(pane: DatabaseManagerPane, stores: List<DatabaseManagerStore>, paneId: String,
    compact: Boolean, active: Boolean, enabled: Boolean, onActivate: () -> Unit,
    onOpen: (MdbxStructureNode) -> Unit, modifier: Modifier = Modifier,
    onCreateFolder: () -> Unit = {}, onManageDatabase: () -> Unit = {},
    onCompareDatabase: () -> Unit = {},
    onActions: (Set<String>) -> Unit = {}) {
    val index = pane.snapshot?.index
    val folderId = pane.folderId?.takeIf { it in index?.folders.orEmpty() }
    val typeNames = pane.snapshot?.nodes.orEmpty().map { it.metadata }.distinct().associateWith { mdbxBrowserTypeName(it) }
    val visible = remember(pane.snapshot, folderId, pane.query, pane.descending, typeNames) {
        (if (pane.query.isBlank()) index?.children(folderId).orEmpty() else index?.nodes.orEmpty().filter {
            it.name.contains(pane.query, true) || typeNames[it.metadata].orEmpty().contains(pane.query, true)
        }).sortedWith(compareBy<MdbxStructureNode> { it.type != MdbxStructureNodeType.FOLDER }
            .thenComparator { a, b -> (if (pane.descending) -1 else 1) * a.name.lowercase(Locale.ROOT).compareTo(b.name.lowercase(Locale.ROOT)) }.thenBy { it.id })
    }
    LaunchedEffect(visible) { pane.visibleIds = visible.map { it.id } }
    val stateHolder = rememberSaveableStateHolder()
    val activate by rememberUpdatedState(onActivate)
    Column(modifier.fillMaxHeight().testTag("manager-pane-$paneId").pointerInput(enabled) {
        if (enabled) awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); activate() }
    }) {
        Surface(shape = RoundedCornerShape(16.dp), color = if (active && compact) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().testTag("manager-header-$paneId"), enabled = enabled, onClick = onActivate) {
            Row(Modifier.heightIn(min = if (compact) 40.dp else 48.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pane.snapshot?.title ?: stringResource(R.string.manager_databases), Modifier.weight(1f),
                    fontSize = if (compact) 13.sp else 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { onActivate(); menu = true }, enabled = enabled, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.more_options), Modifier.size(20.dp))
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.manager_databases)) }, onClick = { menu = false; pane.navigate(null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.search)) }, onClick = { menu = false; pane.search = !pane.search })
                        if (pane.location != null) DropdownMenuItem(text = { Text(stringResource(R.string.keepass_native_create_group)) }, onClick = { menu = false; onCreateFolder() })
                        if (pane.location?.database?.kind == ImportDestinationKind.KEEPASS) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.db_compare_title)) },
                                enabled = enabled, modifier = Modifier.testTag("manager-compare-$paneId"),
                                onClick = { menu = false; onCompareDatabase() })
                        }
                    }
                }
            }
        }
        if (pane.databaseKey != null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("manager-path-$paneId"), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onActivate(); pane.navigate(pane.databaseKey) }, enabled = enabled, modifier = if (compact) Modifier.height(36.dp) else Modifier, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("/", fontSize = 12.sp)
                }
                index?.ancestors(folderId).orEmpty().forEach { folder ->
                    TextButton(onClick = { onActivate(); pane.navigate(pane.databaseKey, folder.id) }, enabled = enabled, modifier = if (compact) Modifier.height(36.dp) else Modifier, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(folder.name, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val selectionDescription = stringResource(R.string.manager_items_selected, visible.size, pane.selected.size)
                Text(if (compact) "${visible.size} · ✓ ${pane.selected.size}" else selectionDescription,
                    Modifier.weight(1f).semantics { contentDescription = selectionDescription },
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onActivate(); pane.selectAll(visible.map { it.id }) }, modifier = Modifier.size(32.dp), enabled = enabled) {
                    Icon(Icons.Default.SelectAll, stringResource(R.string.select_all), Modifier.size(18.dp))
                }
                IconButton(onClick = { onActivate(); pane.selectionAnchor = null; pane.descending = !pane.descending }, enabled = enabled, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Sort, stringResource(R.string.keepass_native_sort), Modifier.size(18.dp)) }
            }
        }
        if (pane.search) OutlinedTextField(pane.query, { onActivate(); pane.clearSelection(); pane.query = it }, singleLine = true, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("manager-search-$paneId"), shape = RoundedCornerShape(16.dp),
            placeholder = { Text(stringResource(R.string.search), fontSize = 12.sp) })
        if (pane.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        pane.error?.let { error ->
            Text(managerFailureText(error), Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onManageDatabase, enabled = enabled) { Text(stringResource(R.string.manager_open_database)) }
        }
        if (pane.databaseKey == null) LazyColumn(Modifier.weight(1f).testTag("manager-list-$paneId"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            stores.filter { it.title.contains(pane.query, true) }.groupBy { it.key.kind }.forEach { (kind, databases) ->
                item("section:$kind") { Text(when (kind) { ImportDestinationKind.LOCAL -> "Monica"; ImportDestinationKind.MDBX -> "MDBX"; else -> "KeePass" },
                    Modifier.padding(top = 16.dp, bottom = 6.dp, start = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                itemsIndexed(databases, key = { _, store -> store.key.key }) { position, store ->
                    Surface(onClick = { onActivate(); pane.navigate(store.key.key) }, shape = settingsSectionItemShape(position, databases.size),
                        modifier = Modifier.testTag("manager-store-$paneId-${store.key.key}"),
                        color = MaterialTheme.colorScheme.surfaceContainerLow, enabled = store.available && enabled) {
                        Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 52.dp else 64.dp).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Storage, null, Modifier.size(if (compact) 20.dp else 24.dp))
                            Text(store.title, fontSize = if (compact) 13.sp else 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        } else stateHolder.SaveableStateProvider("${pane.databaseKey}:$folderId:${pane.query.isNotBlank()}") {
            val state = rememberLazyListState()
            ManagerEntryList(visible, pane, paneId, compact, enabled, state, onActivate, onOpen, onActions,
                onParent = { pane.navigate(pane.databaseKey, index?.folders?.get(folderId)?.parentId) }, hasParent = folderId != null,
                modifier = Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ManagerEntryList(rows: List<MdbxStructureNode>, pane: DatabaseManagerPane, paneId: String, compact: Boolean,
    enabled: Boolean, state: LazyListState, onActivate: () -> Unit, onOpen: (MdbxStructureNode) -> Unit,
    onActions: (Set<String>) -> Unit, onParent: () -> Unit, hasParent: Boolean, modifier: Modifier = Modifier) {
    val selectLabel = stringResource(R.string.manager_select)
    val orderedIds = rows.map { it.id }
    LazyColumn(state = state, userScrollEnabled = enabled,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).testTag("manager-list-$paneId"),
        verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        if (hasParent) item("parent") { TextButton(onClick = { onActivate(); onParent() }, enabled = enabled,
            modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.ArrowUpward, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("..")
        } }
        itemsIndexed(rows, key = { _, row -> row.id }) { position, row ->
            val selected = row.id in pane.selected
            val shape = settingsSectionItemShape(position, rows.size)
            fun actions() { onActivate(); onActions(if (selected) pane.selected else setOf(row.id)) }
            SwipeActions(onSwipeLeft = {}, onSwipeRight = { onActivate(); pane.swipeSelect(row.id, orderedIds) },
                enabled = enabled, allowSwipeLeft = false, isSwiped = selected, cardShape = shape) {
                Surface(shape = shape,
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().testTag("manager-row-$paneId-${row.id}").semantics {
                        customActions = listOf(CustomAccessibilityAction(selectLabel) {
                            if (enabled) { onActivate(); pane.toggleSelection(row.id) }
                            enabled
                        })
                    }.clip(shape).combinedClickable(enabled = enabled, onLongClick = ::actions, onClick = {
                        onActivate()
                        when {
                            pane.isSelecting -> pane.toggleSelection(row.id)
                            row.type == MdbxStructureNodeType.FOLDER -> pane.navigate(pane.databaseKey, row.id)
                            else -> onOpen(row)
                        }
                    })) {
                    Row(Modifier.heightIn(min = if (compact) 58.dp else 64.dp)
                        .padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
                        Icon(if (selected) Icons.Default.CheckCircle else if (row.type == MdbxStructureNodeType.FOLDER) Icons.Default.Folder else Icons.Default.Description,
                            null, Modifier.size(if (compact) 20.dp else 24.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(row.name, modifier = Modifier.testTag("manager-title-$paneId-${row.id}"),
                                fontSize = if (compact) 12.sp else 16.sp, lineHeight = if (compact) 16.sp else 24.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (row.type != MdbxStructureNodeType.FOLDER) Text(mdbxBrowserTypeName(row.metadata),
                                fontSize = if (compact) 10.sp else 12.sp, lineHeight = if (compact) 14.sp else 18.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        IconButton(onClick = ::actions, enabled = enabled,
                            modifier = Modifier.size(if (compact) 32.dp else 40.dp).testTag("manager-menu-$paneId-${row.id}")) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.more_options), Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        if (rows.isEmpty()) item { Text(stringResource(R.string.keepass_native_empty_group), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun managerFailureText(reason: String) = stringResource(when {
    reason.contains("lossless", true) || reason.contains("schema", true) || reason.contains("format", true) || reason.contains("metadata", true) || reason.contains("legacy image", true) || reason.contains("references", true) -> R.string.manager_failure_format
    reason.contains("changed", true) || reason.contains("verification", true) || reason.contains("differs", true) -> R.string.manager_failure_changed
    reason.contains("locked", true) || reason.contains("unlock", true) || reason.contains("password", true) -> R.string.manager_failure_locked
    else -> R.string.manager_failure_general
})
