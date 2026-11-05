package takagi.ru.monica.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.security.BoundedSecureDataRead
import takagi.ru.monica.security.SecurityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.ThemeMode
import takagi.ru.monica.security.MasterPasswordPolicy
import takagi.ru.monica.ui.theme.MonicaTheme

@Composable
fun MonicaPasswordDialogAuthScreen(
    settingsFlow: Flow<AppSettings>,
    title: String,
    subtitle: String,
    passwordLabel: String,
    description: String,
    confirmText: String,
    cancelText: String,
    emptyError: String,
    unsupportedCharacterError: String,
    minLengthError: String,
    incorrectError: String,
    verifyPassword: (String, SecurityManager.PasswordVerificationAttempt) -> Boolean,
    onSuccess: (Long) -> Unit,
    onCancel: () -> Unit,
    appName: String
) {
    val settings by settingsFlow.collectAsState(initial = AppSettings(themeMode = ThemeMode.DARK, biometricEnabled = false))
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDarkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MonicaTheme(
        darkTheme = darkTheme,
        colorScheme = settings.colorScheme,
        customPrimaryColor = settings.customPrimaryColor,
        customSecondaryColor = settings.customSecondaryColor,
        customTertiaryColor = settings.customTertiaryColor,
        customNeutralColor = settings.customNeutralColor,
        customNeutralVariantColor = settings.customNeutralVariantColor
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            MonicaPasswordDialogAuthCard(
                appName = appName,
                title = title,
                subtitle = subtitle,
                passwordLabel = passwordLabel,
                description = description,
                confirmText = confirmText,
                cancelText = cancelText,
                emptyError = emptyError,
                unsupportedCharacterError = unsupportedCharacterError,
                minLengthError = minLengthError,
                incorrectError = incorrectError,
                verifyPassword = verifyPassword,
                onSuccess = onSuccess,
                onCancel = onCancel
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MonicaPasswordDialogAuthCard(
    appName: String,
    title: String,
    subtitle: String,
    passwordLabel: String,
    description: String,
    confirmText: String,
    cancelText: String,
    emptyError: String,
    unsupportedCharacterError: String,
    minLengthError: String,
    incorrectError: String,
    verifyPassword: (String, SecurityManager.PasswordVerificationAttempt) -> Boolean,
    onSuccess: (Long) -> Unit,
    onCancel: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val verify = rememberUpdatedState(verifyPassword)
    val success = rememberUpdatedState(onSuccess)
    val cancel = rememberUpdatedState(onCancel)
    var attempt by remember { mutableStateOf<SecurityManager.PasswordVerificationAttempt?>(null) }
    var verificationJob by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }

    fun revokePending() {
        attempt?.cancel()
        attempt = null
        verificationJob?.cancel()
        verificationJob = null
        busy = false
        password = ""
        passwordVisible = false
    }

    fun cancelDialog() {
        if (ended) return
        ended = true
        revokePending()
        cancel.value()
    }

    BackHandler { cancelDialog() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) cancelDialog()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            ended = true
            revokePending()
        }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .wrapContentHeight(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 8.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = appName,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            MasterPasswordTextField(
                value = password,
                enabled = !busy && !ended,
                saveTextState = false,
                onValueChange = { input ->
                    password = input
                    errorMessage = null
                },
                onUnsupportedCharacterAttempt = {
                    errorMessage = unsupportedCharacterError
                },
                label = { Text(passwordLabel) },
                placeholder = { Text(description) },
                visible = passwordVisible,
                onVisibilityChange = { passwordVisible = it },
                imeAction = ImeAction.Done,
                isError = errorMessage != null,
                supportingText = if (errorMessage != null) {
                    { Text(text = errorMessage.orEmpty()) }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    if (!busy && !ended) {
                        if (MasterPasswordPolicy.isEmpty(password)) {
                            errorMessage = emptyError
                        } else {
                            // Existing passwords retain their original length/character policy.
                            val submitted = password
                            val submittedVerifier = verify.value
                            password = ""
                            passwordVisible = false
                            keyboardController?.hide()
                            val pending = SecurityManager.PasswordVerificationAttempt()
                            attempt = pending
                            busy = true
                            verificationJob = scope.launch {
                                try {
                                    val verified = BoundedSecureDataRead.readChecked(isCurrent = pending::isCurrent) {
                                        submittedVerifier(submitted, pending)
                                    }
                                    if (ended || attempt !== pending) return@launch
                                    val foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                        context.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true &&
                                        context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == false
                                    if (verified == true && foreground && pending.commit()) {
                                        busy = false
                                        attempt = null
                                        ended = true
                                        success.value(pending.generation)
                                    } else {
                                        errorMessage = if (verified == false && pending.isCurrent()) incorrectError
                                            else context.getString(R.string.secure_startup_title)
                                    }
                                } finally {
                                    pending.cancel()
                                    if (attempt === pending) {
                                        attempt = null
                                        busy = false
                                    }
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = password.isNotEmpty() && !busy && !ended,
                shape = RoundedCornerShape(28.dp)
            ) {
                Text(text = confirmText, modifier = Modifier.padding(vertical = 4.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = { cancelDialog() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text(text = cancelText)
            }
        }
    }
}
