package takagi.ru.monica.security

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.ui.components.MonicaPasswordDialogAuthScreen

class MasterPasswordFallbackUiTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var fixture: KeystoreDataFailureInstrumentedTest.Fixture
    @org.junit.Before fun preparePassword() {
        fixture = KeystoreDataFailureInstrumentedTest.Fixture()
        fixture.savePasswordCiphertext("a")
        SecurityManager.clearRuntimeUnlockCache()
    }
    @org.junit.After fun closeFixture() { if (::fixture.isInitialized) fixture.close() }

    private fun show(verify: (String) -> Boolean, success: () -> Unit, cancel: () -> Unit, stageBeforeVerify: Boolean = false) {
        compose.setContent {
            MonicaPasswordDialogAuthScreen(
                settingsFlow = flowOf(AppSettings()), appName = "Monica", title = "Verify",
                subtitle = "Existing password", passwordLabel = "Password", description = "Enter password",
                confirmText = "Confirm", cancelText = "Cancel", emptyError = "Empty",
                unsupportedCharacterError = "Unsupported", minLengthError = "Too short",
                incorrectError = "Incorrect", verifyPassword = { input, attempt ->
                    if (stageBeforeVerify) fixture.manager.unlockVaultWithPassword(input, attempt) && verify(input)
                    else verify(input) && fixture.manager.unlockVaultWithPassword(input, attempt)
                }, onSuccess = { success() }, onCancel = cancel,
            )
        }
    }

    @Test fun existingPasswordIsVerifiedWithoutApplyingNewPasswordLengthRules() {
        val successes = java.util.concurrent.atomic.AtomicInteger()
        val attempts = java.util.Collections.synchronizedList(mutableListOf<String>())
        show({ attempts += it; it == "a" }, { successes.incrementAndGet() }, {})
        compose.onNodeWithText("Confirm").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("wrong")
        compose.onNodeWithText("Confirm").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Incorrect").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Incorrect").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, successes.get()) }
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("a")
        compose.onNodeWithText("Confirm").performClick()
        compose.waitUntil(5_000) { successes.get() == 1 }
        compose.runOnIdle {
            assertEquals(listOf("wrong", "a"), attempts)
            assertEquals(1, successes.get())
        }
    }

    @Test fun cancellingDoesNotVerifyOrAuthorize() {
        var verifies = 0
        var successes = 0
        var cancellations = 0
        show({ verifies++; true }, { successes++ }, { cancellations++ })
        compose.onNode(hasSetTextAction()).performTextInput("synthetic-password")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertEquals(0, verifies)
            assertEquals(0, successes)
            assertEquals(1, cancellations)
        }
    }

    @Test fun slowPasswordVerificationKeepsMainResponsive() = slowVerification(cancel = false)
    @Test fun cancellingPendingVerificationRejectsItsLateSuccess() = slowVerification(cancel = true)

    private fun slowVerification(cancel: Boolean) {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val successes = java.util.concurrent.atomic.AtomicInteger()
        val cancellations = java.util.concurrent.atomic.AtomicInteger()
        show({ input ->
            check(input == "a")
            entered.countDown()
            check(release.await(15, java.util.concurrent.TimeUnit.SECONDS))
            true
        }, { successes.incrementAndGet() }, { cancellations.incrementAndGet() }, stageBeforeVerify = true)
        compose.onNode(hasSetTextAction()).performTextInput("a")
        val clickFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val click = Thread {
            runCatching { compose.onNodeWithText("Confirm").performClick() }.onFailure { clickFailure.set(it) }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("Staged verification must not expose the runtime key", !SecurityManager.hasRuntimeUnlockCache())
            val heartbeat = java.util.concurrent.CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post { heartbeat.countDown() }
            assertTrue("Password confirmation blocked the main looper", heartbeat.await(750, java.util.concurrent.TimeUnit.MILLISECONDS))
            if (cancel) {
                compose.onNodeWithText("Cancel").performClick()
                assertEquals(1, cancellations.get())
            }
            release.countDown()
            click.join(5_000)
            clickFailure.get()?.let { throw it }
            if (cancel) {
                compose.waitForIdle()
                android.os.SystemClock.sleep(150)
                compose.runOnIdle {
                    assertEquals(0, successes.get())
                    assertTrue("Cancellation must destroy the staged key", !SecurityManager.hasRuntimeUnlockCache())
                }
            } else compose.waitUntil(5_000) { successes.get() == 1 }
        } finally {
            release.countDown()
            click.join(5_000)
        }
    }
}
