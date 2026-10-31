package takagi.ru.monica.ui.password

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.keepass.KeePassFileState
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.viewmodel.PasswordViewModel

internal data class KeePassFileActions(
    val state: KeePassFileState, val writable: Boolean, val available: Boolean,
    val save: () -> Unit, val merge: () -> Unit, val reload: () -> Unit,
    val dismissStatus: () -> Unit = {},
) {
    val busy get() = state == KeePassFileState.BUSY
    val showStatus get() = available && state != KeePassFileState.READY
}

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
    var failed by remember(databaseId, unlocked, lockVersions[databaseId]) { mutableStateOf(false) }
    val notice = states[databaseId]
    val state = notice?.state ?: KeePassFileState.READY
    val available = unlocked && store.isDatabaseAvailable(databaseId)
    val writable = available && store.canWriteDatabase(databaseId)
    LaunchedEffect(databaseId, available, notice) {
        if (available && notice != null && state in setOf(KeePassFileState.SAVED,
                KeePassFileState.RELOADED, KeePassFileState.EXTERNAL_RELOADED, KeePassFileState.MERGED)) {
            delay(3000)
            store.dismissFileNotice(databaseId, notice)
        }
    }
    fun runOperation(operation: suspend () -> Unit) {
        scope.launch {
            try { operation(); refreshed() }
            catch (error: Exception) { if (error is CancellationException) throw error; failed = true }
        }
    }
    LaunchedEffect(databaseId, unlocked, lockVersions[databaseId], available, lifecycle) {
        if (!available) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (store.checkForExternalChanges(databaseId)) refreshed()
                delay(5000)
            }
        }
    }
    if (failed && unlocked) AlertDialog(onDismissRequest = { failed = false },
        title = { Text(stringResource(R.string.kp_file_failed)) }, text = { Text(stringResource(R.string.kp_file_failure_help)) },
        confirmButton = { TextButton(onClick = { failed = false }) { Text(stringResource(R.string.ok)) } })
    return KeePassFileActions(state, writable, available,
        save = { runOperation { store.saveDatabase(databaseId) } },
        merge = { runOperation { store.mergeCurrentDatabase(databaseId) } }, reload = { runOperation { viewModel.reloadNativeKeePassFile(databaseId) } },
        dismissStatus = { notice?.let { store.dismissFileNotice(databaseId, it) } })
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
    if (!actions.showStatus) return
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
    Surface(modifier = Modifier.fillMaxWidth().testTag("keepass-file-status"),
        shape = RoundedCornerShape(16.dp), color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (actions.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(if (failed) Icons.Default.ErrorOutline else Icons.Default.CheckCircle, null, Modifier.size(18.dp))
            Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            if (failed) TextButton(onClick = actions.reload, enabled = actions.available) { Text(stringResource(R.string.kp_file_reload)) }
            if (failed || actions.state == KeePassFileState.MERGED_CONFLICTS) {
                IconButton(onClick = actions.dismissStatus) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            }
        }
    }
}
