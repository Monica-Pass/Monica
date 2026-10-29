package takagi.ru.monica.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin

class SecureStartupInstrumentedTest {
    @Test fun providerDeadlineReturnsBlockedBeforeTheReadReturns() = kotlinx.coroutines.runBlocking {
        Fixture().use { f ->
            f.create()
            val before = f.raw.all.toMap()
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            val blocking = object : ContextWrapper(f.context) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val delegate = super.getSharedPreferences(name, mode)
                    return if (name != SecurePreferencesStore.MONICA) delegate else object : SharedPreferences by delegate {
                        override fun getAll(): MutableMap<String, *> {
                            if (!entered.isCompleted) {
                                entered.complete(Unit)
                                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            }
                            return delegate.all
                        }
                    }
                }
            }
            SessionManager.markLocked()
            val result = kotlinx.coroutines.CompletableDeferred<SecureStartupResult>()
            val job = launch(kotlinx.coroutines.Dispatchers.Main) {
                result.complete(SecureStorageStartup.prepareWithRetry(blocking, timeoutMillis = 150))
            }
            try {
                kotlinx.coroutines.withTimeout(5_000) { entered.await() }
                val completed = kotlinx.coroutines.withTimeoutOrNull(1000) { result.await() }
                assertTrue("A stalled provider must reach the data-preserving blocked result within its deadline", completed is SecureStartupResult.Blocked)
                assertEquals("SECURE_OPERATION_TIMEOUT", (completed as SecureStartupResult.Blocked).failure.reason)
                assertFalse(completed.recoveryAvailable)
                assertFalse(SecureStorageStartup.readyForMaintenance)
                assertFalse(SessionManager.isUnlocked.value)
                assertEquals(before, f.raw.all)
                repeat(20) {
                    val retry = SecureStorageStartup.prepareWithRetry(f.context, timeoutMillis = 300) as SecureStartupResult.Blocked
                    assertEquals("SECURE_OPERATION_IN_PROGRESS", retry.failure.reason)
                    assertFalse(retry.recoveryAvailable)
                }
            } finally {
                release.countDown()
                job.cancelAndJoin()
                awaitProviderExit()
            }
            assertFalse("A late provider success must not enable maintenance", SecureStorageStartup.readyForMaintenance)
            assertFalse(SessionManager.isUnlocked.value)
            assertEquals(before, f.raw.all)
            assertTrue(SecureStorageStartup.prepareWithRetry(f.context) is SecureStartupResult.Ready)
        }
    }

    /** Inject a provider read failure at the real startup boundary, using fixture storage only. */
    private class ReadFaultContext(
        base: Context,
        private var failuresLeft: Int,
        private val failOnRead: Int,
        private val onFault: () -> Unit = {},
        private val failureFactory: () -> Exception = {
            java.security.ProviderException("synthetic-secret-provider-detail")
        }
    ) : ContextWrapper(base) {
        @Volatile
        var failures = 0
            private set
        private var reads = 0
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val original = super.getSharedPreferences(name, mode)
            if (name != SecurePreferencesStore.MONICA) return original
            return object : SharedPreferences by original {
                override fun getAll(): MutableMap<String, *> {
                    if (++reads >= failOnRead && failuresLeft > 0) {
                        failuresLeft--
                        failures++
                        onFault()
                        throw failureFactory()
                    }
                    return original.all
                }
            }
        }
    }

    @Test fun transientPreflightFailureIsRetriedWithoutChangingCiphertextOrUnlocking() = kotlinx.coroutines.runBlocking {
        Fixture().use { f ->
            f.create()
            val original = f.raw.all.toMap()
            val fault = ReadFaultContext(f.context, failuresLeft = 1, failOnRead = 2)
            SessionManager.markLocked()
            assertTrue(SecureStorageStartup.prepareWithRetry(fault) is SecureStartupResult.Ready)
            assertEquals(1, fault.failures)
            assertEquals(original, f.raw.all)
            assertFalse(SessionManager.isUnlocked.value)
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
        }
    }

    @Test fun persistentReadFailureStopsAfterThreeAttemptsAndKeepsStorage() = kotlinx.coroutines.runBlocking {
        Fixture().use { f ->
            f.create()
            val original = f.raw.all.toMap()
            val fault = ReadFaultContext(f.context, failuresLeft = 100, failOnRead = 2)
            val result = SecureStorageStartup.prepareWithRetry(fault) as SecureStartupResult.Blocked
            assertEquals("UNREADABLE_SECURE_STORAGE", result.failure.reason)
            assertEquals(3, fault.failures)
            assertEquals(3, result.attempts)
            assertFalse(SecureStorageStartup.readyForMaintenance)
            assertEquals(original, f.raw.all)
        }
    }

    @Test fun missingKeysetIsNotAutomaticallyRetriedOrRebuilt() = kotlinx.coroutines.runBlocking {
        Fixture().use { f ->
            f.create()
            assertTrue(f.raw.edit().remove("__androidx_security_crypto_encrypted_prefs_value_keyset__").commit())
            val damaged = f.raw.all.toMap()
            val result = SecureStorageStartup.prepareWithRetry(f.context) as SecureStartupResult.Blocked
            assertEquals("INCOMPLETE_KEYSET", result.failure.reason)
            assertEquals(1, result.attempts)
            assertEquals(damaged, f.raw.all)
            assertFalse(SecureStorageStartup.readyForMaintenance)
        }
    }

    @Test fun diagnosticIdentifiesReadPhaseWithoutExceptionMessages() {
        Fixture().use { f ->
            f.create()
            val fault = ReadFaultContext(f.context, failuresLeft = 1, failOnRead = 3)
            val result = SecureStorageStartup.prepare(fault) as SecureStartupResult.Blocked
            assertEquals(SecureStoragePhase.VALUE_READ, result.failure.phase)
            val diagnostic = SecureStorageStartup.diagnostic(result.failure, attempts = 3)
            assertTrue(diagnostic.contains("Phase: VALUE_READ"))
            assertTrue(diagnostic.contains("Attempts: 3"))
            assertTrue(diagnostic.contains("ProviderException"))
            assertFalse(diagnostic.contains("synthetic-secret-provider-detail"))
        }
    }

    @Test fun cancelledStartupDoesNotPerformAnotherRead() = kotlinx.coroutines.runBlocking {
        Fixture().use { f ->
            f.create()
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val releaseRead = java.util.concurrent.CountDownLatch(1)
            val fault = ReadFaultContext(f.context, failuresLeft = 100, failOnRead = 2, onFault = {
                entered.complete(Unit)
                check(releaseRead.await(10, java.util.concurrent.TimeUnit.SECONDS))
            })
            val job = launch { SecureStorageStartup.prepareWithRetry(fault) }
            try {
                kotlinx.coroutines.withTimeout(10_000) { entered.await() }
                job.cancel()
            } finally {
                releaseRead.countDown()
                job.cancelAndJoin()
                awaitProviderExit()
            }
            assertEquals(1, fault.failures)
            assertFalse(SecureStorageStartup.readyForMaintenance)
        }
    }

    @Test fun wrappedKeystoreFailuresReachBlockedStartupAndPreserveEncryptedPreferences() = kotlinx.coroutines.runBlocking {
        val causes = listOf(
            android.security.keystore.KeyPermanentlyInvalidatedException("synthetic-invalidated-key"),
            java.security.UnrecoverableKeyException("synthetic-unrecoverable-key"),
            java.security.KeyStoreException("synthetic-keystore-service-error")
        )
        for (cause in causes) Fixture().use { f ->
            f.create()
            val before = f.raw.all.toMap()
            val fault = ReadFaultContext(f.context, failuresLeft = 100, failOnRead = 3,
                failureFactory = { java.security.ProviderException("synthetic-provider-secret", cause) })
            SessionManager.markLocked()
            val result = SecureStorageStartup.prepareWithRetry(fault) as SecureStartupResult.Blocked
            assertEquals("UNREADABLE_SECURE_STORAGE", result.failure.reason)
            assertEquals(3, result.attempts)
            assertFalse(SecureStorageStartup.readyForMaintenance)
            assertFalse(SessionManager.isUnlocked.value)
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
            assertEquals(before, f.raw.all)
            val diagnostic = SecureStorageStartup.diagnostic(result.failure, result.attempts)
            assertTrue(diagnostic.contains(cause.javaClass.simpleName))
            assertFalse(diagnostic.contains("synthetic-"))
        }
    }

    @Test fun cancellingStartupReleasesUiWaitButRetainsTheSingleProviderSlot() = kotlinx.coroutines.runBlocking {
        Fixture().use { f ->
            f.create()
            val before = f.raw.all.toMap()
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val releaseRead = java.util.concurrent.CountDownLatch(1)
            val startupPending = java.util.concurrent.atomic.AtomicBoolean(true)
            val fault = ReadFaultContext(f.context, failuresLeft = 100, failOnRead = 2, onFault = {
                entered.complete(Unit)
                check(releaseRead.await(10, java.util.concurrent.TimeUnit.SECONDS))
            })
            SessionManager.markLocked()
            // Match MainActivity: it clears the splash condition only after preparation returns.
            val job = launch(kotlinx.coroutines.Dispatchers.Main) {
                SecureStorageStartup.prepareWithRetry(fault)
                startupPending.set(false)
            }
            try {
                kotlinx.coroutines.withTimeout(5_000) { entered.await() }
                kotlinx.coroutines.withTimeout(5_000) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        assertTrue("The UI dispatcher still runs while the provider call is blocked", startupPending.get())
                    }
                }
                assertFalse(SecureStorageStartup.readyForMaintenance)
                assertFalse(SessionManager.isUnlocked.value)
                assertEquals(before, f.raw.all)
                job.cancel()
                assertTrue("The UI coroutine must finish without waiting for a native call",
                    kotlinx.coroutines.withTimeout(1000) { job.join(); true })
                assertTrue(job.isCompleted)
                assertTrue(SecureStorageStartup.operationInProgress)
                val retry = SecureStorageStartup.prepareWithRetry(f.context) as SecureStartupResult.Blocked
                assertEquals("SECURE_OPERATION_IN_PROGRESS", retry.failure.reason)
                assertTrue(startupPending.get())
            } finally {
                releaseRead.countDown()
                job.cancelAndJoin()
                awaitProviderExit()
            }
            assertEquals(1, fault.failures)
            assertFalse(SecureStorageStartup.readyForMaintenance)
            assertEquals(before, f.raw.all)
        }
    }

    private suspend fun awaitProviderExit() = kotlinx.coroutines.withTimeout(5_000) {
        while (SecureStorageStartup.operationInProgress) kotlinx.coroutines.delay(10)
    }

    @Test fun preliminaryStoreReadFailureAlsoFailsClosedInsteadOfEscapingStartup() {
        Fixture().use { f ->
            f.create()
            val original = f.raw.all.toMap()
            val fault = ReadFaultContext(f.context, failuresLeft = 1, failOnRead = 1)
            val result = SecureStorageStartup.prepare(fault) as SecureStartupResult.Blocked
            assertEquals("UNREADABLE_SECURE_STORAGE", result.failure.reason)
            assertFalse(SecureStorageStartup.readyForMaintenance)
            assertEquals(original, f.raw.all)
        }
    }

    private class Fixture : AutoCloseable {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "startup-fixture-${UUID.randomUUID()}-"
        val dataDir = java.io.File(base.cacheDir, prefix)
        val context = object : ContextWrapper(base) {
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply {
                dataDir = this@Fixture.dataDir.absolutePath
            }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(prefix + name, mode)
        }
        val raw get() = context.getSharedPreferences("monica_secure_prefs", Context.MODE_PRIVATE)
        fun create() {
            SecurityManager(context)
        }
        fun corruptKeyset() {
            val name = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
            val hex = checkNotNull(raw.getString(name, null))
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            // EncryptedKeyset protobuf field 2 is the length-delimited AEAD ciphertext.
            check(bytes[0].toInt() == 0x12)
            var cursor = 1
            var length = 0
            var shift = 0
            do {
                val value = bytes[cursor++].toInt() and 255
                length = length or ((value and 127) shl shift)
                shift += 7
            } while (value and 128 != 0)
            val tagEnd = cursor + length - 1
            check(tagEnd in bytes.indices)
            bytes[tagEnd] = (bytes[tagEnd].toInt() xor 1).toByte()
            check(raw.edit().putString(name, bytes.joinToString("") { "%02x".format(it) }).commit())
        }
        override fun close() {
            base.deleteSharedPreferences(prefix + "monica_secure_prefs")
            base.deleteSharedPreferences(prefix + "bitwarden_secure_prefs")
            java.io.File(dataDir, "shared_prefs/monica_secure_prefs.xml").delete()
            java.io.File(dataDir, "shared_prefs").delete()
            dataDir.delete()
        }
    }

    @Test fun reproduceAuthenticatedKeysetFailureWithoutTouchingUserData() {
        Fixture().use { fixture ->
            fixture.create()
            fixture.corruptKeyset()
            val before = fixture.raw.all.toMap()
            repeat(2) {
                val error = runCatching { SecurityManager(fixture.context) }.exceptionOrNull()
                assertTrue("Corrupted keyset must fail closed", error is SecureStorageUnavailableException)
                assertTrue(SecureStorageStartup.prepare(fixture.context) is SecureStartupResult.Blocked)
                assertFalse(SecureStorageStartup.readyForMaintenance)
                val causes = generateSequence(error) { it.cause }.map { it.javaClass.simpleName }.toList()
                assertTrue("Expected authenticated-decryption failure: $causes",
                    causes.any { it == "AEADBadTagException" || it == "KeyStoreException" })
                assertEquals(before, fixture.raw.all)
            }
        }
    }
    @Test fun retrySucceedsOnlyAfterTheOriginalConfigurationIsRestored() {
        Fixture().use { f ->
            f.create()
            val original = f.raw.all.mapValues { it.value as String }
            f.corruptKeyset()
            assertTrue(SecureStorageStartup.prepare(f.context) is SecureStartupResult.Blocked)
            val editor = f.raw.edit().clear()
            original.forEach { (key, value) -> editor.putString(key, value) }
            assertTrue(editor.commit())
            assertTrue(SecureStorageStartup.prepare(f.context) is SecureStartupResult.Ready)
            assertTrue(SecureStorageStartup.readyForMaintenance)
            assertEquals(original, f.raw.all)
        }
    }

    @Test fun missingMasterKeyIsNotRecreatedForAnExistingStore() {
        Fixture().use { f ->
            val alias = f.prefix + "master"
            val keystore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            try {
                SecurePreferencesStore.open(f.context, SecurePreferencesStore.MONICA, alias)
                val before = f.raw.all.toMap()
                keystore.deleteEntry(alias) // Fixture-owned key only; never delete the application alias.
                val error = runCatching { SecurePreferencesStore.open(f.context, SecurePreferencesStore.MONICA, alias) }.exceptionOrNull()
                assertEquals("MISSING_MASTER_KEY", (error as SecureStorageUnavailableException).reason)
                assertFalse(keystore.containsAlias(alias))
                assertEquals(before, f.raw.all)
            } finally { if (keystore.containsAlias(alias)) keystore.deleteEntry(alias) }
        }
    }

    @Test fun missingKeysetIsNotGeneratedOverExistingEncryptedValues() {
        Fixture().use { f ->
            f.create()
            f.raw.edit().remove("__androidx_security_crypto_encrypted_prefs_value_keyset__").commit()
            val before = f.raw.all.toMap()
            val result = SecureStorageStartup.prepare(f.context) as SecureStartupResult.Blocked
            assertEquals("INCOMPLETE_KEYSET", result.failure.reason)
            assertEquals(before, f.raw.all)
        }
    }

    @Test fun corruptIndividualPreferenceIsDetectedBeforeNormalStartup() {
        Fixture().use { f ->
            val prefs = SecurePreferencesStore.open(f.context, SecurePreferencesStore.MONICA).preferences
            prefs.edit().putString("fixture-value", "synthetic-value").commit()
            val key = f.raw.all.keys.single { !it.startsWith("__androidx_security_crypto_") }
            val value = android.util.Base64.decode(f.raw.getString(key, null), android.util.Base64.DEFAULT)
            value[value.lastIndex] = (value.last().toInt() xor 1).toByte()
            f.raw.edit().putString(key, android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)).commit()
            val before = f.raw.all.toMap()
            assertTrue(SecureStorageStartup.prepare(f.context) is SecureStartupResult.Blocked)
            assertEquals(before, f.raw.all)
        }
    }

    @Test fun bitwardenFailureDoesNotBlockOrModifyTheLocalVault() {
        Fixture().use { f ->
            f.create()
            SecurePreferencesStore.open(f.context, SecurePreferencesStore.BITWARDEN)
            val raw = f.context.getSharedPreferences(SecurePreferencesStore.BITWARDEN, Context.MODE_PRIVATE)
            raw.edit().remove("__androidx_security_crypto_encrypted_prefs_key_keyset__").commit()
            val before = f.raw.all.toMap()
            val damaged = raw.all.toMap()
            assertTrue(SecureStorageStartup.prepare(f.context) is SecureStartupResult.Ready)
            assertEquals(before, f.raw.all)
            assertEquals(damaged, raw.all)
        }
    }

    @Test fun healthyStoreRoundTripsAndDiagnosticsExcludeExceptionMessages() {
        Fixture().use { f ->
            val opened = SecurePreferencesStore.open(f.context, SecurePreferencesStore.MONICA)
            opened.preferences.edit().putString("fixture", "synthetic-secret").commit()
            assertEquals("synthetic-secret", SecurePreferencesStore.open(f.context, SecurePreferencesStore.MONICA).preferences.getString("fixture", null))
            assertTrue(SecureStorageStartup.prepare(f.context) is SecureStartupResult.Ready)
            val diagnostic = SecureStorageStartup.diagnostic(SecureStorageUnavailableException(
                SecurePreferencesStore.MONICA, "AUTHENTICATION_FAILED", Exception("synthetic-secret")))
            assertFalse(diagnostic.contains("synthetic-secret"))
            assertTrue(diagnostic.contains("AUTHENTICATION_FAILED"))
        }
    }

    @Test fun existingUnreadableXmlIsNotTreatedAsANewInstallation() {
        Fixture().use { f ->
            val file = java.io.File(f.dataDir, "shared_prefs/monica_secure_prefs.xml")
            file.parentFile!!.mkdirs()
            val broken = "<map><broken-encrypted-configuration".toByteArray()
            file.writeBytes(broken)
            val result = SecureStorageStartup.prepare(f.context) as SecureStartupResult.Blocked
            assertEquals("EMPTY_OR_UNREADABLE_STORE", result.failure.reason)
            assertArrayEquals(broken, file.readBytes())
            assertTrue(f.raw.all.isEmpty())
        }
    }

    @Test fun healthyBackgroundStartupDoesNotDependOnAnActivity() = kotlinx.coroutines.runBlocking {
        Fixture().use { f ->
            f.create()
            f.corruptKeyset()
            assertTrue(SecureStorageStartup.prepare(f.context) is SecureStartupResult.Blocked)
        }
        Fixture().use { fresh ->
            // Simulate a new process with no attempted startup. The old failed fixture is
            // deliberately not retried by maintenance in the same process after hardening.
            assertFalse(SecureStorageStartup.operationInProgress)
            SecureStorageStartup::class.java.getDeclaredField("attempted").apply {
                isAccessible = true
                setBoolean(null, false)
            }
            kotlinx.coroutines.withTimeout(10_000) { SecureStorageStartup.awaitReadyForMaintenance(fresh.context) }
            assertTrue(SecureStorageStartup.readyForMaintenance)
        }
    }

    @Test fun maintenanceDoesNotRetryBlockedStorageWithoutExplicitStartup() = kotlinx.coroutines.runBlocking {
        Fixture().use { f ->
            f.create()
            val before = f.raw.all.toMap()
            val fault = ReadFaultContext(f.context, failuresLeft = 100, failOnRead = 2)
            assertTrue(SecureStorageStartup.prepareWithRetry(fault) is SecureStartupResult.Blocked)
            assertEquals(3, fault.failures)
            assertNull(kotlinx.coroutines.withTimeoutOrNull(250) { SecureStorageStartup.awaitReadyForMaintenance(fault); true })
            assertEquals(3, fault.failures)
            assertFalse(SecureStorageStartup.readyForMaintenance)
            assertEquals(before, f.raw.all)
        }
    }

    @Test fun concurrentFirstOpenDoesNotMistakeAnInProgressKeysetForCorruption() {
        Fixture().use { f ->
            val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
            try {
                val start = java.util.concurrent.CountDownLatch(1)
                val tasks = (0 until 4).map {
                    executor.submit<SecurePreferencesStore.Opened> {
                        start.await()
                        SecurePreferencesStore.open(f.context, SecurePreferencesStore.MONICA)
                    }
                }
                start.countDown()
                tasks.forEach { assertNotNull(it.get(10, java.util.concurrent.TimeUnit.SECONDS)) }
                assertEquals(2, f.raw.all.size)
            } finally { executor.shutdownNow() }
        }
    }

}
