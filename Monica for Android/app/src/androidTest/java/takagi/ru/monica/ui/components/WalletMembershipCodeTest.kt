package takagi.ru.monica.ui.components

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.screens.DocumentDetailScreen
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.DocumentViewModel

class WalletMembershipCodeTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var model: DocumentViewModel
    private var wasUnlocked = false

    @Before fun setup() {
        wasUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        model = DocumentViewModel(SecureItemRepository(db.secureItemDao()), strings = AppLocaleStringResolver(context), sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it })
    }
    @After fun cleanup() {
        model.viewModelScope.cancel(); db.close()
        if (!wasUnlocked) SessionManager.markLocked()
    }

    @Test fun detailOpensQrAndBarcodeWithExactMembershipNumberWithoutSaving() {
        val value = " 00001234567890 "
        val data = pass("MEMBERSHIP", value)
        val id = runBlocking { db.secureItemDao().insertItem(SecureItem(
            itemType = ItemType.DOCUMENT, title = "Synthetic membership", itemData = CardWalletDataCodec.encodeDocumentData(data)
        )) }
        val before = runBlocking { db.secureItemDao().getItemById(id) }
        var navigatedBack = false
        compose.setContent { MaterialTheme {
            DocumentDetailScreen(model, id, { navigatedBack = true }, {})
        } }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("wallet_pass_show_code").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("wallet_pass_show_code").performScrollTo().performClick()
        assertCode(value, BarcodeFormat.QR_CODE)
        capture("membership-qr")
        compose.onNodeWithText(context.getString(R.string.field_barcode_format_linear)).performClick().assertIsSelected()
        assertCode(value, BarcodeFormat.CODE_128)
        capture("membership-barcode")
        Espresso.pressBack()
        compose.onNodeWithTag("wallet_pass_show_code").assertExists()
        assertFalse(navigatedBack)
        compose.onNodeWithTag("wallet_pass_show_code").performClick()
        assertCode(value, BarcodeFormat.QR_CODE)
        Espresso.pressBack()
        assertEquals(before, runBlocking { db.secureItemDao().getItemById(id) })
    }

    @Test fun rewardsUnicodeCodeCanReturnToQrAfterUnsupportedBarcodeAtLargeFont() {
        val value = "会员卡🎁-0001234"
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
            MaterialTheme(colorScheme = darkColorScheme()) { WalletPassCodeButton(pass("REWARDS", value)) }
        } }
        compose.onNodeWithTag("wallet_pass_show_code").performClick()
        assertCode(value, BarcodeFormat.QR_CODE)
        compose.onNodeWithText(context.getString(R.string.field_barcode_format_linear)).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText(context.getString(R.string.field_barcode_linear_failed)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.field_barcode_linear_failed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.field_barcode_format_qr)).performClick()
        assertCode(value, BarcodeFormat.QR_CODE)
        capture("rewards-dark-large")
    }

    @Test fun blankNumbersAndOtherDocumentTypesDoNotOfferMembershipCodes() {
        var data by mutableStateOf(pass("MEMBERSHIP", " \t"))
        compose.setContent { MaterialTheme { WalletPassCodeButton(data) } }
        compose.onNodeWithTag("wallet_pass_show_code").assertDoesNotExist()
        for (template in listOf("TRAVEL", "", "UNKNOWN")) {
            compose.runOnIdle { data = pass(template, "00001234") }
            compose.onNodeWithTag("wallet_pass_show_code").assertDoesNotExist()
        }
        compose.runOnIdle { data = pass("REWARDS", "00001234") }
        compose.onNodeWithTag("wallet_pass_show_code").assertIsDisplayed()
    }

    private fun pass(template: String, number: String) = DocumentData(
        DocumentType.OTHER, number, "Synthetic holder", walletTemplate = template, issuedBy = "Synthetic issuer"
    )

    private fun assertCode(value: String, format: BarcodeFormat) {
        val description = context.getString(R.string.field_action_show_barcode)
        compose.waitUntil(10000) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        val bitmap = compose.onNodeWithContentDescription(description).assertIsDisplayed().captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
        assertEquals(value, result.text)
        assertEquals(format, result.barcodeFormat)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, "wallet-code-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
