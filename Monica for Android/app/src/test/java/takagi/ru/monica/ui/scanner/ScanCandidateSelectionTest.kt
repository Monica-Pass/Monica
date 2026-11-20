package takagi.ru.monica.ui.scanner

import com.google.zxing.BarcodeFormat
import org.junit.Assert.*
import org.junit.Test

class ScanCandidateSelectionTest {
    private fun code(text: String) = DecodedBarcode(text, BarcodeFormat.QR_CODE)

    @Test fun duplicateDetectionsAreOneChoice() {
        assertEquals(listOf(code("one")), selectScanCandidates(listOf(code("one"), code("one")), true) { true })
    }
    @Test fun multipleValidCodesRemainAmbiguousRegardlessOfOrder() {
        listOf(listOf(code("one"), code("two")), listOf(code("two"), code("one"))).forEach {
            assertEquals(2, selectScanCandidates(it, true) { true }.size)
        }
    }
    @Test fun unsupportedCodesDoNotHideTheOnlyValidCode() {
        assertEquals(listOf(code("one")), selectScanCandidates(listOf(code("url"), code("one")), true) { it == "one" })
        assertTrue(selectScanCandidates(listOf(code("url")), true) { false }.isEmpty())
    }
    @Test fun legacyCallersKeepTheirFirstValidResult() {
        assertEquals(listOf(code("one")), selectScanCandidates(listOf(code("one"), code("two")), false) { true })
    }
}
