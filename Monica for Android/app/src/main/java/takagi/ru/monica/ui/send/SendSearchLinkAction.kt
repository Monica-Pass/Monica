package takagi.ru.monica.ui.send

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

@Composable
internal fun SendSearchLinkAction(query: String, enabled: Boolean, onOpen: (String) -> Unit) {
    val host = remember(query) { sendLinkHost(query) } ?: return
    Surface(
        onClick = { if (sendLinkHost(query) != null) onOpen(query.trim()) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("send_search_open_link"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.bitwarden_receiver_entry), style = MaterialTheme.typography.titleSmall)
                Text(host, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
