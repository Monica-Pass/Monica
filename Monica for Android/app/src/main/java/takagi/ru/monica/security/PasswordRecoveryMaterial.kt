package takagi.ru.monica.security

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Short-lived, password-authenticated material. It never opens or creates a device key. */
internal class PasswordRecoveryMaterial(
    values: Map<String, Any>,
    private val key: ByteArray,
    private val recoveryKey: ByteArray,
    private val sourceCurrent: () -> Unit,
) : AutoCloseable {
    private var closed = false
    private val values = values.toMutableMap()

    init { require(key.size == 32 && recoveryKey.size == 32) }

    @Synchronized
    fun checkCurrent() {
        check(!closed) { "Recovery material has been closed" }
        sourceCurrent()
    }

    @Synchronized
    internal fun preferenceSnapshot(): Map<String, Any> {
        checkCurrent()
        return values.toMap()
    }

    @Synchronized
    internal fun copyRecoveryKey(): ByteArray {
        checkCurrent()
        return recoveryKey.copyOf()
    }

    /** Strict MDK decoding: device ciphertext and unprefixed legacy data are not plaintext. */
    @Synchronized
    fun decryptMdk(value: String): String {
        checkCurrent()
        require(LocalCipherEnvelope.format(value) == LocalCipherEnvelope.Format.MDK) {
            "This value is not protected by the recovered data key"
        }
        val bytes = Base64.getDecoder().decode(value.trim().removePrefix("MDK|"))
        require(bytes.size >= 28) { "Incomplete data envelope" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, 0, 12))
        val plain = cipher.doFinal(bytes, 12, bytes.size - 12)
        return try { checkCurrent(); plain.toString(Charsets.UTF_8) } finally { plain.fill(0) }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            key.fill(0)
            recoveryKey.fill(0)
            values.clear()
        }
    }
}
