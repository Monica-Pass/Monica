package takagi.ru.monica.bitwarden.ui

import takagi.ru.monica.ui.navigation.MonicaPageContent
import takagi.ru.monica.ui.navigation.MonicaPageDialogMotion

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.api.*
import takagi.ru.monica.bitwarden.discovery.*
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.components.LocalFilledEntryForm
import takagi.ru.monica.ui.components.OutlinedTextField as MonicaTextField

/** A memory-only editor. The owner cancels pending authentication before opening it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BitwardenServerConfigurationDialog(
    initialDraft: BitwardenEnvironmentDraft,
    email: String,
    baseLocked: Boolean = false,
    onApply: (BitwardenEnvironmentDraft) -> Unit,
    onDismiss: () -> Unit,
    discovery: BitwardenServerDiscovery? = null,
) {
    val editor = remember(initialDraft) { ServerConfigurationEditor(initialDraft) }
    val scope = rememberCoroutineScope()
    val lookup = remember(discovery) { discovery ?: BitwardenServerDiscovery(AndroidBitwardenDnsTxtResolver()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentEmail by rememberUpdatedState(email)
    val currentApply by rememberUpdatedState(onApply)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentBaseLocked by rememberUpdatedState(baseLocked)
    val initialLock = remember(editor) { SessionManager.lockGeneration.value }
    val lockGeneration by SessionManager.lockGeneration.collectAsState()
    fun dismiss() { if (editor.active) { editor.erase(); currentDismiss() } }
    fun current(revision: Long, capturedEmail: String): Boolean = editor.active && editor.revision == revision &&
        currentEmail == capturedEmail && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == initialLock

    DisposableEffect(editor, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) editor.invalidate()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); editor.erase() }
    }
    LaunchedEffect(lockGeneration) { if (lockGeneration != initialLock) dismiss() }
    LaunchedEffect(email) { editor.invalidate() }

    fun back() {
        if (editor.page == ServerConfigurationPage.OVERVIEW) dismiss()
        else { if (editor.page == ServerConfigurationPage.DISCOVERY) editor.invalidate(); editor.page = ServerConfigurationPage.OVERVIEW }
    }
    fun apply() {
        if (!editor.active || editor.validating) return
        editor.invalidate()
        val revision = editor.revision
        val capturedEmail = currentEmail
        val draft = editor.snapshot()
        editor.validating = true
        editor.validationJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { BitwardenEnvironmentResolver.resolve(draft, UUID.randomUUID().toString()) }
                if (!current(revision, capturedEmail)) return@launch
                editor.errors = result.errors
                if (result.isValid) {
                    editor.validationJob = null
                    editor.erase()
                    currentApply(draft)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(revision, capturedEmail)) editor.genericError = true }
            finally { if (editor.revision == revision) editor.validating = false }
        }
    }
    fun find() {
        if (!editor.active || currentBaseLocked) return
        editor.invalidate()
        editor.page = ServerConfigurationPage.DISCOVERY
        val domain = BitwardenServerDiscovery.canonicalEmailDomain(currentEmail)
        if (domain == null) { editor.lookupMessage = R.string.bitwarden_server_lookup_email; return }
        val revision = editor.revision
        val capturedEmail = currentEmail
        editor.lookupEmail = capturedEmail
        editor.lookupRevision = revision
        editor.searching = true
        editor.lookupJob = scope.launch {
            try {
                val result = lookup.discover(domain)
                if (current(revision, capturedEmail) && !currentBaseLocked) {
                    editor.result = result
                    editor.lookupMessage = discoveryMessage(result.status)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(revision, capturedEmail)) editor.lookupMessage = R.string.bitwarden_server_lookup_failed }
            finally { if (editor.revision == revision) editor.searching = false }
        }
    }

    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightSurface
                    isAppearanceLightNavigationBars = lightSurface
                }
            }
        }
        MonicaPageDialogMotion()
        MonicaPageContent(pageKey = editor.page, animateInitial = true, animate = editor.active, modifier = Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize().testTag("bitwarden_server_configuration"), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
                topBar = { TopAppBar(title = { Text(stringResource(pageTitle(editor.page))) }, navigationIcon = {
                    IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                }) },
                bottomBar = {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { if (editor.page == ServerConfigurationPage.OVERVIEW) apply() else back() },
                            enabled = editor.active && !editor.validating,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("bitwarden_server_apply")) {
                            if (editor.validating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Text(stringResource(if (editor.page == ServerConfigurationPage.OVERVIEW) R.string.bitwarden_server_apply else R.string.bitwarden_server_done))
                        }
                    }
                },
            ) { insets ->
                key(editor.page) {
                    CompositionLocalProvider(LocalFilledEntryForm provides true) {
                        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (editor.genericError) ServerConfigurationNotice(stringResource(R.string.bitwarden_server_error_generic), error = true)
                            if (editor.errors.isNotEmpty()) ServerConfigurationNotice(stringResource(R.string.bitwarden_server_error_summary), error = true)
                            when (editor.page) {
                                ServerConfigurationPage.OVERVIEW -> {
                                    if (baseLocked) ServerConfigurationNotice(stringResource(R.string.bitwarden_server_locked))
                                    Text(stringResource(R.string.bitwarden_server_preset), style = MaterialTheme.typography.labelLarge)
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        BitwardenEnvironmentPreset.entries.forEachIndexed { index, preset ->
                                            Surface(shape = bitwardenGroupShape(index == 0, index == 2), color = MaterialTheme.colorScheme.surfaceContainer) {
                                                Row(Modifier.fillMaxWidth().clickable(enabled = !baseLocked && !editor.validating) {
                                                    if (editor.preset != preset) editor.deployment(preset, "")
                                                }.heightIn(min = 56.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    RadioButton(editor.preset == preset, onClick = null, enabled = !baseLocked && !editor.validating)
                                                    Spacer(Modifier.width(12.dp))
                                                    Text(stringResource(presetLabel(preset)), Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    }
                                    ServerConfigurationField(editor.baseForDisplay(), { editor.deployment(BitwardenEnvironmentPreset.CUSTOM, it) },
                                        stringResource(R.string.bitwarden_server_base), "base", editor.errors, BitwardenEnvironmentField.BASE,
                                        enabled = !baseLocked && editor.preset == BitwardenEnvironmentPreset.CUSTOM && !editor.validating)
                                    Text(stringResource(R.string.bitwarden_server_base_hint), style = MaterialTheme.typography.bodyMedium)
                                    Text(stringResource(R.string.bitwarden_server_deployment_clears), style = MaterialTheme.typography.bodySmall)
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        BitwardenSettingRow(stringResource(R.string.bitwarden_server_endpoints), Icons.Outlined.Link,
                                            supporting = stringResource(R.string.bitwarden_server_endpoints_hint), first = true, last = false,
                                            onClick = { editor.page = ServerConfigurationPage.ENDPOINTS })
                                        BitwardenSettingRow(stringResource(R.string.bitwarden_server_headers), Icons.Outlined.Tune,
                                            supporting = stringResource(R.string.bitwarden_server_headers_hint), first = false, last = false,
                                            onClick = { editor.page = ServerConfigurationPage.HEADERS })
                                        BitwardenSettingRow(stringResource(R.string.bitwarden_server_certificates), Icons.Outlined.VerifiedUser,
                                            first = false, last = true, onClick = { editor.page = ServerConfigurationPage.TLS })
                                    }
                                    FilledTonalButton(onClick = ::find, enabled = !baseLocked && !editor.validating,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("bitwarden_server_find")) {
                                        Icon(Icons.Outlined.Search, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.bitwarden_server_find))
                                    }
                                    Text(stringResource(R.string.bitwarden_server_find_hint), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { editor.edit { editor.clearSettings() } }, enabled = !editor.validating, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.bitwarden_server_reset))
                                    }
                                    Text(stringResource(R.string.bitwarden_server_reset_hint), style = MaterialTheme.typography.bodySmall)
                                }
                                ServerConfigurationPage.ENDPOINTS -> {
                                    Text(stringResource(R.string.bitwarden_server_endpoints_hint))
                                    ServerConfigurationField(editor.web, { editor.edit { editor.web = it } }, stringResource(R.string.bitwarden_server_web), "web", editor.errors, BitwardenEnvironmentField.WEB)
                                    ServerConfigurationField(editor.api, { editor.edit { editor.api = it } }, "API", "api", editor.errors, BitwardenEnvironmentField.API)
                                    ServerConfigurationField(editor.identity, { editor.edit { editor.identity = it } }, stringResource(R.string.bitwarden_server_identity), "identity", editor.errors, BitwardenEnvironmentField.IDENTITY)
                                    ServerConfigurationField(editor.notifications, { editor.edit { editor.notifications = it } }, stringResource(R.string.bitwarden_server_notifications), "notifications", editor.errors, BitwardenEnvironmentField.NOTIFICATIONS)
                                }
                                ServerConfigurationPage.HEADERS -> {
                                    Text(stringResource(R.string.bitwarden_server_headers_hint))
                                    ServerConfigurationDestinations(editor)
                                    editor.headers.forEachIndexed { index, header -> key(header.id) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            ServerConfigurationField(header.name, { editor.edit { header.name = it } }, stringResource(R.string.bitwarden_server_header_name), "header_${index}_name",
                                                editor.errors, BitwardenEnvironmentField.HEADER_NAME, index = index, first = true, last = false)
                                            ServerConfigurationField(header.value, { editor.edit { header.value = it } }, stringResource(R.string.bitwarden_server_header_value), "header_${index}_value",
                                                editor.errors, BitwardenEnvironmentField.HEADER_VALUE, index = index, secret = true, first = false, last = true)
                                            TextButton(onClick = { editor.edit { header.erase(); editor.headers = editor.headers.filterNot { it === header } } }, modifier = Modifier.fillMaxWidth()) {
                                                Icon(Icons.Outlined.Delete, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.remove))
                                            }
                                        }
                                    } }
                                    editor.errors.filter { it.field == BitwardenEnvironmentField.HEADERS }.forEach { ServerConfigurationNotice(stringResource(errorMessage(it.code)), true) }
                                    FilledTonalButton(onClick = { editor.edit { editor.headers = editor.headers + ServerConfigurationHeader("", "") } },
                                        enabled = editor.headers.size < BitwardenTransportPolicy.MAX_HEADERS, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                        Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.bitwarden_server_add_header))
                                    }
                                }
                                ServerConfigurationPage.TLS -> {
                                    Text(stringResource(R.string.bitwarden_server_certificates_hint))
                                    ServerConfigurationDestinations(editor)
                                    ServerConfigurationField(editor.tls.caCertificatePem.orEmpty(), { editor.edit { editor.tls = editor.tls.copy(caCertificatePem = it.takeUnless(String::isEmpty)) } },
                                        stringResource(R.string.bitwarden_server_ca), "ca", editor.errors, BitwardenEnvironmentField.TLS_CA, multiline = true)
                                    BitwardenSettingRow(stringResource(R.string.legacy_ui_tls_enable_mtls), Icons.Outlined.VerifiedUser,
                                        checked = editor.tls.mtlsEnabled, onCheckedChange = { enabled -> editor.edit { editor.tls = editor.tls.copy(mtlsEnabled = enabled) } })
                                    ServerConfigurationField(editor.tls.clientCertPkcs12Base64.orEmpty(), { editor.edit { editor.tls = editor.tls.copy(clientCertPkcs12Base64 = it.takeUnless(String::isEmpty)) } },
                                        stringResource(R.string.legacy_ui_tls_client_certificate), "p12", editor.errors, BitwardenEnvironmentField.TLS_CLIENT_CERTIFICATE, secret = true, multiline = true)
                                    ServerConfigurationField(editor.tls.clientCertPassword.orEmpty(), { editor.edit { editor.tls = editor.tls.copy(clientCertPassword = it) } },
                                        stringResource(R.string.legacy_ui_tls_client_password), "p12_password", editor.errors, BitwardenEnvironmentField.TLS_CLIENT_PASSWORD, secret = true)
                                    editor.errors.filter { it.field == BitwardenEnvironmentField.TLS_MODE }.forEach { ServerConfigurationNotice(stringResource(errorMessage(it.code)), true) }
                                    TextButton(onClick = { editor.edit { editor.tls = BitwardenTlsConfig() } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.remove)) }
                                }
                                ServerConfigurationPage.DISCOVERY -> {
                                    Text(BitwardenServerDiscovery.canonicalEmailDomain(email).orEmpty(), style = MaterialTheme.typography.titleMedium)
                                    if (editor.searching) {
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                        Text(stringResource(R.string.bitwarden_server_lookup_busy))
                                        TextButton(onClick = { editor.invalidate(); editor.lookupMessage = R.string.bitwarden_server_lookup_cancelled }, modifier = Modifier.fillMaxWidth()) {
                                            Text(stringResource(R.string.cancel))
                                        }
                                    }
                                    editor.lookupMessage?.let { ServerConfigurationNotice(stringResource(it)) }
                                    val result = editor.result
                                    if (result != null && result.candidates.isNotEmpty()) {
                                        Text(stringResource(R.string.bitwarden_server_review))
                                        if (result.lookups.any { it.status in setOf(BitwardenDiscoveryLookupStatus.ERROR, BitwardenDiscoveryLookupStatus.UNSUPPORTED, BitwardenDiscoveryLookupStatus.TIMED_OUT) })
                                            ServerConfigurationNotice(stringResource(R.string.bitwarden_server_lookup_partial))
                                        if (result.candidateLimitReached) ServerConfigurationNotice(stringResource(R.string.bitwarden_server_lookup_limit))
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            result.candidates.forEachIndexed { index, candidate ->
                                                Surface(shape = bitwardenGroupShape(index == 0, index == result.candidates.lastIndex), color = MaterialTheme.colorScheme.surfaceContainer) {
                                                    Row(Modifier.fillMaxWidth().clickable { editor.selected = candidate }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        RadioButton(editor.selected === candidate, onClick = null)
                                                        Spacer(Modifier.width(12.dp))
                                                        Text(candidate.url.toString(), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                                    }
                                                }
                                            }
                                        }
                                        editor.selected?.let { selected ->
                                            if (selected.requiresDestinationReview) ServerConfigurationNotice(stringResource(
                                                if (selected.domainRelation == BitwardenDiscoveryDomainRelation.DIFFERENT_REGISTRABLE_DOMAIN) R.string.bitwarden_server_review_cross else R.string.bitwarden_server_review_unknown))
                                            Text(selected.url.toString(), style = MaterialTheme.typography.bodyLarge)
                                        }
                                        FilledTonalButton(onClick = {
                                            val selected = editor.selected
                                            if (selected != null && !currentBaseLocked && current(editor.lookupRevision, editor.lookupEmail.orEmpty())) {
                                                editor.deployment(BitwardenEnvironmentPreset.CUSTOM, selected.url.toString())
                                                editor.page = ServerConfigurationPage.OVERVIEW
                                            }
                                        }, enabled = editor.selected != null && !baseLocked, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("bitwarden_server_use_candidate")) {
                                            Text(stringResource(R.string.bitwarden_server_use_candidate))
                                        }
                                    }
                                    if (!editor.searching) TextButton(onClick = ::find, enabled = !baseLocked, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bitwarden_server_find)) }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun ServerConfigurationDestinations(editor: ServerConfigurationEditor) {
    // Resolve endpoints only: never parse certificates or retain credentials in the preview.
    val preview = remember(editor.preset, editor.base, editor.web, editor.api, editor.identity, editor.notifications) {
        BitwardenEnvironmentResolver.resolve(
            BitwardenEnvironmentDraft(editor.preset, editor.base, editor.web, editor.api, editor.identity, editor.notifications),
            "00000000-0000-0000-0000-000000000000",
        ).value
    }
    if (preview == null) {
        ServerConfigurationNotice(stringResource(R.string.bitwarden_server_error_summary), error = true)
        return
    }
    val destinations = listOf(
        Triple(stringResource(R.string.bitwarden_server_base), preview.base, "base"),
        Triple("API", preview.api, "api"),
        Triple(stringResource(R.string.bitwarden_server_identity), preview.identity, "identity"),
        Triple(stringResource(R.string.bitwarden_server_notifications), preview.notifications, "notifications"),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        destinations.forEach { (label, url, tag) ->
            Column(Modifier.fillMaxWidth().testTag("bitwarden_server_scope_$tag")) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(url.toString(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ServerConfigurationField(value: String, onValueChange: (String) -> Unit, label: String, tag: String,
    errors: List<BitwardenEnvironmentError>, field: BitwardenEnvironmentField, index: Int? = null,
    secret: Boolean = false, multiline: Boolean = false, enabled: Boolean = true, first: Boolean = true, last: Boolean = true) {
    var visible by remember { mutableStateOf(false) }
    val error = errors.firstOrNull { it.field == field && it.index == index }
    MonicaTextField(value, onValueChange, modifier = Modifier.fillMaxWidth().testTag("bitwarden_server_$tag"), enabled = enabled,
        label = { Text(label) }, saveTextState = false, shape = bitwardenGroupShape(first, last),
        singleLine = false, minLines = if (multiline) 3 else 1, maxLines = if (multiline) 5 else 3,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri, autoCorrectEnabled = false),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) ({ IconButton(onClick = { visible = !visible }) {
            Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                stringResource(if (visible) R.string.hide_password else R.string.show_password))
        } }) else null,
        isError = error != null, supportingText = if (error != null) ({ Text(stringResource(errorMessage(error.code))) }) else null)
}

@Composable
private fun ServerConfigurationNotice(text: String, error: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp))
}

private enum class ServerConfigurationPage { OVERVIEW, ENDPOINTS, HEADERS, TLS, DISCOVERY }
private fun pageTitle(page: ServerConfigurationPage): Int = when (page) {
    ServerConfigurationPage.OVERVIEW -> R.string.bitwarden_server_title
    ServerConfigurationPage.ENDPOINTS -> R.string.bitwarden_server_endpoints
    ServerConfigurationPage.HEADERS -> R.string.bitwarden_server_headers
    ServerConfigurationPage.TLS -> R.string.bitwarden_server_certificates
    ServerConfigurationPage.DISCOVERY -> R.string.bitwarden_server_discovery_title
}
private fun presetLabel(preset: BitwardenEnvironmentPreset): Int = when (preset) {
    BitwardenEnvironmentPreset.US -> R.string.legacy_ui_bitwarden_server_us
    BitwardenEnvironmentPreset.EU -> R.string.legacy_ui_bitwarden_server_eu
    BitwardenEnvironmentPreset.CUSTOM -> R.string.legacy_ui_bitwarden_server_self_hosted
}
private fun discoveryMessage(status: BitwardenDiscoveryStatus): Int? = when (status) {
    BitwardenDiscoveryStatus.FOUND -> null
    BitwardenDiscoveryStatus.NO_RECORDS -> R.string.bitwarden_server_lookup_none
    BitwardenDiscoveryStatus.NO_VALID_CANDIDATES -> R.string.bitwarden_server_lookup_invalid
    BitwardenDiscoveryStatus.LOOKUP_FAILED -> R.string.bitwarden_server_lookup_failed
    BitwardenDiscoveryStatus.UNSUPPORTED -> R.string.bitwarden_server_lookup_unsupported
    BitwardenDiscoveryStatus.TIMED_OUT -> R.string.bitwarden_server_lookup_timeout
    BitwardenDiscoveryStatus.INVALID_DOMAIN -> R.string.bitwarden_server_lookup_email
}
private fun errorMessage(code: BitwardenEnvironmentErrorCode): Int = when (code) {
    BitwardenEnvironmentErrorCode.REQUIRED -> R.string.bitwarden_server_error_required
    BitwardenEnvironmentErrorCode.HTTPS_REQUIRED -> R.string.bitwarden_server_error_https
    BitwardenEnvironmentErrorCode.INVALID_HEADER_NAME -> R.string.bitwarden_server_error_header_name
    BitwardenEnvironmentErrorCode.INVALID_HEADER_VALUE -> R.string.bitwarden_server_error_header_value
    BitwardenEnvironmentErrorCode.RESERVED_HEADER -> R.string.bitwarden_server_error_header_reserved
    BitwardenEnvironmentErrorCode.DUPLICATE_HEADER -> R.string.bitwarden_server_error_header_duplicate
    BitwardenEnvironmentErrorCode.TOO_MANY_HEADERS, BitwardenEnvironmentErrorCode.VALUE_TOO_LARGE,
    BitwardenEnvironmentErrorCode.TOTAL_HEADERS_TOO_LARGE -> R.string.bitwarden_server_error_limit
    BitwardenEnvironmentErrorCode.INVALID_CA_CERTIFICATE -> R.string.bitwarden_server_error_ca
    BitwardenEnvironmentErrorCode.CLIENT_CERTIFICATE_REQUIRED, BitwardenEnvironmentErrorCode.INVALID_CLIENT_CERTIFICATE,
    BitwardenEnvironmentErrorCode.CLIENT_PRIVATE_KEY_REQUIRED -> R.string.bitwarden_server_error_p12
    BitwardenEnvironmentErrorCode.INVALID_REVISION -> R.string.bitwarden_server_error_generic
    else -> R.string.bitwarden_server_error_url
}

private class ServerConfigurationHeader(name: String, value: String) {
    val id = UUID.randomUUID().toString()
    var name by mutableStateOf(name)
    var value by mutableStateOf(value)
    fun erase() { name = ""; value = "" }
    override fun toString() = "ServerConfigurationHeader(redacted)"
}

private class ServerConfigurationEditor(initial: BitwardenEnvironmentDraft) {
    var active = true
    var revision = 0L
    var page by mutableStateOf(ServerConfigurationPage.OVERVIEW)
    var preset by mutableStateOf(initial.preset)
    var base by mutableStateOf(initial.baseUrl)
    var web by mutableStateOf(initial.webUrl.orEmpty())
    var api by mutableStateOf(initial.apiUrl.orEmpty())
    var identity by mutableStateOf(initial.identityUrl.orEmpty())
    var notifications by mutableStateOf(initial.notificationsUrl.orEmpty())
    var headers by mutableStateOf(initial.headers.map { ServerConfigurationHeader(it.name, it.value) })
    var tls by mutableStateOf(initial.tls.copy())
    var errors by mutableStateOf(emptyList<BitwardenEnvironmentError>())
    var genericError by mutableStateOf(false)
    var validating by mutableStateOf(false)
    var searching by mutableStateOf(false)
    var result by mutableStateOf<BitwardenDiscoveryResult?>(null)
    var selected by mutableStateOf<BitwardenDiscoveryCandidate?>(null)
    var lookupMessage by mutableStateOf<Int?>(null)
    var lookupEmail: String? = null
    var lookupRevision = -1L
    var validationJob: Job? = null
    var lookupJob: Job? = null
    fun invalidate() {
        revision++; validationJob?.cancel(); lookupJob?.cancel(); validationJob = null; lookupJob = null
        validating = false; searching = false; result = null; selected = null; lookupMessage = null; lookupEmail = null
    }
    fun edit(change: () -> Unit) { if (active) { invalidate(); errors = emptyList(); genericError = false; change() } }
    fun deployment(value: BitwardenEnvironmentPreset, url: String) { edit { preset = value; base = url; clearSettings() } }
    fun clearSettings() { web = ""; api = ""; identity = ""; notifications = ""; headers.forEach { it.erase() }; headers = emptyList(); tls = BitwardenTlsConfig() }
    fun baseForDisplay() = when (preset) { BitwardenEnvironmentPreset.US -> "https://vault.bitwarden.com/"; BitwardenEnvironmentPreset.EU -> "https://vault.bitwarden.eu/"; else -> base }
    fun snapshot() = BitwardenEnvironmentDraft(preset, base, web, api, identity, notifications,
        headers.map { BitwardenHeaderDraft(it.name, it.value) }, tls)
    fun erase() { active = false; invalidate(); base = ""; clearSettings(); errors = emptyList(); genericError = false }
    override fun toString() = "ServerConfigurationEditor(redacted)"
}
