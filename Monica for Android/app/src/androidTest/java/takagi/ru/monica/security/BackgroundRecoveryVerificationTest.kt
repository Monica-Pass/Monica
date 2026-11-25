package takagi.ru.monica.security

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BackgroundRecoveryVerificationTest {
    private val password = BiometricWrapperRecoveryInstrumentedTest.PASSWORD
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    // Hold only the optional worker, so revocation can be observed without timing a native KDF.
    private val executor get() = BackgroundRecoveryVerification::class.java.getDeclaredField("executor")
        .apply { isAccessible = true }.get(BackgroundRecoveryVerification) as ExecutorService
    private val occupied get() = BackgroundRecoveryVerification::class.java.getDeclaredField("occupied")
        .apply { isAccessible = true }.get(BackgroundRecoveryVerification) as AtomicBoolean
    private fun drained() {
        val done = CountDownLatch(1)
        executor.execute { done.countDown() }
        assertTrue("Optional worker did not complete", done.await(15, TimeUnit.SECONDS))
        main { }
    }

    @Test fun acceptedForegroundCheckIsReadOnlyAndDoesNotChangeAuthentication() = BiometricWrapperRecoveryInstrumentedTest.Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(password))
        val cipher = f.manager.encryptData("background-original")
        val before = f.prefs.all.toMap()
        val file = java.io.File(f.root, "files/local-vault-recovery/recovery.json")
        val bytes = file.readBytes()
        lateinit var owner: Owner
        try {
            main {
                owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
                SessionManager.markUnlocked()
                BackgroundRecoveryVerification.start(f.manager, password, owner.lifecycle)
            }
            drained()
            assertNotNull(f.manager.recoveryProtectionStatus().verifiedAtMillis)
            assertEquals(before, f.prefs.all)
            assertArrayEquals(bytes, file.readBytes())
            assertTrue(SessionManager.isUnlocked.value)
            assertEquals("background-original", f.manager.decryptData(cipher))
        } finally {
            main { owner.registry.currentState = Lifecycle.State.DESTROYED }
            SessionManager.markLocked()
        }
    }

    @Test fun backgroundCancellationDropsPendingPasswordAndDoesNotQueueAnotherCheck() = pendingCheck(revokeByLock = false)
    @Test fun lockCancellationCannotPublishOrRestoreSession() = pendingCheck(revokeByLock = true)

    private fun pendingCheck(revokeByLock: Boolean) = BiometricWrapperRecoveryInstrumentedTest.Fixture().use { f ->
        assertTrue(f.manager.setMasterPassword(password))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        lateinit var owner: Owner
        try {
            main {
                owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
                SessionManager.markUnlocked()
                BackgroundRecoveryVerification.start(f.manager, password, owner.lifecycle)
                assertTrue(occupied.get())
                if (revokeByLock) SessionManager.markLocked()
                else owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                owner.registry.currentState = Lifecycle.State.RESUMED
                BackgroundRecoveryVerification.start(f.manager, password, owner.lifecycle)
            }
            // The authentication/read worker remains available while optional work is held.
            val value = runBlocking {
                BoundedSecureDataRead.readChecked(2_000, { true }) { "normal-worker-available" }
            }
            assertEquals("normal-worker-available", value)
            release.countDown(); drained()
            assertFalse(occupied.get())
            assertNull(f.manager.recoveryProtectionStatus().verifiedAtMillis)
            if (revokeByLock) {
                assertFalse(SessionManager.isUnlocked.value)
                assertFalse(SecurityManager.hasRuntimeUnlockCache())
            }
            assertTrue(f.manager.unlockVaultWithPassword(password))
            val cipher = f.manager.encryptData("normal-write-after-cancel")
            assertEquals("normal-write-after-cancel", f.manager.decryptData(cipher))
        } finally {
            release.countDown(); drained()
            main { owner.registry.currentState = Lifecycle.State.DESTROYED }
            SessionManager.markLocked()
        }
    }
}
