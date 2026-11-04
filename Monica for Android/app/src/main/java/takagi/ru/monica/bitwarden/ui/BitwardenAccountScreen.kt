package takagi.ru.monica.bitwarden.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitwardenAccountScreen(viewModel: BitwardenViewModel, vaultId: Long, onNavigateBack: () -> Unit) {
    val vaults by viewModel.vaults.collectAsState()
    val vault = vaults.firstOrNull { it.id == vaultId }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var reload by remember(vaultId) { mutableIntStateOf(0) }
    var hasResumed by remember(vaultId) { mutableStateOf(false) }
    var busy by remember(vaultId) { mutableStateOf(false) }
    var devices by remember(vaultId) { mutableStateOf<List<BitwardenAccountDevice>>(emptyList()) }
    var error by remember(vaultId) { mutableStateOf<String?>(null) }
    val defaultError = stringResource(R.string.bitwarden_account_operation_failed)
    DisposableEffect(lifecycle, vaultId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (hasResumed) reload++ else hasResumed = true
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(vaultId, reload, hasResumed) {
        if (!hasResumed) return@LaunchedEffect
        busy = true
        error = null
        try { viewModel.getAccountDevices(vaultId)
            .onSuccess { devices = it }.onFailure { devices = emptyList(); error = it.message ?: defaultError } }
        finally { busy = false }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.bitwarden_account_security)) },
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = { IconButton(onClick = { reload++ }, enabled = !busy) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("bitwarden_account_screen"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                vault?.let {
                    Text(it.email, style = MaterialTheme.typography.titleMedium)
                    Text(it.serverUrl, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse(BitwardenAccountNavigation.webSettingsUrl(it.serverUrl)))) }
                            .onFailure { error = defaultError }
                    }, modifier = Modifier.fillMaxWidth().testTag("bitwarden_account_web_settings")) {
                        Text(stringResource(R.string.bitwarden_account_web_settings))
                    }
                }
            }
            item { Text(stringResource(R.string.bitwarden_account_devices), style = MaterialTheme.typography.titleMedium) }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (!busy && error == null && devices.isEmpty()) item { Text(stringResource(R.string.bitwarden_account_no_devices)) }
            items(devices, key = { it.id }) { device ->
                Card(Modifier.fillMaxWidth().testTag("bitwarden_account_device_${device.id}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(device.name.ifBlank { device.id }, style = MaterialTheme.typography.titleMedium)
                        device.isTrusted?.let { trusted ->
                            Text(stringResource(if (trusted) R.string.bitwarden_account_device_trusted else R.string.bitwarden_account_device_untrusted),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        device.creationDate?.let { Text(stringResource(R.string.created_at) + ": " + it, style = MaterialTheme.typography.bodySmall) }
                        device.lastActivityDate?.let { Text(stringResource(R.string.bitwarden_account_last_activity) + ": " + it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
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
