package takagi.ru.monica.passkey.hardware

import android.content.Intent
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.provider.PendingIntentHandler
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import takagi.ru.monica.R
import takagi.ru.monica.ui.theme.MonicaTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HardwareKeyUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var preference: HardwarePasskeyPreferenceFixture
    @Before fun optIntoHardwareForExistingHardwareTests() {
        preference = HardwarePasskeyPreferenceFixture(context)
        HardwarePasskeyPreferences.setEnabled(context, true)
        // MuMu's launcher/EmptyActivity can rotate to landscape between scenarios. Install
        // test content only after the replacement portrait host owns an attached, focused window.
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(5_000) {
            var ready = false
            compose.runOnUiThread {
                val host = compose.activity
                ready = host.lifecycle.currentState == Lifecycle.State.RESUMED && !host.isChangingConfigurations &&
                    !host.isDestroyed && !host.isFinishing && host.window.decorView.isAttachedToWindow &&
                    host.hasWindowFocus() && host.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            }
            ready
        }
    }
    @After fun restoreHardwarePreference() = preference.restore()
    private fun screen(state: HardwareKeyUiState, onConnect: () -> Unit = {}, onPerform: () -> Unit = {}) {
        compose.setContent { MonicaTheme { HardwareKeyScreen(state, {}, onConnect, {}, {}, {}, { _,_,_ -> }, {}, {}, onPerform, {}) } }
    }
    @Test fun noDeviceStateExplainsWebsiteRegistrationAndOffersUsbWithoutOfflineWriteButton() {
        var connect = 0
        screen(HardwareKeyUiState(), onConnect = { connect++ })
        compose.onNodeWithText(context.getString(R.string.hardware_key_find_usb)).performClick()
        compose.runOnIdle { assertEquals(1, connect) }
        compose.onNodeWithTag("hardware-perform").assertDoesNotExist()
        compose.onNodeWithTag("hardware-pin").assertDoesNotExist()
    }
    @Test fun providerSubmitDisabledWithoutDeviceAndAfterMissingPin() {
        screen(HardwareKeyUiState(providerMode = true, info = DeviceInfo(options = mapOf("clientPin" to true)), transport = "usb"))
        compose.onNodeWithTag("hardware-perform").assertIsNotEnabled()
        compose.onNodeWithTag("hardware-pin").assertExists()
    }
    @Test fun connectedManagementRendersActualCapabilitiesWithoutAddingNavigationDock() {
        val state = HardwareKeyUiState(info = DeviceInfo(versions = listOf("U2F_V2", "FIDO_2_0", "FIDO_2_1_PRE"), aaguid = Aaguid(ByteArray(16) { it.toByte() }),
            options = mapOf("rk" to true, "clientPin" to false), pinUvAuthProtocols = listOf(2)), transport = "usb")
        screen(state)
        compose.onNodeWithTag("hardware-info-card").assertExists()
        compose.onNodeWithText("U2F_V2, FIDO_2_0, FIDO_2_1_PRE").assertExists()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(context.getExternalFilesDir(null), "hardware-key-management.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText(context.getString(R.string.hardware_key_set_pin)).performScrollTo().assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.hardware_key_change_pin)).assertIsNotEnabled()
    }
    @Test fun managementActivityOpensOnApi32AndDoesNotWriteVaultData() {
        ActivityScenario.launch<HardwareSecurityKeyActivity>(Intent(context, HardwareSecurityKeyActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test fun managementActivityDrawsToWindowTopWithSafeHeaderAndClose() {
        ActivityScenario.launchActivityForResult<HardwareSecurityKeyActivity>(
            Intent(context, HardwareSecurityKeyActivity::class.java)
        ).use { scenario ->
            assertHardwareWindowGeometry(scenario)
            scenario.recreate()
            val labels = assertHardwareWindowGeometry(scenario)
            compose.onNodeWithContentDescription(labels.back).assertHasClickAction().performClick()
            compose.waitUntil(5_000) { scenario.state == Lifecycle.State.DESTROYED }
            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        }
    }

    @Test fun bothProviderWindowsKeepSafeHeaderAndExplicitCancellation() {
        // A missing synthetic provider request cannot open a hardware credential operation.
        // This checks the real provider window and cancellation, not successful USB/NFC auth.
        for (action in listOf(HardwareSecurityKeyActivity.ACTION_CREATE, HardwareSecurityKeyActivity.ACTION_GET)) {
            ActivityScenario.launchActivityForResult<HardwareSecurityKeyActivity>(
                Intent(context, HardwareSecurityKeyActivity::class.java).setAction(action)
            ).use { scenario ->
                val labels = assertHardwareWindowGeometry(scenario)
                compose.onNodeWithTag("hardware-perform").assertIsDisplayed().assertIsNotEnabled()
                compose.onNodeWithText(labels.cancel).assertIsDisplayed().performClick()
                compose.waitUntil(5_000) { scenario.state == Lifecycle.State.DESTROYED }
                val result = scenario.result
                assertEquals(Activity.RESULT_CANCELED, result.resultCode)
                if (Build.VERSION.SDK_INT >= 34) {
                    if (action == HardwareSecurityKeyActivity.ACTION_CREATE) {
                        assertTrue(PendingIntentHandler.retrieveCreateCredentialException(result.resultData)
                            is CreateCredentialCancellationException)
                    } else {
                        assertTrue(PendingIntentHandler.retrieveGetCredentialException(result.resultData)
                            is GetCredentialCancellationException)
                    }
                }
            }
        }
    }

    private data class HardwareWindowLabels(val title: String, val back: String, val cancel: String)

    private data class HardwareWindowGeometry(
        val contentTop: Int,
        val contentLeft: Int,
        val contentRight: Int,
        val contentBottom: Int,
        val windowHeight: Int,
        val statusTop: Int,
        val navigationBottom: Int,
        val density: Float,
    )

    private fun assertHardwareWindowGeometry(
        scenario: ActivityScenario<HardwareSecurityKeyActivity>,
    ): HardwareWindowLabels {
        lateinit var labels: HardwareWindowLabels
        scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            labels = HardwareWindowLabels(activity.getString(R.string.hardware_key_title),
                activity.getString(R.string.hardware_key_back), activity.getString(R.string.hardware_key_cancel))
        }
        compose.waitUntil(5_000) {
            var ready = false
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val content = activity.findViewById<View>(android.R.id.content)
                val insets = ViewCompat.getRootWindowInsets(decor)
                ready = activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                    !activity.isChangingConfigurations && !activity.isFinishing && !activity.isDestroyed &&
                    decor.isAttachedToWindow && activity.hasWindowFocus() && content.width > 0 &&
                    decor.height > decor.width &&
                    activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                    insets != null && insets.isVisible(WindowInsetsCompat.Type.statusBars()) &&
                    insets.getInsets(WindowInsetsCompat.Type.statusBars()).top > 0
            }
            ready
        }
        compose.onNodeWithText(labels.title).assertIsDisplayed()
        compose.onNodeWithContentDescription(labels.back).assertIsDisplayed().assertHasClickAction()
        compose.waitForIdle()
        lateinit var geometry: HardwareWindowGeometry
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val content = activity.findViewById<View>(android.R.id.content)
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor))
            val position = IntArray(2).also(content::getLocationInWindow)
            geometry = HardwareWindowGeometry(position[1], position[0], position[0] + content.width,
                position[1] + content.height, decor.height,
                insets.getInsets(WindowInsetsCompat.Type.statusBars()).top,
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                activity.resources.displayMetrics.density)
            assertTrue("Actual hardware window must retain FLAG_SECURE",
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertTrue("Status bar must really be present for this regression", geometry.statusTop > 0)
        }
        val title = compose.onNodeWithText(labels.title).fetchSemanticsNode().boundsInWindow
        val back = compose.onNodeWithContentDescription(labels.back).fetchSemanticsNode().boundsInWindow
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stream", "HARDWARE_INSETS_328 contentTop=${geometry.contentTop} " +
                "statusTop=${geometry.statusTop} titleTop=${title.top} backTop=${back.top} " +
                "backBottom=${back.bottom} windowHeight=${geometry.windowHeight}\n")
        })
        assertEquals("Real Activity content must extend behind the status bar to window top", 0, geometry.contentTop)
        assertTrue("Title must avoid the actual status-bar region", title.top >= geometry.statusTop - 1f)
        assertTrue("Header must not reserve a second status-bar-sized blank band",
            title.top - geometry.statusTop <= 24f * geometry.density + 1f)
        assertTrue("Close hit area must avoid status-bar icons", back.top >= geometry.statusTop - 1f)
        assertTrue("Close hit area must remain inside the visible content width",
            back.left >= geometry.contentLeft - 1f && back.right <= geometry.contentRight + 1f)
        assertTrue("Close hit area must remain above the navigation bar",
            back.bottom <= geometry.windowHeight - geometry.navigationBottom + 1f)
        assertTrue("Title must be fully inside the current Activity content",
            title.height > 0f && title.bottom <= geometry.contentBottom + 1f)
        return labels
    }


    private fun renderReference(locale: String, fontScale: Float, filename: String) {
        val config = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(locale))
            this.fontScale = fontScale
        }
        val localized = context.createConfigurationContext(config)
        val state = HardwareKeyUiState(info = DeviceInfo(versions = listOf("U2F_V2", "FIDO_2_0", "FIDO_2_1_PRE"),
            aaguid = Aaguid(ByteArray(16) { it.toByte() }), firmwareVersion = 328707,
            options = mapOf("rk" to true, "clientPin" to false), pinUvAuthProtocols = listOf(2)), transport = "usb")
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MonicaTheme(darkTheme = true) { HardwareKeyScreen(state, {}, {}, {}, {}, {}, { _,_,_ -> }, {}, {}, {}, {}) }
            }
        }
        compose.onNodeWithTag("hardware-info-card").assertIsDisplayed()
        compose.onNodeWithText(state.info!!.aaguid.toString()).assertExists()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(context.getExternalFilesDir(null), filename).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText(localized.getString(R.string.hardware_key_set_pin)).performScrollTo().assertIsEnabled()
        compose.onNodeWithText(localized.getString(R.string.hardware_key_change_pin)).assertIsNotEnabled()
        compose.onNodeWithText(localized.getString(R.string.hardware_key_registration_help)).performScrollTo().assertIsDisplayed().performClick()
        // Dialog owns an Android window: local page overrides do not change its host activity locale.
        val dialogContext = compose.activity
        compose.waitUntil(5000) { compose.onAllNodesWithText(dialogContext.getString(R.string.hardware_key_instructions)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(dialogContext.getString(R.string.hardware_key_instructions)).assertExists()
        compose.onNodeWithText(dialogContext.getString(R.string.hardware_key_no_import)).performScrollTo()
        compose.waitUntil(5000) { compose.onNodeWithText(dialogContext.getString(R.string.hardware_key_no_import)).isDisplayed() }
        val dialog = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("No dialog screenshot")
        java.io.File(context.getExternalFilesDir(null), "dialog-$filename").outputStream().use {
            dialog.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        dialog.recycle()
        compose.onNodeWithText(dialogContext.getString(R.string.hardware_key_back)).performClick()
        compose.onNodeWithText(dialogContext.getString(R.string.hardware_key_instructions)).assertDoesNotExist()
    }

    @Test fun chineseConnectedPageKeepsReferenceGroupsAndReadableActions() = renderReference("zh-CN", 1f, "hardware-key-redesign-zh.png")
    @Test fun largeEnglishTextKeepsFullIdentifiersAndPinActionsReachable() = renderReference("en", 1.6f, "hardware-key-redesign-large-en.png")

    @Test fun cancelledNativeOpenCannotCloseOrReplaceTheNextConnection() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val oldClosed = CountDownLatch(1)
        class Key(private val closedSignal: CountDownLatch? = null) : FidoTransport {
            @Volatile var closed = false
            override val transportType = TransportType.USB
            override val isConnected get() = !closed
            override fun reclaimConnection() = check(!closed)
            override suspend fun sendCtapCommand(command: ByteArray) = byteArrayOf(0) + cbor {
                map { 1 to listOf("FIDO_2_1"); 3 to bytes(ByteArray(16)); 4 to map { "rk" to true } }
            }
            override fun close() { closed = true; closedSignal?.countDown() }
        }
        val oldKey = Key(oldClosed)
        val nextKey = Key()
        val connect = HardwareSecurityKeyActivity::class.java.getDeclaredMethod("connect", kotlin.jvm.functions.Function1::class.java).apply { isAccessible = true }
        val disconnect = HardwareSecurityKeyActivity::class.java.getDeclaredMethod("disconnect").apply { isAccessible = true }
        val active = HardwareSecurityKeyActivity::class.java.getDeclaredField("transport").apply { isAccessible = true }
        ActivityScenario.launch<HardwareSecurityKeyActivity>(Intent(context, HardwareSecurityKeyActivity::class.java)).use { scenario ->
            try {
                val oldOpen: suspend () -> FidoTransport = {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    oldKey
                }
                scenario.onActivity { connect.invoke(it, oldOpen) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val nextOpen: suspend () -> FidoTransport = { nextKey }
                scenario.onActivity { disconnect.invoke(it); connect.invoke(it, nextOpen) }
                val deadline = System.nanoTime() + 5_000_000_000L
                var connected = false
                do {
                    scenario.onActivity { connected = active.get(it) === nextKey }
                    if (!connected) Thread.sleep(25)
                } while (!connected && System.nanoTime() < deadline)
                assertTrue(connected)
                release.countDown()
                assertTrue(oldClosed.await(5, TimeUnit.SECONDS))
                scenario.onActivity { assertSame(nextKey, active.get(it)); assertFalse(nextKey.closed) }
            } finally { release.countDown() }
        }
        assertTrue(nextKey.closed)
    }
}
