package takagi.ru.monica.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import takagi.ru.monica.data.model.PermissionCategory
import takagi.ru.monica.data.model.PermissionStats
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import takagi.ru.monica.R
import takagi.ru.monica.data.model.PermissionInfo
import takagi.ru.monica.data.model.PermissionSettingsTarget
import takagi.ru.monica.ui.components.*
import takagi.ru.monica.viewmodel.PermissionViewModel
import takagi.ru.monica.notifications.LiveUpdateNotifications

/**
 * 权限管理主界面
 * Permission management main screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionManagementScreen(
    onNavigateBack: () -> Unit,
    viewModel: PermissionViewModel = viewModel()
) {
    val context = LocalContext.current
    val permissionsByCategory by viewModel.permissionsByCategory.collectAsState()
    val permissionStats by viewModel.permissionStats.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var showHelpDialog by rememberSaveable { mutableStateOf(false) }
    var pendingRuntimePermission by remember { mutableStateOf<PermissionInfo?>(null) }
    var deniedRuntimePermission by remember { mutableStateOf<PermissionInfo?>(null) }
    var selectedPermission by remember { mutableStateOf<PermissionInfo?>(null) }

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissions()
    }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val permission = pendingRuntimePermission
        pendingRuntimePermission = null
        viewModel.refreshPermissions()
        if (permission != null) {
            val grantedPermission = runtimeGrantedPermission(permission,grants)
            val name = if(grantedPermission==android.Manifest.permission.ACCESS_COARSE_LOCATION)
                context.getString(R.string.pm_location_approximate) else permission.systemLabel ?: context.getString(permission.nameResId)
            if (grantedPermission != null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.permission_request_granted, name),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                deniedRuntimePermission = permission
            }
        }
    }

    fun launchPermissionSettings(permission: PermissionInfo) {
        try {
            settingsLauncher.launch(createPermissionSettingsIntent(context, permission.settingsTarget))
        } catch (_: RuntimeException) {
            try {
                settingsLauncher.launch(LiveUpdateNotifications.appSettingsIntent(context))
                return
            } catch (_: RuntimeException) { /* Report below. */ }
            Toast.makeText(
                context,
                context.getString(R.string.cannot_open_settings),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun handlePermissionClick(permission: PermissionInfo) {
        // Re-read immediately before requesting: never request an undeclared/stale permission.
        val current = takagi.ru.monica.repository.PermissionRepository(context).getAllPermissions()
            .firstOrNull { it.id == permission.id } ?: return
        when (resolvePermissionClickAction(current)) {
            PermissionClickAction.REQUEST_RUNTIME_PERMISSION -> {
                pendingRuntimePermission = current
                runtimePermissionLauncher.launch(current.runtimePermissions.toTypedArray())
            }
            PermissionClickAction.OPEN_AUTOFILL_SETTINGS,
            PermissionClickAction.OPEN_ACCESSIBILITY_SETTINGS,
            PermissionClickAction.OPEN_BIOMETRIC_SETTINGS,
            PermissionClickAction.OPEN_APP_SETTINGS -> launchPermissionSettings(current)
            PermissionClickAction.SHOW_GRANTED -> Toast.makeText(
                context,
                context.getString(
                    R.string.permission_already_granted,
                    context.getString(permission.nameResId)
                ),
                Toast.LENGTH_SHORT
            ).show()
            PermissionClickAction.IGNORE -> Unit
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
    }

    PermissionManagementContent(
        permissionsByCategory = permissionsByCategory,
        permissionStats = permissionStats,
        isLoading = isLoading,
        onNavigateBack = onNavigateBack,
        onRefresh = viewModel::refreshPermissions,
        onHelp = { showHelpDialog = true },
        onPermissionClick = { selectedPermission = it },
    )

    // 帮助对话框
    if (showHelpDialog) {
        PermissionHelpDialog(
            onDismiss = { showHelpDialog = false }
        )
    }

    selectedPermission?.let { selected ->
        val permission = permissionsByCategory.values.flatten().firstOrNull { it.id == selected.id } ?: selected
        PermissionDetailsDialog(permission, { selectedPermission = null }) {
            selectedPermission = null
            handlePermissionClick(permission)
        }
    }

    deniedRuntimePermission?.let { permission ->
        val permissionName = permission.systemLabel ?: stringResource(permission.nameResId)
        AlertDialog(
            onDismissRequest = { deniedRuntimePermission = null },
            title = { Text(stringResource(R.string.permission_request_denied_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.permission_request_denied_message,
                        permissionName
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deniedRuntimePermission = null
                        launchPermissionSettings(permission)
                    }
                ) {
                    Text(stringResource(R.string.permission_open_system_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { deniedRuntimePermission = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
internal fun PermissionManagementContent(
    permissionsByCategory: Map<PermissionCategory, List<PermissionInfo>>,
    permissionStats: PermissionStats?,
    isLoading: Boolean,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
    onHelp: () -> Unit,
    onPermissionClick: (PermissionInfo) -> Unit,
) {
    Scaffold(
        topBar = {
            SettingsSubpageTopBar(stringResource(R.string.permission_management_title), onNavigateBack) {
                IconButton(onClick = onHelp) {
                    Icon(Icons.Default.HelpOutline, stringResource(R.string.help))
                }
            }
        }
    ) { paddingValues ->
        Box(Modifier.fillMaxSize().padding(paddingValues)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 24.dp)) {
                PermissionStatsCard(permissionStats, onRefresh, isLoading)
                permissionsByCategory.forEach { (category, permissions) ->
                    if (permissions.isNotEmpty()) {
                        PermissionCategorySection(category, permissions, onPermissionClick)
                    }
                }
            }
            if (isLoading && permissionsByCategory.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

internal fun createPermissionSettingsIntent(context: Context, target: PermissionSettingsTarget): Intent {
    val packageUri = Uri.fromParts("package", context.packageName, null)
    return when (target) {
        PermissionSettingsTarget.LIVE_UPDATES -> LiveUpdateNotifications.settingsIntent(context)
        PermissionSettingsTarget.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        PermissionSettingsTarget.AUTOFILL -> Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        PermissionSettingsTarget.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        PermissionSettingsTarget.BIOMETRIC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        PermissionSettingsTarget.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
        PermissionSettingsTarget.INSTALL -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
        PermissionSettingsTarget.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        PermissionSettingsTarget.IME -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        PermissionSettingsTarget.CREDENTIALS -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_CREDENTIAL_PROVIDER, packageUri)
            else Intent(Settings.ACTION_SECURITY_SETTINGS)
        PermissionSettingsTarget.NFC -> Intent(Settings.ACTION_NFC_SETTINGS)
        PermissionSettingsTarget.SHIZUKU -> context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:moe.shizuku.privileged.api"))
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    }
}

@Composable
internal fun PermissionDetailsDialog(permission: PermissionInfo, onDismiss: () -> Unit, onAction: () -> Unit) {
    val canAct = resolvePermissionClickAction(permission) != PermissionClickAction.IGNORE
    AlertDialog(onDismissRequest = onDismiss,
        modifier = Modifier.testTag("permission_details"),
        title = { Text(permission.systemLabel ?: stringResource(permission.nameResId)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(permissionStatusText(permission), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(permission.descriptionResId))
                Text(stringResource(when {
                    permission.internalProtection -> R.string.pm_mode_internal
                    permission.availability -> R.string.pm_mode_biometric
                    permission.category == PermissionCategory.RUNTIME -> R.string.pm_mode_runtime
                    permission.category == PermissionCategory.SPECIAL -> R.string.pm_mode_special
                    else -> R.string.pm_mode_auto
                }))
                Text(if (permission.maxSdk != null) stringResource(R.string.pm_version_max, permission.maxSdk)
                    else stringResource(R.string.pm_version_min, permission.minSdk), style = MaterialTheme.typography.bodySmall)
                if (permission.androidPermission.isNotEmpty()) Text(permission.androidPermission,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { if (canAct) TextButton(onClick = onAction, modifier = Modifier.testTag("permission_details_action")) {
            Text(stringResource(if (resolvePermissionClickAction(permission) == PermissionClickAction.REQUEST_RUNTIME_PERMISSION)
                R.string.permission_action_request else R.string.permission_open_system_settings))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}


