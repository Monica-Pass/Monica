package takagi.ru.monica.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.security.LocalPasswordCredentials
import takagi.ru.monica.security.LocalVaultRecovery
import takagi.ru.monica.security.PasswordKdf
import takagi.ru.monica.security.PreparedSecureStore
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SecurePreferencesStore

/** Real encrypted credential publications; the old process preference cache is frozen explicitly. */
@RunWith(AndroidJUnit4::class)
class SecureStoreGenerationGateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixture: Fixture? = null
    private var mounted by mutableStateOf(true)
    private var reloads = 0
    private val events = mutableListOf<String>()
    private lateinit var lifecycle: TestLifecycle

    @After fun cleanup() {
        compose.runOnIdle { mounted = false }
        compose.waitForIdle()
        fixture?.close()
    }

    @Test fun resumeWithoutStopDetectsForeignCredentialsAndUnmountsBeforeReload() {
        val f = Fixture().also { fixture = it }
        show(f.manager)
        compose.onNodeWithText(PROTECTED).assertIsDisplayed()
        compose.runOnIdle { lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        f.publishOtherCredentials()
        compose.runOnIdle { lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        assertReloadedOnce()
        val lockedGeneration = SecurityManager.dataReadGeneration.value
        compose.runOnIdle {
            lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.waitForIdle()
        assertEquals("A retired handle must not repeatedly revoke the generation", lockedGeneration,
            SecurityManager.dataReadGeneration.value)
        assertEquals(1, reloads)
    }

    @Test fun observedLockGenerationRetiresStaleGraphAndDoesNotReloadAgain() {
        val f = Fixture().also { fixture = it }
        show(f.manager)
        compose.onNodeWithText(PROTECTED).assertIsDisplayed()
        f.publishOtherCredentials()
        SecurityManager.clearRuntimeUnlockCache()
        assertReloadedOnce()
        compose.runOnIdle { SecurityManager.clearRuntimeUnlockCache() }
        compose.waitForIdle()
        assertEquals(1, reloads)
        compose.onNodeWithText(PROTECTED).assertDoesNotExist()
    }

    @Test fun currentCredentialsSurviveOrdinaryResumeAndLockGenerationChecks() {
        val f = Fixture().also { fixture = it }
        show(f.manager)
        compose.runOnIdle {
            lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            SecurityManager.clearRuntimeUnlockCache()
        }
        compose.onNodeWithText(PROTECTED).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, reloads); assertTrue(events.isEmpty()) }
    }

    private fun show(manager: SecurityManager) {
        compose.runOnUiThread { lifecycle = TestLifecycle() }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                    if (mounted) SecureStoreGenerationGate(manager, onReload = {
                        // The gate must dispose the old sensitive subtree before this callback.
                        events += "reload"
                        reloads++
                    }) {
                        DisposableEffect(Unit) { onDispose { events += "content-disposed" } }
                        Text(PROTECTED)
                    }
                }
            }
        }
    }

    private fun assertReloadedOnce() {
        compose.waitUntil(5_000) { reloads == 1 }
        compose.onNodeWithText(PROTECTED).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("content-disposed", "reload"), events) }
    }

    private class TestLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private class Fixture : AutoCloseable {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "generation-gate-327-${UUID.randomUUID()}-"
        val root = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        private val names = linkedSetOf<String>()
        private val oldViews = mutableMapOf<String, FrozenPreferences>()
        private fun context(oldCache: Boolean) = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply {
                dataDir = root.absolutePath
            }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names += name
                val raw = base.getSharedPreferences(prefix + name, mode)
                return if (oldCache) oldViews.getOrPut(name) { FrozenPreferences(raw) } else raw
            }
        }
        private val writerContext = context(false)
        private val oldContext = context(true)
        private val recovery = LocalVaultRecovery(writerContext)
        private val prefs = SecurePreferencesStore.openRaw(writerContext, SecurePreferencesStore.MONICA,
            MasterKey.DEFAULT_MASTER_KEY_ALIAS).preferences
        private val password = "SyntheticGate327Password!"
        private val mdk = ByteArray(32) { (it + 19).toByte() }
        val manager: SecurityManager
        init {
            PreparedSecureStore.invalidate()
            SecurityManager.clearRuntimeUnlockCache()
            enroll(password)
            manager = SecurityManager.fromPrepared(oldContext, PreparedSecureStore.fresh(oldContext))
            oldViews.values.forEach { it.freeze() }
            check(manager.isPreparedStoreCurrent())
        }
        private fun enroll(value: String) {
            val updates = LocalPasswordCredentials.create(value, mdk, PasswordKdf.PBKDF2_600K) + ("mdk_ready" to true)
            recovery.enroll(prefs, value, updates, expectedCredentials = prefs.all.toMap())
        }
        fun publishOtherCredentials() {
            enroll("SyntheticChangedGate327Password!")
            recovery.validate(prefs)
            // The old handle still reads its old encrypted mMap; the canonical transaction is valid.
            val current = LocalPasswordCredentials.unwrap("SyntheticChangedGate327Password!", prefs.all)
            try { assertArrayEquals(mdk, current) } finally { current.fill(0) }
        }
        override fun close() {
            PreparedSecureStore.invalidate()
            SecurityManager.clearRuntimeUnlockCache()
            PreparedSecureStore.clearMirrorPending(root.absolutePath)
            names.forEach { base.deleteSharedPreferences(prefix + it) }
            mdk.fill(0)
            check(root.parentFile == base.cacheDir && root.name.startsWith("generation-gate-327-"))
            root.deleteRecursively()
            // The platform default master alias is shared and deliberately retained.
        }
    }

    private class FrozenPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        @Volatile private var snapshot: Map<String, *>? = null
        fun freeze() { snapshot = delegate.all.toMap() }
        override fun getAll(): MutableMap<String, *> = snapshot?.toMutableMap() ?: delegate.all
        override fun getString(key: String?, defValue: String?): String? {
            val saved = snapshot ?: return delegate.getString(key, defValue)
            return saved[key] as? String ?: defValue
        }
        override fun contains(key: String?): Boolean = snapshot?.containsKey(key) ?: delegate.contains(key)
    }
    companion object { private const val PROTECTED = "Synthetic protected contents" }
}
