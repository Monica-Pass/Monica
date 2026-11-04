package takagi.ru.monica.bitwarden

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.api.SendApiResponse
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.mapper.BitwardenSendMapper
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.repository.BitwardenRepository.SendMutationResult
import takagi.ru.monica.bitwarden.service.BitwardenSendDraft
import takagi.ru.monica.bitwarden.service.SendPasswordAction
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.Language
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenSend
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.ui.screens.AddEditSendScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.LocaleHelper

/**
 * Real production Repository/Room and AddEditSendScreen, backed by synthetic local HTTP.
 * No real accounts, global settings, migrations, or other vault rows are changed.
 * Optional: -e sendManagementScreenshots true, -e sendLargeFont true, -e sendDark true.
 * Images go to filesDir/editor-shapes-325; HTTP fixtures retain their prior external copy too.
 * Pending review/compilation/device execution; source alone does not establish a passing result.
 */
@RunWith(AndroidJUnit4::class)
class BitwardenSendManagementInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val localized get() = android.view.ContextThemeWrapper(compose.activity, compose.activity.theme).apply {
        applyOverrideConfiguration(Configuration(compose.activity.resources.configuration).apply {
            setLocale(java.util.Locale.ENGLISH)
        })
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val testFontScale get() = if (InstrumentationRegistry.getArguments().getString("sendLargeFont") == "true") 1.5f else 1f
    private val testDark get() = InstrumentationRegistry.getArguments().getString("sendDark") == "true"
    private fun editorConfiguration() = Configuration(localized.resources.configuration).apply { fontScale = testFontScale }

    @org.junit.Before fun unlockSyntheticRuntime() {
        val security = takagi.ru.monica.security.SecurityManager(context)
        val password = InstrumentationRegistry.getArguments().getString("testMasterPassword") ?: "Synthetic Bitwarden settings fixture 325!"
        if (!security.isMasterPasswordSet()) check(security.setMasterPassword(password))
        if (!security.isVaultRuntimeUnlocked()) check(security.unlockVaultWithPassword(password))
        takagi.ru.monica.security.SessionManager.markUnlocked()
    }

    @Test fun textEditRoundTripsExactContentPasswordThreeStatesAndDatesThroughHttpAndRoom() = runBlocking {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            try {
                fixture.initialize()
                val original = fixture.seed(BitwardenSend.TYPE_TEXT)
                val originalDocument = fixture.document(original)
                val originalPassword = originalDocument["password"]
                val keep = fixture.draft(original).copy(
                    title = "  Edited synthetic text  ", text = "  text\n\t ", notes = "  notes\n ",
                    maxAccessCount = 9, hideEmail = true, hiddenText = true, disabled = true,
                    expirationMillis = null,
                )
                var saved = success(fixture.repository.updateSend(original, keep))
                fixture.assertStored(saved, keep, original)
                assertEquals(originalPassword, fixture.document(saved)["password"])
                assertEquals(JsonNull, fixture.putBodies.last()["password"])
                assertEquals(0, fixture.passwordRemovals.get())
                assertOpaqueIdentity(originalDocument, fixture.document(saved))

                val replace = keep.copy(
                    passwordAction = SendPasswordAction.REPLACE, password = "  new synthetic password  ",
                    disabled = false, expirationMillis = keep.deletionMillis - 86_400_000L,
                )
                saved = success(fixture.repository.updateSend(saved, replace))
                fixture.assertStored(saved, replace, original)
                assertTrue(saved.hasPassword)
                val passwordHash = fixture.document(saved)["password"]!!.jsonPrimitive.content
                assertEquals(BitwardenCrypto.hashSendPassword(replace.password!!, fixture.material), passwordHash)
                assertNotEquals(BitwardenCrypto.hashSendPassword(replace.password!!.trim(), fixture.material), passwordHash)
                assertFalse(fixture.putBodies.last().toString().contains(replace.password!!))
                assertEquals(JsonPrimitive(1), fixture.document(saved)["authType"])

                val remove = replace.copy(passwordAction = SendPasswordAction.REMOVE, password = null,
                    notes = " ", expirationMillis = null)
                saved = success(fixture.repository.updateSend(saved, remove))
                fixture.assertStored(saved, remove, original)
                assertFalse(saved.hasPassword)
                assertEquals(JsonNull, fixture.document(saved)["password"])
                assertEquals(1, fixture.passwordRemovals.get())
                assertEquals(JsonPrimitive(1), fixture.putBodies.last()["authType"])
                assertEquals(JsonNull, fixture.putBodies.last()["password"])
                assertEquals(JsonPrimitive(2), fixture.document(saved)["authType"])
                assertEquals(3, fixture.putBodies.size)
                assertOpaqueIdentity(originalDocument, fixture.document(saved))
                fixture.assertNoUnexpectedRequests()
            } finally { fixture.close() }
        }
    }

    @Test fun fileEditPreservesIdKeyAndCiphertextRejectsRemoteRevisionsAndReadsBackLostResponse() = runBlocking {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            try {
                fixture.initialize()
                val original = fixture.seed(BitwardenSend.TYPE_FILE)
                val originalDocument = fixture.document(original)
                val bytesBefore = fixture.fetchStoredFileBytes()
                assertArrayEquals(fixture.fileCiphertext, bytesBefore)
                assertArrayEquals(fixture.filePlaintext, fixture.decryptFile(bytesBefore))
                val edit = fixture.draft(original).copy(title = "Edited synthetic file", notes = " file note ", disabled = true)
                var saved = success(fixture.repository.updateSend(original, edit))
                fixture.assertStored(saved, edit, original)
                assertEquals(originalDocument["file"], fixture.document(saved)["file"])
                assertOpaqueIdentity(originalDocument, fixture.document(saved))
                assertArrayEquals(bytesBefore, fixture.fetchStoredFileBytes())

                // Existing editor baseline is stale before the first server read: no replacement PUT.
                fixture.changeRevision(saved)
                val putCount = fixture.putBodies.size
                val localBefore = fixture.row(saved)
                assertTrue(fixture.repository.updateSend(saved, edit) is SendMutationResult.Error)
                assertEquals(putCount, fixture.putBodies.size)
                assertEquals(localBefore, fixture.row(saved))
                saved = fixture.reloadLocal(saved)

                // A second reader/writer changes the remote revision between the two GETs.
                fixture.changeOnRead.set(fixture.reads.get() + 2)
                val secondBaseline = fixture.row(saved)
                assertTrue(fixture.repository.updateSend(saved, edit) is SendMutationResult.Error)
                assertEquals(putCount, fixture.putBodies.size)
                assertEquals(secondBaseline, fixture.row(saved))
                saved = fixture.reloadLocal(saved)

                // Commit before dropping the response. Assert the actual network PUT count, including
                // any automatic HTTP-client retries; never weaken this to count only Kotlin calls.
                fixture.loseNextPutResponse.set(true)
                val recoveredDraft = edit.copy(title = "Recovered after lost response", disabled = false,
                    passwordAction = SendPasswordAction.REPLACE, password = " file password ")
                val recovered = success(fixture.repository.updateSend(saved, recoveredDraft))
                fixture.assertStored(recovered, recoveredDraft, original)
                assertEquals(putCount + 1, fixture.putBodies.size)
                assertFalse(fixture.loseNextPutResponse.get())
                assertTrue(fixture.paths.any { it == "GET /api/sends/${saved.bitwardenSendId}" })
                assertEquals(originalDocument["file"], fixture.document(recovered)["file"])
                assertArrayEquals(bytesBefore, fixture.fetchStoredFileBytes())
                assertArrayEquals(fixture.filePlaintext, fixture.decryptFile(fixture.fetchStoredFileBytes()))
                fixture.assertNoUnexpectedRequests()
            } finally { fixture.close() }
        }
    }

    @Test fun nativeTextAndFileEditorsSaveRealRowsWithPasswordRemovalDatePickerAndEnableSwitch() {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            try {
                val seeded = runBlocking {
                    fixture.initialize()
                    fixture.seed(BitwardenSend.TYPE_TEXT) to fixture.seed(BitwardenSend.TYPE_FILE)
                }
                val current = mutableStateOf<BitwardenSend?>(seeded.first)
                val state = mutableStateOf<BitwardenViewModel.SendState>(BitwardenViewModel.SendState.Idle)
                val successVersion = mutableStateOf(0)
                val outcome = AtomicReference<SendMutationResult?>()
                val capturedDraft = AtomicReference<BitwardenSendDraft?>()
                val navigationDiagnostic = AtomicReference("No navigation callback")
                fun clickVisibleSwitch(target: SemanticsNodeInteraction, expectedOn: Boolean) {
                    try {
                        // Scroll-to can leave the right-hand switch beneath the fixed Save button.
                        // Assert actual hit-target visibility before injecting a physical click.
                        revealFully(target)
                        assertNull("No save before the explicit Save action", outcome.get())
                        target.performClick()
                        assertNull("Toggling must not submit the editor", outcome.get())
                        if (expectedOn) target.assertIsOn() else target.assertIsOff()
                    } catch (failure: Throwable) {
                        writeUiDiagnostic("send-switch-failure", buildString {
                            appendLine("navigation=${navigationDiagnostic.get()}")
                            appendLine("lifecycle=${compose.activity.lifecycle.currentState} unlocked=${takagi.ru.monica.security.SessionManager.isUnlocked.value} lock=${takagi.ru.monica.security.SessionManager.lockGeneration.value}")
                            appendLine("editorPresent=${current.value != null} submitted=${outcome.get() != null} state=${state.value.javaClass.simpleName}")
                            appendLine(runCatching { compose.onRoot().printToString() }.getOrElse { "Tree unavailable: $it" })
                        })
                        throw failure
                    }
                }
                val english = localized
                val configuration = editorConfiguration()
                compose.setContent {
                    val scope = rememberCoroutineScope()
                    CompositionLocalProvider(LocalContext provides english, LocalConfiguration provides configuration,
                        LocalDensity provides Density(compose.activity.resources.displayMetrics.density, configuration.fontScale),
                        LocalActivityResultRegistryOwner provides compose.activity) {
                        MonicaTheme(darkTheme = testDark) {
                            Surface(Modifier.fillMaxSize()) {
                                val editing = current.value
                                if (editing == null) Text("Synthetic Send edit complete")
                                else key(editing.id) {
                                    AddEditSendScreen(
                                        sendState = state.value, sendCreateSuccessVersion = successVersion.value,
                                        vaults = listOf(fixture.vault), activeVault = fixture.vault,
                                        unlockStateByVault = mapOf(fixture.vaultId to BitwardenViewModel.UnlockState.Unlocked),
                                        existingSend = editing, onNavigateBack = {
                                            navigationDiagnostic.set("lifecycle=${compose.activity.lifecycle.currentState} unlocked=${takagi.ru.monica.security.SessionManager.isUnlocked.value} lock=${takagi.ru.monica.security.SessionManager.lockGeneration.value} submitted=${outcome.get() != null} state=${state.value.javaClass.simpleName}")
                                            current.value = null
                                        },
                                        onCreate = { _, _, _, _, _, _, _, _, _ -> error("Unexpected create-text fallback") },
                                        onCreateFile = { _, _, _, _, _, _, _, _, _ -> error("Unexpected create-file fallback") },
                                        onSaveDraft = { vaultId, draft, uri, fileName ->
                                            check(vaultId == fixture.vaultId && uri == null && fileName == null)
                                            capturedDraft.set(draft)
                                            state.value = BitwardenViewModel.SendState.Creating
                                            scope.launch {
                                                val result = fixture.repository.updateSend(editing, draft)
                                                outcome.set(result)
                                                if (result is SendMutationResult.Success) {
                                                    successVersion.value++
                                                    state.value = BitwardenViewModel.SendState.Idle
                                                } else state.value = BitwardenViewModel.SendState.Error("Synthetic Save failed")
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                val originalDeletion = Instant.parse(seeded.first.deletionDate!!).toEpochMilli()
                field(R.string.title).performScrollTo().performTextReplacement("  Native edited text  ")
                field(R.string.send_content_label).performScrollTo().performTextReplacement("  native text\n ")
                field(R.string.notes_optional).performScrollTo().performTextReplacement(" native notes ")
                clickVisibleSwitch(compose.onNodeWithTag("send_auth_remove_password"), expectedOn = true)
                compose.onNode(hasSetTextAction() and hasText(localized.getString(R.string.send_new_password))).assertDoesNotExist()
                clickVisibleSwitch(toggle(R.string.enabled), expectedOn = false)
                clickVisibleSwitch(toggle(R.string.send_no_expiration), expectedOn = true)
                compose.onNode(hasClickAction() and hasText(english.getString(R.string.send_expiration_date))).assertDoesNotExist()
                dateRow(R.string.send_deletion_date, originalDeletion).performScrollTo().assertIsDisplayed()
                screenshotIfRequested(fixture, "text-options", dateRow(R.string.send_deletion_date, originalDeletion))
                assertBottomActionsDoNotOverlap()
                compose.onNodeWithContentDescription(english.getString(R.string.save)).performClick()
                awaitSaved(outcome)
                val textDraft = requireNotNull(capturedDraft.get())
                assertEquals(originalDeletion, textDraft.deletionMillis)
                assertNull(textDraft.expirationMillis)
                assertEquals(SendPasswordAction.REMOVE, textDraft.passwordAction)
                val textSaved = success(requireNotNull(outcome.get()))
                runBlocking { fixture.assertStored(textSaved, textDraft, seeded.first) }
                assertFalse(textSaved.hasPassword)

                compose.runOnIdle {
                    outcome.set(null)
                    capturedDraft.set(null)
                    state.value = BitwardenViewModel.SendState.Idle
                    current.value = seeded.second
                }
                compose.waitForIdle()
                field(R.string.title).performScrollTo().performTextReplacement("Native edited file")
                compose.onNode(hasSetTextAction() and hasText(english.getString(R.string.send_content_label))).assertDoesNotExist()
                field(R.string.notes_optional).performScrollTo().performTextReplacement(" file UI note ")
                // Existing password stays protected until explicit removal. API replacement is exercised above.
                compose.onNode(hasSetTextAction() and hasText(english.getString(R.string.send_new_password))).assertDoesNotExist()
                clickVisibleSwitch(toggle(R.string.enabled), expectedOn = false)
                // Exercise the production native DatePicker -> TimePicker, confirming an exact minute.
                val newDeletion = ((System.currentTimeMillis() + 6 * 86_400_000L) / 60_000L) * 60_000L
                selectNativeDateTime(R.string.send_deletion_date, Instant.parse(seeded.second.deletionDate!!).toEpochMilli(), newDeletion)
                dateRow(R.string.send_deletion_date, newDeletion).performScrollTo().assertIsDisplayed()
                screenshotIfRequested(fixture, "file-options", dateRow(R.string.send_deletion_date, newDeletion))
                assertBottomActionsDoNotOverlap()
                compose.onNodeWithContentDescription(english.getString(R.string.save)).performClick()
                awaitSaved(outcome)
                val fileDraft = requireNotNull(capturedDraft.get())
                assertEquals(newDeletion, fileDraft.deletionMillis)
                assertEquals(SendPasswordAction.KEEP, fileDraft.passwordAction)
                assertNull(fileDraft.password)
                val fileSaved = success(requireNotNull(outcome.get()))
                runBlocking { fixture.assertStored(fileSaved, fileDraft, seeded.second) }
                assertTrue(fileSaved.hasPassword)
                assertArrayEquals(fixture.fileCiphertext, fixture.fetchStoredFileBytes())
                fixture.assertNoUnexpectedRequests()
            } finally {
                compose.runOnIdle { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
                runBlocking { fixture.close() }
            }
        }
    }

    @Test fun nativeNewSendUsesSharedContentPickerAndSavesCompleteDraft() {
        MockWebServer().use { server ->
            val fixture = Fixture(server)
            try {
                runBlocking { fixture.initialize() }
                val english = localized
                val configuration = editorConfiguration()
                val draft = AtomicReference<BitwardenSendDraft?>()
                compose.setContent {
                    CompositionLocalProvider(LocalContext provides english, LocalConfiguration provides configuration,
                        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                            compose.activity.resources.displayMetrics.density, configuration.fontScale),
                        LocalActivityResultRegistryOwner provides compose.activity) {
                        MonicaTheme(darkTheme = testDark) {
                            Surface(Modifier.fillMaxSize()) {
                                AddEditSendScreen(sendState = BitwardenViewModel.SendState.Idle,
                                    vaults = listOf(fixture.vault), activeVault = fixture.vault,
                                    unlockStateByVault = mapOf(fixture.vaultId to BitwardenViewModel.UnlockState.Unlocked),
                                    onNavigateBack = {},
                                    onCreate = { _, _, _, _, _, _, _, _, _ -> error("Unexpected legacy create") },
                                    onCreateFile = { _, _, _, _, _, _, _, _, _ -> error("Unexpected legacy file create") },
                                    onSaveDraft = { id, value, uri, file ->
                                        assertEquals(fixture.vaultId, id); assertNull(uri); assertNull(file); draft.set(value)
                                    })
                            }
                        }
                    }
                }
                compose.onNodeWithTag("send_editor_save").assertIsNotEnabled()
                compose.onNodeWithTag("item_editor_section_notes").assertDoesNotExist()
                compose.onNodeWithTag("item_editor_section_options").assertDoesNotExist()
                compose.onNodeWithTag("item_editor_title").performScrollTo().performTextReplacement("New synthetic Send")
                field(R.string.send_content_label).performScrollTo().performTextReplacement("  complete new content\n  ")
                screenshotIfRequested(fixture, "new-send-fields", field(R.string.send_content_label))
                compose.onNodeWithTag("item_editor_add_content").performScrollTo().performClick()
                compose.onNodeWithTag("item_editor_choose_notes").performClick()
                field(R.string.notes_optional).performScrollTo().performTextReplacement("  optional note  ")
                compose.onNodeWithTag("item_editor_add_content").performScrollTo().performClick()
                compose.onNodeWithTag("item_editor_choose_options").performClick()
                field(R.string.send_max_access_count).performScrollTo().performTextReplacement("3")
                toggle(R.string.send_no_expiration).performScrollTo().performClick().assertIsOn()
                toggle(R.string.send_hide_email).performScrollTo().performClick().assertIsOn()
                screenshotIfRequested(fixture, "new-send-options", toggle(R.string.send_hide_email))
                assertBottomActionsDoNotOverlap()
                screenshotIfRequested(fixture, "new-send-bottom", compose.onNodeWithTag("item_editor_add_content"))
                compose.onNodeWithTag("send_editor_save").assertIsEnabled().performClick()
                compose.waitUntil(5000) { draft.get() != null }
                val actual = requireNotNull(draft.get())
                assertEquals("New synthetic Send", actual.title)
                assertEquals("  complete new content\n  ", actual.text)
                assertEquals("  optional note  ", actual.notes)
                assertEquals(3, actual.maxAccessCount); assertNull(actual.expirationMillis); assertTrue(actual.hideEmail)
            } finally {
                compose.runOnIdle { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
                runBlocking { fixture.close() }
            }
        }
    }

    /** Actual production editor with in-memory display data only; no HTTP, Room or account mutation. */
    @Test fun nativeAccountTypesAndBottomActionsRemainReadableAcrossUiSettings() {
        val vault = BitwardenVault(id = Long.MAX_VALUE - 325, email = "sample@example.invalid",
            serverUrl = "https://vault.example.invalid", isLocked = false, isConnected = true)
        val displayName = mutableStateOf<String?>(null)
        val english = localized
        val configuration = editorConfiguration()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides english, LocalConfiguration provides configuration,
                LocalDensity provides Density(compose.activity.resources.displayMetrics.density, configuration.fontScale),
                LocalActivityResultRegistryOwner provides compose.activity) {
                MonicaTheme(darkTheme = testDark) {
                    val shown = vault.copy(displayName = displayName.value)
                    Surface(Modifier.fillMaxSize()) {
                        AddEditSendScreen(sendState = BitwardenViewModel.SendState.Idle,
                            vaults = listOf(shown), activeVault = shown,
                            unlockStateByVault = mapOf(vault.id to BitwardenViewModel.UnlockState.Unlocked),
                            initialTitle = "Synthetic Send", initialText = "Synthetic content",
                            onNavigateBack = {},
                            onCreate = { _, _, _, _, _, _, _, _, _ -> error("Visual test must not create a Send") },
                            onCreateFile = { _, _, _, _, _, _, _, _, _ -> error("Visual test must not upload a file") },
                            onSaveDraft = { _, _, _, _ -> error("Visual test must not save a draft") })
                    }
                }
            }
        }
        val account = compose.onNodeWithTag("send_vault_${vault.id}")
        revealFully(account)
        account.assertIsSelected()
        compose.onAllNodesWithText(vault.email, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText(vault.serverUrl, useUnmergedTree = true).assertCountEquals(1)
        assertTextFits(hasText(vault.email))
        assertTextFits(hasText(vault.serverUrl))
        screenshotIfRequested(null, "account", account)

        // A configured name replaces the heading, while the email still appears exactly once.
        compose.runOnIdle { displayName.value = "Synthetic team" }
        compose.waitForIdle()
        compose.onAllNodesWithText("Synthetic team", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText(vault.email, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText(vault.serverUrl, useUnmergedTree = true).assertCountEquals(1)
        assertTextFits(hasText("Synthetic team"))
        assertTextFits(hasText(vault.email))
        assertTextFits(hasText(vault.serverUrl))

        val textChip = compose.onNode(hasClickAction() and hasText(english.getString(R.string.send_type_text)))
        val fileChip = compose.onNode(hasClickAction() and hasText(english.getString(R.string.send_type_file)))
        revealFully(textChip)
        textChip.assertIsSelected()
        fileChip.assertIsNotSelected().assertIsEnabled()
        assertFullyVisible(fileChip)
        assertTextFits(hasText(english.getString(R.string.send_type_text)))
        assertTextFits(hasText(english.getString(R.string.send_type_file)))
        screenshotIfRequested(null, "types", textChip)
        assertFullyVisible(fileChip)
        assertBottomActionsDoNotOverlap()
        screenshotIfRequested(null, "bottom", compose.onNodeWithTag("item_editor_add_content"))
    }

    private fun field(label: Int) = compose.onNode(hasSetTextAction() and hasText(localized.getString(label)))
    private fun toggle(label: Int) = compose.onNode(
        isToggleable() and hasContentDescription(localized.getString(label)), useUnmergedTree = true)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))

    private fun dateValue(millis: Long) =
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(millis))

    private fun dateRow(label: Int, millis: Long): SemanticsNodeInteraction {
        val target = compose.onNode(hasClickAction() and hasText(localized.getString(label)) and hasText(dateValue(millis)))
        try {
            target.fetchSemanticsNode()
        } catch (failure: Throwable) {
            val detail = buildString {
                appendLine("Synthetic Send fixture date lookup failed: label=${localized.getString(label)} millis=$millis formatted=${dateValue(millis)}")
                appendLine("locale=${java.util.Locale.getDefault()} timezone=${ZoneId.systemDefault()} localizedLocales=${localized.resources.configuration.locales}")
                appendLine("fontScale=$testFontScale dark=$testDark cause=$failure")
                appendLine("Merged tree:")
                appendLine(runCatching { compose.onRoot().printToString() }.getOrElse { "Unavailable: $it" })
                appendLine("Unmerged tree:")
                appendLine(runCatching { compose.onRoot(useUnmergedTree = true).printToString() }.getOrElse { "Unavailable: $it" })
            }
            writeUiDiagnostic("send-date-lookup", detail)
            throw failure
        }
        return target
    }

    private fun selectNativeDateTime(label: Int, currentMillis: Long, millis: Long) {
        val selected = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        dateRow(label, currentMillis).performScrollTo().performClick()
        onView(isAssignableFrom(DatePicker::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(DatePicker::class.java)
            override fun getDescription() = "Select the synthetic Send deletion date in the native DatePicker"
            override fun perform(controller: UiController, view: View) {
                (view as DatePicker).updateDate(selected.year, selected.monthValue - 1, selected.dayOfMonth)
                controller.loopMainThreadUntilIdle()
            }
        })
        onView(withId(android.R.id.button1)).perform(click())
        onView(isAssignableFrom(TimePicker::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(TimePicker::class.java)
            override fun getDescription() = "Select the synthetic Send deletion time in the native TimePicker"
            override fun perform(controller: UiController, view: View) {
                (view as TimePicker).apply { hour = selected.hour; minute = selected.minute }
                controller.loopMainThreadUntilIdle()
            }
        })
        onView(withId(android.R.id.button1)).perform(click())
        compose.waitForIdle()
    }

    private fun awaitSaved(outcome: AtomicReference<SendMutationResult?>) {
        compose.waitUntil(30_000) { outcome.get() != null }
        success(requireNotNull(outcome.get()))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Synthetic Send edit complete").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun scrollContainer() = compose.onNode(hasScrollAction() and
        hasAnyDescendant(hasTestTag("item_editor_add_content")))

    private fun assertFullyVisible(target: SemanticsNodeInteraction) {
        target.assertIsDisplayed()
        val node = target.fetchSemanticsNode()
        assertEquals("Full target height must remain visible", node.size.height.toFloat(), node.boundsInRoot.height, 1f)
        assertEquals("Full target width must remain visible", node.size.width.toFloat(), node.boundsInRoot.width, 1f)
    }

    private fun overlaps(first: SemanticsNodeInteraction, second: SemanticsNodeInteraction): Boolean {
        val a = first.fetchSemanticsNode().boundsInRoot
        val b = second.fetchSemanticsNode().boundsInRoot
        return minOf(a.right, b.right) > maxOf(a.left, b.left) && minOf(a.bottom, b.bottom) > maxOf(a.top, b.top)
    }

    private fun revealFully(target: SemanticsNodeInteraction) {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        target.performScrollTo()
        compose.waitForIdle()
        val save = compose.onNodeWithTag("send_editor_save")
        if (overlaps(target, save)) {
            val delta = target.fetchSemanticsNode().boundsInRoot.bottom - save.fetchSemanticsNode().boundsInRoot.top + 16f
            scrollContainer().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
            compose.waitForIdle()
        }
        assertFullyVisible(target)
        assertFalse("Screenshot target must not be obscured by Save", overlaps(target, save))
    }

    private fun assertBottomActionsDoNotOverlap() {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        val scroll = scrollContainer()
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
        compose.waitUntil(5000) {
            val current = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            kotlin.math.abs(current.maxValue() - current.value()) <= 1f
        }
        val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("Check the actual end of the scrollable editor", range.maxValue(), range.value(), 1f)
        val add = compose.onNodeWithTag("item_editor_add_content")
        val save = compose.onNodeWithTag("send_editor_save")
        assertFullyVisible(add)
        assertFullyVisible(save)
        assertFalse("Add content and Save must not overlap at the end of the editor", overlaps(add, save))
    }

    private fun assertTextFits(matcher: SemanticsMatcher) {
        val text = compose.onAllNodes(matcher and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true)
        val count = text.fetchSemanticsNodes().size
        assertTrue("Expected real text layout for $matcher", count > 0)
        repeat(count) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            text[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val node = text[index].fetchSemanticsNode()
            writeUiDiagnostic("send-text-layout", buildString {
                appendLine("matcher=$matcher index=$index nodeSize=${node.size} bounds=${node.boundsInRoot} window=${node.boundsInWindow}")
                layouts.forEach { layout ->
                    val input = layout.layoutInput
                    appendLine("text=${input.text.text} fontSize=${input.style.fontSize} lineHeight=${input.style.lineHeight} density=${input.density.density} fontScale=${input.density.fontScale}")
                    appendLine("constraints=${input.constraints} size=${layout.size} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}")
                    appendLine("widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight} maxLinesExceeded=${layout.multiParagraph.didExceedMaxLines} maxLines=${input.maxLines} overflow=${input.overflow}")
                    repeat(layout.lineCount) { line ->
                        appendLine("line=$line top=${layout.getLineTop(line)} bottom=${layout.getLineBottom(line)} baseline=${layout.getLineBaseline(line)} left=${layout.getLineLeft(line)} right=${layout.getLineRight(line)} end=${layout.getLineEnd(line)} visibleEnd=${layout.getLineEnd(line, visibleEnd = true)} ellipsized=${layout.isLineEllipsized(line)}")
                    }
                }
            })
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { assertActualTextFits(it, matcher.toString()) }
            assertTrue("Editor must retain system font scale", layouts.all { it.layoutInput.density.fontScale == testFontScale })
            assertEquals("Text height must remain fully visible", node.size.height.toFloat(), node.boundsInRoot.height, 1f)
            assertEquals("Text width must remain fully visible", node.size.width.toFloat(), node.boundsInRoot.width, 1f)
        }
    }

    private fun assertActualTextFits(layout: TextLayoutResult, label: String) {
        val value = layout.layoutInput.text.text
        assertTrue("$label must contain measured text", value.isNotEmpty() && layout.lineCount > 0)
        assertFalse("$label must not drop lines", layout.multiParagraph.didExceedMaxLines)
        // Compare rendered line/character extents, not an unused parent paragraph constraint.
        fun inside(left: Float, top: Float, right: Float, bottom: Float, part: String) {
            assertTrue("$label $part [$left,$top,$right,$bottom] exceeds ${layout.size}",
                left >= -1f && top >= -1f && right <= layout.size.width + 1f && bottom <= layout.size.height + 1f)
        }
        repeat(layout.lineCount) { line ->
            assertFalse("$label line $line must not ellipsize", layout.isLineEllipsized(line))
            inside(layout.getLineLeft(line), layout.getLineTop(line), layout.getLineRight(line),
                layout.getLineBottom(line), "line $line")
        }
        value.indices.filter { !value[it].isWhitespace() }.forEach { offset ->
            val box = layout.getBoundingBox(offset)
            inside(box.left, box.top, box.right, box.bottom, "character $offset")
        }
    }

    private fun writeUiDiagnostic(name: String, detail: String) {
        android.util.Log.i("SendEditorLayout", detail)
        val directory = File(context.filesDir, "editor-shapes-325").apply { check(isDirectory || mkdirs()) }
        File(directory, "$name.txt").appendText("time=${System.currentTimeMillis()}\n$detail\n")
    }

    private fun screenshotIfRequested(fixture: Fixture?, name: String, target: SemanticsNodeInteraction) {
        // Visibility assertions run even when image capture is disabled.
        revealFully(target)
        if (InstrumentationRegistry.getArguments().getString("sendManagementScreenshots") != "true") return
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder = File(context.filesDir, "editor-shapes-325").apply { check(isDirectory || mkdirs()) }
        val suffix = "${if (testDark) "dark" else "light"}-${if (testFontScale > 1f) "large" else "normal"}"
        try {
            File(folder, "send-$name-$suffix.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            if (fixture != null) {
                val previous = File(context.getExternalFilesDir(null), "send-management-${fixture.uuid}").apply { check(isDirectory || mkdirs()) }
                File(previous, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        } finally { bitmap.recycle() }
    }

    private fun success(result: SendMutationResult): BitwardenSend {
        assertTrue("Expected Send success, got $result", result is SendMutationResult.Success)
        return (result as SendMutationResult.Success).send
    }

    private fun assertOpaqueIdentity(before: JsonObject, after: JsonObject) {
        listOf("id", "accessId", "key", "type", "future", "file").forEach { assertEquals(it, before[it], after[it]) }
        if (before["text"] != null && before["text"] != JsonNull) {
            assertEquals(before["text"]!!.jsonObject["futureText"], after["text"]!!.jsonObject["futureText"])
        }
    }

    private inner class Fixture(val server: MockWebServer) {
        val uuid = UUID.randomUUID().toString()
        private val accountKey = "send-management-$uuid"
        private val email = "$accountKey@example.invalid"
        private val token = "synthetic-send-token-$uuid"
        val repository = BitwardenRepository.getInstance(context)
        val database = PasswordDatabase.getDatabase(context)
        private val sendCache = takagi.ru.monica.bitwarden.service.BitwardenSendCache(context)
        val material = ByteArray(16) { (it + 21).toByte() }
        private val vaultKey = SymmetricCryptoKey(ByteArray(32) { 7 }, ByteArray(32) { 11 })
        private val sendKey = BitwardenCrypto.deriveSendKey(material)
        val filePlaintext = "Synthetic file bytes only\n\u0000\tend".toByteArray()
        val fileCiphertext = encryptedFile(filePlaintext)
        private val fileId = "file-$uuid"
        private val documents = ConcurrentHashMap<String, JsonObject>()
        val paths = CopyOnWriteArrayList<String>()
        val putBodies = CopyOnWriteArrayList<JsonObject>()
        val reads = AtomicInteger()
        val changeOnRead = AtomicInteger(-1)
        val passwordRemovals = AtomicInteger()
        val loseNextPutResponse = AtomicBoolean()
        private val revision = AtomicInteger()
        private val unexpected = CopyOnWriteArrayList<String>()
        var vaultId = 0L
        lateinit var vault: BitwardenVault

        suspend fun initialize() {
            val endpoint = server.url("/").toString().trimEnd('/')
            vaultId = database.bitwardenVaultDao().insert(BitwardenVault(
                email = email, canonicalEmail = email, userId = uuid, accountKey = accountKey,
                serverUrl = endpoint, identityUrl = "$endpoint/identity", apiUrl = "$endpoint/api/",
                accessTokenExpiresAt = System.currentTimeMillis() + 3_600_000L,
                isLocked = false, isConnected = true, syncEnabled = false,
            ))
            vault = requireNotNull(database.bitwardenVaultDao().getVaultById(vaultId))
            cache<SymmetricCryptoKey>("symmetricKeyCache")[vaultId] = vaultKey
            cache<String>("accessTokenCache")[vaultId] = token
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    paths += "${request.method} $path"
                    if (request.getHeader("Authorization") != "Bearer $token") {
                        unexpected += "Unexpected authorization at $path"
                        return MockResponse().setResponseCode(401)
                    }
                    // This route is a synthetic stored blob, not a recipient or OTP API.
                    if (path == "/synthetic-blob/$fileId" && request.method == "GET") {
                        return MockResponse().setBody(Buffer().write(fileCiphertext))
                    }
                    val sendId = path.removePrefix("/api/sends/").substringBefore('/')
                    val existing = documents[sendId]
                    if (existing == null) {
                        unexpected += "Unexpected request ${request.method} $path"
                        return MockResponse().setResponseCode(404)
                    }
                    if (path == "/api/sends/$sendId" && request.method == "GET") {
                        if (reads.incrementAndGet() == changeOnRead.get()) changeRevisionDocument(sendId)
                        return ok(requireNotNull(documents[sendId]))
                    }
                    if (path == "/api/sends/$sendId" && request.method == "PUT") {
                        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        putBodies += body
                        val fields = body.toMutableMap()
                        if (body["password"] == JsonNull || !body.containsKey("password")) fields["password"] = existing["password"] ?: JsonNull
                        fields["revisionDate"] = nextRevision()
                        documents[sendId] = JsonObject(fields)
                        if (loseNextPutResponse.compareAndSet(true, false)) {
                            return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        }
                        return ok(requireNotNull(documents[sendId]))
                    }
                    if (path == "/api/sends/$sendId/remove-password" && request.method == "PUT") {
                        passwordRemovals.incrementAndGet()
                        documents[sendId] = JsonObject(existing + mapOf("password" to JsonNull,
                            "authType" to JsonPrimitive(2), "revisionDate" to nextRevision()))
                        return ok(requireNotNull(documents[sendId]))
                    }
                    unexpected += "Unexpected request ${request.method} $path"
                    return MockResponse().setResponseCode(405)
                }
            }
        }

        suspend fun seed(type: Int): BitwardenSend {
            val sendId = "send-${UUID.randomUUID()}"
            val now = (System.currentTimeMillis() / 60_000L) * 60_000L
            documents[sendId] = buildJsonObject {
                put("id", sendId); put("accessId", "access-$sendId"); put("type", type)
                put("key", BitwardenCrypto.encrypt(material, vaultKey))
                put("name", BitwardenCrypto.encryptString(if (type == 0) "Synthetic original text" else "Synthetic original file", sendKey))
                put("notes", BitwardenCrypto.encryptString("Original synthetic notes", sendKey))
                put("password", "server-password-marker"); put("authType", 1); put("emails", JsonNull)
                put("accessCount", 1); put("maxAccessCount", 5); put("disabled", false); put("hideEmail", false)
                put("revisionDate", nextRevision())
                put("deletionDate", Instant.ofEpochMilli(now + 8 * 86_400_000L).toString())
                put("expirationDate", Instant.ofEpochMilli(now + 3 * 86_400_000L).toString())
                put("future", buildJsonObject { put("nested", "Preserve synthetic opaque metadata") })
                if (type == BitwardenSend.TYPE_TEXT) put("text", buildJsonObject {
                    put("text", BitwardenCrypto.encryptString("Original synthetic content", sendKey))
                    put("hidden", false); put("futureText", 42)
                }) else put("file", buildJsonObject {
                    put("id", fileId); put("fileName", BitwardenCrypto.encryptString("synthetic-only.txt", sendKey))
                    put("key", BitwardenCrypto.encrypt(ByteArray(64) { (it + 9).toByte() }, sendKey))
                    put("size", fileCiphertext.size.toString()); put("futureFile", "must remain")
                })
            }
            val entity = map(requireNotNull(documents[sendId]))
            sendCache.upsert(entity)
            return requireNotNull(sendCache.getBySendId(vaultId, sendId))
        }

        fun document(send: BitwardenSend) = requireNotNull(documents[send.bitwardenSendId])
        fun draft(send: BitwardenSend) = BitwardenSendDraft(send.name, send.textContent.orEmpty(), send.notes,
            maxAccessCount = send.maxAccessCount, hideEmail = send.hideEmail, hiddenText = send.isTextHidden,
            disabled = send.disabled, deletionMillis = Instant.parse(send.deletionDate!!).toEpochMilli(),
            expirationMillis = send.expirationDate?.let { Instant.parse(it).toEpochMilli() })
        suspend fun row(send: BitwardenSend) = requireNotNull(sendCache.getBySendId(vaultId, send.bitwardenSendId))
        private fun map(document: JsonObject) = requireNotNull(BitwardenSendMapper.mapApiToEntity(vaultId,
            vault.serverUrl, json.decodeFromString<SendApiResponse>(document.toString()), vaultKey))
        suspend fun reloadLocal(send: BitwardenSend): BitwardenSend {
            sendCache.upsert(map(document(send)).copy(id = send.id, createdAt = send.createdAt))
            return row(send)
        }
        fun changeRevision(send: BitwardenSend) = changeRevisionDocument(send.bitwardenSendId)
        private fun changeRevisionDocument(sendId: String) {
            documents[sendId] = JsonObject(requireNotNull(documents[sendId]) + ("revisionDate" to nextRevision()))
        }
        private fun nextRevision() = JsonPrimitive(Instant.ofEpochMilli(1_800_000_000_000L + revision.incrementAndGet() * 1000L).toString())

        suspend fun assertStored(saved: BitwardenSend, draft: BitwardenSendDraft, original: BitwardenSend) {
            val stored = row(saved)
            val raw = requireNotNull(database.bitwardenSendDao().getBySendId(vaultId, saved.bitwardenSendId))
            assertEquals(1, raw.cacheVersion); assertTrue(raw.encryptedCache!!.startsWith("MDK|"))
            assertEquals("", raw.name); assertEquals("", raw.notes); assertNull(raw.textContent)
            assertNull(raw.fileName); assertNull(raw.keyBase64); assertEquals("", raw.shareUrl); assertEquals("", raw.accessId)
            assertEquals(saved, stored)
            assertEquals(original.id, stored.id); assertEquals(original.createdAt, stored.createdAt)
            assertEquals(original.accessId, stored.accessId); assertEquals(original.keyBase64, stored.keyBase64)
            assertEquals(original.type, stored.type); assertEquals(original.fileName, stored.fileName)
            assertEquals(original.fileSize, stored.fileSize); assertFalse(stored.isDirty)
            assertEquals(draft.title, stored.name); assertEquals(draft.notes.orEmpty(), stored.notes)
            assertEquals(draft.disabled, stored.disabled); assertEquals(draft.hideEmail, stored.hideEmail)
            assertEquals(draft.maxAccessCount, stored.maxAccessCount)
            assertEquals(draft.deletionMillis, Instant.parse(stored.deletionDate!!).toEpochMilli())
            assertEquals(draft.expirationMillis, stored.expirationDate?.let { Instant.parse(it).toEpochMilli() })
            if (stored.isTextType) { assertEquals(draft.text, stored.textContent); assertEquals(draft.hiddenText, stored.isTextHidden) }
        }

        fun fetchStoredFileBytes(): ByteArray = OkHttpClient().newCall(Request.Builder()
            .url(server.url("/synthetic-blob/$fileId")).header("Authorization", "Bearer $token").build())
            .execute().use { response -> check(response.isSuccessful); requireNotNull(response.body).bytes() }
        private fun encryptedFile(plain: ByteArray): ByteArray {
            val iv = ByteArray(16) { (it + 31).toByte() }
            val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey.encKey, "AES"), IvParameterSpec(iv))
            }.doFinal(plain)
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(sendKey.macKey, "HmacSHA256")) }.doFinal(iv + encrypted)
            return byteArrayOf(BitwardenCrypto.CIPHER_TYPE_AES_CBC_HMAC.toByte()) + iv + mac + encrypted
        }
        fun decryptFile(bytes: ByteArray): ByteArray {
            assertEquals(BitwardenCrypto.CIPHER_TYPE_AES_CBC_HMAC.toByte(), bytes[0])
            val iv = bytes.copyOfRange(1, 17)
            val encrypted = bytes.copyOfRange(49, bytes.size)
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(sendKey.macKey, "HmacSHA256")) }.doFinal(iv + encrypted)
            assertArrayEquals(mac, bytes.copyOfRange(17, 49))
            return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(sendKey.encKey, "AES"), IvParameterSpec(iv))
            }.doFinal(encrypted)
        }
        fun assertNoUnexpectedRequests() = assertTrue("Unexpected synthetic HTTP requests: $unexpected", unexpected.isEmpty())
        @Suppress("UNCHECKED_CAST")
        private fun <T> cache(name: String): MutableMap<Long, T> = BitwardenRepository::class.java
            .getDeclaredField(name).apply { isAccessible = true }.get(repository) as MutableMap<Long, T>

        suspend fun close() {
            if (vaultId != 0L) {
                val owned = database.bitwardenVaultDao().getVaultById(vaultId)
                if (owned != null) {
                    check(owned.accountKey == accountKey && owned.email == email && owned.userId == uuid)
                    cache<SymmetricCryptoKey>("symmetricKeyCache").remove(vaultId)?.clear()
                    cache<String>("accessTokenCache").remove(vaultId)
                    database.bitwardenSendDao().getSendsByVault(vaultId).forEach { row ->
                        check(documents.containsKey(row.bitwardenSendId))
                        database.bitwardenSendDao().deleteBySendId(vaultId, row.bitwardenSendId)
                    }
                    database.bitwardenVaultDao().deleteById(vaultId)
                }
            }
            vaultKey.clear(); sendKey.clear(); material.fill(0)
            filePlaintext.fill(0); fileCiphertext.fill(0); documents.clear()
        }
    }

    private fun ok(body: JsonObject) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(body.toString())
}
