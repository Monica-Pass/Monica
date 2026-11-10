package takagi.ru.monica.ui.send

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.service.BitwardenSendAccountIdentity
import takagi.ru.monica.bitwarden.service.BitwardenSendDraft
import takagi.ru.monica.bitwarden.service.BitwardenSendDraftStore
import takagi.ru.monica.bitwarden.service.SendAuthAction
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.screens.AddEditSendScreen
import takagi.ru.monica.ui.theme.MonicaTheme

/** Public editor and link action, with synthetic in-memory accounts and drafts only. */
@RunWith(AndroidJUnit4::class)
class SendEntryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var wasUnlocked = false
    private val first = vault(9101, "alpha")
    private val second = vault(9102, "beta")
    private val locked = vault(9103, "locked").copy(isLocked = true)
    private val refreshes = AtomicInteger()
    private val exits = AtomicInteger()
    private val cleanups = AtomicInteger()
    private val saved = mutableListOf<Pair<Long, BitwardenSendDraft>>()

    @Before fun prepareIsolatedHost() {
        val user = android.os.Process.myUid() / 100000
        check(user > 0 && InstrumentationRegistry.getArguments().getString("legacyVaultwardenUser") == user.toString()) {
            "Use the task-owned synthetic Android user"
        }
        runBlocking { assertTrue(SecureStorageStartup.prepareWithRetry(context) is SecureStartupResult.Ready) }
        wasUnlocked = SessionManager.isUnlocked.value
        compose.runOnIdle { SessionManager.markUnlocked() }
    }

    @After fun restoreSession() {
        compose.runOnIdle {
            compose.activity.setContentView(android.widget.FrameLayout(compose.activity))
            if (wasUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
        }
    }

    @Test fun toolbarBackCleansAndExitsExactlyOnceDespiteLaterLifecycleEvents() {
        showEditor(initialTitle = "Toolbar synthetic title", initialText = "Toolbar synthetic private body")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).performClick()
        assertEditorClosedOnce("Toolbar synthetic private body")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEditorClosedOnce("Toolbar synthetic private body")
    }

    @Test fun systemBackCleansAndExitsExactlyOnceDespiteALaterLock() {
        showEditor(initialTitle = "System back synthetic title", initialText = "System back synthetic private body")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEditorClosedOnce("System back synthetic private body")
        compose.runOnIdle { SessionManager.markLocked() }
        compose.runOnIdle { SessionManager.markUnlocked() }
        assertEditorClosedOnce("System back synthetic private body")
    }

    @Test fun backgroundOnStopClosesAccountSheetAndEditorExactlyOnceWithoutRestoringOnResume() {
        showEditor(initialTitle = "Background synthetic title", initialText = "Background synthetic private body")
        openAccounts()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("send_account_sheet").assertDoesNotExist()
        assertEditorClosedOnce("Background synthetic private body")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEditorClosedOnce("Background synthetic private body")
    }

    @Test fun accountSheetSelectsOneUnlockedAccountWithoutLosingTypedContent() {
        showEditor()
        enter("Unsaved synthetic title", "  Synthetic private text\n  ")
        compose.onNodeWithTag("send_vault_${first.id}").assertDoesNotExist()
        openAccounts()
        compose.onNodeWithTag("send_vault_${first.id}").assertIsSelected()
        compose.onNodeWithTag("send_vault_${second.id}").assertIsNotSelected().performClick()
        compose.onNodeWithTag("send_account_sheet").assertDoesNotExist()
        assertValue("item_editor_title", "Unsaved synthetic title")
        assertValue("send_editor_text", "  Synthetic private text\n  ")
        openAccounts()
        compose.onNodeWithTag("send_vault_${first.id}").assertIsNotSelected()
        compose.onNodeWithTag("send_vault_${second.id}").assertIsSelected()
        // Locked accounts may be absent or visibly disabled, but can never be chosen.
        val lockedRows = compose.onAllNodesWithTag("send_vault_${locked.id}").fetchSemanticsNodes()
        if (lockedRows.isNotEmpty()) compose.onNodeWithTag("send_vault_${locked.id}").assertIsNotEnabled()
        capture("account-sheet")
        compose.onNodeWithTag("send_vault_${second.id}").performClick()
        compose.onNodeWithTag("send_editor_save").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, saved.size)
            assertEquals(second.id, saved.single().first)
            assertEquals("  Synthetic private text\n  ", saved.single().second.text)
        }
    }

    @Test fun submissionKeepsAccountAndDraftSwitchingDisabled() {
        showEditor(initialTitle = "Synthetic title", initialText = "Synthetic body")
        compose.onNodeWithTag("send_editor_save").assertIsEnabled().performClick()
        compose.onNodeWithTag("send_account_picker").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("send_open_drafts").assertIsNotEnabled()
        compose.onNodeWithTag("send_account_sheet").assertDoesNotExist()
        compose.onNodeWithTag("send_drafts_sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(first.id, saved.single().first) }
    }

    @Test fun openingDraftsFromNewEditorLoadsStoredContentAndPreservesItsAccount() {
        val stored = draft(second, "00000000-0000-4000-8000-000000009102", "Stored beta title", "  Stored beta body\n  ")
        showEditor(drafts = listOf(stored))
        openDrafts()
        compose.runOnIdle { assertEquals(1, refreshes.get()) }
        capture("drafts-sheet")
        compose.onNodeWithTag("send_draft_resume_${stored.id}").performScrollTo().performClick()
        compose.onNodeWithTag("send_draft_replace_confirm").assertDoesNotExist()
        assertValue("item_editor_title", stored.draft.title)
        assertValue("send_editor_text", stored.draft.text)
        compose.onNodeWithTag("send_account_picker").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("send_vault_${first.id}").assertDoesNotExist()
        capture("resumed-draft")
        compose.onNodeWithTag("send_editor_save").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(second.id, saved.single().first)
            assertEquals(stored.draft.text, saved.single().second.text)
            assertEquals(stored.draft.notes, saved.single().second.notes)
        }
    }

    @Test fun replacingDirtyFormNeedsConfirmationAndCancelPreservesExactText() {
        val stored = draft(second, "00000000-0000-4000-8000-000000009103", "Replacement title", "Replacement body")
        showEditor(drafts = listOf(stored))
        enter("  Unsaved title  ", "  Unsaved body\n\t ")
        openDrafts()
        compose.onNodeWithTag("send_draft_resume_${stored.id}").performScrollTo().performClick()
        compose.onNodeWithTag("send_draft_replace_confirm").assertIsDisplayed()
        capture("replace-confirmation")
        compose.onNodeWithTag("send_draft_replace_cancel").performClick()
        compose.onNodeWithTag("send_draft_replace_confirm").assertDoesNotExist()
        assertValue("item_editor_title", "  Unsaved title  ")
        assertValue("send_editor_text", "  Unsaved body\n\t ")
        compose.onNodeWithTag("send_account_picker").performScrollTo().assertIsEnabled()
        openDrafts()
        compose.onNodeWithTag("send_draft_resume_${stored.id}").performScrollTo().performClick()
        compose.onNodeWithTag("send_draft_replace_apply").performClick()
        assertValue("item_editor_title", stored.draft.title)
        assertValue("send_editor_text", stored.draft.text)
        compose.onNodeWithTag("send_account_picker").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
    }

    @Test fun alreadyLoadedDraftCannotSwitchToAnotherAccount() {
        val stored = draft(second, "00000000-0000-4000-8000-000000009104", "Bound title", "Bound body")
        showEditor(initialDraft = stored)
        compose.onNodeWithTag("send_account_picker").assertIsNotEnabled()
        compose.onNodeWithTag("send_account_sheet").assertDoesNotExist()
        assertValue("item_editor_title", stored.draft.title)
        compose.onNodeWithTag("send_editor_save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(second.id, saved.single().first) }
    }

    @Test fun reopeningTheSameDirtyDraftCancelsSafelyAndConfirmedReloadRestoresStoredText() {
        val stored = draft(second, "00000000-0000-4000-8000-000000009107", "Original stored title", "  Original stored body\n ")
        val other = draft(first, "00000000-0000-4000-8000-000000009111", "Other stored title", "Other stored body")
        showEditor(drafts = listOf(stored, other), initialDraft = stored)
        enter("Edited title", "  Edited body\n\t ")
        openDrafts()
        compose.onNodeWithTag("send_draft_discard_${stored.id}").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("send_draft_discard_${other.id}").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("send_draft_resume_${stored.id}").performScrollTo().performClick()
        compose.onNodeWithTag("send_draft_replace_confirm").assertIsDisplayed()
        compose.onNodeWithTag("send_draft_replace_cancel").performClick()
        assertValue("item_editor_title", "Edited title")
        assertValue("send_editor_text", "  Edited body\n\t ")
        openDrafts()
        compose.onNodeWithTag("send_draft_discard_${stored.id}").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("send_draft_resume_${stored.id}").performScrollTo().performClick()
        compose.onNodeWithTag("send_draft_replace_apply").performClick()
        assertValue("item_editor_title", stored.draft.title)
        assertValue("send_editor_text", stored.draft.text)
        compose.onNodeWithTag("send_account_picker").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(saved.isEmpty()); assertEquals(0, cleanups.get()) }
    }

    @Test fun consecutiveDifferentDraftsReloadTheirOwnValuesAndAccountBinding() {
        val alpha = draft(first, "00000000-0000-4000-8000-000000009108", "Alpha stored title", "  Alpha stored body\n ")
        val beta = draft(second, "00000000-0000-4000-8000-000000009109", "Beta stored title", "  Beta stored body\t ")
        showEditor(drafts = listOf(alpha, beta))
        openDrafts()
        compose.onNodeWithTag("send_draft_resume_${alpha.id}").performScrollTo().performClick()
        assertValue("item_editor_title", alpha.draft.title)
        assertValue("send_editor_text", alpha.draft.text)
        openDrafts()
        compose.onNodeWithTag("send_draft_resume_${beta.id}").performScrollTo().performClick()
        compose.onNodeWithTag("send_draft_replace_confirm").assertDoesNotExist()
        assertValue("item_editor_title", beta.draft.title)
        assertValue("send_editor_text", beta.draft.text)
        compose.onNodeWithTag("send_account_picker").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("send_editor_save").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, saved.size)
            assertEquals(second.id, saved.single().first)
            assertEquals(beta.draft.title, saved.single().second.title)
            assertEquals(beta.draft.text, saved.single().second.text)
        }
    }

    @Test fun uncertainSubmissionIsReadOnlyAndOnlyReconcilesTheOriginalStoredObject() {
        val stored = draft(second, "00000000-0000-4000-8000-000000009110", "Uncertain stored title", "Uncertain stored body")
            .copy(submissionUncertain = true, submittedKey = "synthetic-submission-identity")
        val reconciled = mutableListOf<BitwardenSendDraftStore.Draft>()
        showEditor(initialDraft = stored, onSubmit = { reconciled += it })
        compose.onNodeWithTag("item_editor_title").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("send_editor_text").performScrollTo().assertIsNotEnabled()
        assertValue("item_editor_title", stored.draft.title)
        assertValue("send_editor_text", stored.draft.text)
        compose.onNodeWithTag("send_account_picker").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("item_editor_add_content").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("send_editor_save").assertIsNotEnabled()
        compose.onNodeWithTag("send_draft_save").assertDoesNotExist()
        compose.runOnIdle { assertTrue(saved.isEmpty()); assertTrue(reconciled.isEmpty()) }
        compose.onNodeWithTag("send_draft_check").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, reconciled.size)
            assertSame("Reconciliation must keep the original submission identity and prepared request", stored, reconciled.single())
            assertTrue("The normal create/save callback must never run", saved.isEmpty())
        }
        compose.onNodeWithTag("send_draft_check").assertIsNotEnabled()
        compose.onNodeWithTag("send_open_drafts").assertIsNotEnabled()
    }

    @Test fun lockDismissesPendingDraftReplacementAndClearsEditorOnce() {
        val stored = draft(second, "00000000-0000-4000-8000-000000009105", "Locked replacement", "Locked secret")
        showEditor(drafts = listOf(stored))
        enter("Private original", "Private original body")
        openDrafts()
        compose.onNodeWithTag("send_draft_resume_${stored.id}").performScrollTo().performClick()
        compose.onNodeWithTag("send_draft_replace_confirm").assertIsDisplayed()
        compose.runOnIdle { SessionManager.markLocked() }
        compose.onNodeWithTag("send_draft_replace_confirm").assertDoesNotExist()
        compose.onNodeWithTag("send_drafts_sheet").assertDoesNotExist()
        compose.onNodeWithTag("item_editor_title").assertDoesNotExist()
        compose.onNodeWithText("Private original body").assertDoesNotExist()
        compose.onNodeWithTag("synthetic_send_closed").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, exits.get()); assertEquals(1, cleanups.get()); assertTrue(saved.isEmpty()) }
        compose.runOnIdle { SessionManager.markUnlocked() }
        assertEditorClosedOnce("Private original body")
        compose.onNodeWithTag("send_draft_replace_confirm").assertDoesNotExist()
        compose.onNodeWithTag("send_drafts_sheet").assertDoesNotExist()
        compose.onNodeWithText("Locked secret").assertDoesNotExist()
    }

    @Test fun largeTextKeepsAccountSelectionAndDraftEntryReachable() {
        val stored = draft(second, "00000000-0000-4000-8000-000000009106", "Synthetic large-text draft", "Large text body")
        showEditor(drafts = listOf(stored), largeText = true)
        openAccounts()
        compose.onNodeWithTag("send_vault_${second.id}").performScrollTo().assertIsDisplayed()
        assertLargeAccountText("Synthetic beta")
        assertLargeAccountText(second.email)
        capture("large-text-accounts")
        compose.onNodeWithTag("send_vault_${second.id}").performClick()
        compose.onNodeWithTag("send_open_drafts").assertIsDisplayed()
        openDrafts()
        compose.onNodeWithTag("send_draft_resume_${stored.id}").performScrollTo().assertIsDisplayed()
        capture("large-text-drafts")
        compose.onNodeWithTag("send_draft_resume_${stored.id}").performClick()
        // A user-selected account is an edit, so replacing it may also require confirmation.
        if (compose.onAllNodesWithTag("send_draft_replace_confirm").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("send_draft_replace_apply").assertIsDisplayed().performClick()
        }
        assertValue("send_editor_text", stored.draft.text)
        compose.onNodeWithTag("send_editor_save").assertIsDisplayed()
    }

    @Test fun validSearchLinkNeedsExplicitClickAndForwardsTrimmedInputWithoutAccount() {
        val link = syntheticLink()
        val query = mutableStateOf("  $link\n")
        val enabled = mutableStateOf(true)
        val opened = mutableListOf<String>()
        show { SendSearchLinkAction(query.value, enabled.value) { opened += it } }
        compose.onNodeWithTag("send_search_open_link").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
        capture("search-link-action")
        compose.onNodeWithTag("send_search_open_link").performClick()
        compose.runOnIdle { assertEquals(listOf(link), opened); enabled.value = false }
        compose.onNodeWithTag("send_search_open_link").assertIsNotEnabled()
        compose.runOnIdle { query.value = "Synthetic meeting notes" }
        compose.onNodeWithTag("send_search_open_link").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, opened.size) }
    }

    @Test fun ordinaryAndUnsafeSearchInputsNeverExposeReceiverAction() {
        val query = mutableStateOf("ordinary words")
        show { SendSearchLinkAction(query.value, true) { error("No receiver action for ordinary or unsafe input") } }
        listOf("ordinary words", "http://vault.example.test/#/send/invalid/invalid",
            syntheticLink().replace("vault.example.test", "user:secret@vault.example.test"), "").forEach {
            compose.runOnIdle { query.value = it }
            compose.onNodeWithTag("send_search_open_link").assertDoesNotExist()
        }
    }

    private fun showEditor(
        drafts: List<BitwardenSendDraftStore.Draft> = emptyList(),
        initialDraft: BitwardenSendDraftStore.Draft? = null,
        initialTitle: String = "",
        initialText: String = "",
        largeText: Boolean = false,
        onSubmit: ((BitwardenSendDraftStore.Draft) -> Unit)? = null,
    ) {
        val visible = mutableStateOf(true)
        show(largeText) {
            if (visible.value) AddEditSendScreen(
                sendState = BitwardenViewModel.SendState.Idle,
                vaults = listOf(first, second, locked), activeVault = first,
                unlockStateByVault = mapOf(first.id to BitwardenViewModel.UnlockState.Unlocked,
                    second.id to BitwardenViewModel.UnlockState.Unlocked, locked.id to BitwardenViewModel.UnlockState.Locked),
                initialTitle = initialTitle, initialText = initialText, initialStoredDraft = initialDraft,
                onSubmitStoredDraft = onSubmit,
                localDrafts = drafts, onRefreshDrafts = { refreshes.incrementAndGet() }, onDiscardDraft = {},
                onNavigateBack = { exits.incrementAndGet(); visible.value = false },
                onLeaveForeground = { cleanups.incrementAndGet() },
                onCreate = { _, _, _, _, _, _, _, _, _ -> error("Use captured draft callback") },
                onCreateFile = { _, _, _, _, _, _, _, _, _ -> error("No file upload in UI fixture") },
                onSaveDraft = { id, value, uri, name ->
                    assertNull(uri); assertNull(name); saved += id to value
                },
            ) else Text("Synthetic editor closed", Modifier.testTag("synthetic_send_closed"))
        }
    }

    private fun show(largeText: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(
                compose.activity.resources.displayMetrics.density, if (largeText) 1.5f else 1f)) {
                MonicaTheme(darkTheme = largeText) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.widthIn(max = if (largeText) 320.dp else 420.dp)) { content() }
                    }
                }
            }
        }
    }

    private fun enter(title: String, text: String) {
        compose.onNodeWithTag("item_editor_title").performScrollTo().performTextReplacement(title)
        compose.onNodeWithTag("send_editor_text").performScrollTo().performTextReplacement(text)
        compose.runOnIdle {
            compose.activity.currentFocus?.clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
    }

    private fun assertValue(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
    }

    private fun openAccounts() {
        compose.onNodeWithTag("send_account_picker").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("send_account_sheet").assertIsDisplayed()
    }

    private fun openDrafts() {
        compose.onNodeWithTag("send_open_drafts").assertIsEnabled().performClick()
        compose.onNodeWithTag("send_drafts_sheet").assertIsDisplayed()
    }

    private fun assertEditorClosedOnce(previousBody: String) {
        compose.waitForIdle()
        compose.onNodeWithTag("synthetic_send_closed").assertIsDisplayed()
        compose.onNodeWithTag("item_editor_title").assertDoesNotExist()
        compose.onNodeWithTag("send_editor_text").assertDoesNotExist()
        compose.onNodeWithTag("send_account_picker").assertDoesNotExist()
        compose.onNodeWithText(previousBody).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("One editor must navigate back exactly once", 1, exits.get())
            assertEquals("One editor must clean up exactly once", 1, cleanups.get())
            assertTrue("Exiting must never save or submit the draft", saved.isEmpty())
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // Android dialog-window fade runs outside the Compose test clock. Wait only for
        // the screenshot; behavior assertions keep their existing synchronization.
        android.os.SystemClock.sleep(500)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val folder = File(context.filesDir, "send-entry-327")
            check(folder.isDirectory || folder.mkdirs())
            File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun assertLargeAccountText(value: String) {
        val nodes = compose.onAllNodes(hasText(value) and hasAnyAncestor(hasTestTag("send_vault_${second.id}")) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        nodes.assertCountEquals(1)
        val layouts = mutableListOf<TextLayoutResult>()
        nodes[0].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertEquals("The sheet must keep the requested font scale", 1.5f, layout.layoutInput.density.fontScale, 0f)
            assertFalse("Account text must keep every line", layout.multiParagraph.didExceedMaxLines)
            repeat(layout.lineCount) { line ->
                assertFalse("Account text must not be ellipsized", layout.isLineEllipsized(line))
                assertTrue(layout.getLineLeft(line) >= -1f)
                assertTrue(layout.getLineRight(line) <= layout.size.width + 1f)
                assertTrue(layout.getLineBottom(line) <= layout.size.height + 1f)
            }
        }
        val node = nodes[0].fetchSemanticsNode()
        assertEquals(node.size.width.toFloat(), node.boundsInRoot.width, 1f)
        assertEquals(node.size.height.toFloat(), node.boundsInRoot.height, 1f)
    }

    private fun vault(id: Long, name: String) = BitwardenVault(id = id, email = "$name@example.test",
        canonicalEmail = "$name@example.test", displayName = "Synthetic $name", accountKey = "synthetic-send-$id",
        userId = "synthetic-user-$id", serverUrl = "https://vault.example.test/", apiUrl = "https://vault.example.test/api/",
        identityUrl = "https://vault.example.test/identity/", isLocked = false, isConnected = true)

    private fun draft(vault: BitwardenVault, id: String, title: String, text: String) = BitwardenSendDraftStore.Draft(
        id = id, vaultId = vault.id, account = BitwardenSendAccountIdentity.from(vault),
        draft = BitwardenSendDraft(title, text, "Synthetic preserved notes", deletionMillis = System.currentTimeMillis() + 5 * 86_400_000L,
            authAction = SendAuthAction.NONE), updatedAt = 1_800_000_000_000L)

    private fun syntheticLink(): String {
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 7 })
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 11 })
        return "https://vault.example.test/team/#/send/$id/$key"
    }
}
