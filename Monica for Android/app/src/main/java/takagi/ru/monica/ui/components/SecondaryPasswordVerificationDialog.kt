package takagi.ru.monica.ui.components

import android.app.KeyguardManager
import android.os.PowerManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
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

/** Password confirmation never grants a late result to a dismissed or replaced request. */
@Composable
internal fun SecondaryPasswordVerificationDialog(
    manager: SecurityManager,
    onDismiss: () -> Unit,
    onSuccess: (Long) -> Unit,
    title: String = stringResource(R.string.verify_identity),
    message: String = stringResource(R.string.verify_master_password_desc),
    requirePassword: Boolean = true,
    confirmEnabled: Boolean = true,
    confirmText: String = stringResource(R.string.confirm),
    icon: ImageVector = Icons.Default.Lock,
    destructiveConfirm: Boolean = false,
    biometricHintText: String? = null,
    isRequestCurrent: () -> Boolean = { true },
    onBiometricClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val generation by SecurityManager.dataReadGeneration.collectAsState()
    val entryGeneration = remember(manager) { generation }
    val active = remember(manager) { AtomicBoolean(true) }
    val requestCurrent = rememberUpdatedState(isRequestCurrent)
    val dismiss = rememberUpdatedState(onDismiss)
    val success = rememberUpdatedState(onSuccess)
    var password by remember(manager) { mutableStateOf("") }
    var busy by remember(manager) { mutableStateOf(false) }
    var failed by remember(manager) { mutableStateOf(false) }
    var ended by remember(manager) { mutableStateOf(false) }
    var pending by remember(manager) { mutableStateOf<SecurityManager.PasswordVerificationAttempt?>(null) }
    var work by remember(manager) { mutableStateOf<Job?>(null) }

    fun current() = active.get() && manager.isDataReadCurrent(entryGeneration)
    fun foreground() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
    fun revoke() {
        active.set(false)
        pending?.cancel()
        pending = null
        work?.cancel()
        work = null
        password = ""
        busy = false
        ended = true
        keyboard?.hide()
    }
    fun close() {
        if (!active.get()) return
        revoke()
        dismiss.value()
    }
    DisposableEffect(manager, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) close()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); revoke() }
    }
    LaunchedEffect(generation) {
        if (generation != entryGeneration || !current()) close()
    }
    if (ended || generation != entryGeneration) return

    M3IdentityVerifyDialog(
        title = title,
        message = if (busy) stringResource(R.string.loading) else message,
        passwordValue = password,
        onPasswordChange = { password = it; failed = false },
        onDismiss = ::close,
        onConfirm = {
            if (confirmEnabled && !busy && (!requirePassword || password.isNotEmpty()) && current() && foreground() && requestCurrent.value()) {
                if (!requirePassword) {
                    // Only the caller's existing explicit no-password policy may select this path.
                    active.set(false)
                    ended = true
                    password = ""
                    keyboard?.hide()
                    success.value(entryGeneration)
                } else {
                val submitted = password
                val attempt = SecurityManager.PasswordVerificationAttempt()
                pending = attempt
                password = ""
                busy = true
                failed = false
                keyboard?.hide()
                work = scope.launch {
                    try {
                        val verified = BoundedSecureDataRead.readChecked(isCurrent = {
                            current() && attempt.isCurrent()
                        }) {
                            manager.verifyMasterPassword(submitted, attempt) { ensureActive() }
                        }
                        if (!current() || pending !== attempt) return@launch
                        if (!foreground() || !requestCurrent.value()) { close(); return@launch }
                        if (verified == true && attempt.commit()) {
                            // No suspension between the final gate and the caller's action.
                            pending = null
                            active.set(false)
                            ended = true
                            success.value(entryGeneration)
                        } else failed = true
                    } finally {
                        attempt.cancel()
                        if (pending === attempt) pending = null
                        busy = false
                    }
                }
                }
            }
        },
        confirmText = confirmText,
        icon = icon,
        confirmEnabled = confirmEnabled && !busy && (!requirePassword || password.isNotEmpty()),
        requireIdentityVerification = requirePassword && !busy,
        destructiveConfirm = destructiveConfirm,
        isPasswordError = failed,
        passwordErrorText = stringResource(R.string.current_password_incorrect),
        showBiometricSlot = onBiometricClick != null,
        onBiometricClick = if (!busy) onBiometricClick else null,
        biometricHintText = biometricHintText,
        savePasswordTextState = false,
    )
}
