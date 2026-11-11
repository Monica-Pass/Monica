package takagi.ru.monica.ui

import takagi.ru.monica.utils.AppLocaleStringResolver

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.AuthenticatorLayoutMode
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.TotpViewModel

@RunWith(AndroidJUnit4::class)
class TotpListInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun standardListKeepsSelectionQrAndDeleteConfirmation() {
        verifyInteractions(AuthenticatorLayoutMode.STANDARD)
    }

    @Test
    fun tileGridKeepsSelectionQrAndDeleteConfirmation() {
        verifyInteractions(AuthenticatorLayoutMode.TILE)
    }

    private fun verifyInteractions(layout: AuthenticatorLayoutMode) = runBlocking<Unit> {
        val database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val passwords = PasswordRepository(database.passwordEntryDao())
        val items = SecureItemRepository(database.secureItemDao())
        val model = TotpViewModel(items, passwords, strings = AppLocaleStringResolver(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext), sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it })
        val passwordModel = PasswordViewModel(passwords, SecurityManager(context), items, strings = AppLocaleStringResolver(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext))
        val visible = mutableStateOf(true)
        val selection = AtomicReference(Selection())
        val singleDeletes = mutableListOf<Long>()
        var passkeyScans = 0
        var authenticatorScans = 0
        val first = "PR138 Alpha"
        val second = "PR138 Beta"
        try {
            val ids = listOf(first, second).mapIndexed { index, title ->
                database.secureItemDao().insertItem(
                    SecureItem(
                        itemType = ItemType.TOTP,
                        title = title,
                        itemData = Json.encodeToString(
                            TotpData(secret = "JBSWY3DPEHPK3PXP", issuer = title, accountName = "fixture@example.test")
                        ),
                        sortOrder = index
                    )
                )
            }
            withTimeout(10_000) { model.parsedTotpState.first { it.isReady && it.items.size == 2 } }
            compose.setContent {
                if (visible.value) MonicaTheme {
                    TotpListContent(
                        viewModel = model,
                        passwordViewModel = passwordModel,
                        appSettings = AppSettings(
                            authenticatorLayoutMode = layout,
                            biometricEnabled = false,
                            disablePasswordVerification = true,
                            iconCardsEnabled = false,
                            validatorVibrationEnabled = false
                        ),
                        onAuthenticatorLayoutModeChange = {},
                        onTotpClick = {},
                        onDeleteTotp = { singleDeletes += it.id },
                        onQuickScanTotp = { authenticatorScans++ },
                        onScanFidoQr = { passkeyScans++ },
                        onSelectionModeChange = { active, count, exit, _, _, delete ->
                            selection.set(Selection(active, count, exit, delete))
                        }
                    )
                }
            }
            awaitCard(first)
            awaitCard(second)
            compose.onAllNodesWithContentDescription(context.getString(R.string.more_options)).onFirst().performClick()
            compose.onNodeWithText(context.getString(R.string.passkey_scan_qr_menu_title)).assertIsDisplayed()
            val menu = compose.onNode(isPopup()).captureToImage().asAndroidBitmap()
            File(context.filesDir, "passkey-scan-totp-${layout.name}.png").outputStream().use {
                menu.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            menu.recycle()
            compose.onNodeWithText(context.getString(R.string.passkey_scan_qr_menu_title)).performClick()
            compose.onNode(isPopup()).assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, passkeyScans); assertEquals(0, authenticatorScans) }
            compose.onAllNodesWithContentDescription(context.getString(R.string.more_options)).onFirst().performClick()
            compose.onNodeWithText(context.getString(R.string.quick_action_scan_qr)).performClick()
            compose.runOnIdle { assertEquals(1, passkeyScans); assertEquals(1, authenticatorScans) }
            // Measure the real list wrappers, not a fixture with its own spacing.
            val firstBounds = card(first).fetchSemanticsNode().boundsInRoot
            val secondBounds = card(second).fetchSemanticsNode().boundsInRoot
            val gap = if (layout == AuthenticatorLayoutMode.STANDARD) {
                secondBounds.top - firstBounds.bottom
            } else {
                secondBounds.left - firstBounds.right
            }
            assertEquals("Card edge gap must match the 8dp tile gutter",
                8f * context.resources.displayMetrics.density, gap, 1f)
            File(context.getExternalFilesDir(null), "authenticator-spacing-${layout.name}.png")
                .outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap()
                        .compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            card(first).performTouchInput { longClick() }
            awaitSelection(selection, true, 1)
            card(second).performClick()
            awaitSelection(selection, true, 2)
            compose.runOnIdle { selection.get().exit() }
            awaitSelection(selection, false, 0)

            openMenu(first)
            compose.onNodeWithText(context.getString(R.string.show_qr_code)).performClick()
            compose.onNode(isDialog()).assertExists()
            compose.onNodeWithText(context.getString(R.string.close)).performClick()
            compose.onNode(isDialog()).assertDoesNotExist()

            if (layout == AuthenticatorLayoutMode.STANDARD) {
                card(first).performTouchInput { swipeLeft() }
            } else {
                openMenu(first)
                compose.onNodeWithText(context.getString(R.string.delete)).performClick()
            }
            compose.onNode(isDialog()).assertExists()
            compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
            awaitCard(first)
            compose.runOnIdle { assertTrue("Cancelling must not delete the item", singleDeletes.isEmpty()) }

            card(first).performTouchInput { longClick() }
            awaitSelection(selection, true, 1)
            card(second).performClick()
            awaitSelection(selection, true, 2)
            compose.runOnIdle { selection.get().delete() }
            compose.onNode(isDialog()).assertExists()
            compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
            awaitSelection(selection, true, 2)
            awaitCard(first)
            awaitCard(second)

            compose.runOnIdle { selection.get().delete() }
            compose.onNode(hasText(context.getString(R.string.delete)) and hasClickAction()).performClick()
            awaitSelection(selection, false, 0)
            withTimeout(10_000) { model.parsedTotpState.first { it.isReady && it.items.isEmpty() } }
            ids.forEach { id ->
                assertEquals(true, database.secureItemDao().getItemById(id)?.isDeleted)
            }
            compose.onNodeWithText(first).assertDoesNotExist()
            compose.onNodeWithText(second).assertDoesNotExist()
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            passwordModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            database.close()
        }
    }

    private fun card(title: String) = compose.onNode(hasText(title) and hasClickAction())

    private fun awaitCard(title: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(title) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        card(title).assertIsDisplayed()
    }

    private fun openMenu(title: String) {
        val bounds = card(title).fetchSemanticsNode().boundsInRoot
        compose.onAllNodesWithContentDescription(context.getString(R.string.more_options))
            .filterToOne(SemanticsMatcher("inside $title") { bounds.contains(it.boundsInRoot.center) })
            .performClick()
    }

    private fun awaitSelection(state: AtomicReference<Selection>, active: Boolean, count: Int) {
        compose.waitUntil(10_000) { state.get().active == active && state.get().count == count }
    }

    private data class Selection(
        val active: Boolean = false,
        val count: Int = 0,
        val exit: () -> Unit = {},
        val delete: () -> Unit = {}
    )
}
