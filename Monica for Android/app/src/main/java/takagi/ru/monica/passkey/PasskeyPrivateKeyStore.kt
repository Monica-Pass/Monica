package takagi.ru.monica.passkey

import android.content.Context
import android.util.Base64
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.security.SecurityManager
import java.security.MessageDigest

/**
 * Stores exportable passkey private-key material outside Room.
 *
 * Older Monica builds wrote PKCS#8 private keys directly into
 * PasskeyEntry.privateKeyAlias. New writes keep only a stable, non-secret
 * reference in Room and move the key material into protected preferences.
 */
object PasskeyPrivateKeyStore {
    private const val REF_PREFIX = "monica-passkey-key-ref-v1:"
    private const val STORAGE_PREFIX = "passkey_private_key_v1_"

    fun isProtectedReference(value: String?): Boolean {
        return value?.trim().orEmpty().startsWith(REF_PREFIX)
    }

    fun protectForStorage(
        context: Context,
        credentialId: String,
        rpId: String,
        userId: String,
        keyMaterial: String?
    ): String {
        val normalized = keyMaterial?.trim().orEmpty()
        if (normalized.isBlank() || isProtectedReference(normalized)) return normalized

        val pkcs8Base64 = PasskeyPrivateKeySupport.exportPkcs8Base64(normalized)
            ?: return normalized
        val storageKey = storageKeyFor(credentialId, rpId, userId, pkcs8Base64)
        SecurityManager(context.applicationContext).putProtectedString(storageKey, pkcs8Base64)
        val reference = REF_PREFIX + storageKey
        PasskeyPortabilityCache.shared.invalidate(reference)
        return reference
    }

    fun protectPasskey(context: Context, passkey: PasskeyEntry): PasskeyEntry {
        val protectedKey = protectForStorage(
            context = context,
            credentialId = passkey.credentialId,
            rpId = passkey.rpId,
            userId = passkey.userId,
            keyMaterial = passkey.privateKeyAlias
        )
        return if (protectedKey == passkey.privateKeyAlias) passkey else passkey.copy(privateKeyAlias = protectedKey)
    }

    fun resolve(context: Context, keyReferenceOrMaterial: String?): String? {
        return resolve(SecurityManager(context.applicationContext), keyReferenceOrMaterial)
    }

    fun resolve(securityManager: SecurityManager, keyReferenceOrMaterial: String?): String? {
        val value = keyReferenceOrMaterial?.trim().orEmpty()
        if (value.isBlank()) return null
        if (!isProtectedReference(value)) return value
        val storageKey = value.removePrefix(REF_PREFIX)
        return securityManager.getProtectedString(storageKey)
    }

    fun normalizeForBitwardenUpload(context: Context, keyReferenceOrMaterial: String?): String? {
        return PasskeyPrivateKeySupport.normalizeForBitwardenUpload(
            resolve(context, keyReferenceOrMaterial)
        )
    }

    fun hasBitwardenCompatiblePrivateKey(context: Context, keyReferenceOrMaterial: String?): Boolean {
        return PasskeyPrivateKeySupport.hasBitwardenCompatiblePrivateKey(
            resolve(context, keyReferenceOrMaterial)
        )
    }

    fun hasUsablePrivateKey(context: Context, keyReferenceOrMaterial: String?): Boolean {
        val resolved = runCatching {
            resolve(SecurityManager(context.applicationContext), keyReferenceOrMaterial)
        }.getOrNull()
        return PasskeyPrivateKeySupport.hasUsablePrivateKey(resolved)
    }

    fun hasUsablePrivateKey(
        securityManager: SecurityManager,
        keyReferenceOrMaterial: String?,
    ): Boolean {
        val resolved = runCatching {
            resolve(securityManager, keyReferenceOrMaterial)
        }.getOrNull()
        return PasskeyPrivateKeySupport.hasUsablePrivateKey(resolved)
    }

    fun exportPem(context: Context, keyReferenceOrMaterial: String?): String? {
        return PasskeyPrivateKeySupport.exportPem(resolve(context, keyReferenceOrMaterial))
    }

    fun removeIfProtectedReference(context: Context, keyReferenceOrMaterial: String?) {
        val value = keyReferenceOrMaterial?.trim().orEmpty()
        if (!isProtectedReference(value)) return
        SecurityManager(context.applicationContext).removeProtectedString(value.removePrefix(REF_PREFIX))
        PasskeyPortabilityCache.shared.invalidate(value)
    }

    private fun storageKeyFor(
        credentialId: String,
        rpId: String,
        userId: String,
        pkcs8Base64: String
    ): String {
        val digestSource = listOf(credentialId, rpId, userId, shortSha(pkcs8Base64)).joinToString("|")
        return STORAGE_PREFIX + shortSha(digestSource, bytes = 16)
    }

    private fun shortSha(value: String, bytes: Int = 12): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(bytes).joinToString("") { byte -> "%02x".format(byte) }
    }
}
