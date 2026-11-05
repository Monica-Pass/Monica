package takagi.ru.monica.ime

import takagi.ru.monica.security.launchWithSecureStorage

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.DeveloperVerificationPolicy
import takagi.ru.monica.ui.components.MonicaPasswordDialogAuthScreen
import takagi.ru.monica.utils.BiometricAuthHelper
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.StartupLanguageCache

class ImeUnlockActivity : AppCompatActivity() {

    private var resultPublished = false
    private var startupAutoLockMinutes: Int = 5
    private var passwordFallbackScheduled = false
    private var authSurface: AuthSurface = AuthSurface.NONE
    private lateinit var securityManager: SecurityManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var biometricAuthHelper: BiometricAuthHelper

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchWithSecureStorage { onSecureCreate(savedInstanceState) }
    }

    private fun onSecureCreate(savedInstanceState: Bundle?) {

        window?.setBackgroundDrawableResource(android.R.color.transparent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            findViewById<View?>(android.R.id.content)?.importantForAutofill =
                View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }

        securityManager = SecurityManager(applicationContext)
        settingsManager = SettingsManager(applicationContext)
        biometricAuthHelper = BiometricAuthHelper(this)

        val startupSettings = runCatching {
            runBlocking { settingsManager.settingsFlow.first() }
        }.getOrDefault(takagi.ru.monica.data.AppSettings())
        startupAutoLockMinutes = startupSettings.autoLockMinutes
        if (DeveloperVerificationPolicy.bypassesIdentityVerification(startupSettings)) {
            securityManager.unlockVaultForDeveloperBypass(startupAutoLockMinutes)
            publishResult(success = true, errorMessage = null)
            return
        }
        if (!securityManager.isMasterPasswordSet() ||
            securityManager.canAccessVaultNowStrict(this, startupAutoLockMinutes)
        ) {
            publishResult(success = true, errorMessage = null)
            return
        }

        lifecycleScope.launch {
            val settings = settingsManager.settingsFlow.first()
            if (settings.biometricEnabled && biometricAuthHelper.isBiometricAvailable()) {
                showBiometricAuthentication()
            } else {
                showPasswordAuthentication()
            }
        }
    }

    override fun onDestroy() {
        authSurface = AuthSurface.NONE
        if (!resultPublished && !isChangingConfigurations) {
            publishResult(success = false, errorMessage = null)
        }
        super.onDestroy()
    }

    private fun showBiometricAuthentication() {
        if (resultPublished || authSurface != AuthSurface.NONE) return
        authSurface = AuthSurface.BIOMETRIC
        biometricAuthHelper.authenticate(
            activity = this,
            title = getString(R.string.ime_unlock_title),
            subtitle = getString(R.string.autofill_auth_subtitle),
            description = getString(R.string.ime_unlock_in_app_message),
            onSuccess = {
                val unlocked = runCatching { securityManager.unlockVaultWithBiometric() }.getOrDefault(false)
                if (unlocked) {
                    securityManager.markSecondaryVaultAuthenticated(startupAutoLockMinutes)
                    publishResult(success = true, errorMessage = null)
                } else {
                    schedulePasswordAuthentication()
                }
            },
            onError = { _, _ ->
                schedulePasswordAuthentication()
            },
            onCancel = {
                schedulePasswordAuthentication()
            }
        )
    }

    private fun schedulePasswordAuthentication() {
        if (resultPublished || passwordFallbackScheduled) return
        passwordFallbackScheduled = true
        authSurface = AuthSurface.TRANSITIONING
        window?.decorView?.postDelayed({
            if (!resultPublished && !isFinishing && !isDestroyed) {
                showPasswordAuthentication()
            }
        }, PASSWORD_FALLBACK_DELAY_MS)
    }

    private fun showPasswordAuthentication() {
        if (resultPublished || authSurface == AuthSurface.PASSWORD) return
        authSurface = AuthSurface.PASSWORD
        setContent {
            MonicaPasswordDialogAuthScreen(
                settingsFlow = settingsManager.settingsFlow,
                appName = getString(R.string.app_name),
                title = getString(R.string.ime_unlock_title),
                subtitle = getString(R.string.ime_unlock_in_app_message),
                passwordLabel = getString(R.string.ime_unlock_password_label),
                description = getString(R.string.enter_master_password),
                confirmText = getString(R.string.unlock),
                cancelText = getString(R.string.cancel),
                emptyError = getString(R.string.current_password_required),
                unsupportedCharacterError = getString(R.string.error_password_contains_unsupported_characters),
                minLengthError = getString(R.string.error_password_too_short),
                incorrectError = getString(R.string.ime_unlock_error),
                verifyPassword = { input, attempt -> securityManager.unlockVaultWithPassword(input, attempt) },
                onSuccess = {
                    securityManager.markSecondaryVaultAuthenticated(startupAutoLockMinutes)
                    publishResult(success = true, errorMessage = null)
                },
                onCancel = { publishResult(success = false, errorMessage = null) }
            )
        }
    }

    private fun publishResult(success: Boolean, errorMessage: String?) {
        if (resultPublished) return
        resultPublished = true
        sendBroadcast(
            Intent(MonicaInputMethodService.ACTION_IME_BIOMETRIC_RESULT).apply {
                setPackage(packageName)
                putExtra(MonicaInputMethodService.EXTRA_IME_BIOMETRIC_SUCCESS, success)
                putExtra(MonicaInputMethodService.EXTRA_IME_BIOMETRIC_ERROR, errorMessage)
            }
        )
        finish()
    }

    private enum class AuthSurface {
        NONE,
        BIOMETRIC,
        TRANSITIONING,
        PASSWORD
    }

    companion object {
        private const val PASSWORD_FALLBACK_DELAY_MS = 300L
    }
}
