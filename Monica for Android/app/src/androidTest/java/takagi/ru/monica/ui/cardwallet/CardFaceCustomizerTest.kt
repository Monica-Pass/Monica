package takagi.ru.monica.ui.cardwallet

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.CardFaceConfig
import takagi.ru.monica.data.model.CardFaceDisplayMode
import takagi.ru.monica.ui.theme.MonicaTheme

@RunWith(AndroidJUnit4::class)
class CardFaceCustomizerTest {
    @get:Rule val compose = createComposeRule()
    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val configuration get() = Configuration(appContext.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
    private val previewContext get() = appContext.createConfigurationContext(configuration)
    private val applied = AtomicReference<CardFaceEditResult>()

    private fun showEditor() {
        val card = BankCardData("4242424242424242", "MONICA USER", "09", "29", bankName = "Monica", brand = "Visa")
        val bitmap = cover()
        val localized = previewContext
        compose.setContent {
            val pickerOwner = checkNotNull(LocalActivityResultRegistryOwner.current)
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalActivityResultRegistryOwner provides pickerOwner
            ) {
                MonicaTheme(darkTheme = false) {
                    CardFaceCustomizer(
                        title = "MONICA DAILY",
                        cardData = card,
                        initialConfig = CardFaceConfig("monica_card_face_0123456789abcdef.jpg"),
                        initialBitmap = bitmap,
                        imageSelectionAllowed = true,
                        imageSelectionWarning = null,
                        onDismiss = {},
                        onApply = applied::set
                    )
                }
            }
        }
    }

    @Test
    fun numberOnlyAndBrandToggleAreSavedTogether() {
        showEditor()
        capture("all")
        compose.onNodeWithText(previewContext.getString(R.string.card_face_show_brand)).performScrollTo().performClick()
        compose.onNodeWithText(previewContext.getString(R.string.card_face_display_identifier, previewContext.getString(R.string.card_face_identifier_bank)))
            .performScrollTo().performClick()
        scrollToTop()
        compose.onAllNodesWithText("MONICA DAILY").assertCountEquals(0)
        compose.onNodeWithText("••••  ••••  ••••  4242").assertIsDisplayed()
        capture("number")
        compose.onNodeWithText(previewContext.getString(R.string.save)).performClick()
        compose.waitForIdle()
        val result = requireNotNull(applied.get())
        assertEquals(CardFaceDisplayMode.CARD_NUMBER_ONLY, result.config?.displayMode)
        assertFalse(requireNotNull(result.config).showBrandIcon)
    }

    @Test
    fun hiddenModeRemovesCardTextAndPreservesTheImageReference() {
        showEditor()
        compose.onNodeWithText(previewContext.getString(R.string.card_face_display_hidden)).performScrollTo().performClick()
        scrollToTop()
        compose.onAllNodesWithText("MONICA DAILY").assertCountEquals(0)
        compose.onAllNodesWithText("••••  ••••  ••••  4242").assertCountEquals(0)
        capture("hidden")
        compose.onNodeWithText(previewContext.getString(R.string.save)).performClick()
        compose.waitForIdle()
        val config = requireNotNull(applied.get()).config
        assertEquals(CardFaceDisplayMode.HIDDEN, config?.displayMode)
        assertEquals("monica_card_face_0123456789abcdef.jpg", config?.imageAttachmentName)
    }

    private fun scrollToTop() {
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -10_000f) }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val image = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        val directory = File(appContext.cacheDir, "card-face-review").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun cover(): Bitmap = Bitmap.createBitmap(1280, 807, Bitmap.Config.ARGB_8888).also { bitmap ->
        Canvas(bitmap).apply {
            drawPaint(Paint().apply {
                shader = LinearGradient(0f, 0f, 1280f, 807f, Color.rgb(18, 63, 88), Color.rgb(73, 154, 163), Shader.TileMode.CLAMP)
            })
            drawCircle(1150f, 40f, 580f, Paint().apply { color = Color.argb(48, 189, 230, 223) })
            drawCircle(1190f, -80f, 420f, Paint().apply { color = Color.argb(45, 224, 241, 223) })
        }
    }
}
