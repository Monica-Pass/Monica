package takagi.ru.monica.credentialexchange

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.*

class BitwardenCredentialCreateAtomicityTest {
    private suspend fun scenario(block: suspend TransferFixture.() -> Unit) {
        val field = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
        val previous = field.get(null)
        val synthetic = ByteArray(32) { (it + 19).toByte() }
        field.set(null, synthetic)
        try {
            val fixture = TransferFixture()
            try { fixture.block() } finally { fixture.close() }
        } finally { field.set(null, previous); synthetic.fill(0) }
    }

    private fun TransferFixture.editor(dao: PasswordEntryDao = db.passwordEntryDao()) = PasswordViewModel(
        PasswordRepository(dao, mdbxRepository = mdbx), security,
        customFieldRepository = CustomFieldRepository(db.customFieldDao()), context = context,
        localKeePassDatabaseDao = db.localKeePassDatabaseDao(), strings = AppLocaleStringResolver(context))

    private suspend fun TransferFixture.save(model: PasswordViewModel, vaultId: Long, password: String = ""):
        List<SavedPasswordCredential> {
        val done = CompletableDeferred<List<SavedPasswordCredential>>()
        model.saveCredentialsAcrossTargets(
            commonEntry = PasswordEntry(title = "$prefix-empty-credential", username = "", password = "", website = ""),
            credentials = listOf(PasswordCredentialDraft(username = "", password = password)),
            targets = listOf(StorageTarget.Bitwarden(vaultId, null)),
            customFields = listOf(CustomFieldDraft(title = "Synthetic field", value = "kept", isProtected = true)),
            onComplete = { done.complete(it) })
        return withTimeout(30_000) { done.await() }
    }

    @Test fun emptyUsernameAndPasswordReportSuccessAndStoreOneCompleteRecord() = runBlocking {
        scenario {
            val target = bitwarden()
            val model = editor()
            try {
                val saved = save(model, target.databaseId!!)
                assertEquals(1, saved.size)
                val entries = importedPasswords(target)
                assertEquals(listOf(saved.single().firstPasswordId), entries.map { it.id })
                assertEquals("", security.decryptData(entries.single().password))
                assertEquals("kept", db.customFieldDao().getFieldsByEntryIdSync(entries.single().id).single().value)
            } finally { model.viewModelScope.cancel() }
        }
    }

    @Test fun lockAfterInsertAndRepeatedSaveCannotLeaveOrphanRecords() = runBlocking {
        scenario {
            val target = bitwarden()
            val actual = db.passwordEntryDao()
            var inserts = 0
            val dao = object : PasswordEntryDao by actual {
                override suspend fun insertPasswordEntry(entry: PasswordEntry): Long = actual.insertPasswordEntry(entry).also {
                    inserts++
                    SessionManager.markLocked()
                }
            }
            val model = editor(dao)
            try {
                repeat(3) {
                    SessionManager.markUnlocked()
                    // Locking invalidates the vault session too. Reauthenticate before
                    // every retry so each attempt reaches the real post-insert failure.
                    val vaults = takagi.ru.monica.bitwarden.repository.BitwardenRepository.getInstance(context)
                    vaults.unlock(target.databaseId!!, "Synthetic transfer fixture password")
                    assertTrue("Each retry starts with an authenticated vault", vaults.isVaultUnlocked(target.databaseId!!))
                    assertTrue(save(model, target.databaseId!!).isEmpty())
                    assertEquals("Each failed attempt rolls back its inserted row", 0, importedPasswords(target).size)
                }
                assertEquals("Exercise failure after the actual Room insert on each attempt", 3, inserts)
                assertEquals("Failed saves must not leave new passwords", 0, importedPasswords(target).size)
            } finally { model.viewModelScope.cancel(); SessionManager.markUnlocked() }
        }
    }

    @Test fun otherReadersCannotSeeNewRecordBeforeItsFieldsAreSaved() = runBlocking {
        scenario {
            val target = bitwarden()
            val actual = db.passwordEntryDao()
            val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val observedFields = CompletableDeferred<List<CustomField>>()
            val dao = object : PasswordEntryDao by actual {
                override suspend fun insertPasswordEntry(entry: PasswordEntry): Long = actual.insertPasswordEntry(entry).also { id ->
                    observerScope.launch {
                        // A separate coroutine/Room transaction models the sync reader.
                        actual.getAllPasswordEntriesSync().firstOrNull { it.id == id }?.let {
                            observedFields.complete(db.customFieldDao().getFieldsByEntryIdSync(id))
                        }
                    }
                    // Unfixed code exposes the inserted row immediately. A transaction keeps
                    // this independent reader waiting until both row and fields commit.
                    withTimeoutOrNull(500) { observedFields.await() }
                }
            }
            val model = editor(dao)
            try {
                assertEquals(1, save(model, target.databaseId!!, "synthetic-password").size)
                val observed = withTimeout(10_000) { observedFields.await() }
                assertEquals(listOf("kept"), observed.map { it.value })
            } finally { model.viewModelScope.cancel(); observerScope.cancel() }
        }
    }
}
