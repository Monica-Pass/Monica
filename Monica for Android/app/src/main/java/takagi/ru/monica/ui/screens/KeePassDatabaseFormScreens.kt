package takagi.ru.monica.ui.screens

import takagi.ru.monica.ui.haptic.switchClick

import takagi.ru.monica.ui.haptic.MonicaSwitch as Switch

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import takagi.ru.monica.R
import takagi.ru.monica.data.KeePassCipherAlgorithm
import takagi.ru.monica.data.KeePassDatabaseCreationOptions
import takagi.ru.monica.data.KeePassDatabaseSourceType
import takagi.ru.monica.data.KeePassFormatVersion
import takagi.ru.monica.data.KeePassKdfAlgorithm
import takagi.ru.monica.data.KeePassStorageLocation
import takagi.ru.monica.data.KeePassSyncStatus
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.isRemoteSource
import takagi.ru.monica.data.toCreationOptions
import takagi.ru.monica.keepass.KeePassNativeResolvedRoute
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.utils.KeePassFileNameResolver
import takagi.ru.monica.utils.KeePassUriPermissionState

import takagi.ru.monica.ui.components.LocalFilledEntryForm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateKeePassDatabaseScreen(
    onDismiss: () -> Unit,
    onGenerateKeyFile: (Uri, (Boolean) -> Unit) -> Unit,
    busy: Boolean = false,
    error: String? = null,
    onCreate: (
        name: String,
        password: String,
        location: KeePassStorageLocation,
        externalUri: Uri?,
        keyFileUri: Uri?,
        options: KeePassDatabaseCreationOptions,
        keepKeyFileCopy: Boolean,
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var generatingKey by remember { mutableStateOf(false) }
    val active = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { active.set(false) } }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var storageLocation by remember { mutableStateOf(KeePassStorageLocation.INTERNAL) }
    var showPassword by remember { mutableStateOf(false) }
    var externalUri by remember { mutableStateOf<Uri?>(null) }

    var formatVersion by remember { mutableStateOf(KeePassFormatVersion.KDBX4) }
    var cipherAlgorithm by remember { mutableStateOf(KeePassCipherAlgorithm.AES) }
    var kdfAlgorithm by remember { mutableStateOf(KeePassKdfAlgorithm.ARGON2D) }
    var transformRounds by remember { mutableStateOf("8") }
    var memoryMb by remember {
        mutableStateOf((KeePassDatabaseCreationOptions.DEFAULT_ARGON_MEMORY_BYTES / 1024L / 1024L).toString())
    }
    var parallelism by remember { mutableStateOf("2") }
    var showAdvancedCryptoOptions by remember { mutableStateOf(false) }

    // 密钥文件相关状态
    var useKeyFile by remember { mutableStateOf(false) }
    var keyFileUri by remember { mutableStateOf<Uri?>(null) }
    var keyFileName by remember { mutableStateOf("") }
    var keepKeyFileCopy by remember { mutableStateOf(false) }

    // 外部存储选择器
    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) externalUri = uri
    }

    // 密钥文件选择器
    val keyFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            keyFileUri = it
            keyFileName = it.lastPathSegment?.substringAfterLast("/") ?: "keyfile"
        }
    }

    // 密钥文件生成器
    val createKeyFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/xml")
    ) { uri: Uri? ->
        uri?.let {
            // 调用 ViewModel 生成文件内容
            generatingKey = true
            onGenerateKeyFile(it) { success ->
                if (active.get()) {
                    generatingKey = false
                    if (success) {
                        keyFileUri = it
                        keyFileName = it.lastPathSegment?.substringAfterLast("/") ?: "new_keyfile.xml"
                    }
                }
            }
        }
    }

    val availableCipherOptions = remember(formatVersion) {
        if (formatVersion == KeePassFormatVersion.KDBX3) {
            listOf(KeePassCipherAlgorithm.AES, KeePassCipherAlgorithm.TWOFISH)
        } else {
            listOf(
                KeePassCipherAlgorithm.AES,
                KeePassCipherAlgorithm.CHACHA20,
                KeePassCipherAlgorithm.TWOFISH
            )
        }
    }
    val availableKdfOptions = remember(formatVersion) {
        if (formatVersion == KeePassFormatVersion.KDBX3) {
            listOf(KeePassKdfAlgorithm.AES_KDF)
        } else {
            listOf(
                KeePassKdfAlgorithm.ARGON2D,
                KeePassKdfAlgorithm.ARGON2ID,
                KeePassKdfAlgorithm.AES_KDF
            )
        }
    }

    LaunchedEffect(formatVersion) {
        if (cipherAlgorithm !in availableCipherOptions) {
            cipherAlgorithm = availableCipherOptions.first()
        }
        if (kdfAlgorithm !in availableKdfOptions) {
            kdfAlgorithm = availableKdfOptions.first()
        }
    }

    val roundsValue = transformRounds.toLongOrNull()
    val memoryMbValue = memoryMb.toLongOrNull()
    val parallelismValue = parallelism.toIntOrNull()
    val advancedOptionsValid = roundsValue != null && roundsValue > 0L &&
        (
            kdfAlgorithm == KeePassKdfAlgorithm.AES_KDF ||
                ((memoryMbValue != null && memoryMbValue > 0L) &&
                    (parallelismValue != null && parallelismValue > 0))
            )

    val isValid = name.isNotBlank() &&
                  (storageLocation == KeePassStorageLocation.INTERNAL || externalUri != null) &&
                  (
                    (password.isNotBlank() && password == confirmPassword) ||
                    (useKeyFile && keyFileUri != null)
                  ) &&
                  advancedOptionsValid && !busy && !generatingKey

    KeePassFormPage(
        title = stringResource(R.string.create_keepass_database),
        onDismiss = onDismiss,
        busy = busy || generatingKey,
        error = error,
        testTagPrefix = "keepass_create",
        actions = {
            Button(
                onClick = {
                    val options = KeePassDatabaseCreationOptions(
                        formatVersion = formatVersion,
                        cipherAlgorithm = cipherAlgorithm,
                        kdfAlgorithm = kdfAlgorithm,
                        transformRounds = roundsValue ?: 8L,
                        memoryBytes = ((memoryMbValue ?: 32L) * 1024L * 1024L),
                        parallelism = parallelismValue ?: 2
                    ).normalized()
                    onCreate(
                        name,
                        password,
                        storageLocation,
                        externalUri,
                        if (useKeyFile) keyFileUri else null,
                        options,
                        useKeyFile && keepKeyFileCopy,
                    )
                },
                enabled = isValid,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text(
                    stringResource(R.string.create),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    ) {

            // 表单区域
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 数据库名称
                OutlinedTextField(
                    saveTextState = false,
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.database_name)) },
                    placeholder = { Text(stringResource(R.string.database_name_placeholder)) },
                    singleLine = true,
                    shape = DatabaseManagementFieldShape,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Label, contentDescription = null) }
                )

                // 密码
                OutlinedTextField(
                    saveTextState = false,
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.database_password)) },
                    singleLine = true,
                    shape = DatabaseManagementFieldShape,
                    visualTransformation = if (showPassword)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    keyboardOptions = keepassCredentialKeyboardOptions(),
                    leadingIcon = { Icon(Icons.Default.Password, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // 确认密码
                OutlinedTextField(
                    saveTextState = false,
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text(stringResource(R.string.confirm_password)) },
                    singleLine = true,
                    shape = DatabaseManagementFieldShape,
                    visualTransformation = if (showPassword)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    keyboardOptions = keepassCredentialKeyboardOptions(),
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    isError = confirmPassword.isNotBlank() && password != confirmPassword,
                    supportingText = if (confirmPassword.isNotBlank() && password != confirmPassword) {
                        { Text(stringResource(R.string.password_mismatch)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 安全设置区
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.local_keepass_security_options),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )

                // 密钥文件开关
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    onClick = switchClick(useKeyFile) { useKeyFile = it }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.local_keepass_use_key_file),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                stringResource(R.string.local_keepass_use_key_file_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = useKeyFile,
                            onCheckedChange = { useKeyFile = it }
                        )
                    }
                }

                // 密钥文件选择
                AnimatedVisibility(visible = useKeyFile) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                    saveTextState = false,
                            value = keyFileName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.local_keepass_key_file)) },
                            placeholder = { Text(stringResource(R.string.local_keepass_key_file_pick_or_generate)) },
                            shape = DatabaseManagementFieldShape,
                            leadingIcon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { keyFilePickerLauncher.launch(arrayOf("*/*")) }
                        )
                        KeePassFormButtonGroup(
                            firstLabel = stringResource(R.string.local_keepass_select_existing_key_file),
                            secondLabel = stringResource(R.string.local_keepass_generate_new_key_file),
                            firstClick = { keyFilePickerLauncher.launch(arrayOf("*/*")) },
                            secondClick = { createKeyFileLauncher.launch("monica.key") },
                            enabled = !busy && !generatingKey,
                        )
                        if (keyFileUri != null) {
                            TextButton(onClick = { keyFileUri = null; keyFileName = ""; keepKeyFileCopy = false }) {
                                Text(stringResource(R.string.remove))
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { keepKeyFileCopy = !keepKeyFileCopy }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = keepKeyFileCopy,
                                    onCheckedChange = { keepKeyFileCopy = it },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.local_keepass_keep_key_file_copy),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        stringResource(R.string.local_keepass_keep_key_file_copy_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    onClick = { showAdvancedCryptoOptions = !showAdvancedCryptoOptions }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.local_keepass_advanced_crypto_options),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (showAdvancedCryptoOptions) {
                                stringResource(R.string.collapse)
                            } else {
                                stringResource(R.string.expand)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = if (showAdvancedCryptoOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                AnimatedVisibility(visible = showAdvancedCryptoOptions) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        KeepassOptionDropdown(
                            label = stringResource(R.string.local_keepass_kdbx_version),
                            selectedText = when (formatVersion) {
                                KeePassFormatVersion.KDBX3 -> stringResource(R.string.local_keepass_kdbx3)
                                KeePassFormatVersion.KDBX4 -> stringResource(R.string.local_keepass_kdbx4)
                            },
                            options = KeePassFormatVersion.entries,
                            optionLabel = { option ->
                                when (option) {
                                    KeePassFormatVersion.KDBX3 -> stringResource(R.string.local_keepass_kdbx3)
                                    KeePassFormatVersion.KDBX4 -> stringResource(R.string.local_keepass_kdbx4)
                                }
                            },
                            onSelected = { formatVersion = it }
                        )

                        KeepassOptionDropdown(
                            label = stringResource(R.string.local_keepass_cipher_algorithm),
                            selectedText = cipherAlgorithm.toReadableLabel(),
                            options = availableCipherOptions,
                            optionLabel = { it.toReadableLabel() },
                            onSelected = { cipherAlgorithm = it }
                        )

                        KeepassOptionDropdown(
                            label = stringResource(R.string.local_keepass_kdf_algorithm),
                            selectedText = kdfAlgorithm.toReadableLabel(),
                            options = availableKdfOptions,
                            optionLabel = { it.toReadableLabel() },
                            onSelected = { kdfAlgorithm = it }
                        )

                        OutlinedTextField(
                    saveTextState = false,
                            value = transformRounds,
                            onValueChange = { transformRounds = it.filter(Char::isDigit) },
                            label = { Text(stringResource(R.string.local_keepass_transform_rounds)) },
                            singleLine = true,
                            shape = DatabaseManagementFieldShape,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )

                        AnimatedVisibility(visible = kdfAlgorithm != KeePassKdfAlgorithm.AES_KDF) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                    saveTextState = false,
                                    value = memoryMb,
                                    onValueChange = { memoryMb = it.filter(Char::isDigit) },
                                    label = { Text(stringResource(R.string.local_keepass_kdf_memory_mb)) },
                                    singleLine = true,
                                    shape = DatabaseManagementFieldShape,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                    saveTextState = false,
                                    value = parallelism,
                                    onValueChange = { parallelism = it.filter(Char::isDigit) },
                                    label = { Text(stringResource(R.string.local_keepass_kdf_parallelism)) },
                                    singleLine = true,
                                    shape = DatabaseManagementFieldShape,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 存储位置选择
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.storage_location),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 内部存储卡片
                    StorageCard(
                        icon = Icons.Outlined.PhoneAndroid,
                        title = stringResource(R.string.internal_storage),
                        selected = storageLocation == KeePassStorageLocation.INTERNAL,
                        onClick = { storageLocation = KeePassStorageLocation.INTERNAL },
                        modifier = Modifier.weight(1f)
                    )

                    // 外部存储卡片
                    StorageCard(
                        icon = Icons.Outlined.SdStorage,
                        title = stringResource(R.string.external_storage),
                        selected = storageLocation == KeePassStorageLocation.EXTERNAL,
                        onClick = {
                            storageLocation = KeePassStorageLocation.EXTERNAL
                            if (externalUri == null) {
                                directoryPickerLauncher.launch(null)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // 外部存储路径显示
                AnimatedVisibility(visible = storageLocation == KeePassStorageLocation.EXTERNAL) {
                    Surface(
                        shape = DatabaseManagementFieldShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        onClick = { directoryPickerLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                if (externalUri != null)
                                    stringResource(R.string.location_selected)
                                else
                                    stringResource(R.string.select_location),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

    }
}

/**
 * KeePass 参数下拉框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> KeepassOptionDropdown(
    label: String,
    selectedText: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
                    saveTextState = false,
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            shape = RoundedCornerShape(12.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
internal fun KeePassCipherAlgorithm.toReadableLabel(): String {
    return when (this) {
        KeePassCipherAlgorithm.AES -> stringResource(R.string.local_keepass_cipher_aes)
        KeePassCipherAlgorithm.CHACHA20 -> stringResource(R.string.local_keepass_cipher_chacha20)
        KeePassCipherAlgorithm.TWOFISH -> stringResource(R.string.local_keepass_cipher_twofish)
    }
}

@Composable
internal fun KeePassKdfAlgorithm.toReadableLabel(): String {
    return when (this) {
        KeePassKdfAlgorithm.AES_KDF -> stringResource(R.string.local_keepass_kdf_aes)
        KeePassKdfAlgorithm.ARGON2D -> stringResource(R.string.local_keepass_kdf_argon2d)
        KeePassKdfAlgorithm.ARGON2ID -> stringResource(R.string.local_keepass_kdf_argon2id)
    }
}

/**
 * 存储位置选择卡片
 */
@Composable
private fun StorageCard(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected)
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else
            null,
        modifier = modifier.height(80.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 导入外部数据库对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenKeePassDatabaseScreen(
    uri: Uri,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    error: String? = null,
    onImport: (name: String, password: String, keyFileUri: Uri?, keepKeyFileCopy: Boolean) -> Unit
) {
    val context = LocalContext.current
    val providerDisplayName by produceState<String?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            KeePassFileNameResolver.queryDisplayName(context.contentResolver, uri)
        }
    }

    var name by remember(uri) {
        mutableStateOf(
            KeePassFileNameResolver.databaseNameFromCandidates(
                displayName = null,
                uriLastPathSegment = uri.lastPathSegment
            ) ?: KeePassFileNameResolver.DEFAULT_DATABASE_NAME
        )
    }
    var nameEdited by remember(uri) { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var keyFileUri by remember { mutableStateOf<Uri?>(null) }
    var keyFileName by remember { mutableStateOf("") }
    var keepKeyFileCopy by remember { mutableStateOf(false) }

    // URI 的末段可能只是 document:数字；拿到 DISPLAY_NAME 后再补上真实文件名，
    // 但不能覆盖用户已经手动修改过的名称。
    LaunchedEffect(providerDisplayName, nameEdited) {
        if (!nameEdited) {
            KeePassFileNameResolver.databaseNameFromCandidates(
                displayName = providerDisplayName,
                uriLastPathSegment = uri.lastPathSegment
            )?.let { resolvedName -> name = resolvedName }
        }
    }

    val keyFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { selectedUri: Uri? ->
        selectedUri?.let {
            keyFileUri = it
            keyFileName = KeePassFileNameResolver.displayFileNameFromCandidates(
                displayName = null,
                uriLastPathSegment = it.lastPathSegment
            )
        }
    }

    val isValid = name.isNotBlank() && (password.isNotBlank() || keyFileUri != null)

    KeePassFormPage(
        title = stringResource(R.string.import_existing_database),
        onDismiss = onDismiss,
        busy = busy,
        error = error,
        testTagPrefix = "keepass_open",
        actions = {
            Button(onClick = { onImport(name, password, keyFileUri, keepKeyFileCopy) },
                enabled = isValid && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.open_existing))
            }
        }
    ) {
                // 文件位置提示
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.SdStorage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            KeePassFileNameResolver.displayFileNameFromCandidates(
                                displayName = providerDisplayName,
                                uriLastPathSegment = uri.lastPathSegment
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 数据库显示名称
                OutlinedTextField(
                    saveTextState = false,
                    shape = DatabaseManagementFieldShape,
                    value = name,
                    onValueChange = {
                        nameEdited = true
                        name = it
                    },
                    label = { Text(stringResource(R.string.database_name)) },
                    placeholder = { Text(stringResource(R.string.database_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 密码
                OutlinedTextField(
                    saveTextState = false,
                    shape = DatabaseManagementFieldShape,
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.database_password)) },
                    singleLine = true,
                    visualTransformation = if (showPassword)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    keyboardOptions = keepassCredentialKeyboardOptions(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    supportingText = {
                        Text(stringResource(R.string.enter_database_password_hint))
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    saveTextState = false,
                    shape = DatabaseManagementFieldShape,
                    value = keyFileName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.local_keepass_key_file_optional)) },
                    placeholder = { Text(stringResource(R.string.local_keepass_key_file_tap_to_select)) },
                    trailingIcon = {
                        IconButton(onClick = { keyFilePickerLauncher.launch(arrayOf("*/*")) }) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = stringResource(R.string.local_keepass_select_key_file)
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { keyFilePickerLauncher.launch(arrayOf("*/*")) }
                )

                Text(
                    if (keyFileUri == null) {
                        stringResource(R.string.local_keepass_no_key_file_selected)
                    } else {
                        stringResource(R.string.local_keepass_key_file_selected)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
                KeePassFormButtonGroup(
                    firstLabel = stringResource(R.string.local_keepass_select_key_file),
                    secondLabel = stringResource(R.string.remove),
                    firstClick = { keyFilePickerLauncher.launch(arrayOf("*/*")) },
                    secondClick = { keyFileUri = null; keyFileName = ""; keepKeyFileCopy = false },
                    enabled = !busy,
                    secondEnabled = keyFileUri != null,
                )
                if (keyFileUri != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { keepKeyFileCopy = !keepKeyFileCopy },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = keepKeyFileCopy,
                            onCheckedChange = { keepKeyFileCopy = it },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.local_keepass_keep_key_file_copy),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.local_keepass_keep_key_file_copy_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
    }
}
