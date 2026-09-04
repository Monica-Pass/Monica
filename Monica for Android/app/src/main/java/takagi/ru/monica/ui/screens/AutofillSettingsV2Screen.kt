package takagi.ru.monica.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DoNotDisturb
import androidx.compose.material.icons.outlined.Input
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import takagi.ru.monica.ui.components.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.AutofillPreferences
import takagi.ru.monica.autofill_ng.DomainMatchStrategy
import takagi.ru.monica.autofill_ng.core.AutofillServiceChecker
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.ui.components.AppInfo
import takagi.ru.monica.ui.components.loadInstalledApps
import takagi.ru.monica.ui.components.AppIcon
import takagi.ru.monica.utils.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutofillSettingsV2Screen(
    onNavigateBack: () -> Unit,
    onNavigateToBlockedFields: () -> Unit,
    onNavigateToSaveBlockedTargets: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val preferences = remember { AutofillPreferences(context) }
    val settingsManager = remember { SettingsManager(context) }
    val serviceChecker = remember { AutofillServiceChecker(context) }
    val localKeepassDao = remember(context.applicationContext) {
        PasswordDatabase.getDatabase(context.applicationContext).localKeePassDatabaseDao()
    }
    val bitwardenVaultDao = remember(context.applicationContext) {
        PasswordDatabase.getDatabase(context.applicationContext).bitwardenVaultDao()
    }

    val autofillEnabled by preferences.isAutofillEnabled.collectAsState(initial = true)
    val activeFillNotificationEnabled by preferences.isActiveFillNotificationEnabled.collectAsState(initial = false)
    val appSettings by settingsManager.settingsFlow.collectAsState(initial = AppSettings())
    val autofillAuthRequired = appSettings.autofillAuthRequired
    val strictMode by preferences.isBitwardenStrictModeEnabled.collectAsState(initial = true)
    val subdomainMatch by preferences.isBitwardenSubdomainMatchEnabled.collectAsState(initial = true)
    val domainMatchStrategy by preferences.domainMatchStrategy.collectAsState(initial = DomainMatchStrategy.BASE_DOMAIN)
    val savePromptEnabled by preferences.isRequestSaveDataEnabled.collectAsState(initial = true)
    val autoUpdateDuplicatePasswordsEnabled by preferences.isAutoUpdateDuplicatePasswordsEnabled.collectAsState(initial = false)
    val showSaveNotificationEnabled by preferences.isShowSaveNotificationEnabled.collectAsState(initial = true)
    val smartTitleGenerationEnabled by preferences.isSmartTitleGenerationEnabled.collectAsState(initial = true)
    val autoSaveAppInfoEnabled by preferences.isAutoSaveAppInfoEnabled.collectAsState(initial = true)
    val autoSaveWebsiteInfoEnabled by preferences.isAutoSaveWebsiteInfoEnabled.collectAsState(initial = true)
    val showOtpNotificationEnabled by preferences.isOtpNotificationEnabled.collectAsState(initial = false)
    val otpNotificationDurationSeconds by preferences.otpNotificationDuration.collectAsState(initial = 30)
    val autoCopyOtpEnabled by preferences.isAutoCopyOtpEnabled.collectAsState(initial = false)
    val respectOffEnabled by preferences.isV2RespectAutofillOffEnabled.collectAsState(initial = true)
    val inlineSuggestionsEnabled by preferences.isInlineSuggestionsEnabled.collectAsState(initial = true)
    val passwordSuggestionEnabled by preferences.isPasswordSuggestionEnabled.collectAsState(initial = true)
    val blacklistEnabled by preferences.isBlacklistEnabled.collectAsState(initial = true)
    val blacklistPackages by preferences.blacklistPackages.collectAsState(initial = emptySet())
    val saveBlockedTargetRecords by preferences.saveBlockedTargetRecords.collectAsState(initial = emptyList())
    val blockedFieldSignatureRecords by preferences.blockedFieldSignatureRecords.collectAsState(initial = emptyList())
    val defaultSourceFilter by preferences.v2DefaultSourceFilter.collectAsState(
        initial = AutofillPreferences.AutofillDefaultSourceFilter.ALL,
    )
    val defaultKeepassDatabaseId by preferences.v2DefaultKeepassDatabaseId.collectAsState(initial = null)
    val keepassDatabases by localKeepassDao.getAllDatabases().collectAsState(initial = emptyList())
    val defaultBitwardenVaultId by preferences.v2DefaultBitwardenVaultId.collectAsState(initial = null)
    val bitwardenVaults by bitwardenVaultDao.getAllVaultsFlow().collectAsState(initial = emptyList())

    var serviceStatus by remember { mutableStateOf<AutofillServiceChecker.ServiceStatus?>(null) }
    var sourceFilterMenuExpanded by remember { mutableStateOf(false) }
    var keepassDbMenuExpanded by remember { mutableStateOf(false) }
    var bitwardenVaultMenuExpanded by remember { mutableStateOf(false) }
    var showDomainStrategyDialog by remember { mutableStateOf(false) }
    var showBlacklistDialog by remember { mutableStateOf(false) }
    var showOtpDurationDialog by remember { mutableStateOf(false) }

    fun refreshStatus() {
        serviceStatus = serviceChecker.checkServiceStatus()
    }

    fun openSystemAutofillSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } else {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    fun openSystemKeyboardSettings() {
        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    LaunchedEffect(Unit) {
        preferences.ensureBitwardenV2EngineMode()
        refreshStatus()
    }

    LaunchedEffect(defaultKeepassDatabaseId, keepassDatabases) {
        val selected = defaultKeepassDatabaseId ?: return@LaunchedEffect
        if (keepassDatabases.none { it.id == selected }) {
            preferences.setV2DefaultKeepassDatabaseId(null)
        }
    }

    LaunchedEffect(defaultBitwardenVaultId, bitwardenVaults) {
        val selected = defaultBitwardenVaultId ?: return@LaunchedEffect
        if (bitwardenVaults.none { it.id == selected }) {
            preferences.setV2DefaultBitwardenVaultId(null)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.autofill_v2_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.autofill_settings_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = ::refreshStatus) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.autofill_settings_refresh),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val statusEnabled = serviceStatus?.let { status ->
                status.isSystemEnabled && status.isAppEnabled
            } ?: false

            val statusContainerColor = if (statusEnabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
            val statusContentColor = if (statusEnabled) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            }
            val statusIconTint = if (statusEnabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = statusContainerColor,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Input,
                            contentDescription = null,
                            tint = statusIconTint,
                        )
                        Text(
                            text = if (statusEnabled) {
                                stringResource(R.string.autofill_status_enabled)
                            } else {
                                stringResource(R.string.autofill_status_disabled)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = statusContentColor,
                        )
                    }
                    Text(
                        text = if (statusEnabled) {
                            stringResource(R.string.autofill_status_enabled_desc)
                        } else {
                            stringResource(R.string.autofill_status_disabled_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = statusContentColor.copy(alpha = 0.9f),
                    )
                    serviceStatus?.let { status ->
                        Text(
                            text = status.getSummary(context),
                            style = MaterialTheme.typography.bodySmall,
                            color = statusContentColor.copy(alpha = 0.9f),
                        )
                    }
                    TextButton(onClick = ::openSystemAutofillSettings) {
                        Text(stringResource(R.string.autofill_v2_set_system_service))
                    }
                }
            }

            SectionCard(
                title = stringResource(R.string.autofill_v2_default_scope_title),
                icon = Icons.Outlined.AccountTree,
                iconTint = MaterialTheme.colorScheme.tertiary,
            ) {
                Text(
                    text = stringResource(R.string.autofill_v2_default_scope_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))

                ExposedDropdownMenuBox(
                    expanded = sourceFilterMenuExpanded,
                    onExpandedChange = { sourceFilterMenuExpanded = !sourceFilterMenuExpanded },
                ) {
                    OutlinedTextField(
                        value = defaultSourceFilter.label(),
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceFilterMenuExpanded)
                        },
                        shape = RoundedCornerShape(14.dp),
                    )
                    ExposedDropdownMenu(
                        expanded = sourceFilterMenuExpanded,
                        onDismissRequest = { sourceFilterMenuExpanded = false },
                    ) {
                        AutofillPreferences.AutofillDefaultSourceFilter.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label()) },
                                onClick = {
                                    sourceFilterMenuExpanded = false
                                    scope.launch {
                                        preferences.setV2DefaultSourceFilter(option)
                                        if (option != AutofillPreferences.AutofillDefaultSourceFilter.KEEPASS) {
                                            preferences.setV2DefaultKeepassDatabaseId(null)
                                        }
                                        if (option != AutofillPreferences.AutofillDefaultSourceFilter.BITWARDEN) {
                                            preferences.setV2DefaultBitwardenVaultId(null)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                if (defaultSourceFilter == AutofillPreferences.AutofillDefaultSourceFilter.KEEPASS) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    Text(
                        text = stringResource(R.string.autofill_v2_default_keepass_title),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    ExposedDropdownMenuBox(
                        expanded = keepassDbMenuExpanded,
                        onExpandedChange = { keepassDbMenuExpanded = !keepassDbMenuExpanded },
                    ) {
                        val selectedDatabaseName = defaultKeepassDatabaseId
                            ?.let { selectedId -> keepassDatabases.firstOrNull { it.id == selectedId }?.name }
                            ?: stringResource(R.string.password_picker_all_databases)
                        OutlinedTextField(
                            value = selectedDatabaseName,
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = keepassDbMenuExpanded)
                            },
                            shape = RoundedCornerShape(14.dp),
                        )
                        ExposedDropdownMenu(
                            expanded = keepassDbMenuExpanded,
                            onDismissRequest = { keepassDbMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.password_picker_all_databases)) },
                                onClick = {
                                    keepassDbMenuExpanded = false
                                    scope.launch { preferences.setV2DefaultKeepassDatabaseId(null) }
                                },
                            )
                            keepassDatabases.forEach { database ->
                                DropdownMenuItem(
                                    text = { Text(database.name) },
                                    onClick = {
                                        keepassDbMenuExpanded = false
                                        scope.launch { preferences.setV2DefaultKeepassDatabaseId(database.id) }
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.autofill_v2_default_keepass_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }

                if (defaultSourceFilter == AutofillPreferences.AutofillDefaultSourceFilter.BITWARDEN) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    Text(
                        text = stringResource(R.string.autofill_v2_default_bitwarden_title),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    ExposedDropdownMenuBox(
                        expanded = bitwardenVaultMenuExpanded,
                        onExpandedChange = { bitwardenVaultMenuExpanded = !bitwardenVaultMenuExpanded },
                    ) {
                        val selectedVaultName = defaultBitwardenVaultId
                            ?.let { selectedId ->
                                bitwardenVaults.firstOrNull { it.id == selectedId }?.displayLabel()
                            }
                            ?: stringResource(R.string.password_picker_all_vaults)
                        OutlinedTextField(
                            value = selectedVaultName,
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = bitwardenVaultMenuExpanded)
                            },
                            shape = RoundedCornerShape(14.dp),
                        )
                        ExposedDropdownMenu(
                            expanded = bitwardenVaultMenuExpanded,
                            onDismissRequest = { bitwardenVaultMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.password_picker_all_vaults)) },
                                onClick = {
                                    bitwardenVaultMenuExpanded = false
                                    scope.launch { preferences.setV2DefaultBitwardenVaultId(null) }
                                },
                            )
                            bitwardenVaults.forEach { vault ->
                                DropdownMenuItem(
                                    text = { Text(vault.displayLabel()) },
                                    onClick = {
                                        bitwardenVaultMenuExpanded = false
                                        scope.launch { preferences.setV2DefaultBitwardenVaultId(vault.id) }
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.autofill_v2_default_bitwarden_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
            }

            SectionCard(
                title = stringResource(R.string.autofill_system_settings_title),
                icon = Icons.Outlined.Settings,
                iconTint = MaterialTheme.colorScheme.primary,
            ) {
                AutofillSettingItem(
                    icon = Icons.Outlined.Smartphone,
                    title = stringResource(R.string.autofill_v2_set_system_service),
                    subtitle = stringResource(R.string.autofill_v2_set_system_service_desc),
                    onClick = ::openSystemAutofillSettings,
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                AutofillSettingItem(
                    icon = Icons.Outlined.Key,
                    title = stringResource(R.string.autofill_system_passkey_settings),
                    subtitle = stringResource(R.string.autofill_system_passkey_settings_desc),
                    onClick = { context.startActivity(Intent(Settings.ACTION_SETTINGS)) },
                )
            }

            SectionCard(
                title = stringResource(R.string.ime_settings_title),
                icon = Icons.Outlined.Keyboard,
                iconTint = MaterialTheme.colorScheme.tertiary,
            ) {
                AutofillSettingItem(
                    icon = Icons.Outlined.Settings,
                    title = stringResource(R.string.ime_manage_input_methods),
                    subtitle = stringResource(R.string.ime_manage_input_methods_desc),
                    onClick = ::openSystemKeyboardSettings,
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

            SectionCard(
                title = stringResource(R.string.autofill_fill_behavior_title),
                icon = Icons.Outlined.Input,
                iconTint = MaterialTheme.colorScheme.secondary,
            ) {
                SwitchSettingItem(
                    icon = Icons.Outlined.Input,
                    title = stringResource(R.string.autofill_v2_enable_service),
                    subtitle = stringResource(R.string.autofill_v2_enable_service_desc),
                    checked = autofillEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setAutofillEnabled(enabled) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.Notifications,
                    title = stringResource(R.string.autofill_active_fill_notification_title),
                    subtitle = stringResource(R.string.autofill_active_fill_notification_desc),
                    checked = activeFillNotificationEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setActiveFillNotificationEnabled(enabled) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.autofill_auth_required),
                    subtitle = stringResource(R.string.autofill_auth_required_desc),
                    checked = autofillAuthRequired,
                    onCheckedChange = { enabled ->
                        scope.launch { settingsManager.updateAutofillAuthRequired(enabled) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.Link,
                    title = stringResource(R.string.autofill_v2_strict_match),
                    subtitle = stringResource(R.string.autofill_v2_strict_match_desc),
                    checked = strictMode,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setBitwardenStrictModeEnabled(enabled) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.AccountTree,
                    title = stringResource(R.string.autofill_v2_subdomain_match),
                    subtitle = stringResource(R.string.autofill_v2_subdomain_match_desc),
                    checked = subdomainMatch,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setBitwardenSubdomainMatchEnabled(enabled) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                AutofillSettingItem(
                    icon = Icons.Outlined.Language,
                    title = stringResource(R.string.autofill_domain_strategy_title),
                    subtitle = DomainMatchStrategy.getDisplayName(context, domainMatchStrategy),
                    onClick = { showDomainStrategyDialog = true },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.DoNotDisturb,
                    title = stringResource(R.string.autofill_v2_respect_off),
                    subtitle = stringResource(R.string.autofill_v2_respect_off_desc),
                    checked = respectOffEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setV2RespectAutofillOffEnabled(enabled) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.Input,
                    title = stringResource(R.string.autofill_inline_suggestions),
                    subtitle = stringResource(R.string.autofill_inline_suggestions_desc),
                    checked = inlineSuggestionsEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setInlineSuggestionsEnabled(enabled) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.AutoAwesome,
                    title = stringResource(R.string.password_suggestion_title),
                    subtitle = stringResource(R.string.autofill_password_suggestion_setting_desc),
                    checked = passwordSuggestionEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setPasswordSuggestionEnabled(enabled) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.AddCircleOutline,
                    title = stringResource(R.string.autofill_save_enable),
                    subtitle = stringResource(R.string.autofill_save_enable_desc),
                    checked = savePromptEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setRequestSaveDataEnabled(enabled) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.Refresh,
                    title = stringResource(R.string.autofill_save_update_duplicate),
                    subtitle = stringResource(R.string.autofill_save_update_duplicate_desc),
                    checked = autoUpdateDuplicatePasswordsEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setAutoUpdateDuplicatePasswordsEnabled(enabled) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.Settings,
                    title = stringResource(R.string.autofill_save_show_notification),
                    subtitle = stringResource(R.string.autofill_save_show_notification_desc),
                    checked = showSaveNotificationEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setShowSaveNotificationEnabled(enabled) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.Link,
                    title = stringResource(R.string.autofill_save_smart_title),
                    subtitle = stringResource(R.string.autofill_save_smart_title_desc),
                    checked = smartTitleGenerationEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setSmartTitleGenerationEnabled(enabled) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.Apps,
                    title = stringResource(R.string.autofill_save_app_info),
                    subtitle = stringResource(R.string.autofill_save_app_info_desc),
                    checked = autoSaveAppInfoEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setAutoSaveAppInfoEnabled(enabled) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.Language,
                    title = stringResource(R.string.autofill_save_website_info),
                    subtitle = stringResource(R.string.autofill_save_website_info_desc),
                    checked = autoSaveWebsiteInfoEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setAutoSaveWebsiteInfoEnabled(enabled) }
                    },
                )
            }

            SectionCard(
                title = stringResource(R.string.autofill_otp_settings_title),
                icon = Icons.Outlined.Key,
                iconTint = MaterialTheme.colorScheme.tertiary,
            ) {
                SwitchSettingItem(
                    icon = Icons.Outlined.Smartphone,
                    title = stringResource(R.string.autofill_show_otp_notification),
                    subtitle = stringResource(R.string.autofill_show_otp_notification_desc),
                    checked = showOtpNotificationEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setOtpNotificationEnabled(enabled) }
                        if (enabled) {
                            val notificationIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            try {
                                context.startActivity(notificationIntent)
                            } catch (_: Exception) {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    },
                                )
                            }
                        }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                AutofillSettingItem(
                    icon = Icons.Outlined.Settings,
                    title = stringResource(R.string.autofill_otp_notification_duration),
                    subtitle = "${stringResource(R.string.autofill_otp_notification_duration_desc)}: ${otpNotificationDurationSeconds}s",
                    onClick = {
                        showOtpDurationDialog = true
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchSettingItem(
                    icon = Icons.Outlined.ContentCopy,
                    title = stringResource(R.string.autofill_auto_copy_otp),
                    subtitle = stringResource(R.string.autofill_auto_copy_otp_desc),
                    checked = autoCopyOtpEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setAutoCopyOtpEnabled(enabled) }
                    },
                )
            }

            SectionCard(
                title = stringResource(R.string.autofill_save_blocked_targets_title),
                icon = Icons.Outlined.Language,
                iconTint = MaterialTheme.colorScheme.secondary,
            ) {
                AutofillSettingItem(
                    icon = Icons.Outlined.Block,
                    title = stringResource(R.string.autofill_save_blocked_targets_manage),
                    subtitle = stringResource(
                        R.string.autofill_save_blocked_targets_manage_desc,
                        saveBlockedTargetRecords.size,
                    ),
                    onClick = onNavigateToSaveBlockedTargets,
                )
            }

            SectionCard(
                title = stringResource(R.string.autofill_blacklist_title),
                icon = Icons.Outlined.Block,
                iconTint = MaterialTheme.colorScheme.error,
            ) {
                SwitchSettingItem(
                    icon = Icons.Outlined.Block,
                    title = stringResource(R.string.autofill_v2_blacklist),
                    subtitle = stringResource(R.string.autofill_v2_blacklist_desc, blacklistPackages.size),
                    checked = blacklistEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setBlacklistEnabled(enabled) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                AutofillSettingItem(
                    icon = Icons.Outlined.Apps,
                    title = stringResource(R.string.autofill_blacklist_manage),
                    subtitle = stringResource(R.string.autofill_blacklist_manage_desc, blacklistPackages.size),
                    onClick = { showBlacklistDialog = true },
                )
            }

            SectionCard(
                title = stringResource(R.string.autofill_blocked_fields_title),
                icon = Icons.Outlined.DoNotDisturb,
                iconTint = MaterialTheme.colorScheme.secondary,
            ) {
                AutofillSettingItem(
                    icon = Icons.Outlined.DoNotDisturb,
                    title = stringResource(R.string.autofill_blocked_fields_manage),
                    subtitle = stringResource(
                        R.string.autofill_blocked_fields_manage_desc,
                        blockedFieldSignatureRecords.size,
                    ),
                    onClick = onNavigateToBlockedFields,
                )
            }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Text(
                    text = stringResource(R.string.autofill_v2_freeze_notice),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        if (showDomainStrategyDialog) {
            DomainStrategyDialog(
                currentStrategy = domainMatchStrategy,
                onStrategySelected = { strategy ->
                    scope.launch { preferences.setDomainMatchStrategy(strategy) }
                },
                onDismiss = { showDomainStrategyDialog = false },
            )
        }

        if (showBlacklistDialog) {
            V2BlacklistManagementDialog(
                blacklistPackages = blacklistPackages,
                onDismiss = { showBlacklistDialog = false },
                onPackageToggle = { packageName, shouldBlock ->
                    scope.launch {
                        if (shouldBlock) {
                            preferences.addToBlacklist(packageName)
                        } else {
                            preferences.removeFromBlacklist(packageName)
                        }
                    }
                },
            )
        }

        if (showOtpDurationDialog) {
            OtpDurationDialog(
                currentDuration = otpNotificationDurationSeconds,
                onDurationSelected = { duration ->
                    scope.launch { preferences.setOtpNotificationDuration(duration) }
                    showOtpDurationDialog = false
                },
                onDismiss = { showOtpDurationDialog = false },
            )
        }
    }
}

private fun BitwardenVault.displayLabel(): String {
    return displayName
        ?.takeIf { it.isNotBlank() }
        ?.let { "$it ($email)" }
        ?: email
}

@Composable
private fun AutofillPreferences.AutofillDefaultSourceFilter.label(): String {
    return when (this) {
        AutofillPreferences.AutofillDefaultSourceFilter.ALL -> stringResource(R.string.filter_all)
        AutofillPreferences.AutofillDefaultSourceFilter.LOCAL -> stringResource(R.string.filter_monica)
        AutofillPreferences.AutofillDefaultSourceFilter.KEEPASS -> stringResource(R.string.filter_keepass)
        AutofillPreferences.AutofillDefaultSourceFilter.BITWARDEN -> stringResource(R.string.filter_bitwarden)
    }
}

@Composable
private fun DomainStrategyDialog(
    currentStrategy: DomainMatchStrategy,
    onStrategySelected: (DomainMatchStrategy) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.autofill_domain_strategy_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DomainMatchStrategy.values().forEach { strategy ->
                    val selected = strategy == currentStrategy
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                            .clickable { onStrategySelected(strategy) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { onStrategySelected(strategy) },
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 4.dp),
                            ) {
                                Text(
                                    text = DomainMatchStrategy.getDisplayName(context, strategy),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = DomainMatchStrategy.getDescription(context, strategy),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.autofill_domain_strategy_dialog_close))
            }
        },
    )
}

@Composable
private fun V2BlacklistManagementDialog(
    blacklistPackages: Set<String>,
    onDismiss: () -> Unit,
    onPackageToggle: (String, Boolean) -> Unit,
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
        // Blocked apps are the active items in this management view; keep them
        // visible at the top while retaining alphabetical order within each group.
        matchingApps.sortedWith(
            compareByDescending<AppInfo> { blacklistPackages.contains(it.packageName) }
                .thenBy { it.appName.lowercase(java.util.Locale.getDefault()) }
                .thenBy { it.packageName.lowercase(java.util.Locale.getDefault()) }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.autofill_blacklist_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.autofill_blacklist_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.search_apps)) },
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
                                stringResource(R.string.autofill_blacklist_no_apps)
                            } else {
                                stringResource(R.string.autofill_blacklist_no_match, searchQuery)
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
                            val checked = blacklistPackages.contains(app.packageName)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPackageToggle(app.packageName, !checked) },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (checked)
                                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                    else
                                        MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AppIcon(app = app, modifier = Modifier.size(36.dp))

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = app.appName,
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Text(
                                            text = app.packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = checked,
                                        onCheckedChange = { enabled ->
                                            onPackageToggle(app.packageName, enabled)
                                        },
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
                Text(text = stringResource(R.string.autofill_blacklist_dialog_done))
            }
        },
    )
}

@Composable
private fun OtpDurationDialog(
    currentDuration: Int,
    onDurationSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(15, 30, 60, 120)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.autofill_otp_notification_duration)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.autofill_otp_notification_duration_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                options.forEach { duration ->
                    val selected = duration == currentDuration
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDurationSelected(duration) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { onDurationSelected(duration) },
                            )
                            Text(
                                text = "${duration}s",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.autofill_domain_strategy_dialog_close))
            }
        },
    )
}

