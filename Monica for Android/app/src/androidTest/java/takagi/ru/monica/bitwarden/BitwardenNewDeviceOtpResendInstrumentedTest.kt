package takagi.ru.monica.bitwarden

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.service.BitwardenAuthService
import takagi.ru.monica.bitwarden.ui.BitwardenLoginScreen
import takagi.ru.monica.bitwarden.ui.TwoFactorDialog
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.bitwarden.viewmodel.NewDeviceOtpResendOutcome
import takagi.ru.monica.bitwarden.viewmodel.NewDeviceOtpResendState
import takagi.ru.monica.data.Language
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.LocaleHelper

/** Real pending login + explicit native resend, using only an in-process synthetic HTTP server. */
@RunWith(AndroidJUnit4::class)
class BitwardenNewDeviceOtpResendInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val english get() = LocaleHelper.setLocale(context, Language.ENGLISH)
    private val models = ViewModelStore()
    private val originallyUnlocked = SessionManager.isUnlocked.value
    private val originalLocale = Locale.getDefault()
    private var originalConfiguration: Configuration? = null

    @After fun cleanUp() {
        try {
            compose.runOnIdle { models.clear() }
        } finally {
            originalConfiguration?.let { original ->
                compose.runOnUiThread {
                    @Suppress("DEPRECATION")
                    compose.activity.resources.updateConfiguration(original, compose.activity.resources.displayMetrics)
                }
            }
            Locale.setDefault(originalLocale)
            if (originallyUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
        }
    }

    @Test fun nativeResendUsesCurrentProofOnlyAfterClickAndKeepsNewDeviceOtpSubmission() {
        Fixture().use { fixture ->
            val model = showLogin(fixture)
            assertEquals(0, fixture.resends.size)
            compose.onNodeWithTag("bitwarden_two_factor_send_email").assertDoesNotExist()
            compose.onNodeWithTag("bitwarden_two_factor_remember").assertDoesNotExist()
            compose.onNodeWithTag("bitwarden_new_device_resend").performScrollTo().assertIsEnabled().performClick()
            waitForOutcome(model, NewDeviceOtpResendOutcome.SENT)
            compose.onNodeWithTag("bitwarden_new_device_resend").assertIsNotEnabled()
            compose.onNodeWithTag("bitwarden_new_device_resend_status").performScrollTo()
                .assertTextEquals(english.getString(R.string.bitwarden_new_device_resend_sent))
            val request = fixture.resends.single()
            assertEquals("POST", request.method)
            assertNull(request.getHeader("Authorization"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(setOf("Email", "MasterPasswordHash"), body.keys)
            assertEquals(fixture.email, body["Email"]!!.jsonPrimitive.content)
            assertEquals(fixture.forms.first()["password"], body["MasterPasswordHash"]!!.jsonPrimitive.content)
            screenshotIfRequested("sent")
            compose.onNodeWithTag("bitwarden_two_factor_code").performScrollTo().performTextInput("654321")
            compose.onNodeWithTag("bitwarden_two_factor_submit").performClick()
            compose.waitUntil(20_000) { fixture.forms.size == 2 }
            assertEquals("654321", fixture.forms.last()["newDeviceOtp"])
            assertFalse(fixture.forms.last().containsKey("twoFactorToken"))
            assertEquals(1, fixture.resends.size)
            assertFalse(fixture.paths.any { it.contains("two-factor/send-email-login") })
        }
    }

    @Test fun nativeRateLimitShowsSanitizedMessageAndServerCooldown() {
        Fixture().use { fixture ->
            fixture.resendStatus = 429
            fixture.retryAfter = "91"
            val model = showLogin(fixture)
            compose.onNodeWithTag("bitwarden_new_device_resend").performScrollTo().performClick()
            waitForOutcome(model, NewDeviceOtpResendOutcome.RATE_LIMITED)
            assertTrue(model.newDeviceOtpResend.value.remainingSeconds in 80..91)
            compose.onNodeWithTag("bitwarden_new_device_resend_status").performScrollTo()
                .assertTextEquals(english.getString(R.string.bitwarden_new_device_resend_rate_limited))
            compose.onNodeWithTag("bitwarden_new_device_resend_wait").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("synthetic-sensitive-server-detail", substring = true).assertDoesNotExist()
            compose.onNodeWithTag("bitwarden_new_device_resend").assertIsNotEnabled()
            screenshotIfRequested("rate-limit")
        }
    }

    @Test fun nativeUnsupportedServerShowsTruthfulFailureWithoutOrdinaryEmailFallback() {
        Fixture().use { fixture ->
            fixture.resendStatus = 404
            val model = showLogin(fixture)
            compose.onNodeWithTag("bitwarden_new_device_resend").performScrollTo().performClick()
            waitForOutcome(model, NewDeviceOtpResendOutcome.UNAVAILABLE)
            compose.onNodeWithTag("bitwarden_new_device_resend_status").performScrollTo()
                .assertTextEquals(english.getString(R.string.bitwarden_new_device_resend_unavailable))
            compose.onNodeWithTag("bitwarden_new_device_resend").assertIsNotEnabled()
            assertEquals(1, fixture.resends.size)
            assertFalse(fixture.paths.any { it.contains("two-factor/send-email-login") })
        }
    }

    @Test fun mailboxBackgroundCancelsLateResendButPreservesOriginalOtpChallengeAndCooldown() {
        Fixture().use { fixture ->
            fixture.holdResend = true
            val model = showLogin(fixture)
            compose.onNodeWithTag("bitwarden_two_factor_code").performScrollTo().performTextInput("654321")
            compose.onNodeWithTag("bitwarden_new_device_resend").performScrollTo().performClick()
            assertTrue(fixture.entered.await(10, TimeUnit.SECONDS))
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            fixture.release.countDown()
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            assertTrue(model.loginState.value is BitwardenViewModel.LoginState.TwoFactorRequired)
            assertEquals(NewDeviceOtpResendOutcome.NONE, model.newDeviceOtpResend.value.outcome)
            assertFalse(model.newDeviceOtpResend.value.sending)
            assertTrue(model.newDeviceOtpResend.value.remainingSeconds > 0)
            assertEquals("Backgrounding must clear the unsubmitted OTP", "",
                compose.onNodeWithTag("bitwarden_two_factor_code").performScrollTo()
                    .fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            compose.onNodeWithTag("bitwarden_new_device_resend_status").assertDoesNotExist()
            assertEquals(1, fixture.resends.size)
            assertEquals(1, fixture.forms.size)
        }
    }

    @Test fun explicitCancelDuringResendClosesChallengeAndSuppressesLateStatus() {
        Fixture().use { fixture ->
            fixture.holdResend = true
            val model = showLogin(fixture)
            compose.onNodeWithTag("bitwarden_new_device_resend").performScrollTo().performClick()
            assertTrue(fixture.entered.await(10, TimeUnit.SECONDS))
            compose.onNodeWithTag("bitwarden_two_factor_cancel").assertIsEnabled().performClick()
            fixture.release.countDown()
            compose.waitForIdle()
            assertEquals(BitwardenViewModel.LoginState.Idle, model.loginState.value)
            assertEquals(NewDeviceOtpResendState(), model.newDeviceOtpResend.value)
            compose.onNodeWithTag("bitwarden_two_factor_dialog").assertDoesNotExist()
        }
    }

    @Test fun globalLockClearsChallengeAndLateResendCannotRestoreIt() {
        Fixture().use { fixture ->
            fixture.holdResend = true
            val model = showLogin(fixture)
            compose.onNodeWithTag("bitwarden_new_device_resend").performScrollTo().performClick()
            assertTrue(fixture.entered.await(10, TimeUnit.SECONDS))
            compose.runOnIdle { SessionManager.markLocked() }
            fixture.release.countDown()
            compose.waitUntil(10_000) { model.loginState.value is BitwardenViewModel.LoginState.Idle }
            compose.runOnIdle { SessionManager.markUnlocked() }
            compose.onNodeWithTag("bitwarden_two_factor_dialog").assertDoesNotExist()
            assertEquals(NewDeviceOtpResendState(), model.newDeviceOtpResend.value)
        }
    }

    @Test fun largePortugueseNewDeviceResendAndStatusWrapWithoutTextOverflow() {
        val localized = LocaleHelper.setLocale(context, Language.PORTUGUESE_PORTUGAL)
        show(localized, 1.6f) {
            TwoFactorDialog(availableMethods = listOf(BitwardenAuthService.TWO_FACTOR_EMAIL_NEW_DEVICE),
                selectedMethod = BitwardenAuthService.TWO_FACTOR_EMAIL_NEW_DEVICE, onMethodSelected = {},
                code = "", onCodeChange = {}, statusMessage = null, onSendEmailCode = {}, onConfirm = {}, onDismiss = {},
                newDeviceResendState = NewDeviceOtpResendState(remainingSeconds = 30, outcome = NewDeviceOtpResendOutcome.SENT),
                onResendNewDeviceOtp = {})
        }
        assertEquals(Language.PORTUGUESE_PORTUGAL, LocaleHelper.getCurrentLanguage(compose.activity))
        for (text in listOf(localized.getString(R.string.bitwarden_new_device_resend),
            localized.getString(R.string.bitwarden_new_device_resend_sent))) {
            val node = compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single()
            assertEquals(1.6f, layout.layoutInput.density.fontScale, .001f)
            // Retain the original overflow gate and report visible line extents and all
            // characters so any failure at the actual font scale can be diagnosed.
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val visibleWidth = minOf(layout.size.width.toFloat(), bounds.width)
            val visibleHeight = minOf(layout.size.height.toFloat(), bounds.height)
            val lineWidth = (0 until layout.lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
            val truncated = layout.multiParagraph.didExceedMaxLines ||
                (0 until layout.lineCount).any(layout::isLineEllipsized) ||
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = true) < text.trimEnd().length
            val clipped = lineWidth > visibleWidth + 1f || layout.multiParagraph.height > visibleHeight + 1f
            val details = "text=$text visible=${visibleWidth}x$visibleHeight lineWidth=$lineWidth " +
                "textHeight=${layout.multiParagraph.height} truncated=$truncated clipped=$clipped " +
                "hasVisualOverflow=${layout.hasVisualOverflow}"
            assertFalse(details, layout.hasVisualOverflow)
            assertFalse(details, truncated || clipped)
        }
        screenshotIfRequested("portuguese-large")
    }

    @Test fun ordinaryEmailTwoFactorRetainsItsSeparateSendControl() {
        show {
            TwoFactorDialog(availableMethods = listOf(BitwardenAuthService.TWO_FACTOR_EMAIL),
                selectedMethod = BitwardenAuthService.TWO_FACTOR_EMAIL, onMethodSelected = {}, code = "", onCodeChange = {},
                statusMessage = null, onSendEmailCode = {}, onConfirm = {}, onDismiss = {}, onResendNewDeviceOtp = {})
        }
        compose.onNodeWithTag("bitwarden_two_factor_send_email").assertIsDisplayed()
        compose.onNodeWithTag("bitwarden_new_device_resend").assertDoesNotExist()
    }

    private fun showLogin(fixture: Fixture): BitwardenViewModel {
        SessionManager.markUnlocked()
        val model = BitwardenViewModel(context.applicationContext as android.app.Application)
        models.put("new-device-${UUID.randomUUID()}", model)
        show { BitwardenLoginScreen(model, {}, {}) }
        compose.onNodeWithTag("bitwarden_server_configure").performScrollTo().performClick()
        compose.onNodeWithText(english.getString(R.string.legacy_ui_bitwarden_server_self_hosted)).performScrollTo().performClick()
        compose.onNodeWithTag("bitwarden_server_base").performScrollTo().performTextReplacement(fixture.server.url("/").toString())
        compose.onNodeWithTag("bitwarden_server_apply").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("bitwarden_server_configuration").fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasSetTextAction() and hasText(english.getString(R.string.legacy_ui_email_address)))
            .performScrollTo().performTextInput(fixture.email)
        compose.onNode(hasSetTextAction() and hasText(english.getString(R.string.master_password)))
            .performScrollTo().performTextInput("Synthetic new device password 42!")
        compose.onNodeWithText(english.getString(R.string.legacy_ui_login)).performScrollTo().performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("bitwarden_new_device_resend").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        return model
    }

    private fun show(localized: android.content.Context = english, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val configuration = Configuration(localized.resources.configuration).apply { this.fontScale = fontScale }
        compose.runOnUiThread {
            val resources = compose.activity.resources
            if (originalConfiguration == null) originalConfiguration = Configuration(resources.configuration)
            // Dialog creates a separate window from the host View context. CompositionLocals
            // alone do not change that window's language or resource font configuration.
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MonicaTheme { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun waitForOutcome(model: BitwardenViewModel, expected: NewDeviceOtpResendOutcome) {
        compose.waitUntil(20_000) { model.newDeviceOtpResend.value.outcome == expected }
        compose.waitForIdle()
    }

    private fun screenshotIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("newDeviceOtpScreenshots") != "true") return
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.filesDir, "new-device-otp-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private class Fixture : AutoCloseable {
        val server = MockWebServer()
        val email = "new-device-${UUID.randomUUID()}@example.invalid"
        val paths = CopyOnWriteArrayList<String>()
        val forms = CopyOnWriteArrayList<Map<String, String>>()
        val resends = CopyOnWriteArrayList<RecordedRequest>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var holdResend = false
        @Volatile var resendStatus = 204
        @Volatile var retryAfter: String? = null
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    paths += path
                    return when (path) {
                        "/identity/accounts/prelogin" -> jsonResponse(200, "{\"kdf\":0,\"kdfIterations\":600000}")
                        "/identity/connect/token" -> {
                            forms += request.body.readUtf8().split('&').associate {
                                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                            }
                            jsonResponse(400, "{\"error\":\"invalid_grant\",\"error_description\":\"New device verification required\"}")
                        }
                        "/api/accounts/resend-new-device-otp" -> {
                            resends += request
                            entered.countDown()
                            if (holdResend) release.await(20, TimeUnit.SECONDS)
                            MockResponse().setResponseCode(resendStatus).apply {
                                retryAfter?.let { addHeader("Retry-After", it) }
                                if (resendStatus != 204) setBody("synthetic-sensitive-server-detail")
                            }
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        }
        override fun close() { release.countDown(); server.close() }
        private fun jsonResponse(status: Int, body: String) = MockResponse().setResponseCode(status)
            .setHeader("Content-Type", "application/json").setBody(body)
    }
}
