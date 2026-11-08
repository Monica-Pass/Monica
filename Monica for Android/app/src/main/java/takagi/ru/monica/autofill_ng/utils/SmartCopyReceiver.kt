package takagi.ru.monica.autofill_ng.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.os.SystemClock
import takagi.ru.monica.R
import takagi.ru.monica.utils.ClipboardUtils
import takagi.ru.monica.notifications.LiveUpdateNotifications
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.autofill_ng.NativeAutofillAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Broadcast Receiver for Smart Copy notification actions.
 * 
 * When the user taps the "Copy" notification, this receiver copies the queued value to clipboard.
 */
class SmartCopyReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COPY = "takagi.ru.monica.ACTION_SMART_COPY"
        const val ACTION_DISMISS = "takagi.ru.monica.ACTION_SMART_COPY_DISMISS"
        const val EXTRA_EXPIRES_AT = "expires_at_elapsed"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_IS_PASSWORD = "is_password"
        const val EXTRA_VALUE = "extra_value"
        internal const val EXTRA_NATIVE_ACTION = "native_action"
        const val EXTRA_LABEL = "extra_label"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!SmartCopyNotificationHelper.isCurrentSession(context, intent)) return
        val strings = AppLocaleStringResolver(context)
        if (intent.action == ACTION_DISMISS) {
            SmartCopyNotificationHelper.dismissNotification(context)
            return
        }
        if (intent.action != ACTION_COPY) return
        if (SystemClock.elapsedRealtime() >= intent.getLongExtra(EXTRA_EXPIRES_AT, 0L)) {
            SmartCopyNotificationHelper.dismissNotification(context)
            return
        }
        if (LiveUpdateNotifications.isDeviceLocked(context)) {
            Toast.makeText(context, strings.get(R.string.live_update_unlock_to_copy), Toast.LENGTH_SHORT).show()
            return
        }

        if (intent.hasExtra(EXTRA_NATIVE_ACTION)) {
            val action = NativeAutofillAction.fromBundle(intent.getBundleExtra(EXTRA_NATIVE_ACTION))
            if (action == null || !action.isCurrent(context)) {
                SmartCopyNotificationHelper.dismissNotification(context)
                return
            }
            val pending = goAsync()
            val appContext = context.applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    withTimeoutOrNull(8_000) {
                        val entry = action.resolve(appContext)
                        withContext(Dispatchers.Main.immediate) {
                            if (!SmartCopyNotificationHelper.isCurrentSession(appContext, intent)) return@withContext
                            if (entry == null || !action.isCurrent(appContext)) {
                                SmartCopyNotificationHelper.dismissNotification(appContext)
                                return@withContext
                            }
                            val password = intent.getBooleanExtra(EXTRA_IS_PASSWORD, false)
                            val value = if (password) entry.password else entry.username
                            if (value.isBlank()) return@withContext
                            ClipboardUtils.copyToClipboard(appContext, value,
                                intent.getStringExtra(EXTRA_LABEL) ?: "Credential", sensitive = true)
                            Toast.makeText(appContext, strings.get(if (password) R.string.password_copied
                                else R.string.username_copied), Toast.LENGTH_SHORT).show()
                            SmartCopyNotificationHelper.dismissNotification(appContext)
                        }
                    }
                } catch (_: Exception) {
                    // Provider exceptions can include secrets; only revoke this notification.
                    if (SmartCopyNotificationHelper.isCurrentSession(appContext, intent))
                        SmartCopyNotificationHelper.dismissNotification(appContext)
                } finally {
                    pending.finish()
                }
            }
            return
        }

        val value = intent.getStringExtra(EXTRA_VALUE) ?: return
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "Credential"

        val copied = runCatching { ClipboardUtils.copyToClipboard(
            context = context,
            text = value,
            label = label,
            sensitive = true
        ) }.isSuccess
        if (!copied) return

        // Show toast
        val message = if (intent.getBooleanExtra(EXTRA_IS_PASSWORD, false)) {
            strings.get(R.string.password_copied)
        } else {
            strings.get(R.string.username_copied)
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

        // Dismiss the notification
        SmartCopyNotificationHelper.dismissNotification(context)
    }
}



