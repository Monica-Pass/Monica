package takagi.ru.monica.autofill_ng.ui

import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SecondarySessionManager
import takagi.ru.monica.security.SessionManager
import java.io.InputStream
import java.io.OutputStream
import java.security.*
import java.security.cert.Certificate
import java.util.Date
import java.util.Enumeration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Real detail composition and C2 cipher path; only the uniquely named key read is held. */
class InlinePasswordDetailResponsivenessTest {
    @get:Rule val compose = createComposeRule()

    @Test fun slowLegacyReadKeepsMainResponsiveAndDisplaysOriginalSecret() = exercise("read")
    @Test fun lockDuringLegacyReadDiscardsLatePlaintext() = exercise("lock")
    @Test fun changedRecordDuringLegacyReadDiscardsOldPlaintext() = exercise("replace")
    @Test fun displayedSecretIsClearedWhenRuntimeKeysAreRevoked() = exercise("displayed_lock")
    @Test fun lockThenUnlockDuringLegacyReadCannotPublishOldResult() = exercise("lock_unlock")

    private fun exercise(action: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        runBlocking { assertTrue(SecureStorageStartup.prepareWithRetry(context) is SecureStartupResult.Ready) }
        val manager = SecurityManager(context)
        val alias = "synthetic-detail-read-${UUID.randomUUID()}"
        val key = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
        val originalSecret = "Synthetic detail original plaintext 325"
        val nextSecret = "Synthetic next record plaintext 325"
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val ciphertext = "C2|" + Base64.encodeToString(cipher.iv + cipher.doFinal(originalSecret.toByteArray()), Base64.NO_WRAP)
        SecurityManager::class.java.getDeclaredField("KEY_ALIAS_DATA_COMPAT").apply {
            isAccessible = true
            set(manager, alias)
        }
        val delegate = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val gate = Gate(alias, key, delegate)
        val provider = object : Provider("MonicaDetailReadFixture", 1.0, "Isolated slow key read") {
            init { put("KeyStore.AndroidKeyStore", SlowKeyStore::class.java.name) }
        }
        val selected = mutableStateOf<PasswordEntry?>(null)
        SessionManager.markLocked()
        SecondarySessionManager.markUnlocked()
        val authorizationGeneration = SecurityManager.dataReadGeneration.value
        compose.setContent { MaterialTheme { selected.value?.let {
            InlinePasswordDetailContent(it, manager, {}, {}, {}, authorizationGeneration, { true })
        } } }
        SlowKeyStore.gate = gate
        check(Security.insertProviderAt(provider, 1) == 1)
        // Advancing Compose's test clock may itself block inside the old remember body.
        // Keep that driver separate from the thread measuring the real main looper.
        val frameFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val frameDriver = Thread {
            runCatching { compose.waitForIdle() }.onFailure { frameFailure.set(it) }
        }.apply { isDaemon = true }
        try {
            instrumentation.runOnMainSync {
                selected.value = PasswordEntry(id = 325, title = "Slow legacy detail", website = "", username = "synthetic-user", password = ciphertext)
            }
            frameDriver.start()
            assertTrue("The actual detail must reach the legacy key provider", gate.entered.await(10, TimeUnit.SECONDS))
            val heartbeat = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
            assertTrue("Main-thread heartbeat blocked by detail decryption", heartbeat.await(750, TimeUnit.MILLISECONDS))
            when (action) {
                "lock" -> instrumentation.runOnMainSync { SessionManager.markLocked() }
                "lock_unlock" -> instrumentation.runOnMainSync {
                    SessionManager.markLocked()
                    SecondarySessionManager.markUnlocked()
                }
                "replace" -> instrumentation.runOnMainSync {
                    selected.value = PasswordEntry(id = 326, title = "Next record", website = "", username = "next-user", password = nextSecret)
                }
            }
            gate.release.countDown()
            assertTrue(gate.exited.await(5, TimeUnit.SECONDS))
            frameDriver.join(5_000)
            frameFailure.get()?.let { throw it }
            compose.waitForIdle()
            if (action == "lock" || action == "lock_unlock") {
                val cachedKey = SecurityManager::class.java.getDeclaredField("cachedCompatDataKey").apply { isAccessible = true }
                assertNull("A native key read crossing lock must not restore the cleared key cache", cachedKey.get(null))
                // Clicking a retained visibility control must not expose a revoked value.
                if (compose.onAllNodesWithContentDescription(context.getString(R.string.show)).fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithContentDescription(context.getString(R.string.show)).performClick()
                }
                compose.onNodeWithText(originalSecret).assertDoesNotExist()
            } else {
                compose.onNodeWithContentDescription(context.getString(R.string.show)).performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText(if (action == "replace") nextSecret else originalSecret).fetchSemanticsNodes().isNotEmpty()
                }
                if (action == "replace") compose.onNodeWithText(originalSecret).assertDoesNotExist()
                if (action == "displayed_lock") {
                    instrumentation.runOnMainSync { SecurityManager.clearRuntimeUnlockCache() }
                    compose.waitForIdle()
                    compose.onNodeWithText(originalSecret).assertDoesNotExist()
                }
            }
        } finally {
            gate.release.countDown()
            gate.exited.await(5, TimeUnit.SECONDS)
            frameDriver.join(5_000)
            instrumentation.runOnMainSync { selected.value = null }
            compose.waitForIdle()
            Security.removeProvider(provider.name)
            SlowKeyStore.gate = null
            SessionManager.markLocked()
        }
    }

    class Gate(val alias: String, val key: SecretKey, val delegate: KeyStore) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
    }

    class SlowKeyStore : KeyStoreSpi() {
        private val current get() = checkNotNull(gate)
        override fun engineGetEntry(alias: String, protection: KeyStore.ProtectionParameter?): KeyStore.Entry? {
            val active = current
            if (alias != active.alias) return active.delegate.getEntry(alias, protection)
            active.entered.countDown()
            try {
                check(active.release.await(15, TimeUnit.SECONDS))
                return KeyStore.SecretKeyEntry(active.key)
            } finally { active.exited.countDown() }
        }
        override fun engineGetKey(alias: String, password: CharArray?): Key? = current.delegate.getKey(alias, password)
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = current.delegate.getCertificateChain(alias)
        override fun engineGetCertificate(alias: String): Certificate? = current.delegate.getCertificate(alias)
        override fun engineGetCreationDate(alias: String): Date? = current.delegate.getCreationDate(alias)
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<Certificate>?) = error("read-only fixture")
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<Certificate>?) = error("read-only fixture")
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = error("read-only fixture")
        override fun engineDeleteEntry(alias: String) = error("read-only fixture")
        override fun engineAliases(): Enumeration<String> = current.delegate.aliases()
        override fun engineContainsAlias(alias: String): Boolean = alias == current.alias || current.delegate.containsAlias(alias)
        override fun engineSize(): Int = current.delegate.size() + 1
        override fun engineIsKeyEntry(alias: String): Boolean = alias == current.alias || current.delegate.isKeyEntry(alias)
        override fun engineIsCertificateEntry(alias: String): Boolean = current.delegate.isCertificateEntry(alias)
        override fun engineGetCertificateAlias(cert: Certificate): String? = current.delegate.getCertificateAlias(cert)
        override fun engineStore(stream: OutputStream?, password: CharArray?) = error("read-only fixture")
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
        companion object { @Volatile var gate: Gate? = null }
    }
}
