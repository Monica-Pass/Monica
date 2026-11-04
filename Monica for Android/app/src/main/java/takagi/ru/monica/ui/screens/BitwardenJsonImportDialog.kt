package takagi.ru.monica.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.ui.components.LocalFilledEntryForm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.export.*
import takagi.ru.monica.security.SessionManager

/** Preview contains titles and paths only; lock/dismiss revokes it together with the file password. */
@Composable
internal fun BitwardenJsonImportDialog(
    uri: Uri,
    onPreview: suspend (Uri, String?) -> Result<BitwardenJsonPreview>,
    onImport: suspend (Uri, String?) -> Result<Int>,
    onDismiss: () -> Unit,
    onImported: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val alive = remember { AtomicBoolean(true) }
    val requestRevision = remember { AtomicLong(0) }
    var operation by remember { mutableStateOf<Job?>(null) }
    val generation = remember { SessionManager.lockGeneration.value }
    val liveGeneration by SessionManager.lockGeneration.collectAsState()
    val unlocked by SessionManager.isUnlocked.collectAsState()
    var password by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<BitwardenJsonPreview?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var checkedPassword by remember { mutableStateOf<String?>(null) }
    fun current(request: Long) = alive.get() && requestRevision.get() == request &&
        SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == generation
    fun clear() { password = ""; checkedPassword = null; preview = null; failure = null }
    fun cancel() {
        alive.set(false)
        requestRevision.incrementAndGet()
        operation?.cancel()
        operation = null
        busy = false
        clear()
    }
    fun dismiss() { cancel(); onDismiss() }
    fun check(secret: String?) {
        operation?.cancel()
        val request = requestRevision.incrementAndGet()
        busy = true; failure = null; preview = null
        operation = scope.launch {
            try {
                val result = onPreview(uri, secret)
                currentCoroutineContext().ensureActive()
                if (current(request)) result.onSuccess { preview = it; checkedPassword = secret }.onFailure {
                    failure = context.getString(R.string.bw_json_reason_invalid)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            finally { if (current(request)) busy = false }
        }
    }
    LaunchedEffect(uri) { check(null) }
    LaunchedEffect(unlocked, liveGeneration) { if (!unlocked || liveGeneration != generation) dismiss() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) dismiss()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); cancel() }
    }
    if (!unlocked || liveGeneration != generation) return
    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text(stringResource(R.string.bw_json_review)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.bw_json_boundary))
                if (preview?.requiresPassword == true || checkedPassword != null || password.isNotEmpty()) {
                    CompositionLocalProvider(LocalFilledEntryForm provides true) {
                    OutlinedTextField(value = password, onValueChange = { password = it; checkedPassword = null },
                        label = { Text(stringResource(R.string.bw_json_file_password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), saveTextState = false, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedButton(enabled = !busy && password.isNotEmpty(), onClick = { check(password) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bw_json_check)) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                preview?.let { result ->
                    if (!(result.requiresPassword && checkedPassword == null)) {
                        Text(stringResource(R.string.bw_json_counts, result.items, result.folders))
                        if (result.issues.isEmpty()) Text(stringResource(R.string.bw_json_ready))
                        else BitwardenJsonIssueList(result.issues)
                    }
                }
            }
        },
        confirmButton = {
            Button(modifier = Modifier.testTag("bw_json_confirm_import"), enabled = !busy && preview?.let { it.issues.isEmpty() && (!it.requiresPassword || checkedPassword != null) } == true,
                onClick = {
                    val secret = checkedPassword
                    val request = requestRevision.incrementAndGet()
                    busy = true
                    operation = scope.launch {
                        try {
                            val result = onImport(uri, secret)
                            currentCoroutineContext().ensureActive()
                            if (current(request)) result.onSuccess { count -> cancel(); onImported(count) }.onFailure { error ->
                                if (error is BitwardenJsonRejected) preview = BitwardenJsonPreview(0, 0, secret != null, error.issues)
                                else failure = context.getString(R.string.bw_json_reason_invalid)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        finally { if (current(request)) busy = false }
                    }
                }) { Text(stringResource(R.string.import_data_title)) }
        },
        dismissButton = { TextButton(modifier = Modifier.testTag("bw_json_cancel_import"), onClick = { dismiss() }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun BitwardenJsonIssueList(issues: List<BitwardenJsonIssue>) {
    Text(stringResource(R.string.bw_json_blocked), color = MaterialTheme.colorScheme.error)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(issues) { issue ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                issue.itemName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                issue.itemIndex?.let { Text(stringResource(R.string.bw_json_item, it + 1), style = MaterialTheme.typography.labelMedium) }
                Text(issue.path, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(when (issue.reason) {
                    BitwardenJsonIssue.Reason.INVALID -> R.string.bw_json_reason_invalid
                    BitwardenJsonIssue.Reason.UNKNOWN_FIELD -> R.string.bw_json_reason_unknown
                    BitwardenJsonIssue.Reason.UNSUPPORTED_TYPE -> R.string.bw_json_reason_type
                    BitwardenJsonIssue.Reason.UNSUPPORTED_VALUE -> R.string.bw_json_reason_value
                    BitwardenJsonIssue.Reason.RESOURCE_LIMIT -> R.string.bw_json_reason_limit
                    BitwardenJsonIssue.Reason.ORGANIZATION -> R.string.bw_json_reason_organization
                    BitwardenJsonIssue.Reason.ATTACHMENTS -> R.string.bw_json_reason_attachments
                    BitwardenJsonIssue.Reason.ARCHIVED -> R.string.bw_json_reason_archived
                    BitwardenJsonIssue.Reason.DESTINATION -> R.string.bw_json_reason_destination
                    BitwardenJsonIssue.Reason.PENDING_SYNC -> R.string.bw_json_reason_pending
                    BitwardenJsonIssue.Reason.UNAVAILABLE -> R.string.bw_json_reason_unavailable
                }))
            }
        }
    }
    }
}
