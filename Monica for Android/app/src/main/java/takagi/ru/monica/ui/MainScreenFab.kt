package takagi.ru.monica.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import takagi.ru.monica.R
import takagi.ru.monica.data.AddButtonBehaviorMode
import takagi.ru.monica.data.AddButtonMenuAction
import takagi.ru.monica.ui.components.PasswordQuickAccessItem
import takagi.ru.monica.ui.components.PasswordQuickAccessSheet
import takagi.ru.monica.ui.components.SwipeableAddFab
import takagi.ru.monica.ui.main.navigation.BottomNavItem
import takagi.ru.monica.ui.screens.CardWalletTab
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.DocumentViewModel
import takagi.ru.monica.viewmodel.NoteViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.TotpViewModel

internal fun shouldHideVaultFloatingActionsForFastScroll(
    isVaultV2Tab: Boolean,
    isScrollbarInteracting: Boolean,
): Boolean = isVaultV2Tab && isScrollbarInteracting

internal data class VaultV2FabMenuAction(
    val icon: ImageVector,
    val labelRes: Int,
    val onClick: () -> Unit,
)

@Composable
internal fun BoxScope.MainScreenFabOverlay(
    currentTab: BottomNavItem,
    isCompactWidth: Boolean,
    shouldHideBottomNavigation: Boolean,
    wideFabHostWidth: Dp,
    vaultV2HasWideDetail: Boolean,
    appSettings: takagi.ru.monica.data.AppSettings,
    passwordHistoryPageMode: PasswordHistoryPageMode,
    isAnySelectionMode: Boolean,
    isAddingPasswordInline: Boolean,
    inlinePasswordEditorId: Long?,
    isAddingTotpInline: Boolean,
    selectedTotpId: Long?,
    isAddingBankCardInline: Boolean,
    inlineBankCardEditorId: Long?,
    selectedBankCardId: Long?,
    isAddingDocumentInline: Boolean,
    inlineDocumentEditorId: Long?,
    selectedDocumentId: Long?,
    isAddingBillingAddressInline: Boolean,
    inlineBillingAddressEditorId: Long?,
    selectedBillingAddressId: Long?,
    isAddingNoteInline: Boolean,
    inlineNoteEditorId: Long?,
    isAddingSendInline: Boolean,
    isFabVisible: Boolean,
    isFabExpanded: Boolean,
    onFabExpandedChange: (Boolean) -> Unit,
    fastScrollStripVisible: Boolean,
    onFastScrollStripVisibleChange: (Boolean) -> Unit,
    fastScrollStripProgress: Float,
    onFastScrollProgressChange: (Float) -> Unit,
    fastScrollIndicatorLabel: String?,
    vaultV2FastScrollbarInteracting: Boolean,
    passwordListShowBackToTop: Boolean,
    onBackToTop: () -> Unit,
    quickAccessEnabled: Boolean,
    showPasswordQuickAccessSheet: Boolean,
    onShowPasswordQuickAccessSheetChange: (Boolean) -> Unit,
    recentOpenedPasswords: List<PasswordQuickAccessItem>,
    frequentOpenedPasswords: List<PasswordQuickAccessItem>,
    onOpenPasswordFromQuickAccess: (Long) -> Unit,
    onNavigateToPasskey: () -> Unit,
    cardWalletSubTab: CardWalletTab,
    onPasswordAddOpen: () -> Unit,
    onTotpAddOpen: () -> Unit,
    onBankCardAddOpen: () -> Unit,
    onWalletAddOpen: () -> Unit,
    onNavigateToWalletAdd: (CardWalletTab) -> Unit,
    onCreateVaultFolder: () -> Unit,
    allowVaultFolderCreation: Boolean,
    passwordPageAggregateEnabled: Boolean,
    passwordNewItemDefaults: NewItemStorageDefaults,
    onPreparePasswordAddStorageDefaults: (Long?, Long?, String?, Long?, String?, Long?, String?) -> Unit,
    onPrepareTotpAddStorageDefaults: (Long?, Long?, String?, Long?, String?, Long?, String?) -> Unit,
    onPrepareNoteAddStorageDefaults: (Long?, Long?, String?, Long?, String?, Long?, String?) -> Unit,
    onPrepareWalletAddStorageDefaults: (Long?, Long?, String?, Long?, String?, Long?, String?) -> Unit,
    onNoteAddOpen: () -> Unit,
    onSendAddOpen: () -> Unit,
    onGeneratorRefresh: () -> Unit,
    passwordViewModel: PasswordViewModel,
    totpViewModel: TotpViewModel,
    bankCardViewModel: BankCardViewModel,
    localKeePassViewModel: takagi.ru.monica.viewmodel.LocalKeePassViewModel,
    totpNewItemDefaults: NewItemStorageDefaults,
    onNavigateToQuickTotpScan: () -> Unit,
    pendingPasswordAuthenticatorQrResult: String? = null,
    onConsumePendingPasswordAuthenticatorQrResult: () -> Unit = {},
    onScanPasswordAuthenticatorQrCode: () -> Unit = {},
    walletUnifiedAddType: CardWalletTab,
    onWalletUnifiedAddTypeChange: (CardWalletTab) -> Unit,
    documentViewModel: DocumentViewModel,
    walletAddSaveableStateHolder: androidx.compose.runtime.saveable.SaveableStateHolder,
    noteViewModel: NoteViewModel,
    sendState: takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel.SendState,
    bitwardenViewModel: takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel,
    onNavigateToAddWifi: (Long?) -> Unit = {},
    onNavigateToAddSshKey: (Long?) -> Unit = {},
) {
    // FAB visibility is computed from tab context + selection mode + detail pane occupancy.
    // This avoids conflicting gestures between fast-scroll, quick access, and expandable add menu.
    val hasWideDetailSelection = !isCompactWidth && when (currentTab) {
        BottomNavItem.VaultV2 -> vaultV2HasWideDetail
        BottomNavItem.Passwords -> isAddingPasswordInline || inlinePasswordEditorId != null
        BottomNavItem.Authenticator -> isAddingTotpInline || selectedTotpId != null
        BottomNavItem.CardWallet -> isAddingBankCardInline ||
            inlineBankCardEditorId != null ||
            selectedBankCardId != null ||
            isAddingDocumentInline ||
            inlineDocumentEditorId != null ||
            selectedDocumentId != null ||
            isAddingBillingAddressInline ||
            inlineBillingAddressEditorId != null ||
            selectedBillingAddressId != null
        BottomNavItem.Notes -> isAddingNoteInline || inlineNoteEditorId != null
        BottomNavItem.Send -> isAddingSendInline
        else -> false
    }

    val showFab = (
        currentTab == BottomNavItem.VaultV2 ||
            currentTab == BottomNavItem.Passwords ||
            currentTab == BottomNavItem.Authenticator ||
            currentTab == BottomNavItem.CardWallet ||
            currentTab == BottomNavItem.Generator ||
            currentTab == BottomNavItem.Notes ||
            currentTab == BottomNavItem.Send
        ) &&
        !(currentTab == BottomNavItem.Passwords && passwordHistoryPageMode.isVisible) &&
        !isAnySelectionMode &&
        !hasWideDetailSelection

    val isVaultLikeTab = currentTab == BottomNavItem.Passwords || currentTab == BottomNavItem.VaultV2
    val hideForVaultFastScroll = shouldHideVaultFloatingActionsForFastScroll(
        isVaultV2Tab = currentTab == BottomNavItem.VaultV2,
        isScrollbarInteracting = vaultV2FastScrollbarInteracting,
    )
    val fabOverlayModifier = if (isCompactWidth) {
        Modifier.fillMaxSize().zIndex(5f)
    } else {
        Modifier
            .fillMaxHeight()
            .width(wideFabHostWidth)
            .align(Alignment.TopStart)
            .zIndex(5f)
    }
    val fabContainerColor = MaterialTheme.colorScheme.primaryContainer
    val fabIconTint = MaterialTheme.colorScheme.onPrimaryContainer
    val navBarInsetBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val fabBottomOffset = when {
        !isCompactWidth -> 24.dp
        shouldHideBottomNavigation -> 24.dp + navBarInsetBottom
        else -> 116.dp
    }
    val shouldShowBackToTopFab =
        showFab &&
            isFabVisible &&
            !isFabExpanded &&
            isVaultLikeTab &&
            !isAnySelectionMode &&
            passwordListShowBackToTop &&
            !fastScrollStripVisible &&
            !hideForVaultFastScroll
    val shouldShowQuickAccessFab =
        showFab &&
            isFabVisible &&
            !isFabExpanded &&
            (currentTab == BottomNavItem.Passwords || currentTab == BottomNavItem.VaultV2) &&
            quickAccessEnabled &&
            !isAnySelectionMode &&
            !fastScrollStripVisible &&
            !hideForVaultFastScroll
    val shouldShowPasskeyFab =
        showFab &&
            isFabVisible &&
            !isFabExpanded &&
            currentTab == BottomNavItem.Authenticator &&
            !isAnySelectionMode &&
            !fastScrollStripVisible

    LaunchedEffect(showFab) {
        if (!showFab) {
            onFastScrollStripVisibleChange(false)
        }
    }

    LaunchedEffect(hideForVaultFastScroll) {
        if (hideForVaultFastScroll) {
            onFabExpandedChange(false)
            if (showPasswordQuickAccessSheet) {
                onShowPasswordQuickAccessSheetChange(false)
            }
        }
    }

    BackHandler(enabled = fastScrollStripVisible) {
        onFastScrollStripVisibleChange(false)
    }

    AnimatedVisibility(
        visible = showFab && (isFabVisible || fastScrollStripVisible),
        enter = slideInHorizontally(initialOffsetX = { it * 2 }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it * 2 }) + fadeOut(),
        modifier = fabOverlayModifier
    ) {
        val backToTopInteractionSource = remember { MutableInteractionSource() }
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = shouldShowQuickAccessFab,
                enter = scaleIn(
                    initialScale = 0.25f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                ) + fadeIn(animationSpec = tween(durationMillis = 120)),
                exit = scaleOut(
                    targetScale = 0.25f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeOut(animationSpec = tween(durationMillis = 90)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 16.dp,
                        bottom = fabBottomOffset + 88.dp
                    )
            ) {
                SmallFloatingActionButton(
                    onClick = { onShowPasswordQuickAccessSheetChange(true) },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = stringResource(R.string.password_quick_access_title)
                    )
                }
            }

            AnimatedVisibility(
                visible = shouldShowPasskeyFab,
                enter = scaleIn(
                    initialScale = 0.25f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                ) + fadeIn(animationSpec = tween(durationMillis = 120)),
                exit = scaleOut(
                    targetScale = 0.25f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeOut(animationSpec = tween(durationMillis = 90)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 16.dp,
                        bottom = fabBottomOffset + 88.dp
                    )
            ) {
                SmallFloatingActionButton(
                    onClick = onNavigateToPasskey,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = stringResource(R.string.passkey_title)
                    )
                }
            }

            AnimatedVisibility(
                visible = shouldShowBackToTopFab,
                enter = scaleIn(
                    initialScale = 0.22f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(animationSpec = tween(durationMillis = 120)),
                exit = scaleOut(
                    targetScale = 0.22f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeOut(animationSpec = tween(durationMillis = 90)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 88.dp,
                        bottom = fabBottomOffset + 16.dp
                    )
            ) {
                Box(
                    modifier = Modifier
                        .combinedClickable(
                            interactionSource = backToTopInteractionSource,
                            indication = null,
                            onClick = onBackToTop,
                            onLongClick = {
                                if (currentTab != BottomNavItem.VaultV2) {
                                    onFastScrollStripVisibleChange(true)
                                    if (showPasswordQuickAccessSheet) {
                                        onShowPasswordQuickAccessSheetChange(false)
                                    }
                                    onFabExpandedChange(false)
                                }
                            }
                        )
                ) {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        tonalElevation = 6.dp,
                        shadowElevation = 4.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.cd_back)
                            )
                        }
                    }
                }
            }

            MainScreenAddFab(
                visible = !fastScrollStripVisible && !hideForVaultFastScroll,
                fabBottomOffset = fabBottomOffset,
                fabContainerColor = fabContainerColor,
                fabIconTint = fabIconTint,
                addButtonBehaviorMode = appSettings.addButtonBehaviorMode,
                addButtonMenuOrder = appSettings.addButtonMenuOrder,
                addButtonMenuEnabledActions = appSettings.addButtonMenuEnabledActions,
                currentTab = currentTab,
                isCompactWidth = isCompactWidth,
                cardWalletSubTab = cardWalletSubTab,
                onPasswordAddOpen = onPasswordAddOpen,
                onTotpAddOpen = onTotpAddOpen,
                onBankCardAddOpen = onBankCardAddOpen,
                onWalletAddOpen = onWalletAddOpen,
                onNavigateToWalletAdd = onNavigateToWalletAdd,
                onCreateVaultFolder = onCreateVaultFolder,
                allowVaultFolderCreation = allowVaultFolderCreation,
                passwordPageAggregateEnabled = passwordPageAggregateEnabled,
                passwordNewItemDefaults = passwordNewItemDefaults,
                onPreparePasswordAddStorageDefaults = onPreparePasswordAddStorageDefaults,
                onPrepareTotpAddStorageDefaults = onPrepareTotpAddStorageDefaults,
                onPrepareNoteAddStorageDefaults = onPrepareNoteAddStorageDefaults,
                onPrepareWalletAddStorageDefaults = onPrepareWalletAddStorageDefaults,
                onNoteAddOpen = onNoteAddOpen,
                onSendAddOpen = onSendAddOpen,
                onGeneratorRefresh = onGeneratorRefresh,
                onExpandStateChanged = onFabExpandedChange,
                onNavigateToAddWifi = onNavigateToAddWifi,
                onNavigateToAddSshKey = onNavigateToAddSshKey,
                pendingPasswordAuthenticatorQrResult = pendingPasswordAuthenticatorQrResult,
                onConsumePendingPasswordAuthenticatorQrResult = onConsumePendingPasswordAuthenticatorQrResult,
                onScanPasswordAuthenticatorQrCode = onScanPasswordAuthenticatorQrCode,
                passwordViewModel = passwordViewModel,
                totpViewModel = totpViewModel,
                bankCardViewModel = bankCardViewModel,
                localKeePassViewModel = localKeePassViewModel,
                totpNewItemDefaults = totpNewItemDefaults,
                onNavigateToQuickTotpScan = onNavigateToQuickTotpScan,
                walletUnifiedAddType = walletUnifiedAddType,
                onWalletUnifiedAddTypeChange = onWalletUnifiedAddTypeChange,
                documentViewModel = documentViewModel,
                walletAddSaveableStateHolder = walletAddSaveableStateHolder,
                noteViewModel = noteViewModel,
                sendState = sendState,
                bitwardenViewModel = bitwardenViewModel
            )

            AnimatedVisibility(
                visible = fastScrollStripVisible,
                enter = fadeIn(animationSpec = tween(durationMillis = 90)),
                exit = fadeOut(animationSpec = tween(durationMillis = 90)),
                modifier = Modifier.matchParentSize()
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            val rightGuardPx = 112.dp.toPx()
                            detectTapGestures(
                                onTap = { offset ->
                                    if (offset.x < size.width - rightGuardPx) {
                                        onFastScrollStripVisibleChange(false)
                                    }
                                }
                            )
                        }
                )
            }

            FastScrollPanel(
                visible = fastScrollStripVisible,
                progress = fastScrollStripProgress,
                indicatorLabel = fastScrollIndicatorLabel,
                onProgressChange = onFastScrollProgressChange,
                onDismiss = { onFastScrollStripVisibleChange(false) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
            )
        }
    }

    LaunchedEffect(currentTab, quickAccessEnabled, passwordHistoryPageMode) {
        val quickAccessTabAllowed =
            currentTab == BottomNavItem.Passwords || currentTab == BottomNavItem.VaultV2
        val shouldHideBecausePasswordHistory =
            currentTab == BottomNavItem.Passwords && passwordHistoryPageMode.isVisible
        if (
            (
                !quickAccessTabAllowed ||
                    !quickAccessEnabled ||
                    shouldHideBecausePasswordHistory
                ) &&
            showPasswordQuickAccessSheet
        ) {
            onShowPasswordQuickAccessSheetChange(false)
        }
    }

    PasswordQuickAccessSheet(
        visible = showPasswordQuickAccessSheet,
        recentItems = recentOpenedPasswords,
        frequentItems = frequentOpenedPasswords,
        iconCardsEnabled = appSettings.iconCardsEnabled && appSettings.passwordPageIconEnabled,
        onOpenPassword = onOpenPasswordFromQuickAccess,
        onDismiss = { onShowPasswordQuickAccessSheetChange(false) }
    )
}

@Composable
internal fun FastScrollPanel(
    visible: Boolean,
    progress: Float,
    indicatorLabel: String?,
    onProgressChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it / 2 }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it / 2 }) + fadeOut(),
        modifier = modifier
    ) {
        val clampedProgress = progress.coerceIn(0f, 1f)
        var gestureAreaHeightPx by remember { mutableStateOf(1) }
        var isTracking by remember { mutableStateOf(false) }
        var trackingTouchYPx by remember { mutableStateOf(0f) }
        val activeTrackColor = MaterialTheme.colorScheme.tertiaryContainer
        val inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer
        val indicatorColor = MaterialTheme.colorScheme.tertiary
        val dotColor = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.92f)
        val density = LocalDensity.current

        fun progressFromTouchY(y: Float): Float {
            val height = gestureAreaHeightPx.coerceAtLeast(1).toFloat()
            return (y / height).coerceIn(0f, 1f)
        }

        Column(
            modifier = Modifier.width(128.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .width(128.dp)
                    .height(356.dp)
            ) {
                val sliderWidth = 40.dp
                val separatorGap = 10.dp
                val separatorThickness = 4.dp
                val activeHeight = (maxHeight - separatorGap - separatorThickness) * clampedProgress
                val inactiveHeight = (maxHeight - separatorGap - separatorThickness) - activeHeight
                val indicatorBubbleHeight = 52.dp
                val indicatorBubbleWidth = 56.dp
                val maxBubbleOffsetPx = with(density) {
                    (maxHeight - indicatorBubbleHeight).toPx().coerceAtLeast(0f)
                }
                val bubbleOffsetPx = (trackingTouchYPx - with(density) { indicatorBubbleHeight.toPx() / 2f })
                    .coerceIn(0f, maxBubbleOffsetPx)

                Box(modifier = Modifier.fillMaxSize()) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isTracking && !indicatorLabel.isNullOrBlank(),
                        enter = scaleIn(
                            initialScale = 0.9f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) + fadeIn(animationSpec = tween(120)),
                        exit = scaleOut(
                            targetScale = 0.94f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        ) + fadeOut(animationSpec = tween(90)),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(
                                x = 0.dp,
                                y = with(density) { bubbleOffsetPx.toDp() }
                            )
                    ) {
                        Surface(
                            modifier = Modifier.size(width = indicatorBubbleWidth, height = indicatorBubbleHeight),
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            tonalElevation = 6.dp,
                            shadowElevation = 2.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = indicatorLabel.orEmpty(),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .width(sliderWidth)
                            .fillMaxHeight()
                            .onSizeChanged { size ->
                                gestureAreaHeightPx = size.height.coerceAtLeast(1)
                            }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    isTracking = true
                                    trackingTouchYPx = down.position.y.coerceIn(0f, gestureAreaHeightPx.toFloat())
                                    onProgressChange(progressFromTouchY(trackingTouchYPx))

                                    var activePointerId = down.id
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == activePointerId }
                                            ?: event.changes.firstOrNull()
                                            ?: break

                                        activePointerId = change.id
                                        trackingTouchYPx = change.position.y.coerceIn(0f, gestureAreaHeightPx.toFloat())
                                        onProgressChange(progressFromTouchY(trackingTouchYPx))

                                        if (!change.pressed) {
                                            break
                                        }
                                        change.consume()
                                    }

                                    isTracking = false
                                }
                            }
                    ) {
                        if (activeHeight > 0.dp) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .width(sliderWidth)
                                    .height(activeHeight)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(activeTrackColor)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 8.dp)
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = activeHeight + (separatorGap / 2))
                                .width(28.dp)
                                .height(separatorThickness)
                                .clip(RoundedCornerShape(999.dp))
                                .background(indicatorColor)
                        )

                        if (inactiveHeight > 0.dp) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .offset(y = activeHeight + separatorGap + separatorThickness)
                                    .width(sliderWidth)
                                    .height(inactiveHeight)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(inactiveTrackColor)
                            )
                        }
                    }
                }
            }

            SmallFloatingActionButton(
                onClick = onDismiss,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close)
                )
            }
        }
    }
}

@Composable
internal fun MainScreenAddFab(
    visible: Boolean,
    fabBottomOffset: Dp,
    fabContainerColor: Color,
    fabIconTint: Color,
    addButtonBehaviorMode: AddButtonBehaviorMode,
    addButtonMenuOrder: List<AddButtonMenuAction>,
    addButtonMenuEnabledActions: List<AddButtonMenuAction>,
    currentTab: BottomNavItem,
    isCompactWidth: Boolean,
    cardWalletSubTab: CardWalletTab,
    onPasswordAddOpen: () -> Unit,
    onTotpAddOpen: () -> Unit,
    onBankCardAddOpen: () -> Unit,
    onWalletAddOpen: () -> Unit,
    onNavigateToWalletAdd: (CardWalletTab) -> Unit,
    onCreateVaultFolder: () -> Unit,
    allowVaultFolderCreation: Boolean,
    passwordPageAggregateEnabled: Boolean,
    passwordNewItemDefaults: NewItemStorageDefaults,
    onPreparePasswordAddStorageDefaults: (Long?, Long?, String?, Long?, String?, Long?, String?) -> Unit,
    onPrepareTotpAddStorageDefaults: (Long?, Long?, String?, Long?, String?, Long?, String?) -> Unit,
    onPrepareNoteAddStorageDefaults: (Long?, Long?, String?, Long?, String?, Long?, String?) -> Unit,
    onPrepareWalletAddStorageDefaults: (Long?, Long?, String?, Long?, String?, Long?, String?) -> Unit,
    onNoteAddOpen: () -> Unit,
    onSendAddOpen: () -> Unit,
    onGeneratorRefresh: () -> Unit,
    onExpandStateChanged: (Boolean) -> Unit,
    onNavigateToAddWifi: (Long?) -> Unit = {},
    onNavigateToAddSshKey: (Long?) -> Unit = {},
    pendingPasswordAuthenticatorQrResult: String? = null,
    onConsumePendingPasswordAuthenticatorQrResult: () -> Unit = {},
    onScanPasswordAuthenticatorQrCode: () -> Unit = {},
    passwordViewModel: PasswordViewModel,
    totpViewModel: TotpViewModel,
    bankCardViewModel: BankCardViewModel,
    localKeePassViewModel: takagi.ru.monica.viewmodel.LocalKeePassViewModel,
    totpNewItemDefaults: NewItemStorageDefaults,
    onNavigateToQuickTotpScan: () -> Unit,
    walletUnifiedAddType: CardWalletTab,
    onWalletUnifiedAddTypeChange: (CardWalletTab) -> Unit,
    documentViewModel: DocumentViewModel,
    walletAddSaveableStateHolder: androidx.compose.runtime.saveable.SaveableStateHolder,
    noteViewModel: NoteViewModel,
    sendState: takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel.SendState,
    bitwardenViewModel: takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
) {
    val effectiveAddButtonBehaviorMode =
        if (currentTab == BottomNavItem.VaultV2) {
            AddButtonBehaviorMode.EXPANDABLE_MENU
        } else {
            addButtonBehaviorMode
        }
    val compactWalletAddType = when (cardWalletSubTab) {
        CardWalletTab.DOCUMENTS -> CardWalletTab.DOCUMENTS
        CardWalletTab.BANK_CARDS -> CardWalletTab.BANK_CARDS
        CardWalletTab.BILLING_ADDRESSES -> CardWalletTab.BILLING_ADDRESSES
        CardWalletTab.ALL -> walletUnifiedAddType
    }
    val shouldApplyPasswordAggregateDefaults =
        currentTab == BottomNavItem.Passwords || currentTab == BottomNavItem.VaultV2
    val aggregateStorageDefaults = if (shouldApplyPasswordAggregateDefaults) {
        passwordNewItemDefaults
    } else {
        null
    }

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(
            initialScale = 0.85f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + fadeIn(),
        exit = scaleOut(targetScale = 0.85f) + fadeOut()
    ) {
        if (currentTab == BottomNavItem.VaultV2 || currentTab == BottomNavItem.Passwords) {
            val menuActions = remember(
                addButtonMenuOrder,
                addButtonMenuEnabledActions,
                onPasswordAddOpen,
                onNoteAddOpen,
                onTotpAddOpen,
                onNavigateToWalletAdd,
                isCompactWidth,
                compactWalletAddType,
                aggregateStorageDefaults,
                onPreparePasswordAddStorageDefaults,
                onPrepareTotpAddStorageDefaults,
                onPrepareNoteAddStorageDefaults,
                onPrepareWalletAddStorageDefaults,
                currentTab,
                onCreateVaultFolder,
                allowVaultFolderCreation,
            ) {
                buildList {
                    addAll(addButtonMenuOrder
                        .filter { addButtonMenuEnabledActions.contains(it) }
                        .map { action ->
                        when (action) {
                            AddButtonMenuAction.PASSWORD -> {
                                VaultV2FabMenuAction(
                                    icon = Icons.Default.Lock,
                                    labelRes = R.string.item_type_password,
                                    onClick = {
                                        if (aggregateStorageDefaults != null) {
                                            onPreparePasswordAddStorageDefaults(
                                                aggregateStorageDefaults.categoryId,
                                                aggregateStorageDefaults.keepassDatabaseId,
                                                aggregateStorageDefaults.keepassGroupPath,
                                                aggregateStorageDefaults.mdbxDatabaseId,
                                                aggregateStorageDefaults.mdbxFolderId,
                                                aggregateStorageDefaults.bitwardenVaultId,
                                                aggregateStorageDefaults.bitwardenFolderId
                                            )
                                        }
                                        onPasswordAddOpen()
                                    }
                                )
                            }

                            AddButtonMenuAction.NOTE -> {
                                VaultV2FabMenuAction(
                                    icon = Icons.Default.Description,
                                    labelRes = R.string.v2_create_note,
                                    onClick = {
                                        if (aggregateStorageDefaults != null) {
                                            onPrepareNoteAddStorageDefaults(
                                                aggregateStorageDefaults.categoryId,
                                                aggregateStorageDefaults.keepassDatabaseId,
                                                aggregateStorageDefaults.keepassGroupPath,
                                                aggregateStorageDefaults.mdbxDatabaseId,
                                                aggregateStorageDefaults.mdbxFolderId,
                                                aggregateStorageDefaults.bitwardenVaultId,
                                                aggregateStorageDefaults.bitwardenFolderId
                                            )
                                        }
                                        onNoteAddOpen()
                                    }
                                )
                            }

                            AddButtonMenuAction.AUTHENTICATOR -> {
                                VaultV2FabMenuAction(
                                    icon = Icons.Default.Security,
                                    labelRes = R.string.item_type_authenticator,
                                    onClick = {
                                        if (aggregateStorageDefaults != null) {
                                            onPrepareTotpAddStorageDefaults(
                                                aggregateStorageDefaults.categoryId,
                                                aggregateStorageDefaults.keepassDatabaseId,
                                                aggregateStorageDefaults.keepassGroupPath,
                                                aggregateStorageDefaults.mdbxDatabaseId,
                                                aggregateStorageDefaults.mdbxFolderId,
                                                aggregateStorageDefaults.bitwardenVaultId,
                                                aggregateStorageDefaults.bitwardenFolderId
                                            )
                                        }
                                        onTotpAddOpen()
                                    }
                                )
                            }

                            AddButtonMenuAction.BANK_CARD -> {
                                VaultV2FabMenuAction(
                                    icon = Icons.Default.CreditCard,
                                    labelRes = R.string.add_button_action_card,
                                    onClick = {
                                        if (aggregateStorageDefaults != null) {
                                            onPrepareWalletAddStorageDefaults(
                                                aggregateStorageDefaults.categoryId,
                                                aggregateStorageDefaults.keepassDatabaseId,
                                                aggregateStorageDefaults.keepassGroupPath,
                                                aggregateStorageDefaults.mdbxDatabaseId,
                                                aggregateStorageDefaults.mdbxFolderId,
                                                aggregateStorageDefaults.bitwardenVaultId,
                                                aggregateStorageDefaults.bitwardenFolderId
                                            )
                                        }
                                        onNavigateToWalletAdd(compactWalletAddType)
                                    }
                                )
                            }
                        }
                    })
                    if (currentTab == BottomNavItem.VaultV2 && allowVaultFolderCreation) {
                        add(
                            VaultV2FabMenuAction(
                                icon = Icons.Default.CreateNewFolder,
                                labelRes = R.string.v2_create_folder,
                                onClick = onCreateVaultFolder,
                            )
                        )
                    }
                }
            }
            if (effectiveAddButtonBehaviorMode == AddButtonBehaviorMode.EXPANDABLE_MENU) {
                VaultV2FabMenu(
                    fabBottomOffset = fabBottomOffset,
                    fabContainerColor = fabContainerColor,
                    fabIconTint = fabIconTint,
                    onExpandStateChanged = onExpandStateChanged,
                    menuActions = menuActions
                )
            } else {
                SwipeableAddFab(
                    fabBottomOffset = fabBottomOffset,
                    fabContainerColor = fabContainerColor,
                    modifier = Modifier,
                    onClick = {
                        if (aggregateStorageDefaults != null) {
                            onPreparePasswordAddStorageDefaults(
                                aggregateStorageDefaults.categoryId,
                                aggregateStorageDefaults.keepassDatabaseId,
                                aggregateStorageDefaults.keepassGroupPath,
                                aggregateStorageDefaults.mdbxDatabaseId,
                                aggregateStorageDefaults.mdbxFolderId,
                                aggregateStorageDefaults.bitwardenVaultId,
                                aggregateStorageDefaults.bitwardenFolderId
                            )
                        }
                        onPasswordAddOpen()
                    },
                    onExpandStateChanged = onExpandStateChanged,
                    fabContent = {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.add),
                            tint = fabIconTint
                        )
                    }
                )
            }
        } else {
            SwipeableAddFab(
                // 通过内部参数控制 FAB 位置，确保容器本身是全屏的
                // NavigationBar 高度约 80dp + 系统导航条高度 + 边距
                fabBottomOffset = fabBottomOffset,
                fabContainerColor = fabContainerColor,
                modifier = Modifier,
                onClick = when (currentTab) {
                    BottomNavItem.VaultV2 -> ({ onPasswordAddOpen() })
                    BottomNavItem.Passwords -> ({ onPasswordAddOpen() })
                    BottomNavItem.Authenticator -> ({ onTotpAddOpen() })
                    BottomNavItem.CardWallet -> ({ onWalletAddOpen() })
                    BottomNavItem.Notes -> ({ onNoteAddOpen() })
                    BottomNavItem.Send -> ({ onSendAddOpen() })
                    BottomNavItem.Generator -> ({ onGeneratorRefresh() })
                    else -> ({})
                },
                onExpandStateChanged = onExpandStateChanged,
                fabContent = {
                    when (currentTab) {
                        BottomNavItem.VaultV2,
                        BottomNavItem.Passwords,
                        BottomNavItem.Authenticator,
                        BottomNavItem.CardWallet,
                        BottomNavItem.Notes,
                        BottomNavItem.Send -> {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.add),
                                tint = fabIconTint
                            )
                        }
                        BottomNavItem.Generator -> {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.regenerate),
                                tint = fabIconTint
                            )
                        }
                        else -> { /* 不显示 */ }
                    }
                }
            )
        }
    }
}


@Composable
internal fun VaultV2FabMenu(
    fabBottomOffset: Dp,
    fabContainerColor: Color,
    fabIconTint: Color,
    onExpandStateChanged: (Boolean) -> Unit,
    menuActions: List<VaultV2FabMenuAction>,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val animatedCornerRadius by animateDpAsState(
        targetValue = if (expanded) 28.dp else 16.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "vault_v2_fab_corner"
    )

    fun updateExpanded(next: Boolean) {
        if (expanded == next) return
        expanded = next
        onExpandStateChanged(next)
    }

    DisposableEffect(Unit) {
        onDispose {
            onExpandStateChanged(false)
        }
    }

    BackHandler(enabled = expanded) {
        updateExpanded(false)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = tween(durationMillis = 120)),
            exit = fadeOut(animationSpec = tween(durationMillis = 90)),
            modifier = Modifier.matchParentSize()
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        updateExpanded(false)
                    }
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 16.dp,
                    bottom = fabBottomOffset + 16.dp
                ),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            menuActions.forEachIndexed { index, action ->
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(
                        animationSpec = tween(durationMillis = 160, delayMillis = index * 28)
                    ) + slideInVertically(
                        initialOffsetY = { it / 3 },
                        animationSpec = tween(
                            durationMillis = 220,
                            delayMillis = index * 28,
                            easing = LinearEasing
                        )
                    ),
                    exit = fadeOut(animationSpec = tween(durationMillis = 90)) + slideOutVertically(
                        targetOffsetY = { it / 4 },
                        animationSpec = tween(durationMillis = 120)
                    )
                ) {
                    Surface(
                        onClick = {
                            updateExpanded(false)
                            action.onClick()
                        },
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        tonalElevation = 4.dp,
                        shadowElevation = 3.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(action.labelRes),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(animatedCornerRadius),
                color = fabContainerColor,
                contentColor = fabIconTint,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                onClick = { updateExpanded(!expanded) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = stringResource(R.string.add),
                        tint = fabIconTint
                    )
                }
            }
        }
    }
}
