package takagi.ru.monica.databasecompare

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class DatabaseCompareCopyTest {
    @Test fun authenticatesAllBytesIncludingChunkBoundaries() {
        val bytes = ByteArray(100_003) { (it * 31).toByte() }
        val output = ByteArrayOutputStream()
        val fingerprint = compareCopyAndHash(ByteArrayInputStream(bytes), output, bytes.size.toLong())
        assertEquals(bytes.size.toLong(), fingerprint.size)
        assertArrayEquals(bytes, output.toByteArray())
        bytes[32_768] = (bytes[32_768].toInt() xor 1).toByte()
        assertNotEquals(fingerprint, compareCopyAndHash(ByteArrayInputStream(bytes), null, bytes.size.toLong()))
    }

    @Test fun refusesOverLimitBeforeWritingExcessBytes() {
        val output = ByteArrayOutputStream()
        val input = object : InputStream() {
            var emitted = 0
            override fun read(): Int = if (emitted++ < 18) 1 else -1
            override fun read(bytes: ByteArray, off: Int, length: Int): Int {
                val count = minOf(3, 18 - emitted)
                if (count == 0) return -1
                repeat(count) { bytes[off + it] = 1 }; emitted += count
                return count
            }
        }
        assertThrows(IllegalStateException::class.java) { compareCopyAndHash(input, output, 10) }
        assertEquals(9, output.size())
    }

    @Test fun cancellationStopsBeforeAnotherOutputChunk() {
        val output = ByteArrayOutputStream()
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            compareCopyAndHash(ByteArrayInputStream(ByteArray(100_000)), output, 100_000) {
                check(++checks <= 1) { "cancelled" }
            }
        }
        assertEquals(32 * 1024, output.size())
    }

    @Test fun recognizesZeroLengthWithoutReadingPastLimit() {
        val digest = compareCopyAndHash(ByteArrayInputStream(byteArrayOf()), null, 0)
        assertEquals(0L, digest.size)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", digest.sha256)
        assertThrows(IllegalStateException::class.java) {
            compareCopyAndHash(ByteArrayInputStream(byteArrayOf(1)), null, 0)
        }
    }

    @Test fun cleanupAcceptsOnlyOurOutputPrefix() {
        val original = ByteArray(90_000) { (it * 17).toByte() }
        assertTrue(comparisonIsPrefix(ByteArrayInputStream(original.copyOf(40_000)), ByteArrayInputStream(original)))
        assertTrue(comparisonIsPrefix(ByteArrayInputStream(original), ByteArrayInputStream(original)))
        assertFalse(comparisonIsPrefix(ByteArrayInputStream(original + byteArrayOf(9)), ByteArrayInputStream(original)))
        assertFalse(comparisonIsPrefix(ByteArrayInputStream(original.copyOf().apply { this[40_000] = 99 }), ByteArrayInputStream(original)))
    }
}
