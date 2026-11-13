package takagi.ru.monica.security

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*

@RunWith(AndroidJUnit4::class)
class SensitiveMigrationConcurrentEditTest {
    @Test fun migrationDoesNotOverwriteAnOtpEditedAfterTheBatchWasRead() = runBlocking {
        RecoveryTestFixture().use { fixture ->
            val context = fixture.context
            val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
            val security = fixture.manager
            assertTrue(security.isVaultRuntimeUnlocked())
            assertTrue(SessionManager.isUnlocked.value)
            try {
                val oldKey = "JBSWY3DPEHPK3PXP"
                val newKey = "GEZDGNBVGY3TQOJQ"
                val itemId = db.secureItemDao().insertItem(SecureItem(itemType = ItemType.TOTP, title = "edit-race", itemData = oldKey))
                val untouchedId = db.secureItemDao().insertItem(SecureItem(itemType = ItemType.TOTP, title = "migrate", itemData = oldKey))
                val passwordId = db.passwordEntryDao().insert(PasswordEntry(title = "edit-race", username = "", website = "", password = "",
                    authenticatorKey = oldKey))
                val secureDao = object : SecureItemDao by db.secureItemDao() {
                    override suspend fun getItemDataMigrationBatch(itemType: ItemType, afterId: Long, limit: Int): List<SecureItem> {
                        val batch = db.secureItemDao().getItemDataMigrationBatch(itemType, afterId, limit)
                        if (batch.any { it.id == itemId }) db.secureItemDao().updateItemData(itemId, newKey)
                        return batch
                    }
                }
                val passwordDao = object : PasswordEntryDao by db.passwordEntryDao() {
                    override suspend fun getAuthenticatorKeyMigrationBatch(afterId: Long, limit: Int): List<PasswordEntry> {
                        val batch = db.passwordEntryDao().getAuthenticatorKeyMigrationBatch(afterId, limit)
                        if (batch.any { it.id == passwordId }) db.passwordEntryDao().updateAuthenticatorKey(passwordId, newKey)
                        return batch
                    }
                }
                SensitiveFieldMigrationManager(context, db, security, passwordDao, secureDao).runUnlockedSmallBatch()
                assertEquals("Keep the user's newer standalone OTP", newKey, db.secureItemDao().getItemById(itemId)?.itemData)
                assertEquals("Keep the user's newer bound OTP", newKey, db.passwordEntryDao().getPasswordEntryById(passwordId)?.authenticatorKey)
                val migrated = requireNotNull(db.secureItemDao().getItemById(untouchedId)).itemData
                assertTrue(security.looksLikeMonicaCiphertext(migrated))
                assertEquals(oldKey, security.decryptDataIfMonicaCiphertext(migrated))
            } finally {
                db.close()
            }
        }
    }
}
