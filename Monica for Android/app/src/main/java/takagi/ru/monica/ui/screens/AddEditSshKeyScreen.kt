package takagi.ru.monica.ui.screens

import takagi.ru.monica.ui.components.animateMonicaContentSize
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.LOGIN_TYPE_SSH_KEY
import takagi.ru.monica.data.model.SshKeyData
import takagi.ru.monica.data.model.SshKeyDataCodec
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.normalizedStorageTargets
import takagi.ru.monica.data.model.toStorageTarget
import takagi.ru.monica.data.model.withoutStorageTarget
import takagi.ru.monica.ui.components.CustomFieldEditCard
import takagi.ru.monica.ui.components.CustomFieldSectionHeader
import takagi.ru.monica.ui.components.EntryTypeChip
import takagi.ru.monica.ui.components.EntryTypeChipOption
import takagi.ru.monica.ui.components.MultiStorageTargetPickerBottomSheet
import takagi.ru.monica.ui.components.MultiStorageTargetSelectorCard
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.ui.components.SshKeyGenerationProgressIndicator
import takagi.ru.monica.ui.components.buildMultiStorageTarget
import takagi.ru.monica.ui.icons.MonicaIcons
import takagi.ru.monica.utils.ClipboardUtils
import takagi.ru.monica.utils.SshKeyGenerator
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel

/**
 * SSH 密钥添加 / 编辑页面。
 *
 * 与 [AddEditWifiScreen] 结构一致：顶部选择多存储目标，下方渲染密钥字段卡片、
 * 自定义字段。支持在编辑前「生成」一个新密钥覆盖当前表单。
 *
 * 不需要接入 `pendingQrResult` 等能力（SSH 密钥不通过扫码导入）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditSshKeyScreen(
    viewModel: PasswordViewModel,
    localKeePassViewModel: LocalKeePassViewModel? = null,
    passwordId: Long?,
    initialCategoryId: Long? = null,
    initialKeePassDatabaseId: Long? = null,
    initialKeePassGroupPath: String? = null,
    initialBitwardenVaultId: Long? = null,
    initialBitwardenFolderId: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateToApiToken: () -> Unit = {},
    onNavigateToPassword: () -> Unit,
    onNavigateToBarcode: () -> Unit = onNavigateToPassword,
    onNavigateToWifi: () -> Unit,
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
    val bitwardenFolderDao = remember { database.bitwardenFolderDao() }

    // 表单状态
    var loadedEntry by remember { mutableStateOf<PasswordEntry?>(null) }
    var keyMetadata by remember(passwordId) { mutableStateOf(SshKeyData()) }
    var initialLoadDone by remember { mutableStateOf(passwordId == null || passwordId <= 0L) }
    var title by rememberSaveable { mutableStateOf("") }
    var algorithm by rememberSaveable { mutableStateOf(SshKeyGenerator.DEFAULT_ALGORITHM) }
    var rsaKeySize by rememberSaveable { mutableStateOf(SshKeyGenerator.DEFAULT_RSA_KEY_SIZE) }
    var publicKey by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var comment by rememberSaveable { mutableStateOf("") }
    var isFavorite by rememberSaveable { mutableStateOf(false) }
    var privateKeyVisible by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }

    // 存储目标（多选）
    val selectedStorageTargets = remember { mutableStateListOf<StorageTarget>() }
    var existingReplicaTargetKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showStorageSheet by remember { mutableStateOf(false) }

    val getBitwardenFolders: (Long) -> kotlinx.coroutines.flow.Flow<List<takagi.ru.monica.data.bitwarden.BitwardenFolder>> =
        remember(bitwardenFolderDao) {
            { vaultId -> bitwardenFolderDao.getFoldersByVaultFlow(vaultId) }
        }
    val getKeePassGroups: (Long) -> kotlinx.coroutines.flow.Flow<List<takagi.ru.monica.utils.KeePassGroupInfo>> =
        remember(localKeePassViewModel) {
            { databaseId ->
                localKeePassViewModel?.getGroups(databaseId)
                    ?: flowOf(emptyList())
            }
        }

    // 默认存储目标
    LaunchedEffect(
        initialCategoryId, initialKeePassDatabaseId, initialKeePassGroupPath,
        initialBitwardenVaultId, initialBitwardenFolderId,
        passwordId, currentFilter
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
        title = entry.title
        isFavorite = entry.isFavorite
        val sshData = SshKeyDataCodec.decode(entry.sshKeyData)
        if (sshData != null) {
            keyMetadata = sshData
            algorithm = sshData.algorithm.ifBlank { SshKeyGenerator.DEFAULT_ALGORITHM }
            rsaKeySize = sshData.keySize.takeIf { it in SshKeyGenerator.RSA_ALLOWED_KEY_SIZES }
                ?: SshKeyGenerator.DEFAULT_RSA_KEY_SIZE
            publicKey = sshData.publicKeyOpenSsh
            privateKey = sshData.privateKeyOpenSsh
            fingerprint = sshData.fingerprintSha256
            comment = sshData.comment
        }
        selectedStorageTargets.clear()
        selectedStorageTargets.add(entry.toStorageTarget())
        existingReplicaTargetKeys = setOf(entry.toStorageTarget().stableKey)
        initialLoadDone = true
    }

    // 自定义字段
    val customFields = remember { mutableStateListOf<CustomFieldDraft>() }
    LaunchedEffect(passwordId) {
        val id = passwordId ?: return@LaunchedEffect
        if (id <= 0L) return@LaunchedEffect
        val existing = viewModel.getCustomFieldsByEntryIdSync(id)
        customFields.clear()
        customFields.addAll(existing.map(CustomFieldDraft::fromCustomField))
    }

    val isEditing = passwordId != null && passwordId > 0L
    val canSave = initialLoadDone &&
        title.isNotBlank() &&
        publicKey.isNotBlank() &&
        !isGenerating
    val topBarTitle = stringResource(
        if (isEditing) R.string.edit_ssh_key_title else R.string.add_ssh_key_title
    )

    fun generateNewKey() {
        if (isGenerating) return
        isGenerating = true
        scope.launch {
            val request = when (algorithm) {
                SshKeyData.ALGORITHM_RSA -> SshKeyGenerator.Request.Rsa(rsaKeySize)
                else -> SshKeyGenerator.Request.Ed25519
            }
            val generated = withContext(Dispatchers.Default) {
                runCatching { SshKeyGenerator.generate(request, comment) }
            }
            isGenerating = false
            generated.onSuccess { data ->
                keyMetadata = data
                publicKey = data.publicKeyOpenSsh
                privateKey = data.privateKeyOpenSsh
                fingerprint = data.fingerprintSha256
                // keySize 会被 SshKeyGenerator 强制归一化（Ed25519=256）
                rsaKeySize = if (data.algorithm == SshKeyData.ALGORITHM_RSA) data.keySize else rsaKeySize
            }.onFailure {
                Toast.makeText(
                    context,
                    context.getString(R.string.ssh_key_generate_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun currentSshData(): SshKeyData = keyMetadata.copy(
        algorithm = algorithm,
        keySize = when (algorithm) {
            SshKeyData.ALGORITHM_RSA -> rsaKeySize
            else -> 256
        },
        publicKeyOpenSsh = publicKey.trim(),
        privateKeyOpenSsh = privateKey,
        fingerprintSha256 = fingerprint.trim(),
        comment = comment.trim(),
        format = keyMetadata.format
    )

    fun onSave() {
        if (!canSave) return
        val finalTitle = title.trim()
        val sshData = currentSshData()
        val encoded = SshKeyDataCodec.encode(sshData)
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
            notes = comment,
            isFavorite = isFavorite,
            loginType = LOGIN_TYPE_SSH_KEY,
            sshKeyData = encoded,
            wifiMetadata = "",
            authenticatorKey = ""
        )
        val originalIds = if (isEditing && loadedEntry != null) listOf(loadedEntry!!.id) else emptyList()
        viewModel.savePasswordsAcrossTargets(
            originalIds = originalIds,
            commonEntry = commonEntry,
            passwords = listOf(""), // SSH 条目没有 password 字段
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
                            Icon(
                                MonicaIcons.Navigation.back,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    },
                    actions = {
                        EntryTypeChip(
                            current = EntryTypeChipOption.SSH_KEY,
                            onSelect = { option ->
                                when (option) {
                                    EntryTypeChipOption.GPG_KEY -> Unit // Hidden here; creation starts in the password editor.
                                    EntryTypeChipOption.API_KEY -> Unit // Hidden here; creation starts in the password editor.
                                    EntryTypeChipOption.API_TOKEN -> onNavigateToApiToken()
                                    EntryTypeChipOption.PASSWORD -> onNavigateToPassword()
                                    EntryTypeChipOption.WIFI -> onNavigateToWifi()
                                    EntryTypeChipOption.SSH_KEY -> Unit
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
                SshKeyFormBody(
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
                    title = title, onTitleChange = { title = it },
                    algorithm = algorithm,
                    onAlgorithmChange = { algorithm = it },
                    rsaKeySize = rsaKeySize,
                    onRsaKeySizeChange = { rsaKeySize = it },
                    publicKey = publicKey,
                    onPublicKeyChange = { publicKey = it },
                    privateKey = privateKey,
                    onPrivateKeyChange = { privateKey = it },
                    privateKeyVisible = privateKeyVisible,
                    onPrivateKeyVisibleChange = { privateKeyVisible = it },
                    fingerprint = fingerprint,
                    comment = comment,
                    onCommentChange = { comment = it },
                    isGenerating = isGenerating,
                    onGenerate = { generateNewKey() },
                    onCopy = { label, text -> copyTextToClipboard(context, label, text) },
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
private fun SshKeyFormBody(
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
    title: String, onTitleChange: (String) -> Unit,
    algorithm: String, onAlgorithmChange: (String) -> Unit,
    rsaKeySize: Int, onRsaKeySizeChange: (Int) -> Unit,
    publicKey: String, onPublicKeyChange: (String) -> Unit,
    privateKey: String, onPrivateKeyChange: (String) -> Unit,
    privateKeyVisible: Boolean, onPrivateKeyVisibleChange: (Boolean) -> Unit,
    fingerprint: String,
    comment: String, onCommentChange: (String) -> Unit,
    isGenerating: Boolean,
    onGenerate: () -> Unit,
    onCopy: (label: String, text: String) -> Unit,
    customFields: List<CustomFieldDraft>,
    onCustomFieldsChange: (List<CustomFieldDraft>) -> Unit
) {
    val fieldShape = RoundedCornerShape(12.dp)
    val isRsa = algorithm.equals(SshKeyData.ALGORITHM_RSA, ignoreCase = true)

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

        // 名称
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            label = { Text(stringResource(R.string.title_required)) },
            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
            singleLine = true,
            shape = fieldShape,
            modifier = Modifier.fillMaxWidth()
        )

        // SSH 密钥主体卡片
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
                    .animateMonicaContentSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        stringResource(R.string.ssh_key_section_basic),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                SshKeyAlgorithmField(
                    selected = algorithm,
                    onSelect = onAlgorithmChange,
                    fieldShape = fieldShape
                )
                if (isRsa) {
                    SshKeyRsaSizeField(
                        selected = rsaKeySize,
                        onSelect = onRsaKeySizeChange,
                        fieldShape = fieldShape
                    )
                }

                FilledTonalButton(
                    onClick = onGenerate,
                    enabled = !isGenerating,
                    modifier = Modifier.fillMaxWidth(),
                    shape = fieldShape
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ssh_key_generate))
                }

                if (isGenerating) {
                    SshKeyGenerationProgressIndicator()
                }

                // 指纹
                OutlinedTextField(
                    value = fingerprint.ifEmpty {
                        stringResource(R.string.ssh_key_empty_placeholder)
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.ssh_key_fingerprint)) },
                    trailingIcon = {
                        if (fingerprint.isNotEmpty()) {
                            val copyLabel = stringResource(R.string.ssh_key_copy_fingerprint)
                            IconButton(onClick = { onCopy(copyLabel, fingerprint) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = copyLabel)
                            }
                        }
                    },
                    shape = fieldShape,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 2
                )

                // 公钥
                OutlinedTextField(
                    value = publicKey,
                    onValueChange = onPublicKeyChange,
                    label = { Text(stringResource(R.string.ssh_key_public_key)) },
                    trailingIcon = {
                        if (publicKey.isNotBlank()) {
                            val copyLabel = stringResource(R.string.ssh_key_copy_public)
                            IconButton(onClick = { onCopy(copyLabel, publicKey) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = copyLabel)
                            }
                        }
                    },
                    shape = fieldShape,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

                // 私钥
                OutlinedTextField(
                    value = privateKey,
                    onValueChange = onPrivateKeyChange,
                    label = { Text(stringResource(R.string.ssh_key_private_key)) },
                    visualTransformation = if (privateKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        Row {
                            val toggleLabel = stringResource(
                                if (privateKeyVisible) R.string.ssh_key_hide_private
                                else R.string.ssh_key_reveal_private
                            )
                            IconButton(onClick = { onPrivateKeyVisibleChange(!privateKeyVisible) }) {
                                Icon(
                                    if (privateKeyVisible) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    contentDescription = toggleLabel
                                )
                            }
                            if (privateKey.isNotBlank()) {
                                val copyLabel = stringResource(R.string.ssh_key_copy_private)
                                IconButton(onClick = { onCopy(copyLabel, privateKey) }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = copyLabel)
                                }
                            }
                        }
                    },
                    shape = fieldShape,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8
                )

                // 备注 / comment
                OutlinedTextField(
                    value = comment,
                    onValueChange = onCommentChange,
                    label = { Text(stringResource(R.string.ssh_key_comment)) },
                    singleLine = true,
                    shape = fieldShape,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 自定义字段
        CustomFieldSectionHeader(
            onAddClick = {
                onCustomFieldsChange(
                    customFields + CustomFieldDraft(
                        id = CustomFieldDraft.nextTempId(),
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
            CustomFieldEditCard(
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
private fun SshKeyAlgorithmField(
    selected: String,
    onSelect: (String) -> Unit,
    fieldShape: androidx.compose.ui.graphics.Shape
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when {
        selected.equals(SshKeyData.ALGORITHM_RSA, ignoreCase = true) ->
            stringResource(R.string.ssh_key_algorithm_rsa)
        else -> stringResource(R.string.ssh_key_algorithm_ed25519)
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.ssh_key_algorithm_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = fieldShape,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ssh_key_algorithm_ed25519)) },
                onClick = {
                    expanded = false
                    onSelect(SshKeyData.ALGORITHM_ED25519)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ssh_key_algorithm_rsa)) },
                onClick = {
                    expanded = false
                    onSelect(SshKeyData.ALGORITHM_RSA)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshKeyRsaSizeField(
    selected: Int,
    onSelect: (Int) -> Unit,
    fieldShape: androidx.compose.ui.graphics.Shape
) {
    var expanded by remember { mutableStateOf(false) }
    val options = SshKeyGenerator.RSA_ALLOWED_KEY_SIZES.sorted()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = "$selected bits",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.ssh_key_size_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = fieldShape,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { size ->
                DropdownMenuItem(
                    text = { Text("$size bits") },
                    onClick = {
                        expanded = false
                        onSelect(size)
                    }
                )
            }
        }
    }
}

private fun copyTextToClipboard(context: Context, label: String, text: String) {
    ClipboardUtils.copyToClipboard(context, text, label)
    Toast.makeText(
        context,
        context.getString(R.string.copied_to_clipboard),
        Toast.LENGTH_SHORT
    ).show()
}
