package takagi.ru.monica.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.data.KeePassCipherAlgorithm
import takagi.ru.monica.data.KeePassFormatVersion
import takagi.ru.monica.data.KeePassKdfAlgorithm
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.keepass.KeePassDatabaseCompression
import takagi.ru.monica.keepass.KeePassDatabaseGroupOption
import takagi.ru.monica.keepass.KeePassDatabaseSettingsSnapshot
import takagi.ru.monica.keepass.KeePassDatabaseSettingsUpdate
import takagi.ru.monica.keepass.KeePassKeyFileChangeMode
import takagi.ru.monica.ui.components.MasterPasswordDialog
import takagi.ru.monica.viewmodel.LocalKeePassViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeePassNativeDatabaseSettingsScreen(
    database: LocalKeePassDatabase,
    viewModel: LocalKeePassViewModel,
    onBack: () -> Unit,
    onOpenDatabaseTools: () -> Unit,
    onDatabaseChanged: () -> Unit,
    onLocked: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var snapshot by remember(database.id) { mutableStateOf<KeePassDatabaseSettingsSnapshot?>(null) }
    var loading by remember(database.id) { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showMasterPasswordVerification by remember { mutableStateOf(false) }
    var masterPasswordError by remember { mutableStateOf(false) }
    var showCredentialDialog by remember { mutableStateOf(false) }
    val invalidSettingsMessage = stringResource(R.string.keepass_database_settings_invalid)

    fun load() {
        scope.launch {
            loading = true
            error = null
            viewModel.loadKeePassDatabaseSettings(database.id)
                .onSuccess { snapshot = it }
                .onFailure { failure -> error = failure.message ?: failure.javaClass.simpleName }
            loading = false
        }
    }

    LaunchedEffect(database.id) { load() }

    val currentSnapshot = snapshot
    var form by remember(currentSnapshot) {
        mutableStateOf(currentSnapshot?.toForm())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.keepass_database_settings_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            database.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                    }
                },
                actions = {
                    if (currentSnapshot != null && form != null) {
                        IconButton(
                            onClick = {
                                val update = form!!.toUpdateOrNull()
                                if (update == null) {
                                    error = invalidSettingsMessage
                                } else {
                                    scope.launch {
                                        saving = true
                                        error = null
                                        viewModel.updateKeePassDatabaseSettings(database.id, update)
                                            .onSuccess { updated ->
                                                snapshot = updated
                                                onDatabaseChanged()
                                            }
                                            .onFailure { failure ->
                                                error = failure.message ?: failure.javaClass.simpleName
                                            }
                                        saving = false
                                    }
                                }
                            },
                            enabled = !saving && currentSnapshot.readOnly.not()
                        ) {
                            if (saving) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save))
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            currentSnapshot == null || form == null -> KeePassSettingsLoadError(
                modifier = Modifier.fillMaxSize().padding(padding),
                message = error ?: stringResource(R.string.keepass_database_settings_load_failed),
                onRetry = ::load
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    KeePassSettingsSummaryCard(currentSnapshot)
                }

                error?.let { message ->
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                message,
                                modifier = Modifier.padding(14.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                item {
                    KeePassSettingsSection(
                        icon = Icons.Default.Build,
                        title = stringResource(R.string.keepass_database_tools_title)
                    ) {
                        Text(
                            stringResource(R.string.keepass_database_tools_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(
                            onClick = onOpenDatabaseTools,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Build, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.keepass_database_tools_title))
                        }
                    }
                }

                item {
                    KeePassSettingsSection(
                        icon = Icons.Default.Storage,
                        title = stringResource(R.string.keepass_database_settings_general)
                    ) {
                        OutlinedTextField(
                            value = form!!.name,
                            onValueChange = { form = form!!.copy(name = it) },
                            label = { Text(stringResource(R.string.database_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !currentSnapshot.readOnly
                        )
                        OutlinedTextField(
                            value = form!!.description,
                            onValueChange = { form = form!!.copy(description = it) },
                            label = { Text(stringResource(R.string.keepass_database_description)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            enabled = !currentSnapshot.readOnly
                        )
                        OutlinedTextField(
                            value = form!!.defaultUsername,
                            onValueChange = { form = form!!.copy(defaultUsername = it) },
                            label = { Text(stringResource(R.string.keepass_database_default_username)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !currentSnapshot.readOnly
                        )
                        OutlinedTextField(
                            value = form!!.color,
                            onValueChange = { form = form!!.copy(color = it) },
                            label = { Text(stringResource(R.string.keepass_database_color)) },
                            supportingText = { Text(stringResource(R.string.keepass_database_color_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !currentSnapshot.readOnly
                        )
                    }
                }

                item {
                    KeePassSettingsSection(
                        icon = Icons.Default.History,
                        title = stringResource(R.string.keepass_database_settings_history_recycle)
                    ) {
                        KeePassNumberField(
                            value = form!!.maintenanceHistoryDays,
                            onValueChange = { form = form!!.copy(maintenanceHistoryDays = it) },
                            label = stringResource(R.string.keepass_database_history_maintenance_days),
                            enabled = !currentSnapshot.readOnly
                        )
                        KeePassNumberField(
                            value = form!!.historyMaxItems,
                            onValueChange = { form = form!!.copy(historyMaxItems = it) },
                            label = stringResource(R.string.keepass_database_history_max_items),
                            enabled = !currentSnapshot.readOnly
                        )
                        KeePassNumberField(
                            value = form!!.historyMaxSizeMb,
                            onValueChange = { form = form!!.copy(historyMaxSizeMb = it) },
                            label = stringResource(R.string.keepass_database_history_max_size_mb),
                            enabled = !currentSnapshot.readOnly
                        )
                        Text(
                            stringResource(R.string.keepass_database_unlimited_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider()
                        KeePassSettingsSwitchRow(
                            title = stringResource(R.string.keepass_database_recycle_bin),
                            summary = stringResource(R.string.keepass_database_recycle_bin_summary),
                            checked = form!!.recycleBinEnabled,
                            enabled = !currentSnapshot.readOnly,
                            onCheckedChange = { form = form!!.copy(recycleBinEnabled = it) }
                        )
                        if (form!!.recycleBinEnabled) {
                            KeePassGroupChoice(
                                label = stringResource(R.string.keepass_database_recycle_bin_group),
                                selectedUuid = form!!.recycleBinGroupUuid,
                                groups = currentSnapshot.groups.filterNot { it.isRoot },
                                nullLabel = stringResource(R.string.keepass_database_recycle_bin_auto_create),
                                enabled = !currentSnapshot.readOnly,
                                onSelected = { form = form!!.copy(recycleBinGroupUuid = it) }
                            )
                        }
                        KeePassGroupChoice(
                            label = stringResource(R.string.keepass_database_templates_group),
                            selectedUuid = form!!.templateGroupUuid,
                            groups = currentSnapshot.groups.filterNot { it.isRoot || it.isRecycleBin },
                            nullLabel = stringResource(R.string.not_set),
                            enabled = !currentSnapshot.readOnly,
                            onSelected = { form = form!!.copy(templateGroupUuid = it) }
                        )
                    }
                }

                item {
                    KeePassSettingsSection(
                        icon = Icons.Default.Security,
                        title = stringResource(R.string.keepass_database_settings_encryption)
                    ) {
                        KeePassChoiceField(
                            label = stringResource(R.string.keepass_database_compression),
                            selected = form!!.compression,
                            options = KeePassDatabaseCompression.entries,
                            optionLabel = { compressionLabel(it) },
                            enabled = !currentSnapshot.readOnly,
                            onSelected = { form = form!!.copy(compression = it) }
                        )
                        KeePassChoiceField(
                            label = stringResource(R.string.local_keepass_cipher_algorithm),
                            selected = form!!.cipherAlgorithm,
                            options = KeePassCipherAlgorithm.entries.filterNot {
                                currentSnapshot.formatVersion == KeePassFormatVersion.KDBX3 &&
                                    it == KeePassCipherAlgorithm.CHACHA20
                            },
                            optionLabel = { cipherLabel(it) },
                            enabled = !currentSnapshot.readOnly,
                            onSelected = { form = form!!.copy(cipherAlgorithm = it) }
                        )
                        KeePassChoiceField(
                            label = stringResource(R.string.local_keepass_kdf_algorithm),
                            selected = form!!.kdfAlgorithm,
                            options = if (currentSnapshot.formatVersion == KeePassFormatVersion.KDBX3) {
                                listOf(KeePassKdfAlgorithm.AES_KDF)
                            } else {
                                KeePassKdfAlgorithm.entries
                            },
                            optionLabel = { kdfLabel(it) },
                            enabled = !currentSnapshot.readOnly,
                            onSelected = { form = form!!.copy(kdfAlgorithm = it) }
                        )
                        KeePassNumberField(
                            value = form!!.transformRounds,
                            onValueChange = { form = form!!.copy(transformRounds = it) },
                            label = stringResource(R.string.local_keepass_transform_rounds),
                            enabled = !currentSnapshot.readOnly
                        )
                        if (form!!.kdfAlgorithm != KeePassKdfAlgorithm.AES_KDF) {
                            KeePassNumberField(
                                value = form!!.memoryMb,
                                onValueChange = { form = form!!.copy(memoryMb = it) },
                                label = stringResource(R.string.local_keepass_kdf_memory_mb),
                                enabled = !currentSnapshot.readOnly
                            )
                            KeePassNumberField(
                                value = form!!.parallelism,
                                onValueChange = { form = form!!.copy(parallelism = it) },
                                label = stringResource(R.string.local_keepass_kdf_parallelism),
                                enabled = !currentSnapshot.readOnly
                            )
                        }
                        Text(
                            stringResource(R.string.keepass_database_encryption_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    KeePassSettingsSection(
                        icon = Icons.Default.Key,
                        title = stringResource(R.string.keepass_database_master_key)
                    ) {
                        currentSnapshot.masterKeyChangedAt?.let { changedAt ->
                            Text(
                                stringResource(
                                    R.string.keepass_database_master_key_changed,
                                    DateFormat.getDateTimeInstance().format(Date(changedAt.toEpochMilli()))
                                ),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            stringResource(R.string.keepass_database_master_key_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(
                            onClick = {
                                masterPasswordError = false
                                showMasterPasswordVerification = true
                            },
                            enabled = !currentSnapshot.readOnly,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Password, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.keepass_database_change_master_key))
                        }
                    }
                }

                item {
                    KeePassSettingsSection(
                        icon = if (currentSnapshot.readOnly) Icons.Default.Lock else Icons.Default.LockOpen,
                        title = stringResource(R.string.keepass_database_access_control)
                    ) {
                        KeePassSettingsSwitchRow(
                            title = stringResource(R.string.keepass_database_read_only),
                            summary = stringResource(R.string.keepass_database_read_only_summary),
                            checked = currentSnapshot.readOnly,
                            onCheckedChange = { readOnly ->
                                viewModel.setKeePassDatabaseReadOnly(database.id, readOnly)
                                snapshot = currentSnapshot.copy(readOnly = readOnly)
                            }
                        )
                        if (currentSnapshot.readOnly) {
                            Text(
                                stringResource(R.string.keepass_database_read_only_active),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.lockKeePassDatabase(database.id)
                                onLocked()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.keepass_database_lock_now))
                        }
                    }
                }
            }
        }
    }

    if (showMasterPasswordVerification) {
        MasterPasswordDialog(
            onDismiss = { showMasterPasswordVerification = false },
            onConfirm = { password ->
                if (viewModel.verifyMonicaMasterPassword(password)) {
                    masterPasswordError = false
                    showMasterPasswordVerification = false
                    showCredentialDialog = true
                } else {
                    masterPasswordError = true
                }
            },
            isError = masterPasswordError
        )
    }

    if (showCredentialDialog) {
        KeePassMasterCredentialDialog(
            database = database,
            onDismiss = { showCredentialDialog = false },
            onSubmit = { password, keyFileMode, keyFileUri, keepCopy, onResult ->
                scope.launch {
                    viewModel.changeKeePassMasterCredentials(
                        databaseId = database.id,
                        newPassword = password,
                        keyFileMode = keyFileMode,
                        replacementKeyFileUri = keyFileUri,
                        keepInternalKeyFileCopy = keepCopy
                    ).onSuccess { result ->
                        snapshot = result.settings.copy(readOnly = false)
                        showCredentialDialog = false
                        onDatabaseChanged()
                        onResult(null)
                    }.onFailure { failure ->
                        onResult(failure.message ?: failure.javaClass.simpleName)
                    }
                }
            }
        )
    }
}

@Composable
private fun KeePassSettingsLoadError(
    modifier: Modifier,
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun KeePassSettingsSummaryCard(snapshot: KeePassDatabaseSettingsSnapshot) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (snapshot.readOnly) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            }
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (snapshot.readOnly) Icons.Default.Lock else Icons.Default.Storage,
                contentDescription = null,
                tint = if (snapshot.readOnly) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(snapshot.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${formatLabel(snapshot.formatVersion)} · ${cipherLabel(snapshot.cipherAlgorithm)} · ${kdfLabel(snapshot.kdfAlgorithm)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = if (snapshot.readOnly) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
            ) {
                Text(
                    stringResource(
                        if (snapshot.readOnly) R.string.keepass_database_read_only_short
                        else R.string.keepass_database_writable_short
                    ),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    color = if (snapshot.readOnly) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun KeePassSettingsSection(
    icon: ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun KeePassSettingsSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun KeePassNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            if (next.isEmpty() || next == "-" || next.all { it.isDigit() } ||
                (next.startsWith("-") && next.drop(1).all { it.isDigit() })
            ) {
                onValueChange(next)
            }
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun <T> KeePassChoiceField(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    enabled: Boolean,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            onClick = { expanded = true }
        ) {
            ListItem(
                headlineContent = { Text(optionLabel(selected)) },
                supportingContent = { Text(label) },
                trailingContent = { Icon(Icons.Default.ExpandMore, contentDescription = null) }
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun KeePassGroupChoice(
    label: String,
    selectedUuid: UUID?,
    groups: List<KeePassDatabaseGroupOption>,
    nullLabel: String,
    enabled: Boolean,
    onSelected: (UUID?) -> Unit
) {
    val selected = groups.firstOrNull { it.uuid == selectedUuid }
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            onClick = { expanded = true }
        ) {
            ListItem(
                headlineContent = { Text(selected?.path ?: nullLabel) },
                supportingContent = { Text(label) },
                trailingContent = { Icon(Icons.Default.ExpandMore, contentDescription = null) }
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(nullLabel) },
                onClick = {
                    expanded = false
                    onSelected(null)
                }
            )
            groups.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group.path) },
                    onClick = {
                        expanded = false
                        onSelected(group.uuid)
                    }
                )
            }
        }
    }
}

@Composable
private fun KeePassMasterCredentialDialog(
    database: LocalKeePassDatabase,
    onDismiss: () -> Unit,
    onSubmit: (
        password: String,
        keyFileMode: KeePassKeyFileChangeMode,
        keyFileUri: Uri?,
        keepCopy: Boolean,
        onResult: (String?) -> Unit
    ) -> Unit
) {
    val hasCurrentKeyFile = !database.keyFileUri.isNullOrBlank() || !database.keyFileInternalPath.isNullOrBlank()
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var keyFileMode by remember {
        mutableStateOf(if (hasCurrentKeyFile) KeePassKeyFileChangeMode.KEEP_CURRENT else KeePassKeyFileChangeMode.REMOVE)
    }
    var keyFileUri by remember { mutableStateOf<Uri?>(null) }
    var keyFileName by remember { mutableStateOf("") }
    var keepCopy by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val keyFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        keyFileUri = uri
        keyFileName = uri?.lastPathSegment?.substringAfterLast('/').orEmpty()
    }
    val keyAvailable = when (keyFileMode) {
        KeePassKeyFileChangeMode.KEEP_CURRENT -> hasCurrentKeyFile
        KeePassKeyFileChangeMode.REMOVE -> false
        KeePassKeyFileChangeMode.REPLACE -> keyFileUri != null
    }
    val valid = password == confirmPassword && (password.isNotBlank() || keyAvailable)

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        icon = { Icon(Icons.Default.Key, contentDescription = null) },
        title = { Text(stringResource(R.string.keepass_database_change_master_key)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    ) {
                        Text(
                            stringResource(R.string.keepass_database_master_key_danger),
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                error?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.error) }
                }
                item {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.keepass_database_new_master_password)) },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = keepassCredentialKeyboardOptions()
                    )
                }
                item {
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text(stringResource(R.string.confirm_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        isError = confirmPassword.isNotEmpty() && confirmPassword != password,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = keepassCredentialKeyboardOptions()
                    )
                }
                item { Text(stringResource(R.string.keepass_database_key_file_mode), fontWeight = FontWeight.SemiBold) }
                if (hasCurrentKeyFile) {
                    item {
                        KeePassCredentialModeRow(
                            selected = keyFileMode == KeePassKeyFileChangeMode.KEEP_CURRENT,
                            title = stringResource(R.string.keepass_database_keep_current_key_file),
                            onClick = { keyFileMode = KeePassKeyFileChangeMode.KEEP_CURRENT }
                        )
                    }
                }
                item {
                    KeePassCredentialModeRow(
                        selected = keyFileMode == KeePassKeyFileChangeMode.REMOVE,
                        title = stringResource(R.string.keepass_database_no_key_file),
                        onClick = { keyFileMode = KeePassKeyFileChangeMode.REMOVE }
                    )
                }
                item {
                    KeePassCredentialModeRow(
                        selected = keyFileMode == KeePassKeyFileChangeMode.REPLACE,
                        title = stringResource(R.string.keepass_database_replace_key_file),
                        onClick = { keyFileMode = KeePassKeyFileChangeMode.REPLACE }
                    )
                }
                if (keyFileMode == KeePassKeyFileChangeMode.REPLACE) {
                    item {
                        OutlinedButton(
                            onClick = { keyFileLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Storage, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                keyFileName.ifBlank {
                                    stringResource(R.string.local_keepass_select_key_file)
                                }
                            )
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { keepCopy = !keepCopy },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = keepCopy, onCheckedChange = { keepCopy = it })
                            Column {
                                Text(stringResource(R.string.local_keepass_keep_key_file_copy))
                                Text(
                                    stringResource(R.string.local_keepass_keep_key_file_copy_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    submitting = true
                    error = null
                    onSubmit(password, keyFileMode, keyFileUri, keepCopy) { failure ->
                        error = failure
                        submitting = false
                    }
                },
                enabled = valid && !submitting
            ) {
                if (submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun KeePassCredentialModeRow(
    selected: Boolean,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(title)
    }
}

private data class KeePassDatabaseSettingsForm(
    val name: String,
    val description: String,
    val defaultUsername: String,
    val color: String,
    val maintenanceHistoryDays: String,
    val historyMaxItems: String,
    val historyMaxSizeMb: String,
    val recycleBinEnabled: Boolean,
    val recycleBinGroupUuid: UUID?,
    val templateGroupUuid: UUID?,
    val compression: KeePassDatabaseCompression,
    val cipherAlgorithm: KeePassCipherAlgorithm,
    val kdfAlgorithm: KeePassKdfAlgorithm,
    val transformRounds: String,
    val memoryMb: String,
    val parallelism: String,
    val masterKeyChangeRecommendationDays: Int,
    val masterKeyChangeForceDays: Int
) {
    fun toUpdateOrNull(): KeePassDatabaseSettingsUpdate? {
        val maintenance = maintenanceHistoryDays.toIntOrNull() ?: return null
        val maxItems = historyMaxItems.toIntOrNull() ?: return null
        val maxSizeMbValue = historyMaxSizeMb.toLongOrNull() ?: return null
        val rounds = transformRounds.toLongOrNull() ?: return null
        val memoryMbValue = memoryMb.toLongOrNull() ?: return null
        val parallelismValue = parallelism.toIntOrNull() ?: return null
        if (name.trim().isEmpty()) return null
        if (maxSizeMbValue > Int.MAX_VALUE / (1024L * 1024L)) return null
        return KeePassDatabaseSettingsUpdate(
            name = name.trim(),
            description = description,
            defaultUsername = defaultUsername,
            color = color.trim().takeIf { it.isNotEmpty() },
            maintenanceHistoryDays = maintenance,
            historyMaxItems = maxItems,
            historyMaxSizeBytes = if (maxSizeMbValue < 0) -1 else (maxSizeMbValue * 1024L * 1024L).toInt(),
            masterKeyChangeRecommendationDays = masterKeyChangeRecommendationDays,
            masterKeyChangeForceDays = masterKeyChangeForceDays,
            recycleBinEnabled = recycleBinEnabled,
            recycleBinGroupUuid = recycleBinGroupUuid,
            templateGroupUuid = templateGroupUuid,
            compression = compression,
            cipherAlgorithm = cipherAlgorithm,
            kdfAlgorithm = kdfAlgorithm,
            transformRounds = rounds,
            memoryBytes = memoryMbValue * 1024L * 1024L,
            parallelism = parallelismValue
        )
    }
}

private fun KeePassDatabaseSettingsSnapshot.toForm(): KeePassDatabaseSettingsForm =
    KeePassDatabaseSettingsForm(
        name = name,
        description = description,
        defaultUsername = defaultUsername,
        color = color.orEmpty(),
        maintenanceHistoryDays = maintenanceHistoryDays.toString(),
        historyMaxItems = historyMaxItems.toString(),
        historyMaxSizeMb = if (historyMaxSizeBytes < 0) "-1" else
            (historyMaxSizeBytes / (1024 * 1024)).toString(),
        recycleBinEnabled = recycleBinEnabled,
        recycleBinGroupUuid = recycleBinGroupUuid,
        templateGroupUuid = templateGroupUuid,
        compression = compression,
        cipherAlgorithm = cipherAlgorithm,
        kdfAlgorithm = kdfAlgorithm,
        transformRounds = transformRounds.toString(),
        memoryMb = (memoryBytes / (1024L * 1024L)).toString(),
        parallelism = parallelism.toString(),
        masterKeyChangeRecommendationDays = masterKeyChangeRecommendationDays,
        masterKeyChangeForceDays = masterKeyChangeForceDays
    )

@Composable
private fun compressionLabel(value: KeePassDatabaseCompression): String = when (value) {
    KeePassDatabaseCompression.NONE -> stringResource(R.string.keepass_database_compression_none)
    KeePassDatabaseCompression.GZIP -> stringResource(R.string.keepass_database_compression_gzip)
}

@Composable
private fun cipherLabel(value: KeePassCipherAlgorithm): String = when (value) {
    KeePassCipherAlgorithm.AES -> stringResource(R.string.local_keepass_cipher_aes)
    KeePassCipherAlgorithm.CHACHA20 -> stringResource(R.string.local_keepass_cipher_chacha20)
    KeePassCipherAlgorithm.TWOFISH -> stringResource(R.string.local_keepass_cipher_twofish)
}

@Composable
private fun kdfLabel(value: KeePassKdfAlgorithm): String = when (value) {
    KeePassKdfAlgorithm.AES_KDF -> stringResource(R.string.local_keepass_kdf_aes)
    KeePassKdfAlgorithm.ARGON2D -> stringResource(R.string.local_keepass_kdf_argon2d)
    KeePassKdfAlgorithm.ARGON2ID -> stringResource(R.string.local_keepass_kdf_argon2id)
}

@Composable
private fun formatLabel(value: KeePassFormatVersion): String = when (value) {
    KeePassFormatVersion.KDBX3 -> stringResource(R.string.local_keepass_kdbx3)
    KeePassFormatVersion.KDBX4 -> stringResource(R.string.local_keepass_kdbx4)
}
