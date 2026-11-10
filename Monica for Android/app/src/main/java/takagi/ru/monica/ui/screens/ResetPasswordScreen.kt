package takagi.ru.monica.ui.screens

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.security.MasterPasswordPolicy
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.BoundedSecureDataRead
import takagi.ru.monica.ui.components.MasterPasswordTextField
import takagi.ru.monica.ui.components.rememberBringIntoViewOnFocusModifier

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ResetPasswordScreen(
    securityManager: SecurityManager,
    onNavigateBack: () -> Unit,
    onResetSuccess: () -> Unit,
    skipCurrentPassword: Boolean = false
) {
    val generation by SecurityManager.dataReadGeneration.collectAsState()
    ResetPasswordScreenContent(
        generation = generation,
        isCurrent = securityManager::isDataReadCurrent,
        resetPassword = securityManager::resetMasterPassword,
        authorizedReset = securityManager::setMasterPassword,
        onNavigateBack = onNavigateBack,
        onResetSuccess = onResetSuccess,
        skipCurrentPassword = skipCurrentPassword,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun ResetPasswordScreenContent(
    generation: Long,
    isCurrent: (Long) -> Boolean,
    resetPassword: (String, String, SecurityManager.LocalPasswordChangeAttempt, () -> Unit) -> Boolean,
    authorizedReset: (String, SecurityManager.LocalPasswordChangeAttempt, () -> Unit) -> Boolean,
    onNavigateBack: () -> Unit,
    onResetSuccess: () -> Unit,
    skipCurrentPassword: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val currentPasswordImeModifier = rememberBringIntoViewOnFocusModifier()
    val newPasswordImeModifier = rememberBringIntoViewOnFocusModifier()
    val confirmPasswordImeModifier = rememberBringIntoViewOnFocusModifier()
    
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    
    var currentPasswordVisible by remember { mutableStateOf(false) }
    var newPasswordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showSuccessDialog by remember { mutableStateOf(false) }
    val entryGeneration = remember { generation }
    val active = remember { AtomicBoolean(true) }
    val currentCheck = rememberUpdatedState(isCurrent)
    val reset = rememberUpdatedState(resetPassword)
    val authorized = rememberUpdatedState(authorizedReset)
    val back = rememberUpdatedState(onNavigateBack)
    val success = rememberUpdatedState(onResetSuccess)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val keyboard = LocalSoftwareKeyboardController.current
    var attempt by remember { mutableStateOf<SecurityManager.LocalPasswordChangeAttempt?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var ended by remember { mutableStateOf(false) }

    fun sessionCurrent() = active.get() && currentCheck.value(entryGeneration)
    fun clearPasswords() {
        currentPassword = ""
        newPassword = ""
        confirmPassword = ""
        currentPasswordVisible = false
        newPasswordVisible = false
        confirmPasswordVisible = false
        keyboard?.hide()
    }
    fun revoke() {
        active.set(false)
        attempt?.cancel()
        attempt = null
        operation?.cancel()
        operation = null
        clearPasswords()
        showSuccessDialog = false
        ended = true
    }
    fun leave() {
        if (!active.get()) return
        revoke()
        back.value()
    }
    fun finish() {
        if (!sessionCurrent()) return
        revoke()
        success.value()
    }
    BackHandler(onBack = ::leave)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) leave()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            revoke()
        }
    }
    LaunchedEffect(generation) {
        if (generation != entryGeneration || !sessionCurrent()) leave()
    }
    if (ended || generation != entryGeneration) return

    // 准备共享元素 Modifier
    val sharedTransitionScope = takagi.ru.monica.ui.LocalSharedTransitionScope.current
    val animatedVisibilityScope = takagi.ru.monica.ui.LocalAnimatedVisibilityScope.current
    
    var sharedModifier: Modifier = Modifier
    if (false && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope!!) {
            sharedModifier = Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "reset_password_card"),
                animatedVisibilityScope = animatedVisibilityScope!!,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
            )
        }
    }

    Scaffold(
        modifier = sharedModifier,
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.reset_password_title)) },
                navigationIcon = {
                    IconButton(onClick = ::leave, modifier = Modifier.testTag("reset-password-back")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = context.getString(R.string.back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Description Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (skipCurrentPassword) 
                            context.getString(R.string.reset_password_verified_description)
                        else 
                            context.getString(R.string.reset_password_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            
            if (!isLoading && !showSuccessDialog) {
            // Password fields leave composition during work and after success.
            if (!skipCurrentPassword) {
                MasterPasswordTextField(
                    value = currentPassword,
                    onValueChange = { 
                        currentPassword = it
                        errorMessage = ""
                    },
                    label = { 
                        Text(
                            text = context.getString(R.string.current_password),
                            maxLines = 2,
                            style = MaterialTheme.typography.bodySmall
                        ) 
                    },
                    placeholder = { Text(context.getString(R.string.enter_current_password)) },
                    leadingIcon = {
                        Icon(Icons.Default.Lock, contentDescription = null)
                    },
                    visible = currentPasswordVisible,
                    onVisibilityChange = { currentPasswordVisible = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reset-password-current")
                        .then(currentPasswordImeModifier),
                    enabled = !isLoading,
                    saveTextState = false,
                )
                
                HorizontalDivider()
            }
            
            // New Password
            MasterPasswordTextField(
                value = newPassword,
                onValueChange = { input ->
                    newPassword = input
                    errorMessage = ""
                },
                onUnsupportedCharacterAttempt = {
                    errorMessage = context.getString(R.string.error_password_contains_unsupported_characters)
                },
                label = { 
                    Text(
                        text = context.getString(R.string.new_password),
                        maxLines = 2,
                        style = MaterialTheme.typography.bodySmall
                    ) 
                },
                placeholder = { Text(context.getString(R.string.enter_new_password)) },
                leadingIcon = {
                    Icon(Icons.Default.VpnKey, contentDescription = null)
                },
                visible = newPasswordVisible,
                onVisibilityChange = { newPasswordVisible = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reset-password-new")
                    .then(newPasswordImeModifier),
                enabled = !isLoading,
                saveTextState = false,
                isError = newPassword.isNotEmpty() && !MasterPasswordPolicy.meetsMinLength(newPassword)
            )
            
            if (newPassword.isNotEmpty() && !MasterPasswordPolicy.meetsMinLength(newPassword)) {
                Text(
                    text = context.getString(R.string.password_too_short),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
            
            // Confirm New Password
            MasterPasswordTextField(
                value = confirmPassword,
                onValueChange = { input ->
                    confirmPassword = input
                    errorMessage = ""
                },
                onUnsupportedCharacterAttempt = {
                    errorMessage = context.getString(R.string.error_password_contains_unsupported_characters)
                },
                label = { 
                    Text(
                        text = context.getString(R.string.confirm_new_password),
                        maxLines = 2,
                        style = MaterialTheme.typography.bodySmall
                    ) 
                },
                placeholder = { Text(context.getString(R.string.enter_confirm_password)) },
                leadingIcon = {
                    Icon(Icons.Default.VpnKey, contentDescription = null)
                },
                visible = confirmPasswordVisible,
                onVisibilityChange = { confirmPasswordVisible = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reset-password-confirm")
                    .then(confirmPasswordImeModifier),
                enabled = !isLoading,
                saveTextState = false,
                isError = confirmPassword.isNotEmpty() && newPassword != confirmPassword
            )
            
            if (confirmPassword.isNotEmpty() && newPassword != confirmPassword) {
                Text(
                    text = context.getString(R.string.passwords_do_not_match),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
            }
            
            // Error Message
            if (errorMessage.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Reset Password Button
            Button(
                onClick = {
                    when {
                        !skipCurrentPassword && currentPassword.isEmpty() -> {
                            errorMessage = context.getString(R.string.current_password_required)
                        }
                        newPassword.isEmpty() -> {
                            errorMessage = context.getString(R.string.new_password_required)
                        }
                        !MasterPasswordPolicy.meetsMinLength(newPassword) -> {
                            errorMessage = context.getString(R.string.password_too_short)
                        }
                        newPassword != confirmPassword -> {
                            errorMessage = context.getString(R.string.passwords_do_not_match)
                        }
                        else -> {
                            if (!sessionCurrent()) return@Button
                            isLoading = true
                            errorMessage = ""
                            
                            val current = currentPassword
                            val replacement = newPassword
                            val submittedAttempt = SecurityManager.LocalPasswordChangeAttempt()
                            attempt = submittedAttempt
                            clearPasswords()
                            operation = scope.launch {
                                try {
                                    val saved = BoundedSecureDataRead.readChecked(isCurrent = ::sessionCurrent) {
                                        if (skipCurrentPassword) {
                                            authorized.value(replacement, submittedAttempt) { ensureActive() }
                                        } else {
                                            reset.value(current, replacement, submittedAttempt) { ensureActive() }
                                        }
                                    }
                                    if (!sessionCurrent()) return@launch
                                    if (saved == true) showSuccessDialog = true
                                    else errorMessage = context.getString(R.string.local_password_protection_reset_failed)
                                } finally {
                                    submittedAttempt.cancel()
                                    if (attempt === submittedAttempt) attempt = null
                                    if (sessionCurrent()) isLoading = false
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("reset-password-submit"),
                enabled = !isLoading && 
                         (skipCurrentPassword || currentPassword.isNotEmpty()) && 
                         newPassword.isNotEmpty() && 
                         confirmPassword.isNotEmpty()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(context.getString(R.string.reset_password))
            }
        }
    }
    
    // Success Dialog
    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = ::finish,
            icon = {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(context.getString(R.string.password_reset_success))
            },
            text = {
                Text(context.getString(R.string.password_reset_success_message))
            },
            confirmButton = {
                TextButton(
                    onClick = ::finish,
                    modifier = Modifier.testTag("reset-password-success"),
                ) {
                    Text(context.getString(R.string.ok))
                }
            }
        )
    }
}

