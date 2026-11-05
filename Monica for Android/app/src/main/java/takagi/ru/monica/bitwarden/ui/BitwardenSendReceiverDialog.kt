package takagi.ru.monica.bitwarden.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.service.BitwardenSendReceiverException
import takagi.ru.monica.bitwarden.service.BitwardenSendReceiverFailure
import takagi.ru.monica.bitwarden.service.BitwardenSendReceiverLink
import takagi.ru.monica.bitwarden.service.BitwardenSendReceiverSession
import takagi.ru.monica.bitwarden.service.BitwardenSendReceiverState
import takagi.ru.monica.bitwarden.service.BitwardenSendReceiverTarget
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.components.ExpressiveTopBar
import takagi.ru.monica.ui.components.LocalFilledEntryForm
import takagi.ru.monica.ui.components.OutlinedTextField as MonicaTextField
import takagi.ru.monica.utils.ClipboardUtils
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Receiving is independent of Bitwarden accounts; the surrounding Monica session must be unlocked. */
@Composable
fun BitwardenSendReceiverDialog(onDismiss: () -> Unit) {
    BitwardenSendReceiverDialog(onDismiss) { link, target, current ->
        BitwardenSendReceiverSession(link, target, requireCurrent = current)
    }
}

/** Test seam supplies only a synthetic session factory, never a production account client. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BitwardenSendReceiverDialog(
    onDismiss: () -> Unit,
    sessionFactory: (BitwardenSendReceiverLink, BitwardenSendReceiverTarget, () -> Unit) -> BitwardenSendReceiverSession,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ui = remember { ReceiverDialogState(SessionManager.lockGeneration.value) }
    val scope = rememberCoroutineScope()
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentFactory by rememberUpdatedState(sessionFactory)
    val unlocked by SessionManager.isUnlocked.collectAsState()
    val lockGeneration by SessionManager.lockGeneration.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    fun dismiss() {
        if (ui.active.getAndSet(false)) { ui.erase(); currentDismiss() }
    }
    fun reset() { ui.erase(); ui.touch() }
    fun start(link: BitwardenSendReceiverLink, target: BitwardenSendReceiverTarget) {
        try {
            val previous = ui.session
            ui.lease = ""; ui.session = null
            previous?.close()
            val lease = UUID.randomUUID().toString()
            ui.lease = lease
            ui.touch()
            ui.requireCurrent(lease)
            val session = currentFactory(link, target) { ui.requireCurrent(lease) }
            ui.parsedLink = null
            ui.link = ""; ui.password = ""; ui.code = ""; ui.email = ""
            ui.error = null; ui.notice = null; ui.session = session
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { session.open() } catch (_: CancellationException) {
                    if (ui.lease == lease && ui.session === session) dismiss()
                }
            }
        } catch (error: Exception) {
            link.close()
            ui.error = receiverMessage((error as? BitwardenSendReceiverException)?.code ?: BitwardenSendReceiverFailure.CLOSED)
        }
    }
    fun parseLink() {
        ui.touch(); ui.error = null
        try {
            val link = BitwardenSendReceiverLink.parse(ui.link.trim())
            ui.link = ""
            if (link.requiresTargetReview) {
                ui.parsedLink?.close(); ui.parsedLink = link
                ui.api = link.suggestedApiBase.toString(); ui.identity = link.suggestedIdentityBase.toString()
            } else start(link, link.target())
        } catch (_: Exception) { ui.error = R.string.bitwarden_receiver_invalid_link }
    }
    fun reviewTarget() {
        val link = ui.parsedLink ?: return
        ui.touch(); ui.error = null
        try { start(link, link.target(ui.api, ui.identity, reviewed = true)) }
        catch (_: Exception) { ui.error = R.string.bitwarden_receiver_invalid_target }
    }
    fun invokeSession(action: suspend (BitwardenSendReceiverSession) -> Unit) {
        val session = ui.session ?: return
        val lease = ui.lease
        ui.touch(); ui.error = null; ui.notice = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { action(session) } catch (_: CancellationException) {
                if (ui.lease == lease && ui.session === session) dismiss()
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val ticket = ui.picker
        if (ticket == null) return@rememberLauncherForActivityResult
        if (uri == null) { reset(); return@rememberLauncherForActivityResult }
        // A result is accepted only for this one outstanding CreateDocument invocation. Nothing is
        // persisted, and an unsolicited/late URI can never become a cleanup or output destination.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (ui.active.get() && SystemClock.elapsedRealtime() < ticket.deadline &&
                !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) delay(50)
            if (!ui.validTicket(ticket) || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { dismiss(); return@launch }
            ui.foreground.set(true)
            try { ui.requireCurrent(ticket.lease) } catch (_: Exception) { dismiss(); return@launch }
            if (uri.scheme != "content" || !DocumentsContract.isDocumentUri(context, uri)) {
                ui.picker = null; ui.obscured = false; ui.error = R.string.bitwarden_receiver_export_failed
                return@launch
            }
            // CreateDocument grants a newly created output. Refuse any URI already persisted by
            // Monica rather than treating a known pre-existing document as ours to truncate/delete.
            if (context.contentResolver.persistedUriPermissions.any { it.uri == uri }) {
                ui.picker = null; ui.obscured = false; ui.error = R.string.bitwarden_receiver_export_failed
                return@launch
            }
            ticket.createdUri = uri
            ui.picker = null; ui.obscured = false; ui.saving = true; ui.downloaded = 0
            var success = false
            var removed = false
            try {
                val progressAt = AtomicLong(0)
                success = ticket.session.saveFile(File(context.cacheDir, "bitwarden-send-receiver"), {
                    ui.requireCurrent(ticket.lease)
                    check(ticket.createdUri == uri)
                    context.contentResolver.openOutputStream(uri, "w")
                        ?: throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.IO)
                }) { bytes, _ ->
                    val elapsed = SystemClock.elapsedRealtime()
                    val prior = progressAt.get()
                    if (elapsed - prior >= 150 && progressAt.compareAndSet(prior, elapsed)) scope.launch {
                        if (ui.active.get() && ui.session === ticket.session && !ui.obscured) {
                            ui.touch(); ui.downloaded = bytes
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Cleanup is restricted below even when the session was cancelled by lifecycle.
            } finally {
                if (!success && ticket.createdUri == uri && ui.canUseIo(ticket.lease)) {
                    removed = withContext(NonCancellable + Dispatchers.IO) {
                        if (!ui.canUseIo(ticket.lease)) false
                        else runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
                    }
                }
                ticket.createdUri = null
                if (ui.active.get() && ui.session === ticket.session) {
                    ui.saving = false
                    if (!success && !removed) ui.error = R.string.bitwarden_receiver_export_failed
                }
            }
        }
    }

    fun save() {
        val session = ui.session ?: return
        val ready = ui.model as? BitwardenSendReceiverState.FileReady ?: return
        if (ui.picker != null || ui.saving) return
        try { ui.requireCurrent(ui.lease) } catch (_: Exception) { dismiss(); return }
        ui.touch(); ui.password = ""; ui.code = ""; ui.email = ""; ui.revealed = false
        ui.picker = ReceiverPickerTicket(session, ui.lease, SystemClock.elapsedRealtime() + 60_000)
        ui.obscured = true
        try { saveLauncher.launch(receiverFileName(ready.fileName)) }
        catch (_: Exception) {
            ui.picker = null; ui.obscured = false; ui.error = R.string.bitwarden_receiver_export_failed
        }
    }

    val session = ui.session
    val sessionLease = ui.lease
    LaunchedEffect(session) {
        if (session != null) session.state.collect { value ->
            // Revocation is immediate even when a collector still sees an older Compose snapshot.
            if (ui.active.get() && sessionLease.isNotEmpty() && ui.lease == sessionLease && ui.session === session) {
                if (value === BitwardenSendReceiverState.Closed) dismiss()
                else if (SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == ui.generation) {
                    ui.model = value
                    if (value !is BitwardenSendReceiverState.TextReady) ui.revealed = false
                } else dismiss()
            }
        }
    }
    LaunchedEffect(unlocked, lockGeneration) {
        if (!unlocked || lockGeneration != ui.generation) dismiss()
    }
    LaunchedEffect(ui) {
        while (ui.active.get()) {
            delay(250)
            now = SystemClock.elapsedRealtime()
            if (now - ui.createdAt >= 15 * 60_000 || now - ui.lastAction.get() >= 5 * 60_000 ||
                ui.picker?.let { now >= it.deadline } == true) dismiss()
            val expiry = when (val value = ui.model) {
                is BitwardenSendReceiverState.TextReady -> value.expiresAtMillis
                is BitwardenSendReceiverState.FileReady -> value.expiresAtMillis
                else -> null
            }
            if (expiry != null && System.currentTimeMillis() >= expiry) dismiss()
        }
    }
    DisposableEffect(ui, lifecycle) {
        ui.foreground.set(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    ui.foreground.set(true)
                    if (ui.picker == null) ui.obscured = false
                }
                Lifecycle.Event.ON_PAUSE -> {
                    ui.foreground.set(false); ui.password = ""; ui.code = ""; ui.revealed = false; ui.obscured = true
                }
                Lifecycle.Event.ON_STOP -> {
                    val ticket = ui.picker
                    if (ticket == null || !ui.validTicket(ticket)) dismiss()
                }
                Lifecycle.Event.ON_DESTROY -> dismiss()
                else -> Unit
            }
        }
        val screenOff = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == Intent.ACTION_SCREEN_OFF) dismiss() }
        }
        lifecycle.addObserver(observer)
        ContextCompat.registerReceiver(context, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(screenOff) }
            ui.active.set(false); ui.erase()
        }
    }

    val dialogDensity = LocalDensity.current
    val dialogConfiguration = LocalConfiguration.current
    val dialogResources = LocalResources.current
    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        CompositionLocalProvider(LocalDensity provides dialogDensity,
            LocalConfiguration provides dialogConfiguration, LocalResources provides dialogResources) {
        val view = LocalView.current
        val lightSurface = MaterialTheme.colorScheme.surface.luminance() > .5f
        val concealed = ui.obscured || !unlocked || lockGeneration != ui.generation || !ui.active.get()
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightSurface; isAppearanceLightNavigationBars = lightSurface
                }
            }
        }
        Surface(Modifier.fillMaxSize().testTag("bitwarden_receiver_dialog").pointerInput(ui) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.pressed }) ui.touch()
                }
            }
        }, color = MaterialTheme.colorScheme.surface) {
            Scaffold(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
                topBar = {
                    ExpressiveTopBar(title = stringResource(R.string.bitwarden_receiver_title), searchQuery = "",
                        onSearchQueryChange = {}, isSearchExpanded = false, onSearchExpandedChange = {}, collapsedTitleEndPadding = 64.dp,
                        actions = { IconButton(onClick = ::dismiss, modifier = Modifier.testTag("bitwarden_receiver_close")) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.close))
                        } })
                },
                bottomBar = {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!concealed) when {
                            ui.parsedLink != null -> {
                                ReceiverButton(R.string.bitwarden_receiver_use_target, "bitwarden_receiver_confirm_target", ::reviewTarget)
                                ReceiverOtherLink(::reset)
                            }
                            else -> when (val value = ui.model) {
                                BitwardenSendReceiverState.Idle -> ReceiverButton(R.string.bitwarden_receiver_open, "bitwarden_receiver_open", ::parseLink, ui.link.isNotBlank())
                                is BitwardenSendReceiverState.PasswordRequired -> ReceiverButton(R.string.bitwarden_receiver_verify, "bitwarden_receiver_verify", {
                                    val password = ui.password.toCharArray(); ui.password = ""
                                    invokeSession { try { it.submitPassword(password) } finally { password.fill('\u0000') } }
                                }, ui.password.isNotEmpty())
                                BitwardenSendReceiverState.EmailRequired -> ReceiverButton(R.string.bitwarden_receiver_send_code, "bitwarden_receiver_send_code", {
                                    val email = ui.email; invokeSession { it.requestEmailCode(email) }
                                }, ui.email.isNotBlank())
                                is BitwardenSendReceiverState.EmailCodeRequired -> {
                                    ReceiverButton(R.string.bitwarden_receiver_verify, "bitwarden_receiver_verify", {
                                        val code = ui.code.toCharArray(); ui.code = ""
                                        invokeSession { try { it.submitEmailCode(code) } finally { code.fill('\u0000') } }
                                    }, ui.code.isNotBlank())
                                    FilledTonalButton(onClick = { val email = ui.email; invokeSession { it.requestEmailCode(email) } },
                                        enabled = now >= value.resendAvailableAtMillis,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("bitwarden_receiver_resend")) {
                                        Text(if (now >= value.resendAvailableAtMillis) stringResource(R.string.bitwarden_receiver_resend)
                                            else stringResource(R.string.bitwarden_receiver_resend_wait, ((value.resendAvailableAtMillis - now + 999) / 1000).toInt()))
                                    }
                                }
                                is BitwardenSendReceiverState.TextReady -> {
                                    ReceiverButton(R.string.bitwarden_receiver_copy, "bitwarden_receiver_copy", {
                                        ui.touch()
                                        SessionManager.publishAtGeneration(ui.generation) {
                                            if (ui.canUseIo(ui.lease)) {
                                                ClipboardUtils.copyToClipboard(context, value.text, context.getString(R.string.bitwarden_receiver_text),
                                                    autoClearSeconds = 60, sensitive = true)
                                                ui.notice = R.string.bitwarden_receiver_copied
                                            }
                                        }
                                    })
                                    if (value.hidden) FilledTonalButton(onClick = { ui.touch(); ui.revealed = !ui.revealed },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("bitwarden_receiver_reveal")) {
                                        Text(stringResource(if (ui.revealed) R.string.bitwarden_receiver_hide else R.string.bitwarden_receiver_reveal))
                                    }
                                    ReceiverOtherLink(::reset)
                                }
                                is BitwardenSendReceiverState.FileReady -> { ReceiverButton(R.string.bitwarden_receiver_save, "bitwarden_receiver_file_save", ::save); ReceiverOtherLink(::reset) }
                                is BitwardenSendReceiverState.Failure -> {
                                    ReceiverButton(R.string.bitwarden_receiver_retry, "bitwarden_receiver_retry", { invokeSession { it.open() } })
                                    ReceiverOtherLink(::reset)
                                }
                                BitwardenSendReceiverState.Saved -> ReceiverButton(R.string.bitwarden_receiver_new_link, "bitwarden_receiver_new_link", ::reset)
                                else -> Unit
                            }
                        }
                    }
                },
            ) { padding ->
                CompositionLocalProvider(LocalFilledEntryForm provides true) {
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (concealed) ReceiverInfo(stringResource(if (ui.picker != null) R.string.bitwarden_receiver_picker else R.string.bitwarden_receiver_closed), tag = "bitwarden_receiver_picker_wait")
                        else if (ui.parsedLink != null) {
                            ReceiverInfo(stringResource(R.string.bitwarden_receiver_target_title), stringResource(R.string.bitwarden_receiver_target_hint), last = false)
                            ReceiverInfo(stringResource(R.string.bitwarden_receiver_web), ui.parsedLink!!.webBase.toString(), first = false, last = false)
                            ReceiverField(ui.api, { ui.api = it; ui.touch() }, R.string.bitwarden_receiver_api, "bitwarden_receiver_api", KeyboardType.Uri, first = false, last = false)
                            ReceiverField(ui.identity, { ui.identity = it; ui.touch() }, R.string.bitwarden_receiver_identity, "bitwarden_receiver_identity", KeyboardType.Uri, first = false)
                        } else when (val value = ui.model) {
                            BitwardenSendReceiverState.Idle -> {
                                ReceiverInfo(stringResource(R.string.bitwarden_receiver_intro), last = false)
                                ReceiverField(ui.link, { if (it.length <= 8192) { ui.link = it; ui.touch(); ui.error = null } },
                                    R.string.bitwarden_receiver_link, "bitwarden_receiver_link", KeyboardType.Uri, first = false,
                                    supporting = R.string.bitwarden_receiver_link_hint, singleLine = false)
                            }
                            is BitwardenSendReceiverState.PasswordRequired -> {
                                ReceiverInfo(stringResource(R.string.bitwarden_receiver_password_title), stringResource(R.string.bitwarden_receiver_password_hint), last = false)
                                ReceiverField(ui.password, { if (it.length <= 4096) { ui.password = it; ui.touch() } },
                                    R.string.bitwarden_receiver_password, "bitwarden_receiver_password", KeyboardType.Password, first = false, secret = true)
                                if (value.rejected) ReceiverError(R.string.bitwarden_receiver_password_wrong)
                            }
                            BitwardenSendReceiverState.EmailRequired -> {
                                ReceiverInfo(stringResource(R.string.bitwarden_receiver_email_title), stringResource(R.string.bitwarden_receiver_email_hint), last = false)
                                ReceiverField(ui.email, { if (it.length <= 320) { ui.email = it; ui.touch() } },
                                    R.string.bitwarden_receiver_email, "bitwarden_receiver_email", KeyboardType.Email, first = false)
                            }
                            is BitwardenSendReceiverState.EmailCodeRequired -> {
                                ReceiverInfo(stringResource(R.string.bitwarden_receiver_email_title), stringResource(R.string.bitwarden_receiver_email_sent), last = false)
                                ReceiverField(ui.code, { if (it.length <= 128) { ui.code = it; ui.touch() } },
                                    R.string.bitwarden_receiver_code, "bitwarden_receiver_code", KeyboardType.Password, first = false, secret = true)
                                if (value.rejected) ReceiverError(R.string.bitwarden_receiver_code_wrong)
                            }
                            is BitwardenSendReceiverState.TextReady -> {
                                ReceiverInfo(value.title ?: stringResource(R.string.bitwarden_receiver_text), last = false)
                                ReceiverInfo(if (value.hidden && !ui.revealed) stringResource(R.string.bitwarden_receiver_hidden) else value.text,
                                    first = false, tag = "bitwarden_receiver_content")
                                value.creatorIdentifier?.let { ReceiverInfo(stringResource(R.string.bitwarden_receiver_creator, it)) }
                            }
                            is BitwardenSendReceiverState.FileReady -> {
                                ReceiverInfo(value.title ?: stringResource(R.string.bitwarden_receiver_file), last = false)
                                ReceiverInfo(receiverFileName(value.fileName), value.encryptedSize?.let {
                                    stringResource(R.string.bitwarden_receiver_transfer_size, Formatter.formatFileSize(context, it))
                                }, first = false, tag = "bitwarden_receiver_file")
                                value.creatorIdentifier?.let { ReceiverInfo(stringResource(R.string.bitwarden_receiver_creator, it)) }
                            }
                            BitwardenSendReceiverState.Working -> {
                                ReceiverInfo(stringResource(if (ui.saving) R.string.bitwarden_receiver_downloading else R.string.bitwarden_receiver_working), tag = "bitwarden_receiver_working")
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
                                if (ui.downloaded > 0) Text(stringResource(R.string.bitwarden_receiver_progress, Formatter.formatFileSize(context, ui.downloaded)), Modifier.padding(16.dp))
                            }
                            is BitwardenSendReceiverState.Failure -> {
                                ReceiverError(receiverMessage(value.code))
                                if (value.accessMayHaveBeenCounted) ReceiverInfo(stringResource(R.string.bitwarden_receiver_count_warning))
                            }
                            BitwardenSendReceiverState.Saved -> ReceiverInfo(stringResource(R.string.bitwarden_receiver_saved), tag = "bitwarden_receiver_saved")
                            BitwardenSendReceiverState.Closed -> Unit
                        }
                        if (!concealed) {
                            ui.error?.let { ReceiverError(it) }
                            ui.notice?.let { ReceiverInfo(stringResource(it)) }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        }
    }
}

@Composable private fun ReceiverButton(label: Int, tag: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)) {
        Text(stringResource(label))
    }
}

@Composable private fun ReceiverOtherLink(onClick: () -> Unit) {
    TextButton(onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("bitwarden_receiver_new_link")) {
        Text(stringResource(R.string.bitwarden_receiver_new_link))
    }
}

@Composable private fun ReceiverInfo(title: String, body: String? = null, first: Boolean = true, last: Boolean = true, tag: String = "") {
    Surface(Modifier.fillMaxWidth().testTag(tag), shape = bitwardenGroupShape(first, last), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun ReceiverError(message: Int) {
    Text(stringResource(message), Modifier.fillMaxWidth().padding(16.dp).testTag("bitwarden_receiver_error"),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable private fun ReceiverField(
    value: String, change: (String) -> Unit, label: Int, tag: String, keyboard: KeyboardType,
    first: Boolean = true, last: Boolean = true, supporting: Int? = null, secret: Boolean = false, singleLine: Boolean = true,
) {
    val supportingContent: (@Composable () -> Unit)? = if (supporting != null) {
        { Text(stringResource(supporting)) }
    } else null
    MonicaTextField(value = value, onValueChange = change, label = { Text(stringResource(label)) },
        modifier = Modifier.fillMaxWidth().testTag(tag), keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = singleLine, minLines = if (singleLine) 1 else 3, maxLines = if (singleLine) 1 else 6,
        supportingText = supportingContent, shape = bitwardenGroupShape(first, last),
        saveTextState = false)
}

private fun receiverMessage(code: BitwardenSendReceiverFailure): Int = when (code) {
    BitwardenSendReceiverFailure.INVALID_LINK -> R.string.bitwarden_receiver_invalid_link
    BitwardenSendReceiverFailure.INVALID_TARGET, BitwardenSendReceiverFailure.TARGET_REVIEW_REQUIRED -> R.string.bitwarden_receiver_invalid_target
    BitwardenSendReceiverFailure.INVALID_INPUT -> R.string.bitwarden_receiver_invalid_input
    BitwardenSendReceiverFailure.UNSUPPORTED -> R.string.bitwarden_receiver_unsupported
    BitwardenSendReceiverFailure.UNAVAILABLE -> R.string.bitwarden_receiver_unavailable
    BitwardenSendReceiverFailure.WRONG_KEY_OR_DAMAGED, BitwardenSendReceiverFailure.INVALID_RESPONSE -> R.string.bitwarden_receiver_damaged
    BitwardenSendReceiverFailure.RATE_LIMITED -> R.string.bitwarden_receiver_rate_limited
    BitwardenSendReceiverFailure.TOKEN_EXPIRED -> R.string.bitwarden_receiver_token_expired
    BitwardenSendReceiverFailure.TOO_LARGE -> R.string.bitwarden_receiver_too_large
    BitwardenSendReceiverFailure.CLOSED -> R.string.bitwarden_receiver_closed
    else -> R.string.bitwarden_receiver_failed
}

private fun receiverFileName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
    .filter { it.code >= 0x20 && it.code != 0x7f && it !in "\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069" }
    .take(200).trim().takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "Send"

private class ReceiverPickerTicket(val session: BitwardenSendReceiverSession, val lease: String, val deadline: Long) {
    var createdUri: Uri? = null
    override fun toString(): String = "ReceiverPickerTicket(redacted)"
}

private class ReceiverDialogState(val generation: Long) {
    val active = AtomicBoolean(true)
    val foreground = AtomicBoolean(false)
    val createdAt = SystemClock.elapsedRealtime()
    val lastAction = AtomicLong(createdAt)
    @Volatile var lease = ""
    var session by mutableStateOf<BitwardenSendReceiverSession?>(null)
    var parsedLink by mutableStateOf<BitwardenSendReceiverLink?>(null)
    var model by mutableStateOf<BitwardenSendReceiverState>(BitwardenSendReceiverState.Idle)
    var link by mutableStateOf("")
    var api by mutableStateOf("")
    var identity by mutableStateOf("")
    var password by mutableStateOf("")
    var email by mutableStateOf("")
    var code by mutableStateOf("")
    var picker by mutableStateOf<ReceiverPickerTicket?>(null)
    var obscured by mutableStateOf(false)
    var revealed by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var downloaded by mutableLongStateOf(0L)
    var error by mutableStateOf<Int?>(null)
    var notice by mutableStateOf<Int?>(null)

    fun touch() { lastAction.set(SystemClock.elapsedRealtime()) }
    fun canUseIo(expectedLease: String): Boolean = active.get() && foreground.get() && lease == expectedLease &&
        SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == generation &&
        SystemClock.elapsedRealtime() - createdAt < 15 * 60_000 && SystemClock.elapsedRealtime() - lastAction.get() < 5 * 60_000
    fun requireCurrent(expectedLease: String) {
        if (!canUseIo(expectedLease)) throw CancellationException("Send receiver UI lease revoked")
    }
    fun validTicket(ticket: ReceiverPickerTicket): Boolean = active.get() && picker === ticket &&
        session === ticket.session && lease == ticket.lease && SystemClock.elapsedRealtime() < ticket.deadline &&
        SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == generation
    fun erase() {
        lease = ""
        val previous = session; session = null; previous?.close()
        parsedLink?.close(); parsedLink = null
        model = BitwardenSendReceiverState.Idle
        link = ""; api = ""; identity = ""; password = ""; code = ""; email = ""
        picker = null; obscured = false; revealed = false; saving = false; downloaded = 0
        error = null; notice = null
    }
    override fun toString(): String = "ReceiverDialogState(redacted)"
}
