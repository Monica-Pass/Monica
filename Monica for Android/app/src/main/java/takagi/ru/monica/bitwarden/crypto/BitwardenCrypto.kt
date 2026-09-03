package takagi.ru.monica.bitwarden.crypto

import android.util.Base64
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import takagi.ru.monica.rustcore.RustBitwardenKdfCore

/**
 * Bitwarden encryption core.
 *
 * Protocol normalization remains in Kotlin while 32-byte PBKDF2-SHA256 and
 * Argon2id KDF work prefer the Rust JNI core. The existing implementations stay
 * available as compatibility fallbacks if the native core cannot be loaded or
 * rejects a request.
 */
object BitwardenCrypto {
    private const val MAX_CIPHER_STRING_LENGTH = 1024 * 1024
    private const val MAX_BASE64_PART_LENGTH = 1024 * 1024
    private const val ARGON2_JVM_FALLBACK_MAX_MEMORY_MB = 64

    const val CIPHER_TYPE_AES_CBC = 0
    const val CIPHER_TYPE_AES_CBC_HMAC = 2

    private const val AES_KEY_SIZE = 32
    private const val MAC_KEY_SIZE = 32
    private const val IV_SIZE = 16
    private const val SEND_KEY_MATERIAL_SIZE = 16
    private val nativeArgon2 by lazy { Argon2Kt() }

    data class SymmetricCryptoKey(
        val encKey: ByteArray,
        val macKey: ByteArray
    ) {
        init {
            require(encKey.size == AES_KEY_SIZE) { "encKey must be 32 bytes" }
            require(macKey.size == MAC_KEY_SIZE) { "macKey must be 32 bytes" }
        }

        fun clear() {
            encKey.fill(0)
            macKey.fill(0)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SymmetricCryptoKey) return false
            return encKey.contentEquals(other.encKey) && macKey.contentEquals(other.macKey)
        }

        override fun hashCode(): Int {
            var result = encKey.contentHashCode()
            result = 31 * result + macKey.contentHashCode()
            return result
        }
    }

    data class ParsedCipherString(
        val type: Int,
        val iv: ByteArray,
        val data: ByteArray,
        val mac: ByteArray?
    )

    fun deriveMasterKeyPbkdf2(
        password: String,
        salt: String,
        iterations: Int = 600000
    ): ByteArray {
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        val saltBytes = salt.toByteArray(StandardCharsets.UTF_8)
        return try {
            pbkdf2Sha256(
                seed = passwordBytes,
                salt = saltBytes,
                iterations = iterations,
                length = 32
            )
        } finally {
            passwordBytes.fill(0)
            saltBytes.fill(0)
        }
    }

    fun deriveMasterKeyArgon2(
        password: String,
        salt: String,
        iterations: Int = 3,
        memory: Int = 64,
        parallelism: Int = 4
    ): ByteArray {
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        val saltBytes = salt.toByteArray(StandardCharsets.UTF_8)
        val saltHash = MessageDigest.getInstance("SHA-256").digest(saltBytes)

        return try {
            val memoryKiB = argon2MemoryKiB(memory)
            val rustResult = RustBitwardenKdfCore.deriveArgon2id(
                passwordBytes = passwordBytes,
                saltBytes = saltHash,
                iterations = iterations,
                memoryKiB = memoryKiB,
                parallelism = parallelism
            )
            if (rustResult != null) {
                if (rustResult.size == AES_KEY_SIZE) {
                    rustResult
                } else {
                    rustResult.fill(0)
                    deriveMasterKeyArgon2LegacyNative(
                        passwordBytes = passwordBytes,
                        saltHash = saltHash,
                        iterations = iterations,
                        memory = memory,
                        parallelism = parallelism
                    )
                }
            } else {
                deriveMasterKeyArgon2LegacyNative(
                    passwordBytes = passwordBytes,
                    saltHash = saltHash,
                    iterations = iterations,
                    memory = memory,
                    parallelism = parallelism
                )
            }
        } catch (nativeError: Throwable) {
            if (nativeError is CancellationException) throw nativeError
            if (nativeError is ThreadDeath) throw nativeError
            if (memory > ARGON2_JVM_FALLBACK_MAX_MEMORY_MB) {
                throw IllegalStateException(
                    "Bitwarden Argon2id KDF requires ${memory}MB memory; native KDF failed " +
                        "and JVM fallback is disabled to avoid Android heap OOM.",
                    nativeError
                )
            }

            BitwardenArgon2MemoryGuard.requireCanRun(memory)
            deriveMasterKeyArgon2BouncyCastle(
                passwordBytes = passwordBytes,
                saltHash = saltHash,
                iterations = iterations,
                memory = memory,
                parallelism = parallelism
            )
        } finally {
            passwordBytes.fill(0)
            saltBytes.fill(0)
            saltHash.fill(0)
        }
    }

    /** Legacy argon2kt native path retained only as a compatibility fallback. */
    private fun deriveMasterKeyArgon2LegacyNative(
        passwordBytes: ByteArray,
        saltHash: ByteArray,
        iterations: Int,
        memory: Int,
        parallelism: Int
    ): ByteArray {
        val passwordBuffer = ByteBuffer.allocateDirect(passwordBytes.size).apply {
            put(passwordBytes)
            flip()
        }
        val saltBuffer = ByteBuffer.allocateDirect(saltHash.size).apply {
            put(saltHash)
            flip()
        }

        return try {
            val result = nativeArgon2.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = passwordBuffer,
                salt = saltBuffer,
                tCostInIterations = iterations,
                mCostInKibibyte = argon2MemoryKiB(memory),
                parallelism = parallelism,
                hashLengthInBytes = AES_KEY_SIZE,
                version = Argon2Version.V13
            )
            val hash = result.rawHashAsByteArray()
            wipeDirectBuffer(result.rawHash)
            wipeDirectBuffer(result.encodedOutput)
            hash
        } finally {
            wipeDirectBuffer(passwordBuffer)
            wipeDirectBuffer(saltBuffer)
        }
    }

    private fun deriveMasterKeyArgon2BouncyCastle(
        passwordBytes: ByteArray,
        saltHash: ByteArray,
        iterations: Int,
        memory: Int,
        parallelism: Int
    ): ByteArray {
        val params = org.bouncycastle.crypto.params.Argon2Parameters.Builder(
            org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id
        )
            .withSalt(saltHash)
            .withIterations(iterations)
            .withMemoryAsKB(argon2MemoryKiB(memory))
            .withParallelism(parallelism)
            .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13)
            .build()

        val generator = org.bouncycastle.crypto.generators.Argon2BytesGenerator()
        generator.init(params)

        val hash = ByteArray(AES_KEY_SIZE)
        try {
            generator.generateBytes(passwordBytes, hash)
        } catch (error: OutOfMemoryError) {
            hash.fill(0)
            throw BitwardenKdfMemoryException(
                requestedMemoryMb = memory,
                maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L),
                safeLimitMb = BitwardenArgon2MemoryGuard.safeLimitMb(
                    maxHeapBytes = Runtime.getRuntime().maxMemory(),
                    usedHeapBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                )
            )
        }
        return hash
    }

    private fun wipeDirectBuffer(buffer: ByteBuffer) {
        if (buffer.isReadOnly) return
        val duplicate = buffer.duplicate()
        duplicate.clear()
        while (duplicate.hasRemaining()) duplicate.put(0.toByte())
    }

    private fun argon2MemoryKiB(memoryMb: Int): Int {
        require(memoryMb > 0) { "Bitwarden Argon2id KDF memory must be positive: $memoryMb" }
        return try {
            Math.multiplyExact(memoryMb, 1024)
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("Bitwarden Argon2id KDF memory is too large: ${memoryMb}MB", e)
        }
    }

    fun deriveMasterPasswordHash(
        masterKey: ByteArray,
        password: String
    ): String {
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        var hash: ByteArray? = null
        return try {
            hash = pbkdf2Sha256(
                seed = masterKey,
                salt = passwordBytes,
                iterations = 1,
                length = AES_KEY_SIZE
            )
            Base64.encodeToString(hash, Base64.NO_WRAP)
        } finally {
            hash?.fill(0)
            passwordBytes.fill(0)
        }
    }

    /**
     * Prefer byte-oriented Rust PBKDF2 for Bitwarden's 32-byte KDF output.
     * BouncyCastle remains the exact byte-semantics fallback.
     */
    private fun pbkdf2Sha256(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int
    ): ByteArray {
        if (length == AES_KEY_SIZE) {
            val rustResult = RustBitwardenKdfCore.derivePbkdf2Sha256(
                passwordBytes = seed,
                saltBytes = salt,
                iterations = iterations
            )
            if (rustResult != null) {
                if (rustResult.size == length) return rustResult
                rustResult.fill(0)
            }
        }

        val generator = org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator(
            org.bouncycastle.crypto.digests.SHA256Digest()
        )
        generator.init(seed, salt, iterations)
        val params = generator.generateDerivedMacParameters(length * 8)
        return (params as org.bouncycastle.crypto.params.KeyParameter).key
    }

    fun pbkdf2Sha256Standard(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int
    ): ByteArray {
        val passwordChars = password.map { (it.toInt() and 0xFF).toChar() }.toCharArray()
        val spec = javax.crypto.spec.PBEKeySpec(passwordChars, salt, iterations, length * 8)
        return try {
            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            passwordChars.fill('\u0000')
        }
    }

    fun comparePbkdf2Implementations(
        password: String,
        salt: String,
        iterations: Int
    ): String {
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        val saltBytes = salt.toByteArray(StandardCharsets.UTF_8)
        var preferredResult: ByteArray? = null
        var stdResult: ByteArray? = null
        return try {
            val preferred = pbkdf2Sha256(passwordBytes, saltBytes, iterations, AES_KEY_SIZE)
            preferredResult = preferred
            val standard = pbkdf2Sha256Standard(passwordBytes, saltBytes, iterations, AES_KEY_SIZE)
            stdResult = standard

            val matches = MessageDigest.isEqual(preferred, standard)
            val preferredHex = preferred.joinToString("") { "%02x".format(it) }
            val stdHex = standard.joinToString("") { "%02x".format(it) }
            """
                Preferred: $preferredHex
                Standard API: $stdHex
                Match: $matches
            """.trimIndent()
        } finally {
            preferredResult?.fill(0)
            stdResult?.fill(0)
            passwordBytes.fill(0)
            saltBytes.fill(0)
        }
    }

    fun stretchMasterKey(masterKey: ByteArray): SymmetricCryptoKey {
        val encKey = hkdfExpand(masterKey, "enc".toByteArray(), AES_KEY_SIZE)
        val macKey = hkdfExpand(masterKey, "mac".toByteArray(), MAC_KEY_SIZE)
        return SymmetricCryptoKey(encKey, macKey)
    }

    fun generateSendKeyMaterial(): ByteArray {
        return ByteArray(SEND_KEY_MATERIAL_SIZE).also { SecureRandom().nextBytes(it) }
    }

    fun deriveSendKey(keyMaterial: ByteArray): SymmetricCryptoKey {
        require(keyMaterial.size == SEND_KEY_MATERIAL_SIZE) {
            "Send key material must be 16 bytes"
        }

        val fullKey = hkdf(
            seed = keyMaterial,
            salt = "bitwarden-send".toByteArray(StandardCharsets.UTF_8),
            info = "send".toByteArray(StandardCharsets.UTF_8),
            length = 64
        )
        return try {
            SymmetricCryptoKey(
                encKey = fullKey.copyOfRange(0, AES_KEY_SIZE),
                macKey = fullKey.copyOfRange(AES_KEY_SIZE, AES_KEY_SIZE + MAC_KEY_SIZE)
            )
        } finally {
            fullKey.fill(0)
        }
    }

    fun hashSendPassword(password: String, keyMaterial: ByteArray): String {
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        var hash: ByteArray? = null
        return try {
            hash = pbkdf2Sha256(
                seed = passwordBytes,
                salt = keyMaterial,
                iterations = 100_000,
                length = AES_KEY_SIZE
            )
            Base64.encodeToString(hash, Base64.NO_WRAP)
        } finally {
            hash?.fill(0)
            passwordBytes.fill(0)
        }
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val hashLen = 32
        val n = (length + hashLen - 1) / hashLen
        val output = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0

        for (i in 1..n) {
            val previous = t
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            if (previous.isNotEmpty()) previous.fill(0)

            val copyLen = minOf(hashLen, length - pos)
            System.arraycopy(t, 0, output, pos, copyLen)
            pos += copyLen
        }
        t.fill(0)
        return output
    }

    private fun hkdf(seed: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(seed)
        return try {
            hkdfExpand(prk = prk, info = info, length = length)
        } finally {
            prk.fill(0)
        }
    }

    fun parseCipherString(cipherString: String): ParsedCipherString {
        require(cipherString.isNotBlank()) { "Cipher string is blank" }
        require(cipherString.length <= MAX_CIPHER_STRING_LENGTH) {
            "Cipher string too large: ${cipherString.length}"
        }
        val dotIndex = cipherString.indexOf('.')
        if (dotIndex == -1) return parseCipherStringParts(CIPHER_TYPE_AES_CBC, cipherString)

        val type = cipherString.substring(0, dotIndex).toIntOrNull()
            ?: throw IllegalArgumentException("Invalid cipher type")
        return parseCipherStringParts(type, cipherString.substring(dotIndex + 1))
    }

    private fun parseCipherStringParts(type: Int, data: String): ParsedCipherString {
        val parts = data.split('|')
        return when (type) {
            CIPHER_TYPE_AES_CBC -> {
                require(parts.size >= 2) { "AES-CBC requires at least iv|data" }
                ParsedCipherString(
                    type = type,
                    iv = decodeBase64Part(parts[0], "iv", type),
                    data = decodeBase64Part(parts[1], "data", type),
                    mac = null
                )
            }
            CIPHER_TYPE_AES_CBC_HMAC -> {
                require(parts.size >= 3) { "AES-CBC-HMAC requires iv|data|mac" }
                ParsedCipherString(
                    type = type,
                    iv = decodeBase64Part(parts[0], "iv", type),
                    data = decodeBase64Part(parts[1], "data", type),
                    mac = decodeBase64Part(parts[2], "mac", type)
                )
            }
            else -> throw IllegalArgumentException("Unsupported cipher type: $type")
        }
    }

    private fun decodeBase64Part(rawPart: String, partName: String, type: Int): ByteArray {
        val part = rawPart.trim()
        require(part.isNotEmpty()) { "Empty cipher part: $partName, type=$type" }
        require(part.length <= MAX_BASE64_PART_LENGTH) {
            "Cipher part too large: $partName, len=${part.length}, type=$type"
        }

        val normalized = part.replace('-', '+').replace('_', '/')
        val padding = (4 - normalized.length % 4) % 4
        val padded = if (padding == 0) normalized else normalized + "=".repeat(padding)

        return try {
            org.bouncycastle.util.encoders.Base64.decode(padded)
        } catch (e: Throwable) {
            throw IllegalArgumentException(
                "Invalid base64 part: $partName, len=${part.length}, type=$type",
                e
            )
        }
    }

    fun decrypt(cipherString: String, key: SymmetricCryptoKey): ByteArray {
        return decrypt(parseCipherString(cipherString), key)
    }

    fun decryptToString(cipherString: String, key: SymmetricCryptoKey): String {
        val decrypted = decrypt(cipherString, key)
        return try {
            String(decrypted, StandardCharsets.UTF_8)
        } finally {
            decrypted.fill(0)
        }
    }

    fun decrypt(parsed: ParsedCipherString, key: SymmetricCryptoKey): ByteArray {
        if (parsed.mac != null) {
            val computedMac = computeMac(parsed.iv, parsed.data, key.macKey)
            try {
                if (!MessageDigest.isEqual(computedMac, parsed.mac)) {
                    throw SecurityException("MAC verification failed")
                }
            } finally {
                computedMac.fill(0)
            }
        }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.encKey, "AES"),
            IvParameterSpec(parsed.iv)
        )
        return cipher.doFinal(parsed.data)
    }

    fun decryptSymmetricKey(
        encryptedKey: String,
        masterKey: SymmetricCryptoKey
    ): SymmetricCryptoKey {
        val decrypted = decrypt(encryptedKey, masterKey)
        return try {
            require(decrypted.size == 64) {
                "Decrypted key should be 64 bytes, got ${decrypted.size}"
            }
            SymmetricCryptoKey(
                encKey = decrypted.copyOfRange(0, AES_KEY_SIZE),
                macKey = decrypted.copyOfRange(AES_KEY_SIZE, AES_KEY_SIZE + MAC_KEY_SIZE)
            )
        } finally {
            decrypted.fill(0)
        }
    }

    fun encrypt(plaintext: ByteArray, key: SymmetricCryptoKey): String {
        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.encKey, "AES"),
            IvParameterSpec(iv)
        )
        val encrypted = cipher.doFinal(plaintext)
        val mac = computeMac(iv, encrypted, key.macKey)

        return try {
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val dataBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            val macBase64 = Base64.encodeToString(mac, Base64.NO_WRAP)
            "$CIPHER_TYPE_AES_CBC_HMAC.$ivBase64|$dataBase64|$macBase64"
        } finally {
            iv.fill(0)
            encrypted.fill(0)
            mac.fill(0)
        }
    }

    fun encryptString(plaintext: String, key: SymmetricCryptoKey): String {
        val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        return try {
            encrypt(plaintextBytes, key)
        } finally {
            plaintextBytes.fill(0)
        }
    }

    private fun computeMac(iv: ByteArray, data: ByteArray, macKey: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        mac.update(iv)
        mac.update(data)
        return mac.doFinal()
    }

    fun clearBytes(vararg arrays: ByteArray) {
        arrays.forEach { it.fill(0) }
    }
}
