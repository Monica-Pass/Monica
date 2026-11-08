package takagi.ru.monica.autofill_ng.auth

import android.os.SystemClock
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import takagi.ru.monica.security.SecurityManager
import java.net.URI
import java.util.Locale

data class AutofillGrantContext(
    val packageName: String,
    val webDomain: String?,
    val interactionIdentifier: String?,
    val fieldSignatureKey: String?,
) {
    fun normalized(): AutofillGrantContext = copy(
        packageName = packageName.trim().lowercase(Locale.ROOT),
        webDomain = webDomain
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.removePrefix("www.")
            ?.trim('.')
            ?.takeIf { it.isNotBlank() },
        interactionIdentifier = interactionIdentifier
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() },
        fieldSignatureKey = fieldSignatureKey
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() },
    )

    companion object {
        fun fromRequestUri(
            packageName: String,
            requestUri: String?,
            interactionIdentifier: String?,
            fieldSignatureKey: String?,
        ): AutofillGrantContext {
            val parsedUri = requestUri
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { URI(it) }.getOrNull() }
            val webDomain = parsedUri
                ?.takeUnless { it.scheme.equals("androidapp", ignoreCase = true) }
                ?.host
            return AutofillGrantContext(
                packageName = packageName,
                webDomain = webDomain,
                interactionIdentifier = interactionIdentifier,
                fieldSignatureKey = fieldSignatureKey,
            ).normalized()
        }
    }
}

class AutofillSessionGrantStore(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private data class Grant(
        val context: AutofillGrantContext,
        val grantedAtMillis: Long,
    )

    @Volatile
    private var activeGrant: Grant? = null

    @Synchronized
    fun grant(context: AutofillGrantContext) {
        if (context.packageName.isBlank()) {
            clear()
            return
        }
        val now = elapsedRealtime()
        activeGrant = Grant(
            context = context.grantScope(),
            grantedAtMillis = now,
        )
    }

    @Synchronized
    fun isGranted(context: AutofillGrantContext): Boolean {
        val grant = activeGrant ?: return false
        val age = elapsedRealtime() - grant.grantedAtMillis
        if (age < 0 || age >= ttlMillis) {
            clear()
            return false
        }
        return grant.context == context.grantScope()
    }

    @Synchronized
    fun clear() {
        activeGrant = null
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 120_000L
    }
}

private fun AutofillGrantContext.grantScope(): AutofillGrantContext =
    // Activities and fields can change between the username and password steps.
    // Authorization stays limited to the same app and exact normalized web host.
    normalized().copy(interactionIdentifier = null, fieldSignatureKey = null)

/** Process-only autofill authorization. Never unlocks the main app or the IME session. */
object AutofillSessionGrants {
    private val store = AutofillSessionGrantStore()
    private var appContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        val application = context.applicationContext
        ContextCompat.registerReceiver(
            application,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                        clear()
                        AutofillUnlockRequests.clear()
                        takagi.ru.monica.autofill_ng.NativeAutofillAction.revokeAll()
                    }
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        appContext = application
    }

    private fun deviceIsUnlocked(): Boolean {
        val context = appContext ?: return false
        return context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
    }

    @Synchronized
    fun grant(context: AutofillGrantContext, enabled: Boolean = false) {
        val application = appContext ?: return
        takagi.ru.monica.security.MainTaskSessionGuard.revokeIfTaskRemoved(application)
        if (enabled && deviceIsUnlocked() && SecurityManager.hasRuntimeUnlockCache()) store.grant(context)
        else clear()
    }

    @Synchronized
    fun isGranted(context: AutofillGrantContext): Boolean {
        val application = appContext ?: return false
        if (takagi.ru.monica.security.MainTaskSessionGuard.revokeIfTaskRemoved(application)) return false
        if (!deviceIsUnlocked() || !SecurityManager.hasRuntimeUnlockCache()) {
            clear()
            return false
        }
        return store.isGranted(context)
    }

    @Synchronized
    fun clear() = store.clear()
}
