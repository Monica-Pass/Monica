package takagi.ru.monica.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.ProgressBarStyle
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.UnifiedProgressBarMode
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.util.TotpGenerator
import takagi.ru.monica.util.TotpDataResolver
import kotlin.math.PI
import kotlin.math.sin
import takagi.ru.monica.bitwarden.sync.SyncStatus
import takagi.ru.monica.ui.icons.UnmatchedIconFallback
import takagi.ru.monica.ui.icons.shouldShowFallbackSlot
import takagi.ru.monica.ui.nextSmoothTotpProgressTarget
import takagi.ru.monica.ui.rememberTotpTickerMillis
import takagi.ru.monica.ui.shouldResetSmoothTotpProgress

/**
 * TOTP验证码卡片
 * 显示实时生成的6位验证码和倒计时
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TotpCodeCard(
    item: SecureItem,
    onCopyCode: (String) -> Unit,
    onToggleSelect: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    onToggleFavorite: ((Long, Boolean) -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onGenerateNext: ((Long) -> Unit)? = null,
    onShowQrCode: ((SecureItem) -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onCardClick: (() -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    compactTile: Boolean = false,
    boundPasswordSummary: String? = null,
    sharedTickSeconds: Long? = null,
    sharedProgressTimeMillis: Long? = null,
    appSettings: AppSettings? = null,
    parsedTotpData: TotpData? = null,
    decryptStoredValue: ((String) -> String)? = null,
    backgroundContent: (@Composable BoxScope.() -> Unit)? = null,
    immersiveBackgroundVisible: Boolean = backgroundContent != null
) {
    val context = LocalContext.current
    
    // 使用传入的设置或默认值，避免创建多个 SettingsManager 实例
    val settings = appSettings ?: AppSettings()
    
    // 解析TOTP数据
    val resolvedTotpData = remember(item.itemData, item.title, parsedTotpData, decryptStoredValue) {
        parsedTotpData ?: TotpDataResolver.parseStoredItemData(
            itemData = item.itemData,
            fallbackIssuer = item.title,
            decryptIfNeeded = decryptStoredValue
        ) ?: TotpData(secret = "")
    }
    val totpData = remember(resolvedTotpData) { normalizeTotpData(resolvedTotpData) }
    
    // 列表页面提供共享秒级时间源时，卡片不再订阅 50ms 备用 ticker。
    // 平滑视觉由进度条内部动画完成，验证码与卡片主体仅按秒更新。
    val fallbackProgressTimeMillis = if (
        sharedProgressTimeMillis == null && sharedTickSeconds == null
    ) {
        rememberTotpTickerMillis(settings.validatorSmoothProgress)
    } else {
        null
    }
    val progressTimeMillis = sharedProgressTimeMillis
        ?: fallbackProgressTimeMillis
        ?: ((sharedTickSeconds ?: System.currentTimeMillis() / 1000L) * 1000L)
    val progressSeconds = progressTimeMillis / 1000L
    val generationSeconds = sharedTickSeconds ?: progressSeconds
    val effectiveProgressTimeMillis = if (settings.validatorSmoothProgress) {
        progressTimeMillis
    } else {
        generationSeconds * 1000L
    }

    // 同一个验证码周期内复用 HMAC 结果，倒计时仍按秒更新。
    val generationWindow = remember(
        generationSeconds,
        totpData.otpType,
        totpData.counter,
        totpData.period,
        settings.totpTimeOffset,
    ) {
        if (totpData.otpType == OtpType.HOTP) {
            totpData.counter
        } else {
            val periodSeconds = totpData.period.coerceAtLeast(1).toLong()
            (generationSeconds + settings.totpTimeOffset.toLong()) / periodSeconds
        }
    }

    // 根据当前验证码周期计算验证码/倒计时/进度
    val currentCode = remember(generationWindow, totpData, settings.totpTimeOffset) {
        when (totpData.otpType) {
            OtpType.HOTP -> TotpGenerator.generateOtp(totpData)
            else -> TotpGenerator.generateOtp(
                totpData = totpData,
                timeOffset = settings.totpTimeOffset,
                currentSeconds = generationSeconds
            )
        }
    }
    
    // 下一个验证码（用于倒计时结束前5秒内复制）
    val nextCode = remember(generationWindow, totpData, settings.totpTimeOffset) {
        when (totpData.otpType) {
            OtpType.HOTP -> currentCode // HOTP 不支持下一个
            else -> TotpGenerator.generateOtp(
                totpData = totpData,
                timeOffset = settings.totpTimeOffset,
                currentSeconds = generationSeconds + totpData.period
            )
        }
    }

    val remainingSeconds = remember(generationSeconds, totpData, settings.totpTimeOffset) {
        if (totpData.otpType == OtpType.HOTP) {
            0
        } else {
            TotpGenerator.getRemainingSeconds(
                period = totpData.period,
                timeOffset = settings.totpTimeOffset,
                currentSeconds = generationSeconds
            )
        }
    }

    val progress = remember(
        generationSeconds,
        effectiveProgressTimeMillis,
        totpData,
        settings.totpTimeOffset,
        settings.validatorSmoothProgress
    ) {
        if (totpData.otpType == OtpType.HOTP) {
            0f
        } else {
            if (settings.validatorSmoothProgress) {
                val periodMillis = (totpData.period * 1000L).coerceAtLeast(1000L)
                val correctedMillis = effectiveProgressTimeMillis + (settings.totpTimeOffset * 1000L)
                val elapsedInPeriod = ((correctedMillis % periodMillis) + periodMillis) % periodMillis
                (elapsedInPeriod.toFloat() / periodMillis.toFloat()).coerceIn(0f, 1f)
            } else {
                TotpGenerator.getProgress(
                    period = totpData.period,
                    timeOffset = settings.totpTimeOffset,
                    currentSeconds = generationSeconds
                ).coerceIn(0f, 1f)
            }
        }
    }

    // 判断是否复制下一个验证码
    val codeToCopy = remember(currentCode, nextCode, remainingSeconds, settings.copyNextCodeWhenExpiring, totpData.otpType) {
        if (settings.isPlusActivated &&
            settings.copyNextCodeWhenExpiring &&
            totpData.otpType != OtpType.HOTP && 
            remainingSeconds in 1..5) {
            nextCode
        } else {
            currentCode
        }
    }
    val hideCodeByDefault = settings.authenticatorCardHideCodeByDefault
    var isCodeRevealed by remember(item.id, hideCodeByDefault) {
        mutableStateOf(!hideCodeByDefault)
    }
    val showOtpCode = !hideCodeByDefault || isCodeRevealed

    val iconWebsite = remember(totpData.link) { totpData.link.trim() }
    val iconTitle = remember(item.title, totpData.issuer) { totpData.issuer.ifBlank { item.title } }
    val associatedAppPackage = remember(totpData.associatedApp) { totpData.associatedApp.trim() }
    val selectedSimpleIconBitmap = takagi.ru.monica.ui.icons.rememberSimpleIconBitmap(
        slug = if (totpData.customIconType == takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_SIMPLE) {
            totpData.customIconValue
        } else {
            null
        },
        tintColor = MaterialTheme.colorScheme.primary,
        enabled = settings.iconCardsEnabled
    )
    val selectedUploadedIconBitmap = takagi.ru.monica.ui.icons.rememberUploadedPasswordIcon(
        value = if (totpData.customIconType == takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_UPLOADED) {
            totpData.customIconValue
        } else {
            null
        }
    )
    val autoMatchedSimpleIcon = takagi.ru.monica.ui.icons.rememberAutoMatchedSimpleIcon(
        website = iconWebsite,
        title = iconTitle,
        appPackageName = associatedAppPackage.ifBlank { null },
        tintColor = MaterialTheme.colorScheme.primary,
        enabled = settings.iconCardsEnabled &&
            totpData.customIconType == takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_NONE
    )
    val favicon = if (iconWebsite.isNotBlank()) {
        takagi.ru.monica.autofill_ng.ui.rememberFavicon(
            url = iconWebsite,
            enabled = settings.iconCardsEnabled && autoMatchedSimpleIcon.resolved && autoMatchedSimpleIcon.slug == null
        )
    } else {
        null
    }
    val appIcon = if (
        settings.iconCardsEnabled &&
        associatedAppPackage.isNotBlank() &&
        !takagi.ru.monica.autofill_ng.ui.isWebAddress(iconWebsite)
    ) {
        takagi.ru.monica.autofill_ng.ui.rememberAppIcon(packageName = associatedAppPackage)
    } else {
        null
    }
    val infoLines = remember(
        totpData.issuer,
        totpData.accountName,
        settings.authenticatorCardDisplayFields
    ) {
        resolveAuthenticatorInfoLines(
            issuer = totpData.issuer,
            accountName = totpData.accountName,
            fields = settings.authenticatorCardDisplayFields
        )
    }
    
    val cardInteractionModifier = if (isSelectionMode) {
        // In selection mode, keep long-press free for list-level drag reorder.
        modifier
            .fillMaxWidth()
            .clickable { onToggleSelect?.invoke() }
    } else {
        modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (onCardClick != null) {
                        onCardClick()
                    } else if (hideCodeByDefault) {
                        isCodeRevealed = !isCodeRevealed
                    } else {
                        onCopyCode(codeToCopy)
                    }
                },
                onLongClick = {
                    when {
                        onCardClick != null && onLongClick != null -> onLongClick()
                        hideCodeByDefault -> onCopyCode(codeToCopy)
                        else -> onLongClick?.invoke()
                    }
                }
            )
    }

    val hasImmersiveBackground = backgroundContent != null && immersiveBackgroundVisible
    val immersivePrimaryColor = Color.White
    val immersiveSecondaryColor = Color.White.copy(alpha = 0.82f)
    val immersiveAccentColor = Color.White.copy(alpha = 0.92f)
    val immersiveTertiaryColor = Color.White.copy(alpha = 0.96f)
    val immersiveErrorColor = Color.White

    if (compactTile) {
        MonicaItemCard(
            modifier = cardInteractionModifier.heightIn(min = 142.dp),
            isSelected = isSelected
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (settings.iconCardsEnabled) {
                        when {
                            selectedSimpleIconBitmap != null -> Image(
                                bitmap = selectedSimpleIconBitmap,
                                contentDescription = null,
                                modifier = Modifier.size(30.dp),
                                contentScale = ContentScale.Fit
                            )
                            selectedUploadedIconBitmap != null -> Image(
                                bitmap = selectedUploadedIconBitmap,
                                contentDescription = null,
                                modifier = Modifier.size(30.dp),
                                contentScale = ContentScale.Fit
                            )
                            autoMatchedSimpleIcon.bitmap != null -> Image(
                                bitmap = autoMatchedSimpleIcon.bitmap,
                                contentDescription = null,
                                modifier = Modifier.size(30.dp),
                                contentScale = ContentScale.Fit
                            )
                            favicon != null -> Image(
                                bitmap = favicon,
                                contentDescription = null,
                                modifier = Modifier.size(30.dp).clip(CircleShape)
                            )
                            appIcon != null -> Image(
                                bitmap = appIcon,
                                contentDescription = null,
                                modifier = Modifier.size(30.dp).clip(CircleShape)
                            )
                            shouldShowFallbackSlot(settings.unmatchedIconHandlingStrategy) ->
                                UnmatchedIconFallback(
                                    strategy = settings.unmatchedIconHandlingStrategy,
                                    primaryText = iconWebsite,
                                    secondaryText = iconTitle,
                                    defaultIcon = Icons.Default.Security,
                                    iconSize = 30.dp
                                )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        infoLines.firstOrNull()?.let { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (isSelectionMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelect?.invoke() },
                            modifier = Modifier.size(30.dp)
                        )
                    } else {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = { expanded = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_options),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                onToggleFavorite?.let { toggleFavorite ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(if (item.isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites)) },
                                        onClick = {
                                            expanded = false
                                            toggleFavorite(item.id, !item.isFavorite)
                                        },
                                        leadingIcon = { Icon(Icons.Default.Favorite, contentDescription = null) }
                                    )
                                }
                                onEdit?.let { edit ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.edit)) },
                                        onClick = { expanded = false; edit() },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                                    )
                                }
                                onShowQrCode?.let { showQr ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.show_qr_code)) },
                                        onClick = { expanded = false; showQr(item) },
                                        leadingIcon = { Icon(Icons.Default.QrCode, contentDescription = null) }
                                    )
                                }
                                onDelete?.let { delete ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.delete)) },
                                        onClick = { expanded = false; delete() },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                val shouldBlink = remainingSeconds in 1..5 && totpData.otpType != OtpType.HOTP
                val expiryAlpha by if (shouldBlink) {
                    rememberInfiniteTransition(label = "totp_tile_expiry_blink").animateFloat(
                        initialValue = 1f,
                        targetValue = 0.5f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 500, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "totp_tile_expiry_alpha"
                    )
                } else {
                    remember { mutableFloatStateOf(1f) }
                }

                Text(
                    text = if (showOtpCode) formatOtpCode(currentCode, totpData.otpType)
                    else formatMaskedOtpCode(currentCode, totpData),
                    modifier = Modifier.graphicsLayer { alpha = expiryAlpha },
                    color = if (remainingSeconds in 1..5) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (totpData.otpType == OtpType.STEAM) 24.sp else 28.sp,
                    maxLines = 1
                )

                if (totpData.otpType == OtpType.HOTP) {
                    TextButton(
                        onClick = { onGenerateNext?.invoke(item.id) },
                        enabled = onGenerateNext != null,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(stringResource(R.string.generate_next))
                    }
                } else {
                    Text(
                        text = formatOtpCode(nextCode, totpData.otpType),
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
        return
    }

    MonicaItemCard(
        modifier = cardInteractionModifier,
        isSelected = isSelected,
        transparentContainer = hasImmersiveBackground
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (backgroundContent != null) {
                backgroundContent()
                if (hasImmersiveBackground) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.34f),
                                        Color.Black.copy(alpha = 0.74f)
                                    )
                                )
                            )
                    )
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
                        )
                    }
                }
            }
            CompositionLocalProvider(
                LocalContentColor provides if (hasImmersiveBackground) {
                    immersivePrimaryColor
                } else {
                    LocalContentColor.current
                }
            ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
            // 标题和菜单
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (leadingContent != null) {
                        leadingContent()
                        Spacer(modifier = Modifier.width(12.dp))
                    } else if (settings.iconCardsEnabled) {
                        when {
                            selectedSimpleIconBitmap != null -> {
                                Image(
                                    bitmap = selectedSimpleIconBitmap,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            selectedUploadedIconBitmap != null -> {
                                Image(
                                    bitmap = selectedUploadedIconBitmap,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            autoMatchedSimpleIcon.bitmap != null -> {
                                Image(
                                    bitmap = autoMatchedSimpleIcon.bitmap,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            favicon != null -> {
                                Image(
                                    bitmap = favicon,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                )
                            }
                            appIcon != null -> {
                                Image(
                                    bitmap = appIcon,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                )
                            }
                            else -> {
                                if (shouldShowFallbackSlot(settings.unmatchedIconHandlingStrategy)) {
                                    UnmatchedIconFallback(
                                        strategy = settings.unmatchedIconHandlingStrategy,
                                        primaryText = iconWebsite,
                                        secondaryText = iconTitle,
                                        defaultIcon = Icons.Default.Security,
                                        iconSize = 40.dp
                                    )
                                }
                            }
                        }
                        if (autoMatchedSimpleIcon.bitmap != null ||
                            favicon != null ||
                            appIcon != null ||
                            shouldShowFallbackSlot(settings.unmatchedIconHandlingStrategy)
                        ) {
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        infoLines.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hasImmersiveBackground) {
                                    immersiveSecondaryColor
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Bitwarden 同步状态指示器
                    if (item.bitwardenVaultId != null) {
                        val syncStatus = when (item.syncStatus) {
                            "PENDING" -> SyncStatus.PENDING
                            "SYNCING" -> SyncStatus.SYNCING
                            "SYNCED" -> SyncStatus.SYNCED
                            "FAILED" -> SyncStatus.FAILED
                            "CONFLICT" -> SyncStatus.CONFLICT
                            else -> if (item.bitwardenLocalModified) SyncStatus.PENDING else SyncStatus.SYNCED
                        }
                        SyncStatusIcon(
                            status = syncStatus,
                            size = 16.dp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    
                    if (!isSelectionMode && item.isFavorite) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = stringResource(R.string.favorite),
                            tint = if (hasImmersiveBackground) {
                                immersiveAccentColor
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    if (isSelectionMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelect?.invoke() }
                        )
                    } else if (onDelete != null) {
                        // 菜单按钮
                        var expanded by remember { mutableStateOf(false) }
                        
                        Box {
                            IconButton(onClick = { expanded = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_options)
                                )
                            }
                            
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                // 收藏选项
                                if (onToggleFavorite != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(if (item.isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites)) },
                                        onClick = {
                                            expanded = false
                                            onToggleFavorite(item.id, !item.isFavorite)
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (item.isFavorite) Icons.Default.FavoriteBorder else Icons.Default.Favorite,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    )
                                }
                                
                                // 上移选项
                                if (onMoveUp != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.move_up)) },
                                        onClick = {
                                            expanded = false
                                            onMoveUp()
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.KeyboardArrowUp,
                                                contentDescription = null
                                            )
                                        }
                                    )
                                }
                                
                                // 下移选项
                                if (onMoveDown != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.move_down)) },
                                        onClick = {
                                            expanded = false
                                            onMoveDown()
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.KeyboardArrowDown,
                                                contentDescription = null
                                            )
                                        }
                                    )
                                }

                                // 编辑
                                if (onEdit != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.edit)) },
                                        onClick = {
                                            expanded = false
                                            onEdit()
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Edit,
                                                contentDescription = null
                                            )
                                        }
                                    )
                                }

                                // 显示二维码选项
                                if (onShowQrCode != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.show_qr_code)) },
                                        onClick = {
                                            expanded = false
                                            onShowQrCode(item)
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.QrCode,
                                                contentDescription = null
                                            )
                                        }
                                    )
                                }
                                
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete)) },
                                    onClick = {
                                        expanded = false
                                        onDelete()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            // 验证码显示
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val shouldBlink = remainingSeconds in 1..5 && totpData.otpType != OtpType.HOTP
                val expiryBlinkAlpha: State<Float> = if (shouldBlink) {
                    val blinkTransition = rememberInfiniteTransition(label = "totp_expiry_blink")
                    blinkTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.5f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 500, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "totp_expiry_blink_alpha"
                    )
                } else {
                    remember { mutableFloatStateOf(1f) }
                }
                
                // 验证码（等宽字体）
                // 统一进度条模式下放大验证码
                val isStandardPeriod = totpData.period == 30 || totpData.period == 60
                val useUnifiedProgressBar = settings.validatorUnifiedProgressBar == UnifiedProgressBarMode.ENABLED
                val isUnifiedMode = useUnifiedProgressBar && isStandardPeriod && totpData.otpType != OtpType.HOTP
                
                val codeFontSize = when {
                    totpData.otpType == OtpType.STEAM -> if (isUnifiedMode) 34.sp else 28.sp
                    isUnifiedMode -> 40.sp
                    else -> 32.sp
                }
                
                DisableSelection {
                    Text(
                        text = if (showOtpCode) {
                            formatOtpCode(currentCode, totpData.otpType)
                        } else {
                            formatMaskedOtpCode(currentCode, totpData)
                        },
                        fontSize = codeFontSize,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.graphicsLayer {
                            // Read animation state in the layer phase so only
                            // the code glyphs redraw during the final seconds.
                            alpha = expiryBlinkAlpha.value
                        },
                        color = when {
                            totpData.otpType == OtpType.STEAM && hasImmersiveBackground -> immersiveTertiaryColor
                            remainingSeconds <= 5 && hasImmersiveBackground -> immersiveErrorColor
                            hasImmersiveBackground -> immersiveAccentColor
                            totpData.otpType == OtpType.STEAM -> MaterialTheme.colorScheme.tertiary
                            remainingSeconds <= 5 -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                }
                
                // 下一次验证码预览
                if (totpData.otpType != OtpType.HOTP) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Next",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (hasImmersiveBackground) {
                                immersiveAccentColor
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                        DisableSelection {
                            Text(
                                text = if (showOtpCode) {
                                    formatOtpCode(nextCode, totpData.otpType)
                                } else {
                                    formatMaskedOtpCode(nextCode, totpData)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (hasImmersiveBackground) {
                                    immersiveSecondaryColor
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                } else {
                    IconButton(
                        onClick = { onCopyCode(codeToCopy) }
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.copy_verification_code)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 进度条/计数器显示
            // 判断是否需要隐藏进度条（启用统一进度条模式且是标准周期30s/60s）
            val isStandardPeriod = totpData.period == 30 || totpData.period == 60
            val useUnifiedProgressBar = settings.validatorUnifiedProgressBar == UnifiedProgressBarMode.ENABLED
            val shouldHideProgress = useUnifiedProgressBar && isStandardPeriod && totpData.otpType != OtpType.HOTP
            
            when (totpData.otpType) {
                OtpType.HOTP -> {
                    // HOTP显示计数器和生成按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.counter_value, totpData.counter),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (hasImmersiveBackground) {
                                immersiveSecondaryColor
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        
                        if (onGenerateNext != null) {
                            OutlinedButton(
                                onClick = { onGenerateNext(item.id) },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.generate_next))
                            }
                        }
                    }
                }
                else -> {
                    if (!shouldHideProgress) {
                        // TOTP/Steam/Yandex/mOTP显示倒计时和进度条（仅在非统一进度条模式或自定义周期时显示）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val progressColor = if (remainingSeconds <= 5) {
                                if (hasImmersiveBackground) immersiveErrorColor else MaterialTheme.colorScheme.error
                            } else {
                                if (hasImmersiveBackground) immersiveAccentColor else MaterialTheme.colorScheme.primary
                            }
                            
                            M3EProgressIndicator(
                                progress = progress,
                                color = progressColor,
                                style = settings.validatorProgressBarStyle,
                                smoothProgress = settings.validatorSmoothProgress,
                                periodSeconds = totpData.period,
                                trackColor = if (hasImmersiveBackground) {
                                    Color.White.copy(alpha = 0.28f)
                                } else {
                                    null
                                },
                                modifier = Modifier.weight(1f)
                            )
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            Text(
                                text = "${remainingSeconds}s",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (remainingSeconds <= 5) {
                                    if (hasImmersiveBackground) immersiveErrorColor else MaterialTheme.colorScheme.error
                                } else {
                                    if (hasImmersiveBackground) immersiveSecondaryColor else MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    } else {
                        // 统一进度条模式下，不显示任何内容（倒计时已在顶部统一显示）
                        // 不需要显示任何UI
                    }
                }
            }
            }
            }
        }
    }
}

private fun resolveAuthenticatorInfoLines(
    issuer: String,
    accountName: String,
    fields: List<takagi.ru.monica.data.AuthenticatorCardDisplayField>
): List<String> {
    val distinctFields = fields.distinct()
    return buildList {
        distinctFields.forEach { field ->
            when (field) {
                takagi.ru.monica.data.AuthenticatorCardDisplayField.ISSUER -> {
                    val value = issuer.trim()
                    if (value.isNotBlank()) add(value)
                }
                takagi.ru.monica.data.AuthenticatorCardDisplayField.ACCOUNT_NAME -> {
                    val value = accountName.trim()
                    if (value.isNotBlank()) add(value)
                }
            }
        }
    }
}

@Composable
private fun M3EProgressIndicator(
    progress: Float,
    color: Color,
    style: ProgressBarStyle,
    smoothProgress: Boolean,
    periodSeconds: Int,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 12.dp,
    trackColor: Color? = null
) {
    val safeProgress = if (progress.isFinite()) progress else 0f
    val clampedProgress = safeProgress.coerceIn(0f, 1f)
    val animatedProgress = remember { Animatable(clampedProgress) }
    LaunchedEffect(clampedProgress, smoothProgress, periodSeconds) {
        if (smoothProgress) {
            if (shouldResetSmoothTotpProgress(animatedProgress.value, clampedProgress)) {
                animatedProgress.snapTo(clampedProgress)
            }
            animatedProgress.animateTo(
                targetValue = nextSmoothTotpProgressTarget(clampedProgress, periodSeconds),
                animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
            )
        } else {
            animatedProgress.animateTo(
                targetValue = clampedProgress,
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            )
        }
    }

    val waveOffsetState = if (style == ProgressBarStyle.WAVE) {
        val waveTransition = rememberInfiniteTransition(label = "m3e_wave")
        waveTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2f * PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "m3e_wave_offset"
        )
    } else {
        null
    }

    val resolvedTrackColor = trackColor
        ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)

    Box(
        modifier = modifier
            .height(trackHeight)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f) return@Canvas

            // Reading animation state inside the draw phase invalidates only
            // this canvas instead of recomposing the surrounding card.
            val fillFraction = animatedProgress.value.coerceIn(0f, 1f)
            val waveOffset = waveOffsetState?.value ?: 0f
            val progressWidth = width * fillFraction
            if (!progressWidth.isFinite()) return@Canvas
            val centerY = height / 2f
            val gap = 8.dp.toPx()

            when (style) {
                ProgressBarStyle.WAVE -> {
                    // 与统一进度条一致：波浪线 + 断开轨道
                    val strokeWidth = height * 0.5f
                    val amplitude = height * 0.25f
                    val wavelength = 35.dp.toPx()
                    if (wavelength <= 0f) return@Canvas

                    val trackStartX = progressWidth + gap
                    if (trackStartX < width) {
                        drawLine(
                            color = resolvedTrackColor,
                            start = Offset(trackStartX, centerY),
                            end = Offset(width, centerY),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                    }

                    if (progressWidth > strokeWidth) {
                        val progressPath = Path().apply {
                            var x = 0f
                            val startY = centerY + amplitude * sin(waveOffset)
                            moveTo(0f, startY)
                            var safetySteps = 0
                            while (x <= progressWidth && safetySteps < 4000) {
                                val phase = (x / wavelength) * 2f * PI.toFloat() + waveOffset
                                val y = centerY + amplitude * sin(phase)
                                if (!y.isFinite()) break
                                lineTo(x, y)
                                x += 2f
                                safetySteps++
                            }
                        }
                        drawPath(
                            path = progressPath,
                            color = color,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = strokeWidth,
                                cap = StrokeCap.Round
                            )
                        )
                    }
                }

                ProgressBarStyle.LINEAR -> {
                    // 与统一进度条一致：线形断开轨道
                    val strokeWidth = height * 0.6f

                    if (progressWidth > strokeWidth) {
                        drawLine(
                            color = color,
                            start = Offset(strokeWidth / 2f, centerY),
                            end = Offset(progressWidth, centerY),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                    }

                    val trackStartX = progressWidth + gap
                    if (trackStartX < width - strokeWidth / 2f) {
                        drawLine(
                            color = resolvedTrackColor,
                            start = Offset(trackStartX, centerY),
                            end = Offset(width - strokeWidth / 2f, centerY),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }
    }
}

private fun normalizeTotpData(data: TotpData): TotpData {
    return TotpDataResolver.normalizeTotpData(data)
}

private fun formatMaskedOtpCode(code: String, totpData: TotpData): String {
    val fallbackLength = when (totpData.otpType) {
        OtpType.STEAM -> 5
        else -> totpData.digits.coerceIn(1, 12)
    }
    val visibleCode = code.ifEmpty { "*".repeat(fallbackLength) }
    val maskedCode = when {
        visibleCode.length <= 2 -> visibleCode
        else -> buildString {
            append(visibleCode.take(2))
            repeat(visibleCode.length - 2) { append('*') }
        }
    }
    return formatOtpCode(maskedCode, totpData.otpType)
}

/**
 * 格式化OTP验证码（根据类型添加空格分隔）
 * 例如: 
 * - TOTP 6位: 123456 -> 123 456
 * - TOTP 8位: 12345678 -> 1234 5678
 * - Steam 5位: 2BC4X -> 2B C4X
 */
private fun formatOtpCode(code: String, otpType: OtpType): String {
    return when (otpType) {
        OtpType.STEAM -> {
            // Steam使用5位字符，格式为 2B C4X
            if (code.length == 5) {
                "${code.substring(0, 2)} ${code.substring(2)}"
            } else {
                code
            }
        }
        else -> {
            // 数字验证码
            when (code.length) {
                6 -> "${code.substring(0, 3)} ${code.substring(3)}"
                8 -> "${code.substring(0, 4)} ${code.substring(4)}"
                else -> code
            }
        }
    }
}

