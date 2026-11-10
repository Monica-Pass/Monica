package takagi.ru.monica.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.ui.scanner.ZxingBarcodeDecoder

/** Hashes were independently decoded from the original JPEGs, before optimization. */
@RunWith(AndroidJUnit4::class)
class DonationQrEncodingInstrumentedTest {
    @Test fun paymentImageRetainsBothOriginalQrPayloadsAtOriginalAndPreviewSizes() {
        verify(R.drawable.support_author_qr, 3525, 1800, setOf(
            "710005a1b47ef97b77d96a8a9c951d9d5b1232365d19aef1323b545c37f3bf44",
            "fb150b0097c11d785331a8168483c80846eeba7915b9bd49cfca252d3320c645",
        ))
    }

    @Test fun freeDonationImageRetainsBothOriginalQrPayloadsAtOriginalAndPreviewSizes() {
        verify(R.drawable.support_author_qr_free, 2516, 1280, setOf(
            "8a0bd73aa96dd8808291ca7f51cf39fd4fa19332c49197380bd2b2a33e389411",
            "e8b473bf12fb8a501e2a2ceebae2f31a7178e5c3dea97f5dfc554c01c3b17422",
        ))
    }

    private fun verify(resource: Int, width: Int, height: Int, expected: Set<String>) {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val original = BitmapFactory.decodeResource(resources, resource,
            BitmapFactory.Options().apply { inScaled = false })
        assertNotNull("Packaged donation image must decode", original)
        requireNotNull(original)
        try {
            assertEquals(width, original.width)
            assertEquals(height, original.height)
            for (targetWidth in listOf(width, 1200, 800)) {
                val bitmap = if (targetWidth == width) original else Bitmap.createScaledBitmap(
                    original, targetWidth, (height.toLong() * targetWidth / width).toInt(), true,
                )
                try {
                    val decoded = ZxingBarcodeDecoder(listOf(BarcodeFormat.QR_CODE), preserveWhitespace = true)
                        .decodeBitmap(bitmap)
                    val hashes = decoded.map { text ->
                        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    }.toSet()
                    assertEquals("Original payment destinations must survive at width $targetWidth", expected, hashes)
                } finally {
                    if (bitmap !== original) bitmap.recycle()
                }
            }
        } finally {
            original.recycle()
        }
    }
}
