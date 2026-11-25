package takagi.ru.monica.ui.components

import android.app.KeyguardManager
import android.os.PowerManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.security.BoundedSecureDataRead
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.RecoveryProtectionCheck

/** Password and confirmation belong to one foreground submission, never to saved UI state. */
@Composable
internal fun RecoveryProtectionDialog(manager: SecurityManager, onDismiss: () -> Unit, onSuccess: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val generation by SecurityManager.dataReadGeneration.collectAsState()
    val entryGeneration = remember(manager) { generation }
    val active = remember(manager) { AtomicBoolean(true) }
    val dismiss = rememberUpdatedState(onDismiss)
    val success = rememberUpdatedState(onSuccess)
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var ended by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf<SecurityManager.PasswordVerificationAttempt?>(null) }
    var work by remember { mutableStateOf<Job?>(null) }
    fun current() = active.get() && SecurityManager.dataReadGeneration.value == entryGeneration
    fun foreground() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
    fun revoke() {
        active.set(false); attempt?.cancel(); attempt = null
        work?.cancel(); work = null; password = ""; busy = false; ended = true
        keyboard?.hide()
    }
    fun close() { if (active.get()) { revoke(); dismiss.value() } }
    DisposableEffect(manager, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) close()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); revoke() }
    }
    LaunchedEffect(generation) { if (generation != entryGeneration || !current()) close() }
    if (ended || generation != entryGeneration) return
    M3IdentityVerifyDialog(
        title = stringResource(R.string.recovery_protection_title),
        message = stringResource(if (busy) R.string.recovery_protection_checking else R.string.recovery_protection_explanation),
        passwordValue = password,
        onPasswordChange = { password = it; error = null },
        onDismiss = ::close,
        onConfirm = {
            if (!busy && password.isNotEmpty() && current() && foreground()) {
                val submitted = password
                val request = SecurityManager.PasswordVerificationAttempt()
                attempt = request; password = ""; busy = true; error = null; keyboard?.hide()
                work = scope.launch {
                    try {
                        val result = BoundedSecureDataRead.readChecked(isCurrent = ::current) {
                            manager.verifyRecoveryProtection(submitted, request) { ensureActive() }
                        }
                        if (!current() || attempt !== request) return@launch
                        if (!foreground()) { close(); return@launch }
                        if (result == RecoveryProtectionCheck.VERIFIED) {
                            active.set(false); ended = true; attempt = null; success.value()
                        } else error = when (result) {
                            RecoveryProtectionCheck.INVALID_PASSWORD -> R.string.current_password_incorrect
                            else -> R.string.recovery_protection_not_ready
                        }
                    } finally {
                        request.cancel()
                        if (attempt === request) attempt = null
                        busy = false
                    }
                }
            }
        },
        confirmText = stringResource(R.string.recovery_protection_verify),
        icon = Icons.Default.Security,
        confirmEnabled = !busy && password.isNotEmpty(),
        requireIdentityVerification = !busy,
        destructiveConfirm = false,
        isPasswordError = error != null,
        passwordErrorText = error?.let { stringResource(it) },
        showBiometricSlot = false,
        savePasswordTextState = false,
    )
}
