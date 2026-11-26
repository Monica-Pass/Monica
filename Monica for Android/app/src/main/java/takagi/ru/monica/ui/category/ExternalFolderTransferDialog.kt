package takagi.ru.monica.ui.category

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.repository.DatabaseManagerLocation
import takagi.ru.monica.repository.DatabaseManagerStore

/** One centered dialog, bounded and scrollable at large font and display sizes. */
@Composable
internal fun ExternalFolderTransferDialog(
    name: String, copy: Boolean, onDismiss: () -> Unit,
    loadStores: suspend () -> List<DatabaseManagerStore>,
    loadFolders: suspend (DatabaseManagerStore) -> List<Pair<DatabaseManagerLocation, String>>,
    accountHint: String? = null,
    onConfirm: suspend (DatabaseManagerLocation) -> Unit,
) {
    var stores by remember { mutableStateOf<List<DatabaseManagerStore>>(emptyList()) }
    var store by remember { mutableStateOf<DatabaseManagerStore?>(null) }
    var folders by remember { mutableStateOf<List<Pair<DatabaseManagerLocation, String>>>(emptyList()) }
    var selected by remember { mutableStateOf<DatabaseManagerLocation?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(store, reload) {
        selected = null
        folders = emptyList()
        loading = true
        error = null
        try {
            val current = store
            if (current == null) stores = loadStores() else folders = loadFolders(current)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message.orEmpty() }
        finally { loading = false }
    }
    AlertDialog(
        modifier = Modifier.testTag("external-folder-transfer"),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (copy) R.string.copy else R.string.move)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            val current = store
            if (current != null) TextButton(enabled = !busy, onClick = { store = null }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text(current.title)
            }
            Text(stringResource(R.string.external_folder_choose_destination), style = MaterialTheme.typography.labelLarge)
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                if (current == null) items(stores, key = { it.key.key }) { option ->
                    ListItem(headlineContent = { Text(option.title) },
                        modifier = Modifier.testTag("external-folder-store-${option.key.key}")
                            .clickable(enabled = !busy && !loading) { store = option })
                } else if (!loading && (error == null || selected != null)) {
                    val locations = listOf(DatabaseManagerLocation(current.key) to context.getString(R.string.external_folder_root)) + folders
                    items(locations, key = { it.first.folderId.orEmpty() }) { (location, label) ->
                        ListItem(headlineContent = { Text(label) },
                            leadingContent = { RadioButton(selected == location, onClick = null, enabled = !busy) },
                            modifier = Modifier.testTag("external-folder-location-${location.folderId ?: "root"}")
                                .clickable(enabled = !busy) { selected = location })
                    }
                }
            }
            Text(stringResource(if (copy) R.string.external_folder_copy_hint else R.string.external_folder_move_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            accountHint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = { reload++ }) { Text(stringResource(R.string.retry)) }
            }
        } },
        confirmButton = { TextButton(modifier = Modifier.testTag("external-folder-confirm"), enabled = selected != null && !loading && !busy, onClick = {
            val destination = selected ?: return@TextButton
            busy = true
            error = null
            scope.launch {
                try { onConfirm(destination); onDismiss() }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { error = context.getString(R.string.save_failed_with_error, failure.message.orEmpty()) }
                finally { busy = false }
            }
        }) { Text(stringResource(if (copy) R.string.copy else R.string.move)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
