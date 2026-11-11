package takagi.ru.monica.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*

/** Only private synthetic stores and aliases; never overwrites the installed application's vault. */
internal class RecoveryTestFixture : AutoCloseable {
    val base = InstrumentationRegistry.getInstrumentation().targetContext
    val prefix = "recovery-327-${UUID.randomUUID()}-"
    val root = File(base.cacheDir, prefix).apply { check(mkdirs()) }
    private val names = linkedSetOf<String>()
    val alias = prefix + "master"
    val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply { dataDir = root.absolutePath }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names += name
            return base.getSharedPreferences(prefix + name, mode)
        }
    }
    val recovery = LocalVaultRecovery(context)
    val password = "Synthetic-old-327"
    val replacement = "Synthetic-new-327"
    private val opened = SecurePreferencesStore.openRaw(context, SecurePreferencesStore.MONICA, alias)
    val prefs = opened.preferences
    val manager = SecurityManager.fromPrepared(context, opened.copy(preferences = recovery.wrap(prefs))).also {
        for (field in listOf("KEY_ALIAS_DATA", "KEY_ALIAS_DATA_COMPAT")) {
            SecurityManager::class.java.getDeclaredField(field).apply { isAccessible = true }.set(it, prefix + field)
        }
    }
    val record: String
    val mdk: ByteArray
    init {
        SessionManager.markLocked()
        assertTrue(manager.setMasterPassword(password))
        SessionManager.markUnlocked()
        mdk = LocalPasswordCredentials.unwrap(password, prefs.all)
        record = manager.encryptData("Synthetic retained password/OTP/证件")
        assertTrue(record.startsWith("MDK|"))
        fun oldHash(answer: String) = PasswordKdf.hex(MessageDigest.getInstance("SHA-256").digest(answer.toByteArray()))
        assertTrue(manager.restoreSecurityQuestionsFromBackup(SecurityQuestionsBackupSnapshot(
            1, null, oldHash("answer one"), 2, null, oldHash("answer two"))))
    }
    fun assertRecord(password: String) {
        assertTrue(manager.unlockVaultWithPassword(password))
        assertEquals("Synthetic retained password/OTP/证件", manager.decryptData(record))
        val unwrapped = LocalPasswordCredentials.unwrap(password, prefs.all)
        try { assertArrayEquals(mdk, unwrapped) } finally { unwrapped.fill(0) }
    }
    override fun close() {
        SessionManager.markLocked()
        PreparedSecureStore.invalidate()
        mdk.fill(0)
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        listOf(alias, prefix + "KEY_ALIAS_DATA", prefix + "KEY_ALIAS_DATA_COMPAT").forEach {
            if (keystore.containsAlias(it)) keystore.deleteEntry(it)
        }
        names.forEach { base.deleteSharedPreferences(prefix + it) }
        check(root.parentFile == base.cacheDir && root.name.startsWith("recovery-327-"))
        root.deleteRecursively()
    }
}
