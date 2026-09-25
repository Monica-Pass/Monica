package takagi.ru.monica.ui.screens

import takagi.ru.monica.ui.components.MonicaExpandableContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.WifiData
import takagi.ru.monica.data.model.WifiIp
import takagi.ru.monica.data.model.WifiProxy
import takagi.ru.monica.data.model.WifiSecurity
import takagi.ru.monica.data.model.normalizedStorageTargets
import takagi.ru.monica.data.model.toStorageTarget
import takagi.ru.monica.data.model.withoutStorageTarget
import takagi.ru.monica.ui.components.EntryTypeChip
import takagi.ru.monica.ui.components.EntryTypeChipOption
import takagi.ru.monica.ui.components.MultiStorageTargetPickerBottomSheet
import takagi.ru.monica.ui.components.MultiStorageTargetSelectorCard
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.ui.components.buildMultiStorageTarget
import takagi.ru.monica.ui.icons.MonicaIcons
import takagi.ru.monica.utils.WifiQrParser
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel

/**
 * WIFI 添加/编辑页面。
 *
 * 结构参考 [AddEditPasswordScreen]：顶部选择存储位置（多目标），下方按
 * 分组（基础/安全/隐私/代理/IP 设置）展开可折叠卡片。
 *
 * 额外能力：从 [pendingQrResult] 解析 ZXing 约定的 `WIFI:T:..;S:..;P:..` 字串
 * 自动回填表单；顶部 "扫码" 按钮跳到二维码扫描页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditWifiScreen(
    viewModel: PasswordViewModel,
    localKeePassViewModel: LocalKeePassViewModel? = null,
    passwordId: Long?,
    initialCategoryId: Long? = null,
    initialKeePassDatabaseId: Long? = null,
    initialKeePassGroupPath: String? = null,
    initialBitwardenVaultId: Long? = null,
    initialBitwardenFolderId: String? = null,
    pendingQrResult: String? = null,
    onConsumePendingQrResult: () -> Unit = {},
    onScanQrCode: (() -> Unit)? = null,
    onNavigateBack: () -> Unit,
    onNavigateToApiToken: () -> Unit = {},
    onNavigateToPassword: () -> Unit,
    onNavigateToBarcode: () -> Unit = onNavigateToPassword,
    onNavigateToSshKey: (() -> Unit)? = null,
    onSaveCompleted: ((Long?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { PasswordDatabase.getDatabase(context) }
    val categories by viewModel.categories.collectAsState(initial = emptyList())
    val keepassDatabases by database.localKeePassDatabaseDao().getAllDatabases()
        .collectAsState(initial = emptyList<LocalKeePassDatabase>())
    val bitwardenVaults by database.bitwardenVaultDao().getAllVaultsFlow()
        .collectAsState(initial = emptyList<BitwardenVault>())
    val currentFilter by viewModel.categoryFilter.collectAsState()

    // 已保存条目（用于 loginType 判断、replica 信息回显等）
    var loadedEntry by remember { mutableStateOf<PasswordEntry?>(null) }
    var initialLoadDone by remember { mutableStateOf(passwordId == null || passwordId <= 0L) }

    // 表单状态（精简版：只保留连接 Wi-Fi 的核心字段）
    var title by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isFavorite by rememberSaveable { mutableStateOf(false) }
    var ssid by rememberSaveable { mutableStateOf("") }
    var hiddenNetwork by rememberSaveable { mutableStateOf(false) }
    var security by rememberSaveable { mutableStateOf(WifiSecurity.WPA2_WPA3) }

    // 存储目标（多选）
    val selectedStorageTargets = remember { mutableStateListOf<StorageTarget>() }
    var existingReplicaTargetKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showStorageSheet by remember { mutableStateOf(false) }
    val bitwardenFolderDao = remember { database.bitwardenFolderDao() }

    val getBitwardenFolders: (Long) -> kotlinx.coroutines.flow.Flow<List<takagi.ru.monica.data.bitwarden.BitwardenFolder>> =
        remember(bitwardenFolderDao) {
            { vaultId -> bitwardenFolderDao.getFoldersByVaultFlow(vaultId) }
        }
    val getKeePassGroups: (Long) -> kotlinx.coroutines.flow.Flow<List<takagi.ru.monica.utils.KeePassGroupInfo>> =
        remember(localKeePassViewModel) {
            { databaseId ->
                localKeePassViewModel?.getGroups(databaseId)
                    ?: flowOf(emptyList<takagi.ru.monica.utils.KeePassGroupInfo>())
            }
        }

    // 初始存储目标：先用 initial 参数；如果没有，按当前过滤上下文推断
    LaunchedEffect(
        initialCategoryId,
        initialKeePassDatabaseId,
        initialKeePassGroupPath,
        initialBitwardenVaultId,
        initialBitwardenFolderId,
        passwordId,
        currentFilter
    ) {
        if (passwordId != null && passwordId > 0L) return@LaunchedEffect
        val hasExplicit = initialCategoryId != null ||
            initialKeePassDatabaseId != null ||
            initialKeePassGroupPath != null ||
            initialBitwardenVaultId != null ||
            initialBitwardenFolderId != null
        if (hasExplicit) {
            selectedStorageTargets.clear()
            selectedStorageTargets.add(
                buildMultiStorageTarget(
                    categoryId = initialCategoryId,
                    keepassDatabaseId = initialKeePassDatabaseId,
                    keepassGroupPath = initialKeePassGroupPath,
                    bitwardenVaultId = initialBitwardenVaultId,
                    bitwardenFolderId = initialBitwardenFolderId
                )
            )
            return@LaunchedEffect
        }
        val defaultTarget = when (val filter = currentFilter) {
            is CategoryFilter.Custom -> StorageTarget.MonicaLocal(filter.categoryId)
            is CategoryFilter.KeePassDatabase -> StorageTarget.KeePass(filter.databaseId, null)
            is CategoryFilter.KeePassGroupFilter -> StorageTarget.KeePass(filter.databaseId, filter.groupPath)
            is CategoryFilter.KeePassDatabaseStarred -> StorageTarget.KeePass(filter.databaseId, null)
            is CategoryFilter.KeePassDatabaseUncategorized -> StorageTarget.KeePass(filter.databaseId, null)
            is CategoryFilter.BitwardenVault -> StorageTarget.Bitwarden(filter.vaultId, null)
            is CategoryFilter.BitwardenFolderFilter -> StorageTarget.Bitwarden(filter.vaultId, filter.folderId)
            is CategoryFilter.BitwardenVaultStarred -> StorageTarget.Bitwarden(filter.vaultId, null)
            is CategoryFilter.BitwardenVaultUncategorized -> StorageTarget.Bitwarden(filter.vaultId, null)
            else -> StorageTarget.MonicaLocal(null)
        }
        selectedStorageTargets.clear()
        selectedStorageTargets.add(defaultTarget)
    }

    // 回显已有条目
    LaunchedEffect(passwordId) {
        val id = passwordId ?: return@LaunchedEffect
        if (id <= 0L) return@LaunchedEffect
        val entry = viewModel.getPasswordEntryById(id) ?: run {
            initialLoadDone = true
            return@LaunchedEffect
        }
        loadedEntry = entry
        val meta = WifiData.fromJsonOrEmpty(entry.wifiMetadata)
        title = entry.title
        password = entry.password
        isFavorite = entry.isFavorite
        ssid = meta.ssid.ifEmpty { entry.title }
        hiddenNetwork = meta.hiddenNetwork
        security = meta.security
        selectedStorageTargets.clear()
        selectedStorageTargets.add(entry.toStorageTarget())
        existingReplicaTargetKeys = setOf(entry.toStorageTarget().stableKey)
        initialLoadDone = true
    }

    // 扫码结果回填
    LaunchedEffect(pendingQrResult) {
        val raw = pendingQrResult ?: return@LaunchedEffect
        val parsed = WifiQrParser.parse(raw)
        if (parsed != null) {
            ssid = parsed.ssid
            password = parsed.password
            security = parsed.security
            hiddenNetwork = parsed.hidden
            if (title.isBlank()) title = parsed.ssid
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.wifi_scan_success, parsed.ssid),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } else {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.wifi_scan_invalid),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        onConsumePendingQrResult()
    }

    val isEditing = passwordId != null && passwordId > 0L
    val canSave = initialLoadDone && (title.isNotBlank() || ssid.isNotBlank())
    val topBarTitle = stringResource(if (isEditing) R.string.edit_wifi_title else R.string.add_wifi_title)

    // 自定义字段状态
    val customFields = remember { mutableStateListOf<takagi.ru.monica.data.CustomFieldDraft>() }

    // 回显自定义字段
    LaunchedEffect(passwordId) {
        val id = passwordId ?: return@LaunchedEffect
        if (id <= 0L) return@LaunchedEffect
        val existing = viewModel.getCustomFieldsByEntryIdSync(id)
        customFields.clear()
        customFields.addAll(existing.map { takagi.ru.monica.data.CustomFieldDraft.fromCustomField(it) })
    }

    fun buildWifiData(): WifiData = WifiData(
        ssid = ssid.ifBlank { title }.trim(),
        hiddenNetwork = hiddenNetwork,
        security = security
    )

    fun onSave() {
        if (!canSave) return
        val finalTitle = title.ifBlank { ssid }.trim()
        val wifiData = buildWifiData()
        val commonEntry = (loadedEntry ?: PasswordEntry(
            title = finalTitle,
            website = "",
            username = "",
            password = "",
            notes = "",
            isFavorite = isFavorite
        )).copy(
            title = finalTitle,
            website = "",
            username = "",
            password = "",
            notes = "",
            isFavorite = isFavorite,
            loginType = "WIFI",
            wifiMetadata = wifiData.toJson()
        )
        val originalIds = if (isEditing && loadedEntry != null) {
            listOf(loadedEntry!!.id)
        } else emptyList()

        viewModel.savePasswordsAcrossTargets(
            originalIds = originalIds,
            commonEntry = commonEntry,
            passwords = listOf(password),
            targets = selectedStorageTargets.toList(),
            customFields = customFields.toList()
        ) { firstId ->
            onSaveCompleted?.invoke(firstId)
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                    title = {
                        Text(
                            topBarTitle,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(MonicaIcons.Navigation.back, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        EntryTypeChip(
                            current = EntryTypeChipOption.WIFI,
                            onSelect = { option ->
                                when (option) {
                                    EntryTypeChipOption.GPG_KEY -> Unit // Hidden here; creation starts in the password editor.
                                    EntryTypeChipOption.API_KEY -> Unit // Hidden here; creation starts in the password editor.
                                    EntryTypeChipOption.API_TOKEN -> onNavigateToApiToken()
                                    EntryTypeChipOption.PASSWORD -> onNavigateToPassword()
                                    EntryTypeChipOption.SSH_KEY -> onNavigateToSshKey?.invoke()
                                    EntryTypeChipOption.WIFI -> Unit
                                    EntryTypeChipOption.BARCODE -> onNavigateToBarcode()
                                }
                            },
                            enabled = !isEditing
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { isFavorite = !isFavorite }) {
                            Icon(
                                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = stringResource(R.string.favorite),
                                tint = if (isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onSave() },
                containerColor = if (canSave) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save))
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                WifiFormBody(
                    innerPadding = innerPadding,
                    selectedStorageTargets = selectedStorageTargets,
                    existingReplicaTargetKeys = existingReplicaTargetKeys,
                    categories = categories,
                    keepassDatabases = keepassDatabases,
                    bitwardenVaults = bitwardenVaults,
                    bitwardenFolderDao = bitwardenFolderDao,
                    isEditing = isEditing,
                    onAddTarget = { showStorageSheet = true },
                    onRemoveTarget = { t ->
                        val updatedTargets = selectedStorageTargets.withoutStorageTarget(t)
                        selectedStorageTargets.clear()
                        selectedStorageTargets.addAll(updatedTargets)
                    },
                    onScanQrCode = onScanQrCode,
                    title = title, onTitleChange = { title = it },
                    ssid = ssid, onSsidChange = { ssid = it },
                    password = password, onPasswordChange = { password = it },
                    hiddenNetwork = hiddenNetwork, onHiddenNetworkChange = { hiddenNetwork = it },
                    security = security, onSecurityChange = { security = it },
                    customFields = customFields,
                    onCustomFieldsChange = { updated ->
                        customFields.clear()
                        customFields.addAll(updated)
                    }
                )
            }
        }
    }

    MultiStorageTargetPickerBottomSheet(
        visible = showStorageSheet,
        selectedTargets = selectedStorageTargets.toList(),
        lockedTargetKeys = existingReplicaTargetKeys,
        categories = categories,
        keepassDatabases = keepassDatabases,
        bitwardenVaults = bitwardenVaults,
        getBitwardenFolders = getBitwardenFolders,
        getKeePassGroups = getKeePassGroups,
        onDismiss = { showStorageSheet = false },
        onSelectedTargetsChange = { targets ->
            val normalized = targets.normalizedStorageTargets()
            selectedStorageTargets.clear()
            selectedStorageTargets.addAll(normalized)
        }
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WifiFormBody(
    innerPadding: PaddingValues,
    selectedStorageTargets: List<StorageTarget>,
    existingReplicaTargetKeys: Set<String>,
    categories: List<takagi.ru.monica.data.Category>,
    keepassDatabases: List<LocalKeePassDatabase>,
    bitwardenVaults: List<BitwardenVault>,
    bitwardenFolderDao: takagi.ru.monica.data.bitwarden.BitwardenFolderDao,
    isEditing: Boolean,
    onAddTarget: () -> Unit,
    onRemoveTarget: (StorageTarget) -> Unit,
    onScanQrCode: (() -> Unit)?,
    title: String, onTitleChange: (String) -> Unit,
    ssid: String, onSsidChange: (String) -> Unit,
    password: String, onPasswordChange: (String) -> Unit,
    hiddenNetwork: Boolean, onHiddenNetworkChange: (Boolean) -> Unit,
    security: WifiSecurity, onSecurityChange: (WifiSecurity) -> Unit,
    customFields: List<takagi.ru.monica.data.CustomFieldDraft>,
    onCustomFieldsChange: (List<takagi.ru.monica.data.CustomFieldDraft>) -> Unit
) {
    val fieldShape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 顶部：存储位置选择
        MultiStorageTargetSelectorCard(
            selectedTargets = selectedStorageTargets,
            existingTargetKeys = existingReplicaTargetKeys,
            categories = categories,
            keepassDatabases = keepassDatabases,
            bitwardenVaults = bitwardenVaults,
            bitwardenFolderDao = bitwardenFolderDao,
            isEditing = isEditing,
            onAddTargetClick = onAddTarget,
            onRemoveTarget = onRemoveTarget
        )

        // 单卡片：所有字段 + 扫码
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 标题行
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        stringResource(R.string.wifi_section_basic),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }

                // 扫码按钮
                if (onScanQrCode != null) {
                    FilledTonalButton(
                        onClick = onScanQrCode,
                        modifier = Modifier.fillMaxWidth(),
                        shape = fieldShape
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.wifi_scan_qr))
                    }
                }

                // SSID（网络名称）
                OutlinedTextField(
                    value = ssid,
                    onValueChange = {
                        onSsidChange(it)
                        if (title.isBlank() || title == ssid) onTitleChange(it)
                    },
                    label = { Text(stringResource(R.string.wifi_ssid_required)) },
                    leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                    singleLine = true,
                    shape = fieldShape,
                    modifier = Modifier.fillMaxWidth()
                )

                // 安全性
                EnumDropdown(
                    label = stringResource(R.string.wifi_security_label),
                    value = security,
                    options = WifiSecurity.entries.filter {
                        it != WifiSecurity.WPA2_ENTERPRISE && it != WifiSecurity.WPA3_ENTERPRISE
                    },
                    optionLabel = { it.displayLabel() },
                    onValueChange = onSecurityChange,
                    fieldShape = fieldShape
                )

                // 密码（开放网络不显示）
                MonicaExpandableContent(expanded = security != WifiSecurity.NONE) {
                    var passwordVisible by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        label = { Text(stringResource(R.string.wifi_password_label)) },
                        singleLine = true,
                        shape = fieldShape,
                        visualTransformation = if (passwordVisible) {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        } else {
                            androidx.compose.ui.text.input.PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null
                                    )
                                }
                                IconButton(onClick = {
                                    val gen = takagi.ru.monica.utils.PasswordGenerator()
                                    onPasswordChange(gen.generatePassword())
                                }) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 隐藏网络
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.wifi_hidden_network),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(checked = hiddenNetwork, onCheckedChange = onHiddenNetworkChange)
                }
            }
        }

        // 自定义字段区域（与密码页一致）
        takagi.ru.monica.ui.components.CustomFieldSectionHeader(
            onAddClick = {
                onCustomFieldsChange(
                    customFields + takagi.ru.monica.data.CustomFieldDraft(
                        id = takagi.ru.monica.data.CustomFieldDraft.nextTempId(),
                        title = "",
                        value = "",
                        isProtected = false,
                        isPreset = false,
                        isRequired = false
                    )
                )
            }
        )
        customFields.forEachIndexed { index, field ->
            takagi.ru.monica.ui.components.CustomFieldEditCard(
                index = index,
                field = field,
                onFieldChange = { updated ->
                    val list = customFields.toMutableList()
                    list[index] = updated
                    onCustomFieldsChange(list)
                },
                onDelete = {
                    val list = customFields.toMutableList()
                    list.removeAt(index)
                    onCustomFieldsChange(list)
                }
            )
        }

        Spacer(Modifier.height(80.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(
    label: String,
    value: T,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onValueChange: (T) -> Unit,
    fieldShape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp)
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = optionLabel(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = fieldShape,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onValueChange(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun WifiSecurity.displayLabel(): String = when (this) {
    WifiSecurity.NONE -> stringResource(R.string.wifi_security_none)
    WifiSecurity.WEP -> stringResource(R.string.wifi_security_wep)
    WifiSecurity.WPA_WPA2 -> stringResource(R.string.wifi_security_wpa_wpa2)
    WifiSecurity.WPA2_WPA3 -> stringResource(R.string.wifi_security_wpa2_wpa3)
    WifiSecurity.WPA3 -> stringResource(R.string.wifi_security_wpa3)
    WifiSecurity.WPA2_ENTERPRISE -> stringResource(R.string.wifi_security_wpa2_enterprise)
    WifiSecurity.WPA3_ENTERPRISE -> stringResource(R.string.wifi_security_wpa3_enterprise)
}
