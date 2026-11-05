package takagi.ru.monica.bitwarden

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentDraft
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentPreset
import takagi.ru.monica.bitwarden.api.BitwardenHeaderDraft
import takagi.ru.monica.bitwarden.api.BitwardenTlsConfig
import takagi.ru.monica.bitwarden.discovery.BitwardenDnsTxtResolver
import takagi.ru.monica.bitwarden.discovery.BitwardenServerDiscovery
import takagi.ru.monica.bitwarden.discovery.BitwardenTxtLookup
import takagi.ru.monica.bitwarden.ui.BitwardenServerConfigurationDialog
import takagi.ru.monica.data.Language
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.LocaleHelper

/** Production dialog and resolver; only DNS answers are synthetic. No account or database writes. */
@RunWith(AndroidJUnit4::class)
class BitwardenServerConfigurationUiInstrumentedTest {
    // v2 uses the StandardTestDispatcher-backed composition scheduler, including IO completions.
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var visible by mutableStateOf(false)
    private var opening by mutableStateOf(0)
    private var draft by mutableStateOf(defaultDraft())
    private var email by mutableStateOf(EMAIL)
    private var baseLocked by mutableStateOf(false)
    private var discovery by mutableStateOf(BitwardenServerDiscovery(BitwardenDnsTxtResolver { BitwardenTxtLookup.NoRecords }))
    private var localized by mutableStateOf(LocaleHelper.setLocale(context, Language.ENGLISH))
    private var configuration by mutableStateOf(Configuration(localized.resources.configuration))
    private var layoutDirection by mutableStateOf(LayoutDirection.Ltr)
    private var contentInstalled = false
    private var originalActivityConfiguration: Configuration? = null
    private var originallyUnlocked = false
    private val applied = CopyOnWriteArrayList<BitwardenEnvironmentDraft>()
    private val dismissed = AtomicInteger()
    private val controlledResolvers = mutableListOf<ControlledDns>()

    @Before fun captureSession() {
        originallyUnlocked = SessionManager.isUnlocked.value
        onMain { SessionManager.markUnlocked() }
    }

    @After fun disposeOnlyThisFixture() {
        onMain { visible = false }
        controlledResolvers.forEach { it.releaseAll() }
        if (contentInstalled) compose.waitForIdle()
        originalActivityConfiguration?.let { original -> onMain {
            val resources = compose.activity.resources
            @Suppress("DEPRECATION")
            resources.updateConfiguration(original, resources.displayMetrics)
        } }
        onMain {
            if (originallyUnlocked && !SessionManager.isUnlocked.value) SessionManager.markUnlocked()
            if (!originallyUnlocked && SessionManager.isUnlocked.value) SessionManager.markLocked()
        }
    }

    @Test fun editsRealServiceFieldsAndPreservesHeaderAndCertificatePasswordSpaces() {
        open()
        section(R.string.bitwarden_server_endpoints)
        replace("web", "https://web.example.net/WebVault/")
        replace("api", "https://api.example.net/Case/V2/")
        replace("identity", "https://identity.example.net/Auth/")
        replace("notifications", "https://notify.example.net/Notify/")
        done()
        section(R.string.bitwarden_server_headers)
        compose.onNodeWithTag("bitwarden_server_scope_api").performScrollTo()
        compose.onNode(hasText("https://api.example.net/Case/V2/") and
            hasAnyAncestor(hasTestTag("bitwarden_server_scope_api")), useUnmergedTree = true).assertIsDisplayed()
        resourceAction(R.string.bitwarden_server_add_header).performScrollTo().performClick()
        replace("header_0_name", "X-Synthetic-Proxy")
        replace("header_0_value", "  synthetic proxy value  ")
        field("header_0_value").assertIsDisplayed()
        assertPassword(field("header_0_value"))
        assertEquals("  synthetic proxy value  ", editableText("header_0_value"))
        done()
        section(R.string.bitwarden_server_certificates)
        replace("p12_password", "  synthetic certificate password  ")
        assertPassword(field("p12_password"))
        done()
        applyAndWait(1)
        val saved = applied.single()
        assertEquals(BASE, saved.baseUrl)
        assertEquals("https://web.example.net/WebVault/", saved.webUrl)
        assertEquals("https://api.example.net/Case/V2/", saved.apiUrl)
        assertEquals("https://identity.example.net/Auth/", saved.identityUrl)
        assertEquals("https://notify.example.net/Notify/", saved.notificationsUrl)
        assertEquals("X-Synthetic-Proxy", saved.headers.single().name)
        assertEquals("  synthetic proxy value  ", saved.headers.single().value)
        assertEquals("  synthetic certificate password  ", saved.tls.clientCertPassword)
        assertFalse(saved.tls.mtlsEnabled)
        assertEquals(0, dismissed.get())
    }

    @Test fun reservedHeadersControlCharactersAndInvalidServiceUrlCannotApply() {
        open(defaultDraft(headers = listOf(BitwardenHeaderDraft("Authorization", "synthetic"))))
        applyExpectErrors()
        section(R.string.bitwarden_server_headers)
        compose.onNodeWithText(text(R.string.bitwarden_server_error_header_reserved)).performScrollTo().assertIsDisplayed()
        replace("header_0_name", "X-Synthetic-Proxy")
        replace("header_0_value", "synthetic\nsecond line")
        done()
        applyExpectErrors()
        section(R.string.bitwarden_server_headers)
        compose.onNodeWithText(text(R.string.bitwarden_server_error_header_value)).performScrollTo().assertIsDisplayed()
        replace("header_0_value", "synthetic safe value")
        done()
        section(R.string.bitwarden_server_endpoints)
        replace("api", "https://api.example.net/Case/?credential=synthetic")
        done()
        applyExpectErrors()
        section(R.string.bitwarden_server_endpoints)
        compose.onNodeWithText(text(R.string.bitwarden_server_error_url)).performScrollTo().assertIsDisplayed()
        assertTrue(applied.isEmpty())
        field("api").assertTextContains("https://api.example.net/Case/?credential=synthetic")
    }

    @Test fun enabledMtlsRejectsMissingAndUnreadablePrivateKeyWithoutApplying() {
        open()
        section(R.string.bitwarden_server_certificates)
        resourceAction(R.string.legacy_ui_tls_enable_mtls).performScrollTo().assertIsOff().performClick().assertIsOn()
        replace("p12_password", "  ")
        assertEquals("  ", editableText("p12_password"))
        done()
        applyExpectErrors()
        section(R.string.bitwarden_server_certificates)
        compose.onNodeWithText(text(R.string.bitwarden_server_error_p12)).performScrollTo().assertIsDisplayed()
        replace("p12", "not-a-pkcs12-certificate")
        assertPassword(field("p12"))
        done()
        applyExpectErrors()
        section(R.string.bitwarden_server_certificates)
        compose.onNodeWithText(text(R.string.bitwarden_server_error_p12)).performScrollTo().assertIsDisplayed()
        assertTrue(applied.isEmpty())
    }

    @Test fun resetPresetAndBaseChangesRemovePreviousOverridesHeadersAndTls() {
        for (operation in listOf("reset", "preset", "base")) {
            val expectedCount = applied.size + 1
            open(seededDraft())
            when (operation) {
                "reset" -> resourceAction(R.string.bitwarden_server_reset).performScrollTo().performClick()
                "preset" -> resourceAction(R.string.legacy_ui_bitwarden_server_eu).performScrollTo().performClick()
                else -> replace("base", "https://replacement.example.net/NewBase/")
            }
            applyAndWait(expectedCount)
            val saved = applied.last()
            assertCleared(saved)
            when (operation) {
                "reset" -> { assertEquals(BitwardenEnvironmentPreset.CUSTOM, saved.preset); assertEquals(BASE, saved.baseUrl) }
                "preset" -> assertEquals(BitwardenEnvironmentPreset.EU, saved.preset)
                else -> assertEquals("https://replacement.example.net/NewBase/", saved.baseUrl)
            }
        }
    }

    @Test fun discoveryQueriesOnlyDomainAndRequiresExplicitCrossDomainSelection() {
        val names = CopyOnWriteArrayList<String>()
        val resolver = BitwardenDnsTxtResolver { name ->
            names += name
            if (name.startsWith("_bitwarden.")) BitwardenTxtLookup.Records(listOf(CANDIDATE, "http://unsafe.example.net/"))
            else BitwardenTxtLookup.NoRecords
        }
        open(seededDraft(), resolver = resolver)
        assertTrue("Opening the editor must not query DNS", names.isEmpty())
        startDiscovery()
        waitText(CANDIDATE)
        assertEquals(setOf("_bitwarden.example.com", "_vaultwarden.example.com"), names.toSet())
        assertEquals(2, names.size)
        assertTrue(names.none { EMAIL in it || "synthetic-login" in it })
        compose.onNodeWithTag(USE).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("http://unsafe.example.net/").assertDoesNotExist()
        assertTrue(applied.isEmpty())
        candidateAction().performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.bitwarden_server_review_cross)).performScrollTo().assertIsDisplayed()
        assertTrue("Selection alone cannot apply the configuration", applied.isEmpty())
        compose.onNodeWithTag(USE).performScrollTo().assertIsEnabled().performClick()
        waitTag(BASE_TAG)
        field("base").assertTextContains(CANDIDATE)
        applyAndWait(1)
        assertEquals(CANDIDATE, applied.single().baseUrl)
        assertCleared(applied.single())
    }

    @Test fun existingAccountCannotRetargetBaseOrDiscoverButCanEditServiceOverride() {
        val names = CopyOnWriteArrayList<String>()
        open(defaultDraft(headers = listOf(BitwardenHeaderDraft("X-Synthetic-Proxy", "  retained  "))),
            locked = true, resolver = BitwardenDnsTxtResolver { names += it; BitwardenTxtLookup.NoRecords })
        field("base").performScrollTo().assertIsNotEnabled().assertTextContains(BASE)
        resourceAction(R.string.legacy_ui_bitwarden_server_us).performScrollTo().assertIsNotEnabled()
        resourceAction(R.string.legacy_ui_bitwarden_server_eu).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(FIND).performScrollTo().assertIsNotEnabled()
        section(R.string.bitwarden_server_endpoints)
        replace("api", "https://api.example.net/Explicit/")
        done()
        applyAndWait(1)
        assertEquals(BASE, applied.single().baseUrl)
        assertEquals(BitwardenEnvironmentPreset.CUSTOM, applied.single().preset)
        assertEquals("https://api.example.net/Explicit/", applied.single().apiUrl)
        assertEquals("  retained  ", applied.single().headers.single().value)
        assertTrue(names.isEmpty())
    }

    @Test fun cancelledAndEditedSearchesRejectNonCooperativeLateDnsResults() {
        val resolver = controlledDns()
        open(resolver = resolver)
        startDiscovery()
        waitPending(resolver, 2)
        resourceAction(R.string.cancel).performScrollTo().performClick()
        waitText(text(R.string.bitwarden_server_lookup_cancelled))
        assertTrue(resolver.pending.take(2).all { it.job.isCancelled })
        resolver.releaseAll()
        settle()
        compose.onNodeWithText(CANDIDATE).assertDoesNotExist()
        compose.onNodeWithTag(USE).assertDoesNotExist()
        assertTrue(applied.isEmpty())

        resourceAction(R.string.bitwarden_server_find).performScrollTo().performClick()
        waitPending(resolver, 4)
        back()
        replace("base", "https://edited.example.net/Manual/")
        assertTrue(resolver.pending.drop(2).all { it.job.isCancelled })
        resolver.releaseAll()
        settle()
        field("base").assertTextContains("https://edited.example.net/Manual/")
        compose.onNodeWithText(CANDIDATE).assertDoesNotExist()
        applyAndWait(1)
        assertEquals("https://edited.example.net/Manual/", applied.single().baseUrl)
    }

    @Test fun dismissAndLockDiscardButBackgroundOnlyCancelsLateDiscovery() {
        for (operation in listOf("dismiss", "lock", "background")) {
            val resolver = controlledDns()
            val originalDismissed = dismissed.get()
            open(seededDraft(), resolver = resolver)
            startDiscovery()
            waitPending(resolver, 2)
            when (operation) {
                "dismiss" -> { back(); back() }
                "lock" -> onMain { SessionManager.markLocked() }
                else -> {
                    compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                }
            }
            if (operation == "background") {
                waitTag(DIALOG)
                assertEquals(originalDismissed, dismissed.get())
            } else {
                waitAbsent(DIALOG)
                assertEquals(originalDismissed + 1, dismissed.get())
            }
            assertTrue(resolver.pending.all { it.job.isCancelled })
            resolver.releaseAll()
            settle()
            compose.onNodeWithText(CANDIDATE).assertDoesNotExist()
            assertTrue(applied.isEmpty())
            if (operation == "background") {
                back()
                field("base").performScrollTo().assertTextContains(BASE)
                section(R.string.bitwarden_server_headers)
                assertEquals("X-Old-Proxy", editableText("header_0_name"))
                assertEquals(" old synthetic secret ", editableText("header_0_value"))
                done()
                back()
                waitAbsent(DIALOG)
            } else compose.onNodeWithTag(DIALOG).assertDoesNotExist()
        }
    }

    @Test fun capturedCandidateActionCannotRetargetAfterEmailOrBaseLockChanges() {
        for (change in listOf("email", "baseLock")) {
            open(resolver = BitwardenDnsTxtResolver { BitwardenTxtLookup.Records(listOf(CANDIDATE)) })
            startDiscovery()
            waitText(CANDIDATE)
            candidateAction().performScrollTo().performClick()
            val captured = requireNotNull(compose.onNodeWithTag(USE).performScrollTo()
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            onMain { if (change == "email") email = "changed@example.org" else baseLocked = true }
            settle()
            onMain { captured() }
            settle()
            assertTrue(applied.isEmpty())
            back()
            field("base").performScrollTo().assertTextContains(BASE)
            back()
            waitAbsent(DIALOG)
        }
    }

    @Test fun largeTextKeepsRealDialogControlsReadableInEnglishChinesePortugueseAndRtl() {
        // Monica has no Arabic/Hebrew translation. RTL is an English layout-direction test.
        val appearances = listOf(
            Triple(Language.ENGLISH, false, "english"),
            Triple(Language.CHINESE, false, "chinese"),
            Triple(Language.PORTUGUESE_BRAZIL, false, "portuguese-br"),
            Triple(Language.ENGLISH, true, "english-rtl"),
        )
        val requested = InstrumentationRegistry.getArguments().getString("serverConfigurationVisualLocale")
        require(requested == null || appearances.any { it.third == requested }) { "Unknown synthetic visual locale" }
        for ((language, rtl, name) in appearances.filter { requested == null || it.third == requested }) {
            open(defaultDraft(headers = listOf(BitwardenHeaderDraft("X-Synthetic-Proxy", "synthetic screenshot value"))),
                language = language, fontScale = 1.5f, rtl = rtl)
            screenshotIfRequested("$name-overview")
            assertTitleAndActionFit(R.string.bitwarden_server_title, R.string.bitwarden_server_apply)
            for (sectionId in listOf(R.string.bitwarden_server_endpoints, R.string.bitwarden_server_headers,
                R.string.bitwarden_server_certificates)) {
                val rowText = compose.onNode(hasText(text(sectionId)) and !hasClickAction(), useUnmergedTree = true)
                rowText.performScrollTo()
                assertTextFits(rowText, 1.5f)
                section(sectionId)
                if (sectionId == R.string.bitwarden_server_headers) screenshotIfRequested("$name-headers")
                assertTitleAndActionFit(sectionId, R.string.bitwarden_server_done)
                when (sectionId) {
                    R.string.bitwarden_server_endpoints -> field("notifications").performScrollTo().assertIsDisplayed()
                    R.string.bitwarden_server_headers -> field("header_0_value").performScrollTo().assertIsDisplayed()
                    else -> field("p12_password").performScrollTo().assertIsDisplayed()
                }
                compose.onNodeWithTag(APPLY).assertIsDisplayed().assertIsEnabled()
                done()
            }
            back()
            waitAbsent(DIALOG)
        }
        assertTrue(applied.isEmpty())
    }

    private fun open(
        initial: BitwardenEnvironmentDraft = defaultDraft(),
        locked: Boolean = false,
        resolver: BitwardenDnsTxtResolver = BitwardenDnsTxtResolver { BitwardenTxtLookup.NoRecords },
        language: Language = Language.ENGLISH,
        fontScale: Float = 1f,
        rtl: Boolean = false,
    ) {
        check(!visible) { "Close the previous production dialog before opening another" }
        val localeContext = LocaleHelper.setLocale(context, language)
        val nextConfiguration = Configuration(localeContext.resources.configuration).apply {
            this.fontScale = fontScale
            if (rtl) setLayoutDirection(Locale("ar"))
        }
        onMain {
            SessionManager.markUnlocked()
            val resources = compose.activity.resources
            if (originalActivityConfiguration == null) originalActivityConfiguration = Configuration(resources.configuration)
            // DialogWrapper uses the actual Activity context, not only the composition locals.
            @Suppress("DEPRECATION")
            resources.updateConfiguration(nextConfiguration, resources.displayMetrics)
            localized = localeContext.createConfigurationContext(nextConfiguration)
            configuration = nextConfiguration
            layoutDirection = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            draft = initial
            email = EMAIL
            baseLocked = locked
            discovery = BitwardenServerDiscovery(resolver)
            opening++
        }
        if (!contentInstalled) {
            contentInstalled = true
            compose.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                    LocalDensity provides Density(density, configuration.fontScale), LocalLayoutDirection provides layoutDirection) {
                    MonicaTheme { Surface(Modifier.fillMaxSize()) {
                        if (visible) key(opening) {
                            BitwardenServerConfigurationDialog(draft, email, baseLocked,
                                onApply = { applied += it; visible = false },
                                onDismiss = { dismissed.incrementAndGet(); visible = false }, discovery = discovery)
                        }
                    } }
                }
            }
        }
        onMain { visible = true }
        waitTag(DIALOG)
    }

    private fun defaultDraft(headers: List<BitwardenHeaderDraft> = emptyList()) =
        BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM, BASE, headers = headers)

    private fun seededDraft() = BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM, BASE,
        "https://old.example.com/Web/", "https://old.example.com/Api/", "https://old.example.com/Identity/",
        "https://old.example.com/Notifications/", listOf(BitwardenHeaderDraft("X-Old-Proxy", " old synthetic secret ")),
        BitwardenTlsConfig(certificateAlias = "synthetic-old-alias", caCertificatePem = "synthetic-old-ca",
            mtlsEnabled = true, clientCertPkcs12Base64 = "synthetic-old-p12", clientCertPassword = " old synthetic password "))

    private fun assertCleared(saved: BitwardenEnvironmentDraft) {
        assertTrue(saved.webUrl.isNullOrEmpty())
        assertTrue(saved.apiUrl.isNullOrEmpty())
        assertTrue(saved.identityUrl.isNullOrEmpty())
        assertTrue(saved.notificationsUrl.isNullOrEmpty())
        assertTrue(saved.headers.isEmpty())
        assertEquals(BitwardenTlsConfig(), saved.tls)
    }

    private fun text(id: Int) = localized.getString(id)
    private fun resourceAction(id: Int) = compose.onNode(hasText(text(id)) and hasClickAction())
    private fun field(tag: String) = compose.onNodeWithTag("bitwarden_server_$tag")
    private fun section(id: Int) { resourceAction(id).performScrollTo().assertIsEnabled().performClick(); settle() }
    private fun replace(tag: String, value: String) { field(tag).performScrollTo().performTextReplacement(value); settle() }
    // EditableText contains the visual transformation (password dots); InputText is the entered value.
    private fun editableText(tag: String) = field(tag).fetchSemanticsNode().config[SemanticsProperties.InputText].text
    private fun assertPassword(node: SemanticsNodeInteraction) {
        assertNotNull("Production secret field must use password semantics", node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Password))
    }
    private fun done() { compose.onNodeWithTag(APPLY).assertIsDisplayed().performClick(); waitTag(BASE_TAG) }
    private fun back() { compose.onNodeWithContentDescription(text(R.string.back)).performClick(); settle() }
    private fun startDiscovery() { compose.onNodeWithTag(FIND).performScrollTo().assertIsEnabled().performClick(); settle() }
    private fun candidateAction() = compose.onNode(hasText(CANDIDATE) and hasClickAction())
    private fun applyAndWait(count: Int) {
        compose.onNodeWithTag(APPLY).assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(20_000) { applied.size == count }
        waitAbsent(DIALOG)
    }
    private fun applyExpectErrors() {
        compose.onNodeWithTag(APPLY).assertIsDisplayed().assertIsEnabled().performClick()
        waitText(text(R.string.bitwarden_server_error_summary))
        compose.onNodeWithTag(APPLY).assertIsEnabled()
        assertTrue(applied.isEmpty())
    }
    private fun waitTag(tag: String) { compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }; settle() }
    private fun waitAbsent(tag: String) { compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }; settle() }
    private fun waitText(value: String) { compose.waitUntil(20_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }; settle() }
    private fun settle() { compose.mainClock.advanceTimeBy(64); compose.waitForIdle() }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync {
        check(Looper.myLooper() == Looper.getMainLooper()); action()
    }
    private fun <T> onMainValue(action: () -> T): T = AtomicReference<T>().also { result -> onMain { result.set(action()) } }.get()

    private fun controlledDns() = ControlledDns().also { controlledResolvers += it }
    private fun waitPending(resolver: ControlledDns, count: Int) {
        compose.waitUntil(10_000) { resolver.pending.size == count }
        assertEquals(count, resolver.pending.size)
    }

    /** Deliberately ignores cancellation until released, like a late platform callback. */
    private class ControlledDns : BitwardenDnsTxtResolver {
        val pending = CopyOnWriteArrayList<Pending>()
        override suspend fun queryTxt(name: String): BitwardenTxtLookup {
            val job = requireNotNull(currentCoroutineContext()[Job])
            return suspendCoroutine { continuation -> pending += Pending(name, job, continuation) }
        }
        fun releaseAll() = pending.forEach { request ->
            if (request.released.compareAndSet(false, true)) request.continuation.resume(BitwardenTxtLookup.Records(listOf(CANDIDATE)))
        }
        class Pending(val name: String, val job: Job, val continuation: Continuation<BitwardenTxtLookup>) {
            val released = AtomicBoolean(false)
        }
    }

    private fun assertTitleAndActionFit(title: Int, action: Int) {
        assertTextFits(compose.onNode(hasText(text(title)) and !hasClickAction(), useUnmergedTree = true), 1.5f)
        compose.onNodeWithTag(APPLY).assertIsDisplayed().assertIsEnabled()
        assertTextFits(compose.onNode(hasText(text(action)) and hasAnyAncestor(hasTestTag(APPLY)), useUnmergedTree = true), 1.5f)
    }

    private fun assertTextFits(target: SemanticsNodeInteraction, scale: Float) {
        val results = mutableListOf<TextLayoutResult>()
        target.assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("Expected an actual production text layout", results.isNotEmpty())
        val node = target.fetchSemanticsNode()
        val origin = node.positionInRoot
        val visibleBounds = node.boundsInRoot
        for (result in results) {
            val input = result.layoutInput
            val diagnostic = "text=${input.text}; layout=${result.size}; node=${node.size}; visible=$visibleBounds; " +
                "origin=$origin; fontScale=${input.density.fontScale}; constraints=${input.constraints}; " +
                "visualOverflow=${result.hasVisualOverflow}; paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}"
            assertEquals(diagnostic, scale, input.density.fontScale, .001f)
            assertFalse(diagnostic, result.didOverflowHeight)
            assertFalse(diagnostic, result.multiParagraph.didExceedMaxLines)
            assertTrue(diagnostic, result.lineCount > 0)
            assertEquals(diagnostic, input.text.length, result.getLineEnd(result.lineCount - 1, visibleEnd = false))
            assertEquals(diagnostic, node.size.width.toFloat(), visibleBounds.width, 1f)
            assertEquals(diagnostic, node.size.height.toFloat(), visibleBounds.height, 1f)
            for (line in 0 until result.lineCount) {
                assertFalse(diagnostic, result.isLineEllipsized(line))
                assertTrue(diagnostic, result.getLineLeft(line) >= -1f && result.getLineRight(line) <= result.size.width + 1f)
                assertTrue(diagnostic, result.getLineTop(line) >= -1f && result.getLineBottom(line) <= result.size.height + 1f)
                assertTrue(diagnostic, origin.x + result.getLineLeft(line) >= visibleBounds.left - 1f &&
                    origin.x + result.getLineRight(line) <= visibleBounds.right + 1f)
                assertTrue(diagnostic, origin.y + result.getLineTop(line) >= visibleBounds.top - 1f &&
                    origin.y + result.getLineBottom(line) <= visibleBounds.bottom + 1f)
            }
        }
    }

    private fun screenshotIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("serverConfigurationScreenshots") != "true") return
        check(Build.VERSION.SDK_INT >= 29) { "Window frame diagnostics require API29+, including the shared API32 AVD" }
        settle()
        val diagnostic = File(context.filesDir, "bitwarden-server-$name-window.txt")
        diagnostic.writeText(windowDiagnostic("before-frame"))
        try {
            val host = compose.activity
            var focusedDialog: View? = null
            compose.waitUntil(5_000) {
                focusedDialog = onMainValue { WindowInspector.getGlobalWindowViews().singleOrNull { root ->
                    root !== host.window.decorView && root.isAttachedToWindow && root.isShown &&
                        root.windowVisibility == View.VISIBLE && root.hasWindowFocus() && root.width > 0 && root.height > 0
                } }
                focusedDialog != null
            }
            val root = requireNotNull(focusedDialog)
            onMain { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0) }
            val committed = CountDownLatch(1)
            val callback = Runnable { committed.countDown() }
            val observer = onMainValue {
                assertEquals(Lifecycle.State.RESUMED, host.lifecycle.currentState)
                assertTrue("A real hardware-rendered dialog is required", root.isHardwareAccelerated)
                root.viewTreeObserver.also { it.registerFrameCommitCallback(callback); root.postInvalidateOnAnimation() }
            }
            val frameCommitted = try { committed.await(5, TimeUnit.SECONDS) } finally {
                onMain { if (observer.isAlive) observer.unregisterFrameCommitCallback(callback) }
            }
            diagnostic.appendText("frameCommitted=$frameCommitted\n")
            assertTrue("The focused dialog must submit a real frame", frameCommitted)
            instrumentation.waitForIdleSync()
            onMain {
                assertEquals(Lifecycle.State.RESUMED, host.lifecycle.currentState)
                assertTrue(root.isAttachedToWindow && root.isShown && root.hasWindowFocus())
            }
            diagnostic.appendText(windowDiagnostic("after-frame"))
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(context.filesDir, "bitwarden-server-$name.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                val colors = mutableSetOf<Int>()
                for (y in bitmap.height / 10 until bitmap.height * 9 / 10 step 24) {
                    for (x in bitmap.width / 10 until bitmap.width * 9 / 10 step 24) colors += bitmap.getPixel(x, y) and 0x00FFFFFF
                }
                diagnostic.appendText("capture=${bitmap.width}x${bitmap.height}; sampledColors=${colors.size}\n")
                assertTrue("Blank captures are failures; keep PNG and window diagnostics", colors.size > 1)
            } finally { bitmap.recycle() }
        } finally { diagnostic.appendText(windowDiagnostic("capture-finished")) }
    }

    private fun windowDiagnostic(stage: String): String = onMainValue {
        buildString {
            appendLine("stage=$stage; uptime=${SystemClock.uptimeMillis()}; lifecycle=${compose.activity.lifecycle.currentState}")
            WindowInspector.getGlobalWindowViews().forEachIndexed { index, root ->
                val parameters = root.layoutParams as? WindowManager.LayoutParams
                appendLine("root=$index; class=${root.javaClass.name}; type=${parameters?.type}; attached=${root.isAttachedToWindow}; " +
                    "shown=${root.isShown}; visibility=${root.windowVisibility}; focus=${root.hasWindowFocus()}; hardware=${root.isHardwareAccelerated}; " +
                    "size=${root.width}x${root.height}; alpha=${root.alpha}; flags=${parameters?.flags}")
            }
        }
    }

    private companion object {
        const val BASE = "https://vault.example.com/Original/"
        const val EMAIL = "synthetic-login@example.com"
        const val CANDIDATE = "https://vault.example.net/Discovered/"
        const val DIALOG = "bitwarden_server_configuration"
        const val BASE_TAG = "bitwarden_server_base"
        const val APPLY = "bitwarden_server_apply"
        const val FIND = "bitwarden_server_find"
        const val USE = "bitwarden_server_use_candidate"
    }
}
