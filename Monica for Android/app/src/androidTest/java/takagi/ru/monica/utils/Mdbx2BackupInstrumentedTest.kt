package takagi.ru.monica.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Date
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.BackupPreferences
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem

@RunWith(AndroidJUnit4::class)
class Mdbx2BackupInstrumentedTest {
    @Test
    fun localBackupIncludesMdbx2ContentAsPortableLocalFallback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = WebDavHelper(context)
        val security = takagi.ru.monica.security.SecurityManager(context)
        val result = helper.createBackupZip(
            passwords = listOf(
                password(id = 91L, title = "MDBX2 password", mdbxDatabaseId = 7L).copy(
                    notes = "one\n  two  \n", addressLine = "12 Example Street", city = "City", state = "State",
                    zipCode = "10000", country = "US", creditCardNumber = security.encryptData("4242424242424242"),
                    creditCardHolder = "ALICE", creditCardExpiry = "09/30", creditCardCVV = security.encryptData("123")),
                password(
                    id = 92L,
                    title = "Bitwarden password",
                    bitwardenVaultId = 8L,
                    bitwardenCipherId = "cipher"
                )
            ),
            secureItems = listOf(
                note(id = 93L, title = "MDBX2 note", mdbxDatabaseId = 7L),
                note(
                    id = 94L,
                    title = "Bitwarden note",
                    bitwardenVaultId = 8L,
                    bitwardenCipherId = "cipher"
                )
            ),
            preferences = BackupPreferences(
                includePasswords = true,
                includeAuthenticators = false,
                includeDocuments = false,
                includeBankCards = false,
                includePasskeys = false,
                includeGeneratorHistory = false,
                includeImages = false,
                includeNotes = true,
                includeTimeline = false,
                includeTrash = false,
                includeTrashAndHistory = false,
                includeWebDavConfig = false,
                includeLocalKeePass = false
            ),
            contentScope = BackupContentScope.MONICA_LOCAL_ONLY,
            allowBackupEncryption = false
        ).getOrThrow()

        val backupFile = result.first
        try {
            assertTrue(result.second.warnings.contains(
                AppLocaleStringResolver(context).get(takagi.ru.monica.R.string.backup_mdbx_local_restore, 2)
            ))
            ZipFile(backupFile).use { zip ->
                val jsonEntries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".json") }
                    .map { entry -> zip.getInputStream(entry).bufferedReader().use { it.readText() } }
                    .toList()
                val payload = jsonEntries.joinToString("\n")

                assertTrue(payload.contains("MDBX2 password"))
                assertTrue(payload.contains("MDBX2 note"))
                assertFalse(payload.contains("Bitwarden password"))
                assertFalse(payload.contains("Bitwarden note"))
                assertFalse(payload.contains("mdbxDatabaseId"))
                assertFalse(payload.contains("mdbxFolderId"))
                assertEquals(1, result.second.successItems.passwords)
                assertEquals(1, result.second.successItems.notes)
            }
            val restored = helper.restoreFromBackupFile(backupFile, restoreMonicaConfig = false,
                importDataOnly = true).getOrThrow().content.passwords.single()
            assertEquals("one\n  two  \n", restored.notes)
            assertEquals("12 Example Street", restored.addressLine)
            assertEquals("City", restored.city)
            assertEquals("State", restored.state)
            assertEquals("10000", restored.zipCode)
            assertEquals("US", restored.country)
            assertEquals("4242424242424242", restored.creditCardNumber)
            assertEquals("ALICE", restored.creditCardHolder)
            assertEquals("09/30", restored.creditCardExpiry)
            assertEquals("123", restored.creditCardCVV)
        } finally {
            backupFile.delete()
        }
    }

    private fun password(
        id: Long,
        title: String,
        mdbxDatabaseId: Long? = null,
        bitwardenVaultId: Long? = null,
        bitwardenCipherId: String? = null
    ) = PasswordEntry(
        id = id,
        title = title,
        website = "https://backup.test",
        username = "backup-user",
        password = "backup-test-only",
        createdAt = Date(1_700_000_000_000L + id),
        updatedAt = Date(1_700_000_000_000L + id),
        mdbxDatabaseId = mdbxDatabaseId,
        mdbxFolderId = mdbxDatabaseId?.let { "folder-$it" },
        bitwardenVaultId = bitwardenVaultId,
        bitwardenCipherId = bitwardenCipherId
    )

    private fun note(
        id: Long,
        title: String,
        mdbxDatabaseId: Long? = null,
        bitwardenVaultId: Long? = null,
        bitwardenCipherId: String? = null
    ) = SecureItem(
        id = id,
        itemType = ItemType.NOTE,
        title = title,
        itemData = "release stability note",
        createdAt = Date(1_700_000_000_000L + id),
        updatedAt = Date(1_700_000_000_000L + id),
        mdbxDatabaseId = mdbxDatabaseId,
        mdbxFolderId = mdbxDatabaseId?.let { "folder-$it" },
        bitwardenVaultId = bitwardenVaultId,
        bitwardenCipherId = bitwardenCipherId,
        syncStatus = if (bitwardenCipherId == null) "NONE" else "SYNCED"
    )
}
