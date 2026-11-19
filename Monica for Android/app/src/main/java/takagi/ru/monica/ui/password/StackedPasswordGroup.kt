package takagi.ru.monica.ui.password

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordCardDisplayField
import takagi.ru.monica.ui.haptic.rememberHapticFeedback

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StackedPasswordGroup(
    passwords: List<takagi.ru.monica.data.PasswordEntry>,
    isExpanded: Boolean,
    stackCardMode: StackCardMode,
    onToggleExpand: () -> Unit,
    onPasswordClick: (takagi.ru.monica.data.PasswordEntry) -> Unit,
    onSwipeLeft: (takagi.ru.monica.data.PasswordEntry) -> Unit,
    onSwipeRight: (takagi.ru.monica.data.PasswordEntry) -> Unit,
    onGroupSwipeRight: (List<takagi.ru.monica.data.PasswordEntry>) -> Unit,
    onToggleFavorite: (takagi.ru.monica.data.PasswordEntry) -> Unit,
    onToggleGroupCover: (takagi.ru.monica.data.PasswordEntry) -> Unit,
    isSelectionMode: Boolean,
    selectedPasswords: Set<Long>,
    swipedItemId: Long? = null,
    onToggleSelection: (Long) -> Unit,
    onOpenMultiPasswordDialog: (List<takagi.ru.monica.data.PasswordEntry>) -> Unit,
    onLongClick: (takagi.ru.monica.data.PasswordEntry) -> Unit, // 新增：长按进入多选模式
    iconCardsEnabled: Boolean = false,
    unmatchedIconHandlingStrategy: takagi.ru.monica.data.UnmatchedIconHandlingStrategy = takagi.ru.monica.data.UnmatchedIconHandlingStrategy.DEFAULT_ICON,
    passwordCardDisplayMode: takagi.ru.monica.data.PasswordCardDisplayMode = takagi.ru.monica.data.PasswordCardDisplayMode.SHOW_ALL,
    passwordCardDisplayFields: List<PasswordCardDisplayField> = PasswordCardDisplayField.DEFAULT_ORDER,
    showAuthenticator: Boolean = false,
    hideOtherContentWhenAuthenticator: Boolean = false,
    totpTimeOffsetSeconds: Int = 0,
    smoothAuthenticatorProgress: Boolean = true,
    decryptAuthenticatorKey: ((String) -> String)? = null,
    enableSharedBounds: Boolean = true,
    displayTitle: String? = null
) {
    val leadPassword = passwords.firstOrNull() ?: return
    val collapsedTitle = displayTitle?.takeIf { it.isNotBlank() } ?: leadPassword.title
    val summary = remember(passwords) { summarizePasswordStack(passwords) }
    val isMergedPasswordCard = summary.isMergedPasswordCard
    
    // Expand stacks between projects; each project's password rows still form one card.
    if (stackCardMode == StackCardMode.ALWAYS_EXPANDED && passwords.size > 1 && !isMergedPasswordCard) {
        val projects = remember(passwords) { passwords.groupBy(::getPasswordInfoKey) }
        projects.forEach { (projectKey, projectPasswords) ->
            key(projectKey) {
                // AUTO terminates this expansion: this child is one project or one singleton.
                StackedPasswordGroup(
                    passwords = projectPasswords,
                    isExpanded = false,
                    stackCardMode = StackCardMode.AUTO,
                    onToggleExpand = onToggleExpand,
                    onPasswordClick = onPasswordClick,
                    onSwipeLeft = onSwipeLeft,
                    onSwipeRight = onSwipeRight,
                    onGroupSwipeRight = onGroupSwipeRight,
                    onToggleFavorite = onToggleFavorite,
                    onToggleGroupCover = onToggleGroupCover,
                    isSelectionMode = isSelectionMode,
                    selectedPasswords = selectedPasswords,
                    swipedItemId = swipedItemId,
                    onToggleSelection = onToggleSelection,
                    onOpenMultiPasswordDialog = onOpenMultiPasswordDialog,
                    onLongClick = onLongClick,
                    unmatchedIconHandlingStrategy = unmatchedIconHandlingStrategy,
                    passwordCardDisplayMode = passwordCardDisplayMode,
                    passwordCardDisplayFields = passwordCardDisplayFields,
                    showAuthenticator = showAuthenticator,
                    hideOtherContentWhenAuthenticator = hideOtherContentWhenAuthenticator,
                    totpTimeOffsetSeconds = totpTimeOffsetSeconds,
                    smoothAuthenticatorProgress = smoothAuthenticatorProgress,
                    decryptAuthenticatorKey = decryptAuthenticatorKey,
                    iconCardsEnabled = iconCardsEnabled,
                    enableSharedBounds = enableSharedBounds
                )
            }
        }
        return
    }

    // 单个密码直接显示，不堆叠 (且不是合并卡片)
    if (passwords.size == 1 && !isMergedPasswordCard) {
        val password = leadPassword
        PasswordListSingleCardItem(
            entry = password,
            onClick = {
                if (isSelectionMode) {
                    onToggleSelection(password.id)
                } else {
                    onPasswordClick(password)
                }
            },
            onLongClick = { onLongClick(password) },
            onSwipeLeft = { onSwipeLeft(password) },
            onSwipeRight = { onSwipeRight(password) },
            isSwiped = password.id == swipedItemId,
            isSelectionMode = isSelectionMode,
            isSelected = selectedPasswords.contains(password.id),
            onToggleFavorite = { onToggleFavorite(password) },
            unmatchedIconHandlingStrategy = unmatchedIconHandlingStrategy,
            passwordCardDisplayMode = passwordCardDisplayMode,
            passwordCardDisplayFields = passwordCardDisplayFields,
            showAuthenticator = showAuthenticator,
            hideOtherContentWhenAuthenticator = hideOtherContentWhenAuthenticator,
            totpTimeOffsetSeconds = totpTimeOffsetSeconds,
            smoothAuthenticatorProgress = smoothAuthenticatorProgress,
            decryptAuthenticatorKey = decryptAuthenticatorKey,
            iconCardsEnabled = iconCardsEnabled,
            enableSharedBounds = enableSharedBounds
        )
        return
    }

    // 多密码合并卡片：普通模式显示为普通单卡代理，点击后仍进入多密码详情；多选模式保留多密码卡。
    if (isMergedPasswordCard) {
        if (isSelectionMode) {
            takagi.ru.monica.ui.gestures.SwipeActions(
                onSwipeLeft = { onSwipeLeft(leadPassword) },
                onSwipeRight = { onGroupSwipeRight(passwords) },
                isSwiped = leadPassword.id == swipedItemId,
                enabled = true
            ) {
                MultiPasswordEntryCard(
                    passwords = passwords,
                    onClick = { password ->
                        if (isSelectionMode) {
                            onToggleSelection(password.id)
                        } else {
                            onPasswordClick(password)
                        }
                    },
                    onCardClick = if (!isSelectionMode) {
                        { onOpenMultiPasswordDialog(passwords) }
                    } else null,
                    onLongClick = { onLongClick(leadPassword) },
                    onToggleFavorite = { password -> onToggleFavorite(password) },
                    onToggleGroupCover = null,
                    isSelectionMode = isSelectionMode,
                    selectedPasswords = selectedPasswords,
                    canSetGroupCover = false,
                    hasGroupCover = false,
                    isInExpandedGroup = false,
                    iconCardsEnabled = iconCardsEnabled,
                    unmatchedIconHandlingStrategy = unmatchedIconHandlingStrategy,
                    passwordCardDisplayMode = passwordCardDisplayMode,
                    passwordCardDisplayFields = passwordCardDisplayFields,
                    showAuthenticator = showAuthenticator,
                    hideOtherContentWhenAuthenticator = hideOtherContentWhenAuthenticator,
                    totpTimeOffsetSeconds = totpTimeOffsetSeconds,
                    smoothAuthenticatorProgress = smoothAuthenticatorProgress,
                    decryptAuthenticatorKey = decryptAuthenticatorKey
                )
            }
        } else {
            val displayEntry = leadPassword.copy(
                isFavorite = passwords.all { it.isFavorite }
            )
            takagi.ru.monica.ui.gestures.SwipeActions(
                onSwipeLeft = { onSwipeLeft(leadPassword) },
                onSwipeRight = { onGroupSwipeRight(passwords) },
                isSwiped = leadPassword.id == swipedItemId,
                enabled = true
            ) {
                PasswordEntryCard(
                    entry = displayEntry,
                    onClick = { onOpenMultiPasswordDialog(passwords) },
                    onLongClick = { onLongClick(leadPassword) },
                    onToggleFavorite = { passwords.forEach(onToggleFavorite) },
                    onToggleGroupCover = null,
                    isSelectionMode = false,
                    isSelected = false,
                    canSetGroupCover = false,
                    isInExpandedGroup = false,
                    isSingleCard = true,
                    iconCardsEnabled = iconCardsEnabled,
                    unmatchedIconHandlingStrategy = unmatchedIconHandlingStrategy,
                    passwordCardDisplayMode = passwordCardDisplayMode,
                    passwordCardDisplayFields = passwordCardDisplayFields,
                    showAuthenticator = showAuthenticator,
                    hideOtherContentWhenAuthenticator = hideOtherContentWhenAuthenticator,
                    totpTimeOffsetSeconds = totpTimeOffsetSeconds,
                    smoothAuthenticatorProgress = smoothAuthenticatorProgress,
                    decryptAuthenticatorKey = decryptAuthenticatorKey,
                    enableSharedBounds = enableSharedBounds
                )
            }
        }
        return
    }
    
    // 否则使用原有的堆叠逻辑
    val isGroupFavorited = summary.isGroupFavorited
    val hasGroupCover = summary.hasGroupCover
    
    // ALWAYS_EXPANDED has returned above, so the stack transition only follows
    // the actual expanded state. One transition clock keeps all properties in sync.
    val effectiveExpanded = isExpanded
    val stackTransition = updateTransition(
        targetState = effectiveExpanded,
        label = "password_stack_transition"
    )
    val stackAlpha = stackTransition.animateFloat(
        transitionSpec = { tween(200) },
        label = "stack_alpha"
    ) { expanded ->
        if (expanded) 0f else 1f
    }

    // 🎯 下滑手势状态
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    val haptic = rememberHapticFeedback()
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        // Background layers remain structurally stable during the transition.
        // ModulateAlpha avoids an offscreen buffer for every visible stack.
        val stackCount = passwords.size.coerceAtMost(3)
        for (i in (stackCount - 1) downTo 1) {
            val offsetDp = (i * 4).dp
            val scaleFactor = 1f - (i * 0.02f)
            val baseLayerAlpha = 0.7f - (i * 0.2f)

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = offsetDp)
                    .graphicsLayer {
                        val currentStackAlpha = stackAlpha.value
                        scaleX = scaleFactor
                        scaleY = scaleFactor
                        alpha = baseLayerAlpha * currentStackAlpha
                        translationY = (i * 4).dp.toPx() * (1f - currentStackAlpha)
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
                elevation = CardDefaults.cardElevation(defaultElevation = (i * 1.5).dp),
                colors = CardDefaults.cardColors(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Box(modifier = Modifier.height(76.dp))
            }
        }

        // 🎯 主卡片 (持续存在，内容和属性变化)
        val isSelected = selectedPasswords.contains(leadPassword.id)
        val cardColors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }

        takagi.ru.monica.ui.gestures.SwipeActions(
            onSwipeLeft = {
                if (!effectiveExpanded) onSwipeLeft(leadPassword)
            },
            onSwipeRight = {
                if (!effectiveExpanded) onGroupSwipeRight(passwords)
            },
            isSwiped = leadPassword.id == swipedItemId,
            enabled = !effectiveExpanded
        ) {
            AnimatedStackCard(
                transition = stackTransition,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        // 展开时的下滑手势
                        if (effectiveExpanded && passwords.size > 1) {
                            Modifier.pointerInput(Unit) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { haptic.performLongPress() },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        if (dragAmount.y > 0) {
                                            swipeOffset = (swipeOffset + dragAmount.y).coerceAtMost(150f)
                                        }
                                    },
                                    onDragEnd = {
                                        if (swipeOffset > 80f) {
                                            haptic.performSuccess()
                                            onToggleExpand()
                                        }
                                        swipeOffset = 0f
                                    },
                                    onDragCancel = { swipeOffset = 0f }
                                )
                            }
                        } else Modifier
                    )
                    .graphicsLayer {
                        // 下滑时的位移效果
                        if (effectiveExpanded) {
                            translationY = swipeOffset * 0.5f
                        }
                    },
                colors = cardColors
            ) {
                // 内容切换：收起态(Header) vs 展开态(Column)
                AnimatedContent(
                        targetState = effectiveExpanded,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(180)) togetherWith
                                fadeOut(animationSpec = tween(140))).using(
                                SizeTransform(clip = false) { initialSize, targetSize ->
                                    tween(
                                        durationMillis = if (targetSize.height < initialSize.height) 120 else 220,
                                        easing = FastOutSlowInEasing
                                    )
                                }
                            )
                        },
                        label = "content_switch"
                    ) { expanded ->
                        if (!expanded) {
                            // --- 收起状态的内容 ---
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleExpand() }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            // 🏷️ 数量徽章
                                            Surface(
                                                shape = RoundedCornerShape(18.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                shadowElevation = 2.dp
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        Icons.Default.Layers,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp),
                                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                                    )
                                                    Text(
                                                        text = "${passwords.size}",
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                                    )
                                                }
                                            }
                                            
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = collapsedTitle,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (leadPassword.website.isNotBlank()) {
                                                    Text(
                                                        text = leadPassword.website,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                        
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isSelectionMode) {
                                                androidx.compose.material3.Checkbox(
                                                    checked = isSelected,
                                                    onCheckedChange = { onGroupSwipeRight(passwords) }
                                                )
                                            } else {
                                                if (isGroupFavorited) {
                                                    Icon(
                                                        Icons.Default.Favorite,
                                                        contentDescription = stringResource(R.string.favorite),
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                                Icon(
                                                    Icons.Default.ExpandMore,
                                                    contentDescription = stringResource(R.string.expand),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // --- 展开状态的内容 ---
                            val edgeInteractionSource = remember { MutableInteractionSource() }
                            val edgeContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
                            val edgeBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                            val edgeHitWidth = 14.dp
                            val edgeHitHeight = 12.dp
                            val edgeTapModifier = Modifier.clickable(
                                interactionSource = edgeInteractionSource,
                                indication = null,
                                onClick = onToggleExpand
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // 📌 1. 顶部标题栏
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 左侧：密码数量标签
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Layers,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = stringResource(R.string.passwords_count, passwords.size),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    
                                    // 右侧：收起按钮
                                    FilledTonalIconButton(
                                        onClick = onToggleExpand,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ExpandLess,
                                            contentDescription = stringResource(R.string.collapse),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                
                                // 分隔线
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                                
                                // 📦 2. 密码列表内容
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(edgeContainerColor)
                                        .border(
                                            width = 1.dp,
                                            color = edgeBorderColor,
                                            shape = RoundedCornerShape(16.dp)
                                        )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = edgeHitWidth, vertical = edgeHitHeight),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        val groupedByInfo = remember(passwords) {
                                            passwords
                                                .groupBy { getPasswordInfoKey(it) }
                                                .values
                                                .mapNotNull { group ->
                                                    group
                                                        .sortedBy { it.sortOrder }
                                                        .takeIf { it.isNotEmpty() }
                                                }
                                        }
                                        
                                        groupedByInfo.forEach { passwordGroup ->
                                            val groupLeadPassword = passwordGroup.firstOrNull()
                                                ?: return@forEach
                                            key("stack_group_${groupLeadPassword.id}_${passwordGroup.size}") {
                                                takagi.ru.monica.ui.gestures.SwipeActions(
                                                    onSwipeLeft = { onSwipeLeft(groupLeadPassword) },
                                                    onSwipeRight = { onSwipeRight(groupLeadPassword) },
                                                    enabled = true
                                                ) {
                                                    if (passwordGroup.size == 1) {
                                                        val password = groupLeadPassword
                                                        PasswordEntryCard(
                                                            entry = password,
                                                            onClick = {
                                                                if (isSelectionMode) {
                                                                    onToggleSelection(password.id)
                                                                } else {
                                                                    onPasswordClick(password)
                                                                }
                                                            },
                                                            onLongClick = { onLongClick(password) },
                                                            onToggleFavorite = { onToggleFavorite(password) },
                                                            onToggleGroupCover = if (passwords.size > 1) {
                                                                { onToggleGroupCover(password) }
                                                            } else null,
                                                            isSelectionMode = isSelectionMode,
                                                            isSelected = selectedPasswords.contains(password.id),
                                                            canSetGroupCover = passwords.size > 1,
                                                            isInExpandedGroup = true, // We are inside the expanded container
                                                            isSingleCard = false,
                                                            iconCardsEnabled = iconCardsEnabled,
                                                            unmatchedIconHandlingStrategy = unmatchedIconHandlingStrategy,
                                                            passwordCardDisplayMode = passwordCardDisplayMode,
                                                            passwordCardDisplayFields = passwordCardDisplayFields,
                                                            showAuthenticator = showAuthenticator,
                                                            hideOtherContentWhenAuthenticator = hideOtherContentWhenAuthenticator,
                                                            totpTimeOffsetSeconds = totpTimeOffsetSeconds,
                                                            smoothAuthenticatorProgress = smoothAuthenticatorProgress,
                                                            decryptAuthenticatorKey = decryptAuthenticatorKey,
                                                            enableSharedBounds = enableSharedBounds
                                                        )
                                                    } else if (isSelectionMode) {
                                                        MultiPasswordEntryCard(
                                                            passwords = passwordGroup,
                                                            onClick = { password ->
                                                                if (isSelectionMode) {
                                                                    onToggleSelection(password.id)
                                                                } else {
                                                                    onPasswordClick(password)
                                                                }
                                                            },
                                                            onCardClick = if (!isSelectionMode) {
                                                                { onOpenMultiPasswordDialog(passwordGroup) }
                                                            } else null,
                                                            onLongClick = { onLongClick(groupLeadPassword) },
                                                            onToggleFavorite = { password -> onToggleFavorite(password) },
                                                            onToggleGroupCover = if (passwords.size > 1) {
                                                                { password -> onToggleGroupCover(password) }
                                                            } else null,
                                                            isSelectionMode = isSelectionMode,
                                                            selectedPasswords = selectedPasswords,
                                                            canSetGroupCover = passwords.size > 1,
                                                            hasGroupCover = hasGroupCover,
                                                            isInExpandedGroup = true, // We are inside the expanded container
                                                            iconCardsEnabled = iconCardsEnabled,
                                                            unmatchedIconHandlingStrategy = unmatchedIconHandlingStrategy,
                                                            passwordCardDisplayMode = passwordCardDisplayMode,
                                                            passwordCardDisplayFields = passwordCardDisplayFields,
                                                            showAuthenticator = showAuthenticator,
                                                            hideOtherContentWhenAuthenticator = hideOtherContentWhenAuthenticator,
                                                            totpTimeOffsetSeconds = totpTimeOffsetSeconds,
                                                            smoothAuthenticatorProgress = smoothAuthenticatorProgress,
                                                            decryptAuthenticatorKey = decryptAuthenticatorKey
                                                        )
                                                    } else {
                                                        val displayEntry = groupLeadPassword.copy(
                                                            isFavorite = passwordGroup.all { it.isFavorite },
                                                            isGroupCover = passwordGroup.any { it.isGroupCover }
                                                        )
                                                        PasswordEntryCard(
                                                            entry = displayEntry,
                                                            onClick = { onOpenMultiPasswordDialog(passwordGroup) },
                                                            onLongClick = { onLongClick(groupLeadPassword) },
                                                            onToggleFavorite = { passwordGroup.forEach(onToggleFavorite) },
                                                            onToggleGroupCover = if (passwords.size > 1) {
                                                                {
                                                                    val coverTarget = passwordGroup.firstOrNull { it.isGroupCover }
                                                                        ?: groupLeadPassword
                                                                    onToggleGroupCover(coverTarget)
                                                                }
                                                            } else null,
                                                            isSelectionMode = false,
                                                            isSelected = false,
                                                            canSetGroupCover = passwords.size > 1,
                                                            isInExpandedGroup = true, // We are inside the expanded container
                                                            isSingleCard = false,
                                                            iconCardsEnabled = iconCardsEnabled,
                                                            unmatchedIconHandlingStrategy = unmatchedIconHandlingStrategy,
                                                            passwordCardDisplayMode = passwordCardDisplayMode,
                                                            passwordCardDisplayFields = passwordCardDisplayFields,
                                                            showAuthenticator = showAuthenticator,
                                                            hideOtherContentWhenAuthenticator = hideOtherContentWhenAuthenticator,
                                                            totpTimeOffsetSeconds = totpTimeOffsetSeconds,
                                                            smoothAuthenticatorProgress = smoothAuthenticatorProgress,
                                                            decryptAuthenticatorKey = decryptAuthenticatorKey,
                                                            enableSharedBounds = enableSharedBounds
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    
                                    // Expanded state edge zones: only these non-card areas collapse the stack.
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .fillMaxWidth()
                                            .height(edgeHitHeight)
                                            .then(edgeTapModifier)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .height(edgeHitHeight)
                                            .then(edgeTapModifier)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .width(edgeHitWidth)
                                            .fillMaxHeight()
                                            .then(edgeTapModifier)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .width(edgeHitWidth)
                                            .fillMaxHeight()
                                            .then(edgeTapModifier)
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }
}

internal data class PasswordStackSummary(
    val isMergedPasswordCard: Boolean,
    val isGroupFavorited: Boolean,
    val hasGroupCover: Boolean
)

@Composable
private fun AnimatedStackCard(
    transition: Transition<Boolean>,
    modifier: Modifier,
    colors: CardColors,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardElevation by transition.animateDp(
        transitionSpec = { tween(200) },
        label = "elevation"
    ) { expanded ->
        if (expanded) 4.dp else 6.dp
    }
    val cardShape by transition.animateDp(
        transitionSpec = { tween(200) },
        label = "shape"
    ) { expanded ->
        if (expanded) 16.dp else 14.dp
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(cardShape),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation),
        colors = colors,
        content = content
    )
}

internal fun summarizePasswordStack(
    passwords: List<takagi.ru.monica.data.PasswordEntry>
): PasswordStackSummary {
    val leadPassword = passwords.firstOrNull()
        ?: return PasswordStackSummary(
            isMergedPasswordCard = false,
            isGroupFavorited = false,
            hasGroupCover = false
        )
    val leadInfoKey = if (passwords.size > 1) getPasswordInfoKey(leadPassword) else null
    var isMergedPasswordCard = passwords.size > 1
    var isGroupFavorited = true
    var hasGroupCover = false

    passwords.forEachIndexed { index, password ->
        isGroupFavorited = isGroupFavorited && password.isFavorite
        hasGroupCover = hasGroupCover || password.isGroupCover
        if (
            index > 0 &&
            isMergedPasswordCard &&
            getPasswordInfoKey(password) != leadInfoKey
        ) {
            isMergedPasswordCard = false
        }
    }

    return PasswordStackSummary(
        isMergedPasswordCard = isMergedPasswordCard,
        isGroupFavorited = isGroupFavorited,
        hasGroupCover = hasGroupCover
    )
}
