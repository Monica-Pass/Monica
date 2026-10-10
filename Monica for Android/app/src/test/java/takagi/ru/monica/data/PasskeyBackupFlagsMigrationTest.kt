package takagi.ru.monica.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import takagi.ru.monica.passkey.PasskeyBackupFlags

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PasskeyBackupFlagsMigrationTest {
    @Test fun migrationAddsOnlyNullableFlagsAndRetainsEveryLegacyValue() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration
            .builder(RuntimeEnvironment.getApplication()).name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(79) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        try {
            val db = helper.writableDatabase
            db.execSQL("CREATE TABLE passkeys (id INTEGER PRIMARY KEY, credential_id TEXT, private_key_alias TEXT, sign_count INTEGER, is_backed_up INTEGER)")
            db.execSQL("INSERT INTO passkeys VALUES (42, 'original-id', 'protected-key-reference', 41, 0)")
            db.execSQL("INSERT INTO passkeys VALUES (43, 'other-original-id', 'other-key-reference', 0, 1)")
            PasswordDatabase.MIGRATION_79_80.migrate(db)
            PasswordDatabase.MIGRATION_79_80.migrate(db)
            db.query("SELECT * FROM passkeys ORDER BY id").use { cursor ->
                assertEquals(2, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals(42L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
                assertEquals("original-id", cursor.getString(cursor.getColumnIndexOrThrow("credential_id")))
                assertEquals("protected-key-reference", cursor.getString(cursor.getColumnIndexOrThrow("private_key_alias")))
                assertEquals(41L, cursor.getLong(cursor.getColumnIndexOrThrow("sign_count")))
                do {
                    assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("backup_eligible")))
                    assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("backup_state")))
                } while (cursor.moveToNext())
            }
        } finally { helper.close() }
    }

    @Test fun roomKeepsExplicitFalseSeparateFromLegacyNull() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PasswordDatabase::class.java).build()
        try {
            val original = PasskeyEntry(credentialId = "registered-id", rpId = "example.test", rpName = "RP",
                userId = "user", userName = "Account", userDisplayName = "Account", publicKey = "public",
                privateKeyAlias = "protected-reference", signCount = 41)
            db.passkeyDao().insert(original.copy(id = 42))
            db.passkeyDao().insert(original.copy(id = 43, credentialId = "other-id", backupEligible = false, backupState = false))
            val old = db.passkeyDao().getPasskeyByRecordId(42)!!
            val explicit = db.passkeyDao().getPasskeyByRecordId(43)!!
            assertNull(old.backupEligible)
            assertEquals(29, PasskeyBackupFlags.authenticatorFlags(old.backupEligible, old.backupState))
            assertEquals(false, explicit.backupEligible)
            assertEquals(false, explicit.backupState)
            assertEquals(5, PasskeyBackupFlags.authenticatorFlags(explicit.backupEligible, explicit.backupState))
            assertEquals(original.privateKeyAlias, old.privateKeyAlias)
            assertEquals(original.signCount, old.signCount)
        } finally { db.close() }
    }
}
