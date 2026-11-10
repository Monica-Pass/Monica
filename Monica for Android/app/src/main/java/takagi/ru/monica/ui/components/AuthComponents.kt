package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.security.MasterPasswordPolicy
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.BoundedSecureDataRead
import takagi.ru.monica.utils.BiometricAuthHelper

private enum class PasswordVerificationBiometricAccessResult {
    PROCEED,
    REQUIRE_PASSWORD_REENTRY,
    LOCKED
}

/**
 * 统一的密码验证组件
 * 用于 LoginScreen 和 AutofillPickerActivityV2
 */
@Composable
fun PasswordVerificationContent(
    modifier: Modifier = Modifier,
    isFirstTime: Boolean = false,
    isConfirmingPassword: Boolean = false,
    disablePasswordVerification: Boolean = false,
    biometricEnabled: Boolean = false,
    autoLockMinutes: Int = 5,
    persistVaultUnlockToSession: Boolean = true,
    onVerifyPassword: (String) -> Boolean,
    onSetPassword: (String) -> Boolean = { false },
    onSuccess: () -> Unit,
    onForgotPassword: (() -> Unit)? = null,
    passwordSecurityManager: SecurityManager? = null,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    
    var masterPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var authenticationCompleted by remember { mutableStateOf(false) }
    var passwordBusy by remember { mutableStateOf(false) }
    var passwordForeground by remember { mutableStateOf(true) }
    val passwordActive = remember { AtomicBoolean(true) }
    val passwordRequest = remember { AtomicLong(0) }
    val passwordScope = rememberCoroutineScope()
    val passwordLifecycle = LocalLifecycleOwner.current.lifecycle
    val passwordGeneration by SecurityManager.dataReadGeneration.collectAsState()
    var passwordJob by remember { mutableStateOf<Job?>(null) }
    var verificationAttempt by remember { mutableStateOf<SecurityManager.PasswordVerificationAttempt?>(null) }
    var setupAttempt by remember { mutableStateOf<SecurityManager.LocalPasswordChangeAttempt?>(null) }
    
    // 内部状态，用于处理首次设置密码的确认流程
    var internalIsConfirming by remember { mutableStateOf(isConfirmingPassword) }
    
    // 生物识别帮助类。SecurityManager 故意延迟到系统认证成功之后再初始化，
    // 避免 AndroidX Security / Keystore 构造阻挡原生认证面板弹出。
    val biometricHelper = remember(context) { BiometricAuthHelper(context.applicationContext) }
    val securityManager = remember(context) {
        lazy(LazyThreadSafetyMode.NONE) { SecurityManager(context.applicationContext) }
    }
    val isBiometricAvailable = remember(biometricHelper) { biometricHelper.isBiometricAvailable() }
    val canUseBiometric = !isFirstTime && isBiometricAvailable && biometricEnabled
    var autoBiometricTried by remember { mutableStateOf(false) }

    fun clearPasswordInputs() {
        masterPassword = ""
        confirmPassword = ""
        passwordVisible = false
        confirmPasswordVisible = false
        internalIsConfirming = false
    }

    fun revokePasswordRequest() {
        passwordRequest.incrementAndGet()
        verificationAttempt?.cancel()
        setupAttempt?.cancel()
        verificationAttempt = null
        setupAttempt = null
        passwordJob?.cancel()
        passwordJob = null
        passwordBusy = false
        clearPasswordInputs()
    }

    DisposableEffect(passwordLifecycle, passwordSecurityManager) {
        if (passwordSecurityManager == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                passwordActive.set(false)
                passwordForeground = false
                revokePasswordRequest()
            } else if (event == Lifecycle.Event.ON_RESUME) {
                passwordActive.set(true)
                passwordForeground = true
            }
        }
        passwordLifecycle.addObserver(observer)
        onDispose {
            passwordLifecycle.removeObserver(observer)
            passwordActive.set(false)
            revokePasswordRequest()
        }
    }
    LaunchedEffect(passwordGeneration) {
        if (passwordSecurityManager != null) revokePasswordRequest()
    }

    fun completeAuthentication() {
        if (authenticationCompleted) return
        if (passwordSecurityManager != null) revokePasswordRequest()
        if (persistVaultUnlockToSession) {
            (passwordSecurityManager ?: securityManager.value).markVaultAuthenticated()
        }
        authenticationCompleted = true
        keyboardController?.hide()
        focusManager.clearFocus()
        onSuccess()
    }

    fun canSubmitPassword(): Boolean = !authenticationCompleted && !passwordBusy && passwordForeground &&
        (disablePasswordVerification ||
            (if (internalIsConfirming) confirmPassword else masterPassword).isNotEmpty())

    fun submitManagedPassword(setup: Boolean) {
        val manager = checkNotNull(passwordSecurityManager)
        val submittedPassword = masterPassword
        val request = passwordRequest.incrementAndGet()
        val generation = SecurityManager.dataReadGeneration.value
        val verifyAttempt = if (setup) null else SecurityManager.PasswordVerificationAttempt()
        val createAttempt = if (setup) SecurityManager.LocalPasswordChangeAttempt() else null
        verificationAttempt = verifyAttempt
        setupAttempt = createAttempt
        passwordBusy = true
        clearPasswordInputs()
        keyboardController?.hide()
        fun isCurrent() = passwordActive.get() && passwordRequest.get() == request &&
            manager.isDataReadCurrent(generation)
        passwordJob = passwordScope.launch {
            try {
                val accepted = BoundedSecureDataRead.readChecked(isCurrent = ::isCurrent) {
                    if (createAttempt != null) manager.setMasterPassword(submittedPassword, createAttempt) { ensureActive() }
                    else manager.verifyMasterPassword(submittedPassword, checkNotNull(verifyAttempt)) { ensureActive() }
                }
                if (!isCurrent()) return@launch
                if (accepted == true && (setup || verifyAttempt?.commit() == true)) completeAuthentication()
                else errorMessage = context.getString(if (setup)
                    R.string.local_password_protection_reset_failed else R.string.error_invalid_password)
            } finally {
                verifyAttempt?.cancel()
                createAttempt?.cancel()
                if (verificationAttempt === verifyAttempt) verificationAttempt = null
                if (setupAttempt === createAttempt) setupAttempt = null
                if (passwordRequest.get() == request) passwordBusy = false
            }
        }
    }

    // The button and IME must use the same validation, including empty-input guards.
    fun submitPassword() {
        if (!canSubmitPassword()) return

        // 如果已存在主密码且关闭了密码验证,直接通过
        if (!isFirstTime && disablePasswordVerification) {
            completeAuthentication()
            return
        }

        if (isFirstTime) {
            if (!internalIsConfirming) {
                internalIsConfirming = true
            } else {
                if (!MasterPasswordPolicy.meetsMinLength(masterPassword)) {
                    errorMessage = context.getString(R.string.password_too_short)
                    confirmPassword = ""
                    internalIsConfirming = false
                    return
                }
                if (masterPassword != confirmPassword) {
                    errorMessage = context.getString(R.string.error_passwords_not_match)
                    confirmPassword = ""
                    internalIsConfirming = false
                    return
                }
                if (passwordSecurityManager != null) submitManagedPassword(setup = true)
                else if (onSetPassword(masterPassword)) completeAuthentication()
                else errorMessage = context.getString(R.string.local_recovery_save_failed)
            }
        } else {
            if (passwordSecurityManager != null) submitManagedPassword(setup = false)
            else if (onVerifyPassword(masterPassword)) {
                completeAuthentication()
            } else {
                errorMessage = context.getString(R.string.error_invalid_password)
            }
        }
    }

    fun canProceedAfterBiometricAuth(): PasswordVerificationBiometricAccessResult {
        val manager = securityManager.value
        if (!manager.isMasterPasswordSet()) return PasswordVerificationBiometricAccessResult.PROCEED
        val directUnlock = runCatching {
            manager.unlockVaultWithBiometric()
        }.getOrDefault(false)
        if (directUnlock) {
            android.util.Log.d("PasswordVerification", "Biometric post-check: direct unlock succeeded")
            return PasswordVerificationBiometricAccessResult.PROCEED
        }
        if (manager.canAccessVaultMaterialNow()) {
            android.util.Log.d("PasswordVerification", "Biometric post-check: vault material accessible after retry")
            return PasswordVerificationBiometricAccessResult.PROCEED
        }
        val vaultState = manager.getVaultAccessState(context.applicationContext, autoLockMinutes)
        if (vaultState == SecurityManager.VaultAccessState.REQUIRES_PASSWORD_REENTRY) {
            val rebuilt = manager.rebuildKeystoreWrapperFromRuntimeCacheIfNeeded()
            if (rebuilt) {
                android.util.Log.d("PasswordVerification", "Biometric post-check: wrapper rebuilt from runtime cache")
                return PasswordVerificationBiometricAccessResult.PROCEED
            }
            if (manager.isVaultRuntimeUnlocked()) {
                android.util.Log.w(
                    "PasswordVerification",
                    "Biometric post-check: wrapper rebuild failed but runtime MDK exists; allowing access to avoid lock loop"
                )
                return PasswordVerificationBiometricAccessResult.PROCEED
            }
        }
        val result = when (vaultState) {
            SecurityManager.VaultAccessState.ACCESSIBLE -> PasswordVerificationBiometricAccessResult.PROCEED
            SecurityManager.VaultAccessState.REQUIRES_PASSWORD_REENTRY -> PasswordVerificationBiometricAccessResult.REQUIRE_PASSWORD_REENTRY
            SecurityManager.VaultAccessState.LOCKED -> PasswordVerificationBiometricAccessResult.LOCKED
        }
        android.util.Log.w(
            "PasswordVerification",
            "Biometric post-check: direct unlock failed, result=$result, autoLockMinutes=$autoLockMinutes"
        )
        return result
    }

    fun updateBiometricFailureMessage(result: PasswordVerificationBiometricAccessResult) {
        errorMessage = when (result) {
            PasswordVerificationBiometricAccessResult.REQUIRE_PASSWORD_REENTRY -> {
                context.getString(R.string.biometric_requires_password_reentry)
            }
            PasswordVerificationBiometricAccessResult.LOCKED -> {
                context.getString(R.string.ime_unlock_required)
            }
            PasswordVerificationBiometricAccessResult.PROCEED -> {
                ""
            }
        }
    }
    
    // 自动触发生物识别
    LaunchedEffect(isFirstTime, isBiometricAvailable, biometricEnabled, activity) {
        if (!autoBiometricTried && canUseBiometric && activity != null) {
            autoBiometricTried = true
            biometricHelper.prepare(activity)
            biometricHelper.authenticate(
                activity = activity,
                onSuccess = {
                    when (val result = canProceedAfterBiometricAuth()) {
                        PasswordVerificationBiometricAccessResult.PROCEED -> completeAuthentication()
                        else -> updateBiometricFailureMessage(result)
                    }
                },
                onError = { _, errorMsg ->
                    errorMessage = context.getString(R.string.biometric_error, errorMsg)
                },
                onCancel = {
                    // 用户主动取消，不显示错误，停留在密码输入界面
                }
            )
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App Title
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        
        Text(
            text = when {
                isFirstTime && !internalIsConfirming -> 
                    stringResource(R.string.setup_master_password)
                isFirstTime && internalIsConfirming -> 
                    stringResource(R.string.confirm_master_password)
                else -> 
                    stringResource(R.string.enter_master_password)
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        
        if (isFirstTime && !internalIsConfirming) {
            Text(
                text = stringResource(R.string.password_min_6_digits),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        
        if (!isFirstTime && disablePasswordVerification) {
            Text(
                text = stringResource(R.string.developer_mode_password_disabled),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Master Password Field
        if (!passwordBusy && passwordForeground) MasterPasswordTextField(
            value = if (internalIsConfirming) confirmPassword else masterPassword,
            onValueChange = { input ->
                if (internalIsConfirming) {
                    confirmPassword = input
                } else {
                    masterPassword = input
                }
                errorMessage = ""
            },
            onUnsupportedCharacterAttempt = {
                errorMessage = context.getString(R.string.error_password_contains_unsupported_characters)
            },
            label = { 
                Text(
                    if (internalIsConfirming) 
                        stringResource(R.string.confirm_master_password)
                    else 
                        stringResource(R.string.master_password)
                ) 
            },
            visible = if (internalIsConfirming) confirmPasswordVisible else passwordVisible,
            onVisibilityChange = { visible ->
                if (internalIsConfirming) {
                    confirmPasswordVisible = visible
                } else {
                    passwordVisible = visible
                }
            },
            modifier = Modifier.fillMaxWidth(),
            imeAction = if (isFirstTime && !internalIsConfirming) ImeAction.Next else ImeAction.Done,
            keyboardActions = KeyboardActions(
                onNext = { submitPassword() },
                onDone = { submitPassword() }
            ),
            saveTextState = false,
        )
        
        // Error Message
        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Login/Setup Button
        Button(
            onClick = { submitPassword() },
            modifier = Modifier.fillMaxWidth(),
            enabled = canSubmitPassword()
        ) {
            if (passwordBusy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = when {
                    isFirstTime && !internalIsConfirming -> stringResource(R.string.set_up_password)
                    isFirstTime && internalIsConfirming -> stringResource(R.string.confirm)
                    else -> stringResource(R.string.unlock)
                }
            )
        }
        
        // 生物识别按钮（仅在非首次使用、生物识别可用且已启用时显示）
        if (canUseBiometric && activity != null) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    biometricHelper.authenticate(
                        activity = activity,
                        onSuccess = {
                            when (val result = canProceedAfterBiometricAuth()) {
                                PasswordVerificationBiometricAccessResult.PROCEED -> completeAuthentication()
                                else -> updateBiometricFailureMessage(result)
                            }
                        },
                        onError = { _, errorMsg ->
                            errorMessage = context.getString(R.string.biometric_error, errorMsg)
                        },
                        onCancel = {
                            // 用户取消
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !passwordBusy
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = stringResource(R.string.use_biometric),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.use_biometric))
            }
        }
        
        // Forgot password option
        if (!isFirstTime && onForgotPassword != null) {
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
                onClick = {
                    if (passwordSecurityManager != null) revokePasswordRequest()
                    onForgotPassword()
                },
                enabled = !passwordBusy
            ) {
                Text(
                    text = stringResource(R.string.forgot_password),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
