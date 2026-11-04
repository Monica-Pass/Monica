package takagi.ru.monica.bitwarden.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.net.URI
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.api.BitwardenAccountDevice
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.sync.notificationAccountFingerprint
import takagi.ru.monica.security.SessionManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitwardenAccountScreen(viewModel: BitwardenViewModel, vaultId: Long, onNavigateBack: () -> Unit) {
    val vaults by viewModel.vaults.collectAsState()
    val vault = vaults.firstOrNull { it.id == vaultId }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val repository = remember(context) { BitwardenRepository.getInstance(context) }
    val generationFlow = remember(repository, vaultId) { repository.getVaultSessionGenerationFlow(vaultId) }
    val vaultGeneration by generationFlow.collectAsState()
    val appUnlocked by SessionManager.isUnlocked.collectAsState()
    val appGeneration by SessionManager.lockGeneration.collectAsState()
    val accountIdentity = vault?.let { it.accountKey + notificationAccountFingerprint(it) }
    val unlocked = appUnlocked && vault?.isLocked == false && viewModel.isVaultUnlocked(vaultId)
    var reload by remember(vaultId) { mutableIntStateOf(0) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var foregroundGeneration by remember(lifecycle, vaultId) { mutableLongStateOf(0L) }
    var busy by remember(vaultId, accountIdentity, vaultGeneration, appGeneration) { mutableStateOf(false) }
    var devices by remember(vaultId, accountIdentity, vaultGeneration, appGeneration) { mutableStateOf<List<BitwardenAccountDevice>>(emptyList()) }
    var error by remember(vaultId, accountIdentity, vaultGeneration, appGeneration) { mutableStateOf<String?>(null) }
    val defaultError = stringResource(R.string.bitwarden_account_operation_failed)
    val clearVisibleDevices by rememberUpdatedState<() -> Unit>({ devices = emptyList(); error = null })
    DisposableEffect(lifecycle, vaultId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_STOP -> {
                    foregroundGeneration++
                    resumed = false
                    clearVisibleDevices()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(vaultId, reload, resumed, foregroundGeneration, unlocked, accountIdentity, vaultGeneration, appGeneration) {
        devices = emptyList()
        error = null
        if (!resumed || !unlocked) { busy = false; return@LaunchedEffect }
        val requestForegroundGeneration = foregroundGeneration
        busy = true
        try {
            val result = viewModel.getAccountDevices(vaultId)
            val current = viewModel.vaults.value.firstOrNull { it.id == vaultId }
            if (resumed && foregroundGeneration == requestForegroundGeneration &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == appGeneration &&
                repository.getVaultSessionGeneration(vaultId) == vaultGeneration && viewModel.isVaultUnlocked(vaultId) &&
                current?.let { it.accountKey + notificationAccountFingerprint(it) } == accountIdentity) {
                result.onSuccess { devices = it }.onFailure { error = it.message ?: defaultError }
            }
        } finally { busy = false }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.bitwarden_account_security)) },
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = { IconButton(onClick = { reload++ }, enabled = !busy && unlocked) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) } })
    }) { padding ->
        BitwardenAccountContent(
            modifier = Modifier.fillMaxSize().padding(padding),
            displayName = vault?.displayName, email = vault?.email.orEmpty(), serverUrl = vault?.serverUrl.orEmpty(),
            devices = if (unlocked && resumed) devices else emptyList(), busy = busy && unlocked,
            error = if (unlocked) error else stringResource(R.string.legacy_ui_vault_locked),
            onWebSettings = {
                vault?.let {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse(BitwardenAccountNavigation.webSettingsUrl(it.serverUrl)))) }
                        .onFailure { error = defaultError }
                }
            }
        )
    }
}

@Composable
internal fun BitwardenAccountContent(
    displayName: String?, email: String, serverUrl: String,
    devices: List<BitwardenAccountDevice>, busy: Boolean, error: String?,
    onWebSettings: () -> Unit, modifier: Modifier = Modifier
) {
    LazyColumn(modifier.testTag("bitwarden_account_screen"),
        contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Surface(shape = bitwardenGroupShape(), color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.AccountCircle, null, tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(32.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(displayName?.takeIf { it.isNotBlank() } ?: email, style = MaterialTheme.typography.titleLarge)
                        if (!displayName.isNullOrBlank()) Text(email, style = MaterialTheme.typography.bodyMedium)
                        Text(serverUrl, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { BitwardenSectionTitle(stringResource(R.string.bitwarden_settings_account_management)) }
        item {
            BitwardenSettingRow(stringResource(R.string.bitwarden_account_web_settings), Icons.Outlined.OpenInNew,
                supporting = stringResource(R.string.bitwarden_account_web_settings_hint),
                onClick = onWebSettings, enabled = serverUrl.isNotBlank(), modifier = Modifier.testTag("bitwarden_account_web_settings"))
        }
        item { BitwardenSectionTitle(stringResource(R.string.bitwarden_account_devices)) }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
        error?.let { message -> item {
            BitwardenSettingRow(message, Icons.Outlined.ErrorOutline, destructive = true)
        } }
        if (!busy && error == null && devices.isEmpty()) item {
            BitwardenSettingRow(stringResource(R.string.bitwarden_account_no_devices), Icons.Outlined.Devices)
        }
        items(devices.size, key = { devices[it].id }) { index ->
            val device = devices[index]
            val trusted = device.isTrusted?.let {
                stringResource(if (it) R.string.bitwarden_account_device_trusted else R.string.bitwarden_account_device_untrusted)
            }
            val created = device.creationDate?.let { stringResource(R.string.created_at) + ": " + it }
            val activity = device.lastActivityDate?.let { stringResource(R.string.bitwarden_account_last_activity) + ": " + it }
            BitwardenSettingRow(device.name.ifBlank { device.id }, Icons.Outlined.Devices,
                supporting = listOfNotNull(trusted, created, activity).joinToString("\n"), first = index == 0, last = index == devices.lastIndex,
                modifier = Modifier.testTag("bitwarden_account_device_${device.id}"))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

internal object BitwardenAccountNavigation {
    fun webSettingsUrl(serverUrl: String): String {
        val uri = URI(serverUrl)
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        return serverUrl.trimEnd('/') + "/#/settings"
    }
}
