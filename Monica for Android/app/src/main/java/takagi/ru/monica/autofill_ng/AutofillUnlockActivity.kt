package takagi.ru.monica.autofill_ng

import takagi.ru.monica.security.launchWithSecureStorage

import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.StartupLanguageCache
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.auth.AutofillSessionGrants
import takagi.ru.monica.autofill_ng.auth.AutofillUnlockRequests
import takagi.ru.monica.autofill_ng.builder.FillResponseBuilderNg
import takagi.ru.monica.autofill_ng.builder.FilledDataBuilderNg
import takagi.ru.monica.autofill_ng.core.AutofillLogger
import takagi.ru.monica.autofill_ng.model.AutofillRequest
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.DeveloperVerificationPolicy
import takagi.ru.monica.ui.components.MonicaPasswordDialogAuthScreen
import takagi.ru.monica.utils.BiometricAuthHelper
import takagi.ru.monica.utils.SettingsManager

class AutofillUnlockActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_REQUEST_TOKEN = "extra_request_token"

        fun getIntent(context: Context, requestToken: String): Intent =
            Intent(context, AutofillUnlockActivity::class.java).apply {
                putExtra(EXTRA_REQUEST_TOKEN, requestToken)
            }
    }

    private lateinit var securityManager: SecurityManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var biometricAuthHelper: BiometricAuthHelper
    private var requestToken: String? = null
    private var biometricPromptShown = false
    private var resultPublished = false
    private var nativeReadStarted = false
    private var requestRevoked = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchWithSecureStorage { onSecureCreate(savedInstanceState) }
    }

    private fun onSecureCreate(savedInstanceState: Bundle?) {
        overridePendingTransition(0, 0)
        setContentView(R.layout.activity_transparent)

        requestToken = intent.getStringExtra(EXTRA_REQUEST_TOKEN)
        val pendingRequest = AutofillUnlockRequests.peek(requestToken)
        if (pendingRequest == null) {
            cancelAndFinish("missing_or_expired_request")
            return
        }

        lifecycleScope.launch {
            if (rejectBlockedRequest(pendingRequest.request)) return@launch
            securityManager = SecurityManager(applicationContext)
            settingsManager = SettingsManager(applicationContext)
            biometricAuthHelper = BiometricAuthHelper(this@AutofillUnlockActivity)
            val generation = SecurityManager.dataReadGeneration.value
            val settings = settingsManager.settingsFlow.first()
            if (DeveloperVerificationPolicy.bypassesIdentityVerification(settings)) {
                securityManager.unlockVaultForDeveloperBypass(settings.autoLockMinutes)
                completeUnlock(authenticationVerified = false, generation = generation)
            } else if (settings.autofillKeepUnlocked && AutofillSessionGrants.isGranted(pendingRequest.grantContext)) {
                completeUnlock(authenticationVerified = false, generation = generation)
            } else if (!settings.autofillAuthRequired) {
                completeUnlock(authenticationVerified = false, generation = generation)
            } else if (settings.biometricEnabled && biometricAuthHelper.isBiometricAvailable()) {
                showBiometricPrompt()
            } else {
                showPasswordAuthentication()
            }
        }
    }

    private fun showBiometricPrompt() {
        if (biometricPromptShown || resultPublished || isFinishing || isDestroyed) return
        val generation = SecurityManager.dataReadGeneration.value
        biometricPromptShown = true
        AutofillLogger.i("AUTH", "Showing response-level autofill biometric prompt")

        runCatching {
            biometricAuthHelper.authenticate(
                activity = this,
                title = getString(R.string.autofill_auth_title),
                subtitle = getString(R.string.autofill_auth_subtitle),
                description = getString(R.string.autofill_auth_description),
                negativeButtonText = getString(R.string.use_password),
                onSuccess = {
                    lifecycleScope.launch {
                        val unlocked = takagi.ru.monica.security.BoundedSecureDataRead.read(securityManager, generation) {
                            securityManager.unlockVaultWithBiometric()
                        } == true
                        if (unlocked) completeUnlock(authenticationVerified = true, generation = generation)
                        else showPasswordAuthentication()
                    }
                },
                onError = { code, message ->
                    AutofillLogger.w("AUTH", "Autofill biometric error: $code $message")
                    showPasswordAuthentication()
                },
                onCancel = { cancelAndFinish("authentication_cancelled") },
                onNegativeButton = { showPasswordAuthentication() },
            )
        }.onFailure { error ->
            biometricPromptShown = false
            AutofillLogger.w(
                "AUTH",
                "Unable to show autofill biometric prompt: ${error.message.orEmpty()}"
            )
            showPasswordAuthentication()
        }
    }

    private fun showPasswordAuthentication() {
        setContent {
            MonicaPasswordDialogAuthScreen(
                settingsFlow = settingsManager.settingsFlow,
                appName = getString(R.string.app_name),
                title = getString(R.string.verify_identity),
                subtitle = getString(R.string.autofill_auth_subtitle),
                passwordLabel = getString(R.string.master_password),
                description = getString(R.string.enter_master_password),
                confirmText = getString(R.string.confirm),
                cancelText = getString(R.string.cancel),
                emptyError = getString(R.string.current_password_required),
                unsupportedCharacterError = getString(R.string.error_password_contains_unsupported_characters),
                minLengthError = getString(R.string.error_password_too_short),
                incorrectError = getString(R.string.password_incorrect),
                verifyPassword = { input, attempt -> securityManager.unlockVaultWithPassword(input, attempt) },
                onSuccess = { generation ->
                    lifecycleScope.launch { completeUnlock(authenticationVerified = true, generation = generation) }
                },
                onCancel = { cancelAndFinish("authentication_cancelled") },
            )
        }
    }

    private suspend fun completeUnlock(authenticationVerified: Boolean, generation: Long) {
        if (resultPublished) return
        if (!securityManager.isDataReadCurrent(generation)) { cancelAndFinish("authentication_revoked"); return }
        val pendingRequest = AutofillUnlockRequests.consume(requestToken) ?: run {
            cancelAndFinish("missing_or_consumed_request")
            return
        }
        if (rejectBlockedRequest(pendingRequest.request)) return

        lifecycle.withResumed { Unit }
        val needsNative = pendingRequest.passwordIds.any(takagi.ru.monica.keepass.KeePassMonicaPasswordStore::isUiId)
        nativeReadStarted = needsNative
        val source = if (needsNative) AutofillPasswordSource(
            context = applicationContext, security = securityManager, generation = generation,
            isAuthorized = { !requestRevoked && canPublishResponse(generation) },
            allowLockedDatabases = authenticationVerified,
        ) else null
        var selectedPasswords = emptyList<takagi.ru.monica.data.PasswordEntry>()
        val responseResult = runCatching {
            val passwordIdsInOrder = pendingRequest.passwordIds
            val passwords = withContext(Dispatchers.IO) {
                val entries = source?.load() ?: AutofillPasswordSource.loadRoomEntries(PasswordDatabase.getDatabase(applicationContext))
                val byId = entries.filterNot { it.isKeyCredential() }.associateBy { it.id }
                passwordIdsInOrder.mapNotNull(byId::get)
            }
            selectedPasswords = passwords
            takagi.ru.monica.security.BoundedSecureDataRead.read(securityManager, generation) {
            val postFillOtpPasswordIds = kotlinx.coroutines.runBlocking {
                    AutofillOtpActions(applicationContext).eligiblePasswordIds(passwords)
                }
            val filledData = FilledDataBuilderNg(
                context = applicationContext,
                securityManager = securityManager,
            ).build(
                request = pendingRequest.request,
                passwords = passwords,
                requireAuthentication = false,
                checkCancelled = { ensureActive() },
                nativeSelections = passwords.mapNotNull { entry -> source?.selection(entry)?.let { entry.id to it } }.toMap(),
            )
            ensureActive()
            FillResponseBuilderNg(applicationContext).build(
                request = pendingRequest.request,
                filledData = filledData,
                passwordSuggestionEnabled = pendingRequest.passwordSuggestionEnabled,
                requireAuthentication = false,
                matchedPasswords = passwords,
                postFillOtpPasswordIds = postFillOtpPasswordIds,
            )
                ?.let { response -> response to passwords.size } ?: error("No unlocked autofill response")
            } ?: error("Autofill response preparation unavailable")
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            AutofillLogger.e("AUTH", "Unable to build unlocked autofill response", error)
        }.getOrNull() ?: run {
            AutofillSessionGrants.clear()
            cancelAndFinish("unable_to_build_unlocked_response")
            return
        }
        val (response, passwordCount) = responseResult
        if (rejectBlockedRequest(pendingRequest.request)) return

        if (authenticationVerified) {
            val settings = settingsManager.settingsFlow.first()
            if (!canPublishResponse(generation)) { cancelAndFinish("authentication_revoked"); return }
            AutofillSessionGrants.grant(pendingRequest.grantContext,
                enabled = settings.autofillKeepUnlocked && settings.autofillAuthRequired)
        }

        if (!canPublishResponse(generation) || selectedPasswords.any {
            AutofillPasswordSource.isNative(it) && source?.isCurrent(it) != true
        }) { cancelAndFinish("authentication_revoked"); return }
        resultPublished = true
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response)
            }
        )
        AutofillLogger.i(
            "AUTH",
            "Returning unlocked autofill response",
            metadata = mapOf(
                "passwordCount" to passwordCount,
                "packageName" to pendingRequest.grantContext.packageName,
                "webDomain" to (pendingRequest.grantContext.webDomain ?: "none"),
            )
        )
        finishWithoutAnimation()
    }

    private fun canPublishResponse(generation: Long): Boolean =
        !requestRevoked && !resultPublished && !isFinishing && !isDestroyed &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
            getSystemService(android.os.PowerManager::class.java)?.isInteractive == true &&
            getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == false &&
            securityManager.isDataReadCurrent(generation)

    private suspend fun rejectBlockedRequest(
        request: AutofillRequest.Fillable,
    ): Boolean {
        if (!isAutofillRequestBlocked(applicationContext, request.fieldSignatureKey)) return false
        AutofillUnlockRequests.discard(requestToken)
        resultPublished = true
        AutofillLogger.i("AUTH", "Discard blocked cached autofill request")
        finishBlockedAutofillRequest(request.partition.views.map { it.data.autofillId } + request.ignoreAutofillIds)
        return true
    }

    override fun onStop() {
        if (nativeReadStarted && !resultPublished) requestRevoked = true
        super.onStop()
    }

    private fun cancelAndFinish(reason: String) {
        if (resultPublished) return
        resultPublished = true
        AutofillLogger.w("AUTH", "Cancel response-level autofill unlock: $reason")
        setResult(Activity.RESULT_CANCELED)
        finishWithoutAnimation()
    }

    private fun finishWithoutAnimation() {
        finish()
        overridePendingTransition(0, 0)
    }
}
