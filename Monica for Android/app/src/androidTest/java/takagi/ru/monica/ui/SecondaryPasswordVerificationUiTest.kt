package takagi.ru.monica.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.security.BoundedSecureDataRead
import takagi.ru.monica.security.LocalVaultRecovery
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SecurePreferencesStore
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.components.SecondaryPasswordVerificationDialog

/** The actual shared secondary dialog and real KDF/MDK; the latch delays only final staging. */
@RunWith(AndroidJUnit4::class)
class SecondaryPasswordVerificationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var fixture: Fixture
    private lateinit var lifecycle: TestLifecycle
    private var visible by mutableStateOf(true)
    private var successes = 0
    private var dismissals = 0
    private var requirePassword by mutableStateOf(true)
    private var confirmEnabled by mutableStateOf(true)
    private val requestCurrent = AtomicBoolean(true)
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = base.getString(id)

    @Before fun setup() {
        fixture = Fixture()
        assertTrue(fixture.manager.setMasterPassword(PASSWORD))
        fixture.ciphertext = fixture.manager.encryptData("Synthetic secondary record")
        SecurityManager.clearRuntimeUnlockCache()
        compose.runOnUiThread {
            lifecycle = TestLifecycle()
            compose.activity.setShowWhenLocked(true)
            compose.activity.setTurnScreenOn(true)
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                MaterialTheme {
                    if (visible) SecondaryPasswordVerificationDialog(
                        manager = fixture.manager,
                        onDismiss = { dismissals++; visible = false },
                        onSuccess = { generation ->
                            assertTrue(fixture.manager.isDataReadCurrent(generation))
                            successes++; visible = false
                        },
                        isRequestCurrent = requestCurrent::get,
                        requirePassword = requirePassword,
                        confirmEnabled = confirmEnabled,
                    )
                }
            }
        }
    }

    @After fun cleanup() {
        fixture.onRawPreferenceRead = null
        release.countDown()
        compose.runOnIdle { visible = false }
        drainWorker()
        fixture.close()
    }

    @Test fun wrongPasswordCannotPublishAndCorrectPasswordCommitsOnce() {
        submit("SyntheticIncorrect327")
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(label(R.string.current_password_incorrect)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, successes)
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
        submit(PASSWORD)
        compose.waitUntil(20_000) { successes == 1 }
        assertEquals("Synthetic secondary record", fixture.manager.decryptData(fixture.ciphertext))
        assertEquals(0, dismissals)
    }

    @Test fun cancelDuringVerifiedMdkStagingRejectsLateResult() {
        stallThenSubmit()
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        finishRejected()
        assertEquals(1, dismissals)
    }

    @Test fun pauseWithoutStopRejectsLateResultAndRemovesPassword() {
        stallThenSubmit()
        compose.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.STARTED }
        finishRejected()
        assertEquals(1, dismissals)
    }

    @Test fun lockDuringVerifiedMdkStagingRejectsLateResult() {
        stallThenSubmit()
        compose.runOnIdle { SessionManager.markLocked() }
        finishRejected()
        assertEquals(1, dismissals)
    }

    @Test fun disposedDialogCannotPublishVerifiedMdk() {
        stallThenSubmit()
        compose.runOnIdle { visible = false }
        // runOnIdle waits before its action. Observe the resulting removal and its
        // DisposableEffect cleanup before allowing the still-blocked worker to finish.
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.confirm)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.cancel)).assertDoesNotExist()
        assertEquals(0, successes)
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
        finishRejected()
        assertEquals(0, dismissals)
    }

    @Test fun replacedCallerTargetCannotConsumeVerification() {
        stallThenSubmit()
        requestCurrent.set(false)
        finishRejected()
        assertEquals(1, dismissals)
    }

    @Test fun explicitNoPasswordPolicyStillRequiresConfirmationAndCannotUnlockRuntime() {
        compose.runOnIdle { requirePassword = false }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertEquals(0, successes)
        compose.onNodeWithText(label(R.string.confirm)).performClick()
        compose.waitUntil { successes == 1 }
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
    }

    @Test fun noPasswordConfirmationRejectsChangedTargetAndDisabledConfirmation() {
        compose.runOnIdle { requirePassword = false; confirmEnabled = false }
        compose.onNodeWithText(label(R.string.confirm)).assertIsNotEnabled()
        compose.runOnIdle { confirmEnabled = true; requestCurrent.set(false) }
        compose.onNodeWithText(label(R.string.confirm)).performClick()
        compose.runOnIdle { assertEquals(0, successes) }
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
    }

    private fun submit(password: String) {
        compose.onNode(hasSetTextAction()).performTextInput(password)
        compose.onNodeWithText(label(R.string.confirm)).performClick()
    }

    private fun stallThenSubmit() {
        val armed = AtomicBoolean(true)
        fixture.onRawPreferenceRead = {
            if (Thread.currentThread().stackTrace.any {
                it.className.contains("SecurityManager\$PasswordVerificationAttempt") && it.methodName.startsWith("stage")
            } && armed.compareAndSet(true, false)) {
                check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS)) { "Test staging release missing" }
            }
        }
        submit(PASSWORD)
        compose.waitUntil(20_000) { entered.count == 0L }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertEquals(0, successes)
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
    }

    private fun finishRejected() {
        release.countDown()
        drainWorker()
        compose.waitForIdle()
        assertEquals(0, successes)
        assertFalse("Late verified MDK must not escape the cancelled attempt", SecurityManager.hasRuntimeUnlockCache())
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        fixture.onRawPreferenceRead = null
        assertTrue(fixture.manager.unlockVaultWithPassword(PASSWORD))
        assertEquals("Synthetic secondary record", fixture.manager.decryptData(fixture.ciphertext))
    }

    private fun drainWorker() = runBlocking {
        assertEquals(true, BoundedSecureDataRead.readChecked(isCurrent = { true }) { true })
    }

    private class TestLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private class Fixture : AutoCloseable {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "secondary-password-327-${UUID.randomUUID()}-"
        val root = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        val names = linkedSetOf<String>()
        val alias = prefix + "master"
        @Volatile var onRawPreferenceRead: (() -> Unit)? = null
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply {
                dataDir = root.absolutePath
            }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names += name
                val raw = base.getSharedPreferences(prefix + name, mode)
                // Instrument only the synthetic backing store. Keep SecurityManager's real
                // recovery/checked wrappers visible to LocalVaultRecovery.enroll/unwrap.
                return object : SharedPreferences by raw {
                    override fun getAll(): MutableMap<String, *> {
                        val snapshot = raw.all
                        onRawPreferenceRead?.invoke()
                        return snapshot
                    }
                }
            }
        }
        val manager: SecurityManager
        lateinit var ciphertext: String
        init {
            SecurityManager.clearRuntimeUnlockCache()
            val recovery = LocalVaultRecovery(context)
            val opened = SecurePreferencesStore.openRaw(context, SecurePreferencesStore.MONICA, alias)
            manager = SecurityManager.fromPrepared(context, opened.copy(preferences = recovery.wrap(opened.preferences)))
            for (field in listOf("KEY_ALIAS_DATA", "KEY_ALIAS_DATA_COMPAT"))
                SecurityManager::class.java.getDeclaredField(field).apply { isAccessible = true }.set(manager, prefix + field)
        }
        override fun close() {
            SecurityManager.clearRuntimeUnlockCache()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            listOf(alias, prefix + "KEY_ALIAS_DATA", prefix + "KEY_ALIAS_DATA_COMPAT").forEach {
                if (keys.containsAlias(it)) keys.deleteEntry(it)
            }
            names.forEach { base.deleteSharedPreferences(prefix + it) }
            check(root.parentFile == base.cacheDir && root.name.startsWith("secondary-password-327-"))
            root.deleteRecursively()
        }
    }
    companion object { private const val PASSWORD = "SyntheticSecondary327Password!" }
}
