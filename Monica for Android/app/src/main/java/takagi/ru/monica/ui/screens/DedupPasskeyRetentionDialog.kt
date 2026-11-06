package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.GroupedItemDefaults

/** Titles and source labels only; never carries credential values or write authorization. */
internal data class DedupPasskeyRetentionRow(val key: String, val title: String, val sourceLabel: String)

@Composable
internal fun DedupPasskeyRetentionNotice(count: Int, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val strings = rememberScreenStrings()
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth(),
        shape = GroupedItemDefaults.SingleShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(strings.get(R.string.dedup_passkey_retention_title)) },
            supportingContent = { Text(strings.get(R.string.dedup_passkey_retention_count, count)) },
            leadingContent = { Icon(Icons.Default.Info, contentDescription = null) }
        )
    }
}

@Composable
internal fun DedupPasskeyRetentionDialog(
    rows: List<DedupPasskeyRetentionRow>,
    onDismiss: () -> Unit,
    onContinueReview: (() -> Unit)? = null,
) {
    val strings = rememberScreenStrings()
    // A bounded viewport avoids eager composition of all retained project names.
    val listHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.42f).coerceIn(120.dp, 320.dp)
    AlertDialog(
        modifier = Modifier.testTag("dedup_passkey_dialog"),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, contentDescription = null) },
        title = { Text(strings.get(R.string.dedup_passkey_retention_title)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().height(listHeight).testTag("dedup_passkey_retention_list"),
                verticalArrangement = Arrangement.spacedBy(GroupedItemDefaults.Spacing)) {
                item("explanation") { Text(strings.get(R.string.dedup_passkey_retention_message)) }
                items(rows, key = { it.key }) { row ->
                    Surface(shape = GroupedItemDefaults.SingleShape, color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().testTag("dedup_passkey_retained:${row.key}")) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(row.title.ifBlank { strings.get(R.string.dedup_merge_untitled_password) }) },
                            supportingContent = { Text(row.sourceLabel) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (onContinueReview != null) {
                TextButton(onClick = onContinueReview, modifier = Modifier.testTag("dedup_passkey_continue")) {
                    Text(strings.get(R.string.dedup_passkey_retention_continue))
                }
            } else {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("dedup_passkey_close")) {
                    Text(strings.get(R.string.dedup_merge_done))
                }
            }
        },
        dismissButton = {
            if (onContinueReview != null) TextButton(onClick = onDismiss) { Text(strings.get(R.string.cancel)) }
        }
    )
}
