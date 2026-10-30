package takagi.ru.monica.ui.screens

import android.content.ContentUris
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R

@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class DonationQrPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)

    private fun savedImages(): Set<Uri> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return context.contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("Monica_%"), null)?.use { cursor ->
            buildSet { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
        }.orEmpty()
    }

    private fun verifyPreviewAndSave(imageDescription: Int, expectedDrawable: Int) {
        val before = savedImages()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(label(imageDescription)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(label(imageDescription)).performScrollTo().performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithContentDescription(label(R.string.view_image)).performTouchInput {
            pinch(start0 = center - Offset(30f, 0f), end0 = center - Offset(150f, 0f),
                start1 = center + Offset(30f, 0f), end1 = center + Offset(150f, 0f))
        }
        compose.onNodeWithText(label(R.string.reset_zoom)).assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(label(R.string.save_to_gallery)).performClick()
        compose.waitUntil(10_000) { (savedImages() - before).isNotEmpty() }
        val uri = (savedImages() - before).single()
        try {
            val expected = BitmapFactory.decodeResource(context.resources, expectedDrawable,
                BitmapFactory.Options().apply { inScaled = false })
            val actual = context.contentResolver.openInputStream(uri)!!.use(BitmapFactory::decodeStream)
            assertNotNull("Saved image must be readable", actual)
            assertTrue("Save must preserve the selected original QR image", expected.sameAs(actual))
            expected.recycle()
            actual?.recycle()
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
        compose.onNodeWithContentDescription(label(R.string.cancel)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun plusPaymentQrCanBeZoomedAndSaved() {
        compose.setContent { MaterialTheme { PaymentScreen(onNavigateBack = {}, onActivatePlus = {}) } }
        verifyPreviewAndSave(R.string.payment_qr_code_title, R.drawable.support_author_qr)
        // Optional visual review of the real payment screen, with inert activation callbacks.
        if (InstrumentationRegistry.getArguments().getString("capturePlusUnlock") == "true") {
            compose.onNodeWithText(label(R.string.payment_paid_button)).performScrollTo().performClick()
            compose.waitForIdle()
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {
                java.io.File(context.filesDir, "plus-unlock-review.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { screenshot.recycle() }
            compose.onNodeWithText(label(R.string.cancel)).performClick()
        }
    }

    @Test fun plusFreeDonationSavesTheCurrentlySelectedQr() {
        compose.setContent { MaterialTheme { PaymentScreen(onNavigateBack = {}, onActivatePlus = {}) } }
        compose.onNode(isToggleable()).performClick()
        verifyPreviewAndSave(R.string.payment_qr_code_title, R.drawable.support_author_qr_free)
    }

    @Test fun supportPageUsesTheSamePreviewAndSaveFlow() {
        compose.setContent { MaterialTheme { SupportAuthorScreen(onNavigateBack = {},
            onRequestPermission = { _, _ -> fail("Saving owned images needs no storage permission on Android 10+") }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(label(R.string.qr_code_description)).fetchSemanticsNodes().isNotEmpty() }
        verifyPreviewAndSave(R.string.qr_code_description, R.drawable.support_author_qr)
    }
}
