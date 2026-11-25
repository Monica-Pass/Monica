package takagi.ru.monica.ui.screens

import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.databasecompare.DatabaseCompareSource

/** One size animation owns the entire card. Removed password controls never linger in an exit tree. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CompareSourceCard(
    isLeft: Boolean,
    source: DatabaseCompareSource?,
    password: String,
    keyUri: Uri?,
    authenticated: Boolean,
    checking: Boolean,
    onChoose: () -> Unit,
    onPassword: (String) -> Unit,
    onKey: () -> Unit,
    onRemoveKey: () -> Unit,
) {
    val side = if (isLeft) "left" else "right"
    Card(
        modifier = Modifier.fillMaxWidth().testTag("compare_${side}_card"),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(if (isLeft) R.string.db_compare_left else R.string.db_compare_right),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(onClick = onChoose, shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().testTag("compare_${side}_choose")) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Icon(if (source is DatabaseCompareSource.Document) Icons.Default.FolderOpen else Icons.Default.Storage,
                            null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(source?.label ?: stringResource(R.string.db_compare_choose), style = MaterialTheme.typography.titleMedium)
                        if (source != null) Text(sourceLocation(source), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
            when {
                source == null -> Unit
                checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.passkey_portability_checking), style = MaterialTheme.typography.bodyMedium)
                }
                authenticated -> Row(Modifier.testTag("compare_${side}_authenticated"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.db_compare_reuse), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Lock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.db_compare_auth_required), style = MaterialTheme.typography.bodyMedium)
                    }
                    ComparePasswordField(password, onPassword, R.string.database_password, "compare_${side}_password")
                    TextButton(onClick = onKey, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                        Icon(Icons.Default.Key, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (keyUri == null) R.string.db_compare_key_file else R.string.db_compare_key_selected))
                    }
                    if (keyUri != null) TextButton(onClick = onRemoveKey) { Text(stringResource(R.string.db_compare_remove_key)) }
                }
            }
        }
    }
}

@Composable
private fun sourceLocation(source: DatabaseCompareSource): String = stringResource(when (source) {
    is DatabaseCompareSource.Document -> R.string.db_compare_external_file
    is DatabaseCompareSource.Registered -> if (source.external) R.string.external_storage else R.string.internal_storage
})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompareSourcePicker(
    catalog: List<DatabaseCompareSource.Registered>,
    authenticatedIds: Set<Long>,
    selected: DatabaseCompareSource?,
    onDismiss: () -> Unit,
    onSelect: (DatabaseCompareSource.Registered) -> Unit,
    onFile: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().testTag("compare_source_picker"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.db_compare_choose), Modifier.padding(bottom = 12.dp),
                style = MaterialTheme.typography.headlineSmall) }
            item {
                FilledTonalButton(onClick = onFile, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .testTag("compare_open_file"), shape = MaterialTheme.shapes.large) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.db_compare_file))
                }
            }
            item { Text(stringResource(R.string.db_compare_registered), Modifier.padding(top = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            items(catalog, key = { it.id }) { source ->
                val ready = source.id in authenticatedIds
                val chosen = (selected as? DatabaseCompareSource.Registered)?.id == source.id
                Surface(onClick = { onSelect(source) }, shape = MaterialTheme.shapes.large,
                    color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().testTag("compare_source_${source.id}")) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(if (ready) Icons.Default.CheckCircle else Icons.Default.Lock, null,
                            tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(source.label, style = MaterialTheme.typography.titleMedium)
                            Text(sourceLocation(source) + " · " + stringResource(if (ready) R.string.ime_state_unlocked else R.string.ime_state_locked),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                }
            }
        }
    }
}
