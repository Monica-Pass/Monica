package takagi.ru.monica.utils

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import takagi.ru.monica.R

/**
 * Fast path for the system identity prompt used by Monica's unlock surfaces.
 *
 * The prompt itself remains fully owned by Android/Google's biometric stack.
 * This helper only removes application-side work from the tap-to-prompt path:
 * - a single BiometricManager instance replaces legacy FingerprintManager probes;
 * - PromptInfo is cached; each request owns an isolated AndroidX prompt lifecycle;
 * - only strong biometrics are accepted; the negative button returns to Monica's password UI.
 */
class BiometricAuthHelper(
    context: Context
) {
    companion object {
        private const val TAG = "BiometricAuthHelper"
        private val owners = WeakHashMap<FragmentActivity, WeakReference<BiometricAuthHelper>>()
    }

    private data class PromptKey(
        val title: String,
        val subtitle: String?,
        val description: String?,
        val negativeButtonText: String
    )

    private data class AuthenticationCallbacks(
        val onSuccess: () -> Unit,
        val onError: (errorCode: Int, errorMessage: String) -> Unit,
        val onCancel: () -> Unit,
        val onNegativeButton: (() -> Unit)?
    )

    private val appContext = context.applicationContext
    private val strings = AppLocaleStringResolver(appContext)
    private val biometricManager = BiometricManager.from(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val vivoHelper by lazy(LazyThreadSafetyMode.NONE) { VivoFingerprintHelper(appContext) }

    private var promptInfoKey: PromptKey? = null
    private var promptInfo: BiometricPrompt.PromptInfo? = null
    private var request: Request? = null

    private class Request(
        val activity: FragmentActivity,
        val callbacks: AuthenticationCallbacks,
        val info: BiometricPrompt.PromptInfo
    ) {
        var armed = false
        var host: BiometricPromptHostFragment? = null
        var prompt: BiometricPrompt? = null
        var observer: LifecycleEventObserver? = null
    }

    /** Strong biometric availability only; preserves the existing settings semantics. */
    fun isBiometricAvailable(): Boolean {
        val result = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (result == BiometricManager.BIOMETRIC_SUCCESS && VivoFingerprintHelper.isVivoDevice()) {
            Log.d(TAG, "Vivo device detected: ${VivoFingerprintHelper.getDeviceInfo()}")
            Log.d(TAG, "Has under-display fingerprint: ${vivoHelper.hasUnderDisplayFingerprint()}")
        }
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun isStrongBiometricAvailable(): Boolean = isBiometricAvailable()

    fun isWeakBiometricOnly(): Boolean {
        val weak = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        val strong = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return weak == BiometricManager.BIOMETRIC_SUCCESS && strong != BiometricManager.BIOMETRIC_SUCCESS
    }

    fun isBiometricEnrolled(): Boolean = isBiometricAvailable()

    fun getBiometricStatusMessage(): String {
        val result = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        val baseMessage = when (result) {
            BiometricManager.BIOMETRIC_SUCCESS -> strings.get(R.string.biometric_available)
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> strings.get(R.string.biometric_no_hardware)
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> strings.get(R.string.biometric_hw_unavailable)
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> strings.get(R.string.biometric_none_enrolled)
            else -> strings.get(R.string.biometric_not_available)
        }

        return if (
            result == BiometricManager.BIOMETRIC_SUCCESS &&
            VivoFingerprintHelper.isVivoDevice() &&
            vivoHelper.hasUnderDisplayFingerprint()
        ) {
            strings.get(R.string.legacy_ui_biometric_under_display_status, baseMessage)
        } else {
            baseMessage
        }
    }

    fun getOptimizationTips(): List<String> {
        return if (VivoFingerprintHelper.isVivoDevice()) {
            vivoHelper.getOptimizationTips()
        } else {
            emptyList()
        }
    }

    fun getDebugInfo(): String {
        return if (VivoFingerprintHelper.isVivoDevice()) {
            vivoHelper.getDebugInfo()
        } else {
            "非 vivo 设备"
        }
    }

    /**
     * Prepares prompt text without registering a callback on an Activity's retained ViewModel.
     * Safe to call from an unlock surface as soon as its Activity is ready.
     */
    @MainThread
    fun prepare(activity: FragmentActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        promptInfoFor(
            title = strings.get(R.string.biometric_login_title),
            subtitle = strings.get(R.string.biometric_login_subtitle),
            description = strings.get(R.string.biometric_login_description),
            negativeButtonText = strings.get(R.string.use_password)
        )
    }

    @MainThread
    fun authenticate(
        activity: FragmentActivity,
        title: String = strings.get(R.string.biometric_login_title),
        subtitle: String? = strings.get(R.string.biometric_login_subtitle),
        description: String? = strings.get(R.string.biometric_login_description),
        negativeButtonText: String = strings.get(R.string.use_password),
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errorMessage: String) -> Unit,
        onCancel: () -> Unit,
        onNegativeButton: (() -> Unit)? = null,
    ) {
        cancelAuthentication()
        owners[activity]?.get()?.takeIf { it !== this }?.cancelAuthentication()
        val pending = Request(
            activity,
            AuthenticationCallbacks(onSuccess, onError, onCancel, onNegativeButton),
            promptInfoFor(title, subtitle, description, negativeButtonText)
        )
        request = pending
        owners[activity] = WeakReference(this)
        pending.observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                if (request === pending) cancelAuthentication()
            }
        }.also { activity.lifecycle.addObserver(it) }
        // Callers may request this during onCreate. Defer until that lifecycle dispatch and any
        // FragmentManager transaction finish; never retain a request waiting for a later resume.
        mainHandler.post { launch(pending) }
    }

    /** Cancel a request whose owning screen has gone away, including its callbacks. */
    @MainThread
    fun cancelAuthentication() {
        request?.let { retire(it, cancelPrompt = true) }
    }

    private fun ready(activity: FragmentActivity): Boolean =
        !activity.isFinishing && !activity.isDestroyed &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !activity.supportFragmentManager.isStateSaved

    private fun launch(pending: Request) {
        if (request !== pending) return
        if (!ready(pending.activity)) {
            retire(pending, cancelPrompt = true)
            pending.callbacks.onCancel()
            return
        }
        try {
            val host = BiometricPromptHostFragment()
            pending.host = host
            host.onHostDestroyed = {
                if (request === pending) retire(pending, cancelPrompt = false)
            }
            pending.activity.supportFragmentManager.beginTransaction()
                .add(host, "monica.biometric.request")
                .commitNow()
            if (request !== pending) return
            if (!ready(pending.activity) || !host.isAdded) {
                retire(pending, cancelPrompt = true)
                pending.callbacks.onCancel()
                return
            }

            // AndroidX activity prompts share a retained ViewModel. A previous result can remove
            // the next Fragment during authenticate(), or be delivered to its new callback.
            // A fresh public Fragment host gives this request its own ViewModel and child manager.
            val prompt = BiometricPrompt(
                host,
                { command -> mainHandler.post { if (isCurrent(pending)) command.run() } },
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (!isCurrent(pending)) return
                        val current = pending.callbacks
                        retire(pending, cancelPrompt = false)
                        if (BiometricAuthenticationPolicy.acceptsResult(result.authenticationType, Build.VERSION.SDK_INT)) {
                            current.onSuccess()
                        } else {
                            current.onError(BiometricPrompt.ERROR_UNABLE_TO_PROCESS, strings.get(R.string.biometric_not_available))
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!isCurrent(pending)) return
                        val current = pending.callbacks
                        retire(pending, cancelPrompt = false)
                        when (errorCode) {
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON -> (current.onNegativeButton ?: current.onCancel)()
                            BiometricPrompt.ERROR_USER_CANCELED -> current.onCancel()
                            else -> current.onError(errorCode, errString.toString())
                        }
                    }

                    override fun onAuthenticationFailed() {
                        // Keep the native prompt open; Android owns retry/lockout policy.
                    }
                }
            )
            pending.prompt = prompt
            prompt.authenticate(pending.info)
            pending.armed = request === pending
        } catch (_: IllegalStateException) {
            // Fragment attachment/state changes must return to password verification, not crash
            // or accept a callback queued by a partially started prompt.
            val notify = request === pending && ready(pending.activity)
            retire(pending, cancelPrompt = true)
            if (notify) pending.callbacks.onError(
                BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
                strings.get(R.string.biometric_not_available)
            )
        }
    }

    private fun isCurrent(pending: Request): Boolean =
        request === pending && pending.armed && ready(pending.activity)

    private fun retire(pending: Request, cancelPrompt: Boolean) {
        pending.armed = false
        if (request === pending) {
            request = null
            if (owners[pending.activity]?.get() === this) owners.remove(pending.activity)
        }
        pending.observer?.let { pending.activity.lifecycle.removeObserver(it) }
        pending.observer = null
        if (cancelPrompt) {
            try {
                pending.prompt?.cancelAuthentication()
            } catch (_: IllegalStateException) {
                // The host may already have been removed by FragmentManager.
            }
        }
        pending.prompt = null
        pending.host?.let { host ->
            host.onHostDestroyed = null
            if (host.isAdded && !pending.activity.supportFragmentManager.isDestroyed) {
                pending.activity.supportFragmentManager.beginTransaction()
                    .remove(host).commitAllowingStateLoss()
            }
        }
        pending.host = null
    }

    private fun promptInfoFor(
        title: String,
        subtitle: String?,
        description: String?,
        negativeButtonText: String
    ): BiometricPrompt.PromptInfo {
        val key = PromptKey(title, subtitle, description, negativeButtonText)
        if (promptInfoKey == key) {
            promptInfo?.let { return it }
        }

        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(BiometricAuthenticationPolicy.authenticators)
            .setConfirmationRequired(false)
            .apply {
                if (subtitle != null) setSubtitle(subtitle)
                if (description != null) setDescription(description)
            }

        AppLauncherIconManager.applyBiometricPromptBranding(appContext, builder)

        builder.setNegativeButtonText(negativeButtonText)

        return builder.build().also {
            promptInfoKey = key
            promptInfo = it
        }
    }

}
