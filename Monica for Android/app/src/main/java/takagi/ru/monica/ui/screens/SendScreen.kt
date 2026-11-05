package takagi.ru.monica.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import takagi.ru.monica.ui.components.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.cancelChildren
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import takagi.ru.monica.bitwarden.service.BitwardenSendDraft
import takagi.ru.monica.bitwarden.service.SendPasswordAction
import takagi.ru.monica.bitwarden.service.SendAuthAction
import takagi.ru.monica.bitwarden.service.BitwardenSendDraftStore
import takagi.ru.monica.security.SessionManager
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import takagi.ru.monica.ui.components.TemplateFormSection
import takagi.ru.monica.ui.components.ItemEditorIdentity
import takagi.ru.monica.ui.components.ItemEditorOptionalSection
import takagi.ru.monica.ui.components.ItemEditorAddContent
import takagi.ru.monica.ui.components.ItemEditorContentOption
import takagi.ru.monica.ui.components.rememberItemEditorSections
import androidx.lifecycle.viewmodel.compose.viewModel
import takagi.ru.monica.R
import takagi.ru.monica.attachments.facade.AttachmentUriMetadata
import takagi.ru.monica.bitwarden.BitwardenVaultPremiumStore
import takagi.ru.monica.bitwarden.api.BitwardenApiFactory
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.bitwarden.ui.BitwardenAutoSyncEffect
import takagi.ru.monica.bitwarden.sync.buildHeadline
import takagi.ru.monica.bitwarden.sync.isUserVisibleSyncInProgress
import takagi.ru.monica.data.bitwarden.BitwardenSend
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.ui.components.ExpressiveTopBar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import takagi.ru.monica.ui.common.pull.rememberPullToSearchState
import takagi.ru.monica.ui.common.pull.PullSearchDefaults
import takagi.ru.monica.ui.common.pull.PullSearchHint

private enum class SendCreateType {
    Text,
    File
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SendScreen(
    modifier: Modifier = Modifier,
    onSendClick: (BitwardenSend) -> Unit = {},
    selectedSendId: String? = null,
    showTopBar: Boolean = true,
    showStandaloneSettingsEntry: Boolean = false,
    onOpenStandaloneSettings: () -> Unit = {},
    bitwardenViewModel: BitwardenViewModel = viewModel(),
    onBitwardenEvent: ((BitwardenViewModel.BitwardenEvent) -> Boolean)? = null
) {
    val sends by bitwardenViewModel.sendsAcrossVaults.collectAsState()
    val activeVault by bitwardenViewModel.activeVault.collectAsState()
    val unlockState by bitwardenViewModel.unlockState.collectAsState()
    val unlockStateByVault by bitwardenViewModel.unlockStateByVault.collectAsState()
    val allVaults by bitwardenViewModel.vaults.collectAsState()
    val sendState by bitwardenViewModel.sendState.collectAsState()
    val syncStatusByVault by bitwardenViewModel.syncStatusByVault.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 多账号场景下：只要有任一已解锁 Vault 就允许操作；空账号 / 全锁定时禁用刷新按钮
    val anyVaultUnlocked = unlockStateByVault.any { it.value == BitwardenViewModel.UnlockState.Unlocked }
    val canCreateSend = activeVault != null && unlockState == BitwardenViewModel.UnlockState.Unlocked
    val isAnyVaultSyncing = syncStatusByVault.values.any { it.isUserVisibleSyncInProgress() }
    val isSendSyncing = sendState is BitwardenViewModel.SendState.Syncing || isAnyVaultSyncing

    val vaultLookup = remember(allVaults) { allVaults.associateBy { it.id } }

    var deletingSend by remember { mutableStateOf<BitwardenSend?>(null) }
    var editingSend by remember { mutableStateOf<BitwardenSend?>(null) }
    var showLocalDrafts by remember { mutableStateOf(false) }
    var showReceiver by remember { mutableStateOf(false) }
    if (showReceiver) takagi.ru.monica.bitwarden.ui.BitwardenSendReceiverDialog(onDismiss = { showReceiver = false })
    var resumingDraft by remember { mutableStateOf<BitwardenSendDraftStore.Draft?>(null) }
    val localDrafts by bitwardenViewModel.localSendDrafts.collectAsState()
    val draftsUnavailable by bitwardenViewModel.localSendDraftsUnavailable.collectAsState()
    val cacheUnavailable by takagi.ru.monica.bitwarden.service.BitwardenSendCache.unavailable.collectAsState()
    val appUnlocked by SessionManager.isUnlocked.collectAsState()
    LaunchedEffect(appUnlocked, anyVaultUnlocked) {
        if (!appUnlocked || !anyVaultUnlocked) {
            showLocalDrafts = false; resumingDraft = null; editingSend = null; deletingSend = null
        }
    }
    val sendSaveVersion by bitwardenViewModel.sendCreateSuccessVersion.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var showTopActionsMenu by remember { mutableStateOf(false) }
    val triggerDistance = with(LocalDensity.current) { PullSearchDefaults.TriggerDistance.toPx() }
    val pullSearch = rememberPullToSearchState(
        isSearchExpanded = isSearchExpanded,
        searchTriggerDistance = triggerDistance,
        maxDragDistance = triggerDistance * 1.6f,
        onSearchTriggered = { isSearchExpanded = true },
    )
    val currentOffset = pullSearch.currentOffset

    val filteredSends = remember(sends, searchQuery, vaultLookup) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            sends
        } else {
            sends.filter { send ->
                val vaultLabel = vaultLookup[send.vaultId]?.let { v ->
                    v.displayName?.takeIf { it.isNotBlank() } ?: v.email
                }.orEmpty()
                send.name.contains(query, ignoreCase = true) ||
                    send.shareUrl.contains(query, ignoreCase = true) ||
                    (send.textContent?.contains(query, ignoreCase = true) == true) ||
                    (send.fileName?.contains(query, ignoreCase = true) == true) ||
                    send.notes.contains(query, ignoreCase = true) ||
                    vaultLabel.contains(query, ignoreCase = true)
            }
        }
    }


    BitwardenAutoSyncEffect(
        viewModel = bitwardenViewModel,
        selectedVaultId = activeVault?.id,
        isAllView = false,
        enabled = canCreateSend
    )
    LaunchedEffect(activeVault?.id, unlockState, anyVaultUnlocked) {
        if (anyVaultUnlocked) {
            // 至少有一个已解锁账号就允许刷新视图：当前活跃账号触发自动同步，
            // 其它账号通过 sendsAcrossVaults 直接复用本地缓存。
            delay(1_200L)
            bitwardenViewModel.loadSends(forceRemoteSync = false)
        }
    }

    LaunchedEffect(bitwardenViewModel, onBitwardenEvent) {
        bitwardenViewModel.events.collect { event ->
            val consumedByParent = onBitwardenEvent?.invoke(event) == true
            if (consumedByParent) return@collect
            when (event) {
                is BitwardenViewModel.BitwardenEvent.ShowSuccess -> snackbarHostState.showSnackbar(event.message)
                is BitwardenViewModel.BitwardenEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
                is BitwardenViewModel.BitwardenEvent.ShowWarning -> snackbarHostState.showSnackbar(event.message)
                is BitwardenViewModel.BitwardenEvent.SyncFinished ->
                    snackbarHostState.showSnackbar(event.summary.buildHeadline(context))
                is BitwardenViewModel.BitwardenEvent.SendCreated -> snackbarHostState.showSnackbar(event.message)
                is BitwardenViewModel.BitwardenEvent.SendDeleted -> snackbarHostState.showSnackbar(event.message)
                else -> Unit
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            if (showTopBar) {
                ExpressiveTopBar(
                    title = stringResource(R.string.send_screen_title),
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    isSearchExpanded = isSearchExpanded,
                    onSearchExpandedChange = { expanded ->
                        isSearchExpanded = expanded
                        if (!expanded) {
                            searchQuery = ""
                        }
                    },
                    searchHint = stringResource(R.string.send_search_hint),
                    actions = {
                        IconButton(
                            onClick = { bitwardenViewModel.refreshAllUnlockedSends() },
                            enabled = anyVaultUnlocked && !isSendSyncing
                        ) {
                            if (isSendSyncing) {
                                val refreshRotation by rememberInfiniteTransition(label = "send_refresh_spin")
                                    .animateFloat(
                                        initialValue = 0f,
                                        targetValue = 360f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(durationMillis = 900, easing = LinearEasing),
                                            repeatMode = RepeatMode.Restart
                                        ),
                                        label = "send_refresh_rotation"
                                    )
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.refresh),
                                    modifier = Modifier.rotate(refreshRotation)
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                            }
                        }
                        IconButton(onClick = { isSearchExpanded = true }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                        }
                        if (showStandaloneSettingsEntry) {
                            Box {
                                IconButton(onClick = { showTopActionsMenu = true }) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = stringResource(R.string.more_options)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showTopActionsMenu,
                                    onDismissRequest = { showTopActionsMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.nav_settings)) },
                                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                        onClick = {
                                            showTopActionsMenu = false
                                            onOpenStandaloneSettings()
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Button(onClick = { showReceiver = true }, enabled = appUnlocked,
                    modifier = Modifier.fillMaxWidth().testTag("send_open_receiver")) {
                    Text(stringResource(R.string.bitwarden_receiver_entry))
                }
                OutlinedButton(onClick = { bitwardenViewModel.refreshLocalSendDrafts(); showLocalDrafts = true },
                    enabled = appUnlocked && anyVaultUnlocked,
                    modifier = Modifier.fillMaxWidth().testTag("send_open_drafts")) {
                    Text(stringResource(R.string.bw_send_drafts))
                }
                if (cacheUnavailable.any { (id, count) -> count > 0 && unlockStateByVault[id] == BitwardenViewModel.UnlockState.Unlocked })
                    StateBanner(stringResource(R.string.bw_send_cache_unavailable))
                when (sendState) {
                    is BitwardenViewModel.SendState.Syncing -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    is BitwardenViewModel.SendState.Warning -> {
                        StateBanner((sendState as BitwardenViewModel.SendState.Warning).message)
                    }
                    is BitwardenViewModel.SendState.Error -> {
                        StateBanner((sendState as BitwardenViewModel.SendState.Error).message)
                    }
                    else -> Unit
                }
                if (sendState !is BitwardenViewModel.SendState.Syncing && isAnyVaultSyncing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    PullSearchHint(currentOffset = currentOffset, triggerDistance = triggerDistance)
                    when {
                        // 没有任何已连接 Vault：保留原"未连接"提示
                        allVaults.isEmpty() -> {
                            EmptyStateCard(
                                title = stringResource(R.string.send_empty_no_connection_title),
                                message = stringResource(R.string.send_empty_no_connection_message)
                            )
                        }
                        // 有 Vault 但没有任何已解锁、并且本地也没有跨账号的 Send 缓存可用
                        !anyVaultUnlocked && sends.isEmpty() -> {
                            EmptyStateCard(
                                title = stringResource(R.string.send_empty_vault_locked_title),
                                message = stringResource(R.string.send_empty_vault_locked_message)
                            )
                        }
                        sends.isEmpty() && sendState == BitwardenViewModel.SendState.Loading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        sends.isEmpty() -> {
                            EmptyStateCard(
                                title = stringResource(R.string.send_empty_none_title),
                                message = stringResource(R.string.send_empty_none_message)
                            )
                        }
                        filteredSends.isEmpty() -> {
                            EmptyStateCard(
                                title = stringResource(R.string.send_empty_no_match_title),
                                message = stringResource(R.string.send_empty_no_match_message)
                            )
                        }
                        else -> {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset { IntOffset(0, currentOffset.toInt()) }
                                    .then(pullSearch.gestureModifier),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(bottom = 96.dp)
                            ) {
                                items(
                                    items = filteredSends,
                                    // 跨账号视图下，单独的 sendId 不再唯一（不同 vault 间会重复），
                                    // 用 vaultId+sendId 复合键避免 LazyColumn key 冲突。
                                    key = { "${it.vaultId}:${it.bitwardenSendId}" }
                                ) { send ->
                                    val vault = vaultLookup[send.vaultId]
                                    val vaultLabel = vault?.let {
                                        it.displayName?.takeIf { name -> name.isNotBlank() } ?: it.email
                                    }.orEmpty()
                                    val vaultServer = vault?.serverUrl.orEmpty()
                                    SendItemCard(
                                        send = send,
                                        selected = selectedSendId == send.bitwardenSendId,
                                        vaultLabel = vaultLabel,
                                        vaultServerUrl = vaultServer,
                                        onClick = { onSendClick(send) },
                                        onCopyLink = {
                                            clipboardManager.setText(AnnotatedString(send.shareUrl))
                                        },
                                        onOpenLink = {
                                            runCatching {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(send.shareUrl))
                                                context.startActivity(intent)
                                            }
                                        },
                                        onDelete = { deletingSend = send },
                                        onEdit = { editingSend = send }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLocalDrafts) Dialog(onDismissRequest = { showLocalDrafts = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showLocalDrafts = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    Text(stringResource(R.string.bw_send_drafts), style = MaterialTheme.typography.titleLarge)
                }
                SendLocalDraftList(localDrafts, draftsUnavailable, sendState is BitwardenViewModel.SendState.Creating,
                    onResume = { resumingDraft = it; showLocalDrafts = false },
                    onDiscard = bitwardenViewModel::discardLocalSendDraft)
            }
        }
    }
    resumingDraft?.let { saved ->
        Dialog(onDismissRequest = { if (sendState !is BitwardenViewModel.SendState.Creating) resumingDraft = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
            Surface(Modifier.fillMaxSize()) {
                AddEditSendScreen(sendState = sendState, sendCreateSuccessVersion = sendSaveVersion,
                    vaults = allVaults, activeVault = vaultLookup[saved.vaultId], unlockStateByVault = unlockStateByVault,
                    initialStoredDraft = saved, onNavigateBack = { resumingDraft = null; bitwardenViewModel.refreshLocalSendDrafts() },
                    onSubmitStoredDraft = bitwardenViewModel::submitStoredSendDraft,
                    onLeaveForeground = bitwardenViewModel::leaveSendEditorForeground,
                    onCreate = { _, _, _, _, _, _, _, _, _ -> }, onCreateFile = { _, _, _, _, _, _, _, _, _ -> })
            }
        }
    }

    editingSend?.let { send ->
        Dialog(
            onDismissRequest = { if (sendState !is BitwardenViewModel.SendState.Creating) editingSend = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                AddEditSendScreen(
                    sendState = sendState, sendCreateSuccessVersion = sendSaveVersion,
                    vaults = allVaults, activeVault = vaultLookup[send.vaultId],
                    unlockStateByVault = unlockStateByVault, existingSend = send,
                    onNavigateBack = { editingSend = null },
                    onCreate = { _, _, _, _, _, _, _, _, _ -> },
                    onCreateFile = { _, _, _, _, _, _, _, _, _ -> },
                    onSubmitStoredDraft = bitwardenViewModel::submitStoredSendDraft,
                    onLeaveForeground = bitwardenViewModel::leaveSendEditorForeground,
                    onSaveDraft = { vaultId, draft, _, _ -> bitwardenViewModel.saveSendDraft(vaultId, draft, send) }
                )
            }
        }
    }

    if (deletingSend != null) {
        val send = deletingSend ?: return
        AlertDialog(
            onDismissRequest = { deletingSend = null },
            title = { Text(stringResource(R.string.send_delete_title)) },
            text = { Text(stringResource(R.string.send_delete_message, send.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        // 多账号场景下必须把 send 自身的 vaultId 传进去，否则会落到错误的 Vault
                        bitwardenViewModel.deleteSend(send.bitwardenSendId, send.vaultId)
                        deletingSend = null
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSend = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SendHeroCard(
    sendCount: Int,
    textCount: Int,
    fileCount: Int
) {
    val gradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
        )
    )

    Surface(
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.send_secure_share_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = stringResource(R.string.send_secure_share_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroChip(label = stringResource(R.string.send_chip_total, sendCount))
                    HeroChip(label = stringResource(R.string.send_chip_text, textCount))
                    HeroChip(label = stringResource(R.string.send_chip_file, fileCount))
                }
            }
        }
    }
}

@Composable
private fun HeroChip(label: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SendItemCard(
    send: BitwardenSend,
    selected: Boolean,
    /** 例如 "joyinsana@163.com" 或 displayName。空串时不展示账号信息。 */
    vaultLabel: String,
    /** 当前 vault 的 serverUrl，仅用于展开后的细节展示。空串时省略。 */
    vaultServerUrl: String,
    onClick: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenLink: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "send_arrow_rotation"
    )
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { expanded = !expanded }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        ),
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (send.isTextType) Icons.AutoMirrored.Filled.Send else Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column {
                        Text(
                            text = send.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        
                        val typeLabel = if (send.isTextType) {
                            stringResource(R.string.send_type_text)
                        } else {
                            stringResource(R.string.send_type_file)
                        }
                        
                        // 多账号场景下，副标题同时展示类型和所属账号，便于一眼区分两个 Vault 下同名 Send。
                        val subtitle = if (vaultLabel.isNotBlank()) {
                            "$typeLabel · $vaultLabel"
                        } else {
                            typeLabel
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(arrowRotation)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(durationMillis = 180)),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(durationMillis = 140))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                if (vaultLabel.isNotBlank()) {
                    // 展开后展示创建该 Send 的账号 / 服务器，回答"这条 Send 来自哪个账号"。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = vaultLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (vaultServerUrl.isNotBlank()) {
                                Text(
                                    text = vaultServerUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                val body = when {
                    send.isTextType && !send.textContent.isNullOrBlank() -> send.textContent
                    send.isFileType -> send.fileName ?: stringResource(R.string.send_file_fallback_name)
                    else -> send.notes
                }
                
                if (!body.isNullOrBlank()) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (send.hasPassword) {
                        MetaTag(icon = Icons.Default.Key, label = stringResource(R.string.send_tag_password_protected))
                    }
                    if (send.isTextHidden) {
                        MetaTag(icon = Icons.Default.VisibilityOff, label = stringResource(R.string.send_tag_hidden_content))
                    }
                    if (send.disabled) {
                        MetaTag(icon = Icons.Default.Lock, label = stringResource(R.string.send_tag_disabled))
                    }
                    MetaTag(icon = Icons.Default.Refresh, label = stringResource(R.string.send_tag_access_count, send.accessCount))
                    send.maxAccessCount?.let { max ->
                        MetaTag(icon = Icons.AutoMirrored.Filled.Send, label = stringResource(R.string.send_tag_limit, max))
                    }
                    send.expirationDate?.let { exp ->
                        MetaTag(icon = Icons.Default.CloudOff, label = stringResource(R.string.send_tag_expire, formatDate(exp)))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onCopyLink,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.send_copy_link),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    OutlinedButton(
                        onClick = onOpenLink,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.open_link),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.edit))
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.delete))
                }
                }
            }
        }
    }
}

@Composable
private fun MetaTag(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditSendScreen(
    modifier: Modifier = Modifier,
    sendState: BitwardenViewModel.SendState,
    sendCreateSuccessVersion: Int = 0,
    vaults: List<BitwardenVault> = emptyList(),
    activeVault: BitwardenVault? = null,
    unlockStateByVault: Map<Long, BitwardenViewModel.UnlockState> = emptyMap(),
    initialTitle: String = "",
    initialText: String = "",
    initialNotes: String = "",
    onNavigateBack: () -> Unit,
    onCreate: (
        vaultId: Long,
        title: String,
        text: String,
        notes: String?,
        password: String?,
        maxAccessCount: Int?,
        hideEmail: Boolean,
        hiddenText: Boolean,
        expireInDays: Int
    ) -> Unit,
    onCreateFile: (
        vaultId: Long,
        title: String,
        fileUri: Uri,
        fileName: String,
        notes: String?,
        password: String?,
        maxAccessCount: Int?,
        hideEmail: Boolean,
        expireInDays: Int
    ) -> Unit,
    existingSend: BitwardenSend? = null,
    onSaveDraft: ((Long, BitwardenSendDraft, Uri?, String?) -> Unit)? = null,
    initialStoredDraft: BitwardenSendDraftStore.Draft? = null,
    onSubmitStoredDraft: ((BitwardenSendDraftStore.Draft) -> Unit)? = null,
    onLeaveForeground: () -> Unit = {}
) {
    val sourceSend = initialStoredDraft?.original ?: existingSend
    val initialSourceIdentity = remember(sourceSend, initialStoredDraft) {
        initialStoredDraft?.account ?: sourceSend?.let { send ->
            (vaults + listOfNotNull(activeVault)).firstOrNull { it.id == send.vaultId }
                ?.let { takagi.ru.monica.bitwarden.service.BitwardenSendAccountIdentity.from(it) }
        }
    }
    val restored = initialStoredDraft?.draft
    var storedDraft by remember(initialStoredDraft) { mutableStateOf(initialStoredDraft) }
    var draftSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val appUnlocked by SessionManager.isUnlocked.collectAsState()
    val appLockGeneration by SessionManager.lockGeneration.collectAsState()
    val initialLockGeneration = remember { appLockGeneration }
    var title by remember(initialTitle, sourceSend) { mutableStateOf(restored?.title ?: sourceSend?.name ?: initialTitle) }
    var text by remember(initialText, sourceSend) { mutableStateOf(restored?.text ?: sourceSend?.textContent ?: initialText) }
    var notes by remember(initialNotes, sourceSend) { mutableStateOf(restored?.notes ?: sourceSend?.notes ?: initialNotes) }
    var password by remember { mutableStateOf(restored?.password.orEmpty()) }
    var maxAccessCount by remember { mutableStateOf(initialStoredDraft?.accessLimitInput ?: restored?.maxAccessCount?.toString() ?: sourceSend?.maxAccessCount?.toString().orEmpty()) }
    var expireDaysText by remember { mutableStateOf("7") }
    var hideEmail by remember { mutableStateOf(restored?.hideEmail ?: sourceSend?.hideEmail ?: false) }
    var hiddenText by remember { mutableStateOf(restored?.hiddenText ?: sourceSend?.isTextHidden ?: false) }
    var disabled by remember { mutableStateOf(restored?.disabled ?: sourceSend?.disabled ?: false) }
    var removePassword by remember { mutableStateOf(restored?.passwordAction == SendPasswordAction.REMOVE) }
    val initialNow = remember { System.currentTimeMillis() }
    var deletionMillis by remember { mutableStateOf(restored?.deletionMillis ?: sourceSend?.deletionDate?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: initialNow + 8L * 86_400_000L) }
    var expirationMillis by remember { mutableStateOf(if (restored != null) restored.expirationMillis else if (sourceSend != null) sourceSend.expirationDate?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } else initialNow + 7L * 86_400_000L) }
    var sendType by remember { mutableStateOf(if (initialStoredDraft?.type == BitwardenSend.TYPE_FILE || sourceSend?.isFileType == true) SendCreateType.File else SendCreateType.Text) }
    var filePickerPending by remember { mutableStateOf(false) }
    var editorRevoked by remember { mutableStateOf(false) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileMeta by remember { mutableStateOf<AttachmentUriMetadata.Metadata?>(null) }
    var selectedVaultId by rememberSaveable {
        mutableStateOf(initialStoredDraft?.vaultId ?: sourceSend?.vaultId ?: activeVault?.id ?: vaults.firstOrNull()?.id)
    }
    var submitRequested by rememberSaveable { mutableStateOf(false) }
    var submitStarted by rememberSaveable { mutableStateOf(false) }
    var submitSuccessBaseline by rememberSaveable { mutableStateOf(sendCreateSuccessVersion) }
    val context = LocalContext.current
    val draftStore = remember(context) { BitwardenSendDraftStore(context) }
    var authMode by remember { mutableStateOf(restored?.authAction ?: if (sourceSend == null) SendAuthAction.NONE else SendAuthAction.KEEP) }
    var recipients by remember { mutableStateOf(restored?.recipientEmails?.takeIf { it.isNotEmpty() }
        ?: sourceSend?.recipientEmails?.split(',')?.takeIf { it.isNotEmpty() } ?: listOf("")) }
    val leaveForeground by rememberUpdatedState(onLeaveForeground)
    val navigateBack by rememberUpdatedState(onNavigateBack)
    fun revokeEditor() {
        editorRevoked = true
        title = ""; text = ""; notes = ""; password = ""; recipients = listOf("")
        selectedFileUri = null; selectedFileMeta = null; storedDraft = null
        scope.coroutineContext.cancelChildren()
        leaveForeground()
        navigateBack()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !filePickerPending && !editorRevoked) revokeEditor()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(appUnlocked, appLockGeneration) {
        if (!appUnlocked || appLockGeneration != initialLockGeneration) revokeEditor()
    }
    LaunchedEffect(sendState) {
        if (sendState is BitwardenViewModel.SendState.Error || sendState is BitwardenViewModel.SendState.Warning) {
            storedDraft?.let { saved ->
                try { storedDraft = draftStore.load(saved.vaultId, saved.id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Existing encrypted copy remains on disk. */ }
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        filePickerPending = false
        if (uri != null && !editorRevoked && SessionManager.isUnlocked.value &&
            SessionManager.lockGeneration.value == initialLockGeneration) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            selectedFileUri = uri
            selectedFileMeta = AttachmentUriMetadata.resolve(context, uri)
        }
    }

    val creating = sendState is BitwardenViewModel.SendState.Creating
    val isSubmitting = submitRequested || creating || draftSaving
    val availableVaults = remember(vaults, activeVault, unlockStateByVault, sourceSend, storedDraft?.vaultId) {
        val knownVaults = if (vaults.isNotEmpty()) vaults else listOfNotNull(activeVault)
        knownVaults.filter { vault ->
            (sourceSend == null || vault.id == sourceSend.vaultId) &&
                (storedDraft == null || (vault.id == storedDraft?.vaultId &&
                    storedDraft?.account == takagi.ru.monica.bitwarden.service.BitwardenSendAccountIdentity.from(vault))) &&
                (initialSourceIdentity == null || initialSourceIdentity == takagi.ru.monica.bitwarden.service.BitwardenSendAccountIdentity.from(vault)) &&
                (unlockStateByVault[vault.id] == BitwardenViewModel.UnlockState.Unlocked ||
                (vault.id == activeVault?.id && unlockStateByVault[vault.id] == null))
        }
    }
    LaunchedEffect(availableVaults, selectedVaultId) {
        if ((sourceSend != null || storedDraft != null) && availableVaults.isEmpty()) {
            revokeEditor()
            return@LaunchedEffect
        }
        if (selectedVaultId == null || availableVaults.none { it.id == selectedVaultId }) {
            selectedVaultId = availableVaults.firstOrNull { it.id == activeVault?.id }?.id
                ?: availableVaults.firstOrNull()?.id
        }
    }
    LaunchedEffect(selectedFileMeta?.fileName) {
        if (sendType == SendCreateType.File && title.isBlank()) {
            title = selectedFileMeta?.fileName.orEmpty()
        }
    }
    LaunchedEffect(sendCreateSuccessVersion, submitRequested) {
        if (!submitRequested) return@LaunchedEffect
        if (sendCreateSuccessVersion != submitSuccessBaseline) {
            submitRequested = false
            submitStarted = false
            submitSuccessBaseline = sendCreateSuccessVersion
            onNavigateBack()
        }
    }
    LaunchedEffect(sendState, submitRequested, submitStarted) {
        if (!submitRequested) return@LaunchedEffect
        when (sendState) {
            is BitwardenViewModel.SendState.Creating -> {
                submitStarted = true
            }
            is BitwardenViewModel.SendState.Idle -> {
                if (submitStarted && sendCreateSuccessVersion != submitSuccessBaseline) {
                    submitRequested = false
                    submitStarted = false
                    submitSuccessBaseline = sendCreateSuccessVersion
                    onNavigateBack()
                }
            }
            is BitwardenViewModel.SendState.Error,
            is BitwardenViewModel.SendState.Warning,
            is BitwardenViewModel.SendState.Locked -> {
                submitRequested = false
                submitStarted = false
            }
            else -> Unit
        }
    }

    val selectedVault = availableVaults.firstOrNull { it.id == selectedVaultId }
    val fileSendAllowed = remember(selectedVault?.id, selectedVault?.serverUrl) {
        val vault = selectedVault ?: return@remember false
        val official = BitwardenApiFactory.isOfficialServer(vault.serverUrl) ||
            BitwardenApiFactory.isOfficialEuServer(vault.serverUrl)
        !official || BitwardenVaultPremiumStore.isPremium(context, vault.id)
    }

    BackHandler(enabled = isSubmitting) {}

    val draft = BitwardenSendDraft(title, text, notes.takeUnless { it.isEmpty() },
        passwordAction = when { removePassword -> SendPasswordAction.REMOVE; password.isNotEmpty() -> SendPasswordAction.REPLACE; else -> SendPasswordAction.KEEP },
        password = password.takeUnless { it.isEmpty() }, maxAccessCount = maxAccessCount.toIntOrNull(),
        hideEmail = hideEmail, hiddenText = hiddenText, disabled = disabled,
        deletionMillis = deletionMillis, expirationMillis = expirationMillis,
        authAction = if (sourceSend?.hasPassword == true) {
            if (removePassword) SendAuthAction.NONE else SendAuthAction.KEEP
        } else authMode,
        recipientEmails = if (authMode == SendAuthAction.EMAIL) recipients else emptyList())
    val datesValid = (onSaveDraft == null && onSubmitStoredDraft == null) || runCatching { draft.validate(System.currentTimeMillis(), sendType == SendCreateType.Text) }.isSuccess
    val accessLimitValid = maxAccessCount.isEmpty() || (maxAccessCount.toIntOrNull()?.let { it > 0 } == true)
    val canSave = !isSubmitting && storedDraft?.submissionUncertain != true && selectedVault != null && title.isNotBlank() && datesValid && accessLimitValid && when (sendType) {
        SendCreateType.Text -> text.isNotEmpty()
        // fileSendAllowed 已经在文件选择按钮的 enabled 上做了门控，
        // 如果用户能选到文件说明已经允许。这里不再重复检查，避免
        // remember 缓存时序问题导致按钮灰色。
        SendCreateType.File -> sourceSend?.isFileType == true || (selectedFileUri != null && selectedFileMeta != null) || storedDraft?.file != null
    }
    val sendTopBarTitle = stringResource(if (sourceSend == null) R.string.send_create_title else R.string.send_edit_title)
    val sections = rememberItemEditorSections()

    suspend fun persistDraft(): BitwardenSendDraftStore.Draft {
        val target = requireNotNull(selectedVault)
        return draftStore.save(target.id, draft, sourceSend, storedDraft?.id,
            selectedFileUri.takeIf { sendType == SendCreateType.File }, selectedFileMeta?.fileName,
            keepExistingFile = sendType == SendCreateType.File,
            type = if (sendType == SendCreateType.File) BitwardenSend.TYPE_FILE else BitwardenSend.TYPE_TEXT,
            accessLimitInput = maxAccessCount).also { storedDraft = it; selectedFileUri = null }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(sendTopBarTitle, style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, enabled = !isSubmitting) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                modifier = Modifier.testTag("send_editor_save").semantics { if (!canSave) disabled() },
                onClick = {
                    if (!canSave) return@FloatingActionButton
                    submitRequested = true
                    submitStarted = false
                    submitSuccessBaseline = sendCreateSuccessVersion
                    val targetVaultId = selectedVault?.id ?: return@FloatingActionButton
                    if (onSubmitStoredDraft != null) {
                        draftSaving = true
                        scope.launch {
                            try { onSubmitStoredDraft(persistDraft()) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                submitRequested = false
                                snackbar.showSnackbar(context.getString(R.string.bw_send_draft_failed))
                            } finally { draftSaving = false }
                        }
                    } else if (onSaveDraft != null) {
                        onSaveDraft(targetVaultId, draft, selectedFileUri, selectedFileMeta?.fileName)
                    } else if (sendType == SendCreateType.File) {
                        val uri = selectedFileUri ?: return@FloatingActionButton
                        val meta = selectedFileMeta ?: return@FloatingActionButton
                        onCreateFile(
                            targetVaultId,
                            title.trim(),
                            uri,
                            meta.fileName,
                            notes.takeIf { it.isNotBlank() }?.trim(),
                            password.takeIf { it.isNotBlank() }?.trim(),
                            maxAccessCount.toIntOrNull(),
                            hideEmail,
                            expireDaysText.toIntOrNull()?.coerceIn(1, 30) ?: 7
                        )
                    } else {
                        onCreate(
                            targetVaultId,
                            title.trim(),
                            text.trim(),
                            notes.takeIf { it.isNotBlank() }?.trim(),
                            password.takeIf { it.isNotBlank() }?.trim(),
                            maxAccessCount.toIntOrNull(),
                            hideEmail,
                            hiddenText,
                            expireDaysText.toIntOrNull()?.coerceIn(1, 30) ?: 7
                        )
                    }
                },
                containerColor = if (canSave) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (canSave) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            ) {
                if (creating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save))
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isSubmitting) {
                    StateBanner(stringResource(R.string.legacy_ui_send_creating))
                }
                if (sendState is BitwardenViewModel.SendState.Error) StateBanner(sendState.message)
                if (!datesValid || !accessLimitValid) StateBanner(stringResource(R.string.send_edit_invalid))

                TemplateFormSection(title = stringResource(R.string.send_account_section_title)) {
                    if (availableVaults.isEmpty()) {
                        StateBanner(stringResource(R.string.send_account_locked_hint))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            availableVaults.forEach { vault ->
                                SendVaultChoiceRow(
                                    vault = vault,
                                    selected = vault.id == selectedVaultId,
                                    enabled = !isSubmitting,
                                    onClick = { if (!isSubmitting) selectedVaultId = vault.id }
                                )
                            }
                        }
                    }
                }

                TemplateFormSection(title = stringResource(R.string.send_type_section_title)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                selected = sendType == SendCreateType.Text,
                                enabled = sourceSend == null && !isSubmitting,
                                onClick = { sendType = SendCreateType.Text },
                                label = { Text(stringResource(R.string.send_type_text)) },
                                leadingIcon = if (sendType == SendCreateType.Text) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                } else null
                            )
                            FilterChip(
                                selected = sendType == SendCreateType.File,
                                enabled = sourceSend == null && fileSendAllowed && !isSubmitting,
                                onClick = { sendType = SendCreateType.File },
                                label = { Text(stringResource(R.string.send_type_file)) },
                                leadingIcon = if (sendType == SendCreateType.File) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                } else null
                            )
                        }
                        if (!fileSendAllowed && sourceSend == null) {
                            StateBanner(stringResource(R.string.send_file_premium_required_hint))
                        }
                    }
                }

                ItemEditorIdentity(title, { title = it }, stringResource(R.string.title),
                    Icons.AutoMirrored.Filled.Send, enabled = !isSubmitting)

                TemplateFormSection(
                    title = if (sendType == SendCreateType.File) {
                        stringResource(R.string.send_file_send_heading)
                    } else {
                        stringResource(R.string.send_text_send_heading)
                    }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                        if (sendType == SendCreateType.File) {
                            OutlinedButton(
                                onClick = {
                                    filePickerPending = true
                                    try { filePicker.launch(arrayOf("*/*")) }
                                    catch (error: Exception) { filePickerPending = false; throw error }
                                },
                                enabled = sourceSend == null && fileSendAllowed && !creating,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(sourceSend?.fileName ?: selectedFileMeta?.fileName ?: storedDraft?.file?.name ?: stringResource(R.string.send_select_file))
                            }
                            selectedFileMeta?.let { meta ->
                                Text(
                                    text = stringResource(R.string.send_selected_file_meta, meta.fileName, formatFileSize(meta.sizeBytes)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            OutlinedTextField(
                                value = text,
                                enabled = !isSubmitting,
                                onValueChange = { text = it },
                                label = { Text(stringResource(R.string.send_content_label)) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 130.dp)
                            )
                        }

                    }
                }
                ItemEditorOptionalSection(sections, "notes", sourceSend != null || notes.isNotEmpty()) {
                    TemplateFormSection(stringResource(R.string.notes)) {
                        OutlinedTextField(
                            value = notes,
                            enabled = !isSubmitting,
                            onValueChange = { notes = it },
                            label = { Text(stringResource(R.string.notes_optional)) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (onSaveDraft != null || onSubmitStoredDraft != null) SendAuthenticationFields(sourceSend, authMode, password, recipients, removePassword,
                    premium = selectedVault?.let { BitwardenVaultPremiumStore.isPremium(context, it.id) } == true,
                    enabled = !isSubmitting && storedDraft?.submissionUncertain != true,
                    onMode = { authMode = it; if (it != SendAuthAction.PASSWORD) password = "" },
                    onPassword = { password = it }, onRecipients = { recipients = it },
                    onRemovePassword = { removePassword = it; password = "" })

                ItemEditorOptionalSection(sections, "options", sourceSend != null) {
                    TemplateFormSection(title = stringResource(R.string.send_options_section_title)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = maxAccessCount,
                                    enabled = !isSubmitting,
                                    onValueChange = { maxAccessCount = it.filter(Char::isDigit) },
                                    label = { Text(stringResource(R.string.send_max_access_count)) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (onSaveDraft == null && onSubmitStoredDraft == null) OutlinedTextField(
                                    value = expireDaysText,
                                    enabled = !isSubmitting,
                                    onValueChange = { expireDaysText = it.filter(Char::isDigit) },
                                    label = { Text(stringResource(R.string.send_valid_days)) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (sourceSend != null) {
                                SendSwitchRow(title = stringResource(R.string.enabled), description = "",
                                    checked = !disabled, enabled = !isSubmitting, onCheckedChange = { disabled = !it })
                            }
                            if (onSaveDraft != null || onSubmitStoredDraft != null) {
                                SendDateTimeField(stringResource(R.string.send_deletion_date), deletionMillis, !isSubmitting) { deletionMillis = it }
                                SendSwitchRow(title = stringResource(R.string.send_no_expiration), description = "",
                                    checked = expirationMillis == null, enabled = !isSubmitting,
                                    onCheckedChange = { expirationMillis = if (it) null else minOf(deletionMillis, System.currentTimeMillis() + 7L * 86_400_000L) })
                                expirationMillis?.let { value ->
                                    SendDateTimeField(stringResource(R.string.send_expiration_date), value, !isSubmitting) { expirationMillis = it }
                                }
                            }

                            SendSwitchRow(
                                title = stringResource(R.string.send_hide_email),
                                description = stringResource(R.string.send_hide_email_desc),
                                checked = hideEmail,
                                enabled = !isSubmitting,
                                onCheckedChange = { hideEmail = it }
                            )

                            if (sendType == SendCreateType.Text) {
                                SendSwitchRow(
                                    title = stringResource(R.string.send_hide_text),
                                    description = stringResource(R.string.send_hide_text_desc),
                                    checked = hiddenText,
                                    enabled = !isSubmitting,
                                    onCheckedChange = { hiddenText = it }
                                )
                            }
                        }
                    }
                }

                ItemEditorAddContent(sections, listOf(
                    ItemEditorContentOption("notes", R.string.notes, Icons.Default.Notes, sections.visible("notes", notes.isNotEmpty())),
                    ItemEditorContentOption("options", R.string.send_options_section_title, Icons.Default.Settings, sections.visible("options", sourceSend != null)),
                ), enabled = !isSubmitting)

                if (onSubmitStoredDraft != null) {
                    if (storedDraft?.submissionUncertain == true) {
                        StateBanner(stringResource(R.string.bw_send_draft_uncertain))
                        Button(onClick = {
                            storedDraft?.let { saved ->
                                submitRequested = true
                                submitSuccessBaseline = sendCreateSuccessVersion
                                onSubmitStoredDraft(saved)
                            }
                        }, enabled = !isSubmitting, modifier = Modifier.fillMaxWidth().testTag("send_draft_check")) {
                            Text(stringResource(R.string.bw_send_draft_reconcile))
                        }
                    } else {
                        OutlinedButton(onClick = {
                            draftSaving = true
                            scope.launch {
                                val message = try {
                                    persistDraft()
                                    context.getString(R.string.bw_send_draft_saved)
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { context.getString(R.string.bw_send_draft_failed) }
                                finally { draftSaving = false }
                                // Persistence has finished; a transient notice must not block Back or Submit.
                                snackbar.showSnackbar(message)
                            }
                        }, enabled = !isSubmitting && selectedVault != null,
                            modifier = Modifier.fillMaxWidth().testTag("send_draft_save")) {
                            Text(stringResource(R.string.bw_send_draft_save))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(112.dp))
            }
        }
    }
}

@Composable
private fun SendDateTimeField(label: String, millis: Long, enabled: Boolean, onChange: (Long) -> Unit) {
    val context = LocalContext.current
    val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val formatted = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(millis))
    val openPicker = {
        android.app.DatePickerDialog(context, { _, year, month, day ->
            android.app.TimePickerDialog(context, { _, hour, minute ->
                val selected = java.time.LocalDateTime.of(year, month + 1, day, hour, minute)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                onChange(selected)
            }, time.hour, time.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
        }, time.year, time.monthValue - 1, time.dayOfMonth).show()
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        ListItem(
            modifier = Modifier.clickable(enabled = enabled, onClick = openPicker),
            headlineContent = { Text(label, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant) },
            supportingContent = { Text(formatted, style = MaterialTheme.typography.bodyLarge) },
            leadingContent = { Icon(Icons.Default.Event, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
    }
}

@Composable
private fun SendVaultChoiceRow(
    vault: BitwardenVault,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val displayName = vault.displayName?.takeIf { it.isNotBlank() && it != vault.email }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("send_vault_${vault.id}").semantics { this.selected = selected },
        shape = RoundedCornerShape(24.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Key, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName ?: vault.email,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                if (displayName != null) Text(
                    text = vault.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = vault.serverUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null)
            }
        }
    }
}

@Composable
private fun SendSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        ListItem(
            modifier = Modifier.fillMaxWidth()
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .semantics { contentDescription = title },
            headlineContent = { Text(title) },
            supportingContent = if (description.isNotEmpty()) {{ Text(description) }} else null,
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        )
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    message: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StateBanner(message: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

private fun formatDate(raw: String): String {
    return try {
        val instant = Instant.parse(raw)
        val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
        formatter.format(instant)
    } catch (_: Exception) {
        raw
    }
}

private fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes < 0) return "unknown size"
    if (sizeBytes < 1024) return "$sizeBytes B"
    val units = listOf("KB", "MB", "GB")
    var size = sizeBytes.toDouble() / 1024.0
    var index = 0
    while (size >= 1024.0 && index < units.lastIndex) {
        size /= 1024.0
        index += 1
    }
    return "%.1f %s".format(java.util.Locale.US, size, units[index])
}
