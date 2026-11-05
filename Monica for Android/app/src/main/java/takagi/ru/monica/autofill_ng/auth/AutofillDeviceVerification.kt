package takagi.ru.monica.autofill_ng.auth

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.components.MonicaPasswordDialogAuthScreen
import takagi.ru.monica.utils.BiometricAuthHelper
import takagi.ru.monica.utils.SettingsManager

/** Fresh strong biometric or Monica master-password verification, never the device screen lock. */
@Composable
internal fun AutofillDeviceVerification(
    onSuccess: (Long) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.fragmentActivity() }
    val success = rememberUpdatedState(onSuccess)
    val cancel = rememberUpdatedState(onCancel)
    val finished = remember { AtomicBoolean(false) }
    val keyguard = remember { context.getSystemService(KeyguardManager::class.java) }
    val security = remember { lazy { SecurityManager(context.applicationContext) } }
    val settingsManager = remember { SettingsManager(context.applicationContext) }
    val helper = remember(context) { BiometricAuthHelper(context) }
    val verificationScope = rememberCoroutineScope()
    // A lock revokes this verification request, including a result awaiting the UI callback.
    val authorizationGeneration = remember { SecurityManager.dataReadGeneration.value }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    fun cancelOnce(message: CharSequence? = null) {
        if (!finished.compareAndSet(false, true)) return
        message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        cancel.value()
    }

    fun canComplete(): Boolean = !finished.get() && activity != null &&
        !activity.isFinishing && !activity.isDestroyed &&
        context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
        keyguard?.isKeyguardLocked == false

    fun verified() {
        if (!canComplete() || !security.value.isDataReadCurrent(authorizationGeneration)) {
            cancelOnce()
            return
        }
        if (finished.compareAndSet(false, true)) success.value(authorizationGeneration)
    }

    fun useMasterPassword() {
        if (finished.get()) return
        helper.cancelAuthentication()
        // An unconfigured vault must not become a successful password check.
        if (runCatching { security.value.isMasterPasswordSet() }.getOrDefault(false)) {
            showPassword = true
        } else {
            cancelOnce(context.getString(R.string.autofill_device_auth_unavailable))
        }
    }

    BackHandler { cancelOnce() }
    DisposableEffect(helper) {
        onDispose { finished.set(true); helper.cancelAuthentication() }
    }
    LaunchedEffect(helper, activity) {
        if (activity == null) {
            cancelOnce()
            return@LaunchedEffect
        }
        if (showPassword) return@LaunchedEffect
        val settings = settingsManager.settingsFlow.first()
        if (settings.biometricEnabled && helper.isStrongBiometricAvailable()) {
            runCatching {
                helper.authenticate(
                    activity = activity,
                    title = context.getString(R.string.autofill_auth_title),
                    subtitle = null,
                    description = context.getString(R.string.autofill_device_auth_description),
                    negativeButtonText = context.getString(R.string.use_password),
                    onSuccess = {
                        verificationScope.launch {
                            if (!canComplete()) { cancelOnce(); return@launch }
                            val manager = runCatching { security.value }.getOrNull()
                                ?: run { cancelOnce(); return@launch }
                            val unlocked = takagi.ru.monica.security.BoundedSecureDataRead.read(manager, authorizationGeneration) {
                                manager.isVaultRuntimeUnlocked() || manager.unlockVaultWithBiometric()
                            } == true
                            if (unlocked) verified()
                            // Preserve the existing password path for older key wrappers.
                            else useMasterPassword()
                        }
                    },
                    onError = { code, _ ->
                        if (code == BiometricPrompt.ERROR_CANCELED) cancelOnce()
                        else useMasterPassword()
                    },
                    onCancel = { cancelOnce() },
                    onNegativeButton = { useMasterPassword() },
                )
            }.onFailure { useMasterPassword() }
        } else useMasterPassword()
    }

    if (showPassword) {
        MonicaPasswordDialogAuthScreen(
            settingsFlow = settingsManager.settingsFlow,
            appName = context.getString(R.string.app_name),
            title = context.getString(R.string.verify_identity),
            subtitle = context.getString(R.string.autofill_auth_subtitle),
            passwordLabel = context.getString(R.string.master_password),
            description = context.getString(R.string.enter_master_password),
            confirmText = context.getString(R.string.confirm),
            cancelText = context.getString(R.string.cancel),
            emptyError = context.getString(R.string.current_password_required),
            unsupportedCharacterError = context.getString(R.string.error_password_contains_unsupported_characters),
            minLengthError = context.getString(R.string.error_password_too_short),
            incorrectError = context.getString(R.string.password_incorrect),
            verifyPassword = { input, attempt ->
                canComplete() && runCatching {
                    security.value.isDataReadCurrent(authorizationGeneration) &&
                        security.value.isMasterPasswordSet() && security.value.unlockVaultWithPassword(input, attempt) &&
                        security.value.isDataReadCurrent(authorizationGeneration)
                }.getOrDefault(false)
            },
            onSuccess = { verified() },
            onCancel = { cancelOnce() },
        )
    }
}

private tailrec fun Context.fragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.fragmentActivity()
    else -> null
}
