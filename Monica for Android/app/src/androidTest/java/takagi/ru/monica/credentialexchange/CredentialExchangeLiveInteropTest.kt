package takagi.ru.monica.credentialexchange

import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.ui.screens.ImportDataScreen
import takagi.ru.monica.ui.theme.MonicaTheme

/** Requires the separate synthetic peer and functioning Google Play services; never a CI mock. */
@RunWith(AndroidJUnit4::class)
class CredentialExchangeLiveInteropTest {
    @get:Rule val compose = createComposeRule()

    @Test fun receivesPasswordAndPasskeyThroughTheRealSystemPicker() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveExchangePeer") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val serviceInfo = automation.serviceInfo
        val originalFlags = serviceInfo.flags
        serviceInfo.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo = serviceInfo
        val visible = mutableStateOf(true)
        var writes = 0
        try {
            compose.setContent {
                if (visible.value) MonicaTheme {
                    ImportDataScreen(onNavigateBack = {}, onImport = { Result.success(0) },
                        onImportExchange = { writes++; Result.success(2) },
                        onImportAegis = { Result.success(0) }, onImportEncryptedAegis = { _, _ -> Result.success(0) },
                        onImportSteamMaFile = { Result.success(0) }, onImportZip = { _, _, _ -> Result.success(0) })
                }
            }
            compose.onNodeWithText(context.getString(R.string.exchange_choose_app)).performClick()
            fun find(text: String): AccessibilityNodeInfo? {
                fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (node == null) return null
                    if (node.text?.toString()?.contains(text, ignoreCase = true) == true ||
                        node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) return node
                    for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
                    return null
                }
                for (window in automation.windows) {
                    val root = window.root ?: continue
                    root.refresh()
                    visit(root)?.let { return it }
                }
                return visit(automation.rootInActiveWindow)
            }
            fun click(text: String) {
                var node = find(text)
                while (node != null && !node.isClickable) node = node.parent
                check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot select $text" }
            }
            fun awaitExternal(timeoutMillis: Long, condition: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + timeoutMillis
                while (!condition()) {
                    check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for the external exchange UI" }
                    SystemClock.sleep(100)
                }
            }
            awaitExternal(30_000) { find("Test vault") != null }
            click("Test vault")
            awaitExternal(15_000) {
                if (find("Continue") != null) click("Continue")
                find("Send synthetic fixture") != null
            }
            click("Send synthetic fixture")
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithText(context.getString(R.string.exchange_received)).assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithText("CXF Test Peer").assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.exchange_password_count, 1)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.exchange_passkey_count, 1)).assertIsDisplayed()
            assertEquals("Credentials must await confirmation before a database write", 0, writes)
            val image = automation.takeScreenshot()
            if (image != null) {
                File(context.getExternalFilesDir(null), "credential-exchange-live-received.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
        } finally {
            if (automation.rootInActiveWindow?.packageName?.toString() != context.packageName) {
                automation.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true)
                automation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true)
            }
            compose.runOnUiThread { visible.value = false }
            serviceInfo.flags = originalFlags
            automation.serviceInfo = serviceInfo
        }
    }
}
