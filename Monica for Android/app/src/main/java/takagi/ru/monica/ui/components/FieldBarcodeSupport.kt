package takagi.ru.monica.ui.components

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import takagi.ru.monica.R

internal enum class FieldBarcodeFormat(val labelRes: Int) {
    QR_CODE(R.string.field_barcode_format_qr),
    CODE_128(R.string.field_barcode_format_linear),
}

internal fun canEncodeAsCode128(value: String): Boolean =
    value.isNotEmpty() && value.length <= 80 && value.all { it.code in 0x20..0x7E }

/** Generate the actual matrix separately from Android graphics, so round trips are testable. */
internal fun createFieldBarcodeMatrix(
    value: String,
    format: FieldBarcodeFormat,
    width: Int,
    height: Int,
): BitMatrix {
    require(width > 0 && height > 0)
    val barcodeFormat: BarcodeFormat
    val hints: Map<EncodeHintType, Any>
    when (format) {
        FieldBarcodeFormat.QR_CODE -> {
            barcodeFormat = BarcodeFormat.QR_CODE
            hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 4,
            )
        }
        FieldBarcodeFormat.CODE_128 -> {
            require(canEncodeAsCode128(value))
            barcodeFormat = BarcodeFormat.CODE_128
            // Ten modules on each side, as required by Code 128's quiet zone.
            hints = mapOf(EncodeHintType.MARGIN to 20)
        }
    }
    return MultiFormatWriter().encode(value, barcodeFormat, width, height, hints)
}
