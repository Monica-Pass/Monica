package takagi.ru.monica.credentialexchange

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Date
import java.util.UUID
import takagi.ru.monica.bitwarden.service.BitwardenPasswordHistoryStore
import takagi.ru.monica.data.*
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.*
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel

/** Real editor save pipeline and authenticated Bitwarden repository, synthetic accounts only. */
class LocalBitwardenMoveIssue189Test {
    private suspend fun scenario(block: suspend TransferFixture.() -> Unit) {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(SecureStorageStartup.prepareForEntry(context) is SecureStartupResult.Ready)
        val manager = SecurityManager(context)
        assertTrue(if (manager.isMasterPasswordSet()) manager.unlockVaultWithPassword(MASTER) else manager.setMasterPassword(MASTER))
        val f = TransferFixture()
        try { f.block() } finally { f.close() }
    }

    private fun TransferFixture.editor(dao: PasswordEntryDao = db.passwordEntryDao()) = PasswordViewModel(
        PasswordRepository(dao, categoryDao = db.categoryDao(), bitwardenFolderDao = db.bitwardenFolderDao()),
        security, customFieldRepository = CustomFieldRepository(db.customFieldDao()), context = context,
        strings = AppLocaleStringResolver(context))

    private suspend fun TransferFixture.save(model: PasswordViewModel, source: PasswordEntry,
        target: StorageTarget, value: String = SECRET, fields: List<CustomFieldDraft> = emptyList()): Long? {
        val done = CompletableDeferred<Long?>()
        withContext(Dispatchers.Main) {
            model.savePasswordsAcrossTargets(listOf(source.id), source, listOf(value), listOf(target),
                customFields = fields, onComplete = { done.complete(it) })
        }
        return withTimeout(15_000) { done.await() }
    }

    @Test fun localLoginMovesToBitwardenRootAndKeepsOriginalId() = runBlocking {
        scenario {
            val target = bitwarden()
            val source = PasswordEntry(title = "$prefix-issue189-root", website = "https://example.invalid/189",
                username = "synthetic-user", password = security.encryptData(SECRET), notes = "Synthetic original note")
            val id = db.passwordEntryDao().insertPasswordEntry(source)
            val stored = requireNotNull(db.passwordEntryDao().getPasswordEntryById(id))
            val model = editor()
            try {
                assertEquals("Issue189: unchanged existing local login must move", id,
                    save(model, stored, StorageTarget.Bitwarden(target.databaseId!!, null)))
                val moved = requireNotNull(db.passwordEntryDao().getPasswordEntryById(id))
                assertEquals(target.databaseId, moved.bitwardenVaultId)
                assertNull(moved.categoryId)
                assertEquals(SECRET, security.decryptData(moved.password))
                assertEquals(stored.notes, moved.notes)
                assertEquals(1, importedPasswords(target).size)
            } finally { model.viewModelScope.cancel() }
        }
    }

    @Test fun localHistoryAndFieldsSurviveMoveToFolderWhileEditingPassword() = runBlocking {
        scenario {
            val target = bitwarden()
            val folder = UUID.randomUUID().toString()
            db.bitwardenFolderDao().insert(BitwardenFolder(vaultId = target.databaseId!!,
                bitwardenFolderId = folder, name = "Synthetic issue189 folder", revisionDate = "2026-11-25T00:00:00Z"))
            val source = PasswordEntry(title = "$prefix-issue189-history", website = "https://example.invalid/189",
                username = "synthetic-user", password = security.encryptData(SECRET), notes = "Original note")
            val id = db.passwordEntryDao().insertPasswordEntry(source)
            val oldTime = Date(1_700_000_000_123L)
            val historyId = db.passwordHistoryDao().insert(PasswordHistoryEntry(entryId = id,
                password = security.encryptDataLegacyCompat("Synthetic older password"), lastUsedAt = oldTime))
            val stored = requireNotNull(db.passwordEntryDao().getPasswordEntryById(id))
            val model = editor()
            try {
                assertEquals(id, save(model, stored, StorageTarget.Bitwarden(target.databaseId!!, folder),
                    value = "Synthetic new password", fields = listOf(CustomFieldDraft(title = "Synthetic field", value = "kept", isProtected = true))))
                val moved = requireNotNull(db.passwordEntryDao().getPasswordEntryById(id))
                assertEquals(folder, moved.bitwardenFolderId)
                assertEquals("Synthetic new password", security.decryptData(moved.password))
                val histories = db.passwordHistoryDao().getHistoryByEntryIdSync(id)
                assertTrue(histories.any { it.id == historyId && it.lastUsedAt == oldTime &&
                    security.decryptData(it.password) == "Synthetic older password" })
                assertTrue(histories.any { security.decryptData(it.password) == SECRET })
                assertEquals("kept", db.customFieldDao().getFieldsByEntryIdSync(id).single().value)
            } finally { model.viewModelScope.cancel() }
        }
    }

    @Test fun ordinaryHistoryEditStillRejectsCrossVaultOwnershipChange() = runBlocking {
        scenario {
            val a = bitwarden(); val b = bitwarden()
            val source = PasswordEntry(title = "$prefix-issue189-boundary", website = "", username = "",
                password = security.encryptData(SECRET), bitwardenVaultId = a.databaseId, bitwardenLocalModified = true)
            val id = db.passwordEntryDao().insertPasswordEntry(source)
            val stored = requireNotNull(db.passwordEntryDao().getPasswordEntryById(id))
            val store = BitwardenPasswordHistoryStore(context, db, security)
            val failure = runCatching {
                store.persistPasswordTransition(stored, stored.copy(bitwardenVaultId = b.databaseId), SECRET)
            }.exceptionOrNull()
            assertEquals("Password ownership changed", failure?.message)
            assertEquals(stored, db.passwordEntryDao().getPasswordEntryById(id))
            assertTrue(db.passwordHistoryDao().getHistoryByEntryIdSync(id).isEmpty())
        }
    }

    @Test fun fieldFailureRollsBackOwnershipPasswordHistoryAndFields() = runBlocking {
        scenario {
            val target = bitwarden()
            val id = db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "$prefix-field-failure",
                website = "", username = "", password = security.encryptData(SECRET)))
            val source = requireNotNull(db.passwordEntryDao().getPasswordEntryById(id))
            db.customFieldDao().insert(CustomField(entryId = id, title = "Original", value = "retained"))
            db.passwordHistoryDao().insert(PasswordHistoryEntry(entryId = id,
                password = security.encryptDataLegacyCompat("Synthetic old"), lastUsedAt = Date(123)))
            val oldHistory = db.passwordHistoryDao().getHistoryByEntryIdSync(id)
            val oldFields = db.customFieldDao().getFieldsByEntryIdSync(id)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER issue189_fail_field BEFORE INSERT ON custom_fields WHEN NEW.entry_id = $id BEGIN SELECT RAISE(ABORT, 'Synthetic issue189 field failure'); END")
            val model = editor()
            try {
                assertNull(save(model, source, StorageTarget.Bitwarden(target.databaseId!!, null), "Synthetic changed",
                    listOf(CustomFieldDraft(title = "New", value = "fail"))))
                assertEquals(source, db.passwordEntryDao().getPasswordEntryById(id))
                assertEquals(oldHistory, db.passwordHistoryDao().getHistoryByEntryIdSync(id))
                assertEquals(oldFields, db.customFieldDao().getFieldsByEntryIdSync(id))
                assertTrue(importedPasswords(target).isEmpty())
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER issue189_fail_field")
                model.viewModelScope.cancel()
            }
        }
    }

    @Test fun lockAfterTransferRollsBackBeforeFieldsCommit() = runBlocking {
        scenario {
            val target = bitwarden()
            val actual = db.passwordEntryDao()
            val id = actual.insertPasswordEntry(PasswordEntry(title = "$prefix-lock", website = "", username = "",
                password = security.encryptData(SECRET)))
            val source = requireNotNull(actual.getPasswordEntryById(id))
            var injected = false
            val dao = object : PasswordEntryDao by actual {
                override suspend fun getPasswordEntryById(id: Long): PasswordEntry? = actual.getPasswordEntryById(id).also {
                    if (it?.bitwardenVaultId == target.databaseId && !injected) {
                        injected = true
                        SessionManager.markLocked()
                    }
                }
            }
            val model = editor(dao)
            try {
                assertNull(save(model, source, StorageTarget.Bitwarden(target.databaseId!!, null)))
                assertTrue("Lock must occur after the ownership update", injected)
                assertEquals(source, actual.getPasswordEntryById(id))
                assertTrue(db.passwordHistoryDao().getHistoryByEntryIdSync(id).isEmpty())
            } finally { model.viewModelScope.cancel(); SessionManager.markUnlocked() }
        }
    }

    @Test fun transferRejectsStaleSourceAndWrongCapturedVault() = runBlocking {
        scenario {
            val a = bitwarden(); val b = bitwarden()
            val id = db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "$prefix-stale",
                website = "", username = "", password = security.encryptData(SECRET)))
            val source = requireNotNull(db.passwordEntryDao().getPasswordEntryById(id))
            val changed = source.copy(notes = "Concurrent synthetic edit")
            db.passwordEntryDao().update(changed)
            val store = BitwardenPasswordHistoryStore(context, db, security)
            val session = store.captureSession(a.databaseId!!)
            val destination = source.copy(bitwardenVaultId = a.databaseId, bitwardenLocalModified = true)
            assertEquals("Password changed before transfer", runCatching {
                store.persistLocalTransfer(source, destination, SECRET, session)
            }.exceptionOrNull()?.message)
            assertEquals("Transfer destination changed", runCatching {
                store.persistLocalTransfer(changed, destination.copy(bitwardenVaultId = b.databaseId), SECRET, session)
            }.exceptionOrNull()?.message)
            assertEquals(changed, db.passwordEntryDao().getPasswordEntryById(id))
            assertTrue(db.passwordHistoryDao().getHistoryByEntryIdSync(id).isEmpty())
        }
    }

    @Test fun cancellationAfterLocalTransferRetainsOriginalRowAndHistory() = runBlocking {
        scenario {
            val target = bitwarden()
            val id = db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "$prefix-cancel", website = "",
                username = "", password = security.encryptData(SECRET)))
            val source = requireNotNull(db.passwordEntryDao().getPasswordEntryById(id))
            db.passwordHistoryDao().insert(PasswordHistoryEntry(entryId = id,
                password = security.encryptDataLegacyCompat("Synthetic older"), lastUsedAt = Date(456)))
            val before = db.passwordHistoryDao().getHistoryByEntryIdSync(id)
            val store = BitwardenPasswordHistoryStore(context, db, security)
            val result = runCatching {
                store.commitLocalCreate(target.databaseId!!) {
                    assertTrue(store.persistLocalTransfer(source, source.copy(bitwardenVaultId = target.databaseId,
                        bitwardenLocalModified = true), SECRET, store.captureSession(target.databaseId!!)))
                    throw CancellationException("Synthetic cancellation")
                }
            }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(source, db.passwordEntryDao().getPasswordEntryById(id))
            assertEquals(before, db.passwordHistoryDao().getHistoryByEntryIdSync(id))
        }
    }

    @Test fun movedLoginUploadsExactlyOnceWithPasswordHistoryAndProtectedField() = runBlocking {
        scenario {
            val target = bitwarden()
            val id = db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "$prefix-upload", website = "",
                username = "synthetic", password = security.encryptData(SECRET), notes = "Retained note"))
            val source = requireNotNull(db.passwordEntryDao().getPasswordEntryById(id))
            val model = editor()
            try {
                assertEquals(id, save(model, source, StorageTarget.Bitwarden(target.databaseId!!, null), "Synthetic uploaded",
                    listOf(CustomFieldDraft(title = "Private", value = "retained", isProtected = true))))
                val vault = requireNotNull(db.bitwardenVaultDao().getVaultById(target.databaseId!!))
                val sync = takagi.ru.monica.bitwarden.service.BitwardenSyncService(context)
                val first = sync.uploadLocalEntries(vault, accessToken, vaultKey)
                assertTrue(first.toString(), first is takagi.ru.monica.bitwarden.service.UploadResult.Success && first.uploaded == 1 && first.failed == 0)
                assertEquals(1, remote.created.size)
                val wire = remote.created.single()
                assertEquals("Synthetic uploaded", takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.decryptToString(
                    wire.getJSONObject("login").getString("password"), vaultKey))
                val histories = takagi.ru.monica.bitwarden.service.BitwardenPasswordHistory.decode(
                    kotlinx.serialization.json.Json.parseToJsonElement(wire.getJSONArray("passwordHistory").toString()), vaultKey)
                assertTrue(histories.any { it.password == SECRET })
                assertTrue((0 until wire.getJSONArray("fields").length()).any { i ->
                    val field = wire.getJSONArray("fields").getJSONObject(i)
                    takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.decryptToString(field.getString("name"), vaultKey) == "Private" &&
                        takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.decryptToString(field.getString("value"), vaultKey) == "retained" && field.getInt("type") == 1
                })
                assertEquals(takagi.ru.monica.bitwarden.service.UploadResult.Success(0, 0), sync.uploadLocalEntries(vault, accessToken, vaultKey))
                assertEquals(1, remote.created.size)
                assertEquals(id, importedPasswords(target).single().id)
            } finally { model.viewModelScope.cancel() }
        }
    }

    companion object {
        const val MASTER = "Synthetic-Issue189-Master-20261125!"
        const val SECRET = "Synthetic-Issue189-Original!"
    }
}
