package takagi.ru.monica.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.utils.KeePassOperationException

@Composable
fun PasswordKeyboardTagDialog(
    onDismiss: () -> Unit,
    onConvert: () -> Unit,
    onDrop: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_password_keyboard_tag_dialog_title)) },
        text = {
            Text(stringResource(R.string.import_password_keyboard_tag_dialog_message))
        },
        confirmButton = {
            TextButton(onClick = onConvert) {
                Text(stringResource(R.string.import_password_keyboard_tag_convert_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDrop) {
                Text(stringResource(R.string.import_password_keyboard_tag_drop_action))
            }
        }
    )
}

@Composable
fun ZipRestoreConfirmDialog(
    selectedFileName: String?,
    isImporting: Boolean,
    restoreConfig: Boolean = false,
    onRestoreConfigChange: (Boolean) -> Unit = {},
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.webdav_restore_backup_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.webdav_restore_backup_confirm_message,
                    selectedFileName ?: stringResource(R.string.import_data_file_selected)))
                Row(Modifier.fillMaxWidth().toggleable(value = restoreConfig, enabled = !isImporting,
                    role = Role.Checkbox, onValueChange = onRestoreConfigChange),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.zip_restore_config), modifier = Modifier.weight(1f))
                    Checkbox(checked = restoreConfig, onCheckedChange = null, enabled = !isImporting)
                }
                Text(stringResource(R.string.zip_restore_config_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isImporting
            ) {
                Text(stringResource(R.string.webdav_restore_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isImporting
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun EncryptedImportPasswordDialog(
    importType: String,
    password: String,
    passwordError: String?,
    isImporting: Boolean,
    onPasswordChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val title = when (importType) {
                "monica_zip" -> stringResource(R.string.transfer_zip_password_title)
                "stratum" -> stringResource(R.string.stratum_decrypt_password_title)
                else -> stringResource(R.string.aegis_decrypt_password_title)
            }
            Text(title)
        },
        text = {
            Column {
                Text(
                    when (importType) {
                        "monica_zip" -> stringResource(R.string.transfer_zip_password_description)
                        "stratum" -> stringResource(R.string.stratum_decrypt_password_hint)
                        else -> stringResource(R.string.aegis_decrypt_password_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                    isError = passwordError != null,
                    supportingText = passwordError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isImporting
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isImporting
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun KdbxImportPasswordDialog(
    password: String,
    passwordVisible: Boolean,
    keyFileName: String,
    hasKeyFile: Boolean,
    isImporting: Boolean,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onPickKeyFile: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kdbx_import_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.kdbx_import_password_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.password)) },
                    visualTransformation = if (passwordVisible)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = onTogglePasswordVisible) {
                            Icon(
                                if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null
                            )
                        }
                    },
                    singleLine = true
                )
                OutlinedTextField(
                    value = keyFileName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.local_keepass_key_file_optional)) },
                    placeholder = { Text(stringResource(R.string.local_keepass_key_file_tap_to_select)) },
                    trailingIcon = {
                        IconButton(onClick = onPickKeyFile) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = stringResource(R.string.local_keepass_select_key_file)
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPickKeyFile)
                )
                Text(
                    if (!hasKeyFile) {
                        stringResource(R.string.local_keepass_no_key_file_selected)
                    } else {
                        stringResource(R.string.local_keepass_key_file_selected)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = (password.isNotEmpty() || hasKeyFile) && !isImporting
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.import_data_btn))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isImporting
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun KeepassImportErrorDialog(
    error: KeePassOperationException,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.import_data_keepass_error_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.import_data_keepass_error_code_value, error.code.name),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    error.message ?: stringResource(R.string.import_data_error),
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider()
                Text(
                    stringResource(R.string.import_data_keepass_error_suggestion_label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    keepassImportSuggestion(androidx.compose.ui.platform.LocalContext.current, error.code),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        }
    )
}
