package takagi.ru.monica.data.dedup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry

@RunWith(AndroidJUnit4::class)
class SingleVaultDedupMigrationInstrumentedTest {
    @Test fun version84MigrationPreservesOriginalsAndRecoveryIsIndependentOfParentCascade() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "dedup-migration-${UUID.randomUUID()}.db"
        var db: PasswordDatabase? = null
        try {
            db = Room.databaseBuilder(context, PasswordDatabase::class.java, name).build()
            val original = PasswordEntry(id = 91, title = "Synthetic migration", website = "https://example.invalid",
                username = "synthetic", password = "synthetic-ciphertext", notes = "must survive")
            db.passwordEntryDao().insertPasswordEntry(original)
            // The preceding schema differs only by the new table. Remove its Room identity so the
            // real migration path must validate every generated entity, not a hand-written subset.
            db.openHelper.writableDatabase.execSQL("DROP TABLE single_vault_dedup_recovery")
            db.openHelper.writableDatabase.execSQL("DELETE FROM room_master_table")
            db.openHelper.writableDatabase.version = 84
            db.close()
            db = Room.databaseBuilder(context, PasswordDatabase::class.java, name)
                .addMigrations(PasswordDatabase.MIGRATION_84_85).build()
            assertEquals(original, db.passwordEntryDao().getPasswordEntryById(91))
            val receipt = SingleVaultDedupRecoveryEntity("synthetic-receipt", "local", 1, 1, "sealed-fixture", "COMMITTED")
            db.singleVaultDedupRecoveryDao().insert(receipt)
            db.openHelper.writableDatabase.execSQL("DELETE FROM password_entries WHERE id = 91")
            assertEquals(receipt, db.singleVaultDedupRecoveryDao().get(receipt.id))
            assertEquals(85, db.openHelper.writableDatabase.version)
        } finally { db?.close(); context.deleteDatabase(name) }
    }
}
