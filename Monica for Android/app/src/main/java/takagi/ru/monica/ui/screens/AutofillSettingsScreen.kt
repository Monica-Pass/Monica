package takagi.ru.monica.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.LaunchedEffect
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import takagi.ru.monica.autofill_ng.AutofillPreferences
import takagi.ru.monica.autofill_ng.DomainMatchStrategy
import takagi.ru.monica.autofill_ng.core.AutofillServiceChecker
import takagi.ru.monica.autofill_ng.core.AutofillDiagnostics
import takagi.ru.monica.ui.components.AutofillStatusCard
import takagi.ru.monica.ui.components.AppInfo
import takagi.ru.monica.ui.components.loadInstalledApps
import takagi.ru.monica.ui.components.AppIcon
import takagi.ru.monica.utils.SettingsManager
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun AutofillSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 准备共享元素 Modifier
    val sharedTransitionScope = takagi.ru.monica.ui.LocalSharedTransitionScope.current
    val animatedVisibilityScope = takagi.ru.monica.ui.LocalAnimatedVisibilityScope.current
    
    var scaffoldModifier: Modifier = Modifier
    if (false && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope!!) {
            scaffoldModifier = Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "autofill_settings_card"),
                animatedVisibilityScope = animatedVisibilityScope!!,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
            )
        }
    }
    

    val autofillPreferences = remember { AutofillPreferences(context) }
    val settingsManager = remember { SettingsManager(context) }
    
    // 自动填充验证设置
    val appSettings by settingsManager.settingsFlow.collectAsState(initial = takagi.ru.monica.data.AppSettings())
    val autofillAuthRequired = appSettings.autofillAuthRequired
    val domainMatchStrategy by autofillPreferences.domainMatchStrategy.collectAsState(initial = DomainMatchStrategy.BASE_DOMAIN)
    val fillSuggestionsEnabled by autofillPreferences.isFillSuggestionsEnabled.collectAsState(initial = true)
    val manualSelectionEnabled by autofillPreferences.isManualSelectionEnabled.collectAsState(initial = true)
    val biometricQuickFillEnabled by autofillPreferences.isBiometricQuickFillEnabled.collectAsState(initial = true)
    val requestSaveDataEnabled by autofillPreferences.isRequestSaveDataEnabled.collectAsState(initial = true)
    val autoSaveAppInfoEnabled by autofillPreferences.isAutoSaveAppInfoEnabled.collectAsState(initial = true)
    val autoSaveWebsiteInfoEnabled by autofillPreferences.isAutoSaveWebsiteInfoEnabled.collectAsState(initial = true)
    val autoUpdateDuplicatePasswordsEnabled by autofillPreferences.isAutoUpdateDuplicatePasswordsEnabled.collectAsState(initial = false)
    val showSaveNotificationEnabled by autofillPreferences.isShowSaveNotificationEnabled.collectAsState(initial = true)
    val smartTitleGenerationEnabled by autofillPreferences.isSmartTitleGenerationEnabled.collectAsState(initial = true)
    val blacklistEnabled by autofillPreferences.isBlacklistEnabled.collectAsState(initial = true)
    val blacklistPackages by autofillPreferences.blacklistPackages.collectAsState(initial = AutofillPreferences.DEFAULT_BLACKLIST_PACKAGES)
    
    var showStrategyDialog by remember { mutableStateOf(false) }
    var showBlacklistDialog by remember { mutableStateOf(false) }
    
    // 服务状态检查器
    val serviceChecker = remember { AutofillServiceChecker(context) }
    var serviceStatus by remember { mutableStateOf<AutofillServiceChecker.ServiceStatus?>(null) }
    
    // 使用可变状态来追踪系统自动填充服务状态,这样可以实时更新
    var isSystemAutofillEnabled by remember { mutableStateOf(false) }
    
    // 获取生命周期所有者
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // 刷新状态的函数
    fun refreshAutofillStatus() {
        scope.launch {
            serviceStatus = serviceChecker.checkServiceStatus()
            
            // 实时检查系统自动填充服务状态
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val autofillManager = context.getSystemService(AutofillManager::class.java)
                isSystemAutofillEnabled = autofillManager?.hasEnabledAutofillServices() == true
                
                android.util.Log.d("AutofillSettings", "刷新状态: isSystemAutofillEnabled = $isSystemAutofillEnabled")
                android.util.Log.d("AutofillSettings", "当前服务: ${autofillManager?.autofillServiceComponentName}")
            }
        }
    }
    
    // 监听生命周期,当界面 Resume 时刷新状态
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                android.util.Log.d("AutofillSettings", "界面 Resume,刷新自动填充状态")
                refreshAutofillStatus()
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // 首次加载时检查状态
    LaunchedEffect(Unit) {
        refreshAutofillStatus()
    }
    
    Scaffold(
        modifier = scaffoldModifier,
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(
                            stringResource(R.string.autofill_settings_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.autofill_settings_back))
                    }
                },
                actions = {
                    // 添加刷新按钮
                    IconButton(
                        onClick = { 
                            android.util.Log.d("AutofillSettings", "手动刷新状态")
                            refreshAutofillStatus() 
                        }
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.autofill_settings_refresh),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 顶部状态卡片 - 使用新的 AutofillStatusCard 组件
            serviceStatus?.let { status ->
                AutofillStatusCard(
                    status = status,
                    onEnableClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                            intent.data = Uri.parse("package:${context.packageName}")
                            context.startActivity(intent)
                        }
                    }
                )
            }
            
            // 系统设置卡片
            SectionCard(
                title = stringResource(R.string.autofill_system_settings_title),
                icon = Icons.Outlined.Settings,
                iconTint = MaterialTheme.colorScheme.primary
            ) {
                AutofillSettingItem(
                    icon = Icons.Outlined.Smartphone,
                    title = stringResource(R.string.autofill_system_set_provider),
                    subtitle = stringResource(R.string.autofill_system_set_provider_desc),
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                            intent.data = Uri.parse("package:${context.packageName}")
                            context.startActivity(intent)
                        }
                    }
                )
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    AutofillSettingItem(
                        icon = Icons.Outlined.Key,
                        title = stringResource(R.string.autofill_system_passkey_settings),
                        subtitle = stringResource(R.string.autofill_system_passkey_settings_desc),
                        onClick = {
                            val intent = Intent(Settings.ACTION_SETTINGS)
                            context.startActivity(intent)
                        }
                    )
                }
                
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                AutofillSettingItem(
                    icon = Icons.Outlined.Language,
                    title = stringResource(R.string.autofill_system_chrome_settings),
                    subtitle = stringResource(R.string.autofill_system_chrome_settings_desc),
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://passwords.google.com/options"))
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val chromeIntent = context.packageManager.getLaunchIntentForPackage("com.android.chrome")
                            if (chromeIntent != null) {
                                context.startActivity(chromeIntent)
                            }
                        }
                    }
                )
            }
            
            // 匹配设置卡片
            SectionCard(
                title = stringResource(R.string.autofill_match_settings_title),
                icon = Icons.Outlined.Link,
                iconTint = MaterialTheme.colorScheme.secondary
            ) {
                AutofillSettingItem(
                    icon = Icons.Outlined.AccountTree,
                    title = stringResource(R.string.autofill_domain_strategy_title),
                    subtitle = DomainMatchStrategy.getDisplayName(context, domainMatchStrategy),
                    trailingIcon = Icons.Default.ChevronRight,
                    onClick = { showStrategyDialog = true }
                )
            }
            
            // 填充行为卡片
            SectionCard(
                title = stringResource(R.string.autofill_fill_behavior_title),
                icon = Icons.Outlined.Input,
                iconTint = MaterialTheme.colorScheme.tertiary
            ) {

                SwitchSettingItem(
                    icon = Icons.Outlined.AutoAwesome,
                    title = stringResource(R.string.autofill_fill_suggestions),
                    subtitle = stringResource(R.string.autofill_fill_suggestions_desc),
                    checked = fillSuggestionsEnabled,
                    onCheckedChange = {
                        scope.launch {
                            autofillPreferences.setFillSuggestionsEnabled(it)
                        }
                    }
                )
                
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.TouchApp,
                    title = stringResource(R.string.autofill_fill_manual_selection),
                    subtitle = stringResource(R.string.autofill_fill_manual_selection_desc),
                    checked = manualSelectionEnabled,
                    onCheckedChange = {
                        scope.launch {
                            autofillPreferences.setManualSelectionEnabled(it)
                        }
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                val respectAutofillDisabled by autofillPreferences.isRespectAutofillDisabledEnabled.collectAsState(initial = false)
                SwitchSettingItem(
                    icon = Icons.Outlined.DoNotDisturb,
                    title = stringResource(R.string.autofill_respect_disabled_flag_title),
                    subtitle = stringResource(R.string.autofill_respect_disabled_flag_desc),
                    checked = respectAutofillDisabled,
                    onCheckedChange = {
                        scope.launch {
                            autofillPreferences.setRespectAutofillDisabledEnabled(it)
                        }
                    }
                )
            }
            
            // 验证器设置卡片
            SectionCard(
                title = stringResource(R.string.autofill_otp_settings_title),
                icon = Icons.Outlined.LockClock,
                iconTint = MaterialTheme.colorScheme.secondary
            ) {
                val showOtpNotification by autofillPreferences.isOtpNotificationEnabled.collectAsState(initial = false)
                val autoCopyOtp by autofillPreferences.isAutoCopyOtpEnabled.collectAsState(initial = false)
                val otpDuration by autofillPreferences.otpNotificationDuration.collectAsState(initial = 30)

                SwitchSettingItem(
                    icon = Icons.Outlined.Notifications,
                    title = stringResource(R.string.autofill_show_otp_notification),
                    subtitle = stringResource(R.string.autofill_show_otp_notification_desc),
                    checked = showOtpNotification,
                    onCheckedChange = {  enabled ->
                        scope.launch {
                            autofillPreferences.setOtpNotificationEnabled(enabled)
                            if (enabled) {
                                // 跳转到通知设置
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                                context.startActivity(intent)
                            }
                        }
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.ContentCopy,
                    title = stringResource(R.string.autofill_auto_copy_otp),
                    subtitle = stringResource(R.string.autofill_auto_copy_otp_desc),
                    checked = autoCopyOtp,
                    onCheckedChange = {
                        scope.launch {
                            autofillPreferences.setAutoCopyOtpEnabled(it)
                        }
                    }
                )
                
                // Duration Slider or Selection? Simple text input or predefined here for simplicity?
                // Using a simple row for now as slider takes more space.
                // Let's use a dialog selection or just a simple cycle for now.
                // Or simply repurpose AutofillSettingItem to click and pick.
                // Let's add a "Duration: Xs" item.
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                 AutofillSettingItem(
                    icon = Icons.Outlined.Timer,
                    title = stringResource(R.string.autofill_otp_notification_duration),
                    subtitle = "${otpDuration}s",
                    onClick = {
                        // Cycle through values 15 -> 30 -> 60 -> 120 -> 15
                        val next = when (otpDuration) {
                            15 -> 30
                            30 -> 60
                            60 -> 120
                            else -> 15
                        }
                        scope.launch {
                            autofillPreferences.setOtpNotificationDuration(next)
                        }
                    }
                )
            }
            
            // 保存行为卡片
            SectionCard(
                title = stringResource(R.string.autofill_save_behavior_title),
                icon = Icons.Outlined.Save,
                iconTint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
            ) {
                SwitchSettingItem(
                    icon = Icons.Outlined.AddCircleOutline,
                    title = stringResource(R.string.autofill_save_enable),
                    subtitle = stringResource(R.string.autofill_save_enable_desc),
                    checked = requestSaveDataEnabled,
                    onCheckedChange = {
                        scope.launch {
                            autofillPreferences.setRequestSaveDataEnabled(it)
                        }
                    }
                )
                
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.Sync,
                    title = stringResource(R.string.autofill_save_update_duplicate),
                    subtitle = stringResource(R.string.autofill_save_update_duplicate_desc),
                    checked = autoUpdateDuplicatePasswordsEnabled,
                    onCheckedChange = {
                        scope.launch {
                            autofillPreferences.setAutoUpdateDuplicatePasswordsEnabled(it)
                        }
                    }
                )
                
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.Notifications,
                    title = stringResource(R.string.autofill_save_show_notification),
                    subtitle = stringResource(R.string.autofill_save_show_notification_desc),
                    checked = showSaveNotificationEnabled,
                    onCheckedChange = {
                        scope.launch {
                            autofillPreferences.setShowSaveNotificationEnabled(it)
                        }
                    }
                )
                
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.AutoAwesome,
                    title = stringResource(R.string.autofill_save_smart_title),
                    subtitle = stringResource(R.string.autofill_save_smart_title_desc),
                    checked = smartTitleGenerationEnabled,
                    onCheckedChange = {
                        scope.launch {
                            autofillPreferences.setSmartTitleGenerationEnabled(it)
                        }
                    }
                )
                
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.PhoneAndroid,
                    title = stringResource(R.string.autofill_save_app_info),
                    subtitle = stringResource(R.string.autofill_save_app_info_desc),
                    checked = autoSaveAppInfoEnabled,
                    onCheckedChange = {
                        scope.launch {
                            autofillPreferences.setAutoSaveAppInfoEnabled(it)
                        }
                    }
                )
                
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.Public,
                    title = stringResource(R.string.autofill_save_website_info),
                    subtitle = stringResource(R.string.autofill_save_website_info_desc),
                    checked = autoSaveWebsiteInfoEnabled,
                    onCheckedChange = {
                        scope.launch {
                            autofillPreferences.setAutoSaveWebsiteInfoEnabled(it)
                        }
                    }
                )
            }
            
            // 黑名单卡片
            SectionCard(
                title = stringResource(R.string.autofill_blacklist_title),
                icon = Icons.Outlined.Block,
                iconTint = MaterialTheme.colorScheme.error
            ) {
                SwitchSettingItem(
                    icon = Icons.Outlined.Block,
                    title = stringResource(R.string.autofill_blacklist_enable),
                    subtitle = stringResource(R.string.autofill_blacklist_enable_desc),
                    checked = blacklistEnabled,
                    onCheckedChange = {
                        scope.launch {
                            autofillPreferences.setBlacklistEnabled(it)
                        }
                    }
                )
                
                if (blacklistEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    AutofillSettingItem(
                        icon = Icons.Outlined.Apps,
                        title = stringResource(R.string.autofill_blacklist_manage),
                        subtitle = stringResource(R.string.autofill_blacklist_manage_desc, blacklistPackages.size),
                        trailingIcon = Icons.Default.ChevronRight,
                        onClick = { showBlacklistDialog = true }
                    )
                }
            }
            
            // 底部说明
            InfoCard()
        }
    }
    
    // 域名匹配策略选择对话框
    if (showStrategyDialog) {
        StrategySelectionDialog(
            currentStrategy = domainMatchStrategy,
            onStrategySelected = { strategy ->
                scope.launch {
                    autofillPreferences.setDomainMatchStrategy(strategy)
                    showStrategyDialog = false
                }
            },
            onDismiss = { showStrategyDialog = false }
        )
    }
    
    // 黑名单管理对话框
    if (showBlacklistDialog) {
        BlacklistManagementDialog(
            blacklistPackages = blacklistPackages,
            onDismiss = { showBlacklistDialog = false },
            onPackageToggle = { packageName, isBlacklisted ->
                scope.launch {
                    if (isBlacklisted) {
                        autofillPreferences.addToBlacklist(packageName)
                    } else {
                        autofillPreferences.removeFromBlacklist(packageName)
                    }
                }
            }
        )
    }
}

// 状态Banner组件
@Composable
fun StatusBanner(isEnabled: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            if (isEnabled)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else
                                MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isEnabled) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = if (isEnabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                }
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isEnabled) {
                            stringResource(R.string.autofill_status_enabled)
                        } else {
                            stringResource(R.string.autofill_status_disabled)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isEnabled)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isEnabled) 
                            stringResource(R.string.autofill_status_enabled_desc)
                        else 
                            stringResource(R.string.autofill_status_disabled_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isEnabled)
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        else
                            MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

// 分区卡片组件
@Composable
fun SectionCard(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 卡片标题
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(iconTint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = iconTint
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            // 内容
            Column(content = content)
            
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// 设置项组件
@Composable
fun AutofillSettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailingIcon: ImageVector = Icons.Outlined.OpenInNew,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Icon(
            imageVector = trailingIcon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

// 开关设置项组件
@Composable
fun SwitchSettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (checked) 
                MaterialTheme.colorScheme.primary 
            else 
                MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

// 普通设置项组件
@Composable
fun AutofillSettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// 策略选择对话框
@Composable
fun StrategySelectionDialog(
    currentStrategy: DomainMatchStrategy,
    onStrategySelected: (DomainMatchStrategy) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.AccountTree,
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
        },
        title = { 
            Text(
                stringResource(R.string.autofill_domain_strategy_dialog_title),
                fontWeight = FontWeight.Bold
            ) 
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DomainMatchStrategy.values().forEach { strategy ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onStrategySelected(strategy) },
                        color = if (strategy == currentStrategy) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else 
                            Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = strategy == currentStrategy,
                                onClick = { onStrategySelected(strategy) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = DomainMatchStrategy.getDisplayName(context, strategy),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (strategy == currentStrategy) 
                                        FontWeight.SemiBold 
                                    else 
                                        FontWeight.Normal
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = DomainMatchStrategy.getDescription(context, strategy),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.autofill_domain_strategy_dialog_close))
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

// 黑名单管理对话框
@Composable
fun BlacklistManagementDialog(
    blacklistPackages: Set<String>,
    onDismiss: () -> Unit,
    onPackageToggle: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        installedApps = loadInstalledApps(context)
        isLoading = false
    }

    val filteredApps = remember(installedApps, searchQuery) {
        val matchingApps = if (searchQuery.isBlank()) installedApps else {
            val query = searchQuery.trim().lowercase(java.util.Locale.getDefault())
            installedApps.filter { app ->
                app.appName.lowercase(java.util.Locale.getDefault()).contains(query) ||
                app.packageName.lowercase(java.util.Locale.getDefault()).contains(query)
            }
        }
        matchingApps.sortedWith(
            compareByDescending<AppInfo> { blacklistPackages.contains(it.packageName) }
                .thenBy { it.appName.lowercase(java.util.Locale.getDefault()) }
                .thenBy { it.packageName.lowercase(java.util.Locale.getDefault()) }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Block,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                context.getString(R.string.autofill_blacklist_dialog_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = context.getString(R.string.autofill_blacklist_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(context.getString(R.string.search_apps)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = null)
                            }
                        }
                    },
                    singleLine = true,
                )

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (filteredApps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) {
                                context.getString(R.string.autofill_blacklist_no_apps)
                            } else {
                                context.getString(R.string.autofill_blacklist_no_match, searchQuery)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // Keep rows tied to packages rather than their current
                        // position so filtering cannot reuse a previous icon.
                        items(
                            items = filteredApps,
                            key = { it.packageName }
                        ) { app ->
                            val isInBlacklist = blacklistPackages.contains(app.packageName)

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        onPackageToggle(app.packageName, !isInBlacklist)
                                    },
                                color = if (isInBlacklist)
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                else
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        AppIcon(app = app, modifier = Modifier.size(32.dp))

                                        Column {
                                            Text(
                                                text = app.appName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = app.packageName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Switch(
                                        checked = isInBlacklist,
                                        onCheckedChange = { checked ->
                                            onPackageToggle(app.packageName, checked)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.autofill_blacklist_dialog_done))
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

// 信息卡片
@Composable
fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = stringResource(R.string.autofill_info_card_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
            )
        }
    }
}



