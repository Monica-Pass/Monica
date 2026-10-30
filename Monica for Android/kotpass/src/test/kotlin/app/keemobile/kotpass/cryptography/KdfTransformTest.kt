package app.keemobile.kotpass.cryptography

import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.models.Meta
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class KdfTransformTest {
    private val credentials = Credentials.from(EncryptedValue.fromString("Synthetic KDF scope"))
    private val database = KeePassDatabase.Ver4x.create("Probe", Meta(), credentials)
    private val header = database.header.copy(kdfParameters =
        (database.header.kdfParameters as KdfParameters.Argon2).copy(
            memory = 64UL * 1024UL, iterations = 1UL, parallelism = 1U))

    @Test fun scopedProviderIsUsedAndBorrowedCompositeIsCleared() {
        var borrowed: ByteArray? = null
        val result = KdfTransform.withArgon2(Argon2KeyTransformer { _, key ->
            borrowed = key
            ByteArray(32) { 7 }
        }) { KeyTransform.transformedKey(header, credentials) }
        assertArrayEquals(ByteArray(32) { 7 }, result)
        assertArrayEquals(ByteArray(32), borrowed)
        assertFalse(KeyTransform.transformedKey(header, credentials).contentEquals(result))
    }

    @Test fun nullResultUsesTheUnchangedOriginalTransform() {
        val original = KeyTransform.transformedKey(header, credentials)
        val fallback = KdfTransform.withArgon2(Argon2KeyTransformer { _, _ -> null }) {
            KeyTransform.transformedKey(header, credentials)
        }
        assertArrayEquals(original, fallback)
    }

    @Test fun nestedScopeAndExceptionRestoreThePreviousProvider() {
        KdfTransform.withArgon2(Argon2KeyTransformer { _, _ -> ByteArray(32) { 1 } }) {
            try {
                KdfTransform.withArgon2(Argon2KeyTransformer { _, _ -> ByteArray(32) { 2 } }) {
                    assertArrayEquals(ByteArray(32) { 2 }, KeyTransform.transformedKey(header, credentials))
                    throw IllegalStateException("synthetic failure")
                }
            } catch (_: IllegalStateException) { }
            assertArrayEquals(ByteArray(32) { 1 }, KeyTransform.transformedKey(header, credentials))
        }
        assertFalse(KeyTransform.transformedKey(header, credentials).contentEquals(ByteArray(32) { 1 }))
    }

    @Test fun providerCannotLeakIntoAnotherWorker() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val original = KeyTransform.transformedKey(header, credentials)
            KdfTransform.withArgon2(Argon2KeyTransformer { _, _ -> ByteArray(32) { 1 } }) {
                assertArrayEquals(original, executor.submit<ByteArray> {
                    KeyTransform.transformedKey(header, credentials)
                }.get(5, TimeUnit.SECONDS))
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun wrongLengthRejectsBeforeEncodingAndClearsInput() {
        var borrowed: ByteArray? = null
        try {
            KdfTransform.withArgon2(Argon2KeyTransformer { _, key -> borrowed = key; ByteArray(31) }) {
                KeyTransform.transformedKey(header, credentials)
            }
            fail("Wrong-size provider output must fail")
        } catch (_: IllegalArgumentException) { }
        assertArrayEquals(ByteArray(32), borrowed)
    }

    @Test fun aesTransformIgnoresTheArgon2Provider() {
        val aes = header.copy(kdfParameters = KdfParameters.Aes(
            seed = okio.ByteString.of(*ByteArray(32) { 3 }), rounds = 10UL))
        val expected = KeyTransform.transformedKey(aes, credentials)
        KdfTransform.withArgon2(Argon2KeyTransformer { _, _ -> error("AES must not use Argon2") }) {
            assertArrayEquals(expected, KeyTransform.transformedKey(aes, credentials))
        }
    }

    @Test fun aesScopeIsUsedAndClearsItsBorrowedInputs() {
        var key: ByteArray? = null
        var seed: ByteArray? = null
        val aes = header.copy(kdfParameters = KdfParameters.Aes(
            seed = okio.ByteString.of(*ByteArray(32) { 3 }), rounds = 10UL))
        KdfTransform.withAes(AesKeyTransformer { borrowedSeed, _, borrowedKey ->
            seed = borrowedSeed; key = borrowedKey; ByteArray(32) { 9 }
        }) { assertArrayEquals(ByteArray(32) { 9 }, KeyTransform.transformedKey(aes, credentials)) }
        assertArrayEquals(ByteArray(32), key)
        assertArrayEquals(ByteArray(32), seed)
        assertFalse(KeyTransform.transformedKey(aes, credentials).contentEquals(ByteArray(32) { 9 }))
    }
}
