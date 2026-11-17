package takagi.ru.monica.security

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.AppSettings
import java.io.IOException

class ForegroundEntrySettingsTest {
    private class Owner(state: Lifecycle.State = Lifecycle.State.RESUMED) : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = state }
    }

    @Test fun delayedSettingsAreDeliveredExactlyOnceWithoutDefaults() = runBlocking {
        withContext(Dispatchers.Main) {
            val owner = Owner()
            val request = ForegroundEntrySettings(owner.lifecycle)
            val emitted = CompletableDeferred<AppSettings>()
            var observed: AppSettings? = null
            val pending = async { request.deliver(flow { emit(emitted.await()) }, { true }) { observed = it } }
            yield()
            assertNull(observed)
            val expected = AppSettings(autoLockMinutes = 7, biometricEnabled = false, autofillKeepUnlocked = false)
            emitted.complete(expected)
            assertTrue(pending.await())
            assertSame(expected, observed)
            assertFalse(request.deliver(flowOf(AppSettings()), { true }) { fail("Duplicate delivery") })
        }
    }

    @Test fun alreadyLoadedSettingsStillWaitForForeground() = runBlocking {
        withContext(Dispatchers.Main) {
            val owner = Owner(Lifecycle.State.CREATED)
            val request = ForegroundEntrySettings(owner.lifecycle)
            var delivered = false
            val pending = async { request.deliver(flowOf(AppSettings()), { true }) {
                assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
                delivered = true
            } }
            yield(); yield()
            assertFalse(delivered)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            assertTrue(pending.await())
            assertTrue(delivered)
        }
    }

    @Test fun stopCancelsCollectorAndNeverDeliversAfterResume() = runBlocking {
        withContext(Dispatchers.Main) {
            val owner = Owner()
            val request = ForegroundEntrySettings(owner.lifecycle)
            val entered = CompletableDeferred<Unit>()
            val exited = CompletableDeferred<Unit>()
            val pending = async { request.deliver(flow<AppSettings> {
                entered.complete(Unit)
                try { awaitCancellation() } finally { exited.complete(Unit) }
            }, { true }) { fail("Stopped entry delivered settings") } }
            entered.await()
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            assertFalse(pending.await())
            exited.await()
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            assertFalse(request.deliver(flowOf(AppSettings()), { true }) { fail("Revoked request restarted") })
        }
    }

    @Test fun stopDuringSecureStartupPreventsLaterSettingsSubscription() = runBlocking {
        withContext(Dispatchers.Main) {
            val owner = Owner()
            val request = ForegroundEntrySettings(owner.lifecycle)
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            assertFalse(request.deliver(flow { fail("Stopped request subscribed"); emit(AppSettings()) }, { true }) { fail() })
        }
    }

    @Test fun pauseWithSettingsReadyDoesNotAuthorizeUntilResumed() = runBlocking {
        withContext(Dispatchers.Main) {
            val owner = Owner(Lifecycle.State.STARTED)
            val request = ForegroundEntrySettings(owner.lifecycle)
            var calls = 0
            val pending = async { request.deliver(flowOf(AppSettings()), { true }) { calls++ } }
            yield(); yield()
            assertEquals(0, calls)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            assertTrue(pending.await())
            assertEquals(1, calls)
        }
    }

    @Test fun replacedOrLockedRequestRejectsLoadedSettings() = runBlocking {
        withContext(Dispatchers.Main) {
            val owner = Owner()
            val request = ForegroundEntrySettings(owner.lifecycle)
            val emitted = CompletableDeferred<AppSettings>()
            var generation = 1
            val pending = async { request.deliver(flow { emit(emitted.await()) }, { generation == 1 }) { fail("Stale request accepted") } }
            yield()
            generation++
            emitted.complete(AppSettings(disablePasswordVerification = true))
            assertFalse(pending.await())
        }
    }

    @Test fun settingsTimeoutDoesNotAuthorizeDefaults() = runBlocking {
        withContext(Dispatchers.Main) {
            val owner = Owner()
            val request = ForegroundEntrySettings(owner.lifecycle)
            assertFalse(request.deliver(flow { awaitCancellation() }, { true }, timeoutMillis = 80) { fail("Timeout authorized") })
        }
    }

    @Test fun settingsReadFailureDoesNotAuthorizeDefaults() = runBlocking {
        withContext(Dispatchers.Main) {
            val owner = Owner()
            val request = ForegroundEntrySettings(owner.lifecycle)
            assertFalse(request.deliver(flow { throw IOException("synthetic settings failure") }, { true }) { fail("Failure authorized") })
        }
    }

    @Test fun parentCancellationReleasesCollectorWithoutDelivery() = runBlocking {
        withContext(Dispatchers.Main) {
            val owner = Owner()
            val request = ForegroundEntrySettings(owner.lifecycle)
            val entered = CompletableDeferred<Unit>()
            val exited = CompletableDeferred<Unit>()
            val pending = async { request.deliver(flow<AppSettings> {
                entered.complete(Unit)
                try { awaitCancellation() } finally { exited.complete(Unit) }
            }, { true }) { fail("Cancelled request delivered") } }
            entered.await()
            pending.cancel(); pending.join()
            exited.await()
            assertTrue(pending.isCancelled)
        }
    }
}
