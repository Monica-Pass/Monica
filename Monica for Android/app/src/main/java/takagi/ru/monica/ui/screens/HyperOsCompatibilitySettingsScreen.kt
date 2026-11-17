package takagi.ru.monica.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.AutofillPreferences
import takagi.ru.monica.autofill_ng.HyperOsSaveOverlayPermission
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.ui.components.SettingsPanelGroup
import takagi.ru.monica.ui.components.SettingsPanelRow
import takagi.ru.monica.ui.components.SettingsSubpageTopBar
import takagi.ru.monica.utils.SettingsManager

/** Existing preferences and privileged tool, grouped without adding another backend. */
@Composable
internal fun HyperOsCompatibilitySettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { AutofillPreferences(context) }
    val settingsManager = remember(context) { SettingsManager(context) }
    val settings by settingsManager.settingsFlow.collectAsStateWithLifecycle(AppSettings())
    val saveCompatibility by preferences.isHyperOsSaveCompatibilityEnabled.collectAsStateWithLifecycle(false)
    val scope = rememberCoroutineScope()
    var showDefaultManager by rememberSaveable { mutableStateOf(false) }

    var destination by rememberSaveable { mutableStateOf<String?>(null) }
    if (destination == "diagnostics") {
        HyperOsDiagnosticsScreen { destination = null }
        return
    }
    if (destination == "module") {
        HyperOsModuleSettingsScreen { destination = null }
        return
    }
    BackHandler(onBack = onNavigateBack)
    HyperOsCompatibilitySettingsContent(
        saveCompatibility = saveCompatibility,
        reduceAnimations = settings.reduceAnimations,
        onNavigateBack = onNavigateBack,
        onShowDefaultManager = { showDefaultManager = true },
        onShowDiagnostics = { destination = "diagnostics" },
        onShowModule = { destination = "module" },
        onSaveCompatibilityChanged = { enabled ->
            scope.launch { preferences.setHyperOsSaveCompatibilityEnabled(enabled) }
        },
        onReduceAnimationsChanged = { enabled ->
            scope.launch { settingsManager.updateReduceAnimations(enabled) }
        },
        overlayPermission = { HyperOsSaveOverlayPermission(saveCompatibility) },
    )
    if (showDefaultManager) DefaultPasswordManagerSheet { showDefaultManager = false }
}

@Composable
internal fun HyperOsCompatibilitySettingsContent(
    saveCompatibility: Boolean,
    reduceAnimations: Boolean,
    onNavigateBack: () -> Unit,
    onShowDefaultManager: () -> Unit,
    onSaveCompatibilityChanged: (Boolean) -> Unit,
    onReduceAnimationsChanged: (Boolean) -> Unit,
    overlayPermission: @Composable () -> Unit = {},
    onShowDiagnostics: () -> Unit = {},
    onShowModule: () -> Unit = {},
) {
    val feedbackContext = LocalContext.current
    Scaffold(modifier = Modifier.testTag("hyperos_compatibility_screen"), topBar = {
        SettingsSubpageTopBar(stringResource(R.string.hyperos_compatibility_title), onNavigateBack)
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.hyperos_compatibility_shared_settings_note),
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsPanelGroup("") {
                SettingsPanelRow(Icons.Default.FactCheck,
                    stringResource(R.string.hyperos_diagnostics_title),
                    stringResource(R.string.hyperos_diagnostics_note),
                    onClick = onShowDiagnostics,
                    modifier = Modifier.testTag("hyperos_diagnostics_entry"))
                SettingsPanelRow(Icons.Default.Extension,
                    stringResource(R.string.hyperos_module_title),
                    stringResource(R.string.hyperos_module_entry_note),
                    onClick = onShowModule,
                    modifier = Modifier.testTag("hyperos_module_entry"))
            }
            SettingsPanelGroup("") {
                SettingsPanelRow(Icons.Default.Key,
                    stringResource(R.string.default_manager_title),
                    stringResource(R.string.default_manager_entry_hint),
                    onClick = onShowDefaultManager,
                    modifier = Modifier.testTag("hyperos_default_manager_entry"))
                Box(Modifier.testTag("hyperos_save_compatibility_setting")) {
                    SettingsPanelRow(Icons.Default.Save,
                        stringResource(R.string.autofill_hyperos_save_compatibility),
                        stringResource(R.string.autofill_hyperos_save_compatibility_desc),
                        checked = saveCompatibility,
                        onCheckedChange = onSaveCompatibilityChanged)
                }
            }
            overlayPermission()
            SettingsPanelGroup("") {
                SettingsPanelRow(Icons.Default.Speed,
                    stringResource(R.string.reduce_animations),
                    stringResource(R.string.reduce_animations_description),
                    checked = reduceAnimations,
                    onCheckedChange = onReduceAnimationsChanged,
                    modifier = Modifier.testTag("hyperos_reduce_animations_setting"))
            }
            Text(stringResource(R.string.hyperos_physical_feedback), Modifier.padding(horizontal = 12.dp))
            androidx.compose.material3.TextButton(onClick = {
                runCatching { feedbackContext.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/Monica-Pass/Monica/issues"))) }
            }) { Text(stringResource(R.string.hyperos_feedback_action)) }
        }
    }
}
