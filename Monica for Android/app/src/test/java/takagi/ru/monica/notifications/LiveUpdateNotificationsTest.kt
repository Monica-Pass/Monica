package takagi.ru.monica.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import takagi.ru.monica.repository.PermissionRepository
import takagi.ru.monica.ui.screens.PermissionClickAction
import takagi.ru.monica.ui.screens.resolvePermissionClickAction
import takagi.ru.monica.data.model.PermissionStatus

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 32, 34], application = Application::class)
class LiveUpdateNotificationsTest {
    @Test fun oldPlatformsKeepStandardNotificationAndPromotionCannotBeEnabled() {
        val context = RuntimeEnvironment.getApplication()
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("test", "test", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = Notification.Builder(context, "test").setContentTitle("Fixture")
            .setContentText("ordinary notification").build()
        assertFalse(LiveUpdateNotifications.isSupported())
        assertFalse(LiveUpdateNotifications.canPostPromotedNotifications(context))
        assertSame(notification, LiveUpdateNotifications.enhance(context, notification, "10s"))
        assertFalse(notification.extras.getBoolean(LiveUpdateNotifications.REQUEST_PROMOTED_ONGOING))
        PermissionRepository(context).getAllPermissions().filter { it.id == "LIVE_UPDATES" }.forEach {
            assertEquals(PermissionStatus.UNAVAILABLE, it.status)
        }
        val settings = LiveUpdateNotifications.settingsIntent(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings.action)
        assertEquals(context.packageName, settings.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test fun promotionPermissionNeverUsesRuntimeRequest() {
        for (status in listOf(PermissionStatus.GRANTED, PermissionStatus.DENIED)) {
            assertEquals(PermissionClickAction.OPEN_APP_SETTINGS, resolvePermissionClickAction("LIVE_UPDATES", status))
        }
    }
}
