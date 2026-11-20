package takagi.ru.monica.lansync

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import takagi.ru.monica.security.SessionManager
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DeviceSyncImmediateLockTest {
    @After fun reset() {
        SessionManager.markLocked()
        SessionManager.updateAutoLockTimeout(5)
    }

    private fun coordinator(visible: Boolean): DeviceSyncCoordinator {
        val context = RuntimeEnvironment.getApplication()
        return DeviceSyncCoordinator::class.java.getDeclaredConstructor(android.content.Context::class.java)
            .apply { isAccessible = true }.newInstance(context).also { it.setAppVisible(visible) }
    }

    private fun readSettings(coordinator: DeviceSyncCoordinator): DeviceSyncPreferences = try {
        DeviceSyncCoordinator::class.java.getDeclaredMethod("settings")
            .apply { isAccessible = true }.invoke(coordinator) as DeviceSyncPreferences
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    @Test fun immediateUnlockObserverCanReadSyncSettingsWithoutRevokingAuthentication() = runBlocking {
        SessionManager.markLocked()
        SessionManager.updateAutoLockTimeout(0)
        val coordinator = coordinator(true)
        val generation = SessionManager.lockGeneration.value
        val observer = async(Dispatchers.Unconfined) {
            SessionManager.isUnlocked.first { it }
            readSettings(coordinator)
        }
        SessionManager.markUnlocked()
        assertTrue(observer.await().peers.isEmpty())
        assertTrue(SessionManager.isUnlocked.value)
        assertEquals(generation, SessionManager.lockGeneration.value)
        // Re-entry remains strict: a foreground-work check must not grant session restoration.
        assertTrue(SessionManager.isSessionExpired())
    }

    @Test fun backgroundSyncCannotUseImmediateSession() {
        SessionManager.updateAutoLockTimeout(0)
        SessionManager.markUnlocked()
        assertThrows(CancellationException::class.java) { readSettings(coordinator(false)) }
        assertFalse(SessionManager.isUnlocked.value)
    }

    @Test fun explicitLockCannotBeReopenedByForegroundSync() {
        SessionManager.updateAutoLockTimeout(0)
        SessionManager.markUnlocked()
        SessionManager.markLocked()
        assertThrows(CancellationException::class.java) { readSettings(coordinator(true)) }
        assertFalse(SessionManager.isUnlocked.value)
    }

    @Test fun finiteAndUnlimitedForegroundSessionsContinueWorking() {
        for (minutes in listOf(1, 5, -1)) {
            SessionManager.updateAutoLockTimeout(minutes)
            SessionManager.markUnlocked()
            readSettings(coordinator(true))
            assertTrue(SessionManager.isUnlocked.value)
            SessionManager.markLocked()
        }
    }
}
