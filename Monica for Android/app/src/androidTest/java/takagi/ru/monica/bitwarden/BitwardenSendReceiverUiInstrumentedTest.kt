package takagi.ru.monica.bitwarden

import android.graphics.Bitmap
import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.api.*
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.service.*
import takagi.ru.monica.bitwarden.ui.BitwardenSendReceiverDialog
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.theme.MonicaTheme
import java.io.File
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real Compose receiver + production session/crypto, synthetic transport only. No account rows. */
@RunWith(AndroidJUnit4::class)
class BitwardenSendReceiverUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val dismissed = AtomicBoolean(false)
    private val created = AtomicInteger(0)
    private var originalUnlocked = false

    @Before fun unlockSyntheticRuntime() {
        originalUnlocked = SessionManager.isUnlocked.value
        compose.runOnUiThread { SessionManager.markUnlocked() }
    }
    @After fun restoreRuntimeState() {
        compose.runOnUiThread {
            if (originalUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
        }
    }

    @Test fun nativeTextReceiverWorksWithoutAnyBitwardenAccountAndHonorsHiddenText() {
        val transport = SyntheticTransport()
        show(transport)
        enterLink("https://send.bitwarden.com/#$ID/$KEY")
        waitForTag("bitwarden_receiver_content")
        compose.onNodeWithText(CONTENT).assertDoesNotExist()
        compose.onNodeWithTag("bitwarden_receiver_reveal").performClick()
        compose.onNodeWithText(CONTENT).assertIsDisplayed()
        assertEquals(1, transport.credentials.size)
        assertTrue(transport.credentials.single().isEmpty())
        assertEquals(1, transport.accesses.get())
        screenshot("text-revealed")
        compose.onNodeWithTag("bitwarden_receiver_new_link").performClick()
        waitForTag("bitwarden_receiver_link")
        assertFalse("Closing the previous link must not dismiss the new receiver form", dismissed.get())
        compose.onNodeWithText(CONTENT).assertDoesNotExist()
        assertTrue(transport.closed.get())
    }

    @Test fun customServicesRequireAnExplicitReviewBeforeAnyRequest() {
        val transport = SyntheticTransport()
        show(transport)
        enterLink("https://self.example.test/team/vault/#/send/$ID/$KEY")
        waitForTag("bitwarden_receiver_confirm_target")
        assertEquals(0, created.get())
        assertTrue(transport.credentials.isEmpty())
        compose.onNodeWithTag("bitwarden_receiver_api").assertTextContains("https://self.example.test/team/vault/api/")
        compose.onNodeWithTag("bitwarden_receiver_identity").performTextReplacement("https://identity.example.test/private/")
        screenshot("custom-target-review")
        compose.onNodeWithTag("bitwarden_receiver_confirm_target").performClick()
        waitForTag("bitwarden_receiver_content")
        assertEquals("https://self.example.test/team/vault/api/", transport.targets.single().apiBase.toString())
        assertEquals("https://identity.example.test/private/", transport.targets.single().identityBase.toString())
    }

    @Test fun passwordInputIsExplicitAndClearedAfterSuccessfulOpening() {
        val transport = SyntheticTransport(Auth.PASSWORD)
        show(transport)
        enterLink("https://send.bitwarden.eu/#$ID/$KEY")
        waitForTag("bitwarden_receiver_password")
        assertEquals(0, transport.accesses.get())
        compose.onNodeWithTag("bitwarden_receiver_password").performTextInput("  synthetic password  ")
        screenshot("password")
        compose.onNodeWithTag("bitwarden_receiver_verify").performClick()
        waitForTag("bitwarden_receiver_content")
        compose.onNodeWithTag("bitwarden_receiver_password").assertDoesNotExist()
        assertEquals(setOf("password_hash_b64"), transport.credentials.last().keys)
        assertFalse(transport.credentials.last().values.single().contains("synthetic password"))
        assertEquals(1, transport.accesses.get())
    }

    @Test fun emailNeedsAUserRequestThenCodeAndCannotResendImmediately() {
        val transport = SyntheticTransport(Auth.EMAIL)
        show(transport)
        enterLink("https://vault.bitwarden.com/#/send/$ID/$KEY")
        waitForTag("bitwarden_receiver_email")
        assertEquals(1, transport.credentials.size)
        compose.onNodeWithTag("bitwarden_receiver_email").performTextInput("recipient@example.test")
        compose.onNodeWithTag("bitwarden_receiver_send_code").performClick()
        waitForTag("bitwarden_receiver_code")
        assertEquals(mapOf("email" to "recipient@example.test"), transport.credentials.last())
        compose.onNodeWithTag("bitwarden_receiver_resend").assertIsNotEnabled()
        screenshot("email-code")
        compose.onNodeWithTag("bitwarden_receiver_code").performTextInput("synthetic-code")
        compose.onNodeWithTag("bitwarden_receiver_verify").performClick()
        waitForTag("bitwarden_receiver_content")
        assertEquals(mapOf("email" to "recipient@example.test", "otp" to "synthetic-code"), transport.credentials.last())
        assertEquals(3, transport.credentials.size)
    }

    @Test fun malformedLinkNeverConstructsASessionOrSendsCredentials() {
        val transport = SyntheticTransport()
        show(transport)
        enterLink("https://user:password@send.bitwarden.com/#$ID/$KEY")
        waitForTag("bitwarden_receiver_error")
        assertEquals(0, created.get())
        assertTrue(transport.credentials.isEmpty())
        compose.onNodeWithTag("bitwarden_receiver_link").assertIsDisplayed()
    }

    @Test fun lockingClosesTheNativeReceiverAndRejectsLateContent() {
        val release = CompletableDeferred<Unit>()
        val entered = AtomicBoolean(false)
        val transport = object : SyntheticTransport() {
            override suspend fun access(target: BitwardenSendReceiverTarget, token: String): BitwardenSendReceiverAccess =
                withContext(NonCancellable) { entered.set(true); release.await(); response() }
        }
        show(transport)
        enterLink("https://send.bitwarden.com/#$ID/$KEY")
        compose.waitUntil(10_000) { entered.get() }
        compose.runOnUiThread { SessionManager.markLocked() }
        compose.waitUntil(10_000) { dismissed.get() }
        release.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithTag("bitwarden_receiver_dialog").assertDoesNotExist()
        assertTrue(transport.closed.get())
    }

    @Test fun ordinaryBackgroundClosesVisibleDecryptedDataWithoutReopeningOnResume() {
        val transport = SyntheticTransport()
        show(transport)
        enterLink("https://send.bitwarden.com/#$ID/$KEY")
        waitForTag("bitwarden_receiver_content")
        compose.onNodeWithTag("bitwarden_receiver_reveal").performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { dismissed.get() }
        compose.onNodeWithText(CONTENT).assertDoesNotExist()
        compose.onNodeWithTag("bitwarden_receiver_dialog").assertDoesNotExist()
        assertTrue(transport.closed.get())
    }

    @Test fun largeFontContentWrapsWithoutLosingRevealOrCloseControls() {
        val transport = SyntheticTransport()
        show(transport, scale = 1.5f)
        enterLink("https://send.bitwarden.com/#$ID/$KEY")
        waitForTag("bitwarden_receiver_content")
        compose.onNodeWithTag("bitwarden_receiver_reveal").assertIsDisplayed().performClick()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(CONTENT).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { assertEquals("Receiver must inherit the requested font scale", 1.5f, it.layoutInput.density.fontScale, .001f) }
        assertTrue(layouts.none { it.hasVisualOverflow })
        compose.onNodeWithTag("bitwarden_receiver_close").assertIsDisplayed()
        screenshot("large-font")
    }

    @Test fun cancellingTheActualDocumentPickerClearsTheFileSessionBeforeAnyDownloadAuthorization() {
        val transport = SyntheticTransport(file = true)
        show(transport)
        enterLink("https://send.bitwarden.com/#$ID/$KEY")
        waitForTag("bitwarden_receiver_file")
        compose.onNodeWithTag("bitwarden_receiver_file_save").assertIsDisplayed()
        screenshot("file-ready")
        compose.onNodeWithTag("bitwarden_receiver_file_save").performClick()
        compose.waitForIdle()
        val observedWindows = linkedSetOf<String>()
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var pickerVisible = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val packageName = root?.packageName?.toString()
            observedWindows += packageName ?: "no active accessibility root"
            pickerVisible = packageName?.endsWith("documentsui") == true
            root?.recycle()
            if (pickerVisible) break
            SystemClock.sleep(50)
        }
        assertTrue("Expected the platform document picker; observed=$observedWindows; dismissed=${dismissed.get()}", pickerVisible)
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        waitForTag("bitwarden_receiver_link")
        assertEquals(0, transport.fileAuthorizations.get())
        assertTrue(transport.closed.get())
        compose.onNodeWithTag("bitwarden_receiver_file").assertDoesNotExist()
    }

    private fun show(transport: SyntheticTransport, scale: Float = 1f) {
        compose.setContent {
            val visible = remember { mutableStateOf(true) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MonicaTheme(darkTheme = arguments.getString("sendReceiverDark") == "true") {
                    Surface {
                        if (visible.value) BitwardenSendReceiverDialog(
                            onDismiss = { dismissed.set(true); visible.value = false },
                            sessionFactory = { link, target, current ->
                                created.incrementAndGet()
                                BitwardenSendReceiverSession(link, target, transport, current)
                            },
                        )
                    }
                }
            }
        }
        waitForTag("bitwarden_receiver_link")
    }

    private fun enterLink(link: String) {
        compose.onNodeWithTag("bitwarden_receiver_link").performTextInput(link)
        compose.onNodeWithTag("bitwarden_receiver_open").performClick()
    }
    private fun waitForTag(tag: String) {
        try {
            compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Missing $tag; dismissed=${dismissed.get()}; synthetic tree: " +
                compose.onRoot(useUnmergedTree = true).printToString(), error)
        }
    }
    private fun screenshot(name: String) {
        if (arguments.getString("sendReceiverScreenshots") != "true") return
        val folder = File(instrumentation.targetContext.filesDir, "send-receiver-325").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose.onNodeWithTag("bitwarden_receiver_dialog").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private enum class Auth { NONE, PASSWORD, EMAIL }
    private open class SyntheticTransport(private val auth: Auth = Auth.NONE, private val file: Boolean = false) : BitwardenSendReceiverTransport {
        val credentials = CopyOnWriteArrayList<Map<String, String>>()
        val targets = CopyOnWriteArrayList<BitwardenSendReceiverTarget>()
        val accesses = AtomicInteger(0)
        val fileAuthorizations = AtomicInteger(0)
        val closed = AtomicBoolean(false)
        override suspend fun token(target: BitwardenSendReceiverTarget, accessId: String,
                                   credential: BitwardenSendReceiverCredential): BitwardenSendReceiverTokenReply {
            targets += target; credentials += credential.fields.toMap()
            return when {
                auth == Auth.PASSWORD && "password_hash_b64" !in credential.fields -> BitwardenSendReceiverTokenReply.Challenge(BitwardenSendReceiverChallenge.PASSWORD)
                auth == Auth.EMAIL && "email" !in credential.fields -> BitwardenSendReceiverTokenReply.Challenge(BitwardenSendReceiverChallenge.EMAIL)
                auth == Auth.EMAIL && "otp" !in credential.fields -> BitwardenSendReceiverTokenReply.Challenge(BitwardenSendReceiverChallenge.EMAIL_CODE)
                else -> BitwardenSendReceiverTokenReply.Granted("synthetic-receiver-token", 120)
            }
        }
        fun response(): BitwardenSendReceiverAccess {
            val key = BitwardenCrypto.deriveSendKey(Base64.getUrlDecoder().decode(KEY))
            return try {
                if (file) BitwardenSendReceiverAccess(ID, 1, BitwardenCrypto.encryptString("Synthetic shared file", key),
                    null, false, "file-id", BitwardenCrypto.encryptString("received.txt", key), 65, null, null)
                else BitwardenSendReceiverAccess(ID, 0, BitwardenCrypto.encryptString("Synthetic shared text", key),
                    BitwardenCrypto.encryptString(CONTENT, key), true, null, null, null, null, null)
            }
            finally { key.clear() }
        }
        override suspend fun access(target: BitwardenSendReceiverTarget, token: String): BitwardenSendReceiverAccess {
            accesses.incrementAndGet(); return response()
        }
        override suspend fun authorizeFile(target: BitwardenSendReceiverTarget, token: String, fileId: String): BitwardenSendReceiverDownload {
            fileAuthorizations.incrementAndGet()
            return BitwardenSendReceiverDownload(fileId, "https://synthetic.example.test/file".toHttpUrl())
        }
        override suspend fun download(target: BitwardenSendReceiverDownload, expectedBytes: Long?, sink: OutputStream,
                                      requireCurrent: () -> Unit, progress: (Long, Long?) -> Unit) = error("Text fixture cannot download")
        override fun close() { closed.set(true) }
    }

    companion object {
        const val ID = "AAAAAAAAAAAAAAAAAAAAAA"
        const val KEY = "Pgui0FK85cNhBGWHAlBHBw"
        const val CONTENT = "Synthetic receiver text with preserved whitespace and a long sentence that wraps clearly at larger font sizes."
    }
}
