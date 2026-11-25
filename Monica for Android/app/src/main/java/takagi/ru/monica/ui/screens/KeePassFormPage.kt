package takagi.ru.monica.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import takagi.ru.monica.R
import takagi.ru.monica.keepass.KeePassNativeResolvedRoute
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.LocalReduceAnimations
import takagi.ru.monica.ui.components.LocalFilledEntryForm
import takagi.ru.monica.ui.navigation.MonicaPageContent
import takagi.ru.monica.viewmodel.LocalKeePassViewModel

/** Only the scalar page phase participates in transitions. Credentials stay in the form. */
@Composable
internal fun LocalKeePassScreen(
    viewModel: LocalKeePassViewModel,
    onNavigateBack: () -> Unit,
    onNavigateNativeEntry: (KeePassNativeResolvedRoute) -> Unit,
) {
    var page by remember { mutableStateOf("manager") }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var request by remember { mutableLongStateOf(0) }
    val lockGeneration by SessionManager.lockGeneration.collectAsState()
    var enteredGeneration by remember { mutableLongStateOf(lockGeneration) }
    val holder = rememberSaveableStateHolder()
    fun close() {
        request++
        job?.cancel()
        job = null
        busy = false
        error = null
        uri = null
        page = "manager"
    }
    DisposableEffect(Unit) { onDispose { request++; job?.cancel() } }
    LaunchedEffect(lockGeneration) { if (lockGeneration != enteredGeneration) close() }
    BackHandler(page != "manager") { close() }
    MonicaPageContent(pageKey = page, animate = !LocalReduceAnimations.current) {
        // Remove sensitive form content in the same composition that observes a lock.
        if (lockGeneration != enteredGeneration && page != "manager") return@MonicaPageContent
        when (page) {
            "create" -> CreateKeePassDatabaseScreen(
                onDismiss = ::close,
                busy = busy,
                error = error,
                onGenerateKeyFile = { target, finished ->
                    val token = ++request
                    job = viewModel.generateKeyFile(target) { success ->
                        if (token == request) {
                            if (!success) error = (viewModel.operationState.value as? LocalKeePassViewModel.OperationState.Error)?.message
                            finished(success)
                        }
                    }
                },
                onCreate = { name, password, location, directory, key, options, copy ->
                    busy = true; error = null
                    val token = ++request
                    job = viewModel.createDatabase(name, password, location, directory, key, options, keepKeyFileCopy = copy) { success ->
                        if (token == request) {
                            busy = false
                            if (success) close() else error = (viewModel.operationState.value as? LocalKeePassViewModel.OperationState.Error)?.message
                        }
                    }
                },
            )
            "open" -> uri?.let { selected ->
                OpenKeePassDatabaseScreen(selected, ::close, busy, error) { name, password, key, copy ->
                    busy = true; error = null
                    val token = ++request
                    job = viewModel.importExternalDatabase(name, selected, password, key, keepKeyFileCopy = copy) { success ->
                        if (token == request) {
                            busy = false
                            if (success) close() else error = (viewModel.operationState.value as? LocalKeePassViewModel.OperationState.Error)?.message
                        }
                    }
                }
            }
            else -> holder.SaveableStateProvider("manager") {
                LocalKeePassManagementScreen(viewModel, onNavigateBack, onNavigateNativeEntry,
                    onCreateDatabase = { enteredGeneration = lockGeneration; error = null; page = "create" },
                    onOpenDatabaseFile = { enteredGeneration = lockGeneration; uri = it; error = null; page = "open" })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeePassFormPage(
    title: String,
    onDismiss: () -> Unit,
    busy: Boolean,
    error: String?,
    testTagPrefix: String,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    CompositionLocalProvider(LocalFilledEntryForm provides true) {
        Scaffold(
            modifier = Modifier.testTag("${testTagPrefix}_page"),
            topBar = {
                DatabaseManagementTopAppBar(title = { Text(title) }, navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.go_back))
                    }
                })
            },
            bottomBar = { DatabaseManagementFormActionBar(testTagPrefix, actions) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp).testTag("${testTagPrefix}_fields"),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("${testTagPrefix}_error")) }
                content()
            }
        }
    }
}

@Composable
internal fun KeePassFormButtonGroup(
    firstLabel: String, secondLabel: String,
    firstClick: () -> Unit, secondClick: () -> Unit,
    enabled: Boolean = true, secondEnabled: Boolean = true,
) {
    val stacked = LocalDensity.current.fontScale > 1.25f
    val firstShape = if (stacked) RoundedCornerShape(24.dp, 24.dp, 6.dp, 6.dp)
        else RoundedCornerShape(24.dp, 6.dp, 6.dp, 24.dp)
    val secondShape = if (stacked) RoundedCornerShape(6.dp, 6.dp, 24.dp, 24.dp)
        else RoundedCornerShape(6.dp, 24.dp, 24.dp, 6.dp)
    val first: @Composable (Modifier) -> Unit = { modifier ->
        FilledTonalButton(firstClick, modifier.heightIn(min = 52.dp), enabled = enabled, shape = firstShape) { Text(firstLabel) }
    }
    val second: @Composable (Modifier) -> Unit = { modifier ->
        FilledTonalButton(secondClick, modifier.heightIn(min = 52.dp), enabled = enabled && secondEnabled, shape = secondShape) { Text(secondLabel) }
    }
    if (stacked) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        first(Modifier.fillMaxWidth()); second(Modifier.fillMaxWidth())
    } else Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        first(Modifier.weight(1f).fillMaxHeight()); second(Modifier.weight(1f).fillMaxHeight())
    }
}
