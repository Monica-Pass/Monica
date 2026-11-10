package takagi.ru.monica.ui.screens

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import takagi.ru.monica.BuildConfig
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.MonicaExpandableContent
import takagi.ru.monica.ui.components.BottomNavConfigRow
import takagi.ru.monica.ui.components.MonicaExpansionChevron
import takagi.ru.monica.data.BottomNavContentTab
import takagi.ru.monica.data.InterfaceScale
import takagi.ru.monica.data.Language
import takagi.ru.monica.ui.components.TrashSettingsSheet
import takagi.ru.monica.data.ThemeMode
import takagi.ru.monica.ui.components.M3IdentityVerifyDialog
import takagi.ru.monica.ui.components.UnifiedMoveAction
import takagi.ru.monica.ui.common.pull.rememberPullToActionState
import takagi.ru.monica.ui.main.navigation.SteamDockIcon
import takagi.ru.monica.ui.password.PasswordBatchDeleteGlobalProgressState
import takagi.ru.monica.ui.password.PasswordBatchDeleteProgressTracker
import takagi.ru.monica.ui.password.PasswordBatchDeletePhase
import takagi.ru.monica.ui.password.PasswordBatchTransferGlobalProgressState
import takagi.ru.monica.ui.password.PasswordBatchTransferProgressTracker
import takagi.ru.monica.ui.settings.SecurityAnalysisPullCard
import takagi.ru.monica.ui.settings.SecurityAnalysisPullMaxDistance
import takagi.ru.monica.ui.settings.SecurityAnalysisPullTriggerDistance
import takagi.ru.monica.utils.BiometricAuthHelper
import takagi.ru.monica.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import takagi.ru.monica.data.SecureItem
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import takagi.ru.monica.ui.components.OutlinedTextField

@Composable
private fun VersionLinkButton(
    title: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

private class DeveloperSettingsVerificationRequest(val generation: Long)

private val LocalSettingsSectionContent = compositionLocalOf { false }

internal data class SettingsSectionCornerRadii(
    val top: Int,
    val bottom: Int
)

internal fun settingsSectionCornerRadii(
    index: Int,
    totalItems: Int
): SettingsSectionCornerRadii = when {
    totalItems <= 1 -> SettingsSectionCornerRadii(top = 24, bottom = 24)
    index == 0 -> SettingsSectionCornerRadii(top = 24, bottom = 4)
    index == totalItems - 1 -> SettingsSectionCornerRadii(top = 4, bottom = 24)
    else -> SettingsSectionCornerRadii(top = 4, bottom = 4)
}

internal fun settingsSectionItemShape(index: Int, totalItems: Int): RoundedCornerShape {
    val radii = settingsSectionCornerRadii(index, totalItems)
    return RoundedCornerShape(
        topStart = radii.top.dp,
        topEnd = radii.top.dp,
        bottomStart = radii.bottom.dp,
        bottomEnd = radii.bottom.dp
    )
}

private fun visibleSettingsIndex(vararg precedingItems: Boolean): Int =
    precedingItems.count { it }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onResetPassword: () -> Unit,
    onSecurityQuestions: () -> Unit,
    onNavigateToMasterPasswordLocking: () -> Unit,
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
    onNavigateToPageCustomization: () -> Unit = {},
    onNavigateToMdbx: () -> Unit = {},
    onClearAllData: (Boolean, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    showTopBar: Boolean = true  // 添加参数控制是否显示顶栏
) {
    val context = LocalContext.current
    val openExternalLink: (String) -> Unit = { url ->
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.cannot_open_browser),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    // 直接使用 LocalContext.current as? ComponentActivity 获取 Activity
    val activity = context as? FragmentActivity
    
    val settings by viewModel.settings.collectAsState()
    val batchDeleteProgress by PasswordBatchDeleteProgressTracker.progress.collectAsState()
    val batchTransferProgress by PasswordBatchTransferProgressTracker.progress.collectAsState()
    val totpItems by viewModel.totpItems.collectAsState()
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var showClearDataDialog by remember { mutableStateOf(false) }
    var clearDataPasswordInput by remember { mutableStateOf("") }
    
    var showThemeDialog by remember { mutableStateOf(false) }
    var showInterfaceScaleSheet by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showVersionInfoDialog by remember { mutableStateOf(false) }
    val updateController = takagi.ru.monica.update.LocalAppUpdateController.current
    val updateState by (updateController?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(takagi.ru.monica.update.AppUpdateState()) }).collectAsState()
    val isCheckingUpdate = updateState.checking
    var showDeveloperVerifyDialog by remember { mutableStateOf(false) }
    var previewFeaturesExpanded by remember { mutableStateOf(false) }
    var developerPasswordInput by remember { mutableStateOf("") }
    var developerPasswordError by remember { mutableStateOf(false) }
    var showWeakBiometricWarning by remember { mutableStateOf(false) }
    var settingsSearchQuery by rememberSaveable { mutableStateOf("") }
    var settingsSearchFocused by remember { mutableStateOf(false) }

    val startUpdateCheck: () -> Unit = { updateController?.checkManually() }

    // 生物识别帮助类
    val biometricHelper = remember(context) { BiometricAuthHelper(context) }
    val isBiometricAvailable = remember(biometricHelper) { 
        biometricHelper.isBiometricAvailable()
    }
    
    val developerLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val developerAlive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val developerNavigate = rememberUpdatedState(onNavigateToDeveloperSettings)
    val developerLockGeneration by takagi.ru.monica.security.SecurityManager.dataReadGeneration.collectAsState()
    var developerRequest by remember { mutableStateOf<DeveloperSettingsVerificationRequest?>(null) }
    var developerBiometric by remember { mutableStateOf<BiometricAuthHelper?>(null) }
    fun currentDeveloperRequest(request: DeveloperSettingsVerificationRequest): Boolean =
        developerAlive.get() && developerRequest === request &&
            takagi.ru.monica.security.SecurityManager.dataReadGeneration.value == request.generation &&
            developerLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
            context.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true &&
            context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == false
    fun cancelDeveloperVerification() {
        val prompt = developerBiometric
        developerBiometric = null
        developerRequest = null
        showDeveloperVerifyDialog = false
        developerPasswordInput = ""
        developerPasswordError = false
        prompt?.cancelAuthentication()
    }
    fun completeDeveloperVerification(request: DeveloperSettingsVerificationRequest) {
        if (!currentDeveloperRequest(request)) return
        cancelDeveloperVerification()
        developerNavigate.value()
    }
    fun startDeveloperBiometric(request: DeveloperSettingsVerificationRequest) {
        if (!currentDeveloperRequest(request)) return
        val owner = activity ?: run { showDeveloperVerifyDialog = true; return }
        developerBiometric?.cancelAuthentication()
        // Each prompt owns its callbacks; an older prompt cannot inherit a replacement request.
        val prompt = BiometricAuthHelper(context)
        developerBiometric = prompt
        prompt.authenticate(
            activity = owner,
            title = context.getString(R.string.biometric_login_title),
            subtitle = context.getString(R.string.biometric_login_subtitle),
            description = context.getString(R.string.biometric_login_description),
            negativeButtonText = context.getString(R.string.use_master_password),
            onSuccess = {
                if (developerBiometric === prompt) completeDeveloperVerification(request)
            },
            onError = { _, errorMessage ->
                if (developerBiometric === prompt && currentDeveloperRequest(request)) {
                    developerBiometric = null
                    showDeveloperVerifyDialog = true
                    Toast.makeText(context, context.getString(R.string.biometric_auth_error, errorMessage), Toast.LENGTH_SHORT).show()
                }
            },
            onCancel = {
                if (developerBiometric === prompt && currentDeveloperRequest(request)) {
                    developerBiometric = null
                    showDeveloperVerifyDialog = true
                    Toast.makeText(context, context.getString(R.string.use_master_password), Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
    DisposableEffect(developerLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                cancelDeveloperVerification()
            }
        }
        developerLifecycle.addObserver(observer)
        onDispose {
            developerLifecycle.removeObserver(observer)
            developerAlive.set(false)
            cancelDeveloperVerification()
        }
    }
    LaunchedEffect(developerLockGeneration) {
        developerRequest?.let { if (it.generation != developerLockGeneration) cancelDeveloperVerification() }
    }

    // 使用本地状态跟踪生物识别开关,避免验证失败时状态不一致
    var biometricSwitchState by remember(settings.biometricEnabled) { 
        mutableStateOf(settings.biometricEnabled) 
    }


    // 准备共享元素 Modifier
    val sharedTransitionScope = takagi.ru.monica.ui.LocalSharedTransitionScope.current
    val animatedVisibilityScope = takagi.ru.monica.ui.LocalAnimatedVisibilityScope.current
    
    @Composable
    fun getSharedModifier(key: String): Modifier {
        // Disable card-to-page shared bounds for settings entries.
        return Modifier
    }

    val startBiometricEnable = {
        if (activity != null) {
            android.util.Log.d("SettingsScreen", "Starting biometric authentication...")
            biometricHelper.authenticate(
                activity = activity,
                title = context.getString(R.string.biometric_login_title),
                subtitle = context.getString(R.string.biometric_subtitle),
                description = context.getString(R.string.biometric_login_description),
                negativeButtonText = context.getString(R.string.cancel),
                onSuccess = {
                    android.util.Log.d("SettingsScreen", "Biometric authentication SUCCESS")
                    biometricSwitchState = true
                    viewModel.updateBiometricEnabled(true)
                    Toast.makeText(
                        context,
                        context.getString(R.string.biometric_unlock_enabled),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onError = { errorCode, errorMsg ->
                    android.util.Log.e("SettingsScreen", "Biometric authentication ERROR: code=$errorCode, msg=$errorMsg")
                    biometricSwitchState = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.biometric_auth_error, errorMsg),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onCancel = {
                    android.util.Log.d("SettingsScreen", "Biometric authentication CANCELLED")
                    biometricSwitchState = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.cancel),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        } else {
            android.util.Log.e("SettingsScreen", "Activity is NULL! Cannot authenticate")
            biometricSwitchState = false
            Toast.makeText(
                context,
                context.getString(R.string.biometric_cannot_enable),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val securityTitle = context.getString(R.string.security)
    val masterPasswordLockingTitle = context.getString(R.string.master_password_and_locking)
    val masterPasswordLockingDescription = context.getString(R.string.master_password_and_locking_description)
    val dataManagementTitle = context.getString(R.string.data_management)
    val appearanceTitle = context.getString(R.string.theme)
    val aboutTitle = context.getString(R.string.about)
    val developerTitle = context.getString(R.string.developer_settings)
    val installedVersion = BuildConfig.VERSION_NAME
        .ifBlank { BuildConfig.FULL_VERSION_NAME }
        .let { version ->
            if (version.startsWith("v", ignoreCase = true)) version else "V$version"
        }

    val biometricSubtitle = if (isBiometricAvailable) {
        if (biometricSwitchState) context.getString(R.string.biometric_unlock_enabled)
        else context.getString(R.string.biometric_unlock_disabled)
    } else {
        biometricHelper.getBiometricStatusMessage()
    }
    val screenshotProtectionSubtitle = if (settings.screenshotProtectionEnabled) {
        context.getString(R.string.screenshot_protection_enabled)
    } else {
        context.getString(R.string.screenshot_protection_disabled)
    }
    val autoLockSubtitle = getAutoLockDisplayName(settings.autoLockMinutes, context)
    val trashSubtitle = if (settings.trashEnabled) {
        if (settings.trashAutoDeleteDays > 0) {
            context.getString(R.string.trash_status_enabled_auto_clear, settings.trashAutoDeleteDays)
        } else {
            context.getString(R.string.trash_status_enabled_no_auto_clear)
        }
    } else {
        context.getString(R.string.trash_status_disabled_permanent_delete)
    }
    val themeSubtitle = getAppearanceDisplayName(
        theme = settings.themeMode,
        oledPureBlackEnabled = settings.oledPureBlackEnabled,
        context = context
    )
    val colorSchemeSubtitle = getColorSchemeDisplayName(settings.colorScheme, context)
    val normalizedInterfaceScalePercent = InterfaceScale.normalizePercent(settings.interfaceScalePercent)
    val interfaceScaleSubtitle = context.getString(
        R.string.interface_scale_current,
        normalizedInterfaceScalePercent,
        InterfaceScale.calculateEffectiveDpi(
            baseDensityDpi = LocalConfiguration.current.densityDpi,
            percent = normalizedInterfaceScalePercent
        )
    )
    val languageSubtitle = getLanguageDisplayName(settings.language, context)

    val searchNavigation = LocalSettingsSearchNavigation.current
    val searchFocusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val searchKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val isSettingsSearchActive = settingsSearchFocused || settingsSearchQuery.isNotEmpty()
    val closeSettingsSearch: () -> Unit = {
        settingsSearchQuery = ""
        searchFocusManager.clearFocus()
        searchKeyboard?.hide()
    }
    BackHandler(enabled = isSettingsSearchActive, onBack = closeSettingsSearch)
    val searchLocale = LocalConfiguration.current.locales.toLanguageTags()
    val searchEntries = remember(context, searchLocale, settings.isPlusActivated) {
        settingsSearchEntries(context, settings.isPlusActivated)
    }
    val searchResults = remember(settingsSearchQuery, searchEntries) {
        searchSettings(searchEntries, settingsSearchQuery)
    }
    val showMonicaPlusCard = !settings.isPlusActivated
    val showSecurityAnalysisCard = settingsSearchQuery.isBlank()
    val showMasterPasswordLockingItem = true
    val showScreenshotProtectionItem = true
    val showPermissionManagementItem = true
    val showSecuritySection = true
    val showSyncBackupItem = true
    val showAutofillItem = true
    val showTrashItem = true
    val showClearDataItem = true
    val showDataManagementSection = true
    val showThemeItem = true
    val showColorSchemeItem = true
    val showInterfaceScaleItem = true
    val showLanguageItem = true
    val showBottomNavItem = true
    val showExtensionsItem = true
    val showPageCustomizationItem = true
    val showAppearanceSection = true
    val showVersionItem = true
    val showUpdateCheckItem = true
    val showPreviewFeaturesItem = true
    val showDeveloperSettingsItem = true
    val securityItemCount = 3
    val dataManagementItemCount = 4
    val appearanceItemCount = 7
    val aboutItemCount = 3
    val developerItemCount = 2
    val density = LocalDensity.current
    val securityPullTriggerDistance = remember(density) {
        with(density) { SecurityAnalysisPullTriggerDistance.toPx() }
    }
    val securityPullMaxDistance = remember(density) {
        with(density) { SecurityAnalysisPullMaxDistance.toPx() }
    }
    val securityPullState = rememberPullToActionState(
        enabled = settingsSearchQuery.isBlank(),
        triggerDistance = securityPullTriggerDistance,
        maxDragDistance = securityPullMaxDistance,
        onTriggered = onSecurityAnalysis,
        canStartPull = { scrollState.value == 0 }
    )

    Scaffold(
        contentWindowInsets = if (showTopBar) {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        } else {
            WindowInsets(0, 0, 0, 0)
        },
        topBar = if (showTopBar) {
            {
                // 使用自定义顶部栏以减小高度
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars) // 仅适配状态栏
                            .height(56.dp) // 标准高度
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            if (isSettingsSearchActive) closeSettingsSearch() else onNavigateBack()
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = context.getString(R.string.back))
                        }
                        
                        Text(
                            text = context.getString(R.string.settings_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        )
                        
                        // 安全分析图标
                        IconButton(onClick = onSecurityAnalysis) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = context.getString(R.string.security_analysis),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        } else {
            {}
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(securityPullState.nestedScrollConnection)
                .verticalScroll(scrollState)
        ) {
            // Top padding spacer for edge-to-edge scrolling
            Spacer(modifier = Modifier.height(paddingValues.calculateTopPadding()))

            if (showSecurityAnalysisCard) {
                SecurityAnalysisPullCard(
                    pullOffset = securityPullState.currentOffset,
                    triggerDistance = securityPullTriggerDistance,
                    isArmed = securityPullState.isArmed,
                    onOpen = onSecurityAnalysis,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .then(getSharedModifier("security_analysis_card"))
                )
            }

            SettingsSearchField(
                query = settingsSearchQuery,
                onQueryChange = { settingsSearchQuery = it },
                active = isSettingsSearchActive,
                onClose = closeSettingsSearch,
                modifier = Modifier.onFocusChanged { settingsSearchFocused = it.isFocused }
            )

            if (settingsSearchQuery.isNotBlank()) {
                SettingsSearchResults(searchResults) { result ->
                    searchFocusManager.clearFocus()
                    searchKeyboard?.hide()
                    checkNotNull(searchNavigation) { "Settings search requires navigation" }.open(result)
                }
            } else {
            batchDeleteProgress?.let { progress ->
                PasswordBatchDeleteProgressCard(progress = progress)
            }

            batchTransferProgress?.let { progress ->
                PasswordBatchTransferProgressCard(progress = progress)
            }

            // Monica Plus card is moved to Extensions page after activation.
            if (showMonicaPlusCard) {
                takagi.ru.monica.ui.components.MonicaPlusCard(
                    isPlusActivated = settings.isPlusActivated,
                    onClick = {
                        android.util.Log.d("SettingsScreen", "Monica Plus card clicked")
                        onNavigateToMonicaPlus()
                    },
                    modifier = getSharedModifier("monica_plus_card")
                )
            }

            if (showSecuritySection) {
                SettingsSection(title = securityTitle) {
                    if (showMasterPasswordLockingItem) {
                        SettingsItem(
                            icon = Icons.Default.Lock,
                            title = masterPasswordLockingTitle,
                            subtitle = masterPasswordLockingDescription,
                            onClick = onNavigateToMasterPasswordLocking,
                            modifier = getSharedModifier("master_password_locking_card"),
                            sectionShape = settingsSectionItemShape(0, securityItemCount)
                        )
                    }

                    if (showScreenshotProtectionItem) {
                        SettingsItem(
                            icon = Icons.Default.Security,
                            title = context.getString(R.string.screenshot_protection),
                            subtitle = screenshotProtectionSubtitle,
                            onClick = {
                                viewModel.updateScreenshotProtectionEnabled(!settings.screenshotProtectionEnabled)
                            },
                            sectionShape = settingsSectionItemShape(
                                visibleSettingsIndex(showMasterPasswordLockingItem),
                                securityItemCount
                            )
                        )
                    }

                    if (showPermissionManagementItem) {
                        SettingsItem(
                            icon = Icons.Default.AdminPanelSettings,
                            title = context.getString(R.string.permission_management_title),
                            subtitle = context.getString(R.string.permission_management_subtitle),
                            onClick = onNavigateToPermissionManagement,
                            modifier = getSharedModifier("permission_settings_card"),
                            sectionShape = settingsSectionItemShape(
                                visibleSettingsIndex(
                                    showMasterPasswordLockingItem,
                                    showScreenshotProtectionItem
                                ),
                                securityItemCount
                            )
                        )
                    }
                }
            }
            
            if (showDataManagementSection) {
                SettingsSection(title = dataManagementTitle) {
                    if (showSyncBackupItem) {
                        SettingsItem(
                            icon = Icons.Default.Sync,
                            title = context.getString(R.string.sync_backup_title),
                            subtitle = context.getString(R.string.sync_backup_description),
                            onClick = onNavigateToSyncBackup,
                            modifier = getSharedModifier("sync_settings_card"),
                            sectionShape = settingsSectionItemShape(0, dataManagementItemCount)
                        )
                    }

                    if (showAutofillItem) {
                        SettingsItem(
                            icon = Icons.Default.VpnKey,
                            title = context.getString(R.string.autofill),
                            subtitle = context.getString(R.string.autofill_subtitle),
                            onClick = onNavigateToAutofill,
                            modifier = getSharedModifier("autofill_settings_card"),
                            sectionShape = settingsSectionItemShape(
                                visibleSettingsIndex(showSyncBackupItem),
                                dataManagementItemCount
                            )
                        )
                    }

                    if (showTrashItem) {
                        SettingsItemWithTrashConfig(
                            trashEnabled = settings.trashEnabled,
                            trashAutoDeleteDays = settings.trashAutoDeleteDays,
                            onTrashEnabledChange = { enabled ->
                                viewModel.updateTrashEnabled(enabled)
                            },
                            onAutoDeleteDaysChange = { days ->
                                viewModel.updateTrashAutoDeleteDays(days)
                            },
                            sectionShape = settingsSectionItemShape(
                                visibleSettingsIndex(showSyncBackupItem, showAutofillItem),
                                dataManagementItemCount
                            )
                        )
                    }

                    if (showClearDataItem) {
                        SettingsItem(
                            icon = Icons.Default.DeleteForever,
                            title = context.getString(R.string.clear_all_data),
                            subtitle = context.getString(R.string.clear_all_data_subtitle),
                            onClick = { showClearDataDialog = true },
                            iconTint = MaterialTheme.colorScheme.error,
                            sectionShape = settingsSectionItemShape(
                                visibleSettingsIndex(
                                    showSyncBackupItem,
                                    showAutofillItem,
                                    showTrashItem
                                ),
                                dataManagementItemCount
                            )
                        )
                    }
                }
            }
            
            if (showAppearanceSection) {
                SettingsSection(title = appearanceTitle) {
                    if (showThemeItem) {
                        SettingsItem(
                            icon = Icons.Default.Palette,
                            title = context.getString(R.string.theme),
                            subtitle = themeSubtitle,
                            onClick = { showThemeDialog = true },
                            sectionShape = settingsSectionItemShape(0, appearanceItemCount)
                        )
                    }

                    if (showColorSchemeItem) {
                        SettingsItem(
                            icon = Icons.Default.Colorize,
                            title = context.getString(R.string.color_scheme),
                            subtitle = colorSchemeSubtitle,
                            onClick = { onNavigateToColorScheme() },
                            modifier = getSharedModifier("color_scheme_card"),
                            sectionShape = settingsSectionItemShape(
                                visibleSettingsIndex(showThemeItem),
                                appearanceItemCount
                            )
                        )
                    }

                    if (showInterfaceScaleItem) {
                        InterfaceScaleSettingsItem(
                            scalePercent = settings.interfaceScalePercent,
                            onClick = { showInterfaceScaleSheet = true },
                            sectionShape = settingsSectionItemShape(
                                visibleSettingsIndex(showThemeItem, showColorSchemeItem),
                                appearanceItemCount
                            )
                        )
                    }

                    if (showLanguageItem) {
                        SettingsItem(
                            icon = Icons.Default.Language,
                            title = context.getString(R.string.language),
                            subtitle = languageSubtitle,
                            onClick = { showLanguageDialog = true },
                            sectionShape = settingsSectionItemShape(
                                visibleSettingsIndex(
                                    showThemeItem,
                                    showColorSchemeItem,
                                    showInterfaceScaleItem
                                ),
                                appearanceItemCount
                            )
                        )
                    }

                    if (showBottomNavItem) {
                        SettingsItem(
                            icon = Icons.Default.ViewWeek,
                            title = context.getString(R.string.bottom_nav_settings),
                            subtitle = context.getString(R.string.bottom_nav_settings_entry_subtitle),
                            onClick = onNavigateToBottomNavSettings,
                            modifier = getSharedModifier("bottom_nav_settings_card"),
                            sectionShape = settingsSectionItemShape(
                                visibleSettingsIndex(
                                    showThemeItem,
                                    showColorSchemeItem,
                                    showInterfaceScaleItem,
                                    showLanguageItem
                                ),
                                appearanceItemCount
                            )
                        )
                    }

                    if (showExtensionsItem) {
                        SettingsItem(
                            icon = Icons.Default.Extension,
                            title = context.getString(R.string.extensions_title),
                            subtitle = context.getString(R.string.extensions_description),
                            onClick = onNavigateToExtensions,
                            modifier = getSharedModifier("extensions_settings_card"),
                            sectionShape = settingsSectionItemShape(
                                visibleSettingsIndex(
                                    showThemeItem,
                                    showColorSchemeItem,
                                    showInterfaceScaleItem,
                                    showLanguageItem,
                                    showBottomNavItem
                                ),
                                appearanceItemCount
                            )
                        )
                    }

                    if (showPageCustomizationItem) {
                        SettingsItem(
                            icon = Icons.Default.Tune,
                            title = context.getString(R.string.page_adjust_custom_title),
                            subtitle = context.getString(R.string.page_adjust_custom_subtitle),
                            onClick = onNavigateToPageCustomization,
                            sectionShape = settingsSectionItemShape(
                                visibleSettingsIndex(
                                    showThemeItem,
                                    showColorSchemeItem,
                                    showInterfaceScaleItem,
                                    showLanguageItem,
                                    showBottomNavItem,
                                    showExtensionsItem
                                ),
                                appearanceItemCount
                            )
                        )
                    }
                }
            }

            if (showVersionItem || showUpdateCheckItem) {
                SettingsSection(title = aboutTitle) {
                    if (showVersionItem) {
                        SettingsItem(
                            icon = Icons.Default.Info,
                            title = context.getString(R.string.version),
                            subtitle = installedVersion,
                            onClick = { showVersionInfoDialog = true },
                            sectionShape = settingsSectionItemShape(0, aboutItemCount)
                        )
                    }

                    if (showUpdateCheckItem) {
                        SettingsItem(
                            icon = Icons.Default.Update,
                            title = context.getString(R.string.update_check_title),
                            subtitle = if (isCheckingUpdate) {
                                context.getString(R.string.update_check_checking)
                            } else {
                                context.getString(R.string.update_check_subtitle)
                            },
                            onClick = startUpdateCheck,
                            trailingContent = {
                                if (isCheckingUpdate) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            sectionShape = settingsSectionItemShape(
                                visibleSettingsIndex(showVersionItem),
                                aboutItemCount
                            )
                        )
                    }
                    SettingsItemWithSwitch(
                        icon = Icons.Default.Update,
                        title = stringResource(R.string.update_auto_check),
                        subtitle = stringResource(R.string.update_auto_check_summary),
                        checked = updateState.preferences.autoCheckEnabled,
                        enabled = updateState.loaded,
                        onCheckedChange = { updateController?.setAutoCheckEnabled(it) },
                        sectionShape = settingsSectionItemShape(2, aboutItemCount),
                    )
                }
            }
            
            if (showPreviewFeaturesItem || showDeveloperSettingsItem) {
                SettingsSection(title = developerTitle) {
                    if (showPreviewFeaturesItem) {
                        SettingsItem(
                            icon = Icons.Default.Science,
                            title = stringResource(R.string.preview_features_title),
                            subtitle = stringResource(R.string.preview_features_description),
                            onClick = { previewFeaturesExpanded = true },
                            sectionShape = settingsSectionItemShape(0, developerItemCount)
                        )
                    }

                    if (showDeveloperSettingsItem) {
                        SettingsItem(
                            icon = Icons.Default.Code,
                            title = stringResource(R.string.developer_settings),
                            subtitle = stringResource(R.string.developer_settings_subtitle),
                            modifier = getSharedModifier("developer_settings_card"),
                            sectionShape = settingsSectionItemShape(
                                visibleSettingsIndex(showPreviewFeaturesItem),
                                developerItemCount
                            ),
                            onClick = {
                                val hasActivity = activity != null
                                val biometricEnabled = settings.biometricEnabled
                                val biometricAvailableNow = hasActivity && biometricEnabled && biometricHelper.isBiometricAvailable()
                                android.util.Log.d(
                                    "SettingsScreen",
                                    "Developer settings tapped. hasActivity=$hasActivity, biometricEnabled=$biometricEnabled, biometricAvailable=$biometricAvailableNow"
                                )

                                cancelDeveloperVerification()
                                val request = DeveloperSettingsVerificationRequest(
                                    takagi.ru.monica.security.SecurityManager.dataReadGeneration.value
                                )
                                developerRequest = request

                                when {
                                    settings.disablePasswordVerification -> {
                                        completeDeveloperVerification(request)
                                    }
                                    !hasActivity -> {
                                        android.util.Log.w(
                                            "SettingsScreen",
                                            "Cannot start biometric auth: FragmentActivity context missing"
                                        )
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.use_master_password),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        showDeveloperVerifyDialog = true
                                    }
                                    biometricAvailableNow -> {
                                        startDeveloperBiometric(request)
                                    }
                                    else -> {
                                        android.util.Log.d(
                                            "SettingsScreen",
                                            "Biometric unavailable, showing password dialog for developer settings"
                                        )
                                        if (biometricEnabled) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.biometric_not_available),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        showDeveloperVerifyDialog = true
                                    }
                                }
                            }
                        )
                    }
                }
            }

            } // Regular settings are hidden while search results are displayed.

            Spacer(modifier = Modifier.height(32.dp))
            
            // Bottom padding spacer for edge-to-edge scrolling
            Spacer(modifier = Modifier.height(paddingValues.calculateBottomPadding()))
        }
    }
    
    // Theme Selection Dialog
    if (showThemeDialog) {
        AppearanceSelectionSheet(
            currentTheme = settings.themeMode,
            oledPureBlackEnabled = settings.oledPureBlackEnabled,
            onThemeSelected = { theme ->
                viewModel.updateThemeMode(theme)
            },
            onOledPureBlackChanged = { enabled ->
                viewModel.updateOledPureBlackEnabled(enabled)
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    if (showInterfaceScaleSheet) {
        InterfaceScaleSelectionSheet(
            currentPercent = settings.interfaceScalePercent,
            onPercentChanged = { percent ->
                viewModel.updateInterfaceScalePercent(percent)
            },
            onDismiss = { showInterfaceScaleSheet = false }
        )
    }
    
    // Language Selection Dialog
    if (showLanguageDialog) {
        LanguageSelectionDialog(
            currentLanguage = settings.language,
            onLanguageSelected = { language ->
                viewModel.updateLanguage(language)
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
    
    // Developer Settings Verification Dialog
    val currentDeveloperVerification = developerRequest
    if (showDeveloperVerifyDialog && currentDeveloperVerification != null) {
        val canUseBiometricInDialog = activity != null &&
            settings.biometricEnabled && biometricHelper.isBiometricAvailable()
        val manager = remember(context) { takagi.ru.monica.security.SecurityManager(context) }
        key(currentDeveloperVerification) {
            takagi.ru.monica.ui.components.SecondaryPasswordVerificationDialog(
                manager = manager,
                title = stringResource(R.string.verify_identity),
                message = stringResource(R.string.enter_master_password_confirm),
                icon = Icons.Default.Code,
                isRequestCurrent = { showDeveloperVerifyDialog && currentDeveloperRequest(currentDeveloperVerification) },
                onDismiss = ::cancelDeveloperVerification,
                onSuccess = { completeDeveloperVerification(currentDeveloperVerification) },
                onBiometricClick = if (canUseBiometricInDialog) ({ startDeveloperBiometric(currentDeveloperVerification) }) else null,
            )
        }
    }

    if (showWeakBiometricWarning) {
        AlertDialog(
            onDismissRequest = { showWeakBiometricWarning = false },
            icon = {
                Icon(
                    Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(stringResource(R.string.biometric_weak_warning_title)) },
            text = { Text(stringResource(R.string.biometric_weak_warning_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWeakBiometricWarning = false
                        startBiometricEnable()
                    }
                ) {
                    Text(stringResource(R.string.biometric_weak_warning_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showWeakBiometricWarning = false }
                ) {
                    Text(stringResource(R.string.biometric_weak_warning_cancel))
                }
            }
        )
    }

    if (showVersionInfoDialog) {
        AlertDialog(
            onDismissRequest = { showVersionInfoDialog = false },
            icon = {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(stringResource(R.string.version_info_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val githubUrl = "https://github.com/Monica-Pass/Monica"
                    val websiteUrl = "https://monica-pass.github.io/MonicaDocs/"
                    val iconSourceUrl = "https://github.com/stratumauth/app/tree/v1.4.0/icons"
                    val iconReleaseUrl = "https://github.com/stratumauth/app/releases/tag/v1.4.0"
                    val fullVersion = BuildConfig.FULL_VERSION_NAME.ifBlank { BuildConfig.VERSION_NAME }

                    Text(
                        text = stringResource(R.string.version_info_full_version_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = fullVersion,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    VersionLinkButton(
                        title = stringResource(R.string.version_info_github_label),
                        onClick = { openExternalLink(githubUrl) }
                    )
                    VersionLinkButton(
                        title = stringResource(R.string.version_info_website_label),
                        onClick = { openExternalLink(websiteUrl) }
                    )
                    VersionLinkButton(
                        title = stringResource(R.string.version_info_icon_source_label),
                        onClick = { openExternalLink(iconSourceUrl) }
                    )
                    VersionLinkButton(
                        title = stringResource(R.string.version_info_icon_release_label),
                        onClick = { openExternalLink(iconReleaseUrl) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.version_info_simple_icons_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showVersionInfoDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // 预览功能对话框
    if (previewFeaturesExpanded) {
        PreviewFeaturesDialog(
            settings = settings,
            onHideFabOnScrollChanged = viewModel::updateHideFabOnScroll,
            onBitwardenStatusChanged = viewModel::updateBitwardenBottomStatusBarEnabled,
            onReduceAnimationsChanged = viewModel::updateReduceAnimations,
            onDismiss = { previewFeaturesExpanded = false },
        )
    }

    // Clear All Data Confirmation Bottom Sheet
    if (showClearDataDialog) {
        var clearPasswords by remember { mutableStateOf(true) }
        var clearTotp by remember { mutableStateOf(true) }
        var clearNotes by remember { mutableStateOf(true) }
        var clearDocuments by remember { mutableStateOf(true) }
        var clearBankCards by remember { mutableStateOf(true) }
        var clearGeneratorHistory by remember { mutableStateOf(true) }
        
        var verifying by remember { mutableStateOf(false) }
        val manager = remember(context) { takagi.ru.monica.security.SecurityManager(context) }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        fun clearSelectedData() {
            showClearDataDialog = false
            clearDataPasswordInput = ""
            onClearAllData(clearPasswords, clearTotp, clearNotes, clearDocuments, clearBankCards, clearGeneratorHistory)
        }
        ModalBottomSheet(
            onDismissRequest = { showClearDataDialog = false; clearDataPasswordInput = "" },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            takagi.ru.monica.ui.components.ClearDataSheetContent(
                selected = listOf(clearPasswords, clearTotp, clearNotes, clearDocuments, clearBankCards, clearGeneratorHistory),
                onSelection = { index, value -> when (index) {
                    0 -> clearPasswords = value
                    1 -> clearTotp = value
                    2 -> clearNotes = value
                    3 -> clearDocuments = value
                    4 -> clearBankCards = value
                    5 -> clearGeneratorHistory = value
                } },
                password = "", onPassword = { },
                verificationRequired = false,
                verifying = verifying,
                onCancel = { showClearDataDialog = false; clearDataPasswordInput = "" },
                onConfirm = { verifying = true },
            )
        }
        if (verifying) takagi.ru.monica.ui.components.SecondaryPasswordVerificationDialog(
            manager = manager,
            requirePassword = !settings.disablePasswordVerification,
            destructiveConfirm = true,
            confirmText = context.getString(R.string.reset_all_data),
            isRequestCurrent = { showClearDataDialog && verifying },
            onDismiss = { verifying = false },
            onSuccess = { clearSelectedData() },
        )
    }
}

@Composable
private fun PasswordBatchDeleteProgressCard(
    progress: PasswordBatchDeleteGlobalProgressState
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.batch_delete_settings_card_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            if (progress.phase == PasswordBatchDeletePhase.RUNNING) {
                if (progress.processed <= 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { progress.progressFraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = if (progress.phase == PasswordBatchDeletePhase.FAILURE) {
                    if (progress.successCount != null) stringResource(R.string.batch_delete_notification_done_detail_partial, progress.successCount, progress.total - progress.successCount)
                    else stringResource(R.string.batch_delete_interrupted_count, progress.processed, progress.total)
                } else if (progress.phase == PasswordBatchDeletePhase.SUCCESS) {
                    stringResource(R.string.batch_delete_notification_done_detail_success, progress.successCount ?: progress.processed)
                } else if (progress.processed <= 0) {
                    stringResource(R.string.batch_delete_in_progress_preparing)
                } else {
                    stringResource(
                        R.string.batch_delete_in_progress_count,
                        progress.processed,
                        progress.total
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun PasswordBatchTransferProgressCard(
    progress: PasswordBatchTransferGlobalProgressState
) {
    val actionTitleRes = when (progress.action) {
        UnifiedMoveAction.COPY -> R.string.password_batch_transfer_progress_title_copy
        UnifiedMoveAction.MOVE -> R.string.password_batch_transfer_progress_title_move
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.password_batch_transfer_settings_card_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = stringResource(actionTitleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = stringResource(
                    R.string.password_batch_transfer_target,
                    progress.targetLabel
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
            )
            LinearProgressIndicator(
                progress = { progress.progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(
                    R.string.password_batch_transfer_notification_progress,
                    progress.processed,
                    progress.total
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)
        )
        CompositionLocalProvider(LocalSettingsSectionContent provides true) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                content()
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    trailingContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    sectionShape: androidx.compose.ui.graphics.Shape? = null
) {
    val isInSection = LocalSettingsSectionContent.current
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth().settingsSearchAnchor(title)
            .padding(horizontal = if (isInSection) 0.dp else 16.dp, vertical = if (isInSection) 0.dp else 4.dp),
        shape = sectionShape
            ?: if (isInSection) RoundedCornerShape(10.dp) else RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isInSection) {
                MaterialTheme.colorScheme.surfaceContainer
            } else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (trailingContent != null) {
                trailingContent()
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 带开关的设置项组件
 */
@Composable
fun SettingsItemWithSwitch(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    sectionShape: androidx.compose.ui.graphics.Shape? = null,
) {
    val isInSection = LocalSettingsSectionContent.current
    Card(
        modifier = Modifier
            .fillMaxWidth().settingsSearchAnchor(title)
            .padding(horizontal = if (isInSection) 0.dp else 16.dp, vertical = if (isInSection) 0.dp else 4.dp),
        shape = sectionShape ?: if (isInSection) RoundedCornerShape(10.dp) else RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled && isInSection) {
                MaterialTheme.colorScheme.surfaceContainer
            } else if (enabled) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) iconTint else iconTint.copy(alpha = 0.38f),
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) {
                        LocalContentColor.current
                    } else {
                        LocalContentColor.current.copy(alpha = 0.38f)
                    }
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    }
                )
            }
            
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
    }
}

/**
 * 带复选框的行组件
 */
@Composable
fun CheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun BottomNavContentTab.toIcon(): ImageVector = when (this) {
    BottomNavContentTab.VAULT_V2 -> Icons.Default.Home
    BottomNavContentTab.PASSWORDS -> Icons.Default.Lock
    BottomNavContentTab.AUTHENTICATOR -> Icons.Default.Security
    BottomNavContentTab.CARD_WALLET -> Icons.Default.Wallet
    BottomNavContentTab.GENERATOR -> Icons.Default.AutoAwesome
    BottomNavContentTab.NOTES -> Icons.Default.Note
    BottomNavContentTab.PASSKEY -> Icons.Default.Key
    BottomNavContentTab.SEND -> Icons.AutoMirrored.Default.Send
    BottomNavContentTab.STEAM -> SteamDockIcon
}

private fun BottomNavContentTab.toLabelRes(): Int = when (this) {
    BottomNavContentTab.VAULT_V2 -> R.string.nav_v2_vault
    BottomNavContentTab.PASSWORDS -> R.string.nav_passwords
    BottomNavContentTab.AUTHENTICATOR -> R.string.nav_authenticator
    BottomNavContentTab.CARD_WALLET -> R.string.nav_card_wallet
    BottomNavContentTab.GENERATOR -> R.string.nav_generator
    BottomNavContentTab.NOTES -> R.string.nav_notes
    BottomNavContentTab.PASSKEY -> R.string.nav_passkey
    BottomNavContentTab.SEND -> R.string.nav_v2_send
    BottomNavContentTab.STEAM -> R.string.nav_steam
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSelectionSheet(
    currentTheme: ThemeMode,
    oledPureBlackEnabled: Boolean,
    onThemeSelected: (ThemeMode) -> Unit,
    onOledPureBlackChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = context.getString(R.string.theme),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = context.getString(R.string.appearance_sheet_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    ThemeMode.values().forEach { theme ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { onThemeSelected(theme) }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = theme == currentTheme,
                                onClick = { onThemeSelected(theme) }
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(getThemeDisplayName(theme, context))
                                if (theme == ThemeMode.DARK) {
                                    Text(
                                        text = context.getString(R.string.oled_pure_black_dark_mode_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOledPureBlackChanged(!oledPureBlackEnabled) }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = context.getString(R.string.oled_pure_black),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = context.getString(R.string.oled_pure_black_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = oledPureBlackEnabled,
                        onCheckedChange = onOledPureBlackChanged
                    )
                }
            }

            FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(context.getString(R.string.close))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoLockSelectionSheet(
    currentMinutes: Int,
    onMinutesSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val options = listOf(0, 1, 5, 10, 60, 300, 1440, -1) // 0=立即, 1/5/10分钟, 60=1小时, 300=5小时, 1440=1天, -1=从不
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = context.getString(R.string.auto_lock),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                color = MaterialTheme.colorScheme.onSurface
            )
            
            options.forEach { minutes ->
                val isSelected = minutes == currentMinutes
                val containerColor = if (isSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                }
                
                val contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(containerColor)
                        .clickable { onMinutesSelected(minutes) }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when {
                            minutes == 0 -> Icons.Default.LockOpen
                            minutes == -1 -> Icons.Default.Lock
                            minutes >= 1440 -> Icons.Default.Bedtime
                            else -> Icons.Default.Timer
                        },
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(24.dp)
                    )
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Text(
                        text = getAutoLockDisplayName(minutes, context),
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor,
                        modifier = Modifier.weight(1f)
                    )
                    
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = contentColor
                        )
                    }
                }
            }
        }
    }
}

// Helper functions
private fun getThemeDisplayName(theme: ThemeMode, context: android.content.Context): String {
    return when (theme) {
        ThemeMode.SYSTEM -> context.getString(R.string.theme_system)
        ThemeMode.LIGHT -> context.getString(R.string.theme_light)
        ThemeMode.DARK -> context.getString(R.string.theme_dark)
    }
}

private fun getAppearanceDisplayName(
    theme: ThemeMode,
    oledPureBlackEnabled: Boolean,
    context: android.content.Context
): String {
    val themeLabel = getThemeDisplayName(theme, context)
    return if (oledPureBlackEnabled) {
        context.getString(R.string.appearance_with_oled_subtitle, themeLabel)
    } else {
        themeLabel
    }
}

internal fun getAutoLockDisplayName(minutes: Int, context: android.content.Context): String {
    return when (minutes) {
        0 -> context.getString(R.string.auto_lock_immediately)
        1 -> context.getString(R.string.auto_lock_1_minute)
        5 -> context.getString(R.string.auto_lock_5_minutes)
        10 -> context.getString(R.string.auto_lock_10_minutes)
        15 -> context.getString(R.string.auto_lock_15_minutes)
        30 -> context.getString(R.string.auto_lock_30_minutes)
        60 -> context.getString(R.string.auto_lock_1_hour)
        300 -> context.getString(R.string.auto_lock_5_hours)
        1440 -> context.getString(R.string.auto_lock_1_day)
        -1 -> context.getString(R.string.auto_lock_never)
        else -> "$minutes ${context.getString(R.string.auto_lock_5_minutes).substringAfter("5")}"
    }
}

private fun getColorSchemeDisplayName(colorScheme: takagi.ru.monica.data.ColorScheme, context: android.content.Context): String {
    return when (colorScheme) {
        takagi.ru.monica.data.ColorScheme.DEFAULT -> context.getString(R.string.default_color_scheme)
        takagi.ru.monica.data.ColorScheme.OCEAN_BLUE -> context.getString(R.string.ocean_blue_scheme)
        takagi.ru.monica.data.ColorScheme.SUNSET_ORANGE -> context.getString(R.string.sunset_orange_scheme)
        takagi.ru.monica.data.ColorScheme.FOREST_GREEN -> context.getString(R.string.forest_green_scheme)
        takagi.ru.monica.data.ColorScheme.TECH_PURPLE -> context.getString(R.string.tech_purple_scheme)
        takagi.ru.monica.data.ColorScheme.BLACK_MAMBA -> context.getString(R.string.black_mamba_scheme)
        takagi.ru.monica.data.ColorScheme.GREY_STYLE -> context.getString(R.string.grey_style_scheme)
        takagi.ru.monica.data.ColorScheme.WATER_LILIES -> context.getString(R.string.water_lilies_scheme)
        takagi.ru.monica.data.ColorScheme.IMPRESSION_SUNRISE -> context.getString(R.string.impression_sunrise_scheme)
        takagi.ru.monica.data.ColorScheme.JAPANESE_BRIDGE -> context.getString(R.string.japanese_bridge_scheme)
        takagi.ru.monica.data.ColorScheme.HAYSTACKS -> context.getString(R.string.haystacks_scheme)
        takagi.ru.monica.data.ColorScheme.ROUEN_CATHEDRAL -> context.getString(R.string.rouen_cathedral_scheme)
        takagi.ru.monica.data.ColorScheme.PARLIAMENT_FOG -> context.getString(R.string.parliament_fog_scheme)
        takagi.ru.monica.data.ColorScheme.CATPPUCCIN_LATTE -> context.getString(R.string.catppuccin_latte_scheme)
        takagi.ru.monica.data.ColorScheme.CATPPUCCIN_FRAPPE -> context.getString(R.string.catppuccin_frappe_scheme)
        takagi.ru.monica.data.ColorScheme.CATPPUCCIN_MACCHIATO -> context.getString(R.string.catppuccin_macchiato_scheme)
        takagi.ru.monica.data.ColorScheme.CATPPUCCIN_MOCHA -> context.getString(R.string.catppuccin_mocha_scheme)
        takagi.ru.monica.data.ColorScheme.CUSTOM -> context.getString(R.string.custom_color_scheme)
    }
}

private fun getProgressBarStyleDisplayName(style: takagi.ru.monica.data.ProgressBarStyle, context: android.content.Context): String {
    return when (style) {
        takagi.ru.monica.data.ProgressBarStyle.LINEAR -> context.getString(R.string.progress_bar_style_linear)
        takagi.ru.monica.data.ProgressBarStyle.WAVE -> context.getString(R.string.progress_bar_style_wave)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun BottomNavSettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val bottomNavVisibility = settings.bottomNavVisibility
    val listState = rememberLazyListState()
    var localBottomNavOrder by remember(settings.bottomNavOrder) {
        mutableStateOf(settings.bottomNavOrder.filterNot { it == BottomNavContentTab.PASSKEY })
    }
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        localBottomNavOrder = localBottomNavOrder.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        viewModel.updateBottomNavOrder(localBottomNavOrder)
    }

    // 准备共享元素 Modifier
    val sharedTransitionScope = takagi.ru.monica.ui.LocalSharedTransitionScope.current
    val animatedVisibilityScope = takagi.ru.monica.ui.LocalAnimatedVisibilityScope.current
    
    var sharedModifier: Modifier = Modifier
    if (false && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope!!) {
            sharedModifier = Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "bottom_nav_settings_card"),
                animatedVisibilityScope = animatedVisibilityScope!!,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
            )
        }
    }

    Scaffold(
        modifier = sharedModifier,
        topBar = {
            TopAppBar(
                title = { Text(text = context.getString(R.string.bottom_nav_settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = context.getString(R.string.back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Text(
                text = context.getString(R.string.bottom_nav_reorder_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                userScrollEnabled = !reorderableState.isAnyItemDragging,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = localBottomNavOrder,
                    key = { it.name }
                ) { tab ->
                    ReorderableItem(
                        reorderableState,
                        key = tab.name,
                        enabled = true
                    ) { isDragging ->
                        val elevation by androidx.compose.animation.core.animateDpAsState(
                            if (isDragging) 8.dp else 0.dp,
                            label = "bottom_nav_drag_elevation"
                        )
                        val isVisible = if (tab == BottomNavContentTab.AUTHENTICATOR) {
                            bottomNavVisibility.authenticator || bottomNavVisibility.passkey
                        } else {
                            bottomNavVisibility.isVisible(tab)
                        }
                        val switchEnabled = !isVisible || bottomNavVisibility.visibleCount() > 1
                        BottomNavConfigRow(
                            icon = tab.toIcon(),
                            title = context.getString(tab.toLabelRes()),
                            subtitle = context.getString(R.string.bottom_nav_toggle_subtitle),
                            checked = isVisible,
                            switchEnabled = switchEnabled,
                            onCheckedChange = { checked ->
                                viewModel.updateBottomNavVisibility(tab, checked)
                            },
                            dragHandleModifier = Modifier.longPressDraggableHandle(),
                            modifier = Modifier.shadow(elevation)
                        )
                    }
                }

                item(key = "bottom_nav_auto_hide_single_tab") {
                    BottomNavConfigRow(
                        icon = Icons.Default.VisibilityOff,
                        title = context.getString(R.string.bottom_nav_auto_hide_single_tab_title),
                        subtitle = context.getString(R.string.bottom_nav_auto_hide_single_tab_subtitle),
                        checked = settings.autoHideBottomNavWhenSingleTab,
                        switchEnabled = true,
                        onCheckedChange = viewModel::updateAutoHideBottomNavWhenSingleTab,
                        showDragHandle = false,
                    )
                }
            }
        }
    }
}

@Composable
fun ProgressBarStyleDialog(
    currentStyle: takagi.ru.monica.data.ProgressBarStyle,
    onStyleSelected: (takagi.ru.monica.data.ProgressBarStyle) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.validator_progress_bar_style)) },
        text = {
            Column {
                takagi.ru.monica.data.ProgressBarStyle.values().forEach { style ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = style == currentStyle,
                            onClick = { 
                                android.util.Log.d("ProgressBarStyleDialog", "User selected style: $style")
                                onStyleSelected(style)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(getProgressBarStyleDisplayName(style, context))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.ok))
            }
        }
    )
}

@Composable
fun NotificationValidatorCard(
    enabled: Boolean,
    autoMatchEnabled: Boolean,
    selectedId: Long,
    totpItems: List<SecureItem>,
    onEnabledChange: (Boolean) -> Unit,
    onAutoMatchChange: (Boolean) -> Unit,
    onValidatorSelected: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    // If disabled, collapse
    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
            }
        )
    ) {
        Column {
            // Header with Switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (enabled) expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                    modifier = Modifier.size(24.dp)
                )
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.notification_validator_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f)
                    )
                    Text(
                        text = if (enabled) stringResource(R.string.notification_validator_enabled) else stringResource(R.string.notification_validator_disabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
                
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }
            
            // Expanded Content
            MonicaExpandableContent(expanded = expanded && enabled) {
                Column {
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))

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
    }
}

/**
 * 回收站设置项组件
 */
@Composable
private fun SettingsItemWithTrashConfig(
    trashEnabled: Boolean,
    trashAutoDeleteDays: Int,
    onTrashEnabledChange: (Boolean) -> Unit,
    onAutoDeleteDaysChange: (Int) -> Unit,
    sectionShape: androidx.compose.ui.graphics.Shape? = null
) {
    var showTrashSettingsSheet by remember { mutableStateOf(false) }
    
    val subtitleText = if (trashEnabled) {
        if (trashAutoDeleteDays > 0) {
            stringResource(R.string.trash_status_enabled_auto_clear, trashAutoDeleteDays)
        } else {
            stringResource(R.string.trash_status_enabled_no_auto_clear)
        }
    } else {
        stringResource(R.string.trash_status_disabled_permanent_delete)
    }

    SettingsItem(
        icon = Icons.Default.Delete,
        title = stringResource(R.string.trash_bin),
        subtitle = subtitleText,
        onClick = { showTrashSettingsSheet = true },
        sectionShape = sectionShape
    )

    if (showTrashSettingsSheet) {
        TrashSettingsSheet(
            currentSettings = takagi.ru.monica.viewmodel.TrashSettings(trashEnabled, trashAutoDeleteDays),
            onDismiss = { showTrashSettingsSheet = false },
            onConfirm = { enabled, days ->
                onTrashEnabledChange(enabled)
                onAutoDeleteDaysChange(days)
                showTrashSettingsSheet = false
            }
        )
    }
}

/**
 * 常用账号信息卡片（折叠式）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommonAccountCard() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val commonAccountPreferences = remember { takagi.ru.monica.data.CommonAccountPreferences(context) }
    
    val commonInfo by commonAccountPreferences.commonAccountInfo.collectAsState(
        initial = takagi.ru.monica.data.CommonAccountInfo()
    )
    
    var expanded by remember { mutableStateOf(false) }
    var email by remember(commonInfo.email) { mutableStateOf(commonInfo.email) }
    var phone by remember(commonInfo.phone) { mutableStateOf(commonInfo.phone) }
    var username by remember(commonInfo.username) { mutableStateOf(commonInfo.username) }
    var autoFillEnabled by remember(commonInfo.autoFillEnabled) { mutableStateOf(commonInfo.autoFillEnabled) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = context.getString(R.string.common_account_title),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (commonInfo.hasAnyInfo()) 
                            context.getString(R.string.common_account_configured) 
                        else 
                            context.getString(R.string.common_account_not_configured),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                MonicaExpansionChevron(
                    expanded = expanded,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Expanded Content
            MonicaExpandableContent(expanded = expanded) {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = context.getString(R.string.common_account_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 常用邮箱
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text(context.getString(R.string.common_account_email)) },
                            placeholder = { Text("name@example.com") },
                            leadingIcon = { Icon(Icons.Default.Email, null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        // 常用手机号
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 15) phone = it },
                            label = { Text(context.getString(R.string.common_account_phone)) },
                            placeholder = { Text("13800000000") },
                            leadingIcon = { Icon(Icons.Default.Phone, null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                        )

                        // 常用用户名
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text(context.getString(R.string.common_account_username)) },
                            placeholder = { Text(context.getString(R.string.common_account_username_hint)) },
                            leadingIcon = { Icon(Icons.Default.AccountCircle, null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        // 自动填入开关
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { autoFillEnabled = !autoFillEnabled }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = autoFillEnabled,
                                onCheckedChange = { autoFillEnabled = it }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = context.getString(R.string.common_account_auto_fill),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = context.getString(R.string.common_account_auto_fill_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 保存按钮
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    commonAccountPreferences.setDefaultEmail(email)
                                    commonAccountPreferences.setDefaultPhone(phone)
                                    commonAccountPreferences.setDefaultUsername(username)
                                    commonAccountPreferences.setAutoFillEnabled(autoFillEnabled)
                                    Toast.makeText(context, context.getString(R.string.common_account_saved), Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.align(Alignment.End),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(context.getString(R.string.save))
                        }
                    }
                }
            }
        }
    }
}

