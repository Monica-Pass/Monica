package takagi.ru.monica.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

/** Reads exercise real AndroidKeyStore keys; only uniquely named fixture aliases are deleted. */
class SecurityKeyReadInstrumentedTest {
    @Test fun missingCompatKeyIsNotGeneratedByDecryptingOldCiphertext() = missingDataKey("KEY_ALIAS_DATA_COMPAT", "C2|")
    @Test fun missingAuthenticatedKeyIsNotGeneratedByDecryptingOldCiphertext() = missingDataKey("KEY_ALIAS_DATA", "V2|")

    private fun missingDataKey(field: String, prefix: String) {
        Fixture().use { f ->
            val alias = f.alias(field)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, f.key(alias))
            val ciphertext = prefix + Base64.encodeToString(cipher.iv + cipher.doFinal("synthetic-old-value".toByteArray()), Base64.NO_WRAP)
            assertEquals("synthetic-old-value", f.manager.decryptData(ciphertext))
            f.keystore.deleteEntry(alias)
            SecurityManager.clearRuntimeUnlockCache()
            val before = f.raw.all.toMap()
            assertTrue("A missing key must be reported as unavailable before any generation is attempted",
                runCatching { f.manager.decryptData(ciphertext) }.exceptionOrNull() is java.security.UnrecoverableKeyException)
            assertFalse("Reading old ciphertext must not generate a replacement key", f.keystore.containsAlias(alias))
            assertEquals(before, f.raw.all)
        }
    }

    @Test fun damagedMdkWrapperIsRetainedAndPasswordCanStillUnlockOriginalData() {
        Fixture().use { f ->
            assertTrue(f.manager.setMasterPassword("Synthetic-key-read-323!"))
            val data = f.manager.encryptData("synthetic-password-recoverable-content")
            val prefs = f.preferences()
            val wrapper = checkNotNull(prefs.getString("mdk_keystore_blob", null))
            assertTrue(wrapper.startsWith("CP|"))
            val bytes = Base64.decode(wrapper.removePrefix("CP|"), Base64.NO_WRAP)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            assertTrue(prefs.edit().putString("mdk_keystore_blob", "CP|" + Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
            SecurityManager.clearRuntimeUnlockCache()
            val before = f.raw.all.toMap()
            assertNotNull(runCatching { f.manager.decryptData(data) }.exceptionOrNull())
            assertEquals("An unreadable wrapper must remain available for recovery", before, f.raw.all)
            assertTrue(f.manager.requiresPasswordReentryForWrapperRebuild())
            assertTrue(f.manager.unlockVaultWithPassword("Synthetic-key-read-323!"))
            assertEquals("synthetic-password-recoverable-content", f.manager.decryptData(data))
            assertFalse(f.manager.requiresPasswordReentryForWrapperRebuild())
        }
    }

    @Test fun missingMdkWrapperAliasesAreNotRebuiltOrRemovedByReads() {
        for (field in listOf("KEY_ALIAS_DATA", "KEY_ALIAS_DATA_COMPAT")) Fixture().use { f ->
            val alias = f.alias(field)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, f.key(alias))
            val prefix = if (field == "KEY_ALIAS_DATA") "AU|" else "CP|"
            val wrapper = prefix + Base64.encodeToString(cipher.iv + cipher.doFinal(ByteArray(32) { 7 }), Base64.NO_WRAP)
            assertTrue(f.preferences().edit().putBoolean("mdk_ready", true).putString("mdk_keystore_blob", wrapper).commit())
            f.keystore.deleteEntry(alias)
            SecurityManager.clearRuntimeUnlockCache()
            val before = f.raw.all.toMap()
            assertNotNull(runCatching { f.manager.decryptData("MDK|" + Base64.encodeToString(ByteArray(28), Base64.NO_WRAP)) }.exceptionOrNull())
            assertFalse(f.keystore.containsAlias(alias))
            assertEquals(before, f.raw.all)
        }
    }

    private class Fixture : AutoCloseable {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "key-read-fixture-${UUID.randomUUID()}-"
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply { dataDir = root.absolutePath }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences(prefix + name, mode)
        }
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val manager = SecurityManager(context)
        init {
            SecurityManager.clearRuntimeUnlockCache()
            for (field in listOf("KEY_ALIAS_DATA", "KEY_ALIAS_DATA_COMPAT")) {
                val declared = SecurityManager::class.java.getDeclaredField(field).apply { isAccessible = true }
                declared.set(manager, alias(field))
                assertEquals(alias(field), declared.get(manager))
            }
        }
        val raw get() = context.getSharedPreferences(SecurePreferencesStore.MONICA, Context.MODE_PRIVATE)
        fun alias(field: String) = prefix + field
        fun preferences() = SecurePreferencesStore.open(context, SecurePreferencesStore.MONICA).preferences
        fun key(alias: String) = KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setUserAuthenticationRequired(false).build())
        }.generateKey()
        override fun close() {
            SecurityManager.clearRuntimeUnlockCache()
            for (field in listOf("KEY_ALIAS_DATA", "KEY_ALIAS_DATA_COMPAT")) if (keystore.containsAlias(alias(field))) keystore.deleteEntry(alias(field))
            base.deleteSharedPreferences(prefix + SecurePreferencesStore.MONICA)
            base.deleteSharedPreferences(prefix + SecurePreferencesStore.BITWARDEN)
            check(root.parentFile == base.cacheDir && root.name.startsWith("key-read-fixture-"))
            root.deleteRecursively()
        }
    }
}
