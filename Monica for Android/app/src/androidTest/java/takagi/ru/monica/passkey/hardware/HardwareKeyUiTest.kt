package takagi.ru.monica.passkey.hardware

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import takagi.ru.monica.R
import takagi.ru.monica.ui.theme.MonicaTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HardwareKeyUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
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
        val dialogContext = (compose as AndroidComposeTestRule<*, *>).activity
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
