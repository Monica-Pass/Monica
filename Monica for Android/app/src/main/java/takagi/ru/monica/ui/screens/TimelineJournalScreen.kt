package takagi.ru.monica.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.timeline.*
import takagi.ru.monica.viewmodel.TimelineJournalViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineJournalScreen(
    onTrash: (TimelineTarget?) -> Unit,
    onBack: (() -> Unit)? = null,
    onMdbxHistory: ((Long) -> Unit)? = null,
    bottomActionInset: androidx.compose.ui.unit.Dp = 0.dp,
    model: TimelineJournalViewModel = viewModel()
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 840.dp).fillMaxSize().padding(bottom = bottomActionInset)) {
                TimelineJournalContent(onTrash, onBack, onMdbxHistory, model)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineJournalContent(onTrash: (TimelineTarget?) -> Unit, onBack: (() -> Unit)?,
    onMdbxHistory: ((Long) -> Unit)?, model: TimelineJournalViewModel) {
    val state by model.state.collectAsState()
    val unlocked by SessionManager.isUnlocked.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            model.foreground(resumed)
        }
        lifecycle.addObserver(observer)
        model.foreground(resumed)
        onDispose { lifecycle.removeObserver(observer); model.foreground(false) }
    }
    var tab by rememberSaveable { mutableStateOf("CHANGES") }
    var scope by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(tab, scope, days, query) {
        val since = if (days == 0) 0L else LocalDate.now().minusDays((days - 1).toLong())
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        model.filter(TimelineReader.Filter(tab, scope, since = since, query = query))
    }
    if (!unlocked || !resumed) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.timeline_v2_locked)) }
        return
    }
    if (state.detail != null) {
        BackHandler { model.back() }
        TimelineJournalDetail(state, model::back, model::preview, model::confirm, onTrash, onMdbxHistory)
        return
    }
    val context = LocalContext.current
    val db = remember(context) { PasswordDatabase.getDatabase(context) }
    val keepass by db.localKeePassDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val mdbx by db.localMdbxDatabaseDao().getAvailableDatabases().collectAsState(initial = emptyList())
    val bitwarden by db.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
    val allLabel = stringResource(R.string.filter_all)
    val localLabel = stringResource(R.string.filter_monica)
    val unknownLabel = stringResource(R.string.timeline_v2_unknown)
    val labels = remember(keepass, mdbx, bitwarden, state.scopes, allLabel, localLabel, unknownLabel) {
        buildMap {
            put("", allLabel); put("local", localLabel)
            keepass.forEach { put("keepass:${it.id}", "KeePass · ${it.name}") }
            mdbx.forEach { put("mdbx:${it.id}", "MDBX · ${it.name}") }
            bitwarden.forEach { put("bitwarden:${it.id}", "Bitwarden · ${it.displayName ?: it.email}") }
            state.scopes.forEach { if (!containsKey(it)) put(it, if (it == "unknown") unknownLabel else it) }
        }
    }
    LaunchedEffect(labels) { model.scopeNames(labels) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.timeline_title), style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { onTrash(null) }) { Text(stringResource(R.string.timeline_trash_title)) }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.timeline_search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(24.dp))
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("CHANGES" to R.string.timeline_v2_changes, "RECOVERABLE" to R.string.timeline_v2_recoverable,
                        "SYNC" to R.string.timeline_v2_sync).forEach { (value, label) ->
                        FilterChip(selected = tab == value, onClick = { tab = value }, label = { Text(stringResource(label)) })
                    }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    var scopesOpen by remember { mutableStateOf(false) }
                    Box {
                        AssistChip(onClick = { scopesOpen = true }, label = { Text(labels[scope] ?: scope) },
                            trailingIcon = { Icon(Icons.Default.ExpandMore, null) })
                        DropdownMenu(scopesOpen, { scopesOpen = false }) {
                            labels.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { scope = key; scopesOpen = false }) }
                        }
                    }
                    var datesOpen by remember { mutableStateOf(false) }
                    val dates = listOf(0 to R.string.timeline_v2_all_dates, 7 to R.string.timeline_v2_week, 30 to R.string.timeline_v2_month)
                    Box {
                        AssistChip(onClick = { datesOpen = true }, label = { Text(stringResource(dates.first { it.first == days }.second)) })
                        DropdownMenu(datesOpen, { datesOpen = false }) { dates.forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { days = value; datesOpen = false })
                        } }
                    }
                }
            }
            item { Text(stringResource(R.string.timeline_v2_local_activity), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val grouped = state.entries.groupBy { TimelineReader.day(it.record.occurredAt) }
            grouped.forEach { (date, entries) ->
                item(key = "day:$date") { Text(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(context.resources.configuration.locales[0])),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp).semantics { heading() }) }
                items(entries, key = { it.key }) { entry -> TimelineJournalCard(entry, labels[entry.record.scopeKey] ?: entry.record.scopeKey) { model.open(entry) } }
            }
            if (state.error != null) item { Text(stringResource(statusResource(state.error!!)), color = MaterialTheme.colorScheme.error) }
            if (state.entries.isEmpty() && !state.loading && state.error == null) item {
                Text(stringResource(R.string.timeline_empty_hint), modifier = Modifier.padding(vertical = 40.dp))
            }
            if (state.loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            if (state.more && !state.loading) item { OutlinedButton(onClick = model::more, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.timeline_v2_more)) } }
        }
    }
}

@Composable
private fun TimelineJournalCard(entry: TimelineReader.Entry, scope: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(when (entry.record.action) { "SYNC" -> Icons.Default.Sync; "TRASH", "PERMANENT_DELETE", "DELETE" -> Icons.Default.Delete; "CREATE" -> Icons.Default.Add; else -> Icons.Default.History }, null)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.details?.title?.takeIf { it.isNotBlank() } ?: scope, style = MaterialTheme.typography.titleMedium)
                Text(if (entry.checkCount > 1) stringResource(R.string.timeline_v2_checks, entry.checkCount)
                    else stringResource(actionResource(entry.record.action)), style = MaterialTheme.typography.bodyMedium)
                val fields = entry.details?.facts.orEmpty().map { fieldLabel(it.field) + if (entry.record.action == "SYNC" && it.field in TimelineReader.syncCountFields && it.after.toLongOrNull() != null) ": ${it.after}" else "" }.distinct()
                if (fields.isNotEmpty()) Text(fields.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$scope · ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, LocalContext.current.resources.configuration.locales[0]).format(Date(entry.record.occurredAt))}", style = MaterialTheme.typography.labelMedium)
                if (entry.status != "READY") Text(stringResource(statusResource(entry.status)), style = MaterialTheme.typography.bodySmall)
                if (entry.record.recoveryCandidate && entry.status == "READY") Text(stringResource(R.string.timeline_v2_saved), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun TimelineJournalDetail(state: TimelineJournalViewModel.State, onBack: () -> Unit,
    onPreview: () -> Unit, onConfirm: () -> Unit, onTrash: (TimelineTarget?) -> Unit, onMdbxHistory: ((Long) -> Unit)?) {
    val entry = requireNotNull(state.detail)
    val preview = state.plan != null
    val details = state.plan?.details ?: entry.details
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !state.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(if (preview) R.string.timeline_v2_preview else actionResource(entry.record.action)), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(details?.title?.takeIf { it.isNotBlank() } ?: stringResource(actionResource(entry.record.action)), style = MaterialTheme.typography.headlineMedium)
                Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, LocalContext.current.resources.configuration.locales[0]).format(Date(entry.record.occurredAt)), style = MaterialTheme.typography.bodySmall)
            }
            if (state.busy) item { CircularProgressIndicator() }
            if (entry.status != "READY") item { Text(stringResource(statusResource(entry.status))) }
            if (entry.record.action == "PERMANENT_DELETE") item { Text(stringResource(R.string.timeline_v2_permanent)) }
            if (entry.record.action == "DELETE") item { Text(stringResource(R.string.timeline_v2_trash_unknown)) }
            if (entry.record.action == "SYNC") item { Text(stringResource(R.string.timeline_v2_sync_details)) }
            if (state.error != null) item { Text(stringResource(statusResource(state.error)), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (state.restored) item { Text(stringResource(R.string.timeline_v2_success), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (preview) item { Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(stringResource(R.string.timeline_v2_partial), modifier = Modifier.padding(16.dp))
            } }
            details?.changes.orEmpty().forEach { change ->
                val before = change.before
                val after = change.after
                item(key = "target:${change.kind}:${change.itemId}") {
                    Text((after ?: before)?.accountLabel()?.takeIf { it.isNotBlank() } ?: ((after ?: before)?.row?.text("title").orEmpty() + " · #${change.itemId}"), style = MaterialTheme.typography.titleMedium)
                }
                TimelineRecoveryPolicy.columns(change).forEach { field ->
                    item(key = "${change.kind}:${change.itemId}:$field") {
                        TimelineValueDiff(fieldLabel(field), before?.row?.text(field), after?.row?.text(field), preview)
                    }
                }
                if (TimelineRecoveryPolicy.changedFieldIds(change).isNotEmpty()) {
                    val old = before?.fields.orEmpty().associateBy { it.text("id") }
                    val new = after?.fields.orEmpty().associateBy { it.text("id") }
                    TimelineRecoveryPolicy.changedFieldIds(change).forEach { id ->
                        item(key = "field:${change.itemId}:$id") {
                            TimelineValueDiff((new[id] ?: old[id])?.text("title").orEmpty() + " · #$id", old[id]?.text("value"), new[id]?.text("value"), preview)
                        }
                    }
                }
            }
            details?.facts.orEmpty().forEachIndexed { index, fact -> item(key = "fact:$index") {
                if (entry.record.action == "SYNC" && fact.field in TimelineReader.syncCountFields && fact.after.toLongOrNull() != null)
                    Text("${fieldLabel(fact.field)}: ${fact.after}")
                else TimelineValueDiff(fieldLabel(fact.field), fact.before, fact.after, false)
            } }
            if (entry.record.action == "SYNC") item { Text(stringResource(R.string.timeline_v2_sync_details)) }
            if (entry.record.action == "PERMANENT_DELETE") item { Text(stringResource(R.string.timeline_v2_permanent)) }
            if (entry.record.action in setOf("DELETE", "TRASH")) item {
                if (entry.record.action == "DELETE") Text(stringResource(R.string.timeline_v2_trash_unknown))
                OutlinedButton(onClick = { onTrash(entry.targets.singleOrNull()) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.timeline_trash_title)) }
            }
            val nativeId = entry.record.scopeKey.takeIf { it.startsWith("mdbx:") }?.substringAfter(':')?.toLongOrNull()
            if (nativeId != null && onMdbxHistory != null) item {
                OutlinedButton(onClick = { onMdbxHistory(nativeId) }) { Text(stringResource(R.string.timeline_v2_native)) }
            }
            if (entry.record.recoveryCandidate && entry.status == "READY" && !state.restored && state.error == null) item {
                Button(onClick = if (preview) onConfirm else onPreview, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (preview) stringResource(R.string.timeline_restore_selected, details?.changes?.size ?: 0) else stringResource(R.string.timeline_v2_preview))
                }
                if (preview) TextButton(onClick = onBack, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
            }
            if (!entry.record.recoveryCandidate && entry.record.action == "UPDATE" && entry.status == "READY") item { Text(stringResource(R.string.timeline_v2_unsupported)) }
        }
    }
}

@Composable
private fun TimelineValueDiff(label: String, before: String?, after: String?, preview: Boolean) {
    var visible by remember(label, before, after, preview) { mutableStateOf(false) }
    val context = LocalContext.current
    val generation by SessionManager.lockGeneration.collectAsState()
    var decoded by remember(label, before, after, preview, generation) { mutableStateOf<Pair<String?, String?>?>(null) }
    LaunchedEffect(visible, before, after, generation) {
        decoded = null
        if (visible) {
            val result = withContext(Dispatchers.IO) {
                val manager = SecurityManager(context)
                fun decode(value: String?): String? = value?.let { runCatching { manager.decryptDataIfMonicaCiphertext(it) }.getOrNull() }
                decode(before) to decode(after)
            }
            if (SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == generation) decoded = result
        }
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { visible = !visible }) { Text(stringResource(if (visible) R.string.hide else R.string.show)) }
            }
            val first = if (preview) decoded?.second else decoded?.first
            val second = if (preview) decoded?.first else decoded?.second
            Text(stringResource(if (preview) R.string.timeline_v2_current else R.string.timeline_v2_before), style = MaterialTheme.typography.labelMedium)
            Text(if (visible && first != null && first != "<redacted>") first else "••••••")
            HorizontalDivider()
            Text(stringResource(if (preview) R.string.timeline_v2_target else R.string.timeline_v2_after), style = MaterialTheme.typography.labelMedium)
            Text(if (visible && second != null && second != "<redacted>") second else "••••••")
        }
    }
}

internal fun actionResource(action: String): Int = when (action) {
    "CREATE" -> R.string.timeline_v2_create
    "UPDATE" -> R.string.timeline_v2_update
    "TRASH" -> R.string.timeline_v2_trash
    "RESTORE_FIELDS", "RESTORE_TRASH" -> R.string.timeline_v2_restore
    "PERMANENT_DELETE" -> R.string.timeline_permanent_delete_title
    "SYNC" -> R.string.timeline_v2_sync
    "ARCHIVE" -> R.string.timeline_v2_archive
    "UNARCHIVE" -> R.string.timeline_v2_unarchive
    "MOVE" -> R.string.timeline_v2_move
    "DELETE" -> R.string.timeline_op_delete
    else -> R.string.timeline_v2_batch
}
internal fun statusResource(status: String): Int = when (status) {
    "EXPIRED" -> R.string.timeline_v2_expired
    "MISSING" -> R.string.timeline_v2_missing
    "UNSUPPORTED" -> R.string.timeline_v2_unsupported
    "CONFLICT" -> R.string.timeline_v2_conflict
    "LOCKED" -> R.string.timeline_v2_locked
    "LEGACY" -> R.string.timeline_v2_legacy
    "REDACTED" -> R.string.timeline_v2_redacted
    else -> R.string.timeline_v2_unreadable
}
@Composable
private fun fieldLabel(field: String): String = timelineFieldResource(field)?.let { stringResource(it) } ?: field
