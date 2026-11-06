package takagi.ru.monica.data.dedup

import java.io.File
import java.nio.file.Files
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.keepass.KeePassSourceSafety

class SingleVaultDedupFileSafetyTest {
    private fun kdf(extra: Boolean = false, duplicate: Boolean = false): ByteArray = Buffer().apply {
        writeShortLe(0x100)
        fun item(type: Int, key: String, bytes: ByteArray) {
            writeByte(type); writeIntLe(key.toByteArray().size); writeUtf8(key); writeIntLe(bytes.size); write(bytes)
        }
        item(0x42, "\$UUID", ByteArray(16)); item(0x42, "S", ByteArray(32)); item(5, "R", ByteArray(8))
        if (extra) item(0x42, "unclassified", byteArrayOf(1))
        if (duplicate) item(5, "R", ByteArray(8))
        writeByte(0)
    }.readByteArray()

    private fun header(version: Int = 0x40001, comment: Boolean = false, duplicate: Boolean = false, parameters: ByteArray = kdf()): ByteArray = Buffer().apply {
        writeIntLe(0x9AA2D903.toInt()); writeIntLe(0xB54BFB67.toInt()); writeIntLe(version)
        fun item(id: Int, bytes: ByteArray) { writeByte(id); writeIntLe(bytes.size); write(bytes) }
        item(2, ByteArray(16)); item(3, ByteArray(4)); item(4, ByteArray(32)); item(7, ByteArray(16)); item(11, parameters)
        item(12, byteArrayOf(0, 1, 0))
        if (comment) item(1, "preserve this".toByteArray())
        if (duplicate) item(3, ByteArray(4))
        item(0, byteArrayOf(13, 10, 13, 10))
    }.readByteArray()

    @Test fun onlyProvenVersionAndUnambiguousOuterMetadataCanAuthorizeCleanup() {
        assertTrue(SingleVaultDedupKeePassHeaderAudit.complete(header().inputStream()))
        assertFalse(SingleVaultDedupKeePassHeaderAudit.complete(header(version = 0x40000).inputStream()))
        assertFalse(SingleVaultDedupKeePassHeaderAudit.complete(header(comment = true).inputStream()))
        assertFalse(SingleVaultDedupKeePassHeaderAudit.complete(header(duplicate = true).inputStream()))
        assertFalse(SingleVaultDedupKeePassHeaderAudit.complete(header(parameters = kdf(extra = true)).inputStream()))
        assertFalse(SingleVaultDedupKeePassHeaderAudit.complete(header(parameters = kdf(duplicate = true)).inputStream()))
    }

    @Test fun atomicReplaceVerifiesStagingAndLateSourceWithoutTruncatingOnFailure() {
        val directory = Files.createTempDirectory("dedup-atomic-test").toFile()
        val target = File(directory, "target"); val encoded = File(directory, "encoded")
        try {
            target.writeText("original"); encoded.writeText("replacement")
            val expected = KeePassSourceSafety.revisionOf(target); val replacement = KeePassSourceSafety.revisionOf(encoded)
            assertThrows(IllegalStateException::class.java) {
                SingleVaultDedupAtomicFile.replace(target, encoded, expected, replacement, { target.writeText("other writer") }, { })
            }
            assertEquals("other writer", target.readText())
            assertEquals(setOf("target", "encoded"), directory.list()!!.toSet())
            val nextExpected = KeePassSourceSafety.revisionOf(target)
            encoded.writeText("corrupt staging")
            assertThrows(IllegalStateException::class.java) {
                SingleVaultDedupAtomicFile.replace(target, encoded, nextExpected, replacement, { }, { })
            }
            assertEquals("other writer", target.readText())
            encoded.writeText("replacement")
            var synced = false
            SingleVaultDedupAtomicFile.replace(target, encoded, nextExpected, replacement, { }, { synced = true })
            assertEquals("replacement", target.readText()); assertTrue(synced)
        } finally { directory.listFiles().orEmpty().forEach { it.delete() }; directory.delete() }
    }

    @Test fun authorizationIsRepeatedAfterTargetVerificationAndBeforeReplacement() {
        val directory = Files.createTempDirectory("dedup-atomic-cancel-test").toFile()
        val target = File(directory, "target"); val encoded = File(directory, "encoded")
        try {
            target.writeText("original"); encoded.writeText("replacement")
            var checks = 0
            assertThrows(IllegalStateException::class.java) {
                SingleVaultDedupAtomicFile.replace(target, encoded,
                    KeePassSourceSafety.revisionOf(target), KeePassSourceSafety.revisionOf(encoded), {
                        checks++
                        check(checks < 2) { "Authorization revoked" }
                    }, { fail("No replacement may be committed") })
            }
            assertEquals(2, checks)
            assertEquals("original", target.readText())
            assertEquals(setOf("target", "encoded"), directory.list()!!.toSet())
        } finally { directory.listFiles().orEmpty().forEach { it.delete() }; directory.delete() }
    }
}
