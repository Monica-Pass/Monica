package takagi.ru.monica.ui.icons

import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.EmojiIconInputDialog
import takagi.ru.monica.ui.components.InstalledIconPickerBottomSheet
import takagi.ru.monica.ui.components.PasswordFieldActionMenuButton
import takagi.ru.monica.ui.components.rememberPasswordFieldActionMenuState

@RunWith(AndroidJUnit4::class)
class PasswordCustomIconsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun label(id: Int) = context.getString(id)

    @Test
    fun invalidEmojiCannotBeSavedAndQuickPickCanBeSaved() {
        var selected: String? = null
        compose.setContent {
            MaterialTheme { EmojiIconInputDialog(onConfirm = { selected = it }, onDismissRequest = {}) }
        }
        compose.onNodeWithText(label(R.string.save)).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("ab")
        compose.onNodeWithText(label(R.string.custom_icon_emoji_invalid)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.save)).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("🔑‍🔐")
        compose.onNodeWithText(label(R.string.save)).assertIsNotEnabled()
        compose.onNodeWithText("🎁").performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.save)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("🎁", selected) }
    }

    @Test
    fun emojiDialogPrefillsTheCurrentChoice() {
        compose.setContent {
            MaterialTheme { EmojiIconInputDialog(initialEmoji = "👍🏽", onConfirm = {}, onDismissRequest = {}) }
        }
        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("👍🏽"))
        )
        compose.onNodeWithText(label(R.string.save)).assertIsEnabled()
    }

    @Test
    fun emojiArtworkKeepsItsDimensionsWithLargeFonts() {
        var fontScale by mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme { Box(Modifier.size(40.dp)) { EmojiIconText("🔑", 28.dp) } }
            }
        }
        val original = compose.onNodeWithText("🔑").fetchSemanticsNode().boundsInRoot.size
        compose.runOnIdle { fontScale = 1.5f }
        val enlarged = compose.onNodeWithText("🔑").fetchSemanticsNode().boundsInRoot.size
        assertEquals(original.width, enlarged.width, 1f)
        assertEquals(original.height, enlarged.height, 1f)
    }

    @Test
    fun installedPackResourcesAndAssetCatalogAreImportedAsOwnedImages() = runBlocking {
        val pack = InstalledIconCatalog.packs(context).first { it.packageName == instrumentation.context.packageName }
        val icons = InstalledIconCatalog.icons(context, pack)
        assertEquals(setOf("icon_fixture_star", "icon_fixture_mail"), icons.map { it.drawableName }.toSet())
        val fileName = InstalledIconCatalog.importIcon(context, icons.first()).getOrThrow()
        try {
            val file = PasswordCustomIconStore.resolveIconFile(context, fileName)
            assertNotNull(file)
            val saved = BitmapFactory.decodeFile(file!!.absolutePath)
            assertNotNull(saved)
            assertTrue(saved.width <= 384 && saved.height <= 384)
            saved.recycle()
        } finally {
            PasswordCustomIconStore.deleteIconFile(context, fileName)
        }
    }

    @Test
    fun installedPackCanBeBrowsedSearchedAndSelected() {
        var fileName: String? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme { InstalledIconPickerBottomSheet(onIconSelected = { fileName = it }, onDismissRequest = {}) }
        }
        try {
            compose.onNodeWithText(label(R.string.custom_icon_installed_packs)).performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Monica Icon Fixture").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Monica Icon Fixture").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("fixture mail")
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("icon fixture mail").fetchSemanticsNodes().isNotEmpty()
            }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNode(hasSetTextAction()).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("fixture mail"))
            )
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("icon fixture mail").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("icon fixture mail").performClick()
            compose.waitUntil(15_000) { fileName != null }
            assertNotNull(PasswordCustomIconStore.resolveIconFile(context, fileName))
        } finally {
            fileName?.let { PasswordCustomIconStore.deleteIconFile(context, it) }
        }
    }

    @Test
    fun fieldMenuGeneratesScannableQrAndCode128() {
        showFieldMenu("GIFT-1234567890")
        decodeDisplayedBarcode().also {
            assertEquals("GIFT-1234567890", it.text)
            assertEquals(BarcodeFormat.QR_CODE, it.barcodeFormat)
        }
        compose.onNodeWithText(label(R.string.field_barcode_format_linear)).performScrollTo().performClick()
        decodeDisplayedBarcode().also {
            assertEquals("GIFT-1234567890", it.text)
            assertEquals(BarcodeFormat.CODE_128, it.barcodeFormat)
        }
    }

    @Test
    fun unicodeQrScansAndCode128ShowsARecoverableError() {
        showFieldMenu("会员卡🎁-1234")
        assertEquals("会员卡🎁-1234", decodeDisplayedBarcode().text)
        compose.onNodeWithText(label(R.string.field_barcode_format_linear)).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(label(R.string.field_barcode_linear_failed)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(label(R.string.field_barcode_linear_failed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.field_barcode_format_qr)).performScrollTo().performClick()
        assertEquals("会员卡🎁-1234", decodeDisplayedBarcode().text)
    }

    @Test
    fun barcodePageAndFormatSurviveStateRestoration() {
        val restoration = StateRestorationTester(compose)
        showFieldMenu("GIFT-1234567890", restoration)
        compose.onNodeWithText(label(R.string.field_barcode_format_linear)).performScrollTo().performClick()
        assertEquals(BarcodeFormat.CODE_128, decodeDisplayedBarcode().barcodeFormat)
        restoration.emulateSavedInstanceStateRestore()
        decodeDisplayedBarcode().also {
            assertEquals("GIFT-1234567890", it.text)
            assertEquals(BarcodeFormat.CODE_128, it.barcodeFormat)
        }
    }

    private fun showFieldMenu(value: String, restoration: StateRestorationTester? = null) {
        val content: @Composable () -> Unit = {
            MaterialTheme {
                PasswordFieldActionMenuButton(
                    state = rememberPasswordFieldActionMenuState(),
                    label = "Membership",
                    value = value,
                    displayValue = value,
                    context = context,
                )
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        compose.onNodeWithContentDescription(label(R.string.field_action_more)).performClick()
        compose.onNodeWithText(label(R.string.field_action_show_barcode)).performClick()
    }

    private fun decodeDisplayedBarcode(): com.google.zxing.Result {
        val description = label(R.string.field_action_show_barcode)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
        val bitmap = compose.onNodeWithContentDescription(description)
            .performScrollTo()
            .captureToImage()
            .asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))
    }
}
