package takagi.ru.monica.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import takagi.ru.monica.ui.components.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.security.SecurityManager

import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager
import android.content.ContextWrapper
import takagi.ru.monica.security.BoundedSecureDataRead
import takagi.ru.monica.security.RecoveryChallenge
import takagi.ru.monica.security.RecoveryPermit
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private fun android.content.Context.recoveryActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.recoveryActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityQuestionsVerificationScreen(
    securityManager: SecurityManager,
    onNavigateBack: () -> Unit,
    onVerificationSuccess: (RecoveryPermit) -> Unit,
) {
    val context = LocalContext.current
    val activity = context.recoveryActivity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val generation by SecurityManager.dataReadGeneration.collectAsState()
    val entryGeneration = remember { generation }
    val active = remember { AtomicBoolean(true) }
    var answer1 by remember { mutableStateOf("") }
    var answer2 by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var retryAfter by remember { mutableStateOf(0L) }
    var prompt by remember { mutableStateOf<BiometricPrompt?>(null) }
    var challenge by remember { mutableStateOf<RecoveryChallenge?>(null) }
    val back = rememberUpdatedState(onNavigateBack)
    val success = rememberUpdatedState(onVerificationSuccess)
    fun current() = active.get() && securityManager.isDataReadCurrent(entryGeneration)
    fun cancel() {
        active.set(false)
        challenge?.let(securityManager::cancelRecovery)
        challenge = null
        prompt?.cancelAuthentication()
        prompt = null
        answer1 = ""; answer2 = ""
    }
    fun leave() { if (active.get()) { cancel(); back.value() } }
    BackHandler(onBack = ::leave)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) leave()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancel() }
    }
    LaunchedEffect(generation) { if (generation != entryGeneration) leave() }
    LaunchedEffect(Unit) {
        retryAfter = BoundedSecureDataRead.readChecked(isCurrent = ::current) {
            securityManager.recoveryAttemptStatus().retryAfterMillis
        } ?: 900_000L
    }
    if (!active.get()) return
    Scaffold(topBar = {
        CenterAlignedTopAppBar(title = { Text(stringResource(R.string.verify_identity)) }, navigationIcon = {
            IconButton(onClick = ::leave) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .imePadding().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SecurityQuestionVerificationHero()
            Text(stringResource(R.string.recovery_device_required), style = MaterialTheme.typography.bodyMedium)
            AnswerSecurityQuestionCard(stringResource(R.string.question_1), securityManager.getSecurityQuestion1Text().orEmpty(),
                answer1, { answer1 = it }, !isLoading, "recovery-answer-1")
            AnswerSecurityQuestionCard(stringResource(R.string.question_2), securityManager.getSecurityQuestion2Text().orEmpty(),
                answer2, { answer2 = it }, !isLoading, "recovery-answer-2")
            if (errorMessage.isNotEmpty()) SecurityQuestionVerificationMessageCard(errorMessage, true)
            if (retryAfter > 0) SecurityQuestionVerificationMessageCard(stringResource(R.string.recovery_attempts_limited), true)
            Button(onClick = {
                if (!current() || activity == null) return@Button
                // Credential-only native prompts require API 30+. Older devices retain original-password
                // change and backup recovery; a biometric fallback cannot silently replace this proof.
                val available = android.os.Build.VERSION.SDK_INT >= 30 &&
                    BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
                if (!available) { errorMessage = context.getString(R.string.recovery_device_unavailable); return@Button }
                val first = answer1; val second = answer2
                answer1 = ""; answer2 = ""; errorMessage = ""; isLoading = true
                scope.launch {
                    val candidate = BoundedSecureDataRead.readChecked(isCurrent = ::current) {
                        listOfNotNull(securityManager.beginRecoveryWithAnswers(first, second) { ensureActive() })
                    }?.firstOrNull()
                    if (!current()) { candidate?.let(securityManager::cancelRecovery); return@launch }
                    if (candidate == null) {
                        retryAfter = BoundedSecureDataRead.readChecked(isCurrent = ::current) {
                            securityManager.recoveryAttemptStatus().retryAfterMillis
                        } ?: 900_000L
                        errorMessage = context.getString(R.string.incorrect_answers)
                        isLoading = false
                        return@launch
                    }
                    challenge = candidate
                    val nativePrompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                if (challenge !== candidate || !current() ||
                                    !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                                    result.authenticationType != BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL) {
                                    securityManager.cancelRecovery(candidate)
                                    return
                                }
                                val permit = securityManager.completeRecoveryDeviceCredential(candidate)
                                challenge = null; prompt = null; isLoading = false
                                if (permit == null) { errorMessage = context.getString(R.string.recovery_authorization_expired); return }
                                // Ownership transfers to the destination, then this screen is disposed.
                                active.set(false)
                                success.value(permit)
                            }
                            override fun onAuthenticationError(code: Int, message: CharSequence) {
                                securityManager.cancelRecovery(candidate)
                                if (challenge !== candidate || !current()) return
                                challenge = null; prompt = null; isLoading = false
                                errorMessage = context.getString(R.string.recovery_authorization_expired)
                            }
                        })
                    prompt = nativePrompt
                    nativePrompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                        .setTitle(context.getString(R.string.recovery_device_title))
                        .setSubtitle(context.getString(R.string.recovery_device_required))
                        .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
                }
            }, modifier = Modifier.fillMaxWidth().testTag("recovery-verify-submit"),
                enabled = !isLoading && retryAfter == 0L && answer1.trim().isNotEmpty() && answer2.trim().isNotEmpty()) {
                if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.verify_answers))
            }
            OutlinedButton(onClick = ::leave, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.back_to_login))
            }
        }
    }
}

@Composable
private fun SecurityQuestionVerificationHero() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.56f)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Text(
                text = stringResource(R.string.answer_security_questions),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Text(
                text = stringResource(R.string.recovery_device_required),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.88f)
            )
        }
    }
}

@Composable
private fun AnswerSecurityQuestionCard(
    title: String,
    question: String,
    answer: String,
    onAnswerChange: (String) -> Unit,
    enabled: Boolean,
    tag: String = "",
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.QuestionAnswer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
            ) {
                Text(
                    text = question,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(14.dp)
                )
            }

            OutlinedTextField(
                value = answer,
                onValueChange = onAnswerChange,
                modifier = Modifier.fillMaxWidth().testTag(tag),
                visualTransformation = PasswordVisualTransformation(),
                enabled = enabled,
                label = { Text(stringResource(R.string.your_answer)) },
                placeholder = { Text(stringResource(R.string.enter_answer_hint)) },
                leadingIcon = {
                    Icon(Icons.Default.HelpOutline, contentDescription = null)
                },
                shape = RoundedCornerShape(22.dp),
                singleLine = true
            )
        }
    }
}

@Composable
private fun SecurityQuestionVerificationMessageCard(
    message: String,
    isError: Boolean
) {
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
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
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (isError) Icons.Default.HelpOutline else Icons.Default.Security,
                contentDescription = null,
                tint = contentColor
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor
            )
        }
    }
}
