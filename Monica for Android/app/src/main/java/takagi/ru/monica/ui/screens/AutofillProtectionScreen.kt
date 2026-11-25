package takagi.ru.monica.ui.screens

import takagi.ru.monica.ui.haptic.hapticSwitchToggleable

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import takagi.ru.monica.ui.haptic.MonicaSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import takagi.ru.monica.autofill_ng.privilege.PrivilegeAccess
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.AutofillPreferences
import takagi.ru.monica.autofill_ng.protection.AccessibilityConnection
import takagi.ru.monica.autofill_ng.protection.AccessibilityRecoveryResult
import takagi.ru.monica.autofill_ng.protection.AutofillProtection
import takagi.ru.monica.autofill_ng.protection.AutofillProtectionSettings
import takagi.ru.monica.autofill_ng.protection.AutofillProtectionState
import takagi.ru.monica.autofill_ng.protection.ProtectionRuntime
import takagi.ru.monica.autofill_ng.protection.ProtectionSettingsDestination
import takagi.ru.monica.autofill_ng.protection.ShizukuAccess
import takagi.ru.monica.autofill_ng.protection.ShizukuAccessibilityRecovery

internal sealed interface ProtectionEvent {
    data object Refresh : ProtectionEvent
    data object RetryBackground : ProtectionEvent
    data object RecoverAccessibility : ProtectionEvent
    data class Background(val enabled: Boolean) : ProtectionEvent
    data class EnhancedRecovery(val enabled: Boolean) : ProtectionEvent
    data class ProactiveFill(val enabled: Boolean) : ProtectionEvent
    data class OpenSettings(val destination: ProtectionSettingsDestination) : ProtectionEvent
}

@Composable
fun AutofillProtectionScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val state by remember(context) { AutofillProtection.observe(context) }
        .collectAsStateWithLifecycle(initialValue = AutofillProtection.snapshot(context))
    val preferences = remember(context) { AutofillPreferences(context.applicationContext) }
    val proactiveFill by preferences.isActiveFillNotificationEnabled.collectAsStateWithLifecycle(initialValue = false)
    var showRiskDialog by rememberSaveable { mutableStateOf(false) }
    var awaitingShizukuPermission by rememberSaveable { mutableStateOf(false) }
    var recovering by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        AutofillProtection.refresh()
    }

    fun message(resource: Int) { scope.launch { snackbar.showSnackbar(context.getString(resource)) } }
    fun openSettings(destination: ProtectionSettingsDestination) {
        if (!AutofillProtectionSettings.open(context, destination)) message(R.string.autofill_protection_settings_unavailable)
    }
    fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) AutofillProtection.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(context) {
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grant ->
            if (requestCode == ShizukuAccessibilityRecovery.PERMISSION_REQUEST && awaitingShizukuPermission && !AutofillProtection.snapshot(context).useRoot) {
                awaitingShizukuPermission = false
                if (grant == PackageManager.PERMISSION_GRANTED && ShizukuAccessibilityRecovery.access() == ShizukuAccess.ADB_AUTHORIZED) {
                    AutofillProtection.setEnhancedRecoveryEnabled(context, true)
                } else {
                    message(R.string.autofill_protection_shizuku_denied)
                }
                AutofillProtection.refresh()
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    AutofillProtectionContent(
        state = state,
        proactiveFill = proactiveFill,
        recovering = recovering,
        snackbar = snackbar,
        onNavigateBack = onNavigateBack,
        onEvent = { event ->
            when (event) {
                ProtectionEvent.Refresh -> AutofillProtection.refresh()
                ProtectionEvent.RetryBackground -> if (!AutofillProtection.restoreIfEnabled(context)) {
                    message(R.string.autofill_protection_start_failed)
                }
                is ProtectionEvent.Background -> {
                    if (!AutofillProtection.setEnabled(context, event.enabled)) message(R.string.autofill_protection_start_failed)
                    if (event.enabled) requestNotificationsIfNeeded()
                    if (!event.enabled) awaitingShizukuPermission = false
                }
                is ProtectionEvent.EnhancedRecovery -> {
                    if (event.enabled) {
                        if (state.useRoot) {
                            if (state.rootAuthorized) AutofillProtection.setEnhancedRecoveryEnabled(context, true)
                            else message(R.string.privilege_root_not_authorized)
                        } else showRiskDialog = true
                    } else {
                        awaitingShizukuPermission = false
                        AutofillProtection.setEnhancedRecoveryEnabled(context, false)
                    }
                }
                is ProtectionEvent.ProactiveFill -> {
                    scope.launch { preferences.setActiveFillNotificationEnabled(event.enabled) }
                    if (event.enabled) requestNotificationsIfNeeded()
                }
                is ProtectionEvent.OpenSettings -> openSettings(event.destination)
                ProtectionEvent.RecoverAccessibility -> if (!recovering) {
                    scope.launch {
                        recovering = true
                        try {
                            val result = ShizukuAccessibilityRecovery.recover(context)
                            message(when (result) {
                                AccessibilityRecoveryResult.CONNECTED -> R.string.autofill_protection_recovery_success
                                AccessibilityRecoveryResult.NOT_ENABLED -> R.string.autofill_protection_recovery_disabled
                                AccessibilityRecoveryResult.SETTINGS_CHANGED -> R.string.autofill_protection_recovery_settings_changed
                                AccessibilityRecoveryResult.NOT_AUTHORIZED -> if (state.useRoot) R.string.privilege_root_not_authorized else R.string.autofill_protection_shizuku_denied
                                AccessibilityRecoveryResult.COOLDOWN -> R.string.autofill_protection_recovery_cooldown
                                else -> R.string.autofill_protection_recovery_failed
                            })
                        } finally {
                            recovering = false
                            AutofillProtection.refresh()
                        }
                    }
                }
            }
        },
    )

    if (showRiskDialog) {
        ShizukuRecoveryRiskDialog(
            onDismiss = { showRiskDialog = false },
            onConfirm = {
                showRiskDialog = false
                when (ShizukuAccessibilityRecovery.access()) {
                    ShizukuAccess.ADB_AUTHORIZED -> AutofillProtection.setEnhancedRecoveryEnabled(context, true)
                    ShizukuAccess.NEEDS_PERMISSION -> {
                        awaitingShizukuPermission = true
                        if (!ShizukuAccessibilityRecovery.requestPermission()) {
                            awaitingShizukuPermission = false
                            message(R.string.autofill_protection_shizuku_denied)
                            openSettings(ProtectionSettingsDestination.SHIZUKU)
                        }
                    }
                    else -> openSettings(ProtectionSettingsDestination.SHIZUKU)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutofillProtectionContent(
    state: AutofillProtectionState,
    proactiveFill: Boolean,
    recovering: Boolean,
    snackbar: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onEvent: (ProtectionEvent) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.autofill_protection_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Outlined.ArrowBack, stringResource(R.string.autofill_settings_back))
                    }
                },
                actions = {
                    IconButton(onClick = { onEvent(ProtectionEvent.Refresh) }) {
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.autofill_settings_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp).testTag("protection-content"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val connected = state.accessibility == AccessibilityConnection.CONNECTED
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.AccessibilityNew, null)
                        Text(
                            stringResource(when (state.accessibility) {
                                AccessibilityConnection.CONNECTED -> R.string.autofill_protection_accessibility_connected
                                AccessibilityConnection.DISCONNECTED -> R.string.autofill_protection_accessibility_disconnected
                                AccessibilityConnection.DISABLED -> R.string.autofill_protection_accessibility_disabled
                            }),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("accessibility-status"),
                        )
                    }
                    Text(
                        stringResource(when (state.accessibility) {
                            AccessibilityConnection.CONNECTED -> R.string.autofill_protection_accessibility_connected_desc
                            AccessibilityConnection.DISCONNECTED -> R.string.autofill_protection_accessibility_disconnected_desc
                            AccessibilityConnection.DISABLED -> R.string.autofill_protection_accessibility_disabled_desc
                        }), style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { onEvent(ProtectionEvent.OpenSettings(ProtectionSettingsDestination.ACCESSIBILITY)) }) {
                        Text(stringResource(R.string.autofill_protection_accessibility_settings))
                    }
                }
            }

            ProtectionCard {
                ProtectionSwitchRow(
                    icon = Icons.Outlined.Security,
                    title = stringResource(R.string.autofill_protection_background_title),
                    description = stringResource(R.string.autofill_protection_background_desc),
                    checked = state.backgroundEnabled,
                    tag = "background-protection-toggle",
                    onCheckedChange = { onEvent(ProtectionEvent.Background(it)) },
                )
                if (state.backgroundEnabled) {
                    Text(
                        stringResource(when (state.runtime) {
                            ProtectionRuntime.RUNNING -> R.string.autofill_protection_running
                            ProtectionRuntime.STARTING -> R.string.autofill_protection_starting
                            ProtectionRuntime.STOPPED -> R.string.autofill_protection_stopped
                        }), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (state.runtime == ProtectionRuntime.STOPPED) {
                        TextButton(onClick = { onEvent(ProtectionEvent.RetryBackground) }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.autofill_protection_boot_desc), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.autofill_protection_vault_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            ProtectionCard {
                ProtectionSwitchRow(
                    icon = Icons.Outlined.Notifications,
                    title = stringResource(R.string.autofill_active_fill_notification_title),
                    description = stringResource(R.string.autofill_active_fill_notification_desc),
                    checked = proactiveFill,
                    tag = "proactive-fill-toggle",
                    onCheckedChange = { onEvent(ProtectionEvent.ProactiveFill(it)) },
                )
                HorizontalDivider()
                ProtectionSettingsRow(
                    Icons.Outlined.Notifications, stringResource(R.string.notification_settings_title),
                    stringResource(if (state.notificationsAllowed) R.string.autofill_protection_notifications_allowed else R.string.autofill_protection_notifications_blocked),
                ) { onEvent(ProtectionEvent.OpenSettings(ProtectionSettingsDestination.NOTIFICATIONS)) }
                HorizontalDivider()
                ProtectionSettingsRow(
                    Icons.Outlined.BatterySaver, stringResource(R.string.autofill_protection_battery_title),
                    stringResource(if (state.batteryExempt) R.string.autofill_protection_battery_exempt else R.string.autofill_protection_battery_desc),
                ) { onEvent(ProtectionEvent.OpenSettings(ProtectionSettingsDestination.BATTERY)) }
                HorizontalDivider()
                ProtectionSettingsRow(
                    Icons.Outlined.Smartphone, stringResource(R.string.autofill_protection_autostart_title),
                    stringResource(if (state.backgroundRestricted) R.string.autofill_protection_background_restricted else R.string.autofill_protection_autostart_desc),
                ) { onEvent(ProtectionEvent.OpenSettings(ProtectionSettingsDestination.AUTOSTART)) }
            }

            ProtectionCard {
                PrivilegeBackendSelector(enabled = !recovering)
                Text(stringResource(R.string.privilege_recovery_title), style = MaterialTheme.typography.titleMedium)
                if (!state.useRoot) Text(
                    stringResource(when (state.shizuku) {
                        ShizukuAccess.ADB_AUTHORIZED -> R.string.autofill_protection_shizuku_authorized
                        ShizukuAccess.NEEDS_PERMISSION -> R.string.autofill_protection_shizuku_needs_permission
                        ShizukuAccess.ROOT_MODE -> R.string.autofill_protection_shizuku_root
                        ShizukuAccess.UNSUPPORTED -> R.string.autofill_protection_shizuku_unsupported
                        ShizukuAccess.UNAVAILABLE -> R.string.autofill_protection_shizuku_unavailable
                    }), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("shizuku-status"),
                )
                ProtectionSwitchRow(
                    icon = Icons.Outlined.Security,
                    title = stringResource(R.string.autofill_protection_enhanced_title),
                    description = stringResource(if (state.backgroundEnabled) R.string.autofill_protection_enhanced_desc else R.string.autofill_protection_enhanced_needs_background),
                    checked = state.enhancedRecoveryEnabled,
                    enabled = state.enhancedRecoveryEnabled || state.backgroundEnabled &&
                        (if (state.useRoot) state.rootAuthorized else state.shizuku in setOf(ShizukuAccess.ADB_AUTHORIZED, ShizukuAccess.NEEDS_PERMISSION)),
                    tag = "enhanced-recovery-toggle",
                    onCheckedChange = { onEvent(ProtectionEvent.EnhancedRecovery(it)) },
                )
                if (state.enhancedRecoveryEnabled && state.accessibility == AccessibilityConnection.DISCONNECTED) {
                    TextButton(
                        onClick = { onEvent(ProtectionEvent.RecoverAccessibility) },
                        enabled = state.canRecoverAccessibility() && !recovering,
                        modifier = Modifier.testTag("recover-accessibility"),
                    ) { Text(stringResource(if (recovering) R.string.autofill_protection_recovering else R.string.autofill_protection_recover_now)) }
                }
                if (!state.useRoot) ProtectionSettingsRow(
                    Icons.Outlined.Settings, stringResource(R.string.autofill_protection_shizuku_open),
                    stringResource(R.string.autofill_protection_shizuku_availability),
                ) { onEvent(ProtectionEvent.OpenSettings(ProtectionSettingsDestination.SHIZUKU)) }
            }
        }
    }
}

@Composable
private fun ProtectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun ProtectionSwitchRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    tag: String,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
            .hapticSwitchToggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ProtectionSettingsRow(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp))
    }
}

@Composable
internal fun ShizukuRecoveryRiskDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    val acknowledgementInteraction = remember { MutableInteractionSource() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.autofill_protection_risk_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.autofill_protection_risk_body))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("shizuku-risk-acknowledgement")
                        .toggleable(
                            acknowledged,
                            interactionSource = acknowledgementInteraction,
                            indication = null,
                            role = Role.Checkbox,
                            onValueChange = { acknowledged = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        acknowledged,
                        onCheckedChange = null,
                        modifier = Modifier.size(48.dp)
                            .indication(acknowledgementInteraction, ripple(bounded = false, radius = 20.dp)),
                    )
                    Text(
                        stringResource(R.string.autofill_protection_risk_acknowledge),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = acknowledged, modifier = Modifier.testTag("shizuku-risk-confirm")) {
                Text(stringResource(R.string.autofill_protection_risk_continue))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
