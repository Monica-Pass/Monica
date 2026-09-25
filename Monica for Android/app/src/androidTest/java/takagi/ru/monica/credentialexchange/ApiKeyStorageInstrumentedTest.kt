package takagi.ru.monica.credentialexchange

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keemobile.kotpass.models.EntryValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.api.CipherApiResponse
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.service.BitwardenSyncService
import takagi.ru.monica.bitwarden.service.CipherSyncProcessor
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.*
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.Mdbx2NativeReadSessions
import takagi.ru.monica.repository.Mdbx2Repository
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.ApiKeyEditorViewModel

@RunWith(AndroidJUnit4::class)
class ApiKeyStorageInstrumentedTest {
    private val key = "sk-synthetic-api-key-storage-12345"

    private suspend fun scenario(block: suspend TransferFixture.(PasswordViewModel) -> Unit) {
        val fixture = TransferFixture()
        val editor = PasswordViewModel(fixture.passwords, fixture.security,
            customFieldRepository = CustomFieldRepository(fixture.db.customFieldDao()), context = fixture.context,
            localKeePassDatabaseDao = fixture.db.localKeePassDatabaseDao(), strings = AppLocaleStringResolver(fixture.context))
        try { fixture.block(editor) } finally { editor.viewModelScope.cancel(); fixture.close() }
    }

    private suspend fun save(
        viewModel: PasswordViewModel, draft: ApiKeyDraft, target: StorageTarget,
        original: PasswordEntry? = null, extra: List<CustomFieldDraft> = emptyList(),
    ): Long {
        val done = CompletableDeferred<Long?>()
        viewModel.savePasswordsAcrossTargets(listOfNotNull(original?.id), draft.toEntry(original),
            listOf(draft.key), listOf(target), draft.customFields(extra), onComplete = { done.complete(it) })
        return requireNotNull(withTimeout(30_000) { done.await() }) { "API key save failed" }
    }

    @Test fun keepassWriteEditClearAndReopenPreserveApiKeyAndUnknownFields() = runBlocking {
        scenario { viewModel ->
            val target = keepass()
            val storage = StorageTarget.KeePass(target.databaseId, null)
            val draft = ApiKeyDraft("$prefix-API", "https://console.example.org", key,
                "https://gateway.example.org/v1", "AI development\n备注")
            val extra = listOf(CustomFieldDraft(title = "Third party field", value = "preserve me", isProtected = true))
            val entryId = save(viewModel, draft, storage, extra = extra)
            KeePassKdbxService.invalidateProcessCache(target.databaseId)
            val service = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
            val first = service.loadWorkspace(target.databaseId).getOrThrow().passwords.single()
            assertEquals(ApiKeyEntryFields.TYPE, first.loginType)
            assertEquals(key, first.password)
            assertEquals(draft.website, first.url)
            assertEquals(draft.apiUrl, first.customFields.single { it.title == ApiKeyEntryFields.API_URL }.value)
            val row = requireNotNull(viewModel.getPasswordEntryById(entryId))
            save(viewModel, draft.copy(apiUrl = "", website = "", notes = "updated"), storage, row, extra)
            KeePassKdbxService.invalidateProcessCache(target.databaseId)
            val reopened = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
                .loadWorkspace(target.databaseId).getOrThrow().passwords.single()
            assertEquals(ApiKeyEntryFields.TYPE, reopened.loginType)
            assertEquals(key, reopened.password)
            assertEquals("", reopened.url)
            assertEquals("updated", reopened.notes)
            assertFalse(reopened.customFields.any { it.title == ApiKeyEntryFields.API_URL })
            assertTrue(reopened.customFields.any { it.title == "Third party field" && it.value == "preserve me" && it.isProtected })
            assertTrue(keepassEntries(target.databaseId).single().fields.getValue("Password") is EntryValue.Encrypted)
            assertFalse(keepassFile(target.databaseId).readText(Charsets.ISO_8859_1).contains(key))
        }
    }

    @Test fun mdbxSaveCommitsMetadataBeforeReportingSuccessAndReopenPreservesEdits() = runBlocking {
        scenario { viewModel ->
            val target = mdbx()
            val storage = StorageTarget.Mdbx(target.databaseId, null)
            val draft = ApiKeyDraft("$prefix-API", "https://console.example.org", key,
                "http://192.168.1.2:11434/v1", "local model")
            val entryId = save(viewModel, draft, storage)
            fun fields(payload: JSONObject): Map<String, String> {
                val values = payload.getJSONArray("custom_fields")
                return (0 until values.length()).associate {
                    values.getJSONObject(it).let { f -> f.getString("title") to f.getString("value") }
                }
            }
            suspend fun reopen(): JSONObject {
                Mdbx2NativeReadSessions.clear()
                val rows = Mdbx2Repository(context, db.localMdbxDatabaseDao(), security)
                    .readStoredEntries(target.databaseId).filterNot { it.deleted }
                return JSONObject(rows.single { it.entryType == "login" }.payloadJson)
            }
            val first = reopen()
            assertEquals(ApiKeyEntryFields.TYPE, first.getString("login_type"))
            assertEquals(key, first.getString("password_plain"))
            assertEquals(draft.apiUrl, fields(first)[ApiKeyEntryFields.API_URL])
            assertTrue(ApiKeyEntryFields.isApiKey(fields(first)))
            val original = requireNotNull(viewModel.getPasswordEntryById(entryId))
            save(viewModel, draft.copy(apiUrl = "", notes = "edited"), storage, original)
            val changed = reopen()
            assertTrue(ApiKeyEntryFields.isApiKey(fields(changed)))
            assertFalse(fields(changed).containsKey(ApiKeyEntryFields.API_URL))
            assertEquals(key, changed.getString("password_plain"))
            assertEquals("edited", changed.getString("notes"))
        }
    }

    @Test fun bitwardenEncryptedUploadAndFreshDownloadPreserveApiTypeAndClearableUrls() = runBlocking {
        scenario { viewModel ->
            val target = bitwarden()
            val storage = StorageTarget.Bitwarden(target.databaseId, null)
            val draft = ApiKeyDraft("$prefix-API", "https://console.example.org", key,
                "https://api.example.org/v1", "API development")
            val entryId = save(viewModel, draft, storage)
            val vault = requireNotNull(db.bitwardenVaultDao().getVaultById(target.databaseId))
            BitwardenSyncService(context).uploadLocalEntries(vault, accessToken, vaultKey)
            val uploaded = remote.created.single()
            assertFalse(uploaded.toString().contains(key))
            assertFalse(uploaded.toString().contains(draft.apiUrl))
            val cipher = Json { ignoreUnknownKeys = true }.decodeFromString<CipherApiResponse>(uploaded.toString())
            db.passwordEntryDao().deletePasswordEntryById(entryId)
            val processor = CipherSyncProcessor(context)
            processor.syncCipherFromServer(vault, cipher, vaultKey)
            val downloaded = importedPasswords(target).single()
            assertTrue(downloaded.isApiKeyEntry())
            assertEquals(key, security.decryptData(downloaded.password))
            assertEquals(draft.website, downloaded.website)
            assertEquals(draft.notes, downloaded.notes)
            assertEquals(draft.apiUrl, db.customFieldDao().getFieldsByEntryIdSync(downloaded.id)
                .single { it.title == ApiKeyEntryFields.API_URL }.value)
            // An earlier version may already have cached this cipher as a regular password.
            db.passwordEntryDao().update(downloaded.copy(loginType = "PASSWORD"))
            // A second device clears the optional URL fields. Use the item's own encryption key.
            val itemKeyBytes = cipher.key?.let { BitwardenCrypto.decrypt(it, vaultKey) }
            val itemKey = itemKeyBytes?.let { BitwardenCrypto.SymmetricCryptoKey(it.copyOfRange(0, 32), it.copyOfRange(32, 64)) }
                ?: vaultKey
            try {
                val cleared = cipher.copy(revisionDate = "2026-09-26T12:00:00.000Z",
                    login = cipher.login!!.copy(uris = emptyList()),
                    fields = cipher.fields?.filterNot {
                        BitwardenCrypto.decryptToString(it.name!!, itemKey) == ApiKeyEntryFields.API_URL
                    })
                processor.syncCipherFromServer(vault, cleared, vaultKey)
                val updated = importedPasswords(target).single()
                assertTrue(updated.isApiKeyEntry())
                assertEquals("", updated.website)
                assertFalse(db.customFieldDao().getFieldsByEntryIdSync(updated.id).any { it.title == ApiKeyEntryFields.API_URL })
                assertEquals(key, security.decryptData(updated.password))
            } finally {
                itemKeyBytes?.fill(0)
                if (itemKey !== vaultKey) itemKey.clear()
            }
        }
    }

    @Test fun failedSecondTargetKeepsDraftAndRetryDoesNotDuplicateSuccessfulCopy() = runBlocking {
        scenario { viewModel ->
            val editor = ApiKeyEditorViewModel()
            var savedId: Long? = null
            try {
                withContext(Dispatchers.Main) {
                    editor.initialize(viewModel, null, StorageTarget.MonicaLocal(null))
                    editor.draft = ApiKeyDraft("$prefix-retry", key = key)
                    editor.targets = listOf(StorageTarget.MonicaLocal(null), StorageTarget.Mdbx(Long.MAX_VALUE, null))
                    editor.save(viewModel) { savedId = it }
                }
                withTimeout(30_000) { while (editor.saving) delay(10) }
                assertNull(savedId)
                assertEquals(ApiKeyEditorViewModel.Failure.SAVE, editor.failure)
                assertEquals(key, editor.draft.key)
                val successfulCopy = importedPasswords(ImportDestination.Local).single()
                withContext(Dispatchers.Main) {
                    editor.targets = listOf(StorageTarget.MonicaLocal(null))
                    editor.save(viewModel) { savedId = it }
                }
                withTimeout(30_000) { while (editor.saving) delay(10) }
                assertEquals(successfulCopy.id, savedId)
                assertEquals(1, importedPasswords(ImportDestination.Local).size)
            } finally { editor.viewModelScope.cancel() }
        }
    }
}
