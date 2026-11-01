package takagi.ru.monica.lansync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.storage.AttachmentStorage
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceAssetStreamingInstrumentedTest {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun key() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 71).toByte() })

    @Test fun nativeChunkStreamWritesAndReopensEncryptedAttachment() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "asset-stream-fixture-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val payload = ByteArray(3 * 1024 * 1024 + 17) { (it % 251).toByte() }
        val id = hash(payload)
        val descriptor = JSONObject().put("id", id).put("size", payload.size)
        val storage = AttachmentStorage(context, AttachmentStorage.Scope.DEVICE_METADATA)
        try {
            val transfer = DeviceAssetTransfer(directory, key())
            payload.inputStream().use { transfer.append(id, payload.size.toLong(), it) }
            val result = transfer.openStream(descriptor).use { storage.writeEncrypted(it) }
            try {
                assertEquals(payload.size.toLong(), result.sizeBytes)
                assertEquals(id, result.sha256Hex)
                val actual = MessageDigest.getInstance("SHA-256")
                storage.openDecryptedStream(result.relativePath, result.cek).use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    try { while (true) { val count = stream.read(buffer); if (count < 0) break; actual.update(buffer, 0, count) } }
                    finally { buffer.fill(0) }
                }
                assertEquals(id, actual.digest().joinToString("") { "%02x".format(it) })
            } finally { storage.delete(result.relativePath); result.cek.fill(0) }
        } finally { payload.fill(0); check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile); directory.deleteRecursively() }
    }

    @Test fun damagedNativeFinalChunkCannotPublishEncryptedBlob() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "asset-stream-fixture-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val payload = ByteArray(768 * 1024 + 17) { (it % 251).toByte() }
        val id = hash(payload)
        val descriptor = JSONObject().put("id", id).put("size", payload.size)
        val storage = AttachmentStorage(context, AttachmentStorage.Scope.DEVICE_METADATA)
        try {
            val transfer = DeviceAssetTransfer(directory, key())
            payload.inputStream().use { transfer.append(id, payload.size.toLong(), it) }
            java.io.RandomAccessFile(File(directory, "assets.monica"), "rw").use {
                it.seek(it.length() - 1)
                val old = it.read()
                it.seek(it.length() - 1)
                it.write(old xor 1)
            }
            val before = storage.listAllBlobs().toSet()
            try { transfer.openStream(descriptor).use { storage.writeEncrypted(it) }; fail("Damaged AEAD chunk must fail") }
            catch (expected: LanSyncException) { assertEquals("invalid_asset", expected.code) }
            assertEquals(before, storage.listAllBlobs().toSet())
        } finally { payload.fill(0); check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile); directory.deleteRecursively() }
    }

    @Test fun sourceDigestMismatchDoesNotWriteAuthenticatedFinalChunk() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "asset-stream-fixture-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val payload = ByteArray(768 * 1024 + 17) { (it % 251).toByte() }
        val id = "0".repeat(64)
        try {
            val transfer = DeviceAssetTransfer(directory, key())
            try { payload.inputStream().use { transfer.append(id, payload.size.toLong(), it) }; fail("Wrong source digest must fail") }
            catch (expected: LanSyncException) { assertEquals("invalid_asset", expected.code) }
            try { transfer.openStream(JSONObject().put("id", id).put("size", payload.size)).use { it.readBytes() }; fail("Partial asset cannot be read completely") }
            catch (expected: LanSyncException) { assertEquals("invalid_asset", expected.code) }
        } finally { payload.fill(0); check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile); directory.deleteRecursively() }
    }
}
