package takagi.ru.monica.bitwarden

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.service.BitwardenAccountManagementInfo
import takagi.ru.monica.bitwarden.service.BitwardenAccountManagementState
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationAction
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationHint
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationKdf
import takagi.ru.monica.bitwarden.ui.BitwardenAccountManagementDialogContent
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.theme.MonicaTheme
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Native production content with injected callbacks; no account, server, or mutation Repository. */
@RunWith(AndroidJUnit4::class)
class BitwardenAccountManagementUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = org.junit.rules.TestName()
    private var originalUnlocked = false
    private var originalMdk: ByteArray? = null
    private val mdkField = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }

    @Before fun openSyntheticSession() {
        originalUnlocked = SessionManager.isUnlocked.value
        originalMdk = (mdkField.get(null) as? ByteArray)?.copyOf()
        compose.runOnUiThread { SessionManager.markUnlocked() }
    }

    @After fun restoreOriginalSession() {
        compose.runOnUiThread {
            if (originalUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
            mdkField.set(null, originalMdk)
        }
    }

    @Test fun hintChangeRequiresSeparateReviewAndExplicitFinalConfirmation() {
        val harness = show()
        prepare("hint")
        review(harness)
        submit(harness)
        val sent = harness.submissions.single()
        assertEquals(CURRENT_PASSWORD, sent.password)
        assertTrue(sent.action is BitwardenAccountMutationAction.HintProfile)
        assertEquals(HINT, (sent.action as BitwardenAccountMutationAction.HintProfile).hint)
        compose.onNodeWithTag(CURRENT).assertDoesNotExist()
    }

    @Test fun passwordChangePreservesKnownHintAndDispatchesOnlyAfterReview() {
        val harness = show()
        prepare("password")
        review(harness)
        submit(harness)
        val action = harness.submissions.single().action as BitwardenAccountMutationAction.Password
        assertEquals(NEW_PASSWORD, action.password)
        assertEquals(NEW_PASSWORD, action.confirmation)
        assertTrue(action.hint === BitwardenAccountMutationHint.Preserve)
        assertEquals(CURRENT_PASSWORD, harness.submissions.single().password)
    }

    @Test fun kdfChangeReviewsTheChosenParametersBeforeDispatch() {
        val harness = show()
        prepare("kdf")
        fill("bitwarden_management_memory", "32")
        fill("bitwarden_management_parallelism", "2")
        review(harness)
        submit(harness)
        val action = harness.submissions.single().action as BitwardenAccountMutationAction.Kdf
        assertEquals(BitwardenAccountMutationKdf(1, 3, 32, 2), action.kdf)
        assertEquals(CURRENT_PASSWORD, harness.submissions.single().password)
    }

    @Test fun deleteRequiresTheExactDisplayedEmailAndASecondConfirmation() {
        val harness = show()
        open("delete")
        fill(CURRENT, CURRENT_PASSWORD)
        fill("bitwarden_management_confirm_email", EMAIL.lowercase())
        compose.onNodeWithTag(REVIEW).assertIsNotEnabled()
        fill("bitwarden_management_confirm_email", " $EMAIL ")
        compose.onNodeWithTag(REVIEW).assertIsNotEnabled()
        fill("bitwarden_management_confirm_email", EMAIL)
        review(harness)
        assertTrue(harness.submissions.isEmpty())
        submit(harness)
        assertTrue(harness.submissions.single().action === BitwardenAccountMutationAction.Delete)
        assertEquals(CURRENT_PASSWORD, harness.submissions.single().password)
    }

    @Test fun missingHintRequiresExplicitClearOrReplacementInsteadOfImplicitPreservation() {
        val harness = show(hintPresent = false)
        open("password")
        fillPasswordFields()
        compose.onNodeWithTag("bitwarden_management_hint_preserve").assertIsNotEnabled()
        compose.onNodeWithTag(REVIEW).assertIsNotEnabled()
        choose("bitwarden_management_hint_clear")
        review(harness)
        submit(harness)
        val clear = (harness.submissions.single().action as BitwardenAccountMutationAction.Password).hint
        assertTrue(clear is BitwardenAccountMutationHint.Replace)
        assertNull((clear as BitwardenAccountMutationHint.Replace).value)

        reset(harness)
        open("password")
        fillPasswordFields()
        compose.onNodeWithTag(REVIEW).assertIsNotEnabled()
        choose("bitwarden_management_hint_replace")
        compose.onNodeWithTag(REVIEW).assertIsNotEnabled()
        fill("bitwarden_management_hint_value", HINT)
        review(harness, expectedSubmissions = 1)
        submit(harness, expectedSubmissions = 2)
        val replacement = (harness.submissions.last().action as BitwardenAccountMutationAction.Password).hint
        assertTrue(replacement is BitwardenAccountMutationHint.Replace)
        assertEquals(HINT, (replacement as BitwardenAccountMutationHint.Replace).value)
    }

    @Test fun missingHintDoesNotSilentlyChooseThePasswordEndpoint() {
        val harness = show(hintPresent = false)
        open("hint")
        choose("bitwarden_management_hint_clear")
        fill(CURRENT, CURRENT_PASSWORD)
        compose.onNodeWithTag(REVIEW).assertIsNotEnabled()
        choose("bitwarden_management_hint_password_action")
        review(harness)
        submit(harness)
        val action = harness.submissions.single().action
        assertTrue(action is BitwardenAccountMutationAction.HintWithPassword)
        assertNull((action as BitwardenAccountMutationAction.HintWithPassword).hint)
    }

    @Test fun cancellingEachReviewedActionClearsItsInputWithoutCallingMutationOrRecovery() {
        val harness = show()
        listOf("hint", "password", "kdf", "delete").forEach { page ->
            prepare(page)
            review(harness)
            compose.onNodeWithTag("bitwarden_management_back").performClick()
            waitForTag("bitwarden_management_open_hint")
            open(page)
            assertEmpty(CURRENT)
            if (page == "password") {
                assertEmpty("bitwarden_management_new_password")
                assertEmpty("bitwarden_management_confirm_password_value")
            }
            if (page == "delete") assertEmpty("bitwarden_management_confirm_email")
            compose.onNodeWithTag("bitwarden_management_back").performClick()
            waitForTag("bitwarden_management_open_hint")
        }
        assertTrue(harness.submissions.isEmpty())
        assertEquals(0, harness.unlocks.get())
        assertEquals(0, harness.reauthentications.get())
        compose.onNodeWithTag("bitwarden_management_close").performClick()
        assertEquals(1, harness.dismissals.get())
        compose.onNodeWithTag("bitwarden_management_dialog").assertDoesNotExist()
    }

    @Test fun globalLockRejectsAnAlreadyCapturedSubmitCallbackAndClearsPasswords() {
        val harness = show()
        prepare("password")
        review(harness)
        val staleSubmit = requireNotNull(compose.onNodeWithTag(SUBMIT).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        // Invoke before recomposition: this proves the callback reads the live lock lease.
        compose.runOnUiThread { SessionManager.markLocked(); staleSubmit() }
        compose.waitForIdle()
        assertTrue(harness.submissions.isEmpty())
        compose.onNodeWithTag(SUBMIT).assertDoesNotExist()
        compose.onNodeWithTag(CURRENT).assertDoesNotExist()
        compose.runOnUiThread { SessionManager.markUnlocked() }
        compose.waitForIdle()
        compose.onNodeWithTag("bitwarden_management_open_password").assertDoesNotExist()
        reset(harness)
        open("password")
        assertEmpty(CURRENT)
        assertEmpty("bitwarden_management_new_password")
        assertEmpty("bitwarden_management_confirm_password_value")
    }

    @Test fun resettingTheReviewedAccountDiscardsAllSecretsAndRejectsItsOldCallback() {
        val harness = show()
        prepare("password")
        review(harness)
        val staleSubmit = requireNotNull(compose.onNodeWithTag(SUBMIT).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        reset(harness)
        compose.runOnUiThread { staleSubmit() }
        assertTrue(harness.submissions.isEmpty())
        open("password")
        assertEmpty(CURRENT)
        assertEmpty("bitwarden_management_new_password")
        assertEmpty("bitwarden_management_confirm_password_value")
        compose.onNodeWithTag(REVIEW).assertIsNotEnabled()
    }

    @Test fun backgroundingClearsAnUnsubmittedPasswordDraft() {
        val harness = show()
        open("password")
        fillPasswordFields()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForTag("bitwarden_management_open_password")
        open("password")
        assertEmpty(CURRENT)
        assertEmpty("bitwarden_management_new_password")
        assertEmpty("bitwarden_management_confirm_password_value")
        assertTrue(harness.submissions.isEmpty())
    }

    @Test fun largeFontMenuAndDestructiveReviewWrapWithoutClippingOrLosingControls() {
        val harness = show(scale = 1.5f)
        screenshot(testName.methodName + "-large-menu")
        assertTextLayoutsFit()
        prepare("delete")
        review(harness)
        compose.onNodeWithTag("bitwarden_management_review_action").performScrollTo().assertIsDisplayed()
        assertTextLayoutsFit()
        compose.onNodeWithTag(SUBMIT).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("bitwarden_management_back").assertIsDisplayed()
        assertTrue(harness.submissions.isEmpty())
    }

    private fun show(hintPresent: Boolean = true, scale: Float = 1f): Harness {
        val harness = Harness()
        val info = BitwardenAccountManagementInfo(73, EMAIL, "https://synthetic.example.invalid/team/vault/",
            BitwardenAccountMutationKdf(0, 600_000), hintPresent, if (hintPresent) "Existing synthetic hint" else null,
            BitwardenAccountManagementState.READY)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MonicaTheme {
                    Surface {
                        if (harness.visible.value) BitwardenAccountManagementDialogContent(
                            info = info, loading = false, busy = false, error = null, notice = null,
                            resetKey = harness.resetKey.value,
                            onMutate = { password, action -> harness.submissions += Submission(password, action) },
                            onUnlockLocal = { harness.unlocks.incrementAndGet() },
                            onReload = { harness.reloads.incrementAndGet() },
                            onDismiss = { harness.dismissals.incrementAndGet(); harness.visible.value = false },
                            onReauthenticate = { harness.reauthentications.incrementAndGet() },
                        )
                    }
                }
            }
        }
        waitForTag("bitwarden_management_open_hint")
        screenshot("home-${scale}")
        return harness
    }

    private fun prepare(page: String) {
        open(page)
        when (page) {
            "hint" -> { choose("bitwarden_management_hint_replace"); fill("bitwarden_management_hint_value", HINT) }
            "password" -> {
                fill("bitwarden_management_new_password", NEW_PASSWORD)
                fill("bitwarden_management_confirm_password_value", NEW_PASSWORD)
            }
            "kdf" -> choose("bitwarden_management_argon2")
            "delete" -> fill("bitwarden_management_confirm_email", EMAIL)
        }
        fill(CURRENT, CURRENT_PASSWORD)
    }

    private fun open(page: String) = choose("bitwarden_management_open_$page")
    private fun choose(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun fill(tag: String, value: String) { compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value) }
    private fun fillPasswordFields() {
        fill("bitwarden_management_new_password", NEW_PASSWORD)
        fill("bitwarden_management_confirm_password_value", NEW_PASSWORD)
        fill(CURRENT, CURRENT_PASSWORD)
    }
    private fun review(harness: Harness, expectedSubmissions: Int = 0) {
        assertEquals(expectedSubmissions, harness.submissions.size)
        compose.onNodeWithTag(SUBMIT).assertDoesNotExist()
        compose.onNodeWithTag(REVIEW).assertIsEnabled().performClick()
        waitForTag("bitwarden_management_review_action")
        compose.onNodeWithTag("bitwarden_management_review_action").performScrollTo().assertIsDisplayed()
        assertEquals(expectedSubmissions, harness.submissions.size)
        compose.onNodeWithTag(CURRENT).assertDoesNotExist()
        compose.onNodeWithTag(SUBMIT).assertIsEnabled()
        screenshot(testName.methodName + "-review")
    }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("personalScreenshots") != "true") return
        val safe = name.replace('.', '-')
        val output = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "bitwarden-personal-account-$safe.png")
        output.outputStream().use {
            check(compose.onNodeWithTag("bitwarden_management_dialog").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    private fun submit(harness: Harness, expectedSubmissions: Int = 1) {
        compose.onNodeWithTag(SUBMIT).performClick()
        compose.waitUntil(10_000) { harness.submissions.size == expectedSubmissions }
        compose.onNodeWithTag(SUBMIT).assertDoesNotExist()
    }
    private fun reset(harness: Harness) {
        compose.runOnUiThread { harness.resetKey.value++ }
        waitForTag("bitwarden_management_open_hint")
    }
    private fun assertEmpty(tag: String) {
        compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }
    private fun waitForTag(tag: String) {
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: ComposeTimeoutException) {
            // This class renders only hard-coded synthetic inputs; report the actual failed page.
            val tree = runCatching { compose.onNodeWithTag("bitwarden_management_dialog", useUnmergedTree = true).printToString() }
                .getOrElse { "Dialog semantics unavailable: ${it.javaClass.simpleName}" }
            throw AssertionError("Missing native state $tag after 10000 ms. Synthetic dialog:\n$tree", timeout)
        }
    }
    private fun assertTextLayoutsFit() {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        val count = nodes.fetchSemanticsNodes().size
        assertTrue(count > 0)
        repeat(count) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Native text must expose a measured layout", layouts.isNotEmpty())
            layouts.forEach { assertEquals("Rendered fontScale must match the requested large-font case", 1.5f, it.layoutInput.density.fontScale, .001f) }
            // Paragraph width can retain the parent's max width even for a short, centered
            // TextButton label. Check rendered line bounds and omitted content instead.
            val overflowing = layouts.filter { layout ->
                layout.multiParagraph.didExceedMaxLines || (0 until layout.lineCount).any { line ->
                    layout.isLineEllipsized(line) || layout.getLineLeft(line) < -1f ||
                        layout.getLineRight(line) > layout.size.width + 1f ||
                        layout.getLineBottom(line) > layout.size.height + 1f
                }
            }.joinToString("\n") { layout ->
                "text=${layout.layoutInput.text.text}; size=${layout.size}; paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}; constraints=${layout.layoutInput.constraints}; " +
                    "lines=${layout.lineCount}; maxLines=${layout.layoutInput.maxLines}; " +
                    "widthOverflow=${layout.didOverflowWidth}; heightOverflow=${layout.didOverflowHeight}; " +
                    "bounds=" + (0 until layout.lineCount).joinToString { line ->
                        "${layout.getLineLeft(line)}..${layout.getLineRight(line)} bottom=${layout.getLineBottom(line)}"
                    }
            }
            assertTrue("Large-font text must wrap instead of clipping. Node $index:\n$overflowing", overflowing.isEmpty())
        }
    }

    private class Harness {
        val visible = mutableStateOf(true)
        val resetKey = mutableStateOf(0)
        val submissions = CopyOnWriteArrayList<Submission>()
        val dismissals = AtomicInteger()
        val unlocks = AtomicInteger()
        val reauthentications = AtomicInteger()
        val reloads = AtomicInteger()
    }
    private class Submission(val password: String, val action: BitwardenAccountMutationAction) {
        override fun toString() = "SyntheticAccountSubmission(redacted)"
    }
    private companion object {
        const val EMAIL = "Person+Case@example.invalid"
        const val CURRENT_PASSWORD = "Synthetic current password 42!"
        const val NEW_PASSWORD = "Synthetic replacement password 91!"
        const val HINT = "Synthetic non-secret reminder"
        const val CURRENT = "bitwarden_management_current_password"
        const val REVIEW = "bitwarden_management_review"
        const val SUBMIT = "bitwarden_management_submit"
    }
}
