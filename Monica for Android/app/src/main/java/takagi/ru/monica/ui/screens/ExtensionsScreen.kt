package takagi.ru.monica.ui.screens

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import takagi.ru.monica.ui.haptic.MonicaSwitch as Switch

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import takagi.ru.monica.ui.components.SettingsPanelGroup
import takagi.ru.monica.ui.components.SettingsPanelRow
import takagi.ru.monica.ui.components.settingsPanelSurface
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordSwipeSelectionMode
import takagi.ru.monica.data.SecureItem

/**
 * 功能拓展页面 - 聚合各种扩展功能的设置
 * 包含：显示分组、验证器震动提醒等
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ExtensionsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToMonicaPlus: () -> Unit = {},
    onNavigateToQuickSetup: () -> Unit = {},
    isPlusActivated: Boolean = false,
    hapticFeedbackEnabled: Boolean = true,
    hapticFeedbackStrength: Int = 1,
    onHapticStrengthChange: (Int) -> Unit = {},
    onHapticFeedbackChange: (Boolean) -> Unit = {},
    validatorVibrationEnabled: Boolean = false,
    onValidatorVibrationChange: (Boolean) -> Unit = {},
    copyNextCodeWhenExpiring: Boolean = false,
    onCopyNextCodeWhenExpiringChange: (Boolean) -> Unit = {},
    smartDeduplicationEnabled: Boolean = false,
    onSmartDeduplicationEnabledChange: (Boolean) -> Unit = {},
    clipboardAutoClearSeconds: Int = 0,
    onClipboardAutoClearSecondsChange: (Int) -> Unit = {},
    passwordDetailSecurityAnalysisEnabled: Boolean = true,
    onPasswordDetailSecurityAnalysisEnabledChange: (Boolean) -> Unit = {},
    steamMiniProfileBackgroundEnabled: Boolean = false,
    onSteamMiniProfileBackgroundEnabledChange: (Boolean) -> Unit = {},
    passwordSwipeSelectionMode: PasswordSwipeSelectionMode = PasswordSwipeSelectionMode.DEFAULT,
    onPasswordSwipeSelectionModeChange: (PasswordSwipeSelectionMode) -> Unit = {},
    passwordCardDisplayMode: takagi.ru.monica.data.PasswordCardDisplayMode = takagi.ru.monica.data.PasswordCardDisplayMode.SHOW_ALL,
    onPasswordCardDisplayModeChange: (takagi.ru.monica.data.PasswordCardDisplayMode) -> Unit = {},
    validatorUnifiedProgressBar: takagi.ru.monica.data.UnifiedProgressBarMode = takagi.ru.monica.data.UnifiedProgressBarMode.DISABLED,
    onValidatorUnifiedProgressBarChange: (takagi.ru.monica.data.UnifiedProgressBarMode) -> Unit = {},
    // 通知栏验证器参数
    notificationValidatorEnabled: Boolean = false,
    notificationValidatorAutoMatch: Boolean = false,
    notificationValidatorId: Long = 0L,
    totpItems: List<SecureItem> = emptyList(),
    onNotificationValidatorEnabledChange: (Boolean) -> Unit = {},
    onNotificationValidatorAutoMatchChange: (Boolean) -> Unit = {},
    onNotificationValidatorSelected: (Long) -> Unit = {}
) {
    val scrollState = rememberScrollState()
    
    // 密码卡片显示模式选择对话框
    var showDisplayModeDialog by remember { mutableStateOf(false) }
    var showClipboardAutoClearDialog by remember { mutableStateOf(false) }
    val clipboardAutoClearOptions = remember { listOf(0, 10, 20, 30, 60) }
    
    if (showDisplayModeDialog) {
        AlertDialog(
            onDismissRequest = { showDisplayModeDialog = false },
            title = { Text(stringResource(R.string.password_card_display_mode_title), modifier = Modifier.settingsSearchAnchor(stringResource(R.string.password_card_display_mode_title))) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    takagi.ru.monica.data.PasswordCardDisplayMode.values().forEach { mode ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPasswordCardDisplayModeChange(mode)
                                    showDisplayModeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (mode == passwordCardDisplayMode),
                                onClick = {
                                    onPasswordCardDisplayModeChange(mode)
                                    showDisplayModeDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = when (mode) {
                                    takagi.ru.monica.data.PasswordCardDisplayMode.SHOW_ALL -> 
                                        stringResource(R.string.display_mode_all)
                                    takagi.ru.monica.data.PasswordCardDisplayMode.TITLE_USERNAME -> 
                                        stringResource(R.string.display_mode_title_username)
                                    takagi.ru.monica.data.PasswordCardDisplayMode.TITLE_ONLY -> 
                                        stringResource(R.string.display_mode_title_only)
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDisplayModeDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showClipboardAutoClearDialog) {
        AlertDialog(
            modifier = Modifier.testTag("extensions_clipboard_dialog"),
            onDismissRequest = { showClipboardAutoClearDialog = false },
            title = { Text(stringResource(R.string.clipboard_auto_clear_title), modifier = Modifier.settingsSearchAnchor(stringResource(R.string.clipboard_auto_clear_title))) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    clipboardAutoClearOptions.forEach { seconds ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onClipboardAutoClearSecondsChange(seconds)
                                    showClipboardAutoClearDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = seconds == clipboardAutoClearSeconds,
                                onClick = {
                                    onClipboardAutoClearSecondsChange(seconds)
                                    showClipboardAutoClearDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(clipboardAutoClearLabel(seconds))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showClipboardAutoClearDialog = false }, modifier = Modifier.testTag("extensions_clipboard_close")) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 准备共享元素 Modifier
    val sharedTransitionScope = takagi.ru.monica.ui.LocalSharedTransitionScope.current
    val animatedVisibilityScope = takagi.ru.monica.ui.LocalAnimatedVisibilityScope.current
    
    var sharedModifier: Modifier = Modifier
    if (false && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope!!) {
            sharedModifier = Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "extensions_settings_card"),
                animatedVisibilityScope = animatedVisibilityScope!!,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
            )
        }
    }
    
    Scaffold(
        modifier = sharedModifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.extensions_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
        ) {
            if (isPlusActivated) {
                takagi.ru.monica.ui.components.MonicaPlusCard(
                    isPlusActivated = true,
                    onClick = onNavigateToMonicaPlus
                )
            }

            ExtensionSection(title = stringResource(R.string.quick_init_section_title)) {
                ExtensionClickableItem(
                    icon = Icons.Default.SettingsSuggest,
                    setting = Setting.RE_QUICK_INIT_TITLE,
                    onClick = onNavigateToQuickSetup
                )
            }

            ExtensionSection(title = stringResource(R.string.extensions_security_settings)) {
                ExtensionChoiceItem(
                    icon = Icons.Default.ContentPaste,
                    setting = Setting.CLIPBOARD_AUTO_CLEAR_TITLE,
                    value = clipboardAutoClearLabel(clipboardAutoClearSeconds),
                    onClick = { showClipboardAutoClearDialog = true }
                )
            }
            
            ExtensionSection(title = stringResource(R.string.display_options_menu_title)) {
                ExtensionChoiceItem(Icons.Default.Dns,
                    Setting.PASSWORD_CARD_DISPLAY_MODE_TITLE, "",
                    stringResource(when (passwordCardDisplayMode) {
                        takagi.ru.monica.data.PasswordCardDisplayMode.SHOW_ALL -> R.string.display_mode_all
                        takagi.ru.monica.data.PasswordCardDisplayMode.TITLE_USERNAME -> R.string.display_mode_title_username
                        takagi.ru.monica.data.PasswordCardDisplayMode.TITLE_ONLY -> R.string.display_mode_title_only
                    }), { showDisplayModeDialog = true })
                ExtensionSwitchItem(
                    icon = Icons.Default.CallMerge,
                    setting = Setting.SMART_DEDUPLICATION,
                    checked = smartDeduplicationEnabled,
                    onCheckedChange = onSmartDeduplicationEnabledChange
                )

                ExtensionSwitchItem(
                    icon = Icons.Default.Security,
                    setting = Setting.PASSWORD_DETAIL_SECURITY_ANALYSIS_TITLE,
                    checked = passwordDetailSecurityAnalysisEnabled,
                    onCheckedChange = onPasswordDetailSecurityAnalysisEnabledChange
                )

                ExtensionSegmentedItem(
                    icon = Icons.Default.Swipe,
                    setting = Setting.SWIPE_SELECTION_MODE,
                    selectedMode = passwordSwipeSelectionMode,
                    onModeChange = onPasswordSwipeSelectionModeChange
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            ExtensionSection(title = stringResource(R.string.extensions_steam_settings)) {
                ExtensionSwitchItem(
                    icon = Icons.Default.Wallpaper,
                    setting = Setting.STEAM_MINI_PROFILE_BACKGROUND_TITLE,
                    checked = steamMiniProfileBackgroundEnabled,
                    onCheckedChange = onSteamMiniProfileBackgroundEnabledChange
                )
            }
             
            Spacer(modifier = Modifier.height(8.dp))
            ExtensionSection(title = stringResource(R.string.haptic_feedback)) {
                ExtensionSwitchItem(
                    icon = Icons.Default.Vibration,
                    setting = Setting.HAPTIC_FEEDBACK,
                    checked = hapticFeedbackEnabled,
                    onCheckedChange = onHapticFeedbackChange
                )
                takagi.ru.monica.ui.components.HapticStrengthControl(
                    enabled = hapticFeedbackEnabled,
                    strength = hapticFeedbackStrength,
                    onStrengthChange = onHapticStrengthChange,
                )
            }

            // 验证器设置（需要 Plus）
            if (isPlusActivated) {
                Spacer(modifier = Modifier.height(8.dp))
                ExtensionSection(title = stringResource(R.string.extensions_totp_settings)) {
                    ExtensionSwitchItem(
                        icon = Icons.Default.Vibration,
                        setting = Setting.VALIDATOR_VIBRATION,
                        checked = validatorVibrationEnabled,
                        enabled = hapticFeedbackEnabled,
                        onCheckedChange = onValidatorVibrationChange
                    )

                    ExtensionSwitchItem(
                        icon = Icons.Default.Update,
                        setting = Setting.COPY_NEXT_CODE_WHEN_EXPIRING,
                        checked = copyNextCodeWhenExpiring,
                        onCheckedChange = onCopyNextCodeWhenExpiringChange
                    )
                }
                
            }
            
            // 更多功能即将推出提示
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Upcoming,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        stringResource(R.string.extensions_more_coming_soon),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun clipboardAutoClearLabel(seconds: Int): String {
    return if (seconds <= 0) {
        stringResource(R.string.clipboard_auto_clear_never)
    } else {
        stringResource(R.string.clipboard_auto_clear_seconds, seconds)
    }
}

/**
 * 功能分类区块
 */
@Composable
private fun ExtensionSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsPanelGroup(title, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), content)
}

/**
 * 可点击的功能项（导航到其他页面）
 */
@Composable
private fun ExtensionClickableItem(
    icon: ImageVector,
    setting: Setting,
    description: String = setting.summary(),
    onClick: () -> Unit
) {
    val title = setting.title()
    SettingsPanelRow(icon, setting, description, onClick = onClick)
}

@Composable
private fun ExtensionChoiceItem(
    icon: ImageVector,
    setting: Setting,
    description: String = setting.summary(),
    value: String,
    onClick: () -> Unit
) {
    val title = setting.title()
    SettingsPanelRow(icon, setting, "$value\n$description", onClick = onClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtensionSegmentedItem(
    icon: ImageVector,
    setting: Setting,
    description: String = setting.summary(),
    selectedMode: PasswordSwipeSelectionMode,
    onModeChange: (PasswordSwipeSelectionMode) -> Unit
) {
    val title = setting.title()
    Column(
        modifier = Modifier
            .settingsPanelSurface().settingsSearchAnchor(title)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val modes = PasswordSwipeSelectionMode.values()
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = selectedMode == mode,
                    onClick = { onModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = modes.size
                    ),
                    label = {
                        Text(
                            when (mode) {
                                PasswordSwipeSelectionMode.SINGLE -> stringResource(R.string.single_select)
                                PasswordSwipeSelectionMode.CONTINUOUS -> stringResource(R.string.swipe_mode_continuous)
                            }
                        )
                    }
                )
            }
        }
    }
}

/**
 * 带开关的功能项
 */
@Composable
private fun ExtensionSwitchItem(
    icon: ImageVector,
    setting: Setting,
    description: String = setting.summary(),
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val title = setting.title()
    SettingsPanelRow(icon, setting, description, enabled = enabled, checked = checked, onCheckedChange = onCheckedChange)
}

/**
 * 通知栏验证器卡片（扩展页面版本）
 */
@Composable
private fun NotificationValidatorExtensionCard(
    enabled: Boolean,
    autoMatchEnabled: Boolean,
    selectedId: Long,
    totpItems: List<SecureItem>,
    onEnabledChange: (Boolean) -> Unit,
    onAutoMatchChange: (Boolean) -> Unit,
    onValidatorSelected: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    // If disabled, collapse
    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }

    Column(
        modifier = Modifier.settingsSearchAnchor(stringResource(R.string.notification_validator_title)).animateContentSize()
    ) {
        // Header with Switch
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (enabled) expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (enabled) MaterialTheme.colorScheme.primaryContainer 
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = if (enabled) MaterialTheme.colorScheme.primary 
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.notification_validator_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface 
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Text(
                    text = if (enabled) stringResource(R.string.notification_validator_enabled) 
                           else stringResource(R.string.notification_validator_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }
        
        // Expanded Content
        if (expanded && enabled) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.select_validator_to_display),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                if (totpItems.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_validators_available),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    totpItems.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onValidatorSelected(item.id) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = item.id == selectedId,
                                onClick = { onValidatorSelected(item.id) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}


