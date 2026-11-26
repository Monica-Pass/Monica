package takagi.ru.monica.ui.screens

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.widget.Toast
import android.app.KeyguardManager
import android.os.PowerManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import takagi.ru.monica.security.BoundedSecureDataRead
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.rememberUiSecurityManager
import takagi.ru.monica.ui.components.BiometricRebindDialog
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import takagi.ru.monica.R
import takagi.ru.monica.utils.BiometricAuthHelper
import takagi.ru.monica.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun MasterPasswordLockingSettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onResetPassword: () -> Unit,
    onSecurityQuestions: () -> Unit,
    onBackup: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val settings by viewModel.settings.collectAsState()
    val biometricHelper = remember(context) { BiometricAuthHelper(context) }
    val isBiometricAvailable = remember(biometricHelper) { biometricHelper.isBiometricAvailable() }
    val biometricSubtitle = if (isBiometricAvailable) {
        if (settings.biometricEnabled) context.getString(R.string.biometric_unlock_enabled)
        else context.getString(R.string.biometric_unlock_disabled)
    } else {
        biometricHelper.getBiometricStatusMessage()
    }

    var biometricSwitchState by remember(settings.biometricEnabled) {
        mutableStateOf(settings.biometricEnabled)
    }
    var showWeakBiometricWarning by remember { mutableStateOf(false) }
    var showAutoLockDialog by remember { mutableStateOf(false) }

    val manager = rememberUiSecurityManager()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val active = remember { AtomicBoolean(true) }
    val generation by SecurityManager.dataReadGeneration.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var showRepair by remember { mutableStateOf(false) }
    var repairComplete by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf(0L) }
    fun foreground() = active.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
    fun revoke() {
        request++; busy = false; showRepair = false; repairComplete = false
        biometricHelper.cancelAuthentication()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) revoke()
        }
        lifecycle.addObserver(observer)
        onDispose { active.set(false); revoke(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(generation) { revoke() }
    val startBiometricEnable: () -> Unit = {
        if (activity != null && foreground() && !busy) {
            val id = ++request
            val startedGeneration = generation
            busy = true
            biometricHelper.authenticate(
                activity = activity,
                title = context.getString(R.string.biometric_login_title),
                subtitle = context.getString(R.string.biometric_subtitle),
                description = context.getString(R.string.biometric_login_description),
                negativeButtonText = context.getString(R.string.cancel),
                onSuccess = {
                    if (request == id && foreground()) scope.launch {
                        val health = BoundedSecureDataRead.read(manager, startedGeneration) {
                            manager.biometricBindingHealth()
                        }
                        if (request == id && foreground() && manager.isDataReadCurrent(startedGeneration)) {
                            busy = false
                            if (health == SecurityManager.BiometricBindingHealth.AVAILABLE) {
                                biometricSwitchState = true
                                viewModel.updateBiometricEnabled(true)
                            } else {
                                biometricSwitchState = false
                                if (health == SecurityManager.BiometricBindingHealth.REPAIR_REQUIRED) showRepair = true
                                else Toast.makeText(context, context.getString(
                                    if (health == SecurityManager.BiometricBindingHealth.DEVICE_AUTH_REQUIRED)
                                        R.string.biometric_repair_device_auth else R.string.biometric_repair_failed), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                onError = { _, errorMsg ->
                    if (request == id && foreground()) {
                        busy = false; biometricSwitchState = false
                        Toast.makeText(context, context.getString(R.string.biometric_auth_error, errorMsg), Toast.LENGTH_SHORT).show()
                    }
                },
                onCancel = { if (request == id) { busy = false; biometricSwitchState = false } }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.master_password_and_locking)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
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
                .verticalScroll(rememberScrollState())
        ) {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                Text(
                    text = stringResource(R.string.master_password_and_locking_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)
                )
            }

            SettingsSection(title = "") {
                SettingsItemWithSwitch(
                    icon = Icons.Default.Fingerprint,
                    setting = Setting.BIOMETRIC_UNLOCK,
                    subtitle = biometricSubtitle,
                    checked = biometricSwitchState,
                    enabled = isBiometricAvailable && !busy,
                    sectionShape = settingsSectionItemShape(0, 6),
                    onCheckedChange = { newState ->
                        if (newState) {
                            if (biometricHelper.isWeakBiometricOnly()) {
                                showWeakBiometricWarning = true
                            } else {
                                startBiometricEnable()
                            }
                        } else {
                            biometricSwitchState = false
                            viewModel.updateBiometricEnabled(false)
                            Toast.makeText(
                                context,
                                context.getString(R.string.biometric_unlock_disabled),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )

                SettingsItem(
                    icon = Icons.Default.Fingerprint,
                    setting = Setting.BIOMETRIC_REPAIR,
                    sectionShape = settingsSectionItemShape(1, 6),
                    onClick = { if (!busy && foreground()) showRepair = true }
                )

                takagi.ru.monica.ui.components.RecoveryProtectionSetting(
                    manager, sectionShape = settingsSectionItemShape(2, 6), onBackup = onBackup
                )

                SettingsItem(
                    icon = Icons.Default.Timer,
                    setting = Setting.AUTO_LOCK,
                    sectionShape = settingsSectionItemShape(3, 6),
                    subtitle = getAutoLockDisplayName(settings.autoLockMinutes, context),
                    onClick = { showAutoLockDialog = true }
                )

                SettingsItem(
                    icon = Icons.Default.Security,
                    setting = Setting.SECURITY_QUESTIONS,
                    sectionShape = settingsSectionItemShape(4, 6),
                    onClick = onSecurityQuestions
                )

                SettingsItem(
                    icon = Icons.Default.VpnKey,
                    setting = Setting.RESET_MASTER_PASSWORD,
                    sectionShape = settingsSectionItemShape(5, 6),
                    onClick = onResetPassword
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    if (showRepair) {
        BiometricRebindDialog(manager,
            onDismiss = { showRepair = false },
            onSuccess = { showRepair = false; repairComplete = true })
    }
    if (repairComplete) {
        AlertDialog(
            onDismissRequest = { repairComplete = false },
            title = { Text(stringResource(R.string.biometric_repair_title)) },
            text = { Text(stringResource(R.string.biometric_repair_success)) },
            confirmButton = { TextButton(onClick = { repairComplete = false }) { Text(stringResource(R.string.confirm)) } }
        )
    }

    if (showAutoLockDialog) {
        AutoLockSelectionSheet(
            currentMinutes = settings.autoLockMinutes,
            onMinutesSelected = { minutes ->
                viewModel.updateAutoLockMinutes(minutes)
                showAutoLockDialog = false
            },
            onDismiss = { showAutoLockDialog = false }
        )
    }

    if (showWeakBiometricWarning) {
        AlertDialog(
            onDismissRequest = {
                showWeakBiometricWarning = false
                biometricSwitchState = false
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
                    Text(stringResource(R.string.continue_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showWeakBiometricWarning = false
                        biometricSwitchState = false
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
