package takagi.ru.monica.bitwarden.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.service.BitwardenAccountManagementInfo
import takagi.ru.monica.bitwarden.service.BitwardenAccountManagementResult
import takagi.ru.monica.bitwarden.service.BitwardenAccountManagementState
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationAction
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationHint
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationKdf
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationKdfLimits
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.components.LocalFilledEntryForm
import takagi.ru.monica.ui.components.OutlinedTextField

/** The repository owns dispatch, preservation and recovery; this dialog owns transient input only. */
@Composable
internal fun BitwardenAccountManagementDialog(
    vaultId: Long,
    onDismiss: () -> Unit,
    onReauthenticate: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) { BitwardenRepository.getInstance(context) }
    val generationFlow = remember(repository, vaultId) { repository.getVaultSessionGenerationFlow(vaultId) }
    val vaultGeneration by generationFlow.collectAsState()
    val appGeneration by SessionManager.lockGeneration.collectAsState()
    val appUnlocked by SessionManager.isUnlocked.collectAsState()
    val openingAppGeneration = remember(vaultId) { SessionManager.lockGeneration.value }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val dismissCallback by rememberUpdatedState(onDismiss)
    val reauthenticateCallback by rememberUpdatedState(onReauthenticate)
    var active by remember(vaultId) { mutableStateOf(true) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var reload by remember(vaultId) { mutableIntStateOf(0) }
    var operationEpoch by remember(vaultId) { mutableLongStateOf(0) }
    var operationJob by remember(vaultId) { mutableStateOf<Job?>(null) }
    var busy by remember(vaultId) { mutableStateOf(false) }
    var loading by remember(vaultId) { mutableStateOf(true) }
    var info by remember(vaultId) { mutableStateOf<BitwardenAccountManagementInfo?>(null) }
    var loadedVaultGeneration by remember(vaultId) { mutableLongStateOf(-1) }
    var loadedAppGeneration by remember(vaultId) { mutableLongStateOf(-1) }
    var error by remember(vaultId) { mutableStateOf<String?>(null) }
    var notice by remember(vaultId) { mutableStateOf<Int?>(null) }
    val defaultError = stringResource(R.string.bitwarden_management_operation_failed)

    fun cancelOwnedOperation() {
        operationEpoch++
        operationJob?.cancel(); operationJob = null; busy = false
    }
    fun dismiss() {
        if (!active) return
        active = false
        cancelOwnedOperation()
        info = null; error = null; notice = null
        dismissCallback()
    }
    fun current(epoch: Long, generation: Long) = active && operationEpoch == epoch &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && SessionManager.isUnlocked.value &&
        SessionManager.lockGeneration.value == generation

    val pause by rememberUpdatedState<() -> Unit>({
        resumed = false; reload++; cancelOwnedOperation(); info = null; error = null; notice = null
    })
    DisposableEffect(lifecycle, vaultId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); active = false; cancelOwnedOperation(); info = null }
    }
    LaunchedEffect(appGeneration, appUnlocked) {
        if (!appUnlocked || appGeneration != openingAppGeneration) dismiss()
    }
    LaunchedEffect(vaultId, vaultGeneration, appGeneration, reload, resumed, appUnlocked) {
        info = null; error = null
        if (!active || !resumed || !appUnlocked) { loading = false; return@LaunchedEffect }
        val expectedVaultGeneration = vaultGeneration
        val expectedAppGeneration = appGeneration
        loading = true
        try {
            val result = repository.loadAccountManagement(vaultId)
            if (active && resumed && generationFlow.value == expectedVaultGeneration &&
                SessionManager.lockGeneration.value == expectedAppGeneration && SessionManager.isUnlocked.value) {
                result.onSuccess { info = it; loadedVaultGeneration = expectedVaultGeneration; loadedAppGeneration = expectedAppGeneration }
                    .onFailure { error = defaultError }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (active && resumed) error = defaultError }
        finally { if (generationFlow.value == expectedVaultGeneration && SessionManager.lockGeneration.value == expectedAppGeneration) loading = false }
    }

    // Each rendered callback retains the generations of the identity the user reviewed.
    // Do not read a newly loaded generation from an older button's callback.
    val displayedVaultGeneration = loadedVaultGeneration
    val displayedAppGeneration = loadedAppGeneration
    val visibleInfo = info.takeIf { resumed && appUnlocked && displayedVaultGeneration == vaultGeneration && displayedAppGeneration == appGeneration }
    fun begin(action: BitwardenAccountMutationAction?, password: String) {
        val confirmedVaultGeneration = displayedVaultGeneration
        val confirmedAppGeneration = displayedAppGeneration
        if (busy || visibleInfo == null || !active || !resumed || !SessionManager.isUnlocked.value ||
            SessionManager.lockGeneration.value != confirmedAppGeneration || generationFlow.value != confirmedVaultGeneration) { reload++; return }
        if (action != null && visibleInfo.state != BitwardenAccountManagementState.READY) { reload++; return }
        val epoch = ++operationEpoch
        val generation = confirmedAppGeneration
        busy = true; error = null; notice = null
        operationJob = scope.launch {
            try {
                if (!current(epoch, generation) || generationFlow.value != confirmedVaultGeneration) { reload++; return@launch }
                if (action == null) {
                    val result = repository.unlock(vaultId, password)
                    if (current(epoch, generation)) {
                        if (result != BitwardenRepository.UnlockResult.Success) error = defaultError
                        reload++
                    }
                } else {
                    val result = repository.mutateAccount(vaultId, password, action,
                        expectedVaultGeneration = confirmedVaultGeneration,
                        expectedAppGeneration = confirmedAppGeneration)
                    // A completed mutation may retire the old vault generation. Only fresh loads
                    // below display recovery state; this callback never restores a session/key.
                    if (current(epoch, generation)) {
                        result.onSuccess { notice = when (it) {
                            BitwardenAccountManagementResult.HINT_SAVED -> R.string.bitwarden_management_hint_saved
                            BitwardenAccountManagementResult.REAUTHENTICATE -> R.string.bitwarden_management_reauthenticate_description
                            BitwardenAccountManagementResult.DELETED_LOCAL_COPY -> R.string.bitwarden_management_deleted_description
                            BitwardenAccountManagementResult.UNCERTAIN -> R.string.bitwarden_management_uncertain
                            BitwardenAccountManagementResult.NOT_SENT -> R.string.bitwarden_management_not_sent
                        } }.onFailure { error = defaultError }
                        reload++
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(epoch, generation)) { error = defaultError; reload++ } }
            finally { if (operationEpoch == epoch) { busy = false; operationJob = null } }
        }
    }

    if (active && appUnlocked) BitwardenAccountManagementDialogContent(
        info = visibleInfo, loading = loading, busy = busy, error = error,
        notice = notice?.let { stringResource(it) },
        resetKey = listOf(vaultId, vaultGeneration, appGeneration, reload),
        localCopyUnlocked = visibleInfo != null && repository.isVaultUnlocked(vaultId),
        onMutate = { password, action -> begin(action, password) }, onUnlockLocal = { begin(null, it) },
        onReload = { reload++ }, onDismiss = ::dismiss,
        onReauthenticate = {
            if (!busy && visibleInfo?.state == BitwardenAccountManagementState.REAUTHENTICATE &&
                SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == displayedAppGeneration &&
                generationFlow.value == displayedVaultGeneration) {
                dismiss(); reauthenticateCallback()
            }
        },
    )
}

/** Injectable native content for synthetic UI tests; no repository or real-account calls. */
@Composable
internal fun BitwardenAccountManagementDialogContent(
    info: BitwardenAccountManagementInfo?,
    loading: Boolean,
    busy: Boolean,
    error: String?,
    notice: String?,
    resetKey: Any,
    localCopyUnlocked: Boolean = false,
    onMutate: (String, BitwardenAccountMutationAction) -> Unit,
    onUnlockLocal: (String) -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    onReauthenticate: () -> Unit,
) {
    val draft = remember(resetKey) { AccountManagementDraft() }
    val latestDraft by rememberUpdatedState(draft)
    val draftAppGeneration = remember(draft) { SessionManager.lockGeneration.value }
    val appUnlocked by SessionManager.isUnlocked.collectAsState()
    val appGeneration by SessionManager.lockGeneration.collectAsState()
    val activeSession = appUnlocked && appGeneration == draftAppGeneration
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    fun inputIsCurrent() = latestDraft === draft && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == draftAppGeneration
    LaunchedEffect(activeSession, draft) { if (!activeSession) draft.clear() }
    DisposableEffect(lifecycle, draft) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> { resumed = false; draft.clear() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); draft.clear() }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    // A new review starts at its identity and warning, rather than inheriting a long form's offset.
    val contentScroll = key(draft, draft.screen, draft.reviewing) { rememberScrollState() }
    val working = busy || draft.submitted || !activeSession
    val title = when {
        draft.reviewing -> if (draft.screen == ManagementPage.DELETE) R.string.bitwarden_management_confirm_delete else R.string.bitwarden_management_confirm_title
        draft.screen == ManagementPage.HINT -> R.string.bitwarden_management_hint
        draft.screen == ManagementPage.PASSWORD -> R.string.bitwarden_management_password
        draft.screen == ManagementPage.KDF -> R.string.bitwarden_management_kdf
        draft.screen == ManagementPage.DELETE -> R.string.bitwarden_management_delete
        else -> R.string.bitwarden_management_title
    }
    fun dismiss() { draft.clear(); onDismiss() }
    val dialogDensity = LocalDensity.current
    val dialogConfiguration = LocalConfiguration.current
    val dialogResources = LocalResources.current
    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        CompositionLocalProvider(LocalDensity provides dialogDensity,
            LocalConfiguration provides dialogConfiguration, LocalResources provides dialogResources) {
        val view = LocalView.current
        val focusManager = LocalFocusManager.current
        val lightSurface = MaterialTheme.colorScheme.surface.luminance() > .5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightSurface; isAppearanceLightNavigationBars = lightSurface
                }
            }
        }
        Surface(modifier = Modifier.fillMaxSize().testTag("bitwarden_management_dialog"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).testTag("bitwarden_management_title"))
                    IconButton(onClick = ::dismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.close)) }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(contentScroll)
                    .padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (loading) { CircularProgressIndicator(Modifier.size(24.dp)); Text(stringResource(R.string.bitwarden_management_loading)) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("bitwarden_management_error")) }
                    notice?.let { Text(it, modifier = Modifier.testTag("bitwarden_management_notice")) }
                    if (busy) Text(stringResource(R.string.bitwarden_management_working))
                    if (info != null && resumed && activeSession) {
                        when {
                            info.state != BitwardenAccountManagementState.READY -> {
                                ManagementIdentity(info)
                                Text(stringResource(when (info.state) {
                                    BitwardenAccountManagementState.REAUTHENTICATE -> R.string.bitwarden_management_reauthenticate_description
                                    BitwardenAccountManagementState.DELETED_LOCAL_COPY -> R.string.bitwarden_management_deleted_description
                                    else -> R.string.bitwarden_management_unreadable_description
                                }))
                                if (info.state != BitwardenAccountManagementState.UNREADABLE) {
                                    Text(stringResource(R.string.bitwarden_management_local_preserved))
                                    if (localCopyUnlocked) Text(stringResource(R.string.bitwarden_management_local_unlocked), modifier = Modifier.testTag("bitwarden_management_local_unlocked"))
                                    else {
                                        Text(stringResource(R.string.bitwarden_management_local_unlock_description))
                                        ManagementPasswordField(draft.currentPassword, { draft.currentPassword = it }, working,
                                            R.string.bitwarden_management_current_password, "bitwarden_management_local_password")
                                    }
                                }
                            }
                            draft.screen == ManagementPage.HOME -> ManagementMenu(info, working) { draft.select(it, info) }
                            draft.reviewing -> ManagementReview(info, draft)
                            else -> {
                                ManagementIdentity(info)
                                CompositionLocalProvider(LocalFilledEntryForm provides true) { ManagementForm(info, draft, working) }
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (info == null) {
                        if (!loading && resumed) Button(onClick = { if (inputIsCurrent()) onReload() }, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.retry)) }
                    } else if (resumed && activeSession && info.state == BitwardenAccountManagementState.READY && draft.screen != ManagementPage.HOME) {
                        val deleting = draft.screen == ManagementPage.DELETE && draft.reviewing
                        Button(onClick = {
                            if (inputIsCurrent() && !working && draft.ready(info)) {
                                focusManager.clearFocus(force = true)
                                keyboard?.hide()
                                if (!draft.reviewing) draft.reviewing = true
                                else {
                                    val action = draft.action(info) ?: return@Button
                                    val password = draft.currentPassword
                                    draft.clear(); draft.submitted = true
                                    onMutate(password, action)
                                }
                            }
                        }, enabled = !working && draft.ready(info), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .testTag(if (draft.reviewing) "bitwarden_management_submit" else "bitwarden_management_review"),
                            colors = if (deleting) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors()) {
                            Text(stringResource(if (!draft.reviewing) R.string.bitwarden_management_continue else when (draft.screen) {
                                ManagementPage.HINT -> R.string.bitwarden_management_confirm_hint
                                ManagementPage.PASSWORD -> R.string.bitwarden_management_confirm_password
                                ManagementPage.KDF -> R.string.bitwarden_management_confirm_kdf
                                else -> R.string.bitwarden_management_confirm_delete
                            }))
                        }
                        TextButton(onClick = { draft.clear() }, modifier = Modifier.fillMaxWidth().testTag("bitwarden_management_back")) { Text(stringResource(R.string.cancel)) }
                    } else if (resumed && activeSession && info.state != BitwardenAccountManagementState.READY) {
                        if (info.state == BitwardenAccountManagementState.REAUTHENTICATE) Button(onClick = { if (inputIsCurrent() && !working) { draft.clear(); onReauthenticate() } }, enabled = !working,
                            modifier = Modifier.fillMaxWidth().testTag("bitwarden_management_reauthenticate")) { Text(stringResource(R.string.bitwarden_management_reauthenticate)) }
                        if (info.state != BitwardenAccountManagementState.UNREADABLE && !localCopyUnlocked) FilledTonalButton(onClick = {
                            if (inputIsCurrent() && !working && draft.currentPassword.isNotEmpty()) {
                                val password = draft.currentPassword
                                draft.clear(); draft.submitted = true; keyboard?.hide(); onUnlockLocal(password)
                            }
                        }, enabled = !working && draft.currentPassword.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("bitwarden_management_unlock_local")) { Text(stringResource(R.string.bitwarden_management_unlock_local)) }
                    }
                    if (info == null || info.state != BitwardenAccountManagementState.READY || draft.screen == ManagementPage.HOME) {
                        TextButton(onClick = ::dismiss, modifier = Modifier.fillMaxWidth().testTag("bitwarden_management_close")) { Text(stringResource(R.string.close)) }
                    }
                }
            }
        }
        }
    }
}

@Composable private fun ManagementIdentity(info: BitwardenAccountManagementInfo, grouped: Boolean = false) {
    Surface(shape = managementShape(first = true, last = !grouped), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().testTag("bitwarden_management_identity").padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AccountCircle, null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(info.email, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().testTag("bitwarden_management_identity_email"))
                Text(info.server, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().testTag("bitwarden_management_identity_server"))
            }
        }
    }
}

@Composable private fun ManagementMenu(info: BitwardenAccountManagementInfo, busy: Boolean, choose: (ManagementPage) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ManagementIdentity(info, grouped = true)
        ManagementMenuRow(R.string.bitwarden_management_hint, R.string.bitwarden_management_hint_summary, Icons.Outlined.Lightbulb,
            "bitwarden_management_open_hint", busy) { choose(ManagementPage.HINT) }
        ManagementMenuRow(R.string.bitwarden_management_password, R.string.bitwarden_management_password_summary, Icons.Outlined.Password,
            "bitwarden_management_open_password", busy) { choose(ManagementPage.PASSWORD) }
        ManagementMenuRow(R.string.bitwarden_management_kdf, R.string.bitwarden_management_kdf_summary, Icons.Outlined.Tune,
            "bitwarden_management_open_kdf", busy) { choose(ManagementPage.KDF) }
        ManagementMenuRow(R.string.bitwarden_management_delete, R.string.bitwarden_management_delete_summary, Icons.Outlined.PersonRemove,
            "bitwarden_management_open_delete", busy, last = true) { choose(ManagementPage.DELETE) }
    }
}

@Composable private fun ManagementMenuRow(title: Int, summary: Int, icon: ImageVector, tag: String, busy: Boolean, last: Boolean = false, click: () -> Unit) {
    Surface(shape = managementShape(last = last), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = click).testTag(tag)
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().testTag("${tag}_title"))
                Text(stringResource(summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().testTag("${tag}_summary"))
            }
            Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.size(24.dp))
        }
    }
}

private fun managementShape(first: Boolean = false, last: Boolean = false) = RoundedCornerShape(
    topStart = if (first) 24.dp else 4.dp, topEnd = if (first) 24.dp else 4.dp,
    bottomStart = if (last) 24.dp else 4.dp, bottomEnd = if (last) 24.dp else 4.dp,
)

@Composable private fun ManagementPasswordField(value: String, change: (String) -> Unit, busy: Boolean, label: Int, tag: String) {
    OutlinedTextField(value, change, enabled = !busy, saveTextState = false, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable private fun ManagementChoice(label: Int, selected: Boolean, enabled: Boolean, tag: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = click)
        .testTag(tag).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(stringResource(label), modifier = Modifier.weight(1f).padding(start = 8.dp))
    }
}

@Composable private fun ManagementHintChoices(info: BitwardenAccountManagementInfo, draft: AccountManagementDraft, busy: Boolean, preserve: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (preserve) ManagementChoice(R.string.bitwarden_management_hint_preserve, draft.hintChoice == HintChoice.PRESERVE, !busy && info.hintPresent,
            "bitwarden_management_hint_preserve") { draft.hintChoice = HintChoice.PRESERVE }
        ManagementChoice(R.string.bitwarden_management_hint_replace, draft.hintChoice == HintChoice.REPLACE, !busy,
            "bitwarden_management_hint_replace") { draft.hintChoice = HintChoice.REPLACE }
        ManagementChoice(R.string.bitwarden_management_hint_clear, draft.hintChoice == HintChoice.CLEAR, !busy,
            "bitwarden_management_hint_clear") { draft.hintChoice = HintChoice.CLEAR; draft.hint = "" }
    }
    if (draft.hintChoice == HintChoice.REPLACE) OutlinedTextField(draft.hint, { draft.hint = it }, enabled = !busy, saveTextState = false,
        label = { Text(stringResource(R.string.bitwarden_management_hint_value)) }, modifier = Modifier.fillMaxWidth().testTag("bitwarden_management_hint_value"))
}

@Composable private fun ManagementForm(info: BitwardenAccountManagementInfo, draft: AccountManagementDraft, busy: Boolean) {
    when (draft.screen) {
        ManagementPage.HINT -> {
            Text(stringResource(if (!info.hintPresent) R.string.bitwarden_management_hint_unavailable else if (info.hint == null) R.string.bitwarden_management_hint_empty else R.string.bitwarden_management_hint_current, info.hint ?: ""))
            Text(stringResource(R.string.bitwarden_management_hint_warning), style = MaterialTheme.typography.bodySmall)
            ManagementHintChoices(info, draft, busy, preserve = false)
            ManagementChoice(R.string.bitwarden_management_hint_password_action, draft.hintWithPassword, !busy,
                "bitwarden_management_hint_password_action") { draft.hintWithPassword = !draft.hintWithPassword }
            if (!info.hintPresent || draft.hintWithPassword) Text(stringResource(R.string.bitwarden_management_hint_password_description))
        }
        ManagementPage.PASSWORD -> {
            Text(stringResource(R.string.bitwarden_account_password_requirements))
            ManagementPasswordField(draft.newPassword, { draft.newPassword = it }, busy, R.string.bitwarden_management_new_password, "bitwarden_management_new_password")
            ManagementPasswordField(draft.confirmation, { draft.confirmation = it }, busy, R.string.bitwarden_management_confirm_password_value, "bitwarden_management_confirm_password_value")
            if (draft.confirmation.isNotEmpty() && draft.confirmation != draft.newPassword) Text(stringResource(R.string.bitwarden_management_password_mismatch), color = MaterialTheme.colorScheme.error)
            if (!info.hintPresent) Text(stringResource(R.string.bitwarden_management_hint_unavailable))
            ManagementHintChoices(info, draft, busy, preserve = true)
            Text(stringResource(R.string.bitwarden_management_password_description))
        }
        ManagementPage.KDF -> {
            Text(managementKdfSummary(info.kdf))
            ManagementChoice(R.string.bitwarden_management_pbkdf2, draft.kdfType == 0, !busy, "bitwarden_management_pbkdf2") { draft.chooseKdf(0) }
            ManagementChoice(R.string.bitwarden_management_argon2, draft.kdfType == 1, !busy, "bitwarden_management_argon2") { draft.chooseKdf(1) }
            OutlinedTextField(draft.iterations, { draft.iterations = it }, enabled = !busy, saveTextState = false, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(stringResource(R.string.bitwarden_management_iterations)) },
                modifier = Modifier.fillMaxWidth().testTag("bitwarden_management_iterations"))
            if (draft.kdfType == 1) {
                OutlinedTextField(draft.memory, { draft.memory = it }, enabled = !busy, saveTextState = false, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(stringResource(R.string.bitwarden_management_memory)) },
                    modifier = Modifier.fillMaxWidth().testTag("bitwarden_management_memory"))
                OutlinedTextField(draft.parallelism, { draft.parallelism = it }, enabled = !busy, saveTextState = false, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(stringResource(R.string.bitwarden_management_parallelism)) },
                    modifier = Modifier.fillMaxWidth().testTag("bitwarden_management_parallelism"))
            }
            val limits = BitwardenAccountMutationKdfLimits()
            Text(if (draft.kdfType == 0) stringResource(R.string.bitwarden_management_pbkdf2_limits, 600_000, limits.maxPbkdf2Iterations)
                else stringResource(R.string.bitwarden_management_argon2_limits, 2, limits.maxArgonIterations, 16, limits.maxArgonMemoryMiB, limits.maxArgonParallelism), style = MaterialTheme.typography.bodySmall)
            if (draft.selectedKdf() == null) Text(stringResource(R.string.bitwarden_management_kdf_invalid), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.bitwarden_management_kdf_description))
        }
        ManagementPage.DELETE -> {
            Text(stringResource(R.string.bitwarden_management_delete_warning), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.bitwarden_management_local_preserved))
            OutlinedTextField(draft.emailConfirmation, { draft.emailConfirmation = it }, enabled = !busy, saveTextState = false, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), label = { Text(stringResource(R.string.bitwarden_management_confirm_email)) },
                supportingText = { Text(stringResource(R.string.bitwarden_management_confirm_email_description)) },
                modifier = Modifier.fillMaxWidth().testTag("bitwarden_management_confirm_email"))
        }
        else -> Unit
    }
    ManagementPasswordField(draft.currentPassword, { draft.currentPassword = it }, busy,
        R.string.bitwarden_management_current_password, "bitwarden_management_current_password")
}

@Composable private fun ManagementReview(info: BitwardenAccountManagementInfo, draft: AccountManagementDraft) {
    ManagementIdentity(info)
    Text(stringResource(when (draft.screen) {
        ManagementPage.HINT -> R.string.bitwarden_management_confirm_hint
        ManagementPage.PASSWORD -> R.string.bitwarden_management_confirm_password
        ManagementPage.KDF -> R.string.bitwarden_management_confirm_kdf
        else -> R.string.bitwarden_management_delete_warning
    }), color = if (draft.screen == ManagementPage.DELETE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().testTag("bitwarden_management_review_action"))
    if (draft.screen == ManagementPage.KDF) draft.selectedKdf()?.let { Text(managementKdfSummary(it)) }
    if (draft.screen == ManagementPage.HINT || draft.screen == ManagementPage.PASSWORD) Text(stringResource(when (draft.hintChoice) {
        HintChoice.PRESERVE -> R.string.bitwarden_management_hint_preserve
        HintChoice.CLEAR -> R.string.bitwarden_management_hint_clear
        else -> R.string.bitwarden_management_hint_replace
    }))
    Text(stringResource(R.string.bitwarden_management_local_preserved))
    if (draft.screen == ManagementPage.PASSWORD || draft.screen == ManagementPage.KDF || draft.hintWithPassword)
        Text(stringResource(R.string.bitwarden_management_password_description))
}

@Composable private fun managementKdfSummary(kdf: BitwardenAccountMutationKdf): String = when (kdf.type) {
    0 -> stringResource(R.string.bitwarden_management_pbkdf2_summary, kdf.iterations)
    1 -> stringResource(R.string.bitwarden_management_argon2_summary, kdf.iterations, kdf.memoryMiB ?: 0, kdf.parallelism ?: 0)
    else -> stringResource(R.string.bitwarden_management_kdf_invalid)
}

private enum class ManagementPage { HOME, HINT, PASSWORD, KDF, DELETE }
private enum class HintChoice { UNSELECTED, PRESERVE, REPLACE, CLEAR }

/** Deliberately remembered, never saved; redacted even in accidental diagnostic formatting. */
private class AccountManagementDraft {
    var screen by mutableStateOf(ManagementPage.HOME)
    var currentPassword by mutableStateOf("")
    var newPassword by mutableStateOf("")
    var confirmation by mutableStateOf("")
    var hint by mutableStateOf("")
    var hintChoice by mutableStateOf(HintChoice.UNSELECTED)
    var hintWithPassword by mutableStateOf(false)
    var emailConfirmation by mutableStateOf("")
    var kdfType by mutableIntStateOf(0)
    var iterations by mutableStateOf("")
    var memory by mutableStateOf("")
    var parallelism by mutableStateOf("")
    var reviewing by mutableStateOf(false)
    var submitted by mutableStateOf(false)

    fun clear() {
        currentPassword = ""; newPassword = ""; confirmation = ""; hint = ""; emailConfirmation = ""
        hintChoice = HintChoice.UNSELECTED; hintWithPassword = false; reviewing = false; submitted = false
        iterations = ""; memory = ""; parallelism = ""; screen = ManagementPage.HOME
    }
    fun select(page: ManagementPage, info: BitwardenAccountManagementInfo) {
        clear(); screen = page
        if (page == ManagementPage.PASSWORD && info.hintPresent) hintChoice = HintChoice.PRESERVE
        kdfType = info.kdf.type; iterations = info.kdf.iterations.toString()
        memory = info.kdf.memoryMiB?.toString() ?: "64"; parallelism = info.kdf.parallelism?.toString() ?: "4"
    }
    fun chooseKdf(type: Int) {
        if (type == kdfType) return
        kdfType = type; iterations = if (type == 0) "600000" else "3"; memory = "64"; parallelism = "4"
    }
    fun selectedKdf(): BitwardenAccountMutationKdf? {
        val count = iterations.toIntOrNull() ?: return null
        val limits = BitwardenAccountMutationKdfLimits()
        return when (kdfType) {
            0 -> if (count in 600_000..limits.maxPbkdf2Iterations) BitwardenAccountMutationKdf(0, count) else null
            1 -> {
                val mem = memory.toIntOrNull() ?: return null
                val parallel = parallelism.toIntOrNull() ?: return null
                if (count in 2..limits.maxArgonIterations && mem in 16..limits.maxArgonMemoryMiB && parallel in 1..limits.maxArgonParallelism)
                    BitwardenAccountMutationKdf(1, count, mem, parallel) else null
            }
            else -> null
        }
    }
    private fun hintReady(info: BitwardenAccountManagementInfo) = when (hintChoice) {
        HintChoice.PRESERVE -> info.hintPresent
        HintChoice.REPLACE -> hint.isNotEmpty() && hint.length <= 4096 && '\u0000' !in hint
        HintChoice.CLEAR -> true
        else -> false
    }
    fun ready(info: BitwardenAccountManagementInfo): Boolean = currentPassword.isNotEmpty() && currentPassword.length <= 4096 && when (screen) {
        ManagementPage.HINT -> hintReady(info) && (info.hintPresent || hintWithPassword)
        ManagementPage.PASSWORD -> newPassword.length in 12..4096 && newPassword == confirmation && hintReady(info)
        ManagementPage.KDF -> selectedKdf()?.let { it != info.kdf } == true
        ManagementPage.DELETE -> emailConfirmation == info.email
        else -> false
    }
    fun action(info: BitwardenAccountManagementInfo): BitwardenAccountMutationAction? {
        if (!ready(info)) return null
        val newHint = if (hintChoice == HintChoice.CLEAR) null else hint
        return when (screen) {
            ManagementPage.HINT -> if (hintWithPassword) BitwardenAccountMutationAction.HintWithPassword(newHint) else BitwardenAccountMutationAction.HintProfile(newHint)
            ManagementPage.PASSWORD -> BitwardenAccountMutationAction.Password(newPassword, confirmation,
                if (hintChoice == HintChoice.PRESERVE) BitwardenAccountMutationHint.Preserve else BitwardenAccountMutationHint.Replace(newHint))
            ManagementPage.KDF -> BitwardenAccountMutationAction.Kdf(selectedKdf()!!)
            ManagementPage.DELETE -> BitwardenAccountMutationAction.Delete
            else -> null
        }
    }
    override fun toString() = "AccountManagementDraft(redacted)"
}
