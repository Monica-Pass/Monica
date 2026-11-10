package takagi.ru.monica.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Builds a complete credential replacement in memory; never writes a store or generates a new MDK. */
internal object LocalPasswordCredentials {
    const val HASH = "master_password_hash"
    const val HASH_SALT = "master_password_salt"
    const val BLOB = "mdk_password_blob"
    const val BLOB_SALT = "mdk_password_salt"
    val keys = listOf(HASH, HASH_SALT, BLOB, BLOB_SALT, PasswordKdf.METADATA_KEY)

    fun snapshot(values: Map<String, *>): Map<String, Any?> = keys.associateWith { values[it] }

    fun matches(first: Map<String, *>, second: Map<String, *>): Boolean =
        keys.all { first[it] == second[it] }

    fun verify(password: String, values: Map<String, *>): Boolean {
        val profile = PasswordKdf.fromValues(values)
        val stored = PasswordKdf.unhex(values[HASH] as? String ?: return false, 32)
        val salt = PasswordKdf.unhex(values[HASH_SALT] as? String ?: return false, 16)
        val actual = PasswordKdf.derive(password, salt, profile)
        return try { MessageDigest.isEqual(stored, actual) }
        finally { actual.fill(0); stored.fill(0); salt.fill(0) }
    }

    fun unwrap(password: String, values: Map<String, *>): ByteArray {
        val profile = PasswordKdf.fromValues(values)
        val salt = PasswordKdf.unhex(values[BLOB_SALT] as String, 32)
        val key = PasswordKdf.derive(password, salt, profile)
        return try { unwrapWithKey(key, values[BLOB] as String, profile) }
        finally { key.fill(0); salt.fill(0) }
    }

    fun create(password: String, mdk: ByteArray, profile: PasswordKdf.Profile): Map<String, Any> {
        require(mdk.size == 32 && profile != PasswordKdf.LEGACY)
        val salt = ByteArray(32).also(SecureRandom()::nextBytes)
        val hashSalt = ByteArray(16).also(SecureRandom()::nextBytes)
        val key = PasswordKdf.derive(password, salt, profile)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.updateAAD(checkNotNull(PasswordKdf.mdkAad(profile)))
            check(cipher.iv.size == 12)
            val blob = Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(mdk))
            val checked = unwrapWithKey(key, blob, profile)
            try { check(MessageDigest.isEqual(mdk, checked)) }
            finally { checked.fill(0) }
            val hash = PasswordKdf.derive(password, hashSalt, profile)
            return try {
                mapOf(HASH to PasswordKdf.hex(hash), HASH_SALT to PasswordKdf.hex(hashSalt),
                    BLOB to blob, BLOB_SALT to PasswordKdf.hex(salt),
                    PasswordKdf.METADATA_KEY to checkNotNull(profile.encoded))
            } finally { hash.fill(0) }
        } finally { key.fill(0); salt.fill(0); hashSalt.fill(0) }
    }

    private fun unwrapWithKey(key: ByteArray, blob: String, profile: PasswordKdf.Profile): ByteArray {
        require(blob.length == 80) { "Invalid password wrapper" }
        val bytes = Base64.getDecoder().decode(blob)
        require(bytes.size == 60) { "Invalid password wrapper" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, 0, 12))
        PasswordKdf.mdkAad(profile)?.let(cipher::updateAAD)
        return cipher.doFinal(bytes, 12, 48).also { require(it.size == 32) }
    }
}
