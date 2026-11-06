package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

/** One composition visit only. This acknowledgment never authorizes a database write. */
internal class DedupEntrySession {
    var accepted by mutableStateOf(false)
        private set
    var closed by mutableStateOf(false)
        private set
    private var active = true

    fun confirm(): Boolean {
        if (!active || closed || accepted) return false
        accepted = true
        return true
    }

    fun cancel(): Boolean {
        if (!active || closed || accepted) return false
        closed = true
        return true
    }

    fun dispose() { active = false }
}

/** Returns before the actual screen mounts until the user accepts this visit's notice. */
@Composable
internal fun DedupEntryNotice(singleVault: Boolean, onCancel: () -> Unit): Boolean {
    val session = remember(singleVault) { DedupEntrySession() }
    val cancel = rememberUpdatedState(onCancel)
    val strings = rememberScreenStrings()
    DisposableEffect(session) { onDispose { session.dispose() } }
    if (!session.accepted && !session.closed) {
        val dismiss = { if (session.cancel()) cancel.value() }
        AlertDialog(
            modifier = Modifier.testTag("dedup_entry_notice"),
            onDismissRequest = dismiss,
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text(strings.get(R.string.dedup_entry_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("dedup_entry_body"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.get(if (singleVault) R.string.single_dedup_mode else R.string.single_dedup_merge_mode),
                        style = MaterialTheme.typography.titleSmall)
                    Text(strings.get(R.string.dedup_entry_common))
                    Text(strings.get(R.string.dedup_passkey_retention_message))
                    Text(strings.get(R.string.dedup_entry_exclusions))
                    Text(strings.get(if (singleVault) R.string.dedup_entry_single_scope else R.string.dedup_entry_merge_scope),
                        modifier = Modifier.testTag("dedup_entry_scope"))
                }
            },
            confirmButton = {
                TextButton(onClick = { session.confirm() }, modifier = Modifier.testTag("dedup_entry_confirm")) {
                    Text(strings.get(R.string.dedup_entry_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss, modifier = Modifier.testTag("dedup_entry_cancel")) {
                    Text(strings.get(R.string.cancel))
                }
            }
        )
    }
    return session.accepted
}
