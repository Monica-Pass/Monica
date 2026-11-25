package takagi.ru.monica.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.databasecompare.*
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.KeePassKdbxService

private enum class ComparePage { SOURCES, REVIEW, DETAILS, OUTPUT, SAVED }

/** Explicit management tool. No comparison jobs are created by ordinary database browsing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatabaseCompareScreen(initialDatabaseId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val sources = remember(context) { DatabaseCompareSources(context) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val unlocked by SessionManager.isUnlocked.collectAsState()
    val lockGeneration by SessionManager.lockGeneration.collectAsState()
    val dataReadGeneration by SecurityManager.dataReadGeneration.collectAsState()
    val nativeLockVersions by KeePassKdbxService.nativeLockVersions.collectAsState()
    val nativeVersions by KeePassKdbxService.nativeIconVersions.collectAsState()
    var foreground by remember { mutableStateOf(true) }
    var authenticationRevision by remember { mutableIntStateOf(0) }
    var authentication by remember { mutableStateOf<CompareAuthentication?>(null) }
    var pickingSource by remember { mutableStateOf<Boolean?>(null) }
    var controller by remember { mutableStateOf<DatabaseCompareController?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var serial by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf(ComparePage.SOURCES) }
    var catalog by remember { mutableStateOf(emptyList<DatabaseCompareSource.Registered>()) }
    var left by remember { mutableStateOf<DatabaseCompareSource?>(null) }
    var right by remember { mutableStateOf<DatabaseCompareSource?>(null) }
    var leftPassword by remember { mutableStateOf("") }
    var rightPassword by remember { mutableStateOf("") }
    var leftKey by remember { mutableStateOf<Uri?>(null) }
    var rightKey by remember { mutableStateOf<Uri?>(null) }
    var outputPassword by remember { mutableStateOf("") }
    var repeatPassword by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<ComparePlan?>(null) }
    var choices by remember { mutableStateOf(emptyMap<String, CompareChoice>()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var pickingLeft by remember { mutableStateOf(false) }
    var inputPickerGeneration by remember { mutableStateOf<Long?>(null) }
    val selectedLockVersions = listOf(left, right).mapNotNull {
        (it as? DatabaseCompareSource.Registered)?.let { registered ->
            registered.id to (nativeLockVersions[registered.id] ?: 0L)
        }
    }

    fun clearSecrets() {
        leftPassword = ""; rightPassword = ""; outputPassword = ""; repeatPassword = ""
        plan = null; choices = emptyMap(); selectedId = null
    }

    fun invalidate(showMessage: Boolean) {
        serial++; job?.cancel(); job = null
        controller?.invalidate(); controller = null
        clearSecrets(); authentication = null; authenticationRevision++; pickingSource = null; busy = false
        if (page != ComparePage.SAVED) page = ComparePage.SOURCES
        if (showMessage) error = R.string.db_compare_expired
    }

    fun navigateBack() {
        when {
            busy -> invalidate(false)
            page == ComparePage.DETAILS || page == ComparePage.OUTPUT -> {
                outputPassword = ""; repeatPassword = ""; selectedId = null; page = ComparePage.REVIEW
            }
            page == ComparePage.REVIEW -> invalidate(false)
            else -> { invalidate(false); onBack() }
        }
    }
    BackHandler(onBack = ::navigateBack)

    LaunchedEffect(sources, unlocked) {
        if (unlocked) {
            runCatching { sources.catalog(CompareFormat.KDBX) }
                .onSuccess { list ->
                    catalog = list
                    if (left == null) left = list.firstOrNull { it.id == initialDatabaseId }
                }.onFailure { if (it !is CancellationException) error = R.string.db_compare_failed }
        } else { invalidate(false); catalog = emptyList(); left = null; right = null }
    }
    LaunchedEffect(lockGeneration, dataReadGeneration, selectedLockVersions) {
        if (!unlocked || controller != null) invalidate(controller != null)
    }
    LaunchedEffect(catalog, lockGeneration, dataReadGeneration, nativeVersions, foreground, authenticationRevision) {
        authentication = null
        if (unlocked && foreground) {
            try {
                val ids = sources.authenticatedIds(catalog)
                authentication = CompareAuthentication(lockGeneration, dataReadGeneration, nativeVersions, ids)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                authentication = CompareAuthentication(lockGeneration, dataReadGeneration, nativeVersions, emptySet())
            }
        }
    }
    val currentAuthentication = authentication?.takeIf {
        foreground && unlocked && it.lockGeneration == lockGeneration &&
            it.dataGeneration == dataReadGeneration && it.nativeVersions == nativeVersions
    }
    fun usesSession(source: DatabaseCompareSource?) = source is DatabaseCompareSource.Registered &&
        source.id in currentAuthentication?.ids.orEmpty()
    fun checking(source: DatabaseCompareSource?) = source is DatabaseCompareSource.Registered && currentAuthentication == null

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) foreground = true
            if (event == Lifecycle.Event.ON_STOP) {
                foreground = false
                authentication = null
                pickingSource = null
                val keepEncryptedResult = controller?.awaitingPicker == true
                val hadReview = plan != null || busy
                clearSecrets()
                if (!keepEncryptedResult) invalidate(hadReview)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            serial++; job?.cancel(); controller?.close(); clearSecrets()
        }
    }

    val databasePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val targetLeft = pickingLeft
        val generation = inputPickerGeneration
        inputPickerGeneration = null
        val attempt = serial
        if (uri != null && generation == SessionManager.lockGeneration.value && SessionManager.isUnlocked.value) scope.launch {
            runCatching { sources.document(uri) }
                .onSuccess {
                    if (serial == attempt && generation == SessionManager.lockGeneration.value && SessionManager.isUnlocked.value) {
                        if (targetLeft) { left = it; leftKey = null } else { right = it; rightKey = null }
                    }
                }
                .onFailure { if (serial == attempt && it !is CancellationException) error = R.string.db_compare_failed }
        }
    }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val generation = inputPickerGeneration
        inputPickerGeneration = null
        if (uri != null && generation == SessionManager.lockGeneration.value && SessionManager.isUnlocked.value) {
            if (pickingLeft) leftKey = uri else rightKey = uri
        }
    }
    val destinationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-keepass2")
    ) { uri ->
        val activeController = controller
        if (uri == null || activeController == null) {
            activeController?.cancelPicker(); invalidate(false)
        } else {
            busy = true
            val attempt = serial
            job = scope.launch {
                try {
                    activeController.exportDocument(uri)
                    if (serial == attempt && unlocked) { page = ComparePage.SAVED; error = null }
                } catch (failure: Exception) {
                    if (serial == attempt && failure !is CancellationException) {
                        error = R.string.db_compare_failed; page = ComparePage.SOURCES
                    }
                } finally {
                    activeController.close()
                    if (serial == attempt) { busy = false; controller = null }
                }
            }
        }
    }

    pickingSource?.let { targetLeft ->
        CompareSourcePicker(catalog, currentAuthentication?.ids.orEmpty(),
            selected = if (targetLeft) left else right,
            onDismiss = { pickingSource = null },
            onSelect = {
                if (targetLeft) { left = it; leftPassword = ""; leftKey = null }
                else { right = it; rightPassword = ""; rightKey = null }
                error = null; pickingSource = null
            },
            onFile = {
                pickingSource = null; clearSecrets(); pickingLeft = targetLeft
                inputPickerGeneration = SessionManager.lockGeneration.value
                databasePicker.launch(arrayOf("*/*"))
            })
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(when (page) {
            ComparePage.SOURCES -> R.string.db_compare_title
            ComparePage.REVIEW, ComparePage.DETAILS -> R.string.db_compare_review
            ComparePage.OUTPUT, ComparePage.SAVED -> R.string.db_compare_output_title
        })) }, navigationIcon = {
            IconButton(onClick = ::navigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("database_compare_screen")) {
            error?.let { Text(stringResource(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.db_compare_working), Modifier.padding(16.dp))
                TextButton(onClick = { invalidate(false) }) { Text(stringResource(R.string.cancel)) }
            } else if (unlocked) when (page) {
                ComparePage.SOURCES -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(stringResource(R.string.db_compare_choose), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.db_compare_intro), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CompareSourceCard(true, left, leftPassword, leftKey, usesSession(left), checking(left),
                        { pickingSource = true }, { leftPassword = it },
                        { clearSecrets(); pickingLeft = true; inputPickerGeneration = SessionManager.lockGeneration.value; keyPicker.launch(arrayOf("*/*")) }, { leftKey = null })
                    CompareSourceCard(false, right, rightPassword, rightKey, usesSession(right), checking(right),
                        { pickingSource = false }, { rightPassword = it },
                        { clearSecrets(); pickingLeft = false; inputPickerGeneration = SessionManager.lockGeneration.value; keyPicker.launch(arrayOf("*/*")) }, { rightKey = null })
                    Button(onClick = {
                        val a = left ?: return@Button; val b = right ?: return@Button
                        controller?.close()
                        val activeController = DatabaseCompareController(context, sources).also { controller = it }
                        val first = DatabaseCompareSelection(a, if (usesSession(a)) charArrayOf() else leftPassword.toCharArray(),
                            if (usesSession(a)) null else leftKey, usesSession(a))
                        val second = DatabaseCompareSelection(b, if (usesSession(b)) charArrayOf() else rightPassword.toCharArray(),
                            if (usesSession(b)) null else rightKey, usesSession(b))
                        leftPassword = ""; rightPassword = ""; error = null; busy = true
                        val attempt = ++serial
                        job = scope.launch {
                            try {
                                val result = activeController.compare(first, second)
                                if (serial == attempt && unlocked) {
                                    plan = result; choices = emptyMap(); page = ComparePage.REVIEW
                                }
                            } catch (failure: Exception) {
                                if (serial == attempt && failure !is CancellationException) error = R.string.db_compare_failed
                            } finally {
                                first.password.fill('\u0000'); second.password.fill('\u0000')
                                if (serial == attempt) busy = false
                            }
                        }
                    }, enabled = left != null && right != null && !checking(left) && !checking(right),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("compare_start")) {
                        Text(stringResource(R.string.db_compare_start))
                    }
                }
                ComparePage.REVIEW -> plan?.let { review ->
                    DatabaseComparisonReview(review, choices,
                        onOpen = { selectedId = it; page = ComparePage.DETAILS },
                        onContinue = { page = ComparePage.OUTPUT })
                }
                ComparePage.DETAILS -> plan?.let { review ->
                    review.rows.firstOrNull { it.id == selectedId }?.let { row ->
                        DatabaseComparisonDetails(review, row, choices[row.id] ?: row.defaultChoice) { choice ->
                            choices = choices + (row.id to choice); page = ComparePage.REVIEW; selectedId = null
                        }
                    }
                }
                ComparePage.OUTPUT -> plan?.let { review ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.db_compare_output_info), style = MaterialTheme.typography.bodyLarge)
                        val counts = review.rows.mapNotNull { choices[it.id] ?: it.defaultChoice }.groupingBy { it }.eachCount()
                        Text(stringResource(R.string.db_compare_selection_summary, counts[CompareChoice.LEFT] ?: 0,
                            counts[CompareChoice.RIGHT] ?: 0, counts[CompareChoice.BOTH] ?: 0))
                        ComparePasswordField(outputPassword, { outputPassword = it }, R.string.db_compare_output_password, "compare_output_password")
                        ComparePasswordField(repeatPassword, { repeatPassword = it }, R.string.db_compare_repeat_password, "compare_repeat_password")
                        if (repeatPassword.isNotEmpty() && outputPassword != repeatPassword)
                            Text(stringResource(R.string.db_compare_password_mismatch), color = MaterialTheme.colorScheme.error)
                        Button(onClick = {
                            val activeController = controller ?: return@Button
                            val password = outputPassword.toCharArray()
                            outputPassword = ""; repeatPassword = ""; busy = true; error = null
                            val selection = choices.toMap(); val attempt = serial
                            job = scope.launch {
                                try {
                                    val result = activeController.buildResult(selection, password)
                                    if (serial == attempt && unlocked) {
                                        clearSecrets(); activeController.suspendForPicker()
                                        destinationPicker.launch(result.name)
                                    }
                                } catch (failure: Exception) {
                                    if (serial == attempt && failure !is CancellationException) error = R.string.db_compare_failed
                                } finally { password.fill('\u0000'); if (serial == attempt) busy = false }
                            }
                        }, enabled = outputPassword.isNotEmpty() && outputPassword == repeatPassword,
                            modifier = Modifier.fillMaxWidth().testTag("compare_generate")) { Text(stringResource(R.string.db_compare_write)) }
                    }
                }
                ComparePage.SAVED -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.db_compare_saved), style = MaterialTheme.typography.titleLarge)
                    Button(onClick = { page = ComparePage.SOURCES }) { Text(stringResource(R.string.db_compare_restart)) }
                }
            }
        }
    }
}

private data class CompareAuthentication(
    val lockGeneration: Long,
    val dataGeneration: Long,
    val nativeVersions: Map<Long, Long>,
    val ids: Set<Long>,
)

@Composable
internal fun ComparePasswordField(value: String, change: (String) -> Unit, label: Int, tag: String) {
    OutlinedTextField(value, change, label = { Text(stringResource(label)) }, singleLine = true,
        shape = MaterialTheme.shapes.large,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable
internal fun DatabaseComparisonReview(plan: ComparePlan, choices: Map<String, CompareChoice>, onOpen: (String) -> Unit, onContinue: () -> Unit) {
    var filter by remember { mutableIntStateOf(1) }
    val unresolved = plan.rows.count { (choices[it.id] ?: it.defaultChoice) !in it.allowedChoices }
    val canSave = plan.blockers.isEmpty() && unresolved == 0 && plan.rows.none { it.problem != null || it.change == CompareChange.BLOCKED }
    val rows = plan.rows.filter { row -> when (filter) { 1 -> row.change != CompareChange.UNCHANGED; 2 -> (choices[row.id] ?: row.defaultChoice) !in row.allowedChoices; else -> true } }
    LazyColumn(Modifier.fillMaxSize().testTag("compare_review"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("${plan.leftName} ↔ ${plan.rightName}", style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.db_compare_count, plan.rows.size, unresolved))
        }
        plan.blockers.forEach { problem -> item { Text(stringResource(compareProblemLabel(problem)), color = MaterialTheme.colorScheme.error) } }
        item {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.db_compare_all, R.string.db_compare_differences, R.string.db_compare_pending).forEachIndexed { index, label ->
                    FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(stringResource(label)) })
                }
            }
        }
        if (rows.isEmpty()) item { Text(stringResource(R.string.db_compare_empty)) }
        items(rows, key = { it.id }) { row ->
            Card(onClick = { onOpen(row.id) }, modifier = Modifier.fillMaxWidth().testTag("compare_row_${row.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(compareRowTitle(row), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(compareKindLabel(row.kind)), style = MaterialTheme.typography.labelMedium)
                    if (row.path.isNotBlank()) Text(row.path, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(compareChangeLabel(row.change)), color = MaterialTheme.colorScheme.primary)
                    (choices[row.id] ?: row.defaultChoice)?.let { Text(stringResource(compareChoiceLabel(it)), style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
        item {
            Button(onClick = onContinue, enabled = canSave, modifier = Modifier.fillMaxWidth().testTag("compare_continue")) { Text(stringResource(R.string.db_compare_preview)) }
        }
    }
}

@Composable
internal fun DatabaseComparisonDetails(plan: ComparePlan, row: CompareRow, selected: CompareChoice?, onChoice: (CompareChoice) -> Unit) {
    var reveal by remember(row.id) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("compare_details"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(compareRowTitle(row), style = MaterialTheme.typography.headlineSmall)
            Text(row.path)
            Text(stringResource(compareChangeLabel(row.change)))
            TextButton(onClick = { reveal = !reveal }) { Text(stringResource(if (reveal) R.string.db_compare_hide else R.string.db_compare_reveal)) }
        }
        items(row.fields) { field ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(compareFieldTitle(field), style = MaterialTheme.typography.titleSmall)
                    Text(plan.leftName, style = MaterialTheme.typography.labelMedium)
                    Text(if (field.left == null) stringResource(R.string.db_compare_absent) else if (field.sensitive && !reveal) "••••••" else field.left)
                    HorizontalDivider()
                    Text(plan.rightName, style = MaterialTheme.typography.labelMedium)
                    Text(if (field.right == null) stringResource(R.string.db_compare_absent) else if (field.sensitive && !reveal) "••••••" else field.right)
                }
            }
        }
        (row.problem ?: row.note)?.let { problem -> item { Text(stringResource(compareProblemLabel(problem)), color = MaterialTheme.colorScheme.error) } }
        item { Text(stringResource(if (row.change == CompareChange.ONLY_LEFT || row.change == CompareChange.ONLY_RIGHT)
            R.string.db_compare_choice_omits else R.string.db_compare_choice_history), style = MaterialTheme.typography.bodySmall) }
        items(row.allowedChoices.toList()) { choice ->
            OutlinedButton(onClick = { onChoice(choice) }, enabled = row.problem == null && row.change != CompareChange.BLOCKED,
                modifier = Modifier.fillMaxWidth().testTag("compare_choice_${choice.name}")) {
                Text((if (selected == choice) "✓ " else "") + stringResource(compareChoiceLabel(choice)))
            }
        }
    }
}

@Composable
private fun compareRowTitle(row: CompareRow): String = when {
    row.kind == "metadata" -> stringResource(R.string.keepass_database_settings_title)
    row.title.isBlank() -> stringResource(compareKindLabel(row.kind))
    else -> row.title
}

private fun compareKindLabel(kind: String): Int = when (kind) {
    "group" -> R.string.keepass_conflict_kind_group
    "metadata" -> R.string.keepass_conflict_kind_metadata
    "icon" -> R.string.keepass_conflict_kind_icon
    "deletion" -> R.string.keepass_conflict_kind_deleted_object
    "attachment" -> R.string.keepass_unreferenced_attachments
    else -> R.string.keepass_conflict_kind_entry
}

@Composable
private fun compareFieldTitle(field: CompareField): String {
    val label = if (!field.generatedLabel) when (field.name) {
        "Title" -> R.string.title
        "UserName" -> R.string.username
        "Password" -> R.string.password
        "URL" -> R.string.website
        "Notes" -> R.string.notes
        else -> null
    } else when (field.name) {
        "Name" -> R.string.name
        "Notes" -> R.string.notes
        "Parent" -> R.string.keepass_conflict_detail_location
        "Tags" -> R.string.keepass_native_tags
        "Properties" -> R.string.keepass_conflict_detail_properties
        "History" -> R.string.keepass_native_history_count
        "Version" -> R.string.version
        "Attachments" -> R.string.attachments
        "AutoType" -> R.string.keepass_native_auto_type
        "CustomData" -> R.string.keepass_native_custom_data
        "Times", "DeletionTime" -> R.string.steam_time_label
        "Bytes" -> R.string.db_compare_field_bytes
        "Content" -> R.string.content
        "Protected" -> R.string.keepass_native_protected_field
        "Description" -> R.string.description
        "DefaultUser" -> R.string.keepass_database_default_username
        "RecycleBin" -> R.string.keepass_database_recycle_bin_group
        "Templates" -> R.string.keepass_database_templates_group
        "RecycleBinEnabled" -> R.string.keepass_database_recycle_bin
        "HistoryMaxItems" -> R.string.keepass_database_history_max_items
        "HistoryMaxSize" -> R.string.db_compare_history_bytes
        "MaintenanceHistoryDays" -> R.string.keepass_database_history_maintenance_days
        "MemoryProtection" -> R.string.db_compare_memory
        "Color" -> R.string.keepass_database_color
        "MasterKeyChangeRec" -> R.string.db_compare_key_rec
        "MasterKeyChangeForce" -> R.string.db_compare_key_force
        "NativeMetadata" -> R.string.keepass_native_metadata
        else -> null
    }
    var title = label?.let { stringResource(it) } ?: field.name
    if (field.protectedFlag) title += " · " + stringResource(R.string.keepass_native_protected_field)
    field.historyIndex?.let { title = "${stringResource(R.string.history)} $it · $title" }
    return title
}

internal fun compareChoiceLabel(choice: CompareChoice): Int = when (choice) {
    CompareChoice.LEFT -> R.string.db_compare_use_left
    CompareChoice.RIGHT -> R.string.db_compare_use_right
    CompareChoice.BOTH -> R.string.db_compare_both
}
internal fun compareChangeLabel(change: CompareChange): Int = when (change) {
    CompareChange.ONLY_LEFT -> R.string.db_compare_only_left
    CompareChange.ONLY_RIGHT -> R.string.db_compare_only_right
    CompareChange.CHANGED -> R.string.db_compare_changed
    CompareChange.UNCHANGED -> R.string.db_compare_unchanged
    CompareChange.DELETION -> R.string.db_compare_deletion
    CompareChange.BLOCKED -> R.string.db_compare_blocked
}
internal fun compareProblemLabel(problem: CompareProblem): Int = when (problem) {
    CompareProblem.UNSUPPORTED_CONTENT -> R.string.db_compare_problem_unsupported
    CompareProblem.INCOMPLETE_CONTENT -> R.string.db_compare_problem_incomplete
    CompareProblem.IDENTITY_CONFLICT -> R.string.db_compare_problem_identity
    CompareProblem.UNRESOLVED_REFERENCE -> R.string.db_compare_problem_reference
    CompareProblem.HISTORY_INCOMPATIBLE -> R.string.db_compare_problem_history
    CompareProblem.POLICY_RESTRICTED -> R.string.db_compare_problem_policy
    CompareProblem.SOURCE_CHANGED -> R.string.db_compare_problem_changed
    CompareProblem.TOO_LARGE -> R.string.db_compare_problem_large
    CompareProblem.INVALID_SELECTION -> R.string.db_compare_problem_selection
}
