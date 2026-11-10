package takagi.ru.monica.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import takagi.ru.monica.bitwarden.crypto.BitwardenArgon2MemoryGuard
import takagi.ru.monica.rustcore.RustBitwardenKdfCore
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The application's local password protection; independent of backup and provider formats. */
enum class LocalPasswordAlgorithm { PBKDF2_SHA256, ARGON2ID }

/** Only reviewed, bounded profiles are accepted. Missing metadata means the historical 100k format. */
internal object PasswordKdf {
    const val METADATA_KEY = "local_password_kdf_v1"

    data class Profile internal constructor(
        val encoded: String?,
        val algorithm: LocalPasswordAlgorithm,
        val iterations: Int,
        val memoryKiB: Int = 0,
        val parallelism: Int = 0,
    )

    val LEGACY = Profile(null, LocalPasswordAlgorithm.PBKDF2_SHA256, 100_000)
    val PBKDF2_600K = Profile("1|pbkdf2-sha256|600000", LocalPasswordAlgorithm.PBKDF2_SHA256, 600_000)
    val ARGON2ID = Profile("1|argon2id|65536|3|1", LocalPasswordAlgorithm.ARGON2ID, 3, 65_536, 1)

    fun forAlgorithm(algorithm: LocalPasswordAlgorithm): Profile = when (algorithm) {
        LocalPasswordAlgorithm.PBKDF2_SHA256 -> PBKDF2_600K
        LocalPasswordAlgorithm.ARGON2ID -> ARGON2ID
    }

    fun fromMetadata(value: String?): Profile = when (value) {
        null -> LEGACY
        PBKDF2_600K.encoded -> PBKDF2_600K
        ARGON2ID.encoded -> ARGON2ID
        else -> throw IllegalArgumentException("Unsupported local password protection")
    }

    fun fromValues(values: Map<String, *>): Profile {
        val value = values[METADATA_KEY]
        require(value == null || value is String) { "Invalid local password protection" }
        require(!values.containsKey(METADATA_KEY) || value != null) { "Missing local password profile" }
        return fromMetadata(value as String?)
    }

    fun mdkAad(profile: Profile): ByteArray? = profile.encoded?.let {
        "monica-local-mdk:$it".toByteArray(Charsets.UTF_8)
    }

    fun derive(password: String, salt: ByteArray, profile: Profile): ByteArray {
        require(profile == fromMetadata(profile.encoded)) { "Unsupported local password parameters" }
        require(salt.size in 16..32) { "Invalid password salt" }
        if (profile.algorithm == LocalPasswordAlgorithm.PBKDF2_SHA256) {
            // The existing JNI primitive computes the same PBKDF2 bytes with the same cost.
            // Preserve Java's historical behavior for malformed UTF-16 instead of silently
            // substituting a different password during conversion to native UTF-8 bytes.
            if (wellFormed(password)) {
                val bytes = password.toByteArray(Charsets.UTF_8)
                try {
                    val native = RustBitwardenKdfCore.derivePbkdf2Sha256(bytes, salt, profile.iterations)
                    if (native != null) {
                        if (native.size == 32) return native
                        native.fill(0)
                        error("Invalid PBKDF2 result")
                    }
                } finally { bytes.fill(0) }
            }
            val chars = password.toCharArray()
            val spec = PBEKeySpec(chars, salt, profile.iterations, 256)
            chars.fill('\u0000')
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        }
        require(wellFormed(password)) { "Invalid password encoding" }
        val bytes = password.toByteArray(Charsets.UTF_8)
        try {
            // This JNI facade is a byte-oriented primitive: no Bitwarden email or salt normalization.
            val native = RustBitwardenKdfCore.deriveArgon2id(
                bytes, salt, profile.iterations, profile.memoryKiB, profile.parallelism,
            )
            if (native != null) {
                if (native.size == 32) return native
                native.fill(0)
                error("Invalid Argon2 result")
            }
            BitwardenArgon2MemoryGuard.requireCanRun(profile.memoryKiB / 1024)
            val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt).withIterations(profile.iterations)
                .withMemoryAsKB(profile.memoryKiB).withParallelism(profile.parallelism).build()
            val output = ByteArray(32)
            try {
                val generator = Argon2BytesGenerator()
                generator.init(parameters)
                generator.generateBytes(bytes, output)
                return output
            } catch (error: OutOfMemoryError) {
                output.fill(0)
                throw IllegalStateException("Insufficient memory for local password protection", error)
            } finally { parameters.clear() }
        } finally { bytes.fill(0) }
    }

    private fun wellFormed(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (Character.isHighSurrogate(char)) {
                if (index == value.length || !Character.isLowSurrogate(value[index++])) return false
            } else if (Character.isLowSurrogate(char)) return false
        }
        return true
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(value: String, bytes: Int): ByteArray {
        require(value.length == bytes * 2 && value.all { it in "0123456789abcdefABCDEF" }) {
            "Invalid password material"
        }
        return ByteArray(bytes) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
