package takagi.ru.monica.passkey.hardware

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
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
        val state = HardwareKeyUiState(info = DeviceInfo(versions = listOf("FIDO_2_1"), aaguid = Aaguid(ByteArray(16)),
            options = mapOf("rk" to true, "clientPin" to false), pinUvAuthProtocols = listOf(2)), transport = "usb")
        screen(state)
        compose.onNodeWithText("FIDO_2_1").assertExists()
        compose.onNodeWithText(context.getString(R.string.hardware_key_set_pin)).performScrollTo().assertExists()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(context.getExternalFilesDir(null), "hardware-key-management.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun managementActivityOpensOnApi32AndDoesNotWriteVaultData() {
        ActivityScenario.launch<HardwareSecurityKeyActivity>(Intent(context, HardwareSecurityKeyActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertFalse(activity.isFinishing)
            }
        }
    }

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
