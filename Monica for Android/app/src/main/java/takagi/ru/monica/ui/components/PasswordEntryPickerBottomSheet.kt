package takagi.ru.monica.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import takagi.ru.monica.ui.components.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.data.bitwarden.BitwardenVault

private enum class PasswordPickerSourceFilter {
    ALL,
    LOCAL,
    KEEPASS,
    MDBX,
    BITWARDEN
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordEntryPickerBottomSheet(
    visible: Boolean,
    title: String,
    passwords: List<PasswordEntry>,
    selectedEntryId: Long? = null,
    onSelect: (PasswordEntry) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val context = LocalContext.current
    val database = remember(context) { PasswordDatabase.getDatabase(context) }
    val keepassDatabases by database.localKeePassDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val mdbxDatabases by database.localMdbxDatabaseDao().getAvailableDatabases().collectAsState(initial = emptyList())
    val bitwardenVaults by database.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
    var foldersByVault by remember { mutableStateOf<Map<Long, List<BitwardenFolder>>>(emptyMap()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(bitwardenVaults) {
        foldersByVault = withContext(Dispatchers.IO) {
            bitwardenVaults.associate { vault ->
                vault.id to database.bitwardenFolderDao().getFoldersByVault(vault.id)
            }
        }
    }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sourceFilter by rememberSaveable { mutableStateOf(PasswordPickerSourceFilter.ALL) }
    var selectedKeePassDatabaseId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedMdbxDatabaseId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedVaultId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedFolderId by rememberSaveable { mutableStateOf<String?>(null) }

    var keepassMenuExpanded by remember { mutableStateOf(false) }
    var mdbxMenuExpanded by remember { mutableStateOf(false) }
    var vaultMenuExpanded by remember { mutableStateOf(false) }
    var folderMenuExpanded by remember { mutableStateOf(false) }

    val keepassNameById = remember(keepassDatabases) {
        keepassDatabases.associate { it.id to it.name }
    }
    val mdbxNameById = remember(mdbxDatabases) {
        mdbxDatabases.associate { it.id to it.name }
    }
    val vaultLabelById = remember(bitwardenVaults) {
        bitwardenVaults.associate { vault ->
            val label = vault.displayName?.takeIf { it.isNotBlank() } ?: vault.email
            vault.id to label
        }
    }
    val selectedVaultFolders = remember(selectedVaultId, foldersByVault) {
        selectedVaultId?.let { foldersByVault[it] }.orEmpty()
    }
    val folderNameById = remember(selectedVaultFolders) {
        selectedVaultFolders.associate { it.bitwardenFolderId to it.name }
    }

    val filteredPasswords = remember(
        passwords,
        searchQuery,
        sourceFilter,
        selectedKeePassDatabaseId,
        selectedMdbxDatabaseId,
        selectedVaultId,
        selectedFolderId
    ) {
        val query = searchQuery.trim()
        passwords.filter { entry ->
            val matchesQuery = query.isBlank() || listOf(
                entry.title,
                entry.username,
                entry.website,
                entry.appName
            ).any { value -> value.contains(query, ignoreCase = true) }

            val matchesSource = when (sourceFilter) {
                PasswordPickerSourceFilter.ALL -> true
                PasswordPickerSourceFilter.LOCAL -> entry.isLocalOnlyEntry()
                PasswordPickerSourceFilter.KEEPASS -> {
                    val keepassId = entry.keepassDatabaseId
                    keepassId != null && (selectedKeePassDatabaseId == null || keepassId == selectedKeePassDatabaseId)
                }
                PasswordPickerSourceFilter.MDBX -> {
                    val mdbxId = entry.mdbxDatabaseId
                    mdbxId != null && (selectedMdbxDatabaseId == null || mdbxId == selectedMdbxDatabaseId)
                }
                PasswordPickerSourceFilter.BITWARDEN -> {
                    val vaultId = entry.bitwardenVaultId
                    val folderId = entry.bitwardenFolderId
                    vaultId != null &&
                        (selectedVaultId == null || vaultId == selectedVaultId) &&
                        (selectedFolderId == null || folderId == selectedFolderId)
                }
            }

            matchesQuery && matchesSource
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    MonicaModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.password_picker_results_count, filteredPasswords.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent
                )
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = sourceFilter == PasswordPickerSourceFilter.ALL,
                    onClick = {
                        sourceFilter = PasswordPickerSourceFilter.ALL
                        selectedKeePassDatabaseId = null
                        selectedMdbxDatabaseId = null
                        selectedVaultId = null
                        selectedFolderId = null
                    },
                    label = { Text(stringResource(R.string.filter_all)) }
                )
                FilterChip(
                    selected = sourceFilter == PasswordPickerSourceFilter.LOCAL,
                    onClick = {
                        sourceFilter = PasswordPickerSourceFilter.LOCAL
                        selectedKeePassDatabaseId = null
                        selectedMdbxDatabaseId = null
                        selectedVaultId = null
                        selectedFolderId = null
                    },
                    label = { Text(stringResource(R.string.filter_local_only)) }
                )
                FilterChip(
                    selected = sourceFilter == PasswordPickerSourceFilter.KEEPASS,
                    onClick = {
                        sourceFilter = PasswordPickerSourceFilter.KEEPASS
                        selectedMdbxDatabaseId = null
                        selectedVaultId = null
                        selectedFolderId = null
                    },
                    label = { Text(stringResource(R.string.filter_keepass)) }
                )
                FilterChip(
                    selected = sourceFilter == PasswordPickerSourceFilter.MDBX,
                    onClick = {
                        sourceFilter = PasswordPickerSourceFilter.MDBX
                        selectedKeePassDatabaseId = null
                        selectedVaultId = null
                        selectedFolderId = null
                    },
                    label = { Text("MDBX") }
                )
                FilterChip(
                    selected = sourceFilter == PasswordPickerSourceFilter.BITWARDEN,
                    onClick = {
                        sourceFilter = PasswordPickerSourceFilter.BITWARDEN
                        selectedKeePassDatabaseId = null
                        selectedMdbxDatabaseId = null
                    },
                    label = { Text(stringResource(R.string.filter_bitwarden)) }
                )
            }

            if (sourceFilter == PasswordPickerSourceFilter.KEEPASS) {
                ExposedDropdownMenuBox(
                    expanded = keepassMenuExpanded,
                    onExpandedChange = { keepassMenuExpanded = !keepassMenuExpanded }
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = selectedKeePassDatabaseId?.let { keepassNameById[it] }
                            ?: stringResource(R.string.password_picker_all_databases),
                        onValueChange = {},
                        label = { Text(stringResource(R.string.password_picker_filter_database)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = keepassMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = keepassMenuExpanded,
                        onDismissRequest = { keepassMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.password_picker_all_databases)) },
                            onClick = {
                                selectedKeePassDatabaseId = null
                                keepassMenuExpanded = false
                            }
                        )
                        keepassDatabases.forEach { databaseItem ->
                            DropdownMenuItem(
                                text = { Text(databaseItem.name) },
                                onClick = {
                                    selectedKeePassDatabaseId = databaseItem.id
                                    keepassMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            if (sourceFilter == PasswordPickerSourceFilter.MDBX) {
                ExposedDropdownMenuBox(
                    expanded = mdbxMenuExpanded,
                    onExpandedChange = { mdbxMenuExpanded = !mdbxMenuExpanded }
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = selectedMdbxDatabaseId?.let { mdbxNameById[it] }
                            ?: stringResource(R.string.password_picker_all_databases),
                        onValueChange = {},
                        label = { Text(stringResource(R.string.password_picker_filter_database)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mdbxMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = mdbxMenuExpanded,
                        onDismissRequest = { mdbxMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.password_picker_all_databases)) },
                            onClick = {
                                selectedMdbxDatabaseId = null
                                mdbxMenuExpanded = false
                            }
                        )
                        mdbxDatabases.forEach { databaseItem ->
                            DropdownMenuItem(
                                text = { Text(databaseItem.name) },
                                onClick = {
                                    selectedMdbxDatabaseId = databaseItem.id
                                    mdbxMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            if (sourceFilter == PasswordPickerSourceFilter.BITWARDEN) {
                ExposedDropdownMenuBox(
                    expanded = vaultMenuExpanded,
                    onExpandedChange = { vaultMenuExpanded = !vaultMenuExpanded }
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = selectedVaultId?.let { vaultLabelById[it] }
                            ?: stringResource(R.string.password_picker_all_vaults),
                        onValueChange = {},
                        label = { Text(stringResource(R.string.password_picker_filter_vault)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = vaultMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = vaultMenuExpanded,
                        onDismissRequest = { vaultMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.password_picker_all_vaults)) },
                            onClick = {
                                selectedVaultId = null
                                selectedFolderId = null
                                vaultMenuExpanded = false
                            }
                        )
                        bitwardenVaults.forEach { vault ->
                            val label = vaultLabelById[vault.id].orEmpty()
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedVaultId = vault.id
                                    selectedFolderId = null
                                    vaultMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                if (selectedVaultId != null) {
                    ExposedDropdownMenuBox(
                        expanded = folderMenuExpanded,
                        onExpandedChange = { folderMenuExpanded = !folderMenuExpanded }
                    ) {
                        OutlinedTextField(
                            readOnly = true,
                            value = selectedFolderId?.let { folderNameById[it] }
                                ?: stringResource(R.string.password_picker_all_folders),
                            onValueChange = {},
                            label = { Text(stringResource(R.string.password_picker_filter_folder)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = folderMenuExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = folderMenuExpanded,
                            onDismissRequest = { folderMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.password_picker_all_folders)) },
                                onClick = {
                                    selectedFolderId = null
                                    folderMenuExpanded = false
                                }
                            )
                            selectedVaultFolders.forEach { folder ->
                                DropdownMenuItem(
                                    text = { Text(folder.name) },
                                    onClick = {
                                        selectedFolderId = folder.bitwardenFolderId
                                        folderMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (filteredPasswords.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredPasswords, key = { it.id }) { entry ->
                        val sourceLabel = when {
                            entry.bitwardenVaultId != null -> {
                                val vaultText = vaultLabelById[entry.bitwardenVaultId].orEmpty()
                                val folderText = entry.bitwardenFolderId?.let { folderId ->
                                    foldersByVault[entry.bitwardenVaultId]?.firstOrNull { it.bitwardenFolderId == folderId }?.name
                                } ?: stringResource(R.string.category_none)
                                "${stringResource(R.string.filter_bitwarden)} · $vaultText · $folderText"
                            }
                            entry.keepassDatabaseId != null -> {
                                val dbName = keepassNameById[entry.keepassDatabaseId]
                                    ?: entry.keepassDatabaseId.toString()
                                "${stringResource(R.string.filter_keepass)} · $dbName"
                            }
                            entry.mdbxDatabaseId != null -> {
                                val dbName = mdbxNameById[entry.mdbxDatabaseId]
                                    ?: entry.mdbxDatabaseId.toString()
                                "MDBX · $dbName"
                            }
                            else -> stringResource(R.string.filter_local_only)
                        }

                        val selected = entry.id == selectedEntryId
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        sheetState.hide()
                                        onSelect(entry)
                                    }
                                },
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when {
                                        entry.bitwardenVaultId != null -> Icons.Default.Cloud
                                        entry.keepassDatabaseId != null -> Icons.Default.Storage
                                        entry.mdbxDatabaseId != null -> Icons.Default.Folder
                                        else -> Icons.Default.PhoneAndroid
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                )

                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = entry.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val supporting = listOf(entry.username, entry.website)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · ")
                                    if (supporting.isNotBlank()) {
                                        Text(
                                            text = supporting,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = sourceLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
