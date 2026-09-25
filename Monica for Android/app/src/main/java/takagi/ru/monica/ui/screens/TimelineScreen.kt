package takagi.ru.monica.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.ui.common.selection.SelectionActionBar
import takagi.ru.monica.ui.components.SwipeableAddFab
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.areTimelineSnapshotFieldsReversible
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.TimelineBranch
import takagi.ru.monica.data.model.DiffChange
import takagi.ru.monica.data.model.TimelineEvent
import takagi.ru.monica.ui.components.DiffComparisonSheet
import takagi.ru.monica.ui.components.ExpressiveTopBar
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenu
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuDropdown
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuOffset
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import takagi.ru.monica.ui.components.formatRelativeTime
import takagi.ru.monica.ui.components.formatShortTime
import takagi.ru.monica.viewmodel.TimelineViewModel
import takagi.ru.monica.ui.components.TrashSettingsSheet
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.TrashViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 历史/回收站 Tab 枚举
 */
enum class HistoryTab {
    TIMELINE,  // 操作历史
    TRASH      // 回收站
}

/**
 * 聚合后的时间线组 - 用于按日期和类型聚合
 */
data class TimelineGroup(
    val dateLabel: String,        // 日期标签：今天、昨天、本周等
    val items: List<TimelineDisplayItem>  // 组内的条目
)

/**
 * 时间线显示条目 - 可以是单个事件或聚合事件
 */
sealed class TimelineDisplayItem {
    data class Single(val event: TimelineEvent.StandardLog) : TimelineDisplayItem()
    data class Aggregated(
        val operationType: String,
        val itemType: String,
        val events: List<TimelineEvent.StandardLog>,
        val firstTimestamp: Long,
        val lastTimestamp: Long
    ) : TimelineDisplayItem()
    data class Conflict(val event: TimelineEvent.ConflictBranch) : TimelineDisplayItem()
}

/**
 * 时间线主屏幕 - 高级现代设计
 */
@Composable
fun TimelineScreen(
    viewModel: TimelineViewModel = viewModel(),
    trashViewModel: TrashViewModel = viewModel(),
    onLogSelected: (TimelineEvent.StandardLog) -> Unit = {},
    splitPaneMode: Boolean = false,
    initialTab: HistoryTab = HistoryTab.TIMELINE,
    initialTrashScopeKey: String? = null,
    enableTabSwitch: Boolean = true,
    showBackButton: Boolean = false,
    onNavigateBack: () -> Unit = {},
    showReturnFab: Boolean = true,
    onTrashSelectionModeChange: (Boolean) -> Unit = {}
) {
    if (splitPaneMode) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                SplitPaneHeader(
                    title = stringResource(R.string.timeline_title),
                    subtitle = stringResource(R.string.timeline_subtitle),
                    icon = Icons.Default.History
                )
                TimelineContent(
                    viewModel = viewModel,
                    onLogSelected = onLogSelected,
                    embeddedInSplitPane = true
                )
            }

            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                SplitPaneHeader(
                    title = stringResource(R.string.timeline_trash_title),
                    subtitle = stringResource(R.string.timeline_trash_subtitle),
                    icon = Icons.Default.Delete
                )
                TrashContent(
                    viewModel = trashViewModel,
                    embeddedInSplitPane = true,
                    initialSelectedScopeKey = initialTrashScopeKey,
                    onSelectionModeChange = onTrashSelectionModeChange
                )
            }
        }
        return
    }

    var currentTab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
    var isTrashSelectionMode by remember { mutableStateOf(false) }
    val hideLegacyTopBar = !enableTabSwitch

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!hideLegacyTopBar) {
                // M3E 风格的顶部标题栏
                HistoryTopBar(
                    currentTab = currentTab,
                    onTabSelected = { selectedTab ->
                        if (enableTabSwitch) {
                            currentTab = selectedTab
                        }
                    },
                    enableTabSwitch = enableTabSwitch,
                    showBackButton = showBackButton,
                    onNavigateBack = onNavigateBack
                )
            }

            // 内容区域，带有切换动画
            AnimatedContent(
                targetState = currentTab,
                label = "HistoryTabContent",
                transitionSpec = {
                    (fadeIn(animationSpec = tween(300))).togetherWith(fadeOut(animationSpec = tween(300)))
                },
                modifier = Modifier.weight(1f)
            ) { targetTab ->
                when (targetTab) {
                    HistoryTab.TIMELINE -> TimelineContent(
                        viewModel = viewModel,
                        onLogSelected = onLogSelected,
                        showStandaloneHeader = !enableTabSwitch
                    )
                    HistoryTab.TRASH -> TrashContent(
                        viewModel = trashViewModel,
                        embeddedInSplitPane = false,
                        initialSelectedScopeKey = initialTrashScopeKey,
                        hasReturnAction = !enableTabSwitch && showBackButton,
                        onSelectionModeChange = { selecting ->
                            isTrashSelectionMode = selecting
                            onTrashSelectionModeChange(selecting)
                        }
                    )
                }
            }
        }
        if (showReturnFab && !enableTabSwitch && showBackButton && !isTrashSelectionMode) {
            SwipeableAddFab(
                fabBottomOffset = 24.dp,
                onClick = onNavigateBack,
                fabContent = {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            )
        }
    }
}

@Composable
private fun SplitPaneHeader(
    title: String,
    icon: ImageVector,
    subtitle: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    }
}

/**
 * 历史页面顶栏 - 精致的玻璃态设计
 */
@Composable
private fun HistoryTopBar(
    currentTab: HistoryTab,
    onTabSelected: (HistoryTab) -> Unit,
    enableTabSwitch: Boolean,
    showBackButton: Boolean,
    onNavigateBack: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBackButton) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back)
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(48.dp))
            }

            if (enableTabSwitch) {
                // 右侧胶囊形切换器 - 更精致的设计
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                    tonalElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        HistoryPillTabItem(
                            selected = currentTab == HistoryTab.TIMELINE,
                            onClick = { onTabSelected(HistoryTab.TIMELINE) },
                            icon = Icons.Default.History,
                            contentDescription = stringResource(R.string.timeline_subtitle)
                        )
                        HistoryPillTabItem(
                            selected = currentTab == HistoryTab.TRASH,
                            onClick = { onTabSelected(HistoryTab.TRASH) },
                            icon = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.timeline_trash_title)
                        )
                    }
                }
            } else {
                Text(
                    text = when (currentTab) {
                        HistoryTab.TIMELINE -> stringResource(R.string.timeline_title)
                        HistoryTab.TRASH -> stringResource(R.string.timeline_trash_title)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * 胶囊形 Tab 项 - 更精致的设计
 */
@Composable
private fun HistoryPillTabItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String
) {
    val colorScheme = MaterialTheme.colorScheme
    val backgroundColor = if (selected) colorScheme.primary else Color.Transparent
    val contentColor = if (selected) colorScheme.onPrimary else colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 将时间线事件按日期分组并聚合相同类型的连续操作
 */
@Composable
private fun groupAndAggregateEvents(events: List<TimelineEvent>): List<TimelineGroup> {
    if (events.isEmpty()) return emptyList()
    
    val calendar = Calendar.getInstance()
    val today = calendar.apply { 
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    
    val yesterday = today - 24 * 60 * 60 * 1000
    val thisWeekStart = today - calendar.get(Calendar.DAY_OF_WEEK) * 24 * 60 * 60 * 1000L
    
    // 按日期分组
    val groupedByDate = events.groupBy { event ->
        val timestamp = when (event) {
            is TimelineEvent.StandardLog -> event.timestamp
            is TimelineEvent.ConflictBranch -> event.ancestor.timestamp
        }
        when {
            timestamp >= today -> stringResource(R.string.timeline_date_today)
            timestamp >= yesterday -> stringResource(R.string.timeline_date_yesterday)
            timestamp >= thisWeekStart -> stringResource(R.string.timeline_date_this_week)
            else -> {
                val sdf = SimpleDateFormat(stringResource(R.string.timeline_date_month_day), Locale.getDefault())
                sdf.format(Date(timestamp))
            }
        }
    }
    
    return groupedByDate.map { (dateLabel, dateEvents) ->
        val displayItems = mutableListOf<TimelineDisplayItem>()
        var i = 0
        
        while (i < dateEvents.size) {
            val event = dateEvents[i]
            
            when (event) {
                is TimelineEvent.ConflictBranch -> {
                    displayItems.add(TimelineDisplayItem.Conflict(event))
                    i++
                }
                is TimelineEvent.StandardLog -> {
                    // 查找连续的相同类型操作（用于聚合）
                    val sameTypeEvents = mutableListOf(event)
                    var j = i + 1
                    
                    // 聚合 WebDAV 同步操作、新建操作或连续的相同操作类型
                    val shouldAggregate = event.itemType in listOf("WEBDAV_UPLOAD", "WEBDAV_DOWNLOAD") ||
                            event.operationType == "SYNC" ||
                            event.operationType == "CREATE"
                    
                    if (shouldAggregate) {
                        while (j < dateEvents.size) {
                            val nextEvent = dateEvents[j]
                            if (nextEvent is TimelineEvent.StandardLog &&
                                nextEvent.operationType == event.operationType &&
                                nextEvent.itemType == event.itemType
                            ) {
                                sameTypeEvents.add(nextEvent)
                                j++
                            } else {
                                break
                            }
                        }
                    }
                    
                    if (sameTypeEvents.size >= 3) {
                        // 聚合显示
                        displayItems.add(
                            TimelineDisplayItem.Aggregated(
                                operationType = event.operationType,
                                itemType = event.itemType,
                                events = sameTypeEvents,
                                firstTimestamp = sameTypeEvents.last().timestamp,
                                lastTimestamp = sameTypeEvents.first().timestamp
                            )
                        )
                        i = j
                    } else {
                        // 单独显示
                        displayItems.add(TimelineDisplayItem.Single(event))
                        i++
                    }
                }
            }
        }
        
        TimelineGroup(dateLabel = dateLabel, items = displayItems)
    }
}

/**
 * 操作历史内容 - 全新的高级设计
 */
@Composable
private fun TimelineContent(
    viewModel: TimelineViewModel,
    onLogSelected: (TimelineEvent.StandardLog) -> Unit,
    embeddedInSplitPane: Boolean = false,
    showStandaloneHeader: Boolean = false
) {
    val timelineEvents by viewModel.timelineEvents.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val maintenanceRestoreMessage by viewModel.maintenanceRestoreMessage.collectAsState()
    val context = LocalContext.current
    val database = remember(context) { PasswordDatabase.getDatabase(context.applicationContext) }
    val bitwardenVaults by database.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
    val keepassDatabases by database.localKeePassDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val mdbxDatabases by database.localMdbxDatabaseDao().getAvailableDatabases().collectAsState(initial = emptyList())
    val activePasswordEntries by database.passwordEntryDao().getAllPasswordEntries().collectAsState(initial = emptyList())
    val deletedPasswordEntries by database.passwordEntryDao().getDeletedEntries().collectAsState(initial = emptyList())
    val activeSecureItems by database.secureItemDao().getAllItems().collectAsState(initial = emptyList())
    val deletedSecureItems by database.secureItemDao().getDeletedItems().collectAsState(initial = emptyList())
    val passkeys by database.passkeyDao().getAllPasskeys().collectAsState(initial = emptyList())

    val allLabel = stringResource(R.string.filter_all)
    val localLabel = stringResource(R.string.filter_monica)
    val bitwardenLabel = stringResource(R.string.filter_bitwarden)
    val keepassLabel = stringResource(R.string.filter_keepass)

    val scopeOptions = remember(
        allLabel,
        localLabel,
        bitwardenLabel,
        keepassLabel,
        bitwardenVaults,
        keepassDatabases,
        mdbxDatabases
    ) {
        buildList {
            add(
                TrashScopeFilterOption(
                    key = TrashScopeFilter.All.key,
                    label = allLabel,
                    scope = TrashScopeFilter.All
                )
            )
            add(
                TrashScopeFilterOption(
                    key = TrashScopeFilter.Local.key,
                    label = localLabel,
                    scope = TrashScopeFilter.Local
                )
            )
            bitwardenVaults.forEach { vault ->
                val displayName = vault.displayName?.takeIf { it.isNotBlank() } ?: vault.email
                add(
                    TrashScopeFilterOption(
                        key = TrashScopeFilter.BitwardenVaultScope(vault.id).key,
                        label = "$bitwardenLabel · $displayName",
                        scope = TrashScopeFilter.BitwardenVaultScope(vault.id)
                    )
                )
            }
            keepassDatabases.forEach { keepass ->
                add(
                    TrashScopeFilterOption(
                        key = TrashScopeFilter.KeePassDatabaseScope(keepass.id).key,
                        label = "$keepassLabel · ${keepass.name}",
                        scope = TrashScopeFilter.KeePassDatabaseScope(keepass.id)
                    )
                )
            }
            mdbxDatabases.forEach { mdbx ->
                add(
                    TrashScopeFilterOption(
                        key = TrashScopeFilter.MdbxDatabaseScope(mdbx.id).key,
                        label = "MDBX · ${mdbx.name}",
                        scope = TrashScopeFilter.MdbxDatabaseScope(mdbx.id)
                    )
                )
            }
        }
    }

    var selectedScopeKey by rememberSaveable { mutableStateOf(TrashScopeFilter.All.key) }
    var showScopeSelectionSheet by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }

    val selectedScope = scopeOptions.firstOrNull { it.key == selectedScopeKey }?.scope ?: TrashScopeFilter.All

    LaunchedEffect(scopeOptions) {
        if (scopeOptions.none { it.key == selectedScopeKey }) {
            selectedScopeKey = scopeOptions.firstOrNull()?.key ?: TrashScopeFilter.All.key
        }
    }

    val passwordScopeById = remember(activePasswordEntries, deletedPasswordEntries) {
        buildMap<Long, TrashScopeFilter> {
            (activePasswordEntries + deletedPasswordEntries).forEach { entry ->
                put(
                    entry.id,
                    resolveScopeFilter(
                        bitwardenVaultId = entry.bitwardenVaultId,
                        keepassDatabaseId = entry.keepassDatabaseId,
                        mdbxDatabaseId = entry.mdbxDatabaseId
                    )
                )
            }
        }
    }
    val secureItemScopeById = remember(activeSecureItems, deletedSecureItems) {
        buildMap<Long, TrashScopeFilter> {
            (activeSecureItems + deletedSecureItems).forEach { item ->
                put(
                    item.id,
                    resolveScopeFilter(
                        bitwardenVaultId = item.bitwardenVaultId,
                        keepassDatabaseId = item.keepassDatabaseId,
                        mdbxDatabaseId = item.mdbxDatabaseId
                    )
                )
            }
        }
    }
    val passkeyScopeById = remember(passkeys) {
        passkeys.associate { passkey ->
            passkey.id to resolveScopeFilter(
                bitwardenVaultId = passkey.bitwardenVaultId,
                keepassDatabaseId = passkey.keepassDatabaseId,
                mdbxDatabaseId = passkey.mdbxDatabaseId
            )
        }
    }
    val passwordTitleById = remember(activePasswordEntries, deletedPasswordEntries) {
        (activePasswordEntries + deletedPasswordEntries).associate { it.id to it.title }
    }
    val secureItemTitleById = remember(activeSecureItems, deletedSecureItems) {
        (activeSecureItems + deletedSecureItems).associate { it.id to it.title }
    }
    val passwordSnapshotRestoreAllowedById = remember(activePasswordEntries, deletedPasswordEntries) {
        (activePasswordEntries + deletedPasswordEntries).associate {
            it.id to (it.keepassDatabaseId == null)
        }
    }
    val secureSnapshotRestoreAllowedById = remember(activeSecureItems, deletedSecureItems) {
        (activeSecureItems + deletedSecureItems).associate {
            it.id to (it.keepassDatabaseId == null)
        }
    }
    val passkeyTitleById = remember(passkeys) {
        passkeys.associate { it.id to it.displayTitle() }
    }

    val itemTypeLabels = mapOf(
        "PASSWORD" to stringResource(R.string.timeline_item_password),
        "TOTP" to stringResource(R.string.timeline_item_authenticator),
        "PASSKEY" to stringResource(R.string.passkey_title),
        "BANK_CARD" to stringResource(R.string.timeline_item_card),
        "NOTE" to stringResource(R.string.timeline_item_note),
        "DOCUMENT" to stringResource(R.string.timeline_item_document),
        "BILLING_ADDRESS" to stringResource(R.string.billing_address),
        "PAYMENT_ACCOUNT" to stringResource(R.string.payment_account),
        "CATEGORY" to stringResource(R.string.category),
        "KEEPASS_DATABASE" to stringResource(R.string.database_source_keepass),
        "KEEPASS_GROUP" to stringResource(R.string.v2_keepass_groups),
        "BITWARDEN_SEND" to stringResource(R.string.send_screen_title),
        "BITWARDEN_SYNC" to stringResource(R.string.sync_backup_bitwarden_sync_title),
        "BITWARDEN_CONFLICT" to stringResource(R.string.sync_conflict),
        "WEBDAV_UPLOAD" to stringResource(R.string.timeline_item_cloud_backup),
        "WEBDAV_DOWNLOAD" to stringResource(R.string.timeline_item_cloud_restore)
    )

    var selectedBranch by remember { mutableStateOf<TimelineBranch?>(null) }
    var selectedLog by remember { mutableStateOf<TimelineEvent.StandardLog?>(null) }
    var selectedSnapshotChanges by remember { mutableStateOf<List<DiffChange>?>(null) }
    var selectedSnapshotLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(selectedLog?.id) {
        selectedSnapshotChanges = null
        selectedSnapshotLoaded = false
        val log = selectedLog
        if (log?.hasEncryptedSnapshot == true) {
            selectedSnapshotChanges = viewModel.readEncryptedSnapshot(log.id)
            selectedSnapshotLoaded = true
        }
    }

    val colorScheme = MaterialTheme.colorScheme

    val scopedTimelineEvents = remember(
        timelineEvents,
        selectedScope,
        passwordScopeById,
        secureItemScopeById,
        passkeyScopeById
    ) {
        timelineEvents.filter { event ->
            matchesTimelineScope(
                event = event,
                selectedScope = selectedScope,
                passwordScopeById = passwordScopeById,
                secureItemScopeById = secureItemScopeById,
                passkeyScopeById = passkeyScopeById
            )
        }
    }
    val displayTimelineEvents = remember(
        scopedTimelineEvents,
        passwordTitleById,
        secureItemTitleById,
        passkeyTitleById,
        passwordSnapshotRestoreAllowedById,
        secureSnapshotRestoreAllowedById,
        itemTypeLabels
    ) {
        scopedTimelineEvents.map { event ->
            when (event) {
                is TimelineEvent.StandardLog -> {
                    val currentTitle = when (event.itemType) {
                        "PASSWORD" -> passwordTitleById[event.itemId]
                        "TOTP", "BANK_CARD", "NOTE", "DOCUMENT", "BILLING_ADDRESS", "PAYMENT_ACCOUNT" ->
                            secureItemTitleById[event.itemId]
                        "PASSKEY" -> passkeyTitleById[event.itemId]
                        else -> null
                    }
                    val providerRestoreAllowed = when (event.itemType) {
                        "PASSWORD" -> passwordSnapshotRestoreAllowedById[event.itemId] == true
                        "TOTP", "BANK_CARD", "NOTE", "DOCUMENT", "BILLING_ADDRESS", "PAYMENT_ACCOUNT" ->
                            secureSnapshotRestoreAllowedById[event.itemId] == true
                        else -> false
                    }
                    event.copy(
                        summary = resolveTimelineDisplaySummary(
                            log = event,
                            currentTitle = currentTitle,
                            genericTypeLabel = itemTypeLabels[event.itemType]
                                ?: itemTypeLabels.getValue("DOCUMENT")
                        ),
                        canRevert = event.canRevert && providerRestoreAllowed,
                        canSaveOldAsNew = event.canSaveOldAsNew && providerRestoreAllowed
                    )
                }
                is TimelineEvent.ConflictBranch -> event
            }
        }
    }
    val visibleTimelineEvents = remember(displayTimelineEvents, searchQuery) {
        val keyword = searchQuery.trim()
        if (keyword.isBlank()) {
            displayTimelineEvents
        } else {
            displayTimelineEvents.filter { event -> matchesTimelineSearch(event, keyword) }
        }
    }
    val groups = groupAndAggregateEvents(visibleTimelineEvents)

    LaunchedEffect(maintenanceRestoreMessage) {
        val message = maintenanceRestoreMessage
        if (!message.isNullOrBlank()) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.consumeMaintenanceRestoreMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
    ) {
        if (!embeddedInSplitPane && showStandaloneHeader) {
            TimelineHeaderBar(
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                isSearchExpanded = isSearchExpanded,
                onSearchExpandedChange = { expanded ->
                    isSearchExpanded = expanded
                    if (!expanded) {
                        searchQuery = ""
                    }
                },
                onOpenScopeSheet = { showScopeSelectionSheet = true },
                scopeMenu = {
                    TrashScopeFilterChipMenu(
                        expanded = showScopeSelectionSheet,
                        onDismissRequest = { showScopeSelectionSheet = false },
                        selectedScope = selectedScope,
                        fallbackScope = TrashScopeFilter.All,
                        keepassDatabases = keepassDatabases,
                        mdbxDatabases = mdbxDatabases,
                        bitwardenVaults = bitwardenVaults,
                        database = database,
                        onSelectedScopeKeyChange = { selectedScopeKey = it }
                    )
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .background(colorScheme.background)
        ) {
            if (isLoading && visibleTimelineEvents.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (visibleTimelineEvents.isEmpty()) {
                EmptyTimelineState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = 100.dp,
                        start = 16.dp,
                        end = 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    groups.forEach { group ->
                        item(key = "header_${group.dateLabel}") {
                            DateSectionHeader(dateLabel = group.dateLabel)
                        }

                        items(
                            items = group.items,
                            key = { item ->
                                when (item) {
                                    is TimelineDisplayItem.Single -> "single_${item.event.id}"
                                    is TimelineDisplayItem.Aggregated -> "agg_${item.itemType}_${item.firstTimestamp}"
                                    is TimelineDisplayItem.Conflict -> "conflict_${item.event.ancestor.id}"
                                }
                            }
                        ) { item ->
                            when (item) {
                                is TimelineDisplayItem.Single -> {
                                    ModernLogItem(
                                        log = item.event,
                                        onClick = {
                                            selectedLog = item.event
                                            onLogSelected(item.event)
                                        }
                                    )
                                }
                                is TimelineDisplayItem.Aggregated -> {
                                    AggregatedLogItem(
                                        aggregated = item,
                                        onItemClick = {
                                            selectedLog = it
                                            onLogSelected(it)
                                        }
                                    )
                                }
                                is TimelineDisplayItem.Conflict -> {
                                    ConflictBranchItem(
                                        conflict = item.event,
                                        isFirst = false,
                                        isLast = false,
                                        onBranchClick = { selectedBranch = it }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Diff 比较底部弹窗
    selectedBranch?.let { branch ->
        DiffComparisonSheet(
            branch = branch,
            onDismiss = { selectedBranch = null },
            onRestoreVersion = {
                viewModel.restoreVersion(branch)
                selectedBranch = null
            },
            onSaveAsNewEntry = {
                viewModel.saveAsNewEntry(branch)
                selectedBranch = null
            }
        )
    }

    selectedLog?.let { log ->
        StandardLogDetailSheet(
            log = log,
            snapshotChanges = selectedSnapshotChanges,
            snapshotLoaded = selectedSnapshotLoaded,
            onDismiss = { selectedLog = null },
            onRevert = { 
                viewModel.revertEdit(log) { success ->
                    if (success) {
                        selectedLog = null
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.timeline_restore_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onSaveOldAsNew = {
                viewModel.saveOldDataAsNew(log) { success ->
                    if (success) {
                        selectedLog = null
                    }
                }
            }
        )
    }
}

@Composable
private fun TimelineHeaderBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    onOpenScopeSheet: () -> Unit,
    scopeMenu: @Composable () -> Unit = {}
) {
    ExpressiveTopBar(
        title = stringResource(R.string.timeline_title),
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        isSearchExpanded = isSearchExpanded,
        onSearchExpandedChange = onSearchExpandedChange,
        searchHint = stringResource(R.string.timeline_search_hint),
        actions = {
            Box {
                IconButton(onClick = onOpenScopeSheet) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = stringResource(R.string.category),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                scopeMenu()
            }
            IconButton(onClick = { onSearchExpandedChange(true) }) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(R.string.search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

/**
 * 日期分组标题 - 简洁现代风格
 */
@Composable
private fun DateSectionHeader(dateLabel: String) {
    val colorScheme = MaterialTheme.colorScheme
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dateLabel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colorScheme.primary,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.width(12.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = colorScheme.outlineVariant.copy(alpha = 0.5f),
            thickness = 1.dp
        )
    }
}

/**
 * 空状态显示 - 更现代的设计
 */
@Composable
private fun EmptyTimelineState() {
    val colorScheme = MaterialTheme.colorScheme
    
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            // 渐变背景的图标容器
            Surface(
                shape = CircleShape,
                color = colorScheme.primaryContainer.copy(alpha = 0.3f),
                modifier = Modifier.size(100.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = colorScheme.primary.copy(alpha = 0.6f),
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = stringResource(R.string.no_history_records),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = stringResource(R.string.timeline_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * 获取操作类型的图标
 */
@Composable
private fun getOperationIcon(operationType: String, itemType: String): ImageVector {
    // WebDAV 类型
    if (itemType == "WEBDAV_UPLOAD" || itemType == "WEBDAV_DOWNLOAD") {
        return Icons.Default.Cloud
    }
    return when (operationType) {
        "CREATE" -> Icons.Default.Add
        "UPDATE" -> Icons.Default.Edit
        "DELETE" -> Icons.Default.Delete
        "SYNC" -> Icons.Default.Sync
        else -> Icons.Default.History
    }
}

/**
 * 获取项目类型的图标
 */
@Composable
private fun getItemTypeIcon(itemType: String): ImageVector {
    return when (itemType) {
        "PASSWORD" -> Icons.Default.Key
        "TOTP" -> Icons.Default.History
        "PASSKEY" -> Icons.Default.Lock
        "BANK_CARD" -> Icons.Default.CreditCard
        "NOTE" -> Icons.Default.Note
        "DOCUMENT" -> Icons.Default.Description
        "BILLING_ADDRESS" -> Icons.Default.Home
        "PAYMENT_ACCOUNT" -> Icons.Default.AccountBalanceWallet
        "CATEGORY" -> Icons.Default.Folder
        "KEEPASS_DATABASE" -> Icons.Default.Storage
        "KEEPASS_GROUP" -> Icons.Default.AccountTree
        "BITWARDEN_SEND" -> Icons.Default.CloudUpload
        "BITWARDEN_SYNC" -> Icons.Default.Sync
        "BITWARDEN_CONFLICT" -> Icons.Default.History
        "WEBDAV_UPLOAD", "WEBDAV_DOWNLOAD" -> Icons.Default.CloudUpload
        else -> Icons.Default.Description
    }
}

/**
 * 获取操作类型的显示文本
 */
@Composable
private fun getOperationLabel(operationType: String): String {
    return when (operationType) {
        "CREATE" -> stringResource(R.string.timeline_op_create)
        "UPDATE" -> stringResource(R.string.timeline_op_update)
        "DELETE" -> stringResource(R.string.timeline_op_delete)
        "SYNC" -> stringResource(R.string.timeline_op_sync)
        else -> stringResource(R.string.timeline_op_default)
    }
}

/**
 * 获取项目类型的显示文本
 */
@Composable
private fun getItemTypeLabel(itemType: String): String {
    return when (itemType) {
        "PASSWORD" -> stringResource(R.string.timeline_item_password)
        "TOTP" -> stringResource(R.string.timeline_item_authenticator)
        "PASSKEY" -> stringResource(R.string.passkey_title)
        "BANK_CARD" -> stringResource(R.string.timeline_item_card)
        "NOTE" -> stringResource(R.string.timeline_item_note)
        "DOCUMENT" -> stringResource(R.string.timeline_item_document)
        "BILLING_ADDRESS" -> stringResource(R.string.billing_address)
        "PAYMENT_ACCOUNT" -> stringResource(R.string.payment_account)
        "CATEGORY" -> stringResource(R.string.category)
        "KEEPASS_DATABASE" -> stringResource(R.string.database_source_keepass)
        "KEEPASS_GROUP" -> stringResource(R.string.v2_keepass_groups)
        "BITWARDEN_SEND" -> stringResource(R.string.send_screen_title)
        "BITWARDEN_SYNC" -> stringResource(R.string.sync_backup_bitwarden_sync_title)
        "BITWARDEN_CONFLICT" -> stringResource(R.string.sync_conflict)
        "WEBDAV_UPLOAD" -> stringResource(R.string.timeline_item_cloud_backup)
        "WEBDAV_DOWNLOAD" -> stringResource(R.string.timeline_item_cloud_restore)
        else -> stringResource(R.string.timeline_item_default)
    }
}

/**
 * 获取操作的渐变颜色
 */
@Composable
private fun getOperationGradient(operationType: String, itemType: String): Brush {
    val colorScheme = MaterialTheme.colorScheme
    
    if (itemType == "WEBDAV_UPLOAD" || itemType == "WEBDAV_DOWNLOAD") {
        return Brush.linearGradient(
            colors = listOf(
                colorScheme.tertiary.copy(alpha = 0.8f),
                colorScheme.tertiary.copy(alpha = 0.5f)
            )
        )
    }
    
    return when (operationType) {
        "CREATE" -> Brush.linearGradient(
            colors = listOf(
                colorScheme.primary.copy(alpha = 0.8f),
                colorScheme.primary.copy(alpha = 0.5f)
            )
        )
        "UPDATE" -> Brush.linearGradient(
            colors = listOf(
                colorScheme.secondary.copy(alpha = 0.8f),
                colorScheme.secondary.copy(alpha = 0.5f)
            )
        )
        "DELETE" -> Brush.linearGradient(
            colors = listOf(
                colorScheme.error.copy(alpha = 0.8f),
                colorScheme.error.copy(alpha = 0.5f)
            )
        )
        else -> Brush.linearGradient(
            colors = listOf(
                colorScheme.tertiary.copy(alpha = 0.8f),
                colorScheme.tertiary.copy(alpha = 0.5f)
            )
        )
    }
}

/**
 * 现代风格的日志条目 - 卡片式设计
 */
@Composable
private fun ModernLogItem(
    log: TimelineEvent.StandardLog,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val icon = getOperationIcon(log.operationType, log.itemType)
    val gradient = getOperationGradient(log.operationType, log.itemType)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 左侧渐变图标
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(gradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
            
            // 中间内容
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // 标题行
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = timelineLogSummary(log),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    
                    // 已恢复标签
                    if (log.isReverted) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = stringResource(R.string.timeline_reverted),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
                
                // 操作类型和时间
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.timeline_log_meta,
                            getOperationLabel(log.operationType),
                            getItemTypeLabel(log.itemType)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
            
            // 右侧时间
            Text(
                text = formatShortTime(log.timestamp),
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * 聚合日志条目 - 可展开的卡片
 */
@Composable
private fun AggregatedLogItem(
    aggregated: TimelineDisplayItem.Aggregated,
    onItemClick: (TimelineEvent.StandardLog) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var isExpanded by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(200),
        label = "rotation"
    )
    
    val gradient = getOperationGradient(aggregated.operationType, aggregated.itemType)
    val icon = getOperationIcon(aggregated.operationType, aggregated.itemType)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            // 主行 - 可点击展开
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 左侧渐变图标 - 带数量角标
                Box(
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(gradient),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    // 数量角标
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-4).dp),
                        shape = CircleShape,
                        color = colorScheme.primary
                    ) {
                        Text(
                            text = "${aggregated.events.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                // 中间内容
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.timeline_aggregated_summary,
                            getOperationLabel(aggregated.operationType),
                            aggregated.events.size,
                            getItemTypeLabel(aggregated.itemType)
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.timeline_tap_expand_for_details),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                
                // 展开按钮
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(rotationAngle)
                )
            }
            
            // 展开后的内容
            SafeAnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(150)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(100))
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    HorizontalDivider(
                        color = colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    aggregated.events.forEach { event ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onItemClick(event) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(colorScheme.primary, CircleShape)
                                )
                                Text(
                                    text = timelineLogSummary(event),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = formatShortTime(event.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafeAnimatedVisibility(
    visible: Boolean,
    enter: EnterTransition,
    exit: ExitTransition,
    content: @Composable () -> Unit
) {
    // Android 14+ 机型上此处可能触发 Lookahead 布局竞态，降级为无动画避免闪退。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        if (visible) {
            content()
        }
        return
    }

    AnimatedVisibility(
        visible = visible,
        enter = enter,
        exit = exit
    ) {
        content()
    }
}

/**
 * 日志详情底部弹窗 - 更精致的设计
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StandardLogDetailSheet(
    log: TimelineEvent.StandardLog,
    snapshotChanges: List<DiffChange>? = null,
    snapshotLoaded: Boolean = false,
    onDismiss: () -> Unit,
    onRevert: () -> Unit = {},
    onSaveOldAsNew: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colorScheme = MaterialTheme.colorScheme
    var sensitiveValuesVisible by remember(log.id) { mutableStateOf(false) }
    
    val displayedChanges = (snapshotChanges ?: log.changes).filterNot { change ->
        takagi.ru.monica.data.isTimelineSnapshotInternalField(change.fieldName)
    }
    val isSnapshotAvailable = log.hasEncryptedSnapshot && snapshotLoaded && snapshotChanges != null
    val isSnapshotReversible = isSnapshotAvailable && areTimelineSnapshotFieldsReversible(
        itemType = log.itemType,
        fieldNames = snapshotChanges.orEmpty().map(DiffChange::fieldName)
    )
    val hasMaskedSnapshotValues = isSnapshotAvailable && displayedChanges.any { change ->
        shouldMaskTimelineSnapshotField(log.itemType, change.fieldName)
    }
    val isBatchOperation = displayedChanges.any {
        it.fieldName == stringResource(R.string.timeline_field_batch_move) ||
            it.fieldName == stringResource(R.string.timeline_field_batch_copy)
    }
    val isMaintenanceSnapshotOperation = displayedChanges.any {
        it.fieldName == stringResource(R.string.timeline_field_maintenance_snapshot)
    }
    val hasRestorablePayload = isBatchOperation || isMaintenanceSnapshotOperation
    val gradient = getOperationGradient(log.operationType, log.itemType)
    val icon = getOperationIcon(log.operationType, log.itemType)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colorScheme.surface,
        contentColor = colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 顶部图标和标题
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 渐变图标
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(gradient),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = timelineLogSummary(log),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (log.isReverted) {
                            Surface(
                                color = colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.timeline_reverted),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = stringResource(
                            R.string.timeline_log_meta_with_time,
                            getOperationLabel(log.operationType),
                            getItemTypeLabel(log.itemType),
                            formatTimestamp(log.timestamp)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // 分隔线
            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))
            
            // 变更详情
            if (log.hasEncryptedSnapshot && !isSnapshotAvailable) {
                Text(
                    text = if (!snapshotLoaded) {
                        stringResource(R.string.timeline_snapshot_loading)
                    } else {
                        stringResource(R.string.timeline_snapshot_unlock_required)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
            }
            if (displayedChanges.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = colorScheme.surfaceContainerLow
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.no_changes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.timeline_change_details),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (hasMaskedSnapshotValues) {
                            IconButton(
                                onClick = { sensitiveValuesVisible = !sensitiveValuesVisible }
                            ) {
                                Icon(
                                    imageVector = if (sensitiveValuesVisible) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = stringResource(
                                        if (sensitiveValuesVisible) {
                                            R.string.timeline_hide_sensitive_values
                                        } else {
                                            R.string.timeline_show_sensitive_values
                                        }
                                    )
                                )
                            }
                        }
                    }

                    displayedChanges.forEach { change ->
                        val isUnrecoverableSensitiveChange =
                            !isSnapshotAvailable && change.isTimelineSensitiveChange()
                        val isMaskedSnapshotChange = isSnapshotAvailable &&
                            shouldMaskTimelineSnapshotField(log.itemType, change.fieldName) &&
                            !sensitiveValuesVisible

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = colorScheme.surfaceContainerLow
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = timelineFieldLabel(change.fieldName),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(4.dp))

                                when {
                                    isUnrecoverableSensitiveChange -> Text(
                                        text = stringResource(R.string.timeline_sensitive_change_not_stored),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colorScheme.onSurfaceVariant
                                    )
                                    isMaskedSnapshotChange -> Text(
                                        text = "●●●●●● → ●●●●●●",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colorScheme.onSurface
                                    )
                                    change.oldValue.isNotBlank() -> Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = timelineFieldValue(change.fieldName, change.oldValue),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f, fill = false),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "→",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colorScheme.primary
                                        )
                                        Text(
                                            text = timelineFieldValue(change.fieldName, change.newValue),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colorScheme.onSurface,
                                            modifier = Modifier.weight(1f, fill = false),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    else -> Text(
                                        text = timelineFieldValue(change.fieldName, change.newValue),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // 操作按钮
            val canUseSnapshotActions = !log.hasEncryptedSnapshot || isSnapshotReversible
            if (log.canRevert && canUseSnapshotActions && (!hasRestorablePayload || !log.isReverted)) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onRevert,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorScheme.primaryContainer,
                            contentColor = colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                isBatchOperation -> stringResource(R.string.timeline_undo_operation)
                                isMaintenanceSnapshotOperation -> stringResource(R.string.timeline_restore_snapshot)
                                log.isReverted -> stringResource(R.string.timeline_restore_to_after_edit)
                                else -> stringResource(R.string.timeline_restore_to_before_edit)
                            },
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    if (log.canSaveOldAsNew && canUseSnapshotActions && !log.isReverted && !hasRestorablePayload) {
                        OutlinedButton(
                            onClick = onSaveOldAsNew,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.timeline_save_old_as_new))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

/**
 * 冲突分支项 UI
 */
@Composable
private fun ConflictBranchItem(
    conflict: TimelineEvent.ConflictBranch,
    isFirst: Boolean,
    isLast: Boolean,
    onBranchClick: (TimelineBranch) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val primaryColor = colorScheme.primary
    
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Ancestor 节点
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            TimelineAxis(
                showTopLine = !isFirst,
                showBottomLine = true
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Card(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountTree,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.sync_conflict),
                            style = MaterialTheme.typography.labelMedium,
                            color = colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = conflict.ancestor.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = formatRelativeTime(conflict.ancestor.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        // 分支区域 - Canvas 绘制贝塞尔曲线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            ) {
                val startX = size.width / 2f
                val startY = 0f
                val branchCount = conflict.branches.size
                
                if (branchCount > 0) {
                    val spacing = size.width / (branchCount + 1)
                    
                    conflict.branches.forEachIndexed { index, _ ->
                        val endX = spacing * (index + 1)
                        val endY = size.height
                        val controlY = size.height * 0.5f
                        
                        val path = Path().apply {
                            moveTo(startX, startY)
                            cubicTo(
                                startX, controlY,
                                endX, controlY,
                                endX, endY
                            )
                        }
                        
                        drawPath(
                            path = path,
                            color = primaryColor,
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(10f, 10f),
                                    0f
                                ),
                                cap = StrokeCap.Round
                            )
                        )
                    }
                }
            }
        }
        
        // 分支卡片
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            conflict.branches.forEach { branch ->
                BranchCard(
                    branch = branch,
                    modifier = Modifier.weight(1f),
                    onClick = { onBranchClick(branch) }
                )
            }
        }
        
        // 底部时间线延续
        if (!isLast) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
            ) {
                TimelineAxis(
                    showTopLine = true,
                    showBottomLine = true,
                    showNode = false
                )
            }
        }
    }
}

/**
 * 分支卡片
 */
@Composable
private fun BranchCard(
    branch: TimelineBranch,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (branch.deviceName.contains("PC", ignoreCase = true) || 
                                       branch.deviceName.contains("Windows", ignoreCase = true) ||
                                       branch.deviceName.contains("Mac", ignoreCase = true)) {
                        Icons.Default.Computer
                    } else {
                        Icons.Default.Smartphone
                    },
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = branch.deviceName,
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (branch.changes.isNotEmpty()) {
                val firstChange = branch.changes.first()
                Text(
                    text = stringResource(R.string.modified_field, timelineFieldLabel(firstChange.fieldName)),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = formatShortTime(branch.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * 时间线轴组件
 */
@Composable
private fun TimelineAxis(
    showTopLine: Boolean = true,
    showBottomLine: Boolean = true,
    showNode: Boolean = true
) {
    val colorScheme = MaterialTheme.colorScheme
    val lineColor = colorScheme.outline
    val nodeColor = colorScheme.primary
    
    Box(
        modifier = Modifier
            .width(24.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (showTopLine) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(lineColor)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            
            if (showNode) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(nodeColor, CircleShape)
                )
            }
            
            if (showBottomLine) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(lineColor)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

// ================== 回收站相关组件 ==================

private data class TrashScopeFilterOption(
    val key: String,
    val label: String,
    val scope: TrashScopeFilter
)

private fun TrashScopeFilter.toUnifiedCategoryFilterSelection(): UnifiedCategoryFilterSelection {
    return when (this) {
        TrashScopeFilter.All -> UnifiedCategoryFilterSelection.All
        TrashScopeFilter.Local -> UnifiedCategoryFilterSelection.Local
        is TrashScopeFilter.BitwardenVaultScope ->
            UnifiedCategoryFilterSelection.BitwardenVaultFilter(vaultId)
        is TrashScopeFilter.KeePassDatabaseScope ->
            UnifiedCategoryFilterSelection.KeePassDatabaseFilter(databaseId)
        is TrashScopeFilter.MdbxDatabaseScope ->
            UnifiedCategoryFilterSelection.MdbxDatabaseFilter(databaseId)
    }
}

@Composable
private fun TrashScopeFilterChipMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    selectedScope: TrashScopeFilter,
    fallbackScope: TrashScopeFilter,
    keepassDatabases: List<LocalKeePassDatabase>,
    mdbxDatabases: List<LocalMdbxDatabase>,
    bitwardenVaults: List<BitwardenVault>,
    database: PasswordDatabase,
    onSelectedScopeKeyChange: (String) -> Unit
) {
    val selected = remember(selectedScope) {
        selectedScope.toUnifiedCategoryFilterSelection()
    }
    UnifiedCategoryFilterChipMenuDropdown(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = UnifiedCategoryFilterChipMenuOffset
    ) {
        UnifiedCategoryFilterChipMenu(
            visible = true,
            onDismiss = onDismissRequest,
            selected = selected,
            onSelect = { selection ->
                onSelectedScopeKeyChange(selection.toTrashScopeFilter(fallbackScope).key)
                onDismissRequest()
            },
            categories = emptyList(),
            keepassDatabases = keepassDatabases,
            mdbxDatabases = mdbxDatabases,
            bitwardenVaults = bitwardenVaults,
            getBitwardenFolders = { vaultId ->
                database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId)
            },
            getKeePassGroups = null
        )
    }
}

private fun resolveScopeFilter(
    bitwardenVaultId: Long?,
    keepassDatabaseId: Long?,
    mdbxDatabaseId: Long? = null
): TrashScopeFilter {
    return when {
        bitwardenVaultId != null -> TrashScopeFilter.BitwardenVaultScope(bitwardenVaultId)
        keepassDatabaseId != null -> TrashScopeFilter.KeePassDatabaseScope(keepassDatabaseId)
        mdbxDatabaseId != null -> TrashScopeFilter.MdbxDatabaseScope(mdbxDatabaseId)
        else -> TrashScopeFilter.Local
    }
}

private fun resolveTrashScope(item: takagi.ru.monica.viewmodel.TrashItem): TrashScopeFilter {
    val bitwardenVaultId = when (val data = item.originalData) {
        is PasswordEntry -> data.bitwardenVaultId
        is SecureItem -> data.bitwardenVaultId
        else -> null
    }
    val keepassDatabaseId = when (val data = item.originalData) {
        is PasswordEntry -> data.keepassDatabaseId
        is SecureItem -> data.keepassDatabaseId
        else -> null
    }
    val mdbxDatabaseId = when (val data = item.originalData) {
        is PasswordEntry -> data.mdbxDatabaseId
        is SecureItem -> data.mdbxDatabaseId
        else -> null
    }

    return resolveScopeFilter(
        bitwardenVaultId = bitwardenVaultId,
        keepassDatabaseId = keepassDatabaseId,
        mdbxDatabaseId = mdbxDatabaseId
    )
}

private fun TrashScopeFilter.matches(item: takagi.ru.monica.viewmodel.TrashItem): Boolean {
    if (this == TrashScopeFilter.All) {
        return true
    }
    return resolveTrashScope(item) == this
}

private fun matchesTimelineScope(
    event: TimelineEvent,
    selectedScope: TrashScopeFilter,
    passwordScopeById: Map<Long, TrashScopeFilter>,
    secureItemScopeById: Map<Long, TrashScopeFilter>,
    passkeyScopeById: Map<Long, TrashScopeFilter>
): Boolean {
    if (selectedScope == TrashScopeFilter.All) {
        return true
    }
    val scope = when (event) {
        is TimelineEvent.StandardLog ->
            resolveTimelineScope(event, passwordScopeById, secureItemScopeById, passkeyScopeById)
        is TimelineEvent.ConflictBranch ->
            resolveTimelineScope(event.ancestor, passwordScopeById, secureItemScopeById, passkeyScopeById)
    }
    return scope == selectedScope
}

private fun resolveTimelineScope(
    log: TimelineEvent.StandardLog,
    passwordScopeById: Map<Long, TrashScopeFilter>,
    secureItemScopeById: Map<Long, TrashScopeFilter>,
    passkeyScopeById: Map<Long, TrashScopeFilter>
): TrashScopeFilter {
    return when (log.itemType) {
        "PASSWORD" -> passwordScopeById[log.itemId] ?: TrashScopeFilter.Local
        "TOTP", "BANK_CARD", "NOTE", "DOCUMENT", "BILLING_ADDRESS", "PAYMENT_ACCOUNT" ->
            secureItemScopeById[log.itemId] ?: TrashScopeFilter.Local
        "PASSKEY" -> passkeyScopeById[log.itemId] ?: TrashScopeFilter.Local
        else -> TrashScopeFilter.Local
    }
}

private fun matchesTimelineSearch(event: TimelineEvent, keyword: String): Boolean {
    val query = keyword.trim().lowercase(Locale.getDefault())
    if (query.isEmpty()) return true
    return when (event) {
        is TimelineEvent.StandardLog -> matchesTimelineSearch(event, query)
        is TimelineEvent.ConflictBranch -> {
            matchesTimelineSearch(event.ancestor, query) ||
                event.branches.any { branch ->
                    branch.deviceId.lowercase(Locale.getDefault()).contains(query) ||
                        branch.deviceName.lowercase(Locale.getDefault()).contains(query)
                }
        }
    }
}

private fun matchesTimelineSearch(
    log: TimelineEvent.StandardLog,
    query: String
): Boolean {
    if (log.summary.lowercase(Locale.getDefault()).contains(query)) return true
    if (log.operationType.lowercase(Locale.getDefault()).contains(query)) return true
    if (log.itemType.lowercase(Locale.getDefault()).contains(query)) return true
    if (log.deviceId.lowercase(Locale.getDefault()).contains(query)) return true
    return log.changes.any { change ->
        change.fieldName.lowercase(Locale.getDefault()).contains(query) ||
            change.oldValue.lowercase(Locale.getDefault()).contains(query) ||
            change.newValue.lowercase(Locale.getDefault()).contains(query)
    }
}

/**
 * 回收站内容 - 简化版设计，直接显示所有条目
 */
@Composable
private fun TrashContent(
    viewModel: TrashViewModel,
    embeddedInSplitPane: Boolean = false,
    initialSelectedScopeKey: String? = null,
    hasReturnAction: Boolean = false,
    onSelectionModeChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val database = remember(context) { PasswordDatabase.getDatabase(context.applicationContext) }
    val bitwardenVaults by database.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
    val keepassDatabases by database.localKeePassDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val mdbxDatabases by database.localMdbxDatabaseDao().getAvailableDatabases().collectAsState(initial = emptyList())

    val trashCategories by viewModel.trashCategories.collectAsState()
    val trashSettings by viewModel.trashSettings.collectAsState()
    var selectedScopeKey by rememberSaveable(initialSelectedScopeKey) {
        mutableStateOf(trashScopeFromKey(initialSelectedScopeKey).key)
    }
    var showScopeSelectionSheet by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showEmptyTrashDialog by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<takagi.ru.monica.viewmodel.TrashItem?>(null) }

    // 多选模式状态
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedItems by remember { mutableStateOf(setOf<String>()) }
    val reportSelectionMode by rememberUpdatedState(onSelectionModeChange)

    LaunchedEffect(isSelectionMode) {
        reportSelectionMode(isSelectionMode)
    }
    DisposableEffect(Unit) {
        onDispose { reportSelectionMode(false) }
    }

    val colorScheme = MaterialTheme.colorScheme

    val selectedScope = remember(selectedScopeKey) { trashScopeFromKey(selectedScopeKey) }

    // 扁平化所有条目，按删除时间排序
    val allItems = remember(trashCategories) {
        trashCategories.flatMap { it.items }.sortedByDescending { it.deletedAt.time }
    }
    val scopedItems = remember(allItems, selectedScope) {
        allItems.filter { selectedScope.matches(it) }
    }
    val visibleItems = remember(scopedItems, searchQuery) {
        val keyword = searchQuery.trim()
        if (keyword.isBlank()) {
            scopedItems
        } else {
            scopedItems.filter { item -> matchesTrashSearch(item, keyword) }
        }
    }

    LaunchedEffect(selectedScopeKey, searchQuery) {
        isSelectionMode = false
        selectedItems = emptySet()
    }

    // 选择/取消选择条目
    fun toggleItemSelection(item: takagi.ru.monica.viewmodel.TrashItem) {
        val key = "${item.itemType.name}_${item.id}"
        selectedItems = if (selectedItems.contains(key)) {
            selectedItems - key
        } else {
            selectedItems + key
        }
        // 如果取消选择后没有选中项，退出选择模式
        if (selectedItems.isEmpty()) {
            isSelectionMode = false
        }
    }
    
    fun isItemSelected(item: takagi.ru.monica.viewmodel.TrashItem): Boolean {
        return selectedItems.contains("${item.itemType.name}_${item.id}")
    }
    
    fun toggleSelectAll() {
        val scopedKeys = visibleItems.map { "${it.itemType.name}_${it.id}" }.toSet()
        if (scopedKeys.isNotEmpty() && selectedItems.containsAll(scopedKeys)) {
            selectedItems = emptySet()
            isSelectionMode = false
        } else {
            selectedItems = scopedKeys
        }
    }
    
    fun exitSelectionMode() {
        isSelectionMode = false
        selectedItems = emptySet()
    }

    BackHandler(enabled = isSelectionMode) {
        exitSelectionMode()
    }
    
    fun restoreSelectedItems() {
        val itemsToRestore = visibleItems.filter { isItemSelected(it) }
        itemsToRestore.forEach { item ->
            viewModel.restoreItem(item) { _ -> }
        }
        exitSelectionMode()
    }
    
    fun showDeleteFailure(result: Result<Unit>) {
        val failure = result.exceptionOrNull() ?: return
        val reason = failure.localizedMessage?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.timeline_trash_delete_unknown_error)
        Toast.makeText(context, context.getString(R.string.delete_failed, reason), Toast.LENGTH_LONG).show()
    }

    fun deleteSelectedItems() {
        val itemsToDelete = visibleItems.filter { isItemSelected(it) }
        viewModel.permanentlyDeleteItems(itemsToDelete) { result ->
            showDeleteFailure(result)
        }
        exitSelectionMode()
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
    ) {
        if (!trashSettings.enabled) {
            TrashDisabledView(onEnableClick = { showSettingsDialog = true })
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部信息栏
                TrashHeaderBar(
                    embeddedInSplitPane = embeddedInSplitPane,
                    searchBackEnabled = !isSelectionMode,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    isSearchExpanded = isSearchExpanded,
                    onSearchExpandedChange = { expanded ->
                        isSearchExpanded = expanded
                        if (!expanded) {
                            searchQuery = ""
                        }
                    },
                    onOpenScopeSheet = { showScopeSelectionSheet = true },
                    onSettingsClick = { showSettingsDialog = true },
                    onEmptyTrashClick = { showEmptyTrashDialog = true },
                    canEmptyTrash = scopedItems.isNotEmpty(),
                    scopeMenu = {
                        TrashScopeFilterChipMenu(
                            expanded = showScopeSelectionSheet,
                            onDismissRequest = { showScopeSelectionSheet = false },
                            selectedScope = selectedScope,
                            fallbackScope = TrashScopeFilter.All,
                            keepassDatabases = keepassDatabases,
                            mdbxDatabases = mdbxDatabases,
                            bitwardenVaults = bitwardenVaults,
                            database = database,
                            onSelectedScopeKeyChange = { selectedScopeKey = it }
                        )
                    }
                )
                
                if (visibleItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    ) {
                        TrashEmptyView()
                    }
                } else {
                    // 条目列表 - 直接平铺显示
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = if (isSelectionMode || hasReturnAction) 100.dp else 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = visibleItems,
                            key = { "${it.itemType.name}_${it.id}" }
                        ) { item ->
                            TrashItemCard(
                                item = item,
                                isSelectionMode = isSelectionMode,
                                isSelected = isItemSelected(item),
                                onClick = {
                                    if (isSelectionMode) {
                                        toggleItemSelection(item)
                                    } else {
                                        selectedItem = item
                                    }
                                },
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        isSelectionMode = true
                                        toggleItemSelection(item)
                                    }
                                },
                                onRestore = {
                                    viewModel.restoreItem(item) { _ -> }
                                }
                            )
                        }
                    }
                }
            }
        }
        
        // 底部浮动操作栏（选择模式）
        AnimatedVisibility(
            visible = isSelectionMode && selectedItems.isNotEmpty(),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
        ) {
            TrashSelectionBar(
                selectedCount = selectedItems.size,
                onExit = { exitSelectionMode() },
                onSelectAll = { toggleSelectAll() },
                onRestore = { restoreSelectedItems() },
                onDelete = { deleteSelectedItems() }
            )
        }
    }
    
    // 回收站设置对话框
    if (showSettingsDialog) {
        TrashSettingsSheet(
            currentSettings = trashSettings,
            onDismiss = { showSettingsDialog = false },
            onConfirm = { enabled, days ->
                viewModel.updateTrashSettings(enabled, days)
                showSettingsDialog = false
            }
        )
    }
    
    // 清空回收站确认对话框
    if (showEmptyTrashDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.timeline_empty_trash_title)) },
            text = { Text(stringResource(R.string.timeline_empty_trash_message, scopedItems.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.permanentlyDeleteItems(scopedItems) { result ->
                            showDeleteFailure(result)
                        }
                        showEmptyTrashDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colorScheme.error)
                ) {
                    Text(stringResource(R.string.timeline_empty_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    
    // 条目操作弹窗
    selectedItem?.let { item ->
        TrashItemActionSheet(
            item = item,
            onDismiss = { selectedItem = null },
            onRestore = {
                viewModel.restoreItem(item) { _ -> selectedItem = null }
            },
            onPermanentDelete = {
                viewModel.permanentlyDeleteItem(item) { result ->
                    showDeleteFailure(result)
                    selectedItem = null
                }
            }
        )
    }
}

/**
 * 回收站顶部信息栏
 */
@Composable
private fun TrashHeaderBar(
    embeddedInSplitPane: Boolean,
    searchBackEnabled: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    onOpenScopeSheet: () -> Unit,
    onSettingsClick: () -> Unit,
    onEmptyTrashClick: () -> Unit,
    canEmptyTrash: Boolean,
    scopeMenu: @Composable () -> Unit = {}
) {
    var topActionsMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (embeddedInSplitPane) 2.dp else 0.dp)
    ) {
        ExpressiveTopBar(
            title = stringResource(R.string.timeline_trash_title),
            searchBackEnabled = searchBackEnabled,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            isSearchExpanded = isSearchExpanded,
            onSearchExpandedChange = onSearchExpandedChange,
            searchHint = stringResource(R.string.search_passwords_hint),
            actions = {
                Box {
                    IconButton(onClick = onOpenScopeSheet) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = stringResource(R.string.category),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    scopeMenu()
                }
                IconButton(onClick = { onSearchExpandedChange(true) }) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { topActionsMenuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_options),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    MaterialTheme(
                        shapes = MaterialTheme.shapes.copy(
                            extraSmall = RoundedCornerShape(20.dp),
                            small = RoundedCornerShape(20.dp)
                        )
                    ) {
                        DropdownMenu(
                            expanded = topActionsMenuExpanded,
                            onDismissRequest = { topActionsMenuExpanded = false },
                            offset = DpOffset(x = 48.dp, y = 6.dp),
                            modifier = Modifier
                                .widthIn(min = 220.dp, max = 260.dp)
                                .shadow(10.dp, RoundedCornerShape(20.dp))
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                    shape = RoundedCornerShape(20.dp)
                                )
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.timeline_empty_action)) },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                                enabled = canEmptyTrash,
                                onClick = {
                                    topActionsMenuExpanded = false
                                    onEmptyTrashClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.trash_settings)) },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = {
                                    topActionsMenuExpanded = false
                                    onSettingsClick()
                                }
                            )
                        }
                    }
                }
            }
        )
    }
}

private fun matchesTrashSearch(
    item: takagi.ru.monica.viewmodel.TrashItem,
    keyword: String
): Boolean {
    val query = keyword.trim().lowercase(Locale.getDefault())
    if (query.isEmpty()) return true
    val titleMatched = item.title.lowercase(Locale.getDefault()).contains(query)
    if (titleMatched) return true
    return when (val data = item.originalData) {
        is PasswordEntry -> {
            data.username.lowercase(Locale.getDefault()).contains(query) ||
                data.website.lowercase(Locale.getDefault()).contains(query) ||
                data.notes.lowercase(Locale.getDefault()).contains(query)
        }
        is SecureItem -> {
            data.notes.lowercase(Locale.getDefault()).contains(query)
        }
        else -> false
    }
}

/**
 * 回收站条目卡片 - 简洁直观的设计
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashItemCard(
    item: takagi.ru.monica.viewmodel.TrashItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRestore: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    
    // 根据类型获取图标和颜色
    val (icon, iconColor) = when (item.itemType) {
        takagi.ru.monica.data.ItemType.PASSWORD -> Icons.Default.Key to colorScheme.primary
        takagi.ru.monica.data.ItemType.TOTP -> Icons.Default.History to colorScheme.secondary
        takagi.ru.monica.data.ItemType.BANK_CARD -> Icons.Default.CreditCard to colorScheme.tertiary
        takagi.ru.monica.data.ItemType.DOCUMENT -> Icons.Default.Description to colorScheme.error
        takagi.ru.monica.data.ItemType.BILLING_ADDRESS -> Icons.Default.Home to colorScheme.secondary
        takagi.ru.monica.data.ItemType.PAYMENT_ACCOUNT -> Icons.Default.AccountBalanceWallet to colorScheme.tertiary
        takagi.ru.monica.data.ItemType.NOTE -> Icons.Default.Note to colorScheme.outline
    }
    
    val typeLabel = when (item.itemType) {
        takagi.ru.monica.data.ItemType.PASSWORD -> stringResource(R.string.item_type_password)
        takagi.ru.monica.data.ItemType.TOTP -> stringResource(R.string.item_type_authenticator)
        takagi.ru.monica.data.ItemType.BANK_CARD -> stringResource(R.string.item_type_bank_card)
        takagi.ru.monica.data.ItemType.DOCUMENT -> stringResource(R.string.item_type_document)
        takagi.ru.monica.data.ItemType.BILLING_ADDRESS -> stringResource(R.string.billing_address)
        takagi.ru.monica.data.ItemType.PAYMENT_ACCOUNT -> stringResource(R.string.payment_account)
        takagi.ru.monica.data.ItemType.NOTE -> stringResource(R.string.timeline_item_note)
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                colorScheme.primaryContainer.copy(alpha = 0.5f) 
            else 
                colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 选择模式下显示复选框，否则显示类型图标
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.size(24.dp)
                )
            } else {
                // 类型图标
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            
            // 内容
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 类型标签
                    Text(
                        text = typeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = iconColor
                    )
                        Text(
                        text = stringResource(R.string.timeline_dot_separator),
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    // 删除时间
                    Text(
                        text = dateFormat.format(item.deletedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                    // 剩余天数警告
                    if (item.daysRemaining in 0..3) {
                        Text(
                            text = stringResource(R.string.timeline_dot_separator),
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = if (item.daysRemaining == 0) {
                                stringResource(R.string.timeline_clear_today)
                            } else {
                                stringResource(R.string.timeline_clear_after_days_short, item.daysRemaining)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            
            // 非选择模式下显示恢复按钮
            if (!isSelectionMode) {
                FilledTonalIconButton(
                    onClick = onRestore,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = colorScheme.primaryContainer,
                        contentColor = colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        Icons.Default.Restore,
                        contentDescription = stringResource(R.string.timeline_restore_this_item),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * 底部选择操作栏 - 简洁版
 */
@Composable
private fun TrashSelectionBar(
    selectedCount: Int,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    SelectionActionBar(
        modifier = modifier,
        selectedCount = selectedCount,
        onExit = onExit,
        onSelectAll = onSelectAll,
        onRestore = onRestore,
        onDelete = { showDeleteConfirm = true }
    )
    
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.timeline_permanent_delete_title)) },
            text = { Text(stringResource(R.string.timeline_permanent_delete_selected_message, selectedCount)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * 条目操作底部弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashItemActionSheet(
    item: takagi.ru.monica.viewmodel.TrashItem,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState()
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    
    val (icon, iconColor) = when (item.itemType) {
        takagi.ru.monica.data.ItemType.PASSWORD -> Icons.Default.Key to colorScheme.primary
        takagi.ru.monica.data.ItemType.TOTP -> Icons.Default.History to colorScheme.secondary
        takagi.ru.monica.data.ItemType.BANK_CARD -> Icons.Default.CreditCard to colorScheme.tertiary
        takagi.ru.monica.data.ItemType.DOCUMENT -> Icons.Default.Description to colorScheme.error
        takagi.ru.monica.data.ItemType.BILLING_ADDRESS -> Icons.Default.Home to colorScheme.secondary
        takagi.ru.monica.data.ItemType.PAYMENT_ACCOUNT -> Icons.Default.AccountBalanceWallet to colorScheme.tertiary
        takagi.ru.monica.data.ItemType.NOTE -> Icons.Default.Note to colorScheme.outline
    }
    
    val typeLabel = when (item.itemType) {
        takagi.ru.monica.data.ItemType.PASSWORD -> stringResource(R.string.item_type_password)
        takagi.ru.monica.data.ItemType.TOTP -> stringResource(R.string.item_type_authenticator)
        takagi.ru.monica.data.ItemType.BANK_CARD -> stringResource(R.string.item_type_bank_card)
        takagi.ru.monica.data.ItemType.DOCUMENT -> stringResource(R.string.item_type_document)
        takagi.ru.monica.data.ItemType.BILLING_ADDRESS -> stringResource(R.string.billing_address)
        takagi.ru.monica.data.ItemType.PAYMENT_ACCOUNT -> stringResource(R.string.payment_account)
        takagi.ru.monica.data.ItemType.NOTE -> stringResource(R.string.timeline_item_note)
    }
    
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 顶部信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(iconColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.timeline_deleted_at_with_type, typeLabel, dateFormat.format(item.deletedAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // 剩余天数提示
            if (item.daysRemaining >= 0) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = if (item.daysRemaining <= 3) 
                        colorScheme.errorContainer.copy(alpha = 0.5f) 
                    else 
                        colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            tint = if (item.daysRemaining <= 3) colorScheme.error else colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = when {
                                item.daysRemaining == 0 -> stringResource(R.string.timeline_auto_permanent_delete_today)
                                item.daysRemaining <= 3 -> stringResource(R.string.timeline_auto_permanent_delete_in_days, item.daysRemaining)
                                else -> stringResource(R.string.timeline_auto_clear_in_days, item.daysRemaining)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (item.daysRemaining <= 3) colorScheme.error else colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))
            
            // 操作按钮
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 恢复按钮
                Button(
                    onClick = {
                        onDismiss()
                        onRestore()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.timeline_restore_this_item), fontWeight = FontWeight.Medium)
                }
                
                // 永久删除按钮
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colorScheme.error),
                    border = BorderStroke(1.dp, colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.timeline_permanent_delete_title))
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    
    // 删除确认对话框
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = colorScheme.error) },
            title = { Text(stringResource(R.string.timeline_permanent_delete_title)) },
            text = { Text(stringResource(R.string.timeline_permanent_delete_item_message, item.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDismiss()
                        onPermanentDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * 回收站未启用视图
 */
@Composable
private fun TrashDisabledView(
    onEnableClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = stringResource(R.string.timeline_trash_disabled_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.timeline_trash_disabled_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        FilledTonalButton(onClick = onEnableClick) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings))
        }
    }
}

/**
 * 回收站为空视图
 */
@Composable
private fun TrashEmptyView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = stringResource(R.string.timeline_trash_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.timeline_trash_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}
