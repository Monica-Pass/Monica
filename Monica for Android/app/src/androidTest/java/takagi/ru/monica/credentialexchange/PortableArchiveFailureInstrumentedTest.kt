package takagi.ru.monica.credentialexchange

import android.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.BackupPreferences
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordHistoryEntry
import takagi.ru.monica.transfer.DatabaseArchiveExporter
import takagi.ru.monica.transfer.databaseExportErrorMessage
import takagi.ru.monica.utils.PortableSecretExportException
import java.util.zip.ZipFile

class PortableArchiveFailureInstrumentedTest {
    private val preferences = BackupPreferences(includePasskeys = false, includeWebDavConfig = false)

    @Test fun unreadableHistoryReportsItsOwningItemAndKeepsHistory() = runBlocking {
        val f = TransferFixture()
        try {
            val title = f.prefix + "-history"
            val id = f.passwords.insertPasswordEntry(PasswordEntry(title = title, website = "", username = "",
                password = f.security.encryptData("Synthetic current password")))
            val cipher = "C2|" + Base64.encodeToString(ByteArray(28), Base64.NO_WRAP)
            val historyId = f.db.passwordHistoryDao().insert(PasswordHistoryEntry(entryId = id, password = cipher))
            val before = f.db.passwordHistoryDao().getById(historyId)
            val error = runCatching {
                DatabaseArchiveExporter(f.context).prepare(ImportDestination.Local, preferences, null)
            }.exceptionOrNull()
            assertTrue(error is PortableSecretExportException)
            assertEquals(title, (error as PortableSecretExportException).entryTitle)
            assertEquals(before, f.db.passwordHistoryDao().getById(historyId))
        } finally { f.close() }
    }

    @Test fun failedZipIdentifiesItsItemAndKeepsOriginalCiphertext() = runBlocking {
        val f = TransferFixture()
        try {
            f.security.encryptData("Synthetic warm-up")
            val title = f.prefix + "-unreadable"
            val cipher = "C2|" + Base64.encodeToString(ByteArray(28), Base64.NO_WRAP)
            val id = f.passwords.insertPasswordEntry(PasswordEntry(title = title, website = "", username = "", password = cipher))
            val before = f.db.passwordEntryDao().getAllPasswordEntriesSync().single { it.id == id }
            val error = runCatching {
                DatabaseArchiveExporter(f.context).prepare(ImportDestination.Local, preferences, null)
            }.exceptionOrNull()
            assertTrue(error is PortableSecretExportException)
            error as PortableSecretExportException
            assertEquals(title, error.entryTitle)
            assertFalse("Diagnostic exception text must not contain a private title", error.message.orEmpty().contains(title))
            assertTrue(databaseExportErrorMessage(f.context, error).contains(title))
            assertEquals(before, f.db.passwordEntryDao().getAllPasswordEntriesSync().single { it.id == id })
        } finally { f.close() }
    }

    @Test fun validPaddedCiphertextExportsPlaintextWithoutChangingStoredRecord() = runBlocking {
        val f = TransferFixture()
        try {
            val plaintext = "  Synthetic export secret\n  "
            val padded = " \n" + f.security.encryptData(plaintext) + "\t "
            val id = f.passwords.insertPasswordEntry(PasswordEntry(title = f.prefix + "-padded", website = "", username = "", password = padded))
            val before = f.db.passwordEntryDao().getAllPasswordEntriesSync().single { it.id == id }
            val output = DatabaseArchiveExporter(f.context).prepare(ImportDestination.Local, preferences, null).first
            try {
                ZipFile(output).use { zip ->
                    val entry = checkNotNull(zip.getEntry("passwords/password_$id.json"))
                    val json = zip.getInputStream(entry).bufferedReader().use { JSONObject(it.readText()) }
                    assertEquals(plaintext, json.getString("password"))
                }
            } finally { output.delete() }
            assertEquals(before, f.db.passwordEntryDao().getAllPasswordEntriesSync().single { it.id == id })
        } finally { f.close() }
    }
}
