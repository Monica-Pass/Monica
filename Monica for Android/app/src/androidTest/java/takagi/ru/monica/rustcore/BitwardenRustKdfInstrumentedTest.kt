package takagi.ru.monica.rustcore

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto

@RunWith(AndroidJUnit4::class)
class BitwardenRustKdfInstrumentedTest {
    @Test
    fun rustAndProductionPbkdf2MatchFixedVector() {
        val password = "correct horse battery staple"
        val salt = "user@example.com"
        val iterations = 10_000
        val expected = hexToBytes("d24a56b80f136deb9c2b6bca828e14020938f29effdac77aecbb4ff67628d487")
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        val saltBytes = salt.toByteArray(StandardCharsets.UTF_8)

        try {
            val rust = RustBitwardenKdfCore.derivePbkdf2Sha256(
                passwordBytes = passwordBytes,
                saltBytes = saltBytes,
                iterations = iterations,
            )
            assertNotNull("Rust PBKDF2 JNI returned null", rust)
            assertArrayEquals(expected, rust)

            val production = BitwardenCrypto.deriveMasterKeyPbkdf2(password, salt, iterations)
            assertArrayEquals(expected, production)

            rust?.fill(0)
            production.fill(0)
        } finally {
            expected.fill(0)
            passwordBytes.fill(0)
            saltBytes.fill(0)
        }
    }

    @Test
    fun rustAndProductionArgon2idMatchFixedVector() {
        val password = "correct horse battery staple"
        val salt = "user@example.com"
        val iterations = 2
        val memoryMb = 1
        val parallelism = 1
        val expected = hexToBytes("35dccab354151042368f592f3df2242d67bd2090adb747c67f901d649193855b")
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        val saltBytes = salt.toByteArray(StandardCharsets.UTF_8)
        val saltHash = MessageDigest.getInstance("SHA-256").digest(saltBytes)

        try {
            val rust = RustBitwardenKdfCore.deriveArgon2id(
                passwordBytes = passwordBytes,
                saltBytes = saltHash,
                iterations = iterations,
                memoryKiB = memoryMb * 1024,
                parallelism = parallelism,
            )
            assertNotNull("Rust Argon2id JNI returned null", rust)
            assertArrayEquals(expected, rust)

            val production = BitwardenCrypto.deriveMasterKeyArgon2(
                password = password,
                salt = salt,
                iterations = iterations,
                memory = memoryMb,
                parallelism = parallelism,
            )
            assertArrayEquals(expected, production)

            rust?.fill(0)
            production.fill(0)
        } finally {
            expected.fill(0)
            passwordBytes.fill(0)
            saltBytes.fill(0)
            saltHash.fill(0)
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
