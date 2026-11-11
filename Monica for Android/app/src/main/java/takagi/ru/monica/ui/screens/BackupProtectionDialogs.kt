package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.BackupEncryptionFormat

@Composable
private fun formatLabel(format: BackupEncryptionFormat): String = stringResource(
    if (format == BackupEncryptionFormat.COMPATIBLE_V1) R.string.backup_kdf_compatible
    else R.string.backup_kdf_enhanced
)

/** Only confirmation changes the cloud backup preference; cancellation discards the draft. */
@Composable
internal fun BackupEncryptionFormatField(
    format: BackupEncryptionFormat, onFormatChange: (BackupEncryptionFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    Surface(onClick = { showDialog = true }, modifier = modifier.testTag("backup_format_open"),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.backup_kdf_title), style = MaterialTheme.typography.titleSmall)
                Text(formatLabel(format), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
    if (showDialog) BackupEncryptionFormatDialog(format, { showDialog = false }) {
        showDialog = false
        onFormatChange(it)
    }
}

@Composable
internal fun BackupEncryptionFormatDialog(
    format: BackupEncryptionFormat, onDismiss: () -> Unit, onConfirm: (BackupEncryptionFormat) -> Unit,
) {
    var draft by remember(format) { mutableStateOf(format) }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.Lock, null) },
        title = { Text(stringResource(R.string.backup_kdf_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            BackupEncryptionFormatSelector(draft, { draft = it })
        } },
        confirmButton = { Button(onClick = { onConfirm(draft) }, modifier = Modifier.testTag("backup_format_confirm")) {
            Text(stringResource(R.string.confirm))
        } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("backup_format_cancel")) {
            Text(stringResource(R.string.cancel))
        } },
    )
}

/** Every manual export starts with encrypted V1; protected content cannot select plaintext. */
@Composable
internal fun ZipBackupProtectionDialog(
    encryptionRequired: Boolean, onDismiss: () -> Unit,
    onConfirm: (encrypted: Boolean, format: BackupEncryptionFormat) -> Unit,
) {
    var encrypted by remember { mutableStateOf(true) }
    var format by remember { mutableStateOf(BackupEncryptionFormat.COMPATIBLE_V1) }
    val effectiveEncrypted = encrypted || encryptionRequired
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.Lock, null) },
        title = { Text(stringResource(R.string.zip_protection_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.zip_backup_password_desc), style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    BackupEncryptionFormat.entries.forEachIndexed { index, option ->
                        ZipProtectionChoice(formatLabel(option), effectiveEncrypted && format == option,
                            "zip_protection_${option.name}", index) { encrypted = true; format = option }
                    }
                    ZipProtectionChoice(stringResource(R.string.zip_protection_plain), !effectiveEncrypted,
                        "zip_protection_plain", 2, enabled = !encryptionRequired) { encrypted = false }
                }
                Text(stringResource(if (encryptionRequired) R.string.zip_protection_required else R.string.zip_protection_plain_warning),
                    Modifier.testTag("zip_protection_warning"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.backup_kdf_compatibility_warning), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(effectiveEncrypted, format) }, modifier = Modifier.testTag("zip_protection_continue")) {
            Text(stringResource(R.string.zip_config_export_continue))
        } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("zip_protection_cancel")) {
            Text(stringResource(R.string.cancel))
        } },
    )
}

@Composable
private fun ZipProtectionChoice(label: String, selected: Boolean, tag: String, index: Int,
    enabled: Boolean = true, onSelect: () -> Unit) {
    Surface(shape = settingsSectionItemShape(index, 3),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.fillMaxWidth().testTag(tag)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .alpha(if (enabled) 1f else 0.38f).padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}
