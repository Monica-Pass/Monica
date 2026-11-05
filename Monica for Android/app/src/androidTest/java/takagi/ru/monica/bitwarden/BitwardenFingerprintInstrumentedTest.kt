package takagi.ru.monica.bitwarden

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.crypto.BitwardenAccountFingerprint
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.ui.BitwardenAccountScreen
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.Language
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.LocaleHelper

/** Synthetic authenticated profile -> sealed public identity -> real account sheet and clipboard. */
@RunWith(AndroidJUnit4::class)
class BitwardenFingerprintInstrumentedTest {
    // The v1 rule's eager dispatcher let an IO completion apply a snapshot and re-enter
    // measureAndLayoutForTest on a worker (retained visual-isolated-large failure).
    // v2 queues composition work on the test scheduler instead of resuming it on that worker.
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val models = ViewModelStore()
    private val screenVisible = mutableStateOf(true)
    private var screenShown = false
    private var originalActivityConfiguration: Configuration? = null

    @Test fun nativeProfileCachesOnlySealedPublicIdentityAndCopiesTheProtocolPhrase(): Unit =
        withFixture(Language.CHINESE) { f ->
            showAccount(f)
            openPhrase(f)
            assertEquals(listOf(PROFILE, DEVICES), f.paths.toList())
            assertEquals(f.expectedPhrase, runBlocking { f.repository.getAccountFingerprint(f.vaultId).getOrThrow() })
            assertTrue("The protocol words must remain English in a Chinese UI", f.expectedPhrase.matches(Regex("[a-z-]+")))
            val sealed = requireNotNull(f.sealedIdentity())
            assertTrue(sealed.startsWith("2."))
            val key = f.newKey()
            val bytes = BitwardenCrypto.decrypt(sealed, key)
            try {
                val material = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                assertEquals(setOf("account", "userId", "stamp", "publicKey"), material.keys)
                assertEquals(f.userId, material.getValue("userId").jsonPrimitive.content)
                assertEquals(Base64.getEncoder().encodeToString(f.publicKey), material.getValue("publicKey").jsonPrimitive.content)
            } finally { bytes.fill(0); key.clear() }
            compose.onNodeWithTag(COPY).performScrollTo().assertIsEnabled().performClick()
            assertEquals(f.expectedPhrase, clipboardText())
            assertEquals(f.localized.getString(R.string.bitwarden_account_fingerprint), onMainValue { clipboard().primaryClip!!.description.label.toString() })
            assertEquals(2, f.paths.size)
            screenshotIfRequested("chinese-copy")
        }

    @Test fun offlineGetterReadsSealedIdentityAfterRepositoryRecreation(): Unit = withFixture { f ->
        showAccount(f)
        openPhrase(f)
        val sealed = requireNotNull(f.sealedIdentity())
        hideAccount()
        f.stopServer()
        val recreated = f.recreateRepository()
        assertTrue("A new repository has no unlocked session", runBlocking { recreated.getAccountFingerprint(f.vaultId).isFailure })
        f.installSession(recreated)
        assertEquals(f.expectedPhrase, runBlocking { recreated.getAccountFingerprint(f.vaultId).getOrThrow() })
        assertEquals(sealed, f.sealedIdentity())
        assertEquals("Offline derivation must not issue HTTP", listOf(PROFILE, DEVICES), f.paths.toList())
    }

    @Test fun applicationLockClosesSheetAndRejectsAnAlreadyCapturedCopyCallback(): Unit = withFixture { f ->
        showAccount(f)
        openPhrase(f)
        val staleCopy = captureCopyAction()
        setClipboardSentinel()
        onMain { SessionManager.markLocked(); staleCopy() }
        waitAbsent(SHEET)
        compose.onNodeWithTag(PHRASE).assertDoesNotExist()
        compose.onNodeWithTag(COPY).assertDoesNotExist()
        assertEquals(CLIPBOARD_SENTINEL, clipboardText())
        assertTrue(runBlocking { f.repository.getAccountFingerprint(f.vaultId).isFailure })
        onMain { SessionManager.markUnlocked() }
        settle()
        compose.onNodeWithTag(SHEET).assertDoesNotExist()
        onMain { staleCopy() }
        assertEquals(CLIPBOARD_SENTINEL, clipboardText())
    }

    @Test fun vaultLockClosesSheetAndRejectsAnAlreadyCapturedCopyCallback(): Unit = withFixture { f ->
        showAccount(f)
        openPhrase(f)
        val staleCopy = captureCopyAction()
        setClipboardSentinel()
        runBlocking { f.repository.forceLock(f.vaultId) }
        onMain { staleCopy() }
        waitAbsent(SHEET)
        compose.onNodeWithTag(PHRASE).assertDoesNotExist()
        compose.onNodeWithTag(COPY).assertDoesNotExist()
        assertEquals(CLIPBOARD_SENTINEL, clipboardText())
        assertTrue(runBlocking { f.repository.getAccountFingerprint(f.vaultId).isFailure })
        assertTrue(runBlocking { f.database.bitwardenVaultDao().getVaultById(f.vaultId)!!.isLocked })
    }

    @Test fun changedAccountBindingAndAuthenticatedStampRejectTheCachedIdentity(): Unit = withFixture { f ->
        showAccount(f)
        openPhrase(f)
        hideAccount()
        val dao = f.database.bitwardenVaultDao()
        val original = runBlocking { requireNotNull(dao.getVaultById(f.vaultId)) }
        val replacements = listOf(
            original.copy(userId = UUID.randomUUID().toString()),
            original.copy(email = "changed-${f.userId}@example.invalid"),
            original.copy(serverUrl = "https://different.example.invalid"),
            original.copy(revisionDate = "synthetic-local-changed-stamp"),
        )
        try {
            replacements.forEach { changed ->
                runBlocking { dao.update(changed) }
                assertTrue("A cached identity cannot cross an account binding", runBlocking { f.repository.getAccountFingerprint(f.vaultId).isFailure })
                runBlocking { dao.update(original) }
                assertEquals(f.expectedPhrase, runBlocking { f.repository.getAccountFingerprint(f.vaultId).getOrThrow() })
            }
        } finally { runBlocking { dao.update(original) } }
        f.stamp.set("synthetic-server-revoked-stamp")
        assertTrue(runBlocking { f.repository.getAccountDevices(f.vaultId).isFailure })
        assertFalse(f.repository.isVaultUnlocked(f.vaultId))
        assertNull("Authenticated stamp revocation must remove the sealed identity", f.sealedIdentity())
        assertEquals(listOf(PROFILE, DEVICES, PROFILE), f.paths.toList())
        // Recreate only this synthetic session. The revoked identity must not return from disk.
        runBlocking { dao.update(requireNotNull(dao.getVaultById(f.vaultId)).copy(isLocked = false, isConnected = true)) }
        f.installSession(f.repository)
        assertTrue(runBlocking { f.repository.getAccountFingerprint(f.vaultId).isFailure })
    }

    @Test fun missingProfilePrivateKeyShowsLocalizedErrorAndDisablesCopy(): Unit =
        withFixture(Language.PORTUGUESE_BRAZIL) { f ->
            f.profilePrivateKey.set(null)
            showAccount(f)
            openFingerprintEntry()
            waitTag(ERROR)
            compose.onNodeWithTag(ERROR).assertTextEquals(f.localized.getString(R.string.bitwarden_account_fingerprint_unavailable))
            compose.onNodeWithTag(PHRASE).assertDoesNotExist()
            compose.onNodeWithTag(COPY).performScrollTo().assertIsNotEnabled()
            assertNull(f.sealedIdentity())
            assertTrue(runBlocking { f.repository.getAccountFingerprint(f.vaultId).isFailure })
            assertEquals(listOf(PROFILE, DEVICES), f.paths.toList())
            screenshotIfRequested("missing-key-portuguese")
        }

    @Test fun lateProfileCannotDeleteOrReplaceIdentityAfterLockAndSameTokenUnlock(): Unit = withFixture { f ->
        showAccount(f)
        openPhrase(f)
        hideAccount()
        listOf(true, false).forEach { malformed ->
            val oldEnvelope = if (malformed) {
                val key = f.newKey()
                try { BitwardenCrypto.encrypt(byteArrayOf(9, 8, 7), key) } finally { key.clear() }
            } else requireNotNull(f.profilePrivateKey.get())
            val held = f.holdNextProfile(oldEnvelope)
            runBlocking {
                val oldRequest = async(Dispatchers.IO) { f.repository.getAccountDevices(f.vaultId) }
                try {
                    // This is the instrumentation thread; never block Compose's/main idle thread.
                    assertTrue("Old profile request must reach the server", held.entered.await(20, TimeUnit.SECONDS))
                    f.repository.forceLock(f.vaultId)
                    val dao = f.database.bitwardenVaultDao()
                    dao.update(requireNotNull(dao.getVaultById(f.vaultId)).copy(isLocked = false, isConnected = true))
                    f.installSession(f.repository) // Same token and key bytes, distinct key object/generation.
                    f.rotateIdentity()
                    assertTrue(f.repository.getAccountDevices(f.vaultId).isSuccess)
                    val currentPhrase = f.repository.getAccountFingerprint(f.vaultId).getOrThrow()
                    val currentSealed = requireNotNull(f.sealedIdentity())
                    assertEquals(f.expectedPhrase, currentPhrase)
                    held.release.countDown()
                    assertTrue("The old profile request must fail after a lock generation change", oldRequest.await().isFailure)
                    assertEquals("Late malformed/old profile must not mutate current identity", currentSealed, f.sealedIdentity())
                    assertEquals(currentPhrase, f.repository.getAccountFingerprint(f.vaultId).getOrThrow())
                } finally { held.release.countDown(); oldRequest.cancel() }
            }
        }
    }

    @Test fun malformedProfilePrivateKeyRemovesOldIdentityAndCannotCopyTheOldPhrase(): Unit = withFixture { f ->
        showAccount(f)
        openPhrase(f)
        val staleCopy = captureCopyAction()
        compose.onNode(hasText(f.localized.getString(R.string.close)) and hasAnyAncestor(hasTestTag(SHEET)))
            .performScrollTo().performClick()
        waitAbsent(SHEET)
        val key = f.newKey()
        try { f.profilePrivateKey.set(BitwardenCrypto.encrypt(byteArrayOf(1, 2, 3, 4), key)) } finally { key.clear() }
        compose.onNodeWithContentDescription(f.localized.getString(R.string.refresh)).performClick()
        compose.waitUntil(20_000) { f.paths.count { it == DEVICES } == 2 }
        waitForDeviceRow()
        openFingerprintEntry()
        waitTag(ERROR)
        compose.onNodeWithTag(ERROR).assertTextEquals(f.localized.getString(R.string.bitwarden_account_fingerprint_unavailable))
        compose.onNodeWithTag(COPY).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(PHRASE).assertDoesNotExist()
        assertNull(f.sealedIdentity())
        assertTrue(runBlocking { f.repository.getAccountFingerprint(f.vaultId).isFailure })
        setClipboardSentinel()
        onMain { staleCopy() }
        assertEquals(CLIPBOARD_SENTINEL, clipboardText())
    }

    @Test fun largeFontPhraseWrapsWithoutOverflowAndBackgroundRevokesTheSheet(): Unit = withFixture { f ->
        showAccount(f, fontScale = 1.5f)
        openPhrase(f)
        screenshotIfRequested("english-large-before-layout")
        val phraseNode = compose.onNodeWithTag(PHRASE, useUnmergedTree = true).assertIsDisplayed()
        val phraseLayout = textLayouts(phraseNode)
        assertTrue("The real phrase must wrap at large font scale", phraseLayout.any { it.lineCount > 1 })
        phraseLayout.forEach { layout ->
            for (line in 0 until layout.lineCount - 1) {
                val prefix = layout.layoutInput.text.text.take(layout.getLineEnd(line, visibleEnd = true))
                    .trimEnd('\u200B', '\n', ' ')
                assertTrue("This viewport must wrap at existing hyphens, not inside an English word: $prefix", prefix.endsWith('-'))
            }
        }
        assertTextFits("protocol phrase", phraseNode, phraseLayout, expectedFontScale = 1.5f)
        listOf(R.string.bitwarden_account_fingerprint, R.string.bitwarden_account_fingerprint_hint,
            R.string.bitwarden_account_fingerprint_copy).forEach { id ->
            val label = f.localized.getString(id)
            val node = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(SHEET)), useUnmergedTree = true)
            node.performScrollTo().assertIsDisplayed()
            screenshotIfRequested("english-large-text-" + f.localized.resources.getResourceEntryName(id))
            assertTextFits(label, node, textLayouts(node), expectedFontScale = 1.5f)
        }
        compose.onNodeWithTag(PHRASE).performScrollTo().assertIsDisplayed()
        screenshotIfRequested("english-large-wrapped")
        val staleCopy = captureCopyAction()
        setClipboardSentinel()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        onMain { staleCopy() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(20_000) { f.paths.count { it == DEVICES } >= 2 }
        waitAbsent(SHEET)
        compose.onNodeWithTag(PHRASE).assertDoesNotExist()
        onMain { staleCopy() }
        assertEquals(CLIPBOARD_SENTINEL, clipboardText())
    }

    private fun withFixture(language: Language = Language.ENGLISH, block: (Fixture) -> Unit) {
        val f = Fixture(language)
        var originalFailure: Throwable? = null
        try {
            runBlocking { f.initialize() }
            block(f)
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            try {
                try {
                    if (screenShown) hideAccount() else onMain { models.clear() }
                    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                    if (screenShown) compose.waitForIdle()
                } finally {
                    try { runBlocking { f.close() } } finally { restoreActivityConfiguration() }
                }
            } catch (cleanupFailure: Throwable) {
                val original = originalFailure
                if (original != null) original.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    private fun showAccount(f: Fixture, fontScale: Float = 1f) {
        val model = onMainValue {
            BitwardenViewModel(compose.activity.application).also { models.put("fingerprint-owned", it) }
        }
        val configuration = Configuration(f.localized.resources.configuration).apply { this.fontScale = fontScale }
        onMain {
            val resources = compose.activity.resources
            if (originalActivityConfiguration == null) originalActivityConfiguration = Configuration(resources.configuration)
            // ModalBottomSheet's DialogWrapper uses the host View's ContextThemeWrapper.
            // CompositionLocals alone cannot set the new window's resource locale/font scale.
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, resources.displayMetrics)
            assertEquals(configuration.locales[0], resources.configuration.locales[0])
            assertEquals(fontScale, resources.configuration.fontScale, 0.001f)
        }
        screenShown = true
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides f.localized, LocalConfiguration provides configuration,
                LocalDensity provides Density(density, fontScale)) {
                MonicaTheme { Surface(Modifier.fillMaxSize()) {
                    if (screenVisible.value) BitwardenAccountScreen(model, f.vaultId) {}
                } }
            }
        }
        waitForDeviceRow()
    }

    private fun restoreActivityConfiguration() {
        val original = originalActivityConfiguration ?: return
        onMain {
            val resources = compose.activity.resources
            @Suppress("DEPRECATION")
            resources.updateConfiguration(original, resources.displayMetrics)
        }
        originalActivityConfiguration = null
    }

    private fun hideAccount() {
        try {
            compose.runOnIdle { screenVisible.value = false }
            compose.waitForIdle()
        } finally {
            // Dispose effects before canceling their model; still release it if the test failed.
            onMain { models.clear() }
        }
    }

    private fun openFingerprintEntry() {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(ENTRY))
        compose.onNodeWithTag(ENTRY).performScrollTo().assertIsEnabled().performClick()
        waitTag(SHEET)
    }

    private fun waitForDeviceRow() {
        // The extra account action can place this LazyColumn item outside the viewport.
        compose.waitUntil(20_000) {
            runCatching {
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(DEVICE))
                compose.onNodeWithTag(DEVICE).assertIsDisplayed()
            }.isSuccess
        }
        settle()
    }

    private fun openPhrase(f: Fixture) {
        openFingerprintEntry()
        waitTag(PHRASE)
        compose.onNode(hasText(f.localized.getString(R.string.bitwarden_account_fingerprint)) and
            hasAnyAncestor(hasTestTag(SHEET)), useUnmergedTree = true).assertExists()
        val node = compose.onNodeWithTag(PHRASE).performScrollTo()
        val shown = textLayouts(node).single().layoutInput.text.text
        assertEquals("Display break hints must not alter the account fingerprint", f.expectedPhrase, shown.replace("\u200B", ""))
    }

    private fun captureCopyAction(): () -> Boolean = requireNotNull(
        compose.onNodeWithTag(COPY).fetchSemanticsNode().config[SemanticsActions.OnClick].action)

    private fun waitTag(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        settle()
    }

    private fun waitAbsent(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
        settle()
    }

    private fun settle() { compose.mainClock.advanceTimeBy(600); compose.waitForIdle() }

    private fun textLayouts(node: SemanticsNodeInteraction): List<TextLayoutResult> =
        mutableListOf<TextLayoutResult>().also { results ->
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertTrue("Expected actual production TextLayoutResult", results.isNotEmpty())
        }

    private fun assertTextFits(
        label: String,
        target: SemanticsNodeInteraction,
        results: List<TextLayoutResult>,
        expectedFontScale: Float,
    ) {
        val node = target.assertIsDisplayed().fetchSemanticsNode()
        val origin = node.positionInRoot
        val visible = node.boundsInRoot
        results.forEach { result ->
            val input = result.layoutInput
            val diagnostic = "label=$label; actualText=${input.text.text}; size=${result.size}; " +
                "nodeSize=${node.size}; originInRootPx=$origin; visibleBoundsInRootPx=$visible; " +
                "constraints=${input.constraints}; density=${input.density.density}; fontScale=${input.density.fontScale}; " +
                "fontSize=${input.style.fontSize}; lineHeight=${input.style.lineHeight}; maxLines=${input.maxLines}; " +
                "softWrap=${input.softWrap}; overflow=${input.overflow}; paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}; " +
                "visualOverflow=${result.hasVisualOverflow}; widthOverflow=${result.didOverflowWidth}; heightOverflow=${result.didOverflowHeight}; " +
                "lines=${result.lineCount}; bounds=" + (0 until result.lineCount).joinToString { line ->
                    "${result.getLineStart(line)}..${result.getLineEnd(line, visibleEnd = false)}: " +
                        "${result.getLineLeft(line)}..${result.getLineRight(line)}, ${result.getLineTop(line)}..${result.getLineBottom(line)}, " +
                        "ellipsis=${result.isLineEllipsized(line)}"
                }
            assertEquals("The real dialog must use the requested font scale; $diagnostic", expectedFontScale, input.density.fontScale, 0.001f)
            // Retained main-native-fixes-device.txt failed at fontScale 1.5: the title measured
            // 861px, its paragraph retained 954px, and its complete line ended at 860.5742px.
            // The reviewed screenshot confirmed the complete title. Keep hasVisualOverflow
            // diagnostic, while checking actual lines/characters and clipping independently.
            assertFalse("Text must not overflow vertically; $diagnostic", result.didOverflowHeight)
            assertFalse("Text must not omit lines; $diagnostic", result.multiParagraph.didExceedMaxLines)
            assertTrue(diagnostic, input.text.isNotEmpty() && result.lineCount > 0)
            assertEquals(diagnostic, 0, result.getLineStart(0))
            assertEquals(diagnostic, input.text.length, result.getLineEnd(result.lineCount - 1, visibleEnd = false))
            // These unmerged Text nodes expose content coordinates. The phrase's testTag is
            // after padding, so all positions and bounds below are pixels, without a dp offset.
            assertEquals("Text node/layout widths must share the same origin; $diagnostic", result.size.width.toFloat(), node.size.width.toFloat(), 1f)
            assertEquals("Text node/layout heights must share the same origin; $diagnostic", result.size.height.toFloat(), node.size.height.toFloat(), 1f)
            assertEquals("The whole text node must remain horizontally visible; $diagnostic", node.size.width.toFloat(), visible.width, 1f)
            assertEquals("The whole text node must remain vertically visible; $diagnostic", node.size.height.toFloat(), visible.height, 1f)
            fun assertBounds(left: Float, top: Float, right: Float, bottom: Float, part: String) {
                val detail = "$part=[$left,$top,$right,$bottom]; $diagnostic"
                assertTrue("Text must fit its measured width; $detail", left >= -1f && right <= result.size.width + 1f)
                assertTrue("Text must fit its measured height; $detail", top >= -1f && bottom <= result.size.height + 1f)
                assertTrue("Text must fit the visible horizontal bounds; $detail",
                    origin.x + left >= visible.left - 1f && origin.x + right <= visible.right + 1f)
                assertTrue("Text must fit the visible vertical bounds; $detail",
                    origin.y + top >= visible.top - 1f && origin.y + bottom <= visible.bottom + 1f)
            }
            (0 until result.lineCount).forEach { line ->
                assertFalse(diagnostic, result.isLineEllipsized(line))
                assertBounds(result.getLineLeft(line), result.getLineTop(line), result.getLineRight(line), result.getLineBottom(line), "line $line")
            }
            input.text.text.indices.filter { !input.text[it].isWhitespace() }.forEach { offset ->
                val box = result.getBoundingBox(offset)
                assertBounds(box.left, box.top, box.right, box.bottom, "character $offset")
            }
        }
    }

    private fun clipboard() = context.getSystemService(ClipboardManager::class.java)
    private fun clipboardText(): String? = onMainValue { clipboard().primaryClip?.getItemAt(0)?.text?.toString() }
    private fun setClipboardSentinel() = onMain { clipboard().setPrimaryClip(ClipData.newPlainText("synthetic-fingerprint-sentinel", CLIPBOARD_SENTINEL)) }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync {
        check(Looper.myLooper() == Looper.getMainLooper())
        action()
    }
    private fun <T> onMainValue(action: () -> T): T = AtomicReference<T>().also { result -> onMain { result.set(action()) } }.get()

    private fun screenshotIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("fingerprintScreenshots") != "true") return
        settle()
        check(Build.VERSION.SDK_INT >= 29) { "These window/frame diagnostics require the shared API32 AVD" }
        val diagnostic = File(context.filesDir, "bitwarden-fingerprint-$name-window.txt")
        diagnostic.writeText(windowDiagnostic("before-focus"))
        try {
            val host = compose.activity
            var focusedDialog: View? = null
            compose.waitUntil(5_000) {
                focusedDialog = onMainValue {
                    WindowInspector.getGlobalWindowViews().singleOrNull { root ->
                        root !== host.window.decorView && root.isAttachedToWindow && root.isShown &&
                            root.windowVisibility == View.VISIBLE && root.hasWindowFocus() &&
                            root.width > 0 && root.height > 0
                    }
                }
                focusedDialog != null
            }
            val root = requireNotNull(focusedDialog)
            val committed = CountDownLatch(1)
            val callback = Runnable { committed.countDown() }
            val observer = onMainValue {
                assertEquals("Screenshot host must still be resumed", Lifecycle.State.RESUMED, host.lifecycle.currentState)
                assertTrue("A real hardware-rendered dialog is required", root.isHardwareAccelerated)
                root.viewTreeObserver.also {
                    it.registerFrameCommitCallback(callback)
                    root.postInvalidateOnAnimation()
                }
            }
            val frameCommitted = try { committed.await(5, TimeUnit.SECONDS) } finally {
                onMain { if (observer.isAlive) observer.unregisterFrameCommitCallback(callback) }
            }
            diagnostic.appendText("frameCommitted=$frameCommitted\n")
            assertTrue("The focused dialog must submit a real frame before capture", frameCommitted)
            instrumentation.waitForIdleSync()
            onMain {
                assertEquals(Lifecycle.State.RESUMED, host.lifecycle.currentState)
                assertTrue("The captured dialog must retain foreground focus", root.isAttachedToWindow && root.isShown && root.hasWindowFocus())
            }
            diagnostic.appendText(windowDiagnostic("after-frame"))
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(context.filesDir, "bitwarden-fingerprint-$name.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                // Save the raw full-screen evidence first. A uniform center region is the
                // previously observed blank host frame, never a visual acceptance result.
                val colors = mutableSetOf<Int>()
                for (y in bitmap.height / 10 until bitmap.height * 9 / 10 step 24) {
                    for (x in bitmap.width / 10 until bitmap.width * 9 / 10 step 24) {
                        colors += bitmap.getPixel(x, y) and 0x00FFFFFF
                    }
                }
                diagnostic.appendText("capture=${bitmap.width}x${bitmap.height}; sampledColors=${colors.size}\n")
                assertTrue("Full-screen capture is blank; retain PNG and window diagnostics", colors.size > 1)
            } finally { bitmap.recycle() }
        } finally { diagnostic.appendText(windowDiagnostic("capture-finished")) }
        // The host may pull these synthetic captures into ignored docs/design; never commit images.
    }

    private fun windowDiagnostic(stage: String): String = onMainValue {
        buildString {
            appendLine("stage=$stage; uptime=${SystemClock.uptimeMillis()}; thread=${Thread.currentThread().name}; lifecycle=${compose.activity.lifecycle.currentState}")
            WindowInspector.getGlobalWindowViews().forEachIndexed { index, root ->
                val parameters = root.layoutParams as? WindowManager.LayoutParams
                appendLine("root=$index; class=${root.javaClass.name}; title=${parameters?.title}; type=${parameters?.type}; " +
                    "attached=${root.isAttachedToWindow}; shown=${root.isShown}; visibility=${root.windowVisibility}; " +
                    "focus=${root.hasWindowFocus()}; hardware=${root.isHardwareAccelerated}; size=${root.width}x${root.height}; " +
                    "alpha=${root.alpha}; flags=${parameters?.flags}")
            }
        }
    }

    private inner class Fixture(language: Language) {
        private val originalLocale = Locale.getDefault()
        private val originalUnlocked = SessionManager.isUnlocked.value
        private val originalClipboard = onMainValue { clipboard().primaryClip }
        val localized = LocaleHelper.setLocale(context, language)
        val repository = BitwardenRepository.getInstance(context)
        val database = PasswordDatabase.getDatabase(context)
        private val server = MockWebServer()
        private var serverStopped = false
        private val replicas = mutableListOf<BitwardenRepository>()
        private val heldProfiles = CopyOnWriteArrayList<HeldProfile>()
        private val nextHeldProfile = AtomicReference<HeldProfile?>()
        val paths = CopyOnWriteArrayList<String>()
        val stamp = AtomicReference("synthetic-fingerprint-stamp")
        val profilePrivateKey = AtomicReference<String?>()
        val userId = UUID.randomUUID().toString()
        private val email = "fingerprint-$userId@example.invalid"
        private val accountKey = "fingerprint-test-$userId"
        private val token = "synthetic-fingerprint-token-$userId"
        lateinit var publicKey: ByteArray
        lateinit var expectedPhrase: String
        var vaultId = 0L

        fun newKey() = SymmetricCryptoKey(ByteArray(32) { 41 }, ByteArray(32) { 73 })

        suspend fun initialize() {
            check(repository.getAllVaults().none { repository.isVaultUnlocked(it.id) }) {
                "Run in the dedicated synthetic user without other unlocked accounts"
            }
            onMain { SessionManager.markUnlocked() }
            rotateIdentity()
            val endpoint = server.url("/").toString().trimEnd('/')
            vaultId = database.bitwardenVaultDao().insert(BitwardenVault(
                email = email, canonicalEmail = email, userId = userId, accountKey = accountKey,
                displayName = "Synthetic fingerprint account", serverUrl = endpoint,
                identityUrl = "$endpoint/identity", apiUrl = "$endpoint/api/",
                encryptedAccessToken = "synthetic-fingerprint-access-marker",
                encryptedRefreshToken = "synthetic-fingerprint-refresh-marker",
                accessTokenExpiresAt = System.currentTimeMillis() + 3_600_000,
                revisionDate = stamp.get(), isLocked = false, isConnected = true, syncEnabled = false,
            ))
            installSession(repository)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    paths += path
                    if (request.getHeader("Authorization") != "Bearer $token") return MockResponse().setResponseCode(401)
                    return when (path) {
                        PROFILE -> {
                            val held = nextHeldProfile.getAndSet(null)
                            val body = buildJsonObject {
                                put("id", userId); put("email", email); put("securityStamp", stamp.get())
                                (held?.envelope ?: profilePrivateKey.get())?.let { put("privateKey", it) }
                            }.toString()
                            if (held != null) {
                                held.entered.countDown()
                                if (!held.release.await(20, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                            }
                            ok(body)
                        }
                        DEVICES -> ok("""{"data":[{"id":"fingerprint-device","name":"Synthetic fingerprint device","isTrusted":true}]}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        }

        fun rotateIdentity() {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            if (::publicKey.isInitialized) publicKey.fill(0)
            publicKey = pair.public.encoded
            val privateBytes = pair.private.encoded
            val key = newKey()
            try { profilePrivateKey.set(BitwardenCrypto.encrypt(privateBytes, key)) }
            finally { privateBytes.fill(0); key.clear(); runCatching { pair.private.destroy() } }
            val words = context.resources.openRawResource(R.raw.bitwarden_fingerprint_words).bufferedReader().use { it.readLines() }
            // Independent of profile decryption/capture: start from the generated public SPKI.
            expectedPhrase = BitwardenAccountFingerprint.phrase(userId, publicKey, words)
        }

        fun holdNextProfile(envelope: String): HeldProfile = HeldProfile(envelope).also {
            check(nextHeldProfile.compareAndSet(null, it))
            heldProfiles += it
        }

        fun installSession(target: BitwardenRepository) {
            cache<SymmetricCryptoKey>(target, "symmetricKeyCache").put(vaultId, newKey())?.clear()
            cache<String>(target, "accessTokenCache")[vaultId] = token
        }

        fun recreateRepository(): BitwardenRepository = BitwardenRepository(context.applicationContext).also { replicas += it }

        fun sealedIdentity(): String? {
            val getter = BitwardenRepository::class.java.getDeclaredMethod("getSecurePrefs").apply { isAccessible = true }
            val preferences = requireNotNull(getter.invoke(repository) as? SharedPreferences)
            return preferences.getString("bitwarden_account_identity_v1_$vaultId", null)
        }

        fun stopServer() {
            heldProfiles.forEach { it.release.countDown() }
            if (!serverStopped) { server.shutdown(); serverStopped = true }
        }

        suspend fun close() {
            try {
                if (vaultId != 0L) {
                    replicas.forEach { target ->
                        cache<SymmetricCryptoKey>(target, "symmetricKeyCache").remove(vaultId)?.clear()
                        cache<String>(target, "accessTokenCache").remove(vaultId)
                    }
                    database.bitwardenVaultDao().getVaultById(vaultId)?.let { row ->
                        check(row.accountKey == accountKey) { "Refuse to delete a fixture whose owner changed" }
                        check(repository.logout(vaultId)) { "Could not clean up the owned fingerprint fixture" }
                    }
                }
            } finally {
                try { stopServer() } finally {
                    if (::publicKey.isInitialized) publicKey.fill(0)
                    profilePrivateKey.set(null)
                    onMain {
                        if (originalUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
                        if (originalClipboard == null) clipboard().clearPrimaryClip() else clipboard().setPrimaryClip(originalClipboard)
                    }
                    Locale.setDefault(originalLocale)
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> cache(target: BitwardenRepository, name: String): MutableMap<Long, T> =
            BitwardenRepository::class.java.getDeclaredField(name).apply { isAccessible = true }.get(target) as MutableMap<Long, T>
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private class HeldProfile(val envelope: String) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private companion object {
        const val PROFILE = "/api/accounts/profile"
        const val DEVICES = "/api/devices"
        const val ENTRY = "bitwarden_account_fingerprint"
        const val SHEET = "bitwarden_fingerprint_sheet"
        const val PHRASE = "bitwarden_fingerprint_phrase"
        const val COPY = "bitwarden_fingerprint_copy"
        const val ERROR = "bitwarden_fingerprint_error"
        const val DEVICE = "bitwarden_account_device_fingerprint-device"
        const val CLIPBOARD_SENTINEL = "synthetic clipboard stays unchanged"
    }
}
