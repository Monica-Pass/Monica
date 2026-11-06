package app.keemobile.kotpass.database.header

import app.keemobile.kotpass.constants.CrsAlgorithm
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class DedupInnerHeaderEvidenceTest {
    private fun source(repeatedBinary: Boolean = false, flag: Int = 1, repeatedStream: Boolean = false, trailer: Boolean = false): Buffer = Buffer().apply {
        writeByte(1); writeIntLe(4); writeIntLe(CrsAlgorithm.ChaCha20.ordinal)
        writeByte(2); writeIntLe(64); write(ByteArray(64) { 3 })
        if (repeatedStream) { writeByte(2); writeIntLe(64); write(ByteArray(64) { 4 }) }
        repeat(if (repeatedBinary) 2 else 1) { writeByte(3); writeIntLe(4); writeByte(flag); write(byteArrayOf(1, 2, 3)) }
        writeByte(0); writeIntLe(if (trailer) 1 else 0); if (trailer) writeByte(7)
    }

    @Test fun ordinaryHeaderIsCompleteAndReadsOriginalBinaryProtection() {
        val header = DatabaseInnerHeader.readFrom(source())
        assertTrue(header.sourcePayloadComplete)
        assertEquals(1, header.binaries.size)
        assertTrue(header.binaries.values.single().memoryProtection)
        assertArrayEquals(byteArrayOf(1, 2, 3), header.binaries.values.single().rawContent)
    }

    @Test fun duplicateBinaryAndUnknownFlagsKeepLegacyDecodeButInvalidateDedupEvidence() {
        val duplicate = DatabaseInnerHeader.readFrom(source(repeatedBinary = true))
        assertFalse(duplicate.sourcePayloadComplete)
        assertEquals(1, duplicate.binaries.size)
        val flags = DatabaseInnerHeader.readFrom(source(flag = 2))
        assertFalse(flags.sourcePayloadComplete)
        assertTrue(flags.binaries.values.single().memoryProtection)
        assertFalse(DatabaseInnerHeader.readFrom(source(repeatedStream = true)).sourcePayloadComplete)
        assertFalse(DatabaseInnerHeader.readFrom(source(trailer = true)).sourcePayloadComplete)
    }

    @Test fun evidenceFlagDoesNotChangeEncodedBytes() {
        val incomplete = DatabaseInnerHeader.readFrom(source(repeatedBinary = true))
        val first = Buffer(); incomplete.writeTo(first)
        val second = Buffer(); incomplete.copy(sourcePayloadComplete = true).writeTo(second)
        assertArrayEquals(first.readByteArray(), second.readByteArray())
    }
}
