package takagi.ru.monica.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.hyperos.HyperOsModuleConnection
import takagi.ru.monica.hyperos.HyperOsModuleState
import takagi.ru.monica.hyperos.hooks.HookPolicy
import takagi.ru.monica.ui.components.SettingsPanelGroup
import takagi.ru.monica.ui.components.SettingsPanelRow
import takagi.ru.monica.ui.components.SettingsSubpageTopBar

@Composable internal fun HyperOsModuleSettingsScreen(onBack: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val changes by HyperOsModuleConnection.changes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(HyperOsModuleState()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { HyperOsModuleConnection.register() }
    LaunchedEffect(lifecycle, changes, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { state = HyperOsModuleConnection.read() }
    }
    HyperOsModuleSettingsContent(state, busy, failed, onBack, { refresh++ }) { key, value ->
        busy = true; failed = false
        scope.launch {
            try { HyperOsModuleConnection.set(key, value); state = HyperOsModuleConnection.read() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true; state = HyperOsModuleConnection.read() }
            finally { busy = false }
        }
    }
}

@Composable internal fun HyperOsModuleSettingsContent(
    state: HyperOsModuleState, busy: Boolean, failed: Boolean, onBack: () -> Unit,
    onRefresh: () -> Unit, onChange: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val labels = listOf(
        HookPolicy.RESET to Setting.HYPEROS_HOOK_RESET,
        HookPolicy.SETTINGS to Setting.HYPEROS_HOOK_SETTINGS,
        HookPolicy.CHOOSER to Setting.HYPEROS_HOOK_CHOOSER,
        HookPolicy.HYBRID to Setting.HYPEROS_HOOK_HYBRID,
    )
    BackHandler(onBack = onBack)
    Scaffold(Modifier.testTag("hyperos_module_screen"), topBar = {
        SettingsSubpageTopBar(stringResource(R.string.hyperos_module_title), onBack)
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.hyperos_module_requirements), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(if (state.connected) R.string.hyperos_module_connected else R.string.hyperos_module_disconnected),
                modifier = Modifier.testTag("hyperos_module_status"))
            if (state.connected) Text(stringResource(R.string.hyperos_module_scope, state.scopes.sorted().joinToString(", ").ifEmpty { "—" }))
            SettingsPanelGroup("") {
                labels.forEach { (key, setting) ->
                    SettingsPanelRow(Icons.Default.Extension, setting, setting.summary(),
                        checked = key in state.options,
                        enabled = state.connected && !busy && (state.supported || key in state.options),
                        onCheckedChange = { enable -> if (enable) pending = key else onChange(key, false) },
                        modifier = Modifier.testTag("hyperos_hook_$key"))
                }
            }
            Text(stringResource(R.string.hyperos_module_restart))
            Text(stringResource(R.string.hyperos_physical_feedback))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) Text(stringResource(R.string.hyperos_update_failed))
            OutlinedButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.default_manager_refresh)) }
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Howard20181/HyperPasskey"))) } }) {
                Text(stringResource(R.string.hyperos_reference_projects))
            }
        }
    }
    pending?.let { key ->
        AlertDialog(onDismissRequest = { pending = null },
            title = { Text(labels.first { it.first == key }.second.title()) },
            text = { Text(stringResource(R.string.hyperos_module_confirmation)) },
            confirmButton = { TextButton(enabled = state.connected && state.supported && !busy,
                modifier = Modifier.testTag("hyperos_hook_confirm"),
                onClick = { pending = null; onChange(key, true) }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(modifier = Modifier.testTag("hyperos_hook_cancel"), onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
