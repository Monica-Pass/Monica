package takagi.ru.monica.autofill_ng.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import java.util.UUID
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import takagi.ru.monica.R
import takagi.ru.monica.utils.ClipboardUtils
import takagi.ru.monica.notifications.LiveUpdateNotifications
import takagi.ru.monica.autofill_ng.NativeAutofillAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper for "Smart Copy" feature.
 * 
 * When the user copies one credential (e.g., username), this helper shows a notification
 * that allows them to quickly copy the other credential (e.g., password) with one tap.
 */
object SmartCopyNotificationHelper {

    private const val CHANNEL_ID = "smart_copy_channel"
    private const val NOTIFICATION_ID = 9527
    private const val SESSION_PREFS = "smart_copy_session"

    /**
     * Copies the first value to clipboard and shows a notification to copy the second value.
     * 
     * @param context Application context
     * @param firstValue The value to copy immediately
     * @param firstLabel Label for the first value (e.g., "Username")
     * @param secondValue The value to copy when notification is tapped
     * @param secondLabel Label for the second value (e.g., "Password")
     */
    fun copyAndQueueNext(
        context: Context,
        firstValue: String,
        firstLabel: String,
        secondValue: String,
        secondLabel: String
    ): Boolean {
        ClipboardUtils.copyToClipboard(
            context = context,
            text = firstValue,
            label = firstLabel,
            sensitive = true
        )

        // 2. Show notification to copy second value
        return showCopyNotification(context, secondValue, secondLabel)
    }

    internal suspend fun copyAndQueueNative(
        context: Context,
        action: NativeAutofillAction,
        usernameFirst: Boolean,
        isRequestCurrent: () -> Boolean,
    ): Boolean {
        val entry = action.resolve(context) ?: return false
        return withContext(Dispatchers.Main.immediate) {
            if (!isRequestCurrent() || !action.isCurrent(context)) return@withContext false
            val firstValue = if (usernameFirst) entry.username else entry.password
            val firstLabel = context.getString(if (usernameFirst) R.string.autofill_username else R.string.autofill_password)
            val secondLabel = context.getString(if (usernameFirst) R.string.autofill_password else R.string.autofill_username)
            if (firstValue.isBlank()) return@withContext false
            ClipboardUtils.copyToClipboard(context, firstValue, firstLabel, sensitive = true)
            if (!isRequestCurrent() || !action.isCurrent(context)) return@withContext false
            // The deferred native action contains only identity/revision/authorization metadata.
            showCopyNotification(context, null, secondLabel, action)
        }
    }

    private fun showCopyNotification(context: Context, valueToCopy: String?, label: String,
        nativeAction: NativeAutofillAction? = null): Boolean {
        // Only session metadata is persisted; queued credentials stay in the private PendingIntent.
        dismissNotification(context)
        return try {
            buildAndPost(context, valueToCopy, label, nativeAction)
        } catch (_: Exception) {
            dismissNotification(context)
            android.util.Log.w("SmartCopy", "Queued copy notification unavailable")
            false
        }
    }

    private fun buildAndPost(context: Context, valueToCopy: String?, label: String,
        nativeAction: NativeAutofillAction? = null): Boolean {
        if (nativeAction != null && !nativeAction.isCurrent(context)) return false
        createNotificationChannel(context)

        if (!canPostSmartCopyNotification(context)) {
            android.util.Log.w("SmartCopy", "Notifications unavailable, skip queued smart copy")
            return false
        }

        val sessionId = UUID.randomUUID().toString()
        val isPassword = label == context.getString(R.string.autofill_password) ||
            label.contains("密码") || label.contains("密碼") || label.contains("password", ignoreCase = true)
        context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).edit()
            .putString("id", sessionId).apply()
        val copyIntent = Intent(context, SmartCopyReceiver::class.java).apply {
            action = SmartCopyReceiver.ACTION_COPY
            if (nativeAction == null) putExtra(SmartCopyReceiver.EXTRA_VALUE, valueToCopy)
            else putExtra(SmartCopyReceiver.EXTRA_NATIVE_ACTION, nativeAction.toBundle())
            putExtra(SmartCopyReceiver.EXTRA_LABEL, label)
            putExtra(SmartCopyReceiver.EXTRA_EXPIRES_AT, SystemClock.elapsedRealtime() + 60_000)
            putExtra(SmartCopyReceiver.EXTRA_SESSION_ID, sessionId)
            putExtra(SmartCopyReceiver.EXTRA_IS_PASSWORD, isPassword)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            copyIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissIntent = PendingIntent.getBroadcast(
            context, NOTIFICATION_ID + 1,
            Intent(context, SmartCopyReceiver::class.java).setAction(SmartCopyReceiver.ACTION_DISMISS)
                .putExtra(SmartCopyReceiver.EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationText = if (isPassword) {
            context.getString(R.string.smart_copy_notification_copy_password)
        } else {
            context.getString(R.string.smart_copy_notification_copy_username)
        }

        val publicNotification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_key)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.live_update_unlock_to_view))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_key)
            .setContentTitle(context.getString(R.string.smart_copy_notification_title))
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(dismissIntent)
            .addAction(NotificationCompat.Action.Builder(0, notificationText, pendingIntent)
                .setAuthenticationRequired(true).build())
            .addAction(0, context.getString(R.string.close), dismissIntent)
            .setTimeoutAfter(60_000) // Auto-dismiss after 60 seconds
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID,
            LiveUpdateNotifications.enhance(context, notification, context.getString(R.string.live_update_copy_status)))
        return true
    }

    private fun canPostSmartCopyNotification(context: Context): Boolean {
        val managerCompat = NotificationManagerCompat.from(context)
        if (!managerCompat.areNotificationsEnabled()) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            val channel = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }

        return true
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.live_update_smart_copy_channel)
            val descriptionText = "Quick copy for credentials"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Dismisses the Smart Copy notification.
     */
    fun dismissNotification(context: Context) {
        runCatching { context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).edit().remove("id").apply() }
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
        listOf(SmartCopyReceiver.ACTION_COPY, SmartCopyReceiver.ACTION_DISMISS).forEachIndexed { index, action ->
            runCatching {
                PendingIntent.getBroadcast(context, NOTIFICATION_ID + index,
                    Intent(context, SmartCopyReceiver::class.java).setAction(action),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.cancel()
            }
        }
    }

    internal fun isCurrentSession(context: Context, intent: Intent): Boolean {
        val sessionId = intent.getStringExtra(SmartCopyReceiver.EXTRA_SESSION_ID) ?: return false
        return runCatching {
            sessionId == context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).getString("id", null)
        }.getOrDefault(false)
    }
}



