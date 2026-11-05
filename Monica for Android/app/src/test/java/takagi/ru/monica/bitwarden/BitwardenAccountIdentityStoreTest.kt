package takagi.ru.monica.bitwarden

import java.security.KeyPairGenerator
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import takagi.ru.monica.bitwarden.api.ProfileResponse
import takagi.ru.monica.bitwarden.crypto.BitwardenAccountFingerprint
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.service.BitwardenAccountIdentityStore
import takagi.ru.monica.data.bitwarden.BitwardenVault

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class BitwardenAccountIdentityStoreTest {
    private val entries = mutableMapOf<String, String>()
    private val key = SymmetricCryptoKey(ByteArray(32) { 11 }, ByteArray(32) { 27 })
    private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val vault = BitwardenVault(id = 41, email = "synthetic@example.invalid", userId = "test-user",
        serverUrl = "https://vault.example.invalid", revisionDate = "stamp", isLocked = false)
    private fun store(writeWorks: Boolean = true) = BitwardenAccountIdentityStore(entries::get,
        { name, value -> if (writeWorks) entries[name] = value; writeWorks }, { entries.remove(it); Unit })
    private fun profile() = ProfileResponse(id = "test-user", email = vault.email, securityStamp = "stamp",
        privateKey = BitwardenCrypto.encrypt(rsa.private.encoded, key))

    @Test fun storesOnlySealedPublicIdentityAndReadsItAfterStoreRecreation() {
        assertArrayEquals(rsa.public.encoded,
            BitwardenAccountFingerprint.publicKeyFromEncryptedPrivateKey(profile().privateKey!!, key))
        store().capture(vault, profile(), key) { true }
        assertArrayEquals(rsa.public.encoded, store().publicKey(vault, key))
        val sealed = entries.values.single()
        assertTrue(sealed.startsWith("2."))
        val decoded = BitwardenCrypto.decryptToString(sealed, key)
        assertFalse(decoded.contains("privateKey"))
        assertFalse(decoded.contains(Base64.getEncoder().encodeToString(rsa.private.encoded)))
        assertTrue(decoded.contains(Base64.getEncoder().encodeToString(rsa.public.encoded)))
    }

    @Test fun bindsPublicIdentityToServerEmailUserIdAndSecurityStamp() {
        store().capture(vault, profile(), key) { true }
        for (other in listOf(vault.copy(serverUrl = "https://other.invalid"), vault.copy(email = "other@example.invalid"),
            vault.copy(userId = "other-user"), vault.copy(revisionDate = "other-stamp"))) {
            assertNull(store().publicKey(other, key))
        }
        assertArrayEquals(rsa.public.encoded, store().publicKey(vault.copy(email = "SYNTHETIC@example.invalid"), key))
    }

    @Test fun wrongWrappingKeyOrCorruptCacheCannotReturnAPublicIdentity() {
        store().capture(vault, profile(), key) { true }
        assertNull(store().publicKey(vault, SymmetricCryptoKey(ByteArray(32) { 3 }, ByteArray(32) { 4 })))
        entries[entries.keys.single()] = "2.invalid"
        assertNull(store().publicKey(vault, key))
    }

    @Test fun revokedCaptureCannotPublishAfterExpensiveKeyParsing() {
        var checks = 0
        store().capture(vault, profile(), key) { ++checks == 1 }
        assertTrue(checks >= 2)
        assertTrue(entries.isEmpty())
        store().capture(vault, profile(), key) { false }
        assertTrue(entries.isEmpty())
    }

    @Test fun malformedAuthenticatedPrivateKeyClearsOlderIdentity() {
        val store = store()
        store.capture(vault, profile(), key) { true }
        store.capture(vault, profile().copy(privateKey = BitwardenCrypto.encrypt(byteArrayOf(1, 2, 3), key)), key) { true }
        assertNull(store.publicKey(vault, key))
        assertTrue(runCatching { BitwardenAccountFingerprint.publicKeyFromEncryptedPrivateKey("0.invalid", key) }.isFailure)
    }

    @Test fun unsupportedKeyTypeAndShortRsaCannotCreateAnIdentity() {
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val shortRsa = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        for (unsupported in listOf(ec, shortRsa)) {
            val envelope = BitwardenCrypto.encrypt(unsupported.private.encoded, key)
            assertTrue(runCatching {
                BitwardenAccountFingerprint.publicKeyFromEncryptedPrivateKey(envelope, key)
            }.isFailure)
            val store = store()
            store.capture(vault, profile(), key) { true }
            assertNotNull(store.publicKey(vault, key))
            store.capture(vault, profile().copy(privateKey = envelope), key) { true }
            assertNull(store.publicKey(vault, key))
        }
    }

    @Test fun omittedProfileKeyPreservesOfflineIdentityButExplicitClearRemovesIt() {
        val store = store()
        store.capture(vault, profile(), key) { true }
        store.capture(vault, profile().copy(privateKey = null), key) { true }
        assertArrayEquals(rsa.public.encoded, store.publicKey(vault, key))
        store.clear(vault.id)
        assertNull(store.publicKey(vault, key))
    }

    @Test fun failedStorageDoesNotKeepAnOlderIdentity() {
        store().capture(vault, profile(), key) { true }
        store(writeWorks = false).capture(vault, profile(), key) { true }
        assertTrue(entries.isEmpty())
    }
}
