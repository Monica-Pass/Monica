package takagi.ru.monica.security

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic keys and isolated preferences only. Detection must match the real reader. */
@RunWith(AndroidJUnit4::class)
class PortableCipherMigrationCoverageTest {
    private fun legacy(f: RecoveryTestFixture, plain: String): String =
        SecurityManager::class.java.getDeclaredMethod("encryptDataCompat", String::class.java)
            .apply { isAccessible = true }.invoke(f.manager, plain) as String

    private fun database(context: Context): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE password_entries(id INTEGER PRIMARY KEY, password TEXT, revision INTEGER)")
                        db.execSQL("CREATE TABLE secure_items(id INTEGER PRIMARY KEY, itemData TEXT, revision INTEGER)")
                        db.execSQL("CREATE TABLE local_mdbx_databases(id INTEGER PRIMARY KEY, encrypted_password TEXT)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )

    @Test fun readableWhitespaceWrappedCiphertextsAreNotOmittedBySqlSelection() = runBlocking {
        RecoveryTestFixture().use { f ->
            database(f.context).use { helper ->
                val db = helper.writableDatabase
                val envelopes = listOf(" \r\n", "\t", "\u00a0\u2003").map {
                    it + legacy(f, "retained secret") + it
                }
                envelopes.forEachIndexed { index, value ->
                    assertEquals("retained secret", f.manager.decryptData(value))
                    db.execSQL("INSERT INTO password_entries VALUES(?, ?, 71)", arrayOf<Any>(index + 1, value))
                }
                val untouched = listOf("  plain text  ", " \tC2|corrupt \n", "V9|unknown")
                untouched.forEachIndexed { index, value ->
                    db.execSQL("INSERT INTO password_entries VALUES(?, ?, 72)", arrayOf<Any>(index + 10, value))
                }
                assertEquals(envelopes.size, PortableLocalCipherMigration.migrate(
                    db, f.manager, PortableLocalCipherMigration.vaultColumns))
                db.query("SELECT password, revision FROM password_entries ORDER BY id").use { cursor ->
                    repeat(envelopes.size) {
                        assertTrue(cursor.moveToNext())
                        assertTrue(cursor.getString(0).startsWith("MDK|"))
                        assertEquals("retained secret", f.manager.decryptData(cursor.getString(0)))
                        assertEquals(71, cursor.getInt(1))
                    }
                    untouched.forEach {
                        assertTrue(cursor.moveToNext()); assertEquals(it, cursor.getString(0))
                        assertEquals(72, cursor.getInt(1))
                    }
                }
                assertEquals(0, PortableLocalCipherMigration.migrate(db, f.manager,
                    PortableLocalCipherMigration.vaultColumns))
            }
        }
    }

    @Test fun nestedAndTaggedCiphertextsPreserveTheirContainers() = runBlocking {
        RecoveryTestFixture().use { f ->
            database(f.context).use { helper ->
                val db = helper.writableDatabase
                val wrapped = "\u2003" + legacy(f, "nested secret") + "\n"
                val json = JSONObject().put("secret", wrapped).put("unknown", "  retain exactly  ")
                val outer = f.manager.encryptData(json.toString())
                db.execSQL("INSERT INTO secure_items VALUES(1, ?, 81)", arrayOf(outer))
                val tag = "mdbx2-device-key:v1:"
                db.execSQL("INSERT INTO local_mdbx_databases VALUES(1, ?)", arrayOf(tag + wrapped))
                assertEquals(2, PortableLocalCipherMigration.migrate(db, f.manager,
                    PortableLocalCipherMigration.vaultColumns))
                db.query("SELECT itemData, revision FROM secure_items").use {
                    assertTrue(it.moveToFirst())
                    val restored = JSONObject(f.manager.decryptData(it.getString(0)))
                    assertTrue(restored.getString("secret").startsWith("MDK|"))
                    assertEquals("nested secret", f.manager.decryptData(restored.getString("secret")))
                    assertEquals("  retain exactly  ", restored.getString("unknown"))
                    assertEquals(81, it.getInt(1))
                }
                db.query("SELECT encrypted_password FROM local_mdbx_databases").use {
                    assertTrue(it.moveToFirst()); assertTrue(it.getString(0).startsWith(tag + "MDK|"))
                    assertEquals("nested secret", f.manager.decryptData(it.getString(0).removePrefix(tag)))
                }
            }
        }
    }

    @Test fun preferenceMigrationsUseTheSameCiphertextDetectionAsTheReader() = runBlocking {
        RecoveryTestFixture().use { f ->
            val old = " \t" + legacy(f, "saved secret") + "\r\n"
            val prefs = f.context.getSharedPreferences("bitwarden_offline_secret_cache", Context.MODE_PRIVATE)
            assertTrue(prefs.edit().putString("secret_42", old).putString("unrelated", old).commit())
            assertEquals(1, PortablePreferenceCipherMigration.run(f.context, f.manager))
            assertTrue(requireNotNull(prefs.getString("secret_42", null)).startsWith("MDK|"))
            assertEquals(old, prefs.getString("unrelated", null))
            assertEquals("saved secret", f.manager.decryptData(requireNotNull(prefs.getString("secret_42", null))))

            assertTrue(f.recovery.wrap(f.prefs).edit().putString("synthetic_protected_secret", old).commit())
            f.manager.migrateProtectedDeviceCiphertexts()
            val protected = requireNotNull(f.prefs.getString("synthetic_protected_secret", null))
            assertTrue(protected.startsWith("MDK|"))
            assertEquals("saved secret", f.manager.decryptData(protected))
        }
    }
}
