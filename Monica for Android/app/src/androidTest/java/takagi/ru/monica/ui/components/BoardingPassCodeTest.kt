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
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.junit.*
import org.junit.Assert.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.KeePassKdbxService
import java.io.File

class BoardingPassCodeTest {
    @get:Rule val compose = createComposeRule()
    private val raw = "M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 326J001A0025 10C>5180  W0D  "
    private var unlocked = false
    @Before fun setup() { unlocked = SessionManager.isUnlocked.value; SessionManager.markUnlocked() }
    @After fun cleanup() { if (!unlocked) SessionManager.markLocked() else SessionManager.markUnlocked() }
    private fun document(format: BarcodeFormat) = DocumentData(DocumentType.OTHER, "EDITED", "Edited passenger",
        walletTemplate = "TRAVEL", walletFields = mapOf("origin" to "EDITED", "seat" to "99Z"),
        boardingPassBarcode = BoardingPassBarcode(raw, format.name))
    private fun decode(bitmap: Bitmap): com.google.zxing.Result {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
    }
    @Test fun everyOriginalFormatRendersExactPaddingAndPrivateData() {
        BoardingPassFormats.forEach { format ->
          listOf(raw, raw.replaceRange(1, 2, "2") + raw.drop(23) + "\r\n").forEach { payload ->
            val bitmap = requireNotNull(renderBoardingPassBarcode(BoardingPassBarcode(payload, format.name), 960, 600))
            try { val result = decode(bitmap); assertEquals(payload, result.text); assertEquals(format, result.barcodeFormat) }
            finally { bitmap.recycle() }
          }
        }
        assertNull(renderBoardingPassBarcode(BoardingPassBarcode(raw, "FUTURE_FORMAT"), 960, 600))
        assertNull(renderBoardingPassBarcode(BoardingPassBarcode(raw, "PDF_417"), 1, 1))
        assertNull(renderBoardingPassBarcode(BoardingPassBarcode("ABC123", "QR_CODE"), 960, 600))
    }
    @Test fun darkLargeFontViewerUsesOriginalLabelsAndExpandsWithoutChangingCode() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
            MaterialTheme(colorScheme = darkColorScheme()) { WalletPassCodeButton(document(BarcodeFormat.PDF_417)) }
        } }
        capture("action-dark-large")
        compose.onNodeWithTag("boarding_pass_show_code").performClick()
        val originalExtent = assertRendered(BarcodeFormat.PDF_417)
        compose.onNodeWithText("YUL → FRA").assertIsDisplayed()
        compose.onNodeWithText("AC834 · DESMARAIS/LUC").assertIsDisplayed()
        compose.onNodeWithText(raw).assertDoesNotExist()
        capture("viewer-dark-large")
        compose.onNodeWithTag("boarding_pass_code_expand").performClick()
        assertTrue("Enlarging must increase the actual code size", assertRendered(BarcodeFormat.PDF_417) > originalExtent)
        compose.onNodeWithText("YUL → FRA").assertDoesNotExist()
        capture("expanded")
        Espresso.pressBack()
        compose.onNodeWithTag("boarding_pass_code_page").assertDoesNotExist()
    }
    @Test fun missingOriginalOffersEditWithoutGeneratingBookingReferenceCode() {
        var edited = false
        compose.setContent { MaterialTheme { WalletPassCodeButton(document(BarcodeFormat.QR_CODE).copy(boardingPassBarcode = null), onAddCode = { edited = true }) } }
        compose.onNodeWithTag("boarding_pass_show_code").performClick()
        assertTrue(edited)
        compose.onNodeWithTag("boarding_pass_code_page").assertDoesNotExist()
        capture("missing")
    }
    @Test fun mainLockClosesViewerAndDoesNotReopenAfterUnlock() {
        compose.setContent { MaterialTheme { WalletPassCodeButton(document(BarcodeFormat.QR_CODE)) } }
        compose.onNodeWithTag("boarding_pass_show_code").performClick()
        assertRendered(BarcodeFormat.QR_CODE)
        compose.runOnIdle { SessionManager.markLocked() }
        compose.onNodeWithTag("boarding_pass_code_page").assertDoesNotExist()
        compose.runOnIdle { SessionManager.markUnlocked() }
        compose.onNodeWithTag("boarding_pass_code_page").assertDoesNotExist()
    }
    @Test fun sourceDatabaseLockRevokesOpenCode() {
        val id = -323991L
        val previous = KeePassKdbxService.nativeLockVersions.value
        try {
            compose.setContent { MaterialTheme { WalletPassCodeButton(document(BarcodeFormat.AZTEC), keepassDatabaseId = id) } }
            compose.onNodeWithTag("boarding_pass_show_code").performClick()
            assertRendered(BarcodeFormat.AZTEC)
            compose.runOnIdle { KeePassKdbxService.nativeLockVersions.value = previous + (id to 1L) }
            compose.onNodeWithTag("boarding_pass_code_page").assertDoesNotExist()
        } finally { KeePassKdbxService.nativeLockVersions.value = previous }
    }
    private fun assertRendered(format: BarcodeFormat): Int {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("boarding_pass_barcode").fetchSemanticsNodes().isNotEmpty() }
        val image = compose.onNodeWithTag("boarding_pass_barcode").assertIsDisplayed().captureToImage().asAndroidBitmap()
        val result = decode(image)
        assertEquals(raw, result.text); assertEquals(format, result.barcodeFormat)
        var minX = image.width; var minY = image.height; var maxX = 0; var maxY = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (image.getPixel(x, y) and 0x00ffffff == 0) {
                minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
            }
        }
        return maxOf(maxX - minX, maxY - minY)
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "boarding-code-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
