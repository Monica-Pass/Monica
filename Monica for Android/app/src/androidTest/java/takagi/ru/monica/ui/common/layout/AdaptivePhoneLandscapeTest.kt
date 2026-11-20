package takagi.ru.monica.ui.common.layout

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.MainActivity
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.security.*
import takagi.ru.monica.utils.SettingsManager
import java.io.File

/** Runs the real navigation and pane policy in a short phone window, using isolated synthetic data. */
class AdaptivePhoneLandscapeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val password = "SyntheticAdaptive329"

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(25_000) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }

    private fun size(width: Int, height: Int) {
        shell("wm size ${width}x$height")
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            val bounds = compose.onRoot().getUnclippedBoundsInRoot()
            kotlin.math.abs((bounds.right - bounds.left).value - width) < 2 && (bounds.bottom - bounds.top).value <= height
        }
    }

    private fun screen(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.filesDir, "adaptive-phone-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        File(context.filesDir, "adaptive-phone-$name.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
    }

    private fun assertSideBySide() {
        val list = compose.onNodeWithTag("adaptive-list").assertIsDisplayed().getUnclippedBoundsInRoot()
        val detail = compose.onNodeWithTag("adaptive-detail").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("List must not stretch across the phone: $list", (list.right - list.left).value in 280f..420f)
        assertTrue("Detail needs usable width: $detail", (detail.right - detail.left).value >= 320f)
        assertTrue("List and detail must be beside each other: $list / $detail", list.right <= detail.left)
        assertEquals(list.top.value, detail.top.value, 1f)
    }

    @Test fun passwordListAndDetailStayBesideEachOtherInPhoneLandscape() = verifyPhoneLandscape(wallet = false)

    @Test fun walletListAndDetailStayBesideEachOtherInPhoneLandscape() = verifyPhoneLandscape(wallet = true)

    private fun verifyPhoneLandscape(wallet: Boolean) {
        check(context.packageName in setOf("takagi.ru.monica.adaptive20261119", "takagi.ru.monica.adaptivefd20261119"))
        val originalSize = shell("wm size")
        val originalDensity = shell("wm density")
        val originalRotation = shell("settings get system user_rotation")
        val originalAuto = shell("settings get system accelerometer_rotation")
        val originalFont = shell("settings get system font_scale")
        fun overrideValue(text: String) = text.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
        var scenario: ActivityScenario<MainActivity>? = null
        val page = if (wallet) "wallet" else "password"
        try {
            shell("settings put system accelerometer_rotation 0")
            shell("settings put system user_rotation 0")
            shell("settings put system font_scale 1.0")
            shell("wm density 160")
            shell("wm size 960x432")
            runBlocking {
                assertTrue(SecureStorageStartup.prepareForEntry(context) is SecureStartupResult.Ready)
                SettingsManager(context).apply {
                    updateQuickSetupCompleted(true)
                    updateLanguage(Language.CHINESE)
                    updateThemeMode(ThemeMode.DARK)
                    updateBiometricEnabled(false)
                    updateScreenshotProtectionEnabled(false)
                    updateAutoLockMinutes(5)
                    updateVaultOverviewEnabled(false)
                    updateAutoHideBottomNavWhenSingleTab(false)
                    BottomNavContentTab.entries.forEach { updateBottomNavVisibility(it,
                        it == BottomNavContentTab.PASSWORDS || it == BottomNavContentTab.CARD_WALLET) }
                }
            }
            val security = SecurityManager(context)
            if (!security.isMasterPasswordSet()) assertTrue(security.setMasterPassword(password))
            else assertTrue(security.verifyMasterPassword(password))
            val database = PasswordDatabase.getDatabase(context)
            val ids = runBlocking {
                database.passwordEntryDao().getAllPasswordEntriesSync()
                    .filter { it.title.startsWith("Landscape fixture") }
                    .forEach { database.passwordEntryDao().deletePasswordEntry(it) }
                database.secureItemDao().getLocalItemsIncludingArchived()
                    .filter { it.title.startsWith("Landscape fixture") }
                    .forEach { database.secureItemDao().deleteItem(it) }
                val entry = database.passwordEntryDao().insertPasswordEntry(PasswordEntry(
                    title = "Landscape fixture 邮箱", username = "demo@example.invalid",
                    website = "https://example.invalid", password = security.encryptData("SyntheticRecord329")))
                val card = database.secureItemDao().insertItem(SecureItem(itemType = ItemType.BANK_CARD,
                    title = "Landscape fixture 银行卡", itemData = security.encryptData(Json.encodeToString(BankCardData(
                        cardNumber = "4111111111111111", cardholderName = "SYNTHETIC DEMO",
                        expiryMonth = "09", expiryYear = "2030", bankName = "Demo Bank")))))
                entry to card
            }
            SecurityManager.clearRuntimeUnlockCache()
            SessionManager.markLocked()
            scenario = ActivityScenario.launch(MainActivity::class.java)

            fun unlock() {
                waitFor(hasSetTextAction())
                compose.onNode(hasSetTextAction()).performScrollTo().performTextInput(password)
                compose.onNode(hasSetTextAction()).performImeAction()
                waitFor(hasTestTag("adaptive-list"))
            }
            fun selectTab() {
                compose.onAllNodesWithContentDescription(if (wallet) "卡包" else "密码", useUnmergedTree = true).onLast().performClick()
                waitFor(if (wallet) hasTestTag("wallet_card_${ids.second}") else hasText("Landscape fixture 邮箱"))
            }
            fun openItem() {
                if (wallet) compose.onNodeWithTag("wallet_card_${ids.second}").performScrollTo().performClick()
                else compose.onAllNodesWithText("Landscape fixture 邮箱").onFirst().performClick()
                waitFor(hasContentDescription("编辑") and hasAnyAncestor(hasTestTag("adaptive-detail")))
            }

            unlock()
            selectTab()
            screen("$page-960x432-before-selection")
            assertSideBySide()
            openItem()
            assertSideBySide()
            screen("$page-960x432-detail")
            size(1000, 400)
            assertSideBySide()
            screen("$page-1000x400-detail")
            size(400, 800)
            compose.onNodeWithTag("adaptive-list").assertIsNotDisplayed()
            compose.onNodeWithTag("adaptive-detail").assertIsDisplayed()
            screen("$page-portrait-detail")
            // Exercise the system Back event; Espresso waits on a stale orientation after wm resize.
            shell("input keyevent KEYCODE_BACK")
            compose.waitForIdle()
            compose.onNodeWithTag("adaptive-list").assertIsDisplayed()
            compose.onNodeWithTag("adaptive-detail").assertIsNotDisplayed()
            size(840, 400)
            assertSideBySide()
            size(800, 400)
            compose.onNodeWithTag("adaptive-detail").assertIsNotDisplayed()
            size(960, 432)
            assertSideBySide()

            compose.runOnIdle { SessionManager.markLocked(); SecurityManager.clearRuntimeUnlockCache() }
            waitFor(hasSetTextAction())
            compose.onNodeWithTag("adaptive-list").assertDoesNotExist()
            compose.onNodeWithTag("adaptive-detail").assertDoesNotExist()
            runBlocking { SettingsManager(context).updateThemeMode(ThemeMode.LIGHT) }
            shell("settings put system font_scale 1.5")
            unlock()
            selectTab()
            openItem()
            assertSideBySide()
            screen("$page-960x432-large-text-light")
        } catch (failure: Throwable) {
            runCatching { screen("$page-failure") }
            throw failure
        } finally {
            scenario?.close()
            shell("wm size ${overrideValue(originalSize)}")
            shell("wm density ${overrideValue(originalDensity)}")
            shell("settings put system user_rotation $originalRotation")
            shell("settings put system accelerometer_rotation $originalAuto")
            shell("settings put system font_scale $originalFont")
        }
    }
}
