package takagi.ru.monica.ui.screens

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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.security.LocalPasswordAlgorithm
import takagi.ru.monica.security.LocalVaultRecovery
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SecurePreferencesStore
import takagi.ru.monica.ui.components.PasswordVerificationContent

/** Exercises the actual bounded SecurityManager path, with synthetic isolated credentials. */
@RunWith(AndroidJUnit4::class)
class ManagedPasswordLoginUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixture: Fixture? = null
    private var visible by mutableStateOf(true)
    private var successes = 0
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = base.getString(id)

    @Before fun window() {
        compose.runOnUiThread {
            compose.activity.setShowWhenLocked(true)
            compose.activity.setTurnScreenOn(true)
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @After fun cleanup() {
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        fixture?.close()
    }

    @Test fun firstSetupRunsOnceAndNewPasswordCanUnlockAfterColdManagerOpen() {
        val f = Fixture().also { fixture = it }
        val manager = f.manager()
        show(manager, firstTime = true)
        compose.onNode(hasSetTextAction()).performTextInput(PASSWORD)
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNode(hasSetTextAction()).performTextInput(PASSWORD)
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(20_000) { successes == 1 }
        assertTrue(manager.isMasterPasswordSet())
        assertEquals(LocalPasswordAlgorithm.PBKDF2_SHA256, manager.getLocalPasswordAlgorithm())
        SecurityManager.clearRuntimeUnlockCache()
        assertTrue(f.manager().verifyMasterPassword(PASSWORD))
    }

    @Test fun existingPasswordRejectsWrongThenCommitsVerifiedRuntimeKey() {
        val f = Fixture().also { fixture = it }
        val manager = f.manager()
        assertTrue(manager.setMasterPassword(PASSWORD))
        val encrypted = manager.encryptData("Synthetic login record")
        SecurityManager.clearRuntimeUnlockCache()
        show(manager)
        compose.onNode(hasSetTextAction()).performTextInput("SyntheticWrongPassword")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(label(R.string.error_invalid_password)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, successes)
        compose.onNode(hasSetTextAction()).performTextInput(PASSWORD)
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(20_000) { successes == 1 }
        assertEquals("Synthetic login record", manager.decryptData(encrypted))
    }

    @Test fun backgroundBeforeSetupConfirmationErasesFirstPasswordAndReturnsToFirstStep() {
        val f = Fixture().also { fixture = it }
        val manager = f.manager()
        lateinit var lifecycle: TestLifecycle
        compose.runOnUiThread { lifecycle = TestLifecycle() }
        show(manager, firstTime = true, lifecycle = lifecycle)
        compose.onNode(hasSetTextAction()).performTextInput(PASSWORD)
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNodeWithText(label(R.string.confirm)).assertIsDisplayed()
        compose.runOnIdle { lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle { lifecycle.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        compose.onNodeWithText(label(R.string.set_up_password)).assertIsDisplayed().assertIsNotEnabled()
        assertFalse(manager.isMasterPasswordSet())
        assertEquals(0, successes)
    }

    @Test fun changePasswordScreenRejectsWrongPasswordThenConfirmsOnlyACommittedReplacement() {
        val f = Fixture().also { fixture = it }
        val manager = f.manager()
        assertTrue(manager.setMasterPassword(PASSWORD))
        val encrypted = manager.encryptData("Synthetic change-password record")
        val replacement = "SyntheticReplacement327Password!"
        compose.setContent { MaterialTheme {
            if (visible) ChangePasswordScreen(onNavigateBack = { visible = false }, securityManager = manager)
        } }
        fun fill(current: String) {
            compose.onNodeWithTag("change-password-current").performScrollTo().performTextInput(current)
            compose.onNodeWithTag("change-password-new").performScrollTo().performTextInput(replacement)
            compose.onNodeWithTag("change-password-confirm").performScrollTo().performTextInput(replacement)
            compose.onNodeWithTag("change-password-submit").performScrollTo().performClick()
        }
        fill("WrongSyntheticPassword")
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(label(R.string.local_password_protection_reset_failed)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText(label(R.string.change_password_success_title)).assertCountEquals(0)
        assertTrue(f.manager().verifyMasterPassword(PASSWORD))
        fill(PASSWORD)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(label(R.string.change_password_success_title)).fetchSemanticsNodes().isNotEmpty()
        }
        SecurityManager.clearRuntimeUnlockCache()
        val reopened = f.manager()
        assertFalse(reopened.verifyMasterPassword(PASSWORD))
        assertTrue(reopened.verifyMasterPassword(replacement))
        assertEquals("Synthetic change-password record", reopened.decryptData(encrypted))
    }

    @Test fun changePasswordScreenBackgroundClearsInputsAndKeepsTheOriginalCredentials() {
        val f = Fixture().also { fixture = it }
        val manager = f.manager()
        assertTrue(manager.setMasterPassword(PASSWORD))
        lateinit var lifecycle: TestLifecycle
        compose.runOnUiThread { lifecycle = TestLifecycle() }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) { MaterialTheme {
                if (visible) ChangePasswordScreen(onNavigateBack = { visible = false }, securityManager = manager)
            } }
        }
        compose.onNodeWithTag("change-password-current").performScrollTo().performTextInput(PASSWORD)
        compose.onNodeWithTag("change-password-new").performScrollTo().performTextInput("SyntheticNotSubmitted327!")
        compose.runOnUiThread { lifecycle.registry.currentState = Lifecycle.State.CREATED }
        compose.waitUntil { !visible }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertTrue(f.manager().verifyMasterPassword(PASSWORD))
        assertFalse(f.manager().verifyMasterPassword("SyntheticNotSubmitted327!"))
    }

    private fun show(manager: SecurityManager, firstTime: Boolean = false, lifecycle: LifecycleOwner? = null) {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides (lifecycle ?: LocalLifecycleOwner.current)) {
                MaterialTheme {
                    if (visible) PasswordVerificationContent(
                        isFirstTime = firstTime, persistVaultUnlockToSession = false,
                        onVerifyPassword = { error("Synchronous verification callback must not run") },
                        onSetPassword = { error("Synchronous setup callback must not run") },
                        onSuccess = { successes++; visible = false }, passwordSecurityManager = manager,
                    )
                }
            }
        }
    }

    private class TestLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private class Fixture : AutoCloseable {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "managed-login-327-${UUID.randomUUID()}-"
        val root = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        val names = linkedSetOf<String>()
        val alias = prefix + "master"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply {
                dataDir = root.absolutePath
            }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names += name
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        val recovery = LocalVaultRecovery(context)
        init { SecurityManager.clearRuntimeUnlockCache() }
        fun manager(): SecurityManager {
            val active = recovery.activeStore()
            val opened = SecurePreferencesStore.openRaw(context, active?.first ?: SecurePreferencesStore.MONICA,
                active?.second ?: alias)
            recovery.validate(opened.preferences)
            return SecurityManager.fromPrepared(context, opened.copy(preferences = recovery.wrap(opened.preferences))).also { m ->
                for (field in listOf("KEY_ALIAS_DATA", "KEY_ALIAS_DATA_COMPAT"))
                    SecurityManager::class.java.getDeclaredField(field).apply { isAccessible = true }.set(m, prefix + field)
            }
        }
        override fun close() {
            SecurityManager.clearRuntimeUnlockCache()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val aliases = listOf(alias, prefix + "KEY_ALIAS_DATA", prefix + "KEY_ALIAS_DATA_COMPAT") +
                names.filter { it.startsWith("monica_recovered_") }.map { it.replace("monica_recovered_", "monica_recovered_key_") }
            aliases.forEach { if (keys.containsAlias(it)) keys.deleteEntry(it) }
            names.forEach { base.deleteSharedPreferences(prefix + it) }
            check(root.parentFile == base.cacheDir && root.name.startsWith("managed-login-327-"))
            root.deleteRecursively()
        }
    }
    companion object { private const val PASSWORD = "SyntheticLogin327Password!" }
}
