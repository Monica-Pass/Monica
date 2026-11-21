package takagi.ru.monica.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.UnrecoverableKeyException
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Append-only device bindings. Legacy field-encryption aliases must never be replaced. */
internal object BiometricMdkBinding {
    const val ACTIVE = "mdk_biometric_active_v1"
    private const val SLOT = "mdk_biometric_binding_v1_"
    private const val ALIAS = "monica_biometric_mdk_v1_"

    data class Binding(val id: String, val authenticated: Boolean, val payload: String) {
        val alias: String get() = ALIAS + id
        val updates: Map<String, String> get() = mapOf(
            SLOT + id to (if (authenticated) "AU|" else "CP|") + payload,
            ACTIVE to id,
        )
    }

    fun selected(values: Map<String, *>): Binding? {
        if (!values.containsKey(ACTIVE)) return null
        val id = values[ACTIVE] as? String ?: error("Invalid biometric binding selector")
        require(UUID.fromString(id).toString() == id) { "Invalid biometric binding selector" }
        val raw = values[SLOT + id] as? String ?: error("Missing biometric binding")
        require(raw.startsWith("AU|") || raw.startsWith("CP|")) { "Unknown biometric binding policy" }
        return Binding(id, raw.startsWith("AU|"), raw.substring(3))
    }

    /** Also binds absent/malformed selected material, without falling back to an older slot. */
    fun snapshot(values: Map<String, *>): Map<String, Any?> = values.filterKeys {
        it == ACTIVE || it.startsWith(SLOT) || it == "mdk_keystore_blob" || it == "mdk_ready"
    }

    fun read(binding: Binding): ByteArray = decrypt(binding.alias, binding.payload)

    fun decrypt(alias: String, payload: String): ByteArray {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        require(bytes.size == 60) { "Invalid MDK binding" }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw UnrecoverableKeyException("Existing biometric key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
        return cipher.doFinal(bytes, 12, bytes.size - 12).also { check(it.size == 32) }
    }

    /** Caller owns this UUID before key generation, so even partial generation can be cleaned. */
    fun create(id: String, mdk: ByteArray, authenticated: Boolean, onGenerationStart: () -> Unit, ensureActive: () -> Unit): Binding {
        require(mdk.size == 32 && UUID.fromString(id).toString() == id)
        val alias = ALIAS + id
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        check(!store.containsAlias(alias)) { "Biometric candidate already exists" }
        ensureActive()
        onGenerationStart()
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(authenticated)
        if (authenticated) {
            // Preserve the existing AUTH wrapper's policy. Never retry as COMPAT on failure.
            spec.setUserAuthenticationValidityDurationSeconds(300)
            spec.setInvalidatedByBiometricEnrollment(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(spec.build())
        val key: SecretKey = generator.generateKey()
        ensureActive()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val candidate = Binding(id, authenticated, Base64.encodeToString(cipher.iv + cipher.doFinal(mdk), Base64.NO_WRAP))
        ensureActive()
        // Reopen by alias, independent of the generated key handle and the runtime MDK cache.
        val reread = read(candidate)
        try { check(MessageDigest.isEqual(mdk, reread)) { "Biometric candidate verification failed" } }
        finally { reread.fill(0) }
        ensureActive()
        return candidate
    }

    fun discardUncommitted(id: String) {
        require(UUID.fromString(id).toString() == id)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS + id)
    }
}
