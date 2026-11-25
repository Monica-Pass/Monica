package takagi.ru.monica.ui.components

import android.app.KeyguardManager
import android.os.PowerManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import takagi.ru.monica.R
import takagi.ru.monica.security.*
import takagi.ru.monica.ui.screens.Setting
import takagi.ru.monica.ui.screens.SettingsItem

internal fun recoveryMaterialMessage(state: RecoveryMaterialState): Int = when (state) {
    RecoveryMaterialState.AVAILABLE -> R.string.recovery_material_available
    RecoveryMaterialState.MISSING -> R.string.recovery_material_missing
    RecoveryMaterialState.REENROLLMENT_REQUIRED -> R.string.recovery_material_reenroll
    RecoveryMaterialState.REVOKED -> R.string.recovery_material_revoked
    RecoveryMaterialState.UNSUPPORTED_VERSION -> R.string.recovery_material_unsupported
    RecoveryMaterialState.INVALID -> R.string.recovery_material_invalid
    RecoveryMaterialState.UNREADABLE -> R.string.recovery_material_unreadable
    RecoveryMaterialState.NOT_CHECKED -> R.string.recovery_material_unchecked
}

@Composable
internal fun RecoveryProtectionSetting(
    manager: SecurityManager,
    sectionShape: androidx.compose.ui.graphics.Shape? = null,
    onBackup: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val generation by SecurityManager.dataReadGeneration.collectAsState()
    val changes by LocalVaultRecovery.protectionChanges.collectAsState()
    val active = remember { AtomicBoolean(true) }
    var resume by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf(RecoveryProtectionStatus()) }
    var details by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    fun foreground() = active.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resume++
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                details = false; checking = false; status = RecoveryProtectionStatus()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { active.set(false); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(manager, generation, changes, resume) {
        status = RecoveryProtectionStatus()
        if (foreground()) {
            val result = BoundedSecureDataRead.read(manager, generation) { manager.recoveryProtectionStatus() }
            if (foreground() && generation == SecurityManager.dataReadGeneration.value) {
                status = result ?: RecoveryProtectionStatus(verificationFailed = true)
            }
        }
    }
    val summary = when {
        status.verifiedAtMillis != null -> stringResource(R.string.recovery_protection_verified,
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.verifiedAtMillis!!)))
        status.verificationFailed -> stringResource(R.string.recovery_protection_not_ready)
        status.material in setOf(RecoveryMaterialState.AVAILABLE, RecoveryMaterialState.MISSING,
            RecoveryMaterialState.REENROLLMENT_REQUIRED) -> stringResource(R.string.recovery_protection_needs_password)
        else -> stringResource(recoveryMaterialMessage(status.material))
    }
    SettingsItem(Icons.Default.Security, Setting.RECOVERY_PROTECTION, subtitle = summary,
        onClick = { if (foreground()) details = true }, modifier = Modifier.testTag("recovery_protection_setting"),
        sectionShape = sectionShape)
    if (details) AlertDialog(
        onDismissRequest = { details = false },
        title = { Text(Setting.RECOVERY_PROTECTION.title()) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(summary, Modifier.testTag("recovery_protection_status"))
            Text(stringResource(recoveryMaterialMessage(status.material)))
            Text(stringResource(R.string.recovery_protection_explanation))
            Text(stringResource(R.string.recovery_protection_limits))
            TextButton(onClick = { details = false; onBackup() }) { Text(Setting.SYNC_BACKUP_TITLE.title()) }
        } },
        confirmButton = { TextButton(onClick = { details = false; checking = true }) {
            Text(stringResource(R.string.recovery_protection_verify))
        } },
        dismissButton = { TextButton(onClick = { details = false }) { Text(stringResource(R.string.close)) } },
    )
    if (checking) RecoveryProtectionDialog(manager, onDismiss = { checking = false },
        onSuccess = { checking = false; if (foreground()) { resume++; details = true } })
}
