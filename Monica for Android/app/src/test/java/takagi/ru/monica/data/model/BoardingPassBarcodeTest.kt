package takagi.ru.monica.data.model

import org.junit.Assert.*
import org.junit.Test

class BoardingPassBarcodeTest {
    @Test fun oldDocumentsRemainReadableAndUnknownFormatsSurvive() {
        val old = """{"documentType":"OTHER","documentNumber":"ABC123","fullName":"Name","walletTemplate":"TRAVEL"}"""
        val document = requireNotNull(CardWalletDataCodec.parseDocumentData(old))
        assertNull(document.boardingPassBarcode)
        val code = BoardingPassBarcode(" Original  \r\n", "FUTURE")
        val updated = document.copy(boardingPassBarcode = code)
        assertEquals(updated, CardWalletDataCodec.parseDocumentData(CardWalletDataCodec.encodeDocumentData(updated)))
        assertEquals(code, BoardingPassBarcodeCodec.decode(BoardingPassBarcodeCodec.encode(code)))
        assertNull(BoardingPassBarcodeCodec.decode(""))
    }
    @Test(expected = kotlinx.serialization.SerializationException::class)
    fun malformedRemoteCodeDoesNotSilentlyBecomeAnExplicitRemoval() {
        BoardingPassBarcodeCodec.decode("{\"format\":\"QR_CODE\"}")
    }
    @Test fun malformedCodeDiagnosticsDoNotIncludeTheInput() {
        val failure = runCatching { BoardingPassBarcodeCodec.decode("{private-airline-data}") }.exceptionOrNull()!!
        assertEquals("Invalid boarding pass code", failure.message)
        assertNull(failure.cause)
    }
}
