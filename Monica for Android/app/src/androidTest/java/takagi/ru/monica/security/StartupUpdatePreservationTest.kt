package takagi.ru.monica.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest

/** Host runs seed on the checkpoint APK, replaces that APK with install -r, then runs verify. */
class StartupUpdatePreservationTest {
    @Test fun seedCheckpointBeforeCoveringInstall() {
        val f = Fixture()
        check(!f.expected.exists()) { "Never overwrite existing update evidence" }
        f.root.mkdirs()
        val manager = f.manager()
        check(!manager.isMasterPasswordSet())
        val compat = manager.encryptDataLegacyCompat("synthetic-old-compat-secret")
        assertTrue(compat.startsWith("C2|"))
        assertTrue(manager.setMasterPassword(f.password))
        val mdk = manager.encryptData("synthetic-old-mdk-secret")
        assertTrue(mdk.startsWith("MDK|"))
        manager.putProtectedString("update-fixture-passkey", "synthetic-pkcs8-material")
        SQLiteDatabase.openOrCreateDatabase(f.database, null).use { db ->
            db.execSQL("CREATE TABLE secrets (kind TEXT PRIMARY KEY, ciphertext TEXT NOT NULL)")
            db.execSQL("INSERT INTO secrets VALUES (?, ?)", arrayOf("C2", compat))
            db.execSQL("INSERT INTO secrets VALUES (?, ?)", arrayOf("MDK", mdk))
        }
        f.attachment.writeText(manager.encryptData("synthetic-attachment-content"))
        f.expected.writeText(JSONObject().put("raw", f.rawDigest()).put("database", f.digest(f.database.readBytes()))
            .put("attachment", f.digest(f.attachment.readBytes())).put("recovery", f.digest(f.envelope.readBytes()))
            .put("seedVersion", takagi.ru.monica.BuildConfig.VERSION_NAME).toString())
        SecurityManager.clearRuntimeUnlockCache()
    }

    @Test fun verifyAfterCoveringInstallAndPasswordReentry() = runBlocking {
        val f = Fixture()
        val expected = JSONObject(f.expected.readText())
        SecurityManager.clearRuntimeUnlockCache()
        val prepared = SecureStorageStartup.prepareWithRetry(f.context)
        assertTrue(prepared is SecureStartupResult.Ready)
        assertEquals(expected.getString("raw"), f.rawDigest())
        assertEquals(expected.getString("database"), f.digest(f.database.readBytes()))
        assertEquals(expected.getString("attachment"), f.digest(f.attachment.readBytes()))
        assertEquals(expected.getString("recovery"), f.digest(f.envelope.readBytes()))
        // Exercise the existing 1.0.322 -> current version lock guard without changing real settings.
        f.context.getSharedPreferences("monica_app_update_guard", Context.MODE_PRIVATE).edit()
            .putLong("last_version_code", 12).putString("last_version_name", "1.0.322").commit()
        SessionManager.markUnlocked()
        AppUpdateSecurityGuard.enforceLockIfAppUpdated(f.context, "synthetic_covering_install")
        assertFalse(SessionManager.isUnlocked.value)
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
        assertEquals(expected.getString("raw"), f.rawDigest())
        val manager = f.configure((prepared as SecureStartupResult.Ready).manager)
        assertTrue(manager.unlockVaultWithPassword(f.password))
        SQLiteDatabase.openDatabase(f.database.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT kind, ciphertext FROM secrets ORDER BY kind", null).use { rows ->
                var count = 0
                while (rows.moveToNext()) {
                    val expectedPlain = if (rows.getString(0) == "C2") "synthetic-old-compat-secret" else "synthetic-old-mdk-secret"
                    assertEquals(expectedPlain, manager.decryptData(rows.getString(1)))
                    count++
                }
                assertEquals(2, count)
            }
        }
        assertEquals("synthetic-attachment-content", manager.decryptData(f.attachment.readText()))
        assertEquals("synthetic-pkcs8-material", manager.getProtectedString("update-fixture-passkey"))
        assertEquals(expected.getString("database"), f.digest(f.database.readBytes()))
        assertEquals(expected.getString("attachment"), f.digest(f.attachment.readBytes()))
        SecurityManager.clearRuntimeUnlockCache()
    }

    @Test fun cleanupOnlyOwnedUpdateFixture() {
        val f = Fixture()
        SecurityManager.clearRuntimeUnlockCache()
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (field in listOf("KEY_ALIAS_DATA", "KEY_ALIAS_DATA_COMPAT")) if (keystore.containsAlias(f.prefix + field)) keystore.deleteEntry(f.prefix + field)
        for (name in listOf(SecurePreferencesStore.MONICA, SecurePreferencesStore.BITWARDEN, "monica_app_update_guard")) f.base.deleteSharedPreferences(f.prefix + name)
        check(f.root.parentFile == f.base.cacheDir && f.root.name == "startup-update-fixture-323")
        f.root.deleteRecursively()
    }

    private class Fixture {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "startup-update-fixture-323-"
        val root = File(base.cacheDir, "startup-update-fixture-323")
        val database = File(root, "vault.db")
        val attachment = File(root, "attachment.ciphertext")
        val expected = File(root, "expected.json")
        val envelope = File(root, "files/local-vault-recovery/recovery.json")
        val password = "Synthetic-covering-update-323!"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply { dataDir = root.absolutePath }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences(prefix + name, mode)
        }
        fun configure(manager: SecurityManager): SecurityManager = manager.also {
            for (field in listOf("KEY_ALIAS_DATA", "KEY_ALIAS_DATA_COMPAT")) {
                val declared = SecurityManager::class.java.getDeclaredField(field).apply { isAccessible = true }
                declared.set(it, prefix + field)
                assertEquals(prefix + field, declared.get(it))
            }
        }
        fun manager() = configure(SecurityManager(context))
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun rawDigest() = digest(context.getSharedPreferences(SecurePreferencesStore.MONICA, Context.MODE_PRIVATE).all.toSortedMap().toString().toByteArray())
    }
}
