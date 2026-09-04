package takagi.ru.monica.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.fragment.app.FragmentActivity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import takagi.ru.monica.R
import takagi.ru.monica.data.AddButtonBehaviorMode
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.AddButtonMenuAction
import takagi.ru.monica.data.BottomNavContentTab
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.data.PasswordQuickAccessManager
import takagi.ru.monica.data.model.PasskeyBindingCodec
import takagi.ru.monica.data.model.TimelineEvent
import takagi.ru.monica.passkey.managementKey
import takagi.ru.monica.utils.BiometricHelper
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.SettingsViewModel
import takagi.ru.monica.viewmodel.TotpViewModel
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.data.Category
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.DocumentViewModel
import takagi.ru.monica.viewmodel.GeneratorViewModel
import takagi.ru.monica.viewmodel.GeneratorType
import takagi.ru.monica.viewmodel.NoteViewModel
import takagi.ru.monica.viewmodel.PasskeyViewModel
import takagi.ru.monica.viewmodel.TimelineViewModel
import takagi.ru.monica.viewmodel.BillingAddressViewModel
import takagi.ru.monica.ui.screens.SettingsScreen
import takagi.ru.monica.ui.screens.GeneratorScreen  // 添加生成器页面导入
import takagi.ru.monica.ui.screens.NoteListScreen
import takagi.ru.monica.ui.screens.NoteListContent
import takagi.ru.monica.ui.screens.PasswordDetailScreen
import takagi.ru.monica.ui.screens.SendScreen
import takagi.ru.monica.ui.screens.CardWalletScreen
import takagi.ru.monica.ui.screens.CardWalletTab
import takagi.ru.monica.ui.screens.BankCardDetailScreen
import takagi.ru.monica.ui.screens.BillingAddressDetailScreen
import takagi.ru.monica.ui.screens.DocumentDetailScreen
import takagi.ru.monica.ui.screens.HistoryTab
import takagi.ru.monica.ui.screens.TimelineScreen
import takagi.ru.monica.ui.screens.PasskeyListScreen
import takagi.ru.monica.steam.ui.SteamScreen
import takagi.ru.monica.ui.gestures.SwipeActions
import takagi.ru.monica.ui.haptic.rememberHapticFeedback
import kotlin.math.absoluteValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import takagi.ru.monica.ui.components.QrCodeDialog
import takagi.ru.monica.ui.components.ExpressiveTopBar
import takagi.ru.monica.ui.components.DraggableBottomNavScaffold
import takagi.ru.monica.ui.components.SwipeableAddFab
import takagi.ru.monica.ui.components.DraggableNavItem
import takagi.ru.monica.ui.components.QuickActionItem
import takagi.ru.monica.ui.components.QuickAddCallback
import takagi.ru.monica.ui.components.SyncStatusIcon
import takagi.ru.monica.ui.components.M3IdentityVerifyDialog
import takagi.ru.monica.ui.components.PasswordQuickAccessItem
import takagi.ru.monica.ui.components.rankFrequentPasswordQuickAccessItems
import takagi.ru.monica.ui.components.rankRecentPasswordQuickAccessItems
import takagi.ru.monica.ui.components.CardWalletAddTypeChip
import takagi.ru.monica.ui.components.UnifiedCategoryFilterBottomSheet
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget
import takagi.ru.monica.ui.components.UnifiedMoveToCategoryBottomSheet
import takagi.ru.monica.ui.common.dialog.DeleteConfirmDialog
import takagi.ru.monica.ui.common.layout.DetailPane
import takagi.ru.monica.ui.common.layout.InspectorRow
import takagi.ru.monica.ui.common.layout.ListPane
import takagi.ru.monica.ui.common.pull.PullActionVisualState
import takagi.ru.monica.ui.common.pull.PullGestureIndicator
import takagi.ru.monica.ui.common.pull.rememberPullActionState
import takagi.ru.monica.ui.common.selection.CategoryListItem
import takagi.ru.monica.ui.common.selection.SelectionActionBar
import takagi.ru.monica.ui.common.selection.SelectionModeTopBar
import takagi.ru.monica.ui.main.navigation.BottomNavItem
import takagi.ru.monica.ui.main.navigation.fullLabelRes
import takagi.ru.monica.ui.main.navigation.indexToDefaultTabKey
import takagi.ru.monica.ui.main.navigation.shortLabelRes
import takagi.ru.monica.ui.main.navigation.toBottomNavItem
import takagi.ru.monica.ui.main.layout.AdaptiveMainScaffold
import takagi.ru.monica.ui.password.buildAdditionalInfoPreview
import takagi.ru.monica.ui.password.MultiPasswordEntryCard
import takagi.ru.monica.ui.password.StackedPasswordGroup
import takagi.ru.monica.ui.password.PasswordEntryCard
import takagi.ru.monica.ui.password.StackCardMode
import takagi.ru.monica.ui.password.resolvePasswordPageVisibleTypes
import takagi.ru.monica.ui.password.sanitizeSelectedPasswordPageTypes
import takagi.ru.monica.ui.password.PasswordListAggregateConfig
import takagi.ru.monica.ui.password.getGroupKeyForMode
import takagi.ru.monica.ui.password.getPasswordGroupTitle
import takagi.ru.monica.ui.password.getPasswordInfoKey
import takagi.ru.monica.ui.vaultv2.VaultV2Pane
import takagi.ru.monica.ui.vaultv2.VaultV2DetailKind
import takagi.ru.monica.ui.vaultv2.VaultV2PaneState
import takagi.ru.monica.ui.vaultv2.VaultV2RetainedStateViewModel
import takagi.ru.monica.ui.vaultv2.VaultV2TabPane
import takagi.ru.monica.ui.vaultv2.rememberVaultV2PaneState
import takagi.ru.monica.data.bitwarden.BitwardenPendingOperation
import takagi.ru.monica.data.bitwarden.BitwardenSend
import takagi.ru.monica.bitwarden.sync.SyncBlockReason
import takagi.ru.monica.bitwarden.sync.buildMiniHintDetail
import takagi.ru.monica.bitwarden.sync.buildMiniHintTitle
import takagi.ru.monica.bitwarden.sync.buildDetailLine
import takagi.ru.monica.bitwarden.sync.buildHeadline
import takagi.ru.monica.bitwarden.sync.SyncStatus
import takagi.ru.monica.bitwarden.sync.VaultSyncStatus
import takagi.ru.monica.security.SecurityManager
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.ui.screens.AddEditTotpScreen
import takagi.ru.monica.ui.screens.AddEditBankCardScreen
import takagi.ru.monica.ui.screens.AddEditBillingAddressScreen
import takagi.ru.monica.ui.screens.AddEditDocumentScreen
import takagi.ru.monica.ui.screens.AddEditNoteScreen
import takagi.ru.monica.ui.screens.AddEditSendScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Keep this file as orchestration layer: state wiring + tab routing + pane transitions.
// Feature-specific rendering should stay in dedicated composables/files.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedWalletAddScreen(
    selectedType: CardWalletTab,
    onTypeSelected: (CardWalletTab) -> Unit,
    onNavigateBack: () -> Unit,
    bankCardViewModel: BankCardViewModel,
    documentViewModel: DocumentViewModel,
    billingAddressViewModel: BillingAddressViewModel,
    stateHolder: androidx.compose.runtime.saveable.SaveableStateHolder,
    initialCategoryId: Long? = null,
    initialKeePassDatabaseId: Long? = null,
    initialKeePassGroupPath: String? = null,
    initialMdbxDatabaseId: Long? = null,
    initialMdbxFolderId: String? = null,
    initialBitwardenVaultId: Long? = null,
    initialBitwardenFolderId: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isFavorite by remember { mutableStateOf(false) }
    var canSave by remember { mutableStateOf(false) }
    var onSaveAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onToggleFavoriteAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val titleRes = when (selectedType) {
        CardWalletTab.DOCUMENTS -> R.string.item_type_document
        CardWalletTab.BILLING_ADDRESSES -> R.string.billing_address
        else -> R.string.item_type_bank_card
    }
    val topBarTitle = stringResource(titleRes)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                    title = { Text(topBarTitle) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    },
                    actions = {
                        CardWalletAddTypeChip(
                            current = selectedType,
                            onSelect = onTypeSelected
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                onToggleFavoriteAction?.invoke()
                            },
                            enabled = onToggleFavoriteAction != null
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = stringResource(R.string.favorite),
                                tint = if (isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current
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
                onClick = { onSaveAction?.invoke() },
                containerColor = if (canSave) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (canSave) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            ) {
                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save))
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (selectedType == CardWalletTab.BILLING_ADDRESSES) {
                    stateHolder.SaveableStateProvider("wallet_add_billing_address") {
                        AddEditBillingAddressScreen(
                            viewModel = billingAddressViewModel,
                            addressId = null,
                            onNavigateBack = onNavigateBack,
                            initialCategoryId = initialCategoryId,
                            initialMdbxDatabaseId = initialMdbxDatabaseId,
                            initialMdbxFolderId = initialMdbxFolderId,
                            showTopBar = false,
                            showFab = false,
                            onFavoriteStateChanged = { isFavorite = it },
                            onCanSaveChanged = { canSave = it },
                            onSaveActionChanged = { onSaveAction = it },
                            onToggleFavoriteActionChanged = { onToggleFavoriteAction = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else if (selectedType == CardWalletTab.DOCUMENTS) {
                    stateHolder.SaveableStateProvider("wallet_add_document") {
                        AddEditDocumentScreen(
                            viewModel = documentViewModel,
                            documentId = null,
                            onNavigateBack = onNavigateBack,
                            initialCategoryId = initialCategoryId,
                            initialKeePassDatabaseId = initialKeePassDatabaseId,
                            initialKeePassGroupPath = initialKeePassGroupPath,
                            initialMdbxDatabaseId = initialMdbxDatabaseId,
                            initialMdbxFolderId = initialMdbxFolderId,
                            initialBitwardenVaultId = initialBitwardenVaultId,
                            initialBitwardenFolderId = initialBitwardenFolderId,
                            showTopBar = false,
                            showFab = false,
                            onFavoriteStateChanged = { isFavorite = it },
                            onCanSaveChanged = { canSave = it },
                            onSaveActionChanged = { onSaveAction = it },
                            onToggleFavoriteActionChanged = { onToggleFavoriteAction = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    stateHolder.SaveableStateProvider("wallet_add_bank") {
                        AddEditBankCardScreen(
                            viewModel = bankCardViewModel,
                            cardId = null,
                            onNavigateBack = onNavigateBack,
                            initialCategoryId = initialCategoryId,
                            initialKeePassDatabaseId = initialKeePassDatabaseId,
                            initialKeePassGroupPath = initialKeePassGroupPath,
                            initialMdbxDatabaseId = initialMdbxDatabaseId,
                            initialMdbxFolderId = initialMdbxFolderId,
                            initialBitwardenVaultId = initialBitwardenVaultId,
                            initialBitwardenFolderId = initialBitwardenFolderId,
                            showTopBar = false,
                            showFab = false,
                            onFavoriteStateChanged = { isFavorite = it },
                            onCanSaveChanged = { canSave = it },
                            onSaveActionChanged = { onSaveAction = it },
                            onToggleFavoriteActionChanged = { onToggleFavoriteAction = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

private data class BitwardenBottomStatusUiState(
    val messageRes: Int? = null,
    val showProgress: Boolean = false
)

private data class BottomMiniHintMessage(
    val id: Long,
    val title: String,
    val supportingText: String? = null
)

private data class MainScreenHandlers(
    val passwordAddOpen: () -> Unit,
    val passwordEditOpen: (Long) -> Unit,
    val inlinePasswordEditorBack: () -> Unit,
    val totpAddOpen: () -> Unit,
    val inlineTotpEditorBack: () -> Unit,
    val bankCardAddOpen: () -> Unit,
    val bankCardEditOpen: (Long) -> Unit,
    val inlineBankCardEditorBack: () -> Unit,
    val documentAddOpen: () -> Unit,
    val documentEditOpen: (Long) -> Unit,
    val inlineDocumentEditorBack: () -> Unit,
    val billingAddressOpen: (Long) -> Unit,
    val billingAddressAddOpen: () -> Unit,
    val billingAddressEditOpen: (Long) -> Unit,
    val inlineBillingAddressEditorBack: () -> Unit,
    val walletAddOpen: () -> Unit,
    val noteOpen: (Long?) -> Unit,
    val inlineNoteEditorBack: () -> Unit,
    val passwordDetailOpen: (Long) -> Unit,
    val totpOpen: (Long) -> Unit,
    val bankCardOpen: (Long) -> Unit,
    val documentOpen: (Long) -> Unit,
    val passkeyOpen: (PasskeyEntry) -> Unit,
    val passkeyUnbind: (PasskeyEntry) -> Unit,
    val confirmPasskeyDelete: () -> Unit,
    val sendOpen: (BitwardenSend) -> Unit,
    val sendAddOpen: () -> Unit,
    val inlineSendEditorBack: () -> Unit,
    val timelineLogOpen: (TimelineEvent.StandardLog) -> Unit
)

// Keep this file as orchestration layer: state wiring + tab routing + pane transitions.
// Feature-specific rendering should stay in dedicated composables/files.
private const val MAX_BOTTOM_MINI_HINTS = 2

@Composable
private fun BottomMiniHintBubble(
    visible: Boolean,
    title: String,
    supportingText: String? = null,
    containerColor: Color,
    textColor: Color
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { it / 3 },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        ) + fadeIn(animationSpec = tween(180)) + scaleIn(
            initialScale = 0.92f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ),
        exit = slideOutVertically(
            targetOffsetY = { it / 3 },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ) + fadeOut(animationSpec = tween(160)) + scaleOut(
            targetScale = 0.96f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 260.dp),
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 2.dp,
            color = containerColor
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (supportingText != null) 2.dp else 0.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!supportingText.isNullOrBlank()) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun buildBitwardenSyncMiniHint(
    context: Context,
    summary: takagi.ru.monica.bitwarden.sync.BitwardenSyncSummary
): BottomMiniHintMessage {
    return BottomMiniHintMessage(
        id = System.currentTimeMillis(),
        title = summary.buildMiniHintTitle(context),
        supportingText = summary.buildMiniHintDetail(context)
    )
}

@Composable
private fun SyncMiniHintBubble(
    visible: Boolean,
    text: String,
    containerColor: Color,
    textColor: Color
) {
    BottomMiniHintBubble(
        visible = visible,
        title = text,
        containerColor = containerColor,
        textColor = textColor
    )
}

@Composable
private fun CustomMiniHintBubble(
    visible: Boolean,
    hint: BottomMiniHintMessage,
    containerColor: Color,
    textColor: Color
) {
    BottomMiniHintBubble(
        visible = visible,
        title = hint.title,
        supportingText = hint.supportingText,
        containerColor = containerColor,
        textColor = textColor
    )
}

private fun isBitwardenPasswordFilter(filter: CategoryFilter): Boolean = when (filter) {
    is CategoryFilter.BitwardenVault,
    is CategoryFilter.BitwardenFolderFilter,
    is CategoryFilter.BitwardenVaultStarred,
    is CategoryFilter.BitwardenVaultUncategorized -> true
    else -> false
}

private fun isBitwardenTotpFilter(filter: takagi.ru.monica.viewmodel.TotpCategoryFilter): Boolean = when (filter) {
    is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVault,
    is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenFolderFilter,
    is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVaultStarred,
    is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVaultUncategorized -> true
    else -> false
}

private fun resolveTrashScopeKeyFromPasswordFilter(filter: CategoryFilter): String {
    return when (filter) {
        is CategoryFilter.BitwardenVault -> "bitwarden_${filter.vaultId}"
        is CategoryFilter.BitwardenFolderFilter -> "bitwarden_${filter.vaultId}"
        is CategoryFilter.BitwardenVaultStarred -> "bitwarden_${filter.vaultId}"
        is CategoryFilter.BitwardenVaultUncategorized -> "bitwarden_${filter.vaultId}"
        is CategoryFilter.KeePassDatabase -> "keepass_${filter.databaseId}"
        is CategoryFilter.KeePassGroupFilter -> "keepass_${filter.databaseId}"
        is CategoryFilter.KeePassDatabaseStarred -> "keepass_${filter.databaseId}"
        is CategoryFilter.KeePassDatabaseUncategorized -> "keepass_${filter.databaseId}"
        else -> "local"
    }
}

private fun resolveBitwardenBottomStatusUiState(
    status: VaultSyncStatus?,
    nowMs: Long
): BitwardenBottomStatusUiState? {
    if (status == null) return null

    if (status.blockedReason == SyncBlockReason.AUTH_REQUIRED) {
        return BitwardenBottomStatusUiState(
            messageRes = R.string.bitwarden_status_auth_required
        )
    }
    if (status.blockedReason == SyncBlockReason.VAULT_LOCKED) {
        return BitwardenBottomStatusUiState(
            messageRes = R.string.bitwarden_status_wait_unlock
        )
    }
    if (status.nextRetryAt != null) {
        return BitwardenBottomStatusUiState(
            messageRes = R.string.bitwarden_status_retrying
        )
    }
    if (status.lastError != null) {
        return BitwardenBottomStatusUiState(
            messageRes = R.string.sync_status_failed_short
        )
    }
    if (status.isRunning) {
        return BitwardenBottomStatusUiState(
            messageRes = R.string.sync_status_syncing_short,
            showProgress = true
        )
    }
    if (status.queuedReason != null) {
        return BitwardenBottomStatusUiState(
            messageRes = R.string.bitwarden_status_queued,
            showProgress = true
        )
    }
    val lastSuccess = status.lastSuccessAt
    if (lastSuccess != null && nowMs - lastSuccess in 0..3000L) {
        return BitwardenBottomStatusUiState(
            messageRes = R.string.bitwarden_status_synced
        )
    }

    return null
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun TimelineDetailPane(
    selectedLog: TimelineEvent.StandardLog,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.history),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = selectedLog.summary,
            style = MaterialTheme.typography.titleMedium
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("类型：${selectedLog.itemType.ifBlank { "-" }}")
                Text("操作：${selectedLog.operationType.ifBlank { "-" }}")
                Text(
                    text = "时间戳：${selectedLog.timestamp}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 带有底部导航的主屏幕
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3WindowSizeClassApi::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class
)
@Composable
fun SimpleMainScreen(
    passwordViewModel: PasswordViewModel,
    settingsViewModel: SettingsViewModel,
    totpViewModel: takagi.ru.monica.viewmodel.TotpViewModel,
    bankCardViewModel: takagi.ru.monica.viewmodel.BankCardViewModel,
    documentViewModel: takagi.ru.monica.viewmodel.DocumentViewModel,
    billingAddressViewModel: BillingAddressViewModel,
    generatorViewModel: GeneratorViewModel = viewModel(), // 添加GeneratorViewModel
    noteViewModel: NoteViewModel = viewModel(),
    bitwardenViewModel: takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel = viewModel(),
    passkeyViewModel: PasskeyViewModel,  // Passkey ViewModel
    localKeePassViewModel: takagi.ru.monica.viewmodel.LocalKeePassViewModel,
    mdbxViewModel: takagi.ru.monica.viewmodel.MdbxViewModel,
    securityManager: SecurityManager,
    onNavigateToAddPassword: (Long?) -> Unit,
    onNavigateToAddWifi: (Long?) -> Unit = {},
    onNavigateToAddSshKey: (Long?) -> Unit = {},
    onNavigateToAddTotp: (Long?) -> Unit,
    onNavigateToQuickTotpScan: () -> Unit,
    pendingSteamQrResult: String? = null,
    pendingSteamQrAccountId: Long? = null,
    onConsumePendingSteamQrResult: () -> Unit = {},
    onScanSteamQrCode: (Long?) -> Unit = {},
    pendingPasswordAuthenticatorQrResult: String? = null,
    onConsumePendingPasswordAuthenticatorQrResult: () -> Unit = {},
    onScanPasswordAuthenticatorQrCode: () -> Unit = {},
    onNavigateToFidoQrScan: () -> Unit,
    onNavigateToAddBankCard: (Long?) -> Unit,
    onNavigateToAddDocument: (Long?) -> Unit,
    onNavigateToAddBillingAddress: (Long?) -> Unit,
    onNavigateToWalletAdd: (CardWalletTab) -> Unit,
    onPreparePasswordAddStorageDefaults: (Long?, Long?, String?, Long?, String?, Long?, String?) -> Unit = { _, _, _, _, _, _, _ -> },
    onPrepareTotpAddStorageDefaults: (Long?, Long?, String?, Long?, String?, Long?, String?) -> Unit = { _, _, _, _, _, _, _ -> },
    onPrepareNoteAddStorageDefaults: (Long?, Long?, String?, Long?, String?, Long?, String?) -> Unit = { _, _, _, _, _, _, _ -> },
    onPrepareWalletAddStorageDefaults: (Long?, Long?, String?, Long?, String?, Long?, String?) -> Unit = { _, _, _, _, _, _, _ -> },
    onNavigateToAddNote: (Long?) -> Unit,
    onNavigateToSearchedNote: (Long, String) -> Unit = { noteId, _ -> onNavigateToAddNote(noteId) },
    onNavigateToNoteDetail: (Long) -> Unit = {},
    onNavigateToPasswordDetail: (Long) -> Unit = {},
    onNavigateToPasskeyDetail: (Long) -> Unit,
    onNavigateToMdbxCommitHistory: (Long) -> Unit = {},
    onNavigateToBankCardDetail: (Long) -> Unit, // Add this
    onNavigateToDocumentDetail: (Long) -> Unit, // Keep this
    onNavigateToBillingAddressDetail: (Long) -> Unit,
    onNavigateToChangePassword: () -> Unit = {},
    onNavigateToSecurityQuestion: () -> Unit = {},
    onNavigateToMasterPasswordLocking: () -> Unit = {},
    onNavigateToSyncBackup: () -> Unit = {},
    onNavigateToAutofill: () -> Unit = {},
    onNavigateToPasskeySettings: () -> Unit = {},
    onNavigateToBottomNavSettings: () -> Unit = {},
    onNavigateToColorScheme: () -> Unit = {},
    onSecurityAnalysis: () -> Unit = {},
    onNavigateToDeveloperSettings: () -> Unit = {},
    onNavigateToPermissionManagement: () -> Unit = {},
    onNavigateToMonicaPlus: () -> Unit = {},
    onNavigateToExtensions: () -> Unit = {},
    onNavigateToCommonAccountTemplates: () -> Unit = {},
    onNavigateToPageCustomization: () -> Unit = {},
    onNavigateToStandaloneSettings: () -> Unit = {},
    onNavigateToBitwardenLogin: () -> Unit = {},
    onNavigateToAddSend: () -> Unit = {},
    onClearAllData: (Boolean, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit,
    initialTab: Int = 0
) {

    // --- ViewModel wiring and global app-level state ---
    val timelineViewModel: TimelineViewModel = viewModel()
    
    // 双击返回退出相关状态
    var backPressedOnce by remember { mutableStateOf(false) }
    var passwordHistoryPageMode by rememberSaveable { mutableStateOf(PasswordHistoryPageMode.NONE) }
    var passwordHistoryInitialTrashScopeKey by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitwardenRepository = remember { takagi.ru.monica.bitwarden.repository.BitwardenRepository.getInstance(context) }
    val appSettings by settingsViewModel.settings.collectAsState()
    val passwordPageVisibleContentTypes = remember(
        appSettings.passwordPageAggregateEnabled,
        appSettings.passwordPageVisibleContentTypes
    ) {
        resolvePasswordPageVisibleTypes(
            aggregateEnabled = appSettings.passwordPageAggregateEnabled,
            configuredTypes = appSettings.passwordPageVisibleContentTypes
        )
    }
    var passwordPageSelectedContentTypes by rememberSaveable(
        stateSaver = passwordPageContentTypeSetSaver
    ) {
        mutableStateOf(emptySet())
    }
    
    // --- Global back behavior ---
    // 处理返回键 - 需要按两次才能退出
    // 只有在没有子页面（如添加页面）打开时才启用
    // FAB 展开状态由内部 SwipeableAddFab 管理，这里不需要干预，除非我们需要在 FAB 展开时拦截返回键
    // 目前 SwipeableAddFab 应该自己处理了返回键（如果有 BackHandler）
    // 为了安全起见，我们只在最外层处理
    BackHandler(enabled = true) {
        if (passwordHistoryPageMode.isVisible) {
            passwordHistoryPageMode = PasswordHistoryPageMode.NONE
            passwordHistoryInitialTrashScopeKey = null
            return@BackHandler
        }
        if (backPressedOnce) {
            // 第二次按返回键,退出应用
            (context as? android.app.Activity)?.finish()
        } else {
            // 第一次按返回键,显示提示
            backPressedOnce = true
            Toast.makeText(
                context,
                context.getString(R.string.press_back_again_to_exit),
                Toast.LENGTH_SHORT
            ).show()
            
            // 2秒后重置状态
            scope.launch {
                delay(2000)
                backPressedOnce = false
            }
        }
    }
    
    // --- Cross-tab selection/action-bar state ---
    // 密码列表的选择模式状态
    var isPasswordSelectionMode by remember { mutableStateOf(false) }
    var selectedPasswordCount by remember { mutableIntStateOf(0) }
    var onExitPasswordSelection by remember { mutableStateOf({}) }
    var onSelectAllPasswords by remember { mutableStateOf({}) }
    var onFavoriteSelectedPasswords by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onMoveToCategoryPasswords by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onManualStackPasswords by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onDeleteSelectedPasswords by remember { mutableStateOf({}) }
    var passwordListShowBackToTop by remember { mutableStateOf(false) }
    var passwordScrollToTopRequestKey by remember { mutableIntStateOf(0) }
    var showPasswordQuickAccessSheet by rememberSaveable { mutableStateOf(false) }
    val retainedStateViewModel: VaultV2RetainedStateViewModel = viewModel()
    val vaultV2PaneState = rememberVaultV2PaneState(retainedStateViewModel.retainedState)
    val isPasswordVaultAuthenticated by passwordViewModel.isAuthenticated.collectAsState()

    LaunchedEffect(isPasswordVaultAuthenticated) {
        if (!isPasswordVaultAuthenticated) {
            vaultV2PaneState.clearRetainedListSnapshots()
        }
    }
    
    LaunchedEffect(passwordPageVisibleContentTypes) {
        passwordPageSelectedContentTypes = sanitizeSelectedPasswordPageTypes(
            visibleTypes = passwordPageVisibleContentTypes,
            selectedTypes = passwordPageSelectedContentTypes
        )
    }
    
    // 密码分组模式: smart(备注>网站>应用>标题), note, website, app, title
    // 从设置中读取，如果设置中没有则默认为 "smart"
    val passwordGroupMode = appSettings.passwordGroupMode


    // 堆叠卡片显示模式: 自动/始终展开（始终展开指逐条显示，不堆叠）
    // 从设置中读取，如果设置中没有则默认为 AUTO
    val stackCardModeKey = appSettings.stackCardMode
    val stackCardMode = remember(stackCardModeKey) {
        runCatching { StackCardMode.valueOf(stackCardModeKey) }.getOrDefault(StackCardMode.AUTO)
    }
    
    // TOTP的选择模式状态
    var isTotpSelectionMode by remember { mutableStateOf(false) }
    var selectedTotpCount by remember { mutableIntStateOf(0) }
    var onExitTotpSelection by remember { mutableStateOf({}) }
    var onSelectAllTotp by remember { mutableStateOf({}) }
    var onMoveToCategoryTotp by remember { mutableStateOf({}) }
    var onDeleteSelectedTotp by remember { mutableStateOf({}) }
    
    // 证件的选择模式状态
    var isDocumentSelectionMode by remember { mutableStateOf(false) }
    var selectedDocumentCount by remember { mutableIntStateOf(0) }
    var onExitDocumentSelection by remember { mutableStateOf({}) }
    var onSelectAllDocuments by remember { mutableStateOf({}) }
    var onMoveToCategoryDocuments by remember { mutableStateOf({}) }
    var onDeleteSelectedDocuments by remember { mutableStateOf({}) }
    
    // 银行卡的选择模式状态
    var isBankCardSelectionMode by remember { mutableStateOf(false) }
    var selectedBankCardCount by remember { mutableIntStateOf(0) }
    var onExitBankCardSelection by remember { mutableStateOf({}) }
    var onSelectAllBankCards by remember { mutableStateOf({}) }
    var onMoveToCategoryBankCards by remember { mutableStateOf({}) }
    var onDeleteSelectedBankCards by remember { mutableStateOf({}) }
    var onFavoriteBankCards by remember { mutableStateOf({}) }  // 添加收藏回调

    // CardWallet state
    var cardWalletSubTab by rememberSaveable { mutableStateOf(CardWalletTab.ALL) }
    var cardWalletBitwardenVaultId by rememberSaveable { mutableStateOf<Long?>(null) }
    var walletUnifiedAddType by rememberSaveable { mutableStateOf(CardWalletTab.BANK_CARDS) }
    val walletAddSaveableStateHolder = rememberSaveableStateHolder()
    val cardWalletSaveableStateHolder = rememberSaveableStateHolder()

    val bottomNavVisibility = appSettings.bottomNavVisibility

    val combinedAuthenticatorVisible =
        bottomNavVisibility.authenticator || bottomNavVisibility.passkey
    val dataTabItems = appSettings.bottomNavOrder
        .map { tab ->
            if (tab == BottomNavContentTab.PASSKEY) BottomNavContentTab.AUTHENTICATOR else tab
        }
        .distinct()
        .filter { tab ->
            if (tab == BottomNavContentTab.AUTHENTICATOR) {
                combinedAuthenticatorVisible
            } else {
                bottomNavVisibility.isVisible(tab)
            }
        }
        .map { it.toBottomNavItem() }
    val shouldHideBottomNavigation = appSettings.autoHideBottomNavWhenSingleTab && dataTabItems.size == 1

    val tabs = buildList {
        addAll(dataTabItems)
        if (!shouldHideBottomNavigation) {
            add(BottomNavItem.Settings)
        }
    }

    val defaultTabKey = remember(initialTab, tabs) { 
        if (initialTab == 0 && tabs.isNotEmpty()) {
            tabs.first().key
        } else {
            indexToDefaultTabKey(initialTab) 
        }
    }
    var selectedTabKey by rememberSaveable { mutableStateOf(defaultTabKey) }
    var startupAutoTabApplied by rememberSaveable { mutableStateOf(false) }
    val hasCustomBottomNavConfig = remember(appSettings.bottomNavOrder, bottomNavVisibility) {
        appSettings.bottomNavOrder != BottomNavContentTab.DEFAULT_ORDER ||
            bottomNavVisibility != takagi.ru.monica.data.BottomNavVisibility()
    }

    LaunchedEffect(tabs) {
        val passkeyHostedByAuthenticator =
            selectedTabKey == BottomNavItem.Passkey.key &&
                tabs.any { it == BottomNavItem.Authenticator }
        if (!passkeyHostedByAuthenticator && tabs.none { it.key == selectedTabKey }) {
            selectedTabKey = tabs.first().key
        }
    }
    LaunchedEffect(initialTab, tabs, hasCustomBottomNavConfig, startupAutoTabApplied) {
        if (
            initialTab == 0 &&
            !startupAutoTabApplied &&
            hasCustomBottomNavConfig &&
            tabs.isNotEmpty()
        ) {
            selectedTabKey = tabs.first().key
            startupAutoTabApplied = true
        }
    }

    val currentTab = if (
        selectedTabKey == BottomNavItem.Passkey.key &&
        tabs.any { it == BottomNavItem.Authenticator }
    ) {
        BottomNavItem.Passkey
    } else {
        tabs.firstOrNull { it.key == selectedTabKey } ?: tabs.first()
    }
    val selectedDockTab = if (currentTab == BottomNavItem.Passkey) {
        BottomNavItem.Authenticator
    } else {
        currentTab
    }

    BackHandler(enabled = currentTab == BottomNavItem.Passkey) {
        selectedTabKey = BottomNavItem.Authenticator.key
    }

    LaunchedEffect(currentTab.key) {
        if (currentTab != BottomNavItem.CardWallet) {
            cardWalletBitwardenVaultId = null
        }
    }

    var passwordPaneState by rememberSaveable(stateSaver = PasswordPaneUiStateSaver) {
        mutableStateOf(PasswordPaneUiState())
    }
    var totpPaneState by rememberSaveable(stateSaver = TotpPaneUiStateSaver) {
        mutableStateOf(TotpPaneUiState())
    }
    var cardWalletPaneState by rememberSaveable(stateSaver = CardWalletPaneUiStateSaver) {
        mutableStateOf(CardWalletPaneUiState())
    }
    var notePaneState by rememberSaveable(stateSaver = NotePaneUiStateSaver) {
        mutableStateOf(NotePaneUiState())
    }
    var sendPaneState by remember { mutableStateOf(SendPaneUiState()) }
    var selectedPasskey by remember { mutableStateOf<PasskeyEntry?>(null) }
    var vaultV2DetailKind by rememberSaveable { mutableStateOf<VaultV2DetailKind?>(null) }
    var pendingPasskeyDelete by remember { mutableStateOf<PasskeyEntry?>(null) }
    var selectedTimelineLog by remember { mutableStateOf<TimelineEvent.StandardLog?>(null) }
    val sendState by bitwardenViewModel.sendState.collectAsState()
    val activeBitwardenVault by bitwardenViewModel.activeVault.collectAsState()
    val bitwardenSyncStatusByVault by bitwardenViewModel.syncStatusByVault.collectAsState()
    val totpFilter by totpViewModel.categoryFilter.collectAsState()
    val totpNewItemDefaults = remember(totpFilter) { defaultsFromTotpFilter(totpFilter) }
    var pendingInlinePasswordAddStorageDefaults by remember { mutableStateOf<NewItemStorageDefaults?>(null) }
    var pendingInlineTotpAddStorageDefaults by remember { mutableStateOf<NewItemStorageDefaults?>(null) }
    var pendingInlineNoteAddStorageDefaults by remember { mutableStateOf<NewItemStorageDefaults?>(null) }
    var pendingInlineWalletAddStorageDefaults by remember { mutableStateOf<NewItemStorageDefaults?>(null) }
    val selectedGeneratorType by generatorViewModel.selectedGenerator.collectAsState()
    val symbolGeneratorResult by generatorViewModel.symbolResult.collectAsState()
    val passwordGeneratorResult by generatorViewModel.passwordResult.collectAsState()
    val passphraseGeneratorResult by generatorViewModel.passphraseResult.collectAsState()
    val pinGeneratorResult by generatorViewModel.pinResult.collectAsState()
    val sshKeyGeneratorResult by generatorViewModel.sshKeyResult.collectAsState()
    val currentGeneratorResult = when (selectedGeneratorType) {
        GeneratorType.SYMBOL -> symbolGeneratorResult
        GeneratorType.PASSWORD -> passwordGeneratorResult
        GeneratorType.PASSPHRASE -> passphraseGeneratorResult
        GeneratorType.PIN -> pinGeneratorResult
        GeneratorType.SSH_KEY -> sshKeyGeneratorResult?.fingerprintSha256.orEmpty()
    }

    val selectedPasswordId = passwordPaneState.selectedPasswordId
    val inlinePasswordEditorId = passwordPaneState.inlinePasswordEditorId
    val isAddingPasswordInline = passwordPaneState.isAddingPasswordInline
    val selectedTotpId = totpPaneState.selectedTotpId
    val isAddingTotpInline = totpPaneState.isAddingInline
    val selectedBankCardId = cardWalletPaneState.selectedBankCardId
    val inlineBankCardEditorId = cardWalletPaneState.inlineBankCardEditorId
    val isAddingBankCardInline = cardWalletPaneState.isAddingBankCardInline
    val selectedDocumentId = cardWalletPaneState.selectedDocumentId
    val inlineDocumentEditorId = cardWalletPaneState.inlineDocumentEditorId
    val isAddingDocumentInline = cardWalletPaneState.isAddingDocumentInline
    val selectedBillingAddressId = cardWalletPaneState.selectedBillingAddressId
    val inlineBillingAddressEditorId = cardWalletPaneState.inlineBillingAddressEditorId
    val isAddingBillingAddressInline = cardWalletPaneState.isAddingBillingAddressInline
    val inlineNoteEditorId = notePaneState.inlineNoteEditorId
    val isAddingNoteInline = notePaneState.isAddingInline
    val selectedSend = sendPaneState.selectedSend
    val isAddingSendInline = sendPaneState.isAddingInline
    val resetPasswordPaneState: () -> Unit = {
        pendingInlinePasswordAddStorageDefaults = null
        passwordPaneState = PasswordPaneUiStateTransitions.reset()
    }
    val openInlinePasswordAdd: () -> Unit = {
        passwordPaneState = PasswordPaneUiStateTransitions.openInlineAdd()
    }
    val openInlinePasswordEditor: (Long) -> Unit = { passwordId ->
        passwordPaneState = PasswordPaneUiStateTransitions.openInlineEditor(passwordId)
    }
    val openInlinePasswordDetail: (Long) -> Unit = { passwordId ->
        passwordPaneState = PasswordPaneUiStateTransitions.openDetail(passwordId)
    }
    val closeInlinePasswordEditor: () -> Unit = {
        pendingInlinePasswordAddStorageDefaults = null
        passwordPaneState = PasswordPaneUiStateTransitions.closeInlineEditor(passwordPaneState)
    }
    val clearSelectedPasswordPaneItem: () -> Unit = {
        passwordPaneState = PasswordPaneUiStateTransitions.clearSelected(passwordPaneState)
    }
    val resetTotpPaneState: () -> Unit = {
        pendingInlineTotpAddStorageDefaults = null
        totpPaneState = TotpPaneUiStateTransitions.reset()
    }
    val openInlineTotpAdd: () -> Unit = {
        totpPaneState = TotpPaneUiStateTransitions.openInlineAdd()
    }
    val openInlineTotpDetail: (Long) -> Unit = { totpId ->
        totpPaneState = TotpPaneUiStateTransitions.openDetail(totpId)
    }
    val resetCardWalletPaneState: () -> Unit = {
        pendingInlineWalletAddStorageDefaults = null
        cardWalletPaneState = CardWalletPaneUiStateTransitions.resetAll()
    }
    val openInlineBankCardAdd: () -> Unit = {
        cardWalletPaneState = CardWalletPaneUiStateTransitions.openBankCardAddInline()
    }
    val openInlineBankCardEditor: (Long) -> Unit = { cardId ->
        cardWalletPaneState = CardWalletPaneUiStateTransitions.openBankCardEditInline(cardId)
    }
    val openInlineBankCardDetail: (Long) -> Unit = { cardId ->
        cardWalletPaneState = CardWalletPaneUiStateTransitions.openBankCardDetail(cardId)
    }
    val closeInlineBankCardEditor: () -> Unit = {
        pendingInlineWalletAddStorageDefaults = null
        cardWalletPaneState = CardWalletPaneUiStateTransitions.closeBankCardEditor(cardWalletPaneState)
    }
    val clearSelectedBankCardPaneItem: () -> Unit = {
        cardWalletPaneState = CardWalletPaneUiStateTransitions.clearSelectedBankCard(cardWalletPaneState)
    }
    val openInlineDocumentAdd: () -> Unit = {
        cardWalletPaneState = CardWalletPaneUiStateTransitions.openDocumentAddInline()
    }
    val openInlineDocumentEditor: (Long) -> Unit = { documentId ->
        cardWalletPaneState = CardWalletPaneUiStateTransitions.openDocumentEditInline(documentId)
    }
    val openInlineDocumentDetail: (Long) -> Unit = { documentId ->
        cardWalletPaneState = CardWalletPaneUiStateTransitions.openDocumentDetail(documentId)
    }
    val closeInlineDocumentEditor: () -> Unit = {
        pendingInlineWalletAddStorageDefaults = null
        cardWalletPaneState = CardWalletPaneUiStateTransitions.closeDocumentEditor(cardWalletPaneState)
    }
    val clearSelectedDocumentPaneItem: () -> Unit = {
        cardWalletPaneState = CardWalletPaneUiStateTransitions.clearSelectedDocument(cardWalletPaneState)
    }
    val openInlineBillingAddressAdd: () -> Unit = {
        cardWalletPaneState = CardWalletPaneUiStateTransitions.openBillingAddressAddInline()
    }
    val openInlineBillingAddressEditor: (Long) -> Unit = { addressId ->
        cardWalletPaneState = CardWalletPaneUiStateTransitions.openBillingAddressEditInline(addressId)
    }
    val openInlineBillingAddressDetail: (Long) -> Unit = { addressId ->
        cardWalletPaneState = CardWalletPaneUiStateTransitions.openBillingAddressDetail(addressId)
    }
    val closeInlineBillingAddressEditor: () -> Unit = {
        pendingInlineWalletAddStorageDefaults = null
        cardWalletPaneState = CardWalletPaneUiStateTransitions.closeBillingAddressEditor(cardWalletPaneState)
    }
    val clearSelectedBillingAddressPaneItem: () -> Unit = {
        cardWalletPaneState = CardWalletPaneUiStateTransitions.clearSelectedBillingAddress(cardWalletPaneState)
    }
    val resetDocumentPaneState: () -> Unit = {
        pendingInlineWalletAddStorageDefaults = null
        cardWalletPaneState = CardWalletPaneUiStateTransitions.resetDocumentPane(cardWalletPaneState)
    }
    val resetBankCardPaneState: () -> Unit = {
        pendingInlineWalletAddStorageDefaults = null
        cardWalletPaneState = CardWalletPaneUiStateTransitions.resetBankCardPane(cardWalletPaneState)
    }
    val resetBillingAddressPaneState: () -> Unit = {
        pendingInlineWalletAddStorageDefaults = null
        cardWalletPaneState = CardWalletPaneUiStateTransitions.resetBillingAddressPane(cardWalletPaneState)
    }
    val resetNotePaneState: () -> Unit = {
        pendingInlineNoteAddStorageDefaults = null
        notePaneState = NotePaneUiStateTransitions.reset()
    }
    val openInlineNoteEditor: (Long?) -> Unit = { noteId ->
        notePaneState = NotePaneUiStateTransitions.openInlineEditor(noteId)
    }
    val resetSendPaneState: () -> Unit = {
        sendPaneState = SendPaneUiStateTransitions.reset()
    }
    val openInlineSendDetail: (BitwardenSend) -> Unit = { send ->
        sendPaneState = SendPaneUiStateTransitions.openDetail(send)
    }
    val openInlineSendAdd: () -> Unit = {
        sendPaneState = SendPaneUiStateTransitions.openInlineAdd()
    }
    val closeInlineSendEditor: () -> Unit = {
        sendPaneState = SendPaneUiStateTransitions.closeInlineEditor(sendPaneState)
    }

    // 监听滚动以隐藏/显示 FAB
    var isFabVisible by remember { mutableStateOf(true) }
    
    // 如果设置中禁用了此功能，强制显示 FAB
    LaunchedEffect(appSettings.hideFabOnScroll) {
        if (!appSettings.hideFabOnScroll) {
            isFabVisible = true
        }
    }

    // 监听 FAB 展开状态，展开时禁用隐藏逻辑
    var isFabExpanded by remember { mutableStateOf(false) }
    var isFastScrollStripVisible by rememberSaveable(currentTab) { mutableStateOf(false) }
    // 使用 rememberUpdatedState 确保 currentTab 始终是最新的
    val currentTabState = rememberUpdatedState(currentTab)
    // 确保滚动监听器能获取到最新的设置值
    val hideFabOnScrollState = rememberUpdatedState(appSettings.hideFabOnScroll)
    val fastScrollStripVisibleState = rememberUpdatedState(isFastScrollStripVisible)

    // 检测是否有任何选择模式处于激活状态
    var isNoteSelectionMode by remember { mutableStateOf(false) }
    val isAnySelectionMode =
        isPasswordSelectionMode ||
            isTotpSelectionMode ||
            isDocumentSelectionMode ||
            isBankCardSelectionMode ||
            isNoteSelectionMode ||
            vaultV2PaneState.selectionCount > 0
    var generatorRefreshRequestKey by remember { mutableIntStateOf(0) }
    
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            // 使用 onPostScroll 代替 onPreScroll
            // 只有当子视图实际消费了滚动事件时（即真正滚动了内容），我们才根据方向判断显隐
            // 这样可以解决：
            // 1. 在页面顶部无法上滑时，FAB 不会错误隐藏
            // 2. 内容太少不足以滚动时，FAB 保持显示
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // 如果功能未开启，直接返回
                if (!hideFabOnScrollState.value) return Offset.Zero

                // 如果 FAB 已展开，或当前正在使用快滑条，不要触发自动隐藏
                if (isFabExpanded || fastScrollStripVisibleState.value) return Offset.Zero

                val tab = currentTabState.value
                if (tab == BottomNavItem.Passwords || 
                    tab == BottomNavItem.Authenticator || 
                    tab == BottomNavItem.CardWallet ||
                    tab == BottomNavItem.Generator ||
                    tab == BottomNavItem.Send) {
                    
                    // consumed.y < 0 表示内容向上滚动（手指上滑，查看下方内容） -> 隐藏
                    if (consumed.y < -15f) {
                        isFabVisible = false
                    } 
                    // consumed.y > 0 表示内容向下滚动（手指下滑，回到顶部） -> 显示
                    // 注意：如果是 available.y > 0 但 consumed.y == 0，说明已经到顶滑不动了，
                    // 这种情况下我们也不隐藏（保持原状或强制显示），通常保持原状即可，
                    // 但为了体验，如果在顶部尝试下滑（即使没动），也可以强制显示
                    else if (consumed.y > 15f || (available.y > 0f && consumed.y == 0f)) {
                         isFabVisible = true
                    }
                }
                return Offset.Zero
            }
        }
    }

    val currentFilter by passwordViewModel.categoryFilter.collectAsState()
    val passwordNewItemDefaults = remember(currentFilter) { defaultsFromPasswordFilter(currentFilter) }
    val openHistoryPage: () -> Unit = {
        passwordHistoryInitialTrashScopeKey = null
        passwordHistoryPageMode = PasswordHistoryPageMode.TIMELINE
    }
    val openTrashPage: () -> Unit = {
        passwordHistoryInitialTrashScopeKey = null
        passwordHistoryPageMode = PasswordHistoryPageMode.TRASH
    }
    val closeHistoryPage: () -> Unit = {
        passwordHistoryPageMode = PasswordHistoryPageMode.NONE
        passwordHistoryInitialTrashScopeKey = null
    }
    val isPasskeyDataNeeded = currentTab == BottomNavItem.Passkey ||
        currentTab == BottomNavItem.VaultV2 ||
        selectedPasskey != null ||
        pendingPasskeyDelete != null
    val isQuickAccessDataNeeded = appSettings.passwordListQuickAccessEnabled || showPasswordQuickAccessSheet
    val shouldCollectAllPasswords = isQuickAccessDataNeeded || isPasskeyDataNeeded
    val allPasswords = if (shouldCollectAllPasswords) {
        passwordViewModel.allPasswordsForUi.collectAsState(initial = emptyList()).value
    } else {
        emptyList()
    }
    val passwordQuickAccessManager = remember(context) { PasswordQuickAccessManager(context) }
    val passwordQuickAccessStats = if (isQuickAccessDataNeeded) {
        passwordQuickAccessManager.statsFlow.collectAsState(initial = emptyMap()).value
    } else {
        emptyMap()
    }
    val passwordQuickAccessItems = remember(
        allPasswords,
        passwordQuickAccessStats,
        isQuickAccessDataNeeded
    ) {
        if (!isQuickAccessDataNeeded) {
            emptyList()
        } else {
            allPasswords.mapNotNull { entry ->
                val stat = passwordQuickAccessStats[entry.id] ?: return@mapNotNull null
                PasswordQuickAccessItem(
                    entry = entry,
                    openCount = stat.openCount,
                    lastOpenedAt = stat.lastOpenedAt
                )
            }
        }
    }
    val recentOpenedPasswords = remember(passwordQuickAccessItems) {
        rankRecentPasswordQuickAccessItems(passwordQuickAccessItems)
    }
    val frequentOpenedPasswords = remember(passwordQuickAccessItems) {
        rankFrequentPasswordQuickAccessItems(passwordQuickAccessItems)
    }
    val localPasskeys = if (isPasskeyDataNeeded) {
        passkeyViewModel.allPasskeys.collectAsState(initial = emptyList()).value
    } else {
        emptyList()
    }
    val passkeyTotalCount = localPasskeys.size
    val passkeyBoundCount = localPasskeys.count { it.boundPasswordId != null }
    val passwordById = remember(allPasswords, isPasskeyDataNeeded) {
        if (isPasskeyDataNeeded) {
            allPasswords.associateBy { it.id }
        } else {
            emptyMap()
        }
    }
    val keepassDatabases by localKeePassViewModel.allDatabases.collectAsState()
    val mdbxDatabases by mdbxViewModel.allDatabases.collectAsState()
    val bitwardenVaults by bitwardenViewModel.vaults.collectAsState()
    val selectedMdbxDatabaseId = remember(currentFilter) {
        when (val filter = currentFilter) {
            is CategoryFilter.MdbxDatabase -> filter.databaseId
            is CategoryFilter.MdbxFolderFilter -> filter.databaseId
            else -> null
        }
    }
    LaunchedEffect(selectedMdbxDatabaseId, mdbxDatabases.map { it.id }) {
        selectedMdbxDatabaseId?.let { databaseId ->
            if (mdbxDatabases.any { it.id == databaseId }) {
                mdbxViewModel.activateMdbxDatabase(databaseId)
            }
        }
    }
    // 可拖拽导航栏模式开关 (将来可从设置中读取)
    val useDraggableNav = appSettings.useDraggableBottomNav
    
    // 构建导航项列表 (用于可拖拽导航栏)
    val draggableNavItems = remember(tabs, selectedDockTab) {
        tabs.map { item ->
            DraggableNavItem(
                key = item.key,
                icon = item.icon,
                labelRes = item.shortLabelRes(),
                selected = item.key == selectedDockTab.key,
                onClick = { selectedTabKey = item.key }
            )
        }
    }
    
    val activity = LocalContext.current.findActivity()
    val widthSizeClass = activity?.let { calculateWindowSizeClass(it).widthSizeClass }
    val isCompactWidth = widthSizeClass == null || widthSizeClass == WindowWidthSizeClass.Compact
    val wideListPaneWidth = 400.dp
    val wideNavigationRailWidth = 80.dp
    val wideFabHostWidth = wideNavigationRailWidth + wideListPaneWidth

    val clearVaultV2WideDetail: () -> Unit = {
        vaultV2DetailKind = null
        resetPasswordPaneState()
        resetTotpPaneState()
        resetCardWalletPaneState()
        resetNotePaneState()
        selectedPasskey = null
    }
    fun prepareVaultV2WideDetail(kind: VaultV2DetailKind) {
        if (isCompactWidth || currentTab != BottomNavItem.VaultV2) return
        clearVaultV2WideDetail()
        vaultV2DetailKind = kind
    }
    val vaultV2HasWideDetail =
        !isCompactWidth && currentTab == BottomNavItem.VaultV2 && vaultV2DetailKind != null
    val vaultV2SuppressesFab = vaultV2HasWideDetail && when (vaultV2DetailKind) {
        VaultV2DetailKind.PASSWORD -> isAddingPasswordInline || inlinePasswordEditorId != null
        VaultV2DetailKind.AUTHENTICATOR -> isAddingTotpInline || selectedTotpId != null
        VaultV2DetailKind.BANK_CARD -> isAddingBankCardInline ||
            inlineBankCardEditorId != null || selectedBankCardId != null
        VaultV2DetailKind.DOCUMENT -> isAddingDocumentInline ||
            inlineDocumentEditorId != null || selectedDocumentId != null
        VaultV2DetailKind.BILLING_ADDRESS -> isAddingBillingAddressInline ||
            inlineBillingAddressEditorId != null || selectedBillingAddressId != null
        VaultV2DetailKind.NOTE -> isAddingNoteInline || inlineNoteEditorId != null
        VaultV2DetailKind.PASSKEY, null -> false
    }
    LaunchedEffect(currentTab.key, isCompactWidth) {
        if (currentTab != BottomNavItem.VaultV2 || isCompactWidth) {
            clearVaultV2WideDetail()
        }
    }

    // --- Navigation/interaction handlers hub ---
    // This function centralizes open/edit/back intents so tab/pane switching stays consistent.
    fun buildMainScreenHandlers(): MainScreenHandlers {
        val handlePasswordAddOpen: () -> Unit = {
            val resolvedDefaults = passwordNewItemDefaults.takeIf { it.hasAnyValue() }
            if (isCompactWidth) {
                pendingInlinePasswordAddStorageDefaults = null
                onPreparePasswordAddStorageDefaults(
                    resolvedDefaults?.categoryId,
                    resolvedDefaults?.keepassDatabaseId,
                    resolvedDefaults?.keepassGroupPath,
                    resolvedDefaults?.mdbxDatabaseId,
                    resolvedDefaults?.mdbxFolderId,
                    resolvedDefaults?.bitwardenVaultId,
                    resolvedDefaults?.bitwardenFolderId
                )
                onNavigateToAddPassword(null)
            } else {
                prepareVaultV2WideDetail(VaultV2DetailKind.PASSWORD)
                pendingInlinePasswordAddStorageDefaults = resolvedDefaults
                openInlinePasswordAdd()
            }
        }
        val handlePasswordEditOpen: (Long) -> Unit = { passwordId ->
            if (isCompactWidth) {
                onNavigateToAddPassword(passwordId)
            } else {
                prepareVaultV2WideDetail(VaultV2DetailKind.PASSWORD)
                openInlinePasswordEditor(passwordId)
            }
        }
        val handleInlinePasswordEditorBack: () -> Unit = {
            closeInlinePasswordEditor()
        }
        val handleTotpAddOpen: () -> Unit = {
            if (isCompactWidth) {
                onNavigateToAddTotp(null)
            } else {
                prepareVaultV2WideDetail(VaultV2DetailKind.AUTHENTICATOR)
                openInlineTotpAdd()
            }
        }
        val handleInlineTotpEditorBack: () -> Unit = {
            pendingInlineTotpAddStorageDefaults = null
            resetTotpPaneState()
        }
        val handleBankCardAddOpen: () -> Unit = {
            if (isCompactWidth) {
                onNavigateToAddBankCard(null)
            } else {
                prepareVaultV2WideDetail(VaultV2DetailKind.BANK_CARD)
                openInlineBankCardAdd()
            }
        }
        val handleBankCardEditOpen: (Long) -> Unit = { cardId ->
            if (isCompactWidth) {
                onNavigateToAddBankCard(cardId)
            } else {
                prepareVaultV2WideDetail(VaultV2DetailKind.BANK_CARD)
                openInlineBankCardEditor(cardId)
            }
        }
        val handleInlineBankCardEditorBack: () -> Unit = {
            pendingInlineWalletAddStorageDefaults = null
            closeInlineBankCardEditor()
        }
        val handleDocumentAddOpen: () -> Unit = {
            if (isCompactWidth) {
                onNavigateToAddDocument(null)
            } else {
                prepareVaultV2WideDetail(VaultV2DetailKind.DOCUMENT)
                openInlineDocumentAdd()
            }
        }
        val handleDocumentEditOpen: (Long) -> Unit = { documentId ->
            if (isCompactWidth) {
                onNavigateToAddDocument(documentId)
            } else {
                prepareVaultV2WideDetail(VaultV2DetailKind.DOCUMENT)
                openInlineDocumentEditor(documentId)
            }
        }
        val handleInlineDocumentEditorBack: () -> Unit = {
            pendingInlineWalletAddStorageDefaults = null
            closeInlineDocumentEditor()
        }
        val handleBillingAddressAddOpen: () -> Unit = {
            if (isCompactWidth) {
                onNavigateToAddBillingAddress(null)
            } else {
                prepareVaultV2WideDetail(VaultV2DetailKind.BILLING_ADDRESS)
                openInlineBillingAddressAdd()
            }
        }
        val handleBillingAddressEditOpen: (Long) -> Unit = { addressId ->
            if (isCompactWidth) {
                onNavigateToAddBillingAddress(addressId)
            } else {
                prepareVaultV2WideDetail(VaultV2DetailKind.BILLING_ADDRESS)
                openInlineBillingAddressEditor(addressId)
            }
        }
        val handleInlineBillingAddressEditorBack: () -> Unit = {
            pendingInlineWalletAddStorageDefaults = null
            closeInlineBillingAddressEditor()
        }
        val handleWalletAddOpen: () -> Unit = {
            when (cardWalletSubTab) {
                CardWalletTab.BANK_CARDS -> handleBankCardAddOpen()
                CardWalletTab.DOCUMENTS -> handleDocumentAddOpen()
                CardWalletTab.BILLING_ADDRESSES -> handleBillingAddressAddOpen()
                CardWalletTab.ALL -> {
                    if (isCompactWidth) {
                        onNavigateToWalletAdd(walletUnifiedAddType)
                    } else {
                        openInlineBankCardAdd()
                    }
                }
            }
        }
        val handleNoteOpen: (Long?) -> Unit = { noteId ->
            if (isCompactWidth) {
                onNavigateToAddNote(noteId)
            } else {
                prepareVaultV2WideDetail(VaultV2DetailKind.NOTE)
                openInlineNoteEditor(noteId)
            }
        }
        val handleInlineNoteEditorBack: () -> Unit = {
            pendingInlineNoteAddStorageDefaults = null
            resetNotePaneState()
        }
        val handlePasswordDetailOpen: (Long) -> Unit = { passwordId ->
            if (appSettings.passwordListQuickAccessEnabled) {
                scope.launch {
                    passwordQuickAccessManager.recordOpen(passwordId)
                }
            }
            if (isCompactWidth) {
                onNavigateToPasswordDetail(passwordId)
            } else {
                prepareVaultV2WideDetail(VaultV2DetailKind.PASSWORD)
                openInlinePasswordDetail(passwordId)
            }
        }
        val handleTotpOpen: (Long) -> Unit = { totpId ->
            if (isCompactWidth) {
                onNavigateToAddTotp(totpId)
            } else {
                prepareVaultV2WideDetail(VaultV2DetailKind.AUTHENTICATOR)
                openInlineTotpDetail(totpId)
            }
        }
        val handleBankCardOpen: (Long) -> Unit = { cardId ->
            if (isCompactWidth) {
                onNavigateToBankCardDetail(cardId)
            } else {
                prepareVaultV2WideDetail(VaultV2DetailKind.BANK_CARD)
                openInlineBankCardDetail(cardId)
            }
        }
        val handleDocumentOpen: (Long) -> Unit = { documentId ->
            if (isCompactWidth) {
                onNavigateToDocumentDetail(documentId)
            } else {
                prepareVaultV2WideDetail(VaultV2DetailKind.DOCUMENT)
                openInlineDocumentDetail(documentId)
            }
        }
        val handleBillingAddressOpen: (Long) -> Unit = { addressId ->
            if (isCompactWidth) {
                onNavigateToBillingAddressDetail(addressId)
            } else {
                prepareVaultV2WideDetail(VaultV2DetailKind.BILLING_ADDRESS)
                openInlineBillingAddressDetail(addressId)
            }
        }
        val handlePasskeyOpen: (PasskeyEntry) -> Unit = { passkey ->
            if (isCompactWidth) {
                selectedTabKey = BottomNavItem.Passkey.key
            } else {
                selectedPasskey = passkey
            }
        }
        val handlePasskeyUnbind: (PasskeyEntry) -> Unit = { passkey ->
            val boundId = passkey.boundPasswordId
            if (boundId != null) {
                passwordById[boundId]?.let { entry ->
                    val updatedBindings = PasskeyBindingCodec.removeBinding(
                        entry.passkeyBindings,
                        passkey.credentialId
                    )
                    passwordViewModel.updatePasskeyBindings(boundId, updatedBindings)
                }
            }
            if (passkey.id > 0L) {
                passkeyViewModel.updateBoundPassword(passkey.id, null)
            }
            if (selectedPasskey?.let { selected ->
                    if (selected.id > 0L && passkey.id > 0L) {
                        selected.id == passkey.id
                    } else {
                        selected.managementKey() == passkey.managementKey()
                    }
                } == true
            ) {
                selectedPasskey = selectedPasskey?.copy(boundPasswordId = null)
            }
        }
        val confirmPasskeyDelete: () -> Unit = {
            val passkey = pendingPasskeyDelete
            if (passkey != null) {
                scope.launch {
                    val boundId = passkey.boundPasswordId
                    if (boundId != null) {
                        passwordById[boundId]?.let { entry ->
                            val updatedBindings = PasskeyBindingCodec.removeBinding(
                                entry.passkeyBindings,
                                passkey.credentialId
                            )
                            passwordViewModel.updatePasskeyBindings(boundId, updatedBindings)
                        }
                    }

                    val isReferenceOnly = passkey.syncStatus == "REFERENCE" &&
                        passkey.privateKeyAlias.isBlank() &&
                        passkey.publicKey.isBlank()
                    if (!isReferenceOnly) {
                        val vaultId = passkey.bitwardenVaultId
                        val cipherId = passkey.bitwardenCipherId
                        if (vaultId != null && !cipherId.isNullOrBlank()) {
                            val queueResult = bitwardenRepository.queueCipherDelete(
                                vaultId = vaultId,
                                cipherId = cipherId,
                                itemType = BitwardenPendingOperation.ITEM_TYPE_PASSKEY
                            )
                            if (queueResult.isFailure) {
                                Toast.makeText(
                                    context,
                                    "Bitwarden 删除入队失败",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@launch
                            }
                        }
                        passkeyViewModel.deletePasskey(passkey)
                    }
                    if (selectedPasskey?.let { selected ->
                            if (selected.id > 0L && passkey.id > 0L) {
                                selected.id == passkey.id
                            } else {
                                selected.managementKey() == passkey.managementKey()
                            }
                        } == true
                    ) {
                        selectedPasskey = null
                        if (currentTab == BottomNavItem.VaultV2) {
                            vaultV2DetailKind = null
                        }
                    }
                    pendingPasskeyDelete = null
                }
            }
        }
        val handleSendOpen: (BitwardenSend) -> Unit = { send ->
            if (!isCompactWidth) {
                openInlineSendDetail(send)
            }
        }
        val handleSendAddOpen: () -> Unit = {
            if (isCompactWidth) {
                onNavigateToAddSend()
            } else {
                openInlineSendAdd()
            }
        }
        val handleInlineSendEditorBack: () -> Unit = {
            closeInlineSendEditor()
        }
        val handleTimelineLogOpen: (TimelineEvent.StandardLog) -> Unit = { log ->
            if (!isCompactWidth) {
                selectedTimelineLog = log
            }
        }

        return MainScreenHandlers(
            passwordAddOpen = handlePasswordAddOpen,
            passwordEditOpen = handlePasswordEditOpen,
            inlinePasswordEditorBack = handleInlinePasswordEditorBack,
            totpAddOpen = handleTotpAddOpen,
            inlineTotpEditorBack = handleInlineTotpEditorBack,
            bankCardAddOpen = handleBankCardAddOpen,
            bankCardEditOpen = handleBankCardEditOpen,
            inlineBankCardEditorBack = handleInlineBankCardEditorBack,
            documentAddOpen = handleDocumentAddOpen,
            documentEditOpen = handleDocumentEditOpen,
            inlineDocumentEditorBack = handleInlineDocumentEditorBack,
            billingAddressOpen = handleBillingAddressOpen,
            billingAddressAddOpen = handleBillingAddressAddOpen,
            billingAddressEditOpen = handleBillingAddressEditOpen,
            inlineBillingAddressEditorBack = handleInlineBillingAddressEditorBack,
            walletAddOpen = handleWalletAddOpen,
            noteOpen = handleNoteOpen,
            inlineNoteEditorBack = handleInlineNoteEditorBack,
            passwordDetailOpen = handlePasswordDetailOpen,
            totpOpen = handleTotpOpen,
            bankCardOpen = handleBankCardOpen,
            documentOpen = handleDocumentOpen,
            passkeyOpen = handlePasskeyOpen,
            passkeyUnbind = handlePasskeyUnbind,
            confirmPasskeyDelete = confirmPasskeyDelete,
            sendOpen = handleSendOpen,
            sendAddOpen = handleSendAddOpen,
            inlineSendEditorBack = handleInlineSendEditorBack,
            timelineLogOpen = handleTimelineLogOpen
        )
    }

    val handlers = buildMainScreenHandlers()
    val handlePasswordAddOpen = handlers.passwordAddOpen
    val handlePasswordEditOpen = handlers.passwordEditOpen
    val handleInlinePasswordEditorBack = handlers.inlinePasswordEditorBack
    val handleTotpAddOpen = handlers.totpAddOpen
    val handleInlineTotpEditorBack = handlers.inlineTotpEditorBack
    val handleBankCardAddOpen = handlers.bankCardAddOpen
    val handleBankCardEditOpen = handlers.bankCardEditOpen
    val handleInlineBankCardEditorBack = handlers.inlineBankCardEditorBack
    val handleDocumentAddOpen = handlers.documentAddOpen
    val handleDocumentEditOpen = handlers.documentEditOpen
    val handleInlineDocumentEditorBack = handlers.inlineDocumentEditorBack
    val handleBillingAddressOpen = handlers.billingAddressOpen
    val handleBillingAddressAddOpen = handlers.billingAddressAddOpen
    val handleBillingAddressEditOpen = handlers.billingAddressEditOpen
    val handleInlineBillingAddressEditorBack = handlers.inlineBillingAddressEditorBack
    val handleWalletAddOpen = handlers.walletAddOpen
    val handleNoteOpen = handlers.noteOpen
    val handleInlineNoteEditorBack = handlers.inlineNoteEditorBack
    val handlePasswordDetailOpen = handlers.passwordDetailOpen
    val handleTotpOpen = handlers.totpOpen
    val handleBankCardOpen = handlers.bankCardOpen
    val handleDocumentOpen = handlers.documentOpen
    val handlePasskeyOpen = handlers.passkeyOpen
    val handlePasskeyUnbind = handlers.passkeyUnbind
    val confirmPasskeyDelete = handlers.confirmPasskeyDelete
    val handleSendOpen = handlers.sendOpen
    val handleSendAddOpen = handlers.sendAddOpen
    val handleInlineSendEditorBack = handlers.inlineSendEditorBack
    val handleTimelineLogOpen = handlers.timelineLogOpen
    val handleVaultV2PasskeyOpen: (Long) -> Unit = { passkeyId ->
        if (isCompactWidth) {
            onNavigateToPasskeyDetail(passkeyId)
        } else {
            val passkey = localPasskeys.firstOrNull { it.id == passkeyId }
            if (passkey != null) {
                prepareVaultV2WideDetail(VaultV2DetailKind.PASSKEY)
                selectedPasskey = passkey
            } else {
                onNavigateToPasskeyDetail(passkeyId)
            }
        }
    }

    // --- Tab switch cleanup effects ---
    // Reset detail/editor panes on tab changes to avoid stale selection or mixed mode state.
    MainScreenTabResetEffects(
        currentTab = currentTab,
        isCompactWidth = isCompactWidth,
        cardWalletSubTab = cardWalletSubTab,
        passwordHistoryPageMode = passwordHistoryPageMode,
        onResetPasswordPane = {
            resetPasswordPaneState()
            passwordHistoryPageMode = PasswordHistoryPageMode.NONE
        },
        onHideBackToTop = {
            passwordListShowBackToTop = false
            vaultV2PaneState.clearTransientUi()
        },
        onResetTotpPane = {
            resetTotpPaneState()
        },
        onResetCardWalletPaneAll = {
            resetCardWalletPaneState()
        },
        onResetCardWalletDocumentPane = {
            resetDocumentPaneState()
        },
        onResetCardWalletBankCardPane = {
            resetBankCardPaneState()
        },
        onResetCardWalletBillingAddressPane = {
            resetBillingAddressPaneState()
        },
        onSyncWalletUnifiedAddType = { walletUnifiedAddType = it },
        onResetNotePane = {
            resetNotePaneState()
        },
        onResetPasskeyPane = { selectedPasskey = null },
        onResetSendPane = {
            resetSendPaneState()
        },
        onResetTimelineSelection = { selectedTimelineLog = null }
    )

    val onCardWalletDocumentSelectionModeChange:
        (Boolean, Int, () -> Unit, () -> Unit, () -> Unit, () -> Unit) -> Unit =
        { isSelectionMode, count, onExit, onSelectAll, onMoveToCategory, onDelete ->
            isDocumentSelectionMode = isSelectionMode
            selectedDocumentCount = count
            onExitDocumentSelection = onExit
            onSelectAllDocuments = onSelectAll
            onMoveToCategoryDocuments = onMoveToCategory
            onDeleteSelectedDocuments = onDelete
        }

    val onCardWalletBankCardSelectionModeChange:
        (Boolean, Int, () -> Unit, () -> Unit, () -> Unit, () -> Unit, () -> Unit) -> Unit =
        { isSelectionMode, count, onExit, onSelectAll, onDelete, onFavorite, onMoveToCategory ->
            isBankCardSelectionMode = isSelectionMode
            selectedBankCardCount = count
            onExitBankCardSelection = onExit
            onSelectAllBankCards = onSelectAll
            onMoveToCategoryBankCards = onMoveToCategory
            onDeleteSelectedBankCards = onDelete
            onFavoriteBankCards = onFavorite
        }

    val cardWalletContentState = CardWalletContentState(
        currentTab = cardWalletSubTab,
        onTabSelected = { cardWalletSubTab = it },
        onCardClick = { cardId ->
            handleBankCardOpen(cardId)
        },
        onDocumentClick = { documentId ->
            handleDocumentOpen(documentId)
        },
        onBillingAddressClick = { addressId ->
            handleBillingAddressOpen(addressId)
        },
        onDocumentSelectionModeChange = onCardWalletDocumentSelectionModeChange,
        onBankCardSelectionModeChange = onCardWalletBankCardSelectionModeChange,
        onBitwardenScopeChanged = { vaultId ->
            cardWalletBitwardenVaultId = vaultId
        }
    )
    
    val isBitwardenPageContext = when (currentTab) {
        BottomNavItem.VaultV2,
        BottomNavItem.Passwords -> isBitwardenPasswordFilter(currentFilter)
        BottomNavItem.Authenticator -> isBitwardenTotpFilter(totpFilter)
        BottomNavItem.CardWallet -> cardWalletBitwardenVaultId != null
        BottomNavItem.Notes,
        BottomNavItem.Passkey,
        BottomNavItem.Send -> activeBitwardenVault != null
        else -> false
    }
    val bitwardenStatusVaultId = when (currentTab) {
        BottomNavItem.CardWallet -> cardWalletBitwardenVaultId
        else -> activeBitwardenVault?.id
    }
    val activeVaultSyncState = bitwardenStatusVaultId?.let(bitwardenSyncStatusByVault::get)
    val nowMs by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = activeVaultSyncState?.lastSuccessAt
    ) {
        val lastSuccessAt = activeVaultSyncState?.lastSuccessAt ?: run {
            value = System.currentTimeMillis()
            return@produceState
        }
        while (isActive) {
            val current = System.currentTimeMillis()
            value = current
            if (current - lastSuccessAt > 3000L) break
            delay(250)
        }
    }
    val bottomStatusUiState = resolveBitwardenBottomStatusUiState(
        status = activeVaultSyncState,
        nowMs = nowMs
    )
    val shouldHandleBitwardenStatusVisual =
        appSettings.bitwardenBottomStatusBarEnabled &&
            isBitwardenPageContext &&
            !isAnySelectionMode &&
            !isFabExpanded &&
            bottomStatusUiState != null
    val shouldShowBitwardenSyncIndicator =
        shouldHandleBitwardenStatusVisual && (bottomStatusUiState?.showProgress == true)
    var statusHintVisible by remember { mutableStateOf(false) }
    val activeMiniHints = remember { mutableStateListOf<BottomMiniHintMessage>() }
    val queuedMiniHints = remember { mutableStateListOf<BottomMiniHintMessage>() }
    val dismissingHintIds = remember { mutableStateListOf<Long>() }
    var sendHintSeed by remember { mutableLongStateOf(0L) }

    val syncHintVisible = statusHintVisible && shouldHandleBitwardenStatusVisual && bottomStatusUiState?.messageRes != null
    fun tryActivateQueuedMiniHints() {
        val syncOccupiesSlot = syncHintVisible
        val maxCustomHints = (MAX_BOTTOM_MINI_HINTS - if (syncOccupiesSlot) 1 else 0).coerceAtLeast(0)
        while (activeMiniHints.size < maxCustomHints && queuedMiniHints.isNotEmpty()) {
            val nextHint = queuedMiniHints.removeAt(0)
            activeMiniHints += nextHint
            scope.launch {
                delay(2800L)
                dismissingHintIds += nextHint.id
                delay(280L)
                activeMiniHints.removeAll { it.id == nextHint.id }
                dismissingHintIds.removeAll { it == nextHint.id }
                tryActivateQueuedMiniHints()
            }
        }
    }

    val enqueueMiniHint: (String, String?) -> Unit = { title, supportingText ->
        val hintId = ++sendHintSeed
        queuedMiniHints += BottomMiniHintMessage(
            id = hintId,
            title = title,
            supportingText = supportingText
        )
        tryActivateQueuedMiniHints()
    }
    val handleSendBitwardenEvent: (takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel.BitwardenEvent) -> Boolean = { event ->
        when (event) {
            is takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel.BitwardenEvent.SendCreated -> {
                enqueueMiniHint(event.message, null)
                true
            }

            is takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel.BitwardenEvent.SendDeleted -> {
                enqueueMiniHint(event.message, null)
                true
            }

            is takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel.BitwardenEvent.SyncFinished -> {
                true
            }

            else -> false
        }
    }
    LaunchedEffect(bitwardenViewModel, context) {
        bitwardenViewModel.events.collect { event ->
            if (event is takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel.BitwardenEvent.SyncFinished) {
                val hint = buildBitwardenSyncMiniHint(context, event.summary)
                enqueueMiniHint(hint.title, hint.supportingText)
            }
        }
    }
    LaunchedEffect(shouldHandleBitwardenStatusVisual, bottomStatusUiState?.messageRes) {
        if (!shouldHandleBitwardenStatusVisual || bottomStatusUiState?.messageRes == null) {
            statusHintVisible = false
            return@LaunchedEffect
        }
        statusHintVisible = true
        delay(if (bottomStatusUiState.showProgress) 2600L else 3600L)
        statusHintVisible = false
    }
    LaunchedEffect(syncHintVisible, queuedMiniHints.size, activeMiniHints.size) {
        tryActivateQueuedMiniHints()
    }

    @Composable
    fun RenderVaultV2Tab() {
        if (passwordHistoryPageMode.isVisible) {
            TimelineScreen(
                viewModel = timelineViewModel,
                onLogSelected = handleTimelineLogOpen,
                splitPaneMode = false,
                initialTab = passwordHistoryPageMode.tab ?: HistoryTab.TIMELINE,
                initialTrashScopeKey = passwordHistoryInitialTrashScopeKey,
                enableTabSwitch = false,
                showBackButton = true,
                onNavigateBack = closeHistoryPage
            )
            return
        }

        VaultV2TabPane(
            isCompactWidth = isCompactWidth,
            wideListPaneWidth = wideListPaneWidth,
            hasWideDetail = vaultV2HasWideDetail,
            onClearWideDetail = clearVaultV2WideDetail,
            listContent = {
                VaultV2Pane(
                    passwordViewModel = passwordViewModel,
                    totpViewModel = totpViewModel,
                    bankCardViewModel = bankCardViewModel,
                    documentViewModel = documentViewModel,
                    noteViewModel = noteViewModel,
                    passkeyViewModel = passkeyViewModel,
                    keepassDatabases = keepassDatabases,
                    mdbxDatabases = mdbxDatabases,
                    bitwardenVaults = bitwardenVaults,
                    localKeePassViewModel = localKeePassViewModel,
                    mdbxViewModel = mdbxViewModel,
                    settingsViewModel = settingsViewModel,
                    state = vaultV2PaneState,
                    onOpenPassword = handlePasswordDetailOpen,
                    onOpenTotp = handleTotpOpen,
                    onOpenBankCard = handleBankCardOpen,
                    onOpenDocument = handleDocumentOpen,
                    onOpenNote = { handleNoteOpen(it) },
                    onOpenPasskey = handleVaultV2PasskeyOpen,
                    onOpenMdbxCommitHistory = onNavigateToMdbxCommitHistory,
                    onOpenHistory = openHistoryPage,
                    onOpenTrashPage = openTrashPage,
                    onOpenArchivePage = vaultV2PaneState::openArchiveView,
                    onOpenCommonAccountTemplates = onNavigateToCommonAccountTemplates,
                    onScanFidoQr = onNavigateToFidoQrScan,
                    onOpenStandaloneSettings = onNavigateToStandaloneSettings,
                    showStandaloneSettingsEntry = shouldHideBottomNavigation,
                    appSettings = appSettings,
                    securityManager = securityManager,
                    biometricEnabled = appSettings.biometricEnabled,
                    useEmbeddedHistoryPages = isCompactWidth,
                    modifier = Modifier.fillMaxSize()
                )
            },
            detailContent = {
                when (vaultV2DetailKind) {
                    VaultV2DetailKind.PASSWORD -> PasswordDetailPaneContent(
                        isAddingPasswordInline = isAddingPasswordInline,
                        inlinePasswordEditorId = inlinePasswordEditorId,
                        selectedPasswordId = selectedPasswordId,
                        passwordViewModel = passwordViewModel,
                        totpViewModel = totpViewModel,
                        bankCardViewModel = bankCardViewModel,
                        noteViewModel = noteViewModel,
                        localKeePassViewModel = localKeePassViewModel,
                        passwordNewItemDefaults = pendingInlinePasswordAddStorageDefaults
                            ?: passwordNewItemDefaults,
                        mdbxDatabases = mdbxDatabases,
                        pendingPasswordAuthenticatorQrResult = pendingPasswordAuthenticatorQrResult,
                        onConsumePendingPasswordAuthenticatorQrResult =
                            onConsumePendingPasswordAuthenticatorQrResult,
                        onScanPasswordAuthenticatorQrCode = onScanPasswordAuthenticatorQrCode,
                        onInlinePasswordEditorBack = {
                            handleInlinePasswordEditorBack()
                            if (selectedPasswordId == null) {
                                vaultV2DetailKind = null
                            }
                        },
                        onNavigateToAddWifi = onNavigateToAddWifi,
                        onNavigateToAddSshKey = onNavigateToAddSshKey,
                        passkeyViewModel = passkeyViewModel,
                        biometricEnabled = appSettings.biometricEnabled,
                        iconCardsEnabled = appSettings.iconCardsEnabled && appSettings.passwordPageIconEnabled,
                        unmatchedIconHandlingStrategy = appSettings.unmatchedIconHandlingStrategy,
                        onClearSelectedPassword = clearVaultV2WideDetail,
                        onNavigateToNoteDetail = { noteId -> handleNoteOpen(noteId) },
                        onEditPassword = handlePasswordEditOpen
                    )

                    VaultV2DetailKind.AUTHENTICATOR -> AuthenticatorDetailPaneContent(
                        totpViewModel = totpViewModel,
                        passwordViewModel = passwordViewModel,
                        localKeePassViewModel = localKeePassViewModel,
                        isAddingTotpInline = isAddingTotpInline,
                        selectedTotpId = selectedTotpId,
                        totpNewItemDefaults = pendingInlineTotpAddStorageDefaults ?: totpNewItemDefaults,
                        onInlineTotpEditorBack = {
                            resetTotpPaneState()
                            vaultV2DetailKind = null
                        },
                        onNavigateToQuickTotpScan = onNavigateToQuickTotpScan
                    )

                    VaultV2DetailKind.BANK_CARD,
                    VaultV2DetailKind.DOCUMENT,
                    VaultV2DetailKind.BILLING_ADDRESS -> CardWalletDetailPaneContent(
                        bankCardViewModel = bankCardViewModel,
                        documentViewModel = documentViewModel,
                        billingAddressViewModel = billingAddressViewModel,
                        isAddingBankCardInline = isAddingBankCardInline,
                        inlineBankCardEditorId = inlineBankCardEditorId,
                        onInlineBankCardEditorBack = {
                            handleInlineBankCardEditorBack()
                            vaultV2DetailKind = null
                        },
                        isAddingDocumentInline = isAddingDocumentInline,
                        inlineDocumentEditorId = inlineDocumentEditorId,
                        onInlineDocumentEditorBack = {
                            handleInlineDocumentEditorBack()
                            vaultV2DetailKind = null
                        },
                        isAddingBillingAddressInline = isAddingBillingAddressInline,
                        inlineBillingAddressEditorId = inlineBillingAddressEditorId,
                        onInlineBillingAddressEditorBack = {
                            handleInlineBillingAddressEditorBack()
                            vaultV2DetailKind = null
                        },
                        selectedBankCardId = selectedBankCardId,
                        onClearSelectedBankCard = clearVaultV2WideDetail,
                        onEditBankCard = handleBankCardEditOpen,
                        selectedDocumentId = selectedDocumentId,
                        onClearSelectedDocument = clearVaultV2WideDetail,
                        onEditDocument = handleDocumentEditOpen,
                        selectedBillingAddressId = selectedBillingAddressId,
                        onClearSelectedBillingAddress = clearVaultV2WideDetail,
                        onEditBillingAddress = handleBillingAddressEditOpen,
                        initialCategoryId = pendingInlineWalletAddStorageDefaults?.categoryId,
                        initialKeePassDatabaseId = pendingInlineWalletAddStorageDefaults?.keepassDatabaseId,
                        initialKeePassGroupPath = pendingInlineWalletAddStorageDefaults?.keepassGroupPath,
                        initialMdbxDatabaseId = pendingInlineWalletAddStorageDefaults?.mdbxDatabaseId,
                        initialMdbxFolderId = pendingInlineWalletAddStorageDefaults?.mdbxFolderId,
                        initialBitwardenVaultId = pendingInlineWalletAddStorageDefaults?.bitwardenVaultId,
                        initialBitwardenFolderId = pendingInlineWalletAddStorageDefaults?.bitwardenFolderId
                    )

                    VaultV2DetailKind.NOTE -> NoteDetailPaneContent(
                        noteViewModel = noteViewModel,
                        isAddingNoteInline = isAddingNoteInline,
                        inlineNoteEditorId = inlineNoteEditorId,
                        onInlineNoteEditorBack = {
                            resetNotePaneState()
                            vaultV2DetailKind = null
                        },
                        initialCategoryId = pendingInlineNoteAddStorageDefaults?.categoryId,
                        initialKeePassDatabaseId = pendingInlineNoteAddStorageDefaults?.keepassDatabaseId,
                        initialKeePassGroupPath = pendingInlineNoteAddStorageDefaults?.keepassGroupPath,
                        initialMdbxDatabaseId = pendingInlineNoteAddStorageDefaults?.mdbxDatabaseId,
                        initialBitwardenVaultId = pendingInlineNoteAddStorageDefaults?.bitwardenVaultId,
                        initialBitwardenFolderId = pendingInlineNoteAddStorageDefaults?.bitwardenFolderId
                    )

                    VaultV2DetailKind.PASSKEY -> PasskeyDetailPaneContent(
                        selectedPasskey = selectedPasskey,
                        passkeyTotalCount = passkeyTotalCount,
                        passkeyBoundCount = passkeyBoundCount,
                        resolvePasswordTitle = { passwordId -> passwordById[passwordId]?.title },
                        onOpenPasswordDetail = handlePasswordDetailOpen,
                        onUnbindPasskey = handlePasskeyUnbind,
                        onDeletePasskey = { passkey -> pendingPasskeyDelete = passkey }
                    )

                    null -> Unit
                }
            }
        )
    }

    // --- Main surface composition ---
    // Decides draggable nav vs classic scaffold and dispatches per-tab content.
    @Composable
    fun RenderMainSurface() {
    Box(modifier = Modifier.fillMaxSize()) {
    // 根据设置选择导航模式
    Box(
        modifier = Modifier
            .matchParentSize()
            .nestedScroll(nestedScrollConnection)
    ) {
        if (useDraggableNav && isCompactWidth && !shouldHideBottomNavigation) {
        // 使用可拖拽底部导航栏
        DraggableBottomNavScaffold(
            navItems = draggableNavItems,
            statusIndicatorVisible = shouldShowBitwardenSyncIndicator,

            quickAddCallback = QuickAddCallback(
                onAddPassword = { title, username, password ->
                    passwordViewModel.quickAddPassword(title, username, password)
                },
                onAddTotp = { name, secret ->
                    totpViewModel.quickAddTotp(name, secret)
                },
                onAddBankCard = { name, number ->
                    bankCardViewModel.quickAddBankCard(name, number)
                },
                onAddNote = { title, content ->
                    noteViewModel.quickAddNote(title, content)
                }
            ),
            floatingActionButton = {}, // FAB 移至外层 Overlay
            content = { paddingValues ->
                CompactDraggableTabContent(
                    paddingValues = paddingValues,
                    currentTab = currentTab,
                    showStandaloneSettingsEntry = shouldHideBottomNavigation,
                    onOpenStandaloneSettings = onNavigateToStandaloneSettings,
                    passwordViewModel = passwordViewModel,
                    settingsViewModel = settingsViewModel,
                    securityManager = securityManager,
                    keepassDatabases = keepassDatabases,
						mdbxDatabases = mdbxDatabases,
                    bitwardenVaults = bitwardenVaults,
                    localKeePassViewModel = localKeePassViewModel,
                    mdbxViewModel = mdbxViewModel,
                    passwordGroupMode = passwordGroupMode,
                    stackCardMode = stackCardMode,
                    onPasswordOpen = handlePasswordDetailOpen,
                    onBankCardOpen = handleBankCardOpen,
                    onDocumentOpen = handleDocumentOpen,
                    onNoteOpen = { handleNoteOpen(it) },
                    onPasskeyOpen = handlePasskeyOpen,
                    onPasswordSelectionModeChange = { isSelectionMode, count, onExit, onSelectAll, onFavorite, onMoveToCategory, onStack, onDelete ->
                        isPasswordSelectionMode = isSelectionMode
                        selectedPasswordCount = count
                        onExitPasswordSelection = onExit
                        onSelectAllPasswords = onSelectAll
                        onFavoriteSelectedPasswords = onFavorite
                        onMoveToCategoryPasswords = onMoveToCategory
                        onManualStackPasswords = onStack
                        onDeleteSelectedPasswords = onDelete
                    },
                    onBackToTopVisibilityChange = { visible ->
                        passwordListShowBackToTop = visible
                    },
                    passwordScrollToTopRequestKey = passwordScrollToTopRequestKey,
                    totpViewModel = totpViewModel,
                    onTotpOpen = handleTotpOpen,
                    onNavigateToAddTotp = onNavigateToAddTotp,
                    onNavigateToQuickTotpScan = onNavigateToQuickTotpScan,
                    pendingSteamQrResult = pendingSteamQrResult,
                    pendingSteamQrAccountId = pendingSteamQrAccountId,
                    onConsumePendingSteamQrResult = onConsumePendingSteamQrResult,
                    onScanSteamQrCode = onScanSteamQrCode,
                    onNavigateToFidoQrScan = onNavigateToFidoQrScan,
                    onTotpSelectionModeChange = { isSelectionMode, count, onExit, onSelectAll, onMoveToCategory, onDelete ->
                        isTotpSelectionMode = isSelectionMode
                        selectedTotpCount = count
                        onExitTotpSelection = onExit
                        onSelectAllTotp = onSelectAll
                        onMoveToCategoryTotp = onMoveToCategory
                        onDeleteSelectedTotp = onDelete
                    },
                    cardWalletSaveableStateHolder = cardWalletSaveableStateHolder,
                    bankCardViewModel = bankCardViewModel,
                    documentViewModel = documentViewModel,
                    billingAddressViewModel = billingAddressViewModel,
                    cardWalletContentState = cardWalletContentState,
                    generatorViewModel = generatorViewModel,
                    generatorRefreshRequestKey = generatorRefreshRequestKey,
                    onGeneratorRefreshRequestConsumed = { generatorRefreshRequestKey = 0 },
                    noteViewModel = noteViewModel,
                    onNavigateToAddNote = handleNoteOpen,
                    onNavigateToSearchedNote = onNavigateToSearchedNote,
                    onNavigateToNoteDetail = onNavigateToNoteDetail,
                    onNavigateToBankCardDetail = onNavigateToBankCardDetail,
                    onNavigateToDocumentDetail = onNavigateToDocumentDetail,
                    onNavigateToBillingAddressDetail = handleBillingAddressOpen,
                    onNavigateToPasskeyDetail = onNavigateToPasskeyDetail,
                    onNavigateToMdbxCommitHistory = onNavigateToMdbxCommitHistory,
                    onNoteSelectionModeChange = { isSelectionMode ->
                        isNoteSelectionMode = isSelectionMode
                    },
                    timelineViewModel = timelineViewModel,
                    passkeyViewModel = passkeyViewModel,
                    onNavigateToPasswordDetail = onNavigateToPasswordDetail,
                    onNavigateToAuthenticator = {
                        selectedTabKey = BottomNavItem.Authenticator.key
                    },
                    bitwardenViewModel = bitwardenViewModel,
                    onSendBitwardenEvent = handleSendBitwardenEvent,
                    onNavigateToChangePassword = onNavigateToChangePassword,
                    onNavigateToSecurityQuestion = onNavigateToSecurityQuestion,
                    onNavigateToMasterPasswordLocking = onNavigateToMasterPasswordLocking,
                    onNavigateToSyncBackup = onNavigateToSyncBackup,
                    onNavigateToAutofill = onNavigateToAutofill,
                    onNavigateToPasskeySettings = onNavigateToPasskeySettings,
                    onNavigateToBottomNavSettings = onNavigateToBottomNavSettings,
                    onNavigateToColorScheme = onNavigateToColorScheme,
                    onSecurityAnalysis = onSecurityAnalysis,
                    onNavigateToDeveloperSettings = onNavigateToDeveloperSettings,
                    onNavigateToPermissionManagement = onNavigateToPermissionManagement,
                    onNavigateToMonicaPlus = onNavigateToMonicaPlus,
                    onNavigateToExtensions = onNavigateToExtensions,
                    onNavigateToCommonAccountTemplates = onNavigateToCommonAccountTemplates,
                    onNavigateToPageCustomization = onNavigateToPageCustomization,
                    onOpenVaultV2HistoryPage = {
                        selectedTabKey = BottomNavItem.Passwords.key
                        openHistoryPage()
                    },
                    onOpenVaultV2TrashPage = {
                        selectedTabKey = BottomNavItem.Passwords.key
                        openTrashPage()
                    },
                    onOpenVaultV2ArchivePage = {
                        vaultV2PaneState.openArchiveView()
                    },
                    onClearAllData = onClearAllData,
                    cardWalletSubTab = cardWalletSubTab,
                    passwordHistoryPageMode = passwordHistoryPageMode,
                    passwordHistoryInitialTrashScopeKey = passwordHistoryInitialTrashScopeKey,
                    onOpenHistoryPage = openHistoryPage,
                    onOpenTrashPage = openTrashPage,
                    onCloseHistoryPage = closeHistoryPage,
                    isPasswordSelectionMode = isPasswordSelectionMode,
                    selectedPasswordCount = selectedPasswordCount,
                    onExitPasswordSelection = onExitPasswordSelection,
                    onSelectAllPasswords = onSelectAllPasswords,
                    onFavoriteSelectedPasswords = onFavoriteSelectedPasswords,
                    onMoveToCategoryPasswords = onMoveToCategoryPasswords,
                    onManualStackPasswords = onManualStackPasswords,
                    onDeleteSelectedPasswords = onDeleteSelectedPasswords,
                    isTotpSelectionMode = isTotpSelectionMode,
                    selectedTotpCount = selectedTotpCount,
                    onExitTotpSelection = onExitTotpSelection,
                    onSelectAllTotp = onSelectAllTotp,
                    onMoveToCategoryTotp = onMoveToCategoryTotp,
                    onDeleteSelectedTotp = onDeleteSelectedTotp,
                    isBankCardSelectionMode = isBankCardSelectionMode,
                    selectedBankCardCount = selectedBankCardCount,
                    onExitBankCardSelection = onExitBankCardSelection,
                    onSelectAllBankCards = onSelectAllBankCards,
                    onFavoriteBankCards = onFavoriteBankCards,
                    onMoveToCategoryBankCards = onMoveToCategoryBankCards,
                    onDeleteSelectedBankCards = onDeleteSelectedBankCards,
                    isDocumentSelectionMode = isDocumentSelectionMode,
                    selectedDocumentCount = selectedDocumentCount,
                    onExitDocumentSelection = onExitDocumentSelection,
                    onSelectAllDocuments = onSelectAllDocuments,
                    onMoveToCategoryDocuments = onMoveToCategoryDocuments,
                    onDeleteSelectedDocuments = onDeleteSelectedDocuments,
                    vaultV2PaneState = vaultV2PaneState,
                )
            }
        )
    } else {
        // 使用传统底部导航栏
    Scaffold(
        topBar = {
            // 顶部栏由各自页面内部控制（如 ExpressiveTopBar），这里保持为空以避免叠加
        },
        contentWindowInsets = if (isCompactWidth) {
            ScaffoldDefaults.contentWindowInsets
        } else {
            WindowInsets(0, 0, 0, 0)
        },
        bottomBar = {
            if (isCompactWidth && !shouldHideBottomNavigation) {
                Column {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = shouldShowBitwardenSyncIndicator,
                        enter = slideInVertically(
                            initialOffsetY = { it / 2 },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) + fadeIn(),
                        exit = slideOutVertically(
                            targetOffsetY = { it / 2 },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) + fadeOut()
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        }
                    }

                    NavigationBar(
                        tonalElevation = 0.dp,
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        tabs.forEach { item ->
                            val label = stringResource(item.shortLabelRes())
                            NavigationBarItem(
                                icon = {
                                    Icon(item.icon, contentDescription = label)
                                },
                                label = {
                                    Text(
                                        text = label,
                                        maxLines = 2,
                                        overflow = TextOverflow.Clip
                                    )
                                },
                                selected = item.key == selectedDockTab.key,
                                onClick = { selectedTabKey = item.key }
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {} // FAB 移至外层 Overlay
    ) { paddingValues ->

        if (isCompactWidth) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
            AuthenticatorPasskeyAnimatedContent(currentTab = currentTab) { displayedTab ->
            when (displayedTab) {
                BottomNavItem.VaultV2 -> {
                    RenderVaultV2Tab()
                }
                BottomNavItem.Passwords -> {
                    PasswordTabPane(
                        isCompactWidth = isCompactWidth,
                        wideListPaneWidth = wideListPaneWidth,
                        passwordViewModel = passwordViewModel,
                        settingsViewModel = settingsViewModel,
                        securityManager = securityManager,
                        keepassDatabases = keepassDatabases,
						mdbxDatabases = mdbxDatabases,
                        bitwardenVaults = bitwardenVaults,
                        localKeePassViewModel = localKeePassViewModel,
                        mdbxViewModel = mdbxViewModel,
                        timelineViewModel = timelineViewModel,
                        groupMode = passwordGroupMode,
                        stackCardMode = stackCardMode,
                        visibleContentTypes = passwordPageVisibleContentTypes,
                        selectedContentTypes = passwordPageSelectedContentTypes,
                        onToggleContentType = { type ->
                            passwordPageSelectedContentTypes = togglePasswordPageContentType(
                                currentTypes = passwordPageSelectedContentTypes,
                                toggledType = type,
                                visibleTypes = passwordPageVisibleContentTypes
                            )
                        },
                        onPasswordOpen = handlePasswordDetailOpen,
                        onNavigateToAddTotp = onNavigateToAddTotp,
                        onNavigateToBankCardDetail = onNavigateToBankCardDetail,
                        onNavigateToDocumentDetail = onNavigateToDocumentDetail,
                        onNavigateToBillingAddressDetail = handleBillingAddressOpen,
                        onNavigateToAddNote = handleNoteOpen,
                        onNavigateToNoteDetail = onNavigateToNoteDetail,
                        onNavigateToPasskeyDetail = onNavigateToPasskeyDetail,
                        onOpenMdbxCommitHistory = onNavigateToMdbxCommitHistory,
                        onOpenHistoryPage = openHistoryPage,
                        onOpenTrashPage = openTrashPage,
                        onOpenCommonAccountTemplatesPage = onNavigateToCommonAccountTemplates,
                        onScanFidoQr = onNavigateToFidoQrScan,
                        onCloseHistoryPage = closeHistoryPage,
                        passwordHistoryPageMode = passwordHistoryPageMode,
                        passwordHistoryInitialTrashScopeKey = passwordHistoryInitialTrashScopeKey,
                        onTimelineLogSelected = handleTimelineLogOpen,
                        onSelectionModeChange = { isSelectionMode, count, onExit, onSelectAll, onFavorite, onMoveToCategory, onStack, onDelete ->
                            isPasswordSelectionMode = isSelectionMode
                            selectedPasswordCount = count
                            onExitPasswordSelection = onExit
                            onSelectAllPasswords = onSelectAll
                            onFavoriteSelectedPasswords = onFavorite
                            onMoveToCategoryPasswords = onMoveToCategory
                            onManualStackPasswords = onStack
                            onDeleteSelectedPasswords = onDelete
                        },
                        onBackToTopVisibilityChange = { visible ->
                            passwordListShowBackToTop = visible
                        },
                        scrollToTopRequestKey = passwordScrollToTopRequestKey,
                        isAddingPasswordInline = isAddingPasswordInline,
                        inlinePasswordEditorId = inlinePasswordEditorId,
                        selectedPasswordId = selectedPasswordId,
                        passwordNewItemDefaults = pendingInlinePasswordAddStorageDefaults ?: passwordNewItemDefaults,
                        onInlinePasswordEditorBack = handleInlinePasswordEditorBack,
                        onNavigateToAddWifi = onNavigateToAddWifi,
                        onNavigateToAddSshKey = onNavigateToAddSshKey,
                        pendingPasswordAuthenticatorQrResult = pendingPasswordAuthenticatorQrResult,
                        onConsumePendingPasswordAuthenticatorQrResult =
                            onConsumePendingPasswordAuthenticatorQrResult,
                        onScanPasswordAuthenticatorQrCode = onScanPasswordAuthenticatorQrCode,
                        totpViewModel = totpViewModel,
                        bankCardViewModel = bankCardViewModel,
                        noteViewModel = noteViewModel,
                        documentViewModel = documentViewModel,
                        billingAddressViewModel = billingAddressViewModel,
                        passkeyViewModel = passkeyViewModel,
                        biometricEnabled = appSettings.biometricEnabled,
                        iconCardsEnabled = appSettings.iconCardsEnabled && appSettings.passwordPageIconEnabled,
                        unmatchedIconHandlingStrategy = appSettings.unmatchedIconHandlingStrategy,
                        onClearSelectedPassword = clearSelectedPasswordPaneItem,
                        onEditPassword = handlePasswordEditOpen
                        ,
                        showStandaloneSettingsEntry = shouldHideBottomNavigation,
                        onOpenStandaloneSettings = onNavigateToStandaloneSettings
                    )
                }
                BottomNavItem.Authenticator -> {
                    AuthenticatorTabPane(
                        isCompactWidth = isCompactWidth,
                        wideListPaneWidth = wideListPaneWidth,
                        totpViewModel = totpViewModel,
                        passwordViewModel = passwordViewModel,
                        appSettings = appSettings,
                        onAuthenticatorLayoutModeChange = settingsViewModel::updateAuthenticatorLayoutMode,
                        localKeePassViewModel = localKeePassViewModel,
                        onTotpOpen = handleTotpOpen,
                        onNavigateToQuickTotpScan = onNavigateToQuickTotpScan,
                        onSelectionModeChange = { isSelectionMode, count, onExit, onSelectAll, onMoveToCategory, onDelete ->
                            isTotpSelectionMode = isSelectionMode
                            selectedTotpCount = count
                            onExitTotpSelection = onExit
                            onSelectAllTotp = onSelectAll
                            onMoveToCategoryTotp = onMoveToCategory
                            onDeleteSelectedTotp = onDelete
                        },
                        isAddingTotpInline = isAddingTotpInline,
                        selectedTotpId = selectedTotpId,
                        totpNewItemDefaults = pendingInlineTotpAddStorageDefaults ?: totpNewItemDefaults,
                        onInlineTotpEditorBack = handleInlineTotpEditorBack,
                        showStandaloneSettingsEntry = shouldHideBottomNavigation,
                        onOpenStandaloneSettings = onNavigateToStandaloneSettings
                    )
                }
                BottomNavItem.CardWallet -> {
                    CardWalletPane(
                        isCompactWidth = isCompactWidth,
                        wideListPaneWidth = wideListPaneWidth,
                        saveableStateHolder = cardWalletSaveableStateHolder,
                        bankCardViewModel = bankCardViewModel,
                        documentViewModel = documentViewModel,
                        billingAddressViewModel = billingAddressViewModel,
                        passwordViewModel = passwordViewModel,
                        bitwardenViewModel = bitwardenViewModel,
                        contentState = cardWalletContentState,
                        isAddingBankCardInline = isAddingBankCardInline,
                        inlineBankCardEditorId = inlineBankCardEditorId,
                        onInlineBankCardEditorBack = handleInlineBankCardEditorBack,
                        isAddingDocumentInline = isAddingDocumentInline,
                        inlineDocumentEditorId = inlineDocumentEditorId,
                        onInlineDocumentEditorBack = handleInlineDocumentEditorBack,
                        isAddingBillingAddressInline = isAddingBillingAddressInline,
                        inlineBillingAddressEditorId = inlineBillingAddressEditorId,
                        onInlineBillingAddressEditorBack = handleInlineBillingAddressEditorBack,
                        selectedBankCardId = selectedBankCardId,
                        onClearSelectedBankCard = clearSelectedBankCardPaneItem,
                        onEditBankCard = handleBankCardEditOpen,
                        selectedDocumentId = selectedDocumentId,
                        onClearSelectedDocument = clearSelectedDocumentPaneItem,
                        onEditDocument = handleDocumentEditOpen,
                        selectedBillingAddressId = selectedBillingAddressId,
                        onClearSelectedBillingAddress = clearSelectedBillingAddressPaneItem,
                        onEditBillingAddress = handleBillingAddressEditOpen,
                        initialCategoryId = pendingInlineWalletAddStorageDefaults?.categoryId,
                        initialKeePassDatabaseId = pendingInlineWalletAddStorageDefaults?.keepassDatabaseId,
                        initialKeePassGroupPath = pendingInlineWalletAddStorageDefaults?.keepassGroupPath,
                        initialMdbxDatabaseId = pendingInlineWalletAddStorageDefaults?.mdbxDatabaseId,
                        initialMdbxFolderId = pendingInlineWalletAddStorageDefaults?.mdbxFolderId,
                        initialBitwardenVaultId = pendingInlineWalletAddStorageDefaults?.bitwardenVaultId,
                        initialBitwardenFolderId = pendingInlineWalletAddStorageDefaults?.bitwardenFolderId,
                        showStandaloneSettingsEntry = shouldHideBottomNavigation,
                        onOpenStandaloneSettings = onNavigateToStandaloneSettings
                    )
                }
                BottomNavItem.Generator -> {
                    GeneratorPane(
                        isCompactWidth = isCompactWidth,
                        wideListPaneWidth = wideListPaneWidth,
                        generatorViewModel = generatorViewModel,
                        passwordViewModel = passwordViewModel,
                        externalRefreshRequestKey = generatorRefreshRequestKey,
                        onRefreshRequestConsumed = { generatorRefreshRequestKey = 0 },
                        selectedGenerator = selectedGeneratorType,
                        generatedValue = currentGeneratorResult,
                        showStandaloneSettingsEntry = shouldHideBottomNavigation,
                        onOpenStandaloneSettings = onNavigateToStandaloneSettings
                    )
                }
                BottomNavItem.Notes -> {
                    NotePane(
                        isCompactWidth = isCompactWidth,
                        wideListPaneWidth = wideListPaneWidth,
                        noteViewModel = noteViewModel,
                        settingsViewModel = settingsViewModel,
                        securityManager = securityManager,
                        passwordViewModel = passwordViewModel,
                        onNavigateToAddNote = handleNoteOpen,
                        onNavigateToSearchedNote = onNavigateToSearchedNote,
                        onSelectionModeChange = { isSelectionMode ->
                            isNoteSelectionMode = isSelectionMode
                        },
                        isAddingNoteInline = isAddingNoteInline,
                        inlineNoteEditorId = inlineNoteEditorId,
                        onInlineNoteEditorBack = handleInlineNoteEditorBack,
                        initialCategoryId = pendingInlineNoteAddStorageDefaults?.categoryId,
                        initialKeePassDatabaseId = pendingInlineNoteAddStorageDefaults?.keepassDatabaseId,
                        initialKeePassGroupPath = pendingInlineNoteAddStorageDefaults?.keepassGroupPath,
                        initialMdbxDatabaseId = pendingInlineNoteAddStorageDefaults?.mdbxDatabaseId,
                        initialBitwardenVaultId = pendingInlineNoteAddStorageDefaults?.bitwardenVaultId,
                        initialBitwardenFolderId = pendingInlineNoteAddStorageDefaults?.bitwardenFolderId,
                        showStandaloneSettingsEntry = shouldHideBottomNavigation,
                        onOpenStandaloneSettings = onNavigateToStandaloneSettings
                    )
                }
                BottomNavItem.Passkey -> {
                    PasskeyPane(
                        isCompactWidth = isCompactWidth,
                        wideListPaneWidth = wideListPaneWidth,
                        passkeyViewModel = passkeyViewModel,
                        passwordViewModel = passwordViewModel,
                        onNavigateToPasswordDetail = onNavigateToPasswordDetail,
                        onNavigateToPasskeyDetail = onNavigateToPasskeyDetail,
                        onPasskeyOpen = handlePasskeyOpen,
                        selectedPasskey = selectedPasskey,
                        passkeyTotalCount = passkeyTotalCount,
                        passkeyBoundCount = passkeyBoundCount,
                        resolvePasswordTitle = { passwordId -> passwordById[passwordId]?.title },
                        onOpenPasswordDetail = handlePasswordDetailOpen,
                        onUnbindPasskey = handlePasskeyUnbind,
                        onDeletePasskey = { passkey -> pendingPasskeyDelete = passkey },
                        showStandaloneSettingsEntry = shouldHideBottomNavigation,
                        onOpenStandaloneSettings = onNavigateToStandaloneSettings,
                        onNavigateToAuthenticator = {
                            selectedTabKey = BottomNavItem.Authenticator.key
                        }
                    )
                }
                BottomNavItem.Send -> {
                    SendPane(
                        isCompactWidth = isCompactWidth,
                        wideListPaneWidth = wideListPaneWidth,
                        bitwardenViewModel = bitwardenViewModel,
                        sendState = sendState,
                        selectedSend = selectedSend,
                        isAddingSendInline = isAddingSendInline,
                        onSendClick = handleSendOpen,
                        onInlineSendEditorBack = handleInlineSendEditorBack,
                        onCreateSend = { vaultId, title, text, notes, password, maxAccessCount, hideEmail, hiddenText, expireInDays ->
                            bitwardenViewModel.createTextSend(
                                vaultId = vaultId,
                                title = title,
                                text = text,
                                notes = notes,
                                password = password,
                                maxAccessCount = maxAccessCount,
                                hideEmail = hideEmail,
                                hiddenText = hiddenText,
                                expireInDays = expireInDays
                            )
                        },
                        onCreateFileSend = { vaultId, title, fileUri, fileName, notes, password, maxAccessCount, hideEmail, expireInDays ->
                            bitwardenViewModel.createFileSend(
                                vaultId = vaultId,
                                title = title,
                                fileUri = fileUri,
                                fileName = fileName,
                                notes = notes,
                                password = password,
                                maxAccessCount = maxAccessCount,
                                hideEmail = hideEmail,
                                expireInDays = expireInDays
                            )
                        },
                        onBitwardenEvent = handleSendBitwardenEvent,
                        showStandaloneSettingsEntry = shouldHideBottomNavigation,
                        onOpenStandaloneSettings = onNavigateToStandaloneSettings
                    )
                }
                BottomNavItem.Steam -> {
                    SteamScreen(
                        showStandaloneSettingsEntry = shouldHideBottomNavigation,
                        onOpenStandaloneSettings = onNavigateToStandaloneSettings,
                        pendingSteamQrResult = pendingSteamQrResult,
                        pendingSteamQrAccountId = pendingSteamQrAccountId,
                        onConsumePendingSteamQrResult = onConsumePendingSteamQrResult,
                        onScanSteamQrCode = onScanSteamQrCode,
                        isCompactWidth = isCompactWidth,
                        wideListPaneWidth = wideListPaneWidth,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                BottomNavItem.Settings -> {
                    SettingsTabContent(
                        viewModel = settingsViewModel,
                        onResetPassword = onNavigateToChangePassword,
                        onSecurityQuestions = onNavigateToSecurityQuestion,
                        onNavigateToMasterPasswordLocking = onNavigateToMasterPasswordLocking,
                        onNavigateToSyncBackup = onNavigateToSyncBackup,
                        onNavigateToAutofill = onNavigateToAutofill,
                        onNavigateToPasskeySettings = onNavigateToPasskeySettings,
                        onNavigateToBottomNavSettings = onNavigateToBottomNavSettings,
                        onNavigateToColorScheme = onNavigateToColorScheme,
                        onSecurityAnalysis = onSecurityAnalysis,
                        onNavigateToDeveloperSettings = onNavigateToDeveloperSettings,
                        onNavigateToPermissionManagement = onNavigateToPermissionManagement,
                        onNavigateToMonicaPlus = onNavigateToMonicaPlus,
                        onNavigateToExtensions = onNavigateToExtensions,
                        onNavigateToPageCustomization = onNavigateToPageCustomization,
                        isCompactWidth = isCompactWidth,
                        wideListPaneWidth = wideListPaneWidth,
                        onClearAllData = onClearAllData
                    )
                }
            }
            }

            MainScreenSelectionBars(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 20.dp),
                currentTab = currentTab,
                cardWalletSubTab = cardWalletSubTab,
                isPasswordSelectionMode = isPasswordSelectionMode,
                selectedPasswordCount = selectedPasswordCount,
                onExitPasswordSelection = onExitPasswordSelection,
                onSelectAllPasswords = onSelectAllPasswords,
                onFavoriteSelectedPasswords = onFavoriteSelectedPasswords,
                onMoveToCategoryPasswords = onMoveToCategoryPasswords,
                onManualStackPasswords = onManualStackPasswords,
                onDeleteSelectedPasswords = onDeleteSelectedPasswords,
                isTotpSelectionMode = isTotpSelectionMode,
                selectedTotpCount = selectedTotpCount,
                onExitTotpSelection = onExitTotpSelection,
                onSelectAllTotp = onSelectAllTotp,
                onMoveToCategoryTotp = onMoveToCategoryTotp,
                onDeleteSelectedTotp = onDeleteSelectedTotp,
                isBankCardSelectionMode = isBankCardSelectionMode,
                selectedBankCardCount = selectedBankCardCount,
                onExitBankCardSelection = onExitBankCardSelection,
                onSelectAllBankCards = onSelectAllBankCards,
                onFavoriteBankCards = onFavoriteBankCards,
                onMoveToCategoryBankCards = onMoveToCategoryBankCards,
                onDeleteSelectedBankCards = onDeleteSelectedBankCards,
                isDocumentSelectionMode = isDocumentSelectionMode,
                selectedDocumentCount = selectedDocumentCount,
                onExitDocumentSelection = onExitDocumentSelection,
                onSelectAllDocuments = onSelectAllDocuments,
                onMoveToCategoryDocuments = onMoveToCategoryDocuments,
                onDeleteSelectedDocuments = onDeleteSelectedDocuments
            )
            }
        } else {
            val railTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val railBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            ) {
                if (!shouldHideBottomNavigation) {
                    NavigationRail(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(wideNavigationRailWidth),
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(
                                    top = railTopInset + 8.dp,
                                    bottom = railBottomInset + 8.dp
                                ),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            tabs.forEach { item ->
                                val label = stringResource(item.shortLabelRes())
                                NavigationRailItem(
                                    selected = item.key == selectedDockTab.key,
                                    onClick = { selectedTabKey = item.key },
                                    icon = { Icon(item.icon, contentDescription = label) },
                                    label = {
                                        Text(
                                            text = label,
                                            maxLines = 2,
                                            overflow = TextOverflow.Clip
                                        )
                                    },
                                    alwaysShowLabel = true
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // Wide layouts render page-owned top bars inside this host. Keep
                        // the host below the status bar while the navigation rail applies
                        // its own inset independently.
                        .windowInsetsPadding(WindowInsets.statusBars)
                ) {
                AuthenticatorPasskeyAnimatedContent(currentTab = currentTab) { displayedTab ->
                when (displayedTab) {
                    BottomNavItem.VaultV2 -> {
                        RenderVaultV2Tab()
                    }
                    BottomNavItem.Passwords -> {
                        PasswordTabPane(
                            isCompactWidth = isCompactWidth,
                            wideListPaneWidth = wideListPaneWidth,
                            passwordViewModel = passwordViewModel,
                            settingsViewModel = settingsViewModel,
                            securityManager = securityManager,
                            keepassDatabases = keepassDatabases,
							mdbxDatabases = mdbxDatabases,
                            bitwardenVaults = bitwardenVaults,
                            localKeePassViewModel = localKeePassViewModel,
                            mdbxViewModel = mdbxViewModel,
                            timelineViewModel = timelineViewModel,
                            groupMode = passwordGroupMode,
                            stackCardMode = stackCardMode,
                            visibleContentTypes = passwordPageVisibleContentTypes,
                            selectedContentTypes = passwordPageSelectedContentTypes,
                            onToggleContentType = { type ->
                                passwordPageSelectedContentTypes = togglePasswordPageContentType(
                                    currentTypes = passwordPageSelectedContentTypes,
                                    toggledType = type,
                                    visibleTypes = passwordPageVisibleContentTypes
                                )
                            },
                            onPasswordOpen = handlePasswordDetailOpen,
                            onNavigateToAddTotp = onNavigateToAddTotp,
                            onNavigateToBankCardDetail = onNavigateToBankCardDetail,
                            onNavigateToDocumentDetail = onNavigateToDocumentDetail,
                            onNavigateToBillingAddressDetail = handleBillingAddressOpen,
                            onNavigateToAddNote = handleNoteOpen,
                            onNavigateToNoteDetail = onNavigateToNoteDetail,
                            onNavigateToPasskeyDetail = onNavigateToPasskeyDetail,
                            onOpenMdbxCommitHistory = onNavigateToMdbxCommitHistory,
                            onOpenHistoryPage = openHistoryPage,
                            onOpenTrashPage = openTrashPage,
                            onOpenCommonAccountTemplatesPage = onNavigateToCommonAccountTemplates,
                            onScanFidoQr = onNavigateToFidoQrScan,
                            onCloseHistoryPage = closeHistoryPage,
                            passwordHistoryPageMode = passwordHistoryPageMode,
                            passwordHistoryInitialTrashScopeKey = passwordHistoryInitialTrashScopeKey,
                            onTimelineLogSelected = handleTimelineLogOpen,
                            onSelectionModeChange = { isSelectionMode, count, onExit, onSelectAll, onFavorite, onMoveToCategory, onStack, onDelete ->
                                isPasswordSelectionMode = isSelectionMode
                                selectedPasswordCount = count
                                onExitPasswordSelection = onExit
                                onSelectAllPasswords = onSelectAll
                                onFavoriteSelectedPasswords = onFavorite
                                onMoveToCategoryPasswords = onMoveToCategory
                                onManualStackPasswords = onStack
                                onDeleteSelectedPasswords = onDelete
                            },
                            onBackToTopVisibilityChange = { visible ->
                                passwordListShowBackToTop = visible
                            },
                            scrollToTopRequestKey = passwordScrollToTopRequestKey,
                            isAddingPasswordInline = isAddingPasswordInline,
                            inlinePasswordEditorId = inlinePasswordEditorId,
                            selectedPasswordId = selectedPasswordId,
                            passwordNewItemDefaults = pendingInlinePasswordAddStorageDefaults ?: passwordNewItemDefaults,
                            onInlinePasswordEditorBack = handleInlinePasswordEditorBack,
                            onNavigateToAddWifi = onNavigateToAddWifi,
                            onNavigateToAddSshKey = onNavigateToAddSshKey,
                            pendingPasswordAuthenticatorQrResult = pendingPasswordAuthenticatorQrResult,
                            onConsumePendingPasswordAuthenticatorQrResult =
                                onConsumePendingPasswordAuthenticatorQrResult,
                            onScanPasswordAuthenticatorQrCode = onScanPasswordAuthenticatorQrCode,
                            totpViewModel = totpViewModel,
                            bankCardViewModel = bankCardViewModel,
                            noteViewModel = noteViewModel,
                            documentViewModel = documentViewModel,
                            billingAddressViewModel = billingAddressViewModel,
                            passkeyViewModel = passkeyViewModel,
                            biometricEnabled = appSettings.biometricEnabled,
                            iconCardsEnabled = appSettings.iconCardsEnabled && appSettings.passwordPageIconEnabled,
                            unmatchedIconHandlingStrategy = appSettings.unmatchedIconHandlingStrategy,
                            onClearSelectedPassword = clearSelectedPasswordPaneItem,
                            onEditPassword = handlePasswordEditOpen
                            ,
                            showStandaloneSettingsEntry = shouldHideBottomNavigation,
                            onOpenStandaloneSettings = onNavigateToStandaloneSettings
                        )
                    }
                    BottomNavItem.Authenticator -> {
                        AuthenticatorTabPane(
                            isCompactWidth = isCompactWidth,
                            wideListPaneWidth = wideListPaneWidth,
                            totpViewModel = totpViewModel,
                            passwordViewModel = passwordViewModel,
                            appSettings = appSettings,
                            onAuthenticatorLayoutModeChange = settingsViewModel::updateAuthenticatorLayoutMode,
                            localKeePassViewModel = localKeePassViewModel,
                            onTotpOpen = handleTotpOpen,
                            onNavigateToQuickTotpScan = onNavigateToQuickTotpScan,
                            onSelectionModeChange = { isSelectionMode, count, onExit, onSelectAll, onMoveToCategory, onDelete ->
                                isTotpSelectionMode = isSelectionMode
                                selectedTotpCount = count
                                onExitTotpSelection = onExit
                                onSelectAllTotp = onSelectAll
                                onMoveToCategoryTotp = onMoveToCategory
                                onDeleteSelectedTotp = onDelete
                            },
                            isAddingTotpInline = isAddingTotpInline,
                            selectedTotpId = selectedTotpId,
                            totpNewItemDefaults = pendingInlineTotpAddStorageDefaults ?: totpNewItemDefaults,
                            onInlineTotpEditorBack = handleInlineTotpEditorBack,
                            showStandaloneSettingsEntry = shouldHideBottomNavigation,
                            onOpenStandaloneSettings = onNavigateToStandaloneSettings
                        )
                    }
                    BottomNavItem.CardWallet -> {
                        CardWalletPane(
                            isCompactWidth = isCompactWidth,
                            wideListPaneWidth = wideListPaneWidth,
                            saveableStateHolder = cardWalletSaveableStateHolder,
                            bankCardViewModel = bankCardViewModel,
                            documentViewModel = documentViewModel,
                            billingAddressViewModel = billingAddressViewModel,
                            passwordViewModel = passwordViewModel,
                            bitwardenViewModel = bitwardenViewModel,
                            contentState = cardWalletContentState,
                            isAddingBankCardInline = isAddingBankCardInline,
                            inlineBankCardEditorId = inlineBankCardEditorId,
                            onInlineBankCardEditorBack = handleInlineBankCardEditorBack,
                            isAddingDocumentInline = isAddingDocumentInline,
                            inlineDocumentEditorId = inlineDocumentEditorId,
                            onInlineDocumentEditorBack = handleInlineDocumentEditorBack,
                            isAddingBillingAddressInline = isAddingBillingAddressInline,
                            inlineBillingAddressEditorId = inlineBillingAddressEditorId,
                            onInlineBillingAddressEditorBack = handleInlineBillingAddressEditorBack,
                            selectedBankCardId = selectedBankCardId,
                            onClearSelectedBankCard = clearSelectedBankCardPaneItem,
                            onEditBankCard = handleBankCardEditOpen,
                            selectedDocumentId = selectedDocumentId,
                            onClearSelectedDocument = clearSelectedDocumentPaneItem,
                            onEditDocument = handleDocumentEditOpen,
                            selectedBillingAddressId = selectedBillingAddressId,
                            onClearSelectedBillingAddress = clearSelectedBillingAddressPaneItem,
                            onEditBillingAddress = handleBillingAddressEditOpen,
                            initialCategoryId = pendingInlineWalletAddStorageDefaults?.categoryId,
                            initialKeePassDatabaseId = pendingInlineWalletAddStorageDefaults?.keepassDatabaseId,
                            initialKeePassGroupPath = pendingInlineWalletAddStorageDefaults?.keepassGroupPath,
                            initialMdbxDatabaseId = pendingInlineWalletAddStorageDefaults?.mdbxDatabaseId,
                            initialMdbxFolderId = pendingInlineWalletAddStorageDefaults?.mdbxFolderId,
                            initialBitwardenVaultId = pendingInlineWalletAddStorageDefaults?.bitwardenVaultId,
                            initialBitwardenFolderId = pendingInlineWalletAddStorageDefaults?.bitwardenFolderId,
                            showStandaloneSettingsEntry = shouldHideBottomNavigation,
                            onOpenStandaloneSettings = onNavigateToStandaloneSettings
                        )
                    }
                    BottomNavItem.Generator -> {
                        GeneratorPane(
                            isCompactWidth = isCompactWidth,
                            wideListPaneWidth = wideListPaneWidth,
                            generatorViewModel = generatorViewModel,
                            passwordViewModel = passwordViewModel,
                            externalRefreshRequestKey = generatorRefreshRequestKey,
                            onRefreshRequestConsumed = { generatorRefreshRequestKey = 0 },
                            selectedGenerator = selectedGeneratorType,
                            generatedValue = currentGeneratorResult,
                            showStandaloneSettingsEntry = shouldHideBottomNavigation,
                            onOpenStandaloneSettings = onNavigateToStandaloneSettings
                        )
                    }
                    BottomNavItem.Notes -> {
                        NotePane(
                            isCompactWidth = isCompactWidth,
                            wideListPaneWidth = wideListPaneWidth,
                            noteViewModel = noteViewModel,
                            settingsViewModel = settingsViewModel,
                            securityManager = securityManager,
                            passwordViewModel = passwordViewModel,
                            onNavigateToAddNote = handleNoteOpen,
                            onNavigateToSearchedNote = onNavigateToSearchedNote,
                            onSelectionModeChange = { isSelectionMode ->
                                isNoteSelectionMode = isSelectionMode
                            },
                            isAddingNoteInline = isAddingNoteInline,
                            inlineNoteEditorId = inlineNoteEditorId,
                            onInlineNoteEditorBack = handleInlineNoteEditorBack,
                            initialCategoryId = pendingInlineNoteAddStorageDefaults?.categoryId,
                            initialKeePassDatabaseId = pendingInlineNoteAddStorageDefaults?.keepassDatabaseId,
                            initialKeePassGroupPath = pendingInlineNoteAddStorageDefaults?.keepassGroupPath,
                            initialMdbxDatabaseId = pendingInlineNoteAddStorageDefaults?.mdbxDatabaseId,
                            initialBitwardenVaultId = pendingInlineNoteAddStorageDefaults?.bitwardenVaultId,
                            initialBitwardenFolderId = pendingInlineNoteAddStorageDefaults?.bitwardenFolderId,
                            showStandaloneSettingsEntry = shouldHideBottomNavigation,
                            onOpenStandaloneSettings = onNavigateToStandaloneSettings
                        )
                    }
                    BottomNavItem.Passkey -> {
                        PasskeyPane(
                            isCompactWidth = isCompactWidth,
                            wideListPaneWidth = wideListPaneWidth,
                            passkeyViewModel = passkeyViewModel,
                            passwordViewModel = passwordViewModel,
                            onNavigateToPasswordDetail = onNavigateToPasswordDetail,
                            onNavigateToPasskeyDetail = onNavigateToPasskeyDetail,
                            onPasskeyOpen = handlePasskeyOpen,
                            selectedPasskey = selectedPasskey,
                            passkeyTotalCount = passkeyTotalCount,
                            passkeyBoundCount = passkeyBoundCount,
                            resolvePasswordTitle = { passwordId -> passwordById[passwordId]?.title },
                            onOpenPasswordDetail = handlePasswordDetailOpen,
                            onUnbindPasskey = handlePasskeyUnbind,
                            onDeletePasskey = { passkey -> pendingPasskeyDelete = passkey },
                            showStandaloneSettingsEntry = shouldHideBottomNavigation,
                            onOpenStandaloneSettings = onNavigateToStandaloneSettings,
                            onNavigateToAuthenticator = {
                                selectedTabKey = BottomNavItem.Authenticator.key
                            }
                        )
                    }
                    BottomNavItem.Send -> {
                        SendPane(
                            isCompactWidth = isCompactWidth,
                            wideListPaneWidth = wideListPaneWidth,
                            bitwardenViewModel = bitwardenViewModel,
                            sendState = sendState,
                            selectedSend = selectedSend,
                            isAddingSendInline = isAddingSendInline,
                            onSendClick = handleSendOpen,
                            onInlineSendEditorBack = handleInlineSendEditorBack,
                            onCreateSend = { vaultId, title, text, notes, password, maxAccessCount, hideEmail, hiddenText, expireInDays ->
                                bitwardenViewModel.createTextSend(
                                    vaultId = vaultId,
                                    title = title,
                                    text = text,
                                    notes = notes,
                                    password = password,
                                    maxAccessCount = maxAccessCount,
                                    hideEmail = hideEmail,
                                    hiddenText = hiddenText,
                                    expireInDays = expireInDays
                                )
                            },
                            onCreateFileSend = { vaultId, title, fileUri, fileName, notes, password, maxAccessCount, hideEmail, expireInDays ->
                                bitwardenViewModel.createFileSend(
                                    vaultId = vaultId,
                                    title = title,
                                    fileUri = fileUri,
                                    fileName = fileName,
                                    notes = notes,
                                    password = password,
                                    maxAccessCount = maxAccessCount,
                                    hideEmail = hideEmail,
                                    expireInDays = expireInDays
                                )
                            },
                            onBitwardenEvent = handleSendBitwardenEvent,
                            showStandaloneSettingsEntry = shouldHideBottomNavigation,
                            onOpenStandaloneSettings = onNavigateToStandaloneSettings
                        )
                    }
                    BottomNavItem.Steam -> {
                        SteamScreen(
                            showStandaloneSettingsEntry = shouldHideBottomNavigation,
                            onOpenStandaloneSettings = onNavigateToStandaloneSettings,
                            pendingSteamQrResult = pendingSteamQrResult,
                            pendingSteamQrAccountId = pendingSteamQrAccountId,
                            onConsumePendingSteamQrResult = onConsumePendingSteamQrResult,
                            onScanSteamQrCode = onScanSteamQrCode,
                            isCompactWidth = isCompactWidth,
                            wideListPaneWidth = wideListPaneWidth,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    BottomNavItem.Settings -> {
                        SettingsTabContent(
                            viewModel = settingsViewModel,
                            onResetPassword = onNavigateToChangePassword,
                            onSecurityQuestions = onNavigateToSecurityQuestion,
                            onNavigateToMasterPasswordLocking = onNavigateToMasterPasswordLocking,
                            onNavigateToSyncBackup = onNavigateToSyncBackup,
                            onNavigateToAutofill = onNavigateToAutofill,
                            onNavigateToPasskeySettings = onNavigateToPasskeySettings,
                            onNavigateToBottomNavSettings = onNavigateToBottomNavSettings,
                            onNavigateToColorScheme = onNavigateToColorScheme,
                            onSecurityAnalysis = onSecurityAnalysis,
                            onNavigateToDeveloperSettings = onNavigateToDeveloperSettings,
                            onNavigateToPermissionManagement = onNavigateToPermissionManagement,
                            onNavigateToMonicaPlus = onNavigateToMonicaPlus,
                            onNavigateToExtensions = onNavigateToExtensions,
                            onNavigateToPageCustomization = onNavigateToPageCustomization,
                            isCompactWidth = isCompactWidth,
                            wideListPaneWidth = wideListPaneWidth,
                            onClearAllData = onClearAllData
                        )
                    }
                }
                }

                MainScreenSelectionBars(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 20.dp),
                    currentTab = currentTab,
                    cardWalletSubTab = cardWalletSubTab,
                    isPasswordSelectionMode = isPasswordSelectionMode,
                    selectedPasswordCount = selectedPasswordCount,
                    onExitPasswordSelection = onExitPasswordSelection,
                    onSelectAllPasswords = onSelectAllPasswords,
                    onFavoriteSelectedPasswords = onFavoriteSelectedPasswords,
                    onMoveToCategoryPasswords = onMoveToCategoryPasswords,
                    onManualStackPasswords = onManualStackPasswords,
                    onDeleteSelectedPasswords = onDeleteSelectedPasswords,
                    isTotpSelectionMode = isTotpSelectionMode,
                    selectedTotpCount = selectedTotpCount,
                    onExitTotpSelection = onExitTotpSelection,
                    onSelectAllTotp = onSelectAllTotp,
                    onMoveToCategoryTotp = onMoveToCategoryTotp,
                    onDeleteSelectedTotp = onDeleteSelectedTotp,
                    isBankCardSelectionMode = isBankCardSelectionMode,
                    selectedBankCardCount = selectedBankCardCount,
                    onExitBankCardSelection = onExitBankCardSelection,
                    onSelectAllBankCards = onSelectAllBankCards,
                    onFavoriteBankCards = onFavoriteBankCards,
                    onMoveToCategoryBankCards = onMoveToCategoryBankCards,
                    onDeleteSelectedBankCards = onDeleteSelectedBankCards,
                    isDocumentSelectionMode = isDocumentSelectionMode,
                    selectedDocumentCount = selectedDocumentCount,
                    onExitDocumentSelection = onExitDocumentSelection,
                    onSelectAllDocuments = onSelectAllDocuments,
                    onMoveToCategoryDocuments = onMoveToCategoryDocuments,
                    onDeleteSelectedDocuments = onDeleteSelectedDocuments
                )
                }
            }
        }
    }
    }
    }

    val prepareTotpAddStorageDefaults: (Long?, Long?, String?, Long?, String?, Long?, String?) -> Unit = { categoryId, keepassDatabaseId, keepassGroupPath, mdbxDatabaseId, mdbxFolderId, bitwardenVaultId, bitwardenFolderId ->
        if (isCompactWidth) {
            pendingInlineTotpAddStorageDefaults = null
            onPrepareTotpAddStorageDefaults(categoryId, keepassDatabaseId, keepassGroupPath, mdbxDatabaseId, mdbxFolderId, bitwardenVaultId, bitwardenFolderId)
        } else {
            pendingInlineTotpAddStorageDefaults = NewItemStorageDefaults(
                categoryId = categoryId,
                keepassDatabaseId = keepassDatabaseId,
                keepassGroupPath = keepassGroupPath,
                mdbxDatabaseId = mdbxDatabaseId,
                mdbxFolderId = mdbxFolderId,
                bitwardenVaultId = bitwardenVaultId,
                bitwardenFolderId = bitwardenFolderId
            ).takeIf { it.hasAnyValue() }
        }
    }
    val preparePasswordAddStorageDefaults: (Long?, Long?, String?, Long?, String?, Long?, String?) -> Unit = { categoryId, keepassDatabaseId, keepassGroupPath, mdbxDatabaseId, mdbxFolderId, bitwardenVaultId, bitwardenFolderId ->
        if (isCompactWidth) {
            pendingInlinePasswordAddStorageDefaults = null
            onPreparePasswordAddStorageDefaults(categoryId, keepassDatabaseId, keepassGroupPath, mdbxDatabaseId, mdbxFolderId, bitwardenVaultId, bitwardenFolderId)
        } else {
            pendingInlinePasswordAddStorageDefaults = NewItemStorageDefaults(
                categoryId = categoryId,
                keepassDatabaseId = keepassDatabaseId,
                keepassGroupPath = keepassGroupPath,
                mdbxDatabaseId = mdbxDatabaseId,
                mdbxFolderId = mdbxFolderId,
                bitwardenVaultId = bitwardenVaultId,
                bitwardenFolderId = bitwardenFolderId
            ).takeIf { it.hasAnyValue() }
        }
    }
    val prepareNoteAddStorageDefaults: (Long?, Long?, String?, Long?, String?, Long?, String?) -> Unit = { categoryId, keepassDatabaseId, keepassGroupPath, mdbxDatabaseId, mdbxFolderId, bitwardenVaultId, bitwardenFolderId ->
        if (isCompactWidth) {
            pendingInlineNoteAddStorageDefaults = null
            onPrepareNoteAddStorageDefaults(categoryId, keepassDatabaseId, keepassGroupPath, mdbxDatabaseId, mdbxFolderId, bitwardenVaultId, bitwardenFolderId)
        } else {
            pendingInlineNoteAddStorageDefaults = NewItemStorageDefaults(
                categoryId = categoryId,
                keepassDatabaseId = keepassDatabaseId,
                keepassGroupPath = keepassGroupPath,
                mdbxDatabaseId = mdbxDatabaseId,
                mdbxFolderId = mdbxFolderId,
                bitwardenVaultId = bitwardenVaultId,
                bitwardenFolderId = bitwardenFolderId
            ).takeIf { it.hasAnyValue() }
        }
    }
    val prepareWalletAddStorageDefaults: (Long?, Long?, String?, Long?, String?, Long?, String?) -> Unit = { categoryId, keepassDatabaseId, keepassGroupPath, mdbxDatabaseId, mdbxFolderId, bitwardenVaultId, bitwardenFolderId ->
        if (isCompactWidth) {
            pendingInlineWalletAddStorageDefaults = null
            onPrepareWalletAddStorageDefaults(categoryId, keepassDatabaseId, keepassGroupPath, mdbxDatabaseId, mdbxFolderId, bitwardenVaultId, bitwardenFolderId)
        } else {
            pendingInlineWalletAddStorageDefaults = NewItemStorageDefaults(
                categoryId = categoryId,
                keepassDatabaseId = keepassDatabaseId,
                keepassGroupPath = keepassGroupPath,
                mdbxDatabaseId = mdbxDatabaseId,
                mdbxFolderId = mdbxFolderId,
                bitwardenVaultId = bitwardenVaultId,
                bitwardenFolderId = bitwardenFolderId
            ).takeIf { it.hasAnyValue() }
        }
    }

    val passwordFastScrollStripProgress by passwordViewModel.fastScrollProgress.collectAsState()
    MainScreenFabOverlay(
        currentTab = currentTab,
        isCompactWidth = isCompactWidth,
        shouldHideBottomNavigation = shouldHideBottomNavigation,
        wideFabHostWidth = wideFabHostWidth,
        vaultV2HasWideDetail = vaultV2SuppressesFab,
        appSettings = appSettings,
        passwordHistoryPageMode = passwordHistoryPageMode,
        isAnySelectionMode = isAnySelectionMode,
        isAddingPasswordInline = isAddingPasswordInline,
        inlinePasswordEditorId = inlinePasswordEditorId,
        isAddingTotpInline = isAddingTotpInline,
        selectedTotpId = selectedTotpId,
        isAddingBankCardInline = isAddingBankCardInline,
        inlineBankCardEditorId = inlineBankCardEditorId,
        selectedBankCardId = selectedBankCardId,
        isAddingDocumentInline = isAddingDocumentInline,
        inlineDocumentEditorId = inlineDocumentEditorId,
        selectedDocumentId = selectedDocumentId,
        isAddingBillingAddressInline = isAddingBillingAddressInline,
        inlineBillingAddressEditorId = inlineBillingAddressEditorId,
        selectedBillingAddressId = selectedBillingAddressId,
        isAddingNoteInline = isAddingNoteInline,
        inlineNoteEditorId = inlineNoteEditorId,
        isAddingSendInline = isAddingSendInline,
        isFabVisible = isFabVisible,
        isFabExpanded = isFabExpanded,
        onFabExpandedChange = { expanded -> isFabExpanded = expanded },
        fastScrollStripVisible = isFastScrollStripVisible,
        onFastScrollStripVisibleChange = { visible -> isFastScrollStripVisible = visible },
        fastScrollStripProgress = if (currentTab == BottomNavItem.VaultV2) {
            vaultV2PaneState.fastScrollProgress
        } else {
            passwordFastScrollStripProgress
        },
        onFastScrollProgressChange = if (currentTab == BottomNavItem.VaultV2) {
            vaultV2PaneState::requestFastScroll
        } else {
            passwordViewModel::requestFastScroll
        },
        fastScrollIndicatorLabel = if (currentTab == BottomNavItem.VaultV2) {
            vaultV2PaneState.fastScrollIndicatorLabel
        } else {
            null
        },
        vaultV2FastScrollbarInteracting =
            currentTab == BottomNavItem.VaultV2 && vaultV2PaneState.isFastScrollbarInteracting,
        passwordListShowBackToTop = if (currentTab == BottomNavItem.VaultV2) {
            vaultV2PaneState.showBackToTop
        } else {
            passwordListShowBackToTop
        },
        onBackToTop = {
            if (currentTab == BottomNavItem.VaultV2) {
                vaultV2PaneState.requestScrollToTop()
            } else {
                passwordScrollToTopRequestKey++
            }
        },
        quickAccessEnabled =
            (currentTab == BottomNavItem.Passwords || currentTab == BottomNavItem.VaultV2) &&
                appSettings.passwordListQuickAccessEnabled,
        showPasswordQuickAccessSheet = showPasswordQuickAccessSheet,
        onShowPasswordQuickAccessSheetChange = { showPasswordQuickAccessSheet = it },
        recentOpenedPasswords = recentOpenedPasswords,
        frequentOpenedPasswords = frequentOpenedPasswords,
        onOpenPasswordFromQuickAccess = handlePasswordDetailOpen,
        onNavigateToPasskey = {
            selectedTabKey = BottomNavItem.Passkey.key
        },
        cardWalletSubTab = cardWalletSubTab,
        onPasswordAddOpen = handlePasswordAddOpen,
        onTotpAddOpen = handleTotpAddOpen,
        onBankCardAddOpen = handleBankCardAddOpen,
        onWalletAddOpen = handleWalletAddOpen,
        onNavigateToWalletAdd = onNavigateToWalletAdd,
        onCreateVaultFolder = vaultV2PaneState::requestCreateFolderDialog,
        allowVaultFolderCreation = currentTab == BottomNavItem.VaultV2 &&
            vaultV2PaneState.storageFilterType != takagi.ru.monica.ui.vaultv2.VAULT_V2_STORAGE_FILTER_ALL,
        passwordPageAggregateEnabled =
            if (currentTab == BottomNavItem.VaultV2) true else appSettings.passwordPageAggregateEnabled,
        passwordNewItemDefaults = passwordNewItemDefaults,
        onPreparePasswordAddStorageDefaults = preparePasswordAddStorageDefaults,
        onPrepareTotpAddStorageDefaults = prepareTotpAddStorageDefaults,
        onPrepareNoteAddStorageDefaults = prepareNoteAddStorageDefaults,
        onPrepareWalletAddStorageDefaults = prepareWalletAddStorageDefaults,
        onNoteAddOpen = { handleNoteOpen(null) },
        onSendAddOpen = handleSendAddOpen,
        onGeneratorRefresh = { generatorRefreshRequestKey++ },
        passwordViewModel = passwordViewModel,
        totpViewModel = totpViewModel,
        bankCardViewModel = bankCardViewModel,
        localKeePassViewModel = localKeePassViewModel,
        totpNewItemDefaults = totpNewItemDefaults,
        onNavigateToQuickTotpScan = onNavigateToQuickTotpScan,
        pendingPasswordAuthenticatorQrResult = pendingPasswordAuthenticatorQrResult,
        onConsumePendingPasswordAuthenticatorQrResult =
            onConsumePendingPasswordAuthenticatorQrResult,
        onScanPasswordAuthenticatorQrCode = onScanPasswordAuthenticatorQrCode,
        walletUnifiedAddType = walletUnifiedAddType,
        onWalletUnifiedAddTypeChange = { walletUnifiedAddType = it },
        documentViewModel = documentViewModel,
        walletAddSaveableStateHolder = walletAddSaveableStateHolder,
        noteViewModel = noteViewModel,
        sendState = sendState,
        bitwardenViewModel = bitwardenViewModel,
        onNavigateToAddWifi = onNavigateToAddWifi,
        onNavigateToAddSshKey = onNavigateToAddSshKey
    )

    val navBarInsetBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val compactBottomOffset = if (useDraggableNav) {
        92.dp + navBarInsetBottom
    } else {
        88.dp + navBarInsetBottom
    }
    val hintModifier = if (isCompactWidth) {
        Modifier
            .align(Alignment.BottomStart)
            .padding(start = 16.dp, bottom = compactBottomOffset + 28.dp)
            .zIndex(4f)
    } else {
        Modifier
            .align(Alignment.BottomStart)
            .padding(start = wideFabHostWidth + 16.dp, bottom = 24.dp + 12.dp)
            .zIndex(4f)
    }
    val statusHintContainerColor = when (currentTab) {
        BottomNavItem.CardWallet -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val statusHintTextColor = when (currentTab) {
        BottomNavItem.CardWallet -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Column(
        modifier = hintModifier.animateContentSize(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SyncMiniHintBubble(
            visible = syncHintVisible,
            text = stringResource(bottomStatusUiState?.messageRes ?: R.string.sync_status_syncing_short),
            containerColor = statusHintContainerColor,
            textColor = statusHintTextColor
        )

        activeMiniHints.forEach { hint ->
            val hintVisible = hint.id !in dismissingHintIds
            CustomMiniHintBubble(
                visible = hintVisible,
                hint = hint,
                containerColor = statusHintContainerColor,
                textColor = statusHintTextColor
            )
        }
    }
    } // End Outer Box
    }

    RenderMainSurface()

    if (pendingPasskeyDelete != null) {
        val passkey = pendingPasskeyDelete!!
        AlertDialog(
            onDismissRequest = { pendingPasskeyDelete = null },
            title = { Text(stringResource(R.string.passkey_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.passkey_delete_message,
                        passkey.rpName.ifBlank { passkey.rpId },
                        passkey.userName.ifBlank { "-" }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = confirmPasskeyDelete) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPasskeyDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

}

@Composable
private fun MainScreenTabResetEffects(
    currentTab: BottomNavItem,
    isCompactWidth: Boolean,
    cardWalletSubTab: CardWalletTab,
    passwordHistoryPageMode: PasswordHistoryPageMode,
    onResetPasswordPane: () -> Unit,
    onHideBackToTop: () -> Unit,
    onResetTotpPane: () -> Unit,
    onResetCardWalletPaneAll: () -> Unit,
    onResetCardWalletDocumentPane: () -> Unit,
    onResetCardWalletBankCardPane: () -> Unit,
    onResetCardWalletBillingAddressPane: () -> Unit,
    onSyncWalletUnifiedAddType: (CardWalletTab) -> Unit,
    onResetNotePane: () -> Unit,
    onResetPasskeyPane: () -> Unit,
    onResetSendPane: () -> Unit,
    onResetTimelineSelection: () -> Unit,
) {
    // Each effect owns one tab domain reset. Keep them split to avoid hidden coupling.
    LaunchedEffect(currentTab.key) {
        if (currentTab != BottomNavItem.Passwords && currentTab != BottomNavItem.VaultV2) {
            onHideBackToTop()
        }
    }
    LaunchedEffect(currentTab.key, isCompactWidth) {
        if (isCompactWidth && currentTab != BottomNavItem.Passwords) {
            onResetPasswordPane()
        }
    }
    if (!isCompactWidth) {
        LaunchedEffect(currentTab.key) {
            if (currentTab != BottomNavItem.Passwords) {
                onResetPasswordPane()
            }
        }
        LaunchedEffect(currentTab.key) {
            if (currentTab != BottomNavItem.Authenticator && currentTab != BottomNavItem.Passkey) {
                onResetTotpPane()
            }
        }
        LaunchedEffect(currentTab.key, cardWalletSubTab) {
            if (currentTab != BottomNavItem.CardWallet) {
                onResetCardWalletPaneAll()
            } else {
                when (cardWalletSubTab) {
                    CardWalletTab.BANK_CARDS -> {
                        onResetCardWalletDocumentPane()
                        onResetCardWalletBillingAddressPane()
                    }
                    CardWalletTab.DOCUMENTS -> {
                        onResetCardWalletBankCardPane()
                        onResetCardWalletBillingAddressPane()
                    }
                    CardWalletTab.BILLING_ADDRESSES -> {
                        onResetCardWalletBankCardPane()
                        onResetCardWalletDocumentPane()
                    }
                    CardWalletTab.ALL -> Unit
                }
            }
        }
        LaunchedEffect(currentTab.key) {
            if (currentTab != BottomNavItem.Notes) {
                onResetNotePane()
            }
        }
        LaunchedEffect(currentTab.key) {
            if (currentTab != BottomNavItem.Authenticator && currentTab != BottomNavItem.Passkey) {
                onResetPasskeyPane()
            }
        }
        LaunchedEffect(currentTab.key) {
            if (currentTab != BottomNavItem.Send) {
                onResetSendPane()
            }
        }
        LaunchedEffect(currentTab.key, passwordHistoryPageMode) {
            if (currentTab != BottomNavItem.Passwords || !passwordHistoryPageMode.isVisible) {
                onResetTimelineSelection()
            }
        }
    }
    LaunchedEffect(cardWalletSubTab) {
        if (
            cardWalletSubTab == CardWalletTab.BANK_CARDS ||
            cardWalletSubTab == CardWalletTab.DOCUMENTS ||
            cardWalletSubTab == CardWalletTab.BILLING_ADDRESSES
        ) {
            onSyncWalletUnifiedAddType(cardWalletSubTab)
        }
    }
}
