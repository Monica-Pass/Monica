package takagi.ru.monica.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer

class PasswordFieldBarcodeTest {
    @Test
    fun acceptsPrintableAsciiWithinCode128Limit() {
        assertTrue(canEncodeAsCode128("GIFT-1234"))
        assertTrue(canEncodeAsCode128("A".repeat(80)))
    }

    @Test
    fun rejectsEmptyNonAsciiAndOversizedValues() {
        assertFalse(canEncodeAsCode128(""))
        assertFalse(canEncodeAsCode128("礼品卡"))
        assertFalse(canEncodeAsCode128("A".repeat(81)))
        assertFalse(canEncodeAsCode128("line\nvalue"))
        assertFalse(canEncodeAsCode128("value\u007F"))
        assertFalse(canEncodeAsCode128("value\u0000"))
    }

    @Test
    fun code128RoundTripsMembershipValuesWithoutTrimming() {
        listOf("GIFT-1234", " 1234 5678 ", "A".repeat(80), "0123456789".repeat(8)).forEach { value ->
            assertEquals(value, decode(createFieldBarcodeMatrix(value, FieldBarcodeFormat.CODE_128, 1100, 360)))
        }
    }

    @Test
    fun qrRoundTripsUnicodeAndMultilineText() {
        listOf("会员卡🎁-1234", "line 1\nline 2", "\u0000value").forEach { value ->
            assertEquals(value, decode(createFieldBarcodeMatrix(value, FieldBarcodeFormat.QR_CODE, 720, 720)))
        }
    }

    @Test
    fun narrowCode128KeepsItsModulesInsteadOfSilentlyShrinkingThem() {
        val matrix = createFieldBarcodeMatrix("A".repeat(80), FieldBarcodeFormat.CODE_128, 400, 360)
        assertTrue(matrix.width > 400)
        assertEquals("A".repeat(80), decode(matrix))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedCode128ValueCannotReachTheEncoder() {
        createFieldBarcodeMatrix("会员卡", FieldBarcodeFormat.CODE_128, 1100, 360)
    }

    private fun decode(matrix: BitMatrix): String {
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }
}
