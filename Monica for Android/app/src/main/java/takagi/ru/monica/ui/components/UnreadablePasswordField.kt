package takagi.ru.monica.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import takagi.ru.monica.R
import takagi.ru.monica.domain.provider.PasswordSource
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.model.PasswordReadFailure

@Composable
internal fun UnreadablePasswordField(
    label: String,
    source: PasswordSource,
    failure: PasswordReadFailure,
) {
    val lockGeneration by SessionManager.lockGeneration.collectAsState()
    var showHelp by remember(source, failure, lockGeneration) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, source, failure, lockGeneration) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) showHelp = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val explanation = stringResource(R.string.password_read_help_title)
    Column(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = explanation) { showHelp = true }
            .padding(vertical = 8.dp)
            .testTag("unreadable_password_help"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(R.string.password_read_help_inline), style = MaterialTheme.typography.bodyLarge)
    }
    if (showHelp) PasswordReadFailureDialog(source, failure) { showHelp = false }
}

@Composable
internal fun PasswordReadFailureDialog(
    source: PasswordSource,
    failure: PasswordReadFailure,
    onDismiss: () -> Unit,
) {
    val local = source == PasswordSource.Local
    val reason = when (source) {
        PasswordSource.Local -> when (failure) {
            PasswordReadFailure.AUTH_REQUIRED -> R.string.password_read_auth_required
            PasswordReadFailure.KEY_INVALIDATED -> R.string.password_read_key_invalidated
            PasswordReadFailure.KEY_UNAVAILABLE -> R.string.password_read_key_unavailable
            PasswordReadFailure.AUTHENTICATION_FAILED -> R.string.password_read_auth_failed
            PasswordReadFailure.UNKNOWN -> R.string.password_read_unknown
        }
        is PasswordSource.Bitwarden -> R.string.bitwarden_password_unreadable_display
        is PasswordSource.Conflict -> R.string.password_owner_conflict_display
        else -> R.string.password_read_source_unavailable
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("password_read_help_dialog"),
        icon = { Icon(Icons.Outlined.Info, null) },
        title = { Text(stringResource(R.string.password_read_help_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(reason), Modifier.testTag("password_read_reason"))
                if (local && failure != PasswordReadFailure.AUTH_REQUIRED) {
                    Text(stringResource(R.string.password_read_environment_hint),
                        Modifier.testTag("password_read_environment_hint"))
                }
                Text(stringResource(R.string.password_read_data_retained))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("password_read_help_close")) {
                Text(stringResource(R.string.close))
            }
        }
    )
}
