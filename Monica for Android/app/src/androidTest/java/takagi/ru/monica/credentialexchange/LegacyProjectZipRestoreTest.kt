package takagi.ru.monica.credentialexchange

import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.ProjectCredentialGroup
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.*
import takagi.ru.monica.transfer.DatabaseArchiveExporter
import takagi.ru.monica.utils.*
import takagi.ru.monica.viewmodel.PasswordViewModel
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Runs only in this task's isolated package: replacement must never touch a user's local vault. */
class LegacyProjectZipRestoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val master = "Synthetic-Legacy-Accounts-331!"
    private val archivePassword = "Synthetic-Legacy-Archive-331!"
    private val marker get() = File(context.filesDir, "legacy-account-ids.json")
    private suspend fun prepare(): SecurityManager {
        check(context.packageName.startsWith("takagi.ru.monica.zipaccounts"))
        assertTrue(SecureStorageStartup.prepareForEntry(context) is SecureStartupResult.Ready)
        val security = SecurityManager(context)
        if (!security.isMasterPasswordSet()) assertTrue(security.setMasterPassword(master))
        else assertTrue(security.verifyMasterPassword(master))
        SessionManager.markUnlocked()
        return security
    }
    private fun archive(name: String, invalid: Boolean = false): File {
        val input = InstrumentationRegistry.getInstrumentation().context.assets.open("legacy-three-accounts.json")
            .bufferedReader().use { JSONArray(it.readText()) }
        return File(context.cacheDir, name).also { file -> ZipOutputStream(file.outputStream()).use { zip ->
            for (index in 0 until input.length()) {
                val row = input.getJSONObject(index)
                zip.putNextEntry(ZipEntry("passwords/password_${row.getLong("id")}.json"))
                zip.write(row.toString().toByteArray()); zip.closeEntry()
            }
            if (invalid) { zip.putNextEntry(ZipEntry("passwords/broken.json")); zip.write("{broken".toByteArray()); zip.closeEntry() }
        } }
    }
    @Test fun overwriteLegacyZipTwiceThenSaveAndExportKeepsThreeAccounts() = runBlocking {
        val security = prepare(); val db = PasswordDatabase.getDatabase(context)
        val previous = db.passwordEntryDao().getAllPasswordEntriesSync()
        if (previous.isNotEmpty()) {
            // A failed assertion may leave only this synthetic project for a diagnosed retry.
            verifyRows(db, security)
            assertTrue(previous.all { it.title == "Synthetic three-account project" })
        }
        val passwords = PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao(), passwordHistoryDao = db.passwordHistoryDao())
        val secureItems = SecureItemRepository(db.secureItemDao())
        val model = PasswordViewModel(passwords, security, customFieldRepository = CustomFieldRepository(db.customFieldDao()),
            strings = AppLocaleStringResolver(context))
        val good = archive("legacy-three.zip"); val bad = archive("legacy-broken.zip", true)
        try {
            db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Task replacement sentinel", website = "", username = "sentinel", password = security.encryptData("sentinel")))
            repeat(2) {
                val parsed = WebDavHelper(context).restoreFromBackupFile(good, overwrite = true, restoreMonicaConfig = false).getOrThrow()
                assertEquals(3, parsed.content.passwords.size)
                val applied = BackupRestoreApplier.applyRestoreResult(context, parsed, passwords, secureItems, true, "LegacyAccountFixture")
                assertEquals(3, applied.passwordImported)
                verifyRows(db, security)
            }
            val original = db.passwordEntryDao().getAllPasswordEntriesSync().sortedBy { it.id }
            val badRestore = runCatching {
                val parsed = WebDavHelper(context).restoreFromBackupFile(bad, overwrite = true, restoreMonicaConfig = false).getOrThrow()
                BackupRestoreApplier.applyRestoreResult(context, parsed, passwords, secureItems, true, "LegacyAccountFixture")
            }
            assertTrue(badRestore.isFailure)
            assertEquals(original, db.passwordEntryDao().getAllPasswordEntriesSync().sortedBy { it.id })
            val groups = ProjectCredentialGroup.restoreForEditing(original.map { it.copy(password = security.decryptData(it.password)) }, emptyMap())
            val done = CompletableDeferred<Long?>()
            model.savePasswordsAcrossTargets(original.map { it.id }, original.first().copy(password = ""), emptyList(),
                listOf(StorageTarget.MonicaLocal(null)), projectCredentials = groups, onComplete = { done.complete(it) })
            assertNotNull(withTimeout(30000) { done.await() })
            assertEquals(original.map { it.id }, db.passwordEntryDao().getAllPasswordEntriesSync().sortedBy { it.id }.map { it.id })
            verifyRows(db, security)
            val exported = DatabaseArchiveExporter(context).prepare(ImportDestination.Local, BackupPreferences(includeImages = false), archivePassword).first
            val returned = File(context.filesDir, "legacy-account-return.enc.zip")
            try { exported.copyTo(returned, overwrite = true) } finally { exported.delete() }
            val decoded = WebDavHelper(context).restoreFromBackupFile(returned, archivePassword, restoreMonicaConfig = false).getOrThrow()
            assertEquals(original.map { it.username to security.decryptData(it.password) }.toSet(),
                decoded.content.passwords.map { it.username to security.decryptDataIfMonicaCiphertext(it.password) }.toSet())
            assertEquals(3, decoded.content.customFieldsMap.values.mapNotNull { fields ->
                ProjectCredentialGroup.read(fields.map { CustomFieldDraft(title = it.title, value = it.value, isProtected = it.isProtected) })?.groupId
            }.distinct().size)
            marker.writeText(JSONArray(original.map { it.id }).toString())
        } finally { model.viewModelScope.cancel(); good.delete(); bad.delete() }
    }

    @Test fun newProcessReadsTheSavedAccountsAndRetainsIds() = runBlocking {
        val security = prepare(); val db = PasswordDatabase.getDatabase(context)
        assertTrue("Run the restore/save method before this cold-process method", marker.isFile)
        val ids = JSONArray(marker.readText()).let { list -> (0 until list.length()).map { list.getLong(it) } }
        val rows = db.passwordEntryDao().getAllPasswordEntriesSync().sortedBy { it.id }
        assertEquals(ids, rows.map { it.id }); verifyRows(db, security)
        val fields = rows.associate { it.id to db.customFieldDao().getFieldsByEntryIdSync(it.id).map(CustomFieldDraft::fromCustomField) }
        assertEquals(3, ProjectCredentialGroup.restore(rows.map { it.copy(password = security.decryptData(it.password)) }, fields).size)
    }

    private suspend fun verifyRows(db: PasswordDatabase, security: SecurityManager) {
        val rows = db.passwordEntryDao().getAllPasswordEntriesSync()
        assertEquals(3, rows.size)
        assertEquals(mapOf("alice-example" to "synthetic-password-0", "bob-example" to "synthetic-password-1", "carol-example" to "synthetic-password-2"),
            rows.associate { it.username to security.decryptData(it.password) })
        assertEquals(1, rows.map { it.passwordProjectKey() }.distinct().size)
    }
}
