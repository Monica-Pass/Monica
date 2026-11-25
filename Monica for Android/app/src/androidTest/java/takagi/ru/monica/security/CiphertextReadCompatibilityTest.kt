package takagi.ru.monica.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.*
import org.junit.Test

class CiphertextReadCompatibilityTest {
    private val plain = "  Synthetic retained password\nwith whitespace  "

    private fun payload(f: RecoveryTestFixture, prefix: String): String {
        if (prefix == "MDK|") return f.manager.encryptData(plain)
        val alias = f.prefix + if (prefix == "C2|") "KEY_ALIAS_DATA_COMPAT" else "KEY_ALIAS_DATA"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return prefix + Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    @Test fun paddedMdkRemainsReadable() = readPadded("MDK|")
    @Test fun paddedCompatRemainsReadable() = readPadded("C2|")
    @Test fun paddedV2RemainsReadable() = readPadded("V2|")

    private fun readPadded(prefix: String) {
        RecoveryTestFixture().use { f ->
            val original = payload(f, prefix)
            val before = f.prefs.all.toMap()
            assertEquals(plain, f.manager.decryptData(original))
            val padded = " \r\n\t$original\n "
            assertTrue(f.manager.looksLikeMonicaCiphertext(padded))
            assertEquals(plain, f.manager.decryptDataIfMonicaCiphertext(padded))
            assertEquals(plain, f.manager.decryptData(padded))
            assertEquals(before, f.prefs.all)
        }
    }

    @Test fun paddedDamagedCiphertextMustFailInsteadOfBecomingPlaintext() {
        RecoveryTestFixture().use { f ->
            for (prefix in listOf("MDK|", "C2|", "V2|")) {
                val original = payload(f, prefix)
                val bytes = Base64.decode(original.removePrefix(prefix), Base64.NO_WRAP)
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                val damaged = " \n" + prefix + Base64.encodeToString(bytes, Base64.NO_WRAP) + "\t "
                val before = f.prefs.all.toMap()
                val failure = runCatching { f.manager.decryptDataIfMonicaCiphertext(damaged) }.exceptionOrNull()
                assertTrue("$prefix: authentication failure must remain a failure", failure is AEADBadTagException)
                assertEquals(before, f.prefs.all)
                assertEquals(plain, f.manager.decryptData(original))
            }
        }
    }

    @Test fun ordinaryPlaintextWhitespaceIsPreserved() {
        RecoveryTestFixture().use { f ->
            assertEquals(plain, f.manager.decryptDataIfMonicaCiphertext(plain))
            assertEquals(" \t\n ", f.manager.decryptDataIfMonicaCiphertext(" \t\n "))
        }
    }
}
