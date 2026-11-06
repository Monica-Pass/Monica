package takagi.ru.monica.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyRecoveryCompatibilityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val original = PasswordEntry(id = 91, title = "Synthetic retained record",
        website = "https://example.invalid", username = "synthetic", password = "synthetic-ciphertext",
        notes = "Unknown content must survive")

    @Test fun version84MigrationPreservesRecordsAndValidatesWholeSchema() = runBlocking {
        val name = "legacy-upgrade-${UUID.randomUUID()}.db"
        var db: PasswordDatabase? = null
        try {
            db = Room.databaseBuilder(context, PasswordDatabase::class.java, name).build()
            db.passwordEntryDao().insertPasswordEntry(original)
            // Schema 84 differs only by the recovery table. Force the real Room migration
            // and complete schema validation rather than testing migration SQL in isolation.
            db.openHelper.writableDatabase.execSQL("DROP TABLE single_vault_dedup_recovery")
            db.openHelper.writableDatabase.execSQL("DELETE FROM room_master_table")
            db.openHelper.writableDatabase.version = 84
            db.close()
            db = Room.databaseBuilder(context, PasswordDatabase::class.java, name)
                .addMigrations(PasswordDatabase.MIGRATION_84_85).build()
            assertEquals(original, db.passwordEntryDao().getPasswordEntryById(91))
            assertEquals(85, db.openHelper.writableDatabase.version)
            assertEquals(0, recoveryRows(db))
            val added = original.copy(id = 92, title = "Normal save after upgrade")
            db.passwordEntryDao().insertPasswordEntry(added)
            db.close()
            db = Room.databaseBuilder(context, PasswordDatabase::class.java, name).build()
            assertEquals(original, db.passwordEntryDao().getPasswordEntryById(91))
            assertEquals(added, db.passwordEntryDao().getPasswordEntryById(92))
        } finally { db?.close(); context.deleteDatabase(name) }
    }

    @Test fun existingVersion85KeepsOldIdentityRecoveryBytesAndNormalWrites() = runBlocking {
        val name = "legacy-reopen-${UUID.randomUUID()}.db"
        val payload = "sealed-synthetic-\u0000-payload-旧数据"
        val sidecar = File(context.filesDir, "retained-recovery-${UUID.randomUUID()}.bin")
        val bytes = byteArrayOf(0, 1, -1, 42, 0, 12)
        var db: PasswordDatabase? = null
        try {
            sidecar.writeBytes(bytes)
            db = Room.databaseBuilder(context, PasswordDatabase::class.java, name).build()
            db.passwordEntryDao().insertPasswordEntry(original)
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO single_vault_dedup_recovery VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf("fixture", "local", 1, 1, payload, "COMMITTED"))
            // Captured from the pre-removal generated schema 85, with the old entity class.
            db.openHelper.writableDatabase.execSQL(
                "UPDATE room_master_table SET identity_hash = 'f1cc89b0bd71d086b7a81d230b3fe1b7' WHERE id = 42")
            db.close()
            db = Room.databaseBuilder(context, PasswordDatabase::class.java, name).build()
            assertEquals(original, db.passwordEntryDao().getPasswordEntryById(91))
            val updated = original.copy(notes = "Normal edited record")
            db.passwordEntryDao().insertPasswordEntry(updated)
            assertEquals(updated, db.passwordEntryDao().getPasswordEntryById(91))
            db.openHelper.readableDatabase.query("SELECT sealedPayload, state FROM single_vault_dedup_recovery").use {
                assertTrue(it.moveToFirst()); assertEquals(payload, it.getString(0)); assertEquals("COMMITTED", it.getString(1))
                assertFalse(it.moveToNext())
            }
            assertArrayEquals(bytes, sidecar.readBytes())
            assertEquals(85, db.openHelper.writableDatabase.version)
            db.close()
            db = Room.databaseBuilder(context, PasswordDatabase::class.java, name).build()
            assertEquals(updated, db.passwordEntryDao().getPasswordEntryById(91))
            assertEquals(1, recoveryRows(db))
        } finally { db?.close(); context.deleteDatabase(name); sidecar.delete() }
    }

    private fun recoveryRows(db: PasswordDatabase): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM single_vault_dedup_recovery").use {
            assertTrue(it.moveToFirst()); it.getInt(0)
        }
}
