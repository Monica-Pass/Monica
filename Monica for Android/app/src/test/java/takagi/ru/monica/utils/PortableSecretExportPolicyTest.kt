package takagi.ru.monica.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortableSecretExportPolicyTest {
    @Test
    fun `cancellation remains cancellation`() {
        val cancelled = java.util.concurrent.CancellationException("Synthetic cancellation")
        val actual = assertThrows(java.util.concurrent.CancellationException::class.java) {
            PortableSecretExportPolicy.resolve("MDK|cipher", "Private synthetic title") { throw cancelled }
        }
        org.junit.Assert.assertSame(cancelled, actual)
    }

    @Test
    fun `private titles stay out of diagnostic exception messages`() {
        val error = assertThrows(PortableSecretExportException::class.java) {
            PortableSecretExportPolicy.resolve("MDK|cipher", "Private synthetic title") {
                throw javax.crypto.AEADBadTagException()
            }
        }
        assertEquals("Private synthetic title", error.entryTitle)
        org.junit.Assert.assertFalse(error.message.orEmpty().contains(error.entryTitle))
    }

    @Test
    fun `returns plaintext from a compatible decryptor`() {
        assertEquals(
            "secret",
            PortableSecretExportPolicy.resolve("MDK|cipher", "Account") { "secret" }
        )
    }

    @Test
    fun `keeps an already portable value`() {
        assertEquals(
            "secret",
            PortableSecretExportPolicy.resolve("secret", "Account") { it }
        )
    }

    @Test
    fun `does not fall back to installation bound ciphertext`() {
        assertThrows(PortableSecretExportException::class.java) {
            PortableSecretExportPolicy.resolve("MDK|cipher", "Account") {
                throw IllegalStateException("key unavailable")
            }
        }
    }
}
