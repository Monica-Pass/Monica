package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.security.BoundedSecureDataRead
import takagi.ru.monica.security.LocalPasswordAlgorithm
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.components.MasterPasswordTextField
import takagi.ru.monica.ui.rememberUiSecurityManager

@Composable
internal fun LocalPasswordProtectionDialog(onDismiss: () -> Unit) {
    val manager = rememberUiSecurityManager()
    val generation by SecurityManager.dataReadGeneration.collectAsState()
    LocalPasswordProtectionDialogContent(
        generation = generation,
        isCurrent = { manager.isDataReadCurrent(it) },
        readAlgorithm = manager::getLocalPasswordAlgorithm,
        changeAlgorithm = manager::changeLocalPasswordAlgorithm,
        onDismiss = onDismiss,
    )
}

private enum class ProtectionStatus { LOADING, READY, UPDATING, SUCCEEDED, UNAVAILABLE }

/** Both operations run on the same bounded security worker used by authentication. */
@Composable
internal fun LocalPasswordProtectionDialogContent(
    generation: Long,
    isCurrent: (Long) -> Boolean,
    readAlgorithm: () -> LocalPasswordAlgorithm,
    changeAlgorithm: (String, LocalPasswordAlgorithm, SecurityManager.LocalPasswordChangeAttempt, () -> Unit) -> Boolean,
    onDismiss: () -> Unit,
    operationTimeoutMillis: Long = 15_000,
) {
    val entryGeneration = remember { generation }
    val active = remember { AtomicBoolean(true) }
    val currentCheck = rememberUpdatedState(isCurrent)
    val read = rememberUpdatedState(readAlgorithm)
    val change = rememberUpdatedState(changeAlgorithm)
    val dismiss = rememberUpdatedState(onDismiss)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var status by remember { mutableStateOf(ProtectionStatus.LOADING) }
    var current by remember { mutableStateOf<LocalPasswordAlgorithm?>(null) }
    var selected by remember { mutableStateOf(LocalPasswordAlgorithm.PBKDF2_SHA256) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var changeFailed by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var pending by remember { mutableStateOf<SecurityManager.LocalPasswordChangeAttempt?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }

    fun sessionCurrent() = active.get() && currentCheck.value(entryGeneration)

    fun revoke() {
        active.set(false)
        pending?.cancel()
        pending = null
        operation?.cancel()
        operation = null
        password = ""
        passwordVisible = false
        keyboard?.hide()
        ended = true
    }

    fun close() {
        if (!active.get()) return
        revoke()
        dismiss.value()
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) close()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            revoke()
        }
    }

    LaunchedEffect(generation) {
        if (generation != entryGeneration || !sessionCurrent()) close()
    }
    LaunchedEffect(reload) {
        if (!sessionCurrent()) return@LaunchedEffect
        status = ProtectionStatus.LOADING
        current = null
        val loaded = BoundedSecureDataRead.readChecked(operationTimeoutMillis, ::sessionCurrent) { read.value() }
        if (!sessionCurrent()) return@LaunchedEffect
        if (loaded == null) status = ProtectionStatus.UNAVAILABLE
        else {
            current = loaded
            selected = loaded
            changeFailed = false
            status = ProtectionStatus.READY
        }
    }

    fun submit() {
        if (!sessionCurrent() || status != ProtectionStatus.READY || selected == current || password.isEmpty()) return
        val attempt = SecurityManager.LocalPasswordChangeAttempt()
        val submittedPassword = password
        val algorithm = selected
        pending = attempt
        password = ""
        passwordVisible = false
        keyboard?.hide()
        status = ProtectionStatus.UPDATING
        changeFailed = false
        operation = scope.launch {
            try {
                val changed = BoundedSecureDataRead.readChecked(operationTimeoutMillis, ::sessionCurrent) {
                    change.value(submittedPassword, algorithm, attempt) { ensureActive() }
                }
                if (!sessionCurrent()) return@launch
                if (changed == true) {
                    current = algorithm
                    status = ProtectionStatus.SUCCEEDED
                } else {
                    // A timed-out transaction may already have committed its canonical record.
                    // Do not display the old algorithm as current or promise a rollback.
                    current = null
                    changeFailed = true
                    status = ProtectionStatus.UNAVAILABLE
                }
            } finally {
                attempt.cancel()
                if (pending === attempt) pending = null
            }
        }
    }

    // Dialog has its own Compose view; preserve the host's accessibility configuration.
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val direction = LocalLayoutDirection.current
    if (!ended && generation == entryGeneration) {
        AlertDialog(
            modifier = Modifier.testTag("local-protection-dialog"),
            onDismissRequest = ::close,
            title = { Text(stringResource(R.string.local_password_protection_title)) },
            text = {
                CompositionLocalProvider(LocalDensity provides density, LocalConfiguration provides configuration,
                    LocalLayoutDirection provides direction) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = (configuration.screenHeightDp * 0.58f).dp)
                            .verticalScroll(rememberScrollState()).testTag("local-protection-body"),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(stringResource(R.string.local_password_protection_scope))
                        when (status) {
                            ProtectionStatus.LOADING -> Text(stringResource(R.string.loading))
                            ProtectionStatus.UPDATING -> Text(stringResource(R.string.local_password_protection_changing),
                                Modifier.testTag("local-protection-busy"))
                            ProtectionStatus.SUCCEEDED -> {
                                Text(stringResource(R.string.local_password_protection_success),
                                    Modifier.testTag("local-protection-success"), color = MaterialTheme.colorScheme.primary)
                                Text(algorithmName(selected))
                            }
                            ProtectionStatus.UNAVAILABLE -> Text(stringResource(if (changeFailed)
                                R.string.local_password_protection_failed else R.string.local_password_protection_read_failed),
                                Modifier.testTag("local-protection-error"), color = MaterialTheme.colorScheme.error)
                            ProtectionStatus.READY -> {
                                Text(stringResource(R.string.local_password_protection_current, algorithmName(checkNotNull(current))),
                                    Modifier.testTag("local-protection-current"), style = MaterialTheme.typography.titleSmall)
                                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    LocalPasswordAlgorithm.entries.forEach { algorithm ->
                                        ProtectionChoice(algorithm, selected == algorithm) {
                                            selected = algorithm
                                            password = ""
                                            passwordVisible = false
                                        }
                                    }
                                }
                                Text(stringResource(R.string.local_password_protection_downgrade),
                                    Modifier.testTag("local-protection-downgrade"))
                                if (selected != current) {
                                    Text(stringResource(R.string.local_password_protection_confirm_message))
                                    MasterPasswordTextField(
                                        value = password, onValueChange = { password = it },
                                        modifier = Modifier.fillMaxWidth().testTag("local-protection-password"),
                                        label = { Text(stringResource(R.string.current_password)) },
                                        visible = passwordVisible, onVisibilityChange = { passwordVisible = it },
                                        saveTextState = false,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                when (status) {
                    ProtectionStatus.SUCCEEDED -> TextButton(onClick = ::close,
                        modifier = Modifier.testTag("local-protection-close")) { Text(stringResource(R.string.close)) }
                    ProtectionStatus.UNAVAILABLE -> TextButton(onClick = { reload++ },
                        modifier = Modifier.testTag("local-protection-retry")) { Text(stringResource(R.string.retry)) }
                    else -> TextButton(onClick = ::submit,
                        enabled = status == ProtectionStatus.READY && selected != current && password.isNotEmpty(),
                        modifier = Modifier.testTag("local-protection-update")) {
                        Text(stringResource(R.string.local_password_protection_update))
                    }
                }
            },
            dismissButton = {
                if (status != ProtectionStatus.SUCCEEDED) TextButton(onClick = ::close,
                    modifier = Modifier.testTag("local-protection-cancel")) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ProtectionChoice(algorithm: LocalPasswordAlgorithm, selected: Boolean, onSelect: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().selectable(selected, onClick = onSelect, role = Role.RadioButton)
            .testTag("local-protection-${algorithm.name}").padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(algorithmName(algorithm), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (algorithm == LocalPasswordAlgorithm.PBKDF2_SHA256)
                    R.string.local_password_protection_pbkdf_description else R.string.local_password_protection_argon_description),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun algorithmName(algorithm: LocalPasswordAlgorithm): String = when (algorithm) {
    LocalPasswordAlgorithm.PBKDF2_SHA256 -> "PBKDF2-HMAC-SHA256"
    LocalPasswordAlgorithm.ARGON2ID -> "Argon2id"
}
