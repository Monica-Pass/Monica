package takagi.ru.monica.lansync

import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class DeviceAssetInputStreamTest {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun expectInvalid(block: () -> Unit) {
        try { block(); fail("An invalid asset must not complete") }
        catch (expected: LanSyncException) { assertEquals("invalid_asset", expected.code) }
    }

    @Test fun generatedLargeSourceUsesBoundedReadsAndCloses() {
        val size = 7L * 1024 * 1024 + 17
        val source = GeneratedSource(size)
        val digest = MessageDigest.getInstance("SHA-256")
        GeneratedSource(size).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val id = digest.digest().joinToString("") { "%02x".format(it) }
        var consumed = 0L
        DeviceAssetInputStream.verified(source, id, size) {}.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                repeat(count) { index -> assertEquals(((consumed + index) % 251).toInt(), buffer[index].toInt() and 255) }
                consumed += count
            }
        }
        assertEquals(size, consumed)
        assertTrue(source.maximumRead <= DeviceAssetInputStream.CHUNK_BYTES)
        assertTrue(source.closed)
    }

    @Test fun corruptFinalChunkNeverReachesConsumer() {
        val id = hash("abcdef".toByteArray())
        val bad = "xyz".toByteArray()
        var next = 0
        val stream = DeviceAssetInputStream(id, 6, {}) {
            if (next++ == 0) DeviceAssetInputStream.Chunk(id, 0, false, "abc".toByteArray())
            else DeviceAssetInputStream.Chunk(id, 1, true, bad)
        }
        stream.use {
            val buffer = ByteArray(3)
            assertEquals(3, it.read(buffer))
            assertArrayEquals("abc".toByteArray(), buffer)
            buffer.fill(0)
            expectInvalid { it.read(buffer) }
            assertArrayEquals(ByteArray(3), buffer)
            assertArrayEquals(ByteArray(3), bad)
        }
    }

    @Test fun rejectsTruncatedOversizedAndWrongDigestSources() {
        val expected = "abcdef".toByteArray()
        for (bytes in listOf("abc".toByteArray(), "abcdefg".toByteArray(), "abcxyz".toByteArray())) {
            expectInvalid {
                DeviceAssetInputStream.verified(bytes.inputStream(), hash(expected), expected.size.toLong()) {}.use { it.readBytes() }
            }
        }
    }

    @Test fun rejectsWrongIdentitySkippedIndexAndMissingFinal() {
        val payload = "abc".toByteArray()
        val id = hash(payload)
        for (chunk in listOf(
            DeviceAssetInputStream.Chunk(hash("other".toByteArray()), 0, true, payload.copyOf()),
            DeviceAssetInputStream.Chunk(id, 1, true, payload.copyOf()),
            DeviceAssetInputStream.Chunk(id, 0, false, payload.copyOf())
        )) {
            expectInvalid { DeviceAssetInputStream(id, 3, {}, { chunk }).use { it.read() } }
            assertArrayEquals(ByteArray(3), chunk.bytes)
        }
    }

    @Test fun emptyAssetRequiresVerifiedFinalAndRejectsExtraByte() {
        DeviceAssetInputStream.verified(ByteArray(0).inputStream(), hash(ByteArray(0)), 0) {}.use { assertEquals(-1, it.read()) }
        expectInvalid { DeviceAssetInputStream.verified(byteArrayOf(1).inputStream(), hash(ByteArray(0)), 0) {}.use { it.read() } }
    }

    @Test fun cancellationStopsReadsAndClosingClearsOwnedBuffer() {
        val bytes = ByteArray(20) { it.toByte() }
        val id = hash(bytes)
        var active = true
        val stream = DeviceAssetInputStream.verified(DeviceAssetInputStream.ownedBytes(bytes), id, bytes.size.toLong()) {
            if (!active) throw CancellationException()
        }
        stream.use {
            assertEquals(0, it.read())
            active = false
            try { it.read(); fail("Cancelled reads must stop") } catch (_: CancellationException) { }
        }
        assertArrayEquals(ByteArray(20), bytes)
    }

    @Test fun invalidSizeClosesSourceBeforeReading() {
        val source = GeneratedSource(1)
        try {
            DeviceAssetInputStream.verified(source, "0".repeat(64), DeviceAssetInputStream.MAX_BYTES + 1) {}
            fail("An over-limit source must be rejected")
        } catch (expected: LanSyncException) { assertEquals("asset_limit", expected.code) }
        assertTrue(source.closed)
        assertEquals(0, source.maximumRead)
    }

    private class GeneratedSource(private val size: Long) : InputStream() {
        private var position = 0L
        var maximumRead = 0
        var closed = false
        override fun read(): Int = if (position >= size) -1 else (position++ % 251).toInt()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            maximumRead = maxOf(maximumRead, length)
            if (position >= size) return -1
            val count = minOf(length.toLong(), size - position).toInt()
            repeat(count) { buffer[offset + it] = ((position + it) % 251).toByte() }
            position += count
            return count
        }
        override fun close() { closed = true }
    }
}
