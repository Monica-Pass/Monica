package takagi.ru.monica.ui.scanner

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.model.BoardingPassParser

class BoardingPassDecoderInstrumentedTest {
    private val formats = listOf(BarcodeFormat.PDF_417, BarcodeFormat.AZTEC,
        BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX)
    private val fixture = "M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 326J001A0025 10C>5180  W0D  "

    private fun barcode(payload: String, format: BarcodeFormat): Bitmap {
        val matrix = MultiFormatWriter().encode(payload, format,
            if (format == BarcodeFormat.PDF_417) 800 else 512,
            if (format == BarcodeFormat.PDF_417) 300 else 512)
        val border = 24
        val width = matrix.width + border * 2
        val height = matrix.height + border * 2
        val pixels = IntArray(width * height) { Color.WHITE }
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) pixels[(y + border) * width + x + border] = Color.BLACK
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    @Test fun allSupportedBoardingPassImagesDecodeThroughTheProductionScanner() {
        val decoder = ZxingBarcodeDecoder(formats, preserveWhitespace = true)
        for (format in formats) {
            val bitmap = barcode(fixture, format)
            try {
                val decoded = decoder.decodeBitmap(bitmap)
                assertTrue("Failed to read $format boarding pass", fixture in decoded)
                val pass = BoardingPassParser.parse(decoded.first(), LocalDate.of(2026, 10, 29))!!
                assertEquals("YUL", pass.origin)
                assertEquals("FRA", pass.destination)
                assertEquals("AC834", pass.serviceNumber)
                assertEquals("2026-11-22", pass.departure)
                assertEquals("1A", pass.seat)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun fixedWidthPaddingIsPreservedForBothCameraFramesAndImages() {
        val decoder = ZxingBarcodeDecoder(formats, preserveWhitespace = true)
        val frame = QrScannerFixtures.Frame().code(fixture)
        assertEquals(listOf(fixture), decoder.decodeFrame(frame.proxy()))
        val bitmap = frame.bitmap()
        try {
            assertEquals(listOf(fixture), decoder.decodeBitmap(bitmap))
            val trimmed = ZxingBarcodeDecoder(formats).decodeBitmap(bitmap).single()
            assertEquals(fixture.trim(), trimmed)
            assertNull(BoardingPassParser.parse(trimmed))
            assertEquals("AC834", BoardingPassParser.parse(fixture)!!.serviceNumber)
        } finally { bitmap.recycle() }
    }

    @Test fun anOrdinaryQrCodeIsReadableButRejectedAsABoardingPass() {
        val bitmap = barcode("https://example.com/booking", BarcodeFormat.QR_CODE)
        try {
            val decoded = ZxingBarcodeDecoder(formats).decodeBitmap(bitmap)
            assertEquals(listOf("https://example.com/booking"), decoded)
            assertNull(BoardingPassParser.parse(decoded.single()))
        } finally { bitmap.recycle() }
    }
}
