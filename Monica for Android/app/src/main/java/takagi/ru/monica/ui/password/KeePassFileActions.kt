package takagi.ru.monica.ui.password

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.keepass.KeePassFileState
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.viewmodel.PasswordViewModel

internal data class KeePassFileActions(
    val state: KeePassFileState, val writable: Boolean, val available: Boolean,
    val save: () -> Unit, val merge: () -> Unit, val reload: () -> Unit,
) { val busy get() = state == KeePassFileState.BUSY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberKeePassFileActions(
    databaseId: Long?, viewModel: PasswordViewModel, onReloaded: () -> Unit,
): KeePassFileActions? {
    val store = viewModel.monicaKeePassStore ?: return null
    if (databaseId == null) return null
    val states by store.fileStates.collectAsStateWithLifecycle()
    val unlocked by SessionManager.isUnlocked.collectAsStateWithLifecycle()
    val lockVersions by KeePassKdbxService.nativeLockVersions.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val refreshed by rememberUpdatedState(onReloaded)
    var showMerge by remember(databaseId) { mutableStateOf(false) }
    var source by remember(databaseId) { mutableStateOf<Uri?>(null) }
    var keyFile by remember(databaseId) { mutableStateOf<Uri?>(null) }
    var password by remember(databaseId) { mutableStateOf("") }
    val context = LocalContext.current
    @Composable fun selectedName(uri: Uri?): String? {
        val name by produceState<String?>(null, uri) {
            value = null
            if (uri != null) value = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull()
            }
        }
        return name
    }
    val sourceName = selectedName(source)
    val keyFileName = selectedName(keyFile)
    var failed by remember(databaseId, unlocked, lockVersions[databaseId]) { mutableStateOf(false) }
    val state = states[databaseId] ?: KeePassFileState.READY
    val available = unlocked && store.isDatabaseAvailable(databaseId)
    val writable = available && store.canWriteDatabase(databaseId)
    val busy = state == KeePassFileState.BUSY
    fun dismissMerge() { showMerge = false; password = ""; source = null; keyFile = null }
    fun runOperation(operation: suspend () -> Unit) {
        scope.launch {
            try { operation(); refreshed() }
            catch (error: Exception) { if (error is CancellationException) throw error; failed = true }
        }
    }
    LaunchedEffect(databaseId, unlocked, lockVersions[databaseId], available, lifecycle) {
        if (!available) { dismissMerge(); return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (store.checkForExternalChanges(databaseId)) refreshed()
                delay(5000)
            }
        }
    }
    val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) source = uri }
    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) keyFile = uri }
    if (showMerge && available) {
        ModalBottomSheet(onDismissRequest = { if (!busy) dismissMerge() }) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.kp_file_merge), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.kp_file_merge_summary), style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = { pickSource.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("keepass-merge-source")) {
                    Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(12.dp))
                    Text(sourceName ?: stringResource(if (source == null) R.string.kp_file_select_source else R.string.kp_file_source_selected), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                OutlinedTextField(value = password, onValueChange = { password = it }, enabled = !busy,
                    label = { Text(stringResource(R.string.kp_file_source_password)) }, modifier = Modifier.fillMaxWidth().testTag("keepass-merge-password"),
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedButton(onClick = { pickKey.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Key, null); Spacer(Modifier.width(12.dp))
                    Text(keyFileName ?: stringResource(if (keyFile == null) R.string.kp_file_select_key else R.string.kp_file_key_selected), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (keyFile != null) TextButton(onClick = { keyFile = null }, enabled = !busy) { Text(stringResource(R.string.remove)) }
                Text(stringResource(R.string.kp_file_merge_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        val selected = source ?: return@Button
                        runOperation { store.mergeDatabase(databaseId, selected, password, keyFile); dismissMerge() }
                    }, enabled = writable && !busy && source != null, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("keepass-merge-confirm")) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.kp_file_merge_confirm))
                    }
                    TextButton(onClick = { dismissMerge() }, enabled = !busy, modifier = Modifier.heightIn(min = 52.dp)) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
    if (failed && unlocked) AlertDialog(onDismissRequest = { failed = false },
        title = { Text(stringResource(R.string.kp_file_failed)) }, text = { Text(stringResource(R.string.kp_file_failure_help)) },
        confirmButton = { TextButton(onClick = { failed = false }) { Text(stringResource(R.string.ok)) } })
    return KeePassFileActions(state, writable, available,
        save = { runOperation { store.saveDatabase(databaseId) } },
        merge = { showMerge = true }, reload = { runOperation { viewModel.reloadNativeKeePassFile(databaseId) } })
}

@Composable
internal fun KeePassFileMenuItems(actions: KeePassFileActions, dismiss: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(R.string.kp_file_save)) }, leadingIcon = { Icon(Icons.Default.Save, null) },
        enabled = actions.writable && !actions.busy, onClick = { dismiss(); actions.save() }, modifier = Modifier.testTag("keepass-file-save"))
    DropdownMenuItem(text = { Text(stringResource(R.string.kp_file_merge)) }, leadingIcon = { Icon(Icons.Default.Merge, null) },
        enabled = actions.writable && !actions.busy, onClick = { dismiss(); actions.merge() }, modifier = Modifier.testTag("keepass-file-merge"))
    DropdownMenuItem(text = { Text(stringResource(R.string.kp_file_reload)) }, leadingIcon = { Icon(Icons.Default.Refresh, null) },
        enabled = actions.available && !actions.busy, onClick = { dismiss(); actions.reload() }, modifier = Modifier.testTag("keepass-file-reload"))
}

@Composable
internal fun KeePassFileStatus(actions: KeePassFileActions) {
    val failed = actions.state == KeePassFileState.RELOAD_FAILED || actions.state == KeePassFileState.WRITE_FAILED
    if (!actions.available && !failed) return
    val label = when (actions.state) {
        KeePassFileState.READY -> R.string.kp_file_ready
        KeePassFileState.BUSY -> R.string.kp_file_busy
        KeePassFileState.SAVED -> R.string.kp_file_saved
        KeePassFileState.EXTERNAL_RELOADED -> R.string.kp_file_external_reloaded
        KeePassFileState.RELOADED -> R.string.kp_file_reloaded
        KeePassFileState.MERGED -> R.string.kp_file_merged
        KeePassFileState.MERGED_CONFLICTS -> R.string.kp_file_merged_conflicts
        KeePassFileState.RELOAD_FAILED -> R.string.kp_file_reload_failed
        KeePassFileState.WRITE_FAILED -> R.string.kp_file_failed
    }
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("keepass-file-status"),
        shape = RoundedCornerShape(16.dp), color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.animateContentSize(tween(260, easing = FastOutSlowInEasing), alignment = Alignment.TopStart)
            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (actions.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(if (failed) Icons.Default.ErrorOutline else Icons.Default.CheckCircle, null, Modifier.size(18.dp))
            Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            if (failed) TextButton(onClick = actions.reload, enabled = actions.available) { Text(stringResource(R.string.kp_file_reload)) }
        }
    }
}
