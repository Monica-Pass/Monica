package takagi.ru.monica.data.dedup

import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class SingleVaultDedupPayloadStoreTest {
    private val key = SecretKeySpec(ByteArray(32) { (it + 17).toByte() }, "AES")
    private fun seal(text: String): String = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key)
        Base64.getEncoder().encodeToString(iv + doFinal(text.toByteArray(Charsets.UTF_8)))
    }
    private fun open(text: String): String = Cipher.getInstance("AES/GCM/NoPadding").run {
        val bytes = Base64.getDecoder().decode(text)
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    @Test fun largeEncryptedPayloadRoundTripsThroughSmallReceiptAndRejectsTamperingAndCrossBatchRead() {
        val parent = Files.createTempDirectory("dedup-payload-test").toFile()
        val root = File(parent, "payloads")
        val id = UUID.randomUUID().toString()
        var syncs = 0
        val store = SingleVaultDedupPayloadStore(root, ::seal, ::open) { syncs++ }
        try {
            val text = "private synthetic payload".repeat(120_000)
            assertTrue(text.length > 2 * 1024 * 1024)
            val receipt = store.write(id, "keepass:7", text) { }
            assertTrue(receipt.length < 4096)
            assertEquals(text, store.read(id, "keepass:7", receipt))
            assertEquals(2, syncs)
            val file = File(root, "$id.sealed")
            assertFalse(file.readText().contains("private synthetic payload"))
            assertThrows(IllegalArgumentException::class.java) { store.read(id, "keepass:8", receipt) }
            assertThrows(IllegalArgumentException::class.java) { store.read(UUID.randomUUID().toString(), "keepass:7", receipt) }
            val bytes = file.readBytes()
            file.writeBytes(bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            assertThrows(IllegalArgumentException::class.java) { store.read(id, "keepass:7", receipt) }
            file.writeBytes(bytes.copyOf(bytes.size - 1))
            assertThrows(IllegalArgumentException::class.java) { store.read(id, "keepass:7", receipt) }
        } finally { root.listFiles().orEmpty().forEach { it.delete() }; root.delete(); parent.delete() }
    }

    @Test fun payloadPathsAreUuidOnlyAndCanceledPublicationDoesNotProduceReceipt() {
        val parent = Files.createTempDirectory("dedup-payload-cancel").toFile()
        val root = File(parent, "payloads")
        val store = SingleVaultDedupPayloadStore(root, ::seal, ::open) { }
        try {
            assertThrows(IllegalArgumentException::class.java) { store.write("../other", "keepass:7", "synthetic") { } }
            var checks = 0
            assertThrows(IllegalStateException::class.java) {
                store.write(UUID.randomUUID().toString(), "keepass:7", "synthetic") { checks++; check(checks < 2) }
            }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.listFiles().orEmpty().forEach { it.delete() }; root.delete(); parent.delete() }
    }
}
