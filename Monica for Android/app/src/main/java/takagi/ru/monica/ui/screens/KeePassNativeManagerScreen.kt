package takagi.ru.monica.ui.screens

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keemobile.kotpass.constants.GroupOverride
import app.keemobile.kotpass.constants.PredefinedIcon
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.KeePassSyncStatus
import takagi.ru.monica.data.isRemoteSource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.keepass.KeePassFieldChange
import takagi.ru.monica.keepass.KeePassNativeAttachmentRecord
import takagi.ru.monica.keepass.KeePassNativeBrowserSnapshot
import takagi.ru.monica.keepass.KeePassNativeEntryIdentity
import takagi.ru.monica.keepass.KeePassNativeEntryKind
import takagi.ru.monica.keepass.KeePassNativeEntryRecord
import takagi.ru.monica.keepass.KeePassNativeFieldRecord
import takagi.ru.monica.keepass.KeePassNativeGroupIdentity
import takagi.ru.monica.keepass.KeePassNativeGroupRecord
import takagi.ru.monica.keepass.KeePassFolderDragPolicy
import takagi.ru.monica.keepass.KeePassCustomIconEditor
import takagi.ru.monica.keepass.KeePassNativeCustomIconPayload
import takagi.ru.monica.keepass.KeePassNativeHistoryVersion
import takagi.ru.monica.keepass.KeePassNativeManagerRetainedState
import takagi.ru.monica.keepass.KeePassNativeResolvedRoute
import takagi.ru.monica.keepass.KeePassNativeDeleteMode
import takagi.ru.monica.keepass.KeePassNativeGroupUpdate
import takagi.ru.monica.keepass.KeePassNativeManagement
import takagi.ru.monica.keepass.KeePassNativeSortMode
import takagi.ru.monica.keepass.KeePassNativeSortOptions
import takagi.ru.monica.keepass.KeePassNativeSearch
import takagi.ru.monica.keepass.KeePassNativeSearchField
import takagi.ru.monica.keepass.KeePassNativeSearchOptions
import takagi.ru.monica.keepass.KeePassTemplateEngine
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.ui.components.CustomFieldEditCard
import takagi.ru.monica.ui.components.CustomFieldSectionHeader
import takagi.ru.monica.util.PasswordGenerator
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class
)
@Composable
internal fun KeePassNativeManagerScreen(
    database: LocalKeePassDatabase,
    viewModel: LocalKeePassViewModel,
    onNavigateBack: () -> Unit,
    onNavigateSpecialized: (KeePassNativeResolvedRoute) -> Unit
) {
    val scope = rememberCoroutineScope()
    val conflictResolutionVersion by viewModel.conflictResolution.resolutionVersion.collectAsStateWithLifecycle()
    val retainedState = remember(database.id) { viewModel.nativeManagerRetainedState(database.id) }
    var browser by remember(database.id) { mutableStateOf<KeePassNativeBrowserSnapshot?>(null) }
    var loading by remember(database.id) { mutableStateOf(true) }
    var error by remember(database.id) { mutableStateOf<String?>(null) }
    var currentGroupIdentity by remember(database.id) {
        mutableStateOf(
            retainedState.currentGroupUuid?.let { uuid -> KeePassNativeGroupIdentity(database.id, uuid) }
        )
    }
    var selectedEntryIdentity by remember(database.id) { mutableStateOf<KeePassNativeEntryIdentity?>(null) }
    var editingEntryIdentity by remember(database.id) { mutableStateOf<KeePassNativeEntryIdentity?>(null) }
    var searchQuery by remember(database.id) { mutableStateOf(retainedState.searchQuery) }
    var searchExpanded by remember(database.id) {
        mutableStateOf(retainedState.searchQuery.isNotBlank())
    }
    var searchOptions by remember(database.id) {
        mutableStateOf(retainedState.searchOptions.copy(query = "", groupScope = null))
    }
    var searchCurrentFolderOnly by remember(database.id) { mutableStateOf(retainedState.searchCurrentFolderOnly) }
    var showSearchOptions by remember { mutableStateOf(false) }
    var createGroupParent by remember { mutableStateOf<KeePassNativeGroupIdentity?>(null) }
    var renameGroupIdentity by remember { mutableStateOf<KeePassNativeGroupIdentity?>(null) }
    var moveGroupIdentity by remember { mutableStateOf<KeePassNativeGroupIdentity?>(null) }
    var moveGroupUuids by remember { mutableStateOf<Set<UUID>?>(null) }
    var deleteGroupIdentity by remember { mutableStateOf<KeePassNativeGroupIdentity?>(null) }
    var showDatabaseSettings by remember(database.id) { mutableStateOf(false) }
    var showDatabaseTools by remember(database.id) { mutableStateOf(false) }
    var sortOptions by remember(database.id) { mutableStateOf(KeePassNativeSortOptions()) }
    var selectedEntryUuids by remember(database.id) { mutableStateOf(emptySet<UUID>()) }
    var selectedGroupUuids by remember(database.id) { mutableStateOf(emptySet<UUID>()) }
    var creatingEntryParent by remember { mutableStateOf<KeePassNativeGroupIdentity?>(null) }
    var duplicateEntryIdentity by remember { mutableStateOf<KeePassNativeEntryIdentity?>(null) }
    var moveEntryUuids by remember { mutableStateOf<Set<UUID>?>(null) }
    var deleteEntryUuids by remember { mutableStateOf<Set<UUID>?>(null) }
    var groupPropertiesIdentity by remember { mutableStateOf<KeePassNativeGroupIdentity?>(null) }
    var saveTemplateIdentity by remember { mutableStateOf<KeePassNativeEntryIdentity?>(null) }
    var instantiateTemplateIdentity by remember { mutableStateOf<KeePassNativeEntryIdentity?>(null) }
    var deleteTemplateIdentity by remember { mutableStateOf<KeePassNativeEntryIdentity?>(null) }

    fun reload(afterLoad: (() -> Unit)? = null) {
        scope.launch {
            loading = true
            error = null
            viewModel.openNativeBrowser(database.id)
                .onSuccess { updated ->
                    browser = updated
                    val current = currentGroupIdentity
                    currentGroupIdentity = current
                        ?.takeIf { identity -> updated.group(identity) != null }
                        ?: updated.rootGroup.identity
                    selectedEntryIdentity = selectedEntryIdentity
                        ?.takeIf { identity -> updated.entry(identity) != null }
                    editingEntryIdentity = editingEntryIdentity
                        ?.takeIf { identity -> updated.entry(identity) != null }
                    selectedEntryUuids = selectedEntryUuids.filterTo(linkedSetOf()) { uuid ->
                        updated.entry(KeePassNativeEntryIdentity(database.id, uuid)) != null
                    }
                    selectedGroupUuids = selectedGroupUuids.filterTo(linkedSetOf()) { uuid ->
                        updated.group(KeePassNativeGroupIdentity(database.id, uuid)) != null
                    }
                    afterLoad?.invoke()
                }
                .onFailure { failure -> error = failure.message ?: failure.javaClass.simpleName }
            loading = false
        }
    }

    LaunchedEffect(database.id, conflictResolutionVersion) { reload() }
    LaunchedEffect(currentGroupIdentity, searchQuery, searchOptions, searchCurrentFolderOnly) {
        viewModel.retainNativeManagerState(
            KeePassNativeManagerRetainedState(
                databaseId = database.id,
                currentGroupUuid = currentGroupIdentity?.groupUuid,
                searchQuery = searchQuery,
                searchOptions = searchOptions.copy(query = "", groupScope = null),
                searchCurrentFolderOnly = searchCurrentFolderOnly
            )
        )
    }

    val snapshot = browser
    val currentGroup = currentGroupIdentity?.let { snapshot?.group(it) }
    val selectedEntry = selectedEntryIdentity?.let { snapshot?.entry(it) }
    val editingEntry = editingEntryIdentity?.let { snapshot?.entry(it) }

    BackHandler {
        when {
            showDatabaseTools -> showDatabaseTools = false
            showDatabaseSettings -> showDatabaseSettings = false
            creatingEntryParent != null -> creatingEntryParent = null
            editingEntryIdentity != null -> editingEntryIdentity = null
            selectedEntryIdentity != null -> selectedEntryIdentity = null
            selectedEntryUuids.isNotEmpty() || selectedGroupUuids.isNotEmpty() -> {
                selectedEntryUuids = emptySet()
                selectedGroupUuids = emptySet()
            }
            searchQuery.isNotBlank() -> {
                searchQuery = ""
                searchExpanded = false
            }
            searchExpanded -> searchExpanded = false
            currentGroup?.parentGroup != null -> currentGroupIdentity = currentGroup.parentGroup
            else -> onNavigateBack()
        }
    }

    AnimatedContent(
        targetState = when {
            showDatabaseTools -> NativeManagerPage.TOOLS
            showDatabaseSettings -> NativeManagerPage.SETTINGS
            creatingEntryParent != null || editingEntry != null -> NativeManagerPage.EDITOR
            selectedEntry != null -> NativeManagerPage.DETAIL
            else -> NativeManagerPage.BROWSER
        },
        transitionSpec = {
            (slideInHorizontally { width -> width / 5 } + fadeIn()) togetherWith
                (slideOutHorizontally { width -> -width / 5 } + fadeOut())
        },
        label = "keepass-native-page"
    ) { page ->
        when (page) {
            NativeManagerPage.TOOLS -> KeePassNativeDatabaseToolsScreen(
                database = database,
                viewModel = viewModel,
                onBack = { showDatabaseTools = false },
                onDatabaseChanged = { reload() }
            )

            NativeManagerPage.SETTINGS -> KeePassNativeDatabaseSettingsScreen(
                database = database,
                viewModel = viewModel,
                onBack = { showDatabaseSettings = false },
                onOpenDatabaseTools = {
                    showDatabaseSettings = false
                    showDatabaseTools = true
                },
                onDatabaseChanged = { reload() },
                onLocked = onNavigateBack
            )

            NativeManagerPage.EDITOR -> {
                val editing = editingEntry
                val templateMode = editing?.kind == KeePassNativeEntryKind.TEMPLATE ||
                    (creatingEntryParent != null && creatingEntryParent == snapshot?.templateGroupIdentity)
                  NativeEntryEditorScreen(
                      entry = editing,
                      parentGroup = creatingEntryParent,
                      templateMode = templateMode,
                      customIcons = snapshot?.customIcons.orEmpty(),
                      customIconReferences = snapshot?.customIconReferences.orEmpty(),
                      revisionToken = snapshot?.sourceRevision?.sha256.orEmpty(),
                    savingEnabled = snapshot != null && !viewModel.isKeePassDatabaseReadOnly(database.id),
                      onBack = {
                        creatingEntryParent = null
                        editingEntryIdentity = null
                      },
                      onDeleteCustomIcon = { uuid, onResult ->
                          scope.launch {
                              viewModel.updateNativeCustomIconPool(
                                  databaseId = database.id,
                                  update = takagi.ru.monica.keepass.KeePassNativeCustomIconPoolUpdate(
                                      remove = setOf(uuid),
                                  ),
                                  expectedRevisionToken = snapshot?.sourceRevision?.sha256.orEmpty(),
                              ).onSuccess {
                                  reload()
                                  onResult(null)
                              }.onFailure { failure ->
                                  onResult(failure.message ?: failure.javaClass.simpleName)
                              }
                          }
                      },
                      onRenameCustomIcon = { uuid, name, onResult ->
                          scope.launch {
                              viewModel.updateNativeCustomIconPool(
                                  databaseId = database.id,
                                  update = takagi.ru.monica.keepass.KeePassNativeCustomIconPoolUpdate(
                                      upsert = listOf(
                                          takagi.ru.monica.keepass.KeePassNativeCustomIconPoolItem(
                                              uuid = uuid,
                                              name = name,
                                          ),
                                      ),
                                  ),
                                  expectedRevisionToken = snapshot?.sourceRevision?.sha256.orEmpty(),
                              ).onSuccess {
                                  reload()
                                  onResult(null)
                              }.onFailure { failure ->
                                  onResult(failure.message ?: failure.javaClass.simpleName)
                              }
                          }
                      },
                      onSave = { fields, presentation, pendingAttachments, onResult ->
                          scope.launch {
                            val outcome = saveKeePassNativeManagerEntry(
                                viewModel = viewModel,
                                databaseId = database.id,
                                editingEntry = editing,
                                creatingParent = creatingEntryParent,
                                templateMode = templateMode,
                                fields = fields,
                                presentation = presentation,
                                pendingAttachments = pendingAttachments,
                                revisionToken = snapshot?.sourceRevision?.sha256.orEmpty(),
                            )
                            val saved = outcome.savedEntry
                            if (saved != null) {
                                creatingEntryParent = null
                                editingEntryIdentity = null
                                reload { selectedEntryIdentity = saved.identity }
                                onResult(null)
                            } else {
                                val failure = outcome.failure
                                    ?: IllegalStateException("KeePass entry save returned no result")
                                onResult(failure.message ?: failure.javaClass.simpleName)
                            }
                        }
                    }
                )
            }

            NativeManagerPage.DETAIL -> selectedEntry?.let { entry ->
                NativeEntryDetailScreen(
                    entry = entry,
                    modificationEnabled = snapshot != null && !viewModel.isKeePassDatabaseReadOnly(database.id),
                    onBack = { selectedEntryIdentity = null },
                    onEdit = { editingEntryIdentity = entry.identity },
                    onAdvanceHotp = { data, onResult ->
                        scope.launch {
                            advanceKeePassNativeManagerHotp(
                                viewModel, entry, data, snapshot?.sourceRevision?.sha256.orEmpty(),
                            ).onSuccess { updated ->
                                browser = updated
                                selectedEntryIdentity = entry.identity
                                onResult(null)
                            }.onFailure { failure -> onResult(failure.message ?: failure.javaClass.simpleName) }
                        }
                    },
                    onAddAttachment = { sourceUri, onResult ->
                        scope.launch {
                            viewModel.addNativeAttachment(
                                databaseId = database.id,
                                entryUuid = entry.identity.entryUuid,
                                sourceUri = sourceUri,
                                expectedRevisionToken = snapshot?.sourceRevision?.sha256.orEmpty()
                            ).onSuccess {
                                reload { selectedEntryIdentity = entry.identity }
                                onResult(null)
                            }.onFailure { failure -> onResult(failure.message ?: failure.javaClass.simpleName) }
                        }
                    },
                    onRenameAttachment = { attachment, newName, onResult ->
                        scope.launch {
                            viewModel.renameNativeAttachment(
                                databaseId = database.id,
                                entryUuid = entry.identity.entryUuid,
                                attachmentHashHex = attachment.hash,
                                currentName = attachment.name,
                                newName = newName,
                                expectedRevisionToken = snapshot?.sourceRevision?.sha256.orEmpty()
                            ).onSuccess {
                                reload { selectedEntryIdentity = entry.identity }
                                onResult(null)
                            }.onFailure { failure -> onResult(failure.message ?: failure.javaClass.simpleName) }
                        }
                    },
                    onExportAttachment = { attachment, destinationUri, onResult ->
                        scope.launch {
                            viewModel.exportNativeAttachment(
                                databaseId = database.id,
                                entryUuid = entry.identity.entryUuid,
                                attachmentHashHex = attachment.hash,
                                currentName = attachment.name,
                                destinationUri = destinationUri
                            ).onSuccess { onResult(null) }
                                .onFailure { failure -> onResult(failure.message ?: failure.javaClass.simpleName) }
                        }
                    },
                    onDeleteAttachment = { attachment, onResult ->
                        scope.launch {
                            viewModel.deleteNativeAttachment(
                                databaseId = database.id,
                                entryUuid = entry.identity.entryUuid,
                                attachmentHashHex = attachment.hash,
                                currentName = attachment.name,
                                expectedRevisionToken = snapshot?.sourceRevision?.sha256.orEmpty()
                            ).onSuccess {
                                reload { selectedEntryIdentity = entry.identity }
                                onResult(null)
                            }.onFailure { failure -> onResult(failure.message ?: failure.javaClass.simpleName) }
                        }
                    },
                    onRestoreHistory = { historyIndex, onResult ->
                        scope.launch {
                            viewModel.restoreNativeEntryHistory(
                                database.id,
                                entry.identity.entryUuid,
                                historyIndex,
                                snapshot?.sourceRevision?.sha256.orEmpty()
                            ).onSuccess {
                                reload { selectedEntryIdentity = entry.identity }
                                onResult(null)
                            }.onFailure { failure -> onResult(failure.message ?: failure.javaClass.simpleName) }
                        }
                    },
                    onDeleteHistory = { historyIndex, onResult ->
                        scope.launch {
                            viewModel.deleteNativeEntryHistory(
                                database.id,
                                entry.identity.entryUuid,
                                historyIndex,
                                snapshot?.sourceRevision?.sha256.orEmpty()
                            ).onSuccess {
                                reload { selectedEntryIdentity = entry.identity }
                                onResult(null)
                            }.onFailure { failure -> onResult(failure.message ?: failure.javaClass.simpleName) }
                        }
                    }
                )
            }

            NativeManagerPage.BROWSER -> NativeBrowserPage(
                database = database,
                browser = snapshot,
                currentGroup = currentGroup,
                loading = loading,
                error = error,
                searchQuery = searchQuery,
                searchExpanded = searchExpanded,
                searchOptions = searchOptions,
                searchCurrentFolderOnly = searchCurrentFolderOnly,
                sortOptions = sortOptions,
                selectedEntryUuids = selectedEntryUuids,
                selectedGroupUuids = selectedGroupUuids,
                readOnly = viewModel.isKeePassDatabaseReadOnly(database.id),
                onBack = {
                    if (currentGroup?.parentGroup != null) currentGroupIdentity = currentGroup.parentGroup
                    else onNavigateBack()
                },
                onRetry = { reload() },
                onResolveConflict = { viewModel.conflictResolution.open(database.id, database.name) },
                onSearchQueryChange = { searchQuery = it },
                onSearchExpandedChange = { expanded ->
                    searchExpanded = expanded
                    if (!expanded) searchQuery = ""
                },
                onOpenSearchOptions = { showSearchOptions = true },
                onSortOptionsChange = { sortOptions = it },
                onOpenDatabaseSettings = { showDatabaseSettings = true },
                onOpenDatabaseTools = { showDatabaseTools = true },
                onOpenTemplates = {
                    snapshot?.templateGroupIdentity?.let { currentGroupIdentity = it }
                },
                onOpenGroup = { currentGroupIdentity = it.identity },
                onToggleGroupSelection = { group ->
                    val uuid = group.identity.groupUuid
                    selectedGroupUuids = if (uuid in selectedGroupUuids) {
                        selectedGroupUuids - uuid
                    } else {
                        selectedGroupUuids + uuid
                    }
                },
                onDropGroup = { sources, target ->
                    scope.launch {
                        viewModel.moveNativeGroups(
                            databaseId = database.id,
                            groupUuids = sources.mapTo(linkedSetOf()) { it.groupUuid },
                            targetParentGroupUuid = target.groupUuid,
                            expectedRevisionToken = snapshot?.sourceRevision?.sha256.orEmpty(),
                        ).onSuccess {
                            selectedGroupUuids = emptySet()
                            reload()
                        }.onFailure { failure ->
                            error = failure.message ?: failure.javaClass.simpleName
                        }
                    }
                },
                onOpenEntry = { entry ->
                    scope.launch {
                        viewModel.resolveNativeEntryRoute(entry)
                            .onSuccess { route ->
                                if (route == KeePassNativeResolvedRoute.Generic) {
                                    selectedEntryIdentity = entry.identity
                                } else {
                                    onNavigateSpecialized(route)
                                }
                            }
                            .onFailure { selectedEntryIdentity = entry.identity }
                    }
                },
                onCreateGroup = { createGroupParent = it },
                onCreateEntry = { creatingEntryParent = it },
                onRenameGroup = { renameGroupIdentity = it.identity },
                onMoveGroup = { moveGroupIdentity = it.identity },
                onMoveGroups = { uuids -> moveGroupUuids = uuids },
                onDeleteGroup = { deleteGroupIdentity = it.identity },
                onEditGroupProperties = { groupPropertiesIdentity = it.identity },
                onToggleEntrySelection = { entry ->
                    selectedEntryUuids = toggleNativeManagerEntrySelection(
                        selected = selectedEntryUuids,
                        entryUuid = entry.identity.entryUuid,
                    )
                },
                onToggleSelectAll = { visibleEntryUuids ->
                    selectedEntryUuids = toggleNativeManagerSelectAll(
                        selected = selectedEntryUuids,
                        visible = visibleEntryUuids,
                    )
                },
                onClearEntrySelection = { selectedEntryUuids = emptySet() },
                onClearGroupSelection = { selectedGroupUuids = emptySet() },
                onDuplicateEntry = { duplicateEntryIdentity = it.identity },
                onSaveAsTemplate = { saveTemplateIdentity = it.identity },
                onInstantiateTemplate = { instantiateTemplateIdentity = it.identity },
                onDeleteTemplate = { deleteTemplateIdentity = it.identity },
                onMoveEntries = { uuids -> moveEntryUuids = uuids },
                onDeleteEntries = { uuids -> deleteEntryUuids = uuids }
            )
        }
    }

    if (showSearchOptions) {
        NativeSearchOptionsSheet(
            options = searchOptions,
            currentFolderOnly = searchCurrentFolderOnly,
            onOptionsChange = { searchOptions = it },
            onCurrentFolderOnlyChange = { searchCurrentFolderOnly = it },
            onDismiss = { showSearchOptions = false }
        )
    }

    createGroupParent?.let { parentIdentity ->
        NativeCreateGroupDialog(
            customIcons = snapshot?.customIcons.orEmpty(),
            customIconReferences = snapshot?.customIconReferences.orEmpty(),
            onDismiss = { createGroupParent = null },
            onConfirm = { update, onResult ->
                scope.launch {
                    val name = update.name.orEmpty()
                    viewModel.createNativeGroup(
                        database.id,
                        parentIdentity.groupUuid,
                        name,
                        snapshot?.sourceRevision?.sha256.orEmpty(),
                        update,
                    ).onSuccess {
                        createGroupParent = null
                        reload()
                        onResult(null)
                    }.onFailure { failure -> onResult(failure.message ?: failure.javaClass.simpleName) }
                }
            }
        )
    }

    renameGroupIdentity?.let { identity ->
        snapshot?.group(identity)?.let { group ->
            NativeGroupNameDialog(
                title = stringResource(R.string.keepass_native_rename_group),
                initialName = group.name,
                onDismiss = { renameGroupIdentity = null },
                onConfirm = { name, onResult ->
                    scope.launch {
                        viewModel.renameNativeGroup(
                            database.id,
                            identity.groupUuid,
                            name,
                            snapshot.sourceRevision.sha256
                        ).onSuccess {
                            renameGroupIdentity = null
                            reload()
                            onResult(null)
                        }.onFailure { failure -> onResult(failure.message ?: failure.javaClass.simpleName) }
                    }
                }
            )
        }
    }

    moveGroupIdentity?.let { identity ->
        snapshot?.group(identity)?.let { group ->
            NativeGroupMoveDialog(
                browser = snapshot,
                movingGroupUuids = setOf(group.identity.groupUuid),
                onDismiss = { moveGroupIdentity = null },
                onMove = { target, onResult ->
                    scope.launch {
                        viewModel.moveNativeGroup(
                            database.id,
                            identity.groupUuid,
                            target.identity.groupUuid,
                            snapshot.sourceRevision.sha256
                        ).onSuccess {
                            moveGroupIdentity = null
                            reload()
                            onResult(null)
                        }.onFailure { failure -> onResult(failure.message ?: failure.javaClass.simpleName) }
                    }
                }
            )
        }
    }

    moveGroupUuids?.let { uuids ->
        snapshot?.let { currentSnapshot ->
            NativeGroupMoveDialog(
                browser = currentSnapshot,
                movingGroupUuids = uuids,
                onDismiss = { moveGroupUuids = null },
                onMove = { target, onResult ->
                    scope.launch {
                        viewModel.moveNativeGroups(
                            databaseId = database.id,
                            groupUuids = uuids,
                            targetParentGroupUuid = target.identity.groupUuid,
                            expectedRevisionToken = currentSnapshot.sourceRevision.sha256,
                        ).onSuccess {
                            moveGroupUuids = null
                            selectedGroupUuids = emptySet()
                            reload()
                            onResult(null)
                        }.onFailure { failure ->
                            onResult(failure.message ?: failure.javaClass.simpleName)
                        }
                    }
                },
            )
        }
    }

    deleteGroupIdentity?.let { identity ->
        snapshot?.group(identity)?.let { group ->
            AlertDialog(
                onDismissRequest = { deleteGroupIdentity = null },
                icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                title = { Text(stringResource(R.string.keepass_native_delete_group)) },
                text = { Text(stringResource(R.string.keepass_native_delete_group_message, group.name)) },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                viewModel.deleteNativeGroup(
                                    database.id,
                                    identity.groupUuid,
                                    snapshot.sourceRevision.sha256
                                ).onSuccess {
                                    deleteGroupIdentity = null
                                    if (currentGroupIdentity == identity) currentGroupIdentity = group.parentGroup
                                    reload()
                                }.onFailure { failure -> error = failure.message ?: failure.javaClass.simpleName }
                            }
                        }
                    ) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteGroupIdentity = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }

    duplicateEntryIdentity?.let { identity ->
        val entry = snapshot?.entry(identity)
        if (entry != null) {
            AlertDialog(
                onDismissRequest = { duplicateEntryIdentity = null },
                icon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                title = { Text(stringResource(R.string.duplicate)) },
                text = { Text(stringResource(R.string.keepass_native_duplicate_entry_confirmation, entry.title)) },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            viewModel.duplicateNativeEntry(
                                database.id,
                                entry.identity.entryUuid,
                                entry.parentGroup.groupUuid,
                                snapshot.sourceRevision.sha256
                            ).onSuccess { copied ->
                                duplicateEntryIdentity = null
                                reload { selectedEntryIdentity = copied.identity }
                            }.onFailure { failure -> error = failure.message ?: failure.javaClass.simpleName }
                        }
                    }) { Text(stringResource(R.string.duplicate)) }
                },
                dismissButton = {
                    TextButton(onClick = { duplicateEntryIdentity = null }) { Text(stringResource(R.string.cancel)) }
                }
            )
        } else {
            duplicateEntryIdentity = null
        }
    }

    saveTemplateIdentity?.let { identity ->
        val entry = snapshot?.entry(identity)
        if (entry != null) {
            NativeGroupNameDialog(
                title = stringResource(R.string.keepass_native_save_as_template),
                initialName = entry.title,
                onDismiss = { saveTemplateIdentity = null },
                onConfirm = { title, onResult ->
                    scope.launch {
                        viewModel.saveNativeEntryAsTemplate(
                            databaseId = database.id,
                            entryUuid = identity.entryUuid,
                            titleOverride = title,
                            expectedRevisionToken = snapshot.sourceRevision.sha256,
                        ).onSuccess { template ->
                            saveTemplateIdentity = null
                            reload {
                                currentGroupIdentity = template.parentGroup
                                selectedEntryIdentity = template.identity
                            }
                            onResult(null)
                        }.onFailure { failure ->
                            onResult(failure.message ?: failure.javaClass.simpleName)
                        }
                    }
                },
            )
        } else {
            saveTemplateIdentity = null
        }
    }

    instantiateTemplateIdentity?.let { identity ->
        val template = snapshot?.entry(identity)
        if (template != null) {
            NativeTemplateTargetDialog(
                browser = snapshot,
                template = template,
                initialTarget = currentGroup,
                onDismiss = { instantiateTemplateIdentity = null },
                onCreate = { target, title, onResult ->
                    scope.launch {
                        viewModel.instantiateNativeTemplate(
                            databaseId = database.id,
                            templateEntryUuid = identity.entryUuid,
                            targetGroupUuid = target.identity.groupUuid,
                            titleOverride = title,
                            expectedRevisionToken = snapshot.sourceRevision.sha256,
                        ).onSuccess { created ->
                            instantiateTemplateIdentity = null
                            reload {
                                currentGroupIdentity = target.identity
                                selectedEntryIdentity = created.identity
                            }
                            onResult(null)
                        }.onFailure { failure ->
                            onResult(failure.message ?: failure.javaClass.simpleName)
                        }
                    }
                },
            )
        } else {
            instantiateTemplateIdentity = null
        }
    }

    deleteTemplateIdentity?.let { identity ->
        val template = snapshot?.entry(identity)
        if (template != null) {
            AlertDialog(
                onDismissRequest = { deleteTemplateIdentity = null },
                icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                title = { Text(stringResource(R.string.keepass_native_delete_template)) },
                text = {
                    Text(
                        stringResource(
                            R.string.keepass_native_delete_template_message,
                            template.title.ifBlank { stringResource(R.string.keepass_native_untitled_entry) },
                        )
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            viewModel.deleteNativeTemplate(
                                databaseId = database.id,
                                templateEntryUuid = identity.entryUuid,
                                expectedRevisionToken = snapshot.sourceRevision.sha256,
                            ).onSuccess {
                                deleteTemplateIdentity = null
                                selectedEntryIdentity = null
                                reload()
                            }.onFailure { failure ->
                                error = failure.message ?: failure.javaClass.simpleName
                            }
                        }
                    }) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTemplateIdentity = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        } else {
            deleteTemplateIdentity = null
        }
    }

    moveEntryUuids?.let { uuids ->
        if (snapshot != null) {
            NativeEntryMoveDialog(
                browser = snapshot,
                movingEntryUuids = uuids,
                onDismiss = { moveEntryUuids = null },
                onMove = { target, onResult ->
                    scope.launch {
                        viewModel.moveNativeEntries(
                            database.id,
                            uuids,
                            target.identity.groupUuid,
                            snapshot.sourceRevision.sha256
                        ).onSuccess {
                            moveEntryUuids = null
                            selectedEntryUuids = emptySet()
                            reload()
                            onResult(null)
                        }.onFailure { failure -> onResult(failure.message ?: failure.javaClass.simpleName) }
                    }
                }
            )
        }
    }

    deleteEntryUuids?.let { uuids ->
        if (snapshot != null) {
            val allInRecycleBin = uuids.all { uuid ->
                snapshot.entry(KeePassNativeEntryIdentity(database.id, uuid))?.isInRecycleBin == true
            }
            AlertDialog(
                onDismissRequest = { deleteEntryUuids = null },
                icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                title = { Text(stringResource(R.string.keepass_native_delete_entries, uuids.size)) },
                text = {
                    Text(
                        if (allInRecycleBin) stringResource(R.string.keepass_native_permanent_delete_summary)
                        else stringResource(R.string.keepass_native_delete_entries_summary)
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            viewModel.deleteNativeEntries(
                                database.id,
                                uuids,
                                if (allInRecycleBin) KeePassNativeDeleteMode.PERMANENT else KeePassNativeDeleteMode.RECYCLE_BIN,
                                snapshot.sourceRevision.sha256
                            ).onSuccess {
                                deleteEntryUuids = null
                                selectedEntryUuids = emptySet()
                                selectedEntryIdentity = null
                                reload()
                            }.onFailure { failure -> error = failure.message ?: failure.javaClass.simpleName }
                        }
                    }) {
                        Text(
                            if (allInRecycleBin) stringResource(R.string.delete_permanently)
                            else stringResource(R.string.move_to_recycle_bin)
                        )
                    }
                },
                dismissButton = {
                    Row {
                        if (!allInRecycleBin) {
                            TextButton(onClick = {
                                scope.launch {
                                    viewModel.deleteNativeEntries(
                                        database.id,
                                        uuids,
                                        KeePassNativeDeleteMode.PERMANENT,
                                        snapshot.sourceRevision.sha256
                                    ).onSuccess {
                                        deleteEntryUuids = null
                                        selectedEntryUuids = emptySet()
                                        selectedEntryIdentity = null
                                        reload()
                                    }.onFailure { failure -> error = failure.message ?: failure.javaClass.simpleName }
                                }
                            }) { Text(stringResource(R.string.delete_permanently)) }
                        }
                        TextButton(onClick = { deleteEntryUuids = null }) { Text(stringResource(R.string.cancel)) }
                    }
                }
            )
        }
    }

    groupPropertiesIdentity?.let { identity ->
        val group = snapshot?.group(identity)
        if (group != null) {
            NativeGroupPropertiesDialog(
                group = group,
                customIcons = snapshot.customIcons,
                customIconReferences = snapshot.customIconReferences,
                onDismiss = { groupPropertiesIdentity = null },
                onSave = { update, onResult ->
                    scope.launch {
                        viewModel.updateNativeGroupProperties(
                            database.id,
                            identity.groupUuid,
                            update,
                            snapshot.sourceRevision.sha256
                        ).onSuccess {
                            groupPropertiesIdentity = null
                            reload()
                            onResult(null)
                        }.onFailure { failure -> onResult(failure.message ?: failure.javaClass.simpleName) }
                    }
                }
            )
        }
    }
}

private enum class NativeManagerPage { BROWSER, DETAIL, EDITOR, SETTINGS, TOOLS }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NativeBrowserPage(
    database: LocalKeePassDatabase,
    browser: KeePassNativeBrowserSnapshot?,
    currentGroup: KeePassNativeGroupRecord?,
    loading: Boolean,
    error: String?,
    searchQuery: String,
    searchExpanded: Boolean,
    searchOptions: KeePassNativeSearchOptions,
    searchCurrentFolderOnly: Boolean,
    sortOptions: KeePassNativeSortOptions,
    selectedEntryUuids: Set<UUID>,
    selectedGroupUuids: Set<UUID>,
    readOnly: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onResolveConflict: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchExpandedChange: (Boolean) -> Unit,
    onOpenSearchOptions: () -> Unit,
    onSortOptionsChange: (KeePassNativeSortOptions) -> Unit,
    onOpenDatabaseSettings: () -> Unit,
    onOpenDatabaseTools: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenGroup: (KeePassNativeGroupRecord) -> Unit,
    onToggleGroupSelection: (KeePassNativeGroupRecord) -> Unit,
    onDropGroup: (Set<KeePassNativeGroupIdentity>, KeePassNativeGroupIdentity) -> Unit,
    onOpenEntry: (KeePassNativeEntryRecord) -> Unit,
    onCreateGroup: (KeePassNativeGroupIdentity) -> Unit,
    onCreateEntry: (KeePassNativeGroupIdentity) -> Unit,
    onRenameGroup: (KeePassNativeGroupRecord) -> Unit,
    onMoveGroup: (KeePassNativeGroupRecord) -> Unit,
    onMoveGroups: (Set<UUID>) -> Unit,
    onDeleteGroup: (KeePassNativeGroupRecord) -> Unit,
    onEditGroupProperties: (KeePassNativeGroupRecord) -> Unit,
    onToggleEntrySelection: (KeePassNativeEntryRecord) -> Unit,
    onToggleSelectAll: (Set<UUID>) -> Unit,
    onClearEntrySelection: () -> Unit,
    onClearGroupSelection: () -> Unit,
    onDuplicateEntry: (KeePassNativeEntryRecord) -> Unit,
    onSaveAsTemplate: (KeePassNativeEntryRecord) -> Unit,
    onInstantiateTemplate: (KeePassNativeEntryRecord) -> Unit,
    onDeleteTemplate: (KeePassNativeEntryRecord) -> Unit,
    onMoveEntries: (Set<UUID>) -> Unit,
    onDeleteEntries: (Set<UUID>) -> Unit
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var addMenuExpanded by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var dragSourceGroups by remember { mutableStateOf(emptySet<KeePassNativeGroupIdentity>()) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val effectiveOptions = searchOptions.copy(
        query = searchQuery,
        groupScope = if (searchCurrentFolderOnly) currentGroup?.identity else null
    )
    val searchResult = remember(browser, effectiveOptions) {
        browser?.let { KeePassNativeSearch.search(it, effectiveOptions) }
    }
    val isSearching = searchQuery.isNotBlank()
    val childGroups = remember(browser, currentGroup) {
        if (browser == null || currentGroup == null) emptyList()
        else browser.groups.filter { it.parentGroup == currentGroup.identity }
    }
    val entries = remember(browser, currentGroup) {
        if (browser == null || currentGroup == null) emptyList()
        else browser.entries.filter { it.parentGroup == currentGroup.identity }
    }
    val sortedChildren = remember(childGroups, entries, sortOptions) {
        KeePassNativeManagement.sortChildren(childGroups, entries, sortOptions)
    }
    val visibleEntries = if (isSearching) searchResult?.entries.orEmpty() else sortedChildren.entries
    val visibleEntryUuids = remember(visibleEntries) {
        visibleEntries.mapTo(linkedSetOf()) { entry -> entry.identity.entryUuid }
    }
    val listSummary = NativeManagerListSummary(
        folderCount = if (isSearching) 0 else sortedChildren.groups.size,
        entryCount = visibleEntries.size,
    )
    val selectionMode = selectedEntryUuids.isNotEmpty()
    val groupSelectionMode = selectedGroupUuids.isNotEmpty()
    val anySelectionMode = selectionMode || groupSelectionMode
    val allVisibleEntriesSelected = visibleEntryUuids.isNotEmpty() &&
        visibleEntryUuids.all { it in selectedEntryUuids }

    LaunchedEffect(searchExpanded) {
        if (searchExpanded) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (anySelectionMode) {
                        Text(
                            stringResource(
                                R.string.keepass_native_selected_count,
                                selectedEntryUuids.size + selectedGroupUuids.size,
                            )
                        )
                    } else {
                        Column {
                            Text(
                                currentGroup?.name ?: database.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                database.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = if (anySelectionMode) {
                        { onClearEntrySelection(); onClearGroupSelection() }
                    } else onBack) {
                        Icon(
                            if (anySelectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (anySelectionMode) stringResource(R.string.cancel) else stringResource(R.string.go_back)
                        )
                    }
                },
                actions = {
                    if (anySelectionMode) {
                        if (groupSelectionMode) {
                            IconButton(
                                onClick = { onMoveGroups(selectedGroupUuids) },
                                enabled = !readOnly,
                            ) {
                                Icon(Icons.Default.DriveFileMove, contentDescription = stringResource(R.string.move))
                            }
                        }
                        if (selectionMode) {
                        IconButton(
                            onClick = { onToggleSelectAll(visibleEntryUuids) },
                            enabled = visibleEntryUuids.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.SelectAll,
                                contentDescription = if (allVisibleEntriesSelected) {
                                    stringResource(R.string.clear)
                                } else {
                                    stringResource(R.string.select_all)
                                }
                            )
                        }
                            IconButton(
                                onClick = { onMoveEntries(selectedEntryUuids) },
                                enabled = !readOnly
                            ) {
                                Icon(Icons.Default.DriveFileMove, contentDescription = stringResource(R.string.move))
                            }
                            IconButton(
                                onClick = { onDeleteEntries(selectedEntryUuids) },
                                enabled = !readOnly
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                            }
                        }
                    } else {
                        IconButton(onClick = { onSearchExpandedChange(!searchExpanded) }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.search)
                            )
                        }
                        Box {
                            IconButton(onClick = { sortMenuExpanded = true }) {
                                Icon(Icons.Default.Sort, contentDescription = stringResource(R.string.keepass_native_sort))
                            }
                            DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                                KeePassNativeSortMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(nativeSortModeLabel(mode)) },
                                        leadingIcon = {
                                            if (sortOptions.mode == mode) Icon(Icons.Default.Check, contentDescription = null)
                                        },
                                        onClick = {
                                            onSortOptionsChange(sortOptions.copy(mode = mode))
                                            sortMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { moreMenuExpanded = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_options)
                                )
                            }
                            DropdownMenu(
                                expanded = moreMenuExpanded,
                                onDismissRequest = { moreMenuExpanded = false }
                            ) {
                                if (browser?.templateGroupIdentity != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.keepass_native_manage_templates)) },
                                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                                        onClick = {
                                            moreMenuExpanded = false
                                            onOpenTemplates()
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.keepass_database_settings_title)) },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    onClick = {
                                        moreMenuExpanded = false
                                        onOpenDatabaseSettings()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.keepass_database_tools_title)) },
                                    leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                                    onClick = {
                                        moreMenuExpanded = false
                                        onOpenDatabaseTools()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.refresh)) },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                    onClick = {
                                        moreMenuExpanded = false
                                        onRetry()
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            if (!selectionMode && !readOnly) currentGroup?.let { group ->
                Box {
                    FloatingActionButton(
                        onClick = { addMenuExpanded = true },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add))
                    }
                    DropdownMenu(expanded = addMenuExpanded, onDismissRequest = { addMenuExpanded = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (group.identity == browser?.templateGroupIdentity) {
                                            R.string.keepass_native_create_template
                                        } else {
                                            R.string.keepass_native_create_entry
                                        }
                                    )
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (group.identity == browser?.templateGroupIdentity) Icons.Default.AutoAwesome
                                    else Icons.Default.Add,
                                    contentDescription = null,
                                )
                            },
                            onClick = { addMenuExpanded = false; onCreateEntry(group.identity) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.keepass_native_create_group)) },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                            onClick = { addMenuExpanded = false; onCreateGroup(group.identity) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val horizontalPadding = if (maxWidth >= 840.dp) 32.dp else 12.dp
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 960.dp)
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding)
            ) {
                AnimatedVisibility(
                    visible = shouldShowNativeManagerSearch(searchExpanded, searchQuery),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocusRequester)
                            .padding(top = 8.dp, bottom = 4.dp),
                        placeholder = { Text(stringResource(R.string.keepass_native_search_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onOpenSearchOptions) {
                                    Icon(
                                        Icons.Default.Tune,
                                        contentDescription = stringResource(R.string.keepass_native_search_options)
                                    )
                                }
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { onSearchQueryChange("") }) {
                                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear))
                                    }
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { keyboardController?.hide() }
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp)
                    )
                }

                if (browser != null && currentGroup != null && !isSearching) {
                    NativeBreadcrumbs(browser, currentGroup, onOpenGroup)
                    if (dragSourceGroups.isNotEmpty()) {
                        NativeFolderDragModeBanner(
                            source = if (dragSourceGroups.size == 1) {
                                browser.group(dragSourceGroups.single())?.name.orEmpty()
                            } else {
                                stringResource(R.string.keepass_native_folder_drag_count, dragSourceGroups.size)
                            },
                            onCancel = { dragSourceGroups = emptySet() },
                        )
                    }
                    NativeManagerSummaryRow(
                        summary = listSummary,
                        readOnly = readOnly,
                    )
                }
                if (database.isRemoteSource() && database.lastSyncStatus == KeePassSyncStatus.CONFLICT) {
                    KeePassConflictBanner(onReview = onResolveConflict)
                }
                if (error != null && browser != null) {
                    NativeManagerErrorRow(
                        text = error,
                        onRetry = onRetry,
                    )
                }
                if (loading && browser != null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when {
                    loading && browser == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    error != null && browser == null -> NativeErrorState(error, onRetry, Modifier.align(Alignment.Center))
                    browser == null || currentGroup == null -> Unit
                    isSearching -> {
                        val result = searchResult
                        LazyColumn(
                            contentPadding = PaddingValues(0.dp, 8.dp, 0.dp, 104.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            result?.error?.let { message ->
                                item {
                                    Surface(
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text(
                                            message,
                                            modifier = Modifier.padding(16.dp),
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }
                            if (result?.entries.isNullOrEmpty()) {
                                item { NativeEmptyState(stringResource(R.string.no_results)) }
                            } else {
                                itemsIndexed(
                                    items = result?.entries.orEmpty(),
                                    key = { _, entry -> "${entry.identity.entryUuid}:${entry.occurrenceIndex}" }
                                ) { index, entry ->
                                    NativeEntryCard(
                                        shape = settingsSectionItemShape(index, result?.entries.orEmpty().size),
                                        entry = entry,
                                        showGroupPath = true,
                                        selected = entry.identity.entryUuid in selectedEntryUuids,
                                        selectionMode = selectionMode,
                                        readOnly = readOnly,
                                        onClick = { if (selectionMode) onToggleEntrySelection(entry) else onOpenEntry(entry) },
                                        onLongClick = { if (!readOnly) onToggleEntrySelection(entry) },
                                        onDuplicate = { onDuplicateEntry(entry) },
                                        onSaveAsTemplate = { onSaveAsTemplate(entry) },
                                        onInstantiateTemplate = { onInstantiateTemplate(entry) },
                                        onMove = { onMoveEntries(setOf(entry.identity.entryUuid)) },
                                        onDelete = {
                                            if (entry.kind == KeePassNativeEntryKind.TEMPLATE) onDeleteTemplate(entry)
                                            else onDeleteEntries(setOf(entry.identity.entryUuid))
                                        }
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(0.dp, 8.dp, 0.dp, 104.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (sortedChildren.groups.isNotEmpty()) {
                                item { NativeSectionLabel(stringResource(R.string.keepass_native_groups)) }
                                itemsIndexed(
                                    items = sortedChildren.groups,
                                    key = { _, group -> "${group.identity.groupUuid}:${group.occurrenceIndex}" }
                                ) { index, group ->
                                    NativeGroupCard(
                                        shape = settingsSectionItemShape(index, sortedChildren.groups.size),
                                        group = group,
                                        readOnly = readOnly,
                                        selected = group.identity.groupUuid in selectedGroupUuids,
                                        dragActive = dragSourceGroups.isNotEmpty(),
                                        dropTarget = KeePassFolderDragPolicy.canBatchDrop(
                                            dragSourceGroups,
                                            group.identity,
                                            browser,
                                        ),
                                        onOpen = {
                                            if (dragSourceGroups.isNotEmpty()) {
                                                if (KeePassFolderDragPolicy.canBatchDrop(
                                                        dragSourceGroups,
                                                        group.identity,
                                                        browser,
                                                    )) {
                                                    val sources = dragSourceGroups
                                                    dragSourceGroups = emptySet()
                                                    onDropGroup(sources, group.identity)
                                                }
                                            } else if (groupSelectionMode) {
                                                onToggleGroupSelection(group)
                                            } else {
                                                onOpenGroup(group)
                                            }
                                        },
                                        onStartDrag = {
                                            if (!readOnly) {
                                                val selectedSources = selectedGroupUuids.mapTo(linkedSetOf()) { uuid ->
                                                    KeePassNativeGroupIdentity(browser.databaseId, uuid)
                                                }
                                                dragSourceGroups = if (group.identity.groupUuid in selectedGroupUuids && selectedSources.isNotEmpty()) {
                                                    selectedSources
                                                } else {
                                                    setOf(group.identity)
                                                }
                                            }
                                        },
                                        onToggleSelection = { onToggleGroupSelection(group) },
                                        onCreateChild = { onCreateGroup(group.identity) },
                                        onRename = { onRenameGroup(group) },
                                        onMove = { onMoveGroup(group) },
                                        onDelete = { onDeleteGroup(group) },
                                        onProperties = { onEditGroupProperties(group) }
                                    )
                                }
                            }
                            if (sortedChildren.entries.isNotEmpty()) {
                                item { NativeSectionLabel(stringResource(R.string.keepass_native_entries)) }
                                itemsIndexed(
                                    items = sortedChildren.entries,
                                    key = { _, entry -> "${entry.identity.entryUuid}:${entry.occurrenceIndex}" }
                                ) { index, entry ->
                                    NativeEntryCard(
                                        shape = settingsSectionItemShape(index, sortedChildren.entries.size),
                                        entry = entry,
                                        showGroupPath = false,
                                        selected = entry.identity.entryUuid in selectedEntryUuids,
                                        selectionMode = selectionMode,
                                        readOnly = readOnly,
                                        onClick = { if (selectionMode) onToggleEntrySelection(entry) else onOpenEntry(entry) },
                                        onLongClick = { if (!readOnly) onToggleEntrySelection(entry) },
                                        onDuplicate = { onDuplicateEntry(entry) },
                                        onSaveAsTemplate = { onSaveAsTemplate(entry) },
                                        onInstantiateTemplate = { onInstantiateTemplate(entry) },
                                        onMove = { onMoveEntries(setOf(entry.identity.entryUuid)) },
                                        onDelete = {
                                            if (entry.kind == KeePassNativeEntryKind.TEMPLATE) onDeleteTemplate(entry)
                                            else onDeleteEntries(setOf(entry.identity.entryUuid))
                                        }
                                    )
                                }
                            }
                            if (sortedChildren.groups.isEmpty() && sortedChildren.entries.isEmpty()) {
                                item { NativeEmptyState(stringResource(R.string.keepass_native_empty_group)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NativeBreadcrumbs(
    browser: KeePassNativeBrowserSnapshot,
    currentGroup: KeePassNativeGroupRecord,
    onOpenGroup: (KeePassNativeGroupRecord) -> Unit
) {
    val chain = remember(browser, currentGroup) {
        buildList {
            val reversed = mutableListOf<KeePassNativeGroupRecord>()
            val visited = mutableSetOf<KeePassNativeGroupIdentity>()
            var cursor: KeePassNativeGroupRecord? = currentGroup
            while (cursor != null && visited.add(cursor.identity)) {
                reversed += cursor
                cursor = cursor.parentGroup?.let(browser::group)
            }
            addAll(reversed.asReversed())
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chain.forEachIndexed { index, group ->
            TextButton(
                onClick = { onOpenGroup(group) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                if (index == 0) {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    group.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp)
                )
            }
            if (index != chain.lastIndex) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NativeManagerSummaryRow(
    summary: NativeManagerListSummary,
    readOnly: Boolean,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(
                    R.string.keepass_native_group_counts,
                    summary.folderCount,
                    summary.entryCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (readOnly) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(R.string.keepass_database_read_only_short)) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun NativeManagerErrorRow(
    text: String,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.refresh))
            }
        }
    }
}

@Composable
private fun NativeFolderDragModeBanner(
    source: String,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
                            Text(
                                text = source,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
internal fun NativeGroupCard(
    shape: Shape = DatabaseManagementPanelShape,
    group: KeePassNativeGroupRecord,
    readOnly: Boolean,
    selected: Boolean,
    dragActive: Boolean,
    dropTarget: Boolean,
    onOpen: () -> Unit,
    onStartDrag: () -> Unit,
    onToggleSelection: () -> Unit,
    onCreateChild: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit
) {
    var menuExpanded by remember(group.identity, group.occurrenceIndex) { mutableStateOf(false) }
    Surface(
        shape = shape,
        modifier = Modifier.fillMaxWidth().clip(shape).combinedClickable(
            interactionSource = null, indication = ripple(color = MaterialTheme.colorScheme.primary),
            onClick = onOpen,
            onLongClick = onStartDrag,
        ),
        color = if (group.isInRecycleBin) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f)
        } else if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else if (dropTarget) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        ListItem(
            headlineContent = {
                Text(
                    group.name,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    stringResource(
                        R.string.keepass_native_group_counts,
                        group.childGroups.size,
                        group.childEntries.size
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = { Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Checkbox(checked = true, onCheckedChange = { onToggleSelection() })
                    }
                    if (dragActive && dropTarget) {
                        Text(
                            stringResource(R.string.keepass_native_folder_drag_drop),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.keepass_native_create_subgroup)) },
                                leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                                enabled = !readOnly,
                                onClick = { menuExpanded = false; onCreateChild() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.keepass_native_rename_group)) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                enabled = !readOnly,
                                onClick = { menuExpanded = false; onRename() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.select)) },
                                leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
                                enabled = !readOnly,
                                onClick = { menuExpanded = false; onToggleSelection() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.move)) },
                                leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                                enabled = !readOnly,
                                onClick = { menuExpanded = false; onMove() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.keepass_native_group_properties)) },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                enabled = !readOnly,
                                onClick = { menuExpanded = false; onProperties() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                enabled = !readOnly,
                                onClick = { menuExpanded = false; onDelete() }
                            )
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
internal fun NativeEntryCard(
    shape: Shape = DatabaseManagementPanelShape,
    entry: KeePassNativeEntryRecord,
    showGroupPath: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    readOnly: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDuplicate: () -> Unit,
    onSaveAsTemplate: () -> Unit,
    onInstantiateTemplate: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember(entry.identity, entry.occurrenceIndex) { mutableStateOf(false) }
    val account = entry.field("UserName")?.displayValue.orEmpty()
    val url = entry.field("URL")?.displayValue.orEmpty()
    val subtitle = listOfNotNull(
        nativeKindLabel(entry.kind),
        account.takeIf { it.isNotBlank() },
        url.takeIf { it.isNotBlank() },
        entry.legacyGroupPath?.takeIf { showGroupPath && it.isNotBlank() }
    ).joinToString(" · ")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape).combinedClickable(interactionSource = null, indication = ripple(color = MaterialTheme.colorScheme.primary), onClick = onClick, onLongClick = onLongClick),
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else if (entry.isInRecycleBin) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        ListItem(
            headlineContent = {
                Text(
                    entry.title.ifBlank { stringResource(R.string.keepass_native_untitled_entry) },
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            leadingContent = { Icon(nativeKindIcon(entry.kind), contentDescription = null, tint = nativeKindColor(entry.kind)) },
            trailingContent = {
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() })
                } else if (readOnly) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            if (entry.kind == KeePassNativeEntryKind.TEMPLATE) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.keepass_native_use_template)) },
                                    leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                                    enabled = !readOnly,
                                    onClick = { menuExpanded = false; onInstantiateTemplate() }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.keepass_native_save_as_template)) },
                                    leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                                    enabled = !readOnly,
                                    onClick = { menuExpanded = false; onSaveAsTemplate() }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.duplicate)) },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                enabled = !readOnly,
                                onClick = { menuExpanded = false; onDuplicate() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.move)) },
                                leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                                enabled = !readOnly,
                                onClick = { menuExpanded = false; onMove() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                enabled = !readOnly,
                                onClick = { menuExpanded = false; onDelete() }
                            )
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegacyNativeEntryDetailScreen(
    entry: KeePassNativeEntryRecord,
    modificationEnabled: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onAddAttachment: (Uri, (String?) -> Unit) -> Unit,
    onRenameAttachment: (KeePassNativeAttachmentRecord, String, (String?) -> Unit) -> Unit,
    onExportAttachment: (KeePassNativeAttachmentRecord, Uri, (String?) -> Unit) -> Unit,
    onDeleteAttachment: (KeePassNativeAttachmentRecord, (String?) -> Unit) -> Unit,
    onRestoreHistory: (Int, (String?) -> Unit) -> Unit,
    onDeleteHistory: (Int, (String?) -> Unit) -> Unit
) {
    var revealedFields by remember(entry.identity) { mutableStateOf(emptySet<Int>()) }
    var showMetadata by remember(entry.identity) { mutableStateOf(false) }
    var showHistory by remember(entry.identity) { mutableStateOf(false) }
    var attachmentBusyKey by remember(entry.identity) { mutableStateOf<String?>(null) }
    var attachmentMessage by remember(entry.identity) { mutableStateOf<String?>(null) }
    var attachmentError by remember(entry.identity) { mutableStateOf<String?>(null) }
    var exportAttachmentTarget by remember(entry.identity) { mutableStateOf<KeePassNativeAttachmentRecord?>(null) }
    var renameAttachmentTarget by remember(entry.identity) { mutableStateOf<KeePassNativeAttachmentRecord?>(null) }
    var renameAttachmentName by remember(entry.identity) { mutableStateOf("") }
    var deleteAttachmentTarget by remember(entry.identity) { mutableStateOf<KeePassNativeAttachmentRecord?>(null) }

    val addAttachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        attachmentBusyKey = "add"
        attachmentError = null
        onAddAttachment(uri) { failure ->
            attachmentBusyKey = null
            if (failure == null) attachmentMessage = "added" else attachmentError = failure
        }
    }
    val exportAttachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val attachment = exportAttachmentTarget
        exportAttachmentTarget = null
        if (uri == null || attachment == null) return@rememberLauncherForActivityResult
        attachmentBusyKey = attachmentKey("export", attachment)
        attachmentError = null
        onExportAttachment(attachment, uri) { failure ->
            attachmentBusyKey = null
            if (failure == null) attachmentMessage = "exported" else attachmentError = failure
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.keepass_native_entry_details)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                    }
                },
                actions = {
                    if (entry.history.isNotEmpty()) {
                        IconButton(onClick = { showHistory = true }) {
                            Icon(Icons.Default.History, contentDescription = stringResource(R.string.history))
                        }
                    }
                    IconButton(onClick = onEdit, enabled = modificationEnabled) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = nativeKindColor(entry.kind).copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = nativeKindColor(entry.kind).copy(alpha = 0.18f),
                            modifier = Modifier.size(58.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    nativeKindIcon(entry.kind),
                                    contentDescription = null,
                                    tint = nativeKindColor(entry.kind),
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.title.ifBlank { stringResource(R.string.keepass_native_untitled_entry) },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(nativeKindLabel(entry.kind), color = nativeKindColor(entry.kind))
                            entry.legacyGroupPath?.takeIf { it.isNotBlank() }?.let { path ->
                                Text(path, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            item { NativeSectionLabel(stringResource(R.string.keepass_native_fields)) }
            itemsIndexed(
                items = entry.fields,
                key = { index, field -> "$index:${field.name}" }
            ) { index, field ->
                NativeFieldCard(
                    field = field,
                    revealed = index in revealedFields,
                    onRevealChange = { reveal ->
                        revealedFields = if (reveal) revealedFields + index else revealedFields - index
                    }
                )
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NativeSectionLabel(stringResource(R.string.attachments))
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { addAttachmentLauncher.launch(arrayOf("*/*")) },
                        enabled = modificationEnabled && attachmentBusyKey == null
                    ) {
                        if (attachmentBusyKey == "add") {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.attachment_add))
                    }
                }
            }
            attachmentMessage?.let { message ->
                item {
                    NativeInlineMessage(
                        text = when (message) {
                            "added" -> stringResource(R.string.keepass_native_attachment_added)
                            "exported" -> stringResource(R.string.keepass_native_attachment_exported)
                            "renamed" -> stringResource(R.string.keepass_native_attachment_renamed)
                            "deleted" -> stringResource(R.string.keepass_native_attachment_deleted)
                            else -> message
                        },
                        error = false,
                        onDismiss = { attachmentMessage = null }
                    )
                }
            }
            attachmentError?.let { failure ->
                item {
                    NativeInlineMessage(
                        text = failure,
                        error = true,
                        onDismiss = { attachmentError = null }
                    )
                }
            }
            if (entry.attachments.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.keepass_native_no_attachments),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                items(entry.attachments, key = { attachment -> "${attachment.hash}:${attachment.name}" }) { attachment ->
                    NativeAttachmentCard(
                        attachment = attachment,
                        busy = attachmentBusyKey == attachmentKey("export", attachment) ||
                            attachmentBusyKey == attachmentKey("rename", attachment) ||
                            attachmentBusyKey == attachmentKey("delete", attachment),
                        modificationEnabled = modificationEnabled && attachmentBusyKey == null,
                        onExport = {
                            exportAttachmentTarget = attachment
                            exportAttachmentLauncher.launch(attachment.name)
                        },
                        onRename = {
                            renameAttachmentTarget = attachment
                            renameAttachmentName = attachment.name
                        },
                        onDelete = { deleteAttachmentTarget = attachment }
                    )
                }
            }

            item {
                OutlinedButton(
                    onClick = { showMetadata = !showMetadata },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.keepass_native_metadata))
                }
            }

            if (showMetadata) {
                item { NativeMetadataCard(entry) }
            }
        }
    }

    if (showHistory) {
        NativeHistorySheet(
            entry = entry,
            onDismiss = { showHistory = false },
            onRestore = onRestoreHistory,
            onDelete = onDeleteHistory
        )
    }

    renameAttachmentTarget?.let { attachment ->
        AlertDialog(
            onDismissRequest = { renameAttachmentTarget = null },
            title = { Text(stringResource(R.string.keepass_native_attachment_rename)) },
            text = {
                OutlinedTextField(
                    shape = DatabaseManagementFieldShape,
                    value = renameAttachmentName,
                    onValueChange = { renameAttachmentName = it },
                    label = { Text(stringResource(R.string.keepass_native_attachment_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        attachmentBusyKey = attachmentKey("rename", attachment)
                        attachmentError = null
                        onRenameAttachment(attachment, renameAttachmentName) { failure ->
                            attachmentBusyKey = null
                            if (failure == null) {
                                renameAttachmentTarget = null
                                attachmentMessage = "renamed"
                            } else {
                                attachmentError = failure
                            }
                        }
                    },
                    enabled = renameAttachmentName.isNotBlank() && attachmentBusyKey == null
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameAttachmentTarget = null }, enabled = attachmentBusyKey == null) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    deleteAttachmentTarget?.let { attachment ->
        AlertDialog(
            onDismissRequest = { deleteAttachmentTarget = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.attachment_delete)) },
            text = { Text(stringResource(R.string.keepass_native_attachment_delete_confirmation, attachment.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        attachmentBusyKey = attachmentKey("delete", attachment)
                        attachmentError = null
                        onDeleteAttachment(attachment) { failure ->
                            attachmentBusyKey = null
                            if (failure == null) {
                                deleteAttachmentTarget = null
                                attachmentMessage = "deleted"
                            } else {
                                attachmentError = failure
                            }
                        }
                    },
                    enabled = attachmentBusyKey == null
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteAttachmentTarget = null }, enabled = attachmentBusyKey == null) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun NativeAttachmentCard(
    attachment: KeePassNativeAttachmentRecord,
    busy: Boolean,
    modificationEnabled: Boolean,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember(attachment.hash, attachment.name) { mutableStateOf(false) }
    OutlinedCard(shape = RoundedCornerShape(16.dp)) {
        ListItem(
            headlineContent = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    if (attachment.isMissing) {
                        stringResource(R.string.keepass_native_attachment_missing)
                    } else {
                        stringResource(
                            R.string.keepass_native_attachment_size,
                            attachment.binary?.rawContent?.size ?: 0
                        )
                    }
                )
            },
            leadingContent = { Icon(Icons.Outlined.DataObject, contentDescription = null) },
            trailingContent = {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attachment_save_to_device)) },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                enabled = !attachment.isMissing,
                                onClick = { menuExpanded = false; onExport() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.keepass_native_attachment_rename)) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                enabled = modificationEnabled,
                                onClick = { menuExpanded = false; onRename() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attachment_delete)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                enabled = modificationEnabled,
                                onClick = { menuExpanded = false; onDelete() }
                            )
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
private fun NativeInlineMessage(text: String, error: Boolean, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text,
                modifier = Modifier.weight(1f),
                color = if (error) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun attachmentKey(action: String, attachment: KeePassNativeAttachmentRecord): String =
    "$action:${attachment.hash}:${attachment.name}"

@Composable
private fun NativeFieldCard(
    field: KeePassNativeFieldRecord,
    revealed: Boolean,
    onRevealChange: (Boolean) -> Unit
) {
    OutlinedCard(shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    field.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (field.isProtected) {
                    IconButton(onClick = { onRevealChange(!revealed) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
            SelectionContainer {
                Text(
                    if (field.isProtected && !revealed) "••••••••" else field.displayValue,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            if ((!field.isProtected || revealed) && field.rawValue != field.displayValue) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.keepass_native_raw_value, field.rawValue),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NativeMetadataCard(entry: KeePassNativeEntryRecord) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    OutlinedCard(shape = RoundedCornerShape(18.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            NativeMetadataRow("UUID", entry.identity.entryUuid.toString())
            NativeMetadataRow(
                stringResource(R.string.keepass_native_created),
                entry.times?.creationTime?.toEpochMilli()?.let { dateFormat.format(Date(it)) }.orEmpty()
            )
            NativeMetadataRow(
                stringResource(R.string.keepass_native_modified),
                entry.times?.lastModificationTime?.toEpochMilli()?.let { dateFormat.format(Date(it)) }.orEmpty()
            )
            NativeMetadataRow(stringResource(R.string.keepass_native_tags), entry.tags.joinToString(", "))
            NativeMetadataRow(
                stringResource(R.string.keepass_native_auto_type),
                if (entry.autoType?.enabled == false) stringResource(R.string.disabled) else stringResource(R.string.enabled)
            )
            NativeMetadataRow(stringResource(R.string.keepass_native_history_count), entry.history.size.toString())
            if (entry.customData.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    stringResource(R.string.keepass_native_custom_data),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                entry.customData.forEach { (key, value) -> NativeMetadataRow(key, value.value) }
            }
        }
    }
}

@Composable
private fun NativeMetadataRow(label: String, value: String) {
    if (value.isBlank()) return
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

private data class EditableNativeField(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val value: String,
    val protected: Boolean,
    val revealed: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegacyNativeEntryEditorScreen(
    entry: KeePassNativeEntryRecord,
    revisionToken: String,
    savingEnabled: Boolean,
    onBack: () -> Unit,
    onSave: (List<KeePassFieldChange>, (String?) -> Unit) -> Unit
) {
    val fields: SnapshotStateList<EditableNativeField> = remember(entry.identity, revisionToken) {
        mutableStateListOf<EditableNativeField>().also { list ->
            list.addAll(entry.fields.map { field ->
                EditableNativeField(
                    name = field.name,
                    value = field.rawValue,
                    protected = field.isProtected
                )
            })
        }
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun validate(): String? {
        if (fields.any { it.name.trim().isBlank() }) return "Field names cannot be empty"
        val normalized = fields.map { it.name.trim().lowercase(Locale.ROOT) }
        if (normalized.distinct().size != normalized.size) return "Field names must be unique"
        return null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.keepass_native_edit_entry)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            error = validate()
                            if (error == null) {
                                saving = true
                                onSave(
                                    fields.map { field ->
                                        KeePassFieldChange(
                                            name = field.name.trim(),
                                            value = field.value,
                                            protected = field.protected
                                        )
                                    }
                                ) { failure ->
                                    error = failure
                                    saving = false
                                }
                            }
                        },
                        enabled = savingEnabled && !saving
                    ) {
                        if (saving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (!saving) fields += EditableNativeField(name = "", value = "", protected = false)
                }
            ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.keepass_native_add_field)) }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        stringResource(R.string.keepass_native_editor_hint),
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            error?.let { message ->
                item {
                    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
            itemsIndexed(fields, key = { _, field -> field.id }) { index, field ->
                OutlinedCard(shape = RoundedCornerShape(18.dp)) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            shape = DatabaseManagementFieldShape,
                            value = field.name,
                            onValueChange = { fields[index] = field.copy(name = it) },
                            label = { Text(stringResource(R.string.keepass_native_field_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            shape = DatabaseManagementFieldShape,
                            value = field.value,
                            onValueChange = { fields[index] = field.copy(value = it) },
                            label = { Text(stringResource(R.string.keepass_native_field_value)) },
                            visualTransformation = if (field.protected && !field.revealed) {
                                PasswordVisualTransformation()
                            } else {
                                VisualTransformation.None
                            },
                            trailingIcon = if (field.protected) {
                                {
                                    IconButton(onClick = { fields[index] = field.copy(revealed = !field.revealed) }) {
                                        Icon(
                                            if (field.revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = null
                                        )
                                    }
                                }
                            } else null,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = field.protected,
                                onCheckedChange = { fields[index] = field.copy(protected = it) }
                            )
                            Text(stringResource(R.string.keepass_native_protected_field), modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    if (index > 0) {
                                        val previous = fields[index - 1]
                                        fields[index - 1] = field
                                        fields[index] = previous
                                    }
                                },
                                enabled = index > 0
                            ) { Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.move_up)) }
                            IconButton(
                                onClick = {
                                    if (index < fields.lastIndex) {
                                        val next = fields[index + 1]
                                        fields[index + 1] = field
                                        fields[index] = next
                                    }
                                },
                                enabled = index < fields.lastIndex
                            ) { Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.move_down)) }
                            IconButton(onClick = { fields.removeAt(index) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeHistorySheet(
    entry: KeePassNativeEntryRecord,
    onDismiss: () -> Unit,
    onRestore: (Int, (String?) -> Unit) -> Unit,
    onDelete: (Int, (String?) -> Unit) -> Unit
) {
    var restoreTarget by remember { mutableStateOf<KeePassNativeHistoryVersion?>(null) }
    var deleteTarget by remember { mutableStateOf<KeePassNativeHistoryVersion?>(null) }
    var operationError by remember { mutableStateOf<String?>(null) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(stringResource(R.string.keepass_native_history_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.keepass_native_history_subtitle, entry.history.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier.height(420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(entry.history, key = { version -> "${version.index}:${version.uuid}" }) { version ->
                    OutlinedCard(shape = RoundedCornerShape(16.dp)) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                version.title.ifBlank { stringResource(R.string.keepass_native_untitled_entry) },
                                fontWeight = FontWeight.SemiBold
                            )
                            version.times?.lastModificationTime?.toEpochMilli()?.let { millis ->
                                Text(dateFormat.format(Date(millis)), style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                stringResource(R.string.keepass_native_history_field_count, version.fields.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { deleteTarget = version }) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.delete))
                                }
                                TextButton(onClick = { restoreTarget = version }) {
                                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.keepass_native_restore_version))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    restoreTarget?.let { version ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text(stringResource(R.string.keepass_native_restore_version)) },
            text = { Text(stringResource(R.string.keepass_native_restore_version_message)) },
            confirmButton = {
                Button(onClick = {
                    onRestore(version.index) { error ->
                        operationError = error
                        if (error == null) restoreTarget = null
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    deleteTarget?.let { version ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.keepass_native_delete_history)) },
            text = { Text(stringResource(R.string.keepass_native_delete_history_message)) },
            confirmButton = {
                Button(onClick = {
                    onDelete(version.index) { error ->
                        operationError = error
                        if (error == null) deleteTarget = null
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NativeSearchOptionsSheet(
    options: KeePassNativeSearchOptions,
    currentFolderOnly: Boolean,
    onOptionsChange: (KeePassNativeSearchOptions) -> Unit,
    onCurrentFolderOnlyChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.keepass_native_search_options), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.keepass_native_search_fields), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeePassNativeSearchField.entries.forEach { field ->
                    FilterChip(
                        selected = field in options.fields,
                        onClick = {
                            val fields = if (field in options.fields) options.fields - field else options.fields + field
                            onOptionsChange(options.copy(fields = fields))
                        },
                        label = { Text(nativeSearchFieldLabel(field)) }
                    )
                }
            }
            NativeSearchSwitch(
                stringResource(R.string.keepass_native_current_group_only),
                currentFolderOnly,
                onCurrentFolderOnlyChange
            )
            NativeSearchSwitch(
                stringResource(R.string.keepass_native_case_sensitive),
                options.caseSensitive
            ) { onOptionsChange(options.copy(caseSensitive = it)) }
            NativeSearchSwitch(
                stringResource(R.string.keepass_native_regex),
                options.useRegex
            ) { onOptionsChange(options.copy(useRegex = it)) }
            NativeSearchSwitch(
                stringResource(R.string.keepass_native_search_protected),
                options.searchProtectedValues
            ) { onOptionsChange(options.copy(searchProtectedValues = it)) }
            NativeSearchSwitch(
                stringResource(R.string.keepass_native_include_expired),
                options.includeExpired
            ) { onOptionsChange(options.copy(includeExpired = it)) }
            NativeSearchSwitch(
                stringResource(R.string.keepass_native_include_recycle_bin),
                options.includeRecycleBin
            ) { onOptionsChange(options.copy(includeRecycleBin = it)) }
            NativeSearchSwitch(
                stringResource(R.string.keepass_native_include_templates),
                options.includeTemplates
            ) { onOptionsChange(options.copy(includeTemplates = it)) }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.confirm))
            }
        }
    }
}

@Composable
private fun NativeSearchSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NativeCreateGroupDialog(
    customIcons: Map<UUID, app.keemobile.kotpass.models.CustomIcon>,
    customIconReferences: Map<UUID, Int>,
    onDismiss: () -> Unit,
    onConfirm: (KeePassNativeGroupUpdate, (String?) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var tagsText by remember { mutableStateOf("") }
    var expires by remember { mutableStateOf(false) }
    var expiryTime by remember { mutableStateOf(Instant.now().plus(30, ChronoUnit.DAYS)) }
    var searching by remember { mutableStateOf(GroupOverride.Inherit) }
    var autoType by remember { mutableStateOf(GroupOverride.Inherit) }
    var defaultSequence by remember { mutableStateOf("") }
    var selectedPredefinedIcon by remember {
        mutableStateOf(
            PredefinedIcon.values().firstOrNull { icon -> icon.name.equals("Folder", ignoreCase = true) }
                ?: PredefinedIcon.values().first(),
        )
    }
    var selectedCustomIconUuid by remember { mutableStateOf<UUID?>(null) }
    var pendingCustomIcon by remember { mutableStateOf<KeePassNativeCustomIconPayload?>(null) }
    var showCustomIconPicker by remember { mutableStateOf(false) }
    var showPredefinedIconPicker by remember { mutableStateOf(false) }
    var searchMenuExpanded by remember { mutableStateOf(false) }
    var autoTypeMenuExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val iconReadFailedMessage = stringResource(R.string.keepass_native_custom_icon_read_failed)
    val iconInvalidMessage = stringResource(R.string.keepass_native_custom_icon_invalid)
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to read selected image")
        }.getOrNull()
        when {
            bytes == null -> error = iconReadFailedMessage
            KeePassCustomIconEditor.validateImageBytes(bytes).isFailure -> error = iconInvalidMessage
            else -> {
                pendingCustomIcon = KeePassNativeCustomIconPayload(
                    bytes = bytes,
                    name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                        ?.takeIf(String::isNotBlank) ?: name.ifBlank { "Folder" },
                )
                selectedCustomIconUuid = null
            }
        }
    }

    fun openDatePicker() {
        val local = expiryTime.atZone(ZoneId.systemDefault())
        DatePickerDialog(
            context,
            { _, year, month, day ->
                expiryTime = LocalDate.of(year, month + 1, day)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
            },
            local.year,
            local.monthValue - 1,
            local.dayOfMonth,
        ).show()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.keepass_native_create_group)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    shape = DatabaseManagementFieldShape,
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text(stringResource(R.string.folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    shape = DatabaseManagementFieldShape,
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.notes)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    shape = DatabaseManagementFieldShape,
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text(stringResource(R.string.keepass_native_tags)) },
                    supportingText = { Text(stringResource(R.string.note_tags_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expires = !expires },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.expiry_date))
                        Text(
                            stringResource(R.string.keepass_native_group_expiration_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = expires, onCheckedChange = { expires = it })
                }
                if (expires) {
                    OutlinedButton(onClick = ::openDatePicker, modifier = Modifier.fillMaxWidth()) {
                        Text(DateFormat.getDateInstance().format(Date.from(expiryTime)))
                    }
                }
                Text(
                    stringResource(R.string.keepass_native_custom_icon_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showCustomIconPicker = true },
                        enabled = customIcons.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.keepass_native_custom_icon_choose)) }
                    Button(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.keepass_native_custom_icon_upload)) }
                }
                OutlinedButton(
                    onClick = { showPredefinedIconPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(keepassPredefinedIconVector(selectedPredefinedIcon), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.keepass_native_custom_icon_builtin, selectedPredefinedIcon.name))
                }
                OutlinedTextField(
                    shape = DatabaseManagementFieldShape,
                    value = defaultSequence,
                    onValueChange = { defaultSequence = it },
                    label = { Text(stringResource(R.string.keepass_native_default_auto_type_sequence)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box {
                    OutlinedButton(onClick = { searchMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.keepass_native_search_policy, groupOverrideLabel(searching)))
                    }
                    DropdownMenu(expanded = searchMenuExpanded, onDismissRequest = { searchMenuExpanded = false }) {
                        GroupOverride.entries.forEach { value ->
                            DropdownMenuItem(
                                text = { Text(groupOverrideLabel(value)) },
                                leadingIcon = { if (searching == value) Icon(Icons.Default.Check, contentDescription = null) },
                                onClick = { searching = value; searchMenuExpanded = false },
                            )
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { autoTypeMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.keepass_native_auto_type_policy, groupOverrideLabel(autoType)))
                    }
                    DropdownMenu(expanded = autoTypeMenuExpanded, onDismissRequest = { autoTypeMenuExpanded = false }) {
                        GroupOverride.entries.forEach { value ->
                            DropdownMenuItem(
                                text = { Text(groupOverrideLabel(value)) },
                                leadingIcon = { if (autoType == value) Icon(Icons.Default.Check, contentDescription = null) },
                                onClick = { autoType = value; autoTypeMenuExpanded = false },
                            )
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && !busy,
                onClick = {
                    val parsedTags = tagsText.split(',', ';', '\n')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .distinct()
                    busy = true
                    onConfirm(
                        KeePassNativeGroupUpdate(
                            name = name.trim(),
                            notes = notes,
                            icon = selectedPredefinedIcon,
                            customIconUuid = selectedCustomIconUuid,
                            customIcon = pendingCustomIcon,
                            defaultAutoTypeSequence = defaultSequence,
                            enableAutoType = autoType,
                            enableSearching = searching,
                            tags = parsedTags,
                            expires = expires,
                            expiryTime = expiryTime,
                        ),
                    ) { failure ->
                        error = failure
                        busy = false
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
        },
    )

    if (showCustomIconPicker) {
        NativeCustomIconPickerDialog(
            icons = customIcons,
            iconReferences = customIconReferences,
            selectedUuid = selectedCustomIconUuid,
            onSelect = { uuid ->
                selectedCustomIconUuid = uuid
                pendingCustomIcon = null
                showCustomIconPicker = false
            },
            deletingUuid = null,
            onDelete = null,
            onRename = null,
            onDismiss = { showCustomIconPicker = false },
        )
    }
    if (showPredefinedIconPicker) {
        NativePredefinedIconPickerDialog(
            selectedIcon = selectedPredefinedIcon,
            onSelect = { icon ->
                selectedPredefinedIcon = icon
                selectedCustomIconUuid = null
                pendingCustomIcon = null
                showPredefinedIconPicker = false
            },
            onDismiss = { showPredefinedIconPicker = false },
        )
    }
}

@Composable
private fun NativeGroupNameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String, (String?) -> Unit) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    shape = DatabaseManagementFieldShape,
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text(stringResource(R.string.folder_name)) },
                    singleLine = true
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) error = "Name cannot be empty"
                    else onConfirm(name) { failure -> error = failure }
                },
                enabled = name.isNotBlank()
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun NativeGroupMoveDialog(
    browser: KeePassNativeBrowserSnapshot,
    movingGroupUuids: Set<UUID>,
    onDismiss: () -> Unit,
    onMove: (KeePassNativeGroupRecord, (String?) -> Unit) -> Unit
) {
    val movingIdentities = remember(browser, movingGroupUuids) {
        movingGroupUuids.mapTo(linkedSetOf()) { uuid ->
            KeePassNativeGroupIdentity(browser.databaseId, uuid)
        }
    }
    val excluded = remember(browser, movingIdentities) {
        movingIdentities.flatMapTo(linkedSetOf()) { identity ->
            browser.descendantGroupIdentities(identity)
        }
    }
    val targets = remember(browser, excluded) {
        browser.groups.filter { group -> group.identity !in excluded && !group.isInRecycleBin }
    }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (movingGroupUuids.size == 1) stringResource(R.string.move_folder_title)
                else stringResource(R.string.keepass_native_move_folders, movingGroupUuids.size)
            )
        },
        text = {
            Column {
                Text(stringResource(R.string.move_folder_destination_hint))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(targets, key = { target -> "${target.identity.groupUuid}:${target.occurrenceIndex}" }) { target ->
                        ListItem(
                            headlineContent = { Text(target.name) },
                            supportingContent = { Text(target.legacyPath ?: stringResource(R.string.move_folder_database_root_target)) },
                            leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                            modifier = Modifier.clickable {
                                onMove(target) { failure -> error = failure }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun NativeEntryMoveDialog(
    browser: KeePassNativeBrowserSnapshot,
    movingEntryUuids: Set<UUID>,
    onDismiss: () -> Unit,
    onMove: (KeePassNativeGroupRecord, (String?) -> Unit) -> Unit
) {
    val targets = remember(browser) { browser.groups.filterNot { it.isInRecycleBin } }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keepass_native_move_entries, movingEntryUuids.size)) },
        text = {
            Column {
                Text(stringResource(R.string.move_folder_destination_hint))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(targets, key = { target -> "${target.identity.groupUuid}:${target.occurrenceIndex}" }) { target ->
                        ListItem(
                            headlineContent = { Text(target.name) },
                            supportingContent = { Text(target.legacyPath ?: stringResource(R.string.move_folder_database_root_target)) },
                            leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                            modifier = Modifier.clickable { onMove(target) { failure -> error = failure } }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun NativeTemplateTargetDialog(
    browser: KeePassNativeBrowserSnapshot,
    template: KeePassNativeEntryRecord,
    initialTarget: KeePassNativeGroupRecord?,
    onDismiss: () -> Unit,
    onCreate: (KeePassNativeGroupRecord, String, (String?) -> Unit) -> Unit,
) {
    val templateGroups = remember(browser) {
        browser.templateGroupIdentity
            ?.let { browser.descendantGroupIdentities(it) }
            .orEmpty()
    }
    val targets = remember(browser, templateGroups) {
        browser.groups.filter { group ->
            !group.isInRecycleBin && group.identity !in templateGroups
        }
    }
    val fallback = browser.rootGroup.takeIf { it.identity !in templateGroups }
        ?: targets.firstOrNull()
    var selectedIdentity by remember(template.identity) {
        mutableStateOf(
            initialTarget?.identity?.takeIf { identity -> targets.any { it.identity == identity } }
                ?: fallback?.identity
        )
    }
    var title by remember(template.identity) { mutableStateOf(template.title) }
    var busy by remember(template.identity) { mutableStateOf(false) }
    var error by remember(template.identity) { mutableStateOf<String?>(null) }
    val selected = selectedIdentity?.let(browser::group)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
        title = { Text(stringResource(R.string.keepass_native_use_template)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    shape = DatabaseManagementFieldShape,
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.keepass_native_template_target_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(modifier = Modifier.height(320.dp)) {
                    items(
                        items = targets,
                        key = { target -> "template-target:${target.identity.groupUuid}:${target.occurrenceIndex}" },
                    ) { target ->
                        ListItem(
                            headlineContent = { Text(target.name) },
                            supportingContent = {
                                Text(
                                    target.legacyPath
                                        ?: stringResource(R.string.move_folder_database_root_target)
                                )
                            },
                            leadingContent = {
                                Icon(Icons.Outlined.Folder, contentDescription = null)
                            },
                            trailingContent = {
                                if (selectedIdentity == target.identity) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            },
                            modifier = Modifier.clickable(enabled = !busy) {
                                selectedIdentity = target.identity
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = if (selectedIdentity == target.identity) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Transparent
                                }
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && selected != null && title.isNotBlank(),
                onClick = {
                    val target = selected ?: return@Button
                    busy = true
                    onCreate(target, title.trim()) { failure ->
                        error = failure
                        busy = false
                    }
                },
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.create))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun NativeGroupPropertiesDialog(
    group: KeePassNativeGroupRecord,
    customIcons: Map<UUID, app.keemobile.kotpass.models.CustomIcon>,
    customIconReferences: Map<UUID, Int>,
    onDismiss: () -> Unit,
    onSave: (KeePassNativeGroupUpdate, (String?) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var name by remember(group.identity) { mutableStateOf(group.name) }
    var notes by remember(group.identity) { mutableStateOf(group.notes) }
    var tagsText by remember(group.identity) { mutableStateOf(group.tags.joinToString(", ")) }
    var expires by remember(group.identity) { mutableStateOf(group.times?.expires == true) }
    var expiryTime by remember(group.identity) {
        mutableStateOf(group.times?.expiryTime ?: Instant.now().plus(30, ChronoUnit.DAYS))
    }
    var defaultSequence by remember(group.identity) { mutableStateOf(group.defaultAutoTypeSequence.orEmpty()) }
    var searching by remember(group.identity) { mutableStateOf(group.enableSearching) }
    var autoType by remember(group.identity) { mutableStateOf(group.enableAutoType) }
    var searchMenuExpanded by remember { mutableStateOf(false) }
    var autoTypeMenuExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedCustomIconUuid by remember(group.identity) { mutableStateOf(group.customIconUuid) }
    var selectedPredefinedIcon by remember(group.identity) { mutableStateOf(group.icon) }
    var predefinedIconChanged by remember(group.identity) { mutableStateOf(false) }
    var clearCustomIcon by remember(group.identity) { mutableStateOf(false) }
    var pendingCustomIcon by remember(group.identity) { mutableStateOf<KeePassNativeCustomIconPayload?>(null) }
    var pendingIconBytes by remember(group.identity) { mutableStateOf<ByteArray?>(null) }
    var pendingIconName by remember(group.identity) { mutableStateOf("") }
    var showCustomIconPicker by remember { mutableStateOf(false) }
    var showPredefinedIconPicker by remember { mutableStateOf(false) }
    var showIconNameDialog by remember { mutableStateOf(false) }
    val iconReadFailedMessage = stringResource(R.string.keepass_native_custom_icon_read_failed)
    val iconInvalidMessage = stringResource(R.string.keepass_native_custom_icon_invalid)
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to read selected image")
        }.getOrNull()
        when {
            bytes == null -> error = iconReadFailedMessage
            KeePassCustomIconEditor.validateImageBytes(bytes).isFailure -> error = iconInvalidMessage
            else -> {
                pendingIconBytes = bytes
                pendingIconName = uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.substringBeforeLast('.')
                    ?.takeIf(String::isNotBlank)
                    ?: group.name
                showIconNameDialog = true
            }
        }
    }
    fun openDatePicker() {
        val local = expiryTime.atZone(ZoneId.systemDefault())
        DatePickerDialog(
            context,
            { _, year, month, day ->
                expiryTime = LocalDate.of(year, month + 1, day)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
            },
            local.year,
            local.monthValue - 1,
            local.dayOfMonth,
        ).show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keepass_native_group_properties)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    shape = DatabaseManagementFieldShape,
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    shape = DatabaseManagementFieldShape,
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.notes)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    shape = DatabaseManagementFieldShape,
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text(stringResource(R.string.keepass_native_tags)) },
                    supportingText = { Text(stringResource(R.string.note_tags_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expires = !expires },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.expiry_date))
                        Text(
                            stringResource(R.string.keepass_native_group_expiration_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = expires, onCheckedChange = { expires = it })
                }
                if (expires) {
                    OutlinedButton(onClick = ::openDatePicker, modifier = Modifier.fillMaxWidth()) {
                        Text(DateFormat.getDateInstance().format(Date.from(expiryTime)))
                    }
                }
                Text(
                    stringResource(R.string.keepass_native_custom_icon_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showCustomIconPicker = true },
                        enabled = customIcons.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.keepass_native_custom_icon_choose))
                    }
                    Button(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.keepass_native_custom_icon_upload))
                    }
                }
                OutlinedButton(
                    onClick = { showPredefinedIconPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            R.string.keepass_native_custom_icon_builtin,
                            selectedPredefinedIcon.name,
                        )
                    )
                }
                if (selectedCustomIconUuid != null || pendingCustomIcon != null) {
                    TextButton(
                        onClick = {
                            selectedCustomIconUuid = null
                            pendingCustomIcon = null
                            clearCustomIcon = true
                        },
                    ) {
                        Text(stringResource(R.string.keepass_native_custom_icon_clear))
                    }
                }
                OutlinedTextField(
                    shape = DatabaseManagementFieldShape,
                    value = defaultSequence,
                    onValueChange = { defaultSequence = it },
                    label = { Text(stringResource(R.string.keepass_native_default_auto_type_sequence)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Box {
                    OutlinedButton(onClick = { searchMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.keepass_native_search_policy, groupOverrideLabel(searching)))
                    }
                    DropdownMenu(expanded = searchMenuExpanded, onDismissRequest = { searchMenuExpanded = false }) {
                        GroupOverride.entries.forEach { value ->
                            DropdownMenuItem(
                                text = { Text(groupOverrideLabel(value)) },
                                leadingIcon = { if (searching == value) Icon(Icons.Default.Check, contentDescription = null) },
                                onClick = { searching = value; searchMenuExpanded = false }
                            )
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { autoTypeMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.keepass_native_auto_type_policy, groupOverrideLabel(autoType)))
                    }
                    DropdownMenu(expanded = autoTypeMenuExpanded, onDismissRequest = { autoTypeMenuExpanded = false }) {
                        GroupOverride.entries.forEach { value ->
                            DropdownMenuItem(
                                text = { Text(groupOverrideLabel(value)) },
                                leadingIcon = { if (autoType == value) Icon(Icons.Default.Check, contentDescription = null) },
                                onClick = { autoType = value; autoTypeMenuExpanded = false }
                            )
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedTags = tagsText.split(',', ';', '\n')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                onSave(
                    KeePassNativeGroupUpdate(
                        name = name.trim(),
                        notes = notes,
                        icon = selectedPredefinedIcon.takeIf { predefinedIconChanged },
                        customIconUuid = selectedCustomIconUuid,
                        clearCustomIcon = clearCustomIcon,
                        customIcon = pendingCustomIcon,
                        defaultAutoTypeSequence = defaultSequence,
                        enableSearching = searching,
                        enableAutoType = autoType,
                        tags = parsedTags,
                        expires = expires,
                        expiryTime = expiryTime,
                    )
                ) { failure -> error = failure }
            }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )

    if (showCustomIconPicker) {
        NativeCustomIconPickerDialog(
            icons = customIcons,
            iconReferences = customIconReferences,
            selectedUuid = selectedCustomIconUuid,
            onSelect = { uuid ->
                selectedCustomIconUuid = uuid
                pendingCustomIcon = null
                clearCustomIcon = false
                showCustomIconPicker = false
            },
            deletingUuid = null,
            onDelete = null,
            onRename = null,
            onDismiss = { showCustomIconPicker = false },
        )
    }
    if (showPredefinedIconPicker) {
        NativePredefinedIconPickerDialog(
            selectedIcon = selectedPredefinedIcon,
            onSelect = { icon ->
                selectedPredefinedIcon = icon
                predefinedIconChanged = true
                selectedCustomIconUuid = null
                pendingCustomIcon = null
                clearCustomIcon = true
                showPredefinedIconPicker = false
            },
            onDismiss = { showPredefinedIconPicker = false },
        )
    }
    if (showIconNameDialog) {
        KeePassCustomIconNameDialog(
            name = pendingIconName,
            onNameChange = { pendingIconName = it },
            onConfirm = {
                val bytes = pendingIconBytes
                if (bytes != null && pendingIconName.isNotBlank()) {
                    pendingCustomIcon = KeePassNativeCustomIconPayload(bytes, pendingIconName.trim())
                    pendingIconBytes = null
                    selectedCustomIconUuid = null
                    clearCustomIcon = false
                    showIconNameDialog = false
                }
            },
            onDismiss = {
                pendingIconBytes = null
                showIconNameDialog = false
            },
        )
    }
}

@Composable
private fun groupOverrideLabel(value: GroupOverride): String = when (value) {
    GroupOverride.Inherit -> stringResource(R.string.keepass_native_policy_inherit)
    GroupOverride.Enabled -> stringResource(R.string.enabled)
    GroupOverride.Disabled -> stringResource(R.string.disabled)
}

@Composable
private fun NativeErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.keepass_native_load_failed), fontWeight = FontWeight.Bold)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun NativeEmptyState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(10.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NativeSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 8.dp)
    )
}

@Composable
internal fun nativeKindLabel(kind: KeePassNativeEntryKind): String = when (kind) {
    KeePassNativeEntryKind.PASSWORD -> stringResource(R.string.keepass_native_kind_password)
    KeePassNativeEntryKind.TOTP -> stringResource(R.string.keepass_native_kind_totp)
    KeePassNativeEntryKind.NOTE -> stringResource(R.string.keepass_native_kind_note)
    KeePassNativeEntryKind.BANK_CARD -> stringResource(R.string.keepass_native_kind_bank_card)
    KeePassNativeEntryKind.DOCUMENT -> stringResource(R.string.keepass_native_kind_document)
    KeePassNativeEntryKind.PASSKEY -> stringResource(R.string.keepass_native_kind_passkey)
    KeePassNativeEntryKind.TEMPLATE -> stringResource(R.string.keepass_native_kind_template)
    KeePassNativeEntryKind.UNKNOWN -> stringResource(R.string.keepass_native_kind_unknown)
}

@Composable
internal fun nativeKindColor(kind: KeePassNativeEntryKind): Color = when (kind) {
    KeePassNativeEntryKind.PASSWORD -> MaterialTheme.colorScheme.primary
    KeePassNativeEntryKind.TOTP -> MaterialTheme.colorScheme.tertiary
    KeePassNativeEntryKind.NOTE -> MaterialTheme.colorScheme.secondary
    KeePassNativeEntryKind.BANK_CARD -> MaterialTheme.colorScheme.primary
    KeePassNativeEntryKind.DOCUMENT -> MaterialTheme.colorScheme.secondary
    KeePassNativeEntryKind.PASSKEY -> MaterialTheme.colorScheme.tertiary
    KeePassNativeEntryKind.TEMPLATE -> MaterialTheme.colorScheme.outline
    KeePassNativeEntryKind.UNKNOWN -> MaterialTheme.colorScheme.error
}

internal fun nativeKindIcon(kind: KeePassNativeEntryKind): ImageVector = when (kind) {
    KeePassNativeEntryKind.PASSWORD -> Icons.Outlined.Password
    KeePassNativeEntryKind.TOTP -> Icons.Outlined.Key
    KeePassNativeEntryKind.NOTE -> Icons.Outlined.Notes
    KeePassNativeEntryKind.BANK_CARD -> Icons.Outlined.CreditCard
    KeePassNativeEntryKind.DOCUMENT -> Icons.Outlined.Badge
    KeePassNativeEntryKind.PASSKEY -> Icons.Outlined.Fingerprint
    KeePassNativeEntryKind.TEMPLATE -> Icons.Outlined.DataObject
    KeePassNativeEntryKind.UNKNOWN -> Icons.Outlined.Extension
}

@Composable
private fun nativeSearchFieldLabel(field: KeePassNativeSearchField): String = when (field) {
    KeePassNativeSearchField.TITLE -> stringResource(R.string.title)
    KeePassNativeSearchField.USERNAME -> stringResource(R.string.username)
    KeePassNativeSearchField.PASSWORD -> stringResource(R.string.password)
    KeePassNativeSearchField.URL -> stringResource(R.string.website)
    KeePassNativeSearchField.NOTES -> stringResource(R.string.notes)
    KeePassNativeSearchField.CUSTOM_FIELDS -> stringResource(R.string.custom_fields)
    KeePassNativeSearchField.TAGS -> stringResource(R.string.keepass_native_tags)
    KeePassNativeSearchField.GROUP_NAME -> stringResource(R.string.folder_name)
    KeePassNativeSearchField.ENTRY_UUID -> "Entry UUID"
    KeePassNativeSearchField.GROUP_UUID -> "Group UUID"
}

@Composable
private fun nativeSortModeLabel(mode: KeePassNativeSortMode): String = when (mode) {
    KeePassNativeSortMode.NATURAL -> stringResource(R.string.keepass_native_sort_natural)
    KeePassNativeSortMode.TITLE_ASCENDING -> stringResource(R.string.keepass_native_sort_title_ascending)
    KeePassNativeSortMode.TITLE_DESCENDING -> stringResource(R.string.keepass_native_sort_title_descending)
    KeePassNativeSortMode.MODIFIED_DESCENDING -> stringResource(R.string.keepass_native_sort_modified)
    KeePassNativeSortMode.CREATED_DESCENDING -> stringResource(R.string.keepass_native_sort_created)
}
