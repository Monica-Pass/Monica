package takagi.ru.monica.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
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
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.defaultmanager.*
import takagi.ru.monica.ui.components.SettingsPanelGroup
import takagi.ru.monica.ui.components.SettingsPanelRow
import takagi.ru.monica.ui.components.SettingsContentRow
import takagi.ru.monica.ui.components.SettingsSubpageTopBar

@Composable internal fun HyperOsDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<HyperOsDiagnosticReport?>(null) }
    var monitor by remember { mutableStateOf(HyperOsDiagnostics.monitoring(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    var showManager by rememberSaveable { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycle, refresh, monitor, showManager) {
        if (showManager) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            do {
                report = null
                report = HyperOsDiagnostics.read(context)
                if (monitor) delay(30_000)
            } while (monitor)
        }
    }
    BackHandler(onBack = onBack)
    Scaffold(Modifier.testTag("hyperos_diagnostics_screen"), topBar = {
        SettingsSubpageTopBar(stringResource(R.string.hyperos_diagnostics_title), onBack)
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.hyperos_diagnostics_note), style = MaterialTheme.typography.bodyMedium)
            if (report == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            SettingsPanelGroup("") {
                DiagnosticRow(R.string.hyperos_diag_declaration, report?.declaration)
                DiagnosticRow(R.string.hyperos_diag_feature, report?.feature)
                DiagnosticRow(R.string.hyperos_diag_unrestricted, report?.unrestricted)
                DiagnosticRow(R.string.hyperos_diag_framework, report?.framework)
            }
            SettingsPanelGroup("") {
                DiagnosticRow(R.string.hyperos_diag_registered, report?.configuration?.credential)
                DiagnosticRow(R.string.default_manager_credential, report?.configuration?.primary)
                DiagnosticRow(R.string.default_manager_autofill, report?.configuration?.autofill)
            }
            SettingsPanelGroup("") {
                SettingsPanelRow(Icons.Default.Visibility, Setting.HYPEROS_DRIFT_TITLE,
                    Setting.HYPEROS_DRIFT_TITLE.summary(), checked = monitor,
                    onCheckedChange = { value -> scope.launch {
                        failed = false
                        try { withContext(Dispatchers.IO) { HyperOsDiagnostics.setMonitoring(context, value) }; monitor = value }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { failed = true }
                    } }, modifier = Modifier.testTag("hyperos_drift_toggle"))
                SettingsContentRow(Icons.Default.Security, stringResource(R.string.hyperos_drift_result),
                    stringResource(when (report?.configuration?.drift) {
                        HyperOsDrift.OFF -> R.string.hyperos_drift_off
                        HyperOsDrift.NO_BASELINE -> R.string.hyperos_drift_no_baseline
                        HyperOsDrift.UNCHANGED -> R.string.hyperos_drift_unchanged
                        HyperOsDrift.CHANGED -> R.string.hyperos_drift_changed
                        else -> R.string.default_manager_unknown
                    }), modifier = Modifier.testTag("hyperos_drift_result"))
            }
            if (failed) Text(stringResource(R.string.hyperos_update_failed))
            OutlinedButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth().testTag("hyperos_diagnostics_refresh")) {
                Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.default_manager_refresh))
            }
            Button(onClick = { showManager = true }, modifier = Modifier.fillMaxWidth().testTag("hyperos_diagnostics_repair")) {
                Icon(Icons.Default.Key, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.default_manager_title))
            }
        }
    }
    if (showManager) DefaultPasswordManagerSheet { showManager = false }
}

@Composable private fun DiagnosticRow(title: Int, value: HyperOsCheck?) {
    SettingsContentRow(Icons.Default.Security, stringResource(title), stringResource(when (value) {
        HyperOsCheck.YES -> R.string.hyperos_check_yes
        HyperOsCheck.NO -> R.string.hyperos_check_no
        HyperOsCheck.UNSUPPORTED -> R.string.hyperos_check_unsupported
        else -> R.string.default_manager_unknown
    }))
}
