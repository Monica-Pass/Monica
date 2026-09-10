package takagi.ru.monica.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.viewmodel.MdbxKeyFileSelection
import takagi.ru.monica.viewmodel.MdbxViewModel
import java.text.Normalizer

internal sealed class ConnectionState {
    data object NotTested : ConnectionState()
    data object Testing : ConnectionState()
    data object Connected : ConnectionState()
    data class Failed(val error: String) : ConnectionState()
}

@Composable
internal fun MdbxVaultNameField(
    vaultName: String,
    onVaultNameChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = vaultName,
        onValueChange = onVaultNameChange,
        label = { Text(stringResource(R.string.mdbx_vault_name)) },
        singleLine = true,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
internal fun MdbxPasswordFieldSection(
    masterPassword: String,
    onMasterPasswordChange: (String) -> Unit,
    confirmPassword: String,
    onConfirmPasswordChange: (String) -> Unit,
    passwordRequired: Boolean
) {
    val strings = rememberScreenStrings()
    var showMasterPassword by remember { mutableStateOf(false) }
    var showConfirmPassword by remember { mutableStateOf(false) }

    val normalizedMasterPassword = remember(masterPassword) {
        Normalizer.normalize(masterPassword, Normalizer.Form.NFC)
    }
    val normalizedConfirmPassword = remember(confirmPassword) {
        Normalizer.normalize(confirmPassword, Normalizer.Form.NFC)
    }

    OutlinedTextField(
        value = masterPassword,
        onValueChange = onMasterPasswordChange,
        label = { Text(stringResource(R.string.mdbx_master_password)) },
        visualTransformation = if (showMasterPassword) VisualTransformation.None
            else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showMasterPassword = !showMasterPassword }) {
                Icon(
                    if (showMasterPassword) Icons.Default.VisibilityOff
                    else Icons.Default.Visibility,
                    contentDescription = null
                )
            }
        },
        singleLine = true,
        enabled = passwordRequired,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = confirmPassword,
        onValueChange = onConfirmPasswordChange,
        label = { Text(stringResource(R.string.mdbx_confirm_password)) },
        visualTransformation = if (showConfirmPassword) VisualTransformation.None
            else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showConfirmPassword = !showConfirmPassword }) {
                Icon(
                    if (showConfirmPassword) Icons.Default.VisibilityOff
                    else Icons.Default.Visibility,
                    contentDescription = null
                )
            }
        },
        isError = confirmPassword.isNotEmpty() && normalizedMasterPassword != normalizedConfirmPassword,
        supportingText = if (confirmPassword.isNotEmpty() && normalizedMasterPassword != normalizedConfirmPassword) {
            { Text(stringResource(R.string.mdbx_password_mismatch)) }
        } else {
            { Text(strings.get(R.string.mdbx_ui_unicode_password_hint)) }
        },
        singleLine = true,
        enabled = passwordRequired,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun MdbxUnlockMethodSection(
    unlockMethod: MdbxUnlockMethod,
    onUnlockMethodChange: (MdbxUnlockMethod) -> Unit,
    includeDeviceKey: Boolean = false,
    embedded: Boolean = false
) {
    val strings = rememberScreenStrings()
    var expanded by remember { mutableStateOf(false) }
    val methods = buildList {
        add(Triple(MdbxUnlockMethod.MASTER_PASSWORD, Icons.Default.Key, strings.get(R.string.mdbx_ui_unlock_password_description)))
        add(Triple(MdbxUnlockMethod.KEY_FILE, Icons.Default.VpnKey, strings.get(R.string.mdbx_ui_unlock_keyfile_description)))
        add(
            Triple(
                MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE,
                Icons.Default.Shield,
                strings.get(R.string.mdbx_ui_unlock_both_description)
            )
        )
        if (includeDeviceKey) {
            add(Triple(MdbxUnlockMethod.DEVICE_KEY, Icons.Default.Smartphone, strings.get(R.string.mdbx_ui_unlock_device_description)))
        }
    }
    val titles = mapOf(
        MdbxUnlockMethod.MASTER_PASSWORD to strings.get(R.string.master_password),
        MdbxUnlockMethod.KEY_FILE to strings.get(R.string.local_keepass_key_file),
        MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE to strings.get(R.string.mdbx_ui_unlock_password_and_keyfile),
        MdbxUnlockMethod.DEVICE_KEY to strings.get(R.string.mdbx_ui_unlock_device_key)
    )
    val selected = methods.firstOrNull { it.first == unlockMethod } ?: methods.first()

    val content: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            ListItem(
                headlineContent = { Text(strings.get(R.string.mdbx_ui_unlock_method), fontWeight = FontWeight.SemiBold) },
                supportingContent = {
                    Text("${titles[selected.first]} · ${selected.third}")
                },
                leadingContent = {
                    Icon(selected.second, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingContent = {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) strings.get(R.string.mdbx_ui_collapse_unlock_methods) else strings.get(R.string.mdbx_ui_expand_unlock_methods)
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
            )
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    methods.forEach { (method, icon, description) ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    titles[method].orEmpty(),
                                    fontWeight = if (unlockMethod == method) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            supportingContent = { Text(description) },
                            leadingContent = {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    tint = if (unlockMethod == method) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingContent = {
                                RadioButton(
                                    selected = unlockMethod == method,
                                    onClick = null
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth().clickable {
                                onUnlockMethodChange(method)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }

    if (embedded) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) { Column(content = content) }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            content = content
        )
    }
}

@Composable
internal fun MdbxKeyFileSection(
    keyFile: MdbxKeyFileSelection?,
    keyFileError: String?,
    keyFileRequired: Boolean,
    onPickKeyFile: () -> Unit,
    onGenerateKeyFile: () -> Unit,
    embedded: Boolean = false
) {
    val strings = rememberScreenStrings()
    if (!keyFileRequired) return

    val content: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ListItem(
                headlineContent = {
                    Text(
                        keyFile?.name ?: strings.get(R.string.mdbx_ui_mdbx_keyfile),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = {
                    if (keyFile != null) {
                        AssistChip(
                            onClick = {},
                            label = { Text("SHA-256 ${keyFile.shortFingerprint}...") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Fingerprint,
                                    null,
                                    Modifier.size(16.dp)
                                )
                            }
                        )
                    } else {
                        Text(strings.get(R.string.mdbx_ui_keyfile_description))
                    }
                },
                leadingContent = {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPickKeyFile,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(strings.get(R.string.select))
                }
                Button(
                    onClick = onGenerateKeyFile,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(strings.get(R.string.generator_generate))
                }
            }
            keyFileError?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
    if (embedded) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) { Column(content = content) }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            content = content
        )
    }
}

@Composable
internal fun MdbxOperationFeedback(
    operationState: MdbxViewModel.OperationState
) {
    when (operationState) {
        is MdbxViewModel.OperationState.Success -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                operationState.message,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        is MdbxViewModel.OperationState.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                operationState.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        else -> {}
    }
}

@Composable
internal fun MdbxWebDavConnectionSection(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    showPassword: Boolean,
    onTogglePasswordVisibility: () -> Unit,
    connectionState: ConnectionState,
    onTestConnection: () -> Unit
) {
    OutlinedTextField(
        value = serverUrl,
        onValueChange = onServerUrlChange,
        label = { Text(stringResource(R.string.mdbx_webdav_url)) },
        leadingIcon = { Icon(Icons.Default.Cloud, null) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text(stringResource(R.string.mdbx_webdav_username)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text(stringResource(R.string.mdbx_webdav_password)) },
        visualTransformation = if (showPassword) VisualTransformation.None
            else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onTogglePasswordVisibility) {
                Icon(
                    if (showPassword) Icons.Default.VisibilityOff
                    else Icons.Default.Visibility,
                    contentDescription = null
                )
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Button(
        onClick = onTestConnection,
        enabled = serverUrl.isNotBlank() && username.isNotBlank() &&
            password.isNotBlank() && connectionState !is ConnectionState.Testing,
        modifier = Modifier.fillMaxWidth()
    ) {
        when (connectionState) {
            is ConnectionState.Testing -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.mdbx_test_connection))
            }
            is ConnectionState.Connected -> {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.mdbx_connection_success))
            }
            else -> Text(stringResource(R.string.mdbx_test_connection))
        }
    }

    if (connectionState is ConnectionState.Connected) {
        Text(
            stringResource(R.string.mdbx_connection_success),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    } else if (connectionState is ConnectionState.Failed) {
        Text(
            "${stringResource(R.string.mdbx_connection_failed)}: ${connectionState.error}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}
