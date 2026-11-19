package takagi.ru.monica.ui.screens

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import takagi.ru.monica.autofill_ng.privilege.PrivilegeAccess
import takagi.ru.monica.autofill_ng.privilege.PrivilegeBackend
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.defaultmanager.*
import takagi.ru.monica.ui.components.SettingsPanelGroup
import takagi.ru.monica.ui.components.SettingsPanelRow
import takagi.ru.monica.ui.components.SettingsContentRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DefaultPasswordManagerSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val accessFlow = remember { ShizukuDefaultManager.observeAccess() }
    var access by remember { mutableStateOf(ShizukuDefaultManager.access(context)) }
    val rootStatus by PrivilegeAccess.status.collectAsStateWithLifecycle()
    val privilegeChanges by PrivilegeAccess.changes.collectAsStateWithLifecycle()
    val useRoot = remember(privilegeChanges) { PrivilegeAccess.backend(context) == PrivilegeBackend.ROOT }
    LaunchedEffect(accessFlow) { accessFlow.collect { access = ShizukuDefaultManager.access(context) } }
    LaunchedEffect(rootStatus, privilegeChanges) { access = ShizukuDefaultManager.access(context) }
    var snapshot by remember { mutableStateOf<SettingsSnapshot?>(null) }
    var backup by remember { mutableStateOf(ShizukuDefaultManager.previous(context)) }
    var includeAutofill by remember { mutableStateOf(true) }
    var keepOthers by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableIntStateOf(0) }
    var confirmation by remember { mutableStateOf<SettingsSnapshot?>(null) }
    var restore by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { access = ShizukuDefaultManager.access(context); refresh++ } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { code, grant ->
            if (code == ShizukuDefaultManager.PERMISSION_REQUEST) {
                if (grant != PackageManager.PERMISSION_GRANTED) message = R.string.default_manager_denied
                refresh++
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }
    LaunchedEffect(access, refresh) {
        if (busy) return@LaunchedEffect
        snapshot = null
        confirmation = null
        if (access != DefaultManagerAccess.READY) return@LaunchedEffect
        busy = true
        try { snapshot = ShizukuDefaultManager.read(context) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = R.string.default_manager_read_failed }
        finally { busy = false; backup = ShizukuDefaultManager.previous(context) }
    }

    fun serviceLabel(value: String?): String {
        if (value.isNullOrEmpty()) return context.getString(R.string.default_manager_unset)
        return components(value).joinToString(" · ") { component ->
            val packageName = requireNotNull(ComponentName.unflattenFromString(component)).packageName
            if (packageName == context.packageName) "Monica"
            else runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }
                .getOrDefault(packageName)
        }
    }

    confirmation?.let { expected ->
        val target = if (restore) backup else expected.withSelection(MonicaDefaultServices.credential,
            MonicaDefaultServices.autofill.takeIf { includeAutofill }, keepOthers)
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(if (restore) R.string.default_manager_restore else R.string.default_manager_apply)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (restore) R.string.default_manager_restore_confirm else R.string.default_manager_confirm))
                Text(stringResource(R.string.default_manager_credential) + ": " + serviceLabel(target?.get(SettingKey.PRIMARY)))
                Text(stringResource(R.string.default_manager_autofill) + ": " + serviceLabel(target?.get(SettingKey.AUTOFILL)))
                if (!restore && !keepOthers) Text(stringResource(R.string.default_manager_replace_warning))
            } },
            confirmButton = { TextButton(enabled = !busy && access == DefaultManagerAccess.READY && target != null,
                onClick = {
                    val restoring = restore
                    confirmation = null
                    busy = true
                    message = 0
                    scope.launch {
                        try {
                            snapshot = if (restoring) ShizukuDefaultManager.restore(context, expected)
                                else ShizukuDefaultManager.apply(context, expected, includeAutofill, keepOthers)
                            message = if (restoring) R.string.default_manager_restored else R.string.default_manager_applied
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { snapshot = null; message = R.string.default_manager_write_failed }
                        finally { busy = false; backup = ShizukuDefaultManager.previous(context) }
                    }
                }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.default_manager_title), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.default_manager_description), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
            PrivilegeBackendSelector(enabled = !busy) {
                access = ShizukuDefaultManager.access(context)
                refresh++
            }
            SettingsPanelGroup("") {
                if (!useRoot) SettingsContentRow(Icons.Default.Security, "Shizuku", stringResource(when (access) {
                    DefaultManagerAccess.READY -> R.string.default_manager_ready
                    DefaultManagerAccess.PERMISSION -> R.string.default_manager_permission
                    DefaultManagerAccess.STOPPED -> R.string.default_manager_stopped
                    DefaultManagerAccess.UNSUPPORTED -> R.string.default_manager_unsupported
                }))
                SettingsPanelRow(Icons.Default.Key, Setting.DEFAULT_MANAGER_CREDENTIAL,
                    snapshot?.let { serviceLabel(it[SettingKey.PRIMARY]) } ?: stringResource(R.string.default_manager_unknown))
                SettingsPanelRow(Icons.Default.Settings, Setting.DEFAULT_MANAGER_AUTOFILL,
                    snapshot?.let { serviceLabel(it[SettingKey.AUTOFILL]) } ?: stringResource(R.string.default_manager_unknown))
            }
            if (!useRoot && (access == DefaultManagerAccess.PERMISSION || access == DefaultManagerAccess.STOPPED)) {
                Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    if (access == DefaultManagerAccess.PERMISSION) {
                        if (!ShizukuDefaultManager.requestPermission()) message = R.string.default_manager_denied
                    } else {
                        val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                        runCatching { context.startActivity(intent) }.onFailure { message = R.string.default_manager_stopped }
                    }
                }) { Text(stringResource(if (access == DefaultManagerAccess.PERMISSION) R.string.default_manager_grant else R.string.default_manager_open_shizuku)) }
            }
            SettingsPanelGroup("") {
                SettingsPanelRow(Icons.Default.Settings, Setting.DEFAULT_MANAGER_INCLUDE_AUTOFILL,
                    Setting.DEFAULT_MANAGER_INCLUDE_AUTOFILL.summary(), enabled = !busy,
                    checked = includeAutofill, onCheckedChange = { includeAutofill = it })
                SettingsPanelRow(Icons.Default.Security, Setting.DEFAULT_MANAGER_KEEP_OTHERS,
                    Setting.DEFAULT_MANAGER_KEEP_OTHERS.summary(), enabled = !busy,
                    checked = keepOthers, onCheckedChange = { keepOthers = it })
            }
            if (useRoot && access == DefaultManagerAccess.UNSUPPORTED) Text(stringResource(R.string.privilege_default_requires_android14))
            if (message != 0) Text(stringResource(message), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(enabled = !busy, onClick = { message = 0; access = ShizukuDefaultManager.access(context); refresh++ }) {
                Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.default_manager_refresh))
            }
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/cr-zhichen/password-manager-switch"))) } }) {
                Text(stringResource(R.string.default_manager_source), style = MaterialTheme.typography.labelSmall)
            }
        }
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(modifier = Modifier.fillMaxWidth().testTag("default_manager_apply"), enabled = !busy && snapshot != null && access == DefaultManagerAccess.READY,
                onClick = { restore = false; confirmation = snapshot }) { Text(stringResource(R.string.default_manager_apply)) }
            OutlinedButton(modifier = Modifier.fillMaxWidth().testTag("default_manager_restore"),
                enabled = !busy && snapshot != null && backup != null && access == DefaultManagerAccess.READY,
                onClick = { restore = true; confirmation = snapshot }) { Text(stringResource(R.string.default_manager_restore)) }
        }
    }
}
