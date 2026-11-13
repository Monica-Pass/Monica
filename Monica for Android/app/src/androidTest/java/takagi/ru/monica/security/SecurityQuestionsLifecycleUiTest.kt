package takagi.ru.monica.security

import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.ui.screens.SecurityQuestionsSetupScreen
import takagi.ru.monica.ui.screens.SecurityQuestionsVerificationScreen

/** Actual screen/worker/storage call sites, using only RecoveryTestFixture's synthetic store. */
@RunWith(AndroidJUnit4::class)
class SecurityQuestionsLifecycleUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before fun keepTestWindowVisible() {
        compose.runOnUiThread {
            compose.activity.setShowWhenLocked(true)
            compose.activity.setTurnScreenOn(true)
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Test fun setupBackAfterHashingBeforeCommitPreservesOriginalQuestionsAndRecoveryEnvelope() =
        saveRevokedAtCommitGate(pause = false)

    @Test fun setupPauseAfterHashingBeforeCommitPreservesOriginalQuestionsAndEndsPage() =
        saveRevokedAtCommitGate(pause = true)

    private fun saveRevokedAtCommitGate(pause: Boolean) = RecoveryTestFixture().use { f ->
        val owner = owner()
        val field = SecurityManager::class.java.getDeclaredField("sharedPreferences").apply { isAccessible = true }
        val original = field.get(f.manager) as SharedPreferences
        val before = original.all.toMap()
        val recoveryFile = File(f.root, "files/local-vault-recovery/recovery.json")
        val beforeRecovery = recoveryFile.readBytes()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backs = AtomicInteger()
        val successes = AtomicInteger()
        var shown by mutableStateOf(true)
        val observed = object : SharedPreferences by original {
            override fun edit(): SharedPreferences.Editor {
                // setSecurityQuestions has already completed both PBKDF hashes when edit is requested.
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Test commit gate was not released" }
                return original.edit()
            }
        }
        field.set(f.manager, observed)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    MaterialTheme {
                        if (shown) SecurityQuestionsSetupScreen(f.manager,
                            onNavigateBack = { backs.incrementAndGet(); shown = false },
                            onSetupComplete = { successes.incrementAndGet(); shown = false })
                    }
                }
            }
            fillSetup(f)
            compose.onNodeWithText(f.base.getString(R.string.update_questions)).performScrollTo().performClick()
            assertTrue("Expected real save worker at the storage gate", entered.await(5, TimeUnit.SECONDS))
            if (pause) compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            else compose.onNodeWithContentDescription(f.base.getString(R.string.back)).performClick()
            release.countDown()
            drainSecureWorker()
            assertEquals("Cancelled page must not persist new questions", before, original.all)
            assertArrayEquals("Cancelled save must retain the original authenticated recovery envelope",
                beforeRecovery, recoveryFile.readBytes())
            compose.runOnIdle {
                assertEquals(1, backs.get())
                assertEquals(0, successes.get())
            }
            compose.onNodeWithText(f.base.getString(R.string.update_questions)).assertDoesNotExist()
            // The post-hash edit hook must not wrap the independent password-auth enrollment:
            // its anonymous delegate is not the store's recognized MirroredPreferences layer.
            field.set(f.manager, original)
            f.assertRecord(f.password)
        } finally {
            release.countDown()
            compose.runOnIdle { shown = false }
            drainSecureWorker()
            field.set(f.manager, original)
        }
    }

    @Test fun setupPauseResumeEndsOldPageAndFreshEntryCanSave() = RecoveryTestFixture().use { f ->
        val owner = owner()
        var shown by mutableStateOf(true)
        val backs = AtomicInteger()
        val successes = AtomicInteger()
        val before = f.prefs.all.toMap()
        val recoveryFile = File(f.root, "files/local-vault-recovery/recovery.json")
        val beforeRecovery = recoveryFile.readBytes()
        try {
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) { MaterialTheme {
                    if (shown) SecurityQuestionsSetupScreen(f.manager,
                        onNavigateBack = { backs.incrementAndGet(); shown = false },
                        onSetupComplete = { successes.incrementAndGet(); shown = false })
                } }
            }
            fillSetup(f)
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            assertEquals("Paused page must end rather than retain an irrevocably inactive save scope", 1, backs.get())
            compose.onNodeWithText(f.base.getString(R.string.update_questions)).assertDoesNotExist()
            assertEquals(before, f.prefs.all)
            assertArrayEquals(beforeRecovery, recoveryFile.readBytes())
            compose.runOnIdle { shown = true }
            fillSetup(f)
            compose.onNodeWithText(f.base.getString(R.string.update_questions)).performScrollTo().performClick()
            compose.waitUntil(5_000) { successes.get() == 1 }
            val saved = checkNotNull(f.manager.exportSecurityQuestionsForBackup())
            assertTrue(SecurityAnswerHash.verify("Synthetic new first answer", saved.answer1Hash))
            assertTrue(SecurityAnswerHash.verify("Synthetic new second answer", saved.answer2Hash))
            f.assertRecord(f.password)
        } finally {
            compose.runOnIdle { shown = false }
            drainSecureWorker()
        }
    }

    @Test fun verificationCooldownRefreshesBackendStatusWithoutLeavingScreen() = RecoveryTestFixture().use { f ->
        repeat(3) { assertNull(f.manager.beginRecoveryWithAnswers("wrong", "wrong") {}) }
        assertTrue(f.manager.recoveryAttemptStatus().blocked)
        val owner = owner()
        var shown by mutableStateOf(true)
        val backs = AtomicInteger()
        try {
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) { MaterialTheme {
                    if (shown) SecurityQuestionsVerificationScreen(f.manager,
                        onNavigateBack = { backs.incrementAndGet(); shown = false },
                        onVerificationSuccess = { fail("No credential prompt was authorized by this test") })
                } }
            }
            compose.onNodeWithTag("recovery-answer-1").performScrollTo().performTextInput("answer one")
            compose.onNodeWithTag("recovery-answer-2").performScrollTo().performTextInput("answer two")
            drainSecureWorker()
            compose.onNodeWithTag("recovery-verify-submit").performScrollTo().assertIsNotEnabled()
            // Advance only the fixture's persisted clock origin through the existing prior-boot path.
            // No system clock is changed, no production limiter is cleared, and status() still decides expiry.
            val stateFile = File(f.root, "no_backup/recovery-attempts/state.json")
            val state = JSONObject(stateFile.readText())
            val boot = Settings.Global.getInt(f.context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            state.put("wall", System.currentTimeMillis() - RecoveryAttemptLimiter.COOLDOWN - 1)
                .put("elapsed", SystemClock.elapsedRealtime().coerceAtLeast(0))
                .put("boot", if (boot == Int.MAX_VALUE) Int.MIN_VALUE else boot + 1)
            stateFile.writeText(state.toString())
            compose.waitUntil(5_000) {
                compose.onNodeWithTag("recovery-verify-submit").fetchSemanticsNode().config
                    .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not()
            }
            compose.onNodeWithTag("recovery-verify-submit").assertIsEnabled()
            assertEquals(0, backs.get())
            assertEquals(0, f.manager.recoveryAttemptStatus().attempts)
            f.assertRecord(f.password)
        } finally {
            compose.runOnIdle { shown = false }
            drainSecureWorker()
        }
    }

    private fun owner() = Owner().also { owner ->
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
    }

    private fun fillSetup(f: RecoveryTestFixture) {
        val answers = compose.onAllNodes(hasSetTextAction() and hasText(f.base.getString(R.string.your_answer)))
        answers[0].performScrollTo().performTextInput("Synthetic new first answer")
        answers[1].performScrollTo().performTextInput("Synthetic new second answer")
    }

    private fun drainSecureWorker() = runBlocking {
        assertEquals("Expected all previous real secure workers to finish", true,
            BoundedSecureDataRead.readChecked(timeoutMillis = 5_000, isCurrent = { true }) { true })
    }
}
