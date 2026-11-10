package takagi.ru.monica.ui.screens

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.BoundedSecureDataRead
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.security.MasterPasswordPolicy
import takagi.ru.monica.ui.components.MasterPasswordTextField
import takagi.ru.monica.ui.components.rememberBringIntoViewOnFocusModifier

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ChangePasswordScreen(
    onNavigateBack: () -> Unit,
    securityManager: SecurityManager
) {
    val context = LocalContext.current

    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    var currentPasswordVisible by remember { mutableStateOf(false) }
    var newPasswordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }

    var errorMessage by remember { mutableStateOf("") }
    var showSuccessDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val generation by SecurityManager.dataReadGeneration.collectAsState()
    val entryGeneration = remember(securityManager) { generation }
    val active = remember(securityManager) { AtomicBoolean(true) }
    var busy by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<SecurityManager.LocalPasswordChangeAttempt?>(null) }
    var work by remember { mutableStateOf<Job?>(null) }
    fun clearInputs() {
        currentPassword = ""; newPassword = ""; confirmPassword = ""
        currentPasswordVisible = false; newPasswordVisible = false; confirmPasswordVisible = false
    }
    fun revoke() {
        active.set(false)
        request?.cancel(); request = null
        work?.cancel(); work = null
        busy = false
        showSuccessDialog = false
        clearInputs()
    }
    fun leave() { if (active.get()) { revoke(); onNavigateBack() } }
    BackHandler(onBack = ::leave)
    DisposableEffect(lifecycle, securityManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) leave()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); revoke() }
    }
    LaunchedEffect(generation) { if (generation != entryGeneration) leave() }
    fun submit() {
        if (busy || !active.get() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val old = currentPassword
        val replacement = newPassword
        val attempt = SecurityManager.LocalPasswordChangeAttempt()
        request = attempt
        busy = true
        clearInputs()
        work = scope.launch {
            try {
                val accepted = BoundedSecureDataRead.readChecked(isCurrent = {
                    active.get() && securityManager.isDataReadCurrent(entryGeneration)
                }) { securityManager.resetMasterPassword(old, replacement, attempt) { ensureActive() } }
                if (!active.get() || !securityManager.isDataReadCurrent(entryGeneration) ||
                    request !== attempt || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                if (accepted == true) showSuccessDialog = true
                else errorMessage = context.getString(R.string.local_password_protection_reset_failed)
            } finally {
                attempt.cancel()
                if (request === attempt) request = null
                busy = false
            }
        }
    }

    val scrollState = rememberScrollState()
    val currentPasswordImeModifier = rememberBringIntoViewOnFocusModifier()
    val newPasswordImeModifier = rememberBringIntoViewOnFocusModifier()
    val confirmPasswordImeModifier = rememberBringIntoViewOnFocusModifier()

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

    val errorCurrentEmpty = stringResource(R.string.change_password_error_current_empty)
    val errorNewEmpty = stringResource(R.string.change_password_error_new_empty)
    val errorNewTooShort = stringResource(R.string.change_password_error_new_too_short)
    val errorConfirmEmpty = stringResource(R.string.change_password_error_confirm_empty)
    val errorNotMatch = stringResource(R.string.change_password_error_not_match)
    val errorSameAsCurrent = stringResource(R.string.change_password_error_same_as_current)
    val unsupportedCharacters = stringResource(R.string.error_password_contains_unsupported_characters)

    val showNewPasswordLengthError = newPassword.isNotEmpty() && !MasterPasswordPolicy.meetsMinLength(newPassword)
    val showPasswordMismatchError = confirmPassword.isNotEmpty() && newPassword != confirmPassword
    val canSubmit = !busy && !showSuccessDialog && currentPassword.isNotEmpty() && newPassword.isNotEmpty() && confirmPassword.isNotEmpty()

    Scaffold(
        modifier = sharedModifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.change_password_title)) },
                navigationIcon = {
                    IconButton(onClick = ::leave) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.return_text)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            ChangePasswordHeroCard()

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Text(
                        text = stringResource(R.string.change_password_warning_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    MasterPasswordTextField(
                        value = currentPassword,
                        enabled = !busy && !showSuccessDialog,
                        onValueChange = { input ->
                            currentPassword = input
                            errorMessage = ""
                        },
                        onUnsupportedCharacterAttempt = {
                            errorMessage = unsupportedCharacters
                        },
                        label = { Text(stringResource(R.string.change_password_current)) },
                        visible = currentPasswordVisible,
                        onVisibilityChange = { currentPasswordVisible = it },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(currentPasswordImeModifier).testTag("change-password-current"),
                        imeAction = ImeAction.Next,
                        isError = errorMessage == errorCurrentEmpty
                    )

                    MasterPasswordTextField(
                        value = newPassword,
                        enabled = !busy && !showSuccessDialog,
                        onValueChange = { input ->
                            newPassword = input
                            errorMessage = ""
                        },
                        onUnsupportedCharacterAttempt = {
                            errorMessage = unsupportedCharacters
                        },
                        label = { Text(stringResource(R.string.change_password_new)) },
                        visible = newPasswordVisible,
                        onVisibilityChange = { newPasswordVisible = it },
                        leadingIcon = {
                            Icon(Icons.Default.VpnKey, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(newPasswordImeModifier).testTag("change-password-new"),
                        imeAction = ImeAction.Next,
                        isError = showNewPasswordLengthError ||
                            errorMessage == errorNewEmpty ||
                            errorMessage == errorNewTooShort ||
                            errorMessage == errorSameAsCurrent,
                        supportingText = {
                            Text(
                                text = if (showNewPasswordLengthError) {
                                    errorNewTooShort
                                } else {
                                    stringResource(R.string.change_password_hint)
                                }
                            )
                        }
                    )

                    MasterPasswordTextField(
                        value = confirmPassword,
                        enabled = !busy && !showSuccessDialog,
                        onValueChange = { input ->
                            confirmPassword = input
                            errorMessage = ""
                        },
                        onUnsupportedCharacterAttempt = {
                            errorMessage = unsupportedCharacters
                        },
                        label = { Text(stringResource(R.string.change_password_confirm)) },
                        visible = confirmPasswordVisible,
                        onVisibilityChange = { confirmPasswordVisible = it },
                        leadingIcon = {
                            Icon(Icons.Default.VpnKey, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(confirmPasswordImeModifier).testTag("change-password-confirm"),
                        imeAction = ImeAction.Done,
                        isError = showPasswordMismatchError ||
                            errorMessage == errorConfirmEmpty ||
                            errorMessage == errorNotMatch
                    )

                    ChangePasswordMessageCard(
                        title = stringResource(R.string.change_password_warning_title),
                        message = stringResource(R.string.change_password_warning_message),
                        isError = false
                    )

                    if (errorMessage.isNotEmpty()) {
                        ChangePasswordMessageCard(
                            title = stringResource(R.string.error_authentication_failed),
                            message = errorMessage,
                            isError = true
                        )
                    }

                    Button(
                        onClick = {
                            when {
                                currentPassword.isEmpty() -> {
                                    errorMessage = errorCurrentEmpty
                                }
                                newPassword.isEmpty() -> {
                                    errorMessage = errorNewEmpty
                                }
                                confirmPassword.isEmpty() -> {
                                    errorMessage = errorConfirmEmpty
                                }
                                newPassword != confirmPassword -> {
                                    errorMessage = errorNotMatch
                                }
                                !MasterPasswordPolicy.meetsMinLength(newPassword) -> {
                                    errorMessage = errorNewTooShort
                                }
                                currentPassword == newPassword -> {
                                    errorMessage = errorSameAsCurrent
                                }
                                else -> {
                                    submit()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp).testTag("change-password-submit"),
                        shape = RoundedCornerShape(24.dp),
                        enabled = canSubmit
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(
                            text = stringResource(if (busy) R.string.loading else R.string.change_password_button),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }

    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = { },
            icon = {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(stringResource(R.string.change_password_success_title)) },
            text = { Text(stringResource(R.string.change_password_success_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        leave()
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }
}

@Composable
private fun ChangePasswordHeroCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.change_password_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = stringResource(R.string.change_password_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
                )
            }

            ChangePasswordInfoPill(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                text = stringResource(R.string.change_password_supports_unicode)
            )
        }
    }
}

@Composable
private fun ChangePasswordInfoPill(
    icon: @Composable () -> Unit,
    text: String
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            icon()
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ChangePasswordMessageCard(
    title: String,
    message: String,
    isError: Boolean
) {
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f)
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (isError) Icons.Default.Warning else Icons.Default.Info,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.padding(top = 2.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
            }
        }
    }
}
