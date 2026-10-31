package takagi.ru.monica.autofill_ng

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import takagi.ru.monica.autofill_ng.core.AutofillLogger

/** Fixed events and non-secret state only; never pass Intent extras or exception messages. */
internal object AutofillSaveDiagnostics {
    enum class Event {
        REQUEST_RECEIVED, SAVE_DISABLED, NO_SAVE_DATA, LAUNCH_READY, HANDOFF_SENT,
        LEGACY_LAUNCH_SENT, REQUEST_CANCELED, REQUEST_FAILED, CALLBACK_FAILED,
        ACTIVITY_CREATED, ACTIVITY_RESUMED, ACTIVITY_NEW_INTENT, ACTIVITY_DESTROYED,
        AUTH_READY, REVIEW_READY, EDITOR_READY, SAVE_COMPLETED,
        LOCK_CHANGED, SESSION_LOCKED, SCREEN_OFF, AUTH_CANCELED, REVIEW_CANCELED,
        EDITOR_CLOSED, TARGET_BLOCKED,
        OVERLAY_PERMISSION_MISSING, OVERLAY_SHOWN, OVERLAY_CONTINUED, OVERLAY_CANCELED,
        OVERLAY_INVALIDATED, OVERLAY_EXPIRED, OVERLAY_REPLACED, OVERLAY_FAILED,
    }

    fun record(context: Context, requestId: String, event: Event, error: Throwable? = null, compatibilityMode: Boolean? = null) {
        // Diagnostics must never prevent handoff, authentication or closing on lock.
        try {
            recordSafely(context, requestId, event, error, compatibilityMode)
        } catch (_: Exception) {
            // Best effort only; keep the user's save flow independent of logging.
        }
    }

    private fun recordSafely(context: Context, requestId: String, event: Event, error: Throwable?, compatibilityMode: Boolean?) {
        val monicaImeSelected = runCatching {
            val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ComponentName.unflattenFromString(current.orEmpty())?.packageName == context.packageName
        }.getOrDefault(false)
        val metadata = linkedMapOf<String, Any>(
            // IDs are generated locally by the service; direct/manual launches use a fixed label.
            "requestId" to requestId.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }.orEmpty().ifEmpty { "direct" },
            "pid" to Process.myPid(), "sdk" to Build.VERSION.SDK_INT,
            "monicaImeSelected" to monicaImeSelected,
        )
        compatibilityMode?.let { metadata["compatibilityMode"] = it }
        // A fixed category survives R8 and the logger's token redaction. Do not
        // include arbitrary class names, messages, causes or stack traces.
        error?.let {
            metadata["errorKind"] = when (it) {
                is SecurityException -> "security"
                is IllegalArgumentException -> "invalid_argument"
                is IllegalStateException -> "invalid_state"
                is android.os.RemoteException -> "remote"
                is android.app.PendingIntent.CanceledException -> "canceled_intent"
                else -> "other"
            }
        }
        AutofillLogger.i("SAVE", event.name, metadata)
    }
}
