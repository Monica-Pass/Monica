package takagi.ru.monica.credentialexchange

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keemobile.kotpass.models.EntryValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.BackupPreferences
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.Mdbx2NativeReadSessions
import takagi.ru.monica.repository.Mdbx2Repository
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.BackupContent
import takagi.ru.monica.utils.WebDavHelper
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.MdbxViewModel

/** Native files must retain every existing password field even when the login is empty. */
@RunWith(AndroidJUnit4::class)
class NativePasswordContentStorageInstrumentedTest {
    private val cardNumber = "4242424242424242"
    private val note = "部署说明\n\n订阅：年度计划 🔐\n  保留缩进和末尾空白  \n"
    private val extra = listOf(CustomFieldDraft(title = "Recovery hint", value = "synthetic hint", isProtected = true))

    private suspend fun scenario(block: suspend TransferFixture.(PasswordViewModel) -> Unit) {
        val fixture = TransferFixture()
        val passwords = PasswordViewModel(fixture.passwords, fixture.security,
            customFieldRepository = CustomFieldRepository(fixture.db.customFieldDao()), context = fixture.context,
            localKeePassDatabaseDao = fixture.db.localKeePassDatabaseDao(), strings = AppLocaleStringResolver(fixture.context))
        try { fixture.block(passwords) } finally { passwords.viewModelScope.cancel(); fixture.close() }
    }

    private fun entry(title: String) = PasswordEntry(title = title, username = "", password = "", website = "",
        notes = note, email = "alice@example.invalid", phone = "+1 202 555 0140", addressLine = "12 Example Street",
        city = "Example City", state = "EX", zipCode = "10000", country = "US",
        creditCardNumber = cardNumber, creditCardHolder = "ALICE EXAMPLE", creditCardExpiry = "09/30", creditCardCVV = "123")

    private suspend fun save(passwords: PasswordViewModel, entry: PasswordEntry, target: StorageTarget): Long {
        val done = CompletableDeferred<Long?>()
        passwords.savePasswordsAcrossTargets(listOf(entry.id).filter { it > 0 }, entry, listOf(entry.password),
            listOf(target), extra, onComplete = { done.complete(it) })
        return requireNotNull(withTimeout(30_000) { done.await() }) { "Content save did not complete" }
    }

    @Test fun keepassCanSaveContentWithoutLoginAndReopenAllNativeFields() = runBlocking {
        scenario { passwords ->
            val target = keepass()
            val storage = StorageTarget.KeePass(target.databaseId, null)
            val id = save(passwords, entry("$prefix-content"), storage)
            KeePassKdbxService.invalidateProcessCache(target.databaseId)
            val service = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
            val first = service.loadWorkspace(target.databaseId).getOrThrow().passwords.single()
            assertEquals("", first.password)
            assertEquals("", first.username)
            assertEquals(note, first.notes)
            assertEquals(cardNumber, first.creditCardNumber)
            assertEquals("123", first.creditCardCVV)
            assertEquals("ALICE EXAMPLE", first.creditCardHolder)
            assertEquals("09/30", first.creditCardExpiry)
            assertEquals("alice@example.invalid", first.email)
            assertEquals("12 Example Street", first.addressLine)
            assertTrue(first.customFields.any { it.title == "Recovery hint" && it.isProtected })
            val native = keepassEntries(target.databaseId).single()
            assertTrue(native.fields.getValue("Card Number") is EntryValue.Encrypted)
            assertTrue(native.fields.getValue("Card CVV") is EntryValue.Encrypted)
            assertFalse(native.fields.containsKey("MonicaItemType"))
            assertFalse(keepassFile(target.databaseId).readText(Charsets.ISO_8859_1).contains(cardNumber))

            val original = requireNotNull(passwords.getPasswordEntryById(id))
            save(passwords, original.copy(title = "$prefix-renamed", notes = note + "更新\n"), storage)
            KeePassKdbxService.invalidateProcessCache(target.databaseId)
            val reopened = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
                .loadWorkspace(target.databaseId).getOrThrow().passwords.single()
            assertEquals(first.entryUuid, reopened.entryUuid)
            assertEquals(note + "更新\n", reopened.notes)
            assertEquals(cardNumber, reopened.creditCardNumber)
            assertEquals("synthetic hint", reopened.customFields.single { it.title == "Recovery hint" }.value)
        }
    }

    @Test fun mdbxCanSaveContentWithoutLoginAndReopenNativeRecordAfterEditing() = runBlocking {
        scenario { passwords ->
            val target = mdbx()
            val storage = StorageTarget.Mdbx(target.databaseId, null)
            val id = save(passwords, entry("$prefix-content"), storage)
            suspend fun reopen(): JSONObject {
                Mdbx2NativeReadSessions.clear()
                val rows = Mdbx2Repository(context, db.localMdbxDatabaseDao(), security)
                    .readStoredEntries(target.databaseId).filterNot { it.deleted }
                assertEquals(1, rows.size)
                assertEquals("login", rows.single().entryType)
                return JSONObject(rows.single().payloadJson)
            }
            val first = reopen()
            assertEquals("", first.getString("password_plain"))
            assertEquals(note, first.getString("notes"))
            assertEquals(cardNumber, first.getString("credit_card_number_plain"))
            assertEquals("123", first.getString("credit_card_cvv_plain"))
            assertEquals("alice@example.invalid", first.getString("email"))
            assertEquals("12 Example Street", first.getString("address_line"))
            assertEquals("synthetic hint", first.getJSONArray("custom_fields").getJSONObject(0).getString("value"))
            val original = requireNotNull(passwords.getPasswordEntryById(id))
            save(passwords, original.copy(notes = note + "更新\n"), storage)
            val updated = reopen()
            assertEquals(note + "更新\n", updated.getString("notes"))
            assertEquals(cardNumber, updated.getString("credit_card_number_plain"))
            assertEquals("123", updated.getString("credit_card_cvv_plain"))
            assertEquals(1, updated.getJSONArray("custom_fields").length())

            val edited = requireNotNull(passwords.getPasswordEntryById(id))
            save(passwords, edited.copy(creditCardNumber = "", creditCardCVV = "", addressLine = ""), storage)
            val cleared = reopen()
            assertEquals("", cleared.getString("credit_card_number_plain"))
            assertEquals("", cleared.getString("credit_card_cvv_plain"))
            assertEquals("", cleared.getString("address_line"))
            assertEquals("alice@example.invalid", cleared.getString("email"))
        }
    }

    @Test fun nativeExportAndRepeatedImportPreserveContentWithoutRoomAndNeverCollapseDistinctCards() = runBlocking {
        scenario { passwords ->
            for (source in listOf(keepass(), mdbx())) {
                val storage = if (source.kind == ImportDestinationKind.KEEPASS)
                    StorageTarget.KeePass(source.databaseId, null) else StorageTarget.Mdbx(source.databaseId, null)
                val id = save(passwords, entry("$prefix-content-${source.kind}"), storage)
                // Removing only this synthetic cache row forces the exporter/duplicate lookup
                // to read the native file, as on another device.
                db.passwordEntryDao().deletePasswordEntryById(id)
                val archive = model.prepareZipBackup(backupEncryptionPassword = "synthetic archive password", source = source,
                    preferences = BackupPreferences(includeImages = false)).getOrThrow().first
                try {
                    val content = WebDavHelper(context).restoreFromBackupFile(archive, "synthetic archive password",
                        restoreMonicaConfig = false, importDataOnly = true).getOrThrow().content
                    val restored = content.passwords.single()
                    assertEquals(note, restored.notes)
                    assertEquals(cardNumber, restored.creditCardNumber)
                    assertEquals("123", restored.creditCardCVV)
                    assertEquals("alice@example.invalid", restored.email)
                    assertEquals("12 Example Street", restored.addressLine)
                    assertEquals(0, importer.apply(content, source).imported)
                    val indexed = importedPasswords(source).single()
                    assertEquals(cardNumber, indexed.creditCardNumber)
                    assertEquals("12 Example Street", indexed.addressLine)
                    assertEquals(0, importer.apply(content, source).imported)

                    val variants = listOf(restored.copy(id = 90001, creditCardNumber = "5555555555554444"),
                        restored.copy(id = 90002, notes = note.trim()))
                    val fields = content.customFieldsMap[restored.id].orEmpty()
                    val differentContent = BackupContent(variants, emptyList(),
                        customFieldsMap = variants.associate { it.id to fields })
                    assertEquals("Different card/notes content must remain distinct", 2, importer.apply(differentContent, source).imported)
                    assertEquals(0, importer.apply(differentContent, source).imported)
                    assertEquals(3, importedPasswords(source).size)
                } finally { archive.delete() }
            }
        }
    }

    @Test fun mdbxSyncRebuildsADeletedProjectionWithReadableContactAndPaymentContent() = runBlocking {
        scenario { passwords ->
            val target = mdbx()
            val id = save(passwords, entry("$prefix-sync"), StorageTarget.Mdbx(target.databaseId, null))
            db.passwordEntryDao().deletePasswordEntryById(id)
            val manager = MdbxViewModel(application = context.applicationContext as android.app.Application,
                databaseDao = db.localMdbxDatabaseDao(), remoteSourceDao = db.mdbxRemoteSourceDao(),
                passwordEntryDao = db.passwordEntryDao(), secureItemDao = db.secureItemDao(),
                passkeyDao = db.passkeyDao(), attachmentDao = db.attachmentDao(), customFieldDao = db.customFieldDao(),
                securityManager = security)
            try {
                manager.syncVault(target.databaseId)
                val result = withTimeout(30_000) { manager.operationState.first {
                    it is MdbxViewModel.OperationState.Success || it is MdbxViewModel.OperationState.Error
                } }
                assertTrue(result.toString(), result is MdbxViewModel.OperationState.Success)
                val reopened = importedPasswords(target).single()
                assertEquals("", security.decryptData(reopened.password))
                assertEquals(note, reopened.notes)
                assertEquals(cardNumber, reopened.creditCardNumber)
                assertEquals("123", reopened.creditCardCVV)
                assertEquals("12 Example Street", reopened.addressLine)
                assertEquals("alice@example.invalid", reopened.email)
                assertEquals("synthetic hint", db.customFieldDao().getFieldsByEntryIdSync(reopened.id).single().value)
            } finally { manager.viewModelScope.cancel() }
        }
    }
}
