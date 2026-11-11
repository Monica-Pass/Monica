package takagi.ru.monica.ui.screens

import android.widget.Toast
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
    onSecurityQuestions: () -> Unit
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

    val startBiometricEnable = {
        if (activity != null) {
            biometricHelper.authenticate(
                activity = activity,
                title = context.getString(R.string.biometric_login_title),
                subtitle = context.getString(R.string.biometric_subtitle),
                description = context.getString(R.string.biometric_login_description),
                negativeButtonText = context.getString(R.string.cancel),
                onSuccess = {
                    biometricSwitchState = true
                    viewModel.updateBiometricEnabled(true)
                    Toast.makeText(
                        context,
                        context.getString(R.string.biometric_unlock_enabled),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onError = { _, errorMsg ->
                    biometricSwitchState = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.biometric_auth_error, errorMsg),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onCancel = {
                    biometricSwitchState = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.cancel),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        } else {
            biometricSwitchState = false
            Toast.makeText(
                context,
                context.getString(R.string.biometric_cannot_enable),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.master_password_and_locking)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
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

            SettingsItemWithSwitch(
                icon = Icons.Default.Fingerprint,
                title = stringResource(R.string.biometric_unlock),
                subtitle = biometricSubtitle,
                checked = biometricSwitchState,
                enabled = isBiometricAvailable,
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
                icon = Icons.Default.Timer,
                title = stringResource(R.string.auto_lock),
                subtitle = getAutoLockDisplayName(settings.autoLockMinutes, context),
                onClick = { showAutoLockDialog = true }
            )

            SettingsItem(
                icon = Icons.Default.Security,
                title = stringResource(R.string.security_questions),
                subtitle = stringResource(R.string.security_questions_description),
                onClick = onSecurityQuestions
            )

            SettingsItem(
                icon = Icons.Default.VpnKey,
                title = stringResource(R.string.reset_master_password),
                subtitle = stringResource(R.string.reset_password_description),
                onClick = onResetPassword
            )

            Spacer(modifier = Modifier.height(20.dp))
        }
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
